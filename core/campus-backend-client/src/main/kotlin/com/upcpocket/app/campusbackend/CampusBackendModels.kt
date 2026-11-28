package com.upcpocket.app.campusbackend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** Platform values accepted by the phase-one session endpoint. */
@Serializable
enum class CampusPlatform {
    ANDROID,
    IOS,
    OTHER,
}

/** Device kind values accepted by the phase-one session endpoint. */
@Serializable
enum class CampusDeviceKind {
    PHONE,
    TABLET,
    OTHER,
}

@Serializable
enum class CampusAccountState {
    ACTIVE,
    REAUTH_REQUIRED,
    DISABLED,
}

/** The phase-one response has no tokenType field; Bearer is the fixed scheme. */
@Serializable
data class CampusSessionResponse(
    val token: String,
    val deviceId: String,
    val expiresAt: String,
) {
    override fun toString(): String =
        "CampusSessionResponse(token=[REDACTED], deviceId=[REDACTED], expiresAt=$expiresAt)"
}

/** Phase-one GET /me response. */
@Serializable
data class CampusAccount(
    val accountId: String,
    val state: CampusAccountState,
    val credentialGeneration: Long,
    val gateRevision: Long,
) {
    override fun toString(): String =
        "CampusAccount(accountId=[REDACTED], state=$state, " +
            "credentialGeneration=$credentialGeneration, gateRevision=$gateRevision)"
}

@Serializable
data class CampusDevice(
    val deviceId: String,
    val installationId: String,
    val platform: String,
    val kind: String,
    val expiresAt: String,
) {
    override fun toString(): String =
        "CampusDevice(deviceId=[REDACTED], installationId=[REDACTED], " +
            "platform=$platform, kind=$kind, expiresAt=$expiresAt)"
}

@Serializable
data class TimetableIndexResponse(
    val terms: List<TimetableTermResponse>,
    val currentYear: String? = null,
    val currentTerm: String? = null,
    val currentWeek: Int? = null,
    val weekDates: List<String>? = null,
    val compatibilityNote: String? = null,
) {
    override fun toString(): String =
        "TimetableIndexResponse(terms=${terms.size}, currentYear=[REDACTED], " +
            "currentTerm=[REDACTED], currentWeek=$currentWeek, weekDates=${weekDates?.size}, " +
            "compatibilityNote=[REDACTED])"
}

@Serializable
data class TimetableTermResponse(
    val year: String,
    val term: String,
    val label: String,
    val startDay: String,
    val totalWeeks: Int,
) {
    override fun toString(): String =
        "TimetableTermResponse(year=[REDACTED], term=[REDACTED], label=[REDACTED], " +
            "startDay=[REDACTED], totalWeeks=$totalWeeks)"
}

@Serializable
data class TimetableWeekResponse(
    val year: String,
    val term: String,
    val week: Int,
    val weekDates: List<String>,
    val meetings: List<TimetableMeetingResponse>,
    val complete: Boolean,
    val fetchedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "TimetableWeekResponse(year=[REDACTED], term=[REDACTED], week=$week, " +
            "weekDates=${weekDates.size}, meetings=${meetings.size}, complete=$complete, " +
            "fetchedAtEpochMillis=$fetchedAtEpochMillis)"
}

@Serializable
data class TimetableMeetingResponse(
    val courseName: String,
    val date: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val rawPeriod: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val weeksLabel: String? = null,
    val courseCode: String? = null,
    val classGroup: String? = null,
) {
    override fun toString(): String =
        "TimetableMeetingResponse(courseName=[REDACTED], date=[REDACTED], " +
            "dayOfWeek=$dayOfWeek, startPeriod=$startPeriod, endPeriod=$endPeriod, " +
            "details=[REDACTED])"
}

@Serializable
data class CampusCardBalanceResponse(
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "CampusCardBalanceResponse(amounts=[REDACTED], fetchedAtEpochMillis=$fetchedAtEpochMillis)"
}

@Serializable
data class CampusCardTransactionsResponse(
    val page: Int,
    val size: Int,
    val total: Long,
    val pages: Int,
    val rows: List<CampusCardTransactionResponse>,
) {
    override fun toString(): String =
        "CampusCardTransactionsResponse(page=$page, size=$size, total=$total, pages=$pages, " +
            "rows=${rows.size})"
}

@Serializable
data class CampusCardTransactionResponse(
    val rowKey: String,
    val title: String,
    val time: String,
    val amountFen: Long,
) {
    override fun toString(): String = "CampusCardTransactionResponse(details=[REDACTED])"
}

