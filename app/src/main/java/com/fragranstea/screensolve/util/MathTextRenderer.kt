package com.fragranstea.screensolve.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan
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
    private const val BLOCK_RATIO = 0.33f

    /** 公式在图上的占位符（U+FFFC 对象替换符，渲染时被 ImageSpan 顶掉） */
    private const val PLACEHOLDER = "\uFFFC"

    /** 独占一行的大公式后面允许被"吃掉"的标点（它们本来也挤不下） */
    private const val TRAILING_PUNCT = "，。、；：,.;:"

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
        //
        // 三条排版规则（都是真机上踩出来的）：
        // - 模型自己写了 $$..$$ / \[..\] 的公式：独占一段
        // - 行内公式宽过整行的 BLOCK_RATIO：也让它独占一行，否则 Android 会把它顶出控件右边界裁掉
        // - 独占一行时用 markdown 硬换行（行尾两个空格）而不是分段，
        //   否则紧跟的"，/。"会被顶成独立段落，中间出现一大块空白
        val latexes = ArrayList<String>()
        val drawables = ArrayList<Drawable?>()
        val builder = StringBuilder()
        var cursor = 0

        for (match in MATH.findAll(raw)) {
            builder.append(raw, cursor, match.range.first)

            val latex = (1..4)
                .map { match.groupValues[it] }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .trim()
            val drawable = buildMath(latex, textSizePx, color, maxWidthPx)
            latexes.add(latex)
            drawables.add(drawable)

            val explicitBlock = match.value.startsWith("$$") || match.value.startsWith("\\[")
            val wideInline = maxWidthPx > 0 && (drawable?.intrinsicWidth ?: 0) > maxWidthPx * BLOCK_RATIO
            var next = match.range.last + 1

            when {
                explicitBlock -> builder.append("\n\n$PLACEHOLDER\n\n")
                wideInline -> {
                    // 公式占满一整行了，紧跟的纯标点（，。、；：）挤不下会掉到下一行单独成行，
                    // 视觉上非常难看，这里直接并进公式行一起处理掉（不再单独渲染）
                    while (next < raw.length && raw[next] in TRAILING_PUNCT) next++
                    builder.append("  \n$PLACEHOLDER  \n")
                }

                else -> builder.append(PLACEHOLDER)
            }
            cursor = next
        }
        builder.append(raw, cursor, raw.length)
        val prepared = builder.toString()

        // 2) 整段 markdown 化（关键：不再分段）
        val out = SpannableStringBuilder(markwon.toMarkdown(prepared))

        // 3) 占位符 → 公式图片
        //
        // 占位符用 U+FFFC（对象替换符）而不是 "@@MATH0@@"：图片是画上去的，
        // 但底层文字还在 —— 用可读字符串会在无障碍朗读、uiautomator 里露出 "@@MATH0@@"。
        // 占位符按出现顺序对应之前的公式列表，所以顺序取下标即可。
        var searchFrom = 0
        latexes.forEachIndexed { index, latex ->
            val start = out.indexOf(PLACEHOLDER, searchFrom)
            if (start < 0) return@forEachIndexed
            searchFrom = start + 1

            // 注意：不能用 ArrayList.removeFirst() —— 那是 Java 21 的 SequencedCollection 方法，
            // 编译期没问题，Android 运行时直接 NoSuchMethodError（踩过一次）。
            val drawable = drawables[index]
            if (drawable == null) {
                out.replace(start, start + 1, latex)   // 画不出来就保留源码
            } else {
                out.setSpan(
                    BaselineMathSpan(drawable),
                    start,
                    start + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        view.text = out
    }

    /**
     * 模型偶尔会写出 `\dx` / `\dy` 这种"半个命令"（想写 `\,dx` 时手抖），
     * jlatexmath 遇到未知命令直接抛异常，公式就退化成源码。
     * 这种情况只用补一个反斜杠的反义：把 `\dX`（X 是单个字母）还原成 `dX`，
     * 别去动 `\dfrac`、`\delta` 这类合法长命令。
     */
    private val BAD_D_COMMAND = Regex("\\\\d([a-zA-Z])(?![a-zA-Z])")

    private fun sanitize(latex: String): String = latex.replace(BAD_D_COMMAND) { m -> "d" + m.groupValues[1] }

    /**
     * 公式用的 ReplacementSpan：**底边贴基线**，并且强制把行高撑到能装下图片。
     *
     * 为什么不用 `ImageSpan(ALIGN_BASELINE)`：系统那套只有在 `bounds.top < 0` 时才去扩
     * `FontMetrics`，而我们给 drawable 的 bounds 一直是 `(0,0,w,h)`，于是"行高不变、图片往上顶"，
     * 单行答案（比如答案就是 `2`）顶部会被父布局裁掉一截。这里直接在 getSize 里把
     * `fm.ascent/top` 压到 `-h`，行高必然够用；draw 时再从基线往上画。
     */
    private class BaselineMathSpan(private val drawable: Drawable) : ReplacementSpan() {

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (fm != null && height > 0) {
                // 图片比字体本身高时，用图片高度当上行空间，保证不被裁
                fm.ascent = minOf(fm.ascent, -height)
                fm.top = minOf(fm.top, -height)
                fm.descent = maxOf(fm.descent, 0)
                fm.bottom = maxOf(fm.bottom, 0)
            }
            return width
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            baseline: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (width <= 0 || height <= 0) return
            canvas.save()
            canvas.translate(x, (baseline - height).toFloat())
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildMath(latex: String, textSizePx: Float, color: Int, maxWidthPx: Int): Drawable? = try {
        val inner = JLatexMathDrawable.builder(sanitize(latex))
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
