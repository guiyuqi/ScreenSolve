package com.fragranstea.screensolve.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.data.BubblePositionStore
import com.fragranstea.screensolve.ui.main.MainActivity

/**
 * 持有悬浮球的前台服务。
 *
 * M1 范围：显示悬浮球、拖动、松手吸边、点击回调、位置记忆、通知栏常驻与"关闭"入口。
 * M2 会在这里加 MediaProjection 会话（届时前台服务类型要补 mediaProjection）。
 *
 * 前台服务类型用的是 specialUse：M1 并不在截屏，用 mediaProjection 类型反而会在部分机型上
 * 因为没有活跃投影而被系统拒绝。等真正开始截屏时再按 ARCHITECTURE.md §4.2 的顺序调用。
 */
class OverlayService : Service(), FloatingBubbleView.Listener {

    private lateinit var windowManager: WindowManager
    private lateinit var positionStore: BubblePositionStore
    private var bubble: FloatingBubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        positionStore = BubblePositionStore(this)
        startForegroundCompat()
        showBubble()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        // 用 NOT_STICKY：进程被杀后不自动复活，避免用户以为已关闭却仍在运行
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        bubble?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        bubble = null
        bubbleParams = null
        isRunning = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转屏幕 / 分屏后，把悬浮球拉回可视范围并重新吸边
        val params = bubbleParams ?: return
        val view = bubble ?: return
        val bounds = screenBounds()
        params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
        snapToEdge(params, bounds)
        syncWindowPosition(params)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // ---------------- 悬浮球 ----------------

    private fun showBubble() {
        if (bubble != null) return

        val bounds = screenBounds()
        val size = dp(BUBBLE_SIZE_DP)
        val maxX = (bounds.width() - size).coerceAtLeast(0)
        val maxY = (bounds.height() - size).coerceAtLeast(0)

        val initialX = if (positionStore.hasPosition) positionStore.x else maxX - dp(EDGE_MARGIN_DP)
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
            // 最常见的失败原因：用户没给 SYSTEM_ALERT_WINDOW，或授权后被系统撤销
            Toast.makeText(this, R.string.toast_need_overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        bubble = view
        bubbleParams = params
    }

    override fun onBubbleDragged(x: Int, y: Int) {
        val params = bubbleParams ?: return
        val view = bubble ?: return
        val bounds = screenBounds()
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
        snapToEdge(params, screenBounds())
        syncWindowPosition(params)
        runCatching { windowManager.updateViewLayout(view, params) }
        positionStore.x = params.x
        positionStore.y = params.y
    }

    override fun onBubbleTapped() {
        // M1 只验证链路；M2 会在这里启动截图 + 选区流程
        Toast.makeText(this, R.string.toast_m1_placeholder, Toast.LENGTH_SHORT).show()
    }

    /**
     * 吸边：留一点边距而不是完全贴死，因为现代机型屏幕左右边缘被系统返回手势占用，
     * 贴死会导致悬浮球很难拖出来。
     */
    private fun snapToEdge(params: WindowManager.LayoutParams, bounds: Rect) {
        val margin = dp(EDGE_MARGIN_DP)
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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
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

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.fragranstea.screensolve.action.STOP_OVERLAY"

        private const val CHANNEL_ID = "overlay_service"
        private const val NOTIFICATION_ID = 1001
        private const val BUBBLE_SIZE_DP = 56
        private const val EDGE_MARGIN_DP = 4

        /**
         * 供界面判断服务是否在运行。
         * 只用于本应用自己的服务，够用且没有 ActivityManager 那些兼容性问题。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
