package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeEvidence
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeType
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Opaque CAS flow state can exceed 4 KiB. Keep a finite cap while allowing valid server-issued tokens.
internal const val MAX_CAS_EXECUTION_CHARS = 64 * 1024

/**
 * 一个正常 CAS service 的严格边界。
 *
 * [serviceUrl] 是 CAS 回调 service 的解码值；[callbackHost]/[callbackPath] 是该 service
 * 唯一允许的业务回跳目标。CAS 登录页和业务回调都必须精确匹配，不能只按域名放行。
 */
internal data class UpcCasService(
    val casHost: String,
    val serviceUrl: String,
    val callbackHost: String,
    val callbackPath: String,
    val callbackParameterNames: Set<String>,
    val maxRedirectHops: Int = 8,
) {
    fun isCasLoginUrl(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != casHost ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/cas/login" || url.fragment != null
        ) {
            return false
        }
        val names = url.queryParameterNames
        if (names.any { it !in setOf("service", "from", "noAutoRedirect") }) return false
        names.forEach { name ->
            if (url.queryParameterValues(name).size != 1) return false
        }
        if (url.queryParameter("service") != serviceUrl) return false
        url.queryParameter("from")?.let { if (it != "wap") return false }
        url.queryParameter("noAutoRedirect")?.let { if (it != "1") return false }
        return true
    }

    fun isCallbackUrl(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != callbackHost ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != callbackPath || url.fragment != null
        ) {
            return false
        }
        val names = url.queryParameterNames
        if (names.any { it !in callbackParameterNames }) return false
        names.forEach { name ->
            if (url.queryParameterValues(name).size != 1) return false
        }
        val ticket = url.queryParameter("ticket") ?: return false
        return ticket.startsWith("ST-") && ticket.length in 8..4096
    }

}

internal sealed interface UpcCasLoginResult {
    data class Callback(
        val url: HttpUrl,
        val session: UpcCasHttpSession,
        val passwordSubmitted: Boolean = false,
    ) : UpcCasLoginResult {
        override fun toString(): String = "UpcCasLoginResult.Callback([REDACTED])"
    }

    data class Failure(
        val reason: UpcAuthFailure,
        val interactionUrl: String? = null,
    ) : UpcCasLoginResult
}

/**
 * CAS 表单登录的公共部分：只向精确的 CAS HTTPS 表单提交一次密码，拿到 service 回调
 * 票据后交给业务 provider 继续。密码、Cookie、票据都不离开本模块。
 */
