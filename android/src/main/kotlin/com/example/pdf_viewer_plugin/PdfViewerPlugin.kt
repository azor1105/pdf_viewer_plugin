package com.example.pdf_viewer_plugin

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresExtension
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import kotlin.math.roundToInt
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
    private val binaryMessenger: io.flutter.plugin.common.BinaryMessenger
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *>
        return PdfPlatformView(context, activity, flutterAssets, params, viewId, binaryMessenger)
    }
}

class PdfPlatformView(
    private val context: Context,
    private val activity: Activity,
    private val flutterAssets: FlutterPlugin.FlutterAssets,
    private val params: Map<*, *>?,
    viewId: Int,
    binaryMessenger: io.flutter.plugin.common.BinaryMessenger
) : PlatformView {

    private val container = FrameLayout(context)
    private var pdfRendererAdapter: FallbackPdfRendererAdapter? = null
    private val channel = MethodChannel(binaryMessenger, "pdf_viewer_plugin_view/$viewId")

    init {
        val assetPath = params?.get("assetPath") as? String
        val filePath = params?.get("filePath") as? String
        val isHorizontal = (params?.get("scrollDirection") as? String) == "horizontal"
        val initialPage = (params?.get("initialPage") as? Int) ?: 0

        if (assetPath != null) {
            val flutterAssetKey = flutterAssets.getAssetFilePathBySubpath(assetPath)
            val cacheFile = copyAssetToCache(context, flutterAssetKey)
            loadPdf(cacheFile, isHorizontal, initialPage)
        } else if (filePath != null) {
            loadPdf(File(filePath), isHorizontal, initialPage)
        }
    }

    private fun loadPdf(file: File, isHorizontal: Boolean, initialPage: Int) {
        // Always use the fallback renderer — it supports initialPage, onPageChanged,
        // and horizontal scrolling. The Jetpack PdfViewerFragment doesn't expose
        // page-scroll or page-change APIs, so we bypass it entirely for now.
        loadWithFallbackRenderer(file, isHorizontal, initialPage)
    }

    private fun isPdfViewerFragmentSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        } else {
            false
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun loadWithPdfViewerFragment(file: File, isHorizontal: Boolean) {
        val appCompatActivity = activity as? AppCompatActivity ?: run {
            loadWithFallbackRenderer(file, isHorizontal)
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

    private fun loadWithFallbackRenderer(file: File, isHorizontal: Boolean, initialPage: Int = 0) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val adapter = FallbackPdfRendererAdapter(context, file, screenWidth, isHorizontal)
        pdfRendererAdapter = adapter

        val recyclerView = RecyclerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(
                context,
                if (isHorizontal) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL,
                false
            )
            setHasFixedSize(true)
            setItemViewCacheSize(6)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false
            this.adapter = adapter
        }

        if (isHorizontal) {
            androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(recyclerView)
        }

        val zoomLayout = ZoomableLayout(context, isHorizontal).apply {
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

        val dp = context.resources.displayMetrics.density
        val circleSize = (50 * dp).toInt()

        // Shared scroll listener: notifies Flutter, updates the scrubber position,
        // and shows the page-count pill while scrolling.
        var lastReportedPage = initialPage
        fun makeScrollListener(scrubber: PageScrubberView) = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val page = lm.findFirstVisibleItemPosition()
                scrubber.updatePage(page)
                scrubber.notifyScrolling()
                if (page != lastReportedPage) {
                    lastReportedPage = page
                    Handler(Looper.getMainLooper()).post {
                        channel.invokeMethod("onPageChanged", page)
                    }
                }
            }
        }

        // The container is NOT attached to a window at this point — Flutter embeds
        // it later. We wait for the attachment event, then double-post to ensure
        // both the measure/layout pass AND the first draw have completed before
        // scrolling. This is the only reliable approach for Flutter PlatformViews.
        if (initialPage > 0) {
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    container.removeOnAttachStateChangeListener(this)
                    // First post: after the current layout pass
                    recyclerView.post {
                        // Second post: after the first draw frame
                        recyclerView.post {
                            (recyclerView.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(initialPage, 0)
                        }
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }

        if (isHorizontal) {
            // Horizontal scrubber: 150dp wide × 100dp tall, bottom of screen.
            // Extra height gives the pill drawn above the circle enough room to breathe.
            val scrubberW = (150 * dp).toInt()
            val scrubberH = (100 * dp).toInt()
            val scrubber = PageScrubberView(context, recyclerView, adapter.pageCount, isHorizontal)
            val scrubberParams = FrameLayout.LayoutParams(scrubberW, scrubberH).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = (8 * dp).toInt()
                marginStart = (8 * dp).toInt()
            }
            container.addView(scrubber, scrubberParams)
            recyclerView.addOnScrollListener(makeScrollListener(scrubber))
        } else {
            // Vertical scrubber: right-aligned, vertically adaptive
            val scrubberWidth = (150 * dp).toInt()
            val scrubber = PageScrubberView(context, recyclerView, adapter.pageCount, isHorizontal)
            val scrubberParams = FrameLayout.LayoutParams(scrubberWidth, circleSize).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = (8 * dp).toInt()
                topMargin = (8 * dp).toInt()
            }
            container.addView(scrubber, scrubberParams)
            recyclerView.addOnScrollListener(makeScrollListener(scrubber))
        }
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

class ZoomableLayout(context: Context, private val isHorizontal: Boolean = false) : FrameLayout(context) {

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

    // Sub-pixel accumulators so small per-frame scroll deltas aren't lost to toInt() truncation.
    private var rvPanScrollAccum = 0f
    private var rvScaleScrollAccum = 0f
    // Reference to the running double-tap animator so it can be cancelled on new touch.
    private var currentAnimator: ValueAnimator? = null

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
                if (isHorizontal) {
                    // In horizontal mode, keep the vertical focal point via pure translation.
                    currentTransY = detector.focusY - (detector.focusY - currentTransY) * realFactor
                } else {
                    // Vertical focal point: scroll the RecyclerView so the content point under
                    // the pinch centre stays at the same screen position as the scale changes.
                    // Formula: content_y_focal stays fixed ⟹ delta = focusY*(1/S_old - 1/S_new).
                    rvScaleScrollAccum += detector.focusY * (1f / currentScale - 1f / newScale)
                    val toScrollScale = rvScaleScrollAccum.roundToInt()
                    if (toScrollScale != 0) {
                        (getChildAt(0) as? RecyclerView)?.scrollBy(0, toScrollScale)
                        rvScaleScrollAccum -= toScrollScale
                    }
                }
                currentScale = newScale
                clampTrans()
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
                    if (isHorizontal) {
                        // Horizontal mode: pure translation for both axes when zoomed.
                        currentTransX -= distanceX
                        currentTransY -= distanceY
                    } else {
                        currentTransX -= distanceX
                        // Vertical pan: drive RecyclerView scroll directly so it renders the
                        // correct pages.  distanceY is in screen pixels; divide by scale to get
                        // content pixels.  Accumulate the fractional remainder so no pixels
                        // are silently dropped on slow drags.
                        rvPanScrollAccum += distanceY / currentScale
                        val toScrollPan = rvPanScrollAccum.roundToInt()
                        if (toScrollPan != 0) {
                            (getChildAt(0) as? RecyclerView)?.scrollBy(0, toScrollPan)
                            rvPanScrollAccum -= toScrollPan
                        }
                    }
                    clampTrans()
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Reset per-gesture accumulators and cancel any running zoom animation.
            // Cancelling fires onAnimationEnd synchronously, which commits any pending
            // translationY into the RecyclerView's scroll offset before new gesture
            // callbacks run.
            rvPanScrollAccum = 0f
            rvScaleScrollAccum = 0f
            currentAnimator?.cancel()
        }
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Cache the child to a GPU texture before any gesture begins so that
                // every subsequent pan/zoom frame is a pure matrix transform on the GPU
                // with no CPU-side redraw of page bitmaps.
                if (currentScale > 1.0f) enableHardwareLayer()
            }
            MotionEvent.ACTION_POINTER_DOWN -> enableHardwareLayer()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disableHardwareLayer()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return currentScale > 1.0f || scaleDetector.isInProgress
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return currentScale > 1.0f || scaleDetector.isInProgress
    }

    private fun enableHardwareLayer() {
        getChildAt(0)?.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun disableHardwareLayer() {
        getChildAt(0)?.setLayerType(LAYER_TYPE_NONE, null)
    }

    private fun animateToTransform(targetScale: Float, targetTransX: Float, targetTransY: Float) {
        val startScale = currentScale
        val startTransX = currentTransX
        val startTransY = currentTransY

        currentAnimator?.cancel()
        enableHardwareLayer()
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
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
                    currentAnimator = null
                    commitTranslationY()
                    disableHardwareLayer()
                    onZoomChanged?.invoke(targetScale)
                }
                override fun onAnimationCancel(animation: Animator) {
                    currentAnimator = null
                    commitTranslationY()
                }
            })
            start()
        }
    }

    // Converts any pending translationY into an equivalent RecyclerView scroll so that
    // live panning after an animation uses only rv.scrollBy (no stale translationY).
    // In horizontal mode translationY is kept as pure translation — no commit needed.
    private fun commitTranslationY() {
        if (isHorizontal || currentTransY == 0f) return
        val rv = getChildAt(0) as? RecyclerView
        if (rv != null) {
            rv.scrollBy(0, (-currentTransY / currentScale).roundToInt())
        }
        currentTransY = 0f
        applyTransform()
    }

    private fun clampTrans() {
        if (currentScale <= minScale) {
            currentTransX = 0f
            currentTransY = 0f
            return
        }
        // Horizontal: left/right page edges must stay within screen bounds.
        val minTransX = width.toFloat() * (1f - currentScale)
        currentTransX = currentTransX.coerceIn(minTransX, 0f)
        if (isHorizontal) {
            // In horizontal mode, vertical panning is also a pure translation.
            val minTransY = height.toFloat() * (1f - currentScale)
            currentTransY = currentTransY.coerceIn(minTransY, 0f)
        }
        // In vertical mode, RecyclerView naturally clamps its scroll to [0, maxScrollY]
        // so no translationY clamping is needed.
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

class FallbackPdfRendererAdapter(
    private val context: Context,
    file: File,
    private val screenWidth: Int,
    private val isHorizontal: Boolean = false
) :
    RecyclerView.Adapter<FallbackPdfRendererAdapter.PageViewHolder>() {

    private val fileDescriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(fileDescriptor)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenHeight: Int by lazy { context.resources.displayMetrics.heightPixels }

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

    val pageCount: Int get() = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                if (isHorizontal) screenWidth else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        return PageViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        if (!isHorizontal) {
            holder.imageView.layoutParams = holder.imageView.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = scaledHeight(position)
            }
        }
        // Horizontal: items keep MATCH_PARENT height; FIT_CENTER centres the bitmap.

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
        val baseScale = if (isHorizontal) {
            minOf(screenWidth.toFloat() / info.width, screenHeight.toFloat() / info.height)
        } else {
            screenWidth.toFloat() / info.width
        }
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

/**
 * A circular scrubber handle for the PDF viewer.
 *
 * Vertical mode   — 50×50dp circle pinned to the right edge; drag up/down to jump pages.
 * Horizontal mode — square view with circle centred; drag left/right to jump pages.
 *                   Label pill is drawn ABOVE the circle so it has room to expand.
 */
class PageScrubberView(
    context: Context,
    private val recyclerView: RecyclerView,
    private val totalPages: Int,
    private val isHorizontal: Boolean = false
) : View(context) {

    private val dp = resources.displayMetrics.density
    private val circleR = 25 * dp

    // In horizontal mode: circle is bottom-anchored so the pill above it has room.
    // In vertical mode:   circle is flush to the right edge, vertically centred.
    private val circleCx get() = if (isHorizontal) width / 2f else width - circleR
    private val circleCy get() = if (isHorizontal) height - circleR else height / 2f

    private var currentPage = 0
    private var isDragging = false
    // True whenever the label pill should be visible (drag OR normal swipe scroll)
    private var showLabel = false

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        showLabel = false
        isDragging = false
        invalidate()
    }

    /** Called by the RecyclerView scroll listener on every scroll event. */
    fun notifyScrolling() {
        showLabel = true
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 1000L)
        invalidate()
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 30, 30, 30)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 30, 30, 30)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12 * dp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    fun updatePage(page: Int) {
        if (currentPage == page) return
        currentPage = page
        reposition()
        invalidate()
    }

    private fun reposition() {
        val parent = parent as? View ?: return
        val marginDp = (8 * dp).toInt()
        if (isHorizontal) {
            val parentW = parent.width.takeIf { it > 0 } ?: return
            val viewW = width.takeIf { it > 0 } ?: return
            val travelRange = (parentW - viewW - marginDp * 2).toFloat().coerceAtLeast(0f)
            val ratio = if (totalPages <= 1) 0f else currentPage.toFloat() / (totalPages - 1)
            translationX = ratio * travelRange
        } else {
            val parentH = parent.height.takeIf { it > 0 } ?: return
            val viewH = height.takeIf { it > 0 } ?: return
            val travelRange = (parentH - viewH - marginDp * 2).toFloat().coerceAtLeast(0f)
            val ratio = if (totalPages <= 1) 0f else currentPage.toFloat() / (totalPages - 1)
            translationY = ratio * travelRange
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reposition()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = circleCx
        val cy = circleCy

        if (isDragging || showLabel) {
            val label = "${currentPage + 1} / $totalPages"
            val labelPadH = 14 * dp
            val labelPadV = 10 * dp
            val labelW = textPaint.measureText(label) + labelPadH * 2
            val labelH = textPaint.textSize + labelPadV * 2

            if (isHorizontal) {
                // Pill drawn ABOVE the circle — centred on cx, space guaranteed by square view
                val pillBottom = cy - circleR - 10 * dp
                val pillTop = pillBottom - labelH
                val pillLeft = cx - labelW / 2f
                val pillRight = cx + labelW / 2f
                canvas.drawRoundRect(RectF(pillLeft, pillTop, pillRight, pillBottom), labelH / 2f, labelH / 2f, labelBgPaint)
                val textY = (pillTop + pillBottom) / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(label, cx, textY, textPaint)
            } else {
                // Pill drawn to the LEFT of the circle
                val pillRight = cx - circleR - 10 * dp
                val pillLeft = pillRight - labelW
                val pillTop = cy - labelH / 2f
                val pillBottom = cy + labelH / 2f
                canvas.drawRoundRect(RectF(pillLeft, pillTop, pillRight, pillBottom), labelH / 2f, labelH / 2f, labelBgPaint)
                val textY = cy - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(label, pillLeft + labelW / 2f, textY, textPaint)
            }
        }

        // Circle background
        canvas.drawCircle(cx, cy, circleR, circlePaint)

        val aw = 10 * dp
        if (isHorizontal) {
            // Left arrow
            val leftTipX = cx - 8 * dp
            canvas.drawLine(leftTipX + 6 * dp, cy - aw / 2f, leftTipX, cy, arrowPaint)
            canvas.drawLine(leftTipX, cy, leftTipX + 6 * dp, cy + aw / 2f, arrowPaint)
            // Right arrow
            val rightTipX = cx + 8 * dp
            canvas.drawLine(rightTipX - 6 * dp, cy - aw / 2f, rightTipX, cy, arrowPaint)
            canvas.drawLine(rightTipX, cy, rightTipX - 6 * dp, cy + aw / 2f, arrowPaint)
        } else {
            // Up arrow
            val upTipY = cy - 8 * dp
            canvas.drawLine(cx - aw / 2f, upTipY + 6 * dp, cx, upTipY, arrowPaint)
            canvas.drawLine(cx, upTipY, cx + aw / 2f, upTipY + 6 * dp, arrowPaint)
            // Down arrow
            val downTipY = cy + 8 * dp
            canvas.drawLine(cx - aw / 2f, downTipY - 6 * dp, cx, downTipY, arrowPaint)
            canvas.drawLine(cx, downTipY, cx + aw / 2f, downTipY - 6 * dp, arrowPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx = event.x - circleCx
        val dy = event.y - circleCy
        val inCircle = dx * dx + dy * dy <= circleR * circleR

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!inCircle) return false
                isDragging = true
                showLabel = true
                hideHandler.removeCallbacks(hideRunnable)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return true
                val parentView = parent as? View ?: return true
                val parentLoc = IntArray(2)
                parentView.getLocationOnScreen(parentLoc)
                val targetPage = if (isHorizontal) {
                    val relX = event.rawX - parentLoc[0]
                    val fraction = (relX / parentView.width).coerceIn(0f, 1f)
                    (fraction * (totalPages - 1)).roundToInt().coerceIn(0, totalPages - 1)
                } else {
                    val relY = event.rawY - parentLoc[1]
                    val fraction = (relY / parentView.height).coerceIn(0f, 1f)
                    (fraction * (totalPages - 1)).roundToInt().coerceIn(0, totalPages - 1)
                }
                if (targetPage != currentPage) {
                    currentPage = targetPage
                    (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(targetPage, 0)
                    reposition()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hideHandler.removeCallbacks(hideRunnable)
                hideHandler.postDelayed(hideRunnable, 1000L)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
