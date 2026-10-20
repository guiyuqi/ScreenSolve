package com.fragranstea.screensolve.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.noties.markwon.Markwon

/**
 * 把"markdown + LaTeX 混排"的内容铺进一个 [LinearLayout]：
 * 普通文本仍是 [TextView]，**超宽的公式单独成块、放进可横向滚动的容器**（保持字号，不缩小）。
 *
 * 背景：一屏装不下的长公式，早期做法是"等比缩小到能塞下"，12 项的同余链会被缩到看不清。
 * DeepSeek App 那种做法是保持字号、让公式自己横向滑动。TextView 里塞不进可滚动子视图，
 * 所以这里把内容拆成「文本块 + 公式块」依次排列。
 *
 * 拆分规则：公式自然宽度 ≤ 可用宽度 × [SCROLL_THRESHOLD] → 保持内联（排版不变）；
 * 超过 → 该公式单独成一块（横滑），左右文字各自成段。
 */
object MathContent {

    private const val SCROLL_THRESHOLD = 0.92f

    /**
     * @param textSizePx 正文字号（px），也用来按同字号量公式自然宽度
     * @param color 文字/公式颜色
     * @param maxWidthPx 可用宽度（内容宽度，不含 padding）
     * @param lineSpacingDp 行距，和调用方原有观感保持一致
     * @param style 追加样式（字重、内边距等），会在文本块和兜底文本上调用
     */
    fun populate(
        container: LinearLayout,
        context: Context,
        markwon: Markwon,
        raw: String,
        textSizePx: Float,
        color: Int,
        maxWidthPx: Int,
        lineSpacingDp: Float,
        fillWidth: Boolean = true,
        style: (TextView) -> Unit = {},
    ) {
        container.removeAllViews()
        val text = raw.trim()
        if (text.isEmpty()) return

        val density = DisplayUtils.density(context)
        val chunks = split(context, text, textSizePx, color, maxWidthPx)

        for (chunk in chunks) {
            when (chunk) {
                is Chunk.Text -> {
                    if (chunk.markdown.isBlank()) continue
                    val view = TextView(context).apply {
                        layoutParams = if (fillWidth) {
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        } else {
                            // 气泡要"短文本收缩、长文本换行"，所以宽度不铺满
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).also { maxWidth = maxWidthPx }
                        }
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
                        setTextColor(color)
                        setLineSpacing(lineSpacingDp * density, 1f)
                        style(this)
                    }
                    MathTextRenderer.render(
                        markwon,
                        view,
                        chunk.markdown,
                        view.textSize,
                        view.currentTextColor,
                        maxWidthPx,
                    )
                    container.addView(view)
                }

                is Chunk.Formula -> {
                    container.addView(
                        formulaView(context, chunk.latex, textSizePx, color, maxWidthPx, style),
                    )
                }
            }
        }
    }

    /** 公式块：横向可滚动；只有真的超宽时才会滚动。 */
    private fun formulaView(
        context: Context,
        latex: String,
        textSizePx: Float,
        color: Int,
        maxWidthPx: Int,
        style: (TextView) -> Unit,
    ): View {
        val drawable = MathTextRenderer.drawableNaturalSize(latex, textSizePx, color)
        if (drawable == null) {
            // 画不出来就退回源码文本，别让用户看到空白
            return TextView(context).apply {
                text = MathTextRenderer.readableFallback(latex)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
                setTextColor(color)
                style(this)
            }
        }

        val bitmap = MathTextRenderer.toBitmap(drawable)
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_START
            layoutParams = ViewGroup.LayoutParams(
                drawable.intrinsicWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(image)
            // 宽度取"公式自然宽度"与"可用宽度"的较小者：装得下就和普通块一样宽，装不下才滚动
            layoutParams = LinearLayout.LayoutParams(
                minOf(drawable.intrinsicWidth, maxWidthPx).coerceAtLeast(1),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    // ---------------- 切分 ----------------

    private sealed interface Chunk {
        data class Text(val markdown: String) : Chunk
        data class Formula(val latex: String) : Chunk
    }

    private fun split(
        context: Context,
        raw: String,
        textSizePx: Float,
        color: Int,
        maxWidthPx: Int,
    ): List<Chunk> {
        val matches = MathTextRenderer.mathMatches(raw)
        if (matches.isEmpty() || maxWidthPx <= 0) return listOf(Chunk.Text(raw))

        val limit = maxWidthPx * SCROLL_THRESHOLD
        val out = ArrayList<Chunk>()
        var cursor = 0
        var found = false

        for (m in matches) {
            // 按真实字号量一次自然宽度（公式数量很少，这个开销可以接受）
            val width = MathTextRenderer.drawableNaturalSize(m.latex, textSizePx, color)?.intrinsicWidth ?: 0
            if (width <= limit) continue

            found = true
            if (MathTextRenderer.verboseLogging) {
                android.util.Log.i(
                    "MathContent",
                    "长公式改横滑: 宽=$width > 阈值=${limit.toInt()} latex=[${m.latex.take(80)}]",
                )
            }
            if (m.start > cursor) out.add(Chunk.Text(raw.substring(cursor, m.start)))
            out.add(Chunk.Formula(m.latex))
            cursor = m.end
        }

        if (!found) return listOf(Chunk.Text(raw))
        if (cursor < raw.length) out.add(Chunk.Text(raw.substring(cursor)))
        return out
    }
}
