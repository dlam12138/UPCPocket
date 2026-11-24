package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticCredentialState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 成绩正常登录与只读查询入口；只允许 HTTPS 官方主机。 */
object UpcGradesAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val APP_HOST = "app.upc.edu.cn"
    const val PAGE_URL = "https://app.upc.edu.cn/site/score/index"
    const val INDEX_URL = "https://app.upc.edu.cn/score/wap/default/get-index"

    /**
     * CAS service 的 redirect 只能等于成绩页，且外层固定 from=wap 与 noAutoRedirect=1。
     *
     * 内层 redirect 保留已核对的百分号编码形状；不要改成其他编码或宽松路径。
     */
    const val SERVICE =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fscore%2Findex"
    const val LOGIN_URL =
        "https://cas.upc.edu.cn/cas/login?service=$SERVICE&from=wap&noAutoRedirect=1"

    internal const val SERVICE_DECODED =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https://app.upc.edu.cn/site/score/index"
    internal const val APP_CALLBACK_PATH = "/a_upc/api/cas/login"
    internal const val APP_INDEX_PATH = "/score/wap/default/get-index"
    internal const val APP_PAGE_PATH = "/site/score/index"
    internal const val MAX_REDIRECT_HOPS = 8
}

internal val GRADES_CAS_SERVICE = UpcCasService(
    casHost = UpcGradesAuthEndpoints.CAS_HOST,
    serviceUrl = UpcGradesAuthEndpoints.SERVICE_DECODED,
    callbackHost = UpcGradesAuthEndpoints.APP_HOST,
    callbackPath = UpcGradesAuthEndpoints.APP_CALLBACK_PATH,
    callbackParameterNames = setOf("ticket", "redirect", "from"),
    maxRedirectHops = UpcGradesAuthEndpoints.MAX_REDIRECT_HOPS,
)

/**
 * 成绩认证适配边界。
 *
 * 实现只返回 `app.upc.edu.cn` 的内存 Cookie，不向调用方提供密码、解密能力或
 * 原始响应。调用方必须把结果绑定到同一 [SchoolAccountId] 与凭据代次，并在发布
 * 前复核；Cookie 不得落盘。
 */
interface GradesAuthenticator {
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcAuthResult
}

/**
 * 用当前统一账号的正常 CAS 表单登录建立成绩业务 Cookie。
 *
 * 边界：
 *  - 只在 `cas.upc.edu.cn/cas/login` 的 HTTPS 官方表单提交一次密码；
 *  - 只接受 redirect 精确等于成绩页、单次 ticket、可选 from=wap 的回调；
 *  - 不自动跟随跳转，逐跳只允许成绩页、索引与该校验过的回调；
 *  - 验证码/二次验证立即返回交互登录，不提交密码、不重试、不降级；
 *  - 返回的 Cookie 只在成绩功能自己的内存会话使用，不写入诊断或缓存。
 */
class UpcGradesAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) : GradesAuthenticator {

    constructor(
        credentials: SchoolCredentialAccess,
    ) : this(credentials, OkHttpUpcAuthTransport())

