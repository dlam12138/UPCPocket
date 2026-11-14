/*
 * Protocol adapted from UPC-MCP 948f5f21ad7a12fd94b53403a07ac867209f7700.
 * Copyright (c) 2026 3beuml (MIT).
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeEvidence
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeType
import com.upcpocket.app.platform.diagnostics.DiagnosticCredentialState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One password submission, fixed read-only endpoints, no session persistence. */
class UpcShiguangAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) : ShiguangAuthenticator, ShiguangInteractiveAuthenticator {
    constructor(credentials: SchoolCredentialAccess) : this(credentials, OkHttpUpcAuthTransport())

    override val entryUrl: String get() = UpcShiguangWebLoginPolicy.entryUrl

    override fun inspectWebNavigation(
        url: String,
        method: String,
        isMainFrame: Boolean,
        kind: ShiguangWebRequestKind,
        operation: DiagnosticOperation?,
    ): UpcShiguangWebNavigation {
        val decision = UpcShiguangWebLoginPolicy.inspect(url, method, isMainFrame, kind)
        operation?.let { active ->
            val service = shiguangServiceParameter(url)
            val callback = decision is UpcShiguangWebNavigation.Complete
            recordUpcAuthDiagnostic(
                active,
                DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STAGE,
                result = if (decision is UpcShiguangWebNavigation.Block) DiagnosticResult.BLOCKED else DiagnosticResult.OK,
                phase = DiagnosticPhase.WEBVIEW_NAVIGATION,
                detail = if (decision is UpcShiguangWebNavigation.Block)
                    DiagnosticDetail.WEBVIEW_NAVIGATION_BLOCKED else DiagnosticDetail.WEBVIEW_NAVIGATION_ALLOWED,
                reason = if (decision is UpcShiguangWebNavigation.Block) DiagnosticReason.POLICY else null,
                serviceParameter = service,
                callbackState = if (callback) DiagnosticCallbackState.ACCEPTED else DiagnosticCallbackState.NOT_APPLICABLE,
                navigationFrame = if (isMainFrame) DiagnosticNavigationFrame.MAIN else DiagnosticNavigationFrame.SUB,
                bindingSource = if (callback) DiagnosticBindingSource.VERIFIED_WEB else DiagnosticBindingSource.NONE,
            )
        }
        return decision
    }

