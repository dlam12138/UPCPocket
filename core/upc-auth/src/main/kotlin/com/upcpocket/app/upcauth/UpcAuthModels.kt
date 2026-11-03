package com.upcpocket.app.upcauth

import com.upcpocket.app.account.api.SchoolAccountId

/**
 * 课表业务 Cookie。
 *
 * 只接受 `app.upc.edu.cn` 主机；值不写入日志，`toString` 固定脱敏。持久化由
 * :feature:timetable 以自己的 Keystore alias 加密完成。
 */
data class UpcAuthCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expiresAtEpochSeconds: Long?,
) {
    init {
        require(name.matches(Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,256}"))) { "非法 Cookie 名" }
        require(value.length <= 4096 && value.all { it in '\u0021'..'\u007e' }) { "非法 Cookie 值" }
        require(domain == HOST) { "课表 Cookie 只能属于 app.upc.edu.cn" }
        require(path.startsWith("/") && path.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "非法 Cookie 路径"
        }
        require(expiresAtEpochSeconds == null || expiresAtEpochSeconds > 0) { "非法 Cookie 到期时间" }
    }

    override fun toString(): String =
        "UpcAuthCookie(name=$name, domain=$domain, path=$path, value=[REDACTED])"

    companion object {
        const val HOST = "app.upc.edu.cn"
    }
}

/** 固定认证失败语义；不携带上游正文、账号、密码或 Cookie。 */
enum class UpcAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    ACCESS_DENIED,
}

sealed interface UpcAuthResult {
    data class Success(val cookies: List<UpcAuthCookie>) : UpcAuthResult

    data class Failure(
        val reason: UpcAuthFailure,
        /** 仅 INTERACTION_REQUIRED 时给出学校正常登录页；不含账号或票据。 */
        val interactionUrl: String? = null,
    ) : UpcAuthResult
}

/**
 * 课表认证适配边界。
 *
 * 实现只返回业务 Cookie，不向调用方提供密码或解密能力。调用方必须把结果再次
 * 绑定到同一 [SchoolAccountId] 与凭据代次，并在发布前复核。
 */
interface TimetableAuthenticator {
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcAuthResult
}
