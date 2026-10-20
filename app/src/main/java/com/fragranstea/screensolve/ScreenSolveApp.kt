package com.fragranstea.screensolve

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fragranstea.screensolve.data.SettingsStore
import com.fragranstea.screensolve.util.MathTextRenderer

/**
 * 应用入口：把"白天 / 夜间 / 跟随系统"落到 AppCompat 的全局夜间模式上。
 *
 * 在 Application 里统一设置，好处是所有 Activity（主界面、设置、历史、会话、相机）一次性生效，
 * 不用每个页面自己处理；设置页改完之后调用 [applyTheme] 会触发当前 Activity 重建。
 */
class ScreenSolveApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme(SettingsStore(this).themeMode)

        // 渲染层的调试日志只在 debug 构建打开（正式包不刷日志、也不泄露题目内容）
        MathTextRenderer.verboseLogging = resources.getBoolean(R.bool.is_debug_build)
    }

    companion object {
        fun toNightMode(mode: SettingsStore.ThemeMode): Int = when (mode) {
            SettingsStore.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsStore.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            SettingsStore.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        fun applyTheme(mode: SettingsStore.ThemeMode) {
            AppCompatDelegate.setDefaultNightMode(toNightMode(mode))
        }
    }
}
