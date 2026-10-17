package com.fragranstea.screensolve.ui.region

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.capture.FrameHolder
import com.fragranstea.screensolve.capture.ImagePipeline
import com.fragranstea.screensolve.ui.overlay.OverlayService

/**
 * 选区界面：全屏显示刚抓到的画面，用户框选题目区域后确认。
 *
 * 放在独立 Activity 而不是悬浮窗里，是因为全屏手势、返回键、生命周期都更简单可靠。
 * 裁剪结果通过 [FrameHolder] 交给 OverlayService —— 同进程，不需要把 Bitmap 塞进 Intent。
 */
class RegionSelectActivity : AppCompatActivity() {

    /** 已经处理过结果（确认/取消），避免 onDestroy 里重复通知。 */
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frame = FrameHolder.frame
        if (frame == null) {
            // 进程被杀或直接进来：没有素材，直接退出
            handled = true
            OverlayService.notifyRegionCancelled(this)
            finish()
            return
        }

        setContentView(R.layout.activity_region_select)

        val regionView = findViewById<RegionSelectView>(R.id.regionSelectView)
        val confirmButton = findViewById<Button>(R.id.btnConfirmRegion)
        val cancelButton = findViewById<Button>(R.id.btnCancelRegion)
        val hint = findViewById<TextView>(R.id.tvRegionHint)

        regionView.setFrame(frame)
        confirmButton.isEnabled = false
        hint.text = getString(R.string.region_hint_empty)

        regionView.onSelectionChanged = { rect ->
            confirmButton.isEnabled = rect != null
            hint.text = if (rect == null) {
                getString(R.string.region_hint_empty)
            } else {
                getString(R.string.region_hint_sized, rect.width(), rect.height())
            }
        }

        confirmButton.setOnClickListener {
            val rect = regionView.selectionInBitmap()
            if (rect == null) return@setOnClickListener
            FrameHolder.cropped = ImagePipeline.crop(frame, rect)
            handled = true
            OverlayService.notifyRegionSelected(this)
            finish()
        }

        cancelButton.setOnClickListener {
            handled = true
            OverlayService.notifyRegionCancelled(this)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 用户按返回键/手势退出时也要通知服务，否则悬浮球会一直保持隐藏状态
        if (isFinishing && !handled) {
            handled = true
            OverlayService.notifyRegionCancelled(this)
        }
    }
}
