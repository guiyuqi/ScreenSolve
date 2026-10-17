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
        thinkingSwitch = findViewById(R.id.swDeepThinking)
        promptInput = findViewById(R.id.etPrompt)

        // M4 起端侧 OCR 通道可用：模型打进 APK，离线出字，不需要 Google Play 服务

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
        if (settings.channel == SettingsStore.Channel.OCR) {
            ocrRadio.isChecked = true
        } else {
            visionRadio.isChecked = true
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
        settings.deepThinking = thinkingSwitch.isChecked
        settings.customPrompt = promptInput.text?.toString().orEmpty()
        refreshKeyStatus()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
