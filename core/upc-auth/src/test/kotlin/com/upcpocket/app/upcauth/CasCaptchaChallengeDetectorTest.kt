package com.upcpocket.app.upcauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CasCaptchaChallengeDetectorTest {
    @Test
    fun scriptSelectorsCommentsAndInactivePlaceholdersAreNotChallenges() {
        val html = """
            <form><input type="hidden" name="captcha"></form>
            <!-- <input name="captcha"> -->
            <div id="captchaParent" style="display:none"><input name="captcha"></div>
            <script>
                const selector = "input[name='captcha']";
                // config.captcha = { id: 'commented' };
                config.captcha = null;
                config.captcha = false;
                config.captcha = undefined;
            </script>
        """.trimIndent()

        assertFalse(CasCaptchaChallengeDetector.inspect(html).active)
    }

    @Test
    fun visibleCaptchaInputOrDisplayedContainerIsAChallenge() {
        assertTrue(
            CasCaptchaChallengeDetector.inspect("<form><input name='captcha'></form>").active,
        )
        assertTrue(
            CasCaptchaChallengeDetector.inspect(
                "<div id='captchaParent' style='display: block'></div>",
            ).active,
        )
        assertTrue(
            CasCaptchaChallengeDetector.inspect(
                "<div id='captchaParent'><iframe src='/captcha'></iframe></div>",
            ).active,
        )
        assertFalse(
            CasCaptchaChallengeDetector.inspect("<div id='captchaParent' style='display:none'></div>").active,
        )
        assertFalse(
            CasCaptchaChallengeDetector.inspect(
                "<div id='captchaParent' style='display:none'><input name='captcha'></div>",
            ).active,
        )
        assertFalse(
            CasCaptchaChallengeDetector.inspect("<input name='captcha' style='display:none'>").active,
        )
    }

    @Test
    fun ariaHiddenDoesNotHideCaptchaControlsFromDetection() {
        assertTrue(
            CasCaptchaChallengeDetector.inspect(
                "<div aria-hidden='true'><input name='captcha'></div>",
            ).active,
        )
    }

    @Test
    fun repeatedDisplayDeclarationsAreTreatedAsActive() {
        val styles = listOf("display:none;display:block", "display:block;display:none")
        styles.forEach { style ->
            assertTrue(
                CasCaptchaChallengeDetector.inspect(
                    "<div id='captchaParent' style='$style'></div>",
                ).active,
            )
            assertTrue(
                CasCaptchaChallengeDetector.inspect(
                    "<div style='$style'><input name='captcha'></div>",
                ).active,
            )
        }
    }

    @Test
    fun activeCaptchaConfigurationStopsEvenWhenContainerIsHidden() {
        val unsupported = """
            <div id="captchaParent" style="display:none"></div>
            <script>config.captcha = { unsupported: true };</script>
        """.trimIndent()
        assertTrue(CasCaptchaChallengeDetector.inspect(unsupported).active)

        val configuredId = CasCaptchaChallengeDetector.inspect(
            "<script>config.captcha = { id: 'fixture-image' };</script>",
        )
        assertTrue(configuredId.active)
        assertEquals("fixture-image", configuredId.captchaId)
        assertTrue(CasCaptchaChallengeDetector.inspect("<script>config.captcha = true;</script>").active)
    }

    @Test
    fun emptyObjectsAndUnknownOrComposedRightHandSidesAreActive() {
        val expressions = listOf(
            "config.captcha = {};",
            "config.captcha = challengeConfig;",
            "config.captcha = buildChallenge();",
            "config.captcha = new Error('challenge');",
            "config.captcha = false || challenge();",
            "config.captcha = null ?? challenge();",
            "config.captcha = undefined && challenge();",
            "config.captcha ||= false;",
        )
        expressions.forEach { expression ->
            assertTrue(expression, CasCaptchaChallengeDetector.inspect("<script>$expression</script>").active)
        }
    }

    @Test
    fun comparisonsReferencesAndSelectorsAreNotAssignments() {
        val html = """
            <script>
                if (config.captcha == true) { useChallenge(); }
                if (config.captcha === true) { useChallenge(); }
                if (config.captcha != false) { useChallenge(); }
                if (config.captcha !== false) { useChallenge(); }
                const current = config.captcha;
                const selector = "config.captcha = {}";
            </script>
        """.trimIndent()

        assertFalse(CasCaptchaChallengeDetector.inspect(html).active)
    }
}
