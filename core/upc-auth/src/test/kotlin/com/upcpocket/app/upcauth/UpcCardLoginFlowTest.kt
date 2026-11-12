package com.upcpocket.app.upcauth

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class UpcCardLoginFlowTest {
    private val cas = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fcallback"
    private val html = """<form id="loginForm"><input name="execution" value="fixture-execution">
        <input name="_eventId" value="submit"><input name="type" value="username_password"></form>"""
    private fun response(status: Int = 200, body: String = "", location: String? = null, cookie: String? = null) =
        UpcAuthHttpResponse(status, buildMap {
            location?.let { put("location", listOf(it)) }; cookie?.let { put("set-cookie", listOf(it)) }
        }, body.toByteArray())

    @Test fun `direct platform auth completes without ticket exchange or loading frontend`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val replies = ArrayDeque(listOf(
            response(302, location = cas), response(body = html, cookie = "fixture=cas-cookie; Path=/; Secure"),
            response(302, location = "http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture"),
            response(302, location = "https://mb-xyk.upc.edu.cn/plat/?name=login&synjones-auth=fixture-token"),
        ))
        val transport = object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request
                return replies.removeFirst()
            }
        }
        val flow = UpcCardLoginFlow(transport)
        assertEquals("fixture-token", flow.login(flow.begin(), "2099999999", "fixture-password", ""))
        assertEquals(4, requests.size)
        assertEquals(1, requests.count { it.method == "POST" })
        assertTrue(requests[2].headers["Cookie"].orEmpty().contains("fixture=cas-cookie"))
        assertNull(requests[3].headers["Cookie"])
        assertTrue(requests.all { it.url.startsWith("https://") })
        assertEquals("https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture", requests.last().url)
    }

    @Test fun `platform ticket is exchanged once at fixed endpoint`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val replies = ArrayDeque(listOf(response(302, location = cas), response(body = html),
            response(302, location = "https://mb-xyk.upc.edu.cn/plat/?ticket=fixture-ticket"),
            response(body = """{"access_token":"fixture-token"}""")))
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request; return replies.removeFirst()
            }
        })
        assertEquals("fixture-token", flow.login(flow.begin(), "2099999999", "fixture-password", ""))
        assertEquals(CardAuthOrigins.BASE + "/berserker-auth/oauth/token", requests.last().url)
        assertTrue(requests.last().body.orEmpty().contains("username=fixture-ticket"))
        assertFalse(requests.last().body.orEmpty().contains("fixture-password"))
    }

    @Test fun `external redirect stops before any request to external host`() {
        var calls = 0
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                calls++; return response(302, location = "https://attacker.example/login")
            }
        })
        assertThrows(IOException::class.java) { flow.begin() }
        assertEquals(1, calls)
    }
}
