package com.upcpocket.backend.contract

import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.session.BusinessLoginResult
import com.upcpocket.backend.session.BusinessSessionValue

enum class CampusModuleKey(val wireName: String, val serviceKey: String) {
    TIMETABLE("timetable", "timetable"),
    CARD("card", "card"),
    GRADES("grades", "grades");

    companion object {
        fun parse(value: String): CampusModuleKey? = entries.firstOrNull { it.wireName == value }
    }
}

enum class ModuleCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN,
}

data class ModuleSwitchRecord(
    val moduleKey: CampusModuleKey,
    val enabled: Boolean,
    val revision: Long,
)

data class ModuleSwitchStatus(
    val moduleKey: String,
    val enabled: Boolean,
    val revision: Long,
    val adapterReady: Boolean,
    val circuitState: ModuleCircuitState,
)

data class TimetableTermView(
    val year: String,
    val term: String,
    val label: String,
    val startDay: String,
    val totalWeeks: Int,
)

data class TimetableIndexData(
    val terms: List<TimetableTermView>,
    val currentYear: String? = null,
    val currentTerm: String? = null,
    val currentWeek: Int? = null,
    val weekDates: List<String>? = null,
    val compatibilityNote: String? = null,
)

data class TimetableMeetingView(
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
)

data class TimetableWeekData(
    val year: String,
    val term: String,
    val week: Int,
    val weekDates: List<String>,
    val meetings: List<TimetableMeetingView>,
    val complete: Boolean,
    val fetchedAtEpochMillis: Long,
)

data class CardBalanceData(
    /** Upstream echo; the proxy verifies this against the token-bound student ID and omits it. */
    val sno: String,
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAtEpochMillis: Long,
)

data class CardTransactionRow(
    val rowKey: String,
    val title: String,
    val time: String,
    val amountFen: Long,
)

data class CardTransactionsData(
    val total: Long,
    val rows: List<CardTransactionRow>,
)

data class CardTransactionsSummaryData(
    val incomeFen: Long,
    val expenseFen: Long,
)

data class GradesTermView(val year: String, val term: String)

data class GradesTermsData(
    val terms: List<GradesTermView>,
    val currentTerm: GradesTermView? = null,
)

data class GradesCourseView(
    val courseName: String,
    val score: String? = null,
    val credits: String? = null,
    val courseType: String? = null,
    val examType: String? = null,
)

data class GradesCoursesData(
    val year: String,
    val term: String,
    val courses: List<GradesCourseView>,
    val complete: Boolean,
    val fetchedAtEpochMillis: Long,
)

/** Fixed adapter failures. Exception messages are never forwarded to API responses or logs. */
class CampusAdapterException(val errorCode: ApiErrorCode) : RuntimeException()

interface CampusModuleAdapter {
    val moduleKey: CampusModuleKey
    val adapterReady: Boolean
    fun login(credential: CredentialLease): BusinessLoginResult
}

interface TimetableAdapter : CampusModuleAdapter {
    fun readIndex(session: BusinessSessionValue): TimetableIndexData
    fun readWeek(session: BusinessSessionValue, year: String, term: String, week: Int): TimetableWeekData
}

interface CardAdapter : CampusModuleAdapter {
    fun readBalance(session: BusinessSessionValue, studentId: CharArray): CardBalanceData
    fun readTransactions(
        session: BusinessSessionValue,
        month: String?,
        page: Int,
        size: Int,
    ): CardTransactionsData
    fun readTransactionsSummary(session: BusinessSessionValue, month: String?): CardTransactionsSummaryData
}

interface GradesAdapter : CampusModuleAdapter {
    fun readTerms(session: BusinessSessionValue): GradesTermsData
    fun readCourses(session: BusinessSessionValue, year: String, term: String): GradesCoursesData
}
