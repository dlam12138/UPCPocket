package com.upcpocket.app.campusbackend

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.security.cert.CertificateException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import kotlin.coroutines.resume

/**
 * Endpoint trust policy. Release callers must provide the exact school-approved HTTPS origins.
 * Debug callers may additionally opt in to a small local-development host allowlist.
 */
sealed interface BackendOriginPolicy {
    data class Release(val approvedHttpsOrigins: Set<String>) : BackendOriginPolicy

    data object Debug : BackendOriginPolicy

    companion object {
        val DEFAULT_LOCAL_DEVELOPMENT_HOSTS: Set<String> =
            setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")
    }
}

/**
 * Pure-JVM REST client for the Pocket custody API and its first read-only campus services.
 * The client never persists credentials and never adds student identifiers to business calls.
 */
class CampusBackendClient private constructor(
    origin: String?,
    policy: BackendOriginPolicy,
    private val clockMillis: () -> Long,
) {
    private sealed interface Endpoint {
        data object NotConfigured : Endpoint
        data object Invalid : Endpoint
        data class Ready(val origin: HttpUrl) : Endpoint
    }

    private val endpoint: Endpoint = resolveEndpoint(origin, policy)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }
    private val http = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    /** True only when the configured origin passed its environment-specific trust policy. */
    val isConfigured: Boolean
        get() = endpoint is Endpoint.Ready

    /**
     * POST /api/v1/auth/sessions. Password bytes are encoded directly from [CharArray] and wiped
     * after the call; the caller retains responsibility for wiping its input in `finally`.
     */
    suspend fun createSession(
        loginName: String,
        password: CharArray,
        acceptCustody: Boolean,
        installationId: String,
        platform: CampusPlatform,
        kind: CampusDeviceKind,
    ): BackendResult<CampusSessionResponse> {
        configurationFailure()?.let { return it }
        if (!validLoginName(loginName) || password.isEmpty() || password.size > MAX_PASSWORD_CHARS ||
            !isUuid(installationId)
        ) {
            return invalidInput()
        }
        val body = SensitiveJsonBody.session(
            loginName = loginName,
            password = password,
            acceptCustody = acceptCustody,
            installationId = installationId,
            platform = platform,
            kind = kind,
        )
        val request = requestBuilder("auth", "sessions")
            .post(body)
            .build()
        return execute(request, body, BackendErrorCode.SIGN_IN_FAILED) {
            decode(it, CampusSessionResponse.serializer())
        }
    }

    /** POST /api/v1/auth/credential-updates. The OpenAPI field name is `password`. */
    suspend fun updateCredential(
        loginName: String,
        password: CharArray,
    ): BackendResult<Unit> {
        configurationFailure()?.let { return it }
        if (!validLoginName(loginName) || password.isEmpty() || password.size > MAX_PASSWORD_CHARS) {
            return invalidInput()
        }
        val body = SensitiveJsonBody.credentialUpdate(loginName, password)
        val request = requestBuilder("auth", "credential-updates")
            .post(body)
            .build()
        return execute(request, body, BackendErrorCode.SIGN_IN_FAILED) { parseNoContent(it) }
    }

    /** GET /api/v1/me. */
    suspend fun getMe(token: String): BackendResult<CampusAccount> =
        getProtected(token, listOf("me"), CampusAccount.serializer())

    /** GET /api/v1/me/devices. */
    suspend fun getDevices(token: String): BackendResult<List<CampusDevice>> =
        getProtected(token, listOf("me", "devices"), ListSerializer(CampusDevice.serializer()))

    /** DELETE /api/v1/me/devices/{deviceId}. */
    suspend fun revokeDevice(token: String, deviceId: String): BackendResult<Unit> {
        configurationFailure()?.let { return it }
        if (!validToken(token)) return unauthorized()
        if (!isUuid(deviceId)) return invalidInput()
        val request = authorizedBuilder(token, "me", "devices", deviceId)
            .delete()
            .build()
        return execute(request, defaultUnauthorized = BackendErrorCode.UNAUTHENTICATED) {
            parseNoContent(it)
        }
    }

    /** DELETE /api/v1/me/session. */
    suspend fun deleteSession(token: String): BackendResult<Unit> =
        deleteProtected(token, listOf("me", "session"))

    /** DELETE /api/v1/me. This deletes the managed account and must be explicitly confirmed by UI. */
    suspend fun unbindAccount(token: String): BackendResult<Unit> =
        deleteProtected(token, listOf("me"))

    /** GET /api/v1/timetable/index. */
    suspend fun getTimetableIndex(token: String): BackendResult<TimetableIndexResponse> =
        getProtected(
            token,
            listOf("timetable", "index"),
            TimetableIndexResponse.serializer(),
        )

    /** GET /api/v1/timetable/week?year=&term=&week=. No identity fields are accepted. */
    suspend fun getTimetableWeek(
        token: String,
        year: String,
        term: String,
        week: Int,
    ): BackendResult<TimetableWeekResponse> {
        configurationFailure()?.let { return it }
        if (!validToken(token)) return unauthorized()
        if (!validTermToken(year) || !validTermToken(term) || week !in 1..MAX_TERM_WEEKS) {
            return invalidInput()
        }
        val request = authorizedBuilder(token, "timetable", "week")
            .get()
            .url(
                url(
                    listOf("timetable", "week"),
                    listOf("year" to year, "term" to term, "week" to week.toString()),
                ),
            )
            .build()
        return execute(request, defaultUnauthorized = BackendErrorCode.UNAUTHENTICATED) {
            decode(it, TimetableWeekResponse.serializer())
        }
    }

    /** GET /api/v1/card/balance. */
    suspend fun getCardBalance(token: String): BackendResult<CampusCardBalanceResponse> =
        getProtected(token, listOf("card", "balance"), CampusCardBalanceResponse.serializer())

    /** GET /api/v1/card/transactions?month=&page=&size=. A missing month means all dates. */
    suspend fun getCardTransactions(
        token: String,
        month: String?,
        page: Int,
        size: Int,
    ): BackendResult<CampusCardTransactionsResponse> {
        configurationFailure()?.let { return it }
        if (!validToken(token)) return unauthorized()
        if ((month != null && !isValidMonth(month)) || page < 1 || size !in 1..MAX_PAGE_SIZE) {
            return invalidInput()
        }
        val query = buildList {
            month?.let { add("month" to it) }
            add("page" to page.toString())
            add("size" to size.toString())
        }
        val request = authorizedBuilder(token, "card", "transactions")
            .get()
            .url(url(listOf("card", "transactions"), query))
            .build()
        return execute(request, defaultUnauthorized = BackendErrorCode.UNAUTHENTICATED) {
            decode(it, CampusCardTransactionsResponse.serializer())
        }
    }

    /** GET /api/v1/card/transactions/summary?month=. A missing month means all dates. */
    suspend fun getCardTransactionsSummary(
        token: String,
        month: String?,
    ): BackendResult<CampusCardTransactionsSummaryResponse> {
        configurationFailure()?.let { return it }
        if (!validToken(token)) return unauthorized()
        if (month != null && !isValidMonth(month)) return invalidInput()
        val query = month?.let { listOf("month" to it) }.orEmpty()
        val request = authorizedBuilder(token, "card", "transactions", "summary")
            .get()
            .url(url(listOf("card", "transactions", "summary"), query))
            .build()
        return execute(request, defaultUnauthorized = BackendErrorCode.UNAUTHENTICATED) {
            decode(it, CampusCardTransactionsSummaryResponse.serializer())
        }
    }

    /** GET /api/v1/grades/terms. */
    suspend fun getGradesTerms(token: String): BackendResult<GradesTermsResponse> =
        getProtected(token, listOf("grades", "terms"), GradesTermsResponse.serializer())

    /** GET /api/v1/grades/courses?year=&term=. */
    suspend fun getGradesCourses(
        token: String,
        year: String,
        term: String,
    ): BackendResult<GradesCoursesResponse> {
        configurationFailure()?.let { return it }
        if (!validToken(token)) return unauthorized()
        if (!validTermToken(year) || !validTermToken(term)) return invalidInput()
        val request = authorizedBuilder(token, "grades", "courses")
            .get()
            .url(url(listOf("grades", "courses"), listOf("year" to year, "term" to term)))
            .build()
        return execute(request, defaultUnauthorized = BackendErrorCode.UNAUTHENTICATED) {
            decode(it, GradesCoursesResponse.serializer())
        }
    }

    private suspend fun <T> getProtected(
        token: String,
        path: List<String>,
        serializer: DeserializationStrategy<T>,
    ): BackendResult<T> {
        configurationFailure()?.let { return it }
        if (!validToken(token)) return unauthorized()
        val request = authorizedBuilder(token, *path.toTypedArray()).get().build()
        return execute(request, defaultUnauthorized = BackendErrorCode.UNAUTHENTICATED) {
            decode(it, serializer)
        }
    }

    private suspend fun deleteProtected(token: String, path: List<String>): BackendResult<Unit> {
        configurationFailure()?.let { return it }
        if (!validToken(token)) return unauthorized()
        val request = authorizedBuilder(token, *path.toTypedArray()).delete().build()
        return execute(request, defaultUnauthorized = BackendErrorCode.UNAUTHENTICATED) {
            parseNoContent(it)
        }
    }

    private fun authorizedBuilder(token: String, vararg path: String): Request.Builder =
        requestBuilder(*path)
            .header("Authorization", "Bearer $token")

    private fun requestBuilder(vararg path: String): Request.Builder =
        Request.Builder()
            .url(url(path.toList()))
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")

    private fun url(
        path: List<String>,
        query: List<Pair<String, String>> = emptyList(),
    ): HttpUrl {
        val base = (endpoint as? Endpoint.Ready)?.origin
            ?: error("A trusted backend origin is required before building requests")
        val builder = base.newBuilder().addPathSegment("api").addPathSegment("v1")
        path.forEach { builder.addPathSegment(it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private suspend fun <T> execute(
        request: Request,
        sensitiveBody: SensitiveJsonBody? = null,
        defaultUnauthorized: BackendErrorCode,
        parse: (Response) -> BackendResult<T>,
    ): BackendResult<T> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
            sensitiveBody?.clear()
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    sensitiveBody?.clear()
                    if (continuation.isActive) {
                        continuation.resume(BackendResult.Failure(BackendFailure(networkCode(e))))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive) {
                                BackendResult.Failure(BackendFailure(BackendErrorCode.NETWORK_UNAVAILABLE))
                            } else if (!it.isSuccessful) {
                                decodeFailure(it, defaultUnauthorized)
                            } else {
                                parse(it)
                            }
                        }
                    } catch (_: Exception) {
                        BackendResult.Failure(
                            BackendFailure(
                                code = BackendErrorCode.UPSTREAM_PROTOCOL_ERROR,
                                httpStatus = response.code,
                            ),
                        )
                    } finally {
                        sensitiveBody?.clear()
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            },
        )
    }

    private fun <T> decode(
        response: Response,
        serializer: DeserializationStrategy<T>,
    ): BackendResult<T> {
        if (response.code == HTTP_NO_CONTENT) {
            return protocolFailure(response.code)
        }
        val bytes = readLimited(response, MAX_RESPONSE_BYTES)
            ?: return protocolFailure(response.code)
        return try {
            val value = json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
            BackendResult.Success(value)
        } catch (_: SerializationException) {
            protocolFailure(response.code)
        } catch (_: IllegalArgumentException) {
            protocolFailure(response.code)
        }
    }

    private fun parseNoContent(response: Response): BackendResult<Unit> {
        if (response.code != HTTP_NO_CONTENT) return protocolFailure(response.code)
        return BackendResult.NoContent
    }

    private fun decodeFailure(
        response: Response,
        defaultUnauthorized: BackendErrorCode,
    ): BackendResult.Failure {
        val status = response.code
        if (status in 300..399) {
            return BackendResult.Failure(
                BackendFailure(
                    code = BackendErrorCode.REDIRECT_REJECTED,
                    httpStatus = status,
                ),
            )
        }
        val bodyBytes = readLimited(response, MAX_ERROR_BYTES)
        val body = bodyBytes?.let { bytes ->
            runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }
                .getOrNull()
        }
        val codeText = body?.get("code")?.let { element ->
            runCatching { element.jsonPrimitive.content }.getOrNull()
        }
        val requestIdText = body?.get("requestId")?.let { element ->
            runCatching { element.jsonPrimitive.content }.getOrNull()
        }
        val fallback = when {
            status == HTTP_UNAUTHORIZED -> defaultUnauthorized
            status == HTTP_TOO_MANY_REQUESTS -> BackendErrorCode.RATE_LIMITED
            status == HTTP_GATEWAY_TIMEOUT -> BackendErrorCode.UPSTREAM_TIMEOUT
            status == HTTP_SERVICE_UNAVAILABLE -> BackendErrorCode.UPSTREAM_UNAVAILABLE
            else -> BackendErrorCode.UNKNOWN
        }
        val code = if (codeText != null) BackendErrorCode.fromWire(codeText) else fallback
        val requestId = requestIdText?.takeIf(::isUuid)
        return BackendResult.Failure(
            BackendFailure(
                code = code,
                requestId = requestId,
                httpStatus = status,
                retryAfterMillis = parseRetryAfter(response.header("Retry-After"), clockMillis()),
            ),
        )
    }

    private fun readLimited(response: Response, maxBytes: Int): ByteArray? {
        val body = response.body ?: return null
        if (body.contentLength() > maxBytes) return null
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) return@use null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun protocolFailure(status: Int?): BackendResult.Failure =
        BackendResult.Failure(BackendFailure(BackendErrorCode.UPSTREAM_PROTOCOL_ERROR, httpStatus = status))

    private fun configurationFailure(): BackendResult.Failure? = when (endpoint) {
        Endpoint.NotConfigured -> BackendResult.Failure(BackendFailure(BackendErrorCode.NOT_CONFIGURED))
        Endpoint.Invalid -> BackendResult.Failure(BackendFailure(BackendErrorCode.INVALID_ORIGIN))
        is Endpoint.Ready -> null
    }

    private fun invalidInput(): BackendResult.Failure =
        BackendResult.Failure(BackendFailure(BackendErrorCode.VALIDATION_FAILED))

    private fun unauthorized(): BackendResult.Failure =
        BackendResult.Failure(BackendFailure(BackendErrorCode.UNAUTHENTICATED))

    private fun validToken(token: String): Boolean =
        token.length in 1..MAX_TOKEN_CHARS && token.all { it.code in 0x21..0x7e }

    private fun parseRetryAfter(value: String?, nowMillis: Long): Long? {
        val header = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val seconds = header.toLongOrNull()
        if (seconds != null) {
            return seconds.coerceAtLeast(0).saturatingSecondsToMillis()
        }
        val targetMillis = try {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: Exception) {
            return null
        }
        return (targetMillis - nowMillis).coerceAtLeast(0).coerceAtMost(MAX_RETRY_AFTER_MILLIS)
    }

    private fun Long.saturatingSecondsToMillis(): Long =
        if (this > MAX_RETRY_AFTER_MILLIS / 1000) MAX_RETRY_AFTER_MILLIS else this * 1000

    private fun networkCode(error: IOException): BackendErrorCode = when {
        error.findCause<SSLException>() != null || error.findCause<CertificateException>() != null ->
            BackendErrorCode.TLS_ERROR
        error.findCause<java.net.SocketTimeoutException>() != null -> BackendErrorCode.NETWORK_TIMEOUT
        else -> BackendErrorCode.NETWORK_UNAVAILABLE
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun resolveEndpoint(origin: String?, policy: BackendOriginPolicy): Endpoint {
        if (origin.isNullOrBlank()) return Endpoint.NotConfigured
        val parsed = parseOrigin(origin) ?: return Endpoint.Invalid
        val canonical = canonicalOrigin(parsed)
        val approvedOrigins = (policy as? BackendOriginPolicy.Release)
            ?.approvedHttpsOrigins
            .orEmpty()
            .mapNotNull(::parseOrigin)
            .map(::canonicalOrigin)
            .toSet()
        val trustedApprovedHttps = parsed.scheme == "https" && canonical in approvedOrigins
        val trustedLocalDebug = policy == BackendOriginPolicy.Debug &&
            parsed.host.lowercase(Locale.ROOT) in BackendOriginPolicy.DEFAULT_LOCAL_DEVELOPMENT_HOSTS &&
            parsed.scheme in setOf("http", "https")
        if (!trustedApprovedHttps && !trustedLocalDebug) return Endpoint.Invalid
        if (policy is BackendOriginPolicy.Release && parsed.scheme != "https") return Endpoint.Invalid
        return Endpoint.Ready(parsed)
    }

    private fun parseOrigin(value: String): HttpUrl? {
        val uri = try {
            URI(value.trim())
        } catch (_: Exception) {
            return null
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath !in setOf(null, "", "/")
        ) {
            return null
        }
        val url = value.trim().toHttpUrlOrNull() ?: return null
        if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() ||
            url.encodedPath != "/" || url.encodedQuery != null || url.encodedFragment != null
        ) {
            return null
        }
        return url
    }

    private fun canonicalOrigin(url: HttpUrl): String =
        url.newBuilder().encodedPath("/").build().toString().removeSuffix("/")

    private fun validLoginName(value: String): Boolean =
        value.length in 1..MAX_LOGIN_NAME_CHARS && value.none { it.isWhitespace() || it.isISOControl() }

    private fun validTermToken(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_TERM_TOKEN_CHARS &&
            value.none { it.isISOControl() || it == '&' || it == '=' }

    private fun isValidMonth(value: String): Boolean = MONTH_PATTERN.matches(value)

    private fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    companion object {
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVICE_UNAVAILABLE = 503
        private const val HTTP_GATEWAY_TIMEOUT = 504
        private const val MAX_LOGIN_NAME_CHARS = 128
        private const val MAX_PASSWORD_CHARS = 512
        private const val MAX_TOKEN_CHARS = 4096
        private const val MAX_TERM_TOKEN_CHARS = 64
        private const val MAX_TERM_WEEKS = 30
        private const val MAX_PAGE_SIZE = 200
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024
        private const val MAX_RETRY_AFTER_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val MONTH_PATTERN = Regex("[0-9]{4}-(0[1-9]|1[0-2])")
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        fun create(
            origin: String?,
            policy: BackendOriginPolicy,
            clockMillis: () -> Long = System::currentTimeMillis,
        ): CampusBackendClient = CampusBackendClient(origin, policy, clockMillis)
    }
}

