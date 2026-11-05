package com.upcpocket.app.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.data.chaoxing.ChaoxingRepository
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.upcauth.UpcChaoxingAuthFailure
import com.upcpocket.app.upcauth.UpcChaoxingAuthResult
import com.upcpocket.app.upcauth.UpcChaoxingAuthenticator
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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
 * 真实自动认证 + 最小只读验证。
 *
 * 凭据只由宿主机通过 `run-as` 标准输入写入 target app 私有临时文件；测试不读取
 * Cookie 文件、不打印账号/密码/学号/区域名/座位名。未提供私有凭据时跳过。
 * 只做本人核验、区域列表、一个区域座位图与已用座位；不预约/取消/签到。
 */
@RunWith(AndroidJUnit4::class)
class RealChaoxingAutoAuthTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inputFile = File(context.filesDir, "m4-credentials.json")
    private val accountDirectory = File(context.noBackupFilesDir, "school-account")
    private var shouldCleanup = false
    private var service: ChaoxingService? = null

    @Before
    fun ensureNoExistingAccount() {
        assumeTrue("未提供本机私有凭据文件，跳过真实自动认证测试", inputFile.isFile)
        val existing = File(accountDirectory, "school-account-v1.enc")
        assumeTrue("应用已有学校账号；为避免覆盖，跳过真实自动认证测试", !existing.exists())
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
            runCatching { accountDirectory.deleteRecursively() }
        }
        service = null
        shouldCleanup = false
    }

    @Test
    fun realAutoLoginAndMinimalSeatReadOnly() = runBlocking {
        val fixture = JSONObject(inputFile.readText())
        val account = fixture.getString("account")
        val password = fixture.getString("password").toCharArray()

        val repository = SchoolAccountRepository(context)
        repository.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        assertEquals(SchoolAccountWriteResult.Success, repository.save(account, password))
        shouldCleanup = true
        assertTrue(password.all { it == '\u0000' })

        var autoCalls = 0
        val core = UpcChaoxingAuthenticator(repository)
        val chaoxing = ChaoxingService(
            repository = ChaoxingRepository(),
            accountGate = repository,
            accountReference = repository,
            identityVerifier = repository,
            authenticator = ChaoxingAccountAuthenticator { expectedId, generation ->
                autoCalls++
                when (val result = core.authenticate(expectedId, generation)) {
                    is UpcChaoxingAuthResult.Success ->
                        ChaoxingAccountAuthResult.Success(result.cookieHeader)
                    is UpcChaoxingAuthResult.Failure ->
                        ChaoxingAccountAuthResult.Failure(
                            result.reason.toFeatureFailure(),
                            result.interactionUrl,
                        )
                }
            },
            clearCookies = { true },
        )
        service = chaoxing

        val login = chaoxing.loginWithSavedAccount()
        assertEquals(
            "自动认证未通过：${(login as? ChaoxingLoginResult.Failure)?.reason}",
            ChaoxingLoginResult.Success,
            login,
        )
        assertEquals(1, autoCalls)
        val session = requireNotNull(chaoxing.session.value)
        assertTrue(session.user.sno.isNotBlank())

        val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val seat = chaoxing.seatRooms(ChaoxingSeatKind.SEAT, date)
        assertTrue("学校未返回 seat 区域", seat.rooms.isNotEmpty())
        val detail = chaoxing.seatRoomDetail(
            ChaoxingSeatKind.SEAT,
            seat.rooms.first().id,
            date,
        )
        assertTrue(detail.timeUnit > 0)
        assertTrue(detail.places.isNotEmpty())
        assertEquals(detail.capacity, detail.places.map { it.seatNum }.toSet().size)
        chaoxing.usedSeatNums(
            ChaoxingSeatKind.SEAT,
            detail.roomId,
            detail.startMinute,
            detail.startMinute + detail.timeUnit,
            date,
        )

        val engine = chaoxing.seatRooms(ChaoxingSeatKind.SEATENGINE, date)
        assertTrue("学校未返回 seatengine 区域", engine.rooms.isNotEmpty())
    }

    private fun UpcChaoxingAuthFailure.toFeatureFailure(): ChaoxingAuthFailure = when (this) {
        UpcChaoxingAuthFailure.INVALID_CREDENTIALS -> ChaoxingAuthFailure.INVALID_CREDENTIALS
        UpcChaoxingAuthFailure.INTERACTION_REQUIRED -> ChaoxingAuthFailure.INTERACTION_REQUIRED
        UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE -> ChaoxingAuthFailure.NETWORK_UNAVAILABLE
        UpcChaoxingAuthFailure.TLS_ERROR -> ChaoxingAuthFailure.TLS_ERROR
        UpcChaoxingAuthFailure.UPSTREAM_ERROR -> ChaoxingAuthFailure.UPSTREAM_ERROR
        UpcChaoxingAuthFailure.PROTOCOL_ERROR -> ChaoxingAuthFailure.PROTOCOL_ERROR
        UpcChaoxingAuthFailure.ACCOUNT_UNAVAILABLE -> ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE
        UpcChaoxingAuthFailure.ACCOUNT_CHANGED -> ChaoxingAuthFailure.ACCOUNT_CHANGED
        UpcChaoxingAuthFailure.ACCESS_DENIED -> ChaoxingAuthFailure.ACCESS_DENIED
    }
}
