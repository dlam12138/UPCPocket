package com.upcpocket.app.timetable

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBodyType
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.EpochBoundDiagnosticSink
import com.upcpocket.app.platform.diagnostics.DiagnosticMediaType
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticSink
import com.upcpocket.app.platform.diagnostics.Diagnostics
import com.upcpocket.app.upcauth.TimetableAuthenticator
import com.upcpocket.app.upcauth.UpcAuthCookie
import com.upcpocket.app.upcauth.UpcAuthFailure
import com.upcpocket.app.upcauth.UpcAuthResult
import com.upcpocket.app.upcauth.UpcTimetableAuthEndpoints
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimetableServiceTest {

    private val directory = java.nio.file.Files.createTempDirectory("timetable-service").toFile()
    private val crypto = TestCrypto()
    private val account = FakeAccount()
    private val auth = FakeAuthenticator()
    private var today = LocalDate.parse("2026-10-28")
    private var now = 1_700_000_000_000L

    private val sessionStore = TimetableSessionStore(directory, crypto)
    private val cacheStore = TimetableCacheStore(directory)

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun openOnlyQueriesCurrentWeekAndReusesSession() = runTest {
        val transport = ProgrammedTransport(indexAndWeekHandler())
        val service = service(transport)
        service.open()

        assertEquals(TimetableConnection.CONNECTED, service.state.value.connection)
        assertEquals(8, service.state.value.selectedWeek)
        assertEquals(TimetableWeekState.READY, service.state.value.weekState)
        assertEquals(1, auth.calls)
        assertEquals(1, transport.count("GET"))
        assertEquals(1, transport.count("POST"))

        service.selectWeek(9)
        assertEquals(9, service.state.value.selectedWeek)
        assertEquals(TimetableWeekState.EMPTY, service.state.value.weekState)
        assertEquals(1, auth.calls)
        assertEquals(2, transport.count("POST"))
        assertTrue(transport.postBodies().last().contains("week=9"))
    }

    @Test
    fun widgetSnapshotIsCacheOnlyAndHonestAboutMissingWeek() = runTest {
        seedCacheAndSession()
        val service = service(ProgrammedTransport { error("widget snapshot must not query") })
        val snapshot = service.readWidgetSnapshot()
        assertEquals(TimetableWidgetStatus.OK, snapshot.status)
        assertEquals(today, snapshot.date)
        assertEquals(
            service.state.value.courseColorSlots.isEmpty(),
            snapshot.courses.all { it.colorSlot == null },
        )

        cacheStore.clear()
        val missing = service.readWidgetSnapshot()
        assertEquals(TimetableWidgetStatus.NEEDS_SYNC, missing.status)
    }

    @Test
    fun refreshTodayRestoresPersistedCacheWithoutOpeningOrQueryingNetwork() = runTest {
        seedTodayCache()
        val transport = ProgrammedTransport { error("today refresh must stay cache-only") }
        val service = service(transport)

        service.restoreTodayFromCache()

        assertEquals(TimetableTodayState.READY, service.todayState.value.todayState)
        assertEquals(today, service.todayState.value.today)
        assertEquals(
            listOf("今日示例课程"),
            service.todayState.value.todayMeetings.map { it.courseName },
        )
        assertEquals(now, service.todayState.value.todayFetchedAtEpochMillis)
        assertNull(service.state.value.selectedWeek)
        assertEquals(0, transport.count("GET"))
        assertEquals(0, transport.count("POST"))

        val weekState = service.state.value
        service.restoreTodayFromCache()
        assertSame(weekState, service.state.value)
    }

    @Test
    fun repeatedTodayRestoreKeepsReadyCoursesVisibleUntilNewCacheIsPublished() = runTest {
        seedTodayCache(courseName = "已显示的今日课程")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cacheReads = 0
        val transport = ProgrammedTransport { error("today restore must stay cache-only") }
        val service = service(
            transport = transport,
            commitBarrier = { operation ->
                if (operation == "today-cache" && ++cacheReads == 2) {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        service.restoreTodayFromCache()
        val visible = service.todayState.value
        assertEquals(TimetableTodayState.READY, visible.todayState)
        assertEquals(listOf("已显示的今日课程"), visible.todayMeetings.map { it.courseName })

        now += 60_000
        seedTodayCache(courseName = "更新后的今日课程")
        val restoring = launch { service.restoreTodayFromCache() }
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals(visible, service.todayState.value)
        } finally {
            release.complete(Unit)
        }
        withTimeout(5_000) { restoring.join() }

        assertEquals(TimetableTodayState.READY, service.todayState.value.todayState)
        assertEquals(listOf("更新后的今日课程"), service.todayState.value.todayMeetings.map { it.courseName })
        assertEquals(now, service.todayState.value.todayFetchedAtEpochMillis)
        assertEquals(0, auth.calls)
        assertEquals(0, transport.count("GET"))
        assertEquals(0, transport.count("POST"))
    }

    @Test
    fun restoreTodayFindsActualDateAcrossWeekBoundaryWhenIndexWeekIsStale() = runTest {
        today = LocalDate.parse("2026-11-01")
        val term = index().terms.first { it.term == "1" }
        val staleIndex = index().copy(currentWeek = 8)
        assertTrue(
            cacheStore.save(
                ACCOUNT_ID,
                staleIndex,
                cachedWeekForToday(term, weekNumber = 8, courseName = "上周课程"),
                now,
            ),
        )
        assertTrue(
            cacheStore.save(
                ACCOUNT_ID,
                staleIndex,
                cachedWeekForToday(term, weekNumber = 9, courseName = "跨周今日课程"),
                now + 1,
            ),
        )
        val service = service(ProgrammedTransport { error("cache restore must not query") })

        service.restoreTodayFromCache()
        val widget = service.readWidgetSnapshot()

        assertEquals(TimetableTodayState.READY, service.todayState.value.todayState)
        assertEquals(
            listOf("跨周今日课程"),
            service.todayState.value.todayMeetings.map { it.courseName },
        )
        assertEquals(TimetableWidgetStatus.OK, widget.status)
        assertEquals(listOf("跨周今日课程"), widget.courses.map { it.name })
    }

    @Test
    fun restoreTodayFindsCachedWeekFromNewTermWhenIndexStillPointsToOldTerm() = runTest {
        today = LocalDate.parse("2026-11-01")
        val current = index().terms.first { it.term == "1" }
        val prior = current.copy(
            year = "2025-2026",
            term = "2",
            startDay = LocalDate.parse("2025-02-23"),
            totalWeeks = 18,
            label = "旧学期",
        )
        val staleIndex = TimetableIndex(
            terms = listOf(prior),
            currentYear = prior.year,
            currentTerm = prior.term,
            currentWeek = prior.totalWeeks,
        )
        val actualWeek = cachedWeekForToday(
            current,
            weekNumber = 9,
            courseName = "新学期今日课程",
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, staleIndex, actualWeek, now))
        val service = service(ProgrammedTransport { error("cache restore must not query") })

        service.restoreTodayFromCache()

        assertEquals(TimetableTodayState.READY, service.todayState.value.todayState)
        assertEquals(
            listOf("新学期今日课程"),
            service.todayState.value.todayMeetings.map { it.courseName },
        )
    }

    @Test
    fun conflictingCachedTermsDoNotChooseAnArbitraryTodayWeek() = runTest {
        today = LocalDate.parse("2026-11-01")
        val template = index().terms.first { it.term == "1" }
        val weekStart = today.minusDays((today.dayOfWeek.value % 7).toLong())
        val termA = template.copy(
            year = "2025-2026",
            term = "2",
            startDay = weekStart,
            totalWeeks = 8,
            label = "冲突学期 A",
        )
        val termB = template.copy(
            year = "2026-2027",
            term = "1",
            startDay = weekStart,
            totalWeeks = 8,
            label = "冲突学期 B",
        )
        val noCurrentTerm = TimetableIndex(terms = listOf(termA, termB))
        assertTrue(
            cacheStore.save(
                ACCOUNT_ID,
                noCurrentTerm,
                cachedWeekForToday(termA, weekNumber = 1, courseName = "冲突课程 A"),
                now,
            ),
        )
        assertTrue(
            cacheStore.save(
                ACCOUNT_ID,
                noCurrentTerm,
                cachedWeekForToday(termB, weekNumber = 1, courseName = "冲突课程 B"),
                now + 1,
            ),
        )
        val service = service(ProgrammedTransport { error("cache restore must not query") })

        service.restoreTodayFromCache()

        assertEquals(TimetableTodayState.READ_ERROR, service.todayState.value.todayState)
        assertEquals(
            "本机课表在今天存在冲突，请前往课表页刷新。",
            service.todayState.value.todayMessage,
        )
        assertTrue(service.todayState.value.todayMeetings.isEmpty())
        assertEquals(TimetableWidgetStatus.NEEDS_SYNC, service.readWidgetSnapshot().status)
    }

    @Test
    fun refreshTodayDistinguishesCorruptCacheFromUnsyncedCache() = runTest {
        val service = service(ProgrammedTransport { error("today refresh must stay cache-only") })
        service.restoreTodayFromCache()
        assertEquals(TimetableTodayState.NOT_SYNCED, service.todayState.value.todayState)

        File(directory, TimetableStorage.CACHE_FILE).writeText("{broken")
        service.restoreTodayFromCache()
        assertEquals(TimetableTodayState.READ_ERROR, service.todayState.value.todayState)
        assertEquals("本机课表读取失败，请前往课表页重试。", service.todayState.value.todayMessage)

        val index = index()
        assertTrue(
            cacheStore.save(
                OTHER_REFERENCE.accountId.value,
                index,
                cachedWeek(index.terms.first(), "其他账号课程"),
                now,
            ),
        )
        service.restoreTodayFromCache()
        assertEquals(TimetableTodayState.READ_ERROR, service.todayState.value.todayState)
        assertEquals("本机课表缓存与当前账号不匹配。", service.todayState.value.todayMessage)
    }

    @Test
    fun refreshTodayWaitsForAccountRestoreAndThenReadsCache() = runTest {
        seedTodayCache()
        account.setLoading()
        val service = service(ProgrammedTransport { error("today refresh must stay cache-only") })
        val reading = async { service.refreshToday() }

        yield()
        assertEquals(TimetableTodayState.ACCOUNT_RESTORING, service.todayState.value.todayState)
        account.publish(REFERENCE)
        withTimeout(5_000) { reading.await() }

        assertEquals(TimetableTodayState.READY, service.todayState.value.todayState)
    }

    @Test
    fun refreshTodayStopsAfterBoundedAccountRestoreWait() = runTest {
        account.setLoading()
        val transport = ProgrammedTransport { error("account restore timeout must not query") }
        val service = service(transport)
        val reading = launch { service.refreshToday() }

        runCurrent()
        testScheduler.advanceTimeBy(3_001)
        runCurrent()
        reading.join()

        assertEquals(TimetableTodayState.ACCOUNT_RESTORING, service.todayState.value.todayState)
        assertEquals(0, transport.count("GET"))
        assertEquals(0, transport.count("POST"))
    }

    @Test
    fun refreshTodayKeepsIndependentDateWhenViewingAnotherWeek() = runTest {
        seedTodayCache()
        val transport = ProgrammedTransport {
            throw TimetableTransportException(TimetableTransportFailure.NETWORK)
        }
        val service = service(transport)
        service.open()
        service.selectWeek(9)
        assertEquals(9, service.state.value.selectedWeek)
        val requestCount = transport.count("GET") + transport.count("POST")

        service.refreshToday()

        assertEquals(9, service.state.value.selectedWeek)
        assertEquals(today, service.todayState.value.today)
        assertEquals(
            listOf("今日示例课程"),
            service.todayState.value.todayMeetings.map { it.courseName },
        )
        assertEquals(requestCount, transport.count("GET") + transport.count("POST"))
    }

    @Test
    fun refreshTodayRejectsLateCachePublicationAfterAccountInvalidation() = runTest {
        seedTodayCache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport { error("today refresh must stay cache-only") },
            commitBarrier = { operation ->
                if (operation == "today-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        val reading = launch { service.refreshToday() }
        withTimeout(5_000) { entered.await() }

        assertTrue(service.invalidateForAccount(AccountDataScope.PERSONAL_DATA))
        release.complete(Unit)
        reading.join()

        assertTrue(service.todayState.value.todayMeetings.isEmpty())
        assertEquals(TimetableTodayState.ACCOUNT_BLOCKED, service.todayState.value.todayState)
    }

    @Test
    fun lateDiskRestoreCannotReplaceMoreRecentOnlineTodayData() = runTest {
        today = LocalDate.parse("2026-10-26")
        seedTodayCache(courseName = "旧缓存课程")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport(indexAndWeekHandler()),
            commitBarrier = { operation ->
                if (operation == "today-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        val reading = launch { service.restoreTodayFromCache() }
        withTimeout(5_000) { entered.await() }

        service.open()
        assertEquals(listOf("示例课程A"), service.todayState.value.todayMeetings.map { it.courseName })
        release.complete(Unit)
        reading.join()

        assertEquals(listOf("示例课程A"), service.todayState.value.todayMeetings.map { it.courseName })
    }

    @Test
    fun lateMissingCacheReadCannotReplaceOnlineTodayData() = runTest {
        today = LocalDate.parse("2026-10-26")
        assertTrue(cacheStore.clear())
        assertLateTodayReadCannotReplaceOnlineResult(this)
    }

    @Test
    fun lateCorruptCacheReadCannotReplaceOnlineTodayData() = runTest {
        today = LocalDate.parse("2026-10-26")
        File(directory, TimetableStorage.CACHE_FILE).writeText("{broken")
        assertLateTodayReadCannotReplaceOnlineResult(this)
    }

    @Test
    fun restoreTodayDoesNotCarryYesterdayCoursesAcrossShanghaiMidnight() = runTest {
        seedTodayCache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport { error("cache restore must not query") },
            commitBarrier = { operation ->
                if (operation == "today-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        val reading = launch { service.restoreTodayFromCache() }
        withTimeout(5_000) { entered.await() }
        val yesterday = today
        today = yesterday.plusDays(1)
        release.complete(Unit)
        reading.join()

        assertEquals(yesterday, service.todayState.value.today)
        assertEquals(TimetableTodayState.READING_CACHE, service.todayState.value.todayState)

        service.restoreTodayFromCache()
        assertEquals(today, service.todayState.value.today)
        assertEquals(TimetableTodayState.EMPTY, service.todayState.value.todayState)
        assertTrue(service.todayState.value.todayMeetings.isEmpty())
    }

    @Test
    fun viewingAnotherWeekKeepsActualToday() = runTest {
        val transport = ProgrammedTransport(indexAndWeekHandler())
        val service = service(transport)
        service.open()
        val today = service.todayState.value.today
        val todayMeetings = service.todayState.value.todayMeetings
        assertTrue(service.todayState.value.todayLoaded)

        service.selectWeek(9)
        assertEquals(9, service.state.value.selectedWeek)
        assertEquals(today, service.todayState.value.today)
        assertEquals(todayMeetings, service.todayState.value.todayMeetings)
        assertTrue(service.todayState.value.todayLoaded)
    }

    @Test
    fun selectingTermQueriesExactlyOneDefaultWeekAndKeepsTerm() = runTest {
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexFixture())
            } else if (request.body.orEmpty().contains("term=2")) {
                json(emptyWeek(index().terms.first { it.term == "2" }, 1))
            } else {
                json(weekFixture())
            }
        }
        val service = service(transport)
        service.open()
        val spring = service.state.value.terms.first { it.term == "2" }
        service.selectTerm(spring)
        assertEquals("2", service.state.value.selectedTerm)
        assertEquals(1, service.state.value.selectedWeek)
        assertEquals(1, auth.calls)
        assertEquals(2, transport.count("POST"))
        assertTrue(transport.postBodies().last().contains("term=2"))
        assertTrue(transport.postBodies().last().contains("week=1"))
    }

    @Test
    fun staleWeekSelectionCannotOverwriteNewWeek() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexFixture())
            } else {
                val week = Regex("week=(\\d+)").find(request.body.orEmpty())
                    ?.groupValues?.get(1)?.toInt() ?: 8
                if (week == 9) {
                    started.complete(Unit)
                    gate.await()
                }
                json(emptyWeek(index().terms.first(), week))
            }
        }
        val service = service(transport)
        service.open()
        val old = launch { service.selectWeek(9) }
        withTimeout(5_000) { started.await() }
        val latest = launch { service.selectWeek(10) }
        gate.complete(Unit)
        old.join()
        latest.join()
        assertEquals(10, service.state.value.selectedWeek)
        assertEquals(TimetableWeekState.EMPTY, service.state.value.weekState)
        assertEquals(
            index().terms.first().startDay.plusDays(9L * 7),
            service.state.value.weekDates.first(),
        )
    }

    @Test
    fun openRefreshesStaleCachedCurrentWeekFromServer() = runTest {
        seedCacheAndSession()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexJson(week = 9))
            } else {
                assertEquals("9", weekFromBody(request))
                json(emptyWeek(index().terms.first(), 9))
            }
        }
        val service = service(transport)
        service.open()
        assertEquals(TimetableConnection.CONNECTED, service.state.value.connection)
        assertEquals(9, service.state.value.selectedWeek)
        assertEquals(LocalDate.parse("2026-11-01"), service.state.value.weekDates.first())
        assertEquals(TimetableWeekState.EMPTY, service.state.value.weekState)
        assertEquals(0, auth.calls)
        assertEquals(1, transport.count("GET"))
        assertEquals(1, transport.count("POST"))
    }

    @Test
    fun openUsesServerCurrentTermInsteadOfCachedTerm() = runTest {
        seedCacheAndSession()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexJson(week = 1, term = "2"))
            } else {
                assertEquals("2", termFromBody(request))
                assertEquals("1", weekFromBody(request))
                json(emptyWeek(index().terms.first { it.term == "2" }, 1))
            }
        }
        val service = service(transport)
        service.open()
        assertEquals("2", service.state.value.selectedTerm)
        assertEquals(1, service.state.value.selectedWeek)
        assertEquals(LocalDate.parse("2027-02-21"), service.state.value.weekDates.first())
    }

    @Test
    fun offlineIndexFailureKeepsCachedWeekInsteadOfGuessingFromStartDay() = runTest {
        today = LocalDate.parse("2026-12-09")
        seedCacheAndSession()
        val service = service(
            ProgrammedTransport {
                throw TimetableTransportException(TimetableTransportFailure.NETWORK)
            },
        )
        service.open()
        assertEquals(TimetableConnection.NETWORK_UNAVAILABLE, service.state.value.connection)
        assertEquals(TimetableWeekState.CACHED, service.state.value.weekState)
        assertEquals(8, service.state.value.selectedWeek)
        assertEquals(LocalDate.parse("2026-10-25"), service.state.value.weekDates.first())
        assertEquals(0, auth.calls)
    }

    @Test
    fun manualRefreshFetchesNewIndexAndCurrentWeek() = runTest {
        var serverWeek = 8
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexJson(week = serverWeek))
            } else {
                val week = weekFromBody(request).toInt()
                json(emptyWeek(index().terms.first(), week))
            }
        }
        val service = service(transport)
        service.open()
        assertEquals(8, service.state.value.selectedWeek)
        serverWeek = 9
        service.refresh()
        assertEquals(9, service.state.value.selectedWeek)
        assertEquals(2, transport.count("GET"))
        assertEquals(2, transport.count("POST"))
        assertEquals("9", weekFromBody(transport.lastPost()))
    }

    @Test
    fun refreshKeepsExplicitHistoricalWeekWhenIndexMovesOn() = runTest {
        var serverWeek = 8
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexJson(week = serverWeek))
            } else {
                val week = weekFromBody(request).toInt()
                json(emptyWeek(index().terms.first(), week))
            }
        }
        val service = service(transport)
        service.open()
        service.selectWeek(7)
        assertEquals(7, service.state.value.selectedWeek)
        serverWeek = 9
        service.refresh()
        assertEquals(7, service.state.value.selectedWeek)
        assertEquals("7", weekFromBody(transport.lastPost()))
        assertEquals(2, transport.count("GET"))
    }

    @Test
    fun sessionAuthExpiryRecoversExactlyOnce() = runTest {
        seedCacheAndSession()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexFixture())
            } else if (transportFailures++ == 0) {
                json("""{"e":10013,"d":{}}""")
            } else {
                json(weekFixture())
            }
        }
        val service = service(transport)
        service.open()
        assertEquals(TimetableConnection.CONNECTED, service.state.value.connection)
        assertEquals(1, auth.calls)
        assertEquals(2, transport.count("POST"))
    }

    @Test
    fun secondAuthFailureStopsWithoutPasswordLoop() = runTest {
        seedCacheAndSession()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexFixture())
            } else {
                json("""{"e":10013,"d":{}}""")
            }
        }
        val service = service(transport)
        service.open()
        assertEquals(TimetableConnection.SESSION_EXPIRED, service.state.value.connection)
        assertEquals(1, auth.calls)
        assertEquals(2, transport.count("POST"))
    }

    @Test
    fun invalidPasswordIsTerminalAndNotRetried() = runTest {
        auth.result = UpcAuthResult.Failure(UpcAuthFailure.INVALID_CREDENTIALS)
        val transport = ProgrammedTransport { error("认证失败后不得查询课表") }
        val service = service(transport)
        service.open()
        assertEquals(TimetableConnection.AUTH_FAILED, service.state.value.connection)
        assertEquals(1, auth.calls)
        assertEquals(0, transport.count("POST"))
        assertTrue(service.state.value.message!!.contains("密码不正确"))
    }

    @Test
    fun captchaMovesToInteractionWithoutPasswordRetry() = runTest {
        auth.result = UpcAuthResult.Failure(
            UpcAuthFailure.INTERACTION_REQUIRED,
            "https://cas.upc.edu.cn/cas/login?service=synthetic",
        )
        val service = service(ProgrammedTransport { error("需交互时不得查询课表") })
        service.open()
        assertEquals(TimetableConnection.INTERACTION_REQUIRED, service.state.value.connection)
        assertEquals(1, auth.calls)
        assertNotNull(service.state.value.interactionUrl)
        assertEquals("https://cas.upc.edu.cn/cas/login?service=synthetic", service.state.value.interactionUrl)
    }

    @Test
    fun webLoginPreservesOfficialChallengeUrl() = runTest {
        val challenge = "https://cas.upc.edu.cn/cas/login?service=synthetic&execution=abc"
        auth.result = UpcAuthResult.Failure(UpcAuthFailure.INTERACTION_REQUIRED, challenge)
        val service = service(ProgrammedTransport { error("需交互时不得查询课表") })
        service.open()
        assertEquals(challenge, service.beginInteractiveLogin())
        assertEquals(challenge, service.state.value.interactionUrl)
    }

    @Test
    fun webLoginRejectsNonOfficialChallengeUrl() = runTest {
        auth.result = UpcAuthResult.Failure(
            UpcAuthFailure.INTERACTION_REQUIRED,
            "https://evil.example/cas/login",
        )
        val service = service(ProgrammedTransport { error("需交互时不得查询课表") })
        service.open()
        assertEquals(UpcTimetableAuthEndpoints.LOGIN_URL, service.state.value.interactionUrl)
        assertEquals(UpcTimetableAuthEndpoints.LOGIN_URL, service.beginInteractiveLogin())
    }

    @Test
    fun courseColorSlotsSurviveServiceRebuild() = runTest {
        seedCacheAndSession()
        val colors = java.nio.file.Files.createTempDirectory("timetable-service-colors").toFile()
        try {
            val offline = ProgrammedTransport {
                throw TimetableTransportException(TimetableTransportFailure.NETWORK)
            }
            val first = service(offline, colorStore = TimetableColorStore(colors))
            first.open()
            val slots = first.state.value.courseColorSlots
            assertTrue(slots.isNotEmpty())

            val second = service(offline, colorStore = TimetableColorStore(colors))
            second.open()
            assertEquals(slots, second.state.value.courseColorSlots)

            assertTrue(first.invalidateForAccount(AccountDataScope.PERSONAL_DATA))
            assertFalse(File(colors, TimetableStorage.COLOR_FILE).exists())
        } finally {
            colors.deleteRecursively()
        }
    }

    @Test
    fun networkFailureWithCacheShowsCachedWeekNotEmpty() = runTest {
        seedCacheAndSession()
        val service = service(
            ProgrammedTransport {
                throw TimetableTransportException(TimetableTransportFailure.NETWORK)
            },
        )
        service.open()
        assertEquals(TimetableConnection.NETWORK_UNAVAILABLE, service.state.value.connection)
        assertEquals(TimetableWeekState.CACHED, service.state.value.weekState)
        assertEquals("示例课程A", service.state.value.meetings.first().courseName)
        assertEquals(0, auth.calls)
    }

    @Test
    fun cachedEmptyWeekStaysEmptyWhenRefreshFails() = runTest {
        seedCacheAndSession()
        val index = index()
        val target = TimetableWeek(
            term = index.terms.first(),
            week = 9,
            dates = (0..6).map {
                index.terms.first().startDay.plusDays(8L * 7 + it)
            },
            meetings = emptyList(),
            fetchedAtEpochMillis = now - 30_000,
        )
        assertTrue(
            cacheStore.save(
                ACCOUNT_ID,
                index,
                target,
                now,
            ),
        )
        assertNotNull(cacheStore.load(ACCOUNT_ID).week(index.terms.first(), 9))
        val service = service(
            ProgrammedTransport {
                throw TimetableTransportException(TimetableTransportFailure.NETWORK)
            },
        )
        service.open()
        service.selectWeek(9)
        assertEquals(TimetableWeekState.EMPTY, service.state.value.weekState)
        service.refresh()
        assertEquals(TimetableWeekState.EMPTY, service.state.value.weekState)
        assertEquals(TimetableConnection.NETWORK_UNAVAILABLE, service.state.value.connection)
    }

    @Test
    fun networkFailureWithoutCacheIsErrorNotEmptyWeek() = runTest {
        auth.result = UpcAuthResult.Failure(UpcAuthFailure.NETWORK_UNAVAILABLE)
        val service = service(ProgrammedTransport { error("auth already failed") })
        service.open()
        assertFalse(service.state.value.weekState == TimetableWeekState.EMPTY)
        assertEquals(TimetableWeekState.ERROR, service.state.value.weekState)
    }

    @Test
    fun protocolContradictionDoesNotOverwriteSuccessfulCache() = runTest {
        seedCacheAndSession()
        val service = service(
            ProgrammedTransport {
                json("""{"e":0,"d":{"classes":[],"weekdays":[]}}""")
            },
        )
        service.open()
        assertEquals(TimetableConnection.PROTOCOL_ERROR, service.state.value.connection)
        assertEquals(TimetableWeekState.CACHED, service.state.value.weekState)
        assertTrue(service.state.value.message!!.contains("已保留上次缓存"))
        assertEquals("示例课程A", service.state.value.meetings.first().courseName)
        assertEquals(
            "示例课程A",
            cacheStore.load(ACCOUNT_ID).week(index().terms.first(), 8)!!
                .meetings.first().courseName,
        )
    }

    @Test
    fun weekParseFailureKeepsCachedTodayAndDoesNotClaimEmpty() = runTest {
        today = LocalDate.parse("2026-10-26")
        seedCacheAndSession()
        val service = service(
            ProgrammedTransport { request ->
                if (request.method == "GET") {
                    json(indexFixture())
                } else {
                    json(weekFixture().replace("\"lessArr\": [6, 7]", "\"lessArr\": []"))
                }
            },
        )
        service.open()
        assertEquals(TimetableConnection.PROTOCOL_ERROR, service.state.value.connection)
        assertEquals(TimetableWeekState.CACHED, service.state.value.weekState)
        assertTrue(service.todayState.value.todayLoaded)
        assertEquals(
            listOf("示例课程A"),
            service.todayState.value.todayMeetings.map { it.courseName },
        )
        assertEquals(
            "示例课程A",
            cacheStore.load(ACCOUNT_ID).week(index().terms.first(), 8)!!
                .meetings.first().courseName,
        )
    }

    @Test
    fun protocolFailureWithoutCacheSaysNoCacheInsteadOfKeepingOne() = runTest {
        val service = service(
            ProgrammedTransport { request ->
                if (request.method == "GET") {
                    json(indexFixture())
                } else {
                    json("""{"e":0,"d":{"classes":[],"weekdays":[]}}""")
                }
            },
        )
        service.open()
        assertEquals(TimetableConnection.PROTOCOL_ERROR, service.state.value.connection)
        assertEquals(TimetableWeekState.ERROR, service.state.value.weekState)
        assertTrue(service.state.value.message!!.contains("暂无可用的旧缓存"))
        assertFalse(service.state.value.message!!.contains("已保留"))
    }

    @Test
    fun validWeekJsonWithHtmlMimeIsPublished() = runTest {
        val service = service(
            ProgrammedTransport { request ->
                if (request.method == "GET") {
                    json(indexFixture())
                } else {
                    json(weekFixture(), "text/html; charset=UTF-8")
                }
            },
        )
        service.open()
        assertEquals(TimetableConnection.CONNECTED, service.state.value.connection)
        assertEquals(TimetableWeekState.READY, service.state.value.weekState)
        assertEquals(6, service.state.value.meetings.size)
        assertNull(service.state.value.message)
    }

    @Test
    fun nonLoginHtmlWeekKeepsCompleteCacheAndNamesTheRealCategory() = runTest {
        seedCacheAndSession()
        val service = service(
            ProgrammedTransport { request ->
                if (request.method == "GET") {
                    json(indexFixture())
                } else {
                    json("<html><body>maintenance</body></html>", "text/html")
                }
            },
        )
        service.open()
        assertEquals(TimetableConnection.PROTOCOL_ERROR, service.state.value.connection)
        assertEquals(TimetableWeekState.CACHED, service.state.value.weekState)
        assertTrue(service.state.value.message!!.contains("网页内容"))
        assertTrue(service.state.value.message!!.contains("已保留上次缓存"))
        assertEquals("示例课程A", service.state.value.meetings.first().courseName)
    }

    @Test
    fun malformedWeekJsonIsFormatErrorNotEmptyOrLogin() = runTest {
        val service = service(
            ProgrammedTransport { request ->
                if (request.method == "GET") {
                    json(indexFixture())
                } else {
                    json("{\"e\":0", "application/json")
                }
            },
        )
        service.open()
        assertEquals(TimetableConnection.PROTOCOL_ERROR, service.state.value.connection)
        assertEquals(TimetableWeekState.ERROR, service.state.value.weekState)
        assertTrue(service.state.value.message!!.contains("格式异常"))
        assertFalse(service.state.value.message!!.contains("登录"))
    }

    @Test
    fun acceptedMimeMismatchIsRecordedOnTheSameOperation() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) {
                events += event
            }
        })
        try {
            val service = service(
                ProgrammedTransport { request ->
                    if (request.method == "GET") {
                        json(indexFixture())
                    } else {
                        json(weekFixture(), "text/html")
                    }
                },
            )
            service.open()
            val mismatch = events.single {
                it.detail == DiagnosticDetail.RESPONSE_MIME_MISMATCH
            }
            assertNotNull(mismatch.operationId)
            assertEquals(DiagnosticPhase.RESPONSE, mismatch.phase)
            assertEquals(DiagnosticMediaType.HTML, mismatch.mediaType)
            assertEquals(DiagnosticBodyType.JSON_OBJECT, mismatch.bodyType)
            assertTrue(
                events.filter { it.operationId == mismatch.operationId }
                    .any { it.detail == DiagnosticDetail.WEEK_OK },
            )
        } finally {
            Diagnostics.install(null)
        }
    }

    @Test
    fun lateAuthenticationCompletionIsDroppedAfterAccountClearAdvancesDiagnosticEpoch() = runTest {
        val sink = EpochDiagnosticSink()
        val authStarted = CompletableDeferred<Unit>()
        val releaseAuth = CompletableDeferred<Unit>()
        auth.beforeReturn = {
            authStarted.complete(Unit)
            releaseAuth.await()
        }
        Diagnostics.install(sink)
        try {
            val service = service(ProgrammedTransport { error("transport must not run after account clear") })
            val opening = launch { service.open() }
            withTimeout(5_000) { authStarted.await() }

            val authStart = sink.events.single { it.action == DiagnosticAction.AUTH_START }
            assertNotNull(authStart.operationId)
            sink.clearForAccountChange()
            assertTrue(service.invalidateForAccount(AccountDataScope.SESSIONS_ONLY))
            releaseAuth.complete(Unit)
            opening.join()

            assertFalse(
                sink.events.any {
                    it.action == DiagnosticAction.AUTH_COMPLETE &&
                        it.operationId == authStart.operationId
                },
            )
        } finally {
            releaseAuth.complete(Unit)
            auth.beforeReturn = {}
            Diagnostics.install(null)
        }
    }

    @Test
    fun queryAndMimeDiagnosticsAreDroppedWhenAccountClearsDuringIndexResponse() = runTest {
        seedCacheAndSession()
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                requestStarted.complete(Unit)
                releaseResponse.await()
                json(indexFixture(), "text/html")
            } else {
                error("week request must not run after account clear")
            }
        }
        val sink = EpochDiagnosticSink()
        Diagnostics.install(sink)
        try {
            val service = service(transport)
            val opening = launch { service.open() }
            withTimeout(5_000) { requestStarted.await() }

            val queryStart = sink.events.single {
                it.action == DiagnosticAction.QUERY_START && it.phase == DiagnosticPhase.INDEX
            }
            val operationId = queryStart.operationId!!
            val eventCountBeforeClear = sink.events.size
            sink.clearForAccountChange()
            assertTrue(service.invalidateForAccount(AccountDataScope.SESSIONS_ONLY))
            releaseResponse.complete(Unit)
            opening.join()

            assertTrue(
                sink.events.drop(eventCountBeforeClear).none { it.operationId == operationId },
            )
            assertFalse(sink.events.any { it.detail == DiagnosticDetail.RESPONSE_MIME_MISMATCH })
            assertFalse(sink.events.any { it.detail == DiagnosticDetail.STALE_DISCARDED })
        } finally {
            releaseResponse.complete(Unit)
            Diagnostics.install(null)
        }
    }

    @Test
    fun accountSwitchDuringWeekRequestDiscardsOldResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexFixture())
            } else {
                started.complete(Unit)
                gate.await()
                json(weekFixture())
            }
        }
        val service = service(transport)
        val job = launch { service.open() }
        withTimeout(5_000) { started.await() }
        account.publish(OTHER_REFERENCE)
        gate.complete(Unit)
        job.join()
        assertTrue(service.state.value.meetings.isEmpty())
        assertFalse(service.state.value.connection == TimetableConnection.CONNECTED)
    }

    @Test
    fun personalDataInvalidationClearsSessionAndCacheAndRejectsLateResult() = runTest {
        seedCacheAndSession()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val service = service(
            ProgrammedTransport {
                started.complete(Unit)
                gate.await()
                json(weekFixture())
            },
        )
        val job = launch { service.open() }
        withTimeout(5_000) { started.await() }
        assertTrue(service.invalidateForAccount(AccountDataScope.PERSONAL_DATA))
        gate.complete(Unit)
        job.join()
        assertTrue(service.state.value.meetings.isEmpty())
        assertNull(sessionStore.load(ACCOUNT_ID, 1))
        assertTrue(cacheStore.load(ACCOUNT_ID).weeks.isEmpty())
    }

    @Test
    fun sessionOnlyInvalidationKeepsCacheButDropsSession() = runTest {
        seedCacheAndSession()
        val service = service(ProgrammedTransport { error("no network expected") })
        assertTrue(service.invalidateForAccount(AccountDataScope.SESSIONS_ONLY))
        assertNull(sessionStore.load(ACCOUNT_ID, 1))
        assertEquals(1, cacheStore.load(ACCOUNT_ID).weeks.size)
    }

    @Test
    fun interactiveWebCookiesAreBoundToOpenedAccountAndGeneration() = runTest {
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") json(indexFixture()) else json(weekFixture())
        }
        val service = service(transport)
        assertEquals(
            "https://cas.upc.edu.cn/cas/login",
            service.beginInteractiveLogin()!!.substringBefore("?"),
        )
        assertTrue(service.adoptInteractiveCookies("eai-sess=web-session; other=value"))
        assertEquals(TimetableConnection.CONNECTED, service.state.value.connection)
        assertNotNull(sessionStore.load(ACCOUNT_ID, 1))
        assertEquals("eai-sess", sessionStore.load(ACCOUNT_ID, 1)!!.cookies.first().name)
    }

    @Test
    fun interactiveAdoptionSharesOneOperationIdAcrossCookieIndexWeekAndCache() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) {
                events += event
            }
        })
        try {
            val transport = ProgrammedTransport { request ->
                if (request.method == "GET") json(indexFixture()) else json(weekFixture())
            }
            val service = service(transport)
            assertNotNull(service.beginInteractiveLogin())
            assertTrue(
                service.adoptInteractiveCookies(
                    cookieHeader = "eai-sess=web-session",
                    operationId = "abcd1234",
                ),
            )
            val correlated = events.filter { it.operationId == "abcd1234" }
            assertTrue(
                correlated.any {
                    it.phase == DiagnosticPhase.COOKIE &&
                        it.action == DiagnosticAction.QUERY_COMPLETE
                },
            )
            assertTrue(
                correlated.any {
                    it.phase == DiagnosticPhase.INDEX &&
                        it.detail == DiagnosticDetail.INDEX_OK
                },
            )
            assertTrue(
                correlated.any {
                    it.phase == DiagnosticPhase.CACHE &&
                        it.detail == DiagnosticDetail.CACHE_SAVED
                },
            )
            assertTrue(
                correlated.any {
                    it.phase == DiagnosticPhase.WEEK &&
                        it.detail == DiagnosticDetail.WEEK_OK
                },
            )
        } finally {
            Diagnostics.install(null)
        }
    }

    @Test
    fun interactiveCookiesRejectedAfterAccountSwitch() = runTest {
        val service = service(ProgrammedTransport { json(indexFixture()) })
        service.beginInteractiveLogin()
        account.publish(OTHER_REFERENCE)
        assertFalse(service.adoptInteractiveCookies("eai-sess=web-session"))
        assertNull(sessionStore.load(ACCOUNT_ID, 1))
    }

    @Test
    fun weekCacheAndPublishAreOrderedAgainstAccountInvalidation() = runTest {
        seedCacheAndSession()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") json(indexJson(8)) else json(weekFixture())
        }
        val service = service(transport) { label ->
            if (label == "week-cache") {
                entered.complete(Unit)
                release.await()
            }
        }
        val operation = launch { service.open() }
        withTimeout(5_000) { entered.await() }
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            service.invalidateForAccount(AccountDataScope.PERSONAL_DATA)
        }
        yield()
        assertFalse(invalidation.isCompleted)
        release.complete(Unit)
        operation.join()
        invalidation.await()
        assertTrue(service.state.value.meetings.isEmpty())
        assertNull(service.state.value.selectedWeek)
        assertNull(sessionStore.load(ACCOUNT_ID, 1))
        assertTrue(cacheStore.load(ACCOUNT_ID).weeks.isEmpty())
    }

    @Test
    fun automaticAuthSessionSaveIsOrderedAgainstInvalidation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") json(indexJson(8)) else json(weekFixture())
        }
        val service = service(transport) { label ->
            if (label == "auth-session") {
                entered.complete(Unit)
                release.await()
            }
        }
        val operation = launch { service.open() }
        withTimeout(5_000) { entered.await() }
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            service.invalidateForAccount(AccountDataScope.PERSONAL_DATA)
        }
        yield()
        assertFalse(invalidation.isCompleted)
        release.complete(Unit)
        operation.join()
        invalidation.await()
        assertNull(sessionStore.load(ACCOUNT_ID, 1))
        assertTrue(cacheStore.load(ACCOUNT_ID).weeks.isEmpty())
        assertTrue(service.state.value.terms.isEmpty())
    }

    @Test
    fun webViewAdoptionSessionSaveIsOrderedAgainstInvalidation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") json(indexJson(8)) else json(weekFixture())
        }
        val service = service(transport) { label ->
            if (label == "adopt-session") {
                entered.complete(Unit)
                release.await()
            }
        }
        assertNotNull(service.beginInteractiveLogin())
        val adoption = launch { service.adoptInteractiveCookies("eai-sess=web-session") }
        withTimeout(5_000) { entered.await() }
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            service.invalidateForAccount(AccountDataScope.PERSONAL_DATA)
        }
        yield()
        assertFalse(invalidation.isCompleted)
        release.complete(Unit)
        adoption.join()
        invalidation.await()
        assertNull(sessionStore.load(ACCOUNT_ID, 1))
        assertTrue(cacheStore.load(ACCOUNT_ID).weeks.isEmpty())
        assertTrue(service.state.value.terms.isEmpty())
        assertNull(service.state.value.interactionUrl)
    }

    @Test
    fun lateCachedReadCannotRepublishAfterInvalidation() = runTest {
        seedCacheAndSession()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            ProgrammedTransport { error("late cache must not query") },
        ) { label ->
            if (label == "open-cache") {
                entered.complete(Unit)
                release.await()
            }
        }
        val operation = launch { service.open() }
        withTimeout(5_000) { entered.await() }
        service.invalidateForAccount(AccountDataScope.PERSONAL_DATA)
        release.complete(Unit)
        operation.join()
        assertTrue(service.state.value.meetings.isEmpty())
        assertTrue(service.state.value.terms.isEmpty())
        assertNull(sessionStore.load(ACCOUNT_ID, 1))
        assertTrue(cacheStore.load(ACCOUNT_ID).weeks.isEmpty())
    }

    @Test
    fun newWeekSelectionWinsOverOldPublication() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(indexJson(8))
            } else {
                json(emptyWeek(index().terms.first(), weekFromBody(request).toInt()))
            }
        }
        val service = service(transport) { label ->
            if (label == "week-publish") {
                entered.complete(Unit)
                release.await()
            }
        }
        val opening = launch { service.open() }
        withTimeout(5_000) { entered.await() }
        val selecting = launch { service.selectWeek(9) }
        release.complete(Unit)
        opening.join()
        selecting.join()
        assertEquals(9, service.state.value.selectedWeek)
        assertEquals(TimetableWeekState.EMPTY, service.state.value.weekState)
    }

    @Test
    fun weekWidgetStitchesSundayThroughSaturdayAcrossAdjacentCachedWeeks() = runTest {
        val term = index().terms.first { it.term == "1" }
        val weekStart = LocalDate.parse("2026-10-25")
        val priorWeek = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-22").plusDays(it.toLong()) },
            meetings = listOf(
                weekWidgetMeeting("周日课程", 8, LocalDate.parse("2026-10-25"), 1, 2),
                weekWidgetMeeting("周三前端课程", 8, LocalDate.parse("2026-10-28"), 3, 4),
                weekWidgetMeeting("周三分段课程", 8, LocalDate.parse("2026-10-28"), 1, 2),
                weekWidgetMeeting("周三分段课程", 8, LocalDate.parse("2026-10-28"), 4, 5),
                weekWidgetMeeting("周三重叠课程", 8, LocalDate.parse("2026-10-28"), 4, 5),
            ),
            fetchedAtEpochMillis = now,
        )
        val nextWeek = TimetableWeek(
            term = term,
            week = 9,
            dates = (0..6).map { LocalDate.parse("2026-10-29").plusDays(it.toLong()) },
            meetings = listOf(
                weekWidgetMeeting("周六课程", 9, LocalDate.parse("2026-10-31"), 5, 6),
            ),
            fetchedAtEpochMillis = now + 1,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), priorWeek, now))
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), nextWeek, now + 1))
        val transport = ProgrammedTransport { error("week widget must stay cache-only") }
        val service = service(transport)

        val snapshot = service.readWeekWidgetSnapshot()

        assertEquals(TimetableWeekWidgetStatus.OK, snapshot.status)
        assertEquals(weekStart, snapshot.weekStart)
        assertEquals(today, snapshot.today)
        assertEquals(
            (0..6).map { weekStart.plusDays(it.toLong()) },
            snapshot.days.map { it.date },
        )
        assertEquals(listOf("周日课程"), snapshot.days[0].courses.map { it.name })
        assertEquals(listOf("周六课程"), snapshot.days[6].courses.map { it.name })
        // 重叠课程保留；非连续节次按协议拆分后的区间原样展示。
        assertEquals(
            listOf(
                "第1–2节" to "周三分段课程",
                "第3–4节" to "周三前端课程",
                "第4–5节" to "周三分段课程",
                "第4–5节" to "周三重叠课程",
            ),
            snapshot.days[3].courses.map { it.periodLabel to it.name },
        )
        assertEquals(0, auth.calls)
        assertEquals(0, transport.count("GET"))
        assertEquals(0, transport.count("POST"))
        assertTrue(service.isWeekWidgetSnapshotCurrent(snapshot))
    }

    @Test
    fun weekWidgetPartialCoverageMarksUnsyncedDaysInsteadOfFakeEmptyWeek() = runTest {
        val term = index().terms.first { it.term == "1" }
        val partial = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-22").plusDays(it.toLong()) },
            meetings = emptyList(),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), partial, now))
        val service = service(ProgrammedTransport { error("week widget must stay cache-only") })

        val snapshot = service.readWeekWidgetSnapshot()

        assertEquals(TimetableWeekWidgetStatus.PARTIAL, snapshot.status)
        assertEquals(
            listOf(
                TimetableWeekWidgetDayState.EMPTY,
                TimetableWeekWidgetDayState.EMPTY,
                TimetableWeekWidgetDayState.EMPTY,
                TimetableWeekWidgetDayState.EMPTY,
                TimetableWeekWidgetDayState.UNSYNCED,
                TimetableWeekWidgetDayState.UNSYNCED,
                TimetableWeekWidgetDayState.UNSYNCED,
            ),
            snapshot.days.map { it.state },
        )
        assertFalse(snapshot.status == TimetableWeekWidgetStatus.EMPTY)
    }

    @Test
    fun weekWidgetCompleteEmptyWeekIsSyncedEmpty() = runTest {
        val term = index().terms.first { it.term == "1" }
        val emptyWeek = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) },
            meetings = emptyList(),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), emptyWeek, now))
        val service = service(ProgrammedTransport { error("week widget must stay cache-only") })

        val snapshot = service.readWeekWidgetSnapshot()

        assertEquals(TimetableWeekWidgetStatus.EMPTY, snapshot.status)
        assertTrue(snapshot.days.all { it.state == TimetableWeekWidgetDayState.EMPTY })
        assertEquals(now, snapshot.fetchedAtEpochMillis)
    }

    @Test
    fun weekWidgetTrustedIndexDistinguishesOutsideTermFromNeedsSync() = runTest {
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), null, now))
        val service = service(ProgrammedTransport { error("week widget must stay cache-only") })

        val inTerm = service.readWeekWidgetSnapshot()
        assertEquals(TimetableWeekWidgetStatus.NEEDS_SYNC, inTerm.status)
        assertTrue(inTerm.days.all { it.state == TimetableWeekWidgetDayState.UNSYNCED })

        today = LocalDate.parse("2027-01-20")
        val outsideTerm = service.readWeekWidgetSnapshot()
        assertEquals(TimetableWeekWidgetStatus.OUTSIDE_TERM, outsideTerm.status)
        assertEquals(LocalDate.parse("2027-01-17"), outsideTerm.weekStart)
        assertTrue(outsideTerm.days.all { it.state == TimetableWeekWidgetDayState.OUTSIDE_TERM })
    }

    @Test
    fun weekWidgetConflictIsReportedInsteadOfChoosingOneCachedWeek() = runTest {
        val term = index().terms.first { it.term == "1" }
        val dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) }
        val first = TimetableWeek(
            term = term,
            week = 8,
            dates = dates,
            meetings = listOf(weekWidgetMeeting("缓存课程甲", 8, dates.first(), 1, 2)),
            fetchedAtEpochMillis = now,
        )
        val second = TimetableWeek(
            term = term,
            week = 9,
            dates = dates,
            meetings = listOf(weekWidgetMeeting("缓存课程乙", 9, dates.first(), 1, 2)),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), first, now))
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), second, now))
        val service = service(ProgrammedTransport { error("week widget must stay cache-only") })

        val snapshot = service.readWeekWidgetSnapshot()

        assertEquals(TimetableWeekWidgetStatus.CONFLICT, snapshot.status)
        // 整周冲突时仍保留逐日冲突标记，渲染层据此只显示全局冲突状态。
        assertEquals(
            List(7) { TimetableWeekWidgetDayState.CONFLICT },
            snapshot.days.map { it.state },
        )
    }

    @Test
    fun weekWidgetCorruptedCacheIsAReadErrorNotEmptyWeek() = runTest {
        File(directory, TimetableStorage.CACHE_FILE).writeText("{broken")
        val service = service(ProgrammedTransport { error("week widget must stay cache-only") })

        val snapshot = service.readWeekWidgetSnapshot()

        assertEquals(TimetableWeekWidgetStatus.READ_ERROR, snapshot.status)
        assertTrue(snapshot.days.isEmpty())
    }

    @Test
    fun weekWidgetStaleReadCannotPublishAfterPersonalDataInvalidation() = runTest {
        val term = index().terms.first { it.term == "1" }
        val cached = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) },
            meetings = listOf(
                weekWidgetMeeting("清理前课程", 8, LocalDate.parse("2026-10-28"), 1, 2),
            ),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), cached, now))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport { error("week widget must stay cache-only") },
            commitBarrier = { label ->
                if (label == "week-widget-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        var result: TimetableWeekWidgetSnapshot? = null
        val reading = launch { result = service.readWeekWidgetSnapshot() }
        withTimeout(5_000) { entered.await() }
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            service.invalidateForAccount(AccountDataScope.PERSONAL_DATA)
        }
        yield()
        release.complete(Unit)
        reading.join()
        invalidation.await()

        val snapshot = requireNotNull(result)
        assertFalse(snapshot.isReadResult)
        assertTrue(snapshot.days.isEmpty())
        assertFalse(service.isWeekWidgetSnapshotCurrent(snapshot))
        assertTrue(cacheStore.load(ACCOUNT_ID).weeks.isEmpty())
    }

    @Test
    fun weekWidgetReadBoundToAccountGenerationCannotPublishAfterSwitch() = runTest {
        val term = index().terms.first { it.term == "1" }
        val cached = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) },
            meetings = listOf(
                weekWidgetMeeting("旧账号课程", 8, LocalDate.parse("2026-10-28"), 1, 2),
            ),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), cached, now))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport { error("week widget must stay cache-only") },
            commitBarrier = { label ->
                if (label == "week-widget-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        var result: TimetableWeekWidgetSnapshot? = null
        val reading = launch { result = service.readWeekWidgetSnapshot() }
        withTimeout(5_000) { entered.await() }
        account.publish(OTHER_REFERENCE)
        release.complete(Unit)
        reading.join()

        val snapshot = requireNotNull(result)
        assertFalse(snapshot.isReadResult)
        assertTrue(snapshot.days.isEmpty())
        assertFalse(service.isWeekWidgetSnapshotCurrent(snapshot))
    }

    @Test
    fun todayTomorrowWidgetSaturdayReadsNextWeekIndependently() = runTest {
        today = LocalDate.parse("2026-10-31")
        val term = index().terms.first { it.term == "1" }
        val nextWeekDates = (0..6).map { LocalDate.parse("2026-11-01").plusDays(it.toLong()) }
        val nextWeek = TimetableWeek(
            term = term,
            week = 9,
            dates = nextWeekDates,
            meetings = listOf(
                weekWidgetMeeting("次日跨周课程", 9, LocalDate.parse("2026-11-01"), 3, 4),
            ),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), nextWeek, now))
        val service = service(ProgrammedTransport { error("today/tomorrow widget must stay cache-only") })

        val snapshot = service.readTodayTomorrowWidgetSnapshot()

        assertEquals(TimetableTodayTomorrowWidgetStatus.OK, snapshot.status)
        assertEquals(LocalDate.parse("2026-10-31"), snapshot.today)
        assertEquals(LocalDate.parse("2026-11-01"), snapshot.tomorrow)
        assertEquals(
            listOf(
                TimetableTodayTomorrowWidgetDayState.UNSYNCED,
                TimetableTodayTomorrowWidgetDayState.COURSES,
            ),
            snapshot.days.map { it.state },
        )
        // 只有次日所在的下一教学周被缓存，今天仍如实显示未同步，不能借次日冒充已同步。
        assertEquals(
            listOf("次日跨周课程"),
            snapshot.days[1].courses.map { it.name },
        )
        assertTrue(service.isTodayTomorrowWidgetSnapshotCurrent(snapshot))
    }

    @Test
    fun todayTomorrowWidgetCompleteEmptyDaysAreSyncedEmpty() = runTest {
        val term = index().terms.first { it.term == "1" }
        val emptyWeek = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) },
            meetings = emptyList(),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), emptyWeek, now))
        val service = service(ProgrammedTransport { error("today/tomorrow widget must stay cache-only") })

        val snapshot = service.readTodayTomorrowWidgetSnapshot()

        assertEquals(TimetableTodayTomorrowWidgetStatus.OK, snapshot.status)
        assertTrue(snapshot.days.all { it.state == TimetableTodayTomorrowWidgetDayState.EMPTY })
        assertEquals(
            listOf(LocalDate.parse("2026-10-28"), LocalDate.parse("2026-10-29")),
            snapshot.days.map { it.date },
        )
    }

    @Test
    fun todayTomorrowWidgetMissingCacheIsUnsyncedNotEmpty() = runTest {
        val service = service(ProgrammedTransport { error("today/tomorrow widget must stay cache-only") })

        val snapshot = service.readTodayTomorrowWidgetSnapshot()

        assertEquals(TimetableTodayTomorrowWidgetStatus.OK, snapshot.status)
        assertTrue(snapshot.days.all { it.state == TimetableTodayTomorrowWidgetDayState.UNSYNCED })
        assertTrue(snapshot.days.all { it.courses.isEmpty() })
    }

    @Test
    fun todayTomorrowWidgetTrustedIndexDistinguishesOutsideTerm() = runTest {
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), null, now))
        today = LocalDate.parse("2027-01-20")
        val service = service(ProgrammedTransport { error("today/tomorrow widget must stay cache-only") })

        val snapshot = service.readTodayTomorrowWidgetSnapshot()

        assertEquals(TimetableTodayTomorrowWidgetStatus.OK, snapshot.status)
        assertEquals(
            listOf(
                TimetableTodayTomorrowWidgetDayState.OUTSIDE_TERM,
                TimetableTodayTomorrowWidgetDayState.OUTSIDE_TERM,
            ),
            snapshot.days.map { it.state },
        )
    }

    @Test
    fun todayTomorrowWidgetPerDayConflictAndReadErrorStayExplicit() = runTest {
        val term = index().terms.first { it.term == "1" }
        val dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) }
        val first = TimetableWeek(
            term = term,
            week = 8,
            dates = dates,
            meetings = listOf(weekWidgetMeeting("冲突课程甲", 8, today, 1, 2)),
            fetchedAtEpochMillis = now,
        )
        val second = TimetableWeek(
            term = term,
            week = 9,
            dates = dates,
            meetings = listOf(weekWidgetMeeting("冲突课程乙", 9, today, 3, 4)),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), first, now))
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), second, now))
        val conflictService = service(ProgrammedTransport { error("widget must stay cache-only") })

        val conflict = conflictService.readTodayTomorrowWidgetSnapshot()

        assertEquals(TimetableTodayTomorrowWidgetStatus.OK, conflict.status)
        assertEquals(
            listOf(
                TimetableTodayTomorrowWidgetDayState.CONFLICT,
                TimetableTodayTomorrowWidgetDayState.CONFLICT,
            ),
            conflict.days.map { it.state },
        )

        File(directory, TimetableStorage.CACHE_FILE).writeText("{broken")
        val failedService = service(ProgrammedTransport { error("widget must stay cache-only") })

        val failed = failedService.readTodayTomorrowWidgetSnapshot()

        assertEquals(TimetableTodayTomorrowWidgetStatus.READ_ERROR, failed.status)
        // 整份缓存不可读仍保留两个真实日期，各自标注读取失败。
        assertEquals(
            listOf(today, today.plusDays(1)),
            failed.days.map { it.date },
        )
        assertTrue(
            failed.days.all { it.state == TimetableTodayTomorrowWidgetDayState.READ_ERROR },
        )
    }

    @Test
    fun todayTomorrowWidgetSharesOneCacheReadWithWeekAndBothStayCurrent() = runTest {
        val term = index().terms.first { it.term == "1" }
        val week = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) },
            meetings = listOf(weekWidgetMeeting("共享读取课程", 8, today, 5, 6)),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), week, now))
        var barriers = 0
        val service = service(
            transport = ProgrammedTransport { error("calendar widgets must stay cache-only") },
            commitBarrier = { label ->
                if (label == "calendar-widget-cache") barriers += 1
            },
        )

        val snapshots = service.readCalendarWidgetSnapshots(
            includeWeek = true,
            includeTodayTomorrow = true,
        )

        assertEquals(1, barriers)
        val weekSnapshot = requireNotNull(snapshots.week)
        val todayTomorrowSnapshot = requireNotNull(snapshots.todayTomorrow)
        assertEquals(TimetableWeekWidgetStatus.OK, weekSnapshot.status)
        assertEquals(TimetableTodayTomorrowWidgetStatus.OK, todayTomorrowSnapshot.status)
        assertTrue(service.isWeekWidgetSnapshotCurrent(weekSnapshot))
        assertTrue(service.isTodayTomorrowWidgetSnapshotCurrent(todayTomorrowSnapshot))
    }

    @Test
    fun todayTomorrowWidgetSuccessfulCacheWriteInvalidatesPreviousSnapshot() = runTest {
        seedCacheAndSession()
        val service = service(ProgrammedTransport(indexAndWeekHandler()))
        val snapshot = service.readTodayTomorrowWidgetSnapshot()
        assertTrue(service.isTodayTomorrowWidgetSnapshotCurrent(snapshot))

        service.open()
        assertFalse(service.isTodayTomorrowWidgetSnapshotCurrent(snapshot))
    }

    @Test
    fun todayTomorrowWidgetStaleReadCannotPublishAfterInvalidationOrDateChange() = runTest {
        val term = index().terms.first { it.term == "1" }
        val cached = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) },
            meetings = listOf(weekWidgetMeeting("清理前课程", 8, today, 1, 2)),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), cached, now))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport { error("widget must stay cache-only") },
            commitBarrier = { label ->
                if (label == "today-tomorrow-widget-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        var result: TimetableTodayTomorrowWidgetSnapshot? = null
        val reading = launch { result = service.readTodayTomorrowWidgetSnapshot() }
        withTimeout(5_000) { entered.await() }
        today = LocalDate.parse("2026-10-29")
        release.complete(Unit)
        reading.join()

        val snapshot = requireNotNull(result)
        assertFalse(snapshot.isReadResult)
        assertEquals(TimetableTodayTomorrowWidgetStatus.READING, snapshot.status)
        assertEquals(LocalDate.parse("2026-10-29"), snapshot.today)
        assertTrue(snapshot.days.isEmpty())
        assertFalse(service.isTodayTomorrowWidgetSnapshotCurrent(snapshot))

        val invalidationEntered = CompletableDeferred<Unit>()
        val invalidationRelease = CompletableDeferred<Unit>()
        today = LocalDate.parse("2026-10-28")
        val invalidationService = service(
            transport = ProgrammedTransport { error("widget must stay cache-only") },
            commitBarrier = { label ->
                if (label == "today-tomorrow-widget-cache") {
                    invalidationEntered.complete(Unit)
                    invalidationRelease.await()
                }
            },
        )
        var invalidated: TimetableTodayTomorrowWidgetSnapshot? = null
        val invalidatingRead = launch {
            invalidated = invalidationService.readTodayTomorrowWidgetSnapshot()
        }
        withTimeout(5_000) { invalidationEntered.await() }
        val invalidation = async(start = CoroutineStart.UNDISPATCHED) {
            invalidationService.invalidateForAccount(AccountDataScope.PERSONAL_DATA)
        }
        yield()
        invalidationRelease.complete(Unit)
        invalidatingRead.join()
        invalidation.await()

        val stale = requireNotNull(invalidated)
        assertFalse(stale.isReadResult)
        assertTrue(stale.days.isEmpty())
        assertFalse(invalidationService.isTodayTomorrowWidgetSnapshotCurrent(stale))
    }

    @Test
    fun todayTomorrowWidgetReadBoundToAccountGenerationCannotPublishAfterSwitch() = runTest {
        val term = index().terms.first { it.term == "1" }
        val cached = TimetableWeek(
            term = term,
            week = 8,
            dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) },
            meetings = listOf(weekWidgetMeeting("旧账号课程", 8, today, 1, 2)),
            fetchedAtEpochMillis = now,
        )
        assertTrue(cacheStore.save(ACCOUNT_ID, index(), cached, now))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport { error("widget must stay cache-only") },
            commitBarrier = { label ->
                if (label == "today-tomorrow-widget-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        var result: TimetableTodayTomorrowWidgetSnapshot? = null
        val reading = launch { result = service.readTodayTomorrowWidgetSnapshot() }
        withTimeout(5_000) { entered.await() }
        account.publish(OTHER_REFERENCE)
        release.complete(Unit)
        reading.join()

        val snapshot = requireNotNull(result)
        assertFalse(snapshot.isReadResult)
        assertTrue(snapshot.days.isEmpty())
        assertFalse(service.isTodayTomorrowWidgetSnapshotCurrent(snapshot))
    }

    private fun weekWidgetMeeting(
        name: String,
        weekNumber: Int,
        date: LocalDate,
        startPeriod: Int,
        endPeriod: Int,
    ): TimetableMeeting = TimetableMeeting(
        courseName = name,
        weekday = date.dayOfWeek.value,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        periods = (startPeriod..endPeriod).toList(),
        sourcePeriods = (startPeriod..endPeriod).toList(),
        weeks = listOf(weekNumber),
        weekLabel = weekNumber.toString(),
        teacher = "教师甲",
        location = "教学楼 A-101",
        sectionsSource = "lessArr",
        date = date,
    )

    private fun service(
        transport: ProgrammedTransport,
        colorStore: TimetableColorStore = TimetableColorStore.inMemory(),
        commitBarrier: suspend (String) -> Unit = {},
    ): TimetableService =
        TimetableService(
            account = account,
            authenticator = auth,
            api = TimetableApi(transport, clock = { now }),
            sessionStore = sessionStore,
            cacheStore = cacheStore,
            clock = { now },
            todayProvider = { today },
            commitBarrier = commitBarrier,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            colorStore = colorStore,
        )

    private fun seedCacheAndSession() {
        val index = index()
        cacheStore.save(
            ACCOUNT_ID,
            index,
            cachedWeek(index.terms.first(), "示例课程A"),
            now,
        )
        assertTrue(
            sessionStore.save(
                TimetableSession(
                    accountId = ACCOUNT_ID,
                    credentialGeneration = 1,
                    businessGeneration = 0,
                    savedAtEpochMillis = now,
                    cookies = listOf(
                        TimetableCookie(
                            name = "eai-sess",
                            value = "cached-session",
                            domain = "app.upc.edu.cn",
                            path = "/",
                            secure = true,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun seedTodayCache(
        cacheIndex: TimetableIndex = index(),
        weekNumber: Int = cacheIndex.currentWeek ?: 8,
        courseName: String = "今日示例课程",
    ) {
        val term = cacheIndex.terms.first { it.term == "1" }
        val start = term.startDay.plusWeeks((weekNumber - 1).toLong())
        val dates = (0..6).map { start.plusDays(it.toLong()) }
        val meeting = TimetableMeeting(
            courseName = courseName,
            weekday = today.dayOfWeek.value,
            startPeriod = 6,
            endPeriod = 7,
            periods = listOf(6, 7),
            sourcePeriods = listOf(6, 7),
            weeks = listOf(weekNumber),
            weekLabel = weekNumber.toString(),
            teacher = "教师甲",
            location = "教学楼 A-101",
            sectionsSource = "lessArr",
            date = today,
        )
        assertTrue(
            cacheStore.save(
                ACCOUNT_ID,
                cacheIndex,
                TimetableWeek(term, weekNumber, dates, listOf(meeting), now),
                now,
            ),
        )
    }

    private fun cachedWeekForToday(
        term: TimetableTerm,
        weekNumber: Int,
        courseName: String,
    ): TimetableWeek {
        val start = term.startDay.plusWeeks((weekNumber - 1).toLong())
        val dates = (0..6).map { start.plusDays(it.toLong()) }
        val meetingDate = today.takeIf { it in dates } ?: dates.first()
        val meeting = TimetableMeeting(
            courseName = courseName,
            weekday = meetingDate.dayOfWeek.value,
            startPeriod = 6,
            endPeriod = 7,
            periods = listOf(6, 7),
            sourcePeriods = listOf(6, 7),
            weeks = listOf(weekNumber),
            weekLabel = weekNumber.toString(),
            teacher = "教师甲",
            location = "教学楼 A-101",
            sectionsSource = "lessArr",
            date = meetingDate,
        )
        return TimetableWeek(term, weekNumber, dates, listOf(meeting), now)
    }

    private suspend fun assertLateTodayReadCannotReplaceOnlineResult(scope: CoroutineScope) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            transport = ProgrammedTransport(indexAndWeekHandler()),
            commitBarrier = { operation ->
                if (operation == "today-cache") {
                    entered.complete(Unit)
                    release.await()
                }
            },
        )
        val reading = scope.launch { service.restoreTodayFromCache() }
        // The service's account observer uses Default, outside the test scheduler.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { entered.await() }
        }

        service.open()
        assertEquals(TimetableTodayState.READY, service.todayState.value.todayState)
        assertEquals(
            listOf("示例课程A"),
            service.todayState.value.todayMeetings.map { it.courseName },
        )
        release.complete(Unit)
        reading.join()

        assertEquals(TimetableTodayState.READY, service.todayState.value.todayState)
        assertEquals(
            listOf("示例课程A"),
            service.todayState.value.todayMeetings.map { it.courseName },
        )
    }

    private fun cachedWeek(term: TimetableTerm, courseName: String): TimetableWeek {
        val dates = (0..6).map { term.startDay.plusDays(7L * 7 + it.toLong()) }
        return TimetableWeek(
            term = term,
            week = 8,
            dates = dates,
            meetings = listOf(
                TimetableMeeting(
                    courseName = courseName,
                    weekday = 1,
                    startPeriod = 6,
                    endPeriod = 7,
                    periods = listOf(6, 7),
                    sourcePeriods = listOf(6, 7),
                    weeks = listOf(8),
                    weekLabel = "8",
                    teacher = "教师甲",
                    location = "教学楼A-101",
                    sectionsSource = "lessArr",
                    date = dates[1],
                ),
            ),
            fetchedAtEpochMillis = now - 60_000,
        )
    }

    private fun indexAndWeekHandler(): suspend (TimetableHttpRequest) -> TimetableHttpResponse =
        { request ->
            if (request.method == "GET") {
                json(indexFixture())
            } else if (request.body.orEmpty().contains("week=9")) {
                json(javaClass.getResource("/timetable_week_09_empty.json")!!.readText())
            } else {
                json(weekFixture())
            }
        }

    private fun indexJson(week: Int, term: String = "1"): String {
        val termInfo = index().terms
        val selected = termInfo.first { it.term == term }
        val start = selected.startDay.plusDays((week - 1).toLong() * 7)
        val dates = (0..6).joinToString(",") { "\"${start.plusDays(it.toLong())}\"" }
        val terms = termInfo.joinToString(",") { item ->
            """{"year":"${item.year}","term":"${item.term}",""" +
                """"startday":"${item.startDay}","countweek":"${item.totalWeeks}","id":"${item.id}"}"""
        }
        return """{"e":0,"m":"ok","d":{"params":{"year":"${selected.year}",""" +
            """"term":"${selected.term}","week":$week,"startday":"${selected.startDay}",""" +
            """"countweek":"${selected.totalWeeks}","id":"${selected.id}"},""" +
            """"termInfo":[$terms],"weekday":"6","weekdays":[$dates]}}"""
    }

    private fun weekFromBody(request: TimetableHttpRequest): String =
        Regex("(?:^|&)week=(\\d+)").find(request.body.orEmpty())!!.groupValues[1]

    private fun termFromBody(request: TimetableHttpRequest): String =
        Regex("(?:^|&)term=([^&]+)").find(request.body.orEmpty())!!.groupValues[1]

    private fun emptyWeek(term: TimetableTerm, week: Int): String {
        val start = term.startDay.plusDays((week - 1).toLong() * 7)
        val days = (0..6).joinToString(",") { "\"${start.plusDays(it.toLong())}\"" }
        return """{"e":0,"d":{"classes":[{},{},{},{},{},{},{}],"weekdays":[$days]}}"""
    }

    private fun index(): TimetableIndex = TimetableProtocol.parseIndex(indexFixture())

    private fun indexFixture(): String =
        javaClass.getResource("/timetable_index.json")!!.readText()

    private fun weekFixture(): String =
        javaClass.getResource("/timetable_week_08.json")!!.readText()

    private fun json(
        body: String,
        mime: String = "application/json",
    ): TimetableHttpResponse = TimetableHttpResponse(
        200,
        mapOf("content-type" to listOf(mime)),
        body.toByteArray(),
    )

    private var transportFailures = 0

    companion object {
        private const val ACCOUNT_ID = "acct_synthetic_1"
        private val REFERENCE = SchoolAccountReference(
            SchoolAccountId(ACCOUNT_ID),
            AccountGeneration(1),
        )
        private val OTHER_REFERENCE = SchoolAccountReference(
            SchoolAccountId("acct_synthetic_2"),
            AccountGeneration(2),
        )
    }

    private inner class FakeAccount : SchoolAccountReferenceReader {
        private val _state = MutableStateFlow(
            SchoolAccountState(
                status = SchoolAccountStatus.SAVED_UNVERIFIED,
                generation = AccountGeneration(1),
                maskedAccount = "20••••01",
                studentIdAvailable = false,
                savedAtEpochMillis = 1,
            ),
        )
        private val _reference = MutableStateFlow<SchoolAccountReference?>(REFERENCE)
        override val state: StateFlow<SchoolAccountState> = _state
        override val accountReference: StateFlow<SchoolAccountReference?> = _reference

        fun publish(reference: SchoolAccountReference) {
            _reference.value = reference
            _state.value = SchoolAccountState(
                status = SchoolAccountStatus.SAVED_UNVERIFIED,
                generation = reference.credentialGeneration,
                maskedAccount = "20••••02",
                savedAtEpochMillis = 1,
            )
        }

        fun setLoading() {
            _reference.value = null
            _state.value = SchoolAccountState(status = SchoolAccountStatus.LOADING)
        }
    }

    private inner class FakeAuthenticator : TimetableAuthenticator {
        var calls = 0
        var beforeReturn: suspend () -> Unit = {}
        var result: UpcAuthResult = UpcAuthResult.Success(
            listOf(
                UpcAuthCookie(
                    name = "eai-sess",
                    value = "new-session",
                    domain = "app.upc.edu.cn",
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    expiresAtEpochSeconds = null,
                ),
            ),
        )

        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcAuthResult {
            calls++
            beforeReturn()
            return result
        }
    }

    private class EpochDiagnosticSink : EpochBoundDiagnosticSink {
        var epoch = 0L
        val events = mutableListOf<DiagnosticEvent>()

        override fun captureEpoch(): Long = epoch

        override fun record(event: DiagnosticEvent) {
            events += event
        }

        override fun record(event: DiagnosticEvent, expectedEpoch: Long?) {
            if (expectedEpoch == null || expectedEpoch == epoch) events += event
        }

        fun clearForAccountChange() {
            epoch++
        }
    }

    private class ProgrammedTransport(
        private val handler: suspend (TimetableHttpRequest) -> TimetableHttpResponse,
    ) : TimetableHttpTransport {
        private val requests = mutableListOf<TimetableHttpRequest>()

        override suspend fun execute(request: TimetableHttpRequest): TimetableHttpResponse {
            requests += request
            return handler(request)
        }

        fun count(method: String): Int = requests.count { it.method == method }

        fun postBodies(): List<String> =
            requests.filter { it.method == "POST" }.map { it.body.orEmpty() }

        fun lastPost(): TimetableHttpRequest = requests.last { it.method == "POST" }
    }
}

private class TestCrypto : ByteCrypto {
    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
    override fun decrypt(stored: ByteArray): ByteArray = stored.reversedArray()
}