internal class UpcCasLogin(
    private val service: UpcCasService,
    private val transport: UpcAuthTransport,
    private val operation: DiagnosticOperation = Diagnostics.beginOperation(),
    private val module: DiagnosticModule = DiagnosticModule.LIBRARY,
) {
    @OptIn(RestrictedSchoolCredentialUse::class)
    fun login(
        credential: SchoolLoginCredential,
        casUrl: String,
        existingSession: UpcCasHttpSession? = null,
        allowExistingSessionCallback: Boolean = false,
    ): UpcCasLoginResult {
        val cas = casUrl.toHttpUrlOrNull()
            ?: return fail(
                UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.ENTRY_GET,
                DiagnosticDetail.CAS_FORM_REJECTED,
            )
        if (!service.isCasLoginUrl(cas)) {
            return fail(
                UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.ENTRY_GET,
                DiagnosticDetail.SERVICE_PARAMETER_MISMATCH,
                serviceParameter = serviceParameterState(cas),
            )
        }
        record(
            DiagnosticAction.AUTH_START,
            DiagnosticResult.PENDING,
            DiagnosticPhase.ENTRY_GET,
            DiagnosticDetail.AUTH_REQUIRED,
            serviceParameter = DiagnosticServiceParameter.MATCH,
        )
        val session = existingSession ?: UpcCasHttpSession(
            transport,
            UpcAuthCookieJar(setOf(service.casHost, service.callbackHost)),
        )
        val page = try {
            session.request("GET", casUrl)
        } catch (failure: UpcAuthTransportException) {
            return transportFailure(failure, DiagnosticPhase.CAS_GET, passwordSubmitted = 0)
        }
        if (page.status in 300..399) {
            val location = page.headers["location"]?.singleOrNull()
                ?: return fail(
                    UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_GET,
                    DiagnosticDetail.CAS_CALLBACK_REJECTED, page.status, passwordSubmitted = 0,
                )
            val target = resolveHttpUrl(casUrl, location)
                ?: return fail(
                    UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_GET,
                    DiagnosticDetail.CAS_CALLBACK_REJECTED, page.status, passwordSubmitted = 0,
                )
            if (allowExistingSessionCallback && existingSession != null && service.isCallbackUrl(target)) {
                record(
                    DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK, DiagnosticPhase.CAS_RETURN,
                    DiagnosticDetail.CAS_CALLBACK_ACCEPTED, page.status, passwordSubmitted = 0,
                    callbackState = com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState.ACCEPTED,
                    bindingSource = com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource.BOUND_SESSION,
                )
                return UpcCasLoginResult.Callback(target, session)
            }
            return fail(
                UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_GET,
                DiagnosticDetail.CAS_CALLBACK_REJECTED, page.status, passwordSubmitted = 0,
            )
        }
        if (page.status == 429) return fail(
            UpcAuthFailure.ACCESS_DENIED, DiagnosticPhase.CAS_GET,
            DiagnosticDetail.CAS_RATE_LIMITED, page.status, passwordSubmitted = 0,
        )
        if (page.status !in setOf(200, 401, 403)) return fail(
            UpcAuthFailure.UPSTREAM_ERROR, DiagnosticPhase.CAS_GET,
            DiagnosticDetail.CAS_SERVER_ERROR, page.status, passwordSubmitted = 0,
        )

        val pageHtml = page.text()
        val form = parseForm(pageHtml)
        if (form != null && challengeType(form) in setOf(
                DiagnosticChallengeType.CAPTCHA,
                DiagnosticChallengeType.SECOND_FACTOR,
            )
        ) {
            return interaction(
                casUrl, DiagnosticPhase.CAS_FORM, page.status,
                challengeType(form), passwordSubmitted = 0,
            )
        }
        if (form != null && explicitCredentialRejection(form.errorText)) {
            return fail(
                UpcAuthFailure.INVALID_CREDENTIALS, DiagnosticPhase.CAS_FORM,
                DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION, page.status,
                passwordSubmitted = 0,
                challengeType = DiagnosticChallengeType.NONE,
                challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
            )
        }
        val formRejection = casPasswordFormRejection(form, casUrl, service)
        if (formRejection != null) {
            return if (page.status == 401 || page.status == 403) {
                fail(
                    UpcAuthFailure.ACCESS_DENIED, DiagnosticPhase.CAS_FORM,
                    formRejection, page.status, passwordSubmitted = 0,
                    challengeType = if (form == null) DiagnosticChallengeType.UNKNOWN else challengeType(form),
                    challengeEvidence = if (form == null) DiagnosticChallengeEvidence.UNKNOWN
                    else DiagnosticChallengeEvidence.ABSENT,
                    serviceParameter = serviceParameterState(cas),
                )
            } else {
                fail(
                    UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_FORM,
                    formRejection, page.status, passwordSubmitted = 0,
                    challengeType = if (form == null) DiagnosticChallengeType.UNKNOWN else challengeType(form),
                    challengeEvidence = if (form == null) DiagnosticChallengeEvidence.UNKNOWN
                    else DiagnosticChallengeEvidence.ABSENT,
                    serviceParameter = serviceParameterState(cas),
                )
            }
        }
        record(
            DiagnosticAction.AUTH_STAGE,
            DiagnosticResult.OK,
            DiagnosticPhase.CAS_FORM,
            when (page.status) {
                401 -> DiagnosticDetail.CAS_GET_401_FORM
                403 -> DiagnosticDetail.CAS_GET_403_FORM
                else -> DiagnosticDetail.CAS_FORM_ACCEPTED
            },
            page.status,
            passwordSubmitted = 0,
            challengeType = DiagnosticChallengeType.PASSWORD_FORM,
            challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
            serviceParameter = DiagnosticServiceParameter.MATCH,
        )
        val validatedForm = requireNotNull(form)
        val action = resolveCasFormAction(validatedForm.action, casUrl, service)
            ?: return fail(
                UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_FORM,
                DiagnosticDetail.CAS_FORM_REJECTED, page.status, passwordSubmitted = 0,
            )

        val body = credential.usePassword { password ->
            buildCasFormBody(
                username = credential.loginName,
                password = String(password),
                execution = validatedForm.execution.orEmpty(),
                type = validatedForm.type,
                eventId = validatedForm.eventId,
                submit = validatedForm.submit,
            )
        }
        record(
            DiagnosticAction.AUTH_STAGE,
            DiagnosticResult.PENDING,
            DiagnosticPhase.CAS_POST,
            DiagnosticDetail.AUTH_REQUIRED,
            page.status,
            passwordSubmitted = 1,
            challengeType = DiagnosticChallengeType.NONE,
            challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
            serviceParameter = DiagnosticServiceParameter.MATCH,
        )
        val submitted = try {
            session.request(
                "POST",
                action,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to "https://${service.casHost}",
                    "Referer" to casUrl,
                ),
                body = body,
            )
        } catch (failure: UpcAuthTransportException) {
            return transportFailure(failure, DiagnosticPhase.CAS_POST, passwordSubmitted = 1)
        }

        if (submitted.status in 300..399) {
            val location = submitted.headers["location"]?.singleOrNull()
                ?: return fail(
                    UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_POST,
                    DiagnosticDetail.CAS_CALLBACK_REJECTED, submitted.status, passwordSubmitted = 1,
                )
            val target = resolveHttpUrl(action, location)
                ?: return fail(
                    UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_POST,
                    DiagnosticDetail.CAS_CALLBACK_REJECTED, submitted.status, passwordSubmitted = 1,
                )
            return when {
                service.isCasLoginUrl(target) -> classifyCasReturn(session, target, casUrl)
                service.isCallbackUrl(target) -> {
                    record(
                        DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK, DiagnosticPhase.CAS_POST,
                        DiagnosticDetail.CAS_CALLBACK_ACCEPTED, submitted.status, passwordSubmitted = 1,
                        callbackState = com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState.ACCEPTED,
                        bindingSource = com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource.PASSWORD,
                    )
                    UpcCasLoginResult.Callback(target, session, passwordSubmitted = true)
                }
                else -> fail(
                    UpcAuthFailure.PROTOCOL_ERROR, DiagnosticPhase.CAS_POST,
                    DiagnosticDetail.CAS_CALLBACK_REJECTED, submitted.status, passwordSubmitted = 1,
                    callbackState = com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState.REJECTED,
                )
            }
        }
        if (submitted.status == 429) return fail(
            UpcAuthFailure.ACCESS_DENIED, DiagnosticPhase.CAS_POST,
            DiagnosticDetail.CAS_RATE_LIMITED, submitted.status, passwordSubmitted = 1,
        )
        if (submitted.status !in setOf(200, 401, 403)) return fail(
            UpcAuthFailure.UPSTREAM_ERROR, DiagnosticPhase.CAS_POST,
            DiagnosticDetail.CAS_SERVER_ERROR, submitted.status, passwordSubmitted = 1,
        )
        val returned = parseForm(submitted.text())
        val returnedChallenge = returned?.let(::challengeType)
        if (returnedChallenge in setOf(
                DiagnosticChallengeType.CAPTCHA,
                DiagnosticChallengeType.SECOND_FACTOR,
            )
        ) {
            return interaction(
                casUrl, DiagnosticPhase.CAS_POST, submitted.status,
                requireNotNull(returnedChallenge), passwordSubmitted = 1,
            )
        }
        if (returned != null && explicitCredentialRejection(returned.errorText)) {
            return fail(
                UpcAuthFailure.INVALID_CREDENTIALS, DiagnosticPhase.CAS_POST,
                DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION, submitted.status,
                passwordSubmitted = 1,
                challengeType = DiagnosticChallengeType.NONE,
                challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
            )
        }
        return interaction(
            casUrl,
            DiagnosticPhase.CAS_POST,
            submitted.status,
            if (returned?.hasPassword == true) DiagnosticChallengeType.PASSWORD_FORM
            else DiagnosticChallengeType.UNKNOWN,
            passwordSubmitted = 1,
            detail = DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
            evidence = if (returned == null) DiagnosticChallengeEvidence.UNKNOWN
            else DiagnosticChallengeEvidence.ABSENT,
        )
    }

    private fun classifyCasReturn(
        session: UpcCasHttpSession,
        url: HttpUrl,
        interactionUrl: String,
    ): UpcCasLoginResult {
        val response = try {
            session.request("GET", url.toString())
        } catch (failure: UpcAuthTransportException) {
            return transportFailure(failure, DiagnosticPhase.CAS_RETURN, passwordSubmitted = 1)
        }
        if (response.status == 429) return fail(
            UpcAuthFailure.ACCESS_DENIED, DiagnosticPhase.CAS_RETURN,
            DiagnosticDetail.CAS_RATE_LIMITED, response.status, passwordSubmitted = 1,
        )
        if (response.status in 500..599) return fail(
            UpcAuthFailure.UPSTREAM_ERROR, DiagnosticPhase.CAS_RETURN,
            DiagnosticDetail.CAS_SERVER_ERROR, response.status, passwordSubmitted = 1,
        )
        if (response.status == 401 || response.status == 403) {
            val form = parseForm(response.text())
            val challenge = form?.let(::challengeType)
            return when {
                challenge == DiagnosticChallengeType.CAPTCHA ||
                    challenge == DiagnosticChallengeType.SECOND_FACTOR ->
                    interaction(
                        interactionUrl, DiagnosticPhase.CAS_RETURN, response.status,
                        requireNotNull(challenge), passwordSubmitted = 1,
                    )
                form != null && explicitCredentialRejection(form.errorText) ->
                    fail(
                        UpcAuthFailure.INVALID_CREDENTIALS, DiagnosticPhase.CAS_RETURN,
                        DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION, response.status,
                        passwordSubmitted = 1,
                        challengeType = DiagnosticChallengeType.NONE,
                        challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
                    )
                else -> interaction(
                    interactionUrl, DiagnosticPhase.CAS_RETURN, response.status,
                    if (form?.hasPassword == true) DiagnosticChallengeType.PASSWORD_FORM
                    else DiagnosticChallengeType.UNKNOWN,
                    passwordSubmitted = 1,
                    detail = DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
                    evidence = if (form == null) DiagnosticChallengeEvidence.UNKNOWN
                    else DiagnosticChallengeEvidence.ABSENT,
                )
            }
        }
        if (response.status != 200) {
            return fail(
                UpcAuthFailure.UPSTREAM_ERROR, DiagnosticPhase.CAS_RETURN,
                DiagnosticDetail.CAS_SERVER_ERROR, response.status, passwordSubmitted = 1,
            )
        }
        val form = parseForm(response.text())
            ?: return interaction(
                interactionUrl, DiagnosticPhase.CAS_RETURN, response.status,
                DiagnosticChallengeType.UNKNOWN, passwordSubmitted = 1,
                detail = DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
                evidence = DiagnosticChallengeEvidence.UNKNOWN,
            )
        return when {
            form.captchaRequired -> interaction(
                interactionUrl, DiagnosticPhase.CAS_RETURN, response.status,
                DiagnosticChallengeType.CAPTCHA, passwordSubmitted = 1,
            )
            form.hasSecondFactor -> interaction(
                interactionUrl, DiagnosticPhase.CAS_RETURN, response.status,
                DiagnosticChallengeType.SECOND_FACTOR, passwordSubmitted = 1,
            )
            explicitCredentialRejection(form.errorText) -> fail(
                UpcAuthFailure.INVALID_CREDENTIALS, DiagnosticPhase.CAS_RETURN,
                DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION, response.status,
                passwordSubmitted = 1,
                challengeType = DiagnosticChallengeType.NONE,
                challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
            )
            else -> interaction(
                interactionUrl, DiagnosticPhase.CAS_RETURN, response.status,
                if (form.hasPassword) DiagnosticChallengeType.PASSWORD_FORM
                else DiagnosticChallengeType.UNKNOWN,
                passwordSubmitted = 1,
                detail = DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
                evidence = DiagnosticChallengeEvidence.ABSENT,
            )
        }
    }

    private fun parseForm(html: String): CasLoginForm? =
        runCatching { parseCasLoginForm(html) }.getOrNull()

    private fun challengeType(form: CasLoginForm): DiagnosticChallengeType = when {
        form.captchaRequired -> DiagnosticChallengeType.CAPTCHA
        form.hasSecondFactor -> DiagnosticChallengeType.SECOND_FACTOR
        form.hasPassword -> DiagnosticChallengeType.PASSWORD_FORM
        else -> DiagnosticChallengeType.UNKNOWN
    }

    private fun explicitCredentialRejection(errorText: String?): Boolean =
        isExplicitCasCredentialRejection(errorText)

    private fun serviceParameterState(url: HttpUrl): DiagnosticServiceParameter {
        val values = url.queryParameterValues("service")
        return when {
            values.isEmpty() -> DiagnosticServiceParameter.MISSING
            values.size != 1 -> DiagnosticServiceParameter.DUPLICATE
            values.single() == service.serviceUrl -> DiagnosticServiceParameter.MATCH
            else -> DiagnosticServiceParameter.MISMATCH
        }
    }

    private fun interaction(
        url: String,
        phase: DiagnosticPhase,
        status: Int?,
        challenge: DiagnosticChallengeType,
        passwordSubmitted: Int,
        detail: DiagnosticDetail = DiagnosticDetail.CAS_CHALLENGE_CONFIRMED,
        evidence: DiagnosticChallengeEvidence = DiagnosticChallengeEvidence.CONFIRMED,
    ) = fail(
        UpcAuthFailure.INTERACTION_REQUIRED,
        phase,
        detail,
        status,
        interactionUrl = url,
        passwordSubmitted = passwordSubmitted,
        challengeType = challenge,
        challengeEvidence = evidence,
    )

    private fun transportFailure(
        failure: UpcAuthTransportException,
        phase: DiagnosticPhase,
        passwordSubmitted: Int,
    ): UpcCasLoginResult.Failure {
        val reason = failure.toAuthFailure()
        val diagnosticReason = when (reason) {
            UpcAuthFailure.NETWORK_UNAVAILABLE -> DiagnosticReason.NETWORK
            UpcAuthFailure.TLS_ERROR -> DiagnosticReason.TLS
            UpcAuthFailure.UPSTREAM_ERROR -> DiagnosticReason.HTTP
            else -> DiagnosticReason.UNKNOWN
        }
        record(
            DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL, phase,
            when (reason) {
                UpcAuthFailure.NETWORK_UNAVAILABLE -> DiagnosticDetail.TRANSPORT_NETWORK
                UpcAuthFailure.TLS_ERROR -> DiagnosticDetail.TRANSPORT_TLS
                else -> DiagnosticDetail.RESPONSE_HTTP
            },
            reason = diagnosticReason,
            passwordSubmitted = passwordSubmitted,
        )
        return UpcCasLoginResult.Failure(reason)
    }

    private fun fail(
        reason: UpcAuthFailure,
        phase: DiagnosticPhase,
        detail: DiagnosticDetail,
        status: Int? = null,
        interactionUrl: String? = null,
        passwordSubmitted: Int = 0,
        challengeType: DiagnosticChallengeType? = null,
        challengeEvidence: DiagnosticChallengeEvidence? = null,
        serviceParameter: DiagnosticServiceParameter? = null,
        callbackState: com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState? = null,
    ): UpcCasLoginResult.Failure {
        val diagnosticReason = when (reason) {
            UpcAuthFailure.NETWORK_UNAVAILABLE -> DiagnosticReason.NETWORK
            UpcAuthFailure.TLS_ERROR -> DiagnosticReason.TLS
            UpcAuthFailure.INTERACTION_REQUIRED -> DiagnosticReason.INTERACTION
            UpcAuthFailure.ACCESS_DENIED -> DiagnosticReason.POLICY
            UpcAuthFailure.INVALID_CREDENTIALS -> DiagnosticReason.AUTH
            UpcAuthFailure.UPSTREAM_ERROR -> DiagnosticReason.HTTP
            UpcAuthFailure.ACCOUNT_UNAVAILABLE,
            UpcAuthFailure.ACCOUNT_CHANGED,
            -> DiagnosticReason.ACCOUNT
            UpcAuthFailure.PROTOCOL_ERROR -> DiagnosticReason.SCHEMA
        }
        record(
            if (reason == UpcAuthFailure.INTERACTION_REQUIRED) DiagnosticAction.AUTH_INTERACTION
            else DiagnosticAction.AUTH_STOP,
            if (reason == UpcAuthFailure.INTERACTION_REQUIRED) DiagnosticResult.BLOCKED
            else DiagnosticResult.FAIL,
            phase,
            detail,
            status,
            diagnosticReason,
            passwordSubmitted,
            challengeType,
            challengeEvidence,
            serviceParameter,
            callbackState,
        )
        return UpcCasLoginResult.Failure(reason, interactionUrl)
    }

    private fun record(
        action: DiagnosticAction,
        result: DiagnosticResult,
        phase: DiagnosticPhase,
        detail: DiagnosticDetail?,
        status: Int? = null,
        reason: DiagnosticReason? = null,
        passwordSubmitted: Int? = null,
        challengeType: DiagnosticChallengeType? = null,
        challengeEvidence: DiagnosticChallengeEvidence? = null,
        serviceParameter: DiagnosticServiceParameter? = null,
        callbackState: com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState? = null,
        bindingSource: com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource? = null,
    ) {
        Diagnostics.record(
            module = module,
            action = action,
            result = result,
            reason = reason,
            httpStatus = status,
            operation = operation,
            phase = phase,
            detail = detail,
            passwordSubmitted = passwordSubmitted,
            challengeType = challengeType,
            challengeEvidence = challengeEvidence,
            serviceParameter = serviceParameter,
            callbackState = callbackState,
            bindingSource = bindingSource,
        )
    }
}

