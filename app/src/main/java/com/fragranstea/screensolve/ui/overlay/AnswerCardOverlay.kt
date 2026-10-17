package com.fragranstea.screensolve.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.recognize.Answer
import com.fragranstea.screensolve.recognize.SolveError
import com.fragranstea.screensolve.util.DisplayUtils
import com.fragranstea.screensolve.util.MathTextRenderer
import io.noties.markwon.Markwon

/**
 * 答案卡片：贴底、接近满宽的可滚动面板。
 *
 * 排版原则（M3 真机反馈后重做的一版）：
 * - **答案要抢眼**：22sp 加粗单独一行，用户扫一眼就能拿到结论，而不是从大段文字里找。
 * - **题目要能核对**：13sp 灰色，和答案拉开层级，但必须存在 —— 识别错题时必须一眼看出来。
 * - **解析分条**：每条一个 TextView，条目之间有间距，比一整块文本好读得多。
 * - **贴底满宽**：之前 72% 宽会让"数学成绩/及格"这种半个词被折行，读起来很难受。
 *   代价是卡片会盖住悬浮球，所以显示卡片时把悬浮球藏起来，关闭时再恢复。
 */
class AnswerCardOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    var onClosed: (() -> Unit)? = null

    private var root: View? = null
    private var headerView: TextView? = null
    private var questionLabel: TextView? = null
    private var questionView: TextView? = null
    private var answerLabel: TextView? = null
    private var answerView: TextView? = null
    private var stepsLabel: TextView? = null
    private var stepsContainer: LinearLayout? = null
    private var footnoteView: TextView? = null
    private var copyButton: Button? = null
    private var current: Answer? = null

    // --- M5 多轮追问 ---
    private var followUpInput: EditText? = null
    private var followUpRow: View? = null
    var onFollowUp: ((String) -> Unit)? = null

    /**
     * Markwon 只负责 markdown；`$...$` 的公式由 [MathTextRenderer] 用 jlatexmath 画成 Drawable。
     *
     * 为什么不用 Markwon 的 ext-latex 插件：实测 4.6.2 上即便 `inlinesEnabled(true)` +
     * 显式注册 `MarkwonInlineParserPlugin`，它仍然不解析 `$...$`（不报错、静默当普通文本），
     * 排查成本高于自己实现。
     */
    private val markwon: Markwon by lazy { Markwon.create(context) }

    /** 窗口参数与高度调节状态（顶部锚点拖动时会直接改 params.height）。 */
    private var paramsRef: WindowManager.LayoutParams? = null
    private var handleDragStartY = 0f
    private var handleDragStartHeight = 0
    private var minHeightPx = 0
    private var maxHeightPx = 0

    fun showLoading() {
        ensureView()
        current = null
        headerView?.text = context.getString(R.string.answer_state_loading)
        showSections(question = false, answer = false, steps = false)
        footnoteView?.text = context.getString(R.string.answer_state_loading_hint)
        footnoteView?.visibility = View.VISIBLE
        copyButton?.isEnabled = false
        followUpRow?.visibility = View.GONE
    }

    /**
     * 思考过程实时更新（流式）。
     *
     * 只显示模型 `reasoning_content` 的尾部若干字 —— 这段内容会很长，
     * 全量塞进 TextView 既慢又没人看，用户只需要知道"它在动、在思考什么方向"。
     */
    fun showReasoning(text: String) {
        if (root == null) return
        val tail = text.takeLast(REASONING_TAIL_CHARS).trim()
        if (tail.isEmpty()) return
        footnoteView?.text = context.getString(R.string.answer_thinking, tail)
        footnoteView?.visibility = View.VISIBLE
    }

    /**
     * @param reply true = 这是多轮追问的回复，不是首轮识题结果。
     *   追问回复是"一段话"，用不着置信度标题、不需要回显题目，正文也不该用答案那种大字号。
     */
    fun showAnswer(answer: Answer, reply: Boolean = false) {
        ensureView()
        current = answer

        val asReply = reply || answer.plainText
        headerView?.text = when {
            asReply -> context.getString(R.string.answer_header_reply)
            answer.subject.isNotBlank() -> context.getString(
                R.string.answer_header_subject,
                answer.subject,
                (answer.confidence * 100).toInt(),
            )

            else -> context.getString(R.string.answer_header_plain)
        }

        render(questionView, answer.question)
        showSections(
            question = !asReply && answer.question.isNotBlank(),
            answer = true,
            steps = answer.steps.isNotEmpty(),
        )

        // 首轮"答案"要醒目；追问回复是一段解释，用正常偏大的字号
        answerLabel?.text = context.getString(
            if (asReply) R.string.answer_label_reply else R.string.answer_label_answer,
        )
        answerView?.textSize = if (asReply) 17f else 22f
        // 答案用粗体强调；追问回复是整段解释，粗体会变成一堵墙。
        // 注意：setTypeface(当前粗体实例, NORMAL) 在部分 ROM 上不会真的变细，
        // 直接换成 DEFAULT / DEFAULT_BOLD 才可靠。
        answerView?.typeface = if (asReply) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        render(answerView, answer.answer)

        stepsContainer?.removeAllViews()
        answer.steps.forEachIndexed { index, step ->
            val stepView = TextView(context).apply {
                textSize = 14.5f
                setTextColor(BODY_COLOR)
                setLineSpacing(6f * DisplayUtils.density(context), 1f)
                setPadding(0, DisplayUtils.dp(context, 6), 0, 0)
            }
            stepsContainer?.addView(
                stepView,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            render(stepView, context.getString(R.string.answer_step_item, index + 1, step))
        }

        val footnote = buildFootnote(answer)
        render(footnoteView, footnote)
        footnoteView?.visibility = if (footnote.isBlank()) View.GONE else View.VISIBLE

        copyButton?.isEnabled = true
        followUpRow?.visibility = View.VISIBLE
    }

    fun showError(error: SolveError, detail: String?) {
        ensureView()
        current = null
        headerView?.text = context.getString(R.string.answer_state_failed)
        showSections(question = false, answer = false, steps = false)
        footnoteView?.text = buildString {
            append(errorText(error))
            if (!detail.isNullOrBlank()) {
                appendLine()
                appendLine()
                append(context.getString(R.string.answer_error_detail, detail))
            }
        }
        footnoteView?.visibility = View.VISIBLE
        copyButton?.isEnabled = false
        followUpRow?.visibility = View.GONE
    }

    private fun buildFootnote(answer: Answer): String = buildString {
        if (answer.conclusion.isNotBlank()) {
            append(answer.conclusion.trim())
        }
        if (answer.knowledgePoints.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine()
            append(
                context.getString(
                    R.string.answer_body_knowledge,
                    answer.knowledgePoints.joinToString("、"),
                ),
            )
        }
        if (answer.lowConfidence) {
            if (isNotEmpty()) appendLine()
            appendLine()
            append(context.getString(R.string.answer_low_confidence))
        }
    }.trim()

    private fun showSections(question: Boolean, answer: Boolean, steps: Boolean) {
        questionLabel?.visibility = if (question) View.VISIBLE else View.GONE
        questionView?.visibility = if (question) View.VISIBLE else View.GONE
        answerLabel?.visibility = if (answer) View.VISIBLE else View.GONE
        answerView?.visibility = if (answer) View.VISIBLE else View.GONE
        stepsLabel?.visibility = if (steps) View.VISIBLE else View.GONE
        stepsContainer?.visibility = if (steps) View.VISIBLE else View.GONE
    }

    /**
     * 渲染 markdown/LaTeX 到 TextView。
     * 解析失败时退回纯文本 —— 公式画不出来也不能让用户看到空白。
     */
    private fun render(view: TextView?, markdown: String) {
        if (view == null) return
        val raw = markdown.trim()
        if (raw.isEmpty()) {
            view.text = ""
            return
        }
        try {
            // 公式宽度上限：TextView 已量过就用它的内容宽度，否则按屏宽估一个，
            // 避免超长公式画到卡片外面被裁掉。
            val contentWidth = view.width - view.paddingLeft - view.paddingRight
            val maxWidth = contentWidth.takeIf { it > 0 }
                ?: (DisplayUtils.screenBounds(context).width() * 0.78f).toInt()
            MathTextRenderer.render(markwon, view, raw, view.textSize, view.currentTextColor, maxWidth)
        } catch (t: Throwable) {
            Log.e("AnswerCard", "渲染失败，回退纯文本: ${raw.take(80)}", t)
            view.text = raw
        }
    }

    /**
     * 错误 → 可操作的话术。不要只印错误码，
     * 要让用户知道"下一步该干什么"（去设置填 Key / 等一会儿 / 检查网络）。
     */
    private fun errorText(error: SolveError): String = context.getString(
        when (error) {
            SolveError.NO_API_KEY -> R.string.error_no_api_key
            SolveError.INVALID_KEY -> R.string.error_invalid_key
            SolveError.NO_BALANCE -> R.string.error_no_balance
            SolveError.RATE_LIMIT -> R.string.error_rate_limit
            SolveError.NETWORK -> R.string.error_network
            SolveError.TIMEOUT -> R.string.error_timeout
            SolveError.EMPTY_RESPONSE -> R.string.error_empty_response
            SolveError.BAD_RESPONSE -> R.string.error_bad_response
            SolveError.REQUEST_TOO_LARGE -> R.string.error_request_too_large
            SolveError.UNKNOWN -> R.string.error_unknown
        },
    )

    /**
     * 追问输入法控制。
     *
     * 窗口从创建起就是可聚焦的（见 ensureView 里的 flags 说明），
     * 所以这里不再切换 flag，只负责"真的要弹/收键盘"：
     * 弹键盘必须等这一帧的 layout 走完再请求焦点，否则窗口还没拿到
     * 输入法焦点，showSoftInput 会静默失败。
     */
    private fun setImeEnabled(enabled: Boolean) {
        val view = root ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (enabled) {
            followUpInput?.let { input ->
                input.isFocusableInTouchMode = true
                input.post {
                    input.requestFocus()
                    input.setSelection(input.text.length)
                    imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        } else {
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            followUpInput?.clearFocus()
        }
    }

    private fun submitFollowUp() {
        val text = followUpInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        followUpInput?.setText("")
        setImeEnabled(false)
        onFollowUp?.invoke(text)
    }

    private fun ensureView() {
        if (root != null) return

        val bounds = DisplayUtils.screenBounds(context)
        val pad = DisplayUtils.dp(context, 18)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 18f * DisplayUtils.density(context)
                setColor(CARD_COLOR)
            }
        }

        headerView = TextView(context).apply {
            textSize = 14f
            setTextColor(HEADER_COLOR)
            setTypeface(typeface, Typeface.BOLD)
        }

        questionLabel = sectionLabel(R.string.answer_label_question)
        questionView = TextView(context).apply {
            textSize = 13.5f
            setTextColor(QUESTION_COLOR)
            setLineSpacing(5f * DisplayUtils.density(context), 1f)
        }

        answerLabel = sectionLabel(R.string.answer_label_answer)
        answerView = TextView(context).apply {
            textSize = 22f
            setTextColor(ANSWER_COLOR)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(4f * DisplayUtils.density(context), 1f)
        }

        stepsLabel = sectionLabel(R.string.answer_label_steps)
        stepsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        footnoteView = TextView(context).apply {
            textSize = 12.5f
            setTextColor(FOOTNOTE_COLOR)
            setLineSpacing(4f * DisplayUtils.density(context), 1f)
            setPadding(0, DisplayUtils.dp(context, 10), 0, 0)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(questionLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(questionView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(answerLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(answerView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(stepsLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(stepsContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(footnoteView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        copyButton = Button(context).apply {
            text = context.getString(R.string.answer_copy)
            isEnabled = false
            setOnClickListener { copyToClipboard() }
        }
        val closeButton = Button(context).apply {
            text = context.getString(R.string.answer_close)
            setOnClickListener { remove() }
        }

        val bottomRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bottomRow.addView(copyButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottomRow.addView(
            closeButton,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = DisplayUtils.dp(context, 12)
            },
        )

        // 顶部拖拽锚点：上下拉动改卡片高度，方便把框内答案和框外题目对照着看
        val handleBar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 3f * DisplayUtils.density(context)
                setColor(HANDLE_COLOR)
            }
        }
        val handleRow = FrameLayout(context).apply {
            addView(
                handleBar,
                FrameLayout.LayoutParams(
                    DisplayUtils.dp(context, 52),
                    DisplayUtils.dp(context, 5),
                    Gravity.CENTER_HORIZONTAL,
                ),
            )
            val verticalPad = DisplayUtils.dp(context, 10)
            setPadding(0, verticalPad, 0, verticalPad)
        }

        card.addView(handleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(headerView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(
            scroll,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                topMargin = DisplayUtils.dp(context, 10)
            },
        )
        card.addView(
            bottomRow,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = DisplayUtils.dp(context, 8)
            },
        )

        // 追问输入行：有答案后才显示，避免识题过程中误触
        val input = EditText(context).apply {
            hint = context.getString(R.string.answer_follow_up_hint)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setPadding(DisplayUtils.dp(context, 8), DisplayUtils.dp(context, 6), DisplayUtils.dp(context, 8), DisplayUtils.dp(context, 6))
            // 窗口本身可聚焦，点击后 EditText 自然获得焦点；
            // 这里再补一次 showSoftInput，兜住部分 ROM 不自动弹键盘的情况。
            setOnClickListener { setImeEnabled(true) }
        }
        val sendButton = Button(context).apply {
            text = context.getString(R.string.answer_follow_up_send)
            setOnClickListener { submitFollowUp() }
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(
                sendButton,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = DisplayUtils.dp(context, 8)
                },
            )
            visibility = View.GONE
        }
        followUpInput = input
        followUpRow = inputRow
        card.addView(
            inputRow,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = DisplayUtils.dp(context, 8)
            },
        )

        // 贴底、接近满宽：优先保证行宽，避免半个词就被折行
        // 卡片里有追问输入框，所以窗口本身必须可聚焦（不带 FLAG_NOT_FOCUSABLE），
        // 否则 EditText 永远拿不到输入法焦点。只要没有视图主动 requestFocus，
        // 窗口可聚焦也不会凭空调出键盘。
        // 保留 FLAG_NOT_TOUCH_MODAL：卡片外的触摸仍然落到下层应用。
        val params = WindowManager.LayoutParams(
            bounds.width() - DisplayUtils.dp(context, 24),
            (bounds.height() * 0.58f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = DisplayUtils.dp(context, 12)
            y = DisplayUtils.dp(context, 24)
            // 键盘弹出时把窗口让到键盘上方，不要盖住输入框
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }

        minHeightPx = (bounds.height() * 0.30f).toInt()
        maxHeightPx = (bounds.height() * 0.90f).toInt()

        try {
            windowManager.addView(card, params)
            root = card
            paramsRef = params
            handleRow.setOnTouchListener { _, event ->
                val live = paramsRef ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handleDragStartY = event.rawY
                        handleDragStartHeight = live.height
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // 卡片贴底，所以"向上拖"等于变高
                        val delta = (handleDragStartY - event.rawY).toInt()
                        live.height = (handleDragStartHeight + delta).coerceIn(minHeightPx, maxHeightPx)
                        runCatching { windowManager.updateViewLayout(card, live) }
                        true
                    }

                    else -> false
                }
            }
        } catch (t: Throwable) {
            onClosed?.invoke()
        }
    }

    private fun sectionLabel(resId: Int): TextView = TextView(context).apply {
        text = context.getString(resId)
        textSize = 11.5f
        setTextColor(LABEL_COLOR)
        setPadding(0, DisplayUtils.dp(context, 12), 0, DisplayUtils.dp(context, 2))
    }

    private fun copyToClipboard() {
        val answer = current ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ScreenSolve", answer.toPlainText()))
        Toast.makeText(context, R.string.answer_copied, Toast.LENGTH_SHORT).show()
    }

    fun remove() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        onClosed?.invoke()
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        const val CARD_COLOR = 0xFFFFFFFF.toInt()
        const val HEADER_COLOR = 0xFF1F6FEB.toInt()
        const val LABEL_COLOR = 0xFF8A8A8A.toInt()
        const val QUESTION_COLOR = 0xFF5C5C5C.toInt()
        const val ANSWER_COLOR = 0xFF111111.toInt()
        const val BODY_COLOR = 0xFF222222.toInt()
        const val FOOTNOTE_COLOR = 0xFF6B6B6B.toInt()
        const val HANDLE_COLOR = 0xFFC9C9C9.toInt()
        const val REASONING_TAIL_CHARS = 240
    }
}
