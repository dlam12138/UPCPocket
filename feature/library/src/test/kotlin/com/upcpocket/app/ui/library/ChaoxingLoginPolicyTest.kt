package com.upcpocket.app.ui.library

import com.upcpocket.app.data.chaoxing.ChaoxingWebCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoxingLoginPolicyTest {

    @Test
    fun `only fixed Chaoxing hosts over https are allowed`() {
        assertTrue(isAllowedChaoxingLoginUrl("https://passport2.chaoxing.com/login?refer=x"))
        assertTrue(isAllowedChaoxingLoginUrl("https://office.chaoxing.com/front/third/apps/seat/index"))
        assertFalse(isAllowedChaoxingLoginUrl("https://reserve.chaoxing.com/front/web/apps/reservepc/index"))
        assertFalse(isAllowedChaoxingLoginUrl("http://office.chaoxing.com/front/third/apps/seat/index"))
        assertFalse(isAllowedChaoxingLoginUrl("https://office.chaoxing.com.evil.test/x"))
        assertFalse(isAllowedChaoxingLoginUrl("https://user-secret@office.chaoxing.com/x"))
        assertFalse(isAllowedChaoxingLoginUrl("https://office.chaoxing.com:8443/x"))
        assertTrue(isAllowedChaoxingLoginUrl("https://cas.upc.edu.cn/cas/login?service=x"))
        assertTrue(isAllowedChaoxingLoginUrl("https://tyrzfw.chaoxing.com/auth3/upc/cas/init"))
        assertTrue(isAllowedChaoxingLoginUrl("https://passport2-static.chaoxing.com/css/fanya/login.css"))
        assertTrue(isAllowedChaoxingLoginUrl("https://app.upc.edu.cn/uc/api/oauth/index"))
        assertFalse(isAllowedChaoxingLoginUrl("https://cas.upc.edu.cn/evil"))
        assertFalse(isAllowedChaoxingLoginUrl("https://office.chaoxing.com/data/apps/seat/room/list"))
    }

    @Test
    fun `loading stays visible through the blank SSO relay and about blank`() {
        assertTrue(shouldKeepLoginLoading("about:blank"))
        assertTrue(
            shouldKeepLoginLoading(
                "https://tyrzfw.chaoxing.com/auth3/upc/cas/init",
            ),
        )
        assertFalse(
            shouldKeepLoginLoading(
                "https://cas.upc.edu.cn/cas/login?service=x",
            ),
        )
        assertFalse(
            shouldKeepLoginLoading(
                "https://office.chaoxing.com/front/third/apps/seat/index",
            ),
        )
    }

    @Test
    fun `cookie merge keeps one value per name and office wins`() {
        val merged = mergeCookieHeaders(
            "oa_uid=1; oa_deptid=e32",
            "oa_uid=2; route=abc; oa_deptid=e32",
        )
        assertEquals("oa_uid=1; oa_deptid=e32; route=abc", merged)
    }

    @Test
    fun `session detection needs both app cookies and never returns values`() {
        assertTrue(ChaoxingWebCookies.hasSessionCookie("oa_uid=1; oa_deptid=e32"))
        assertFalse(ChaoxingWebCookies.hasSessionCookie("oa_uid=1"))
        assertFalse(ChaoxingWebCookies.hasSessionCookie("oa_deptid=e32"))
        assertFalse(ChaoxingWebCookies.hasSessionCookie(""))
    }
}
