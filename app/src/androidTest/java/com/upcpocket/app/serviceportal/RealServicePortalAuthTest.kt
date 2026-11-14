package com.upcpocket.app.serviceportal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.upcauth.UpcServicePortalAuthResult
import com.upcpocket.app.upcauth.UpcServicePortalAuthenticator
import com.upcpocket.app.upcauth.UpcServicePortalCookie
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

/** Uses a private fixture only after an explicit instrumentation argument opts in. */
@RunWith(AndroidJUnit4::class)
class RealServicePortalAuthTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val input = File(context.filesDir, "service-portal-credentials.json")
    private val existingAccount = File(context.noBackupFilesDir, "school-account/school-account-v1.enc")
    private var consumedFixture = false
    private var createdAccount = false
    private var authenticator: UpcServicePortalAuthenticator? = null

    @Before
    fun requireExplicitOptInAndIsolatedAccount() {
        assumeTrue(
            "SERVICE_PORTAL_REAL_TEST_OPT_IN_REQUIRED",
            InstrumentationRegistry.getArguments().getString("upc_service_portal_real_auth_opt_in") == "I_HAVE_AUTHORIZATION",
        )
        assumeTrue("SERVICE_PORTAL_PRIVATE_FIXTURE_ABSENT", input.isFile)
        assumeTrue("EXISTING_ACCOUNT_PRESERVED", !existingAccount.exists())
    }

    @After
    fun cleanup() {
        if (consumedFixture) input.delete()
        authenticator?.clearSession()
        authenticator = null
        if (!createdAccount) return
        runBlocking {
            val deleted = runCatching { SchoolAccountRepository(context).delete() }
            assertTrue("ACCOUNT_CLEANUP_FAILED", deleted.isSuccess && !existingAccount.exists())
        }
        createdAccount = false
    }

    @Test
    fun realCasLoginAndBusinessSessionCheck() = runBlocking {
        consumedFixture = true
        val fixture = try {
            JSONObject(input.readText())
        } catch (_: Exception) {
            throw AssertionError("PRIVATE_FIXTURE_INVALID")
        } finally {
            input.delete()
        }
        val accountName = fixture.getString("account")
        val password = fixture.getString("password").toCharArray()
        fixture.remove("account")
        fixture.remove("password")

        val repository = SchoolAccountRepository(context)
        repository.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        createdAccount = true
        val saved = try {
            repository.save(accountName, password)
        } finally {
            password.fill('\u0000')
        }
        assertEquals(SchoolAccountWriteResult.Success, saved)

        val reference = repository.accountReference.value
        assertTrue("ACCOUNT_REFERENCE_ABSENT", reference != null)
        val activeAuthenticator = UpcServicePortalAuthenticator(repository)
        authenticator = activeAuthenticator
        val result = activeAuthenticator.authenticate(
            reference!!.accountId,
            reference.credentialGeneration.value,
        )

        val status = when (result) {
            is UpcServicePortalAuthResult.Success -> {
                assertTrue(
                    "ACCOUNT_BINDING_MISMATCH",
                    result.binding.accountId == reference.accountId &&
                        result.binding.credentialGeneration == reference.credentialGeneration,
                )
                assertTrue(result.cookies.isNotEmpty())
                assertTrue(result.cookies.all { it.domain == UpcServicePortalCookie.HOST })
                instrumentation.sendStatus(
                    0,
                    android.os.Bundle().apply {
                        putString("service_portal_auth_result", "SUCCESS")
                        putString("service_portal_auth_source", result.source.name)
                        putInt("service_portal_cookie_count", result.cookies.size)
                        putBoolean("service_portal_account_bound", true)
                    },
                )
                "SUCCESS"
            }
            is UpcServicePortalAuthResult.Failure -> result.reason.name
        }
        assertTrue("AUTH_STATUS=$status", result is UpcServicePortalAuthResult.Success)
    }
}
