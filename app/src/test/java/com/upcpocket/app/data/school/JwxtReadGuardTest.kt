package com.upcpocket.app.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 桥只对「明确的教务业务页 + 本次读取代次」放行；导航、超时、重复回调都作废。 */
class JwxtReadGuardTest {

    private val page = "https://jwxt.upc.edu.cn/jsxsd/xskb/xskb_list.do"

    @Test
    fun `只在精确教务业务页可读`() {
        assertEquals("https://jwxt.upc.edu.cn:443", JwxtReadGuard.readableOrigin(page))
        assertNull(JwxtReadGuard.readableOrigin("https://jwxt.upc.edu.cn/jsxsd/"))
        assertNull(JwxtReadGuard.readableOrigin("https://cas.upc.edu.cn/cas/login"))
        assertNull(JwxtReadGuard.readableOrigin("https://jwxt.upc.edu.cn:8443/jsxsd/x"))
        assertNull(JwxtReadGuard.readableOrigin("https://evil.example/jsxsd/x"))
    }

    @Test
    fun `HTTP 只限同一台教务主机`() {
        assertEquals("http://jwxt.upc.edu.cn:80", JwxtReadGuard.readableOrigin("http://jwxt.upc.edu.cn/jsxsd/framework/xsMainV.htmlx"))
        assertNull(JwxtReadGuard.readableOrigin("http://cas.upc.edu.cn/cas/login"))
    }

    @Test
    fun `同一代次只能消费一次`() {
        val guard = JwxtReadGuard()
        assertTrue(guard.begin(page, "a", 0))
        assertTrue(guard.isActive("a", 100))
        assertTrue(guard.accept("a", page, 200, 1024))
        assertFalse(guard.accept("a", page, 300, 1024))
    }

    @Test
    fun `错误代次被丢弃`() {
        val guard = JwxtReadGuard()
        assertTrue(guard.begin(page, "a", 0))
        assertFalse(guard.accept("b", page, 100, 1024))
    }

    @Test
    fun `超时后不再接受回调`() {
        val guard = JwxtReadGuard(timeoutMs = 15_000)
        assertTrue(guard.begin(page, "a", 0))
        assertFalse(guard.accept("a", page, 15_001, 1024))
    }

    @Test
    fun `页面换到别的源后拒收`() {
        val guard = JwxtReadGuard()
        assertTrue(guard.begin(page, "a", 0))
        assertFalse(guard.accept("a", "http://jwxt.upc.edu.cn/jsxsd/x", 100, 1024))
    }

    @Test
    fun `离开业务页后拒收`() {
        val guard = JwxtReadGuard()
        assertTrue(guard.begin(page, "a", 0))
        assertFalse(guard.accept("a", "https://jwxt.upc.edu.cn/jsxsd/", 100, 1024))
    }

    @Test
    fun `空响应和超大响应都拒绝`() {
        val guard = JwxtReadGuard(maxChars = 100)
        assertTrue(guard.begin(page, "a", 0))
        assertFalse(guard.accept("a", page, 100, 0))

        assertTrue(guard.begin(page, "b", 200))
        assertFalse(guard.accept("b", page, 300, 101))
    }

    @Test
    fun `取消后立即失效`() {
        val guard = JwxtReadGuard()
        assertTrue(guard.begin(page, "a", 0))
        guard.cancel()
        assertFalse(guard.isActive("a", 10))
        assertFalse(guard.accept("a", page, 20, 1024))
    }
}
