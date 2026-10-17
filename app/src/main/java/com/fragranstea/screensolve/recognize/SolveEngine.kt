package com.fragranstea.screensolve.recognize

import android.util.Log
import com.fragranstea.screensolve.capture.ImagePipeline
import com.fragranstea.screensolve.data.SecureKeyStore
import com.fragranstea.screensolve.data.SettingsStore
import com.fragranstea.screensolve.network.DeepSeekClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 识题编排：读设置 → 读 Key → 拼请求 → 调接口 → 解析结果。
 *
 * 两条通道共用同一套"请求 → 解析"骨架，只有"用户消息里放什么"不同：
 * - [solveVision]：把裁剪后的题目图 base64 直传（默认，公式/图形题更准）
 * - [solveText]：把端侧 OCR 出的文本传过去（省 token、不发原图，弱网也能用）
 *
 * 阻塞执行，**必须放在工作线程**。
 */
class SolveEngine(
    private val settings: SettingsStore,
    private val keyStore: SecureKeyStore,
    private val client: DeepSeekClient = DeepSeekClient(),
) {

    /** 视觉直传：把裁剪后的题目图直接交给多模态模型。 */
    fun solveVision(jpeg: ByteArray): SolveOutcome = execute(
        systemPrompt = PromptBuilder.systemPrompt(settings.customPrompt),
        userContent = JSONArray().apply {
            put(textPart(PromptBuilder.userInstruction()))
            put(imagePart(ImagePipeline.toDataUrl(jpeg)))
        },
    )

    /**
     * OCR 通道：端侧识别出题目文本后交给文本模型。
     * OCR 文本会一并塞进 user 消息，模型据此作答；识别错字导致答错是这个通道的固有风险。
     */
    fun solveText(questionText: String): SolveOutcome = execute(
        systemPrompt = PromptBuilder.systemPromptForText(settings.customPrompt),
        userContent = JSONArray().apply {
            put(textPart(PromptBuilder.userInstructionForText(questionText)))
        },
    )

    private fun execute(systemPrompt: String, userContent: JSONArray): SolveOutcome {
        val apiKey = keyStore.load()
        if (apiKey.isNullOrBlank()) return SolveOutcome.Failure(SolveError.NO_API_KEY)

        val thinking = settings.deepThinking
        val body = JSONObject().apply {
            put("model", settings.modelName)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userContent)
                        },
                    )
                },
            )
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("thinking", JSONObject().apply { put("type", if (thinking) "enabled" else "disabled") })
            put("stream", false)
        }

        // 开思考模式会明显更慢，读超时要放宽
        val readTimeout = if (thinking) THINKING_TIMEOUT_MS else NORMAL_TIMEOUT_MS

        return try {
            val content = client.chatCompletion(apiKey, body, readTimeout)
            SolveOutcome.Success(AnswerParser.parse(content))
        } catch (e: DeepSeekClient.ApiException) {
            Log.w(TAG, "识题失败: ${e.error} ${e.message}")
            SolveOutcome.Failure(e.error, e.message)
        } catch (t: Throwable) {
            Log.e(TAG, "识题异常", t)
            SolveOutcome.Failure(SolveError.UNKNOWN, t.message)
        }
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