    @OptIn(RestrictedSchoolCredentialUse::class)
    override suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcAuthResult {
        val operation = Diagnostics.beginOperation()
        recordUpcAuthDiagnostic(
            operation,
            DiagnosticModule.GRADES,
            action = DiagnosticAction.AUTH_START,
            result = DiagnosticResult.PENDING,
            phase = DiagnosticPhase.CREDENTIAL_RESTORE,
            detail = DiagnosticDetail.AUTH_REQUIRED,
        )
        val result = try {
            credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    recordUpcAuthDiagnostic(
                        operation, DiagnosticModule.GRADES,
                        action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                        phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                        detail = DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED,
                        reason = DiagnosticReason.ACCOUNT,
                        credentialState = DiagnosticCredentialState.GENERATION_CHANGED,
                    )
                    UpcAuthResult.Failure(UpcAuthFailure.ACCOUNT_CHANGED)
                } else {
                    recordUpcAuthDiagnostic(
                        operation, DiagnosticModule.GRADES,
                        result = DiagnosticResult.OK, phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                        detail = DiagnosticDetail.CREDENTIAL_RECOVERED,
                        credentialState = DiagnosticCredentialState.AVAILABLE,
                    )
                    performLogin(credential, operation)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.GRADES,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                detail = DiagnosticDetail.CREDENTIAL_UNAVAILABLE,
                reason = DiagnosticReason.ACCOUNT,
                credentialState = DiagnosticCredentialState.UNAVAILABLE,
            )
            return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        }
        return when (result) {
            is SchoolCredentialUseResult.Success -> result.value
            is SchoolCredentialUseResult.Failure -> {
                val changed = result.reason == SchoolCredentialFailure.CHANGED_DURING_USE
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.GRADES,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                    phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                    detail = when {
                        changed -> DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED
                        result.reason == SchoolCredentialFailure.BLOCKED_BY_CLEANUP ->
                            DiagnosticDetail.CREDENTIAL_CLEANUP_BLOCKED
                        else -> DiagnosticDetail.CREDENTIAL_UNAVAILABLE
                    },
                    reason = DiagnosticReason.ACCOUNT,
                    credentialState = when {
                        changed -> DiagnosticCredentialState.GENERATION_CHANGED
                        result.reason == SchoolCredentialFailure.BLOCKED_BY_CLEANUP ->
                            DiagnosticCredentialState.CLEANUP_BLOCKED
                        else -> DiagnosticCredentialState.UNAVAILABLE
                    },
                )
                UpcAuthResult.Failure(
                    if (changed) UpcAuthFailure.ACCOUNT_CHANGED
                    else UpcAuthFailure.ACCOUNT_UNAVAILABLE,
                )
            }
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private fun performLogin(
        credential: SchoolLoginCredential,
        operation: DiagnosticOperation,
    ): UpcAuthResult {
        val login = UpcCasLogin(
            GRADES_CAS_SERVICE,
            transport,
            operation,
            DiagnosticModule.GRADES,
        ).login(credential, UpcGradesAuthEndpoints.LOGIN_URL)
        return when (login) {
            is UpcCasLoginResult.Failure ->
                UpcAuthResult.Failure(login.reason, login.interactionUrl)
            is UpcCasLoginResult.Callback -> try {
                followCallbackAndVerify(login.session, login.url, operation)
            } finally {
                login.session.clear()
            }
        }
    }

