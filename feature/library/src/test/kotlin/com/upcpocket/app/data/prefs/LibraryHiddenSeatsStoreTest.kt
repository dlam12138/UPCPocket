package com.upcpocket.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.upcpocket.app.data.library.LibraryHiddenSeat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    // ── 原子 edit：并发 hide/hide、hide/restore 不丢更新 ──

    @Test
    fun `并发隐藏两个座位都保留不互相覆盖`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        val a = entry("r1", "s1", "A001")
        val b = entry("r1", "s2", "A002")
        val results = awaitAll(
            async(Dispatchers.IO) { store.hide(a) },
            async(Dispatchers.IO) { store.hide(b) },
        )
        assertTrue(results.all { it })
        assertEquals(setOf("s1", "s2"), store.hiddenIds("1", "r1"))
        assertEquals(2, store.count("1", "r1"))
    }

    @Test
    fun `并发多次隐藏全部保留`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        val results = (1..8).map { n ->
            async(Dispatchers.IO) { store.hide(entry("r1", "s$n", "A%03d".format(n))) }
        }.awaitAll()
        assertTrue(results.all { it })
        assertEquals(8, store.count("1", "r1"))
    }

    @Test
    fun `hide 与 restore 交错不丢另一项`() = runBlocking {
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        store.hide(entry("r1", "keep", "KEEP"))
        val results = awaitAll(
            async(Dispatchers.IO) { store.hide(entry("r1", "s2", "A002")) },
            async(Dispatchers.IO) { store.restore("1", "r1", "keep") },
        )
        assertTrue(results.all { it })
        val ids = store.hiddenIds("1", "r1")
        // keep 可能被恢复（若 restore 后跑）或仍隐藏（若先跑），但 s2 必须存在，
        // 且绝不能出现「只剩空」的覆盖丢失。
        assertTrue("s2" in ids)
    }

    @Test
    fun `损坏 JSON 读取抛异常不返回假空列表`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("hidden_seats_v1")] =
                "NOT_VALID_JSON{{"
        }
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        val readError = runCatching { store.entries("1", "r1") }.exceptionOrNull()
        assertTrue(readError != null)
        // 写入在原子 edit 内解码失败 -> 返回 false，不假成功、不覆盖坏数据成新列表。
        assertFalse(store.hide(entry("r1", "s1")))
    }

    @Test
    fun `读失败后写不会把坏数据覆盖成假空`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("hidden_seats_v1")] = "BROKEN"
        }
        val store = DataStoreLibraryHiddenSeatsStore(dataStore)
        assertFalse(store.hide(entry("r1", "s1")))
        assertFalse(store.restore("1", "r1", "x"))
        assertFalse(store.restoreAll("1", "r1"))
        // 原值仍是损坏的，没被写操作悄悄替换。
        val raw = dataStore.data.first()[
            androidx.datastore.preferences.core.stringPreferencesKey("hidden_seats_v1")
        ]
        assertEquals("BROKEN", raw)
    }
}
