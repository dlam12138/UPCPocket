package com.upcpocket.app.platform.diagnostics

import java.util.concurrent.ThreadLocalRandom

/**
 * Fixed, non-identifying diagnostic vocabulary shared by features and the host.
 *
 * Events never carry free text. URLs, headers, bodies, HTML, credentials,
 * account identifiers, course/room names and exception messages are deliberately
 * not representable by this model.
 */
enum class DiagnosticModule {
    APP,
    TIMETABLE,
    LIBRARY,
    SERVICE_PORTAL,
    SHIGUANG,
    CLASSROOM,
    CARD,
    POWER,
    WIDGET,
}

enum class DiagnosticAction {
    AUTH_START,
    AUTH_STAGE,
    AUTH_COMPLETE,
    AUTH_INTERACTION,
    AUTH_STOP,
    WEBVIEW_START,
    WEBVIEW_FINISH,
    WEBVIEW_MAIN_FRAME_ERROR,
    WEBVIEW_HTTP_ERROR,
    WEBVIEW_SSL_REJECT,
    WEBVIEW_RENDERER_EXIT,
    WEBVIEW_NAVIGATION,
    WEBVIEW_VIEWPORT,
    QUERY_START,
    QUERY_COMPLETE,
    QUERY_FAIL,
    QUERY_CHANGED,
    STALE_RESULT,
    WORKER_START,
    WORKER_COMPLETE,
    WORKER_SKIP,
    ACCOUNT_RESTORE_START,
    ACCOUNT_RESTORE_COMPLETE,
    WIDGET_LAYOUT,
    EXPORT_START,
    EXPORT_COMPLETE,
    EXPORT_FAIL,
    CLEAR,
}

enum class DiagnosticResult {
    PENDING,
    OK,
    FAIL,
    BLOCKED,
    TIMEOUT,
    CANCELLED,
    SKIPPED,
    PARTIAL,
    UNKNOWN,
}

enum class DiagnosticReason {
    NETWORK,
    TLS,
    HTTP,
    MIME,
    SCHEMA,
    AUTH,
    INTERACTION,
    POLICY,
    MAIN_FRAME,
    SUB_FRAME,
    RENDERER,
    VIEWPORT,
    ACCOUNT,
    NOT_CONFIGURED,
    CLEANUP,
    STALE,
    FILTER_CHANGED,
    EMPTY,
    NO_DATA,
    LAYOUT,
    STORAGE,
    UNKNOWN,
}

enum class DiagnosticDestination {
    CAS_LOGIN,
    CAS_MOBILE,
    APP_CALLBACK,
    APP_PAGE,
    APP_API,
    OTHER,
    UNKNOWN_DESTINATION,
}

enum class DiagnosticExceptionType {
    NETWORK,
    TIMEOUT,
    TLS,
    IO,
    PARSE,
    SECURITY,
    CANCELLED,
    OTHER,
}

/**
 * Classification of the HTTP Content-Type header. Values are fixed and never
 * include the original header text, parameters or charset.
 */
enum class DiagnosticMediaType {
    JSON,
    JSON_SUFFIX,
    TEXT,
    HTML,
    OTHER,
    MISSING,
}

/**
 * Classification of the response body after bounded local inspection. This is
 * a category only; raw bodies, prefixes and field values are never exported.
 */
enum class DiagnosticBodyType {
    JSON_OBJECT,
    JSON_ARRAY,
    HTML,
    LOGIN_LIKE,
    EMPTY,
    MALFORMED,
    OTHER,
}

/**
 * Coarse stage of a timetable operation. A single operation id can then be
 * followed from web login through cookie adoption, index and week loading.
 */
enum class DiagnosticPhase {
    COOKIE,
    INDEX,
    WEEK,
    PARSE,
    RESPONSE,
    PUBLISH,
    CACHE,
    SESSION,
    AUTH,
    CREDENTIAL_RESTORE,
    ENTRY_GET,
    CAS_GET,
    CAS_FORM,
    CAS_POST,
    CAS_RETURN,
    BUSINESS_CALLBACK,
    BUSINESS_SESSION,
    PERSONAL_VERIFY,
    SESSION_ADOPT,
    WEBVIEW_NAVIGATION,
    WEBVIEW_CALLBACK,
}

