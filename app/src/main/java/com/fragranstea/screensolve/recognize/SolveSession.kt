package com.fragranstea.screensolve.recognize

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次识题会话（M5 多轮追问用）。
 *
 * 直接持有 **API 所需的 messages 数组**，首轮之后每次追问都往里追加：
 * system → user(首轮图/OCR文本) → assistant(模型原始 JSON) → user(追问) → assistant → …
 *
 * 关键点：assistant 那一轮保存**模型原始输出的 JSON 字符串**而不是解析后的对象，
 * 下一轮原样回传，模型才能延续"同一道题、同一份结构"的上下文；
 * 如果换成我们重新拼的文本，多轮之间就会出现格式漂移。
 */
class SolveSession(
    systemPrompt: String,
    firstUserContent: JSONArray,
) {

    val messages: JSONArray = JSONArray().apply {
        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        put(JSONObject().apply { put("role", "user"); put("content", firstUserContent) })
    }

    fun appendUserText(text: String) {
        messages.put(
            JSONObject().apply {
                put("role", "user")
                put(
                    "content",
                    JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", text) })
                    },
                )
            },
        )
        trim()
    }

    fun appendAssistant(rawContent: String) {
        messages.put(
            JSONObject().apply {
                put("role", "assistant")
                put("content", rawContent)
            },
        )
    }

    /**
     * 控制上下文长度：**首轮三条（system / 首轮 user / 首轮 assistant）必须保留**，
     * 否则模型会失去"在解哪道题"的锚点。之后最多再留 [MAX_FOLLOW_UP_TURNS] * 2 条。
     */
    private fun trim() {
        val fixedPrefix = 3   // system + user(图) + assistant(首次答案)
        val maxFollowUpMessages = MAX_FOLLOW_UP_TURNS * 2
        while (messages.length() > fixedPrefix + maxFollowUpMessages) {
            // 删掉最早的一条追问（索引 fixedPrefix 处），保持首轮不动
            messages.remove(fixedPrefix)
        }
    }

    private companion object {
        const val MAX_FOLLOW_UP_TURNS = 5
    }
}
