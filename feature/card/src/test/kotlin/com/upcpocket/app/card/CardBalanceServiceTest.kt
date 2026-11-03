package com.upcpocket.app.card

import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CardBalanceServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val id = "2099000999"

    private class MemoryInput : CardInputStore {
        override val studentId: Flow<String> = MutableStateFlow("")
        var clears = 0
        override suspend fun save(studentId: String) = Unit
        override suspend fun clear() {
            clears++
        }
    }

    /** 统计受控读取面被访问的次数；构造仓库后重置，验证失效器不反向读取账号。 */
    private class CountingReader(
        private val delegate: SchoolAccountStudentIdentityReader,
    ) : SchoolAccountStudentIdentityReader {
        var reads = 0

        override val state: StateFlow<SchoolAccountState>
            get() {
                reads++
                return delegate.state
            }

        override val studentIdentity: StateFlow<SchoolStudentIdentity?>
            get() {
                reads++
                return delegate.studentIdentity
            }
    }

    private fun service(account: FakeAccountIdentity, input: MemoryInput): CardBalanceService {
        val dir: File = temp.newFolder()
        return CardBalanceService(
            repositoryProvider = {
                DirectCardBalanceRepository(
                    DirectCardBalanceStore(dir),
                    account,
                ) { form ->
                    val studentId = java.net.URLDecoder
                        .decode(form.substringAfter("sno=").substringBefore("&"), "UTF-8")
                    """{"retcode":"0","card":[{"sno":"$studentId","db_balance":"100","unsettle_amount":"0"}]}"""
                }
            },
            account = account,
            legacyInput = input,
            onSnapshotChanged = {},
        )
    }

    @Test
    fun `target gate follows loading cleanup and valid student id`() {
        val account = FakeAccountIdentity()
        val service = service(account, MemoryInput())
        assertEquals(CardBindingAvailability.NO_ACCOUNT, service.bindingStatus().availability)
        assertFalse(service.canRefreshNow())

        account.bind(studentId = id)
        assertEquals(CardBindingAvailability.READY, service.bindingStatus().availability)
        assertTrue(service.canRefreshNow())
        assertNull(service.widgetStateNow().snapshot)
        val oldToken = service.bindingToken()
        assertTrue(service.isBindingTokenCurrent(oldToken))
        assertTrue(service.isBindingTokenCurrent(null))
        // 任务指纹只含内部归属与代次，不能泄露学号。
        assertFalse(oldToken.orEmpty().contains(id))

        account.cleaning()
        assertEquals(CardBindingAvailability.CLEANING, service.bindingStatus().availability)
        assertFalse(service.canRefreshNow())
        assertFalse(service.isBindingTokenCurrent(oldToken))
        assertNull(service.widgetStateNow().snapshot)
        assertNull(service.widgetStateNow().status.binding)

        account.unbind()
        assertFalse(service.canRefreshNow())
        assertEquals(CardBindingAvailability.NO_ACCOUNT, service.bindingStatus().availability)
    }

    @Test
    fun `prepare migrates and clears only the legacy draft when bound`() = runBlocking {
        val input = MemoryInput()
        val account = FakeAccountIdentity()
        val service = service(account, input)
        service.prepare()
        assertEquals(0, input.clears)

        account.bind(studentId = id)
        service.prepare()
        assertEquals(1, input.clears)
        assertNull(service.cached())
    }

    @Test
    fun `clear cache keeps account while account change cleanup clears draft`() = runBlocking {
        val input = MemoryInput()
        val account = FakeAccountIdentity().also { it.bind(studentId = id) }
        val service = service(account, input)
        val value = service.refreshCurrent()
        assertEquals(id, value.studentId)
        assertEquals(1, input.clears)

        service.clearCache()
        assertNull(service.widgetStateNow().snapshot)
        assertEquals(1, input.clears)
        assertTrue(service.canRefreshNow())
        assertEquals(CardBindingAvailability.READY, service.bindingStatus().availability)

        service.clearForAccountChange()
        assertEquals(2, input.clears)
        // 失效器只清业务数据，不修改账号状态/密码。
        assertEquals(CardBindingAvailability.READY, service.bindingStatus().availability)
    }

    @Test
    fun `account-change invalidator never reads back the account lock surface`() = runBlocking {
        val reader = CountingReader(FakeAccountIdentity())
        val directory: File = temp.newFolder()
        val input = MemoryInput()
        val repository = DirectCardBalanceRepository(DirectCardBalanceStore(directory), reader)
        val service = CardBalanceService(
            repositoryProvider = { repository },
            account = reader,
            legacyInput = input,
            onSnapshotChanged = {},
        )
        // 构造仓库/组合流时会获取 state/studentIdentity；真正失效调用不应再读取。
        reader.reads = 0

        // 账号锁内调用失效器时不得反向读取 state/studentIdentity，避免锁顺序死锁。
        service.clearForAccountChange()
        assertEquals(0, reader.reads)
        assertEquals(1, input.clears)
    }
}
