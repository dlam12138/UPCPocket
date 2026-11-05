package com.upcpocket.app.platform.crypto

import android.util.Base64

/**
 * 会话密文的通用加解密接口。
 *
 * platform 只负责“把一段字符串加密成可落盘的字符串”，不认识图书馆 token、
 * 电费表单或任何业务模型。业务模块自行定义序列化格式与键名。
 */
interface SessionCrypto {
    fun encrypt(plain: String): String

    fun decrypt(stored: String): String
}

/**
 * Android Keystore 里的 AES-GCM 实现。
 *
 * 兼容契约：alias、算法、IV 长度与 Base64 编码都不能变，否则已安装用户保存的
 * 图书馆/电费密文将无法解密。密钥不可导出、不随备份迁移；密钥被系统清掉后
 * [decrypt] 会抛异常，调用方据此只删掉对应那一条密文并让用户重登。
 */
object KeystoreSessionCrypto : SessionCrypto {
    private const val KEY_ALIAS = "upcpocket.session.v2"
    private val delegate = KeystoreAesGcmCrypto(KEY_ALIAS)

    override fun encrypt(plain: String): String =
        Base64.encodeToString(delegate.encrypt(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    override fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        return String(delegate.decrypt(bytes), Charsets.UTF_8)
    }
}
