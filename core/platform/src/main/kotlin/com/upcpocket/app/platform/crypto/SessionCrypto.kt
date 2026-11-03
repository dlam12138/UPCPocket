package com.upcpocket.app.platform.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