private class SensitiveJsonBody private constructor(
    private val content: ByteArray,
) : RequestBody() {
    private val lock = Any()
    private var cleared = false

    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    override fun contentLength(): Long = synchronized(lock) {
        if (cleared) 0L else content.size.toLong()
    }

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        synchronized(lock) {
            check(!cleared) { "Sensitive request body already released" }
            sink.write(content)
        }
    }

    fun clear() {
        synchronized(lock) {
            content.fill(0)
            cleared = true
        }
    }

    companion object {
        fun session(
            loginName: String,
            password: CharArray,
            acceptCustody: Boolean,
            installationId: String,
            platform: CampusPlatform,
            kind: CampusDeviceKind,
        ): SensitiveJsonBody = build {
            writeAscii("{\"loginName\":")
            writeJsonString(loginName.toCharArray())
            writeAscii(",\"password\":")
            writeJsonString(password.copyOf())
            writeAscii(",\"acceptCustody\":$acceptCustody,\"installationId\":")
            writeJsonString(installationId.toCharArray())
            writeAscii(",\"platform\":\"${platform.name}\",\"kind\":\"${kind.name}\"}")
        }

        fun credentialUpdate(loginName: String, password: CharArray): SensitiveJsonBody = build {
            writeAscii("{\"loginName\":")
            writeJsonString(loginName.toCharArray())
            writeAscii(",\"password\":")
            writeJsonString(password.copyOf())
            writeAscii("}")
        }

        private fun build(write: WipingJsonBuffer.() -> Unit): SensitiveJsonBody {
            val buffer = WipingJsonBuffer()
            return try {
                buffer.write()
                SensitiveJsonBody(buffer.take())
            } finally {
                buffer.clear()
            }
        }
    }
}

