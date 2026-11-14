package com.upcpocket.app.upcauth

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class UpcCardLoginFlowTest {
    private val cas = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fcallback"
    private val html = """<form id="loginForm"><input name="username"><input name="password" type="password">
        <input name="execution" value="fixture-execution">
        <input name="_eventId" value="submit"><input name="type" value="username_password"></form>"""
    private fun response(status: Int = 200, body: String = "", location: String? = null, cookie: String? = null) =
        UpcAuthHttpResponse(status, buildMap {
            location?.let { put("location", listOf(it)) }; cookie?.let { put("set-cookie", listOf(it)) }
        }, body.toByteArray())

    @Test fun `normal password form is not blocked by inactive alternative login tabs`() {
        val page = html + """<button>扫码登录</button><button>验证码登录</button>
            <form id="smsForm" style="display:none"><input name="type" value="username_smstoken"><input name="captcha"></form>
            <script>const selector = "[name='captcha']"; config.captcha = null;</script>"""
        val form = CardCasProtocol.form(page, cas)
        assertFalse(form.interactionRequired)
        assertTrue(CardCasProtocol.body(form, "2099999999", "fixture-passphrase", "").contains("password="))
        assertTrue(CardCasProtocol.form(html.replace("</form>", "<input name='captcha'></form>"), cas).interactionRequired)
        assertTrue(CardCasProtocol.form(html + "<script>config.captcha = { unsupported: true };</script>", cas).interactionRequired)
    }

    @Test fun `form submit value is replayed with url encoding and missing value keeps legacy default`() {
        val provided = html.replace("</form>", "<input name='submit' value='登录&继续'></form>")
        val body = CardCasProtocol.body(CardCasProtocol.form(provided, cas), "2099999999", "safe-passphrase", "")
        assertTrue(body.contains("submit=%E7%99%BB%E5%BD%95%26%E7%BB%A7%E7%BB%AD"))
        val legacy = CardCasProtocol.body(CardCasProtocol.form(html, cas), "2099999999", "safe-passphrase", "")
        assertTrue(legacy.contains("submit=LOGIN"))
    }

    @Test fun `form action must stay on the same exact cas service`() {
        val changedService = html.replace("id=\"loginForm\"", "id=\"loginForm\" action=\"/cas/login?service=https%3A%2F%2Fevil.example%2F\"")
        assertThrows(IOException::class.java) { CardCasProtocol.form(changedService, cas) }
        val duplicateService = cas + "&service=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fcallback"
        assertThrows(IOException::class.java) { CardCasProtocol.form(html, duplicateService) }
    }

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

    @Test fun `web direct token uses the shared parser without a second network request`() {
        var calls = 0
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                calls++
                error("Direct result does not require ticket exchange")
            }
        })
        assertEquals(
            "fixture-token",
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&synjones-auth=fixture-token"),
        )
        assertEquals(0, calls)
    }

    @Test fun `web platform ticket is exchanged once and CAS service ticket is rejected`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request
                return response(body = """{"access_token":"fixture-token"}""")
            }
        })
        assertEquals("fixture-token", flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?ticket=fixture-ticket"))
        assertEquals(1, requests.size)
        assertEquals(CardAuthOrigins.BASE + "/berserker-auth/oauth/token", requests.single().url)
        assertThrows(IOException::class.java) {
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture")
        }
        assertEquals(1, requests.size)
    }

    @Test fun `web result rejects ambiguous token and ticket parameters`() {
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse = error("Ambiguous result must not be exchanged")
        })
        assertThrows(IOException::class.java) {
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?ticket=one&ticket=two")
        }
        assertThrows(IOException::class.java) {
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?ticket=fixture&synjones-auth=fixture")
        }
    }

    @Test fun `unknown post refusal offers manual login after one password submission`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val replies = ArrayDeque(listOf(response(302, location = cas), response(body = html), response(body = html)))
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request; return replies.removeFirst()
            }
        })
        val form = flow.begin()
        assertThrows(CardCasInteractionRequired::class.java) { flow.login(form, "2099999999", "fixture-password", "") }
        assertEquals(3, requests.size)
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(cas, requests.single { it.method == "POST" }.url)
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