    override suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcShiguangAuthResult {
        val operation = Diagnostics.beginOperation()
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SHIGUANG,
            action = DiagnosticAction.AUTH_START, result = DiagnosticResult.PENDING,
            phase = DiagnosticPhase.CREDENTIAL_RESTORE, detail = DiagnosticDetail.AUTH_REQUIRED,
        )
        return withExpectedCredential(expectedAccountId, expectedCredentialGeneration, operation) {
            performLogin(it, operation)
        }
    }

    override suspend fun completeWebLoginResult(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
        callbackUrl: String,
        operation: DiagnosticOperation?,
    ): UpcShiguangAuthResult {
        val active = operation ?: Diagnostics.beginOperation()
        recordUpcAuthDiagnostic(
            active, DiagnosticModule.SHIGUANG,
            action = DiagnosticAction.AUTH_START, result = DiagnosticResult.PENDING,
            phase = DiagnosticPhase.WEBVIEW_CALLBACK,
            detail = DiagnosticDetail.WEBVIEW_CALLBACK_CONSUMED,
            callbackState = DiagnosticCallbackState.ACCEPTED,
            bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
            serviceParameter = DiagnosticServiceParameter.NOT_APPLICABLE,
        )
        val callback = upgradeShiguangTicketCallback(callbackUrl)
        if (callback == null) {
            recordUpcAuthDiagnostic(
                active, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.WEBVIEW_CALLBACK,
                detail = DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                reason = DiagnosticReason.POLICY,
                callbackState = DiagnosticCallbackState.REJECTED,
                bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
            )
            return failure(UpcShiguangAuthFailure.PROTOCOL_ERROR)
        }
        return withExpectedCredential(expectedAccountId, expectedCredentialGeneration, active) { credential ->
            finishLogin(ShiguangAuthSession(transport, active), callback, credential.loginName, active,
                DiagnosticBindingSource.VERIFIED_WEB)
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private suspend fun withExpectedCredential(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
        operation: DiagnosticOperation,
        block: suspend (SchoolLoginCredential) -> UpcShiguangAuthResult,
    ): UpcShiguangAuthResult {
        val result = try {
            credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    recordUpcAuthDiagnostic(
                        operation, DiagnosticModule.SHIGUANG,
                        action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                        phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                        detail = DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED,
                        reason = DiagnosticReason.ACCOUNT,
                        credentialState = DiagnosticCredentialState.GENERATION_CHANGED,
                    )
                    failure(UpcShiguangAuthFailure.ACCOUNT_CHANGED)
                }
                else try {
                    recordUpcAuthDiagnostic(
                        operation, DiagnosticModule.SHIGUANG,
                        result = DiagnosticResult.OK, phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                        detail = DiagnosticDetail.CREDENTIAL_RECOVERED,
                        credentialState = DiagnosticCredentialState.AVAILABLE,
                    )
                    withContext(Dispatchers.IO) { block(credential) }
                } catch (error: ShiguangAuthException) {
                    recordUpcAuthDiagnostic(
                        operation, DiagnosticModule.SHIGUANG,
                        action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                        phase = error.phase,
                        detail = error.detail,
                        reason = shiguangDiagnosticReason(error.reason),
                        httpStatus = error.httpStatus,
                        passwordSubmitted = error.passwordSubmitted,
                        challengeType = error.challengeType,
                        challengeEvidence = error.challengeEvidence,
                        serviceParameter = error.serviceParameter,
                    )
                    failure(error.reason)
                } catch (error: UpcAuthTransportException) {
                    failure(UpcShiguangAuthFailure.valueOf(error.toAuthFailure().name))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                detail = DiagnosticDetail.CREDENTIAL_UNAVAILABLE,
                reason = DiagnosticReason.ACCOUNT,
                credentialState = DiagnosticCredentialState.UNAVAILABLE,
            )
            return failure(UpcShiguangAuthFailure.PROTOCOL_ERROR)
        }
        return when (result) {
            is SchoolCredentialUseResult.Success -> result.value
            is SchoolCredentialUseResult.Failure -> {
                val changed = result.reason == SchoolCredentialFailure.CHANGED_DURING_USE
                val cleanup = result.reason == SchoolCredentialFailure.BLOCKED_BY_CLEANUP
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.SHIGUANG,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                    phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                    detail = if (changed) DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED
                    else if (cleanup) DiagnosticDetail.CREDENTIAL_CLEANUP_BLOCKED
                    else DiagnosticDetail.CREDENTIAL_UNAVAILABLE,
                    reason = DiagnosticReason.ACCOUNT,
                    credentialState = if (changed) DiagnosticCredentialState.GENERATION_CHANGED
                    else if (cleanup) DiagnosticCredentialState.CLEANUP_BLOCKED
                    else DiagnosticCredentialState.UNAVAILABLE,
                )
                failure(if (changed) UpcShiguangAuthFailure.ACCOUNT_CHANGED else UpcShiguangAuthFailure.ACCOUNT_UNAVAILABLE)
            }
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private suspend fun performLogin(
        credential: SchoolLoginCredential,
        operation: DiagnosticOperation,
    ): UpcShiguangAuthResult {
        val session = ShiguangAuthSession(transport, operation)
        val loginUrl = UpcShiguangAuthEndpoints.LOGIN_URL
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SHIGUANG,
            action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.PENDING,
            phase = DiagnosticPhase.ENTRY_GET, detail = DiagnosticDetail.AUTH_REQUIRED,
            passwordSubmitted = 0, serviceParameter = DiagnosticServiceParameter.MATCH,
        )
        val page = session.request("GET", loginUrl, phase = DiagnosticPhase.ENTRY_GET)
        val callback = if (page.status in REDIRECTS) {
            val target = upgradeShiguangTicketCallback(page.header("Location").orEmpty())
                ?: throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                    DiagnosticPhase.CAS_RETURN, DiagnosticDetail.CAS_CALLBACK_REJECTED)
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
                phase = DiagnosticPhase.CAS_RETURN, detail = DiagnosticDetail.CAS_CALLBACK_ACCEPTED,
                httpStatus = page.status, passwordSubmitted = 0,
                callbackState = DiagnosticCallbackState.ACCEPTED,
                bindingSource = DiagnosticBindingSource.BOUND_SESSION,
            )
            target
        } else {
            checkCasStatus(page, DiagnosticPhase.CAS_GET, passwordSubmitted = 0)
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
                phase = DiagnosticPhase.CAS_GET,
                detail = when (page.status) {
                    401 -> DiagnosticDetail.CAS_GET_401_FORM
                    403 -> DiagnosticDetail.CAS_GET_403_FORM
                    else -> DiagnosticDetail.CAS_FORM_ACCEPTED
                },
                httpStatus = page.status, passwordSubmitted = 0,
                serviceParameter = DiagnosticServiceParameter.MATCH,
            )
            val form = parseCasLoginForm(page.text())
            if (form.captchaRequired) interactionRequired(
                DiagnosticPhase.CAS_FORM, page.status, DiagnosticChallengeType.CAPTCHA,
                DiagnosticDetail.CAS_CHALLENGE_CONFIRMED,
            )
            if (form.hasSecondFactor) interactionRequired(
                DiagnosticPhase.CAS_FORM, page.status, DiagnosticChallengeType.SECOND_FACTOR,
                DiagnosticDetail.CAS_CHALLENGE_CONFIRMED,
            )
            if (isExplicitCasCredentialRejection(form.errorText)) {
                throw ShiguangAuthException(
                    UpcShiguangAuthFailure.INVALID_CREDENTIALS,
                    DiagnosticPhase.CAS_FORM,
                    DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION,
                )
            }
            val formRejection = shiguangPasswordFormRejection(form, loginUrl)
            if (formRejection != null) {
                throw ShiguangAuthException(
                    if (page.status == 401 || page.status == 403)
                        UpcShiguangAuthFailure.ACCESS_DENIED else UpcShiguangAuthFailure.PROTOCOL_ERROR,
                    DiagnosticPhase.CAS_FORM,
                    formRejection,
                    page.status,
                    0,
                    if (form.hasPassword) DiagnosticChallengeType.PASSWORD_FORM else DiagnosticChallengeType.UNKNOWN,
                    if (form.hasPassword) DiagnosticChallengeEvidence.ABSENT else DiagnosticChallengeEvidence.UNKNOWN,
                    shiguangServiceParameter(loginUrl),
                )
            }
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
                phase = DiagnosticPhase.CAS_FORM,
                detail = if (page.status == 401) DiagnosticDetail.CAS_GET_401_FORM
                    else if (page.status == 403) DiagnosticDetail.CAS_GET_403_FORM
                    else DiagnosticDetail.CAS_FORM_ACCEPTED,
                httpStatus = page.status, passwordSubmitted = 0,
                challengeType = DiagnosticChallengeType.PASSWORD_FORM,
                challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
                serviceParameter = DiagnosticServiceParameter.MATCH,
            )
            val action = loginUrl.toHttpUrlOrNull()!!.resolve(form.action ?: loginUrl)
                ?: throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                    DiagnosticPhase.CAS_FORM, DiagnosticDetail.CAS_FORM_REJECTED)
            val body = credential.usePassword { password ->
                buildCasFormBody(credential.loginName, String(password), form.execution.orEmpty(),
                    form.type, form.eventId, form.submit)
            }
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.PENDING,
                phase = DiagnosticPhase.CAS_POST, detail = DiagnosticDetail.AUTH_REQUIRED,
                passwordSubmitted = 1, serviceParameter = DiagnosticServiceParameter.MATCH,
                challengeType = DiagnosticChallengeType.NONE,
                challengeEvidence = DiagnosticChallengeEvidence.ABSENT,
            )
            val submitted = session.request("POST", action.toString(), mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to "https://${UpcShiguangAuthEndpoints.CAS_HOST}",
                "Referer" to loginUrl,
            ), body, DiagnosticPhase.CAS_POST, passwordSubmitted = 1)
            if (submitted.status !in REDIRECTS) {
                checkCasStatus(submitted, DiagnosticPhase.CAS_POST, passwordSubmitted = 1)
                val returned = parseCasLoginForm(submitted.text())
                if (returned.captchaRequired) interactionRequired(
                    DiagnosticPhase.CAS_POST, submitted.status, DiagnosticChallengeType.CAPTCHA,
                    DiagnosticDetail.CAS_CHALLENGE_CONFIRMED, 1,
                )
                if (returned.hasSecondFactor) interactionRequired(
                    DiagnosticPhase.CAS_POST, submitted.status, DiagnosticChallengeType.SECOND_FACTOR,
                    DiagnosticDetail.CAS_CHALLENGE_CONFIRMED, 1,
                )
                if (isExplicitCasCredentialRejection(returned.errorText)) {
                    throw ShiguangAuthException(
                        UpcShiguangAuthFailure.INVALID_CREDENTIALS,
                        DiagnosticPhase.CAS_POST,
                        DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION,
                    )
                }
                interactionRequired(
                    DiagnosticPhase.CAS_POST,
                    submitted.status,
                    if (returned.hasPassword) DiagnosticChallengeType.PASSWORD_FORM else DiagnosticChallengeType.UNKNOWN,
                    DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
                    1,
                    if (returned.hasPassword) DiagnosticChallengeEvidence.ABSENT else DiagnosticChallengeEvidence.UNKNOWN,
                )
            }
            // A 307/308 response must never replay the credential POST.
            if (submitted.status == 307 || submitted.status == 308) {
                throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                    DiagnosticPhase.CAS_POST, DiagnosticDetail.CAS_CALLBACK_REJECTED)
            }
            val target = upgradeShiguangTicketCallback(submitted.header("Location").orEmpty())
                ?: throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                    DiagnosticPhase.CAS_RETURN, DiagnosticDetail.CAS_CALLBACK_REJECTED)
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
                phase = DiagnosticPhase.CAS_RETURN, detail = DiagnosticDetail.CAS_CALLBACK_ACCEPTED,
                httpStatus = submitted.status, passwordSubmitted = 1,
                callbackState = DiagnosticCallbackState.ACCEPTED,
                bindingSource = DiagnosticBindingSource.PASSWORD,
            )
            target
        }
        return finishLogin(session, callback, credential.loginName, operation, DiagnosticBindingSource.PASSWORD)
    }

    private suspend fun finishLogin(
        session: ShiguangAuthSession,
        callback: HttpUrl,
        expectedLogin: String,
        operation: DiagnosticOperation,
        bindingSource: DiagnosticBindingSource,
    ): UpcShiguangAuthResult {
        establishBusinessSession(session, callback, operation, bindingSource)
        val list = session.request("GET", UpcShiguangAuthEndpoints.LIST_URL, phase = DiagnosticPhase.BUSINESS_SESSION)
        checkBusinessStatus(list, DiagnosticPhase.BUSINESS_SESSION)
        if (!ACTIVITY_CONTAINER.containsMatchIn(list.text())) {
            throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                DiagnosticPhase.BUSINESS_SESSION, DiagnosticDetail.SESSION_REJECTED)
        }
        if (session.businessCookies().none { it.name == "SESSION" && it.path == "/" }) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.BUSINESS_SESSION, detail = DiagnosticDetail.SESSION_REJECTED,
                reason = DiagnosticReason.AUTH, httpStatus = list.status, bindingSource = bindingSource,
            )
            throw ShiguangAuthException(UpcShiguangAuthFailure.ACCESS_DENIED,
                DiagnosticPhase.BUSINESS_SESSION, DiagnosticDetail.SESSION_REJECTED)
        }
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SHIGUANG,
            action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
            phase = DiagnosticPhase.BUSINESS_SESSION, detail = DiagnosticDetail.SESSION_REUSED,
            httpStatus = list.status, itemCount = session.businessCookies().size,
            bindingSource = bindingSource,
        )
        val report = session.request("POST", UpcShiguangAuthEndpoints.REPORT_URL,
            mapOf("Referer" to UpcShiguangAuthEndpoints.REPORT_REFERER,
                "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"), "",
            DiagnosticPhase.PERSONAL_VERIFY)
        checkBusinessStatus(report, DiagnosticPhase.PERSONAL_VERIFY)
        val verified = try {
            verifyShiguangIdentity(report.text(), expectedLogin)
        } catch (failure: ShiguangAuthException) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = when (failure.reason) {
                    UpcShiguangAuthFailure.IDENTITY_MISMATCH -> DiagnosticDetail.PERSONAL_IDENTITY_MISMATCH
                    else -> DiagnosticDetail.PERSONAL_IDENTITY_UNVERIFIED
                },
                reason = DiagnosticReason.AUTH, httpStatus = report.status, bindingSource = bindingSource,
            )
            throw ShiguangAuthException(failure.reason, DiagnosticPhase.PERSONAL_VERIFY,
                if (failure.reason == UpcShiguangAuthFailure.IDENTITY_MISMATCH)
                    DiagnosticDetail.PERSONAL_IDENTITY_MISMATCH else DiagnosticDetail.PERSONAL_IDENTITY_UNVERIFIED)
        }
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SHIGUANG,
            action = DiagnosticAction.AUTH_STAGE, result = if (verified) DiagnosticResult.OK else DiagnosticResult.PARTIAL,
            phase = DiagnosticPhase.PERSONAL_VERIFY,
            detail = if (verified) DiagnosticDetail.PERSONAL_IDENTITY_VERIFIED else DiagnosticDetail.PERSONAL_IDENTITY_UNVERIFIED,
            httpStatus = report.status, bindingSource = bindingSource,
        )
        val cookies = session.businessCookies()
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SHIGUANG,
            action = DiagnosticAction.AUTH_COMPLETE, result = DiagnosticResult.OK,
            phase = DiagnosticPhase.SESSION_ADOPT, detail = DiagnosticDetail.SESSION_ADOPTED,
            httpStatus = report.status, itemCount = cookies.size,
            callbackState = DiagnosticCallbackState.CONSUMED, bindingSource = bindingSource,
        )
        return UpcShiguangAuthResult.Success(cookies, verified)
    }

    private suspend fun establishBusinessSession(
        session: ShiguangAuthSession,
        callback: HttpUrl,
        operation: DiagnosticOperation,
        bindingSource: DiagnosticBindingSource,
    ) {
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SHIGUANG,
            action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.PENDING,
            phase = DiagnosticPhase.BUSINESS_CALLBACK, detail = DiagnosticDetail.BUSINESS_CALLBACK_ACCEPTED,
            callbackState = DiagnosticCallbackState.ACCEPTED, bindingSource = bindingSource,
        )
        var current = callback
        repeat(6) {
            val response = session.request("GET", current.toString(), phase = DiagnosticPhase.BUSINESS_CALLBACK)
            if (response.status in REDIRECTS) {
                current = resolveShiguangBusinessTarget(current, response.header("Location")
                    ?: throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                        DiagnosticPhase.BUSINESS_CALLBACK, DiagnosticDetail.BUSINESS_CALLBACK_REJECTED))
                return@repeat
            }
            checkBusinessStatus(response, DiagnosticPhase.BUSINESS_CALLBACK)
            val redirect = PC_REDIRECT.findAll(response.text()).toList()
            if (redirect.size > 1) throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                DiagnosticPhase.BUSINESS_CALLBACK, DiagnosticDetail.BUSINESS_CALLBACK_REJECTED)
            if (redirect.isNotEmpty()) {
                current = resolveShiguangBusinessTarget(current, redirect.single().groupValues[1].replace("&amp;", "&"))
                if (current.encodedPath != UpcShiguangAuthEndpoints.SECOND_STAGE) {
                    throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
                        DiagnosticPhase.BUSINESS_CALLBACK, DiagnosticDetail.BUSINESS_CALLBACK_REJECTED)
                }
                return@repeat
            }
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
                phase = DiagnosticPhase.BUSINESS_CALLBACK,
                detail = DiagnosticDetail.BUSINESS_CALLBACK_ACCEPTED,
                httpStatus = response.status, callbackState = DiagnosticCallbackState.CONSUMED,
                bindingSource = bindingSource,
            )
            return
        }
        throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR,
            DiagnosticPhase.BUSINESS_CALLBACK, DiagnosticDetail.BUSINESS_CALLBACK_REJECTED)
    }

    private fun shiguangPasswordFormRejection(
        form: CasLoginForm,
        pageUrl: String,
    ): DiagnosticDetail? {
        val rawAction = form.action ?: pageUrl
        if (runCatching { rejectAmbiguousPath(rawAction) }.isFailure) {
            return DiagnosticDetail.CAS_FORM_ACTION_REJECTED
        }
        return casPasswordFormRejection(form, pageUrl, SHIGUANG_CAS_SERVICE)
    }
}

