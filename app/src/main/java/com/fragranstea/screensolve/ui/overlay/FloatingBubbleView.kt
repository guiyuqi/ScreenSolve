package com.fragranstea.screensolve.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.fragranstea.screensolve.R
import kotlin.math.abs

/**
 * 悬浮球。
 *
 * 为什么用自绘 View 而不是「布局 + ImageView」：悬浮窗跑在 Service 里，取不到 Activity 的主题，
 * 布局膨胀一旦碰到主题属性（?attr/...）就会直接崩；自绘完全绕开这类坑，
 * 而且 56dp 的小圆点自绘比膨胀布局更快。
 *
 * 坐标约定：所有对外回调传的都是「窗口左上角在屏幕坐标系中的位置」（单位：物理像素）。
 */
class FloatingBubbleView(context: Context) : View(context) {

    interface Listener {
        /** 拖动过程中持续回调，用于实时移动窗口 */
        fun onBubbleDragged(x: Int, y: Int)

        /** 抬手，用于吸边吸附并保存位置 */
        fun onBubbleDragFinished(x: Int, y: Int)

        /** 点击（未发生拖动） */
        fun onBubbleTapped()
    }

    var listener: Listener? = null

    /**
     * 由 Service 维护的"当前窗口左上角坐标"。
     * 按下瞬间会被读取一次作为拖动基准，之后拖动过程中的更新不会再影响本次拖动的计算。
     */
    var windowX: Int = 0
    var windowY: Int = 0

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_pressed)
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_bubble_search)
    private val iconBounds = Rect()

    // 本次手势的基准与最新位置（都相对按下瞬间的窗口位置计算，避免叠加误差）
    private var startWindowX = 0
    private var startWindowY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var latestX = 0
    private var latestY = 0
    private var dragging = false

    init {
        isClickable = true
        isFocusable = false
        contentDescription = context.getString(R.string.bubble_content_desc)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (width > 0) width else height
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        // 留 2dp 边距，避免抗锯齿把圆的边缘切掉
        val radius = centerX - 2f * density
        canvas.drawCircle(centerX, centerY, radius, if (isPressed) pressedPaint else idlePaint)

        val iconSize = (width * 0.52f).toInt()
        val left = (width - iconSize) / 2
        val top = (height - iconSize) / 2
        iconBounds.set(left, top, left + iconSize, top + iconSize)
        icon?.let {
            it.setBounds(iconBounds)
            it.draw(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startWindowX = windowX
                startWindowY = windowY
                latestX = windowX
                latestY = windowY
                dragging = false
                isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    // 超过阈值才认定是拖动，否则松手时按点击处理
                    dragging = true
                    isPressed = false
                }
                if (dragging) {
                    latestX = (startWindowX + dx).toInt()
                    latestY = (startWindowY + dy).toInt()
                    listener?.onBubbleDragged(latestX, latestY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) {
                    listener?.onBubbleDragFinished(latestX, latestY)
                    dragging = false
                } else {
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onBubbleTapped()
        return true
    }
}
