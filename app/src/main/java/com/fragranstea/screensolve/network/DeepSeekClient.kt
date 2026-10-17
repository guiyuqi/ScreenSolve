package com.fragranstea.screensolve.network

import android.util.Log
import com.fragranstea.screensolve.recognize.SolveError
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * DeepSeek Chat Completions 的最小客户端。
 *
 * 故意用 [HttpURLConnection] 而不是 OkHttp：M3 只需要一个同步 POST，
 * 而 OkHttp 5 的超时 API（java.time / kotlin.time Duration）存在版本差异，多一个依赖多一个坑。
 * 需要流式时再加，或者用同一个 InputStream 逐行读 SSE 也能实现。
 */
class DeepSeekClient {

    class ApiException(val error: SolveError, message: String) : Exception(message)

    /**
     * 发送请求并返回 `choices[0].message.content`。
     *
     * 阻塞调用，**必须在工作线程执行**。
     */
    fun chatCompletion(apiKey: String, body: JSONObject, readTimeoutMs: Int): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val payload = body.toString().toByteArray(Charsets.UTF_8)
        try {
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw ApiException(mapStatus(status), extractMessage(detail) ?: "HTTP $status")
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            return extractContent(responseText)
        } catch (e: SocketTimeoutException) {
            throw ApiException(SolveError.TIMEOUT, e.message ?: "读取超时")
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(SolveError.NETWORK, e.message ?: "网络错误")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 发送流式请求（SSE），返回拼好的 `content` 全文。
     *
     * 设计取舍：我们仍然要求模型输出 JSON，所以**流式内容对用户没意义**（会是一堆
     * `{"answer":"\frac...` 的碎片）。因此：
     * - `reasoning_content` 的增量交给 [onReasoning]，用于显示"正在思考"的实时文本
     * - `content` 只累加、不外显，收完后一次性交给解析层渲染成公式
     *
     * 阻塞调用，**必须在工作线程执行**。
     */
    fun chatCompletionStream(
        apiKey: String,
        body: JSONObject,
        readTimeoutMs: Int,
        onReasoning: (String) -> Unit = {},
        onContent: (String) -> Unit = {},
    ): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val payload = body.toString().toByteArray(Charsets.UTF_8)
        try {
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw ApiException(mapStatus(status), extractMessage(detail) ?: "HTTP $status")
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data:")) {
                        val payloadLine = line.removePrefix("data:").trim()
                        if (payloadLine == "[DONE]") break
                        if (payloadLine.isNotEmpty()) {
                            runCatching {
                                val delta = JSONObject(payloadLine)
                                    .optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("delta")
                                val reasoningDelta = delta?.optString("reasoning_content").orEmpty()
                                if (reasoningDelta.isNotEmpty()) {
                                    reasoning.append(reasoningDelta)
                                    onReasoning(reasoning.toString())
                                }
                                val contentDelta = delta?.optString("content").orEmpty()
                                if (contentDelta.isNotEmpty()) {
                                    content.append(contentDelta)
                                    onContent(content.toString())
                                }
                            }.onFailure { Log.w(TAG, "解析流式分片失败: ${payloadLine.take(80)}", it) }
                        }
                    }
                    line = reader.readLine()
                }
            }

            if (content.isBlank()) {
                throw ApiException(SolveError.EMPTY_RESPONSE, "流式响应没有 content")
            }
            return content.toString()
        } catch (e: SocketTimeoutException) {
            throw ApiException(SolveError.TIMEOUT, e.message ?: "读取超时")
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(SolveError.NETWORK, e.message ?: "网络错误")
        } finally {
            connection.disconnect()
        }
    }

    private fun extractContent(responseText: String): String {
        val root = try {
            JSONObject(responseText)
        } catch (t: Throwable) {
            Log.w(TAG, "响应不是 JSON", t)
            throw ApiException(SolveError.BAD_RESPONSE, "响应不是 JSON")
        }
        val choices = root.optJSONArray("choices")
            ?: throw ApiException(SolveError.BAD_RESPONSE, "缺少 choices")
        if (choices.length() == 0) throw ApiException(SolveError.EMPTY_RESPONSE, "choices 为空")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        if (content.isBlank()) throw ApiException(SolveError.EMPTY_RESPONSE, "content 为空")
        return content
    }

    private fun extractMessage(errorBody: String?): String? = try {
        errorBody?.let { JSONObject(it).optJSONObject("error")?.optString("message") }
    } catch (t: Throwable) {
        null
    }

    private fun mapStatus(status: Int): SolveError = when (status) {
        401, 403 -> SolveError.INVALID_KEY
        402 -> SolveError.NO_BALANCE
        413 -> SolveError.REQUEST_TOO_LARGE
        429 -> SolveError.RATE_LIMIT
        else -> SolveError.UNKNOWN
    }

    private companion object {
        const val TAG = "DeepSeekClient"
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}
