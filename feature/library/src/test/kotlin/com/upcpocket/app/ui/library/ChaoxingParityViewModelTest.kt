package com.upcpocket.app.ui.library

import com.upcpocket.app.account.api.*
import com.upcpocket.app.data.chaoxing.*
import com.upcpocket.app.data.prefs.*
import com.upcpocket.app.data.library.LibraryHiddenSeat
import com.upcpocket.app.library.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChaoxingParityViewModelTest {
    private val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString()
    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun reset() { Dispatchers.resetMain() }
    private class Account : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(3)))
        override val accountReference = MutableStateFlow(SchoolAccountReference(SchoolAccountId("synthetic"), AccountGeneration(3)))
    }
    private class Fixture(val kind: ChaoxingSeatKind, val store: LibraryHiddenSeatsStore = NoOpLibraryHiddenSeatsStore,
        val clock: Clock = Clock.system(CHAOXING_ZONE)) {
        val account = Account()
        val posts = AtomicInteger()
        val usedCalls = AtomicInteger()
        var attributes = "[]"
        var capacity = 3
        var minHours: Double? = null
        var maxHours: Double? = null
        var used: (ChaoxingHttpRequest) -> String = { """{"success":true,"data":{"seatReserves":[]}}""" }
        var preflight: () -> String = { """<input id="submit_enc" value="synthetic">""" }
        var submit: () -> String = { "broken" }
        val repo = ChaoxingRepository(ChaoxingTransport { r ->
            val body = when {
                r.url.contains("/index") -> """var userLoginInfo={"userInfo":{"sno":"synthetic"}};"""
                r.url.contains("/room/list") -> """{"success":true,"data":{"seatRoomList":[],"totalPage":1,"totalRow":0}}"""
                r.url.contains("/room/info") -> {
                    val id = r.formBody!!.substringAfter("id=").substringBefore('&')
                    val hours = listOf("mon","tues","wed","thur","fri","sat","sun").flatMap {
                        listOf("\"${it}StartTime\":\"08:00\"", "\"${it}EndTime\":\"22:00\"")
                    }.joinToString(",")
                    """{"success":true,"data":{"seatAttributes":$attributes,"seatRoom":{"id":$id,"capacity":$capacity},"seatConfig":{"timeUnit":30,"minReserveDuration":$minHours,"reserveDuration":$maxHours,"commonTimeConfig":{$hours}}}}"""
                }
                r.url.contains("/getdrawseat") -> """{"success":true,"data":[
                    {"seatNum":"001","xleft":0.5,"ytop":0,"width":30,"height":30,"rotate":630},
                    {"seatNum":"002","xleft":40,"ytop":0,"width":30,"height":30},
                    {"seatNum":"003","xleft":80,"ytop":0,"width":30,"height":30}]}"""
                r.url.contains("/getusedseatnums") -> { usedCalls.incrementAndGet(); used(r) }
                r.url.contains("/select?") -> preflight()
                r.url.endsWith("/submit") -> { posts.incrementAndGet(); submit() }
                else -> error("unexpected synthetic endpoint")
            }
            ChaoxingHttpResponse(200, body)
        })
        val service = ChaoxingService(repo, account, account,
            SchoolAccountIdentityVerifier { _,_,_-> SchoolAccountIdentityCheck.MATCH },
            ChaoxingAccountAuthenticator { _,_-> ChaoxingAccountAuthResult.Failure(ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE) },
            clearCookies = { true })
        lateinit var vm: ChaoxingSeatViewModel
        suspend fun open(date: String, loaded: Boolean = true) {
            assertEquals(ChaoxingLoginResult.Success, service.loginWithWebCookies("synthetic=cookie"))
            vm = ChaoxingSeatViewModel(service,kind,store,clock)
            vm.selectDate(date)
            room(1)
            await { it.detail != null && it.hiddenLoaded && (!loaded || it.usedSeatsLoaded) }
        }
        fun room(id: Int) = vm.openRoom(ChaoxingSeatRoom(id,"合成区域", "",3,null,480,1320))
        suspend fun await(test: (ChaoxingSeatUiState)->Boolean): ChaoxingSeatUiState =
            withTimeout(6000) { vm.state.first(test) }
        suspend fun time() { vm.setSeatMode(RoomSeatMode.TIME); vm.searchSeats(); await { it.usedSeatsLoaded } }
    }

    @Test fun `malformed post envelopes stay unknown across same and new viewmodels for both protocols`() = runBlocking<Unit> {
        val bodies = listOf("{}", """{"data":{}}""", """{"msg":"synthetic"}""",
            """{"success":null}""", """{"success":{}}""", """{"success":[]}""",
            """{"success":"false","msg":"拒绝"}""", """{"success":1}""",
            """{"success":false}""", """{"success":false,"msg":[]}""",
            """{"success":false,"msg":{}}""", """{"success":true,"msg":[]}""",
            """{"success":true,"data":[]}""", """{"success":true,"data":{"seatReserve":{"seatNum":[]}}}""",
            "[]", "null", "<html>synthetic</html>", "{")
        for (kind in ChaoxingSeatKind.entries) for (body in bodies) {
            val f=Fixture(kind);f.submit={body};f.open(date);f.vm.selectSeat("001")
            f.vm.reserveSelected();f.await {it.reservationUnknown}
            assertFalse(f.vm.state.value.reservationMessage!!.contains("未提交"))
            assertEquals(1,f.posts.get());f.vm.reserveSelected();assertEquals(1,f.posts.get())
            f.vm.closeRoom();f.vm=ChaoxingSeatViewModel(f.service,kind)
            f.vm.selectDate(date);f.room(1);f.await {it.usedSeatsLoaded && it.hiddenLoaded}
            f.vm.selectSeat("001");f.vm.reserveSelected();f.await {it.reservationUnknown}
            assertEquals(1,f.posts.get())
        }
    }
    @Test fun `known success rejection and post transport failure retain their distinct outcomes`() = runBlocking<Unit> {
        for(kind in ChaoxingSeatKind.entries) {
            val ok=Fixture(kind);ok.submit={"""{"success":true,"data":{"seatReserve":{"seatNum":"001"}}}"""}
            ok.open(date);ok.vm.selectSeat("001");ok.vm.reserveSelected();ok.await {it.reservationSucceeded}
            assertFalse(ok.vm.state.value.reservationUnknown);assertEquals(1,ok.posts.get())
            val rejected=Fixture(kind);rejected.submit={"""{"success":false,"msg":"座位已被占用"}"""}
            rejected.open(date);rejected.vm.selectSeat("001")
            repeat(2) { rejected.vm.reserveSelected();rejected.await {!it.reserving && it.reservationMessage!=null} }
            assertFalse(rejected.vm.state.value.reservationUnknown);assertEquals(2,rejected.posts.get())
            val broken=Fixture(kind);broken.submit={throw IllegalStateException("synthetic post decode failure")}
            broken.open(date);broken.vm.selectSeat("001");broken.vm.reserveSelected();broken.await {it.reservationUnknown}
            assertFalse(broken.vm.state.value.reservationMessage!!.contains("未提交"));assertEquals(1,broken.posts.get())
        }
    }
    @Test fun `post cancellation propagates while service keeps the claimed key`() = runBlocking<Unit> {
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind);f.open(date);f.submit={throw CancellationException("synthetic")}
            val failure=runCatching{f.service.submitReservation(kind,1,date,480,510,"001")}.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(f.service.submitReservation(kind,1,date,480,510,"001") is ChaoxingReservationResult.Unknown)
            assertEquals(1,f.posts.get())
        }
    }
    @Test fun `final occupancy with an unknown room seat fails before posting`() = runBlocking<Unit> {
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind);f.open(date);f.vm.selectSeat("001")
            f.used={"""{"success":true,"data":{"seatReserves":[{"seatNum":"outside-room"}]}}"""}
            f.vm.reserveSelected();f.await {!it.reserving && it.reservationMessage!=null}
            assertEquals(0,f.posts.get());assertFalse(f.vm.state.value.reservationUnknown)
        }
    }
    @Test fun `duration defaults and start changes are legal in both modes and protocols`() = runBlocking<Unit> {
        val clock=Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"),CHAOXING_ZONE)
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind,clock=clock);f.minHours=1.0;f.maxHours=1.5;f.open("2026-11-09")
            assertEquals(480,f.vm.state.value.selectedStart);assertEquals(540,f.vm.state.value.selectedEnd)
            f.vm.selectStart(510);f.await {it.usedSeatsLoaded};assertEquals(570,f.vm.state.value.selectedEnd)
            f.vm.selectEnd(600);f.await {it.usedSeatsLoaded};f.vm.selectStart(540)
            f.await {it.usedSeatsLoaded};assertEquals(600,f.vm.state.value.selectedEnd)
            f.vm.selectSeat("001");f.vm.setSeatMode(RoomSeatMode.TIME)
            assertNull(f.vm.state.value.selectedSeat);assertFalse(f.vm.state.value.usedSeatsLoaded)
            f.vm.selectStart(600);assertEquals(660,f.vm.state.value.selectedEnd)
            f.vm.selectEnd(720);assertEquals(660,f.vm.state.value.selectedEnd)
        }
    }
    @Test fun `today past starts and closing window without minimum duration cannot be selected`() = runBlocking<Unit> {
        val clock=Clock.fixed(Instant.parse("2026-11-08T13:30:01Z"),CHAOXING_ZONE)
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind,clock=clock);f.minHours=1.0;f.open("2026-11-08",loaded=false)
            assertNull(f.vm.state.value.selectedStart);assertNull(f.vm.state.value.selectedEnd)
            assertNotNull(f.vm.state.value.timeUnavailableReason);assertFalse(f.vm.state.value.querying)
            assertEquals(0,f.usedCalls.get());f.vm.selectStart(480);assertNull(f.vm.state.value.selectedStart)
            f.vm.setSeatMode(RoomSeatMode.TIME);f.vm.searchSeats();assertEquals(0,f.usedCalls.get())
        }
        val early=Fixture(ChaoxingSeatKind.SEAT,clock=Clock.fixed(Instant.parse("2026-11-08T00:00:01Z"),CHAOXING_ZONE))
        early.minHours=1.0;early.open("2026-11-08")
        assertEquals(510,early.vm.state.value.selectedStart);assertEquals(570,early.vm.state.value.selectedEnd)
        early.vm.selectStart(480);assertEquals(510,early.vm.state.value.selectedStart)
    }

    @Test fun `both protocols query exact interval and expose future candidates`() = runBlocking<Unit> {
        for (kind in ChaoxingSeatKind.entries) {
            val f = Fixture(kind)
            f.used = { r ->
                assertTrue(r.url.contains("startTime=08%3A00"))
                if(kind == ChaoxingSeatKind.SEAT) { assertTrue(r.url.contains("fidEnc=")); assertFalse(r.url.contains("seatId=")) }
                else { assertTrue(r.url.contains("seatId=4258")); assertFalse(r.url.contains("fidEnc=")) }
                """{"success":true,"data":{"seatReserves":[{"seatNum":"002"}]}}"""
            }
            f.open(date); f.time()
            assertEquals(listOf("001","003"), f.vm.state.value.candidates.map { it.seatNum })
            f.vm.selectSeat("001"); assertTrue(f.vm.state.value.canConfirm())
            f.vm.selectSeat("invented"); assertEquals("001",f.vm.state.value.selectedSeat)
        }
    }
    @Test fun `both protocols retain minute precision and half open synthetic overlap boundaries`() = runBlocking<Unit> {
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind)
            f.used={ r ->
                val params=java.net.URI(r.url).rawQuery.split('&').associate {
                    it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8")
                }
                val start=parseHhmm(params.getValue("startTime"))!!
                val end=parseHhmm(params.getValue("endTime"))!!
                val busy=listOf(Triple("001",540,600),Triple("002",600,660))
                    .filter { start < it.third && end > it.second }
                    .joinToString(",") { "{\"seatNum\":\"${it.first}\"}" }
                """{"success":true,"data":{"seatReserves":[$busy]}}"""
            }
            f.open(date);f.vm.setSeatMode(RoomSeatMode.TIME)
            f.vm.selectStart(570);f.vm.selectEnd(600);f.vm.searchSeats();f.await{it.usedSeatsLoaded}
            assertEquals(setOf("002","003"),f.vm.state.value.candidates.map{it.seatNum}.toSet())
            f.vm.selectEnd(630);f.vm.searchSeats();f.await{it.usedSeatsLoaded}
            assertEquals(listOf("003"),f.vm.state.value.candidates.map{it.seatNum})
            f.vm.selectStart(600);f.vm.selectEnd(660);f.vm.searchSeats();f.await{it.usedSeatsLoaded}
            assertEquals(setOf("001","003"),f.vm.state.value.candidates.map{it.seatNum}.toSet())
        }
    }
    @Test fun `disabled missing rules and incomplete layout never become bookable`() = runBlocking<Unit> {
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind);f.attributes="""[{"seatNum":"001","isReserve":0}]""";f.open(date)
            assertFalse(f.vm.state.value.candidates.any {it.seatNum=="001"})
            f.vm.selectSeat("001");assertNull(f.vm.state.value.selectedSeat)
            f.capacity=4;f.room(2);f.await {it.detailError!=null}
            assertFalse(f.vm.state.value.usedSeatsLoaded)
            f.capacity=3;f.attributes="null";f.room(3);f.await {it.detailError!=null}
            assertFalse(f.vm.state.value.usedSeatsLoaded)
        }
    }

    @Test fun `time mode is explicit and changed conditions invalidate selection`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEAT); f.open(date)
        val n=f.usedCalls.get(); f.vm.setSeatMode(RoomSeatMode.TIME)
        assertFalse(f.vm.state.value.usedSeatsLoaded); assertEquals(n,f.usedCalls.get())
        f.vm.searchSeats(); f.await { it.usedSeatsLoaded }; f.vm.selectSeat("001")
        f.vm.selectEnd(570)
        assertNull(f.vm.state.value.selectedSeat); assertFalse(f.vm.state.value.canConfirm())
        assertEquals(n+1,f.usedCalls.get())
    }
    @Test fun `malformed occupied item is failure never all free`() = runBlocking<Unit> {
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind); f.open(date)
            f.used={ """{"success":true,"data":{"seatReserves":[{}]}}""" }
            f.vm.setSeatMode(RoomSeatMode.TIME); f.vm.searchSeats()
            f.await { it.detailError!=null }
            assertFalse(f.vm.state.value.usedSeatsLoaded); assertTrue(f.vm.state.value.candidates.isEmpty())
        }
    }
    @Test fun `late interval cannot revive selection after mode change`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEATENGINE); f.open(date)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.used={ entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); """{"success":true,"data":{"seatReserves":[]}}""" }
        f.vm.setSeatMode(RoomSeatMode.TIME);f.vm.searchSeats();assertTrue(entered.await(5,TimeUnit.SECONDS))
        f.vm.selectEnd(570);release.countDown();delay(100)
        assertFalse(f.vm.state.value.usedSeatsLoaded);assertNull(f.vm.state.value.selectedSeat)
    }
    @Test fun `old room response after close cannot reopen detail`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEAT);f.open(date)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.used={ entered.countDown();check(release.await(5,TimeUnit.SECONDS));"""{"success":true,"data":{"seatReserves":[]}}""" }
        f.vm.searchSeats();assertTrue(entered.await(5,TimeUnit.SECONDS));f.vm.closeRoom();release.countDown();delay(100)
        assertNull(f.vm.state.value.detail);assertFalse(f.vm.state.value.canConfirm())
    }
    @Test fun `account generation change invalidates old selection and pending read`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEAT);f.open(date);f.vm.selectSeat("001")
        f.account.state.value=f.account.state.value.copy(generation=AccountGeneration(4))
        f.await { it.detail==null };f.vm.reserveSelected();assertEquals(0,f.posts.get())
    }
    @Test fun `new account reference cannot adopt old cookies before cleanup completes`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEAT);f.open(date)
        f.account.accountReference.value=SchoolAccountReference(SchoolAccountId("new-synthetic"),AccountGeneration(4))
        f.account.state.value=f.account.state.value.copy(generation=AccountGeneration(4))
        assertNull(f.service.readContext())
        val before=f.usedCalls.get()
        val failure=runCatching{f.service.usedSeatNums(f.kind,1,480,510,date)}.exceptionOrNull()
        assertTrue(failure is ChaoxingSessionExpiredException);assertEquals(before,f.usedCalls.get())
        f.await {it.detail==null}
    }
    @Test fun `late old read cannot clear a new session even with identical cookie value`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEAT);f.open(date)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.used={entered.countDown();check(release.await(5,TimeUnit.SECONDS));"""{"success":true,"data":{"seatReserves":[]}}"""}
        f.vm.searchSeats();assertTrue(entered.await(5,TimeUnit.SECONDS))
        assertEquals(ChaoxingLoginResult.Success,f.service.loginWithWebCookies("synthetic=cookie"))
        val fresh=f.service.readContext();assertNotNull(fresh)
        release.countDown();delay(150)
        assertNotNull(f.service.session.value);assertTrue(f.service.isReadContextCurrent(fresh))
    }
    @Test fun `same cookie republished as new session invalidates room selection`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEAT);f.open(date);f.vm.selectSeat("001")
        val old=f.service.readContext()
        assertEquals(ChaoxingLoginResult.Success,f.service.loginWithWebCookies("synthetic=cookie"))
        assertFalse(f.service.isReadContextCurrent(old))
        f.await {it.detail==null};assertNull(f.vm.state.value.selectedSeat)
    }
    @Test fun `preflight failure and risk do not claim sent key unknown survives new vm`() = runBlocking<Unit> {
        for(kind in ChaoxingSeatKind.entries) {
            val f=Fixture(kind);f.open(date);f.vm.selectSeat("001")
            f.preflight={ throw java.io.IOException("synthetic preflight failure") }
            f.vm.reserveSelected();f.await { !it.reserving && it.reservationMessage != null }
            assertFalse(f.vm.state.value.reservationUnknown);assertEquals(0,f.posts.get())
            f.preflight={ """<input id="submit_enc" value="synthetic"><script>var riskCheckOpen=true</script>""" }
            f.vm.reserveSelected();f.await { it.interactionRequired };assertEquals(0,f.posts.get())
            f.preflight={ """<input id="submit_enc" value="synthetic">""" }
            f.vm.reserveSelected();f.await { it.reservationUnknown };assertEquals(1,f.posts.get())
            f.vm.closeRoom();f.vm=ChaoxingSeatViewModel(f.service,kind);f.vm.selectDate(date);f.room(1)
            f.await { it.usedSeatsLoaded && it.hiddenLoaded };f.vm.selectSeat("001");f.vm.reserveSelected()
            f.await { it.reservationUnknown };assertEquals(1,f.posts.get())
        }
    }
    private class HiddenStore : LibraryHiddenSeatsStore by NoOpLibraryHiddenSeatsStore {
        val saved = mutableListOf<LibraryHiddenSeat>()
        var wait: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun entries(system:String, buildingId:String, roomId:String) =
            saved.filter {it.system==system && it.buildingId==buildingId && it.roomId==roomId}
        override suspend fun hide(entry:LibraryHiddenSeat):Boolean {
            wait?.await(); if(fail) return false
            saved.removeAll {it.system==entry.system && it.buildingId==entry.buildingId && it.roomId==entry.roomId && it.seatId==entry.seatId}
            saved+=entry;return true
        }
        override suspend fun restore(system:String,buildingId:String,roomId:String,seatId:String):Boolean {
            saved.removeAll {it.system==system && it.buildingId==buildingId && it.roomId==roomId && it.seatId==seatId};return true
        }
    }
    @Test fun `hide immediately clears chosen seat and undo restores preference without reviving choice`() = runBlocking<Unit> {
        val store=HiddenStore();val f=Fixture(ChaoxingSeatKind.SEATENGINE,store);f.open(date);f.vm.selectSeat("001")
        store.wait=CompletableDeferred();f.vm.hideSeat("001")
        assertNull(f.vm.state.value.selectedSeat);assertFalse(f.vm.state.value.canConfirm())
        store.wait!!.complete(Unit);f.await { "001" in it.hiddenIds }
        assertEquals("chaoxing-seatengine",store.saved.single().system)
        f.vm.restoreHidden("001");f.await {it.hiddenIds.isEmpty() && !it.hiddenBusy}
        assertNull(f.vm.state.value.selectedSeat)
    }
    @Test fun `failed hidden persistence stays failed and never pretends saved`() = runBlocking<Unit> {
        val store=HiddenStore();val f=Fixture(ChaoxingSeatKind.SEAT,store);f.open(date);f.vm.selectSeat("001")
        store.fail=true;f.vm.hideSeat("001");f.await { it.hiddenError!=null }
        assertTrue(store.saved.isEmpty());assertNull(f.vm.state.value.undoHidden)
        assertFalse(f.vm.state.value.canConfirm());assertNull(f.vm.state.value.selectedSeat)
    }
    @Test fun `mode change or account change while preflighting prevents post`() = runBlocking<Unit> {
        for(changeAccount in listOf(false,true)) {
            val f=Fixture(ChaoxingSeatKind.SEAT);f.open(date);f.vm.selectSeat("001")
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.preflight={entered.countDown();check(release.await(5,TimeUnit.SECONDS));"""<input id="submit_enc" value="synthetic">"""}
            f.vm.reserveSelected();assertTrue(entered.await(5,TimeUnit.SECONDS))
            if(changeAccount) f.account.state.value=f.account.state.value.copy(generation=AccountGeneration(4))
            else f.vm.setSeatMode(RoomSeatMode.TIME)
            release.countDown();delay(120)
            assertEquals(0,f.posts.get());assertNull(f.vm.state.value.selectedSeat);assertFalse(f.vm.state.value.reservationUnknown)
        }
    }

    @Test fun `final refresh rejects changed occupancy without post`() = runBlocking<Unit> {
        val f=Fixture(ChaoxingSeatKind.SEAT);f.open(date);f.vm.selectSeat("001")
        f.used={ """{"success":true,"data":{"seatReserves":[{"seatNum":"001"}]}}""" }
        f.vm.reserveSelected();f.await { it.reservationMessage!=null }
        assertEquals(0,f.posts.get());assertNull(f.vm.state.value.selectedSeat)
    }
}
