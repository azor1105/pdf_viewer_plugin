package com.example.pdf_viewer_plugin

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresExtension
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.File
import java.util.concurrent.Executors

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
                PdfViewFactory(activity!!, it.flutterAssets)
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
    private val flutterAssets: FlutterPlugin.FlutterAssets
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *>
        return PdfPlatformView(context, activity, flutterAssets, params)
    }
}

class PdfPlatformView(
    private val context: Context,
    private val activity: Activity,
    private val flutterAssets: FlutterPlugin.FlutterAssets,
    private val params: Map<*, *>?
) : PlatformView {

    private val container = FrameLayout(context)
    private var pdfRendererAdapter: FallbackPdfRendererAdapter? = null

    init {
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
        val screenWidth = context.resources.displayMetrics.widthPixels
        val adapter = FallbackPdfRendererAdapter(file, screenWidth)
        pdfRendererAdapter = adapter

        val recyclerView = RecyclerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            setItemViewCacheSize(6)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
            this.adapter = adapter
        }

        val zoomLayout = ZoomableLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            onZoomChanged = { zoomLevel ->
                adapter.updateZoomLevel(zoomLevel)
            }
        }
        zoomLayout.addView(recyclerView)
        container.addView(zoomLayout)
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
    }
}

class ZoomableLayout(context: Context) : FrameLayout(context) {

    private val minScale = 1.0f
    private val maxScale = 5.0f
    private val doubleTapScale = 2.5f

    private var currentScale = 1.0f
    private var currentTransX = 0f
    private var currentTransY = 0f

    // Timestamp of the last scroll/pan event; used to suppress accidental double-taps
    // that the GestureDetector falsely detects when the user re-touches after panning.
    private var lastScrollTime = 0L

    var onZoomChanged: ((Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Clamp the raw factor to ignore spurious near-zero values emitted
                // by some devices when the second finger lifts.
                val safeFactor = detector.scaleFactor.coerceIn(0.7f, 1.43f)
                val newScale = (currentScale * safeFactor).coerceIn(minScale, maxScale)
                val realFactor = newScale / currentScale
                if (realFactor == 1f) return true
                currentTransX = detector.focusX - (detector.focusX - currentTransX) * realFactor
                currentTransY = detector.focusY - (detector.focusY - currentTransY) * realFactor
                currentScale = newScale
                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onZoomChanged?.invoke(currentScale)
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Ignore a double-tap that happens right after a pan gesture — the
                // user likely just re-touched after hitting the screen edge while panning.
                if (System.currentTimeMillis() - lastScrollTime < 350L) return false
                if (currentScale > 1.05f) {
                    animateToTransform(1.0f, 0f, 0f)
                } else {
                    val factor = doubleTapScale / currentScale
                    animateToTransform(
                        doubleTapScale,
                        e.x - (e.x - currentTransX) * factor,
                        e.y - (e.y - currentTransY) * factor
                    )
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (currentScale > 1.0f) {
                    lastScrollTime = System.currentTimeMillis()
                    currentTransX -= distanceX
                    currentTransY -= distanceY
                    applyTransform()
                    return true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // Consume fling when zoomed so it doesn't leak into the RecyclerView.
                return currentScale > 1.0f
            }
        }
    )

    // Route all touch events through dispatchTouchEvent so each event is seen by
    // the detectors exactly once.  onInterceptTouchEvent and onTouchEvent then
    // only read the resulting state — they never feed events to the detectors again.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return currentScale > 1.0f || scaleDetector.isInProgress
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return currentScale > 1.0f || scaleDetector.isInProgress
    }

    private fun animateToTransform(targetScale: Float, targetTransX: Float, targetTransY: Float) {
        val startScale = currentScale
        val startTransX = currentTransX
        val startTransY = currentTransY

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                currentScale = startScale + (targetScale - startScale) * f
                currentTransX = startTransX + (targetTransX - startTransX) * f
                currentTransY = startTransY + (targetTransY - startTransY) * f
                applyTransform()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onZoomChanged?.invoke(targetScale)
                }
            })
            start()
        }
    }

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = currentScale
        child.scaleY = currentScale
        child.translationX = currentTransX
        child.translationY = currentTransY
    }
}

