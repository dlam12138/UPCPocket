package com.upcpocket.app.ui.ecard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园卡登录 WebView 的两条边界：导航白名单与平台令牌提取。
 *
 * 白名单必须“只放行登录真正要经过的站点”，否则一个被注入的链接就能把
 * 学校会话带到外部页面。这里逐条钉住允许与拒绝的形态。
 */
class CampusCardLoginUrlTest {

    @Test
    fun `storage token capture is restricted to exact HTTPS business page`() {
        assertTrue(isCampusCardStoragePage("https://mb-xyk.upc.edu.cn/plat/login"))
        listOf("http://mb-xyk.upc.edu.cn/plat/", "https://cas.upc.edu.cn/plat/",
            "https://mb-xyk.upc.edu.cn/platform-evil/", "https://mb-xyk.upc.edu.cn:8443/plat/",
            "https://user@mb-xyk.upc.edu.cn/plat/").forEach { assertFalse(isCampusCardStoragePage(it)) }
    }

    @Test
    fun `only campus card origin supplies its token`() {
        assertEquals("fixture", campusCardTokenFromUrl("https://mb-xyk.upc.edu.cn/plat/?synjones-auth=fixture"))
        assertNull(campusCardTokenFromUrl("https://cas.upc.edu.cn/cas/login?synjones-auth=fixture"))
        assertNull(campusCardTokenFromUrl("https://mb-xyk.upc.edu.cn:8443/plat/?synjones-auth=fixture"))
        assertNull(campusCardTokenFromUrl("https://somebody@mb-xyk.upc.edu.cn/plat/?synjones-auth=fixture"))
    }

    @Test
    fun `统一认证入口是学校域名且带平台首页作为回跳`() {
        val url = loginUrl()
        assertEquals("https://mb-xyk.upc.edu.cn/plat/?name=Login", url)

    }

    @Test
    fun `https 学校域名全部放行`() {
        listOf(
            "https://cas.upc.edu.cn/cas/login?service=x",
            "https://mb-xyk.upc.edu.cn/plat/?name=loginTransit",
            "https://i.upc.edu.cn/dcp/index.jsp",
            "https://app.upc.edu.cn/freeclass/wap/default/all",
        ).forEach { assertTrue("应放行 $it", allowedCampusCardUrl(it)) }
    }

    @Test
    fun `平台自己的 http 回跳只限两个路径前缀`() {
        assertTrue(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/plat/?name=loginTransit&synjones-auth=t"))
        assertTrue(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=x"))
        // 同一主机但别的前缀不放行，避免把明文面扩大到整个站点。
        assertFalse(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/"))
        assertFalse(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/payment/"))
    }

    @Test
    fun `外部站点与明文学校站点一律拒绝`() {
        listOf(
            "https://example.com/",
            "https://upc.edu.cn.evil.test/plat/",
            "http://app.upc.edu.cn/freeclass/wap/default/all",
            "http://cas.upc.edu.cn/cas/login",
            "javascript:alert(1)",
            "file:///data/data/com.upcpocket.app/files/x",
            "intent://scan/#Intent;scheme=zxing;end",
            "",
        ).forEach { assertFalse("应拒绝 $it", allowedCampusCardUrl(it)) }
    }

    @Test
    fun `从回跳地址里读平台令牌`() {
        assertEquals(
            "abc123",
            tokenFromUrl("https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&synjones-auth=abc123&x=1"),
        )
        assertEquals(
            "tok",
            tokenFromUrl("http://mb-xyk.upc.edu.cn/plat/?synjones-auth=tok"),
        )
    }

    @Test
    fun `令牌在 hash 查询串里也能读到`() {
        // 平台自己的 getRequest 在 `?` 位于 `#` 之后时会解析 `#` 后面的查询串。
        assertEquals(
            "abc",
            tokenFromUrl("https://mb-xyk.upc.edu.cn/plat/#/loginTransit?synjones-auth=abc"),
        )
        assertEquals(
            "xyz",
            tokenFromUrl("https://mb-xyk.upc.edu.cn/plat-pc/#/?synjones-auth=xyz"),
        )
    }

    @Test
    fun `令牌的百分号编码会被解码`() {
        // 未解码会把 %2B 当字面量发给服务端，表现为“令牌无效”。
        assertEquals("a+b/c=", tokenFromUrl("https://mb-xyk.upc.edu.cn/plat/?synjones-auth=a%2Bb%2Fc%3D"))
    }

    @Test
    fun `明文白名单按路径段判断_不会被子串骗过`() {
        assertTrue(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/plat-pc/#/notfound"))
        assertTrue(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/berserker-app/frontInfo"))
        // 子串匹配会把下面这些也放行，这是典型的开放重定向面。
        assertFalse(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/platform-evil/x"))
        assertFalse(allowedCampusCardUrl("http://mb-xyk.upc.edu.cn/berserkerX/y"))
    }
    @Test
    fun `没有令牌参数时返回空_不把空值当登录成功`() {
        assertNull(tokenFromUrl("https://mb-xyk.upc.edu.cn/plat/"))
        assertNull(tokenFromUrl("https://mb-xyk.upc.edu.cn/plat/?synjones-auth="))
        assertNull(tokenFromUrl("https://mb-xyk.upc.edu.cn/plat/?other=1"))
        // 不得把哈希片段里的同名参数当成平台令牌（片段不会发给服务器，也不会被平台读取）。
        assertNull(tokenFromUrl("https://mb-xyk.upc.edu.cn/plat/#synjones-auth=x"))
    }
}
