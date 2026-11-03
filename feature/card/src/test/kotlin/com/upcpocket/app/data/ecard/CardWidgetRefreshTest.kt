package com.upcpocket.app.data.ecard

import com.upcpocket.app.platform.RefreshInvalidatedException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 余额绑定、并发代次、清除、迁移与重启隔离。
 *
 * 全部使用虚构学号与内存 request fixture，不向学校提交任何查询。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CardWidgetRefreshTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val id = "2099000999"
    private val otherId = "2099000124"
    private val accountA = "acct_test_account_a"
    private val accountB = "acct_test_account_b"

    private fun directory() = temp.newFolder()
    private fun store(directory: File = directory()) = DirectCardBalanceStore(directory)

    private fun body(studentId: String, fetchedAt: Long = 1000) =
        """{"retcode":"0","card":[{"sno":"$studentId","db_balance":"12345","unsettle_amount":"678"}]}"""

    private fun idFromForm(form: String): String =
        java.net.URLDecoder.decode(form.substringAfter("sno=").substringBefore("&"), "UTF-8")

    private fun bodyForForm(form: String): String = body(idFromForm(form))

    private fun writeV1(
        directory: File,
        studentId: String = id,
        fetchedAt: Long = 1234,
    ) {
        File(directory, "card-balance-v1.json").writeText(
            """{"studentId":"$studentId","balanceCents":12345,"transitionCents":678,"fetchedAt":$fetchedAt}""",
        )
    }

    private fun writeMarker(
        directory: File,
        accountId: String = accountA,
        studentId: String = id,
        outcome: String = "MIGRATED",
    ) {
        File(directory, "card-balance-migration-v2.json").writeText(
            """{"format":"upcpocket.card-balance.migration","version":2,""" +
                """"accountId":"$accountId","studentId":"$studentId","outcome":"$outcome"}""",
        )
    }

    @Test
    fun `first save isolates orphan v2 from another account and mismatched v1`() = runBlocking {
        val dir = directory()
        val store = store(dir)
        // 模拟 FIRST_SAVE 前遗留：v2 属于 A，v1 也是 A 的学号，但当前首存账号是 B。
        store.saveCurrent(
            DirectCardSnapshot(accountA, id, 1, 111, 0, 500),
        )
        writeV1(dir, id, fetchedAt = 600)
        val accountBIdentity = FakeAccountIdentity().also {
            it.bind(accountB, otherId, generation = 1)
        }
        val repo = DirectCardBalanceRepository(store, accountBIdentity) {
            error("first-save isolation must not query")
        }

        assertNull(repo.cached())
        assertNull(repo.cachedBlocking())
        assertFalse(File(dir, "card-balance-v2.json").exists())
        assertFalse(File(dir, "card-balance-v1.json").exists())
        // 隔离完成后才允许用户主动查询 B。
        var requested: String? = null
        val querying = DirectCardBalanceRepository(store, accountBIdentity) { form ->
            requested = idFromForm(form)
            bodyForForm(form)
        }
        assertEquals(otherId, querying.refreshCurrent().studentId)
        assertEquals(otherId, requested)
    }

    @Test
    fun `matching marker with lost v2 re-migrates same-account v1`() = runBlocking {
        val dir = directory()
        writeV1(dir, id, fetchedAt = 4321)
        // marker 已提交但 v2 丢失（中断/手工删除）时，同号 v1 仍可幂等重建。
        writeMarker(dir, accountA, id, "MIGRATED")
        val repo = DirectCardBalanceRepository(
            store(dir),
            FakeAccountIdentity().also { it.bind(accountA, id) },
        ) { error("migration must not query") }

        val migrated = repo.cached()
        assertEquals(accountA, migrated?.accountId)
        assertEquals(id, migrated?.studentId)
        assertEquals(4321L, migrated?.fetchedAt)
        assertFalse(File(dir, "card-balance-v1.json").exists())
        assertTrue(File(dir, "card-balance-v2.json").exists())
        assertEquals(migrated, repo.cached())
    }

    @Test
    fun `failed cache clear stays hidden across restart and recovers without query`() = runBlocking {
        val dir = directory()
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        val repo = DirectCardBalanceRepository(store(dir), account) { bodyForForm(it) }
        repo.refreshCurrent()

        // 用非空目录替换 v2，使 AtomicFile 删除失败；这不是生产路径，只用于确定性注入失败。
        val v2 = File(dir, "card-balance-v2.json")
        assertTrue(v2.delete())
        assertTrue(v2.mkdirs())
        File(v2, "blocker").writeText("x")
        try {
            repo.clearCache()
            fail("clear must surface the deletion failure")
        } catch (_: Exception) {
        }
        // 清理失败后旧值立即隐藏，重启也不回退。
        assertNull(repo.cachedBlocking())
        assertNull(repo.cached())
        assertNull(repo.snapshot.first())
        assertTrue(File(dir, "card-balance-clear-v2.json").exists())

        val reopened = DirectCardBalanceRepository(store(dir), account) {
            error("pending clear must not query")
        }
        assertNull(reopened.cachedBlocking())
        assertNull(reopened.cached())

        // 修复文件后只恢复清理，不自动发起余额查询。
        File(v2, "blocker").delete()
        assertTrue(v2.delete())
        assertNull(reopened.cached())
        assertFalse(File(dir, "card-balance-clear-v2.json").exists())
        assertTrue(reopened.currentBinding() != null)
    }

    @Test
    fun `v1 cache is never displayed without a matching binding`() = runBlocking {
        val dir = directory()
        writeV1(dir, id)
        val repo = DirectCardBalanceRepository(
            store(dir),
            FakeAccountIdentity(),
        ) { error("must not query") }
        assertNull(repo.cached())
        assertNull(repo.cachedBlocking())
        // 未绑定时不迁移、不删除，等用户主动绑定后再判定同号/异号。
        assertTrue(File(dir, "card-balance-v1.json").exists())
    }

    @Test
    fun `A in flight switch to B rejects late A and B never displays A`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        val repo = DirectCardBalanceRepository(store(), account) { form ->
            val requested = idFromForm(form)
            if (requested == id) {
                started.complete(Unit)
                gate.await()
            }
            body(requested)
        }
        supervisorScope {
            val request = async(Dispatchers.Default) { repo.refreshCurrent() }
            started.await()
            account.bind(accountB, otherId, generation = 2)
            assertNull(repo.cached())
            assertNull(repo.snapshot.first())
            gate.complete(Unit)
            try {
                request.await()
                fail("late A result must be discarded after switch")
            } catch (_: RefreshInvalidatedException) {
            }
        }
        assertNull(repo.snapshot.first())
        val b = repo.refreshCurrent()
        assertEquals(otherId, b.studentId)
        assertEquals(accountB, b.accountId)
        assertEquals(otherId, repo.cached()?.studentId)
    }

    @Test
    fun `A in flight delete rejects late A and no account cannot query`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        val repo = DirectCardBalanceRepository(store(), account) {
            started.complete(Unit)
            gate.await()
            bodyForForm(it)
        }
        supervisorScope {
            val request = async(Dispatchers.Default) { repo.refreshCurrent() }
            started.await()
            account.unbind(generation = 2)
            assertNull(repo.cached())
            gate.complete(Unit)
            try {
                request.await()
                fail("late result after delete must be discarded")
            } catch (_: RefreshInvalidatedException) {
            }
        }
        assertNull(repo.snapshot.first())
        assertNull(repo.cachedBlocking())
        try {
            repo.refreshCurrent()
            fail("deleted account must not query")
        } catch (_: RefreshInvalidatedException) {
        }
    }

    @Test
    fun `same account password update invalidates in-flight request but keeps history`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val account = FakeAccountIdentity().also { it.bind(accountA, id, generation = 1) }
        var calls = 0
        val repo = DirectCardBalanceRepository(store(), account) { form ->
            calls++
            if (calls == 2) {
                started.complete(Unit)
                gate.await()
            }
            bodyForForm(form)
        }
        val history = repo.refreshCurrent()
        assertEquals(1L, history.credentialGeneration)

        supervisorScope {
            val request = async(Dispatchers.Default) { repo.refreshCurrent() }
            started.await()
            account.cleaning(generation = 2)
            assertNull(repo.snapshot.first())
            account.bind(accountA, id, generation = 2)
            gate.complete(Unit)
            try {
                request.await()
                fail("old credential generation must not publish")
            } catch (_: RefreshInvalidatedException) {
            }
        }
        // 同号改密保留历史余额，但仍带旧凭据代次，可明确显示为历史值。
        assertEquals(1L, repo.cached()?.credentialGeneration)
        val fresh = repo.refreshCurrent()
        assertEquals(2L, fresh.credentialGeneration)
        assertEquals(id, repo.cached()?.studentId)
    }

    @Test
    fun `clear cache during request increments business epoch and cannot be revived`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        val repo = DirectCardBalanceRepository(store(), account) { form ->
            started.complete(Unit)
            gate.await()
            bodyForForm(form)
        }
        supervisorScope {
            val request = async(Dispatchers.Default) { repo.refreshCurrent() }
            started.await()
            repo.clearCache()
            gate.complete(Unit)
            try {
                request.await()
                fail("cleared request must not publish")
            } catch (_: RefreshInvalidatedException) {
            }
        }
        assertNull(repo.cached())
        assertTrue(repo.currentBinding() != null)
    }

    @Test
    fun `B failure after switch never falls back to A display`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        val repo = DirectCardBalanceRepository(store(), account) { bodyForForm(it) }
        repo.refreshCurrent()
        account.bind(accountB, otherId, generation = 2)
        assertNull(repo.snapshot.first())
        assertNull(repo.cached())

        val account2 = FakeAccountIdentity().also { it.bind(accountB, otherId, generation = 2) }
        val repo2 = DirectCardBalanceRepository(store(), account2) {
            throw CampusCardException("网络失败")
        }
        try {
            repo2.refreshCurrent()
            fail("expected failure")
        } catch (_: CampusCardException) {
        }
        assertNull(repo2.cached())
        assertNull(repo2.snapshot.first())
    }

    @Test
    fun `v1 same student id migrates with account ownership and original query time`() = runBlocking {
        val dir = directory()
        writeV1(dir, id, fetchedAt = 4321)
        var requests = 0
        val repo = DirectCardBalanceRepository(store(dir), FakeAccountIdentity().also { it.bind(accountA, id) }) {
            requests++
            bodyForForm(it)
        }
        val migrated = repo.cached()
        assertEquals(accountA, migrated?.accountId)
        assertEquals(id, migrated?.studentId)
        assertEquals(4321L, migrated?.fetchedAt)
        assertEquals(1L, migrated?.credentialGeneration)
        assertEquals(0, requests)
        assertFalse(File(dir, "card-balance-v1.json").exists())
        assertTrue(File(dir, "card-balance-v2.json").exists())
        assertTrue(File(dir, "card-balance-migration-v2.json").exists())
        assertEquals(migrated, DirectCardBalanceRepository(store(dir), FakeAccountIdentity().also { it.bind(accountA, id) }) {
            fail("migration/read must not query")
            bodyForForm(it)
        }.cached())
    }

    @Test
    fun `v1 other student and corrupt v1 are discarded without becoming current`() = runBlocking {
        val mismatchDir = directory()
        writeV1(mismatchDir, otherId)
        var requests = 0
        val mismatch = DirectCardBalanceRepository(
            store(mismatchDir),
            FakeAccountIdentity().also { it.bind(accountA, id) },
        ) {
            requests++
            bodyForForm(it)
        }
        assertNull(mismatch.cached())
        assertEquals(0, requests)
        assertFalse(File(mismatchDir, "card-balance-v1.json").exists())

        val corruptDir = directory()
        File(corruptDir, "card-balance-v1.json").writeText("{broken")
        val corrupt = DirectCardBalanceRepository(
            store(corruptDir),
            FakeAccountIdentity().also { it.bind(accountA, id) },
        ) {
            requests++
            bodyForForm(it)
        }
        assertNull(corrupt.cached())
        assertEquals(0, requests)
        assertFalse(File(corruptDir, "card-balance-v1.json").exists())
    }

    @Test
    fun `interrupted v1 migration is idempotent and preserves v2`() = runBlocking {
        val dir = directory()
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        val store = store(dir)
        val migrated = DirectCardSnapshot(
            accountId = accountA,
            studentId = id,
            credentialGeneration = 1,
            balanceCents = 12345,
            transitionCents = 678,
            fetchedAt = 7777,
        )
        // 模拟 v2 已写、v1 尚未删除、marker 尚未写时中断。
        store.saveCurrent(migrated)
        writeV1(dir, id, fetchedAt = 7777)
        val repo = DirectCardBalanceRepository(store, account) { error("must not query") }
        assertEquals(migrated, repo.cached())
        assertFalse(File(dir, "card-balance-v1.json").exists())
        assertEquals(migrated, repo.cached())
    }

    @Test
    fun `old marker from other account cannot adopt v1 into current account`() = runBlocking {
        val dir = directory()
        writeV1(dir, otherId, fetchedAt = 8888)
        File(dir, "card-balance-migration-v2.json").writeText(
            """{"format":"upcpocket.card-balance.migration","version":2,""" +
                """"accountId":"acct_other_account","studentId":"$id","outcome":"MIGRATED"}""",
        )
        val repo = DirectCardBalanceRepository(
            store(dir),
            FakeAccountIdentity().also { it.bind(accountA, id) },
        ) { error("must not query") }
        assertNull(repo.cached())
        assertFalse(File(dir, "card-balance-v1.json").exists())
    }

    @Test
    fun `account change cleanup removes v1 v2 marker and never revives old target`() = runBlocking {
        val dir = directory()
        writeV1(dir, id, fetchedAt = 9999)
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        val repo = DirectCardBalanceRepository(store(dir), account) { error("must not query") }
        assertEquals(id, repo.cached()?.studentId)
        assertTrue(File(dir, "card-balance-v2.json").exists())
        assertTrue(File(dir, "card-balance-migration-v2.json").exists())

        repo.clearForAccountChange()
        assertFalse(File(dir, "card-balance-v1.json").exists())
        assertFalse(File(dir, "card-balance-v2.json").exists())
        assertFalse(File(dir, "card-balance-migration-v2.json").exists())
        assertNull(repo.cached())
        assertNull(repo.snapshot.first())

        // 同一学号的新账号也不能继承旧 marker/旧 v1。
        account.bind(accountB, id, generation = 2)
        assertNull(repo.cached())
    }

    @Test
    fun `restart with another account never exposes owned A cache`() = runBlocking {
        val dir = directory()
        val account = FakeAccountIdentity().also { it.bind(accountA, id) }
        DirectCardBalanceRepository(store(dir), account) { bodyForForm(it) }.refreshCurrent()
        val reopened = DirectCardBalanceRepository(
            store(dir),
            FakeAccountIdentity().also { it.bind(accountB, otherId, generation = 2) },
        ) { error("must not query") }
        assertNull(reopened.cached())
        assertNull(reopened.snapshot.first())
    }
}
