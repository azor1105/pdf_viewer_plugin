package com.example.pdf_viewer_plugin

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresExtension
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.File

class PdfViewerPlugin : FlutterPlugin, ActivityAware {

    private var activity: Activity? = null
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        pluginBinding?.let {
            it.platformViewRegistry.registerViewFactory(
                "pdf_viewer_plugin_view",
                PdfViewFactory(activity!!, it.flutterAssets, it.binaryMessenger)
            )
        }
    }

    override fun onDetachedFromActivityForConfigChanges() { activity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }
    override fun onDetachedFromActivity() { activity = null }
}

class PdfViewFactory(
    private val activity: Activity,
    private val flutterAssets: FlutterPlugin.FlutterAssets,
    private val messenger: io.flutter.plugin.common.BinaryMessenger
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *>
        return PdfPlatformView(context, activity, flutterAssets, messenger, viewId, params)
    }
}

class PdfPlatformView(
    private val context: Context,
    private val activity: Activity,
    private val flutterAssets: FlutterPlugin.FlutterAssets,
    private val messenger: io.flutter.plugin.common.BinaryMessenger,
    private val viewId: Int,
    private val params: Map<*, *>?
) : PlatformView {

    private val container = FrameLayout(context)
    private var pdfRendererAdapter: FallbackPdfRendererAdapter? = null
    private var eventSink: EventChannel.EventSink? = null
    private var eventChannel: EventChannel? = null
    private var currentPage: Int = 0
    private var totalPages: Int = 0
    private val isHorizontal: Boolean = (params?.get("scrollDirection") as? String) == "horizontal"

    init {
        eventChannel = EventChannel(messenger, "pdf_viewer_plugin_view_$viewId/page_events")
        eventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })

        val assetPath = params?.get("assetPath") as? String
        val filePath = params?.get("filePath") as? String

        if (assetPath != null) {
            val flutterAssetKey = flutterAssets.getAssetFilePathBySubpath(assetPath)
            val cacheFile = copyAssetToCache(context, flutterAssetKey)
            loadPdf(cacheFile)
        } else if (filePath != null) {
            loadPdf(File(filePath))
        }
    }

    private fun sendPageEvent(page: Int, total: Int) {
        currentPage = page
        eventSink?.success(mapOf("currentPage" to page, "totalPages" to total))
    }

    private fun loadPdf(file: File) {
        if (isPdfViewerFragmentSupported()) {
            loadWithPdfViewerFragment(file)
        } else {
            loadWithFallbackRenderer(file)
        }
    }

    private fun isPdfViewerFragmentSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        } else {
            false
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun loadWithPdfViewerFragment(file: File) {
        val appCompatActivity = activity as? AppCompatActivity ?: run {
            loadWithFallbackRenderer(file)
            return
        }

        val fragmentContainer = FrameLayout(context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(fragmentContainer)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.pdf_viewer_plugin.provider",
            file
        )

        val pdfFragment = androidx.pdf.viewer.fragment.PdfViewerFragment()
        appCompatActivity.supportFragmentManager
            .beginTransaction()
            .replace(fragmentContainer.id, pdfFragment, "pdf_viewer_$fragmentContainer.id")
            .commitAllowingStateLoss()
        appCompatActivity.supportFragmentManager.executePendingTransactions()
        pdfFragment.documentUri = uri
    }

    private fun loadWithFallbackRenderer(file: File) {
        // Count total pages
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val tempRenderer = PdfRenderer(fd)
        totalPages = tempRenderer.pageCount
        tempRenderer.close()
        fd.close()

        val recyclerView = RecyclerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val orientation = if (isHorizontal) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
            layoutManager = LinearLayoutManager(context, orientation, false)
        }

        // Wrap recyclerView in a ZoomableLayout for pinch-to-zoom
        val zoomLayout = ZoomableLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(recyclerView)
        }
        container.addView(zoomLayout)

        val adapter = FallbackPdfRendererAdapter(file, isHorizontal)
        pdfRendererAdapter = adapter
        recyclerView.adapter = adapter

        // If horizontal mode, snap pages like a ViewPager
        if (isHorizontal) {
            val snapHelper = PagerSnapHelper()
            snapHelper.attachToRecyclerView(recyclerView)
        }

        // Page indicator label
        val pageLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            val hPad = dpToPx(context, 12f).toInt()
            val vPad = dpToPx(context, 6f).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            textSize = 14f
            visibility = View.GONE
        }
        val labelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(context, 8f).toInt()
        }
        container.addView(pageLabel, labelParams)

        // Track current visible page from scroll
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = lm.findFirstCompletelyVisibleItemPosition().let {
                    if (it == RecyclerView.NO_POSITION) lm.findFirstVisibleItemPosition() else it
                }
                if (firstVisible != RecyclerView.NO_POSITION && firstVisible != currentPage) {
                    currentPage = firstVisible
                    sendPageEvent(currentPage, totalPages)
                }
            }
        })

        // Add scrubber (SeekBar) for fast page navigation
        addPageScrubber(recyclerView, pageLabel)

        // Send initial page event
        sendPageEvent(0, totalPages)
    }

    private fun addPageScrubber(recyclerView: RecyclerView, pageLabel: TextView) {
        if (totalPages <= 1) return

        val seekBar: SeekBar
        val seekParams: FrameLayout.LayoutParams

        if (isHorizontal) {
            // Horizontal PDF → horizontal scrubber at bottom
            seekBar = SeekBar(context).apply {
                max = totalPages - 1
                progress = 0
            }
            seekParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(context, 16f).toInt()
                marginStart = dpToPx(context, 24f).toInt()
                marginEnd = dpToPx(context, 24f).toInt()
            }
        } else {
            // Vertical PDF → vertical scrubber on the right side
            seekBar = VerticalSeekBar(context).apply {
                max = totalPages - 1
                progress = 0
            }
            seekParams = FrameLayout.LayoutParams(
                dpToPx(context, 48f).toInt(),
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dpToPx(context, 4f).toInt()
                topMargin = dpToPx(context, 24f).toInt()
                bottomMargin = dpToPx(context, 24f).toInt()
            }
        }

        // Semi-transparent background
        seekBar.alpha = 0.7f

        var labelHideAnimator: ValueAnimator? = null

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress in 0 until totalPages) {
                    recyclerView.scrollToPosition(progress)
                    currentPage = progress
                    sendPageEvent(currentPage, totalPages)
                    pageLabel.text = "Page ${progress + 1} / $totalPages"
                    pageLabel.visibility = View.VISIBLE
                    labelHideAnimator?.cancel()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                seekBar.alpha = 1.0f
                pageLabel.visibility = View.VISIBLE
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekBar.alpha = 0.7f
                labelHideAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 1500
                    startDelay = 800
                    addUpdateListener {
                        pageLabel.alpha = it.animatedValue as Float
                        if (it.animatedValue as Float == 0f) {
                            pageLabel.visibility = View.GONE
                            pageLabel.alpha = 1f
                        }
                    }
                    start()
                }
            }
        })

        // Keep scrubber in sync when user scrolls manually
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = lm.findFirstCompletelyVisibleItemPosition().let {
                    if (it == RecyclerView.NO_POSITION) lm.findFirstVisibleItemPosition() else it
                }
                if (firstVisible != RecyclerView.NO_POSITION) {
                    seekBar.progress = firstVisible
                }
            }
        })

        container.addView(seekBar, seekParams)
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
        )
    }

    private fun copyAssetToCache(context: Context, assetKey: String): File {
        val cacheFile = File(context.cacheDir, "flutter_pdf_${assetKey.hashCode()}.pdf")
        if (!cacheFile.exists()) {
            context.assets.open(assetKey).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return cacheFile
    }

    override fun getView(): View = container

    override fun dispose() {
        pdfRendererAdapter?.close()
        pdfRendererAdapter = null
        eventChannel?.setStreamHandler(null)
        eventSink = null
    }
}

