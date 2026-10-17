package com.fragranstea.screensolve.recognize

import android.graphics.Bitmap
import android.util.Log

/**
 * 端侧 OCR —— **lite 版本**的占位实现。
 *
 * lite 不带 ML Kit（那会让 APK 从 ~2.5 MB 涨到 ~15 MB），所以这里只提供同签名 API：
 * `available = false`，调用方据此禁用设置页的 OCR 选项，业务代码无需分支。
 */
object OcrRecognizer {

    const val available: Boolean = false

    private const val TAG = "OcrRecognizer"

    fun recognize(bitmap: Bitmap, timeoutMs: Long = 20_000): String? {
        Log.w(TAG, "lite 版本不含端侧 OCR，需要请安装 full 版本")
        return null
    }
}