    private fun followCallbackAndVerify(
        session: UpcCasHttpSession,
        start: HttpUrl,
        operation: DiagnosticOperation,
    ): UpcAuthResult {
        var current = start
        repeat(UpcGradesAuthEndpoints.MAX_REDIRECT_HOPS) {
            if (!isAllowedAppPage(current)) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.GRADES,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = DiagnosticDetail.BUSINESS_CALLBACK_REJECTED,
                    reason = DiagnosticReason.POLICY,
                    callbackState = DiagnosticCallbackState.REJECTED,
                )
                return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            }
            val response = try {
                session.request("GET", current.toString())
            } catch (failure: UpcAuthTransportException) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.GRADES,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = if (failure.failure == UpcAuthTransportFailure.TLS)
                        DiagnosticDetail.TRANSPORT_TLS else DiagnosticDetail.TRANSPORT_NETWORK,
                    reason = if (failure.failure == UpcAuthTransportFailure.TLS)
                        DiagnosticReason.TLS else DiagnosticReason.NETWORK,
                    callbackState = DiagnosticCallbackState.NOT_CONSUMED,
                )
                return UpcAuthResult.Failure(failure.toAuthFailure())
            }
            if (response.status in 300..399) {
                val location = response.header("Location")
                    ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
                current = resolveHttpUrl(current.toString(), location)
                    ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
                return@repeat
            }
            if (response.status == 401 || response.status == 403) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.GRADES,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = DiagnosticDetail.RESPONSE_HTTP,
                    reason = DiagnosticReason.HTTP,
                    httpStatus = response.status,
                    callbackState = DiagnosticCallbackState.REJECTED,
                    bindingSource = DiagnosticBindingSource.PASSWORD,
                )
                return UpcAuthResult.Failure(UpcAuthFailure.ACCESS_DENIED)
            }
            if (response.status != 200) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.GRADES,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = DiagnosticDetail.RESPONSE_HTTP,
                    reason = DiagnosticReason.HTTP,
                    httpStatus = response.status,
                )
                return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
            }
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.GRADES,
                result = DiagnosticResult.OK, phase = DiagnosticPhase.BUSINESS_CALLBACK,
                detail = DiagnosticDetail.BUSINESS_CALLBACK_ACCEPTED,
                httpStatus = response.status,
                callbackState = DiagnosticCallbackState.ACCEPTED,
                bindingSource = DiagnosticBindingSource.PASSWORD,
            )
            return verifyIndexAndExtractCookies(session, operation)
        }
        return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
    }

    private fun verifyIndexAndExtractCookies(
        session: UpcCasHttpSession,
        operation: DiagnosticOperation,
    ): UpcAuthResult {
        val response = try {
            session.request(
                "GET",
                UpcGradesAuthEndpoints.INDEX_URL,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to UpcGradesAuthEndpoints.PAGE_URL,
                ),
            )
        } catch (failure: UpcAuthTransportException) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.GRADES,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = if (failure.failure == UpcAuthTransportFailure.TLS)
                    DiagnosticDetail.TRANSPORT_TLS else DiagnosticDetail.TRANSPORT_NETWORK,
                reason = if (failure.failure == UpcAuthTransportFailure.TLS)
                    DiagnosticReason.TLS else DiagnosticReason.NETWORK,
            )
            return UpcAuthResult.Failure(failure.toAuthFailure())
        }
        if (response.status != 200) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.GRADES,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = DiagnosticDetail.RESPONSE_HTTP,
                reason = DiagnosticReason.HTTP,
                httpStatus = response.status,
            )
            return UpcAuthResult.Failure(
                if (response.status == 401 || response.status == 403) UpcAuthFailure.ACCESS_DENIED
                else UpcAuthFailure.UPSTREAM_ERROR,
            )
        }
        val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
        if (!contentType.contains("application/json")) {
            return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        }
        val code = StrictJson.intField(response.text(), "e")
            ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        if (code != 0) {
            // 10013 与其它业务码都不构成已认证：这里只验证登录回跳，不能把
            // “拿到 Cookie”当作成绩业务已可用。
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        val cookies = session.appCookies().filter { cookie ->
            cookie.domain == UpcGradesAuthEndpoints.APP_HOST
        }
        if (cookies.isEmpty()) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.GRADES,
            action = DiagnosticAction.AUTH_COMPLETE, result = DiagnosticResult.OK,
            phase = DiagnosticPhase.SESSION_ADOPT,
            detail = DiagnosticDetail.SESSION_ADOPTED,
            httpStatus = response.status,
            itemCount = cookies.size,
            bindingSource = DiagnosticBindingSource.PASSWORD,
            callbackState = DiagnosticCallbackState.CONSUMED,
        )
        return UpcAuthResult.Success(cookies)
    }

    /**
     * 成绩回调比课表更严格：redirect 必须存在且精确等于成绩页；from 可缺省，
     * 出现时只能是 wap。`UpcCasService.isCallbackUrl` 已拒绝未知参数与重复参数。
     */
    private fun isGradesCallback(url: HttpUrl): Boolean {
        if (!GRADES_CAS_SERVICE.isCallbackUrl(url)) return false
        if (url.queryParameter("redirect") != UpcGradesAuthEndpoints.PAGE_URL) return false
        url.queryParameter("from")?.let { if (it != "wap") return false }
        return true
    }

    private fun isAllowedAppPage(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcGradesAuthEndpoints.APP_HOST ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null
        ) {
            return false
        }
        return when (url.encodedPath) {
            UpcGradesAuthEndpoints.APP_CALLBACK_PATH -> isGradesCallback(url)
            UpcGradesAuthEndpoints.APP_INDEX_PATH,
            UpcGradesAuthEndpoints.APP_PAGE_PATH,
            -> true
            else -> false
        }
    }

    private fun resolveHttpUrl(base: String, raw: String): HttpUrl? {
        val baseUrl = base.toHttpUrlOrNull() ?: return null
        return try {
            baseUrl.resolve(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
