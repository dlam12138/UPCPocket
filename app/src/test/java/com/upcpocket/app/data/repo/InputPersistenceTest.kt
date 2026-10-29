package com.upcpocket.app.data.repo

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.upcpocket.app.data.ecard.CampusCardException
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.data.power.ElectricityReading
import com.upcpocket.app.data.prefs.UiPrefsStore
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 输入持久化与归属测试。
 *
 * 全部使用虚构的楼号/房号/学号，不访问网络；电费“查询失败不丢输入”用余额仓库的
 * 失败路径与同一个持久层模拟，避免向学校提交任何虚构查询。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class InputPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temp.newFolder(), "user_inputs.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 每次新建 UiPrefsStore 视为“重启后重新读同一份持久配置”。 */
    private fun prefs() = UiPrefsStore(dataStore)

    @Test
    fun `楼号与房间号一起保存_重启后恢复`() = runBlocking {
        PowerInputRepository(prefs()).save("01号楼(1)", "1101")
        assertEquals(
            PowerInput(building = "01号楼(1)", room = "1101"),
            PowerInputRepository(prefs()).input.first(),
        )
    }

    @Test
    fun `清除电费输入后_重启不再回填`() = runBlocking {
        val repo = PowerInputRepository(prefs())
        repo.save("01号楼(1)", "1101")
        repo.clear()
        assertEquals(PowerInput(), PowerInputRepository(prefs()).input.first())
    }

    @Test
    fun `校园卡学号独立于余额快照_查询失败也不丢`() = runBlocking {
        val card = CardInputRepository(prefs())
        card.save("12345678")

        // 另存并清除一份余额快照，模拟“从未查询成功/失败后重进”。
        val snapshotStore = DirectCardBalanceStore(File(temp.newFolder(), "card-balance-v1.json"))
        snapshotStore.save(DirectCardSnapshot("12345678", 1234, 0, 1))
        snapshotStore.clear()

        val failing = DirectCardBalanceRepository(
            DirectCardBalanceStore(File(temp.newFolder(), "card-balance-v1.json")),
        ) { throw CampusCardException("网络失败") }
        try {
            failing.refresh("12345678")
            fail("预期查询失败")
        } catch (_: CampusCardException) {
            // 预期
        }

        assertEquals("12345678", CardInputRepository(prefs()).studentId.first())
    }

    @Test
    fun `清除学号后_重启不再回填`() = runBlocking {
        val card = CardInputRepository(prefs())
        card.save("12345678")
        card.clear()
        assertEquals("", CardInputRepository(prefs()).studentId.first())
    }

    @Test
    fun `旧电量结果只匹配原楼号与房间号`() {
        val reading = ElectricityReading(
            building = "01号楼(1)",
            room = "1101",
            systemNo = "0022250003",
            kwh = BigDecimal("17.34"),
        )
        assertTrue(reading.matches("01号楼(1)", " 1101 "))
        assertFalse(reading.matches("02号楼(2)", "1101"))
        assertFalse(reading.matches("01号楼(1)", "1102"))
    }
}
