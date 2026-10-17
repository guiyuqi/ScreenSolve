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
 * 阻塞执行，**必须放在工作线程**（见 OverlayService 里的处理）。
 * M4 的端侧 OCR 通道会在这里加一个分支：先 OCR 出文本，再把文本发文本模型。
 */
class SolveEngine(
    private val settings: SettingsStore,
    private val keyStore: SecureKeyStore,
    private val client: DeepSeekClient = DeepSeekClient(),
) {

    /** 视觉直传：把裁剪后的题目图直接交给多模态模型。 */
    fun solveVision(jpeg: ByteArray): SolveOutcome {
        val apiKey = keyStore.load()
        if (apiKey.isNullOrBlank()) return SolveOutcome.Failure(SolveError.NO_API_KEY)

        val thinking = settings.deepThinking
        val body = JSONObject().apply {
            put("model", settings.modelName)
            put("messages", JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", PromptBuilder.systemPrompt(settings.customPrompt))
                    },
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray().apply {
                                put(textPart(PromptBuilder.userInstruction()))
                                put(imagePart(ImagePipeline.toDataUrl(jpeg)))
                            },
                        )
                    },
                )
            })
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
