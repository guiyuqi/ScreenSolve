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
 * 端侧 OCR —— **full 版本**的实现（ML Kit 中文识别，模型内置在 APK 里）。
 *
 * bundled 版不依赖 Google Play 服务，可离线出字；代价是 APK 会多出约 13 MB
 * （10.6 MB 原生库 + 2.4 MB 模型），所以只在 full flavor 里编译进来。
 *
 * 阻塞调用，必须在工作线程执行（内部用 Tasks.await）。
 */
object OcrRecognizer {

    const val available: Boolean = true

    private const val TAG = "OcrRecognizer"

    /** 识别器创建成本不低，做成懒加载单例复用。 */
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** @return 识别出的纯文本；识别不到内容时返回 null。 */
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
