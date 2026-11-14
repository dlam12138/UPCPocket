package com.upcpocket.app.upcauth

import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference

/** e站通业务 Cookie。其值只供认证调用方使用，日志字符串固定脱敏。 */
data class UpcServicePortalCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expiresAtEpochSeconds: Long?,
    val hostOnly: Boolean,
) {
    init {
        require(name.matches(Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,256}"))) { "非法 Cookie 名" }
        require(value.length <= 4096 && value.all { it in '\u0021'..'\u007e' }) { "非法 Cookie 值" }
        require(domain == HOST) { "e站通 Cookie 只能属于业务主机" }
        require(path.startsWith("/") && path.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "非法 Cookie 路径"
        }
        require(expiresAtEpochSeconds == null || expiresAtEpochSeconds > 0) { "非法 Cookie 到期时间" }
    }

    override fun toString(): String =
        "UpcServicePortalCookie(name=$name, value=[REDACTED], domain=$domain, path=$path, " +
            "secure=$secure, httpOnly=$httpOnly, expiresAtEpochSeconds=$expiresAtEpochSeconds, " +
            "hostOnly=$hostOnly)"

    companion object {
        const val HOST = "service.upc.edu.cn"
    }
}

enum class UpcServicePortalAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    ACCESS_DENIED,
    IDENTITY_MISMATCH,
    BUSY,
}

enum class UpcServicePortalAuthSource {
    PASSWORD,
    BOUND_SSO,
    VERIFIED_WEB,
}

sealed interface UpcServicePortalAuthResult {
    data class Success(
        val binding: SchoolAccountReference,
        val cookies: List<UpcServicePortalCookie>,
        val source: UpcServicePortalAuthSource,
    ) : UpcServicePortalAuthResult {
        override fun toString(): String =
            "UpcServicePortalAuthResult.Success(binding=[REDACTED], cookies=[REDACTED], source=$source)"
    }

    data class Failure(val reason: UpcServicePortalAuthFailure) : UpcServicePortalAuthResult
}

interface ServicePortalAuthenticator {
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcServicePortalAuthResult

    /** 清除本适配器的内存会话并使正在进行的认证结果失效；不联网。 */
    fun clearSession()
}
