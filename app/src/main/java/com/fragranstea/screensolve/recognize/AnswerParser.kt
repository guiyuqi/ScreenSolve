package com.fragranstea.screensolve.recognize

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把模型输出解析成 [Answer]。
 *
 * 容错策略：模型偶尔会加上 ```json 包裹或前后缀说明，所以先尝试截取第一个 `{` 到最后一个 `}`；
 * 实在解析不出来就**不报错**，把原文当作答案展示 —— 白屏或报错比展示原始文本更糟。
 */
object AnswerParser {

    private const val TAG = "AnswerParser"

    fun parse(content: String): Answer {
        val text = content.trim()
        val json = extractJson(text)
        if (json != null) {
            try {
                val obj = JSONObject(json)
                return Answer(
                    subject = obj.optString("subject"),
                    question = obj.optString("question"),
                    answer = obj.optString("answer").ifBlank { "（模型未给出答案）" },
                    steps = obj.optJSONArray("steps").toStringList(),
                    conclusion = obj.optString("conclusion"),
                    knowledgePoints = obj.optJSONArray("knowledge_points").toStringList(),
                    confidence = obj.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it },
                    raw = text,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "解析 JSON 失败，降级为纯文本", t)
            }
        }
        return fallback(text)
    }

    /** 解析失败时的兜底：整段原文当答案，保证用户至少能看到内容。 */
    private fun fallback(text: String): Answer = Answer(
        subject = "",
        question = "",
        answer = text.ifBlank { "（模型没有返回内容）" },
        steps = emptyList(),
        conclusion = "",
        knowledgePoints = emptyList(),
        confidence = 0.0,
        raw = text,
    )

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }
}
