package com.fragranstea.screensolve.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 把"整屏截图 + 选区"变成可以直接发给模型的图片数据。
 *
 * 尺寸与质量的取舍：长边压到 1600px、JPEG 85，一张题图大约 100~400 KB。
 * DeepSeek 的请求体上限是 48 MiB，完全不是瓶颈，压缩是为了上传速度和 token 成本。
 */
object ImagePipeline {

    /** 模型侧的最长边。题目文字在这个分辨率下足够清晰，再大只是浪费。 */
    const val MAX_LONG_SIDE = 1600
    const val JPEG_QUALITY = 85

    /**
     * 按选区裁剪。传入的 rect 必须是**位图坐标系**（不是屏幕 dp），并且会被 clamp 到图内，
     * 否则越界会让 createBitmap 抛 IllegalArgumentException。
     */
    fun crop(source: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    /** 等比缩放到长边不超过 [maxLongSide]；本来就够小则原样返回。 */
    fun scaleToMaxLongSide(source: Bitmap, maxLongSide: Int = MAX_LONG_SIDE): Bitmap {
        val longSide = maxOf(source.width, source.height)
        if (longSide <= maxLongSide) return source
        val ratio = maxLongSide.toFloat() / longSide
        val targetWidth = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    fun toJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** data URL 形式：DeepSeek 视觉接口支持 base64 内联，这是最简单的方式。 */
    fun toDataUrl(jpeg: ByteArray): String {
        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * 一步到位：裁剪 → 缩放 → JPEG。中间产生的临时位图会回收，避免连续搜题把内存吃满。
     */
    fun prepareForModel(frame: Bitmap, rect: Rect): ByteArray {
        val cropped = crop(frame, rect)
        val scaled = scaleToMaxLongSide(cropped)
        if (scaled !== cropped) cropped.recycle()
        return try {
            toJpeg(scaled)
        } finally {
            scaled.recycle()
        }
    }
}
