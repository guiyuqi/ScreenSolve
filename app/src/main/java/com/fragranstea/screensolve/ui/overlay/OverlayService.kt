package com.fragranstea.screensolve.ui.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.capture.ProjectionSession
import com.fragranstea.screensolve.data.BubblePositionStore
import com.fragranstea.screensolve.ui.consent.ConsentActivity
import com.fragranstea.screensolve.ui.main.MainActivity
import com.fragranstea.screensolve.util.DisplayUtils

/**
 * 持有悬浮球与截屏会话的前台服务。
 *
 * M1：悬浮球（显示/拖动/吸边/点击）、权限引导、位置记忆。
 * M2：**持续投屏会话** —— 首次点悬浮球时申请一次授权，之后会话常驻（[ProjectionSession]），
 *     每次点悬浮球直接取"当前这一帧"，不再反复弹系统授权框。
 *
 * 前台服务类型声明为 specialUse|mediaProjection：平时只用 specialUse；
 * 一旦要建立投屏会话，就按 Android 14+ 的要求**先**让前台服务带上 mediaProjection 类型，
 * **再**调用 getMediaProjection（顺序写反会直接抛 SecurityException）。
 */
class OverlayService :
    Service(),
    FloatingBubbleView.Listener,
    ProjectionSession.StopListener {

    private lateinit var windowManager: WindowManager
    private lateinit var positionStore: BubblePositionStore
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubble: FloatingBubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var projectionSession: ProjectionSession? = null
    private var preview: FramePreviewOverlay? = null

    /** 用户点悬浮球时若还没有会话，授权完成之后要把这次抓帧继续做完。 */
    private var capturePendingAfterConsent = false
    private var capturing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        positionStore = BubblePositionStore(this)
        startForegroundCompat()
        showBubble()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RELEASE_SESSION -> releaseProjectionSession()
            ACTION_CONSENT_RESULT -> handleConsentResult(intent)
            ACTION_CONSENT_DENIED -> {
                capturePendingAfterConsent = false
                toast(R.string.toast_capture_denied)
            }
        }
        // 用 NOT_STICKY：进程被杀后不自动复活，避免用户以为已关闭却仍在运行
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseProjectionSession()
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        bubbleParams = null
        isRunning = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转屏幕 / 分屏后，把悬浮球拉回可视范围并重新吸边
        val params = bubbleParams ?: return
        val view = bubble ?: return
        val bounds = DisplayUtils.screenBounds(this)
        params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
        snapToEdge(params, bounds)
        syncWindowPosition(params)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // ---------------- 截屏授权（无界面 Activity 中转回来） ----------------

    private fun handleConsentResult(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intentExtra(intent, EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            capturePendingAfterConsent = false
            toast(R.string.toast_capture_denied)
            return
        }

        // 顺序很关键：Android 14+ 要求前台服务已经带上 mediaProjection 类型，才能调用 getMediaProjection
        promoteForegroundForProjection()

        val session = projectionSession ?: ProjectionSession(this).also {
            it.onStopped = this
            projectionSession = it
        }

        if (!session.start(resultCode, data)) {
            toast(R.string.toast_capture_failed)
            capturePendingAfterConsent = false
            return
        }

        isSessionActive = true
        toast(R.string.toast_session_ready)

        if (capturePendingAfterConsent) {
            capturePendingAfterConsent = false
            startCaptureFlow()
        }
    }

    override fun onProjectionStopped() {
        isSessionActive = false
        toast(R.string.toast_session_lost)
    }

    private fun releaseProjectionSession() {
        projectionSession?.let {
            it.onStopped = null
            it.release()
        }
        projectionSession = null
        isSessionActive = false
        preview?.remove()
        preview = null
    }

    // ---------------- 悬浮球 ----------------

    private fun showBubble() {
        if (bubble != null) return

        val bounds = DisplayUtils.screenBounds(this)
        val size = DisplayUtils.dp(this, BUBBLE_SIZE_DP)
        val maxX = (bounds.width() - size).coerceAtLeast(0)
        val maxY = (bounds.height() - size).coerceAtLeast(0)

        val initialX = if (positionStore.hasPosition) positionStore.x else maxX - DisplayUtils.dp(this, EDGE_MARGIN_DP)
        val initialY = if (positionStore.hasPosition) positionStore.y else bounds.height() / 3

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX.coerceIn(0, maxX)
            y = initialY.coerceIn(0, maxY)
        }

        val view = FloatingBubbleView(this).apply {
            listener = this@OverlayService
            windowX = params.x
            windowY = params.y
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // 最常见的原因：用户没给 SYSTEM_ALERT_WINDOW，或授权后被系统撤销
            toast(R.string.toast_need_overlay_permission)
            stopSelf()
            return
        }

        bubble = view
        bubbleParams = params
    }

    override fun onBubbleDragged(x: Int, y: Int) {
        val params = bubbleParams ?: return
        val view = bubble ?: return
        val bounds = DisplayUtils.screenBounds(this)
        // 拖动中允许半个球出界（手感更自然），松手时再吸回来
        val half = params.width / 2
        params.x = x.coerceIn(-half, bounds.width() - half)
        params.y = y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
        syncWindowPosition(params)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    override fun onBubbleDragFinished(x: Int, y: Int) {
        val params = bubbleParams ?: return
        val view = bubble ?: return
        snapToEdge(params, DisplayUtils.screenBounds(this))
        syncWindowPosition(params)
        runCatching { windowManager.updateViewLayout(view, params) }
        positionStore.x = params.x
        positionStore.y = params.y
    }

    override fun onBubbleTapped() {
        if (projectionSession?.isActive == true) {
            startCaptureFlow()
        } else {
            // 悬浮球在 Service 里，而授权框只能由 Activity 发起 → 走无界面中转 Activity
            capturePendingAfterConsent = true
            startActivity(
                Intent(this, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---------------- 抓帧 ----------------

    /**
     * 抓当前这一帧。整体跑在工作线程：等新帧是阻塞操作，不能占主线程。
     *
     * 关键一步是**先隐藏悬浮球、再等一个新帧**：隐藏动作本身会产生一次画面变化，
     * 所以新帧一定会来，从而保证截出来的画面里没有自己的悬浮球。
     */
    private fun startCaptureFlow() {
        if (capturing) return
        val session = projectionSession
        if (session == null || !session.isActive) {
            toast(R.string.toast_capture_failed)
            return
        }
        capturing = true

        Thread({
            try {
                val bubbleWasVisible = bubble?.visibility == View.VISIBLE
                val frameBefore = session.frameCount

                if (bubbleWasVisible) onMain { setBubbleVisible(false) }
                val gotFreshFrame = if (bubbleWasVisible) {
                    session.awaitFrameAfter(frameBefore, FRAME_WAIT_TIMEOUT_MS)
                } else {
                    true
                }
                val bitmap = session.snapshot()
                val frameIndex = session.frameCount
                if (bubbleWasVisible) onMain { setBubbleVisible(true) }

                if (bitmap == null) {
                    Log.w(TAG, "抓帧为空（会话可能刚建立，还没产生第一帧）")
                    onMain { toast(R.string.toast_capture_failed) }
                } else {
                    Log.i(
                        TAG,
                        "抓帧成功 frame=$frameIndex fresh=$gotFreshFrame size=${bitmap.width}x${bitmap.height}",
                    )
                    onMain { showPreview(bitmap, frameIndex, gotFreshFrame) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "抓帧异常", t)
                onMain {
                    setBubbleVisible(true)
                    toast(R.string.toast_capture_failed)
                }
            } finally {
                capturing = false
            }
        }, "screensolve-capture").start()
    }

    private fun showPreview(bitmap: Bitmap, frameIndex: Long, fresh: Boolean) {
        preview?.remove()

        // 卡片停靠在悬浮球的另一侧，避免盖住悬浮球（否则用户想再抓一次会点到卡片）
        val params = bubbleParams
        val bounds = DisplayUtils.screenBounds(this)
        val bubbleOnRight = params == null || (params.x + params.width / 2) > bounds.width() / 2

        val overlay = FramePreviewOverlay(this, windowManager)
        overlay.onClosed = { if (preview === overlay) preview = null }
        preview = overlay
        overlay.show(bitmap, frameIndex, fresh, bubbleOnRight)
    }

    private fun setBubbleVisible(visible: Boolean) {
        bubble?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 吸边：留一点边距而不是完全贴死，因为现代机型屏幕左右边缘被系统返回手势占用，
     * 贴死会导致悬浮球很难拖出来。
     */
    private fun snapToEdge(params: WindowManager.LayoutParams, bounds: Rect) {
        val margin = DisplayUtils.dp(this, EDGE_MARGIN_DP)
        val maxX = (bounds.width() - params.width).coerceAtLeast(0)
        val maxY = (bounds.height() - params.height).coerceAtLeast(0)

        params.y = params.y.coerceIn(0, maxY)
        val centerX = params.x + params.width / 2f
        params.x = if (centerX < bounds.width() / 2f) {
            margin.coerceAtMost(maxX)
        } else {
            (maxX - margin).coerceAtLeast(margin.coerceAtMost(maxX))
        }
    }

    private fun syncWindowPosition(params: WindowManager.LayoutParams) {
        bubble?.let {
            it.windowX = params.x
            it.windowY = params.y
        }
    }

    // ---------------- 前台服务与通知 ----------------

    private fun startForegroundCompat() {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundMask(projecting = false),
        )
    }

    /** 建立投屏会话之前把 mediaProjection 类型补上（Android 14+ 的硬性顺序要求）。 */
    private fun promoteForegroundForProjection() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundMask(projecting = true),
        )
    }

    private fun foregroundMask(projecting: Boolean): Int {
        var mask = 0
        if (projecting) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return mask
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_overlay)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_action_stop), stopService)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ---------------- 工具 ----------------

    private fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun intentExtra(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Intent::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    companion object {
        const val ACTION_STOP = "com.fragranstea.screensolve.action.STOP_OVERLAY"
        const val ACTION_RELEASE_SESSION = "com.fragranstea.screensolve.action.RELEASE_SESSION"
        const val ACTION_CONSENT_RESULT = "com.fragranstea.screensolve.action.CONSENT_RESULT"
        const val ACTION_CONSENT_DENIED = "com.fragranstea.screensolve.action.CONSENT_DENIED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_service"
        private const val NOTIFICATION_ID = 1001
        private const val BUBBLE_SIZE_DP = 56
        private const val EDGE_MARGIN_DP = 4

        /** 等"隐藏悬浮球之后的新帧"的最长时间。隐藏本身是一次画面变化，所以新帧一定会来。 */
        private const val FRAME_WAIT_TIMEOUT_MS = 800L

        /**
         * 供界面判断服务是否在运行。
         * 只用于本应用自己的服务，够用且没有 ActivityManager 那些兼容性问题。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 投屏会话是否已建立（会话常驻，界面只用来显示状态）。 */
        @Volatile
        var isSessionActive: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun releaseSession(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_RELEASE_SESSION),
            )
        }

        /** 授权成功：把 resultCode 与授权 Intent 转交给服务。 */
        fun deliverConsent(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_CONSENT_RESULT)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun deliverConsentDenied(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_CONSENT_DENIED)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
