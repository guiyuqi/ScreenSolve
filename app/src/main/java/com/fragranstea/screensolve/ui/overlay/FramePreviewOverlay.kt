package com.fragranstea.screensolve.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.util.DisplayUtils

/**
 * 抓帧结果的预览悬浮窗。
 *
 * M2 阶段的用途是**验证采集链路**：看一眼就知道
 *   - 抓到的画面是不是"当前屏幕"（而不是几十秒前的旧图）
 *   - 画面里有没有把自己的悬浮球截进去
 *   - 帧序号有没有在增长（会话是否还活着）
 * M3 接上 DeepSeek 之后，这里会被答案卡片取代。
 */
class FramePreviewOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    var onClosed: (() -> Unit)? = null

    private var root: View? = null

    fun show(bitmap: Bitmap, frameIndex: Long, fresh: Boolean, bubbleOnRight: Boolean) {
        detach()   // 注意：不能用 remove()，否则会触发 onClosed 清空宿主的引用

        val bounds = DisplayUtils.screenBounds(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = DisplayUtils.dp(context, 12)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 16f * DisplayUtils.density(context)
                setColor(CARD_COLOR)
            }
        }

        val info = TextView(context).apply {
            text = context.getString(
                if (fresh) R.string.preview_info_fresh else R.string.preview_info_stale,
                frameIndex,
                bitmap.width,
                bitmap.height,
            )
            textSize = 12f
            setTextColor(TEXT_COLOR)
        }

        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val close = Button(context).apply {
            text = context.getString(R.string.preview_close)
            setOnClickListener { remove() }
        }

        card.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(image, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        card.addView(close, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 宽度只占 72%，并停靠在悬浮球的**另一侧**：
        // 之前用 86% 居中，实测会盖住悬浮球，用户想"再抓一次"时点到的其实是卡片。
        val cardWidth = (bounds.width() * 0.72f).toInt()
        val cardHeight = (bounds.height() * 0.55f).toInt()
        val margin = DisplayUtils.dp(context, 12)

        val params = WindowManager.LayoutParams(
            cardWidth,
            cardHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (bubbleOnRight) Gravity.START else Gravity.END)
            x = margin
        }

        try {
            windowManager.addView(card, params)
            root = card
        } catch (t: Throwable) {
            onClosed?.invoke()
        }
    }

    /** 用户主动关闭：移除窗口并通知宿主清掉引用。 */
    fun remove() {
        detach()
        onClosed?.invoke()
    }

    /**
     * 只移除窗口，**不**通知宿主。
     *
     * show() 内部必须用这个而不是 remove()：remove() 会触发 onClosed 把宿主的 preview 引用置空，
     * 而宿主紧接着要赋新值，结果引用指向 null → 下一次抓帧就没法移除这个窗口，实测会每抓一次泄漏一个悬浮窗。
     */
    private fun detach() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        const val CARD_COLOR = 0xF5FFFFFF.toInt()
        const val TEXT_COLOR = 0xFF333333.toInt()
    }
}
