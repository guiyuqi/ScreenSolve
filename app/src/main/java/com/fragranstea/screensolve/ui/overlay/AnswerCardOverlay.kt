package com.fragranstea.screensolve.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
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
import com.fragranstea.screensolve.recognize.SolveError
import com.fragranstea.screensolve.util.DisplayUtils

/**
 * 答案卡片的悬浮窗。
 *
 * 一个窗口承载三种状态：识题中 / 失败 / 有答案。这样用户点完悬浮球之后
 * 立刻有反馈，不必等到结果回来才出现东西。
 *
 * 位置策略与预览卡片一致：停靠在悬浮球的另一侧、宽度只占 72%，避免盖住悬浮球。
 */
class AnswerCardOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val bubbleOnRight: Boolean,
) {

    var onClosed: (() -> Unit)? = null

    private var root: View? = null
    private var headerView: TextView? = null
    private var questionView: TextView? = null
    private var bodyView: TextView? = null
    private var copyButton: Button? = null
    private var current: Answer? = null

    fun showLoading() {
        ensureView()
        headerView?.text = context.getString(R.string.answer_state_loading)
        questionView?.visibility = View.GONE
        bodyView?.text = context.getString(R.string.answer_state_loading_hint)
        copyButton?.isEnabled = false
        current = null
    }

    fun showAnswer(answer: Answer) {
        ensureView()
        current = answer
        headerView?.text = if (answer.subject.isNotBlank()) {
            context.getString(R.string.answer_header_subject, answer.subject, (answer.confidence * 100).toInt())
        } else {
            context.getString(R.string.answer_header_plain)
        }
        if (answer.question.isNotBlank()) {
            questionView?.text = context.getString(R.string.answer_question, answer.question)
            questionView?.visibility = View.VISIBLE
        } else {
            questionView?.visibility = View.GONE
        }
        bodyView?.text = buildBody(answer)
        copyButton?.isEnabled = true
    }

    fun showError(error: SolveError, detail: String?) {
        ensureView()
        current = null
        headerView?.text = context.getString(R.string.answer_state_failed)
        questionView?.visibility = View.GONE
        bodyView?.text = buildString {
            append(errorText(error))
            if (!detail.isNullOrBlank()) {
                appendLine()
                appendLine()
                append(context.getString(R.string.answer_error_detail, detail))
            }
        }
        copyButton?.isEnabled = false
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

    private fun buildBody(answer: Answer): String = buildString {
        appendLine(context.getString(R.string.answer_body_answer, answer.answer))
        if (answer.steps.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.answer_body_steps))
            answer.steps.forEachIndexed { index, step ->
                appendLine("${index + 1}. $step")
            }
        }
        if (answer.conclusion.isNotBlank()) {
            appendLine()
            append(answer.conclusion)
        }
        if (answer.knowledgePoints.isNotEmpty()) {
            appendLine()
            appendLine()
            append(context.getString(R.string.answer_body_knowledge, answer.knowledgePoints.joinToString("、")))
        }
        if (answer.lowConfidence) {
            appendLine()
            appendLine()
            append(context.getString(R.string.answer_low_confidence))
        }
    }

    private fun ensureView() {
        if (root != null) return

        val bounds = DisplayUtils.screenBounds(context)
        val pad = DisplayUtils.dp(context, 16)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 16f * DisplayUtils.density(context)
                setColor(CARD_COLOR)
            }
        }

        headerView = TextView(context).apply {
            textSize = 15f
            setTextColor(HEADER_COLOR)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        questionView = TextView(context).apply {
            textSize = 13f
            setTextColor(QUESTION_COLOR)
            val p = DisplayUtils.dp(context, 6)
            setPadding(0, p, 0, p)
            visibility = View.GONE
        }
        bodyView = TextView(context).apply {
            textSize = 14f
            setTextColor(BODY_COLOR)
            setLineSpacing(4f * DisplayUtils.density(context), 1f)
        }

        val scroll = ScrollView(context).apply {
            addView(bodyView, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bottomRow.addView(copyButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottomRow.addView(
            closeButton,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = DisplayUtils.dp(context, 12)
            },
        )

        card.addView(headerView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(questionView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        card.addView(
            bottomRow,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = DisplayUtils.dp(context, 8)
            },
        )

        val params = WindowManager.LayoutParams(
            (bounds.width() * 0.72f).toInt(),
            (bounds.height() * 0.60f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (bubbleOnRight) Gravity.START else Gravity.END)
            x = DisplayUtils.dp(context, 12)
        }

        try {
            windowManager.addView(card, params)
            root = card
        } catch (t: Throwable) {
            onClosed?.invoke()
        }
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
        const val CARD_COLOR = 0xF7FFFFFF.toInt()
        const val HEADER_COLOR = 0xFF1F6FEB.toInt()
        const val QUESTION_COLOR = 0xFF666666.toInt()
        const val BODY_COLOR = 0xFF222222.toInt()
    }
}
