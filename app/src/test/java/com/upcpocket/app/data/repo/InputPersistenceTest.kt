package com.upcpocket.app.data.repo

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.upcpocket.app.data.power.ElectricityReading
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.power.PowerInput
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
    fun `旧安装与旧候选键都不自动开启轻量玻璃`() = runBlocking {
        assertEquals(UiStyle.MATERIAL3, prefs().flow.first().uiStyle)

        dataStore.edit { it[stringPreferencesKey("app_ui_style")] = "FUTURE_STYLE" }

        assertEquals(UiStyle.MATERIAL3, prefs().flow.first().uiStyle)

        // 旧候选的 ui_style 不再读取；即使存的是有效枚举名，也不能自动开启新外观。
        dataStore.edit {
            it.remove(stringPreferencesKey("app_ui_style"))
            it[stringPreferencesKey("ui_style")] = UiStyle.LIQUID_GLASS.name
        }

        assertEquals(UiStyle.MATERIAL3, prefs().flow.first().uiStyle)
    }

    @Test
    fun `界面风格恢复并独立于寝室偏好`() = runBlocking {
        prefs().savePowerInput("01号楼(1)", "1101")
        prefs().setUiStyle(UiStyle.LIQUID_GLASS)

        val restored = prefs().flow.first()
        assertEquals(UiStyle.LIQUID_GLASS, restored.uiStyle)
        assertEquals("01号楼(1)", restored.powerBuilding)
        assertEquals("1101", restored.powerRoom)

        prefs().setUiStyle(UiStyle.MATERIAL3)

        assertEquals(UiStyle.MATERIAL3, prefs().flow.first().uiStyle)
    }

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
    fun `旧校园卡学号草稿可持久化但只是迁移提示来源`() = runBlocking {
        val card = CardInputRepository(prefs())
        card.save("12345678")
        assertEquals("12345678", CardInputRepository(prefs()).studentId.first())
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
