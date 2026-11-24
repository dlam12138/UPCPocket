package com.upcpocket.app.grades

import com.upcpocket.app.platform.crypto.ByteCrypto
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesFilesTest {

    private val directory = Files.createTempDirectory("grades-files").toFile()
    private val crypto = XorCrypto()
    private val files = GradesFiles(directory, crypto)

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun completeSnapshotRoundTripsThroughReopenedEncryptedFile() {
        val snapshot = snapshot(accountId = "acct_synthetic_a", generation = 2)

        assertTrue(files.save(snapshot))

        val stored = File(directory, "${GradesFiles.DIRECTORY}/${GradesFiles.FILE_NAME}")
        assertTrue(stored.isFile)
        val raw = stored.readText(Charsets.UTF_8)
        assertFalse(raw.contains("高等数学"))
        assertFalse(raw.contains("acct_synthetic_a"))
        assertFalse(raw.contains("\"courses\""))

        val reopened = GradesFiles(directory, crypto)
        val loaded = reopened.loadResult()
        assertTrue(loaded is GradesLoadResult.Loaded)
        assertEquals(snapshot, (loaded as GradesLoadResult.Loaded).snapshot)
    }

    @Test
    fun noSnapshotIsMissingButExistingUnreadableFileIsUnreadable() {
        assertEquals(GradesLoadResult.Missing, files.loadResult())

        val snapshot = snapshot(accountId = "acct_synthetic_a", generation = 1)
        assertTrue(files.save(snapshot))
        assertEquals(snapshot, (files.loadResult() as GradesLoadResult.Loaded).snapshot)
        assertEquals(
            GradesLoadResult.Unreadable,
            GradesFiles(directory, FailingDecryptCrypto()).loadResult(),
        )

        val stored = File(directory, "${GradesFiles.DIRECTORY}/${GradesFiles.FILE_NAME}")
        stored.writeText(
            """{"format":"${GradesFiles.FORMAT}","version":99,"ciphertext":"AAAA"}""",
            Charsets.UTF_8,
        )
        assertEquals(GradesLoadResult.Unreadable, files.loadResult())
        assertEquals(GradesLoadResult.Unreadable, GradesFiles(directory, XorCrypto()).loadResult())

        stored.writeText("not-json", Charsets.UTF_8)
        assertEquals(GradesLoadResult.Unreadable, files.loadResult())
    }

    @Test
    fun clearAllOnlyRemovesTheGradesDirectoryAndIsRepeatable() {
        val snapshot = snapshot(accountId = "acct_synthetic_a", generation = 1)
        assertTrue(files.save(snapshot))
        val sibling = File(directory, "account").apply { mkdirs() }
        File(sibling, "state.json").writeText("{}", Charsets.UTF_8)

        assertTrue(GradesFiles.clearAll(directory))
        assertEquals(GradesLoadResult.Missing, files.loadResult())
        assertFalse(File(directory, GradesFiles.DIRECTORY).exists())
        assertTrue(File(sibling, "state.json").isFile)
        assertTrue(GradesFiles.clearAll(directory))
    }

    @Test
    fun clearRemovesOnlyThisSnapshot() {
        assertTrue(files.save(snapshot(accountId = "acct_synthetic_a", generation = 1)))
        assertTrue(files.clear())
        assertEquals(GradesLoadResult.Missing, files.loadResult())
        assertFalse(File(directory, "${GradesFiles.DIRECTORY}/${GradesFiles.FILE_NAME}").exists())
    }

    private fun snapshot(accountId: String, generation: Long): GradesSnapshot {
        val term = GradesTerm(year = "2025", term = "1")
        val course = GradesCourse(
            term = term,
            courseName = "高等数学",
            score = "95",
            credits = 4.0,
            courseType = "必修",
            examType = "考试",
        )
        return GradesSnapshot(
            accountId = accountId,
            credentialGeneration = generation,
            savedAtEpochMillis = 1_700_000_000_000L,
            terms = listOf(term),
            currentTerm = term,
            results = listOf(
                GradesTermResult(
                    term = term,
                    courses = listOf(course),
                    fetchedAtEpochMillis = 1_700_000_000_100L,
                ),
            ),
        )
    }

    private class XorCrypto : ByteCrypto {
        override fun encrypt(plain: ByteArray): ByteArray = transform(plain)

        override fun decrypt(stored: ByteArray): ByteArray = transform(stored)

        private fun transform(bytes: ByteArray): ByteArray =
            bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private class FailingDecryptCrypto : ByteCrypto {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(stored: ByteArray): ByteArray =
            error("synthetic decryption failure")
    }
}
