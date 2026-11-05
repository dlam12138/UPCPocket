package com.upcpocket.app.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.library.LibrarySessionExpiredException
import com.upcpocket.app.data.library.LibraryUser
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.platform.crypto.SessionCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class LibraryAccountSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val accountId = SchoolAccountId("acct_synthetic_library")
    private val otherAccountId = SchoolAccountId("acct_another_library")

    @Before
    fun clearSavedSession() {
        SchoolSessionStore(context, FakeSessionCrypto).clearLibrary()
    }

    @Test
    fun `account invalidation epoch rejects a late login write`() = runTest {
        val account = MutableAccount(savedState())
        val service = service(account)
        val loginContext = requireNotNull(service.captureLoginContext())
        service.signOut()

        assertFailure(
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            service.completeWebLogin(loginContext, "synthetic-token", false),
        )
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
    }

    @Test
    fun `current login context can publish and persist a bound library session`() = runTest {
        val account = MutableAccount(savedState())
        val service = service(account)

        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "synthetic-token",
                false,
            ),
        )
        assertEquals("synthetic-token", service.repository.session.value?.token)
        assertEquals(
            "synthetic-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )
        service.signOut()
    }

    @Test
    fun `manual login under saved A rejects B before publishing or persisting`() = runTest {
        val account = MutableAccount(savedState())
        var checkedCandidate: String? = null
        var publishedBeforeVerification = true
        var serviceRef: LibraryService? = null
        val service = service(
            account = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, candidate ->
                checkedCandidate = candidate
                publishedBeforeVerification =
                    serviceRef?.repository?.session?.value != null
                SchoolAccountIdentityCheck.MISMATCH
            },
            resolver = { _, _, _ ->
                LibrarySeatSession("b-token", LibraryUser("b-id", "B", "B"))
            },
        )
        serviceRef = service

        assertFailure(
            LibraryAccountAuthFailure.IDENTITY_MISMATCH,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "b-app-token",
                false,
            ),
        )
        assertEquals("B", checkedCandidate)
        assertFalse(publishedBeforeVerification)
        assertNull(service.repository.session.value)
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
    }

    @Test
    fun `manual login with the saved account publishes normally`() = runTest {
        val account = MutableAccount(savedState())
        var checkedCandidate: String? = null
        val service = service(
            account = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, candidate ->
                checkedCandidate = candidate
                SchoolAccountIdentityCheck.MATCH
            },
        )

        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "same-account-token",
                false,
            ),
        )
        assertEquals("synthetic", checkedCandidate)
        assertEquals("same-account-token", service.repository.session.value?.token)
        assertEquals(
            "same-account-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )
    }

    @Test
    fun `restore publishes a current bound session after identity check`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val service = service(account)
        service.sessionStore.saveLibrary(
            "saved-token",
            syntheticUser(),
            accountId,
            AccountGeneration(1),
        )

        val restored = service.restoreSavedLibrarySession()

        assertEquals("saved-token", restored?.token)
        assertEquals("saved-token", service.repository.session.value?.token)
        assertEquals(
            "saved-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )
    }

    @Test
    fun `account generation change rejects a login opened under the old generation`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val service = service(account)
        val loginContext = requireNotNull(service.captureLoginContext())

        account.set(savedState(generation = 2))

        assertFailure(
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            service.completeWebLogin(loginContext, "synthetic-token", false),
        )
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
    }

    @Test
    fun `cleanup state blocks context capture restore and writes`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val service = service(account)
        val oldContext = requireNotNull(service.captureLoginContext())
        service.sessionStore.saveLibrary(
            "synthetic-token",
            syntheticUser(),
            accountId,
            AccountGeneration(1),
        )

        account.set(cleanupState(generation = 1))

        assertNull(service.captureLoginContext())
        assertTrue(service.signOutForAccountChange())
        assertNull(service.restoreSavedLibrarySession())
        assertFailure(
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            service.completeWebLogin(oldContext, "synthetic-token", false),
        )
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
    }

    @Test
    fun `cleanup wait blocks new login and resumes only after success`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val cookieStarted = CompletableDeferred<Unit>()
        val cookieResult = CompletableDeferred<Boolean>()
        val service = service(
            account = account,
            clearAwait = {
                cookieStarted.complete(Unit)
                cookieResult.await()
            },
        )
        val oldContext = requireNotNull(service.captureLoginContext())
        service.sessionStore.saveLibrary(
            "synthetic-token",
            syntheticUser(),
            accountId,
            AccountGeneration(1),
        )
        account.set(cleanupState(generation = 1))

        val cleanup = async { service.signOutForAccountChange() }
        cookieStarted.await()

        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
        assertNull(service.captureLoginContext())
        assertNull(service.restoreSavedLibrarySession())
        assertFailure(
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            service.completeWebLogin(oldContext, "synthetic-token", false),
        )

        cookieResult.complete(true)
        assertTrue(cleanup.await())

        account.set(savedState(generation = 2))
        assertFailure(
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            service.completeWebLogin(oldContext, "synthetic-token", false),
        )
        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "synthetic-token",
                false,
            ),
        )
        assertEquals(
            "synthetic-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(2))?.token,
        )
        service.signOut()
    }

    @Test
    fun `cleanup failure keeps the gate closed and can be retried`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val cookieStarted = CompletableDeferred<Unit>()
        val service = service(
            account = account,
            clearAwait = {
                cookieStarted.complete(Unit)
                false
            },
        )
        service.sessionStore.saveLibrary(
            "synthetic-token",
            syntheticUser(),
            accountId,
            AccountGeneration(1),
        )
        account.set(cleanupState(generation = 1))

        val failed = async { service.signOutForAccountChange() }
        cookieStarted.await()
        assertFalse(failed.await())
        assertNull(service.captureLoginContext())
        assertNull(service.restoreSavedLibrarySession())
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
    }

    @Test
    fun `persisted session is rejected for another account or generation`() = runTest {
        val store = SchoolSessionStore(context, FakeSessionCrypto)
        store.saveLibrary(
            "synthetic-token",
            syntheticUser(),
            accountId,
            AccountGeneration(1),
        )

        assertNull(store.loadLibrary(otherAccountId, AccountGeneration(1)))
        assertNull(store.loadLibrary(accountId, AccountGeneration(2)))
    }

    @Test
    fun `manual login without a saved account is memory-only`() = runTest {
        val account = MutableAccount(openState())
        val service = service(
            account = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                error("未配置账号时不得调用身份校验")
            },
        )
        val loginContext = requireNotNull(service.captureLoginContext())

        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(loginContext, "synthetic-token", false),
        )
        assertEquals("synthetic-token", service.repository.session.value?.token)
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
        assertNull(service.sessionStore.loadLibrary(otherAccountId, AccountGeneration(1)))
    }

    @Test
    fun `automatic login publishes and persists the authenticated session`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val service = service(
            account = account,
            authenticator = LibraryAccountAuthenticator { expectedId, generation ->
                assertEquals(accountId, expectedId)
                assertEquals(1L, generation)
                LibraryAccountAuthResult.Success("auto-token", syntheticUser())
            },
        )

        val result = service.loginWithSavedAccount()

        assertEquals(LibraryAccountLoginResult.Success, result)
        assertEquals("auto-token", service.repository.session.value?.token)
        assertEquals(
            "auto-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )
    }

    @Test
    fun `late automatic login result after account generation change is rejected`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(
            account = account,
            authenticator = LibraryAccountAuthenticator { _, _ ->
                started.complete(Unit)
                release.await()
                LibraryAccountAuthResult.Success("late-token", syntheticUser())
            },
        )

        val job = async { service.loginWithSavedAccount() }
        started.await()
        account.set(savedState(generation = 2))
        release.complete(Unit)

        assertFailure(
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            job.await(),
        )
        assertNull(service.repository.session.value)
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
    }

    @Test
    fun `late restore failure cannot delete a newer login`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val restoreStarted = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val service = service(
            account = account,
            resolver = { token, _, _ ->
                if (token == "saved-token") {
                    restoreStarted.complete(Unit)
                    releaseRestore.await()
                    throw LibrarySessionExpiredException()
                }
                LibrarySeatSession("new-token", syntheticUser())
            },
        )
        service.sessionStore.saveLibrary(
            "saved-token",
            syntheticUser(),
            accountId,
            AccountGeneration(1),
        )

        val restore = async { service.restoreSavedLibrarySession() }
        restoreStarted.await()
        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "new-app-token",
                false,
            ),
        )
        releaseRestore.complete(Unit)
        assertNull(restore.await())

        assertEquals("new-token", service.repository.session.value?.token)
        assertEquals(
            "new-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )
    }

    @Test
    fun `late automatic failure cannot delete a newer login`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val autoStarted = CompletableDeferred<Unit>()
        val releaseAuto = CompletableDeferred<Unit>()
        val service = service(
            account = account,
            authenticator = LibraryAccountAuthenticator { _, _ ->
                autoStarted.complete(Unit)
                releaseAuto.await()
                LibraryAccountAuthResult.Success("old-auto-token", syntheticUser())
            },
            resolver = { token, _, _ ->
                LibrarySeatSession(
                    if (token == "new-app-token") "new-token" else "old-auto-token",
                    syntheticUser(),
                )
            },
        )

        val auto = async { service.loginWithSavedAccount() }
        autoStarted.await()
        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "new-app-token",
                false,
            ),
        )
        releaseAuto.complete(Unit)
        assertFailure(
            LibraryAccountAuthFailure.ACCOUNT_CHANGED,
            auto.await(),
        )

        assertEquals("new-token", service.repository.session.value?.token)
        assertEquals(
            "new-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )
    }

    @Test
    fun `stale expiry token cannot clear a newer session`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val service = service(account)
        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "new-token",
                false,
            ),
        )

        assertFalse(service.signOutIfCurrent("old-token"))
        assertEquals("new-token", service.repository.session.value?.token)
        assertEquals(
            "new-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )

        assertTrue(service.signOutIfCurrent("new-token"))
        assertNull(service.repository.session.value)
        assertNull(service.sessionStore.loadLibrary(accountId, AccountGeneration(1)))
    }

    @Test
    fun `late manual failure cannot delete a newer login`() = runTest {
        val account = MutableAccount(savedState(generation = 1))
        val manualStarted = CompletableDeferred<Unit>()
        val releaseManual = CompletableDeferred<Unit>()
        val service = service(
            account = account,
            resolver = { token, _, _ ->
                if (token == "old-app-token") {
                    manualStarted.complete(Unit)
                    releaseManual.await()
                    throw LibrarySessionExpiredException()
                }
                LibrarySeatSession("new-token", syntheticUser())
            },
        )

        val oldContext = requireNotNull(service.captureLoginContext())
        val old = async {
            service.completeWebLogin(oldContext, "old-app-token", false)
        }
        manualStarted.await()
        assertEquals(
            LibraryAccountLoginResult.Success,
            service.completeWebLogin(
                requireNotNull(service.captureLoginContext()),
                "new-app-token",
                false,
            ),
        )
        releaseManual.complete(Unit)
        assertFailure(LibraryAccountAuthFailure.ACCOUNT_CHANGED, old.await())

        assertEquals("new-token", service.repository.session.value?.token)
        assertEquals(
            "new-token",
            service.sessionStore.loadLibrary(accountId, AccountGeneration(1))?.token,
        )
    }

    @Test
    fun `repository refuses to publish a session while the account gate is closed`() {
        var accountOpen = true
        val repository = LibrarySeatRepository { accountOpen }
        val generation = repository.currentSessionGeneration()
        val session = syntheticSession()

        accountOpen = false
        assertFalse(repository.publishSessionIfCurrent(session, generation) { true })
        assertNull(repository.session.value)

        accountOpen = true
        assertTrue(repository.publishSessionIfCurrent(session, generation) { true })
        assertTrue(repository.session.value == session)
    }

    @Test
    fun `repository rejects token requests while the account gate is closed`() = runTest {
        var accountOpen = true
        val repository = LibrarySeatRepository { accountOpen }
        val generation = repository.currentSessionGeneration()
        assertTrue(repository.publishSessionIfCurrent(syntheticSession(), generation) { true })
        accountOpen = false

        val result = runCatching { repository.myReservations() }

        assertTrue(result.exceptionOrNull() is LibrarySessionExpiredException)
        assertNull(repository.session.value)
    }

    private fun assertFailure(
        expected: LibraryAccountAuthFailure,
        result: LibraryAccountLoginResult,
    ) {
        assertEquals(
            expected,
            (result as? LibraryAccountLoginResult.Failure)?.reason,
        )
    }

    private fun service(
        account: MutableAccount,
        identityVerifier: SchoolAccountIdentityVerifier =
            FakeIdentityVerifier(SchoolAccountIdentityCheck.MATCH),
        authenticator: LibraryAccountAuthenticator = LibraryAccountAuthenticator { _, _ ->
            error("测试不应自动认证")
        },
        resolver: suspend (String, Boolean, () -> Boolean) -> LibrarySeatSession = { token, _, _ ->
            LibrarySeatSession(token, syntheticUser())
        },
        clearAwait: suspend () -> Boolean = { true },
    ): LibraryService = LibraryService(
        repository = LibrarySeatRepository(
            sessionAllowed = { !account.state.value.blocksPersonalSessions },
        ),
        sessionStore = SchoolSessionStore(context, FakeSessionCrypto),
        settings = object : LibraryAccountSettings {
            override val rememberLogin = flowOf(true)
            override suspend fun setRememberLogin(enabled: Boolean) = Unit
        },
        accountGate = account,
        accountReference = account,
        identityVerifier = identityVerifier,
        authenticator = authenticator,
        clearCookies = {},
        clearSchoolCookiesAndAwait = clearAwait,
        resolveSeatSession = resolver,
    )

    private class FakeIdentityVerifier(
        var result: SchoolAccountIdentityCheck,
    ) : SchoolAccountIdentityVerifier {
        override suspend fun verifyLoginName(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: AccountGeneration,
            candidateLoginName: String,
        ): SchoolAccountIdentityCheck = result
    }

    private class MutableAccount(
        initial: SchoolAccountState,
        initialReference: SchoolAccountReference? = null,
    ) : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(initial)
        override val accountReference = MutableStateFlow(
            initialReference ?: if (initial.isCredentialSaved) {
                SchoolAccountReference(
                    SchoolAccountId("acct_synthetic_library"),
                    initial.generation,
                )
            } else {
                null
            },
        )

        fun set(next: SchoolAccountState) {
            state.value = next
            accountReference.value = if (next.isCredentialSaved) {
                SchoolAccountReference(
                    SchoolAccountId("acct_synthetic_library"),
                    next.generation,
                )
            } else {
                null
            }
        }
    }

    private fun openState(generation: Long = 0) = SchoolAccountState(
        status = SchoolAccountStatus.NOT_CONFIGURED,
        generation = AccountGeneration(generation),
    )

    private fun savedState(generation: Long = 1) = SchoolAccountState(
        status = SchoolAccountStatus.SAVED_UNVERIFIED,
        generation = AccountGeneration(generation),
        maskedStudentId = "20**********01",
        studentIdAvailable = true,
    )

    private fun cleanupState(generation: Long) = SchoolAccountState(
        status = SchoolAccountStatus.SWITCHING,
        generation = AccountGeneration(generation),
        cleanupRequired = true,
    )

    private fun syntheticUser() = LibraryUser("1", "synthetic", "合成用户")

    private fun syntheticSession() = LibrarySeatSession("synthetic-token", syntheticUser())

    private object FakeSessionCrypto : SessionCrypto {
        override fun encrypt(plain: String): String = "synthetic:$plain"

        override fun decrypt(stored: String): String = stored.removePrefix("synthetic:")
    }
}
