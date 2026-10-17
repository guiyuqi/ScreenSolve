package com.fragranstea.screensolve.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的加密存储。
 *
 * 用 Android Keystore 生成一把不可导出的 AES-256 密钥，用它以 GCM 模式加密 Key，
 * 只把密文存到 SharedPreferences。密钥本身永远待在 Keystore 里，连我们自己的进程都读不出来，
 * 备份或 root 拿到 SharedPreferences 文件也只是密文。
 *
 * 为什么不用 EncryptedSharedPreferences：那套库（androidx.security:security-crypto）已被官方弃用，
 * 自己写这几十行没有迁移负担（见 ARCHITECTURE.md §4.9）。
 */
class SecureKeyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        val trimmed = apiKey.trim()
        // 空串**绝不**删除已有 Key：删除只有一条路径，就是用户显式点"清除已保存的 KEY"。
        // 之前这里写的是 clear()，把"删除"藏进了一个看起来像"写入"的方法里，
        // 任何调用方传空串都会静默抹掉用户的配置 —— 这种 API 形状本身就是 bug 温床。
        if (trimmed.isEmpty()) {
            Log.w(TAG, "save() 收到空 Key，忽略（清除请走 clear()）")
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        // GCM 的 IV 必须与密文一起保存，否则无法解密
        val blob = Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
        prefs.edit().putString(KEY_BLOB, blob).apply()
    }

    fun load(): String? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            if (bytes.size <= IV_LENGTH) return null
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipherText = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 解不开**不要删**用户的 Key：Keystore 在个别时机（刚开机、刚重启进程、
            // 系统密钥服务繁忙）会瞬时失败，自动 clear 等于把用户配置静默抹掉 —— 实测踩过一次。
            // 这里只报告失败，清理由用户在设置页显式点"清除已保存的 KEY"触发。
            Log.w(TAG, "解密 API Key 失败（保留原密文，等待下次重试）", t)
            null
        }
    }

    fun hasKey(): Boolean = prefs.contains(KEY_BLOB)

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecureKeyStore"
        const val PREFS_NAME = "secure_settings"
        const val KEY_BLOB = "api_key_blob"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "screensolve_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
