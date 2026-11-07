package com.upcpocket.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.upcpocket.app.data.library.LibraryHiddenSeat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 本机个人隐藏的命名空间、跨重启持久化、撤销/恢复边界。
 *
 * 这是设备本地偏好：退出/学校换号不应误删（store 不含账号），不同房间同
 * seatId 不串联，恢复只解除个人隐藏。
 */
class LibraryHiddenSeatsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>

    private fun entry(roomId: String, seatId: String, name: String = "A$seatId") =
        LibraryHiddenSeat(
            system = LibraryHiddenSeat.SYSTEM_LIBSEAT,
            buildingId = "1",
            roomId = roomId,
            seatId = seatId,
            nameSnapshot = name,
        )

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(temp.newFolder(), "library_hidden_seats.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `hide 后跨新实例重开仍读到同一项`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        assertTrue(store.hiddenIds("1", "r1").isEmpty())
        assertTrue(store.hide(entry("r1", "s1", "A001")))

        val reopened = DataStoreLibraryHiddenSeatsStore(dataStore)
        assertEquals(setOf("s1"), reopened.hiddenIds("1", "r1"))
        assertEquals("A001", reopened.entries("1", "r1").single().nameSnapshot)
        assertEquals(1, reopened.count("1", "r1"))
    }

    @Test
    fun `不同房间同 seatId 不串联`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        store.hide(entry("r1", "s1", "A001"))
        assertTrue(store.hiddenIds("1", "r1").contains("s1"))
        assertTrue(store.hiddenIds("1", "r2").isEmpty())

        store.hide(entry("r2", "s1", "B001"))
        assertEquals(setOf("s1"), store.hiddenIds("1", "r1"))
        assertEquals(setOf("s1"), store.hiddenIds("1", "r2"))
        assertEquals(1, store.count("1", "r1"))
        assertEquals(1, store.count("1", "r2"))
    }

    @Test
    fun `不同馆区同房间同 seatId 不串联`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        store.hide(entry("r1", "s1").copy(buildingId = "1"))
        assertTrue(store.hiddenIds("2", "r1").isEmpty())
    }

    @Test
    fun `逐个恢复只影响目标房间`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        store.hide(entry("r1", "s1"))
        store.hide(entry("r1", "s2"))
        store.hide(entry("r2", "s1"))
        assertTrue(store.restore("1", "r1", "s1"))

        assertEquals(setOf("s2"), store.hiddenIds("1", "r1"))
        assertEquals(setOf("s1"), store.hiddenIds("1", "r2"))
    }

    @Test
    fun `全部恢复清空当前房间并保留其它房间`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        store.hide(entry("r1", "s1"))
        store.hide(entry("r1", "s2"))
        store.hide(entry("r2", "s1"))
        assertTrue(store.restoreAll("1", "r1"))

        assertTrue(store.hiddenIds("1", "r1").isEmpty())
        assertEquals(setOf("s1"), store.hiddenIds("1", "r2"))
    }

    @Test
    fun `undo 路径恢复后同名再隐藏不会重复计数`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        val e = entry("r1", "s1", "A001")
        store.hide(e)
        store.restore(e.buildingId, e.roomId, e.seatId)
        assertTrue(store.hiddenIds("1", "r1").isEmpty())
        // 撤销后再次隐藏同一座位，仍是单项。
        store.hide(e.copy(nameSnapshot = "A001-new"))
        assertEquals(1, store.count("1", "r1"))
        assertEquals("A001-new", store.entries("1", "r1").single().nameSnapshot)
    }

    @Test
    fun `空实现不持久化用于独立合成预览`() = runBlocking {
        val store = NoOpLibraryHiddenSeatsStore
        assertTrue(store.hide(entry("r1", "s1")))
        assertTrue(store.hiddenIds("1", "r1").isEmpty())
        assertEquals(0, store.count("1", "r1"))
        assertFalse(store.entries("1", "r1").isNotEmpty())
    }
}
