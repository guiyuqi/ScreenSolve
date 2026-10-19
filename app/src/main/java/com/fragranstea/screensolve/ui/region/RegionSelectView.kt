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
import kotlin.math.hypot

/**
 * 选区视图。
 *
 * 交互模型（M3 真机反馈后改成这版）：**先给一个预选框，再让用户拖四个角**。
 * 比"空白画布上重新拖一个框"自然得多 —— 用户多数时候只需要微调，而不是从头画。
 *
 * 手势约定：
 * - 拖角：调整该角（四角各自独立，命中判定给 32dp 的手指容差，比视觉半径大得多）
 * - 框内拖动：整体平移
 * - 框外拖动：重新画一个新框（保留旧的拖拽习惯）
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
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.brand)
    }

    private val density = resources.displayMetrics.density
    private val dstRect = RectF()

    /** 选区（视图坐标系）。 */
    private val selRect = RectF()
    private var hasSelection = false
    private var pendingDefaultSelection = false

    private var mode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    /** 手指命中半径：远大于绘制的圆点半径，避免"看起来点到了却拖不动"。 */
    private val touchRadius = 34f * density
    private val handleRadius = 9f * density
    private val minSize = 32f * density

    private enum class DragMode { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE, NEW }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        if (width > 0 && height > 0) {
            computeDstRect()
            applyDefaultSelection()
        } else {
            pendingDefaultSelection = true
        }
        notifySelection()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeDstRect()
        if (pendingDefaultSelection) {
            pendingDefaultSelection = false
            applyDefaultSelection()
            notifySelection()
        }
    }

    /**
     * 计算位图在视图里的目标矩形：**等比缩放居中**，多余部分留黑边。
     *
     * 之前是把位图直接铺满整个视图（dstRect = 视图矩形）：截屏时位图比例和屏幕一致所以看不出来，
     * 换成拍照（4:3）后画面被横向拉伸 —— 看着变形，框选手感也不对。
     */
    private fun computeDstRect() {
        val bmp = frame
        val w = width
        val h = height
        if (bmp == null || w <= 0 || h <= 0) {
            dstRect.set(0f, 0f, w.toFloat(), h.toFloat())
            return
        }
        val scale = minOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = (w - drawW) / 2f
        val top = (h - drawH) / 2f
        dstRect.set(left, top, left + drawW, top + drawH)
    }

    /**
     * 预选框：屏幕中间偏大的一块，用户在此基础上微调。
     * 底边留到 80% 而不是更下 —— 底部操作栏（约屏幕 13%）会盖住角落的拖拽点。
     */
    private fun applyDefaultSelection() {
        if (width <= 0 || height <= 0) return
        // 预选框落在**图片区域**内（有黑边时不能按整个视图算）
        val left = dstRect.left
        val top = dstRect.top
        val right = dstRect.right
        val bottom = dstRect.bottom
        val insetX = (right - left) * 0.04f
        val insetY = (bottom - top) * 0.08f
        selRect.set(left + insetX, top + insetY, right - insetX, bottom - insetY * 1.2f)
        hasSelection = true
    }

    /** 当前选区（位图坐标系）；不存在时返回 null。 */
    fun selectionInBitmap(): Rect? {
        if (!hasSelection) return null
        val bmp = frame ?: return null
        if (selRect.width() < 4f || selRect.height() < 4f) return null
        val drawW = (dstRect.right - dstRect.left).coerceAtLeast(1f)
        val drawH = (dstRect.bottom - dstRect.top).coerceAtLeast(1f)
        // 等比缩放，所以 x/y 用同一个比例；再减去黑边偏移
        val scaleX = bmp.width / drawW
        val scaleY = bmp.height / drawH
        return Rect(
            ((selRect.left - dstRect.left) * scaleX).toInt().coerceIn(0, bmp.width - 1),
            ((selRect.top - dstRect.top) * scaleY).toInt().coerceIn(0, bmp.height - 1),
            ((selRect.right - dstRect.left) * scaleX).toInt().coerceIn(1, bmp.width),
            ((selRect.bottom - dstRect.top) * scaleY).toInt().coerceIn(1, bmp.height),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = frame ?: return

        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)

        if (!hasSelection) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            return
        }

        // 只压暗选区之外的四个矩形，比 PorterDuff.CLEAR 更省事也更稳
        canvas.drawRect(0f, 0f, width.toFloat(), selRect.top, dimPaint)
        canvas.drawRect(0f, selRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, selRect.top, selRect.left, selRect.bottom, dimPaint)
        canvas.drawRect(selRect.right, selRect.top, width.toFloat(), selRect.bottom, dimPaint)

        canvas.drawRect(selRect, borderPaint)

        // 四角拖拽点
        for ((cx, cy) in corners()) {
            canvas.drawCircle(cx, cy, handleRadius, handleFillPaint)
            canvas.drawCircle(cx, cy, handleRadius, handleStrokePaint)
        }
    }

    private fun corners(): List<Pair<Float, Float>> = listOf(
        selRect.left to selRect.top,
        selRect.right to selRect.top,
        selRect.left to selRect.bottom,
        selRect.right to selRect.bottom,
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (frame == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                mode = resolveMode(event.x, event.y)
                if (mode == DragMode.NEW) {
                    // 从框外开始拖：清掉旧框，重新画
                    hasSelection = false
                    notifySelection()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    DragMode.NONE -> return false
                    DragMode.NEW -> {
                        val left = minOf(lastX, event.x).coerceIn(0f, width.toFloat())
                        val right = maxOf(lastX, event.x).coerceIn(0f, width.toFloat())
                        val top = minOf(lastY, event.y).coerceIn(0f, height.toFloat())
                        val bottom = maxOf(lastY, event.y).coerceIn(0f, height.toFloat())
                        if (right - left >= minSize && bottom - top >= minSize) {
                            selRect.set(left, top, right, bottom)
                            hasSelection = true
                        }
                    }

                    DragMode.MOVE -> {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val newLeft = (selRect.left + dx).coerceIn(0f, width - selRect.width())
                        val newTop = (selRect.top + dy).coerceIn(0f, height - selRect.height())
                        selRect.offsetTo(newLeft, newTop)
                        lastX = event.x
                        lastY = event.y
                    }

                    DragMode.TOP_LEFT -> {
                        selRect.left = event.x.coerceIn(0f, selRect.right - minSize)
                        selRect.top = event.y.coerceIn(0f, selRect.bottom - minSize)
                    }

                    DragMode.TOP_RIGHT -> {
                        selRect.right = event.x.coerceIn(selRect.left + minSize, width.toFloat())
                        selRect.top = event.y.coerceIn(0f, selRect.bottom - minSize)
                    }

                    DragMode.BOTTOM_LEFT -> {
                        selRect.left = event.x.coerceIn(0f, selRect.right - minSize)
                        selRect.bottom = event.y.coerceIn(selRect.top + minSize, height.toFloat())
                    }

                    DragMode.BOTTOM_RIGHT -> {
                        selRect.right = event.x.coerceIn(selRect.left + minSize, width.toFloat())
                        selRect.bottom = event.y.coerceIn(selRect.top + minSize, height.toFloat())
                    }
                }
                notifySelection()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 拖出来的新框太小，或者被拖成了零面积：恢复预选框，别让用户面对"什么都没有"
                if (!hasSelection || selRect.width() < minSize || selRect.height() < minSize) {
                    applyDefaultSelection()
                }
                mode = DragMode.NONE
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                notifySelection()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resolveMode(x: Float, y: Float): DragMode {
        if (!hasSelection) return DragMode.NEW
        val corners = listOf(
            DragMode.TOP_LEFT to (selRect.left to selRect.top),
            DragMode.TOP_RIGHT to (selRect.right to selRect.top),
            DragMode.BOTTOM_LEFT to (selRect.left to selRect.bottom),
            DragMode.BOTTOM_RIGHT to (selRect.right to selRect.bottom),
        )
        for ((cornerMode, point) in corners) {
            val distance = hypot((x - point.first).toDouble(), (y - point.second).toDouble())
            if (distance <= touchRadius) return cornerMode
        }
        return if (selRect.contains(x, y)) DragMode.MOVE else DragMode.NEW
    }

    private fun notifySelection() {
        onSelectionChanged?.invoke(selectionInBitmap())
    }

    private companion object {
        const val DIM_COLOR = 0x99000000.toInt()
    }
}
