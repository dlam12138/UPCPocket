package com.upcpocket.app.grades

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.platform.crypto.ByteCrypto
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GradesServiceTest {

    private val directory = Files.createTempDirectory("grades-service").toFile()
    private val term = GradesTerm(year = "2025", term = "1")
    private val bindingA = SchoolAccountReference(
        SchoolAccountId("acct_synthetic_a"),
        AccountGeneration(1),
    )

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun cancelledSyncClearsConnectingAndRestoreCanStillLoadTheCache() = runTest {
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(files.save(snapshot(bindingA)))
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = BlockingBackendSource()
        val service = createService(reader, backend, files)

        val job = launch { service.sync() }
        backend.entered.await()
        assertEquals(GradesConnection.CONNECTING, service.state.value.connection)

        job.cancelAndJoin()
        assertNotEquals(GradesConnection.CONNECTING, service.state.value.connection)

        service.restore()
        val restored = service.state.value
        assertEquals(bindingA, restored.owner)
        assertEquals(GradesConnection.READY, restored.connection)
        assertEquals(1, restored.termResults.size)
        assertEquals("高等数学", restored.termResults.single().courses.single().courseName)
    }

    @Test
    fun restoreReportsStorageErrorForAnUnreadableSnapshot() = runTest {
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(files.save(snapshot(bindingA)))
        val stored = File(directory, "${GradesFiles.DIRECTORY}/${GradesFiles.FILE_NAME}")
        stored.writeText("not-json", Charsets.UTF_8)

        val reader = FakeReader(validState(bindingA), bindingA)
        val service = createService(reader, NoBackendSource, files)

        service.restore()
        assertEquals(GradesConnection.PROTOCOL_ERROR, service.state.value.connection)
        assertEquals(GradesFailure.STORAGE_ERROR, service.state.value.lastFailure)
    }

    @Test
    fun aFailedSnapshotWriteDoesNotLookLikeAnOrdinaryReadyResult() = runTest {
        val files = GradesFiles(directory, FailEncryptCrypto())
        val reader = FakeReader(validState(bindingA), bindingA)
        val service = createService(
            account = reader,
            backend = FakeBackendSource(
                index = GradesIndex(listOf(term), term),
                courses = mapOf(
                    term.key to listOf(
                        GradesCourse(term = term, courseName = "高等数学", score = "95"),
                    ),
                ),
            ),
            files = files,
        )

        service.sync()

        val state = service.state.value
        assertEquals(bindingA, state.owner)
        assertNotEquals(GradesConnection.READY, state.connection)
        assertEquals(GradesFailure.STORAGE_ERROR, state.lastFailure)
        assertEquals(1, state.termResults.size)
        assertTrue(state.unsavedChanges)
    }

    @Test
    fun firstReferenceObserverCannotClearRestoredResults() = runTest {
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(files.save(snapshot(bindingA)))
        val reader = FakeReader(
            SchoolAccountState(status = SchoolAccountStatus.LOADING),
            reference = null,
        )
        val service = createService(reader, NoBackendSource, files)

        reader.set(validState(bindingA), bindingA)
        service.restore()
        assertEquals(GradesConnection.READY, service.state.value.connection)
        assertEquals(1, service.state.value.termResults.size)

        runCurrent()
        assertEquals(bindingA, service.state.value.owner)
        assertEquals(GradesConnection.READY, service.state.value.connection)
        assertEquals(1, service.state.value.termResults.size)
    }

    @Test
    fun cancelledOldAccountSyncCannotOverwriteTheNewAccountState() = runTest {
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = BlockingBackendSource()
        val service = createService(reader, backend, GradesFiles(directory, XorCrypto()))
        val bindingB = SchoolAccountReference(
            SchoolAccountId("acct_synthetic_b"),
            AccountGeneration(2),
        )

        val job = launch { service.sync() }
        backend.entered.await()
        reader.set(validState(bindingB), bindingB)
        runCurrent()
        assertEquals(bindingB, service.state.value.owner)

        job.cancelAndJoin()
        runCurrent()
        assertEquals(bindingB, service.state.value.owner)
        assertEquals(GradesConnection.IDLE, service.state.value.connection)
    }

    @Test
    fun restoredPartialSnapshotKeepsFailedTermsAndEachOriginalFetchedAt() = runTest {
        val termB = GradesTerm(year = "2024", term = "2")
        val termC = GradesTerm(year = "2023", term = "1")
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(
            files.save(
                snapshot(
                    binding = bindingA,
                    terms = listOf(term, termB, termC),
                    results = listOf(
                        termResult(
                            term = term,
                            courseName = "旧成绩",
                            fetchedAtEpochMillis = 1_700_000_000_100L,
                        ),
                    ),
                ),
            ),
        )
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = FakeBackendSource(
            index = GradesIndex(listOf(term, termB, termC), term),
            courses = mapOf(
                termB.key to listOf(
                    GradesCourse(term = termB, courseName = "新课程", score = "88"),
                ),
            ),
            failures = mapOf(
                term.key to GradesBackendFailureCode.UPSTREAM_PROTOCOL_ERROR,
                termC.key to GradesBackendFailureCode.UPSTREAM_PROTOCOL_ERROR,
            ),
        )
        val synced = createService(reader, backend, files)
        synced.sync()
        assertEquals(GradesConnection.PARTIAL, synced.state.value.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, synced.state.value.lastFailure)

        // 新进程：同一目录重新构造存储与服务，只做恢复。
        val restored = createService(
            reader,
            NoBackendSource,
            GradesFiles(directory, XorCrypto()),
        )
        restored.restore()

        val state = restored.state.value
        assertEquals(GradesConnection.PARTIAL, state.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, state.lastFailure)
        assertEquals(listOf(term), state.lastSyncRetainedTerms)
        assertEquals(listOf(termC), state.lastSyncFailedTerms)
        assertEquals(2, state.termResults.size)
        val retained = state.termResults.single { it.term == term }
        assertEquals(1_700_000_000_100L, retained.fetchedAtEpochMillis)
        assertEquals("旧成绩", retained.courses.single().courseName)
        val fresh = state.termResults.single { it.term == termB }
        assertEquals(1_700_000_000_500L, fresh.fetchedAtEpochMillis)
        assertFalse(state.unsavedChanges)
    }

    @Test
    fun partialSyncWithFailedSnapshotWriteKeepsUnsavedChangesAcrossRestore() = runTest {
        val termB = GradesTerm(year = "2024", term = "2")
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = FakeBackendSource(
            index = GradesIndex(listOf(term, termB), term),
            courses = mapOf(
                term.key to listOf(
                    GradesCourse(term = term, courseName = "高等数学", score = "95"),
                ),
            ),
            failures = mapOf(termB.key to GradesBackendFailureCode.UPSTREAM_PROTOCOL_ERROR),
        )
        val service = createService(
            reader,
            backend,
            GradesFiles(directory, FailEncryptCrypto()),
        )

        service.sync()

        val state = service.state.value
        assertEquals(GradesConnection.PARTIAL, state.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, state.lastFailure)
        assertTrue(state.unsavedChanges)
        assertEquals(1, state.termResults.size)
        assertEquals(listOf(termB), state.lastSyncFailedTerms)
        assertEquals(emptyList<GradesTerm>(), state.lastSyncRetainedTerms)

        // 重新进入页面触发 restore；内存结果仍在，未落盘提示不能消失。
        service.restore()
        assertTrue(service.state.value.unsavedChanges)
        assertEquals(GradesConnection.PARTIAL, service.state.value.connection)
    }

    @Test
    fun allTermsFailedWithCachedResultsRestoresPartialAndOriginalTimes() = runTest {
        val termB = GradesTerm(year = "2024", term = "2")
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(
            files.save(
                snapshot(
                    binding = bindingA,
                    terms = listOf(term, termB),
                    results = listOf(termResult(term)),
                ),
            ),
        )
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = FakeBackendSource(
            index = GradesIndex(listOf(term, termB), term),
            courses = emptyMap(),
            failures = mapOf(
                term.key to GradesBackendFailureCode.UPSTREAM_PROTOCOL_ERROR,
                termB.key to GradesBackendFailureCode.UPSTREAM_PROTOCOL_ERROR,
            ),
        )
        val service = createService(reader, backend, files)

        service.sync()

        val state = service.state.value
        assertEquals(GradesConnection.PARTIAL, state.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, state.lastFailure)
        assertEquals(1_700_000_000_000L, state.lastSyncedAtEpochMillis)
        assertEquals(listOf(term), state.lastSyncRetainedTerms)
        assertEquals(listOf(termB), state.lastSyncFailedTerms)
        assertEquals(1, state.termResults.size)
        assertEquals(1_700_000_000_100L, state.termResults.single().fetchedAtEpochMillis)
        assertFalse(state.unsavedChanges)

        // 全失败也写入了本轮失败范围；新进程恢复后不能退回旧快照的 READY，
        // 也不能把沿用结果的成功时间改成这次失败尝试的时间。
        val restored = createService(
            reader,
            NoBackendSource,
            GradesFiles(directory, XorCrypto()),
        )
        restored.restore()

        val restoredState = restored.state.value
        assertEquals(GradesConnection.PARTIAL, restoredState.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, restoredState.lastFailure)
        assertEquals(1_700_000_000_000L, restoredState.lastSyncedAtEpochMillis)
        assertEquals(listOf(term), restoredState.lastSyncRetainedTerms)
        assertEquals(listOf(termB), restoredState.lastSyncFailedTerms)
        assertEquals(1, restoredState.termResults.size)
        assertEquals(
            1_700_000_000_100L,
            restoredState.termResults.single().fetchedAtEpochMillis,
        )
        assertFalse(restoredState.unsavedChanges)
    }

    @Test
    fun incompleteBackendIndexDoesNotReplacePriorResults() = runTest {
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(files.save(snapshot(bindingA)))
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = FakeBackendSource(
            index = GradesIndex(listOf(term), term),
            courses = emptyMap(),
            completeTerms = false,
        )
        val service = createService(reader, backend, files)

        service.sync()

        assertEquals(GradesConnection.PROTOCOL_ERROR, service.state.value.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, service.state.value.lastFailure)
        assertEquals(1_700_000_000_000L, service.state.value.lastSyncedAtEpochMillis)
        assertEquals("高等数学", service.state.value.termResults.single().courses.single().courseName)
        val stored = files.loadResult() as GradesLoadResult.Loaded
        assertEquals(1, stored.snapshot.results.size)
        assertEquals(1_700_000_000_000L, stored.snapshot.savedAtEpochMillis)
    }

    @Test
    fun moduleDisabledPreservesOwnedSnapshotAndSuccessfulTimestamp() = runTest {
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(files.save(snapshot(bindingA)))
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = FakeBackendSource(
            index = GradesIndex(listOf(term), term),
            courses = emptyMap(),
            failures = mapOf(term.key to GradesBackendFailureCode.MODULE_DISABLED),
        )
        val service = createService(reader, backend, files)

        service.sync()

        val state = service.state.value
        assertEquals(GradesConnection.NETWORK_UNAVAILABLE, state.connection)
        assertEquals(GradesFailure.MODULE_DISABLED, state.lastFailure)
        assertEquals(1_700_000_000_000L, state.lastSyncedAtEpochMillis)
        assertEquals("高等数学", state.termResults.single().courses.single().courseName)
        assertEquals(1_700_000_000_100L, state.termResults.single().fetchedAtEpochMillis)
        assertFalse(state.unsavedChanges)
    }

    @Test
    fun rateLimitKeepsRetryAfterHintAndExistingOwnedResults() = runTest {
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(files.save(snapshot(bindingA)))
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = object : GradesBackendSource {
            override suspend fun terms(): GradesBackendTerms =
                throw GradesBackendFailure(
                    code = GradesBackendFailureCode.RATE_LIMITED,
                    retryAfterSeconds = 45,
                )

            override suspend fun courses(year: String, term: String): GradesBackendCourses =
                error("rate limited term index must stop before course requests")
        }
        val service = createService(reader, backend, files)

        service.sync()

        val state = service.state.value
        assertEquals(GradesConnection.NETWORK_UNAVAILABLE, state.connection)
        assertEquals(GradesFailure.RATE_LIMITED, state.lastFailure)
        assertEquals(45L, state.retryAfterSeconds)
        assertEquals("高等数学", state.termResults.single().courses.single().courseName)
        assertEquals(1_700_000_000_000L, state.lastSyncedAtEpochMillis)
    }

    @Test
    fun incompleteCourseResponseRetainsPreviousTermResult() = runTest {
        val files = GradesFiles(directory, XorCrypto())
        assertTrue(files.save(snapshot(bindingA)))
        val reader = FakeReader(validState(bindingA), bindingA)
        val backend = FakeBackendSource(
            index = GradesIndex(listOf(term), term),
            courses = mapOf(term.key to listOf(GradesCourse(term, "新成绩", score = "88"))),
            incompleteCourseTerms = setOf(term.key),
        )
        val service = createService(reader, backend, files)

        service.sync()

        val state = service.state.value
        assertEquals(GradesConnection.PARTIAL, state.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, state.lastFailure)
        assertEquals(listOf(term), state.lastSyncRetainedTerms)
        assertEquals("高等数学", state.termResults.single().courses.single().courseName)
        assertEquals(1_700_000_000_100L, state.termResults.single().fetchedAtEpochMillis)
    }

    private fun TestScope.createService(
        account: SchoolAccountReferenceReader,
        backend: GradesBackendSource,
        files: GradesFiles,
    ): GradesService = GradesService(
        account = account,
        backend = backend,
        files = files,
        clock = { 1_700_000_000_500L },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        scope = backgroundScope,
    )

    private fun validState(binding: SchoolAccountReference) = SchoolAccountState(
        status = SchoolAccountStatus.MANAGED_ACTIVE,
        generation = binding.credentialGeneration,
    )

    private fun snapshot(
        binding: SchoolAccountReference,
        terms: List<GradesTerm> = listOf(term),
        results: List<GradesTermResult> = listOf(termResult(term)),
        savedAtEpochMillis: Long = 1_700_000_000_000L,
    ): GradesSnapshot = GradesSnapshot(
        accountId = binding.accountId.value,
        credentialGeneration = binding.credentialGeneration.value,
        savedAtEpochMillis = savedAtEpochMillis,
        terms = terms,
        currentTerm = terms.firstOrNull(),
        results = results,
    )

    private fun termResult(
        term: GradesTerm,
        courseName: String = "高等数学",
        fetchedAtEpochMillis: Long = 1_700_000_000_100L,
    ): GradesTermResult {
        val course = GradesCourse(
            term = term,
            courseName = courseName,
            score = "95",
            credits = 4.0,
            courseType = "必修",
        )
        return GradesTermResult(
            term = term,
            courses = listOf(course),
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        )
    }

    private class FakeReader(
        initial: SchoolAccountState,
        reference: SchoolAccountReference?,
    ) : SchoolAccountReferenceReader {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<SchoolAccountState> = mutableState.asStateFlow()

        private val mutableReference = MutableStateFlow(reference)
        override val accountReference: StateFlow<SchoolAccountReference?> =
            mutableReference.asStateFlow()

        fun set(next: SchoolAccountState, reference: SchoolAccountReference?) {
            mutableState.value = next
            mutableReference.value = reference
        }
    }

    private class BlockingBackendSource : GradesBackendSource {
        val entered = CompletableDeferred<Unit>()

        override suspend fun terms(): GradesBackendTerms {
            entered.complete(Unit)
            awaitCancellation()
        }

        override suspend fun courses(year: String, term: String): GradesBackendCourses =
            error("blocking terms request must not continue")
    }

    private object NoBackendSource : GradesBackendSource {
        override suspend fun terms(): GradesBackendTerms =
            error("unexpected index request")

        override suspend fun courses(year: String, term: String): GradesBackendCourses =
            error("unexpected courses request")
    }

    private class FakeBackendSource(
        private val index: GradesIndex,
        private val courses: Map<String, List<GradesCourse>>,
        private val failures: Map<String, GradesBackendFailureCode> = emptyMap(),
        private val completeTerms: Boolean = true,
        private val incompleteCourseTerms: Set<String> = emptySet(),
    ) : GradesBackendSource {
        override suspend fun terms(): GradesBackendTerms = GradesBackendTerms(
            terms = index.terms.map { GradesBackendTerm(it.year, it.term) },
            currentTerm = index.currentTerm?.let { GradesBackendTerm(it.year, it.term) },
            complete = completeTerms,
        )

        override suspend fun courses(year: String, term: String): GradesBackendCourses {
            val key = "$year|$term"
            failures[key]?.let { throw GradesBackendFailure(it) }
            val rows = courses[key].orEmpty().map {
                GradesBackendCourse(
                    courseName = it.courseName,
                    score = it.score,
                    credits = it.credits,
                    courseType = it.courseType,
                    examType = it.examType,
                )
            }
            return GradesBackendCourses(
                courses = rows,
                complete = key !in incompleteCourseTerms,
                fetchedAtEpochMillis = 1_700_000_000_500L,
            )
        }
    }

    private class XorCrypto : ByteCrypto {
        override fun encrypt(plain: ByteArray): ByteArray = transform(plain)

        override fun decrypt(stored: ByteArray): ByteArray = transform(stored)

        private fun transform(bytes: ByteArray): ByteArray =
            bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private class FailEncryptCrypto : ByteCrypto {
        override fun encrypt(plain: ByteArray): ByteArray =
            error("synthetic encryption failure")

        override fun decrypt(stored: ByteArray): ByteArray = stored
    }
}
