package com.upcpocket.app.grades

/** Narrow read-only boundary supplied by the host for the Pocket campus backend. */
interface GradesBackendSource {
    suspend fun terms(): GradesBackendTerms

    suspend fun courses(year: String, term: String): GradesBackendCourses
}

data class GradesBackendTerm(
    val year: String,
    val term: String,
)

data class GradesBackendTerms(
    val terms: List<GradesBackendTerm>,
    val currentTerm: GradesBackendTerm? = null,
    /** Terms are a complete index when true; partial indexes must not replace cache state. */
    val complete: Boolean = true,
)

data class GradesBackendCourse(
    val courseName: String,
    val score: String? = null,
    val credits: Double? = null,
    val courseType: String? = null,
    val examType: String? = null,
)

data class GradesBackendCourses(
    val courses: List<GradesBackendCourse>,
    val complete: Boolean,
    val fetchedAtEpochMillis: Long,
)

internal fun GradesBackendTerms.toIndex(): GradesIndex {
    if (!complete || terms.isEmpty() || terms.size > 200) throw invalidGradesBackendData()
    val mapped = terms.map { item ->
        try {
            GradesTerm(item.year, item.term)
        } catch (_: IllegalArgumentException) {
            throw invalidGradesBackendData()
        }
    }
    if (mapped.map { it.key }.toSet().size != mapped.size) throw invalidGradesBackendData()
    val current = currentTerm?.let { item ->
        mapped.firstOrNull { it.year == item.year && it.term == item.term }
            ?: throw invalidGradesBackendData()
    }
    return GradesIndex(mapped, current)
}

internal data class GradesBackendTermResult(
    val courses: List<GradesCourse>,
    val fetchedAtEpochMillis: Long,
)

internal fun GradesBackendCourses.toTermResult(term: GradesTerm): GradesBackendTermResult {
    if (!complete || fetchedAtEpochMillis <= 0L || courses.size > GradesProtocol.MAX_COURSES_PER_TERM) {
        throw invalidGradesBackendData()
    }
    val mapped = courses.map { item ->
        if (item.courseName.isBlank() || item.courseName.length > 512 ||
            item.courseName.any { Character.isISOControl(it) } ||
            item.score?.length?.let { it > 128 } == true ||
            item.courseType?.length?.let { it > 128 } == true ||
            item.examType?.length?.let { it > 128 } == true ||
            item.credits?.let { !it.isFinite() || it < 0.0 } == true
        ) {
            throw invalidGradesBackendData()
        }
        GradesCourse(
            term = term,
            courseName = item.courseName,
            score = item.score?.trim()?.takeIf { it.isNotEmpty() },
            credits = item.credits,
            courseType = item.courseType?.trim()?.takeIf { it.isNotEmpty() },
            examType = item.examType?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
    return GradesBackendTermResult(mapped, fetchedAtEpochMillis)
}

private fun invalidGradesBackendData() =
    GradesBackendFailure(GradesBackendFailureCode.UPSTREAM_PROTOCOL_ERROR)

/** Fixed failures crossing the host adapter into grades; never contains response text. */
enum class GradesBackendFailureCode {
    SERVICE_NOT_READY,
    TOKEN_INVALID,
    UNAUTHENTICATED,
    DISABLED,
    INTERACTIVE_CHALLENGE,
    CREDENTIAL_UPDATE_REQUIRED,
    VERIFICATION_BUSY,
    MODULE_DISABLED,
    CIRCUIT_OPEN,
    UPSTREAM_UNAVAILABLE,
    UPSTREAM_TIMEOUT,
    DEPENDENCY_UNAVAILABLE,
    VALIDATION_FAILED,
    IDENTITY_UNSUPPORTED,
    UPSTREAM_PROTOCOL_ERROR,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
}

class GradesBackendFailure(
    val code: GradesBackendFailureCode,
    val retryAfterSeconds: Long? = null,
) : Exception(null, null, false, false)
