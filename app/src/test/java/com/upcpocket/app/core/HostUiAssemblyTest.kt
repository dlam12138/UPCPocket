package com.upcpocket.app.core

import com.upcpocket.app.plugin.api.CapabilityKey
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDependency
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostUiAssemblyTest {

    @Test
    fun `faulty plugin contributes nothing while healthy plugin remains`() {
        val runtime = PluginRuntime()
        runtime.register(fakeDescriptor("healthy")) { HealthyPlugin() }
        runtime.register(fakeDescriptor("fault")) { FaultyPlugin() }
        val active = runtime.start().plugins

        val result = HostUiAssembly.build(active)

        assertEquals(
            listOf("healthy.entry"),
            result.snapshot.entriesFor(UiSurface.MORE_LEARNING).map { it.entryId.value },
        )
        assertEquals(setOf("healthy.page"), result.snapshot.pages.map { it.routeId.value }.toSet())
        assertNull(result.snapshot.page(PluginRouteId.parse("fault.page")))
        val diagnostic = result.diagnostics.single()
        assertEquals(HostUiDiagnosticCode.UI_CONTRIBUTION_FAILED, diagnostic.code)
        assertEquals("fault", diagnostic.pluginId)
        assertTrue(diagnostic.detail.isNotBlank())
        assertFalse(diagnostic.detail.contains("fixture UI secret"))
    }

    @Test
    fun `plugin id mismatch is rejected without blocking later plugin`() {
        val runtime = PluginRuntime()
        runtime.register(fakeDescriptor("mismatch")) { MismatchPlugin() }
        runtime.register(fakeDescriptor("healthy")) { HealthyPlugin() }
        val active = runtime.start().plugins

        val result = HostUiAssembly.build(active)

        assertEquals(listOf("healthy.entry"), result.snapshot.entries.map { it.entryId.value })
        assertEquals(
            HostUiDiagnosticCode.PLUGIN_ID_MISMATCH,
            result.diagnostics.single().code,
        )
    }

    @Test
    fun `conflicting contribution is discarded atomically`() {
        val runtime = PluginRuntime()
        runtime.register(fakeDescriptor("healthy")) { HealthyPlugin() }
        runtime.register(fakeDescriptor("conflict")) { ConflictPlugin() }
        val active = runtime.start().plugins

        val result = HostUiAssembly.build(active)

        assertEquals(1, result.snapshot.entries.size)
        assertEquals("healthy.entry", result.snapshot.entries.single().entryId.value)
        assertEquals(
            HostUiDiagnosticCode.UI_CONTRIBUTION_FAILED,
            result.diagnostics.single().code,
        )
    }

    @Test
    fun `ui failure removes plugin and transitive dependents from active set`() {
        val capability = CapabilityKey("fault.value", String::class.java)
        val runtime = PluginRuntime()
        runtime.register(
            PluginDescriptor(
                id = PluginId.parse("fault"),
                version = "1.0.0",
                capabilities = setOf("fault.value"),
            ),
        ) { FaultCapabilityPlugin(capability) }
        runtime.register(
            PluginDescriptor(
                id = PluginId.parse("dependent"),
                version = "1.0.0",
                dependencies = listOf(PluginDependency(PluginId.parse("fault"))),
            ),
        ) { DependentPlugin() }
        runtime.register(fakeDescriptor("healthy")) { HealthyPlugin() }
        val started = runtime.start()

        val resolution = HostUiAssembly.resolve(started.plugins)

        assertEquals(
            listOf("healthy"),
            resolution.activePlugins.ordered.map { it.descriptor.id.value },
        )
        assertNull(resolution.activePlugins.capability(capability))
        assertEquals(
            listOf("healthy.entry"),
            resolution.assembly.snapshot.entries.map { it.entryId.value },
        )
        assertEquals(
            HostUiDiagnosticCode.UI_CONTRIBUTION_FAILED,
            resolution.assembly.diagnostics.single().code,
        )
    }

    private fun fakeDescriptor(rawId: String) = PluginDescriptor(
        id = PluginId.parse(rawId),
        version = "1.0.0",
        capabilities = emptySet(),
    )

    private abstract class BasePlugin(rawId: String) : Plugin, PluginUiContributor {
        override val descriptor = PluginDescriptor(
            id = PluginId.parse(rawId),
            version = "1.0.0",
            capabilities = emptySet(),
        )
    }

    private class HealthyPlugin : BasePlugin("healthy") {
        override val pluginId = descriptor.id

        override fun contribute(registry: PluginUiRegistry) {
            registry.addPage(
                PluginPage(PluginRouteId.parse("healthy.page"), pluginId) { },
            )
            registry.addEntry(
                PluginEntry(
                    entryId = UiEntryId.parse("healthy.entry"),
                    pluginId = pluginId,
                    surface = UiSurface.MORE_LEARNING,
                    order = 0,
                    title = "健康",
                    subtitle = "仍可用",
                    icon = UiIcon.INSIGHTS,
                    route = PluginRouteId.parse("healthy.page"),
                ),
            )
        }
    }

    private class FaultyPlugin : BasePlugin("fault") {
        override val pluginId = descriptor.id

        override fun contribute(registry: PluginUiRegistry) {
            registry.addPage(PluginPage(PluginRouteId.parse("fault.page"), pluginId) { })
            registry.addEntry(
                PluginEntry(
                    entryId = UiEntryId.parse("fault.entry"),
                    pluginId = pluginId,
                    surface = UiSurface.MORE_LEARNING,
                    order = 1,
                    title = "故障",
                    subtitle = "不应出现",
                    icon = UiIcon.INSIGHTS,
                    route = PluginRouteId.parse("fault.page"),
                ),
            )
            error("fixture UI secret must not leak")
        }
    }

    private class MismatchPlugin : BasePlugin("mismatch") {
        override val pluginId = PluginId.parse("other")

        override fun contribute(registry: PluginUiRegistry) {
            registry.addPage(PluginPage(PluginRouteId.parse("other.page"), pluginId) { })
        }
    }

    private class ConflictPlugin : BasePlugin("conflict") {
        override val pluginId = descriptor.id

        override fun contribute(registry: PluginUiRegistry) {
            registry.addPage(PluginPage(PluginRouteId.parse("healthy.page"), pluginId) { })
        }
    }

    private class FaultCapabilityPlugin(
        private val key: CapabilityKey<String>,
    ) : Plugin, PluginUiContributor {
        override val descriptor = PluginDescriptor(
            id = PluginId.parse("fault"),
            version = "1.0.0",
            capabilities = setOf(key.id),
        )
        override val pluginId = descriptor.id

        override fun capabilities(): List<PluginCapability> =
            listOf(PluginCapability(key, "value"))

        override fun contribute(registry: PluginUiRegistry) {
            registry.addPage(PluginPage(PluginRouteId.parse("fault.page"), pluginId) { })
            error("fault ui")
        }
    }

    private class DependentPlugin : Plugin {
        override val descriptor = PluginDescriptor(
            id = PluginId.parse("dependent"),
            version = "1.0.0",
            dependencies = listOf(PluginDependency(PluginId.parse("fault"))),
        )
    }
}