/** Returns only a bounded rule identifier; form values and markup never enter diagnostics. */
internal fun casPasswordFormRejection(
    form: CasLoginForm?,
    pageUrl: String,
    service: UpcCasService,
): DiagnosticDetail? {
    if (form == null) return DiagnosticDetail.CAS_FORM_NOT_FOUND
    if (!form.hasPassword) return DiagnosticDetail.CAS_FORM_PASSWORD_FIELD_MISSING
    if (form.captchaRequired || form.hasSecondFactor) return DiagnosticDetail.CAS_FORM_REJECTED
    if (!form.hasTypeField) return DiagnosticDetail.CAS_FORM_TYPE_FIELD_MISSING
    if (form.type != "username_password") return DiagnosticDetail.CAS_FORM_TYPE_REJECTED
    if (!form.hasEventIdField) return DiagnosticDetail.CAS_FORM_EVENT_FIELD_MISSING
    if (form.eventId != "submit") return DiagnosticDetail.CAS_FORM_EVENT_REJECTED
    val execution = form.execution ?: return DiagnosticDetail.CAS_FORM_EXECUTION_MISSING
    if (!isValidCasExecution(execution)) return DiagnosticDetail.CAS_FORM_EXECUTION_INVALID
    if (resolveCasFormAction(form.action, pageUrl, service) == null) {
        return DiagnosticDetail.CAS_FORM_ACTION_REJECTED
    }
    return null
}

