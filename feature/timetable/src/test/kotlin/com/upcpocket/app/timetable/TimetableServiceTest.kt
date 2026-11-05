package com.upcpocket.app.timetable

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.upcauth.TimetableAuthenticator
import com.upcpocket.app.upcauth.UpcAuthCookie
import com.upcpocket.app.upcauth.UpcAuthFailure
import com.upcpocket.app.upcauth.UpcAuthResult
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun viewingAnotherWeekKeepsActualToday() = runTest {
        val transport = ProgrammedTransport(indexAndWeekHandler())
        val service = service(transport)
        service.open()
        val today = service.state.value.today
        val todayMeetings = service.state.value.todayMeetings
        assertTrue(service.state.value.todayLoaded)

        service.selectWeek(9)
        assertEquals(9, service.state.value.selectedWeek)
        assertEquals(today, service.state.value.today)
        assertEquals(todayMeetings, service.state.value.todayMeetings)
        assertTrue(service.state.value.todayLoaded)
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
        assertEquals("示例课程A", service.state.value.meetings.first().courseName)
        assertEquals(
            "示例课程A",
            cacheStore.load(ACCOUNT_ID).week(index().terms.first(), 8)!!
                .meetings.first().courseName,
        )
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

    private fun json(body: String): TimetableHttpResponse = TimetableHttpResponse(
        200,
        mapOf("content-type" to listOf("application/json")),
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
    }

    private inner class FakeAuthenticator : TimetableAuthenticator {
        var calls = 0
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
            return result
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
