package com.upcpocket.app.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 只在 developer 有本机一次性凭据文件时运行的私有设备测试。
 *
 * 凭据文件由宿主机通过 `run-as` 以 stdin 写入 `files/m1-credentials.json`，
 * 不经过 Gradle 参数、日志、Intent 或 adb input。测试代码与仓库不包含真实值；
 * 测试结束删除凭据文件与隔离账号目录。它只验证 Android Keystore 往返与磁盘密文，
 * 不联网，也不调用任何校园业务。
 */
@RunWith(AndroidJUnit4::class)
class RealKeystoreAccountTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inputFile = File(context.filesDir, "m1-credentials.json")
    private val accountDirectory = File(context.noBackupFilesDir, "m1-instrumented-account")

    @Before
    fun reset() {
        accountDirectory.deleteRecursively()
    }

    @After
    fun cleanup() {
        accountDirectory.deleteRecursively()
        inputFile.delete()
    }

    @Test
    fun realKeystoreSaveRestoreUpdateAndDelete() = runBlocking {
        assumeTrue("本机私有凭据文件不存在，跳过真实 Keystore 集成测试", inputFile.isFile)
        val fixture = JSONObject(inputFile.readText())
        val account = fixture.getString("account")
        val password = fixture.getString("password")
        val password2 = fixture.getString("password2")

        val repository = SchoolAccountRepository(accountDirectory)
        repository.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)

        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(account, password.toCharArray()),
        )
        assertSaved(repository.state.value.generation, 1, password, account, repository.state.value.maskedAccount)
        val firstCiphertext = credentialBytes(accountDirectory)
        assertFalse(firstCiphertext.toString(Charsets.UTF_8).contains(account))
        assertFalse(firstCiphertext.toString(Charsets.UTF_8).contains(password))

        // 新实例只靠磁盘状态和真实 Keystore 恢复，模拟进程重启后的读取路径。
        val restored = SchoolAccountRepository(accountDirectory)
        restored.restore()
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, restored.state.value.status)
        assertEquals(AccountGeneration(1), restored.state.value.generation)

        assertEquals(
            SchoolAccountWriteResult.Success,
            restored.save(account, password2.toCharArray()),
        )
        assertSaved(restored.state.value.generation, 2, password2, account, restored.state.value.maskedAccount)
        val secondCiphertext = credentialBytes(accountDirectory)
        assertFalse(firstCiphertext.contentEquals(secondCiphertext))
        assertFalse(secondCiphertext.toString(Charsets.UTF_8).contains(password2))
        assertFalse(secondCiphertext.toString(Charsets.UTF_8).contains(account))

        assertEquals(SchoolAccountWriteResult.Success, restored.delete())
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, restored.state.value.status)
        assertFalse(File(accountDirectory, "school-account-v1.enc").exists())
        assertFalse(
            File(accountDirectory, "school-account-state-v1.json").exists() &&
                JSONObject(File(accountDirectory, "school-account-state-v1.json").readText())
                    .optBoolean("cleanupRequired"),
        )
    }

    private fun assertSaved(
        actualGeneration: AccountGeneration,
        expectedGeneration: Long,
        password: String,
        account: String,
        maskedAccount: String?,
    ) {
        assertEquals(AccountGeneration(expectedGeneration), actualGeneration)
        // 密码只用于确认测试输入没有被持久化；断言消息不包含其值。
        assertTrue(password.isNotEmpty())
        assertFalse(account.isBlank())
        assertTrue(maskedAccount != null)
        assertFalse(maskedAccount == account)
        assertFalse(maskedAccount.orEmpty().contains(account))
    }

    private fun credentialBytes(directory: File): ByteArray =
        File(directory, "school-account-v1.enc").readBytes()
}
