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
 * 学校业务会话的本地加密副本（Android Keystore AES-GCM，应用私有 SharedPreferences）。
 *
 * 边界（必须如实告诉用户）：
 *  - 只保存**业务自己签发**的可复用凭据：图书馆座位 token（目前唯一使用者），
 *    以及唐岛湾用电查询的 ASP.NET 会话表单（`__VIEWSTATE` 系）；
 *  - 明文只存在内存与这份密文里，密钥不可导出；应用卸载或清除数据即失效；
 *  - 密钥失效/密文损坏时**只清掉对应那一条**，让该业务重新登录，不崩溃、不牵连其他业务；
 *  - `allowBackup=false`，这些文件不会被系统云备份带走；
 *  - **WebView 自己的 Cookie 不由本类加密**，它由系统 WebView 管理；WebView 业务会话是否跨重启
 *    可用取决于 WebView Cookie 的实际行为，交付说明单独标注；
 *  - 不保存账号密码、CAS ticket、OAuth code、微信 code。
 *
 * 轻量版第二步删除了校园卡平台登录与流水，因此这里不再保存平台令牌或平台余额快照；
 * 校园卡余额由 `DirectCardBalance` 直接按学号查询，不经过本类。
 */
class SchoolSessionStore(
    context: Context,
    /**
     * 加解密实现。生产环境固定用 Android Keystore（默认值）；
     * 只有单元测试会换掉它 —— Robolectric 没有可用的 AndroidKeyStore 软件实现，
     * 而“换号隔离/失效清理”这些行为必须真的读写一遍密文才算验证过。
     */
    private val crypto: SessionCrypto = KeystoreSessionCrypto,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class SavedLibrarySession(
        val token: String,
        val user: LibraryUser,
        val savedAt: Long,
    )

    /**
     * 唐家湾/唐岛湾用电查询的会话表单。
     *
     * 该校系统是 ASP.NET WebForms：查询必须提交页面下发的 `__VIEWSTATE` /
     * `__EVENTVALIDATION`。它们**不是用户凭据**，但确实是一次性会话状态，
     * 所以和令牌一样加密保存，并在服务端拒绝时整体丢弃重取。
     */
    data class SavedPowerForm(
        val viewState: String,
        val viewStateGenerator: String,
        val eventValidation: String,
        val savedAt: Long,
    )

    // ── 图书馆 ────────────────────────────────────────────────────────────

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
        runCatching { prefs.edit().putString(KEY_LIBRARY, encrypt(payload)).apply() }
            .onFailure { prefs.edit().remove(KEY_LIBRARY).apply() }
    }

    @Synchronized
    fun loadLibrary(): SavedLibrarySession? {
        val stored = prefs.getString(KEY_LIBRARY, null) ?: return null
        return runCatching {
            val json = JSONObject(decrypt(stored))
            val token = json.optString("token")
            if (token.isBlank()) return dropLibrary()
            SavedLibrarySession(
                token = token,
                user = LibraryUser(
                    id = json.optString("id"),
                    username = json.optString("username"),
                    name = json.optString("name").ifBlank { json.optString("username") },
                ),
                savedAt = json.optLong("savedAt"),
            )
        }.getOrElse { dropLibrary() }
    }

    @Synchronized
    fun clearLibrary() {
        prefs.edit().remove(KEY_LIBRARY).apply()
    }

    // ── 唐岛湾电费会话表单 ────────────────────────────────────────────────

    @Synchronized
    fun savePowerForm(form: SavedPowerForm) {
        if (form.viewState.isBlank() || form.eventValidation.isBlank()) return
        val payload = JSONObject()
            .put("vs", form.viewState)
            .put("vg", form.viewStateGenerator)
            .put("ev", form.eventValidation)
            .put("savedAt", form.savedAt)
            .toString()
        runCatching { prefs.edit().putString(KEY_POWER_FORM, encrypt(payload)).apply() }
            .onFailure { prefs.edit().remove(KEY_POWER_FORM).apply() }
    }

    @Synchronized
    fun loadPowerForm(): SavedPowerForm? {
        val stored = prefs.getString(KEY_POWER_FORM, null) ?: return null
        return runCatching {
            val json = JSONObject(decrypt(stored))
            val vs = json.optString("vs")
            val ev = json.optString("ev")
            if (vs.isBlank() || ev.isBlank()) return dropPowerForm()
            SavedPowerForm(
                viewState = vs,
                viewStateGenerator = json.optString("vg"),
                eventValidation = ev,
                savedAt = json.optLong("savedAt"),
            )
        }.getOrElse { dropPowerForm() }
    }

    @Synchronized
    fun clearPowerForm() {
        prefs.edit().remove(KEY_POWER_FORM).apply()
    }

    // ── 整体清理 ──────────────────────────────────────────────────────────

    /** 只清某个业务，不影响其他系统已经建立的会话。 */
    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * 清掉**全部**持久化会话（退出学校登录 / 关闭“记住登录”时用）。
     *
     * 图书馆单业务退出请用 [clearLibrary]，否则会把仍有效的会话一起踢掉。
     */
    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun dropLibrary(): SavedLibrarySession? {
        prefs.edit().remove(KEY_LIBRARY).apply()
        return null
    }

    private fun dropPowerForm(): SavedPowerForm? {
        prefs.edit().remove(KEY_POWER_FORM).apply()
        return null
    }

    private fun encrypt(plain: String): String = crypto.encrypt(plain)

    private fun decrypt(stored: String): String = crypto.decrypt(stored)

    companion object {
        /**
         * 账号的展示用摘要：只保留首尾字符，中间打码。
         *
         * 目的是让用户能核对“现在登录的是不是我”，而不是把学工号完整写进界面或日志。
         */
        fun maskAccount(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty()) return ""
            if (value.length <= 2) return value.first() + "•"
            if (value.length <= 6) return value.first() + "••" + value.last()
            return value.take(2) + "••••" + value.takeLast(2)
        }

        private const val PREFS_NAME = "school_session"
        private const val KEY_LIBRARY = "library"
        private const val KEY_POWER_FORM = "power_form"
    }
}

/** 会话密文的加解密接口；生产实现固定是 [KeystoreSessionCrypto]。 */
interface SessionCrypto {
    fun encrypt(plain: String): String
    fun decrypt(stored: String): String
}

/**
 * Android Keystore 里的 AES-GCM 实现。
 *
 * 密钥不可导出、不随备份迁移；密钥被系统清掉后 [decrypt] 会抛异常，
 * 调用方据此只删掉**对应那一条**密文并让用户重登。
 */
object KeystoreSessionCrypto : SessionCrypto {
    private const val KEY_ALIAS = "upcpocket.session.v2"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String {
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
}
