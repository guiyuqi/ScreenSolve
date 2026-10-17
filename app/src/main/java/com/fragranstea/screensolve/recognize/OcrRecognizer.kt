package com.fragranstea.screensolve.recognize

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 端侧 OCR（ML Kit 中文识别，模型内置在 APK 里，**不依赖 Google Play 服务**，可离线出字）。
 *
 * 为什么用 bundled 版而不是 play-services 精简版：国内真机普遍没有 GMS，
 * 精简版会在需要下载模型时失败。代价是 APK 大概多 2 MB（实测 AAR 约 1.9 MB）。
 * 中文识别器同时支持拉丁字母，中英混排的题目没问题。
 *
 * 阻塞调用，必须在工作线程执行（内部用 Tasks.await）。
 */
object OcrRecognizer {

    private const val TAG = "OcrRecognizer"

    /** 识别器创建成本不低，做成懒加载单例复用。 */
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * @return 识别出的纯文本；识别不到内容时返回 null。
     */
    fun recognize(bitmap: Bitmap, timeoutMs: Long = 20_000): String? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image), timeoutMs, TimeUnit.MILLISECONDS)
            val text = result.text.trim()
            Log.i(TAG, "OCR 得到 ${text.length} 字：${text.take(120)}")
            text.ifBlank { null }
        } catch (t: Throwable) {
            Log.w(TAG, "OCR 失败", t)
            null
        }
    }
}
