package com.upcpocket.app.library

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 WebView CookieManager 的合成值测试；不联网、不读取任何真实账号。
 *
 * 覆盖根路径、/cas、/cas/login、/libseat/、/remote/static/sso 与 .upc.edu.cn
 * 域 Cookie，以及无关站点保留。
 */
@RunWith(AndroidJUnit4::class)
class SyntheticSchoolCookieCleanupTest {
    private val manager get() = CookieManager.getInstance()

    @Test
    fun clearPathScopedAndDomainCookiesWithoutTouchingOtherSites() = runBlocking {
        withContext(Dispatchers.Main) {
            manager.setCookie(
                "https://cas.upc.edu.cn/cas/login",
                "M1_CAS_ROOT=synthetic; Path=/; Secure; HttpOnly",
            )
            manager.setCookie(
                "https://cas.upc.edu.cn/cas/login",
                "M1_CAS_PATH=synthetic; Path=/cas; Secure; HttpOnly",
            )
            manager.setCookie(
                "https://cas.upc.edu.cn/cas/login",
                "M1_CAS_LOGIN=synthetic; Path=/cas/login; Secure",
            )
            manager.setCookie(
                "https://libspace.upc.edu.cn/libseat/",
                "M1_LIB=synthetic; Path=/libseat/; Secure; HttpOnly",
            )
            manager.setCookie(
                "https://libspace.upc.edu.cn/remote/static/sso/login",
                "M1_REMOTE=synthetic; Path=/remote/static/sso; Secure",
            )
            manager.setCookie(
                "https://libspace.upc.edu.cn/",
                "M1_DOMAIN=synthetic; Domain=.upc.edu.cn; Path=/; Secure; HttpOnly",
            )
            manager.setCookie("https://example.com/", "M1_KEEP=synthetic; Path=/; Secure")
            manager.flush()
        }

        val beforeCas = cookie("https://cas.upc.edu.cn/cas/login")
        assertTrue(beforeCas.contains("M1_CAS_ROOT"))
        assertTrue(beforeCas.contains("M1_CAS_PATH"))
        assertTrue(beforeCas.contains("M1_CAS_LOGIN"))
        assertTrue(beforeCas.contains("M1_DOMAIN"))
        assertTrue(cookie("https://libspace.upc.edu.cn/libseat/").contains("M1_LIB"))
        assertTrue(cookie("https://libspace.upc.edu.cn/remote/static/sso/login").contains("M1_REMOTE"))
        assertTrue(cookie("https://app.upc.edu.cn/").contains("M1_DOMAIN"))
        assertTrue(cookie("https://example.com/").contains("M1_KEEP"))

        assertTrue(SchoolWebCookies.clearAllSchoolCookiesAndAwait(3_000))

        assertFalse(cookie("https://cas.upc.edu.cn/").contains("M1_CAS_ROOT"))
        assertFalse(cookie("https://cas.upc.edu.cn/cas").contains("M1_CAS_PATH"))
        assertFalse(cookie("https://cas.upc.edu.cn/cas/login").contains("M1_CAS_LOGIN"))
        assertFalse(cookie("https://libspace.upc.edu.cn/libseat/").contains("M1_LIB"))
        assertFalse(cookie("https://libspace.upc.edu.cn/remote/static/sso/login").contains("M1_REMOTE"))
        assertFalse(cookie("https://app.upc.edu.cn/").contains("M1_DOMAIN"))
        assertTrue(cookie("https://example.com/").contains("M1_KEEP"))
    }

    @After
    fun cleanup() {
        runBlocking {
            withContext(Dispatchers.Main) {
                manager.setCookie("https://example.com/", "M1_KEEP=; Max-Age=0; Path=/; Secure")
                manager.flush()
            }
            SchoolWebCookies.clearAllSchoolCookiesAndAwait(3_000)
        }
    }

    private fun cookie(url: String): String = manager.getCookie(url).orEmpty()
}
