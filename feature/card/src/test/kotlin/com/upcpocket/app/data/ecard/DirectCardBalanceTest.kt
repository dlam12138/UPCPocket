package com.upcpocket.app.data.ecard

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DirectCardBalanceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `HTTP transport accepts school text plain JSON instead of triggering 406`() {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    check(reader.readLine().startsWith("POST /balance "))
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: error("incomplete headers")
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val length = headers.getValue("content-length").toInt()
                    val sentBody = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = reader.read(sentBody, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    check(String(sentBody) == DirectCardBalanceProtocol.form(id))
                    check(headers["cookie"].isNullOrEmpty())
                    val accepted = headers["accept"] == "*/*"
                    val bytes = (if (accepted) body() else "<html>406</html>").toByteArray()
                    val status = if (accepted) "200 OK" else "406 Not Acceptable"
                    socket.getOutputStream().write(
                        ("HTTP/1.1 $status\r\nContent-Type: text/plain;charset=UTF-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes,
                    )
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        try {
            val response = requestDirectCardBalance(
                DirectCardBalanceProtocol.form(id),
                "http://127.0.0.1:${server.localPort}/balance",
            )
            assertEquals(12345L, DirectCardBalanceProtocol.parse(response, id, 1).balanceCents)
        } finally {
            server.close()
            worker.join(6000)
        }
        failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
    }

    private val id = "2099000123" // fictitious; never sent to school

    private fun body(balance: String = "12345", transition: String = "678") =
        """{"retcode":"0","card":[{"sno":"$id","db_balance":"$balance","unsettle_amount":"$transition","name":"discard-fixture"}]}"""

    private fun accountBinding(): FakeAccountIdentity = FakeAccountIdentity().also {
        it.bind(studentId = id)
    }

    private fun store(): DirectCardBalanceStore = DirectCardBalanceStore(temp.newFolder())

    @Test
    fun `form uses supplied contract with matching student identifiers`() {
        assertEquals("sno=$id&xxbh=synjones&idtype=sno&id=$id", DirectCardBalanceProtocol.form(id))
        listOf("", "123", "2099000123&token=x", "１２３４５６７８", "12345678901234567").forEach {
            assertThrows(IllegalArgumentException::class.java) { DirectCardBalanceProtocol.form(it) }
        }
    }

    @Test
    fun `balances remain separate exact cents with negative and zero support`() {
        val result = DirectCardBalanceProtocol.parse(body(), id, 123)
        assertEquals("123.45", result.balanceText)
        assertEquals("6.78", result.transitionText)
        assertEquals("-0.01", DirectCardBalanceProtocol.parse(body("-1"), id, 123).balanceText)
        assertEquals("0.00", DirectCardBalanceProtocol.parse(body("0", "0"), id, 123).transitionText)
    }

    @Test
    fun `malformed failed multiple cards and wrong account never become zero`() {
        listOf(
            "<html>无效token</html>", "{}", "[]", body().replace("\"0\"", "\"1\""),
            body().replace(id, "2099000124"), body().replace("\"unsettle_amount\":\"678\",", ""),
            body().replace("\"db_balance\":\"12345\"", "\"db_balance\":null"),
            """{"retcode":0,"card":[]}""", """{"retcode":0,"card":[{},{}]}""",
            body("1.5"), body("999999999999999999999999"), body("NaN"),
        ).forEach {
            assertThrows(CampusCardException::class.java) {
                DirectCardBalanceProtocol.parse(it, id, 123)
            }
        }
    }

    @Test
    fun `numeric fields accepted and server data excluded from exception`() {
        assertEquals(12L, DirectCardBalanceProtocol.parse(body().replace("\"12345\"", "12"), id, 123).balanceCents)
        val error = assertThrows(CampusCardException::class.java) {
            DirectCardBalanceProtocol.parse("private-fixture", id, 1)
        }
        assertFalse(error.message.orEmpty().contains(id))
        assertFalse(error.message.orEmpty().contains("private-fixture"))
    }

    @Test
    fun `successful snapshot survives restart with same owner and clear keeps binding`() = runBlocking {
        val cache = store()
        val account = accountBinding()
        val repository = DirectCardBalanceRepository(cache, account) { body() }
        val value = repository.refreshCurrent()
        assertEquals(id, value.studentId)
        assertEquals("acct_test_account_a", value.accountId)
        assertEquals(value, DirectCardBalanceRepository(cache, accountBinding()) { error("no network") }.cached())
        assertEquals("123.45", repository.snapshot.first()?.balanceText)
        repository.clearCache()
        assertNull(repository.cached())
        // 清余额缓存不删除账号绑定。
        assertNotNull(repository.currentBinding())
        assertEquals(id, repository.currentBinding()?.studentId)
    }

    @Test
    fun `network and validation failures preserve same-owner previous snapshot`() = runBlocking {
        val cache = store()
        val account = accountBinding()
        val original = DirectCardBalanceRepository(cache, account) { body() }.refreshCurrent()
        val broken = DirectCardBalanceRepository(cache, accountBinding()) {
            throw java.io.IOException("offline fixture")
        }
        try {
            broken.refreshCurrent()
            fail()
        } catch (_: java.io.IOException) {
        }
        assertEquals(original, broken.cached())

        val mismatch = DirectCardBalanceRepository(cache, accountBinding()) { body().replace(id, "2099000124") }
        try {
            mismatch.refreshCurrent()
            fail()
        } catch (_: CampusCardException) {
        }
        assertEquals(original, mismatch.cached())
    }

    @Test
    fun `no configured account makes zero network requests and no cache`() = runBlocking {
        var requests = 0
        val repository = DirectCardBalanceRepository(store(), FakeAccountIdentity()) {
            requests++
            body()
        }
        try {
            repository.refreshCurrent()
            fail("no binding must not query")
        } catch (_: com.upcpocket.app.platform.RefreshInvalidatedException) {
        }
        assertNull(repository.cached())
        assertNull(repository.cachedBlocking())
        assertEquals(0, requests)
    }

    @Test
    fun `saved account without valid student id never queries`() = runBlocking {
        var requests = 0
        val account = FakeAccountIdentity().also { it.savedWithoutStudentId() }
        val repository = DirectCardBalanceRepository(store(), account) {
            requests++
            body()
        }
        try {
            repository.refreshCurrent()
            fail("invalid student id must not query")
        } catch (_: com.upcpocket.app.platform.RefreshInvalidatedException) {
        }
        assertEquals(0, requests)
        assertEquals(CardBindingAvailability.STUDENT_ID_UNAVAILABLE, cardBindingStatus(account).availability)
    }

    @Test
    fun `bound account without prior snapshot still queries current student id`() = runBlocking {
        var requested: String? = null
        val account = accountBinding()
        val repository = DirectCardBalanceRepository(store(), account) { form ->
            requested = form
            body()
        }
        assertNull(repository.cachedBlocking())
        repository.refreshCurrent()
        assertEquals(DirectCardBalanceProtocol.form(id), requested)
    }

    @Test
    fun `v1 cache is never displayed without a matching binding`() = runBlocking {
        val directory = temp.newFolder()
        File(directory, "card-balance-v1.json").writeText(
            """{"studentId":"$id","balanceCents":12345,"transitionCents":678,"fetchedAt":1234}""",
        )
        val repository = DirectCardBalanceRepository(
            DirectCardBalanceStore(directory),
            FakeAccountIdentity(),
        ) { body() }
        assertNull(repository.cached())
    }
}
