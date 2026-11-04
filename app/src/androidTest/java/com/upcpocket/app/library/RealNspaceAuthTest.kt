package com.upcpocket.app.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.data.nspace.NspaceRepository
import com.upcpocket.app.data.nspace.NspaceUser
import com.upcpocket.app.library.NspaceAccountAuthResult
import com.upcpocket.app.library.NspaceAccountAuthenticator
import com.upcpocket.app.library.NspaceAuthFailure
import com.upcpocket.app.library.NspaceLoginResult
import com.upcpocket.app.library.NspaceService
import com.upcpocket.app.upcauth.UpcLibraryAuthFailure
import com.upcpocket.app.upcauth.UpcNspaceAuthResult
import com.upcpocket.app.upcauth.UpcNspaceAuthenticator
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

/**
 * 一次性的真实 nspace 认证与只读验证。
 *
 * 凭据只由宿主机通过 run-as 标准输入写入 target app 私有临时文件；测试不打印
 * 账号、密码、Cookie、token、房间名或预约内容。未提供私有文件时跳过；只读馆区/
 * 类型/日期/房间/本人预约，不提交预约/取消/签到。
 */
@RunWith(AndroidJUnit4::class)
class RealNspaceAuthTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inputFile = File(context.filesDir, "m4-credentials.json")
    private val accountDirectory = File(context.noBackupFilesDir, "school-account")
    private var shouldCleanup = false
    private var service: NspaceService? = null

    @Before
    fun ensureNoExistingAccount() {
        assumeTrue("未提供本机私有凭据文件，跳过真实 nspace 测试", inputFile.isFile)
        val existing = File(accountDirectory, "school-account-v1.enc")
        assumeTrue("应用已有学校账号；为避免覆盖，跳过真实 nspace 测试", !existing.exists())
    }

    @After
    fun cleanup() {
        inputFile.delete()
        if (shouldCleanup) {
            runCatching { runBlocking { service?.signOutForAccountChange() } }
            runCatching {
                if (File(accountDirectory, "school-account-v1.enc").exists()) {
                    runBlocking { SchoolAccountRepository(context).delete() }
                }
            }
            // delete() 可能留下不含密码的状态文件；测试结束连目录一起清理。
            runCatching { accountDirectory.deleteRecursively() }
        }
        service = null
        shouldCleanup = false
    }

    @Test
    fun realNspaceCasAndReadOnlyQueries() = runBlocking {
        val fixture = JSONObject(inputFile.readText())
        val account = fixture.getString("account")
        val password = fixture.getString("password").toCharArray()

        val repository = SchoolAccountRepository(context)
        repository.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        assertEquals(SchoolAccountWriteResult.Success, repository.save(account, password))
        shouldCleanup = true
        assertTrue(password.all { it == '\u0000' })

        val core = UpcNspaceAuthenticator(repository)
        val nspace = NspaceService(
            repository = NspaceRepository(),
            accountGate = repository,
            accountReference = repository,
            identityVerifier = repository,
            authenticator = NspaceAccountAuthenticator { expectedId, generation ->
                when (val result = core.authenticate(expectedId, generation)) {
                    is UpcNspaceAuthResult.Success -> NspaceAccountAuthResult.Success(
                        token = result.token,
                        user = NspaceUser(result.user.id, result.user.username, result.user.name),
                    )
                    is UpcNspaceAuthResult.Failure -> NspaceAccountAuthResult.Failure(
                        result.reason.toFeatureFailure(),
                        result.interactionUrl,
                    )
                }
            },
        )
        this@RealNspaceAuthTest.service = nspace

        val login = nspace.loginWithSavedAccount()
        assertTrue(
            "nspace 自动认证结果=${(login as? NspaceLoginResult.Failure)?.reason}",
            login == NspaceLoginResult.Success,
        )
        val session = requireNotNull(nspace.session.value)
        assertTrue(session.user.username.isNotBlank())

        val venues = nspace.venues()
        assertTrue("学校未返回馆区", venues.isNotEmpty())
        val venue = venues.first()
        val types = nspace.roomTypes(venue.id)
        assertTrue("学校未返回空间类型", types.isNotEmpty())
        val type = types.firstOrNull { it.enabled } ?: types.first()
        val dates = nspace.openDates(venue.id, type.id)
        assertTrue("学校未返回开放日期", dates.isNotEmpty())
        val rooms = nspace.rooms(venue.id, type.id, dates.first(), 1)
        assertTrue("分页计数应不小于返回条目数", rooms.totalCount >= rooms.rooms.size)
        val reservations = nspace.myReservations(1)
        assertTrue("预约分页计数不合法", reservations.totalCount >= reservations.reservations.size)
    }

    private fun UpcLibraryAuthFailure.toFeatureFailure(): NspaceAuthFailure = when (this) {
        UpcLibraryAuthFailure.INVALID_CREDENTIALS -> NspaceAuthFailure.INVALID_CREDENTIALS
        UpcLibraryAuthFailure.INTERACTION_REQUIRED -> NspaceAuthFailure.INTERACTION_REQUIRED
        UpcLibraryAuthFailure.NETWORK_UNAVAILABLE -> NspaceAuthFailure.NETWORK_UNAVAILABLE
        UpcLibraryAuthFailure.TLS_ERROR -> NspaceAuthFailure.TLS_ERROR
        UpcLibraryAuthFailure.UPSTREAM_ERROR -> NspaceAuthFailure.UPSTREAM_ERROR
        UpcLibraryAuthFailure.PROTOCOL_ERROR -> NspaceAuthFailure.PROTOCOL_ERROR
        UpcLibraryAuthFailure.ACCOUNT_UNAVAILABLE -> NspaceAuthFailure.ACCOUNT_UNAVAILABLE
        UpcLibraryAuthFailure.ACCOUNT_CHANGED -> NspaceAuthFailure.ACCOUNT_CHANGED
        UpcLibraryAuthFailure.ACCESS_DENIED -> NspaceAuthFailure.ACCESS_DENIED
        UpcLibraryAuthFailure.IDENTITY_MISMATCH -> NspaceAuthFailure.IDENTITY_MISMATCH
    }
}
