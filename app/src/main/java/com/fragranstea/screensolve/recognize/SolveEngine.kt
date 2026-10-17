package com.fragranstea.screensolve.recognize

import android.util.Log
import com.fragranstea.screensolve.capture.ImagePipeline
import com.fragranstea.screensolve.data.SecureKeyStore
import com.fragranstea.screensolve.data.SettingsStore
import com.fragranstea.screensolve.network.DeepSeekClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 识题编排：读设置 → 读 Key → 拼消息 → 调接口（流式）→ 解析结果。
 *
 * 两条通道共用同一套骨架，只有"用户消息里放什么"不同：
 * - [solveVision]：把裁剪后的题目图 base64 直传（默认，公式/图形题更准）
 * - [solveText]：把端侧 OCR 出的文本传过去（省 token、不发原图，弱网也能用）
 *
 * 流式的用法（见 ARCHITECTURE.md）：正文 JSON 不对外显示，只把 `reasoning_content`
 * 的增量回调出去做"正在思考"的实时提示；收完再一次性解析成结构化答案。
 *
 * 阻塞执行，**必须放在工作线程**。
 */
class SolveEngine(
    private val settings: SettingsStore,
    private val keyStore: SecureKeyStore,
    private val client: DeepSeekClient = DeepSeekClient(),
) {

    /** 视觉直传：把裁剪后的题目图直接交给多模态模型。 */
    fun solveVision(jpeg: ByteArray, onReasoning: (String) -> Unit = {}): SolveOutcome {
        val messages = JSONArray().apply {
            put(systemMessage(PromptBuilder.systemPrompt(settings.customPrompt)))
            put(
                userMessage(
                    JSONArray().apply {
                        put(textPart(PromptBuilder.userInstruction()))
                        put(imagePart(ImagePipeline.toDataUrl(jpeg)))
                    },
                ),
            )
        }
        return execute(messages, onReasoning)
    }

    /**
     * OCR 通道：端侧识别出题目文本后交给文本模型。
     * OCR 文本会一并塞进 user 消息，模型据此作答；识别错字导致答错是这个通道的固有风险。
     */
    fun solveText(questionText: String, onReasoning: (String) -> Unit = {}): SolveOutcome {
        val messages = JSONArray().apply {
            put(systemMessage(PromptBuilder.systemPromptForText(settings.customPrompt)))
            put(userMessage(JSONArray().apply { put(textPart(PromptBuilder.userInstructionForText(questionText))) }))
        }
        return execute(messages, onReasoning)
    }

    private fun execute(messages: JSONArray, onReasoning: (String) -> Unit): SolveOutcome {
        val apiKey = keyStore.load()
        if (apiKey.isNullOrBlank()) return SolveOutcome.Failure(SolveError.NO_API_KEY)

        val thinking = settings.deepThinking
        val body = JSONObject().apply {
            put("model", settings.modelName)
            put("messages", messages)
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("thinking", JSONObject().apply { put("type", if (thinking) "enabled" else "disabled") })
            put("stream", true)
        }

        // 开思考模式会明显更慢，读超时要放宽
        val readTimeout = if (thinking) THINKING_TIMEOUT_MS else NORMAL_TIMEOUT_MS

        return try {
            val content = client.chatCompletionStream(
                apiKey = apiKey,
                body = body,
                readTimeoutMs = readTimeout,
                onReasoning = onReasoning,
            )
            SolveOutcome.Success(AnswerParser.parse(content))
        } catch (e: DeepSeekClient.ApiException) {
            Log.w(TAG, "识题失败: ${e.error} ${e.message}")
            SolveOutcome.Failure(e.error, e.message)
        } catch (t: Throwable) {
            Log.e(TAG, "识题异常", t)
            SolveOutcome.Failure(SolveError.UNKNOWN, t.message)
        }
    }

    private fun systemMessage(text: String) = JSONObject().apply {
        put("role", "system")
        put("content", text)
    }

    private fun userMessage(content: JSONArray) = JSONObject().apply {
        put("role", "user")
        put("content", content)
    }

    private fun textPart(text: String) = JSONObject().apply {
        put("type", "text")
        put("text", text)
    }

    private fun imagePart(dataUrl: String) = JSONObject().apply {
        put("type", "image_url")
        put("image_url", JSONObject().apply { put("url", dataUrl) })
    }

    private companion object {
        const val TAG = "SolveEngine"
        const val NORMAL_TIMEOUT_MS = 90_000
        const val THINKING_TIMEOUT_MS = 180_000
    }
}
