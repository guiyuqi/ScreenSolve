package com.fragranstea.screensolve.util

import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.Log
import android.widget.TextView
import io.noties.markwon.Markwon
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 把"markdown + LaTeX 混排"的模型输出渲染进 TextView。
 *
 * 为什么不用 Markwon 的 ext-latex 插件：实测它在 4.6.2 上即便 `inlinesEnabled(true)`
 * 也不会解析 `$...$`（不报错、不渲染，直接当普通文本），排查成本高于自己实现。
 * 这里的做法更直接：
 * - 文本片段交给 Markwon 处理 markdown（加粗、列表、行内代码都正常）
 * - `$...$` / `$$...$$` 里的公式交给 jlatexmath 画成 Drawable，再用 ImageSpan 插进文本
 *
 * 好处是渲染完全可控、失败时能回退成原始 LaTeX 而不是空白。
 */
object MathTextRenderer {

    private const val TAG = "MathTextRenderer"

    /** 先匹配 `$$...$$`（块级），再匹配 `$...$`（行内）；行内不允许跨行。 */
    private val MATH = Regex("\\$\\$([\\s\\S]+?)\\$\\$|\\$([^$\\n]+?)\\$")

    /**
     * @param textSizePx 公式字号（像素），一般传 `TextView.textSize`
     * @param color 公式颜色，一般传 `TextView.currentTextColor`
     */
    fun render(
        markwon: Markwon,
        view: TextView,
        raw: String,
        textSizePx: Float,
        color: Int,
    ) {
        val out = SpannableStringBuilder()
        var cursor = 0

        for (match in MATH.findAll(raw)) {
            val textPart = raw.substring(cursor, match.range.first)
            if (textPart.isNotEmpty()) out.append(markwon.toMarkdown(textPart))

            val latex = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            val drawable = buildMath(latex, textSizePx, color)
            if (drawable != null) {
                val start = out.length
                out.append('\uFFFC')   // 占位字符，ImageSpan 会替换它的绘制
                out.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                    start,
                    out.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                // 公式画不出来就保留源码，至少信息不丢
                out.append(match.value)
            }
            cursor = match.range.last + 1
        }

        if (cursor < raw.length) {
            out.append(markwon.toMarkdown(raw.substring(cursor)))
        }

        view.text = out
    }

    private fun buildMath(latex: String, textSizePx: Float, color: Int): Drawable? = try {
        JLatexMathDrawable.builder(latex)
            .textSize(textSizePx)
            .color(color)
            .padding(4)
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "公式渲染失败，保留源码: ${latex.take(60)}", t)
        null
    }
}
