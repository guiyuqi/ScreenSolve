package com.fragranstea.screensolve.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
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
 *
 * 关键实现约束：**整段文本必须一次性交给 Markwon**。
 * 早期版本按 `$...$` 把文本切成若干段分别 markdown 化，结果 `**加粗 $x$ 加粗**`
 * 这种跨公式的强调标记被从中间切断，屏幕上就出现了字面量 `**`。
 * 现在的做法是：
 * 1. 先把数学片段换成不会和 markdown 冲突的占位 token（`@@MATH0@@`）
 * 2. 整段交给 Markwon 解析，markdown 结构完整
 * 3. 在得到的结果里找到 token 的位置，替换成公式图片 ImageSpan
 *
 * 支持的定界符：`$...$`、`$$...$$`、`\(...\)`、`\[...\]`（后者两种是模型爱用的写法）。
 * 失败时保留原始 LaTeX 源码，绝不显示空白。
 */
object MathTextRenderer {

    private const val TAG = "MathTextRenderer"

    /**
     * 行内公式宽到整行的这个比例以上，就改按块级（独占一段）排版。
     *
     * 0.4 是实测调出来的：Android 在 ReplacementSpan 跟在一段文字后面时，
     * 换行判断不可靠 —— 公式经常直接冲出控件右边界被裁掉，
     * 阈值放宽一点让大公式自己占一行，就不会撞上这个坑。
     */
    private const val BLOCK_RATIO = 0.4f

    /** 组 1=$$块级 2=\[块级 3=\(行内 4=$行内 */
    private val MATH = Regex(
        "\\$\\$([\\s\\S]+?)\\$\\$" +
            "|\\\\\\[([\\s\\S]+?)\\\\\\]" +
            "|\\\\\\(([\\s\\S]+?)\\\\\\)" +
            "|\\$([^$\\n]+?)\\$",
    )

    /**
     * @param textSizePx 公式字号（像素），一般传 `TextView.textSize`
     * @param color 公式颜色，一般传 `TextView.currentTextColor`
     * @param maxWidthPx 公式可用宽度上限（像素）；超过会被等比缩小，避免长公式被裁掉
     */
    fun render(
        markwon: Markwon,
        view: TextView,
        raw: String,
        textSizePx: Float,
        color: Int,
        maxWidthPx: Int = 0,
    ) {
        if (raw.isBlank()) {
            view.text = ""
            return
        }

        // 1) 抽出数学片段，正文留 token。
        //    排到"整行宽的 55% 以上"的行内公式也按块级处理：
        //    这种公式一旦跟在文字后面，Android 的换行常常把它顶出控件右边界被裁掉，
        //    顺势还会把紧跟的句号挤到下一行单独成行。让它独占一段就都解决了。
        val latexes = ArrayList<String>()
        val drawables = ArrayList<Drawable?>()
        val prepared = MATH.replace(raw) { match ->
            val latex = (1..4)
                .map { match.groupValues[it] }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .trim()
            val index = latexes.size
            val drawable = buildMath(latex, textSizePx, color, maxWidthPx)
            latexes.add(latex)
            drawables.add(drawable)

            val block = match.value.startsWith("$$") ||
                match.value.startsWith("\\[") ||
                (maxWidthPx > 0 && (drawable?.intrinsicWidth ?: 0) > maxWidthPx * BLOCK_RATIO)
            // 必须是两个换行：markdown 里单个 \n 是软换行（渲染成空格），
            // 公式会和后面的文字挤在同一行上。
            if (block) "\n\n@@MATH$index@@\n\n" else "@@MATH$index@@"
        }

        // 2) 整段 markdown 化（关键：不再分段）
        val out = SpannableStringBuilder(markwon.toMarkdown(prepared))

        // 3) token → 公式图片
        latexes.forEachIndexed { index, latex ->
            val token = "@@MATH$index@@"
            val start = out.indexOf(token)
            if (start < 0) return@forEachIndexed

            val drawable = drawables[index]
            if (drawable == null) {
                out.replace(start, start + token.length, latex)   // 画不出来就保留源码
            } else {
                out.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                    start,
                    start + token.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        view.text = out
    }

    private fun buildMath(latex: String, textSizePx: Float, color: Int, maxWidthPx: Int): Drawable? = try {
        val inner = JLatexMathDrawable.builder(latex)
            .textSize(textSizePx)
            .color(color)
            .padding(4)
            .build()
        FittedDrawable(inner, maxWidthPx)
    } catch (t: Throwable) {
        Log.w(TAG, "公式渲染失败，保留源码: ${latex.take(60)}", t)
        null
    }

    /**
     * 超宽公式等比缩小。
     *
     * jlatexmath 画出来的 Drawable 尺寸只由公式本身决定，不管 TextView 有多宽；
     * 一长条公式会画到控件外面被裁掉（界面上表现为公式右边突然断掉）。
     * ImageSpan 的排版尺寸取的是 intrinsicWidth，所以这里包一层，把 intrinsic 尺寸
     * 按可用宽度等比缩掉，draw 时再对 canvas 做同样的缩放。
     */
    private class FittedDrawable(
        private val inner: Drawable,
        maxWidthPx: Int,
    ) : Drawable() {

        private val scale: Float =
            if (maxWidthPx > 0 && inner.intrinsicWidth > maxWidthPx) {
                maxWidthPx.toFloat() / inner.intrinsicWidth
            } else {
                1f
            }

        init {
            // 必须自己把 bounds 撑开：ImageSpan(带 Drawable 的构造) 不会给 Drawable 设 bounds，
            // 而 DynamicDrawableSpan.getSize() 正是拿 bounds.right 当占位宽度 ——
            // 不设就是 0 宽，公式会被"缩成没有"，屏幕上只剩一段空白。
            setBounds(0, 0, scaledWidth(), scaledHeight())
        }

        private fun scaledWidth(): Int = (inner.intrinsicWidth * scale).toInt()

        private fun scaledHeight(): Int = (inner.intrinsicHeight * scale).toInt()

        override fun getIntrinsicWidth(): Int = scaledWidth()

        override fun getIntrinsicHeight(): Int = scaledHeight()

        override fun draw(canvas: Canvas) {
            val box = bounds
            val width = if (box.width() > 0) box.width() else scaledWidth()
            val height = if (box.height() > 0) box.height() else scaledHeight()
            if (width <= 0 || height <= 0) return

            // 实际缩放比按"最终排版宽度"算，兼容系统给 bounds 和没给 bounds 两种情况
            val drawScale = width.toFloat() / inner.intrinsicWidth
            canvas.save()
            canvas.translate(box.left.toFloat(), box.top.toFloat())
            canvas.scale(drawScale, drawScale)
            inner.setBounds(0, 0, inner.intrinsicWidth, inner.intrinsicHeight)
            inner.draw(canvas)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            inner.colorFilter = colorFilter
        }

        @Deprecated("Drawable.getOpacity is deprecated", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