internal fun isValidCasExecution(execution: String?): Boolean = execution != null &&
    execution.isNotBlank() && execution.length <= MAX_CAS_EXECUTION_CHARS &&
    execution.none { it <= ' ' || it == '\u007f' }

internal fun isExplicitCasCredentialRejection(errorText: String?): Boolean {
    val normalized = errorText.orEmpty().replace(Regex("\\s+"), "")
    return normalized in setOf(
        "用户名或密码错误",
        "账号或密码错误",
        "用户名或密码不正确",
        "账号或密码不正确",
        "密码错误",
        "密码不正确",
        "Invalidusernameorpassword",
        "Invalidcredentials",
    )
}

/** 认证期间的内存 Cookie，只保存 CAS 与目标业务主机。 */
internal class UpcAuthCookieJar(private val allowedHosts: Set<String>) {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    fun save(url: String, setCookieHeaders: List<String>) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        setCookieHeaders.forEach { header ->
            val parsed = Cookie.parse(httpUrl, header) ?: return@forEach
            val domain = parsed.domain.lowercase(Locale.ROOT).trimStart('.')
            if (domain !in allowedHosts) return@forEach
            cookies.removeAll {
                it.name == parsed.name && it.domain == parsed.domain && it.path == parsed.path
            }
            if (parsed.expiresAt > System.currentTimeMillis() && cookies.size < 64) cookies += parsed
        }
    }

    @Synchronized
    fun header(url: String): String? {
        return cookiesForUrl(url)
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun cookiesForUrl(url: String): List<Cookie> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(httpUrl) }.toList()
    }

    @Synchronized
    fun appCookies(): List<UpcAuthCookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.asSequence()
            .filter { it.domain.lowercase(Locale.ROOT).trimStart('.') == UpcAuthCookie.HOST }
            .mapNotNull { cookie ->
                runCatching {
                    UpcAuthCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = UpcAuthCookie.HOST,
                        path = cookie.path,
                        secure = cookie.secure,
                        httpOnly = cookie.httpOnly,
                        expiresAtEpochSeconds = cookie.expiresAt
                            .takeIf { it > 0 && it != Long.MAX_VALUE }
                            ?.div(1000),
                    )
                }.getOrNull()
            }
            .distinctBy { it.name to it.path }
            .toList()
    }

    @Synchronized
    fun cookiesForHost(host: String): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.domain == host }.toList()
    }

    @Synchronized
    fun clear() = cookies.clear()
}

