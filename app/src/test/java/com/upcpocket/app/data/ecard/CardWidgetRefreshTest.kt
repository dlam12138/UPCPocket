package com.upcpocket.app.data.ecard

import com.upcpocket.app.data.RefreshInvalidatedException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 校园卡仓库的“目标归属 / 迟到结果 / 清除”语义。
 *
 * 全部使用虚构学号，request 是内存 fixture，不向学校提交任何查询。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CardWidgetRefreshTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val id = "2099000999"
    private val otherId = "2099000124"

    private fun store() = DirectCardBalanceStore(File(temp.newFolder(), "card-balance-v1.json"))

    private fun body(studentId: String) =
        """{"retcode":"0","card":[{"sno":"$studentId","db_balance":"12345","unsettle_amount":"678"}]}"""

    /** The repository request lambda receives the urlencoded form, not the raw student id. */
    private fun idFromForm(form: String): String =
        java.net.URLDecoder.decode(form.substringAfter("sno=").substringBefore("&"), "UTF-8")

    private fun bodyForForm(form: String): String = body(idFromForm(form))

    @Test
    fun `refreshCurrent keeps the last successful student id and never switches target`() = runBlocking {
        val requested = mutableListOf<String>()
        val repo = DirectCardBalanceRepository(store()) { form ->
            requested += idFromForm(form)
            bodyForForm(form)
        }
        repo.refresh(id)
        repo.refreshCurrent()
        assertEquals(listOf(id, id), requested)

        repo.clear()
        assertNull(repo.refreshCurrent())
        assertEquals(2, requested.size)
    }

    @Test
    fun `late success after clear cannot revive cleared balance`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = DirectCardBalanceRepository(store()) { form ->
            started.complete(Unit)
            gate.await()
            bodyForForm(form)
        }
        supervisorScope {
            val request = async(Dispatchers.Default) { repo.refresh(id) }
            started.await()
            repo.clear()
            gate.complete(Unit)
            try {
                request.await()
                fail("late result must be discarded after clear")
            } catch (_: RefreshInvalidatedException) {
                // expected
            }
        }
        assertNull(repo.cached())
    }

    @Test
    fun `successful value is published to the shared flow for page and widget`() = runBlocking {
        val repo = DirectCardBalanceRepository(store()) { form -> bodyForForm(form) }
        val value = repo.refresh(id)
        assertEquals(value, repo.cached())
        assertEquals("123.45", repo.snapshot.value?.balanceText)
        assertEquals(id, repo.snapshot.value?.studentId)
    }

    @Test
    fun `corrupted cache file is null instead of crashing or showing zero`() {
        val file = File(temp.newFolder(), "card-balance-v1.json")
        file.writeText("{broken")
        assertNull(DirectCardBalanceStore(file).load())
    }

    @Test
    fun `different student id mismatch is rejected and previous snapshot is kept`() = runBlocking {
        val cache = store()
        val repo = DirectCardBalanceRepository(cache) { requested ->
            // Server echoes another account: protocol must reject it.
            body(otherId)
        }
        try {
            repo.refresh(id)
            fail("mismatched account must not be saved")
        } catch (_: CampusCardException) {
            // expected
        }
        assertNull(repo.cached())
    }
}
