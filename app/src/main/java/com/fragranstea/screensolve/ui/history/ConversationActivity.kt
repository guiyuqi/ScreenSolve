package com.fragranstea.screensolve.ui.history

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.data.HistoryEntry
import com.fragranstea.screensolve.data.HistoryStore
import com.fragranstea.screensolve.data.HistoryTurn
import com.fragranstea.screensolve.data.SecureKeyStore
import com.fragranstea.screensolve.data.SettingsStore
import com.fragranstea.screensolve.recognize.SolveEngine
import com.fragranstea.screensolve.recognize.SolveOutcome
import com.fragranstea.screensolve.recognize.SolveSession
import com.fragranstea.screensolve.util.DisplayUtils
import com.fragranstea.screensolve.util.ImagePreview
import com.fragranstea.screensolve.util.MathTextRenderer
import io.noties.markwon.Markwon

/**
 * 一条历史记录的**会话视图**：题目图 + 首轮问答 + 后续追问，并且能接着问。
 *
 * 为什么要有这个页面（而不是弹窗）：用户翻历史的目的基本就三件事 ——
 * 看当时框的是哪道题、再读一遍答案、接着问一句。弹窗三样都别扭
 * （看图会把弹窗顶掉，还没地方输入）。
 *
 * 继续追问的做法：历史里本来就存了本地题目图，用 [SolveEngine.newVisionSession] 重新开一个会话，
 * 再把历史里的问答回放进去，模型就能接上"这道题"的上下文。
 */
class ConversationActivity : AppCompatActivity() {

    private lateinit var store: HistoryStore
    private lateinit var settings: SettingsStore
    private lateinit var engine: SolveEngine

    private val markwon: Markwon by lazy { Markwon.create(this) }
    private val density by lazy { DisplayUtils.density(this) }

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private var thinkingView: TextView? = null

    private var entry: HistoryEntry? = null

    /** 懒创建：只有真的追问了才需要它（要读 Key、建消息数组） */
    private var session: SolveSession? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getLongExtra(EXTRA_ENTRY_ID, 0L)
        store = HistoryStore(this)
        settings = SettingsStore(this)
        engine = SolveEngine(settings, SecureKeyStore(this))

