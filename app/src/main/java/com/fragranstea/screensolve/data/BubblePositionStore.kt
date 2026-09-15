package com.fragranstea.screensolve.data

import android.content.Context

/**
 * 悬浮球位置记忆（贴边后的坐标）。
 *
 * MVP 先用 SharedPreferences；等 M3 的设置页落地时，再和设置项一起统一迁到 DataStore
 * （见 ARCHITECTURE.md §4.9）。故意把读写封在这里，迁移时只改这一个文件。
 */
class BubblePositionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val hasPosition: Boolean
        get() = prefs.getInt(KEY_X, UNSET) != UNSET && prefs.getInt(KEY_Y, UNSET) != UNSET

    var x: Int
        get() = prefs.getInt(KEY_X, UNSET)
        set(value) = prefs.edit().putInt(KEY_X, value).apply()

    var y: Int
        get() = prefs.getInt(KEY_Y, UNSET)
        set(value) = prefs.edit().putInt(KEY_Y, value).apply()

    private companion object {
        const val PREFS_NAME = "bubble_position"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val UNSET = Int.MIN_VALUE
    }
}
