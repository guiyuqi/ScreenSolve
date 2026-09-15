package com.fragranstea.screensolve.util

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

/**
 * 屏幕尺寸相关的统一入口。
 *
 * 注意用 maximumWindowMetrics 而不是 currentWindowMetrics：
 * 后者可能排除系统栏，而 MediaProjection 抓的是整块屏幕，两者不一致会导致
 * VirtualDisplay 尺寸小于实际画面（系统会缩放，画面发虚）。
 */
object DisplayUtils {

    fun screenBounds(context: Context): Rect {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }
    }

    fun density(context: Context): Float = context.resources.displayMetrics.density

    fun dp(context: Context, value: Int): Int = (value * density(context)).toInt()

    fun densityDpi(context: Context): Int = context.resources.displayMetrics.densityDpi
}