        entry = store.entry(id)
        if (entry == null) {
            Toast.makeText(this, R.string.history_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(buildLayout())
        renderConversation()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F7F7.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        bar.addView(
            Button(this).apply {
                text = getString(R.string.conversation_back)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        bar.addView(
            TextView(this).apply {
                text = getString(R.string.conversation_title)
                textSize = 16f
                setPadding(dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        bar.addView(
            Button(this).apply {
                text = getString(R.string.answer_copy)
                setOnClickListener { copyAll() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        input = EditText(this).apply {
            hint = getString(R.string.answer_follow_up_hint)
            textSize = 14f
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        sendButton = Button(this).apply {
            text = getString(R.string.answer_follow_up_send)
            setOnClickListener { submit() }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        inputRow.addView(
            sendButton,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) },
        )
        root.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return root
    }

    private fun renderConversation() {
        val entry = entry ?: return
        content.removeAllViews()

        // 题目图：点一下全屏看
        store.imageFile(entry)?.let { file ->
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = 2 },
            )
            if (bitmap != null) {
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    // 固定高度上限：题目图只是"当时框了哪道题"的凭证，
                    // 让它按原图比例铺满整屏会把下面的答案挤到很远的地方
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0xFFEEEEEE.toInt())
                    setOnClickListener { showFullImage() }
                }
                content.addView(
                    image,
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(180)).apply {
                        bottomMargin = dp(12)
                    },
                )
            }
        }

        addSection(R.string.answer_label_question, entry.question)
        addSection(R.string.answer_label_answer, entry.answer, bold = true)
        entry.steps.forEachIndexed { index, step ->
            addSection(
                if (index == 0) R.string.answer_label_steps else 0,
                getString(R.string.answer_step_item, index + 1, step),
            )
        }
        if (entry.conclusion.isNotBlank()) {
            addSection(0, entry.conclusion, muted = true)
        }

        if (entry.turns.isNotEmpty()) {
            content.addView(
                divider(),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(14); bottomMargin = dp(6)
                },
            )
        }
        entry.turns.forEach { addTurn(it) }
    }

    private fun addTurn(turn: HistoryTurn) {
        addBubble(turn.question, fromUser = true)
        addBubble(turn.answer, fromUser = false)
    }

    /**
     * 一轮追问的两条气泡。
     *
     * 按聊天习惯排：**我问的靠右**（浅蓝），**模型答的靠左**（白底）。
     * 这样翻历史时"谁说的"一眼就分得清，不用读小标题。
     */
    private fun addBubble(text: String, fromUser: Boolean) {
        if (text.isBlank()) return
        val maxBubbleWidth = (DisplayUtils.screenBounds(this).width() * 0.78f).toInt()

        val body = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            setLineSpacing(5f * density, 1f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = maxBubbleWidth - dp(26)   // 长文本自动换行，短文本保持气泡收缩
            isClickable = true
            isLongClickable = true
            setOnLongClickListener {
                copyText(text)
                true
            }
        }
        MathTextRenderer.render(
            markwon,
            body,
            text,
            body.textSize,
            body.currentTextColor,
            maxBubbleWidth - dp(28),
        )

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (fromUser) BUBBLE_USER_COLOR else BUBBLE_AI_COLOR)
                if (!fromUser) setStroke(dp(1), BUBBLE_AI_STROKE)
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        bubble.addView(body, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(bubble, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("screensolve", text))
        Toast.makeText(this, R.string.answer_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 加一段"小标题 + 正文"。
     * @param labelRes 0 表示不加标题
     */
    private fun addSection(labelRes: Int, text: String, bold: Boolean = false, muted: Boolean = false) {
        if (text.isBlank()) return
        if (labelRes != 0) {
            content.addView(
                TextView(this).apply {
                    setText(labelRes)
                    textSize = 11f
                    setTextColor(0xFF888888.toInt())
                    setPadding(0, dp(10), 0, dp(2))
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        val body = TextView(this).apply {
            textSize = if (bold) 19f else 15f
            setTextColor(if (muted) 0xFF555555.toInt() else 0xFF222222.toInt())
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            setLineSpacing(5f * density, 1f)
        }
        MathTextRenderer.render(
            markwon,
            body,
            text,
            body.textSize,
            body.currentTextColor,
            (DisplayUtils.screenBounds(this).width() - dp(40)),
        )
        content.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFFDDDDDD.toInt())
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    // ---------------- 追问 ----------------

    private fun submit() {
        if (busy) return
        val question = input.text?.toString()?.trim().orEmpty()
        if (question.isEmpty()) return
        val entry = entry ?: return

        val jpeg = store.imageFile(entry)?.readBytes()
        if (jpeg == null || jpeg.isEmpty()) {
            Toast.makeText(this, R.string.conversation_image_missing, Toast.LENGTH_LONG).show()
            return
        }

        input.setText("")
        setBusy(true)
        addBubble(question, fromUser = true)
        addThinking()
        scrollDown()

        Thread({
            val outcome = try {
                val active = session ?: buildSession(jpeg, entry).also { session = it }
                active.appendUserText(question)
                engine.followUp(active.messages) { text ->
                    // 思考过程只在视觉上留个动静，这里不做流式刷新（Activity 里没必要）
                    runOnUiThread { thinkingView?.text = getString(R.string.answer_thinking, text.takeLast(60)) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "追问异常", t)
                SolveOutcome.Failure(com.fragranstea.screensolve.recognize.SolveError.UNKNOWN, t.message)
            }

            runOnUiThread {
                setBusy(false)
                thinkingView?.let { content.removeView(it) }
                thinkingView = null
                when (outcome) {
                    is SolveOutcome.Success -> {
                        session?.appendAssistant(outcome.answer.raw)
                        store.appendTurn(entry.id, question, outcome.answer.toPlainText())
                        this@ConversationActivity.entry = store.entry(entry.id) ?: entry
                        renderConversation()
                        scrollDown()
                    }

                    is SolveOutcome.Failure -> {
                        Toast.makeText(
                            this,
                            getString(R.string.conversation_failed, outcome.error.name),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }, "screensolve-history-followup").start()
    }

    /**
     * 用历史里的图 + 已有问答重建会话，让模型接上上下文。
     * 首轮 assistant 用历史里存的答案文本（不是原始 JSON），足够对齐"在讲哪道题"。
     */
    private fun buildSession(jpeg: ByteArray, entry: HistoryEntry): SolveSession {
        val rebuilt = engine.newVisionSession(jpeg)
        rebuilt.appendAssistant(entry.toContextText())
        entry.turns.forEach { turn ->
            rebuilt.appendUserText(turn.question)
            rebuilt.appendAssistant(turn.answer)
        }
        return rebuilt
    }

    private fun HistoryEntry.toContextText(): String = buildString {
        if (question.isNotBlank()) appendLine("题目：$question")
        appendLine("答案：$answer")
        if (steps.isNotEmpty()) {
            appendLine("解析：")
            steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        }
    }.trim()

    private fun addThinking() {
        thinkingView = TextView(this).apply {
            text = getString(R.string.answer_state_loading)
            textSize = 14f
            setTextColor(0xFF1F6FEB.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(thinkingView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun setBusy(value: Boolean) {
        busy = value
        sendButton.isEnabled = !value
        input.isEnabled = !value
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyAll() {
        val entry = entry ?: return
        val text = buildString {
            appendLine(entry.toContextText())
            entry.turns.forEach { turn ->
                appendLine()
                appendLine("Q：${turn.question}")
                appendLine("A：${turn.answer}")
            }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("screensolve", text))
        Toast.makeText(this, R.string.answer_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showFullImage() {
        val file = entry?.let { store.imageFile(it) } ?: return
        ImagePreview.show(this, file)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        /** 打开会话视图时传历史记录 id */
        const val EXTRA_ENTRY_ID = "entry_id"

        /** 气泡配色：自己问的浅蓝靠右，模型答的白底靠左 */
        private const val BUBBLE_USER_COLOR = 0xFFDCEBFF.toInt()
        private const val BUBBLE_AI_COLOR = 0xFFFFFFFF.toInt()
        private const val BUBBLE_AI_STROKE = 0xFFE3E3E3.toInt()

        private const val TAG = "Conversation"
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
