package com.upcpocket.app.upcauth

import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.DiagnosticSink
import com.upcpocket.app.platform.diagnostics.Diagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcCardWebLoginPolicyTest {
    @Test fun `manual web navigation diagnostics carry operation frame and service presence`() {
        val events = mutableListOf<DiagnosticEvent>()
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { events += event }
        })
        try {
            val operation = Diagnostics.beginOperation()
            UpcCardWebLoginPolicy.inspect(
                "https://cas.upc.edu.cn/cas/login?service=fixture",
                "GET", true, operation,
            )
            UpcCardWebLoginPolicy.inspect(
                "https://cas.upc.edu.cn/cas/captcha?id=fixture",
                "GET", false, operation,
            )
            assertEquals(2, events.size)
            assertTrue(events.all { it.operationId == operation.id })
            assertEquals(DiagnosticNavigationFrame.MAIN, events[0].navigationFrame)
            assertEquals(DiagnosticServiceParameter.PRESENT, events[0].serviceParameter)
            assertEquals(DiagnosticNavigationFrame.SUB, events[1].navigationFrame)
        } finally {
            Diagnostics.install(null)
        }
    }

    @Test fun `only the fixed entry and supported authentication documents are top level`() {
        assertEquals(UpcCardWebNavigation.Allow, UpcCardWebLoginPolicy.inspect(UpcCardWebLoginPolicy.entryUrl, "GET", true))
        assertEquals(UpcCardWebNavigation.Allow, UpcCardWebLoginPolicy.inspect("https://cas.upc.edu.cn/cas/login?service=fixture", "GET", true))
        assertEquals(UpcCardWebNavigation.Allow, UpcCardWebLoginPolicy.inspect(
            "https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture", "GET", true,
        ))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect("https://mb-xyk.upc.edu.cn/plat/", "GET", true))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect("https://i.upc.edu.cn/uc/login", "GET", true))
    }

    @Test fun `result is captured only for a main frame and known result path`() {
        val result = "https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&ticket=fixture-ticket"
        assertTrue(UpcCardWebLoginPolicy.inspect(result, "GET", true) is UpcCardWebNavigation.Complete)
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(result, "GET", false))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(result, "POST", true))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(
            "https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?synjones-auth=fixture-token", "GET", true,
        ))
    }

    @Test fun `only an exact HTTP callback is upgraded and its raw query is preserved`() {
        val source = "http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-one%2Btwo&targetUrl=a%3Db"
        val decision = UpcCardWebLoginPolicy.inspect(source, "GET", true)
        assertTrue(decision is UpcCardWebNavigation.Upgrade)
        assertEquals(source.replaceFirst("http:", "https:"), (decision as UpcCardWebNavigation.Upgrade).url)
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(
            "http://mb-xyk.upc.edu.cn/plat/?ticket=fixture", "GET", true,
        ))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(
            "http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn", "POST", true,
        ))
    }

    @Test fun `only the CAS form accepts POST and subresources remain on known paths`() {
        assertEquals(UpcCardWebNavigation.Allow, UpcCardWebLoginPolicy.inspect("https://cas.upc.edu.cn/cas/login", "POST", true))
        assertEquals(UpcCardWebNavigation.Allow, UpcCardWebLoginPolicy.inspect("https://cas.upc.edu.cn/cas/login", "POST", false))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect("https://cas.upc.edu.cn/cas/captcha", "POST", false))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(
            "https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn", "POST", true,
        ))
        assertEquals(UpcCardWebNavigation.Allow, UpcCardWebLoginPolicy.inspect(
            "https://cas.upc.edu.cn/cas/captcha?id=fixture", "GET", false,
        ))
        assertEquals(UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(
            "https://attacker.example/image.png", "GET", false,
        ))
    }

    @Test fun `unsafe schemes authorities ports and path encodings are blocked`() {
        listOf(
            "https://cas.upc.edu.cn.evil.example/cas/login",
            "https://user@cas.upc.edu.cn/cas/login",
            "https://cas.upc.edu.cn:444/cas/login",
            "https://cas.upc.edu.cn/cas/%2e%2e/login",
            "https://cas.upc.edu.cn/cas%2flogin",
            "https://mb-xyk.upc.edu.cn/plat/../plat/?ticket=fixture",
            "intent://mb-xyk.upc.edu.cn/plat/#Intent;scheme=https;end",
            "file:///data/local/tmp/login.html",
            "content://com.example.provider/login",
        ).forEach { url ->
            assertEquals(url, UpcCardWebNavigation.Block, UpcCardWebLoginPolicy.inspect(url, "GET", true))
        }
    }

    @Test fun `scheme and origin checks do not accept foreign redirect targets`() {
        assertFalse(UpcCardWebLoginPolicy.inspect("https://mb-xyk.upc.edu.cn.evil/plat/?ticket=x", "GET", true) is UpcCardWebNavigation.Complete)
        assertFalse(UpcCardWebLoginPolicy.inspect("https://mb-xyk.upc.edu.cn:443/plat/?ticket=x", "GET", true) is UpcCardWebNavigation.Block)
    }
}
