package com.upcpocket.app.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.library.LibraryUser
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.platform.crypto.SessionCrypto
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
        val service = service()
        val epoch = service.currentLoginEpoch()
        service.signOut()

        assertFalse(
            service.persistLoginIfCurrent(
                epoch,
                LibrarySeatSession("synthetic-token", LibraryUser("1", "synthetic", "合成用户")),
            ),
        )
        assertNull(service.sessionStore.loadLibrary())
    }

    @Test
    fun `current login epoch can persist a library session`() = runTest {
        val service = service()
        val epoch = service.currentLoginEpoch()

        assertTrue(
            service.persistLoginIfCurrent(
                epoch,
                LibrarySeatSession("synthetic-token", LibraryUser("1", "synthetic", "合成用户")),
            ),
        )
        assertTrue(service.sessionStore.loadLibrary()?.token == "synthetic-token")
        service.signOutForAccountChange()
    }

    private fun service(): LibraryService = LibraryService(
        repository = com.upcpocket.app.data.library.LibrarySeatRepository(),
        sessionStore = SchoolSessionStore(context, FakeSessionCrypto),
        settings = object : LibraryAccountSettings {
            override val rememberLogin = flowOf(true)
            override suspend fun setRememberLogin(enabled: Boolean) = Unit
        },
        clearCookies = {},
    )

    private object FakeSessionCrypto : SessionCrypto {
        override fun encrypt(plain: String): String = "synthetic:$plain"

        override fun decrypt(stored: String): String = stored.removePrefix("synthetic:")
    }
}
