package com.upcpocket.backend.support

import com.upcpocket.backend.contract.CardAdapter
import com.upcpocket.backend.contract.CardBalanceData
import com.upcpocket.backend.contract.CardTransactionRow
import com.upcpocket.backend.contract.CardTransactionsData
import com.upcpocket.backend.contract.CardTransactionsSummaryData
import com.upcpocket.backend.contract.CampusAdapterException
import com.upcpocket.backend.contract.CampusModuleKey
import com.upcpocket.backend.contract.CredentialLease
import com.upcpocket.backend.contract.GradesAdapter
import com.upcpocket.backend.contract.GradesCourseView
import com.upcpocket.backend.contract.GradesCoursesData
import com.upcpocket.backend.contract.GradesTermView
import com.upcpocket.backend.contract.GradesTermsData
import com.upcpocket.backend.contract.TimetableAdapter
import com.upcpocket.backend.contract.TimetableIndexData
import com.upcpocket.backend.contract.TimetableMeetingView
import com.upcpocket.backend.contract.TimetableTermView
import com.upcpocket.backend.contract.TimetableWeekData
import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.session.BusinessSessionValue
import com.upcpocket.backend.session.BusinessLoginResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FakeTimetableAdapter : TimetableAdapter {
    override val moduleKey = CampusModuleKey.TIMETABLE
    override val adapterReady = true
    val loginCalls = AtomicInteger()
    @Volatile var loginStarted: CountDownLatch? = null
    @Volatile var loginRelease: CountDownLatch? = null
    @Volatile var indexStarted: CountDownLatch? = null
    @Volatile var indexRelease: CountDownLatch? = null
    @Volatile var indexFailureCode: ApiErrorCode? = null

    override fun login(credential: CredentialLease): BusinessLoginResult {
        loginCalls.incrementAndGet()
        loginStarted?.countDown()
        loginRelease?.await(5, TimeUnit.SECONDS)
        return BusinessLoginResult("fake-timetable-session".toByteArray(), null)
    }

    override fun readIndex(session: BusinessSessionValue): TimetableIndexData {
        indexStarted?.countDown()
        indexRelease?.await(5, TimeUnit.SECONDS)
        indexFailureCode?.let { throw CampusAdapterException(it) }
        return TimetableIndexData(
            terms = listOf(TimetableTermView("2026-2027", "1", "2026-2027 第一学期", "2026-09-07", 20)),
            currentYear = "2026-2027",
            currentTerm = "1",
            currentWeek = 1,
            weekDates = listOf(
                "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-12", "2026-09-13",
            ),
        )
    }

    override fun readWeek(session: BusinessSessionValue, year: String, term: String, week: Int) = TimetableWeekData(
        year = year,
        term = term,
        week = week,
        weekDates = listOf(
            "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-12", "2026-09-13",
        ),
        meetings = listOf(
            TimetableMeetingView("__COURSE_MARKER__", "2026-09-07", 1, 1, 2, rawPeriod = "1-2"),
        ),
        complete = true,
        fetchedAtEpochMillis = 1_700_000_000_000,
    )
}

class FakeCardAdapter : CardAdapter {
    override val moduleKey = CampusModuleKey.CARD
    override val adapterReady = true
    val loginCalls = AtomicInteger()
    @Volatile var balanceSnoOverride: String? = null

    override fun login(credential: CredentialLease): BusinessLoginResult {
        loginCalls.incrementAndGet()
        return BusinessLoginResult("fake-card-session".toByteArray(), null)
    }

    override fun readBalance(session: BusinessSessionValue, studentId: CharArray) = CardBalanceData(
        sno = balanceSnoOverride ?: String(studentId),
        balanceCents = 1234,
        transitionCents = 0,
        fetchedAtEpochMillis = 1_700_000_000_000,
    )

    override fun readTransactions(
        session: BusinessSessionValue,
        month: String?,
        page: Int,
        size: Int,
    ) = CardTransactionsData(
        total = 1,
        rows = listOf(CardTransactionRow("raw-payment-number", "__TRANSACTION_MARKER__", "2026-11-28 12:00", -1250)),
    )

    override fun readTransactionsSummary(session: BusinessSessionValue, month: String?) =
        CardTransactionsSummaryData(incomeFen = 10_000, expenseFen = 3_250)
}

class FakeGradesAdapter : GradesAdapter {
    override val moduleKey = CampusModuleKey.GRADES
    override val adapterReady = true
    val loginCalls = AtomicInteger()

    override fun login(credential: CredentialLease): BusinessLoginResult {
        loginCalls.incrementAndGet()
        return BusinessLoginResult("fake-grades-session".toByteArray(), null)
    }

    override fun readTerms(session: BusinessSessionValue) = GradesTermsData(
        terms = listOf(GradesTermView("2026-2027", "1")),
        currentTerm = GradesTermView("2026-2027", "1"),
    )

    override fun readCourses(session: BusinessSessionValue, year: String, term: String) = GradesCoursesData(
        year = year,
        term = term,
        courses = listOf(GradesCourseView("__GRADE_MARKER__", "92", "3.0", "必修", "考试")),
        complete = true,
        fetchedAtEpochMillis = 1_700_000_000_000,
    )
}

@TestConfiguration(proxyBeanMethods = false)
class FakeCampusModuleAdaptersConfiguration {
    @Bean fun fakeTimetableAdapter() = FakeTimetableAdapter()
    @Bean fun fakeCardAdapter() = FakeCardAdapter()
    @Bean fun fakeGradesAdapter() = FakeGradesAdapter()
}
