package com.upcpocket.app.data.power

import com.upcpocket.app.data.RefreshInvalidatedException
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PowerSnapshotTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val success = ElectricityResult.Success(
        ElectricityReading(
            building = "01号楼(1)",
            room = "1101",
            systemNo = "0022250003",
            kwh = BigDecimal("17.34"),
        ),
    )

    private fun store(file: File = File(temp.newFolder(), "power-reading-v1.json")) =
        PowerSnapshotStore(file)

    @Test
    fun `successful reading survives restart and keeps its own target`() = runBlocking {
        val cache = store()
        val repo = PowerReadingRepository(cache) { _, _ -> success }
        repo.query("01号楼(1)", "1101")

        val restarted = PowerReadingRepository(cache) { _, _ -> error("no network") }
        val saved = restarted.cached()
        assertEquals("01号楼(1)", saved?.building)
        assertEquals("1101", saved?.room)
        assertEquals("17.34", saved?.kwh)
        assertTrue(saved!!.matches("01号楼(1)", "1101"))
    }

    @Test
    fun `ambiguous wrong-room and failures never overwrite the last success`() = runBlocking {
        val cache = store()
        val originalRepo = PowerReadingRepository(cache) { _, _ -> success }
        originalRepo.query("01号楼(1)", "1101")
        val original = originalRepo.cached()

        val ambiguous = PowerReadingRepository(cache) {
                _, _ -> ElectricityResult.Ambiguous(
                    listOf(ElectricityProtocol.Candidate("1", "01号楼(1)", "1101")),
                )
        }
        ambiguous.query("01号楼(1)", "1101")
        assertEquals(original, ambiguous.cached())

        val wrongRoom = PowerReadingRepository(cache) { _, _ ->
            ElectricityResult.Success(
                ElectricityReading("01号楼(1)", "1102", "002", BigDecimal("9.99")),
            )
        }
        try {
            wrongRoom.query("01号楼(1)", "1101")
            fail("wrong room must not be treated as success")
        } catch (_: ElectricityException) {
            // expected
        }
        assertEquals(original, wrongRoom.cached())

        val offline = PowerReadingRepository(cache) { _, _ ->
            throw ElectricityException("offline fixture")
        }
        try {
            offline.query("01号楼(1)", "1101")
            fail("offline must fail")
        } catch (_: ElectricityException) {
            // expected
        }
        assertEquals(original, offline.cached())
    }

    @Test
    fun `clear removes target and value for widget`() = runBlocking {
        val cache = store()
        val repo = PowerReadingRepository(cache) { _, _ -> success }
        repo.query("01号楼(1)", "1101")
        repo.clear()
        assertNull(repo.cached())
        assertNull(PowerReadingRepository(cache) { _, _ -> success }.cached())
    }

    @Test
    fun `late success after clear cannot revive the snapshot`() = runBlocking {
        val cache = store()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = PowerReadingRepository(cache) { _, _ ->
            started.complete(Unit)
            gate.await()
            success
        }
        supervisorScope {
            val request = async(Dispatchers.Default) { repo.query("01号楼(1)", "1101") }
            started.await()
            repo.clear()
            gate.complete(Unit)
            try {
                request.await()
                fail("late result must be discarded after clear")
            } catch (_: RefreshInvalidatedException) {
                // expected
            }
        }
        assertNull(repo.cached())
    }

    @Test
    fun `widget refresh only queries the current successful target`() = runBlocking {
        val cache = store()
        val requested = mutableListOf<Pair<String, String>>()
        val repo = PowerReadingRepository(cache) { building, room ->
            requested += building to room
            success
        }
        repo.query("01号楼(1)", "1101")
        repo.refreshCurrent()
        assertEquals(
            listOf("01号楼(1)" to "1101", "01号楼(1)" to "1101"),
            requested,
        )

        repo.clear()
        assertNull(repo.refreshCurrent())
        assertEquals(2, requested.size)
    }

    @Test
    fun `corrupted snapshot is null instead of crashing or fabricated zero`() {
        val file = File(temp.newFolder(), "power-reading-v1.json")
        file.writeText("not-json-and-not-a-reading")
        val loaded = PowerSnapshotStore(file).load()
        assertNull(loaded)
    }
}
