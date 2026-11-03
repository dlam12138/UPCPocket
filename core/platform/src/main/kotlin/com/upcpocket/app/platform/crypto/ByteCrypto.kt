package com.upcpocket.app.platform.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 面向字节的 AES-GCM 边界。
 *
 * 密码和 token 不需要先转成不可擦除的 String；各业务自行定义版本化信封与序列化格式。
 * 当前实现仍由 Android Keystore 保存不可导出的密钥，每次加密由系统生成随机 IV。
 */
interface ByteCrypto {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(stored: ByteArray): ByteArray
}

/**
 * 指定 alias 的 Android Keystore AES-256-GCM 实现。
 *
 * 输出格式固定为 12 字节 IV + 密文 + 128-bit GCM tag。调用方必须把 alias 与
 * 格式版本写进自己的持久化契约；不能把统一账号和业务会话共用同一个 alias。
 */
class KeystoreAesGcmCrypto(private val keyAlias: String) : ByteCrypto {
    init {
        require(keyAlias.isNotBlank()) { "Keystore alias 不能为空" }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Keystore 返回了不支持的 IV 长度" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(stored: ByteArray): ByteArray {
        require(stored.size > IV_BYTES) { "密文长度异常" }
        val iv = stored.copyOfRange(0, IV_BYTES)
        val body = stored.copyOfRange(IV_BYTES, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(createIfMissing = false), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private fun secretKey(createIfMissing: Boolean = true): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) {
            throw UnrecoverableKeyException("Keystore key unavailable")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
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
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
