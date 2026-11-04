package com.upcpocket.app.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.plugin.runtime.PluginRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 图书馆插件的最小装配测试：不登录、不联网、不写会话密文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class LibraryPluginTest {

    @Test
    fun `plugin declares and provides independent seat and nspace capabilities`() {
        val plugin = LibraryPlugin(
            context = ApplicationProvider.getApplicationContext(),
            accountSettings = object : LibraryAccountSettings {
                override val rememberLogin = kotlinx.coroutines.flow.flowOf(false)
                override suspend fun setRememberLogin(enabled: Boolean) = Unit
            },
            account = object : SchoolAccountReferenceReader {
                override val state = MutableStateFlow(
                    SchoolAccountState(
                        status = SchoolAccountStatus.NOT_CONFIGURED,
                        generation = AccountGeneration.None,
                    ),
                )
                override val accountReference = MutableStateFlow<SchoolAccountReference?>(null)
            },
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.UNAVAILABLE
            },
            authenticator = LibraryAccountAuthenticator { _, _ ->
                error("插件装配测试不得自动认证")
            },
            nspaceAuthenticator = NspaceAccountAuthenticator { _, _ ->
                NspaceAccountAuthResult.Failure(NspaceAuthFailure.ACCOUNT_UNAVAILABLE)
            },
        )

        val result = PluginRuntime()
            .register(plugin.descriptor) { plugin }
            .start()

        val active = result.plugins
        assertEquals("library", plugin.descriptor.id.value)
        assertEquals(
            setOf("library.seat", "library.nspace", "library.chaoxing"),
            plugin.descriptor.capabilities,
        )
        assertSame(plugin.service, active.capability(LibraryCapabilities.seat))
        assertSame(plugin.nspaceService, active.capability(LibraryCapabilities.nspace))
        assertSame(plugin.chaoxingService, active.capability(LibraryCapabilities.chaoxing))
    }
}
