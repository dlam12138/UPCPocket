package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBodyType
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticCredentialState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticMediaType
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** 固定的 e站通 CAS 与业务入口。service 内层 redirect_url 保留一次百分号编码。 */
object UpcServicePortalAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val HOST = UpcServicePortalCookie.HOST
    const val REDIRECT_URL = "https://service.upc.edu.cn/v2/site/index"
    const val CALLBACK_SERVICE_URL =
        "https://service.upc.edu.cn/site/login/cas-login?redirect_url=" +
            "https%3A%2F%2Fservice.upc.edu.cn%2Fv2%2Fsite%2Findex"
    const val SESSION_CHECK_URL = "https://service.upc.edu.cn/site/message/count"

    /** CAS 解码一个 service 参数后得到的精确 HTTPS 值。 */
    const val SERVICE = CALLBACK_SERVICE_URL

    internal const val LOGIN_URL =
        "https://cas.upc.edu.cn/cas/login?service=" +
            "https%3A%2F%2Fservice.upc.edu.cn%2Fsite%2Flogin%2Fcas-login%3Fredirect_url%3D" +
            "https%253A%252F%252Fservice.upc.edu.cn%252Fv2%252Fsite%252Findex" +
            "&from=wap&noAutoRedirect=1"
    internal const val CALLBACK_PATH = "/site/login/cas-login"
    internal const val INDEX_PATH = "/v2/site/index"
    internal const val MAX_REDIRECT_HOPS = 4
}

private val SERVICE_PORTAL_CAS_SERVICE = UpcCasService(
    casHost = UpcServicePortalAuthEndpoints.CAS_HOST,
    serviceUrl = UpcServicePortalAuthEndpoints.SERVICE,
    callbackHost = UpcServicePortalAuthEndpoints.HOST,
    callbackPath = UpcServicePortalAuthEndpoints.CALLBACK_PATH,
    callbackParameterNames = setOf("redirect_url", "ticket"),
    maxRedirectHops = UpcServicePortalAuthEndpoints.MAX_REDIRECT_HOPS,
)

/**
 * 以统一账号凭据建立并检查 e站通只读业务会话。
 *
 * SSO 只可复用本实例此前完成并绑定到相同账号代次的 CAS 会话。Cookie 只在内存中
 * 保留；[clearSession] 通过 epoch 门禁使尚未返回的认证结果失效。
 */
