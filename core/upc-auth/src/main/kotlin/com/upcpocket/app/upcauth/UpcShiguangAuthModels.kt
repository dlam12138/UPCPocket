package com.upcpocket.app.upcauth

import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation

interface ShiguangAuthenticator {
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcShiguangAuthResult
}

enum class ShiguangWebRequestKind { NAVIGATION, RESOURCE }

sealed interface UpcShiguangWebNavigation {
    data object Allow : UpcShiguangWebNavigation
    data object Block : UpcShiguangWebNavigation
    class Complete(val callbackUrl: String) : UpcShiguangWebNavigation {
        override fun toString() = "Complete(callbackUrl=[REDACTED])"
    }
}

interface ShiguangInteractiveAuthenticator {
    val entryUrl: String
    fun inspectWebNavigation(
        url: String,
        method: String,
        isMainFrame: Boolean,
        kind: ShiguangWebRequestKind,
        operation: DiagnosticOperation? = null,
    ): UpcShiguangWebNavigation
    suspend fun completeWebLoginResult(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
        callbackUrl: String,
        operation: DiagnosticOperation? = null,
    ): UpcShiguangAuthResult
}

enum class UpcShiguangAuthFailure {
    INVALID_CREDENTIALS, INTERACTION_REQUIRED, NETWORK_UNAVAILABLE, TLS_ERROR,
    UPSTREAM_ERROR, PROTOCOL_ERROR, ACCOUNT_UNAVAILABLE, ACCOUNT_CHANGED,
    ACCESS_DENIED, IDENTITY_MISMATCH,
}

sealed interface UpcShiguangAuthResult {
    data class Success(
        val cookies: List<UpcShiguangCookie>,
        val identityVerified: Boolean,
    ) : UpcShiguangAuthResult {
        override fun toString() = "Success(cookies=[REDACTED], identityVerified=$identityVerified)"
    }

    data class Failure(val reason: UpcShiguangAuthFailure) : UpcShiguangAuthResult
}

data class UpcShiguangCookie(
    val name: String,
    val value: String,
    val domain: String = UpcShiguangAuthEndpoints.HOST,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
    val expiresAtEpochSeconds: Long? = null,
) {
    init {
        require(name.matches(Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,256}")))
        require(value.isNotEmpty() && value.length <= 4096 && value.all { it in '\u0021'..'\u007e' })
        require(domain == UpcShiguangAuthEndpoints.HOST)
        require(path.startsWith("/") && path.none { it == '\r' || it == '\n' || it == '\u0000' })
        require(expiresAtEpochSeconds == null || expiresAtEpochSeconds > 0)
    }

    override fun toString() = "UpcShiguangCookie([REDACTED])"
}

object UpcShiguangAuthEndpoints {
    const val HOST = "dekt.upc.edu.cn"
    const val CAS_HOST = "cas.upc.edu.cn"
    const val BASE = "https://dekt.upc.edu.cn"
    // CAS registration uses HTTP service bytes; requests to this callback are upgraded to HTTPS.
    const val SERVICE = "http://dekt.upc.edu.cn/caslogin.jsp"
    const val LOGIN_URL = "https://cas.upc.edu.cn/cas/login?service=http%3A%2F%2Fdekt.upc.edu.cn%2Fcaslogin.jsp"
    const val SECOND_STAGE = "/sysmng/login!loginByUserNoForCas.action"
    const val LIST_PATH = "/actvMng/actvMngFront!jumpActvCampListPageForWeb.action"
    const val LIST_URL = "$BASE$LIST_PATH?page=1&oper=1"
    const val REPORT_URL = "$BASE/reportCardMngAjax/reportCardMngFront!querySelectedHour.action"
    const val REPORT_REFERER = "$BASE/reportCardMng/reportCardMngFront!jumpReportCardFirstForWeb.action"
}
