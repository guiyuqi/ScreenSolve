package com.fragranstea.screensolve.ui.history

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
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
import com.fragranstea.screensolve.util.MathTextRenderer
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录页：最近 10 条，带题目缩略图。
 *
 * 用 ScrollView + 代码构建行，而不是 RecyclerView —— 只有 10 条，
 * 为它引一个 androidx.recyclerview 依赖（还要连带 ViewHolder 样板）不划算。
 *
 * 两个细节：
 * - 缩略图点击 → 全屏看原图（题目图存本地就是给人复查的，看不到等于没存）
 * - 详情用 [MathTextRenderer] 渲染，公式和历史里其它地方保持一致
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: HistoryStore
    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView

    private val markwon: Markwon by lazy { Markwon.create(this) }

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
        val density = DisplayUtils.density(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        }

        val thumb = ImageView(this).apply {
            val size = (88 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEEEEE.toInt())
            store.imageFile(entry)?.let { file ->
                // 缩略图按 1/4 采样解码，避免解码整张原图
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.absolutePath, options)?.let { setImageBitmap(it) }
            }
            if (store.imageFile(entry) != null) {
                setOnClickListener { showImage(entry) }
            }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, 0, 0, 0)
            setOnClickListener { showDetail(entry) }
        }
        val title = TextView(this).apply {
            text = plain(entry.question.ifBlank { entry.answer })
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = TextView(this).apply {
            text = buildString {
                if (entry.subject.isNotBlank()) append(entry.subject).append(" · ")
                append(getString(R.string.history_answer_prefix, plain(entry.answer).take(30)))
            }
            textSize = 13f
            setTextColor(0xFF1F6FEB.toInt())
            maxLines = 1
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val time = TextView(this).apply {
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
        row.setOnClickListener { showDetail(entry) }
        return row
    }

    /** 列表里用的是截断后的纯文本，先把 LaTeX 去掉，免得显示成一堆反斜杠。 */
    private fun plain(text: String): String = text
        .replace(Regex("\\$\\$?"), "")
        .replace(Regex("\\\\[a-zA-Z]+"), "")
        .replace(Regex("[{}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

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

        val pad = (20 * DisplayUtils.density(this)).toInt()
        val detailView = TextView(this).apply {
            textSize = 14f
            setPadding(pad, 16, pad, 16)
            setLineSpacing(6f * DisplayUtils.density(this@HistoryActivity), 1f)
        }
        MathTextRenderer.render(
            markwon,
            detailView,
            body,
            detailView.textSize,
            detailView.currentTextColor,
            (DisplayUtils.screenBounds(this).width() * 0.78f).toInt(),
        )

        val scroll = ScrollView(this).apply {
            addView(detailView, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.history_detail)
            .setView(scroll)
            .setPositiveButton(R.string.history_cancel_ok, null)
        // 缩略图点击会被整行点击吃掉，所以把"看图"做成弹窗里的显式按钮，命中率 100%
        if (store.imageFile(entry) != null) {
            builder.setNeutralButton(R.string.history_view_image) { _, _ -> showImage(entry) }
        }
        builder.show()
    }

    /** 全屏看原图：点任意处关闭。 */
    private fun showImage(entry: HistoryEntry) {
        val file = store.imageFile(entry) ?: return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(imageView)
        dialog.show()
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