class FallbackPdfRendererAdapter(file: File, private val screenWidth: Int) :
    RecyclerView.Adapter<FallbackPdfRendererAdapter.PageViewHolder>() {

    private val fileDescriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(fileDescriptor)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Track current render zoom so bitmaps are rendered at matching resolution
    private var renderZoom = 1.0f
    // Cache key encodes both page index and zoom level
    private fun cacheKey(position: Int, zoom: Float): Int =
        position * 1000 + (zoom * 100).toInt()

    // Always holds the most recently rendered bitmap for each page (any zoom level),
    // used as a low-res placeholder while the high-res version is rendering.
    private val lastRenderedBitmap = HashMap<Int, Bitmap>()

    // Debounce token: only the latest scheduled re-render runs
    private var pendingRenderRunnable: Runnable? = null
    private val debounceDelayMs = 400L

    private data class PageInfo(val width: Int, val height: Int)
    private val pageInfoList: List<PageInfo>

    private val bitmapCache: LruCache<Int, Bitmap>

    init {
        pageInfoList = (0 until renderer.pageCount).map { i ->
            val page = renderer.openPage(i)
            val info = PageInfo(page.width, page.height)
            page.close()
            info
        }

        val maxMemoryKB = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        bitmapCache = object : LruCache<Int, Bitmap>(maxMemoryKB / 4) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun updateZoomLevel(zoomLevel: Float) {
        val newRenderZoom = zoomLevel.coerceIn(1.0f, 3.0f)
        if ((newRenderZoom * 100).toInt() == (renderZoom * 100).toInt()) return

        // Cancel any previously scheduled re-render
        pendingRenderRunnable?.let { mainHandler.removeCallbacks(it) }

        // Schedule re-render; if another call arrives within the delay it resets the timer
        val runnable = Runnable {
            renderZoom = newRenderZoom
            notifyDataSetChanged()
        }
        pendingRenderRunnable = runnable
        mainHandler.postDelayed(runnable, debounceDelayMs)
    }

    private fun scaledHeight(position: Int): Int {
        val info = pageInfoList[position]
        return (info.height * (screenWidth.toFloat() / info.width)).toInt()
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return PageViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.imageView.layoutParams = holder.imageView.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = scaledHeight(position)
        }

        val key = cacheKey(position, renderZoom)
        val cached = bitmapCache.get(key)
        if (cached != null && !cached.isRecycled) {
            holder.imageView.setImageBitmap(cached)
            return
        }

        // Show the last known bitmap for this page as a placeholder so there's no white flash
        val placeholder = lastRenderedBitmap[position]
        if (placeholder != null && !placeholder.isRecycled) {
            holder.imageView.setImageBitmap(placeholder)
        } else {
            holder.imageView.setImageBitmap(null)
            holder.imageView.setBackgroundColor(Color.WHITE)
        }

        val tag = key
        holder.imageView.tag = tag
        val currentRenderZoom = renderZoom

        renderExecutor.execute {
            try {
                val bitmap = renderPage(position, currentRenderZoom) ?: return@execute
                val cacheKey = cacheKey(position, currentRenderZoom)
                bitmapCache.put(cacheKey, bitmap)
                // Always keep track of the freshest bitmap for placeholder use
                mainHandler.post {
                    lastRenderedBitmap[position] = bitmap
                    if (holder.imageView.tag == tag) {
                        holder.imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.imageView.setImageBitmap(null)
        holder.imageView.tag = null
    }

    @Synchronized
    private fun renderPage(position: Int, zoom: Float): Bitmap? {
        if (position < 0 || position >= renderer.pageCount) return null
        val page = renderer.openPage(position)
        val info = pageInfoList[position]
        val baseScale = screenWidth.toFloat() / info.width
        val renderScale = baseScale * zoom
        val width = (info.width * renderScale).toInt()
        val height = (info.height * renderScale).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    fun close() {
        pendingRenderRunnable?.let { mainHandler.removeCallbacks(it) }
        renderExecutor.shutdownNow()
        bitmapCache.evictAll()
        lastRenderedBitmap.clear()
        renderer.close()
        fileDescriptor.close()
    }

    class PageViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
}