class UpcServicePortalAuthenticator internal constructor(
    private val repository: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) : ServicePortalAuthenticator {

    constructor(repository: SchoolCredentialAccess) : this(repository, servicePortalTransport())

    private val authenticationMutex = Mutex()
    private val epoch = AtomicLong(0)
    private val sessionLock = Any()

    @Volatile
    private var boundSession: BoundSession? = null

    @Volatile
    private var activeSession: UpcCasHttpSession? = null

    @OptIn(RestrictedSchoolCredentialUse::class)
    override suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcServicePortalAuthResult {
        val operation = Diagnostics.beginOperation()
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SERVICE_PORTAL,
            action = DiagnosticAction.AUTH_START, result = DiagnosticResult.PENDING,
            phase = DiagnosticPhase.CREDENTIAL_RESTORE,
            detail = DiagnosticDetail.AUTH_REQUIRED,
        )
        if (!authenticationMutex.tryLock()) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SERVICE_PORTAL,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                phase = DiagnosticPhase.AUTH, detail = DiagnosticDetail.AUTH_INTERACTION,
                reason = DiagnosticReason.POLICY,
            )
            return failure(UpcServicePortalAuthFailure.BUSY)
        }

        val attemptEpoch = epoch.get()
        try {
            if (expectedCredentialGeneration <= 0 || !isCurrent(attemptEpoch)) {
                return failure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED)
            }

            val useResult = try {
                repository.withLoginCredential { credential ->
                    if (credential.accountId != expectedAccountId ||
                        credential.credentialGeneration.value != expectedCredentialGeneration
                    ) {
                        recordUpcAuthDiagnostic(
                            operation, DiagnosticModule.SERVICE_PORTAL,
                            action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                            phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                            detail = DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED,
                            reason = DiagnosticReason.ACCOUNT,
                            credentialState = DiagnosticCredentialState.GENERATION_CHANGED,
                        )
                        AttemptResult(failure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED))
                    } else {
                        recordUpcAuthDiagnostic(
                            operation, DiagnosticModule.SERVICE_PORTAL,
                            result = DiagnosticResult.OK,
                            phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                            detail = DiagnosticDetail.CREDENTIAL_RECOVERED,
                            credentialState = DiagnosticCredentialState.AVAILABLE,
                        )
                        authenticateWithCredential(credential, attemptEpoch, operation)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AttemptInvalidated) {
                return failure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED)
            } catch (_: Exception) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.SERVICE_PORTAL,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                    detail = DiagnosticDetail.CREDENTIAL_UNAVAILABLE,
                    reason = DiagnosticReason.ACCOUNT,
                    credentialState = DiagnosticCredentialState.UNAVAILABLE,
                )
                return if (isCurrent(attemptEpoch)) {
                    failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
                } else {
                    failure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED)
                }
            }

            return when (useResult) {
                is SchoolCredentialUseResult.Success -> {
                    finishAttempt(attemptEpoch, useResult.value, operation)
                }

                is SchoolCredentialUseResult.Failure -> synchronized(sessionLock) {
                    if (epoch.get() != attemptEpoch) {
                        failure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED)
                    } else {
                        val changed = useResult.reason == SchoolCredentialFailure.CHANGED_DURING_USE
                        recordUpcAuthDiagnostic(
                            operation, DiagnosticModule.SERVICE_PORTAL,
                            action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.BLOCKED,
                            phase = DiagnosticPhase.CREDENTIAL_RESTORE,
                            detail = when {
                                changed -> DiagnosticDetail.CREDENTIAL_GENERATION_CHANGED
                                useResult.reason == SchoolCredentialFailure.BLOCKED_BY_CLEANUP ->
                                    DiagnosticDetail.CREDENTIAL_CLEANUP_BLOCKED
                                else -> DiagnosticDetail.CREDENTIAL_UNAVAILABLE
                            },
                            reason = DiagnosticReason.ACCOUNT,
                            credentialState = when {
                                changed -> DiagnosticCredentialState.GENERATION_CHANGED
                                useResult.reason == SchoolCredentialFailure.BLOCKED_BY_CLEANUP ->
                                    DiagnosticCredentialState.CLEANUP_BLOCKED
                                else -> DiagnosticCredentialState.UNAVAILABLE
                            },
                        )
                        failure(
                            when (useResult.reason) {
                                SchoolCredentialFailure.CHANGED_DURING_USE ->
                                    UpcServicePortalAuthFailure.ACCOUNT_CHANGED
                                SchoolCredentialFailure.BLOCKED_BY_CLEANUP,
                                SchoolCredentialFailure.NOT_CONFIGURED,
                                SchoolCredentialFailure.UNAVAILABLE,
                                -> UpcServicePortalAuthFailure.ACCOUNT_UNAVAILABLE
                            },
                        )
                    }
                }
            }
        } finally {
            synchronized(sessionLock) {
                activeSession = null
            }
            authenticationMutex.unlock()
        }
    }

    override fun clearSession() {
        val operation = Diagnostics.beginOperation()
        synchronized(sessionLock) {
            epoch.incrementAndGet()
            boundSession?.session?.clear()
            activeSession?.clear()
            boundSession = null
            activeSession = null
        }
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SERVICE_PORTAL,
            action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
            phase = DiagnosticPhase.SESSION_ADOPT,
            detail = DiagnosticDetail.SESSION_CLEARED,
            credentialState = DiagnosticCredentialState.CLEANUP_COMPLETE,
        )
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private fun authenticateWithCredential(
        credential: SchoolLoginCredential,
        attemptEpoch: Long,
        operation: DiagnosticOperation,
    ): AttemptResult {
        if (!isCurrent(attemptEpoch)) throw AttemptInvalidated()
        val reusable = reusableSession(credential, attemptEpoch)
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SERVICE_PORTAL,
            result = if (reusable == null) DiagnosticResult.PENDING else DiagnosticResult.OK,
            phase = DiagnosticPhase.SESSION_ADOPT,
            detail = if (reusable == null) DiagnosticDetail.SESSION_CLEARED else DiagnosticDetail.SESSION_REUSED,
            bindingSource = if (reusable == null) DiagnosticBindingSource.NONE
            else DiagnosticBindingSource.BOUND_SESSION,
        )
        val attemptTransport = AttemptTransport(transport) { isCurrent(attemptEpoch) }
        val session = reusable?.session ?: UpcCasHttpSession(
            transport = attemptTransport,
            jar = UpcAuthCookieJar(
                setOf(UpcServicePortalAuthEndpoints.CAS_HOST, UpcServicePortalAuthEndpoints.HOST),
            ),
        )
        setActiveSession(attemptEpoch, session)
        val login = UpcCasLogin(
            SERVICE_PORTAL_CAS_SERVICE,
            attemptTransport,
            operation,
            DiagnosticModule.SERVICE_PORTAL,
        ).login(
            credential = credential,
            casUrl = UpcServicePortalAuthEndpoints.LOGIN_URL,
            existingSession = session,
            allowExistingSessionCallback = reusable != null,
        )
        if (!isCurrent(attemptEpoch)) throw AttemptInvalidated()

        return when (login) {
            is UpcCasLoginResult.Failure -> AttemptResult(
                failure(login.reason.toServicePortalFailure()),
            )
            is UpcCasLoginResult.Callback -> {
                val source = if (login.passwordSubmitted) {
                    UpcServicePortalAuthSource.PASSWORD
                } else {
                    UpcServicePortalAuthSource.BOUND_SSO
                }
                val checked = followCallbackAndCheck(
                    session = login.session,
                    start = login.url,
                    attemptEpoch = attemptEpoch,
                    binding = SchoolAccountReference(
                        credential.accountId,
                        credential.credentialGeneration,
                    ),
                    source = source,
                    operation = operation,
                )
                if (checked is UpcServicePortalAuthResult.Success) {
                    AttemptResult(
                        result = checked,
                        sessionToCache = BoundSession(
                            accountId = credential.accountId,
                            credentialGeneration = credential.credentialGeneration.value,
                            epoch = attemptEpoch,
                            session = login.session,
                        ),
                    )
                } else {
                    AttemptResult(checked)
                }
            }
        }
    }

    private fun followCallbackAndCheck(
        session: UpcCasHttpSession,
        start: HttpUrl,
        attemptEpoch: Long,
        binding: SchoolAccountReference,
        source: UpcServicePortalAuthSource,
        operation: DiagnosticOperation,
    ): UpcServicePortalAuthResult {
        if (!isExactCallback(start)) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SERVICE_PORTAL,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.BUSINESS_CALLBACK,
                detail = DiagnosticDetail.BUSINESS_CALLBACK_REJECTED,
                reason = DiagnosticReason.POLICY,
                callbackState = DiagnosticCallbackState.REJECTED,
                bindingSource = source.toDiagnosticBinding(),
            )
            return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
        }
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SERVICE_PORTAL,
            result = DiagnosticResult.OK, phase = DiagnosticPhase.BUSINESS_CALLBACK,
            detail = DiagnosticDetail.BUSINESS_CALLBACK_ACCEPTED,
            callbackState = DiagnosticCallbackState.ACCEPTED,
            bindingSource = source.toDiagnosticBinding(),
        )

        var current = start
        val visited = mutableSetOf<String>()
        var reachedBusinessPage = false
        for (hop in 0..UpcServicePortalAuthEndpoints.MAX_REDIRECT_HOPS) {
            if (!isAllowedBusinessUrl(current) || !visited.add(current.toString())) {
                return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
            }
            val response = try {
                session.request("GET", current.toString())
            } catch (invalidated: AttemptInvalidated) {
                throw invalidated
            } catch (network: UpcAuthTransportException) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.SERVICE_PORTAL,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = when (network.failure) {
                        UpcAuthTransportFailure.TLS -> DiagnosticDetail.TRANSPORT_TLS
                        else -> DiagnosticDetail.TRANSPORT_NETWORK
                    },
                    reason = when (network.failure) {
                        UpcAuthTransportFailure.TLS -> DiagnosticReason.TLS
                        else -> DiagnosticReason.NETWORK
                    },
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(network.failure.toServicePortalFailure())
            }
            if (!isCurrent(attemptEpoch)) throw AttemptInvalidated()

            if (response.status in 300..399) {
                if (hop == UpcServicePortalAuthEndpoints.MAX_REDIRECT_HOPS) {
                    return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
                }
                val location = singleHeader(response, "location")
                    ?: return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
                current = resolveUrl(current, location)
                    ?: return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
                continue
            }
            if (response.status == 401 || response.status == 403) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.SERVICE_PORTAL,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = DiagnosticDetail.RESPONSE_HTTP,
                    reason = DiagnosticReason.HTTP,
                    httpStatus = response.status,
                    callbackState = DiagnosticCallbackState.REJECTED,
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(UpcServicePortalAuthFailure.ACCESS_DENIED)
            }
            if (response.status != 200) {
                recordUpcAuthDiagnostic(
                    operation, DiagnosticModule.SERVICE_PORTAL,
                    action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                    phase = DiagnosticPhase.BUSINESS_CALLBACK,
                    detail = DiagnosticDetail.RESPONSE_HTTP,
                    reason = DiagnosticReason.HTTP,
                    httpStatus = response.status,
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(UpcServicePortalAuthFailure.UPSTREAM_ERROR)
            }
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SERVICE_PORTAL,
                result = DiagnosticResult.OK, phase = DiagnosticPhase.BUSINESS_SESSION,
                detail = DiagnosticDetail.RESPONSE_BUSINESS,
                httpStatus = response.status,
                bindingSource = source.toDiagnosticBinding(),
            )
            reachedBusinessPage = true
            break
        }
        if (!reachedBusinessPage) return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)

        val checkRequest = try {
            session.requestWithCookieInfo(
                "GET",
                UpcServicePortalAuthEndpoints.SESSION_CHECK_URL,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to UpcServicePortalAuthEndpoints.REDIRECT_URL,
                ),
            )
        } catch (invalidated: AttemptInvalidated) {
            throw invalidated
        } catch (network: UpcAuthTransportException) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SERVICE_PORTAL,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = when (network.failure) {
                    UpcAuthTransportFailure.TLS -> DiagnosticDetail.TRANSPORT_TLS
                    else -> DiagnosticDetail.TRANSPORT_NETWORK
                },
                reason = when (network.failure) {
                    UpcAuthTransportFailure.TLS -> DiagnosticReason.TLS
                    else -> DiagnosticReason.NETWORK
                },
                bindingSource = source.toDiagnosticBinding(),
            )
            return failure(network.failure.toServicePortalFailure())
        }
        if (!isCurrent(attemptEpoch)) throw AttemptInvalidated()
        val checked = checkRequest.response

        if (checked.status == 401 || checked.status == 403) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SERVICE_PORTAL,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = DiagnosticDetail.RESPONSE_HTTP,
                reason = DiagnosticReason.HTTP,
                httpStatus = checked.status,
                bindingSource = source.toDiagnosticBinding(),
            )
            return failure(UpcServicePortalAuthFailure.ACCESS_DENIED)
        }
        if (checked.status != 200) {
            recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SERVICE_PORTAL,
                action = DiagnosticAction.AUTH_STOP, result = DiagnosticResult.FAIL,
                phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = DiagnosticDetail.RESPONSE_HTTP,
                reason = DiagnosticReason.HTTP,
                httpStatus = checked.status,
                bindingSource = source.toDiagnosticBinding(),
            )
            return failure(UpcServicePortalAuthFailure.UPSTREAM_ERROR)
        }
        val contentType = singleHeader(checked, "content-type")
            ?.lowercase(Locale.ROOT)
        val mediaType = classifyServiceMediaType(contentType)
        if (contentType == null ||
            !contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)
        ) {
            recordSessionCheckDiagnostic(
                operation = operation,
                detail = DiagnosticDetail.SERVICE_SESSION_MIME_REJECTED,
                reason = DiagnosticReason.MIME,
                status = checked.status,
                mediaType = mediaType,
                bodyType = classifyServiceBody(checked.body),
                responseBytes = checked.body.size,
                bindingSource = source.toDiagnosticBinding(),
            )
            return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
        }

        val bodyType = classifyServiceBody(checked.body)
        val envelope = when (val parsed = parseSessionEnvelope(checked.body)) {
            SessionEnvelopeParse.Malformed -> {
                recordSessionCheckDiagnostic(
                    operation = operation,
                    detail = DiagnosticDetail.SERVICE_SESSION_BODY_MALFORMED,
                    reason = DiagnosticReason.SCHEMA,
                    status = checked.status,
                    mediaType = mediaType,
                    bodyType = bodyType,
                    responseBytes = checked.body.size,
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
            }
            SessionEnvelopeParse.SchemaRejected -> {
                recordSessionCheckDiagnostic(
                    operation = operation,
                    detail = DiagnosticDetail.SERVICE_SESSION_SCHEMA_REJECTED,
                    reason = DiagnosticReason.SCHEMA,
                    status = checked.status,
                    mediaType = mediaType,
                    bodyType = bodyType,
                    responseBytes = checked.body.size,
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(UpcServicePortalAuthFailure.PROTOCOL_ERROR)
            }
            is SessionEnvelopeParse.Parsed -> parsed.envelope
        }
        if (envelope.errorCode == 0) {
            recordSessionCheckDiagnostic(
                operation = operation,
                detail = DiagnosticDetail.SERVICE_SESSION_ENVELOPE_ACCEPTED,
                result = DiagnosticResult.OK,
                status = checked.status,
                mediaType = mediaType,
                bodyType = bodyType,
                responseBytes = checked.body.size,
                bindingSource = source.toDiagnosticBinding(),
            )
        }
        when (envelope.errorCode) {
            0 -> recordUpcAuthDiagnostic(
                operation, DiagnosticModule.SERVICE_PORTAL,
                result = DiagnosticResult.OK, phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = DiagnosticDetail.AUTH_OK, httpStatus = checked.status,
                bindingSource = source.toDiagnosticBinding(),
            )
            10042, 100421 -> {
                recordSessionCheckDiagnostic(
                    operation = operation,
                    detail = DiagnosticDetail.PERSONAL_IDENTITY_MISMATCH,
                    reason = DiagnosticReason.AUTH,
                    status = checked.status,
                    mediaType = mediaType,
                    bodyType = bodyType,
                    responseBytes = checked.body.size,
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(UpcServicePortalAuthFailure.ACCESS_DENIED)
            }
            30000 -> {
                recordSessionCheckDiagnostic(
                    operation = operation,
                    detail = DiagnosticDetail.SERVICE_SESSION_INTERACTION_REQUIRED,
                    reason = DiagnosticReason.INTERACTION,
                    status = checked.status,
                    mediaType = mediaType,
                    bodyType = bodyType,
                    responseBytes = checked.body.size,
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(UpcServicePortalAuthFailure.INTERACTION_REQUIRED)
            }
            else -> {
                recordSessionCheckDiagnostic(
                    operation = operation,
                    detail = DiagnosticDetail.SERVICE_SESSION_SERVER_REJECTED,
                    reason = DiagnosticReason.UNKNOWN,
                    status = checked.status,
                    mediaType = mediaType,
                    bodyType = bodyType,
                    responseBytes = checked.body.size,
                    bindingSource = source.toDiagnosticBinding(),
                )
                return failure(UpcServicePortalAuthFailure.UPSTREAM_ERROR)
            }
        }

        val cookies = session.businessCookies()
        val exportedSentCookie = checkRequest.sentCookies.any { sent ->
            cookies.any { cookie ->
                sent.matches(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                )
            }
        }
        if (!exportedSentCookie) {
            recordSessionCheckDiagnostic(
                operation = operation,
                detail = DiagnosticDetail.SERVICE_SESSION_COOKIE_NOT_SENT,
                reason = DiagnosticReason.AUTH,
                status = checked.status,
                mediaType = mediaType,
                bodyType = bodyType,
                responseBytes = checked.body.size,
                itemCount = cookies.size,
                bindingSource = source.toDiagnosticBinding(),
            )
            return failure(UpcServicePortalAuthFailure.UPSTREAM_ERROR)
        }
        recordUpcAuthDiagnostic(
            operation, DiagnosticModule.SERVICE_PORTAL,
            action = DiagnosticAction.AUTH_STAGE, result = DiagnosticResult.OK,
            phase = DiagnosticPhase.PERSONAL_VERIFY,
            detail = DiagnosticDetail.PERSONAL_IDENTITY_VERIFIED,
            httpStatus = checked.status,
            itemCount = cookies.size,
            bindingSource = source.toDiagnosticBinding(),
            callbackState = DiagnosticCallbackState.CONSUMED,
        )
        return UpcServicePortalAuthResult.Success(
            binding = binding,
            cookies = cookies,
            source = source,
        )
    }

    private fun reusableSession(
        credential: SchoolLoginCredential,
        attemptEpoch: Long,
    ): BoundSession? = synchronized(sessionLock) {
        val current = boundSession
        if (current != null && current.accountId == credential.accountId &&
            current.credentialGeneration == credential.credentialGeneration.value &&
            current.epoch == attemptEpoch && epoch.get() == attemptEpoch
        ) {
            current
        } else {
            if (epoch.get() == attemptEpoch) {
                current?.session?.clear()
                boundSession = null
            }
            null
        }
    }

    private fun setActiveSession(attemptEpoch: Long, session: UpcCasHttpSession) {
        synchronized(sessionLock) {
            if (epoch.get() != attemptEpoch) throw AttemptInvalidated()
            activeSession = session
        }
    }

    private fun finishAttempt(
        attemptEpoch: Long,
        attempt: AttemptResult,
        operation: DiagnosticOperation,
    ): UpcServicePortalAuthResult =
        synchronized(sessionLock) {
            if (epoch.get() != attemptEpoch) {
                failure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED)
            } else {
                attempt.sessionToCache?.let { session ->
                    if (session.epoch != attemptEpoch) {
                        return@synchronized failure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED)
                    }
                    boundSession = session
                }
                if (attempt.sessionToCache != null && attempt.result is UpcServicePortalAuthResult.Success) {
                    recordUpcAuthDiagnostic(
                        operation, DiagnosticModule.SERVICE_PORTAL,
                        action = DiagnosticAction.AUTH_COMPLETE, result = DiagnosticResult.OK,
                        phase = DiagnosticPhase.SESSION_ADOPT,
                        detail = DiagnosticDetail.SESSION_ADOPTED,
                        itemCount = attempt.result.cookies.size,
                        bindingSource = attempt.result.source.toDiagnosticBinding(),
                        callbackState = DiagnosticCallbackState.CONSUMED,
                    )
                }
                attempt.result
            }
        }

    private fun isCurrent(attemptEpoch: Long): Boolean = epoch.get() == attemptEpoch

    private fun isExactCallback(url: HttpUrl): Boolean {
        if (!SERVICE_PORTAL_CAS_SERVICE.isCallbackUrl(url)) return false
        if (url.queryParameterNames != setOf("redirect_url", "ticket")) return false
        if (url.queryParameterValues("redirect_url").size != 1 ||
            url.queryParameter("redirect_url") != UpcServicePortalAuthEndpoints.REDIRECT_URL
        ) {
            return false
        }
        val ticket = url.queryParameter("ticket") ?: return false
        return ticket.length in 8..4096 && ticket.startsWith("ST-") &&
            ticket.all { it in '\u0021'..'\u007e' }
    }

    private fun isAllowedBusinessUrl(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcServicePortalAuthEndpoints.HOST ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null
        ) {
            return false
        }
        return when (url.encodedPath) {
            UpcServicePortalAuthEndpoints.CALLBACK_PATH -> isExactCallback(url)
            UpcServicePortalAuthEndpoints.INDEX_PATH -> url.encodedQuery == null
            else -> false
        }
    }

    private fun parseSessionEnvelope(body: ByteArray): SessionEnvelopeParse {
        val root = try {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return SessionEnvelopeParse.Malformed
        }
        val objectRoot = root as? JsonObject ?: return SessionEnvelopeParse.SchemaRejected
        val errorElement = objectRoot["e"] as? JsonPrimitive ?: return SessionEnvelopeParse.SchemaRejected
        if (errorElement.isString) return SessionEnvelopeParse.SchemaRejected
        val errorCode = errorElement.content.toIntOrNull() ?: return SessionEnvelopeParse.SchemaRejected
        if (errorCode != 0) return SessionEnvelopeParse.Parsed(SessionEnvelope(errorCode))
        objectRoot["d"] as? JsonObject ?: return SessionEnvelopeParse.SchemaRejected
        return SessionEnvelopeParse.Parsed(SessionEnvelope(errorCode))
    }

    private fun recordSessionCheckDiagnostic(
        operation: DiagnosticOperation,
        detail: DiagnosticDetail,
        result: DiagnosticResult = DiagnosticResult.FAIL,
        reason: DiagnosticReason? = null,
        status: Int,
        mediaType: DiagnosticMediaType,
        bodyType: DiagnosticBodyType,
        responseBytes: Int,
        itemCount: Int? = null,
        bindingSource: DiagnosticBindingSource,
    ) {
        runCatching {
            Diagnostics.record(
                module = DiagnosticModule.SERVICE_PORTAL,
                action = if (result == DiagnosticResult.OK) DiagnosticAction.AUTH_STAGE else DiagnosticAction.AUTH_STOP,
                result = result,
                reason = reason,
                httpStatus = status,
                phase = DiagnosticPhase.PERSONAL_VERIFY,
                detail = detail,
                mediaType = mediaType,
                bodyType = bodyType,
                responseBytes = responseBytes.takeIf {
                    it in 0..com.upcpocket.app.platform.diagnostics.DiagnosticEvent.MAX_RESPONSE_BYTES
                },
                itemCount = itemCount,
                bindingSource = bindingSource,
                operation = operation,
            )
        }
    }

    private fun classifyServiceMediaType(contentType: String?): DiagnosticMediaType {
        val base = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return when {
            base == null -> DiagnosticMediaType.MISSING
            base == "application/json" -> DiagnosticMediaType.JSON
            base.endsWith("+json") -> DiagnosticMediaType.JSON_SUFFIX
            base == "text/plain" -> DiagnosticMediaType.TEXT
            base == "text/html" -> DiagnosticMediaType.HTML
            else -> DiagnosticMediaType.OTHER
        }
    }

    private fun classifyServiceBody(body: ByteArray): DiagnosticBodyType {
        if (body.isEmpty()) return DiagnosticBodyType.EMPTY
        val parsed = try {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
        return when (parsed) {
            is JsonObject -> DiagnosticBodyType.JSON_OBJECT
            is kotlinx.serialization.json.JsonArray -> DiagnosticBodyType.JSON_ARRAY
            null -> if (body.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar() == '<') {
                DiagnosticBodyType.HTML
            } else {
                DiagnosticBodyType.MALFORMED
            }
            else -> DiagnosticBodyType.OTHER
        }
    }

    private fun singleHeader(response: UpcAuthHttpResponse, name: String): String? {
        val values = response.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
            ?: return null
        return values.singleOrNull()
    }

    private fun resolveUrl(base: HttpUrl, raw: String): HttpUrl? = try {
        base.resolve(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun failure(reason: UpcServicePortalAuthFailure) =
        UpcServicePortalAuthResult.Failure(reason)

    private data class BoundSession(
        val accountId: SchoolAccountId,
        val credentialGeneration: Long,
        val epoch: Long,
        val session: UpcCasHttpSession,
    )

    private data class AttemptResult(
        val result: UpcServicePortalAuthResult,
        val sessionToCache: BoundSession? = null,
    )

    private data class SessionEnvelope(val errorCode: Int)

    private sealed interface SessionEnvelopeParse {
        data class Parsed(val envelope: SessionEnvelope) : SessionEnvelopeParse
        data object Malformed : SessionEnvelopeParse
        data object SchemaRejected : SessionEnvelopeParse
    }

    private class AttemptTransport(
        private val delegate: UpcAuthTransport,
        private val isCurrent: () -> Boolean,
    ) : UpcAuthTransport {
        override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
            if (!isCurrent()) throw AttemptInvalidated()
            val response = delegate.execute(request)
            if (!isCurrent()) throw AttemptInvalidated()
            return response
        }
    }

    private class AttemptInvalidated : RuntimeException(null, null, false, false)

}

private fun UpcAuthFailure.toServicePortalFailure(): UpcServicePortalAuthFailure = when (this) {
    UpcAuthFailure.INVALID_CREDENTIALS -> UpcServicePortalAuthFailure.INVALID_CREDENTIALS
    UpcAuthFailure.INTERACTION_REQUIRED -> UpcServicePortalAuthFailure.INTERACTION_REQUIRED
    UpcAuthFailure.NETWORK_UNAVAILABLE -> UpcServicePortalAuthFailure.NETWORK_UNAVAILABLE
    UpcAuthFailure.TLS_ERROR -> UpcServicePortalAuthFailure.TLS_ERROR
    UpcAuthFailure.UPSTREAM_ERROR -> UpcServicePortalAuthFailure.UPSTREAM_ERROR
    UpcAuthFailure.PROTOCOL_ERROR -> UpcServicePortalAuthFailure.PROTOCOL_ERROR
    UpcAuthFailure.ACCOUNT_UNAVAILABLE -> UpcServicePortalAuthFailure.ACCOUNT_UNAVAILABLE
    UpcAuthFailure.ACCOUNT_CHANGED -> UpcServicePortalAuthFailure.ACCOUNT_CHANGED
    UpcAuthFailure.ACCESS_DENIED -> UpcServicePortalAuthFailure.ACCESS_DENIED
}

private fun UpcAuthTransportFailure.toServicePortalFailure(): UpcServicePortalAuthFailure = when (this) {
    UpcAuthTransportFailure.NETWORK,
    UpcAuthTransportFailure.TIMEOUT,
    -> UpcServicePortalAuthFailure.NETWORK_UNAVAILABLE
    UpcAuthTransportFailure.TLS -> UpcServicePortalAuthFailure.TLS_ERROR
    UpcAuthTransportFailure.TOO_LARGE,
    UpcAuthTransportFailure.PROTOCOL,
    -> UpcServicePortalAuthFailure.UPSTREAM_ERROR
}

private fun UpcServicePortalAuthSource.toDiagnosticBinding(): DiagnosticBindingSource = when (this) {
    UpcServicePortalAuthSource.PASSWORD -> DiagnosticBindingSource.PASSWORD
    UpcServicePortalAuthSource.BOUND_SSO -> DiagnosticBindingSource.BOUND_SESSION
    UpcServicePortalAuthSource.VERIFIED_WEB -> DiagnosticBindingSource.VERIFIED_WEB
}

private fun servicePortalTransport(): UpcAuthTransport = OkHttpUpcAuthTransport(
    client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    maxBytes = 1024L * 1024L,
)

private fun UpcCasHttpSession.businessCookies(): List<UpcServicePortalCookie> =
    cookiesForHost(UpcServicePortalAuthEndpoints.HOST).mapNotNull { cookie ->
        if (cookie.domain != UpcServicePortalAuthEndpoints.HOST || cookie.expiresAt <= System.currentTimeMillis()) {
            return@mapNotNull null
        }
        runCatching {
            UpcServicePortalCookie(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                expiresAtEpochSeconds = cookie.expiresAt
                    .takeIf { it > 0 && it != Long.MAX_VALUE }
                    ?.div(1000),
                hostOnly = cookie.hostOnly,
            )
        }.getOrNull()
    }
