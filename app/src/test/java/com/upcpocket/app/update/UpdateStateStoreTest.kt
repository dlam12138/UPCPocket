package com.upcpocket.app.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateStateStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): UpdateStateStore =
        UpdateStateStore(File(temporaryFolder.newFolder(), "state.json"))

    @Test
    fun roundTripsAllPreferencesAndHistory() {
        val store = store()
        store.write(
            UpdateState(
                highestVerifiedVersionCode = 23,
                lastAutoCheckEpochDay = 20_700,
                lastSuccessfulSourceId = "gh-proxy.org",
                autoCheckEnabled = false,
                dismissedVersionCode = 22,
            ),
        )
        val state = store.read()
        assertEquals(23, state.highestVerifiedVersionCode)
        assertEquals(20_700L, state.lastAutoCheckEpochDay)
        assertEquals("gh-proxy.org", state.lastSuccessfulSourceId)
        assertEquals(false, state.autoCheckEnabled)
        assertEquals(22, state.dismissedVersionCode)
    }

    @Test
    fun corruptFileFallsBackToSafeDefaultsWithoutCrash() {
        val file = File(temporaryFolder.newFolder(), "state.json")
        file.writeText("{not-json", Charsets.UTF_8)
        val state = UpdateStateStore(file).read()
        assertEquals(0, state.highestVerifiedVersionCode)
        assertEquals(true, state.autoCheckEnabled)
        assertNull(state.lastSuccessfulSourceId)
    }

    @Test
    fun updateIsAtomicAndLeavesNoTempFile() {
        val folder = temporaryFolder.newFolder()
        val file = File(folder, "state.json")
        val store = UpdateStateStore(file)
        store.write(UpdateState(highestVerifiedVersionCode = 1))
        store.update { it.copy(highestVerifiedVersionCode = 2) }
        assertTrue(file.isFile)
        assertEquals(2, store.read().highestVerifiedVersionCode)
        assertTrue(folder.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun sanitizesInvalidSourceIdAndNegativeValues() {
        val file = File(temporaryFolder.newFolder(), "state.json")
        file.writeText(
            """{"stateVersion":1,"highestVerifiedVersionCode":-9,"lastAutoCheckEpochDay":-2,"lastSuccessfulSourceId":"https://evil.example/?q=1","autoCheckEnabled":false,"dismissedVersionCode":-1}""",
            Charsets.UTF_8,
        )
        val state = UpdateStateStore(file).read()
        assertEquals(0, state.highestVerifiedVersionCode)
        assertEquals(0L, state.lastAutoCheckEpochDay)
        assertEquals(0, state.dismissedVersionCode)
        assertEquals(null, state.lastSuccessfulSourceId)
        assertEquals(false, state.autoCheckEnabled)
    }
}