enum class DiagnosticCredentialState {
    AVAILABLE,
    UNAVAILABLE,
    CLEANUP_BLOCKED,
    GENERATION_CHANGED,
    CLEANUP_STARTED,
    CLEANUP_COMPLETE,
    CLEANUP_FAILED,
}

enum class DiagnosticChallengeType {
    NONE,
    CAPTCHA,
    SECOND_FACTOR,
    PASSWORD_FORM,
    UNKNOWN,
}

enum class DiagnosticChallengeEvidence {
    CONFIRMED,
    ABSENT,
    UNKNOWN,
}

enum class DiagnosticServiceParameter {
    NOT_APPLICABLE,
    MISSING,
    PRESENT,
    MATCH,
    MISMATCH,
    DUPLICATE,
    UNKNOWN,
}

enum class DiagnosticCallbackState {
    NOT_APPLICABLE,
    ACCEPTED,
    REJECTED,
    CONSUMED,
    NOT_CONSUMED,
    STALE,
}

enum class DiagnosticNavigationFrame {
    MAIN,
    SUB,
    UNKNOWN,
}

enum class DiagnosticBindingSource {
    NONE,
    PASSWORD,
    BOUND_SESSION,
    VERIFIED_WEB,
    UNKNOWN,
}

/**
 * Fixed rule/diagnosis code. These values describe only the expected schema
 * path or a local rule; they never contain a URL, field value, course name,
 * teacher/location, account identifier or exception message.
 */
