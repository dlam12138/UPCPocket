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
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 课表正常登录与数据入口；只允许 HTTPS 官方主机。 */
object UpcTimetableAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val APP_HOST = "app.upc.edu.cn"
    const val PAGE_URL = "https://app.upc.edu.cn/site/weekschedule/index"
    const val INDEX_URL = "https://app.upc.edu.cn/timetable/wap/default/get-index"

    /**
     * 与已审阅 UPC-MCP 固定提交一致的 service 形状：内层 redirect 不再次编码，
     * 外层带 from=wap 与 noAutoRedirect=1。不要改成普通 URL 编码形式。
     */
    const val SERVICE =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex"
    const val LOGIN_URL =
        "https://cas.upc.edu.cn/cas/login?service=$SERVICE&from=wap&noAutoRedirect=1"

    internal const val SERVICE_DECODED =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https://app.upc.edu.cn/site/weekschedule/index"

    internal const val APP_CALLBACK_PATH = "/a_upc/api/cas/login"
    internal const val APP_INDEX_PATH = "/timetable/wap/default/get-index"
    internal const val MAX_REDIRECT_HOPS = 8
}

internal val TIMETABLE_CAS_SERVICE = UpcCasService(
    casHost = UpcTimetableAuthEndpoints.CAS_HOST,
    serviceUrl = UpcTimetableAuthEndpoints.SERVICE_DECODED,
    callbackHost = UpcTimetableAuthEndpoints.APP_HOST,
    callbackPath = UpcTimetableAuthEndpoints.APP_CALLBACK_PATH,
    callbackParameterNames = setOf("ticket", "redirect", "from"),
    maxRedirectHops = UpcTimetableAuthEndpoints.MAX_REDIRECT_HOPS,
)

/**
 * 用当前统一账号的正常 CAS 表单登录建立课表业务 Cookie。
 *
 * 边界：
 *  - 只在 `cas.upc.edu.cn/cas/login` 的 HTTPS 官方表单提交一次密码；
 *  - 表单 action、service、票据回调和业务跳转逐跳核对，不自动跟随 OkHttp 重定向；
 *  - 检测到验证码/二次验证时立即返回交互登录，不提交密码、不循环重试；
 *  - 返回的 Cookie 只属于 `app.upc.edu.cn`，由课表功能以自己的加密存储保存。
 */
class UpcTimetableAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) : TimetableAuthenticator {

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
            DiagnosticModule.TIMETABLE,
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
                        operation, DiagnosticModule.TIMETABLE,
                        action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                        phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                        detail = DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED,
                        reason = DiagnosticReason.ACCOUNT,
                        credentialState = DiagnosticCredentialState.GENERATION_CHANGED,
                    )
                    UpcAuthResult.Failure(UpcAuthFailure.ACCOUNT_CHANGED)
                } else {
                    recordUpcAuthDiagnostic(
                        operation, DiagnosticModule.TIMETABLE,
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
                operation, DiagnosticModule.TIMETABLE,
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
                    operation, DiagnosticModule.TIMETABLE,
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
        operation: com.upcpocket.app.platform.diagnostics.DiagnosticOperation,
    ): UpcAuthResult {
        val login = UpcCasLogin(
            TIMETABLE_CAS_SERVICE,
            transport,
            operation,
            DiagnosticModule.TIMETABLE,
        )
            .login(credential, UpcTimetableAuthEndpoints.LOGIN_URL)
        return when (login) {
            is UpcCasLoginResult.Failure ->
                UpcAuthResult.Failure(login.reason, login.interactionUrl)
            is UpcCasLoginResult.Callback ->
                followCallbackAndVerify(login.session, login.url, operation)
        }
    }

    private fun followCallbackAndVerify(
        session: UpcCasHttpSession,
        start: HttpUrl,
        operation: com.upcpocket.app.platform.diagnostics.DiagnosticOperation,
    ): UpcAuthResult {
        var current = start
        repeat(UpcTimetableAuthEndpoints.MAX_REDIRECT_HOPS) {
            if (!isAllowedAppPage(current)) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.TIMETABLE,
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
                    operation, DiagnosticModule.TIMETABLE,
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
                    operation, DiagnosticModule.TIMETABLE,
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
                    operation, DiagnosticModule.TIMETABLE,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = DiagnosticDetail.RESPONSE_HTTP,
                    reason = DiagnosticReason.HTTP,
                    httpStatus = response.status,
                )
                return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
            }
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.TIMETABLE,
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
        operation: com.upcpocket.app.platform.diagnostics.DiagnosticOperation,
    ): UpcAuthResult {
        val response = try {
            session.request(
                "GET",
                UpcTimetableAuthEndpoints.INDEX_URL,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to UpcTimetableAuthEndpoints.PAGE_URL,
                ),
            )
        } catch (failure: UpcAuthTransportException) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.TIMETABLE,
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
                operation, DiagnosticModule.TIMETABLE,
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
        if (code == 10013) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        if (code != 0) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        val cookies = session.appCookies()
        if (cookies.isEmpty()) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.TIMETABLE,
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

    private fun isAppCallback(url: HttpUrl): Boolean {
        if (!TIMETABLE_CAS_SERVICE.isCallbackUrl(url)) return false
        url.queryParameter("redirect")?.let {
            if (it != UpcTimetableAuthEndpoints.PAGE_URL) return false
        }
        url.queryParameter("from")?.let { if (it != "wap") return false }
        return true
    }

    private fun isAllowedAppPage(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcTimetableAuthEndpoints.APP_HOST ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null
        ) {
            return false
        }
        return when (url.encodedPath) {
            UpcTimetableAuthEndpoints.APP_CALLBACK_PATH -> isAppCallback(url)
            UpcTimetableAuthEndpoints.APP_INDEX_PATH,
            "/site/weekschedule/index",
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

/** 严格的 JSON 整数读取；不接受布尔、浮点或范围外的字符串。 */
internal object StrictJson {
    fun intField(json: String, name: String): Int? = try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json)
            as? kotlinx.serialization.json.JsonObject ?: return null
        val element = root[name] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (element.isString) {
            element.content.takeIf { it.matches(Regex("[+-]?\\d+")) }?.toIntOrNull()
        } else {
            element.content.toIntOrNull()
        }
    } catch (_: Exception) {
        null
    }
}
