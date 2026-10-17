package com.fragranstea.screensolve.util

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 支持双指缩放 / 双击缩放 / 放大后拖动查看的全屏图片。
 *
 * 为什么自己写：为了一个"看一眼题目原图"的功能引 photoview（外加它的依赖）不值得，
 * 而系统 ImageView 的 FIT_CENTER 不能缩放 —— 题目图里的角标、上下标常常小到看不清。
 *
 * 手势约定（和相册一致）：
 * - 双指捏合：以两指中点为锚点缩放，范围 [1x, 5x]
 * - 放大状态下单指拖动：平移，边界外松手会弹回
 * - 双击：在 1x 与 2.5x 之间切换
 * - 单击：交给外面（全屏看图的场景就是关闭）
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val baseRect = RectF()
    private val viewRect = RectF()

    private var minScale = 1f
    private var maxScale = MAX_SCALE
    private var currentScale = 1f

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    /** 单击（未缩放状态下也算）回调，用于"点一下关闭" */
    var onSingleTap: (() -> Unit)? = null

    /** 当前是否处于放大状态（放大后单击就不该关闭了，否则误触很容易关掉） */
    val isZoomed: Boolean get() = currentScale > minScale * 1.05f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val target = (currentScale * factor).coerceIn(minScale, maxScale)
                val applied = target / currentScale
                if (applied == 1f) return true
                currentScale = target
                matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = matrix
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale > minScale * 1.05f) minScale else DOUBLE_TAP_SCALE
                zoomTo(target, e.x, e.y)
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    /** 图片设置完成后调用，重新计算初始矩阵（FIT_CENTER 居中铺满）。 */
    fun resetMatrix() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || iw <= 0f || ih <= 0f) return

        val fitScale = min(vw / iw, vh / ih)
        minScale = fitScale
        maxScale = fitScale * MAX_SCALE
        currentScale = fitScale

        matrix.reset()
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate((vw - iw * fitScale) / 2f, (vh - ih * fitScale) / 2f)
        imageMatrix = matrix

        baseRect.set(0f, 0f, iw, ih)
        viewRect.set(0f, 0f, vw, vh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    /** 相对"铺满屏幕"的放大倍数（1 = 原始铺满，2.5 = 放大 2.5 倍）。 */
    fun zoomToFitMultiple(multiple: Float, px: Float = width / 2f, py: Float = height / 2f) {
        zoomTo(minScale * multiple, px, py)
    }

    /** 以 ([px],[py]) 为不动点把缩放推到 [target]。 */
    fun zoomTo(target: Float, px: Float = width / 2f, py: Float = height / 2f) {
        val applied = target.coerceIn(minScale, maxScale) / currentScale
        currentScale *= applied
        matrix.postScale(applied, applied, px, py)
        fixTranslation()
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    lastX = event.x
                    lastY = event.y
                    fixTranslation()
                    imageMatrix = matrix
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    /**
     * 边界约束：图片比视图小就居中，比视图大就不允许拖出空白。
     * 每次缩放/拖动后都要调用，否则会出现整张图被拖到屏幕外找不回来。
     */
    private fun fixTranslation() {
        val d = drawable ?: return
        matrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val w = d.intrinsicWidth * scale
        val h = d.intrinsicHeight * scale

        val dx = when {
            w <= viewRect.width() -> (viewRect.width() - w) / 2f - transX
            else -> {
                val minX = viewRect.width() - w   // 最负的合法偏移
                when {
                    transX > 0f -> -transX
                    transX < minX -> minX - transX
                    else -> 0f
                }
            }
        }

        val dy = when {
            h <= viewRect.height() -> (viewRect.height() - h) / 2f - transY
            else -> {
                val minY = viewRect.height() - h
                when {
                    transY > 0f -> -transY
                    transY < minY -> minY - transY
                    else -> 0f
                }
            }
        }

        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    /** 双击最多是"放大到看细节"，再大就没意义了 */
    private var doubleTapTarget = 0f
        get() = max(minScale * 2.5f, minScale)

    private val DOUBLE_TAP_SCALE: Float
        get() = doubleTapTarget

    private companion object {
        const val MAX_SCALE = 5f
    }
}
