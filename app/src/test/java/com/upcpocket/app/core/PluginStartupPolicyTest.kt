package com.upcpocket.app.core

import com.upcpocket.app.plugin.runtime.PluginDiagnostic
import com.upcpocket.app.plugin.runtime.PluginDiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStartupPolicyTest {

    @Test
    fun `debug fails fast while release degrades to healthy plugins`() {
        assertTrue(PluginStartupPolicy.shouldFailFast(debug = true, hasFailures = true))
        assertFalse(PluginStartupPolicy.shouldFailFast(debug = false, hasFailures = true))
        assertFalse(PluginStartupPolicy.shouldFailFast(debug = true, hasFailures = false))
    }

    @Test
    fun `summary contains only fixed codes and ids`() {
        val summary = PluginStartupPolicy.summary(
            pluginDiagnostics = listOf(
                PluginDiagnostic(
                    code = PluginDiagnosticCode.CREATION_FAILED,
                    pluginId = "power",
                    detail = "插件创建失败",
                ),
            ),
            uiDiagnostics = listOf(
                HostUiDiagnostic(
                    code = HostUiDiagnosticCode.UI_CONTRIBUTION_FAILED,
                    pluginId = "focus",
                    detail = "插件 UI 贡献未通过校验",
                ),
            ),
        )

        assertEquals("plugin:CREATION_FAILED:power,ui:UI_CONTRIBUTION_FAILED:focus", summary)
        assertTrue(PluginStartupPolicy.failFastMessage(summary).contains("CREATION_FAILED"))
        assertTrue(PluginStartupPolicy.failFastMessage(summary).contains("focus"))
    }
}
