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
    CLASSROOM,
    CARD,
    POWER,
    WIDGET,
}

enum class DiagnosticAction {
    AUTH_START,
    AUTH_COMPLETE,
    AUTH_INTERACTION,
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
    val timestampEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(httpStatus == null || httpStatus in 100..599) { "httpStatus out of range" }
        require(durationMs == null || durationMs in 0..MAX_DURATION_MS) { "durationMs out of range" }
        require(widthDp == null || widthDp in 0..MAX_DIMENSION_DP) { "widthDp out of range" }
        require(heightDp == null || heightDp in 0..MAX_DIMENSION_DP) { "heightDp out of range" }
        require(operationId == null || OPERATION_ID.matches(operationId)) {
            "operationId must be a short lowercase hex id"
        }
        require(timestampEpochMillis > 0) { "timestamp must be positive" }
        require(frames.size <= MAX_FRAMES) { "too many frames" }
        require(frames.all { FRAME.matches(it) }) { "frame contains non-allowlisted characters" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_FRAMES = 8
        const val MAX_DURATION_MS = 3_600_000L
        const val MAX_DIMENSION_DP = 20_000
        private val OPERATION_ID = Regex("[0-9a-f]{8}")
        private val FRAME = Regex("[A-Za-z0-9_$.<>-]{1,120}")
    }
}

/** Host-provided sink. Implementations must enqueue and must not block callers. */
interface DiagnosticSink {
    fun record(event: DiagnosticEvent)
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
    ): Boolean {
        if (sink == null) return false
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
                operationId = operationId,
            )
        }.getOrNull() ?: return false
        return runCatching {
            sink?.record(event)
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
    ): Boolean = record(
        module = module,
        action = action,
        result = result,
        reason = reason,
        durationMs = durationMs,
        exceptionType = classify(failure),
        frames = appFrames(failure),
        operationId = operationId,
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
        .map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        .filter { it.length <= 120 && it.matches(Regex("[A-Za-z0-9_$.<>-]+")) }
        .take(DiagnosticEvent.MAX_FRAMES)
        .toList()
}
