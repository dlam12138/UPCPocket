package com.upcpocket.app.grades

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.upcauth.GradesAuthenticator
import com.upcpocket.app.upcauth.UpcAuthCookie
import com.upcpocket.app.upcauth.UpcAuthResult
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
        val authenticator = BlockingAuthenticator()
        val service = createService(reader, authenticator, files)

        val job = launch { service.sync() }
        authenticator.entered.await()
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
        val service = createService(reader, SuccessAuthenticator, files)

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
            authenticator = SuccessAuthenticator,
            files = files,
            remote = FakeRemote(
                index = GradesIndex(listOf(term), term),
                courses = mapOf(
                    term.key to listOf(
                        GradesCourse(term = term, courseName = "高等数学", score = "95"),
                    ),
                ),
            ),
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
        val service = createService(reader, SuccessAuthenticator, files)

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
        val authenticator = BlockingAuthenticator()
        val service = createService(reader, authenticator, GradesFiles(directory, XorCrypto()))
        val bindingB = SchoolAccountReference(
            SchoolAccountId("acct_synthetic_b"),
            AccountGeneration(2),
        )

        val job = launch { service.sync() }
        authenticator.entered.await()
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
        val remote = FakeRemote(
            index = GradesIndex(listOf(term, termB, termC), term),
            courses = mapOf(
                termB.key to listOf(
                    GradesCourse(term = termB, courseName = "新课程", score = "88"),
                ),
            ),
            failures = mapOf(
                term.key to GradesRemoteFailure.PROTOCOL,
                termC.key to GradesRemoteFailure.PROTOCOL,
            ),
        )
        val synced = createService(reader, SuccessAuthenticator, files, remote)
        synced.sync()
        assertEquals(GradesConnection.PARTIAL, synced.state.value.connection)
        assertEquals(GradesFailure.PROTOCOL_ERROR, synced.state.value.lastFailure)

        // 新进程：同一目录重新构造存储与服务，只做恢复。
        val restored = createService(
            reader,
            SuccessAuthenticator,
            GradesFiles(directory, XorCrypto()),
            NoRemote,
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
        val remote = FakeRemote(
            index = GradesIndex(listOf(term, termB), term),
            courses = mapOf(
                term.key to listOf(
                    GradesCourse(term = term, courseName = "高等数学", score = "95"),
                ),
            ),
            failures = mapOf(termB.key to GradesRemoteFailure.PROTOCOL),
        )
        val service = createService(
            reader,
            SuccessAuthenticator,
            GradesFiles(directory, FailEncryptCrypto()),
            remote,
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
        val remote = FakeRemote(
            index = GradesIndex(listOf(term, termB), term),
            courses = emptyMap(),
            failures = mapOf(
                term.key to GradesRemoteFailure.PROTOCOL,
                termB.key to GradesRemoteFailure.PROTOCOL,
            ),
        )
        val service = createService(reader, SuccessAuthenticator, files, remote)

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
            SuccessAuthenticator,
            GradesFiles(directory, XorCrypto()),
            NoRemote,
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

    private fun TestScope.createService(
        account: SchoolAccountReferenceReader,
        authenticator: GradesAuthenticator,
        files: GradesFiles,
        remote: GradesRemote = NoRemote,
    ): GradesService = GradesService(
        account = account,
        authenticator = authenticator,
        files = files,
        remote = remote,
        clock = { 1_700_000_000_500L },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        scope = backgroundScope,
    )

    private fun validState(binding: SchoolAccountReference) = SchoolAccountState(
        status = SchoolAccountStatus.SAVED_UNVERIFIED,
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

    private class BlockingAuthenticator : GradesAuthenticator {
        val entered = CompletableDeferred<Unit>()

        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcAuthResult {
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    private object SuccessAuthenticator : GradesAuthenticator {
        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcAuthResult = UpcAuthResult.Success(
            listOf(
                UpcAuthCookie(
                    name = "SESSION",
                    value = "synthetic",
                    domain = UpcAuthCookie.HOST,
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    expiresAtEpochSeconds = null,
                ),
            ),
        )
    }

    private object NoRemote : GradesRemote {
        override suspend fun getIndex(cookies: List<UpcAuthCookie>): GradesIndex =
            error("unexpected index request")

        override suspend fun getTerm(
            cookies: List<UpcAuthCookie>,
            term: GradesTerm,
        ): List<GradesCourse> = error("unexpected term request")
    }

    private class FakeRemote(
        private val index: GradesIndex,
        private val courses: Map<String, List<GradesCourse>>,
        private val failures: Map<String, GradesRemoteFailure> = emptyMap(),
    ) : GradesRemote {
        override suspend fun getIndex(cookies: List<UpcAuthCookie>): GradesIndex = index

        override suspend fun getTerm(
            cookies: List<UpcAuthCookie>,
            term: GradesTerm,
        ): List<GradesCourse> {
            failures[term.key]?.let { throw GradesRemoteException(it) }
            return courses[term.key].orEmpty()
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
