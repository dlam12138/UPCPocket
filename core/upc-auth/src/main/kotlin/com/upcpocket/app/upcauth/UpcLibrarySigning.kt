package com.upcpocket.app.upcauth

import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 图书馆座位系统的公开请求签名。
 *
 * 协议来自学校页面公开前端：`X-request-id` 为 UUID v4，`X-request-date` 为毫秒时间戳，
 * `X-hmac-request-key` 为 HMAC-SHA256(`seat::<uuid>::<date>::<METHOD>`, 页面配置中的
 * 公开密钥)。密钥不是用户凭据；这里用 AES-CBC 解出，避免再复制一份“看起来像密码”的
 * 常量。用户 token 只作为请求头/参数，不写入日志或持久层。
 */
object UpcLibrarySigning {
    private const val NUM_CODE = "cDf+jadFUWncEn536MXItw=="
    private const val CONFIG_KEY = "server_date_time"
    private const val CONFIG_IV = "client_date_time"

    fun signedHeaders(
        token: String?,
        method: String,
        uuid: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        hmacKey: ByteArray = configKey(),
    ): Map<String, String> {
        require(method.uppercase() in setOf("GET", "POST")) { "不支持的请求方法" }
        return buildMap {
            if (!token.isNullOrBlank()) put("Authorization", token)
            put("loginType", "PC")
            put("X-request-id", uuid)
            put("X-request-date", timestamp.toString())
            put("X-hmac-request-key", sign(method, uuid, timestamp, hmacKey))
            put("Accept", "application/json")
        }
    }

    fun sign(
        method: String,
        uuid: String,
        timestamp: Long,
        hmacKey: ByteArray = configKey(),
    ): String {
        val message = "seat::$uuid::$timestamp::${method.uppercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 页面公开配置中的 HMAC 密钥；去掉 PKCS#5 填充后是 9 字节。 */
    fun configKey(): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(CONFIG_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(CONFIG_IV.toByteArray(Charsets.UTF_8)),
        )
        return cipher.doFinal(Base64.getDecoder().decode(NUM_CODE))
    }
}
