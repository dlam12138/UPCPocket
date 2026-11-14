package com.upcpocket.app.upcauth

import org.junit.Assert.*
import org.junit.Test

class UpcShiguangWebLoginPolicyTest {
    private val callback = "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic-ticket"
    private val login = "https://cas.upc.edu.cn/cas/login"

    @Test fun `fixed entry preserves registered service and CAS forms work in main and sub frames`() {
        assertEquals(UpcShiguangAuthEndpoints.LOGIN_URL, UpcShiguangWebLoginPolicy.entryUrl)
        listOf(true, false).forEach { main ->
            ShiguangWebRequestKind.entries.forEach { kind ->
                listOf("GET", "POST").forEach { method ->
                    listOf(login, UpcShiguangAuthEndpoints.LOGIN_URL,
                        UpcShiguangAuthEndpoints.LOGIN_URL + "&from=wap&noAutoRedirect=1").forEach {
                        assertSame(UpcShiguangWebNavigation.Allow, inspect(it, method, main, kind))
                    }
                }
                assertSame(UpcShiguangWebNavigation.Allow,
                    inspect("https://cas.upc.edu.cn/cas/themes/synthetic/login.css?v=1", "GET", main, kind))
                assertSame(UpcShiguangWebNavigation.Allow,
                    inspect("https://cas.upc.edu.cn/cas/captcha?synthetic=1", "GET", main, kind))
            }
        }
    }

    @Test fun `only navigation or main frame resource GET can consume exact HTTP or HTTPS single ticket`() {
        listOf(callback, callback.replace("http://", "https://")).forEach { url ->
            listOf(true, false).forEach { main ->
                val result = inspect(url, "GET", main, ShiguangWebRequestKind.NAVIGATION) as UpcShiguangWebNavigation.Complete
                assertEquals(callback.replace("http://", "https://"), result.callbackUrl)
                assertFalse(result.toString().contains("ST-"))
                assertFalse(result.toString().contains("dekt"))
            }
            assertTrue(inspect(url, "GET", true, ShiguangWebRequestKind.RESOURCE) is UpcShiguangWebNavigation.Complete)
            assertSame(UpcShiguangWebNavigation.Block, inspect(url, "GET", false, ShiguangWebRequestKind.RESOURCE))
            ShiguangWebRequestKind.entries.forEach { kind ->
                listOf(true, false).forEach { main ->
                    assertSame(UpcShiguangWebNavigation.Block, inspect(url, "POST", main, kind))
                }
            }
        }
    }

    @Test fun `malformed callback and business pages are blocked for all frames and request kinds`() {
        val blocked = listOf(
            callback.replace("dekt.upc.edu.cn", "evil.invalid"),
            callback.replace("dekt.upc.edu.cn", "dekt.upc.edu.cn.evil.invalid"),
            callback.replace("dekt.upc.edu.cn", "user@dekt.upc.edu.cn"),
            callback.replace("dekt.upc.edu.cn", "dekt.upc.edu.cn:80"),
            callback.replace("http://", "https://").replace("dekt.upc.edu.cn", "dekt.upc.edu.cn:443"),
            callback.replace("/caslogin.jsp", "/%63aslogin.jsp"),
            callback.replace("/caslogin.jsp", "/a/../caslogin.jsp"),
            callback.replace("/caslogin.jsp", "//caslogin.jsp"),
            callback + "&ticket=ST-second", callback + "&other=1", callback + "#fragment",
            callback.replace("ST-synthetic-ticket", "invalid"),
            callback.replace("ST-synthetic-ticket", "ST-synthetic%0d%0a"),
            "https://dekt.upc.edu.cn/", UpcShiguangAuthEndpoints.LIST_URL,
            "https://dekt.upc.edu.cn${UpcShiguangAuthEndpoints.SECOND_STAGE}?userName=synthetic",
        )
        blocked.forEach { url -> assertBlockedEverywhere(url) }
    }

    @Test fun `CAS rejects wrong service extra duplicate queries insecure hosts ports and ambiguous paths`() {
        listOf(
            "$login?service=https%3A%2F%2Fdekt.upc.edu.cn%2Fcaslogin.jsp",
            "$login?service=https%3A%2F%2Fevil.invalid",
            UpcShiguangAuthEndpoints.LOGIN_URL + "&service=http%3A%2F%2Fdekt.upc.edu.cn%2Fcaslogin.jsp",
            UpcShiguangAuthEndpoints.LOGIN_URL + "&other=1",
            UpcShiguangAuthEndpoints.LOGIN_URL + "&from=wap&from=wap",
            "$login?from=bad", "$login?noAutoRedirect=0",
            "$login#fragment", "$login?service=",
            login.replace("https://", "http://"),
            login.replace("cas.upc.edu.cn", "cas.upc.edu.cn.evil.invalid"),
            login.replace("cas.upc.edu.cn", "user@cas.upc.edu.cn"),
            login.replace("cas.upc.edu.cn", "cas.upc.edu.cn:443"),
            login.replace("/cas/login", "/cas/a/../login"),
            login.replace("/cas/login", "/cas/%6cogin"),
            login.replace("/cas/login", "/cas//login"),
            "https://cas.upc.edu.cn/outside/login.js",
            "https://cas.upc.edu.cn/cas/captcha?service=https%3A%2F%2Fevil.invalid",
            "https://cas.upc.edu.cn/cas/captcha?service=http%3A%2F%2Fdekt.upc.edu.cn%2Fcaslogin.jsp&service=x",
            login + "\n", "javascript:alert(1)", "file:///cas/login",
        ).forEach { assertBlockedEverywhere(it) }
        assertSame(UpcShiguangWebNavigation.Block, inspect("https://cas.upc.edu.cn/cas/captcha", "POST"))
        assertSame(UpcShiguangWebNavigation.Block, inspect(login, "DELETE"))
    }

    private fun assertBlockedEverywhere(url: String) {
        listOf(true, false).forEach { main ->
            ShiguangWebRequestKind.entries.forEach { kind ->
                listOf("GET", "POST").forEach { method ->
                    assertSame(UpcShiguangWebNavigation.Block, inspect(url, method, main, kind))
                }
            }
        }
    }

    private fun inspect(url: String, method: String = "GET", main: Boolean = true,
        kind: ShiguangWebRequestKind = ShiguangWebRequestKind.NAVIGATION) =
        UpcShiguangWebLoginPolicy.inspect(url, method, main, kind)
}