/** 带 Cookie 与逐跳校验的认证请求会话；不自动跟随重定向。 */
internal class UpcCasHttpSession(
    private val transport: UpcAuthTransport,
    private val jar: UpcAuthCookieJar,
) {
    private val stateLock = Any()

    @Volatile
    private var cleared = false

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): UpcAuthHttpResponse = requestWithCookieInfo(method, url, headers, body).response

    fun requestWithCookieInfo(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): UpcAuthSessionRequestResult {
        val requestCookies = synchronized(stateLock) {
            if (cleared) throw UpcAuthTransportException(UpcAuthTransportFailure.PROTOCOL)
            jar.cookiesForUrl(url)
        }
        val cookieHeader = requestCookies.joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
        val merged = if (cookieHeader == null) {
            headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        } else {
            headers.filterKeys { !it.equals("Cookie", ignoreCase = true) } + ("Cookie" to cookieHeader)
        }
        val response = transport.execute(UpcAuthHttpRequest(method, url, merged, body))
        synchronized(stateLock) {
            if (!cleared) jar.save(url, response.headers["set-cookie"].orEmpty())
        }
        return UpcAuthSessionRequestResult(
            response = response,
            sentCookies = requestCookies.map {
                UpcAuthCookieReference(
                    name = it.name,
                    value = it.value,
                    domain = it.domain,
                    path = it.path,
                    secure = it.secure,
                    httpOnly = it.httpOnly,
                    hostOnly = it.hostOnly,
                )
            },
        )
    }

    fun appCookies(): List<UpcAuthCookie> = jar.appCookies()

    /** 返回指定精确 host 的内存 Cookie；调用方负责决定可导出的业务范围。 */
    fun cookiesForHost(host: String): List<Cookie> = jar.cookiesForHost(host)

    /** 清除认证会话持有的内存 Cookie；不执行网络请求。 */
    fun clear() {
        synchronized(stateLock) {
            cleared = true
            jar.clear()
        }
    }

    /** 只返回与目标 URL 的 host/path/secure 匹配的 Cookie；调用方不得记录返回值。 */
    fun cookieHeader(url: String): String? = jar.header(url)
}

