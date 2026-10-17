package com.fragranstea.screensolve.recognize

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把模型输出解析成 [Answer]。
 *
 * 容错策略：模型偶尔会给 JSON 加上 ```json 包裹，所以解析前先剥掉代码围栏。
 * 但**只有当整段内容确实以 `{` 开头时**才尝试 JSON 解析 ——
 * 早先是"截取第一个 `{` 到最后一个 `}`"就硬解，遇到追问里那种纯 markdown 回复
 * （`\int_{0}^{1}` 里的花括号也算 `{`）必然抛异常，还在日志里刷一屏堆栈。
 * 解析不出来就**不报错**，把原文当回答展示 —— 白屏比展示原始文本更糟。
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
                Log.w(TAG, "JSON 解析失败，降级为纯文本: ${t.message}")
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
        plainText = true,
    )

    /**
     * 只在"整段就是一个 JSON 对象"时返回它。
     * JSON 允许前导 ```` ```json ```` 围栏，所以先剥围栏再判断首字符。
     */
    private fun extractJson(text: String): String? {
        var body = text
        if (body.startsWith("```")) {
            body = body.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val fenceEnd = body.lastIndexOf("```")
            if (fenceEnd >= 0) body = body.substring(0, fenceEnd)
            body = body.trim()
        }
        if (!body.startsWith("{")) return null
        val end = body.lastIndexOf('}')
        if (end <= 0) return null
        return body.substring(0, end + 1)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }
}
