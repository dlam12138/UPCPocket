package com.upcpocket.app.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibraryUser
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.library.LibraryAccountAuthFailure
import com.upcpocket.app.library.LibraryAccountAuthResult
import com.upcpocket.app.library.LibraryAccountAuthenticator
import com.upcpocket.app.library.LibraryAccountLoginResult
import com.upcpocket.app.library.LibraryAccountSettings
import com.upcpocket.app.library.LibraryService
import com.upcpocket.app.upcauth.UpcLibraryAuthFailure
import com.upcpocket.app.upcauth.UpcLibraryAuthResult
import com.upcpocket.app.upcauth.UpcLibraryAuthenticator
import java.io.File
import kotlinx.coroutines.flow.flowOf
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
 * 一次性的真实 libseat 认证与只读查询验证。
 *
 * 凭据只由宿主机通过 `run-as` 标准输入写入 target app 私有临时文件；测试不打印
 * 账号、密码、Cookie、token 或预约内容。运行结束删除账号、会话与临时文件；
 * 未提供私有文件时跳过，不访问学校，也不提交预约/取消/签到。
 */
@RunWith(AndroidJUnit4::class)
class RealLibraryAuthTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inputFile = File(context.filesDir, "m4-credentials.json")
    private val accountDirectory = File(context.noBackupFilesDir, "school-account")
    private var shouldCleanup = false
    private var service: LibraryService? = null

    @Before
    fun ensureNoExistingAccount() {
        assumeTrue("未提供本机私有凭据文件，跳过真实图书馆测试", inputFile.isFile)
        val existing = File(accountDirectory, "school-account-v1.enc")
        assumeTrue("应用已有学校账号；为避免覆盖，跳过真实图书馆测试", !existing.exists())
    }

    @After
    fun cleanup() {
        inputFile.delete()
        if (!shouldCleanup) return
        runCatching {
            runBlocking { service?.signOutForAccountChange() }
        }
        runCatching {
            if (File(accountDirectory, "school-account-v1.enc").exists()) {
                runBlocking { SchoolAccountRepository(context).delete() }
            }
        }
        service = null
        shouldCleanup = false
    }

    @Test
    fun realLibraryCasAndReadOnlyQueries() = runBlocking {
        val fixture = JSONObject(inputFile.readText())
        val account = fixture.getString("account")
        val password = fixture.getString("password").toCharArray()

        val repository = SchoolAccountRepository(context)
        repository.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(account, password),
        )
        shouldCleanup = true
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, repository.state.value.status)
        assertTrue(password.all { it == '\u0000' })

        val core = UpcLibraryAuthenticator(repository)
        val service = LibraryService(
            repository = LibrarySeatRepository {
                !repository.state.value.blocksPersonalSessions
            },
            sessionStore = SchoolSessionStore(context),
            settings = object : LibraryAccountSettings {
                override val rememberLogin = flowOf(true)
                override suspend fun setRememberLogin(enabled: Boolean) = Unit
            },
            accountGate = repository,
            accountReference = repository,
            authenticator = LibraryAccountAuthenticator { expectedId, generation ->
                when (val result = core.authenticate(expectedId, generation)) {
                    is UpcLibraryAuthResult.Success -> LibraryAccountAuthResult.Success(
                        result.seatToken,
                        LibraryUser(result.user.id, result.user.username, result.user.name),
                    )
                    is UpcLibraryAuthResult.Failure -> LibraryAccountAuthResult.Failure(
                        result.reason.toFeatureFailure(),
                        result.interactionUrl,
                    )
                }
            },
            clearCookies = {},
        )
        this@RealLibraryAuthTest.service = service

        val login = service.loginWithSavedAccount()
        assertTrue(
            "自动认证结果=${(login as? LibraryAccountLoginResult.Failure)?.reason}",
            login == LibraryAccountLoginResult.Success,
        )
        val session = requireNotNull(service.repository.session.value)
        assertTrue(session.user.id.isNotBlank())
        assertTrue(session.user.username.isNotBlank())

        val filters = service.repository.filters()
        assertTrue(filters.dates.isNotEmpty())
        assertTrue(filters.buildings.isNotEmpty())
        val reservations = service.repository.myReservations()
        // 只读：不打印预约条目的任何字段，只确认解析出的条目仍有稳定 ID。
        assertTrue(reservations.all { it.id.isNotBlank() })

        val reference = requireNotNull(repository.accountReference.value)
        val saved = SchoolSessionStore(context).loadLibrary(
            reference.accountId,
            reference.credentialGeneration,
        )
        assertEquals(session.token, saved?.token)

        // 清内存会话后，从绑定当前账号/代次的加密副本恢复，再核验一次 /rest/v2/user。
        service.repository.clearSession()
        val restored = service.restoreSavedLibrarySession()
        assertTrue(restored?.user?.username == session.user.username)
    }

    private fun UpcLibraryAuthFailure.toFeatureFailure(): LibraryAccountAuthFailure = when (this) {
        UpcLibraryAuthFailure.INVALID_CREDENTIALS ->
            LibraryAccountAuthFailure.INVALID_CREDENTIALS
        UpcLibraryAuthFailure.INTERACTION_REQUIRED ->
            LibraryAccountAuthFailure.INTERACTION_REQUIRED
        UpcLibraryAuthFailure.NETWORK_UNAVAILABLE ->
            LibraryAccountAuthFailure.NETWORK_UNAVAILABLE
        UpcLibraryAuthFailure.TLS_ERROR -> LibraryAccountAuthFailure.TLS_ERROR
        UpcLibraryAuthFailure.UPSTREAM_ERROR -> LibraryAccountAuthFailure.UPSTREAM_ERROR
        UpcLibraryAuthFailure.PROTOCOL_ERROR -> LibraryAccountAuthFailure.PROTOCOL_ERROR
        UpcLibraryAuthFailure.ACCOUNT_UNAVAILABLE ->
            LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE
        UpcLibraryAuthFailure.ACCOUNT_CHANGED -> LibraryAccountAuthFailure.ACCOUNT_CHANGED
        UpcLibraryAuthFailure.ACCESS_DENIED -> LibraryAccountAuthFailure.ACCESS_DENIED
        UpcLibraryAuthFailure.IDENTITY_MISMATCH ->
            LibraryAccountAuthFailure.IDENTITY_MISMATCH
    }
}