enum class DiagnosticDetail {
    COOKIE_EMPTY,
    COOKIE_CONTEXT_STALE,
    COOKIE_PARSED,
    COOKIE_SAVED,
    COOKIE_SAVE_FAILED,
    INDEX_REQUEST,
    INDEX_OK,
    INDEX_PARSE_TERM_INFO,
    INDEX_PARSE_TERM_DATE,
    INDEX_PARSE_TERM_WEEKS,
    INDEX_PARSE_CURRENT,
    WEEK_REQUEST,
    WEEK_OK,
    WEEK_EMPTY,
    WEEK_PARSE_CLASSES,
    WEEK_PARSE_DATES,
    WEEK_PARSE_DATE_ALIGN,
    WEEK_PARSE_CELL_KEY,
    WEEK_PARSE_LESS_ARR,
    WEEK_PARSE_NAME,
    WEEK_PARSE_WEEK_LABEL,
    WEEK_PARSE_WEEKDAY,
    WEEK_PARSE_ORDER,
    WEEK_PARSE_TOTAL_LENGTH,
    WEEK_PARSE_PERIOD_RANGE,
    WEEK_PARSE_ENTRY_TYPE,
    WEEK_PARSE_DAY_TYPE,
    RESPONSE_JSON,
    RESPONSE_NON_JSON,
    RESPONSE_HTML,
    RESPONSE_MALFORMED_JSON,
    RESPONSE_MIME_MISMATCH,
    RESPONSE_LOGIN_PAGE,
    RESPONSE_BUSINESS,
    RESPONSE_HTTP,
    RESPONSE_TOO_LARGE,
    RESPONSE_EMPTY,
    TRANSPORT_NETWORK,
    TRANSPORT_TIMEOUT,
    TRANSPORT_TLS,
    TRANSPORT_TOO_LARGE,
    AUTH_REQUIRED,
    AUTH_INVALID,
    AUTH_INTERACTION,
    AUTH_OK,
    CACHE_SAVED,
    CACHE_SAVE_FAILED,
    CACHE_HIT,
    CACHE_MISS,
    PUBLISH_OK,
    PUBLISH_EMPTY,
    PUBLISH_FAIL,
    STALE_DISCARDED,
    COMPAT_WEEKDAY_IGNORED,
    COMPAT_ORDER_IGNORED,
    COMPAT_TOTAL_LENGTH_IGNORED,
    COMPAT_CELL_START_IGNORED,
    COMPAT_INTEGRAL_FLOAT,
    COMPAT_DUPLICATE_PERIODS,
    COMPAT_INDEX_TERM_SKIPPED,
    COMPAT_INDEX_CURRENT_IGNORED,
    CREDENTIAL_RECOVERED,
    CREDENTIAL_UNAVAILABLE,
    CREDENTIAL_CLEANUP_BLOCKED,
    CREDENTIAL_GENERATION_CHANGED,
    CREDENTIAL_CLEANUP_STARTED,
    CREDENTIAL_CLEANUP_COMPLETE,
    CREDENTIAL_CLEANUP_FAILED,
    CAS_FORM_ACCEPTED,
    CAS_FORM_REJECTED,
    CAS_FORM_NOT_FOUND,
    CAS_FORM_PASSWORD_FIELD_MISSING,
    CAS_FORM_TYPE_FIELD_MISSING,
    CAS_FORM_TYPE_REJECTED,
    CAS_FORM_EVENT_FIELD_MISSING,
    CAS_FORM_EVENT_REJECTED,
    CAS_FORM_EXECUTION_MISSING,
    CAS_FORM_EXECUTION_INVALID,
    CAS_FORM_ACTION_REJECTED,
    CAS_GET_401_FORM,
    CAS_GET_403_FORM,
    CAS_CHALLENGE_CONFIRMED,
    CAS_CHALLENGE_ABSENT,
    CAS_POST_EXPLICIT_REJECTION,
    CAS_POST_UNKNOWN_REJECTION,
    CAS_RATE_LIMITED,
    CAS_SERVER_ERROR,
    CAS_CALLBACK_ACCEPTED,
    CAS_CALLBACK_REJECTED,
    SERVICE_PARAMETER_MISSING,
    SERVICE_PARAMETER_MATCH,
    SERVICE_PARAMETER_MISMATCH,
    SERVICE_PARAMETER_DUPLICATE,
    BUSINESS_CALLBACK_ACCEPTED,
    BUSINESS_CALLBACK_REJECTED,
    PERSONAL_IDENTITY_VERIFIED,
    PERSONAL_IDENTITY_UNVERIFIED,
    PERSONAL_IDENTITY_MISMATCH,
    SESSION_REUSED,
    SESSION_ADOPTED,
    SESSION_CLEARED,
    SESSION_REJECTED,
    WEBVIEW_NAVIGATION_ALLOWED,
    WEBVIEW_NAVIGATION_BLOCKED,
    WEBVIEW_NAVIGATION_UPGRADED,
    WEBVIEW_CALLBACK_CONSUMED,
    WEBVIEW_CALLBACK_REJECTED,
    SERVICE_SESSION_MIME_REJECTED,
    SERVICE_SESSION_BODY_MALFORMED,
    SERVICE_SESSION_SCHEMA_REJECTED,
    SERVICE_SESSION_ENVELOPE_ACCEPTED,
    SERVICE_SESSION_INTERACTION_REQUIRED,
    SERVICE_SESSION_SERVER_REJECTED,
    SERVICE_SESSION_COOKIE_NOT_SENT,
    SHIGUANG_ACTIVITIES_QUERY,
    SHIGUANG_MINE_QUERY,
    SHIGUANG_DETAIL_QUERY,
}

/**
 * A single bounded event. All fields are enums, numeric status/duration/size or
 * a short random operation id; there is no free-form string field.
 */
