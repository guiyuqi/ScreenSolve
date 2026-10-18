package com.fragranstea.screensolve.ui.region

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.capture.ImagePipeline
import com.fragranstea.screensolve.util.DisplayUtils

/**
 * 全屏选区——**悬浮窗版本**，不再启动 Activity。
 *
 * 为什么改成悬浮窗（v1.1.0）：之前用 RegionSelectActivity，点悬浮球时会出现一次 Activity 转场动画，
 * 用户自己的 App 也会短暂 onPause —— 用户明确反馈"搜题时还是跳走了"。
 * 悬浮窗只是往当前屏幕上盖一层，**不改变前台应用**，也就没有转场动画。
 *
 * 交互与之前的 Activity 版完全一致（复用同一个 [RegionSelectView]）：
 * 预选框 + 四角微调 + 框内平移 + 框外重画，底部「取消 / 识别这道题」。
 */
class RegionSelectOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    /** 确认：给出裁剪后的位图 */
    var onConfirm: ((Bitmap) -> Unit)? = null

    /** 取消（含按返回键） */
    var onCancel: (() -> Unit)? = null

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var regionView: RegionSelectView? = null
    private var frame: Bitmap? = null
    private var confirmButton: Button? = null
    private var hintView: TextView? = null

    fun show(frameBitmap: Bitmap) {
        frame = frameBitmap
        if (root != null) return

        val density = DisplayUtils.density(context)
        val bounds = DisplayUtils.screenBounds(context)

        val container = FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        val selector = RegionSelectView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            onSelectionChanged = { rect -> renderHint(rect) }
            setFrame(frameBitmap)
        }

        val hint = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val cancel = Button(context).apply {
            text = context.getString(R.string.region_cancel)
            setOnClickListener { cancelWithCallback() }
        }
        val confirm = Button(context).apply {
            text = context.getString(R.string.region_confirm)
            setOnClickListener { confirmSelection() }
        }

        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(cancel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(
                        confirm,
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                            marginStart = (12 * density).toInt()
                        },
                    )
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = (12 * density).toInt()
                },
            )
        }

        container.addView(selector)
        container.addView(
            bottomBar,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )

        // 返回键要能取消，所以窗口是 focusable 的（悬浮窗默认不带焦点，收不到按键）
        container.isFocusableInTouchMode = true

        val layoutParams = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        container.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                cancelWithCallback()
                true
            } else {
                false
            }
        }

        try {
            windowManager.addView(container, layoutParams)
        } catch (t: Throwable) {
            onCancel?.invoke()
            return
        }

        root = container
        params = layoutParams
        regionView = selector
        confirmButton = confirm
        hintView = hint
        container.requestFocus()

        // 首帧尺寸确定后再刷一次提示与按钮状态（预选框是 onSizeChanged 里给的）
        container.post {
            renderHint(selector.selectionInBitmap())
            container.requestFocus()
        }
    }

    fun remove() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        params = null
        regionView = null
        confirmButton = null
        hintView = null
        // 位图由调用方持有（历史记录还要用来存图），这里只放开引用
        frame = null
    }

    private fun renderHint(rect: android.graphics.Rect?) {
        confirmButton?.isEnabled = rect != null
        hintView?.text = if (rect == null) {
            context.getString(R.string.region_hint_empty)
        } else {
            context.getString(R.string.region_hint_sized, rect.width(), rect.height())
        }
    }

    private fun confirmSelection() {
        val selector = regionView ?: return
        val source = frame ?: return
        val rect = selector.selectionInBitmap() ?: return
        val cropped = ImagePipeline.crop(source, rect)
        remove()
        onConfirm?.invoke(cropped)
    }

    private fun cancelWithCallback() {
        remove()
        onCancel?.invoke()
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
