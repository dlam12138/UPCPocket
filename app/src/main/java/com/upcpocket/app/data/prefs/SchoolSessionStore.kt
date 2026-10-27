package com.upcpocket.app.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.upcpocket.app.data.library.LibraryUser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * “记住登录”的本地持久副本（**仅图书馆业务 token**）。
 *
 * 边界（必须如实告诉用户）：
 *  - token 用 Android Keystore 里的 AES-GCM 密钥加密后写入应用私有 SharedPreferences；
 *  - 密钥不可导出，应用卸载/清除数据即失效；密钥损坏或解密失败时删除密文并重新登录，不崩溃；
 *  - `allowBackup=false`，这些文件不会被系统云备份带走；
 *  - **WebView 自己的 Cookie 不由本类加密**，它由系统 WebView 管理。这里只持久化
 *    App 自己接管的 token；课表会话是否跨重启可用，取决于 WebView Cookie 实际行为，
 *    交付说明单独标注，不能把两者混为一谈；
 *  - 不保存密码、CAS ticket、OAuth code。
 */
class SchoolSessionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class SavedLibrarySession(
        val token: String,
        val user: LibraryUser,
        val savedAt: Long,
    )

    @Synchronized
    fun saveLibrary(token: String, user: LibraryUser) {
        if (token.isBlank()) return
        val payload = JSONObject()
            .put("token", token)
            .put("id", user.id)
            .put("username", user.username)
            .put("name", user.name)
            .put("savedAt", System.currentTimeMillis())
            .toString()
        runCatching {
            prefs.edit().putString(KEY_LIBRARY, encrypt(payload)).apply()
        }.onFailure { clear() }
    }

    @Synchronized
    fun loadLibrary(): SavedLibrarySession? {
        val stored = prefs.getString(KEY_LIBRARY, null) ?: return null
        return runCatching {
            val json = JSONObject(decrypt(stored))
            val token = json.optString("token")
            if (token.isBlank()) return clearAndNull()
            SavedLibrarySession(
                token = token,
                user = LibraryUser(
                    id = json.optString("id"),
                    username = json.optString("username"),
                    name = json.optString("name").ifBlank { json.optString("username") },
                ),
                savedAt = json.optLong("savedAt"),
            )
        }.getOrElse {
            // 密钥失效/密文损坏：清掉这一份，让用户重新登录，不影响课表与自定义日程。
            prefs.edit().remove(KEY_LIBRARY).apply()
            null
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun clearAndNull(): SavedLibrarySession? {
        prefs.edit().remove(KEY_LIBRARY).apply()
        return null
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "密文长度异常" }
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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
        const val PREFS_NAME = "school_session"
        const val KEY_LIBRARY = "library"
        const val KEY_ALIAS = "upcpocket.library.session.v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