data class DiagnosticEvent(
    val module: DiagnosticModule,
    val action: DiagnosticAction,
    val result: DiagnosticResult,
    val reason: DiagnosticReason? = null,
    val destination: DiagnosticDestination? = null,
    val httpStatus: Int? = null,
    val durationMs: Long? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val exceptionType: DiagnosticExceptionType? = null,
    val frames: List<String> = emptyList(),
    val operationId: String? = null,
    val phase: DiagnosticPhase? = null,
    val detail: DiagnosticDetail? = null,
    val mediaType: DiagnosticMediaType? = null,
    val bodyType: DiagnosticBodyType? = null,
    val responseBytes: Int? = null,
    val itemCount: Int? = null,
    /** 0 or 1 only; never the submitted value. */
    val passwordSubmitted: Int? = null,
    val credentialState: DiagnosticCredentialState? = null,
    val challengeType: DiagnosticChallengeType? = null,
    val challengeEvidence: DiagnosticChallengeEvidence? = null,
    val serviceParameter: DiagnosticServiceParameter? = null,
    val callbackState: DiagnosticCallbackState? = null,
    val navigationFrame: DiagnosticNavigationFrame? = null,
    val bindingSource: DiagnosticBindingSource? = null,
    val timestampEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(httpStatus == null || httpStatus in 100..599) { "httpStatus out of range" }
        require(durationMs == null || durationMs in 0..MAX_DURATION_MS) { "durationMs out of range" }
        require(widthDp == null || widthDp in 0..MAX_DIMENSION_DP) { "widthDp out of range" }
        require(heightDp == null || heightDp in 0..MAX_DIMENSION_DP) { "heightDp out of range" }
        require(responseBytes == null || responseBytes in 0..MAX_RESPONSE_BYTES) {
            "responseBytes out of range"
        }
        require(itemCount == null || itemCount in 0..MAX_ITEM_COUNT) { "itemCount out of range" }
        require(passwordSubmitted == null || passwordSubmitted in 0..1) {
            "passwordSubmitted must be 0 or 1"
        }
        require(operationId == null || OPERATION_ID.matches(operationId)) {
            "operationId must be a short lowercase hex id"
        }
        require(timestampEpochMillis > 0) { "timestamp must be positive" }
        require(frames.size <= MAX_FRAMES) { "too many frames" }
        require(frames.all { it.startsWith("com.upcpocket.app.") && FRAME.matches(it) }) {
            "frames must be app-owned class/method names"
        }
    }

    companion object {
        /**
         * Additive schema: schema 4 adds fixed authentication stages, reasons and
         * evidence fields. Older JSONL records remain readable and are re-exported
         * without interpreting or rewriting fields that the older schema did not know.
         */
        const val SCHEMA_VERSION = 4
        const val MAX_FRAMES = 8
        const val MAX_DURATION_MS = 3_600_000L
        const val MAX_DIMENSION_DP = 20_000
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_ITEM_COUNT = 100_000
        private val OPERATION_ID = Regex("[0-9a-f]{8}")
        private val FRAME = Regex("[A-Za-z0-9_$.<>-]{1,120}")
    }
}

/** Host-provided sink. Implementations must enqueue and must not block callers. */
interface DiagnosticSink {
    fun record(event: DiagnosticEvent)
}

/** Optional sink contract for dropping diagnostics from work started before an account clear. */
interface EpochBoundDiagnosticSink : DiagnosticSink {
    fun captureEpoch(): Long
    fun record(event: DiagnosticEvent, expectedEpoch: Long?)
}

/** Explicit operation context passed across one authentication attempt. */
data class DiagnosticOperation internal constructor(
    val id: String,
    val epochToken: Long?,
) {
    override fun toString(): String = "DiagnosticOperation(id=$id)"
}

/**
 * Process-wide narrow facade. The default is a no-op so feature tests and early
 * startup are unaffected; the host installs its private store during onCreate.
 */
object Diagnostics {
    @Volatile
    private var sink: DiagnosticSink? = null

    fun install(sink: DiagnosticSink?) {
        this.sink = sink
    }

    fun newOperationId(): String =
        ThreadLocalRandom.current().nextInt().toUInt().toString(16).padStart(8, '0')

    fun beginOperation(): DiagnosticOperation {
        val activeSink = sink
        val epoch = (activeSink as? EpochBoundDiagnosticSink)?.captureEpoch()
        return DiagnosticOperation(newOperationId(), epoch)
    }

