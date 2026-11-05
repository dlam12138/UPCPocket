package com.upcpocket.app.library

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolWebCookiesTest {

    @Test
    fun `cookie names ignore empty fragments and values`() {
        assertEquals(
            setOf("SESSION", "cas"),
            SchoolWebCookies.cookieNames(" SESSION=abc; cas=ticket; =; ;"),
        )
        assertTrue(SchoolWebCookies.cookieNames("").isEmpty())
        assertTrue(SchoolWebCookies.cookieNames("   ").isEmpty())
    }

    @Test
    fun `path and domain scoped school cookies are cleared but unrelated sites survive`() {
        val jar = FakeCookieJar()
        jar.setCookie("https://cas.upc.edu.cn/cas/login", "CAS_ROOT=synthetic; Path=/; Secure; HttpOnly")
        jar.setCookie("https://cas.upc.edu.cn/cas/login", "CAS_PATH=synthetic; Path=/cas; Secure")
        jar.setCookie("https://cas.upc.edu.cn/cas/login", "CAS_LOGIN=synthetic; Path=/cas/login; Secure")
        jar.setCookie("https://libspace.upc.edu.cn/libseat/", "LIB_PATH=synthetic; Path=/libseat/; Secure")
        jar.setCookie(
            "https://libspace.upc.edu.cn/remote/static/sso/login",
            "REMOTE_PATH=synthetic; Path=/remote/static/sso; Secure",
        )
        jar.setCookie(
            "https://libspace.upc.edu.cn/",
            "UPC_DOMAIN=synthetic; Domain=.upc.edu.cn; Path=/; Secure; HttpOnly",
        )
        jar.setCookie("https://example.com/", "UNRELATED=synthetic; Path=/")
        jar.setCookie("https://notupc.example/", "ALSO_UNRELATED=synthetic; Path=/")

        assertTrue(
            jar.namesAt("https://cas.upc.edu.cn/cas/login")
                .containsAll(listOf("CAS_ROOT", "CAS_PATH", "CAS_LOGIN", "UPC_DOMAIN")),
        )
        assertTrue(jar.namesAt("https://libspace.upc.edu.cn/libseat/").contains("LIB_PATH"))
        assertTrue(jar.namesAt("https://libspace.upc.edu.cn/remote/static/sso/login").contains("REMOTE_PATH"))

        val cleared = SchoolWebCookies.clearScopes(jar)

        assertTrue(
            cleared.containsAll(
                listOf("CAS_ROOT", "CAS_PATH", "CAS_LOGIN", "LIB_PATH", "REMOTE_PATH", "UPC_DOMAIN"),
            ),
        )
        assertFalse(jar.namesAt("https://cas.upc.edu.cn/").contains("CAS_ROOT"))
        assertFalse(jar.namesAt("https://cas.upc.edu.cn/cas").contains("CAS_PATH"))
        assertFalse(jar.namesAt("https://cas.upc.edu.cn/cas/login").contains("CAS_LOGIN"))
        assertFalse(jar.namesAt("https://libspace.upc.edu.cn/libseat/").contains("LIB_PATH"))
        assertFalse(
            jar.namesAt("https://libspace.upc.edu.cn/remote/static/sso/login").contains("REMOTE_PATH"),
        )
        assertFalse(jar.namesAt("https://app.upc.edu.cn/").contains("UPC_DOMAIN"))
        assertTrue(jar.namesAt("https://example.com/").contains("UNRELATED"))
        assertTrue(jar.namesAt("https://notupc.example/").contains("ALSO_UNRELATED"))
        assertTrue(SchoolWebCookies.collectNames(jar).intersect(cleared).isEmpty())
    }

    private class FakeCookieJar : SchoolWebCookies.CookieJar {
        private data class Entry(
            val name: String,
            val host: String,
            val domain: String?,
            val path: String,
            val value: String,
        )

        private val cookies = mutableListOf<Entry>()

        override fun getCookie(url: String): String? {
            val uri = URI(url)
            val path = uri.path.orEmpty().ifEmpty { "/" }
            val host = uri.host.orEmpty()
            return cookies.asSequence()
                .filter { cookie ->
                    val hostMatches = cookie.domain?.let { domain ->
                        host == domain || host.endsWith(".$domain")
                    } ?: (host == cookie.host)
                    hostMatches && pathMatches(path, cookie.path)
                }
                .joinToString("; ") { "${it.name}=${it.value}" }
                .ifBlank { null }
        }

        override fun setCookie(url: String, cookie: String) {
            val uri = URI(url)
            val host = uri.host.orEmpty()
            val parts = cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val pair = parts.firstOrNull() ?: return
            if (!pair.contains('=')) return
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty()) return
            val path = parts.firstOrNull { it.startsWith("Path=", ignoreCase = true) }
                ?.substringAfter('=')?.ifBlank { "/" } ?: "/"
            val domain = parts.firstOrNull { it.startsWith("Domain=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.removePrefix(".")
                ?.lowercase()?.ifBlank { null }
            val maxAge = parts.firstOrNull { it.startsWith("Max-Age=", ignoreCase = true) }
                ?.substringAfter('=')?.toLongOrNull()
            if (maxAge != null && maxAge <= 0) {
                cookies.removeAll { existing ->
                    existing.name == name &&
                        existing.path == path &&
                        (domain?.let { existing.domain == it } ?: (existing.domain == null && existing.host == host))
                }
                return
            }
            val value = pair.substringAfter('=').trim()
            cookies.removeAll { existing ->
                existing.name == name &&
                    existing.host == host &&
                    existing.domain == domain &&
                    existing.path == path
            }
            cookies += Entry(name, host, domain, path, value)
        }

        override fun flush() = Unit

        fun namesAt(url: String): Set<String> = cookieNames(getCookie(url).orEmpty())

        private fun pathMatches(requestPath: String, cookiePath: String): Boolean =
            requestPath == cookiePath ||
                (cookiePath.endsWith('/') && requestPath.startsWith(cookiePath)) ||
                requestPath.startsWith("$cookiePath/")

        private fun cookieNames(header: String): Set<String> = header.split(';')
            .mapNotNull { part ->
                part.trim().substringBefore('=', "").trim().takeIf { it.isNotEmpty() }
            }
            .toSet()
    }
}
