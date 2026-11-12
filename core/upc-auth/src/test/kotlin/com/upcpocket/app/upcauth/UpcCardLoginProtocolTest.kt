package com.upcpocket.app.upcauth

import java.io.IOException
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class UpcCardLoginProtocolTest {
    private val url = "https://cas.upc.edu.cn/cas/login?service=example"
    private val html = """<form method="post" id="loginForm">
        <input name="username"><input name="password"><input name="type" value="username_password">
        <input name="execution" value="example-execution"><input name="_eventId" value="submit"></form>"""
    private val id = "2099999999"

    @Test fun `native login submits observed fields with escaped credentials`() {
        val form = CardCasProtocol.form(html, url)
        val fields = CardCasProtocol.body(form, id, "test+&=%", "").split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("test+&=%", fields["password"])
        assertEquals("example-execution", fields["execution"])
        assertEquals("submit", fields["_eventId"])
        assertEquals("username_password", fields["type"])
    }
    @Test(expected = IOException::class) fun `password form cannot use HTTP`() { CardCasProtocol.form(html, url.replace("https:", "http:")) }
    @Test(expected = IOException::class) fun `changed execution form is rejected`() { CardCasProtocol.form(html.replace("execution", "removed"), url) }
    @Test(expected = IOException::class) fun `captcha cannot be silently skipped`() {
        val form = CardCasProtocol.form(html + "config.captcha = {id: 'example-id'};", url)
        CardCasProtocol.body(form, id, "example", "")
    }
    @Test fun `CAS service ticket and platform ticket cannot be confused`() {
        assertNull(CardCasProtocol.ticket("http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-example"))
        assertNull(CardCasProtocol.ticket("https://attacker.example/plat/?ticket=example"))
        assertEquals("example+encoded", CardCasProtocol.ticket("https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&ticket=example%2Bencoded"))
    }
    @Test fun `actual callback shape with synjones auth is a direct login result rather than ticket`() {
        val callback = "https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&synjones-auth=example-access-token"
        assertTrue(CardCasProtocol.loginResult(callback))
        assertEquals("example-access-token", CardCasProtocol.directToken(callback))
        assertNull(CardCasProtocol.ticket(callback))
    }
    @Test fun `direct callback decodes once and accepts optional bearer prefix`() {
        assertEquals("example+token=value", CardCasProtocol.directToken("https://mb-xyk.upc.edu.cn/plat?name=shouyeUser&synjones-auth=example%2Btoken%3Dvalue"))
        assertEquals("example-token", CardCasProtocol.directToken("https://mb-xyk.upc.edu.cn/plat/?synjones-auth=Bearer%20example-token"))
        assertEquals("example%2Btoken", CardCasProtocol.directToken("https://mb-xyk.upc.edu.cn/plat/?synjones-auth=example%252Btoken"))
    }
    @Test fun `direct token outside official portal cannot complete login`() {
        assertNull(CardCasProtocol.directToken("https://attacker.example/plat/?synjones-auth=example"))
        assertNull(CardCasProtocol.directToken("https://cas.upc.edu.cn/cas/login?synjones-auth=example"))
        assertNull(CardCasProtocol.directToken("http://mb-xyk.upc.edu.cn/plat/?synjones-auth=example"))
        assertNull(CardCasProtocol.directToken("https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?synjones-auth=example"))
    }
    @Test(expected = IOException::class) fun `visitor callback is not a successful user login`() {
        CardCasProtocol.directToken("https://mb-xyk.upc.edu.cn/plat/?visitor=1&synjones-auth=example")
    }
    @Test(expected = IOException::class) fun `empty direct token is not silently treated as valid callback`() {
        CardCasProtocol.directToken("https://mb-xyk.upc.edu.cn/plat/?synjones-auth=")
    }
    @Test(expected = IOException::class) fun `direct token header injection is rejected`() {
        CardCasProtocol.directToken("https://mb-xyk.upc.edu.cn/plat/?synjones-auth=example%0D%0AX-Test%3Avalue")
    }
    @Test fun `literal callback redirect with direct token is a completed result`() {
        val url = CardCasProtocol.pageRedirect("""<script>location.href='http://mb-xyk.upc.edu.cn/plat/?name=shouyeUser&synjones-auth=example-token';</script>""", "https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn")!!
        assertTrue(CardCasProtocol.loginResult(url))
        assertEquals("example-token", CardCasProtocol.directToken(url))
    }
    @Test fun `school callback redirects directly to HTTPS without changing ticket or service query`() {
        val location = "http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit&ticket=ST-example%2Bvalue"
        val result = CardCasProtocol.redirect(url, location)
        assertEquals(location.replaceFirst("http:", "https:"), result)
        assertEquals(java.net.URI(location).rawQuery, java.net.URI(result).rawQuery)
    }
    @Test fun `relative CAS redirect remains on exact official origin`() {
        assertEquals("https://cas.upc.edu.cn/cas/login?example=1", CardCasProtocol.redirect(url, "login?example=1"))
    }
    @Test(expected = IOException::class) fun `redirect cannot send a ticket to another origin`() {
        CardCasProtocol.redirect(url, "https://attacker.example/plat/?ticket=example")
    }
    @Test(expected = IOException::class) fun `HTTP login downgrade is rejected`() {
        CardCasProtocol.redirect(url, "http://cas.upc.edu.cn/cas/login")
    }
    @Test fun `official callback literal script redirect is handled without executing a page`() {
        val callback = "https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn"
        val body = """<html><script>window.location.replace('https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&ticket=example');</script></html>"""
        assertEquals("example", CardCasProtocol.ticket(CardCasProtocol.pageRedirect(body, callback)!!))
        assertNull(CardCasProtocol.pageRedirect("<p>ticket=example</p>", callback))
        assertNull(CardCasProtocol.pageRedirect(body, "https://cas.upc.edu.cn/cas/login"))
    }
    @Test(expected = IOException::class) fun `callback script cannot redirect credentials to foreign origin`() {
        CardCasProtocol.pageRedirect("""<script>location.href='https://attacker.example/?ticket=example';</script>""", "https://mb-xyk.upc.edu.cn/plat/")
    }
    @Test fun `callback diagnostics show structure without secret values or raw page`() {
        val output = CardCasProtocol.callbackDiagnostic("https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&ticket=secret-ticket", """{"code":500,"message":"private user value"}""")
        assertTrue(output.contains("JSON/code=500"))
        assertTrue(output.contains("name,ticket"))
        assertFalse(output.contains("secret-ticket")); assertFalse(output.contains("private user value"))
    }
}
