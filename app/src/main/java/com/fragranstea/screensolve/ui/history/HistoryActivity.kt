package com.fragranstea.screensolve.ui.history

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.data.HistoryEntry
import com.fragranstea.screensolve.data.HistoryStore
import com.fragranstea.screensolve.util.DisplayUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录页：最近 10 条，带题目缩略图。
 *
 * 用 ScrollView + 代码构建行，而不是 RecyclerView —— 只有 10 条，
 * 为了它引一个 androidx.recyclerview 依赖（还要连带 ViewHolder 样板）不划算。
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: HistoryStore
    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        store = HistoryStore(this)
        container = findViewById(R.id.historyContainer)
        emptyView = findViewById(R.id.tvHistoryEmpty)

        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.history_clear)
                .setMessage(R.string.history_clear_confirm)
                .setNegativeButton(R.string.history_cancel, null)
                .setPositiveButton(R.string.history_cancel_ok) { _, _ ->
                    store.clear()
                    render()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val entries = store.entries()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEach { container.addView(buildRow(it)) }
    }

    private fun buildRow(entry: HistoryEntry): View {
        val context = this
        val density = DisplayUtils.density(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            isClickable = true
            setOnClickListener { showDetail(entry) }
        }

        val thumb = ImageView(context).apply {
            val size = (88 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEEEEE.toInt())
            store.imageFile(entry)?.let { file ->
                // 缩略图按 1/4 采样解码，避免解码整张原图
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.absolutePath, options)?.let { setImageBitmap(it) }
            }
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, 0, 0, 0)
        }
        val title = TextView(context).apply {
            text = entry.question.ifBlank { entry.answer }.replace('\n', ' ')
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subtitle = TextView(context).apply {
            text = buildString {
                if (entry.subject.isNotBlank()) append(entry.subject).append(" · ")
                append(getString(R.string.history_answer_prefix, entry.answer.take(30)))
            }
            textSize = 13f
            setTextColor(0xFF1F6FEB.toInt())
            maxLines = 1
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val time = TextView(context).apply {
            text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.createdAt))
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        texts.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        texts.addView(subtitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        texts.addView(time, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        row.addView(thumb)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun showDetail(entry: HistoryEntry) {
        val body = buildString {
            if (entry.question.isNotBlank()) {
                appendLine(getString(R.string.answer_label_question))
                appendLine(entry.question)
                appendLine()
            }
            appendLine(getString(R.string.answer_label_answer))
            appendLine(entry.answer)
            if (entry.steps.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.answer_label_steps))
                entry.steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
            }
            if (entry.conclusion.isNotBlank()) {
                appendLine()
                append(entry.conclusion)
            }
        }
        val scroll = ScrollView(this).apply {
            addView(
                TextView(this@HistoryActivity).apply {
                    text = body
                    textSize = 14f
                    setPadding((20 * DisplayUtils.density(this@HistoryActivity)).toInt(), 16, 20, 16)
                    setLineSpacing(6f * DisplayUtils.density(this@HistoryActivity), 1f)
                },
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_detail)
            .setView(scroll)
            .setPositiveButton(R.string.history_cancel_ok, null)
            .show()
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
