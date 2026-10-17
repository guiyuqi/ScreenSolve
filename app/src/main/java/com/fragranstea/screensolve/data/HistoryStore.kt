package com.fragranstea.screensolve.data

import android.content.Context
import android.util.Log
import com.fragranstea.screensolve.recognize.Answer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一轮追问。
 *
 * [answer] 存**展示用文本**（可能是一段 markdown）：从历史里继续聊的时候要把它回放给模型，
 * 展示文本足够表达上下文，没必要为了这一点把原始 JSON 也存下来。
 */
data class HistoryTurn(
    val question: String,
    val answer: String,
    val createdAt: Long,
)

/** 一条历史记录（题目图只存本地文件名，不上传任何地方）。 */
data class HistoryEntry(
    val id: Long,
    val createdAt: Long,
    val subject: String,
    val question: String,
    val answer: String,
    val steps: List<String>,
    val conclusion: String,
    val knowledgePoints: List<String>,
    val confidence: Double,
    val imageFileName: String?,
    /** M5：这张卡片的追问历史（首轮问答在其它字段里，这里只存追问） */
    val turns: List<HistoryTurn> = emptyList(),
)

/**
 * 历史记录：只保留最近 [MAX_ENTRIES] 条，题目图存在应用私有目录。
 *
 * 为什么连图片一起存：用户回来复查时，"当时框的是哪道题"是最关键的信息，
 * 只有文字的话遇到公式/图形题就失去意义了。图片**只落本地**（`filesDir/history/`），
 * 随应用卸载一起消失，不上传、不外发。
 */
class HistoryStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME)
    private val indexFile = File(dir, INDEX_FILE)

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 保存一次成功的识题；[jpeg] 为空则不存图。 */
    fun save(jpeg: ByteArray?, answer: Answer): HistoryEntry? {
        return try {
            val id = System.currentTimeMillis()
            var imageName: String? = null
            if (jpeg != null && jpeg.isNotEmpty()) {
                imageName = "$id.jpg"
                File(dir, imageName).writeBytes(jpeg)
            }

            val entry = HistoryEntry(
                id = id,
                createdAt = id,
                subject = answer.subject,
                question = answer.question,
                answer = answer.answer,
                steps = answer.steps,
                conclusion = answer.conclusion,
                knowledgePoints = answer.knowledgePoints,
                confidence = answer.confidence,
                imageFileName = imageName,
            )

            val entries = loadEntries().toMutableList()
            entries.add(0, entry)
            val kept = entries.take(MAX_ENTRIES)
            // 超出上限的旧记录连同图片一起删掉，避免悄悄涨满存储
            entries.drop(MAX_ENTRIES).forEach { dropImage(it) }
            writeIndex(kept)
            entry
        } catch (t: Throwable) {
            Log.w(TAG, "保存历史失败", t)
            null
        }
    }

    /**
     * M5：给某条记录追加一轮追问。
     *
     * 追问是不断累积的，所以每次都要重写整份索引；历史最多 10 条、每条约几 KB，
     * 这个代价可以接受（换成 SQLite 才划算）。
     */
    fun appendTurn(entryId: Long, question: String, answer: String): HistoryEntry? {
        return try {
            val entries = loadEntries().toMutableList()
            val index = entries.indexOfFirst { it.id == entryId }
            if (index < 0) return null
            val updated = entries[index].copy(
                turns = entries[index].turns + HistoryTurn(question, answer, System.currentTimeMillis()),
            )
            entries[index] = updated
            writeIndex(entries)
            updated
        } catch (t: Throwable) {
            Log.w(TAG, "追加追问失败", t)
            null
        }
    }

    fun entry(id: Long): HistoryEntry? = loadEntries().firstOrNull { it.id == id }

    /** 最近的历史，新的在前。 */
    fun entries(): List<HistoryEntry> = loadEntries()

    fun imageFile(entry: HistoryEntry): File? {
        val name = entry.imageFileName ?: return null
        val file = File(dir, name)
        return if (file.exists()) file else null
    }

    fun clear() {
        runCatching {
            loadEntries().forEach { dropImage(it) }
            indexFile.delete()
        }
    }

    private fun dropImage(entry: HistoryEntry) {
        val name = entry.imageFileName ?: return
        runCatching { File(dir, name).delete() }
    }

    private fun loadEntries(): List<HistoryEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parse)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取历史失败", t)
            emptyList()
        }
    }

    private fun writeIndex(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(toJson(it)) }
        indexFile.writeText(array.toString())
    }

    private fun parse(obj: JSONObject): HistoryEntry = HistoryEntry(
        id = obj.optLong("id"),
        createdAt = obj.optLong("createdAt"),
        subject = obj.optString("subject"),
        question = obj.optString("question"),
        answer = obj.optString("answer"),
        steps = obj.optJSONArray("steps").toStringList(),
        conclusion = obj.optString("conclusion"),
        knowledgePoints = obj.optJSONArray("knowledgePoints").toStringList(),
        confidence = obj.optDouble("confidence", 0.0),
        imageFileName = obj.optString("imageFileName").takeIf { it.isNotBlank() },
        turns = obj.optJSONArray("turns").toTurns(),
    )

    private fun toJson(entry: HistoryEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("createdAt", entry.createdAt)
        put("subject", entry.subject)
        put("question", entry.question)
        put("answer", entry.answer)
        put("steps", JSONArray(entry.steps))
        put("conclusion", entry.conclusion)
        put("knowledgePoints", JSONArray(entry.knowledgePoints))
        put("confidence", entry.confidence)
        put("imageFileName", entry.imageFileName.orEmpty())
        put("turns", JSONArray().apply {
            entry.turns.forEach { turn ->
                put(JSONObject().apply {
                    put("question", turn.question)
                    put("answer", turn.answer)
                    put("createdAt", turn.createdAt)
                })
            }
        })
    }

    private fun JSONArray?.toTurns(): List<HistoryTurn> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val obj = optJSONObject(index) ?: return@mapNotNull null
            HistoryTurn(
                question = obj.optString("question"),
                answer = obj.optString("answer"),
                createdAt = obj.optLong("createdAt"),
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    companion object {
        const val MAX_ENTRIES = 10
        private const val TAG = "HistoryStore"
        private const val DIR_NAME = "history"
        private const val INDEX_FILE = "index.json"
    }
}
