package com.upcpocket.app.data.classroom

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FreeClassroomCampusStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(temp.newFolder(), "classroom_prefs.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `only the last campus is stored and a new store instance reads it back`() = runBlocking {
        val store = DataStoreFreeClassroomCampusStore(dataStore)
        assertNull(store.read())

        store.write(FreeClassroomCampus.GUZHENKOU)
        assertEquals(FreeClassroomCampus.GUZHENKOU, store.read())

        // 新实例模拟下次进入/重启后重新读取同一份偏好。
        val reopened = DataStoreFreeClassroomCampusStore(dataStore)
        assertEquals(FreeClassroomCampus.GUZHENKOU, reopened.read())
    }

    @Test
    fun `a later campus replaces the saved one`() = runBlocking {
        val store = DataStoreFreeClassroomCampusStore(dataStore)
        store.write(FreeClassroomCampus.GUZHENKOU)
        store.write(FreeClassroomCampus.TANGDAOWAN)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, store.read())
    }

    @Test
    fun `unknown persisted value is treated as no saved campus`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("last_campus")] = "REMOVED_CAMPUS"
        }
        val store = DataStoreFreeClassroomCampusStore(dataStore)
        assertNull(store.read())
    }
}
