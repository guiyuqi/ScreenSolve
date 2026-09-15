package com.fragranstea.screensolve.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.fragranstea.screensolve.util.DisplayUtils

/**
 * 持续投屏会话：**授权一次**，之后随时取"当前这一帧"，不再反复弹系统授权框。
 *
 * 为什么必须持续排空（见 ARCHITECTURE.md §4.2）：
 * ImageReader 只有 [MAX_IMAGES] 个缓冲。如果空闲期间不去取帧，队列填满后 SurfaceFlinger
 * 就停止产帧，缓冲里留下的是"上一次产帧那一刻"的画面 —— 用户滚动十几秒再点搜题，
 * 拿到的就是十几秒前的旧图，而且极难复现。
 * 所以这里注册常驻监听：每来一帧就取最新、关掉上一帧（同一把锁保护），
 * 点击时直接取保留帧。空闲时只做 acquire/close，不拷贝不解码，代价极小；
 * 屏幕完全静止时没有 damage 事件，等于零开销。
 */
class ProjectionSession(private val context: Context) {

    fun interface StopListener {
        /** 系统或用户结束了投射（例如状态栏点"停止共享"）。 */
        fun onProjectionStopped()
    }

    var onStopped: StopListener? = null

    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    /** 已收到的帧序号。用来判断"隐藏悬浮球之后有没有等到新帧"。 */
    @Volatile
    var frameCount: Long = 0L
        private set

    val isActive: Boolean
        get() = projection != null && virtualDisplay != null

    private val mainHandler = Handler(context.mainLooper)
    private val lock = Any()

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameThread: HandlerThread? = null

    /** 保留的最新一帧（故意不关，直到有更新的一帧到来）。 */
    private var latestFrame: Image? = null

    /**
     * 用授权结果建立会话。调用方必须保证：前台服务已经带着 mediaProjection 类型
     * 调过 startForeground（Android 14+ 的顺序要求），否则这里会抛 SecurityException。
     */
    fun start(resultCode: Int, data: Intent): Boolean {
        if (isActive) return true

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.w(TAG, "getMediaProjection 失败", t)
            null
        } ?: return false

        // Android 14+ 必须注册 Callback，否则应用退到后台时投射会被静默停止
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "投射被系统/用户结束")
                release()
                // 注意：fun interface 的实例要用方法名调用，不能写 onStopped?.invoke()
                onStopped?.onProjectionStopped()
            }
        }
        mediaProjection.registerCallback(callback, mainHandler)

        val thread = HandlerThread("screensolve-frames").apply { start() }
        val handler = Handler(thread.looper)

        val screenWidth = DisplayUtils.screenBounds(context).width()
        val screenHeight = DisplayUtils.screenBounds(context).height()
        val imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        imageReader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (t: Throwable) {
                Log.w(TAG, "acquireLatestImage 失败", t)
                null
            } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                latestFrame?.close()   // 关掉上一帧，保持"手上只有最新帧"
                latestFrame = image
                frameCount++
            }
        }, handler)

        val display = try {
            mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                DisplayUtils.densityDpi(context),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "createVirtualDisplay 失败", t)
            null
        }

        if (display == null) {
            runCatching { imageReader.close() }
            runCatching { mediaProjection.unregisterCallback(callback) }
            runCatching { mediaProjection.stop() }
            thread.quitSafely()
            return false
        }

        projection = mediaProjection
        projectionCallback = callback
        virtualDisplay = display
        reader = imageReader
        frameThread = thread
        width = screenWidth
        height = screenHeight
        return true
    }

    /** 取当前最新一帧。还没有任何帧时返回 null。 */
    fun snapshot(): Bitmap? = synchronized(lock) {
        val image = latestFrame ?: return null
        imageToBitmap(image)
    }

    /**
     * 等到出现比 [afterFrameCount] 更新的一帧。
     * 用于"隐藏悬浮球之后再抓"，确保截出来的画面里没有自己。
     * **会阻塞**，必须在工作线程调用。
     */
    fun awaitFrameAfter(afterFrameCount: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (frameCount > afterFrameCount) return true
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun release() {
        // 先把引用清空，避免 projection.stop() 触发 onStop 回调后再次进入 release 造成递归
        val mediaProjection = projection ?: return
        projection = null
        val callback = projectionCallback
        projectionCallback = null
        val display = virtualDisplay
        virtualDisplay = null
        val imageReader = reader
        reader = null
        val thread = frameThread
        frameThread = null

        synchronized(lock) {
            latestFrame?.close()
            latestFrame = null
        }
        runCatching { display?.release() }
        runCatching { imageReader?.close() }
        runCatching { callback?.let { mediaProjection.unregisterCallback(it) } }
        runCatching { mediaProjection.stop() }
        runCatching { thread?.quitSafely() }
    }

    /**
     * Image → Bitmap。
     * 注意 rowStride 往往大于 width*pixelStride（每行有 padding），必须按 rowStride 建图再裁掉右侧多余部分，
     * 否则画面会横向错位/倾斜。这是这块最经典的坑。
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        }
    }

    private companion object {
        const val TAG = "ProjectionSession"
        const val VIRTUAL_DISPLAY_NAME = "screensolve-capture"

        /** 2 个缓冲足够：1 个留在手上、1 个供生产者写入。 */
        const val MAX_IMAGES = 2
        const val POLL_INTERVAL_MS = 16L
    }
}