/**
 * A FrameLayout that supports pinch-to-zoom on its child.
 */
class ZoomableLayout(context: Context) : FrameLayout(context) {

    private var scaleFactor = 1.0f
    private val minScale = 1.0f
    private val maxScale = 5.0f
    private var pivotX = 0f
    private var pivotY = 0f

    // For panning
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pivotX = detector.focusX
                pivotY = detector.focusY
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
                if (newScale != scaleFactor) {
                    scaleFactor = newScale
                    applyTransform()
                }
                return true
            }
        })

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = pivotX
        child.pivotY = pivotY
        child.scaleX = scaleFactor
        child.scaleY = scaleFactor
        child.translationX = translateX
        child.translationY = translateY
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept when zoomed in so we can handle panning
        if (scaleFactor > minScale && ev.pointerCount == 1) {
            return true
        }
        scaleDetector.onTouchEvent(ev)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && scaleFactor > minScale && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    translateX += dx
                    translateY += dy
                    applyTransform()
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                // Snap back if zoomed below minimum
                if (scaleFactor <= minScale) {
                    scaleFactor = minScale
                    translateX = 0f
                    translateY = 0f
                    applyTransform()
                }
            }
        }

        // Double-tap to reset zoom
        return true
    }
}

/**
 * A SeekBar rotated to act as a vertical scrubber.
 * Top = page 0, bottom = last page.
 */
class VerticalSeekBar(context: Context) : SeekBar(context) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    @Synchronized
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onDraw(c: Canvas) {
        c.rotate(-90f)
        c.translate(-height.toFloat(), 0f)
        super.onDraw(c)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                val newProgress = (max * event.y / height).toInt().coerceIn(0, max)
                progress = newProgress
                onSizeChanged(width, height, 0, 0)
            }
        }
        return super.onTouchEvent(event)
    }
}

class FallbackPdfRendererAdapter(file: File, private val isHorizontal: Boolean) :
    RecyclerView.Adapter<FallbackPdfRendererAdapter.PageViewHolder>() {

    private val fileDescriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(fileDescriptor)

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val imageView = ImageView(parent.context).apply {
            if (isHorizontal) {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }
        return PageViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = renderer.openPage(position)
        val metrics = holder.imageView.resources.displayMetrics
        val scale = if (isHorizontal) {
            minOf(
                metrics.widthPixels.toFloat() / page.width,
                metrics.heightPixels.toFloat() / page.height
            )
        } else {
            metrics.widthPixels.toFloat() / page.width
        }
        val width = (page.width * scale).toInt()
        val height = (page.height * scale).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        if (isHorizontal) {
            holder.imageView.layoutParams = ViewGroup.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels
            )
        }

        holder.imageView.setImageBitmap(bitmap)
    }

    fun close() {
        renderer.close()
        fileDescriptor.close()
    }

    class PageViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
}
