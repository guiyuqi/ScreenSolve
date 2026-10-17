package com.fragranstea.screensolve.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.recognize.Answer
import com.fragranstea.screensolve.recognize.AnswerTextFormatter
import com.fragranstea.screensolve.recognize.SolveError
import com.fragranstea.screensolve.util.DisplayUtils

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

    fun showLoading() {
        ensureView()
        current = null
        headerView?.text = context.getString(R.string.answer_state_loading)
        showSections(question = false, answer = false, steps = false)
        footnoteView?.text = context.getString(R.string.answer_state_loading_hint)
        footnoteView?.visibility = View.VISIBLE
        copyButton?.isEnabled = false
    }

    fun showAnswer(answer: Answer) {
        ensureView()
        current = answer

        headerView?.text = if (answer.subject.isNotBlank()) {
            context.getString(
                R.string.answer_header_subject,
                answer.subject,
                (answer.confidence * 100).toInt(),
            )
        } else {
            context.getString(R.string.answer_header_plain)
        }

        val question = AnswerTextFormatter.sanitize(answer.question)
        questionView?.text = question
        showSections(question = question.isNotBlank(), answer = true, steps = answer.steps.isNotEmpty())

        answerView?.text = AnswerTextFormatter.sanitize(answer.answer)

        stepsContainer?.removeAllViews()
        answer.steps.forEachIndexed { index, step ->
            stepsContainer?.addView(
                TextView(context).apply {
                    text = context.getString(
                        R.string.answer_step_item,
                        index + 1,
                        AnswerTextFormatter.sanitize(step),
                    )
                    textSize = 14.5f
                    setTextColor(BODY_COLOR)
                    setLineSpacing(6f * DisplayUtils.density(context), 1f)
                    setPadding(0, DisplayUtils.dp(context, 6), 0, 0)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }

        footnoteView?.text = buildFootnote(answer)
        footnoteView?.visibility =
            if (footnoteView?.text.isNullOrBlank()) View.GONE else View.VISIBLE

        copyButton?.isEnabled = true
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
    }

    private fun buildFootnote(answer: Answer): String = buildString {
        if (answer.conclusion.isNotBlank()) {
            append(AnswerTextFormatter.sanitize(answer.conclusion))
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

        // 贴底、接近满宽：优先保证行宽，避免半个词就被折行
        val params = WindowManager.LayoutParams(
            bounds.width() - DisplayUtils.dp(context, 24),
            (bounds.height() * 0.58f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = DisplayUtils.dp(context, 12)
            y = DisplayUtils.dp(context, 24)
        }

        try {
            windowManager.addView(card, params)
            root = card
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
        const val CARD_COLOR = 0xF9FFFFFF.toInt()
        const val HEADER_COLOR = 0xFF1F6FEB.toInt()
        const val LABEL_COLOR = 0xFF8A8A8A.toInt()
        const val QUESTION_COLOR = 0xFF5C5C5C.toInt()
        const val ANSWER_COLOR = 0xFF111111.toInt()
        const val BODY_COLOR = 0xFF222222.toInt()
        const val FOOTNOTE_COLOR = 0xFF6B6B6B.toInt()
    }
}
