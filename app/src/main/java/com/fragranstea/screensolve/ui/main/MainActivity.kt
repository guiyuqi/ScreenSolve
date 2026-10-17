package com.fragranstea.screensolve.ui.main

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.ui.overlay.OverlayService
import com.fragranstea.screensolve.ui.settings.SettingsActivity
import com.fragranstea.screensolve.util.Permissions

/**
 * 主界面：权限引导 + 悬浮球总开关。
 * M1 用 XML + findViewById，避免在脚手架阶段引入额外变量；等设置页/历史页落地再评估 Compose。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var grantOverlayButton: Button
    private lateinit var grantNotificationButton: Button
    private lateinit var toggleButton: Button
    private lateinit var sessionStatus: TextView
    private lateinit var releaseSessionButton: Button

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = findViewById(R.id.tvOverlayStatus)
        notificationStatus = findViewById(R.id.tvNotifStatus)
        grantOverlayButton = findViewById(R.id.btnGrantOverlay)
        grantNotificationButton = findViewById(R.id.btnGrantNotif)
        toggleButton = findViewById(R.id.btnToggle)
        sessionStatus = findViewById(R.id.tvSessionStatus)
        releaseSessionButton = findViewById(R.id.btnReleaseSession)
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        grantOverlayButton.setOnClickListener {
            // 悬浮窗权限是特殊权限，只能跳系统设置页
            startActivity(Permissions.overlaySettingsIntent(this))
        }

        grantNotificationButton.setOnClickListener {
            if (Permissions.needsNotificationPermission()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        toggleButton.setOnClickListener { toggleBubble() }

        releaseSessionButton.setOnClickListener {
            // 服务没在跑时不要调 releaseSession，否则 startService 会把服务拉起来（反而弹出悬浮球）
            if (OverlayService.isRunning) {
                OverlayService.releaseSession(this)
                releaseSessionButton.postDelayed({ refreshUi() }, 300)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时必须刷新，否则权限状态会停在旧值
        refreshUi()
    }

    private fun toggleBubble() {
        if (OverlayService.isRunning) {
            OverlayService.stop(this)
            Toast.makeText(this, R.string.toast_bubble_stopped, Toast.LENGTH_SHORT).show()
        } else {
            if (!Permissions.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_need_overlay_permission, Toast.LENGTH_LONG).show()
                startActivity(Permissions.overlaySettingsIntent(this))
                return
            }
            OverlayService.start(this)
            Toast.makeText(this, R.string.toast_bubble_started, Toast.LENGTH_SHORT).show()
        }
        // 服务启停是异步的，稍等一下再刷新按钮文案
        // TODO(M5): 改成服务主动回调/广播，去掉这个时间猜测
        toggleButton.postDelayed({ refreshUi() }, 300)
    }

    private fun refreshUi() {
        val canDrawOverlays = Permissions.canDrawOverlays(this)
        overlayStatus.text = getString(
            if (canDrawOverlays) R.string.main_status_overlay_on else R.string.main_status_overlay_off,
        )
        overlayStatus.setTextColor(
            ContextCompat.getColor(this, if (canDrawOverlays) R.color.status_ok else R.color.status_warn),
        )
        grantOverlayButton.visibility = if (canDrawOverlays) View.GONE else View.VISIBLE

        val needsNotification = Permissions.needsNotificationPermission()
        val canNotify = Permissions.canPostNotifications(this)
        notificationStatus.text = when {
            !needsNotification -> getString(R.string.main_status_notif_na)
            canNotify -> getString(R.string.main_status_notif_on)
            else -> getString(R.string.main_status_notif_off)
        }
        notificationStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (!needsNotification || canNotify) R.color.status_ok else R.color.status_warn,
            ),
        )
        grantNotificationButton.visibility =
            if (needsNotification && !canNotify) View.VISIBLE else View.GONE

        toggleButton.setText(
            if (OverlayService.isRunning) R.string.main_btn_stop else R.string.main_btn_start,
        )

        val sessionActive = OverlayService.isRunning && OverlayService.isSessionActive
        sessionStatus.text = getString(
            if (sessionActive) R.string.main_status_session_on else R.string.main_status_session_off,
        )
        sessionStatus.setTextColor(
            ContextCompat.getColor(this, if (sessionActive) R.color.status_ok else R.color.status_warn),
        )
        releaseSessionButton.visibility = if (sessionActive) View.VISIBLE else View.GONE
    }
}
