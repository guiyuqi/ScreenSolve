package com.fragranstea.screensolve.ui.region

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.fragranstea.screensolve.R

/**
 * 选区视图：把抓到的整屏画面铺满，用户拖动手指框出题目区域。
 *
 * 为什么用"抓到的位图"当背景而不是透明窗口看真实屏幕：
 * 这样遮罩、边框、坐标换算全都在同一张图上做，不会出现"看到的"和"截到的"不一致。
 */
class RegionSelectView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 选区变化回调；null 表示当前没有有效选区。 */
    var onSelectionChanged: ((Rect?) -> Unit)? = null

    private var frame: Bitmap? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = DIM_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.brand)
    }

    private var selection: RectF? = null
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    /** 绘制目标矩形，预分配以避开 onDraw 里的对象分配。 */
    private val dstRect = Rect()

    /** 选区太小视为误触，不认为是有效选择。 */
    private val minSize = 24f * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dstRect.set(0, 0, w, h)
    }

    /** 纯自定义绘制视图，补上 performClick 以满足无障碍要求。 */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        selection = null
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    /** 当前选区（位图坐标系）；太小或不存在时返回 null。 */
    fun selectionInBitmap(): Rect? {
        val sel = selection ?: return null
        val bmp = frame ?: return null
        if (sel.width() < 4f || sel.height() < 4f) return null
        val scaleX = bmp.width.toFloat() / width
        val scaleY = bmp.height.toFloat() / height
        return Rect(
            (sel.left * scaleX).toInt().coerceIn(0, bmp.width - 1),
            (sel.top * scaleY).toInt().coerceIn(0, bmp.height - 1),
            (sel.right * scaleX).toInt().coerceIn(1, bmp.width),
            (sel.bottom * scaleY).toInt().coerceIn(1, bmp.height),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = frame ?: return

        // 位图铺满整个视图（抓帧分辨率与屏幕同比例，所以不会被拉伸变形）
        // dst 矩形预分配：onDraw 里 new Rect 会每次绘制都产生垃圾对象
        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)

        val sel = selection
        if (sel == null) {
            // 还没有选区：整屏轻微变暗，提示"这里可以拖"
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            return
        }

        // 只压暗选区之外的四个矩形，比 PorterDuff.CLEAR 更省事也更稳
        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, dimPaint)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dimPaint)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, dimPaint)

        canvas.drawRect(sel, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (frame == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = true
                selection = null
                onSelectionChanged?.invoke(null)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                selection = normalizedRect(startX, startY, event.x, event.y)
                onSelectionChanged?.invoke(selectionInBitmap())
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val current = selection
                if (current != null && (current.width() < minSize || current.height() < minSize)) {
                    // 太小当作误触，清空选区让用户重来
                    selection = null
                }
                // 没有形成有效选区就等同于一次点击（无障碍要求 onTouchEvent 里补 performClick）
                if (selection == null && event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                onSelectionChanged?.invoke(selectionInBitmap())
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun normalizedRect(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val left = minOf(x1, x2).coerceIn(0f, width.toFloat())
        val right = maxOf(x1, x2).coerceIn(0f, width.toFloat())
        val top = minOf(y1, y2).coerceIn(0f, height.toFloat())
        val bottom = maxOf(y1, y2).coerceIn(0f, height.toFloat())
        return RectF(left, top, right, bottom)
    }

    private companion object {
        const val DIM_COLOR = 0x99000000.toInt()
    }
}