/** Contains sensitive cookie credentials in memory; toString is redacted, and this value must never be logged. */
internal class UpcAuthSessionRequestResult(
    val response: UpcAuthHttpResponse,
    val sentCookies: List<UpcAuthCookieReference>,
) {
    override fun toString(): String =
        "UpcAuthSessionRequestResult(sentCookieCount=${sentCookies.size})"
}

/** Cookie identity includes its value and scope; toString is redacted. */
internal class UpcAuthCookieReference(
    private val name: String,
    private val value: String,
    private val domain: String,
    private val path: String,
    private val secure: Boolean,
    private val httpOnly: Boolean,
    private val hostOnly: Boolean,
) {
    /** Expiry is intentionally excluded so a same-value Set-Cookie renewal can remain valid. */
    fun matches(
        name: String,
        value: String,
        domain: String,
        path: String,
        secure: Boolean,
        httpOnly: Boolean,
        hostOnly: Boolean,
    ): Boolean = this.name == name && this.value == value && this.domain == domain && this.path == path &&
        this.secure == secure && this.httpOnly == httpOnly && this.hostOnly == hostOnly

    override fun toString(): String = "UpcAuthCookieReference([REDACTED])"
}

internal fun UpcAuthHttpResponse.text(): String = body.toString(StandardCharsets.UTF_8)

