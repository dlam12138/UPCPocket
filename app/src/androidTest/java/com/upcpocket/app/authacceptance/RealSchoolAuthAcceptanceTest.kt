package com.upcpocket.app.authacceptance

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.core.AppGraph
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Offline credential seeding and exact owned-account cleanup for manual Release UI acceptance. */
@RunWith(AndroidJUnit4::class)
class RealSchoolAuthAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    private val fixtureFile get() = File(context.filesDir, FIXTURE_FILE)
    private val markerFile get() = File(context.noBackupFilesDir, MARKER_FILE)
    private val accountDirectory get() = File(context.noBackupFilesDir, ACCOUNT_DIRECTORY)

    @Test
    fun prepareReleaseAccount() = runBlocking {
        val report = AcceptanceReport(Method.PREPARE_RELEASE_ACCOUNT)
        val authorizationFailure = authorizationFailure()
        if (authorizationFailure != null) {
            report.result = if (authorizationFailure == FailureCode.OPT_IN_REQUIRED ||
                authorizationFailure == FailureCode.ISOLATED_DEVICE_REQUIRED
            ) Result.SKIPPED else Result.FAILED
            report.failure = authorizationFailure
            emit(report)
            if (report.result == Result.FAILED) throw AssertionError(authorizationFailure.name)
            return@runBlocking
        }
        val runId = normalizedRunId()
            ?: run {
                report.failure = FailureCode.RUN_ID_INVALID
                report.result = Result.FAILED
                emit(report)
                throw AssertionError(report.failure.name)
            }

        var repository: SchoolAccountRepository? = null
        var fixture: CredentialFixture? = null
        var preparingMarkerWritten = false
        var markerWriteAttempted = false
        var saveSucceeded = false
        try {
            report.stage = Stage.GUARD
            AppGraph.init(context)
            requireAcceptance(!hasAnyMarkerArtifact(), FailureCode.OWNERSHIP_MARKER_PRESENT)
            requireAcceptance(accountDirectoryIsEmpty(), FailureCode.ACCOUNT_ARTIFACT_PRESENT)
            requireAcceptance(!hasExistingPersonalState(), FailureCode.PERSONAL_DATA_PRESENT)

            val account = AppGraph.account
            repository = account
            account.restore()
            requireAcceptance(
                isEmptyAccount(account) && account.state.value.generation.value == 0L,
                FailureCode.ACCOUNT_STATE_NOT_EMPTY,
            )
            requireAcceptance(accountDirectoryIsEmpty(), FailureCode.ACCOUNT_ARTIFACT_PRESENT)

            report.stage = Stage.FIXTURE
            requireAcceptance(fixtureFile.isFile, FailureCode.FIXTURE_ABSENT)
            val credentials = consumeFixture(onConsumed = { report.fixtureConsumed = true })
            fixture = credentials

            val baselineGeneration = account.state.value.generation.value
            requireAcceptance(baselineGeneration == 0L, FailureCode.ACCOUNT_STATE_NOT_EMPTY)
            val preparingMarker = OwnedMarker(
                phase = MarkerPhase.PREPARING,
                runId = runId,
                baselineGeneration = baselineGeneration,
                expectedGeneration = baselineGeneration + 1,
            )
            report.stage = Stage.PREPARING
            markerWriteAttempted = true
            requireAcceptance(writeMarker(preparingMarker), FailureCode.MARKER_WRITE_FAILED)
            preparingMarkerWritten = true
            report.markerPresent = true

            report.stage = Stage.ACCOUNT_SAVE
            report.accountSaveAttempted = true
            val saveResult = try {
                account.save(credentials.account, credentials.password)
            } finally {
                credentials.clear()
                fixture = null
            }
            requireAcceptance(saveResult == SchoolAccountWriteResult.Success, FailureCode.ACCOUNT_SAVE_FAILED)
            saveSucceeded = true
            report.accountSaved = true

            val reference = account.accountReference.value
                ?: throw HarnessAbort(FailureCode.ACCOUNT_REFERENCE_MISSING)
            requireAcceptance(
                matchesExpectedNewAccount(account, reference, preparingMarker) &&
                    accountTransactionsAbsent(),
                FailureCode.ACCOUNT_SAVE_FAILED,
            )
            val ownedMarker = preparingMarker.copy(
                phase = MarkerPhase.OWNED,
                accountId = reference.accountId.value,
                credentialGeneration = reference.credentialGeneration.value,
            )
            report.stage = Stage.OWNED_MARKER
            requireAcceptance(writeMarker(ownedMarker), FailureCode.MARKER_WRITE_FAILED)

            report.accountSaved = true
            report.markerPresent = true
            report.stage = Stage.COMPLETE
            report.result = Result.ACCOUNT_SEEDED
            report.rollback = RollbackResult.NOT_NEEDED
        } catch (cancelled: CancellationException) {
            report.failure = FailureCode.OPERATION_CANCELLED
            report.result = Result.FAILED
            report.rollback = if (preparingMarkerWritten) RollbackResult.UNKNOWN
            else RollbackResult.NOT_NEEDED
            report.markerPresent = hasAnyMarkerArtifact()
            emit(report)
            throw cancelled
        } catch (abort: HarnessAbort) {
            report.failure = abort.code
            report.result = Result.FAILED
            report.rollback = when {
                preparingMarkerWritten -> repository?.let { rollbackSeed(it, runId) }
                    ?: RollbackResult.UNKNOWN
                markerWriteAttempted && hasAnyMarkerArtifact() -> RollbackResult.UNKNOWN
                else -> RollbackResult.NOT_NEEDED
            }
            if (report.rollback == RollbackResult.SUCCEEDED) {
                report.accountSaved = false
                report.accountRemoved = saveSucceeded
                report.cleanupOk = true
            }
            report.markerPresent = hasAnyMarkerArtifact()
        } catch (_: Exception) {
            report.failure = FailureCode.INTERNAL_FAILURE
            report.result = Result.FAILED
            report.rollback = when {
                preparingMarkerWritten -> repository?.let { rollbackSeed(it, runId) }
                    ?: RollbackResult.UNKNOWN
                markerWriteAttempted && hasAnyMarkerArtifact() -> RollbackResult.UNKNOWN
                else -> RollbackResult.NOT_NEEDED
            }
            if (report.rollback == RollbackResult.SUCCEEDED) {
                report.accountSaved = false
                report.accountRemoved = saveSucceeded
                report.cleanupOk = true
            }
            report.markerPresent = hasAnyMarkerArtifact()
        } finally {
            fixture?.clear()
        }

        emit(report)
        if (report.result == Result.FAILED) {
            throw AssertionError(report.failure.name)
        }
    }

    @Test
    fun cleanupOwnedAccount() = runBlocking {
        val report = AcceptanceReport(Method.CLEANUP_OWNED_ACCOUNT)
        val authorizationFailure = authorizationFailure()
        if (authorizationFailure != null) {
            report.result = if (authorizationFailure == FailureCode.OPT_IN_REQUIRED ||
                authorizationFailure == FailureCode.ISOLATED_DEVICE_REQUIRED
            ) Result.SKIPPED else Result.FAILED
            report.failure = authorizationFailure
            emit(report)
            if (report.result == Result.FAILED) throw AssertionError(authorizationFailure.name)
            return@runBlocking
        }
        val runId = normalizedRunId()
            ?: run {
                report.failure = FailureCode.RUN_ID_INVALID
                report.result = Result.FAILED
                emit(report)
                throw AssertionError(report.failure.name)
            }

        try {
            report.stage = Stage.GUARD
            AppGraph.init(context)
            if (markerNewFile().exists()) throw HarnessAbort(FailureCode.OWNERSHIP_MARKER_INVALID)
            val marker = readMarker()
                ?: throw HarnessAbort(
                    if (hasAnyMarkerArtifact()) FailureCode.OWNERSHIP_MARKER_INVALID
                    else FailureCode.OWNERSHIP_MARKER_MISSING,
                )
            requireAcceptance(marker.runId == runId, FailureCode.OWNERSHIP_MISMATCH)
            requireAcceptance(accountTransactionsAbsent(), FailureCode.ACCOUNT_TRANSACTION_PRESENT)

            val account = AppGraph.account
            account.restore()
            requireAcceptance(accountTransactionsAbsent(), FailureCode.ACCOUNT_TRANSACTION_PRESENT)

            report.stage = Stage.ACCOUNT_DELETE
            val outcome = cleanupMarker(account, marker)
            report.accountRemoved = outcome.accountRemoved
            report.cleanupOk = outcome.cleanupOk
            report.markerPresent = hasAnyMarkerArtifact()
            report.stage = Stage.COMPLETE
            report.result = Result.ACCOUNT_CLEANED
            report.rollback = RollbackResult.NOT_APPLICABLE
        } catch (cancelled: CancellationException) {
            report.failure = FailureCode.OPERATION_CANCELLED
            report.result = Result.FAILED
            report.rollback = RollbackResult.NOT_APPLICABLE
            report.markerPresent = hasAnyMarkerArtifact()
            emit(report)
            throw cancelled
        } catch (abort: HarnessAbort) {
            report.failure = abort.code
            report.result = Result.FAILED
            report.cleanupOk = false
            report.markerPresent = hasAnyMarkerArtifact()
        } catch (_: Exception) {
            report.failure = FailureCode.INTERNAL_FAILURE
            report.result = Result.FAILED
            report.cleanupOk = false
            report.markerPresent = hasAnyMarkerArtifact()
        }

        emit(report)
        if (report.result == Result.FAILED) throw AssertionError(report.failure.name)
    }

    private fun authorizationFailure(): FailureCode? = when {
        arguments.getString(ARG_OPT_IN) != OPT_IN_VALUE -> FailureCode.OPT_IN_REQUIRED
        arguments.getString(ARG_ISOLATED_DEVICE) != ISOLATED_DEVICE_VALUE ->
            FailureCode.ISOLATED_DEVICE_REQUIRED
        normalizedRunId() == null -> FailureCode.RUN_ID_INVALID
        else -> null
    }

    private fun normalizedRunId(): String? = arguments.getString(ARG_RUN_ID)
        ?.takeIf { RUN_ID_PATTERN.matches(it) }
        ?.lowercase(Locale.ROOT)

    /** Check every account-store entry; unknown, backup, pending, or journal files fail closed. */
    private fun accountDirectoryIsEmpty(): Boolean {
        if (!accountDirectory.exists()) return true
        if (!accountDirectory.isDirectory) return false
        return accountDirectory.listFiles()?.isEmpty() == true
    }

    private fun accountTransactionsAbsent(): Boolean {
        if (!accountDirectory.exists()) return true
        if (!accountDirectory.isDirectory) return false
        val entries = accountDirectory.listFiles() ?: return false
        val stableNames = setOf(ACCOUNT_FILE, ACCOUNT_STATE_FILE)
        if (entries.any { it.name !in stableNames }) return false
        return true
    }

    private fun isEmptyAccount(repository: SchoolAccountRepository): Boolean =
        repository.state.value.status == SchoolAccountStatus.NOT_CONFIGURED &&
            !repository.state.value.cleanupRequired &&
            repository.accountReference.value == null

    private fun matchesExpectedNewAccount(
        repository: SchoolAccountRepository,
        reference: SchoolAccountReference,
        marker: OwnedMarker,
    ): Boolean =
        repository.state.value.status == SchoolAccountStatus.SAVED_UNVERIFIED &&
            !repository.state.value.cleanupRequired &&
            repository.state.value.generation.value == marker.expectedGeneration &&
            repository.accountReference.value == reference &&
            reference.credentialGeneration.value == marker.expectedGeneration

    /** Checks all app-owned persistent personal sessions/caches that delete() can invalidate. */
    private suspend fun hasExistingPersonalState(): Boolean {
        val timetableDirectory = File(context.noBackupFilesDir, TIMETABLE_DIRECTORY)
        if (timetableDirectory.exists() &&
            (!timetableDirectory.isDirectory || timetableDirectory.listFiles()?.isNotEmpty() != false)
        ) return true

        val cardCacheNames = listOf(
            CARD_CURRENT_FILE,
            CARD_LEGACY_FILE,
            CARD_MIGRATION_FILE,
            CARD_CLEAR_FILE,
        )
        if (cardCacheNames.any { hasAtomicArtifact(File(context.noBackupFilesDir, it)) }) return true

        // Saving a numeric school account triggers the host's legacy-draft removal observer.
        if (AppGraph.uiPrefsStore.flow.first().cardStudentId.isNotBlank()) return true
        if (context.getSharedPreferences(SCHOOL_SESSION_PREFS, Context.MODE_PRIVATE)
                .all.isNotEmpty()
        ) return true
        return schoolCookiesPresent()
    }

    private fun schoolCookiesPresent(): Boolean {
        val found = BooleanArray(1)
        instrumentation.runOnMainSync {
            val manager = CookieManager.getInstance()
            found[0] = SCHOOL_COOKIE_HOSTS.any { host ->
                SCHOOL_COOKIE_PATHS.any { path ->
                    !manager.getCookie(host + path).isNullOrBlank()
                }
            } || SERVICE_PORTAL_COOKIE_PATHS.any { path ->
                !manager.getCookie(SERVICE_PORTAL_COOKIE_HOST + path).isNullOrBlank()
            }
        }
        return found[0]
    }

    private fun hasAtomicArtifact(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()

    private fun hasAnyMarkerArtifact(): Boolean =
        markerFile.exists() || markerBackupFile().exists() || markerNewFile().exists()

    private fun markerBackupFile() = File(markerFile.path + ".bak")

    private fun markerNewFile() = File(markerFile.path + ".new")

    private fun consumeFixture(onConsumed: () -> Unit): CredentialFixture {
        if (!fixtureFile.isFile) throw HarnessAbort(FailureCode.FIXTURE_ABSENT)
        var bytes: ByteArray? = null
        var password: CharArray? = null
        var consumed = false
        var transferred = false
        try {
            val canonicalFixture = fixtureFile.canonicalFile
            if (canonicalFixture.parentFile != context.filesDir.canonicalFile ||
                canonicalFixture.name != FIXTURE_FILE ||
                fixtureFile.length() !in 1..MAX_FIXTURE_BYTES.toLong()
            ) throw HarnessAbort(FailureCode.FIXTURE_INVALID)
            consumed = true
            val fixtureBytes = fixtureFile.readBytes()
            bytes = fixtureBytes
            if (!fixtureFile.delete() && fixtureFile.exists()) {
                throw HarnessAbort(FailureCode.FIXTURE_DELETE_FAILED)
            }
            onConsumed()
            val json = JSONObject(String(fixtureBytes, Charsets.UTF_8))
            val keys = mutableSetOf<String>()
            val iterator = json.keys()
            while (iterator.hasNext()) keys += iterator.next()
            if (keys != setOf("account", "password")) {
                throw HarnessAbort(FailureCode.FIXTURE_INVALID)
            }
            val account = json.opt("account") as? String
                ?: throw HarnessAbort(FailureCode.FIXTURE_INVALID)
            val passwordText = json.opt("password") as? String
                ?: throw HarnessAbort(FailureCode.FIXTURE_INVALID)
            json.remove("account")
            json.remove("password")
            if (account.isBlank() || account.length > MAX_ACCOUNT_CHARS ||
                passwordText.isEmpty() || passwordText.length > MAX_PASSWORD_CHARS
            ) throw HarnessAbort(FailureCode.FIXTURE_INVALID)
            val passwordChars = passwordText.toCharArray()
            password = passwordChars
            transferred = true
            return CredentialFixture(account, passwordChars)
        } catch (abort: HarnessAbort) {
            throw abort
        } catch (_: Exception) {
            throw HarnessAbort(FailureCode.FIXTURE_INVALID)
        } finally {
            bytes?.fill(0)
            if (consumed && fixtureFile.exists() && !fixtureFile.delete() && fixtureFile.exists()) {
                password?.fill('\u0000')
                throw HarnessAbort(FailureCode.FIXTURE_DELETE_FAILED)
            }
            if (consumed && !fixtureFile.exists()) onConsumed()
            if (!transferred) password?.fill('\u0000')
        }
    }

    private fun writeMarker(marker: OwnedMarker): Boolean {
        val atomic = AtomicFile(markerFile)
        var output: FileOutputStream? = null
        return try {
            val stream = atomic.startWrite()
            output = stream
            val bytes = marker.toJson().toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            output?.let { runCatching { atomic.failWrite(it) } }
            false
        }
    }

    private fun readMarker(): OwnedMarker? {
        if (markerNewFile().exists()) return null
        if (!markerFile.exists() && !markerBackupFile().exists()) return null
        val markerSource = if (markerFile.exists()) markerFile else markerBackupFile()
        if (markerSource.length() !in 1..MAX_MARKER_BYTES.toLong()) return null
        var bytes: ByteArray? = null
        return try {
            val markerBytes = AtomicFile(markerFile).openRead().use { it.readBytes() }
            bytes = markerBytes
            if (markerBytes.size > MAX_MARKER_BYTES) return null
            val json = JSONObject(String(markerBytes, Charsets.UTF_8))
            val phase = MarkerPhase.entries.firstOrNull { it.name == json.optString("phase") }
                ?: return null
            val hasOwnedFields = phase != MarkerPhase.PREPARING
            val expectedKeys = if (hasOwnedFields) MARKER_OWNED_KEYS else MARKER_PREPARING_KEYS
            val keys = mutableSetOf<String>()
            val iterator = json.keys()
            while (iterator.hasNext()) keys += iterator.next()
            if (keys != expectedKeys || json.optInt("schema", -1) != MARKER_SCHEMA) return null
            val runId = json.optString("run_id")
            val baseline = json.optLong("baseline_generation", -1L)
            val expected = json.optLong("expected_generation", -1L)
            if (!RUN_ID_PATTERN.matches(runId) || baseline < 0 ||
                baseline >= Long.MAX_VALUE - 1 || expected != baseline + 1
            ) return null
            val accountId = if (hasOwnedFields) json.optString("account_id") else null
            val generation = if (hasOwnedFields) json.optLong("credential_generation", -1L) else null
            if (hasOwnedFields) {
                val ownedAccountId = accountId ?: return null
                if (ownedAccountId.isBlank() || !ACCOUNT_ID_PATTERN.matches(ownedAccountId) ||
                    generation != expected
                ) return null
            }
            OwnedMarker(
                phase = phase,
                runId = runId,
                baselineGeneration = baseline,
                expectedGeneration = expected,
                accountId = accountId,
                credentialGeneration = generation,
            )
        } catch (_: Exception) {
            null
        } finally {
            bytes?.fill(0)
        }
    }

    private fun OwnedMarker.toJson(): String = JSONObject()
        .put("schema", MARKER_SCHEMA)
        .put("phase", phase.name)
        .put("run_id", runId)
        .put("baseline_generation", baselineGeneration)
        .put("expected_generation", expectedGeneration)
        .apply {
            accountId?.let { put("account_id", it) }
            credentialGeneration?.let { put("credential_generation", it) }
        }
        .toString()

    private fun deleteOwnedMarker(runId: String): Boolean {
        if (markerNewFile().exists()) return false
        val marker = readMarker() ?: return false
        if (marker.runId != runId) return false
        try {
            AtomicFile(markerFile).delete()
        } catch (_: Exception) {
            return false
        }
        return !hasAnyMarkerArtifact()
    }

    private suspend fun cleanupMarker(
        repository: SchoolAccountRepository,
        originalMarker: OwnedMarker,
    ): CleanupOutcome {
        requireAcceptance(accountTransactionsAbsent(), FailureCode.ACCOUNT_TRANSACTION_PRESENT)
        var marker = originalMarker
        val state = repository.state.value

        if (marker.phase == MarkerPhase.PREPARING) {
            if (isEmptyAccount(repository) && state.generation.value == marker.baselineGeneration &&
                !accountCredentialArtifactsPresent()
            ) {
                requireAcceptance(deleteOwnedMarker(marker.runId), FailureCode.MARKER_DELETE_FAILED)
                return CleanupOutcome(cleanupOk = true, accountRemoved = false)
            }
            val reference = repository.accountReference.value
                ?: throw HarnessAbort(FailureCode.OWNERSHIP_MISMATCH)
            requireAcceptance(
                matchesExpectedNewAccount(repository, reference, marker),
                FailureCode.OWNERSHIP_MISMATCH,
            )
            marker = marker.copy(
                phase = MarkerPhase.OWNED,
                accountId = reference.accountId.value,
                credentialGeneration = reference.credentialGeneration.value,
            )
            requireAcceptance(writeMarker(marker), FailureCode.MARKER_WRITE_FAILED)
        }

        if (marker.phase == MarkerPhase.CLEANING && isDeletedState(repository, marker)) {
            requireAcceptance(deleteOwnedMarker(marker.runId), FailureCode.MARKER_DELETE_FAILED)
            return CleanupOutcome(cleanupOk = true, accountRemoved = true)
        }

        requireAcceptance(matchesMarkerReference(repository, marker), FailureCode.OWNERSHIP_MISMATCH)
        if (marker.phase != MarkerPhase.CLEANING) {
            marker = marker.copy(phase = MarkerPhase.CLEANING)
            requireAcceptance(writeMarker(marker), FailureCode.MARKER_WRITE_FAILED)
        }

        val deleteResult = repository.delete()
        requireAcceptance(deleteResult == SchoolAccountWriteResult.Success, FailureCode.ACCOUNT_DELETE_FAILED)
        requireAcceptance(isDeletedState(repository, marker), FailureCode.CLEANUP_NOT_CONFIRMED)
        requireAcceptance(deleteOwnedMarker(marker.runId), FailureCode.MARKER_DELETE_FAILED)
        return CleanupOutcome(cleanupOk = true, accountRemoved = true)
    }

    private fun matchesMarkerReference(
        repository: SchoolAccountRepository,
        marker: OwnedMarker,
    ): Boolean {
        val reference = repository.accountReference.value ?: return false
        return marker.accountId != null && marker.credentialGeneration != null &&
            marker.phase in setOf(MarkerPhase.OWNED, MarkerPhase.CLEANING) &&
            reference.accountId.value == marker.accountId &&
            reference.credentialGeneration.value == marker.credentialGeneration &&
            marker.credentialGeneration == marker.expectedGeneration &&
            repository.state.value.status == SchoolAccountStatus.SAVED_UNVERIFIED &&
            !repository.state.value.cleanupRequired &&
            repository.state.value.generation.value == marker.expectedGeneration
    }

    private fun isDeletedState(
        repository: SchoolAccountRepository,
        marker: OwnedMarker,
    ): Boolean =
        repository.state.value.status == SchoolAccountStatus.NOT_CONFIGURED &&
            !repository.state.value.cleanupRequired &&
            repository.state.value.generation.value == marker.expectedGeneration + 1 &&
            repository.accountReference.value == null &&
            !accountCredentialArtifactsPresent() &&
            accountTransactionsAbsent()

    private fun accountCredentialArtifactsPresent(): Boolean =
        hasAtomicArtifact(File(accountDirectory, ACCOUNT_FILE))

    private suspend fun rollbackSeed(
        repository: SchoolAccountRepository,
        runId: String,
    ): RollbackResult = try {
        if (markerNewFile().exists() || !accountTransactionsAbsent()) {
            RollbackResult.UNKNOWN
        } else {
            val marker = readMarker() ?: return RollbackResult.UNKNOWN
            if (marker.runId != runId) {
                RollbackResult.UNKNOWN
            } else {
                repository.restore()
                if (!accountTransactionsAbsent()) {
                    RollbackResult.UNKNOWN
                } else {
                    val outcome = cleanupMarker(repository, marker)
                    if (outcome.cleanupOk) RollbackResult.SUCCEEDED else RollbackResult.FAILED
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (abort: HarnessAbort) {
        when (abort.code) {
            FailureCode.ACCOUNT_DELETE_FAILED,
            FailureCode.CLEANUP_NOT_CONFIRMED,
            FailureCode.MARKER_WRITE_FAILED,
            FailureCode.MARKER_DELETE_FAILED,
            -> RollbackResult.FAILED
            else -> RollbackResult.UNKNOWN
        }
    } catch (_: Exception) {
        RollbackResult.UNKNOWN
    }

    private fun emit(report: AcceptanceReport) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("upc_method", report.method.name)
                putString("upc_stage", report.stage.name)
                putString("upc_result", report.result.name)
                putString("upc_failure_code", report.failure.name)
                putString("upc_rollback_result", report.rollback.name)
                putBoolean("upc_fixture_consumed", report.fixtureConsumed)
                putBoolean("upc_account_save_attempted", report.accountSaveAttempted)
                putBoolean("upc_account_saved", report.accountSaved)
                putBoolean("upc_account_removed", report.accountRemoved)
                putBoolean("upc_cleanup_ok", report.cleanupOk)
                putBoolean("upc_owned_marker_present", report.markerPresent)
                putInt("upc_harness_school_requests", 0)
            },
        )
    }

    private fun requireAcceptance(condition: Boolean, code: FailureCode) {
        if (!condition) throw HarnessAbort(code)
    }

    private data class CredentialFixture(
        val account: String,
        val password: CharArray,
    ) {
        fun clear() = password.fill('\u0000')
    }

    private data class OwnedMarker(
        val phase: MarkerPhase,
        val runId: String,
        val baselineGeneration: Long,
        val expectedGeneration: Long,
        val accountId: String? = null,
        val credentialGeneration: Long? = null,
    )

    private data class CleanupOutcome(
        val cleanupOk: Boolean,
        val accountRemoved: Boolean,
    )

    private class AcceptanceReport(val method: Method) {
        var stage = Stage.START
        var result = Result.FAILED
        var failure = FailureCode.NONE
        var rollback = RollbackResult.NOT_APPLICABLE
        var fixtureConsumed = false
        var accountSaveAttempted = false
        var accountSaved = false
        var accountRemoved = false
        var cleanupOk = false
        var markerPresent = false
    }

    private class HarnessAbort(val code: FailureCode) : Exception()

    private enum class Method { PREPARE_RELEASE_ACCOUNT, CLEANUP_OWNED_ACCOUNT }

    private enum class Stage {
        START,
        GUARD,
        FIXTURE,
        PREPARING,
        ACCOUNT_SAVE,
        OWNED_MARKER,
        ACCOUNT_DELETE,
        COMPLETE,
    }

    private enum class Result { SKIPPED, ACCOUNT_SEEDED, ACCOUNT_CLEANED, FAILED }

    private enum class RollbackResult { NOT_APPLICABLE, NOT_NEEDED, SUCCEEDED, FAILED, UNKNOWN }

    private enum class MarkerPhase { PREPARING, OWNED, CLEANING }

    private enum class FailureCode {
        NONE,
        OPT_IN_REQUIRED,
        ISOLATED_DEVICE_REQUIRED,
        RUN_ID_INVALID,
        FIXTURE_ABSENT,
        FIXTURE_INVALID,
        FIXTURE_DELETE_FAILED,
        OWNERSHIP_MARKER_PRESENT,
        OWNERSHIP_MARKER_MISSING,
        OWNERSHIP_MARKER_INVALID,
        OWNERSHIP_MISMATCH,
        ACCOUNT_ARTIFACT_PRESENT,
        ACCOUNT_TRANSACTION_PRESENT,
        PERSONAL_DATA_PRESENT,
        ACCOUNT_STATE_NOT_EMPTY,
        MARKER_WRITE_FAILED,
        MARKER_DELETE_FAILED,
        ACCOUNT_SAVE_FAILED,
        ACCOUNT_REFERENCE_MISSING,
        ACCOUNT_DELETE_FAILED,
        CLEANUP_NOT_CONFIRMED,
        OPERATION_CANCELLED,
        INTERNAL_FAILURE,
    }

    private companion object {
        const val ARG_OPT_IN = "upc_auth_acceptance_opt_in"
        const val OPT_IN_VALUE = "I_HAVE_AUTHORIZATION"
        const val ARG_ISOLATED_DEVICE = "upc_auth_acceptance_isolated_device"
        const val ISOLATED_DEVICE_VALUE = "I_CONFIRM_EXCLUSIVE_EMPTY_DEVICE"
        const val ARG_RUN_ID = "upc_auth_acceptance_run_id"

        const val FIXTURE_FILE = "auth-acceptance-credentials.json"
        const val MARKER_FILE = "auth-acceptance-owned.json"
        const val ACCOUNT_DIRECTORY = "school-account"
        const val ACCOUNT_FILE = "school-account-v1.enc"
        const val ACCOUNT_STATE_FILE = "school-account-state-v1.json"
        const val ACCOUNT_PENDING_FILE = "school-account-pending-v1.enc"
        const val ACCOUNT_TRANSITION_FILE = "school-account-transition-v1.json"
        const val TIMETABLE_DIRECTORY = "timetable"
        const val CARD_CURRENT_FILE = "card-balance-v2.json"
        const val CARD_LEGACY_FILE = "card-balance-v1.json"
        const val CARD_MIGRATION_FILE = "card-balance-migration-v2.json"
        const val CARD_CLEAR_FILE = "card-balance-clear-v2.json"
        const val SCHOOL_SESSION_PREFS = "school_session"
        const val MARKER_SCHEMA = 1
        const val MAX_FIXTURE_BYTES = 4 * 1024
        const val MAX_MARKER_BYTES = 2 * 1024
        const val MAX_ACCOUNT_CHARS = 128
        const val MAX_PASSWORD_CHARS = 512
        const val SERVICE_PORTAL_COOKIE_HOST = "https://service.upc.edu.cn"

        val RUN_ID_PATTERN = Regex("[0-9A-Fa-f]{8}")
        val ACCOUNT_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,128}")
        val MARKER_PREPARING_KEYS = setOf(
            "schema",
            "phase",
            "run_id",
            "baseline_generation",
            "expected_generation",
        )
        val MARKER_OWNED_KEYS = MARKER_PREPARING_KEYS + setOf("account_id", "credential_generation")

        val SCHOOL_COOKIE_HOSTS = listOf(
            "https://cas.upc.edu.cn",
            "https://mb-xyk.upc.edu.cn",
            "https://dekt.upc.edu.cn",
            "https://service.upc.edu.cn",
            "https://libspace.upc.edu.cn",
            "https://app.upc.edu.cn",
            "https://i.upc.edu.cn",
            "https://upc.edu.cn",
        )
        val SCHOOL_COOKIE_PATHS = listOf(
            "/",
            "/cas",
            "/cas/",
            "/cas/login",
            "/cas/cas",
            "/cas/cas/",
            "/cas/cas/login-mobile.html",
            "/cas/cas/login-normal.html",
            "/caslogin.jsp",
            "/sysmng",
            "/sysmng/",
            "/sysmng/login!loginByUserNoForCas.action",
            "/web",
            "/web/",
            "/web/index.html",
            "/berserker-auth",
            "/berserker-auth/",
            "/berserker-auth/cas",
            "/berserker-auth/cas/",
            "/berserker-auth/cas/redirect",
            "/berserker-auth/cas/redirect/wislearn",
            "/berserker-auth/cas/login",
            "/berserker-auth/cas/login/wislearn",
            "/berserker-auth/oauth",
            "/berserker-auth/oauth/token",
            "/plat",
            "/plat/",
            "/campus-card",
            "/campus-card/",
            "/libseat",
            "/libseat/",
            "/remote",
            "/remote/",
            "/remote/static",
            "/remote/static/",
            "/remote/static/sso",
            "/remote/static/sso/login",
            "/remote/static/casAuth",
            "/remote/static/casAuth/getServiceByVerifyTicket",
            "/remote/static/casAuth/getServiceByVerifyTicket/casLogin",
            "/site",
            "/site/",
            "/uc",
            "/uc/",
            "/freeclass",
            "/freeclass/",
            "/freeclass/wap",
            "/freeclass/wap/default",
            "/freeclass/wap/default/all",
        )
        val SERVICE_PORTAL_COOKIE_PATHS = listOf(
            "/api",
            "/api/",
            "/api/apps",
            "/api/apps/",
            "/api/apps/list",
            "/api/apps/detail",
            "/site",
            "/site/",
            "/site/login",
            "/site/login/",
            "/site/login/cas-login",
            "/site/message",
            "/site/message/count",
            "/site/my-task",
            "/site/my-task/todo",
            "/site/process",
            "/site/process/inst-list",
            "/v2",
            "/v2/",
            "/v2/site",
            "/v2/site/",
            "/v2/site/index",
        )
    }
}