private val SHIGUANG_CAS_SERVICE = UpcCasService(
    UpcShiguangAuthEndpoints.CAS_HOST, UpcShiguangAuthEndpoints.SERVICE,
    UpcShiguangAuthEndpoints.HOST, "/caslogin.jsp", setOf("ticket"),
)
private val REDIRECTS = setOf(301, 302, 303, 307, 308)
private val PC_REDIRECT = Regex("var\\s+pcRedirectUrl\\s*=\\s*[\"']([^\"']+)[\"']")
private val ACTIVITY_CONTAINER = Regex("<div\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\bactive_2_list\\b[^\"']*[\"']", RegexOption.IGNORE_CASE)

/** The only HTTP URL accepted is the exact CAS service callback, never requested as HTTP. */
internal fun upgradeShiguangTicketCallback(raw: String): HttpUrl? = runCatching {
    rejectAmbiguousPath(raw)
    val uri = URI(raw)
    val url = raw.toHttpUrlOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawPath != "/caslogin.jsp" ||
        uri.rawFragment != null || uri.host != UpcShiguangAuthEndpoints.HOST ||
        uri.scheme !in setOf("http", "https") || url.querySize != 1 ||
        url.queryParameterNames != setOf("ticket") ||
        url.queryParameterValues("ticket").size != 1
    ) return null
    val ticket = url.queryParameter("ticket") ?: return null
    if (!ticket.startsWith("ST-") || ticket.length !in 8..4096 ||
        ticket.any { it !in '\u0021'..'\u007e' }) return null
    url.newBuilder().scheme("https").port(443).build()
}.getOrNull()

