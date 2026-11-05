package com.upcpocket.app.timetable

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.SchoolAccountRepository
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.upcauth.UpcTimetableAuthenticator
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
 * 一次性的真实账号课表只读验证。
 *
 * 凭据只由宿主机通过 `run-as` 的标准输入写入 target app 私有临时文件；测试不打印
 * 账号、密码、Cookie 或课程原文，只输出固定状态和计数。运行结束删除账号、会话、
 * 缓存与临时文件。未提供私有文件时跳过，不访问学校。
 */
@RunWith(AndroidJUnit4::class)
class RealTimetableAuthTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inputFile = File(context.filesDir, "m3-credentials.json")
    private val accountDirectory = File(context.noBackupFilesDir, "school-account")
    private var shouldCleanup = false

    @Before
    fun ensureNoExistingAccount() {
        assumeTrue("未提供本机私有凭据文件，跳过真实课表测试", inputFile.isFile)
        val existing = File(accountDirectory, "school-account-v1.enc")
        assumeTrue("应用已有学校账号；为避免覆盖，跳过真实课表测试", !existing.exists())
    }

    @After
    fun cleanup() {
        inputFile.delete()
        if (!shouldCleanup) return
        runCatching {
            if (File(accountDirectory, "school-account-v1.enc").exists()) {
                runBlocking { SchoolAccountRepository(context).delete() }
            }
        }
        TimetableFiles.clearAll(context)
        shouldCleanup = false
    }

    @Test
    fun realCasLoginAndOneWeekRead() = runBlocking {
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

        val service = TimetableService(
            account = repository,
            authenticator = UpcTimetableAuthenticator(repository),
            api = TimetableApi(),
            sessionStore = TimetableSessionStore(context),
            cacheStore = TimetableCacheStore(context),
        )
        service.open()
        val state = service.state.value
        // 失败原因只输出固定枚举/计数，不输出账号、密码、Cookie 或课程原文。
        assertTrue(
            "课表连接状态=${state.connection}",
            state.connection == TimetableConnection.CONNECTED,
        )
        assertTrue(state.terms.isNotEmpty())
        assertTrue(state.selectedWeek != null)
        assertTrue(state.weekDates.size == 7)
        assertTrue(
            state.weekState == TimetableWeekState.READY ||
                state.weekState == TimetableWeekState.EMPTY,
        )
        val reference = repository.accountReference.value
        assertTrue(reference != null)
        val accountId = reference!!.accountId.value
        val cached = TimetableCacheStore(context).load(accountId)
        assertTrue(cached.index != null)
        assertTrue(cached.weeks.isNotEmpty())
        assertEquals(7, cached.weeks.first().dates.size)
        assertTrue(
            TimetableSessionStore(context).load(
                accountId,
                reference.credentialGeneration.value,
            ) != null,
        )
    }
}
