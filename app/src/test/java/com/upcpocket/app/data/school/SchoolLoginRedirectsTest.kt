package com.upcpocket.app.data.school

import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class SchoolLoginRedirectsTest {
    private val entry = "https://libspace.upc.edu.cn/remote/static/sso/login"
    private val cas = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Flibspace.upc.edu.cn%2Fremote%2Fcallback"
    private fun allowed(url: String): Boolean {
        val uri = URI(url)
        return uri.scheme == "https" && uri.host in setOf("libspace.upc.edu.cn", "cas.upc.edu.cn")
    }

    @Test fun `native GET stops before CAS and preserves service parameter`() {
        val fetched = mutableListOf<String>()
        val result = SchoolLoginRedirects(::allowed, ::isNativeSchoolAuthUrl) {
            fetched += it
            LoginHttpPage(302, cas)
        }.resolve(entry)
        assertEquals(cas, result.url)
        assertEquals(listOf(entry), fetched)
        assertNull(result.html)
    }

    @Test fun `business callback preserves landing token without fetching the front end`() {
        val callback = "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin?ticket=fixture-ticket"
        val landing = "https://libspace.upc.edu.cn/libseat/#/login?token=fixture-token"
        val fetched = mutableListOf<String>()
        val result = SchoolLoginRedirects(::allowed, ::isNativeSchoolAuthUrl) {
            fetched += it
            LoginHttpPage(302, "/libseat/#/login?token=fixture-token")
        }.resolve(callback)
        assertEquals(landing, result.url)
        assertEquals(listOf(callback), fetched)
    }

    @Test fun `external redirect is rejected before request`() {
        var requests = 0
        val error = runCatching {
            SchoolLoginRedirects(::allowed, ::isNativeSchoolAuthUrl) {
                requests++
                LoginHttpPage(302, "https://outside.invalid/?ticket=fixture-ticket")
            }.resolve(entry)
        }.exceptionOrNull()
        assertTrue(error is LoginPageException)
        assertEquals(1, requests)
        assertFalse(error!!.message.orEmpty().contains("fixture-ticket"))
    }

    @Test fun `redirect loop is bounded`() {
        var requests = 0
        val error = runCatching {
            SchoolLoginRedirects(::allowed, ::isNativeSchoolAuthUrl) {
                requests++
                LoginHttpPage(302, entry)
            }.resolve(entry)
        }.exceptionOrNull()
        assertTrue(error is LoginPageException)
        assertEquals(1, requests)
    }

    @Test fun `missing location is not treated as login success`() {
        assertTrue(runCatching {
            SchoolLoginRedirects(::allowed, ::isNativeSchoolAuthUrl) { LoginHttpPage(302) }.resolve(entry)
        }.exceptionOrNull() is LoginPageException)
    }

    @Test fun `HTTP failure is explicit and never exposes the ticket`() {
        val error = runCatching {
            SchoolLoginRedirects(::allowed, ::isNativeSchoolAuthUrl) { LoginHttpPage(503) }
                .resolve("$entry?token=fixture-secret#private")
        }.exceptionOrNull()!!
        assertTrue(error.message.orEmpty().contains("503"))
        assertFalse(error.message.orEmpty().contains("fixture-secret"))
        assertFalse(error.message.orEmpty().contains("private"))
    }

    @Test fun `200 business HTML remains a page not a fabricated token`() {
        val result = SchoolLoginRedirects(::allowed, ::isNativeSchoolAuthUrl) {
            LoginHttpPage(200, html = "<html>official redirect page</html>")
        }.resolve(entry)
        assertEquals(entry, result.url)
        assertEquals("<html>official redirect page</html>", result.html)
    }

    @Test fun `only exact business routes use native auth GET`() {
        assertTrue(isNativeSchoolAuthUrl(entry))
        listOf(cas, "https://libspace.upc.edu.cn/rest/v2/user",
            // 轻量版删掉课表后，教务主机不再属于本应用的登录链路。
            "https://jwxt.upc.edu.cn/jsxsd/",
            "http://jwxt.upc.edu.cn/jsxsd/?ticket=fixture",
            // 校园卡平台登录已删除，它的跳转不再走原生登录解析。
            "http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=fixture",
            "https://libspace.upc.edu.cn.evil.invalid/remote/static/sso/login",
            "https://libspace.upc.edu.cn:8443/remote/static/sso/login",
            "https://someone@libspace.upc.edu.cn/remote/static/sso/login",
            "http://libspace.upc.edu.cn/remote/static/sso/login",
        ).forEach { assertFalse(it, isNativeSchoolAuthUrl(it)) }
    }

    @Test fun `CAS layout repair and frame errors use exact origin and paths`() {
        assertTrue(isCasLoginPage("https://cas.upc.edu.cn/cas/login?service=fixture"))
        assertTrue(isCasLoginFrame("https://cas.upc.edu.cn/cas/cas/login-mobile.html"))
        assertTrue(isCasLoginFrame("https://cas.upc.edu.cn/cas/cas/login-normal.html?v=fixture"))
        assertFalse(isCasLoginPage("https://cas.upc.edu.cn.evil.invalid/cas/login"))
        assertFalse(isCasLoginPage("https://cas.upc.edu.cn:8443/cas/login"))
        assertFalse(isCasLoginFrame("https://cas.upc.edu.cn/cas/logo.png"))
    }

    @Test fun `real HTTP transport forwards app cookie and collects Set-Cookie without following redirect`() {
        val cookies = mutableListOf<Pair<String, String>>()
        var sentCookie: String? = null
        withServer({ path, headers ->
            check(path == "/entry") { "transport followed redirect" }
            sentCookie = headers["cookie"]
            "HTTP/1.1 302 Found\r\nSet-Cookie: business=fixture; Path=/; HttpOnly\r\nLocation: /unexpected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }) { base ->
            val result = fetchLoginPage("$base/entry", "fixture-agent", { "app=fixture" }) { url, cookie ->
                cookies += url to cookie
            }
            assertEquals(302, result.status)
            assertEquals("app=fixture", sentCookie)
            assertEquals(listOf("$base/entry" to "business=fixture; Path=/; HttpOnly"), cookies)
        }
    }

    @Test fun `unexpected content and oversized HTML fail closed`() {
        withServer({ path, _ ->
            val body = if (path == "/json") "{}" else " ".repeat(1_048_577)
            val type = if (path == "/json") "application/json" else "text/html; charset=UTF-8"
            "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        }) { base ->
            for (path in listOf("json", "large")) {
                assertTrue(runCatching {
                    fetchLoginPage("$base/$path", "fixture-agent", { null }) { _, _ -> }
                }.exceptionOrNull() is LoginPageException)
            }
        }
    }

    private fun withServer(response: (String, Map<String, String>) -> String, block: (String) -> Unit) {
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        socket.getOutputStream().use { it.write(response(path, headers).toByteArray()) }
                    }
                }
            } catch (e: SocketException) {
                if (!server.isClosed) failure.set(e)
            } catch (e: Throwable) { failure.set(e) }
        }
        try { block("http://127.0.0.1:${server.localPort}") }
        finally { server.close(); worker.join(6000) }
        failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
    }
}