private fun rejectAmbiguousPath(raw: String) {
    if (raw.any { it <= ' ' || it == '\\' }) protocolError()
    val path = runCatching { URI(raw).rawPath }.getOrNull() ?: protocolError()
    if (path.split('/').any { it == "." || it == ".." } ||
        path.contains('%') || path.contains("//")
    ) protocolError()
}

private fun resolveShiguangBusinessTarget(base: HttpUrl, raw: String): HttpUrl {
    rejectAmbiguousPath(raw)
    val uri = runCatching { URI(raw) }.getOrNull() ?: protocolError()
    if (uri.rawUserInfo != null || uri.port != -1) protocolError()
    val url = base.resolve(raw) ?: protocolError()
    if (url.scheme !in setOf("http", "https") || url.host != UpcShiguangAuthEndpoints.HOST ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
        url.encodedPath !in setOf(UpcShiguangAuthEndpoints.SECOND_STAGE,
            UpcShiguangAuthEndpoints.LIST_PATH, "/", "/index.jsp", "/web/index.html")
    ) protocolError()
    if (url.encodedPath == UpcShiguangAuthEndpoints.SECOND_STAGE) {
        if (url.querySize != 1 || url.queryParameterNames != setOf("userName") ||
            url.queryParameter("userName").isNullOrBlank() ||
            url.queryParameter("userName")!!.length > 4096) protocolError()
    } else if (url.querySize != 0) protocolError()
    return url.newBuilder().scheme("https").port(443).build()
}

