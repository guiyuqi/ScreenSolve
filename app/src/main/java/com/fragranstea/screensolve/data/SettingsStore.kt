package com.fragranstea.screensolve.data

import android.content.Context

/**
 * 非敏感设置项。
 *
 * 先用 SharedPreferences：目前只有三个键值，等历史记录/更多选项落地时再整体迁到 DataStore
 * （见 ARCHITECTURE.md §4.9）。密码类数据一律走 [SecureKeyStore]，不放这里。
 */
class SettingsStore(context: Context) {

    enum class Channel {
        /** 截图直传多模态模型（默认） */
        VISION,

        /** 端侧 OCR 出文本后交给文本模型（M4 提供） */
        OCR,
    }

    /**
     * 悬浮球外观。
     * - DEFAULT：蓝底放大镜，56dp，一眼能找到
     * - DOT：一个很小的白点（30dp），不挡内容、适合长期挂着
     */
    /**
     * @param sizeDp 窗口尺寸（决定可点区域）
     * @param visualSizeDp 实际画出来的圆直径。
     *   DOT 故意让窗口比圆大：10dp 的圆在 480dpi 上只有 30px，
     *   如果窗口也做 30px，手指几乎点不中（Android 建议最小 48dp 可点区域）。
     *   透明区域同样响应触摸，所以"看着很小、依然好点"。
     */
    enum class BubbleStyle(val sizeDp: Int, val visualSizeDp: Int) {
        DEFAULT(56, 56),
        DOT(28, 10),
    }

    /** 题目从哪来：屏幕上看到的（截屏）还是拿相机拍。 */
    enum class Source {
        /** 截屏 + 框选（默认） */
        SCREEN,

        /** 调系统相机拍一张，再框选 */
        CAMERA,
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var channel: Channel
        get() = when (prefs.getString(KEY_CHANNEL, null)) {
            Channel.OCR.name -> Channel.OCR
            else -> Channel.VISION
        }
        set(value) = prefs.edit().putString(KEY_CHANNEL, value.name).apply()

    var bubbleStyle: BubbleStyle
        get() = when (prefs.getString(KEY_BUBBLE_STYLE, null)) {
            BubbleStyle.DOT.name -> BubbleStyle.DOT
            else -> BubbleStyle.DEFAULT
        }
        set(value) = prefs.edit().putString(KEY_BUBBLE_STYLE, value.name).apply()

    var source: Source
        get() = when (prefs.getString(KEY_SOURCE, null)) {
            Source.CAMERA.name -> Source.CAMERA
            else -> Source.SCREEN
        }
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    /** 是否开启深度思考：更准但更慢。 */
    var deepThinking: Boolean
        get() = prefs.getBoolean(KEY_THINKING, false)
        set(value) = prefs.edit().putBoolean(KEY_THINKING, value).apply()

    /** 用户自定义的解题风格；空串表示用默认。 */
    var customPrompt: String
        get() = prefs.getString(KEY_PROMPT, DEFAULT_PROMPT).orEmpty()
        set(value) = prefs.edit().putString(KEY_PROMPT, value.trim()).apply()

    fun resetPrompt() {
        prefs.edit().remove(KEY_PROMPT).apply()
    }

    /** 当前生效的模型名。视觉直传与 OCR 通道都用 flash（它同时支持图文）。 */
    val modelName: String get() = MODEL_FLASH

    companion object {
        const val MODEL_FLASH = "deepseek-flash"

        /** 默认风格：答案 + 分步解析。用户可以改，但输出格式骨架不归这里管。 */
        const val DEFAULT_PROMPT = "先给出最终答案，再给出分步骤解析，解析要说明每一步的推理依据。"

        private const val PREFS_NAME = "settings"
        private const val KEY_CHANNEL = "channel"
        private const val KEY_THINKING = "deep_thinking"
        private const val KEY_PROMPT = "custom_prompt"
        private const val KEY_BUBBLE_STYLE = "bubble_style"
        private const val KEY_SOURCE = "source"
    }
}
