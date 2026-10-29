package com.upcpocket.app.data.school

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class JwxtPageTransportTest {
    @Test fun `scope excludes CAS other services nondefault ports and path escapes`() {
        assertTrue(isJwxtPageUrl("https://jwxt.upc.edu.cn/jsxsd/framework/xsMainV.htmlx"))
        assertTrue(isJwxtPageUrl("http://jwxt.upc.edu.cn/jsxsd/xskb/xskb_list.do?fixture=1"))
        listOf("https://cas.upc.edu.cn/cas/login", "https://libspace.upc.edu.cn/jsxsd/",
            "https://jwxt.upc.edu.cn:8443/jsxsd/", "https://user@jwxt.upc.edu.cn/jsxsd/",
            "https://jwxt.upc.edu.cn.evil.invalid/jsxsd/", "https://jwxt.upc.edu.cn/outside",
            "https://jwxt.upc.edu.cn/jsxsd/../outside").forEach { assertFalse(it, isJwxtPageUrl(it)) }
    }

    @Test fun `real GET bridges cookie at each redirect and preserves binary bytes`() {
        val received = mutableListOf<String?>()
        val stored = mutableListOf<String>()
        server({ path, headers ->
            received += headers["cookie"]
            if (path == "/entry") response(302, byteArrayOf(), "Location: /image\r\nSet-Cookie: fixture=new; Path=/\r\n")
            else response(200, byteArrayOf(0, -1, 4, 7), "Content-Type: image/png\r\n")
        }) { base ->
            val result = fetchJwxtResource("$base/entry", mapOf("Cookie" to "wrong", "Authorization" to "wrong"),
                { if (stored.isEmpty()) "fixture=old" else "fixture=new" }, { _, value -> stored += value },
                { it.startsWith("$base/") })
            assertEquals(listOf("fixture=old", "fixture=new"), received)
            assertEquals("image/png", result.mime)
            assertNull(result.encoding)
            assertArrayEquals(byteArrayOf(0, -1, 4, 7), result.bytes)
            assertFalse(result.headers.keys.any { it.equals("Set-Cookie", true) })
        }
    }

    @Test fun `cross origin redirect is rejected before Cookie is requested for destination`() {
        var cookiesRead = 0
        server({ _, _ -> response(302, byteArrayOf(), "Location: https://outside.invalid/?ticket=fixture-secret\r\n") }) { base ->
            val error = runCatching { fetchJwxtResource("$base/entry", emptyMap(), { cookiesRead++; "fixture=ok" },
                { _, _ -> }, { it.startsWith("$base/") }) }.exceptionOrNull()!!
            assertTrue(error is LoginPageException)
            assertEquals(1, cookiesRead)
            assertFalse(error.message.orEmpty().contains("fixture-secret"))
        }
    }

    @Test fun `gzip text is decoded and HTTP errors retain their status`() {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { it.write("fixture 教务".toByteArray()) }
        server({ path, _ ->
            if (path == "/gzip") response(200, output.toByteArray(), "Content-Type: text/html; charset=GB18030\r\nContent-Encoding: gzip\r\n")
            else response(503, "unavailable".toByteArray(), "Content-Type: text/plain\r\n")
        }) { base ->
            fun fetch(path: String) = fetchJwxtResource("$base/$path", emptyMap(), { null }, { _, _ -> }, { it.startsWith("$base/") })
            val result = fetch("gzip")
            assertEquals("GB18030", result.encoding)
            assertEquals("fixture 教务", result.bytes.toString(Charsets.UTF_8))
            assertFalse(result.headers.keys.any { it.equals("Content-Encoding", true) })
            assertEquals(503, fetch("error").status)
        }
    }

    @Test fun `redirect loops and oversized decoded bodies fail explicitly`() {
        server({ path, _ ->
            if (path == "/loop") response(302, byteArrayOf(), "Location: /loop\r\n")
            else response(200, ByteArray(8 * 1024 * 1024 + 1), "Content-Type: application/octet-stream\r\n")
        }) { base ->
            for (path in listOf("loop", "large")) {
                assertTrue(runCatching { fetchJwxtResource("$base/$path", emptyMap(), { null }, { _, _ -> }, { it.startsWith("$base/") }) }
                    .exceptionOrNull() is LoginPageException)
            }
        }
    }

    private fun response(status: Int, body: ByteArray, headers: String): ByteArray =
        "HTTP/1.1 $status Fixture\r\n${headers}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body

    private fun server(respond: (String, Map<String, String>) -> ByteArray, block: (String) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    socket.getOutputStream().write(respond(path, headers))
                }
            } catch (e: SocketException) { if (!server.isClosed) failure.set(e) }
            catch (e: Throwable) { failure.set(e) }
        }
        try { block("http://127.0.0.1:${server.localPort}") }
        finally { server.close(); worker.join(6000) }
        failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
    }
}
