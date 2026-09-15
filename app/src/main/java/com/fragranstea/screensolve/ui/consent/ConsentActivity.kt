package com.fragranstea.screensolve.ui.consent

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.fragranstea.screensolve.ui.overlay.OverlayService

/**
 * 无界面中转 Activity。
 *
 * 存在的唯一原因：悬浮球跑在 Service 里，而 MediaProjection 的系统授权框
 * **只能由 Activity 发起**。这里只做三件事：拉起授权框 → 把 resultCode/data 回传 Service → 自己结束。
 * 所以它是全透明的、不进最近任务、不保留历史。
 */
class ConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            OverlayService.deliverConsent(this, result.resultCode, data)
        } else {
            OverlayService.deliverConsentDenied(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置变化导致重建时不要重复申请，否则会看到两个授权框
        if (savedInstanceState != null) {
            finish()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }
}
