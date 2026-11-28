package com.upcpocket.app.timetable

/** Local fixture adapter used by service tests; production code never uses TimetableApi. */
internal class TimetableApiTestBackendSource(
    transport: TimetableHttpTransport,
    private val clock: () -> Long,
) : TimetableBackendSource {
    private val api = TimetableApi(transport, clock)
    private var latestIndex: TimetableIndex? = null

    override suspend fun index(): TimetableBackendIndex = try {
        api.getIndex(emptyList()).also { latestIndex = it }.toBackend()
    } catch (failure: TimetableApiException) {
        throw failure.toBackendFailure()
    }

    override suspend fun week(year: String, term: String, week: Int): TimetableBackendWeek = try {
        val currentIndex = latestIndex ?: api.getIndex(emptyList()).also { latestIndex = it }
        val selectedTerm = currentIndex.find(year, term)
            ?: throw TimetableBackendFailure(TimetableBackendFailureCode.VALIDATION_FAILED)
        api.getWeek(emptyList(), selectedTerm, week).toBackend(clock())
    } catch (failure: TimetableApiException) {
        throw failure.toBackendFailure()
    }

    private fun TimetableIndex.toBackend() = TimetableBackendIndex(
        terms = terms.map {
            TimetableBackendTerm(
                year = it.year,
                term = it.term,
                label = it.label,
                startDay = it.startDay,
                totalWeeks = it.totalWeeks,
            )
        },
        currentYear = currentYear,
        currentTerm = currentTerm,
        currentWeek = currentWeek,
        weekDates = currentWeekdays,
    )

    private fun TimetableWeek.toBackend(fetchedAtEpochMillis: Long) = TimetableBackendWeek(
        year = term.year,
        term = term.term,
        week = week,
        weekDates = dates,
        meetings = meetings.map {
            TimetableBackendMeeting(
                courseName = it.courseName,
                date = it.date,
                dayOfWeek = it.weekday,
                startPeriod = it.startPeriod,
                endPeriod = it.endPeriod,
                rawPeriod = it.sourcePeriods.joinToString(","),
                teacher = it.teacher,
                location = it.location,
                weeksLabel = it.weekLabel,
                courseCode = it.courseId,
                classGroup = it.classGroup,
            )
        },
        complete = complete,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
    )

    private fun TimetableApiException.toBackendFailure(): TimetableBackendFailure =
        when (code) {
            TimetableErrorCode.AUTH_REQUIRED,
            TimetableErrorCode.LOGIN_PAGE,
            -> TimetableBackendFailure(TimetableBackendFailureCode.TOKEN_INVALID)
            TimetableErrorCode.INVALID_INPUT ->
                TimetableBackendFailure(TimetableBackendFailureCode.VALIDATION_FAILED)
            TimetableErrorCode.UPSTREAM_ERROR ->
                TimetableBackendFailure(TimetableBackendFailureCode.UPSTREAM_UNAVAILABLE)
            TimetableErrorCode.SCHEMA_CHANGED,
            TimetableErrorCode.RESPONSE_TOO_LARGE,
            -> TimetableBackendFailure(TimetableBackendFailureCode.UPSTREAM_PROTOCOL_ERROR)
        }.let { mapped ->
            if (network) TimetableBackendFailure(TimetableBackendFailureCode.NETWORK_UNAVAILABLE)
            else mapped
        }
}