@Serializable
data class CampusCardTransactionsSummaryResponse(
    val incomeFen: Long,
    val expenseFen: Long,
) {
    override fun toString(): String = "CampusCardTransactionsSummaryResponse(amounts=[REDACTED])"
}

@Serializable
data class GradesTermsResponse(
    val terms: List<GradesTermResponse>,
    val currentTerm: GradesTermResponse? = null,
) {
    override fun toString(): String =
        "GradesTermsResponse(terms=${terms.size}, currentTerm=[REDACTED])"
}

@Serializable
data class GradesTermResponse(
    val year: String,
    val term: String,
) {
    override fun toString(): String = "GradesTermResponse(year=[REDACTED], term=[REDACTED])"
}

@Serializable
data class GradesCoursesResponse(
    val year: String,
    val term: String,
    val courses: List<GradesCourseResponse>,
    val complete: Boolean,
    val fetchedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "GradesCoursesResponse(year=[REDACTED], term=[REDACTED], courses=${courses.size}, " +
            "complete=$complete, fetchedAtEpochMillis=$fetchedAtEpochMillis)"
}

/** Score and credits accept either JSON strings or numbers from the upstream service. */
@Serializable
data class GradesCourseResponse(
    val courseName: String,
    val score: JsonPrimitive? = null,
    val credits: JsonPrimitive? = null,
    val courseType: String? = null,
    val examType: String? = null,
) {
    val scoreText: String? get() = score?.content
    val creditsText: String? get() = credits?.content

    override fun toString(): String = "GradesCourseResponse(details=[REDACTED])"
}

/** Only server-defined codes cross this boundary; response text is never retained. */
enum class BackendErrorCode(val wireCode: String?) {
    VALIDATION_FAILED("VALIDATION_FAILED"),
    CUSTODY_ACCEPTANCE_REQUIRED("CUSTODY_ACCEPTANCE_REQUIRED"),
    SIGN_IN_FAILED("SIGN_IN_FAILED"),
    TOKEN_INVALID("TOKEN_INVALID"),
    UNAUTHENTICATED("UNAUTHENTICATED"),
    FORBIDDEN("FORBIDDEN"),
    NOT_FOUND("NOT_FOUND"),
    DISABLED("DISABLED"),
    VERIFICATION_BUSY("VERIFICATION_BUSY"),
    INTERACTIVE_CHALLENGE("INTERACTIVE_CHALLENGE"),
    CREDENTIAL_UPDATE_REQUIRED("CREDENTIAL_UPDATE_REQUIRED"),
    KEY_VERSION_CONFLICT("KEY_VERSION_CONFLICT"),
    RATE_LIMITED("RATE_LIMITED"),
    REVISION_CHANGED("REVISION_CHANGED"),
    MODULE_DISABLED("MODULE_DISABLED"),
    CIRCUIT_OPEN("CIRCUIT_OPEN"),
    UPSTREAM_UNAVAILABLE("UPSTREAM_UNAVAILABLE"),
    DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE"),
    UPSTREAM_TIMEOUT("UPSTREAM_TIMEOUT"),
    IDENTITY_UNSUPPORTED("IDENTITY_UNSUPPORTED"),
    UPSTREAM_PROTOCOL_ERROR("UPSTREAM_PROTOCOL_ERROR"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    NOT_CONFIGURED(null),
    INVALID_ORIGIN(null),
    REDIRECT_REJECTED(null),
    NETWORK_UNAVAILABLE(null),
    NETWORK_TIMEOUT(null),
    TLS_ERROR(null),
    UNKNOWN(null);

    companion object {
        fun fromWire(value: String?): BackendErrorCode =
            entries.firstOrNull { it.wireCode == value } ?: UNKNOWN

        /** Server-defined error codes used by the phase-one and phase-two contracts. */
        val wireCodes: Set<String> = entries.mapNotNullTo(linkedSetOf()) { it.wireCode }
    }
}

data class BackendFailure(
    val code: BackendErrorCode,
    val requestId: String? = null,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
) {
    override fun toString(): String =
        "BackendFailure(code=$code, requestId=[REDACTED], " +
            "httpStatus=$httpStatus, retryAfterMillis=$retryAfterMillis)"
}

sealed interface BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>
    data object NoContent : BackendResult<Nothing>
    data class Failure(val error: BackendFailure) : BackendResult<Nothing>
}
