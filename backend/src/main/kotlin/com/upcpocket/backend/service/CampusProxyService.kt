package com.upcpocket.backend.service

import com.upcpocket.backend.contract.CampusAdapterException
import com.upcpocket.backend.contract.CampusModuleKey
import com.upcpocket.backend.contract.BusinessSessionKey
import com.upcpocket.backend.contract.CardAdapter
import com.upcpocket.backend.contract.CardBalanceData
import com.upcpocket.backend.contract.CardTransactionRow
import com.upcpocket.backend.contract.CardTransactionsData
import com.upcpocket.backend.contract.CardTransactionsSummaryData
import com.upcpocket.backend.contract.GradesAdapter
import com.upcpocket.backend.contract.GradesCourseView
import com.upcpocket.backend.contract.GradesCoursesData
import com.upcpocket.backend.contract.GradesTermView
import com.upcpocket.backend.contract.GradesTermsData
import com.upcpocket.backend.contract.ModuleSwitchStatus
import com.upcpocket.backend.contract.TimetableAdapter
import com.upcpocket.backend.contract.TimetableIndexData
import com.upcpocket.backend.contract.TimetableMeetingView
import com.upcpocket.backend.contract.TimetableTermView
import com.upcpocket.backend.contract.TimetableWeekData
import com.upcpocket.backend.proxy.AccountBusinessRateLimiter
import com.upcpocket.backend.proxy.BoundedSingleFlight
import com.upcpocket.backend.proxy.CampusModuleAdapterRegistry
import com.upcpocket.backend.proxy.CampusProxyProperties
import com.upcpocket.backend.proxy.ModuleCallExecutor
import com.upcpocket.backend.proxy.ModuleCircuitBreakers
import com.upcpocket.backend.proxy.SingleFlightKey
import com.upcpocket.backend.security.PocketPrincipal
import com.upcpocket.backend.session.BusinessSessionValue
import com.upcpocket.backend.session.InMemorySessionRegistry
import com.upcpocket.backend.session.SessionBusinessFence
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CardBalanceResponse(
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAtEpochMillis: Long,
)

data class CardTransactionsResponse(
    val page: Int,
    val size: Int,
    val total: Long,
    val pages: Long,
    val rows: List<CardTransactionRow>,
)

