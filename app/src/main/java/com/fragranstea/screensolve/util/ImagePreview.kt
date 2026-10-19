package com.fragranstea.screensolve.util

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * 全屏看题目原图（支持缩放）。
 *
 * 抽成公共函数的理由：历史会话页和答案卡片都要"点图看大图"，
 * 以前各写一份 Dialog，缩放一改就得改两处。
 */
object ImagePreview {

    /**
     * @param file 本地题目图（`filesDir/history/<id>.jpg`）
     */
    fun show(context: Context, file: File) {
        val bitmap: Bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        show(context, bitmap)
    }

    fun show(context: Context, bitmap: Bitmap) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        // 竖拍的照片在查看时常常是横的，给一个"转 90°"的按钮（连点四次转回原样）
        var rotation = 0
        val original = bitmap
        val zoom = ZoomImageView(context).apply {
            setBackgroundColor(Color.BLACK)
            setImageBitmap(original)
            onSingleTap = {
                // 放大状态下点一下不关闭：拖动看图时手指稍微一停就会触发单击，很容易误关
                if (!isZoomed) dialog.dismiss()
            }
        }
        zoom.post { zoom.resetMatrix() }

        val rotate = TextView(context).apply {
            text = "⟳"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            contentDescription = context.getString(com.fragranstea.screensolve.R.string.image_rotate)
            setOnClickListener {
                rotation = (rotation + 90) % 360
                zoom.setImageBitmap(rotateBitmap(original, rotation))
                zoom.post { zoom.resetMatrix() }
            }
        }

        val hint = TextView(context).apply {
            text = context.getString(com.fragranstea.screensolve.R.string.image_zoom_hint)
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }

        // 放大状态下单击不关闭，必须留一个明确的关闭出口，否则用户会觉得"退不出去"
        val close = TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dialog.dismiss() }
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(zoom, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                hint,
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                },
            )
            addView(
                close,
                FrameLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(context, 56)
                    marginEnd = dp(context, 16)
                },
            )
            addView(
                rotate,
                FrameLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(context, 112)
                    marginEnd = dp(context, 16)
                },
            )
        }
        dialog.setContentView(root)
        dialog.show()
    }

    /** 把原图旋转 [degrees]，返回新位图（调用方不要复用旧图，原图一直保留以便转回 0°） */
    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
}
