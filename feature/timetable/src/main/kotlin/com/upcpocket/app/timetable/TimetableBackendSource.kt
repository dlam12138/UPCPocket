package com.upcpocket.app.timetable

import java.time.LocalDate

/** Narrow read-only boundary supplied by the host for the Pocket campus backend. */
interface TimetableBackendSource {
    suspend fun index(): TimetableBackendIndex

    suspend fun week(year: String, term: String, week: Int): TimetableBackendWeek
}

data class TimetableBackendIndex(
    val terms: List<TimetableBackendTerm>,
    val currentYear: String? = null,
    val currentTerm: String? = null,
    val currentWeek: Int? = null,
    val weekDates: List<LocalDate>? = null,
    val compatibilityNote: String? = null,
)

data class TimetableBackendTerm(
    val year: String,
    val term: String,
    val label: String,
    val startDay: LocalDate,
    val totalWeeks: Int,
)

data class TimetableBackendWeek(
    val year: String,
    val term: String,
    val week: Int,
    val weekDates: List<LocalDate>,
    val meetings: List<TimetableBackendMeeting>,
    val complete: Boolean,
    val fetchedAtEpochMillis: Long,
)

data class TimetableBackendMeeting(
    val courseName: String,
    val date: LocalDate,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val rawPeriod: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val weeksLabel: String? = null,
    val courseCode: String? = null,
    val classGroup: String? = null,
)

internal fun TimetableBackendIndex.toDomainIndex(): TimetableIndex {
    if (terms.isEmpty() || terms.size > 200) throw invalidBackendData()
    val mappedTerms = terms.map { item ->
        try {
            TimetableTerm(
                year = item.year,
                term = item.term,
                startDay = item.startDay,
                totalWeeks = item.totalWeeks,
                label = item.label,
            )
        } catch (_: IllegalArgumentException) {
            throw invalidBackendData()
        }
    }
    val keys = mappedTerms.map { it.year to it.term }
    if (keys.toSet().size != keys.size) throw invalidBackendData()

    val current = when {
        currentYear == null && currentTerm == null -> null
        currentYear != null && currentTerm != null ->
            mappedTerms.firstOrNull { it.year == currentYear && it.term == currentTerm }
                ?: throw invalidBackendData()
        else -> throw invalidBackendData()
    }
    if (currentWeek != null && (current == null || currentWeek !in 1..current.totalWeeks)) {
        throw invalidBackendData()
    }
    weekDates?.let { dates ->
        if (dates.size != 7 || dates.zipWithNext().any { (a, b) -> a.plusDays(1) != b }) {
            throw invalidBackendData()
        }
        if (current != null && currentWeek != null &&
            dates.first() != current.startDay.plusDays((currentWeek - 1L) * 7L)
        ) {
            throw invalidBackendData()
        }
    }
    return TimetableIndex(
        terms = mappedTerms,
        currentYear = currentYear,
        currentTerm = currentTerm,
        currentWeek = currentWeek,
        currentWeekdays = weekDates,
    )
}

internal fun TimetableBackendWeek.toDomainWeek(
    term: TimetableTerm,
    requestedWeek: Int,
): TimetableWeek {
    if (!complete || fetchedAtEpochMillis <= 0L || year != term.year || this.term != term.term ||
        week != requestedWeek || week !in 1..term.totalWeeks || meetings.size > 5_000
    ) {
        throw invalidBackendData()
    }
    val expectedStart = term.startDay.plusDays((week - 1L) * 7L)
    if (weekDates.size != 7 || weekDates.firstOrNull() != expectedStart ||
        weekDates.zipWithNext().any { (a, b) -> a.plusDays(1) != b }
    ) {
        throw invalidBackendData()
    }

    val mappedMeetings = meetings.map { item ->
        if (item.courseName.isBlank() || item.dayOfWeek !in 1..7 ||
            item.date !in weekDates || item.date.dayOfWeek.value != item.dayOfWeek ||
            item.startPeriod !in 1..14 || item.endPeriod !in item.startPeriod..14
        ) {
            throw invalidBackendData()
        }
        val displayPeriods = (item.startPeriod..item.endPeriod).toList()
        val sourcePeriods = when (val rawPeriod = item.rawPeriod) {
            null -> displayPeriods
            else -> parseBackendPeriods(rawPeriod) ?: throw invalidBackendData()
        }
        if (sourcePeriods.isEmpty() || sourcePeriods.any { it !in 1..14 } ||
            !sourcePeriods.containsAll(displayPeriods)
        ) {
            throw invalidBackendData()
        }
        val weekLabel = item.weeksLabel?.takeIf { it.isNotBlank() } ?: week.toString()
        val sourceWeeks = if (item.weeksLabel.isNullOrBlank()) {
            listOf(week)
        } else {
            TimetableProtocol.parseWeekNumbers(weekLabel, term.totalWeeks)
                ?: throw invalidBackendData()
        }
        if (week !in sourceWeeks) throw invalidBackendData()

        try {
            TimetableMeeting(
                courseName = item.courseName,
                weekday = item.dayOfWeek,
                startPeriod = item.startPeriod,
                endPeriod = item.endPeriod,
                periods = displayPeriods,
                sourcePeriods = sourcePeriods,
                weeks = sourceWeeks,
                weekLabel = weekLabel,
                teacher = item.teacher,
                location = item.location,
                courseId = item.courseCode,
                sectionsSource = if (item.rawPeriod == null) {
                    "backend_start_end"
                } else {
                    "backend_rawPeriod"
                },
                classGroup = item.classGroup,
                date = item.date,
            )
        } catch (_: IllegalArgumentException) {
            throw invalidBackendData()
        }
    }
    return TimetableWeek(
        term = term,
        week = week,
        dates = weekDates,
        meetings = mappedMeetings,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
    )
}

private fun parseBackendPeriods(raw: String): List<Int>? {
    val number = Regex("\\d+")
    val matches = number.findAll(raw).toList()
    if (matches.isEmpty()) return null
    val remainder = number.replace(raw, "")
        .replace(Regex("[\\s,，、;；/|~—－\\-\\[\\]()第节]"), "")
    if (remainder.isNotEmpty()) return null
    return matches.map { it.value.toIntOrNull() ?: return null }
}

private fun invalidBackendData() =
    TimetableBackendFailure(TimetableBackendFailureCode.UPSTREAM_PROTOCOL_ERROR)

/** Fixed failures crossing the host adapter into timetable; never contains response text. */
enum class TimetableBackendFailureCode {
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

class TimetableBackendFailure(
    val code: TimetableBackendFailureCode,
    val retryAfterSeconds: Long? = null,
) : Exception(null, null, false, false)