@Service
class CampusProxyService(
    private val studentAccounts: StudentAccountService,
    private val sessions: InMemorySessionRegistry,
    private val moduleSwitches: ModuleSwitchService,
    private val adapters: CampusModuleAdapterRegistry,
    private val properties: CampusProxyProperties,
    private val rateLimiter: AccountBusinessRateLimiter,
    private val circuits: ModuleCircuitBreakers,
    private val singleFlight: BoundedSingleFlight,
    private val executor: ModuleCallExecutor,
    private val clock: Clock,
) {
    fun timetableIndex(principal: PocketPrincipal, requestId: UUID): TimetableIndexData =
        read(CampusModuleKey.TIMETABLE, principal, requestId) { adapter, session ->
            val data = requireAdapter<TimetableAdapter>(adapter).readIndex(session)
            validateTimetableIndex(data)
            data
        }

    fun timetableWeek(
        principal: PocketPrincipal,
        requestId: UUID,
        year: String,
        term: String,
        week: Int,
    ): TimetableWeekData {
        validateTermInput(year, term)
        if (week !in 1..30) throw ApiException(ApiErrorCode.VALIDATION_FAILED)
        return read(CampusModuleKey.TIMETABLE, principal, requestId) { adapter, session ->
            val timetable = requireAdapter<TimetableAdapter>(adapter)
            val index = timetable.readIndex(session)
            validateTimetableIndex(index)
            val selected = index.terms.firstOrNull { it.year == year && it.term == term }
                ?: throw ApiException(ApiErrorCode.VALIDATION_FAILED)
            if (week > selected.totalWeeks) throw ApiException(ApiErrorCode.VALIDATION_FAILED)
            val data = timetable.readWeek(session, year, term, week)
            validateTimetableWeek(data, year, term, week, selected)
            data.copy(fetchedAtEpochMillis = clock.millis())
        }
    }

    fun cardBalance(principal: PocketPrincipal, requestId: UUID): CardBalanceResponse {
        val studentId = sessions.copyStudentId(principal.identity)
            ?: throw ApiException(ApiErrorCode.IDENTITY_UNSUPPORTED)
        try {
            if (!isNumericStudentId(studentId)) throw ApiException(ApiErrorCode.IDENTITY_UNSUPPORTED)
            return read(CampusModuleKey.CARD, principal, requestId) { adapter, session ->
                val card = requireAdapter<CardAdapter>(adapter)
                val adapterTarget = studentId.copyOf()
                val data = try {
                    card.readBalance(session, adapterTarget)
                } finally {
                    adapterTarget.fill('\u0000')
                }
                if (!matches(data.sno, studentId)) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
                validateBalance(data)
                CardBalanceResponse(data.balanceCents, data.transitionCents, clock.millis())
            }
        } finally {
            studentId.fill('\u0000')
        }
    }

    fun cardTransactions(
        principal: PocketPrincipal,
        requestId: UUID,
        month: String?,
        page: Int,
        size: Int,
    ): CardTransactionsResponse {
        validateMonth(month)
        if (page < 1 || size !in 1..100) throw ApiException(ApiErrorCode.VALIDATION_FAILED)
        return read(CampusModuleKey.CARD, principal, requestId) { adapter, session ->
            val data = requireAdapter<CardAdapter>(adapter).readTransactions(session, month, page, size)
            validateTransactions(data)
            val pages = data.total / size + if (data.total % size == 0L) 0 else 1
            CardTransactionsResponse(page, size, data.total, pages, data.rows.map { row ->
                row.copy(rowKey = opaqueRowKey(principal.accountId, row.rowKey))
            })
        }
    }

    fun cardTransactionsSummary(
        principal: PocketPrincipal,
        requestId: UUID,
        month: String?,
    ): CardTransactionsSummaryData {
        validateMonth(month)
        return read(CampusModuleKey.CARD, principal, requestId) { adapter, session ->
            requireAdapter<CardAdapter>(adapter).readTransactionsSummary(session, month)
        }
    }

    fun gradesTerms(principal: PocketPrincipal, requestId: UUID): GradesTermsData =
        read(CampusModuleKey.GRADES, principal, requestId) { adapter, session ->
            val data = requireAdapter<GradesAdapter>(adapter).readTerms(session)
            validateGradesTerms(data)
            data
        }

    fun gradesCourses(
        principal: PocketPrincipal,
        requestId: UUID,
        year: String,
        term: String,
    ): GradesCoursesData {
        validateTermInput(year, term)
        return read(CampusModuleKey.GRADES, principal, requestId) { adapter, session ->
            val data = requireAdapter<GradesAdapter>(adapter).readCourses(session, year, term)
            validateGradesCourses(data, year, term)
            data.copy(fetchedAtEpochMillis = clock.millis())
        }
    }

    private fun <T> read(
        module: CampusModuleKey,
        principal: PocketPrincipal,
        requestId: UUID,
        operation: (com.upcpocket.backend.contract.CampusModuleAdapter, BusinessSessionValue) -> T,
    ): T {
        val initialStatus = requireEnabled(module)
        val adapter = adapters[module]
        if (!adapter.adapterReady) throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
        rateLimiter.check(principal.accountId)
        val accountFence = studentAccounts.captureBusinessFence(principal.identity)
        var requestFence = ProxyRequestFence(accountFence, initialStatus.revision)
        val settings = properties.module(module)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.timeoutMs)
        val serviceKey = "${module.serviceKey}@r${initialStatus.revision}"
        val sessionKey = BusinessSessionKey(principal.accountId, accountFence.credentialGeneration, serviceKey)

        var session = sessions.getBusinessSession(sessionKey)
        while (session == null) {
            val attemptFence = requestFence
            try {
                singleFlight.execute(
                    SingleFlightKey(principal.accountId, accountFence.credentialGeneration, serviceKey),
                    deadline,
                ) {
                    requireCurrent(module, attemptFence)
                    val permit = acquireCircuitPermit(module, attemptFence)
                    try {
                        executor.call(module, remainingMillis(deadline)) {
                            studentAccounts.executeAutoBusinessLogin(
                                accountFence.accountId,
                                accountFence.deviceId,
                                serviceKey,
                                requestId,
                                beforeCredentialUse = { requireCurrent(module, attemptFence) },
                            ) { credential ->
                                requireCurrent(module, attemptFence)
                                adapter.login(credential)
                            }.use { Unit }
                        }
                        circuits.recordSuccess(module, permit)
                    } catch (error: ApiException) {
                        circuits.recordFailure(module, permit, error.code.isCircuitFailure())
                        throw errorAfterFenceCheck(module, attemptFence, error)
                    } catch (error: CampusAdapterException) {
                        circuits.recordFailure(module, permit, error.errorCode.isCircuitFailure())
                        throw errorAfterFenceCheck(module, attemptFence, ApiException(error.errorCode))
                    } catch (_: Exception) {
                        circuits.recordFailure(module, permit, counted = true)
                        throw errorAfterFenceCheck(
                            module,
                            attemptFence,
                            ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE),
                        )
                    }
                }
            } catch (error: ApiException) {
                throw errorAfterFenceCheck(module, attemptFence, error)
            }
            requestFence = refreshFenceAfterLoginFlight(module, principal, initialStatus.revision)
            session = sessions.getBusinessSession(sessionKey)
        }

        val activeSession = session ?: throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
        return activeSession.use {
            requireCurrent(module, requestFence)
            val permit = acquireCircuitPermit(module, requestFence)
            try {
                val result = executor.call(module, remainingMillis(deadline)) {
                    requireCurrent(module, requestFence)
                    it.use { businessSession -> operation(adapter, businessSession) }
                }
                circuits.recordSuccess(module, permit)
                requireCurrent(module, requestFence)
                result
            } catch (error: ApiException) {
                circuits.recordFailure(module, permit, error.code.isCircuitFailure())
                throw errorAfterFenceCheck(module, requestFence, error)
            } catch (error: CampusAdapterException) {
                circuits.recordFailure(module, permit, error.errorCode.isCircuitFailure())
                throw errorAfterFenceCheck(module, requestFence, ApiException(error.errorCode))
            } catch (_: Exception) {
                circuits.recordFailure(module, permit, counted = true)
                throw errorAfterFenceCheck(
                    module,
                    requestFence,
                    ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE),
                )
            }
        }
    }

    private fun errorAfterFenceCheck(
        module: CampusModuleKey,
        fence: ProxyRequestFence,
        fallback: ApiException,
    ): ApiException = try {
        requireCurrent(module, fence)
        fallback
    } catch (currentError: ApiException) {
        if (fallback.completionSignal != null && currentError.completionSignal == null) {
            ApiException(currentError.code, currentError.retryAfterSeconds, fallback.completionSignal)
        } else {
            currentError
        }
    }

    private fun acquireCircuitPermit(module: CampusModuleKey, fence: ProxyRequestFence) =
        circuits.acquire(module)
            ?: throw errorAfterFenceCheck(module, fence, ApiException(ApiErrorCode.CIRCUIT_OPEN))

    private fun refreshFenceAfterLoginFlight(
        module: CampusModuleKey,
        principal: PocketPrincipal,
        expectedRevision: Long,
    ): ProxyRequestFence {
        val sessionFence = studentAccounts.captureBusinessFence(principal.identity)
        val current = moduleSwitches.status(module.wireName)
        if (!current.enabled || current.revision != expectedRevision) {
            throw ApiException(ApiErrorCode.MODULE_DISABLED)
        }
        if (!current.adapterReady) throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
        return ProxyRequestFence(sessionFence, expectedRevision)
    }

    private fun requireEnabled(module: CampusModuleKey): ModuleSwitchStatus {
        val status = moduleSwitches.status(module.wireName)
        if (!status.enabled) throw ApiException(ApiErrorCode.MODULE_DISABLED)
        if (!status.adapterReady) throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
        return status
    }

    private fun requireCurrent(module: CampusModuleKey, fence: ProxyRequestFence) {
        studentAccounts.assertBusinessFenceCurrent(fence.session)
        val current = moduleSwitches.status(module.wireName)
        if (!current.enabled || current.revision != fence.moduleRevision) {
            throw ApiException(ApiErrorCode.MODULE_DISABLED)
        }
        if (!current.adapterReady) throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) throw ApiException(ApiErrorCode.UPSTREAM_TIMEOUT)
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
    }

    private inline fun <reified T> requireAdapter(adapter: Any): T =
        adapter as? T ?: throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)

    private fun validateTimetableIndex(index: TimetableIndexData) {
        if (index.terms.size > MAX_TERMS || index.terms.any { !validTerm(it) } ||
            (index.currentWeek != null && index.currentWeek !in 1..30) ||
            (index.weekDates != null && !validSevenDates(index.weekDates)) ||
            !optionalText(index.compatibilityNote, MAX_NOTE_CHARS)
        ) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
        if (index.currentYear != null || index.currentTerm != null) {
            if (index.currentYear == null || index.currentTerm == null ||
                index.terms.none { it.year == index.currentYear && it.term == index.currentTerm }
            ) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
        }
    }

    private fun validateTimetableWeek(
        data: TimetableWeekData,
        year: String,
        term: String,
        week: Int,
        selectedTerm: TimetableTermView,
    ) {
        if (data.year != year || data.term != term || data.week != week ||
            data.week !in 1..selectedTerm.totalWeeks || !validSevenDates(data.weekDates) ||
            data.meetings.size > MAX_MEETINGS || data.fetchedAtEpochMillis < 0 ||
            parseDate(selectedTerm.startDay)?.plusWeeks((week - 1).toLong())?.toString() != data.weekDates.firstOrNull() ||
            data.meetings.any { !validMeeting(it, data.weekDates) }
        ) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
    }

    private fun validTerm(term: TimetableTermView): Boolean =
        validToken(term.year) && validToken(term.term) && safeText(term.label, 128) &&
            parseDate(term.startDay) != null && term.totalWeeks in 1..30

    private fun validMeeting(meeting: TimetableMeetingView, dates: List<String>): Boolean =
        safeText(meeting.courseName, 256) && meeting.dayOfWeek in 1..7 &&
            meeting.startPeriod in 1..14 && meeting.endPeriod in meeting.startPeriod..14 &&
            parseDate(meeting.date)?.let { meeting.date in dates && it.dayOfWeek.value == meeting.dayOfWeek } == true &&
            optionalText(meeting.rawPeriod, 128) && optionalText(meeting.teacher, 128) &&
            optionalText(meeting.location, 128) && optionalText(meeting.weeksLabel, 128) &&
            optionalText(meeting.courseCode, 128) && optionalText(meeting.classGroup, 128)

    private fun validSevenDates(dates: List<String>): Boolean {
        if (dates.size != 7) return false
        val parsed = dates.map { parseDate(it) ?: return false }
        return parsed.zipWithNext().all { (first, second) -> first.plusDays(1) == second }
    }

    private fun validateBalance(data: CardBalanceData) {
        if (data.fetchedAtEpochMillis < 0) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
    }

    private fun validateTransactions(data: CardTransactionsData) {
        if (data.total < 0 || data.rows.size > MAX_TRANSACTIONS || data.rows.any { row ->
                !safeText(row.rowKey, 256) || !safeText(row.title, 256) || !safeText(row.time, 64)
            }
        ) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
    }

    private fun validateGradesTerms(data: GradesTermsData) {
        if (data.terms.size > MAX_TERMS || data.terms.any { !validGradesTerm(it) } ||
            (data.currentTerm != null && data.currentTerm !in data.terms)
        ) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
    }

    private fun validateGradesCourses(data: GradesCoursesData, year: String, term: String) {
        if (data.year != year || data.term != term || !validToken(data.year) || !validToken(data.term) ||
            data.courses.size > MAX_COURSES || data.fetchedAtEpochMillis < 0 || data.courses.any { course ->
                !safeText(course.courseName, 256) || optionalText(course.score, 64).not() ||
                    optionalText(course.credits, 64).not() || optionalText(course.courseType, 128).not() ||
                    optionalText(course.examType, 128).not()
            }
        ) throw ApiException(ApiErrorCode.UPSTREAM_PROTOCOL_ERROR)
    }

    private fun validGradesTerm(term: GradesTermView) = validToken(term.year) && validToken(term.term)

    private fun validateMonth(month: String?) {
        if (month == null) return
        if (!month.matches(MONTH_PATTERN) || runCatching { YearMonth.parse(month) }.isFailure) {
            throw ApiException(ApiErrorCode.VALIDATION_FAILED)
        }
    }

    private fun validateTermInput(year: String, term: String) {
        if (!validToken(year) || !validToken(term)) throw ApiException(ApiErrorCode.VALIDATION_FAILED)
    }

    private fun validToken(value: String) =
        value.isNotBlank() && value.length <= 64 && value.none { it.isISOControl() } && !HTML_MARKUP.containsMatchIn(value)

    private fun safeText(value: String, maxChars: Int) =
        value.isNotBlank() && value.length <= maxChars && value.none { it.isISOControl() } && !HTML_MARKUP.containsMatchIn(value)

    private fun optionalText(value: String?, maxChars: Int) = value == null || safeText(value, maxChars)

    private fun parseDate(value: String): LocalDate? =
        if (!value.matches(DATE_PATTERN)) null else runCatching { LocalDate.parse(value) }.getOrNull()

    private fun isNumericStudentId(value: CharArray) = value.size in 8..16 && value.all { it in '0'..'9' }

    private fun matches(value: String, expected: CharArray): Boolean =
        value.length == expected.size && value.indices.all { value[it] == expected[it] }

    private fun opaqueRowKey(accountId: UUID, sourceKey: String): String {
        val bytes = "$accountId\u0000$sourceKey".toByteArray(Charsets.UTF_8)
        var digest: ByteArray? = null
        return try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(18))
        } finally {
            bytes.fill(0)
            digest?.fill(0)
        }
    }

    private fun ApiErrorCode.isCircuitFailure(): Boolean =
        this == ApiErrorCode.UPSTREAM_TIMEOUT || this == ApiErrorCode.UPSTREAM_UNAVAILABLE

    private data class ProxyRequestFence(val session: SessionBusinessFence, val moduleRevision: Long)

    companion object {
        private const val MAX_TERMS = 100
        private const val MAX_MEETINGS = 2_000
        private const val MAX_TRANSACTIONS = 1_000
        private const val MAX_COURSES = 2_000
        private const val MAX_NOTE_CHARS = 512
        private val MONTH_PATTERN = Regex("[0-9]{4}-(0[1-9]|1[0-2])")
        private val DATE_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val HTML_MARKUP = Regex("(?s)<(?:[A-Za-z!/][^>]*|/?[A-Za-z][^>]*)>")
    }
}
