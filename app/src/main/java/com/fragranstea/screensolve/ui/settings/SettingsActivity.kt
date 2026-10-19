package com.fragranstea.screensolve.ui.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.fragranstea.screensolve.R
import com.fragranstea.screensolve.data.SecureKeyStore
import com.fragranstea.screensolve.data.SettingsStore
import com.fragranstea.screensolve.ScreenSolveApp
import com.fragranstea.screensolve.recognize.OcrRecognizer
import com.fragranstea.screensolve.ui.overlay.OverlayService

/**
 * 设置页：API Key、识题通道、深度思考、自定义提示词。
 *
 * API Key 用 Android Keystore 加密后存本地（见 SecureKeyStore），
 * 界面上永远只显示"已保存/未设置"，不回显明文。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var keyStore: SecureKeyStore
    private lateinit var settings: SettingsStore

    private lateinit var apiKeyInput: EditText
    private lateinit var keyStatus: android.widget.TextView
    private lateinit var visionRadio: RadioButton
    private lateinit var ocrRadio: RadioButton
    private lateinit var sourceScreenRadio: RadioButton
    private lateinit var sourceCameraRadio: RadioButton
    private lateinit var bubbleDefaultRadio: RadioButton
    private lateinit var bubbleDotRadio: RadioButton
    private lateinit var themeSystemRadio: RadioButton
    private lateinit var themeLightRadio: RadioButton
    private lateinit var themeDarkRadio: RadioButton
    private lateinit var thinkingSwitch: SwitchCompat
    private lateinit var promptInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        keyStore = SecureKeyStore(this)
        settings = SettingsStore(this)

        apiKeyInput = findViewById(R.id.etApiKey)
        keyStatus = findViewById(R.id.tvKeyStatus)
        visionRadio = findViewById(R.id.rbChannelVision)
        ocrRadio = findViewById(R.id.rbChannelOcr)
        sourceScreenRadio = findViewById(R.id.rbSourceScreen)
        sourceCameraRadio = findViewById(R.id.rbSourceCamera)
        bubbleDefaultRadio = findViewById(R.id.rbBubbleDefault)
        bubbleDotRadio = findViewById(R.id.rbBubbleDot)
        themeSystemRadio = findViewById(R.id.rbThemeSystem)
        themeLightRadio = findViewById(R.id.rbThemeLight)
        themeDarkRadio = findViewById(R.id.rbThemeDark)
        thinkingSwitch = findViewById(R.id.swDeepThinking)
        promptInput = findViewById(R.id.etPrompt)

        // lite 版本不含端侧 OCR（省 13MB），此时禁用该选项并说明原因，避免"选了却静默不生效"
        if (!OcrRecognizer.available) {
            ocrRadio.isEnabled = false
            ocrRadio.text = getString(R.string.settings_channel_ocr_unavailable)
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { save() }
        findViewById<Button>(R.id.btnResetPrompt).setOnClickListener {
            promptInput.setText(SettingsStore.DEFAULT_PROMPT)
        }
        findViewById<Button>(R.id.btnClearKey).setOnClickListener {
            keyStore.clear()
            apiKeyInput.setText("")
            refreshKeyStatus()
            Toast.makeText(this, R.string.settings_key_cleared, Toast.LENGTH_SHORT).show()
        }

        loadCurrentValues()
    }

    private fun loadCurrentValues() {
        refreshKeyStatus()
        if (settings.channel == SettingsStore.Channel.OCR && OcrRecognizer.available) {
            ocrRadio.isChecked = true
        } else {
            visionRadio.isChecked = true
            // 从 full 版换到 lite 版时，把设置里残留的 OCR 通道纠正回视觉
            if (settings.channel == SettingsStore.Channel.OCR) {
                settings.channel = SettingsStore.Channel.VISION
            }
        }
        if (settings.source == SettingsStore.Source.CAMERA) {
            sourceCameraRadio.isChecked = true
        } else {
            sourceScreenRadio.isChecked = true
        }

        if (settings.bubbleStyle == SettingsStore.BubbleStyle.DOT) {
            bubbleDotRadio.isChecked = true
        } else {
            bubbleDefaultRadio.isChecked = true
        }

        when (settings.themeMode) {
            SettingsStore.ThemeMode.LIGHT -> themeLightRadio.isChecked = true
            SettingsStore.ThemeMode.DARK -> themeDarkRadio.isChecked = true
            SettingsStore.ThemeMode.SYSTEM -> themeSystemRadio.isChecked = true
        }

        thinkingSwitch.isChecked = settings.deepThinking
        promptInput.setText(settings.customPrompt)
    }

    private fun refreshKeyStatus() {
        keyStatus.text = getString(
            if (keyStore.hasKey()) R.string.settings_key_saved else R.string.settings_key_missing,
        )
    }

    private fun save() {
        val typedKey = apiKeyInput.text?.toString()?.trim().orEmpty()
        // 只在用户真的输入了新 Key 时才覆盖，避免"只改提示词却把 Key 清空"
        if (typedKey.isNotEmpty()) {
            keyStore.save(typedKey)
            apiKeyInput.setText("")
        }
        settings.channel = if (ocrRadio.isChecked) SettingsStore.Channel.OCR else SettingsStore.Channel.VISION

        val source = if (sourceCameraRadio.isChecked) {
            SettingsStore.Source.CAMERA
        } else {
            SettingsStore.Source.SCREEN
        }
        val sourceChanged = settings.source != source
        settings.source = source
        // 切到拍照模式就不需要录屏会话了，顺手释放，避免状态栏一直挂着"正在录制"
        if (sourceChanged && source == SettingsStore.Source.CAMERA) {
            OverlayService.releaseSession(this)
        }

        val themeMode = when {
            themeLightRadio.isChecked -> SettingsStore.ThemeMode.LIGHT
            themeDarkRadio.isChecked -> SettingsStore.ThemeMode.DARK
            else -> SettingsStore.ThemeMode.SYSTEM
        }
        if (settings.themeMode != themeMode) {
            settings.themeMode = themeMode
            // 立即生效（会重建界面）
            ScreenSolveApp.applyTheme(themeMode)
        }

        val bubbleStyle = if (bubbleDotRadio.isChecked) {
            SettingsStore.BubbleStyle.DOT
        } else {
            SettingsStore.BubbleStyle.DEFAULT
        }
        val styleChanged = settings.bubbleStyle != bubbleStyle
        settings.bubbleStyle = bubbleStyle
        // 悬浮窗宽高写死在 LayoutParams 里，改样式/尺寸只能让服务重建一次
        if (styleChanged) OverlayService.restyle(this)
        settings.deepThinking = thinkingSwitch.isChecked
        settings.customPrompt = promptInput.text?.toString().orEmpty()
        refreshKeyStatus()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