internal fun UpcAuthTransportException.toAuthFailure(): UpcAuthFailure =
    when (failure) {
        UpcAuthTransportFailure.NETWORK,
        UpcAuthTransportFailure.TIMEOUT,
        -> UpcAuthFailure.NETWORK_UNAVAILABLE
        UpcAuthTransportFailure.TLS -> UpcAuthFailure.TLS_ERROR
        UpcAuthTransportFailure.TOO_LARGE,
        UpcAuthTransportFailure.PROTOCOL,
        -> UpcAuthFailure.UPSTREAM_ERROR
    }

internal data class CasLoginForm(
    val action: String?,
    val execution: String?,
    val type: String,
    val eventId: String,
    val submit: String,
    val captchaRequired: Boolean,
    val errorText: String?,
    val hasPassword: Boolean,
    val hasSecondFactor: Boolean,
    val hasTypeField: Boolean,
    val hasEventIdField: Boolean,
)

private fun resolveCasFormAction(
    raw: String?,
    pageUrl: String,
    service: UpcCasService,
): String? {
    val action = resolveHttpUrl(pageUrl, raw ?: pageUrl) ?: return null
    return if (service.isCasLoginUrl(action)) action.toString() else null
}

private fun resolveHttpUrl(base: String, raw: String): HttpUrl? {
    val baseUrl = base.toHttpUrlOrNull() ?: return null
    return try {
        baseUrl.resolve(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun parseCasLoginForm(html: String): CasLoginForm {
    val markup = CasCaptchaChallengeDetector.visibleMarkup(html)
    val forms = CAS_FORM.findAll(markup).map { it.value }.toList()
    val chosen = forms.firstOrNull {
        isVisibleCasForm(it) && CAS_PASSWORD_INPUT.containsMatchIn(it)
    } ?: forms.firstOrNull(::isVisibleCasForm)
    val fields = linkedMapOf<String, String>()
    var hasPassword = false
    var action: String? = null
    chosen?.let { form ->
        val open = CAS_FORM_OPEN.find(form)?.groupValues?.get(1).orEmpty()
        action = casAttribute(open, "action")
        CAS_INPUT.findAll(form).forEach { match ->
            val attrs = match.groupValues[1]
            val name = casAttribute(attrs, "name") ?: return@forEach
            if (casAttribute(attrs, "type").equals("password", true)) hasPassword = true
            fields.putIfAbsent(name, casAttribute(attrs, "value").orEmpty())
        }
    }
    val error = CAS_ERROR_DIV.find(markup)?.groupValues?.get(1)
        ?.replace(CAS_TAG, " ")
        ?.let(::decodeCasEntities)
        ?.replace(CAS_WHITESPACE, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val captcha = CasCaptchaChallengeDetector.inspect(html).active
    val secondFactor = if (chosen != null && hasPassword) {
        hasActiveSecondFactor(chosen, fields["type"])
    } else {
        forms.any { form -> hasActiveSecondFactor(form, activeFormType(form)) }
    }
    return CasLoginForm(
        action = action,
        execution = fields["execution"],
        type = fields["type"] ?: "username_password",
        eventId = fields["_eventId"] ?: "submit",
        submit = fields["submit"] ?: "登录",
        captchaRequired = captcha,
        errorText = error,
        hasPassword = hasPassword,
        hasSecondFactor = secondFactor,
        hasTypeField = fields.containsKey("type"),
        hasEventIdField = fields.containsKey("_eventId"),
    )
}

private fun activeFormType(form: String): String? = CAS_INPUT.findAll(form).firstOrNull { match ->
    casAttribute(match.groupValues[1], "name").equals("type", ignoreCase = true)
}?.let { match -> casAttribute(match.groupValues[1], "value") }

private fun isVisibleCasForm(form: String): Boolean {
    val open = CAS_FORM_OPEN.find(form)?.groupValues?.get(1).orEmpty()
    if (CAS_HIDDEN_ATTRIBUTE.containsMatchIn(open) ||
        casAttribute(open, "aria-hidden").equals("true", ignoreCase = true)
    ) {
        return false
    }
    val style = casAttribute(open, "style").orEmpty().filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)
    return "display:none" !in style && "visibility:hidden" !in style
}

private fun hasActiveSecondFactor(form: String, formType: String?): Boolean {
    if (!isVisibleCasForm(form)) return false
    if (formType.equals("username_smstoken", ignoreCase = true)) return true
    return CAS_INPUT.findAll(form).any { match ->
        val attributes = match.groupValues[1]
        val name = casAttribute(attributes, "name")
        val type = casAttribute(attributes, "type")
        name.equals("username_smstoken", ignoreCase = true) &&
            !type.equals("hidden", ignoreCase = true)
    }
}

private fun casAttribute(attrs: String, name: String): String? =
    CAS_ATTRIBUTE.findAll(attrs).firstOrNull {
        it.groupValues[1].equals(name, true)
    }?.groupValues?.let { groups ->
        groups[2].ifEmpty { groups[3].ifEmpty { groups[4] } }
    }?.let(::decodeCasEntities)

internal fun buildCasFormBody(
    username: String,
    password: String,
    execution: String,
    type: String,
    eventId: String,
    submit: String,
): String = listOf(
    "username" to username,
    "password" to password,
    "type" to type,
    "execution" to execution,
    "_eventId" to eventId,
    "submit" to submit,
).joinToString("&") { (name, value) ->
    URLEncoder.encode(name, StandardCharsets.UTF_8.name()) + "=" +
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private fun decodeCasEntities(value: String): String = value
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")

private val CAS_FORM = Regex(
    "<form\\b[^>]*>.*?</form>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val CAS_FORM_OPEN = Regex("<form\\b([^>]*)>", RegexOption.IGNORE_CASE)
private val CAS_INPUT = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
private val CAS_HIDDEN_ATTRIBUTE = Regex("(?:^|\\s)hidden(?:\\s|=|$)", RegexOption.IGNORE_CASE)
private val CAS_PASSWORD_INPUT = Regex(
    "<input\\b[^>]*type\\s*=\\s*[\"']password[\"']",
    RegexOption.IGNORE_CASE,
)
private val CAS_ERROR_DIV = Regex(
    "<div\\b[^>]*id\\s*=\\s*[\"']errorDiv[\"'][^>]*>(.*?)</div>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val CAS_TAG = Regex("<[^>]+>")
private val CAS_WHITESPACE = Regex("\\s+")
private val CAS_ATTRIBUTE = Regex(
    "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
)