internal fun verifyShiguangIdentity(body: String, expectedLogin: String): Boolean {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: protocolError()
    val rows = root["gridModel"] as? JsonArray ?: return false
    if (rows.isEmpty()) return false
    var complete = true
    var populated = false
    rows.forEach { element ->
        val row = element as? JsonObject ?: protocolError()
        if (row.isEmpty()) {
            complete = false
            return@forEach
        }
        populated = true
        val student = (row["stuNo"] as? JsonPrimitive)?.contentOrNull
        if (student.isNullOrBlank()) complete = false
        else if (student != expectedLogin) throw ShiguangAuthException(UpcShiguangAuthFailure.IDENTITY_MISMATCH)
    }
    return populated && complete
}

private fun checkCasStatus(response: UpcAuthHttpResponse, phase: DiagnosticPhase, passwordSubmitted: Int) {
    if (response.status == 429) throw ShiguangAuthException(
        UpcShiguangAuthFailure.ACCESS_DENIED, phase, DiagnosticDetail.CAS_RATE_LIMITED,
        response.status, passwordSubmitted,
    )
    if (response.status in 500..599) throw ShiguangAuthException(
        UpcShiguangAuthFailure.UPSTREAM_ERROR, phase, DiagnosticDetail.CAS_SERVER_ERROR,
        response.status, passwordSubmitted,
    )
    if (response.status !in setOf(200, 401, 403)) throw ShiguangAuthException(
        UpcShiguangAuthFailure.UPSTREAM_ERROR, phase, DiagnosticDetail.RESPONSE_HTTP,
        response.status, passwordSubmitted,
    )
}

