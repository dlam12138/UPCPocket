package com.upcpocket.app.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.data.chaoxing.ChaoxingException
import com.upcpocket.app.data.chaoxing.ChaoxingRepository
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingTenant
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
 * 一次性真实只读 Chaoxing 验证（Android 进程内）。
 *
 * 凭据/Cookie 只由宿主机通过 run-as 标准输入写入 target app 私有临时文件；测试不打印
 * 账号、密码、Cookie、学号、页面正文或房间名。未提供文件时跳过；只读区域/座位图/
 * 已用座位/研讨间，不提交预约、不取消、不签到、不加入团队。
 */
@RunWith(AndroidJUnit4::class)
class RealChaoxingAuthTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val credentialFile = File(context.filesDir, "m4-credentials.json")
    private val cookieFile = File(context.filesDir, "chaoxing-cookie.txt")
    private val accountDirectory = File(context.noBackupFilesDir, "school-account")
    private var shouldCleanup = false
    private var service: ChaoxingService? = null

    @Before
    fun ensureNoExistingAccount() {
        assumeTrue("未提供本机私有凭据文件，跳过真实 Chaoxing 测试", credentialFile.isFile)
        assumeTrue("未提供本机私有 Cookie 文件，跳过真实 Chaoxing 测试", cookieFile.isFile)
        val existing = File(accountDirectory, "school-account-v1.enc")
        assumeTrue("应用已有学校账号；为避免覆盖，跳过真实 Chaoxing 测试", !existing.exists())
    }

    @After
    fun cleanup() {
        credentialFile.delete()
        if (shouldCleanup) {
            runCatching { runBlocking { service?.signOutForAccountChange() } }
            runCatching {
                if (File(accountDirectory, "school-account-v1.enc").exists()) {
                    runBlocking { SchoolAccountRepository(context).delete() }
                }
            }
            runCatching { accountDirectory.deleteRecursively() }
        }
        cookieFile.delete()
        service = null
        shouldCleanup = false
    }

    @Test
    fun realChaoxingIdentitySeatAndReserveReadOnly() = runBlocking {
        val fixture = JSONObject(credentialFile.readText())
        val account = fixture.getString("account")
        val password = fixture.getString("password").toCharArray()
        val repository = SchoolAccountRepository(context)
        repository.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        assertEquals(SchoolAccountWriteResult.Success, repository.save(account, password))
        shouldCleanup = true
        assertTrue(password.all { it == '\u0000' })

        val cookie = cookieFile.readText().trim()
        val chaoxing = ChaoxingService(
            repository = ChaoxingRepository(),
            accountGate = repository,
            accountReference = repository,
            identityVerifier = repository,
            clearCookies = { true },
        )
        service = chaoxing

        val login = chaoxing.loginWithWebCookies(cookie)
        assertEquals(
            "身份/会话未通过：${(login as? ChaoxingLoginResult.Failure)?.reason}",
            ChaoxingLoginResult.Success,
            login,
        )
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

        // 当前稳定 URL 缺少学校业务目录上下文时会明确失败，不能当作空列表。
        val reserve = runCatching {
            chaoxing.reserveRooms(ChaoxingTenant.GUZHENKOU)
        }
        val failure = reserve.exceptionOrNull()
        if (failure != null) {
            assertTrue(failure is ChaoxingException)
            assertEquals("chaoxing_tenant_context", (failure as ChaoxingException).code)
        } else {
            assertTrue(reserve.getOrThrow().isNotEmpty())
        }
    }
}
