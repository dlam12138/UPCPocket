package com.upcpocket.app.shiguang

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.upcauth.UpcShiguangAuthResult
import com.upcpocket.app.upcauth.UpcShiguangAuthFailure
import com.upcpocket.app.upcauth.UpcShiguangAuthenticator
import com.upcpocket.app.upcauth.ShiguangAuthenticator
import com.upcpocket.app.account.api.SchoolAccountId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in fixture in private files only. Auth includes one activity page and a read-only identity query. */
@RunWith(AndroidJUnit4::class)
class RealShiguangAuthTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val input = File(context.filesDir, "shiguang-credentials.json")
    private val existingAccount = File(context.noBackupFilesDir, "school-account/school-account-v1.enc")
    private var createdAccount = false
    private var activeService: ShiguangService? = null

    @Before fun requireIsolatedAccount() {
        assumeTrue("PRIVATE_INPUT_ABSENT", input.isFile)
        assumeTrue("EXISTING_ACCOUNT_PRESERVED", !existingAccount.exists())
    }

    @After fun cleanup() {
        input.delete()
        runBlocking { activeService?.invalidateForAccountChange() }
        activeService = null
        if (createdAccount) {
            runBlocking {
                val result = runCatching { SchoolAccountRepository(context).delete() }
                assertTrue("ACCOUNT_CLEANUP_FAILED", result.isSuccess && !existingAccount.exists())
            }
            createdAccount = false
        }
    }

    @Test fun realCasLoginAndReadOnlyIdentityCheck() = runBlocking {
        val report = android.os.Bundle()
        try {
            val fixture = try { JSONObject(input.readText()) } finally { input.delete() }
            val account = fixture.getString("account")
            val password = fixture.getString("password").toCharArray()
            fixture.remove("account")
            fixture.remove("password")
            val repository = SchoolAccountRepository(context)
            repository.restore()
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
            // Set before save, so any partially completed write is cleaned up as well.
            createdAccount = true
            try {
                assertEquals(SchoolAccountWriteResult.Success, repository.save(account, password))
            } finally { password.fill('\u0000') }
            val reference = repository.accountReference.value
            assertTrue("ACCOUNT_REFERENCE_ABSENT", reference != null)
            val result = UpcShiguangAuthenticator(repository).authenticate(
                reference!!.accountId, reference.credentialGeneration.value,
            )
            val status = when (result) {
                is UpcShiguangAuthResult.Success -> "CONNECTED"
                is UpcShiguangAuthResult.Failure -> result.reason.name
            }
            report.putString("shiguang_status", status)
            assertTrue("AUTH_STATUS=$status", result is UpcShiguangAuthResult.Success)
            val success = result as UpcShiguangAuthResult.Success
            assertTrue("SESSION_ABSENT", success.cookies.any { it.name == "SESSION" })
            // Reuse the successful session, bound to the same account and generation.
            // This never calls the native authenticator again or submits another password.
            val cachedAuthenticator = object : ShiguangAuthenticator {
                override suspend fun authenticate(
                    expectedAccountId: SchoolAccountId,
                    expectedCredentialGeneration: Long,
                ): UpcShiguangAuthResult = if (
                    expectedAccountId == reference.accountId &&
                    expectedCredentialGeneration == reference.credentialGeneration.value
                ) success else UpcShiguangAuthResult.Failure(UpcShiguangAuthFailure.ACCOUNT_CHANGED)
            }
            val service = ShiguangPlugin(repository, cachedAuthenticator).service
            activeService = service
            // Only fixed states/booleans/counts leave this test. No business content persists.
            report.putBoolean("shiguang_identity_verified", success.identityVerified)
            report.putInt("shiguang_cookie_count", success.cookies.size)
            report.putBoolean("shiguang_detail_verified", false)
            report.putBoolean("shiguang_personal_checked", false)
            report.putString("shiguang_read_stage", "ACTIVITIES")
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
            service.activities(keyword = "", page = 1)
            assertTrue("READ_STATUS=${service.state.value.error?.name ?: "READ_OK"}", service.state.value.error == null)
            val activities = service.state.value.activities
            assertTrue("ACTIVITY_PAGE_ABSENT", activities != null)
            report.putInt("shiguang_activity_count", activities!!.items.size)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
            activities.items.firstOrNull()?.let { first ->
                report.putString("shiguang_read_stage", "DETAIL")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
                service.detail(first)
                assertTrue("READ_STATUS=${service.state.value.error?.name ?: "READ_OK"}", service.state.value.error == null)
                assertTrue("DETAIL_ABSENT", service.state.value.detail != null)
                report.putBoolean("shiguang_detail_verified", true)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
                service.closeDetail()
            }
            if (success.identityVerified) {
                report.putString("shiguang_read_stage", "SUMMARY_AND_PARTICIPATIONS")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
                service.mine(page = 1)
                assertTrue("READ_STATUS=${service.state.value.error?.name ?: "READ_OK"}", service.state.value.error == null)
                val personal = service.state.value
                assertTrue("SUMMARY_ABSENT", personal.summary != null)
                assertTrue("PARTICIPATION_PAGE_ABSENT", personal.participations != null)
                report.putInt("shiguang_summary_module_count", personal.summary!!.modules.size)
                report.putInt("shiguang_participation_count", personal.participations!!.items.size)
                report.putBoolean("shiguang_personal_checked", true)
                report.putString("shiguang_read_status", "READ_OK")
            } else {
                // Do not invoke mine or issue any personal query without identity verification.
                report.putInt("shiguang_summary_module_count", 0)
                report.putInt("shiguang_participation_count", 0)
                report.putString("shiguang_read_status", "IDENTITY_UNVERIFIED")
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
        } catch (assertion: AssertionError) {
            throw assertion
        } catch (_: Exception) {
            throw AssertionError("SHIGUANG_REAL_TEST_FAILED")
        } finally {
            activeService?.state?.value?.error?.let { failure ->
                report.putString("shiguang_read_status", failure.name)
            }
            // Preserve completed-stage metadata even when the next stage fails.
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
        }
    }
}