private fun checkBusinessStatus(response: UpcAuthHttpResponse, phase: DiagnosticPhase) {
    if (response.status in setOf(401, 403, 429)) throw ShiguangAuthException(
        UpcShiguangAuthFailure.ACCESS_DENIED, phase,
        if (response.status == 429) DiagnosticDetail.CAS_RATE_LIMITED else DiagnosticDetail.RESPONSE_HTTP,
        response.status, passwordSubmitted = 1,
    )
    if (response.status in REDIRECTS || response.status != 200) throw ShiguangAuthException(
        UpcShiguangAuthFailure.UPSTREAM_ERROR, phase, DiagnosticDetail.RESPONSE_HTTP,
        response.status, passwordSubmitted = 1,
    )
}

internal class ShiguangAuthException(
    val reason: UpcShiguangAuthFailure,
    val phase: DiagnosticPhase = DiagnosticPhase.CAS_FORM,
    val detail: DiagnosticDetail = DiagnosticDetail.CAS_FORM_REJECTED,
    val httpStatus: Int? = null,
    val passwordSubmitted: Int? = null,
    val challengeType: DiagnosticChallengeType? = null,
    val challengeEvidence: DiagnosticChallengeEvidence? = null,
    val serviceParameter: DiagnosticServiceParameter? = null,
) : Exception(null, null, false, false)

