package com.upcpocket.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_THEME_SEED_ARGB
import com.upcpocket.app.designsystem.MAX_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.MIN_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.timetable.TimetableModuleId
import androidx.datastore.preferences.core.floatPreferencesKey
import java.io.File
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
        store.setHomeModuleOrder(customHome)
        store.setTimetableModuleOrder(customTimetable)

        val reloaded = UiPrefsStore(dataStore).flow.first()
        assertEquals(customHome, reloaded.homeModuleOrder)
        assertEquals(customTimetable, reloaded.timetableModuleOrder)
    }

    @Test
    fun `custom theme seed and custom background preferences default, clamp, and persist`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        val initial = store.flow.first()
        assertEquals(DEFAULT_CUSTOM_THEME_SEED_ARGB, initial.customThemeSeedArgb)
        assertEquals(0L, initial.customBackgroundVersion)
        assertFalse(initial.hasCustomBackground)
        assertEquals(DEFAULT_CUSTOM_BACKGROUND_OPACITY, initial.customBackgroundOpacity, 0.0001f)

        store.setCustomThemeSeed(0x00D81B60)
        val afterSeed = store.flow.first()
        assertEquals(ThemePreset.CUSTOM, afterSeed.themePreset)
        assertEquals(0xFFD81B60.toInt(), afterSeed.customThemeSeedArgb)

        store.setCustomBackgroundVersion(42L)
        store.setCustomBackgroundOpacity(0.75f)
        val afterBg = store.flow.first()
        assertTrue(afterBg.hasCustomBackground)
        assertEquals(42L, afterBg.customBackgroundVersion)
        assertEquals(0.75f, afterBg.customBackgroundOpacity, 0.0001f)

        dataStore.edit { it[floatPreferencesKey("custom_background_opacity")] = 9.9f }
        assertEquals(MAX_CUSTOM_BACKGROUND_OPACITY, store.flow.first().customBackgroundOpacity, 0.0001f)

        store.setCustomBackgroundOpacity(0.01f)
        assertEquals(MIN_CUSTOM_BACKGROUND_OPACITY, store.flow.first().customBackgroundOpacity, 0.0001f)

        store.setCustomBackgroundVersion(0L)
        val cleared = store.flow.first()
        assertFalse(cleared.hasCustomBackground)
        assertEquals(0L, cleared.customBackgroundVersion)
    }
}
