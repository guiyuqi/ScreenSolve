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

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var channel: Channel
        get() = when (prefs.getString(KEY_CHANNEL, null)) {
            Channel.OCR.name -> Channel.OCR
            else -> Channel.VISION
        }
        set(value) = prefs.edit().putString(KEY_CHANNEL, value.name).apply()

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
    }
}