private fun protocolError(
    phase: DiagnosticPhase = DiagnosticPhase.CAS_FORM,
    detail: DiagnosticDetail = DiagnosticDetail.CAS_FORM_REJECTED,
): Nothing = throw ShiguangAuthException(UpcShiguangAuthFailure.PROTOCOL_ERROR, phase, detail)

private fun interactionRequired(
    phase: DiagnosticPhase,
    status: Int?,
    challenge: DiagnosticChallengeType,
    detail: DiagnosticDetail,
    passwordSubmitted: Int = 0,
    evidence: DiagnosticChallengeEvidence = DiagnosticChallengeEvidence.CONFIRMED,
): Nothing = throw ShiguangAuthException(
    UpcShiguangAuthFailure.INTERACTION_REQUIRED,
    phase,
    detail,
    status,
    passwordSubmitted,
    challenge,
    evidence,
    DiagnosticServiceParameter.MATCH,
)

private fun shiguangDiagnosticReason(reason: UpcShiguangAuthFailure): DiagnosticReason = when (reason) {
    UpcShiguangAuthFailure.INTERACTION_REQUIRED -> DiagnosticReason.INTERACTION
    UpcShiguangAuthFailure.INVALID_CREDENTIALS -> DiagnosticReason.AUTH
    UpcShiguangAuthFailure.ACCESS_DENIED -> DiagnosticReason.POLICY
    UpcShiguangAuthFailure.NETWORK_UNAVAILABLE -> DiagnosticReason.NETWORK
    UpcShiguangAuthFailure.TLS_ERROR -> DiagnosticReason.TLS
    UpcShiguangAuthFailure.ACCOUNT_UNAVAILABLE,
    UpcShiguangAuthFailure.ACCOUNT_CHANGED -> DiagnosticReason.ACCOUNT
    UpcShiguangAuthFailure.IDENTITY_MISMATCH -> DiagnosticReason.AUTH
    UpcShiguangAuthFailure.UPSTREAM_ERROR -> DiagnosticReason.HTTP
    UpcShiguangAuthFailure.PROTOCOL_ERROR -> DiagnosticReason.SCHEMA
}

