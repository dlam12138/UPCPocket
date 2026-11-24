package com.upcpocket.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_THEME_SEED_ARGB
import com.upcpocket.app.designsystem.MAX_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.MIN_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.timetable.TimetableDisplayMode
import com.upcpocket.app.timetable.TimetableModuleId
import androidx.datastore.preferences.core.floatPreferencesKey
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UiPrefsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temp.newFolder(), "ui_prefs.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `unset pins use the four defaults`() = runBlocking {
        assertEquals(UiPrefs.DEFAULT_PINNED_SERVICES, UiPrefsStore(dataStore).flow.first().pinnedServiceIds)
    }

    @Test
    fun `explicitly unpinning every default stays empty`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        UiPrefs.DEFAULT_PINNED_SERVICES.forEach { store.togglePinnedService(it) }

        assertEquals(emptySet<String>(), UiPrefsStore(dataStore).flow.first().pinnedServiceIds)
    }

    @Test
    fun `preferred campus defaults to all when unset or unknown`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        assertEquals(PreferredCampus.ALL, store.flow.first().preferredCampus)

        dataStore.edit { it[stringPreferencesKey("preferred_campus")] = "SYNTHETIC_CAMPUS" }
        assertEquals(PreferredCampus.ALL, UiPrefsStore(dataStore).flow.first().preferredCampus)

        store.setPreferredCampus(PreferredCampus.GUZHENKOU)
        assertEquals(PreferredCampus.GUZHENKOU, UiPrefsStore(dataStore).flow.first().preferredCampus)
    }

    @Test
    fun `todo host placement defaults to timetable when unset or unknown`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        assertEquals(TodoHostPlacement.TIMETABLE, store.flow.first().todoHostPlacement)

        dataStore.edit { it[stringPreferencesKey("todo_host_placement")] = "SYNTHETIC_HOST" }
        assertEquals(
            TodoHostPlacement.TIMETABLE,
            UiPrefsStore(dataStore).flow.first().todoHostPlacement,
        )

        store.setTodoHostPlacement(TodoHostPlacement.FOCUS)
        assertEquals(
            TodoHostPlacement.FOCUS,
            UiPrefsStore(dataStore).flow.first().todoHostPlacement,
        )

        store.setTodoHostPlacement(TodoHostPlacement.TIMETABLE)
        assertEquals(
            TodoHostPlacement.TIMETABLE,
            UiPrefsStore(dataStore).flow.first().todoHostPlacement,
        )
    }

    @Test
    fun `timetable display mode defaults to all when unset or unknown and persists`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        assertEquals(TimetableDisplayMode.ALL, store.flow.first().timetableDisplayMode)

        dataStore.edit { it[stringPreferencesKey("timetable_display_mode")] = "SYNTHETIC_MODE" }
        assertEquals(
            TimetableDisplayMode.ALL,
            UiPrefsStore(dataStore).flow.first().timetableDisplayMode,
        )

        store.setTimetableDisplayMode(TimetableDisplayMode.COURSES_ONLY)
        assertEquals(
            TimetableDisplayMode.COURSES_ONLY,
            UiPrefsStore(dataStore).flow.first().timetableDisplayMode,
        )

        store.setTimetableDisplayMode(TimetableDisplayMode.ALL)
        assertEquals(
            TimetableDisplayMode.ALL,
            UiPrefsStore(dataStore).flow.first().timetableDisplayMode,
        )
    }

    @Test
    fun `widget appearance defaults to solid and persists independently`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        assertEquals(WidgetAppearance.SOLID, store.flow.first().widgetAppearance)

        store.setWidgetAppearance(WidgetAppearance.GLASS)

        assertEquals(WidgetAppearance.GLASS, store.widgetAppearanceNow())
        assertEquals(WidgetAppearance.GLASS, UiPrefsStore(dataStore).flow.first().widgetAppearance)
    }

    @Test
    fun `module orders default cleanly, normalize unknown entries, and persist`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        assertEquals(HomeModuleId.DEFAULT_ORDER, store.flow.first().homeModuleOrder)
        assertEquals(TimetableModuleId.DEFAULT_ORDER, store.flow.first().timetableModuleOrder)

        dataStore.edit {
            it[stringPreferencesKey("home_module_order")] = "CARD,UNKNOWN,CARD,SERVICES"
            it[stringPreferencesKey("timetable_module_order")] = "UNKNOWN,TIMETABLE"
        }
        assertEquals(
            listOf(HomeModuleId.CARD, HomeModuleId.SERVICES, HomeModuleId.TIMETABLE, HomeModuleId.TODO),
            UiPrefsStore(dataStore).flow.first().homeModuleOrder,
        )
        assertEquals(
            listOf(TimetableModuleId.TIMETABLE, TimetableModuleId.TODO_SCHEDULE),
            UiPrefsStore(dataStore).flow.first().timetableModuleOrder,
        )

        val customHome = listOf(HomeModuleId.TODO, HomeModuleId.CARD, HomeModuleId.TIMETABLE, HomeModuleId.SERVICES)
        val customTimetable = listOf(TimetableModuleId.TIMETABLE, TimetableModuleId.TODO_SCHEDULE)
        // 排序写回只在拖动开关开启时生效。
        store.setModuleReorderEnabled(true)
        store.setHomeModuleOrder(customHome)
        store.setTimetableModuleOrder(customTimetable)

        val reloaded = UiPrefsStore(dataStore).flow.first()
        assertEquals(customHome, reloaded.homeModuleOrder)
        assertEquals(customTimetable, reloaded.timetableModuleOrder)
    }

    @Test
    fun `module reorder defaults off, gates order writes, and keeps saved orders when disabled`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        assertFalse(store.flow.first().moduleReorderEnabled)

        val customHome = listOf(
            HomeModuleId.CARD,
            HomeModuleId.TODO,
            HomeModuleId.SERVICES,
            HomeModuleId.TIMETABLE,
        )
        val customTimetable = listOf(
            TimetableModuleId.TIMETABLE,
            TimetableModuleId.TODO_SCHEDULE,
        )

        // 缺键即关闭：迟到的拖动回调不写顺序，两页继续使用默认顺序。
        store.setHomeModuleOrder(customHome)
        store.setTimetableModuleOrder(customTimetable)
        val disabled = store.flow.first()
        assertEquals(HomeModuleId.DEFAULT_ORDER, disabled.homeModuleOrder)
        assertEquals(TimetableModuleId.DEFAULT_ORDER, disabled.timetableModuleOrder)

        store.setModuleReorderEnabled(true)
        assertTrue(store.flow.first().moduleReorderEnabled)
        store.setHomeModuleOrder(customHome)
        store.setTimetableModuleOrder(customTimetable)
        val enabled = store.flow.first()
        assertEquals(customHome, enabled.homeModuleOrder)
        assertEquals(customTimetable, enabled.timetableModuleOrder)

        // 关闭后保留已保存顺序，重新开启可继续使用。
        store.setModuleReorderEnabled(false)
        val off = store.flow.first()
        assertFalse(off.moduleReorderEnabled)
        assertEquals(customHome, off.homeModuleOrder)
        assertEquals(customTimetable, off.timetableModuleOrder)
    }

    @Test
    fun `home anniversary defaults off and tolerates missing or unavailable stored values`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        val initial = store.flow.first().homeAnniversary
        assertEquals("", initial.title)
        assertNull(initial.date)
        assertEquals(HomeAnniversaryMode.COUNT_DOWN, initial.mode)
        assertFalse(initial.visible)

        val date = LocalDate.of(2026, 11, 24)
        dataStore.edit {
            it[stringPreferencesKey("home_anniversary_title")] = "校庆"
            it[longPreferencesKey("home_anniversary_epoch_day")] = date.toEpochDay()
            it[stringPreferencesKey("home_anniversary_mode")] = "SYNTHETIC_MODE"
            it[booleanPreferencesKey("home_anniversary_visible")] = true
        }
        val unknownMode = UiPrefsStore(dataStore).flow.first().homeAnniversary
        assertEquals("校庆", unknownMode.title)
        assertEquals(date, unknownMode.date)
        assertEquals(HomeAnniversaryMode.COUNT_DOWN, unknownMode.mode)
        assertTrue(unknownMode.visible)

        // 历史脏数据或损坏的 epoch day 不能阻止首页读取，按“没有可用日期”处理。
        dataStore.edit {
            it[longPreferencesKey("home_anniversary_epoch_day")] = Long.MAX_VALUE
        }
        val unavailableDate = UiPrefsStore(dataStore).flow.first().homeAnniversary
        assertNull(unavailableDate.date)
        assertEquals("校庆", unavailableDate.title)
    }

    @Test
    fun `home anniversary saves all fields once and hiding keeps the saved values`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        val date = LocalDate.of(2026, 9, 1)
        store.saveHomeAnniversary(
            HomeAnniversarySettings(
                title = "  入学纪念日  ",
                date = date,
                mode = HomeAnniversaryMode.COUNT_UP,
                visible = true,
            ),
        )

        val shown = UiPrefsStore(dataStore).flow.first().homeAnniversary
        assertEquals("入学纪念日", shown.title)
        assertEquals(date, shown.date)
        assertEquals(HomeAnniversaryMode.COUNT_UP, shown.mode)
        assertTrue(shown.visible)

        store.saveHomeAnniversary(shown.copy(visible = false))
        val hidden = UiPrefsStore(dataStore).flow.first().homeAnniversary
        assertFalse(hidden.visible)
        assertEquals("入学纪念日", hidden.title)
        assertEquals(date, hidden.date)
        assertEquals(HomeAnniversaryMode.COUNT_UP, hidden.mode)

        // 缺标题不写，避免未完成表单覆盖已保存内容。
        store.saveHomeAnniversary(
            HomeAnniversarySettings(
                title = "   ",
                date = date.plusDays(1),
                mode = HomeAnniversaryMode.COUNT_DOWN,
                visible = true,
            ),
        )
        assertEquals(hidden, UiPrefsStore(dataStore).flow.first().homeAnniversary)
    }

    @Test
    fun `custom theme seed and custom background opacity default, clamp, and persist`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        val initial = store.flow.first()
        assertEquals(DEFAULT_CUSTOM_THEME_SEED_ARGB, initial.customThemeSeedArgb)
        assertEquals(DEFAULT_CUSTOM_BACKGROUND_OPACITY, initial.customBackgroundOpacity, 0.0001f)

        store.setCustomThemeSeed(0x00D81B60)
        val afterSeed = store.flow.first()
        assertEquals(ThemePreset.CUSTOM, afterSeed.themePreset)
        assertEquals(0xFFD81B60.toInt(), afterSeed.customThemeSeedArgb)

        store.setCustomBackgroundOpacity(0.75f)
        assertEquals(0.75f, store.flow.first().customBackgroundOpacity, 0.0001f)

        dataStore.edit { it[floatPreferencesKey("custom_background_opacity")] = 9.9f }
        assertEquals(MAX_CUSTOM_BACKGROUND_OPACITY, store.flow.first().customBackgroundOpacity, 0.0001f)

        store.setCustomBackgroundOpacity(0.01f)
        assertEquals(MIN_CUSTOM_BACKGROUND_OPACITY, store.flow.first().customBackgroundOpacity, 0.0001f)
    }
}
