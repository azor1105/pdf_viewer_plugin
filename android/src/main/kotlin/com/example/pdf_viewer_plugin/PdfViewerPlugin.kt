package com.example.pdf_viewer_plugin

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
import android.view.View
import android.view.ViewGroup
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
        val recyclerView = RecyclerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            setItemViewCacheSize(6)
        }
        container.addView(recyclerView)
        val adapter = FallbackPdfRendererAdapter(file, screenWidth)
        pdfRendererAdapter = adapter
        recyclerView.adapter = adapter
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

class FallbackPdfRendererAdapter(file: File, private val screenWidth: Int) :
    RecyclerView.Adapter<FallbackPdfRendererAdapter.PageViewHolder>() {

    private val fileDescriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(fileDescriptor)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PageInfo(val width: Int, val height: Int)
    private val pageInfoList: List<PageInfo>

    private val bitmapCache: LruCache<Int, Bitmap>

    init {
        // Precompute page dimensions so layout heights are stable during scroll
        pageInfoList = (0 until renderer.pageCount).map { i ->
            val page = renderer.openPage(i)
            val info = PageInfo(page.width, page.height)
            page.close()
            info
        }

        // Use 1/4 of available app memory for bitmap cache
        val maxMemoryKB = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        bitmapCache = object : LruCache<Int, Bitmap>(maxMemoryKB / 4) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }
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
        // Set correct height upfront to prevent scroll jumps
        holder.imageView.layoutParams = holder.imageView.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = scaledHeight(position)
        }

        // Serve from cache if available
        val cached = bitmapCache.get(position)
        if (cached != null && !cached.isRecycled) {
            holder.imageView.setImageBitmap(cached)
            return
        }

        // Show white placeholder while rendering
        holder.imageView.setImageBitmap(null)
        holder.imageView.setBackgroundColor(Color.WHITE)

        // Tag to validate async result against recycled views
        val tag = position
        holder.imageView.tag = tag

        // Render on background thread to keep UI thread free
        renderExecutor.execute {
            try {
                val bitmap = renderPage(position) ?: return@execute
                bitmapCache.put(position, bitmap)
                mainHandler.post {
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
    private fun renderPage(position: Int): Bitmap? {
        if (position < 0 || position >= renderer.pageCount) return null
        val page = renderer.openPage(position)
        val info = pageInfoList[position]
        val scale = screenWidth.toFloat() / info.width
        val width = (info.width * scale).toInt()
        val height = (info.height * scale).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    fun close() {
        renderExecutor.shutdownNow()
        bitmapCache.evictAll()
        renderer.close()
        fileDescriptor.close()
    }

    class PageViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
}