    /**
     * Records only if a host sink exists and the fixed event model accepts all
     * numeric/size values. Any validation or sink failure is dropped.
     */
    fun record(
        module: DiagnosticModule,
        action: DiagnosticAction,
        result: DiagnosticResult,
        reason: DiagnosticReason? = null,
        destination: DiagnosticDestination? = null,
        httpStatus: Int? = null,
        durationMs: Long? = null,
        widthDp: Int? = null,
        heightDp: Int? = null,
        exceptionType: DiagnosticExceptionType? = null,
        frames: List<String> = emptyList(),
        operationId: String? = null,
        phase: DiagnosticPhase? = null,
        detail: DiagnosticDetail? = null,
        mediaType: DiagnosticMediaType? = null,
        bodyType: DiagnosticBodyType? = null,
        responseBytes: Int? = null,
        itemCount: Int? = null,
        passwordSubmitted: Int? = null,
        credentialState: DiagnosticCredentialState? = null,
        challengeType: DiagnosticChallengeType? = null,
        challengeEvidence: DiagnosticChallengeEvidence? = null,
        serviceParameter: DiagnosticServiceParameter? = null,
        callbackState: DiagnosticCallbackState? = null,
        navigationFrame: DiagnosticNavigationFrame? = null,
        bindingSource: DiagnosticBindingSource? = null,
        operation: DiagnosticOperation? = null,
    ): Boolean {
        val activeSink = sink ?: return false
        val event = runCatching {
            DiagnosticEvent(
                module = module,
                action = action,
                result = result,
                reason = reason,
                destination = destination,
                httpStatus = httpStatus,
                durationMs = durationMs,
                widthDp = widthDp,
                heightDp = heightDp,
                exceptionType = exceptionType,
                frames = frames,
                operationId = operation?.id ?: operationId,
                phase = phase,
                detail = detail,
                mediaType = mediaType,
                bodyType = bodyType,
                responseBytes = responseBytes,
                itemCount = itemCount,
                passwordSubmitted = passwordSubmitted,
                credentialState = credentialState,
                challengeType = challengeType,
                challengeEvidence = challengeEvidence,
                serviceParameter = serviceParameter,
                callbackState = callbackState,
                navigationFrame = navigationFrame,
                bindingSource = bindingSource,
            )
        }.getOrNull() ?: return false
        return runCatching {
            if (activeSink is EpochBoundDiagnosticSink) {
                activeSink.record(event, operation?.epochToken)
            } else {
                activeSink.record(event)
            }
            true
        }.getOrDefault(false)
    }

    fun recordException(
        module: DiagnosticModule,
        action: DiagnosticAction,
        result: DiagnosticResult,
        reason: DiagnosticReason?,
        failure: Throwable,
        durationMs: Long? = null,
        operationId: String? = null,
        phase: DiagnosticPhase? = null,
        detail: DiagnosticDetail? = null,
        operation: DiagnosticOperation? = null,
    ): Boolean = record(
        module = module,
        action = action,
        result = result,
        reason = reason,
        durationMs = durationMs,
        exceptionType = classify(failure),
        frames = appFrames(failure),
        operationId = operationId,
        phase = phase,
        detail = detail,
        operation = operation,
    )

    fun classify(failure: Throwable): DiagnosticExceptionType = when {
        failure is java.net.SocketTimeoutException -> DiagnosticExceptionType.TIMEOUT
        failure is javax.net.ssl.SSLException -> DiagnosticExceptionType.TLS
        failure is java.util.concurrent.CancellationException -> DiagnosticExceptionType.CANCELLED
        failure is SecurityException -> DiagnosticExceptionType.SECURITY
        failure is java.io.IOException -> DiagnosticExceptionType.IO
        failure::class.java.name.contains("Json", ignoreCase = true) ||
            failure::class.java.name.contains("Serialization", ignoreCase = true) ||
            failure::class.java.name.contains("Parse", ignoreCase = true) ->
            DiagnosticExceptionType.PARSE
        else -> DiagnosticExceptionType.OTHER
    }

    /**
     * Only application-owned class/method names, truncated to eight frames.
     * File names, line numbers, messages, causes and non-app paths are excluded.
     */
    fun appFrames(failure: Throwable): List<String> = failure.stackTrace.asSequence()
        .filter { it.className.startsWith("com.upcpocket.app.") }
        .map { "${it.className}.${it.methodName}" }
        .filter { it.length <= 120 && it.matches(Regex("[A-Za-z0-9_$.<>-]+")) }
        .take(DiagnosticEvent.MAX_FRAMES)
        .toList()
}
