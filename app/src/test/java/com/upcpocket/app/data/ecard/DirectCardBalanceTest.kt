package com.upcpocket.app.data.ecard

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DirectCardBalanceTest {
    @Test fun `HTTP transport accepts school text plain JSON instead of triggering 406`() {
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
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes
                    )
                }
            } catch (e: Throwable) { failure.set(e) }
        }
        try {
            val response = requestDirectCardBalance(DirectCardBalanceProtocol.form(id), "http://127.0.0.1:${server.localPort}/balance")
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
    private fun store(): DirectCardBalanceStore {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return DirectCardBalanceStore(File(context.noBackupFilesDir, "fixture-${java.util.UUID.randomUUID()}.json"))
    }
    @Test fun `form uses supplied contract with matching student identifiers`() {
        assertEquals("sno=$id&xxbh=synjones&idtype=sno&id=$id", DirectCardBalanceProtocol.form(id))
        listOf("", "123", "2099000123&token=x", "１２３４５６７８", "12345678901234567").forEach {
            assertThrows(IllegalArgumentException::class.java) { DirectCardBalanceProtocol.form(it) }
        }
    }
    @Test fun `balances remain separate exact cents with negative and zero support`() {
        val result = DirectCardBalanceProtocol.parse(body(), id, 123)
        assertEquals("123.45", result.balanceText)
        assertEquals("6.78", result.transitionText)
        assertEquals("-0.01", DirectCardBalanceProtocol.parse(body("-1"), id, 123).balanceText)
        assertEquals("0.00", DirectCardBalanceProtocol.parse(body("0", "0"), id, 123).transitionText)
    }
    @Test fun `malformed failed multiple cards and wrong account never become zero`() {
        listOf("<html>无效token</html>", "{}", "[]", body().replace("\"0\"", "\"1\""),
            body().replace(id, "2099000124"), body().replace("\"unsettle_amount\":\"678\",", ""),
            body().replace("\"db_balance\":\"12345\"", "\"db_balance\":null"),
            """{"retcode":0,"card":[]}""", """{"retcode":0,"card":[{},{}]}""",
            body("1.5"), body("999999999999999999999999"), body("NaN")).forEach {
            assertThrows(CampusCardException::class.java) { DirectCardBalanceProtocol.parse(it, id, 123) }
        }
    }
    @Test fun `numeric fields accepted and server data excluded from exception`() {
        assertEquals(12L, DirectCardBalanceProtocol.parse(body().replace("\"12345\"", "12"), id, 123).balanceCents)
        val error = assertThrows(CampusCardException::class.java) { DirectCardBalanceProtocol.parse("private-fixture", id, 1) }
        assertFalse(error.message.orEmpty().contains(id))
        assertFalse(error.message.orEmpty().contains("private-fixture"))
    }
    @Test fun `successful snapshot survives repository restart and clear removes binding`() = runBlocking {
        val cache = store()
        val repository = DirectCardBalanceRepository(cache) { body() }
        val value = repository.refresh(id)
        assertEquals(value, DirectCardBalanceRepository(cache) { error("no network") }.cached())
        repository.clear()
        assertNull(repository.cached())
    }
    @Test fun `network and validation failures preserve previous snapshot even for different input`() = runBlocking {
        val cache = store()
        val original = DirectCardBalanceRepository(cache) { body() }.refresh(id)
        val broken = DirectCardBalanceRepository(cache) { throw java.io.IOException("offline fixture") }
        try { broken.refresh(id); fail() } catch (_: java.io.IOException) { }
        assertEquals(original, broken.cached())
        val mismatch = DirectCardBalanceRepository(cache) { body() }
        try { mismatch.refresh("2099000124"); fail() } catch (_: CampusCardException) { }
        assertEquals(original, mismatch.cached())
    }
}