/** Writes JSON directly from chars so the school password is not copied into a String. */
private class WipingJsonBuffer : ByteArrayOutputStream() {
    fun writeAscii(value: String) {
        value.forEach { write(it.code) }
    }

    fun writeJsonString(chars: CharArray) {
        try {
            write('"'.code)
            var index = 0
            while (index < chars.size) {
                val current = chars[index]
                when (current) {
                    '"' -> writeAscii("\\\"")
                    '\\' -> writeAscii("\\\\")
                    '\b' -> writeAscii("\\b")
                    '\u000c' -> writeAscii("\\f")
                    '\n' -> writeAscii("\\n")
                    '\r' -> writeAscii("\\r")
                    '\t' -> writeAscii("\\t")
                    else -> {
                        when {
                            current.code < 0x20 -> writeUnicodeEscape(current.code)
                            current.isHighSurrogate() && index + 1 < chars.size && chars[index + 1].isLowSurrogate() -> {
                                val codePoint = Character.toCodePoint(current, chars[index + 1])
                                writeUtf8CodePoint(codePoint)
                                index++
                            }
                            current.isSurrogate() -> writeUnicodeEscape(current.code)
                            else -> writeUtf8CodePoint(current.code)
                        }
                    }
                }
                index++
            }
            write('"'.code)
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun writeUnicodeEscape(value: Int) {
        val hex = value.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
        writeAscii("\\u$hex")
    }

    private fun writeUtf8CodePoint(codePoint: Int) {
        when {
            codePoint <= 0x7f -> write(codePoint)
            codePoint <= 0x7ff -> {
                write(0xc0 or (codePoint shr 6))
                write(0x80 or (codePoint and 0x3f))
            }
            codePoint <= 0xffff -> {
                write(0xe0 or (codePoint shr 12))
                write(0x80 or ((codePoint shr 6) and 0x3f))
                write(0x80 or (codePoint and 0x3f))
            }
            else -> {
                write(0xf0 or (codePoint shr 18))
                write(0x80 or ((codePoint shr 12) and 0x3f))
                write(0x80 or ((codePoint shr 6) and 0x3f))
                write(0x80 or (codePoint and 0x3f))
            }
        }
    }

    fun take(): ByteArray = toByteArray()

    fun clear() {
        buf.fill(0)
        reset()
    }
}

