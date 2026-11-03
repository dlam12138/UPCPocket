package com.upcpocket.app.plugin.runtime

import com.upcpocket.app.plugin.api.CapabilityKey
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDependency
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginFactory
import com.upcpocket.app.plugin.api.PluginId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRuntimeTest {

    private val cardBalance = CapabilityKey("card.balance", String::class.java)
    private val powerReading = CapabilityKey("power.reading", String::class.java)

    @Test
    fun `start honours dependency order and exposes typed capability`() {
        val runtime = PluginRuntime()
        runtime.register(descriptor("card", capabilities = setOf("card.balance")), factory("card", cardBalance, "42.00"))
        runtime.register(
            descriptor("focus", dependencies = listOf(PluginDependency(id("card")))),
            factory("focus"),
        )

        val result = runtime.start()

        val active = result.plugins
        assertEquals(listOf("card", "focus"), active.ordered.map { it.descriptor.id.value })
        assertEquals("42.00", active.capability(cardBalance))
        assertNull(active.capability(powerReading))
        assertEquals(setOf("card.balance"), active.capabilitiesOf(id("card")))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `duplicate plugin id rejected at registration`() {
        val runtime = PluginRuntime()
        runtime.register(descriptor("card"), factory("card"))
        assertThrows(PluginRegistrationException::class.java) {
            runtime.register(descriptor("card"), factory("card"))
        }
    }

    @Test
    fun `one creation failure keeps independent plugin active`() {
        var healthyCreated = 0
        var brokenCreated = 0
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("power", capabilities = setOf("power.reading")),
            PluginFactory {
                brokenCreated++
                error("fixture secret must not leak")
            },
        )
        runtime.register(
            descriptor("card", capabilities = setOf("card.balance")),
            PluginFactory {
                healthyCreated++
                FakePlugin(
                    descriptor("card"),
                    listOf(PluginCapability(cardBalance, "42.00")),
                )
            },
        )

        val first = runtime.start()

        assertEquals(listOf("card"), first.plugins.ordered.map { it.descriptor.id.value })
        assertEquals("42.00", first.plugins.capability(cardBalance))
        assertEquals(1, healthyCreated)
        assertEquals(1, brokenCreated)
        val failure = first.diagnostics.single()
        assertEquals(PluginDiagnosticCode.CREATION_FAILED, failure.code)
        assertEquals("power", failure.pluginId)
        assertFalse(failure.detail.contains("fixture secret"))
        // 重复 start 不重复创建，也不把失败插件补回来。
        assertSameResult(first, runtime.start())
        assertEquals(1, brokenCreated)
    }

    @Test
    fun `failure propagates through transitive required dependencies only`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("base", capabilities = setOf("base.data")),
            PluginFactory { error("base failed") },
        )
        runtime.register(
            descriptor("middle", dependencies = listOf(PluginDependency(id("base")))),
            factory("middle"),
        )
        runtime.register(
            descriptor("leaf", dependencies = listOf(PluginDependency(id("middle")))),
            factory("leaf"),
        )
        runtime.register(descriptor("independent"), factory("independent"))

        val result = runtime.start()

        assertEquals(listOf("independent"), result.plugins.ordered.map { it.descriptor.id.value })
        assertEquals(
            PluginDiagnosticCode.CREATION_FAILED,
            result.diagnostics.single { it.pluginId == "base" }.code,
        )
        assertEquals(
            PluginDiagnosticCode.DEPENDENCY_DISABLED,
            result.diagnostics.single { it.pluginId == "middle" }.code,
        )
        assertEquals(
            PluginDiagnosticCode.DEPENDENCY_DISABLED,
            result.diagnostics.single { it.pluginId == "leaf" }.code,
        )
    }

    @Test
    fun `missing required dependency isolates plugin and keeps independent`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("library", dependencies = listOf(PluginDependency(id("account"), required = true))),
            factory("library"),
        )
        runtime.register(descriptor("healthy"), factory("healthy"))

        val result = runtime.start()

        assertEquals(listOf("healthy"), result.plugins.ordered.map { it.descriptor.id.value })
        val failure = result.diagnostics.single()
        assertEquals(PluginDiagnosticCode.MISSING_DEPENDENCY, failure.code)
        assertEquals("library", failure.pluginId)
    }

    @Test
    fun `optional missing dependency does not block plugin`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("library", dependencies = listOf(PluginDependency(id("account"), required = false))),
            factory("library"),
        )

        val result = runtime.start()

        assertEquals(listOf("library"), result.plugins.ordered.map { it.descriptor.id.value })
    }

    @Test
    fun `optional failed dependency does not block plugin`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("account"),
            PluginFactory { error("optional unavailable") },
        )
        runtime.register(
            descriptor("library", dependencies = listOf(PluginDependency(id("account"), required = false))),
            factory("library"),
        )

        val result = runtime.start()

        assertEquals(listOf("library"), result.plugins.ordered.map { it.descriptor.id.value })
        assertEquals(PluginDiagnosticCode.CREATION_FAILED, result.diagnostics.single().code)
    }

    @Test
    fun `dependency cycle only disables cycle and dependents`() {
        var cycleCreated = 0
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("card", dependencies = listOf(PluginDependency(id("power")))),
            PluginFactory { cycleCreated++; FakePlugin(descriptor("card")) },
        )
        runtime.register(
            descriptor("power", dependencies = listOf(PluginDependency(id("card")))),
            PluginFactory { cycleCreated++; FakePlugin(descriptor("power")) },
        )
        runtime.register(
            descriptor("downstream", dependencies = listOf(PluginDependency(id("card")))),
            factory("downstream"),
        )
        runtime.register(descriptor("healthy"), factory("healthy"))

        val result = runtime.start()

        assertEquals(0, cycleCreated)
        assertEquals(listOf("healthy"), result.plugins.ordered.map { it.descriptor.id.value })
        assertEquals(3, result.diagnostics.count { it.code == PluginDiagnosticCode.DEPENDENCY_CYCLE })
    }

    @Test
    fun `host api mismatch disables only that plugin`() {
        val runtime = PluginRuntime(hostApiVersion = 2)
        runtime.register(descriptor("card", hostApi = 1..1), factory("card"))
        runtime.register(descriptor("healthy", hostApi = 2..2), factory("healthy"))

        val result = runtime.start()

        assertEquals(listOf("healthy"), result.plugins.ordered.map { it.descriptor.id.value })
        assertEquals(PluginDiagnosticCode.HOST_API_MISMATCH, result.diagnostics.single().code)
    }

    @Test
    fun `creation failure leaves no half registered capabilities`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("card", capabilities = setOf("card.balance")),
            factory("card", cardBalance, "42.00"),
        )
        runtime.register(
            descriptor("power", capabilities = setOf("power.reading")),
            PluginFactory { throw IllegalStateException("fixture secret must not leak") },
        )

        val result = runtime.start()

        assertEquals(listOf("card"), result.plugins.ordered.map { it.descriptor.id.value })
        assertEquals(setOf("card.balance"), result.plugins.capabilityIds())
        assertEquals(PluginDiagnosticCode.CREATION_FAILED, result.diagnostics.single().code)
        assertFalse(result.diagnostics.single().detail.contains("fixture secret"))
    }

    @Test
    fun `duplicate capability later plugin is isolated`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("card", capabilities = setOf("card.balance")),
            factory("card", cardBalance, "1.00"),
        )
        runtime.register(
            descriptor("power", capabilities = setOf("card.balance")),
            factory("power", cardBalance, "2.00"),
        )
        runtime.register(descriptor("healthy"), factory("healthy"))

        val result = runtime.start()

        assertEquals(listOf("card", "healthy"), result.plugins.ordered.map { it.descriptor.id.value })
        assertEquals(PluginDiagnosticCode.DUPLICATE_CAPABILITY, result.diagnostics.single().code)
        assertEquals("power", result.diagnostics.single().pluginId)
    }

    @Test
    fun `declared and provided capabilities must match exactly`() {
        val runtime = PluginRuntime()
        runtime.register(descriptor("card", capabilities = setOf("card.balance")), factory("card"))
        runtime.register(descriptor("healthy"), factory("healthy"))

        val result = runtime.start()

        assertEquals(listOf("healthy"), result.plugins.ordered.map { it.descriptor.id.value })
        assertEquals(PluginDiagnosticCode.CAPABILITY_MISMATCH, result.diagnostics.single().code)
    }

    @Test
    fun `static plan reports missing dependency without failing unrelated plugin`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("library", dependencies = listOf(PluginDependency(id("account")))),
            factory("library"),
        )
        runtime.register(descriptor("healthy"), factory("healthy"))

        val plan = runtime.plan()

        assertFalse(plan.isValid)
        assertEquals(PluginDiagnosticCode.MISSING_DEPENDENCY, plan.diagnostics.single().code)
    }

    private fun assertSameResult(first: PluginStartResult.Started, second: PluginStartResult.Started) {
        assertEquals(first.plugins.ordered.map { it.descriptor.id }, second.plugins.ordered.map { it.descriptor.id })
        assertEquals(first.diagnostics, second.diagnostics)
    }

    private fun id(raw: String) = PluginId.parse(raw)

    private fun descriptor(
        rawId: String,
        capabilities: Set<String> = emptySet(),
        dependencies: List<PluginDependency> = emptyList(),
        hostApi: IntRange = 1..1,
    ) = PluginDescriptor(
        id = id(rawId),
        version = "1.0.0",
        hostApi = hostApi,
        dependencies = dependencies,
        capabilities = capabilities,
    )

    private fun factory(rawId: String, capability: CapabilityKey<*>? = null, value: String? = null) =
        PluginFactory {
            FakePlugin(
                descriptor = descriptor(rawId),
                capabilities = if (capability != null && value != null) {
                    listOf(PluginCapability(capability, value))
                } else {
                    emptyList()
                },
            )
        }

    private class FakePlugin(
        override val descriptor: PluginDescriptor,
        private val capabilities: List<PluginCapability> = emptyList(),
    ) : Plugin {
        override fun capabilities(): List<PluginCapability> = capabilities
    }
}
