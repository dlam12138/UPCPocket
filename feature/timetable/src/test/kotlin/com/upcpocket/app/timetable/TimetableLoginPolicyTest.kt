package com.upcpocket.app.timetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLoginPolicyTest {

    @Test
    fun allowsOfficialCasLoginAndMobileFrame() {
        assertTrue(
            TimetableLoginPolicy.isAllowed(
                "https://cas.upc.edu.cn/cas/login?service=" +
                    "https%3A%2F%2Fapp.upc.edu.cn%2Fa_upc%2Fapi%2Fcas%2Flogin&from=wap",
            ),
        )
        assertTrue(
            TimetableLoginPolicy.isAllowed(
                "https://cas.upc.edu.cn/cas/cas/login-mobile.html",
            ),
        )
    }

    @Test
    fun allowsTimetableCallbackAndPage() {
        assertTrue(
            TimetableLoginPolicy.isAllowed(
                "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-1&" +
                    "redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex&from=wap",
            ),
        )
        assertTrue(
            TimetableLoginPolicy.isAllowed(
                "https://app.upc.edu.cn/site/weekschedule/index#/week/8",
            ),
        )
        assertTrue(
            TimetableLoginPolicy.isAllowed(
                "https://app.upc.edu.cn/timetable/wap/default/get-index",
            ),
        )
    }

    @Test
    fun rejectsPlainHttpAndNonStandardPorts() {
        assertFalse(TimetableLoginPolicy.isAllowed("http://cas.upc.edu.cn/cas/login"))
        assertFalse(
            TimetableLoginPolicy.isAllowed("https://cas.upc.edu.cn:8443/cas/login"),
        )
        assertFalse(
            TimetableLoginPolicy.isAllowed("https://app.upc.edu.cn:444/a_upc/api/cas/login"),
        )
    }

    @Test
    fun rejectsOtherHostsAndChaoxingChain() {
        assertFalse(TimetableLoginPolicy.isAllowed("https://evil.example/cas/login"))
        assertFalse(
            TimetableLoginPolicy.isAllowed(
                "https://tyrzfw.chaoxing.com/auth3/upc/cas/index",
            ),
        )
        assertFalse(
            TimetableLoginPolicy.isAllowed(
                "https://passport2-api.chaoxing.com/api/v2/casreceive",
            ),
        )
        assertFalse(
            TimetableLoginPolicy.isAllowed(
                "https://cas.upc.edu.cn.evil.example/cas/login",
            ),
        )
        assertFalse(
            TimetableLoginPolicy.isAllowed("https://app.upc.edu.cn@evil.example/cas/login"),
        )
    }

    @Test
    fun rejectsUnlistedPathsOnAllowedHosts() {
        assertFalse(
            TimetableLoginPolicy.isAllowed("https://app.upc.edu.cn/uc/api/oauth/index"),
        )
        assertFalse(
            TimetableLoginPolicy.isAllowed("https://cas.upc.edu.cn/other/login"),
        )
        assertFalse(
            TimetableLoginPolicy.isAllowed("https://app.upc.edu.cn/site/other/index"),
        )
    }

    @Test
    fun allowsBlankOnlyForWebViewBootstrap() {
        assertTrue(TimetableLoginPolicy.isAllowed("about:blank"))
        assertFalse(TimetableLoginPolicy.isAllowedEntry("about:blank"))
        assertTrue(
            TimetableLoginPolicy.isAllowedEntry(
                "https://cas.upc.edu.cn/cas/login?service=synthetic",
            ),
        )
        assertFalse(TimetableLoginPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(TimetableLoginPolicy.isAllowed("not a url"))
    }

    @Test
    fun destinationIsACoarseFixedCode() {
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDestination.CAS_LOGIN,
            TimetableLoginPolicy.destination("https://cas.upc.edu.cn/cas/login?service=synthetic"),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDestination.CAS_MOBILE,
            TimetableLoginPolicy.destination("https://cas.upc.edu.cn/cas/cas/login-mobile.html"),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDestination.APP_CALLBACK,
            TimetableLoginPolicy.destination(
                "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=secret",
            ),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDestination.APP_PAGE,
            TimetableLoginPolicy.destination("https://app.upc.edu.cn/site/weekschedule/index"),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDestination.UNKNOWN_DESTINATION,
            TimetableLoginPolicy.destination("https://evil.example/cas/login?token=secret"),
        )
    }

    @Test
    fun hostForMessageDoesNotExposePathOrQuery() {
        assertEquals(
            "cas.upc.edu.cn",
            TimetableLoginPolicy.host("https://cas.upc.edu.cn/cas/login?ticket=secret"),
        )
    }
}