private fun shiguangServiceParameter(rawUrl: String): DiagnosticServiceParameter {
    val url = rawUrl.toHttpUrlOrNull() ?: return DiagnosticServiceParameter.UNKNOWN
    if (url.host != UpcShiguangAuthEndpoints.CAS_HOST || url.encodedPath != "/cas/login") {
        return DiagnosticServiceParameter.NOT_APPLICABLE
    }
    val values = url.queryParameterValues("service")
    return when {
        values.isEmpty() -> DiagnosticServiceParameter.MISSING
        values.size != 1 -> DiagnosticServiceParameter.DUPLICATE
        values.single() == UpcShiguangAuthEndpoints.SERVICE -> DiagnosticServiceParameter.MATCH
        else -> DiagnosticServiceParameter.MISMATCH
    }
}

private fun failure(reason: UpcShiguangAuthFailure) = UpcShiguangAuthResult.Failure(reason)

/** Isolated exact-host jar: no parent-domain or CAS cookies cross into business requests/results. */
private class ShiguangAuthSession(
    private val transport: UpcAuthTransport,
    private val operation: DiagnosticOperation,
) {
    private val cookies = mutableListOf<Cookie>()
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        phase: DiagnosticPhase = DiagnosticPhase.CAS_GET,
        passwordSubmitted: Int = 0,
    ): UpcAuthHttpResponse {
        currentCoroutineContext().ensureActive()
        val parsed = url.toHttpUrlOrNull() ?: protocolError()
        if (parsed.scheme != "https" || parsed.port != 443 ||
            parsed.host !in setOf(UpcShiguangAuthEndpoints.CAS_HOST, UpcShiguangAuthEndpoints.HOST)) protocolError()
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        val header = cookies.filter { it.domain == parsed.host && it.matches(parsed) }
            .joinToString("; ") { "${it.name}=${it.value}" }
        val response = try {
            transport.execute(UpcAuthHttpRequest(method, url,
                if (header.isEmpty()) headers else headers + ("Cookie" to header), body))
        } catch (failure: UpcAuthTransportException) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SHIGUANG,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = phase,
                detail = if (failure.failure == UpcAuthTransportFailure.TLS)
                    DiagnosticDetail.TRANSPORT_TLS else DiagnosticDetail.TRANSPORT_NETWORK,
                reason = if (failure.failure == UpcAuthTransportFailure.TLS)
                    DiagnosticReason.TLS else DiagnosticReason.NETWORK,
                passwordSubmitted = passwordSubmitted,
            )
            throw failure
        }
        response.headers["set-cookie"].orEmpty().forEach { raw ->
            val cookie = Cookie.parse(parsed, raw) ?: return@forEach
            if (cookie.domain != parsed.host) return@forEach
            cookies.removeAll { it.domain == cookie.domain && it.name == cookie.name && it.path == cookie.path }
            if (cookies.size < 64 && cookie.expiresAt > now) cookies += cookie
        }
        return response
    }

    fun businessCookies(): List<UpcShiguangCookie> = cookies.asSequence()
        .filter { it.domain == UpcShiguangAuthEndpoints.HOST && it.expiresAt > System.currentTimeMillis() }
        .mapNotNull { cookie -> runCatching {
            UpcShiguangCookie(cookie.name, cookie.value, cookie.domain, cookie.path,
                cookie.secure, cookie.httpOnly,
                if (cookie.persistent) cookie.expiresAt / 1000 else null)
        }.getOrNull() }.toList()
}
