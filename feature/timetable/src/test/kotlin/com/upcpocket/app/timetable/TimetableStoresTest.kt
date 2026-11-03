package com.upcpocket.app.timetable

import com.upcpocket.app.platform.crypto.ByteCrypto
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimetableStoresTest {

    private val directory = java.nio.file.Files.createTempDirectory("timetable-store").toFile()
    private val crypto = FakeCrypto()

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun sessionRoundTripIsEncryptedAndBoundToAccountAndGeneration() {
        val store = TimetableSessionStore(directory, crypto)
        val session = session(account = "acct_synthetic_1", generation = 3)
        assertTrue(store.save(session))
        val raw = File(directory, TimetableStorage.SESSION_FILE).readText()
        assertFalse(raw.contains("synthetic-session-value"))
        assertFalse(raw.contains("acct_synthetic_1"))

        val loaded = store.load("acct_synthetic_1", 3)!!
        assertEquals(session.cookies, loaded.cookies)
        assertEquals(session.credentialGeneration, loaded.credentialGeneration)
        assertNull(store.load("acct_synthetic_1", 4))
        assertNull(store.load("acct_synthetic_2", 3))
    }

    @Test
    fun corruptedOrWrongCiphertextSessionIsUnavailable() {
        val store = TimetableSessionStore(directory, crypto)
        assertTrue(store.save(session("acct_synthetic_1", 1)))
        val file = File(directory, TimetableStorage.SESSION_FILE)
        file.writeText("not-json")
        assertNull(store.load("acct_synthetic_1", 1))

        assertTrue(store.save(session("acct_synthetic_1", 1)))
        val other = TimetableSessionStore(directory, FakeCrypto(seed = 9))
        assertNull(other.load("acct_synthetic_1", 1))
    }

    @Test
    fun clearRemovesBaseAndBackupNames() {
        val store = TimetableSessionStore(directory, crypto)
        assertTrue(store.save(session("acct_synthetic_1", 1)))
        File(directory, "${TimetableStorage.SESSION_FILE}.bak").writeText("synthetic")
        assertTrue(store.clear())
        assertFalse(File(directory, TimetableStorage.SESSION_FILE).exists())
        assertFalse(File(directory, "${TimetableStorage.SESSION_FILE}.bak").exists())
        assertFalse(File(directory, "${TimetableStorage.SESSION_FILE}.new").exists())
    }

    @Test
    fun cacheIsAccountScopedAndKeepsSuccessfulWeeks() {
        val store = TimetableCacheStore(directory)
        val index = index()
        val week = week(index.terms.first(), 8, includeMeeting = true)
        assertTrue(store.save("acct_synthetic_1", index, week, 1_000))
        val loaded = store.load("acct_synthetic_1")
        assertEquals(index, loaded.index)
        assertEquals(1, loaded.weeks.size)
        assertEquals("示例课程A", loaded.week(index.terms.first(), 8)!!.meetings.first().courseName)

        assertEquals(null, store.load("acct_synthetic_2").index)
        assertTrue(store.load("acct_synthetic_2").weeks.isEmpty())
    }

    @Test
    fun emptySuccessfulWeekIsCachedAsEmptyNotMissing() {
        val store = TimetableCacheStore(directory)
        val index = index()
        val empty = week(index.terms.first(), 9, includeMeeting = false)
        assertTrue(store.save("acct_synthetic_1", index, empty, 2_000))
        val loaded = store.load("acct_synthetic_1")
        val cached = loaded.week(index.terms.first(), 9)!!
        assertTrue(cached.complete)
        assertTrue(cached.isEmpty)
    }

    @Test
    fun corruptCacheDoesNotBecomeEmptyWeekOrCrash() {
        val store = TimetableCacheStore(directory)
        File(directory, TimetableStorage.CACHE_FILE).writeText("{broken")
        val loaded = store.load("acct_synthetic_1")
        assertNull(loaded.index)
        assertTrue(loaded.weeks.isEmpty())
    }

    @Test
    fun hostLevelClearWorksWithoutActivePlugin() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val dir = TimetableStorage.directory(context)
        dir.mkdirs()
        File(dir, TimetableStorage.SESSION_FILE).writeText("synthetic-session")
        File(dir, TimetableStorage.CACHE_FILE).writeText("synthetic-cache")
        assertTrue(TimetableFiles.clearAll(context))
        assertFalse(File(dir, TimetableStorage.SESSION_FILE).exists())
        assertFalse(File(dir, TimetableStorage.CACHE_FILE).exists())
    }

    @Test
    fun cacheClearDeletesFile() {
        val store = TimetableCacheStore(directory)
        val index = index()
        assertTrue(store.save("acct_synthetic_1", index, week(index.terms.first(), 8, true), 1))
        assertTrue(store.clear())
        assertFalse(File(directory, TimetableStorage.CACHE_FILE).exists())
    }

    private fun session(account: String, generation: Long): TimetableSession =
        TimetableSession(
            accountId = account,
            credentialGeneration = generation,
            businessGeneration = 0,
            savedAtEpochMillis = 1_000,
            cookies = listOf(
                TimetableCookie(
                    name = "eai-sess",
                    value = "synthetic-session-value",
                    domain = "app.upc.edu.cn",
                    path = "/",
                    secure = true,
                ),
            ),
        )

    private fun index(): TimetableIndex = TimetableProtocol.parseIndex(
        javaClass.getResource("/timetable_index.json")!!.readText(),
    )

    private fun week(term: TimetableTerm, week: Int, includeMeeting: Boolean): TimetableWeek {
        val start = term.startDay.plusDays((week - 1).toLong() * 7)
        val dates = (0..6).map { start.plusDays(it.toLong()) }
        val meetings = if (!includeMeeting) {
            emptyList()
        } else {
            listOf(
                TimetableMeeting(
                    courseName = "示例课程A",
                    weekday = 1,
                    startPeriod = 6,
                    endPeriod = 7,
                    periods = listOf(6, 7),
                    sourcePeriods = listOf(6, 7),
                    weeks = listOf(8),
                    weekLabel = "8",
                    teacher = "教师甲",
                    location = "教学楼A-101",
                    sectionsSource = "lessArr",
                    date = dates[1],
                ),
            )
        }
        return TimetableWeek(term, week, dates, meetings, 1_000)
    }
}

private class FakeCrypto(private val seed: Int = 5) : ByteCrypto {
    override fun encrypt(plain: ByteArray): ByteArray =
        ByteArray(plain.size) { (plain[it].toInt() xor seed).toByte() }

    override fun decrypt(stored: ByteArray): ByteArray =
        ByteArray(stored.size) { (stored[it].toInt() xor seed).toByte() }
}
