package com.upcpocket.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.upcpocket.app.designsystem.WidgetAppearance
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `widget appearance defaults to solid and persists independently`() = runBlocking {
        val store = UiPrefsStore(dataStore)
        assertEquals(WidgetAppearance.SOLID, store.flow.first().widgetAppearance)

        store.setWidgetAppearance(WidgetAppearance.GLASS)

        assertEquals(WidgetAppearance.GLASS, store.widgetAppearanceNow())
        assertEquals(WidgetAppearance.GLASS, UiPrefsStore(dataStore).flow.first().widgetAppearance)
    }
}
