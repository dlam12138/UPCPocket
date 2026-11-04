package com.upcpocket.app.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.card.CardCapabilities
import com.upcpocket.app.card.CardInputStore
import com.upcpocket.app.card.CardPlugin
import com.upcpocket.app.classroom.ClassroomPlugin
import com.upcpocket.app.focus.FocusCapabilities
import com.upcpocket.app.focus.FocusPlugin
import com.upcpocket.app.library.LibraryAccountAuthenticator
import com.upcpocket.app.library.LibraryAccountSettings
import com.upcpocket.app.library.LibraryPlugin
import com.upcpocket.app.library.NspaceAccountAuthResult
import com.upcpocket.app.library.NspaceAccountAuthenticator
import com.upcpocket.app.library.NspaceAuthFailure
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.power.PowerInput
import com.upcpocket.app.power.PowerInputStore
import com.upcpocket.app.power.PowerPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 精简装配：不注册 card 插件时，其余插件仍能启动，首页/更多/页面快照里不再出现
 * 余额入口或余额区块；同时不触碰任何网络与数据库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ReducedAssemblyTest {

    @Test
    fun `without card plugin remaining ui and capabilities stay available`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val power = PowerPlugin(
            context = context,
            inputStore = object : PowerInputStore {
                override val input: Flow<PowerInput> = flowOf(PowerInput())
                override suspend fun save(building: String, room: String) = Unit
                override suspend fun clear() = Unit
            },
            onSnapshotChanged = {},
        )
        val classroom = ClassroomPlugin()
        val focus = FocusPlugin(context) { error("不应创建通知 PendingIntent") }
        val library = LibraryPlugin(
            context = context,
            accountSettings = object : LibraryAccountSettings {
                override val rememberLogin = flowOf(false)
                override suspend fun setRememberLogin(enabled: Boolean) = Unit
            },
            account = openAccount(),
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.UNAVAILABLE
            },
            authenticator = LibraryAccountAuthenticator { _, _ ->
                error("精简装配测试不得自动认证")
            },
            nspaceAuthenticator = unavailableNspaceAuthenticator(),
        )

        val runtime = PluginRuntime()
            .register(power.descriptor) { power }
            .register(classroom.descriptor) { classroom }
            .register(focus.descriptor) { focus }
            .register(library.descriptor) { library }
        val result = runtime.start()

        val active = result.plugins
        assertNull(active.capability(CardCapabilities.balance))

        val assembly = HostUiAssembly.build(active)
        val ui = assembly.snapshot
        assertTrue(assembly.diagnostics.isEmpty())
        assertTrue(ui.sectionsFor(UiSurface.HOME_CARD).isEmpty())
        assertEquals(
            listOf("power.electricity", "classroom.free", "library.home"),
            ui.entriesFor(UiSurface.HOME_SERVICES).map { it.entryId.value },
        )
        assertEquals(
            setOf(
                "power.electricity",
                "power.tangdaowan",
                "classroom.free",
                "library.home",
                "library.seat",
                "library.login",
                "library.nspace",
                "focus",
                "focus.stats",
            ),
            ui.pages.map { it.routeId.value }.toSet(),
        )
        assertEquals(listOf("focus.tab"), ui.bottomNav.map { it.entryId.value })
        assertFalse(ui.entries.any { it.pluginId.value == "card" })
    }

    @Test
    fun `without focus plugin there is no tab page restore entry or capability`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val card = CardPlugin(
            context = context,
            account = object : SchoolAccountStudentIdentityReader {
                override val state = MutableStateFlow(SchoolAccountState())
                override val studentIdentity = MutableStateFlow<SchoolStudentIdentity?>(null)
            },
            inputStore = object : CardInputStore {
                override val studentId: Flow<String> = flowOf("")
                override suspend fun save(studentId: String) = Unit
                override suspend fun clear() = Unit
            },
            onSnapshotChanged = {},
        )
        val power = PowerPlugin(
            context = context,
            inputStore = object : PowerInputStore {
                override val input: Flow<PowerInput> = flowOf(PowerInput())
                override suspend fun save(building: String, room: String) = Unit
                override suspend fun clear() = Unit
            },
            onSnapshotChanged = {},
        )
        val classroom = ClassroomPlugin()
        val library = LibraryPlugin(
            context = context,
            accountSettings = object : LibraryAccountSettings {
                override val rememberLogin = flowOf(false)
                override suspend fun setRememberLogin(enabled: Boolean) = Unit
            },
            account = openAccount(),
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.UNAVAILABLE
            },
            authenticator = LibraryAccountAuthenticator { _, _ ->
                error("精简装配测试不得自动认证")
            },
            nspaceAuthenticator = unavailableNspaceAuthenticator(),
        )

        val result = PluginRuntime()
            .register(card.descriptor) { card }
            .register(power.descriptor) { power }
            .register(classroom.descriptor) { classroom }
            .register(library.descriptor) { library }
            .start()
        val active = result.plugins

        assertNull(active.capability(FocusCapabilities.timer))
        assertNull(active.plugin(PluginId.parse("focus")))
        val ui = HostUiAssembly.build(active).snapshot
        assertTrue(ui.bottomNav.isEmpty())
        assertTrue(ui.pages.none { it.pluginId.value == "focus" })
        assertTrue(ui.entries.none { it.pluginId.value == "focus" })
        assertTrue(ui.sectionsFor(UiSurface.MORE_LEARNING).isEmpty())
    }

    private fun unavailableNspaceAuthenticator(): NspaceAccountAuthenticator =
        NspaceAccountAuthenticator { _, _ ->
            NspaceAccountAuthResult.Failure(NspaceAuthFailure.ACCOUNT_UNAVAILABLE)
        }

    private fun openAccount(): SchoolAccountReferenceReader =
        object : SchoolAccountReferenceReader {
            override val state = MutableStateFlow(
                SchoolAccountState(
                    status = SchoolAccountStatus.NOT_CONFIGURED,
                    generation = AccountGeneration.None,
                ),
            )
            override val accountReference = MutableStateFlow<SchoolAccountReference?>(null)
        }
}
