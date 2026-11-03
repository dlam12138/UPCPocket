package com.upcpocket.app.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountGate
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.library.LibraryUser
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.platform.crypto.SessionCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class LibraryAccountSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `account invalidation epoch rejects a late login write`() = runTest {
        val gate = MutableGate(openState())
        val service = service(gate)
        val loginContext = requireNotNull(service.captureLoginContext())
        service.signOut()

        assertFalse(
            service.persistLoginIfCurrent(
                loginContext,
                syntheticSession(),
            ),
        )
        assertNull(service.sessionStore.loadLibrary())
    }

    @Test
    fun `current login context can persist a library session`() = runTest {
        val service = service(MutableGate(openState()))

        assertTrue(
            service.persistLoginIfCurrent(
                requireNotNull(service.captureLoginContext()),
                syntheticSession(),
            ),
        )
        assertTrue(service.sessionStore.loadLibrary()?.token == "synthetic-token")
        service.signOut()
    }

    @Test
    fun `account generation change rejects a login opened under the old generation`() = runTest {
        val gate = MutableGate(openState(generation = 1))
        val service = service(gate)
        val loginContext = requireNotNull(service.captureLoginContext())

        gate.state.value = openState(generation = 2)

        assertFalse(service.persistLoginIfCurrent(loginContext, syntheticSession()))
        assertNull(service.sessionStore.loadLibrary())
    }

    @Test
    fun `cleanup state blocks context capture restore and writes`() = runTest {
        val gate = MutableGate(openState(generation = 1))
        val service = service(gate)
        val oldContext = requireNotNull(service.captureLoginContext())
        service.sessionStore.saveLibrary("synthetic-token", syntheticUser())

        gate.state.value = SchoolAccountState(
            status = SchoolAccountStatus.SWITCHING,
            generation = AccountGeneration(1),
            cleanupRequired = true,
        )

        assertNull(service.captureLoginContext())
        assertTrue(service.signOutForAccountChange())
        assertNull(service.restoreSavedLibrarySession())
        assertFalse(service.persistLoginIfCurrent(oldContext, syntheticSession()))
        assertNull(service.sessionStore.loadLibrary())
    }

    @Test
    fun `cleanup wait blocks new login and resumes only after success`() = runTest {
        val gate = MutableGate(openState(generation = 1))
        val cookieStarted = CompletableDeferred<Unit>()
        val cookieResult = CompletableDeferred<Boolean>()
        val service = service(
            gate = gate,
            clearAwait = {
                cookieStarted.complete(Unit)
                cookieResult.await()
            },
        )
        val oldContext = requireNotNull(service.captureLoginContext())
        service.sessionStore.saveLibrary("synthetic-token", syntheticUser())
        gate.state.value = SchoolAccountState(
            status = SchoolAccountStatus.SWITCHING,
            generation = AccountGeneration(1),
            cleanupRequired = true,
        )

        val cleanup = async { service.signOutForAccountChange() }
        cookieStarted.await()

        // invalidator 已清掉持久 token，但 Cookie 屏障还没返回。
        assertNull(service.sessionStore.loadLibrary())
        assertNull(service.captureLoginContext())
        assertNull(service.restoreSavedLibrarySession())
        assertFalse(service.persistLoginIfCurrent(oldContext, syntheticSession()))

        cookieResult.complete(true)
        assertTrue(cleanup.await())

        // 清理完成后账号进入新代次；旧上下文仍失效，新打开的手动登录才允许。
        gate.state.value = openState(generation = 2)
        assertFalse(service.persistLoginIfCurrent(oldContext, syntheticSession()))
        val freshContext = requireNotNull(service.captureLoginContext())
        assertTrue(service.persistLoginIfCurrent(freshContext, syntheticSession()))
        assertTrue(service.sessionStore.loadLibrary()?.token == "synthetic-token")
        service.signOut()
    }

    @Test
    fun `cleanup failure keeps the gate closed and can be retried`() = runTest {
        val gate = MutableGate(openState(generation = 1))
        val cookieStarted = CompletableDeferred<Unit>()
        val service = service(
            gate = gate,
            clearAwait = {
                cookieStarted.complete(Unit)
                false
            },
        )
        service.sessionStore.saveLibrary("synthetic-token", syntheticUser())
        gate.state.value = SchoolAccountState(
            status = SchoolAccountStatus.REMOVING,
            generation = AccountGeneration(1),
            cleanupRequired = true,
        )

        val failed = async { service.signOutForAccountChange() }
        cookieStarted.await()
        assertFalse(failed.await())
        assertNull(service.captureLoginContext())
        assertNull(service.restoreSavedLibrarySession())
        assertNull(service.sessionStore.loadLibrary())
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

        assertTrue(result.exceptionOrNull() is com.upcpocket.app.data.library.LibrarySessionExpiredException)
        assertNull(repository.session.value)
    }

    private fun service(
        gate: SchoolAccountGate,
        clearAwait: suspend () -> Boolean = { true },
    ): LibraryService = LibraryService(
        repository = LibrarySeatRepository(
            sessionAllowed = { !gate.state.value.blocksPersonalSessions },
        ),
        sessionStore = SchoolSessionStore(context, FakeSessionCrypto),
        settings = object : LibraryAccountSettings {
            override val rememberLogin = flowOf(true)
            override suspend fun setRememberLogin(enabled: Boolean) = Unit
        },
        accountGate = gate,
        clearCookies = {},
        clearSchoolCookiesAndAwait = clearAwait,
    )

    private class MutableGate(initial: SchoolAccountState) : SchoolAccountGate {
        override val state = MutableStateFlow(initial)
    }

    private fun openState(generation: Long = 0) = SchoolAccountState(
        status = SchoolAccountStatus.NOT_CONFIGURED,
        generation = AccountGeneration(generation),
    )

    private fun syntheticUser() = LibraryUser("1", "synthetic", "合成用户")

    private fun syntheticSession() = LibrarySeatSession("synthetic-token", syntheticUser())

    private object FakeSessionCrypto : SessionCrypto {
        override fun encrypt(plain: String): String = "synthetic:$plain"

        override fun decrypt(stored: String): String = stored.removePrefix("synthetic:")
    }
}
