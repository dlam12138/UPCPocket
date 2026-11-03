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

        assertTrue(result is PluginStartResult.Active)
        val active = (result as PluginStartResult.Active).plugins
        assertEquals(listOf("card", "focus"), active.ordered.map { it.descriptor.id.value })
        assertEquals("42.00", active.capability(cardBalance))
        assertNull(active.capability(powerReading))
        assertEquals(setOf("card.balance"), active.capabilitiesOf(id("card")))
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
    fun `missing required dependency invalidates whole start`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("library", dependencies = listOf(PluginDependency(id("account"), required = true))),
            factory("library"),
        )

        val result = runtime.start()

        val diagnostics = (result as PluginStartResult.Invalid).diagnostics
        assertEquals(PluginDiagnosticCode.MISSING_DEPENDENCY, diagnostics.single().code)
        assertEquals("library", diagnostics.single().pluginId)
        assertTrue(runtime.start() is PluginStartResult.Invalid)
    }

    @Test
    fun `optional missing dependency does not block start`() {
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("library", dependencies = listOf(PluginDependency(id("account"), required = false))),
            factory("library"),
        )

        assertTrue(runtime.start() is PluginStartResult.Active)
    }

    @Test
    fun `dependency cycle is rejected without creating plugins`() {
        var created = 0
        val runtime = PluginRuntime()
        runtime.register(
            descriptor("card", dependencies = listOf(PluginDependency(id("power")))),
            PluginFactory { created++; FakePlugin(descriptor("card")) },
        )
        runtime.register(
            descriptor("power", dependencies = listOf(PluginDependency(id("card")))),
            PluginFactory { created++; FakePlugin(descriptor("power")) },
        )

        val result = runtime.start()

        assertEquals(0, created)
        val diagnostics = (result as PluginStartResult.Invalid).diagnostics
        assertTrue(diagnostics.any { it.code == PluginDiagnosticCode.DEPENDENCY_CYCLE })
    }

    @Test
    fun `host api mismatch is rejected`() {
        val runtime = PluginRuntime(hostApiVersion = 2)
        runtime.register(descriptor("card", hostApi = 1..1), factory("card"))

        val diagnostics = (runtime.start() as PluginStartResult.Invalid).diagnostics

        assertEquals(PluginDiagnosticCode.HOST_API_MISMATCH, diagnostics.single().code)
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

        val first = runtime.start()

        val diagnostics = (first as PluginStartResult.Invalid).diagnostics
        assertEquals(PluginDiagnosticCode.CREATION_FAILED, diagnostics.single().code)
        assertFalse(diagnostics.single().detail.contains("fixture secret"))
        // 失败后保持失败态，不会出现部分激活。
        assertTrue(runtime.start() is PluginStartResult.Invalid)
    }

    @Test
    fun `duplicate capability declarations fail plan`() {
        val runtime = PluginRuntime()
        runtime.register(descriptor("card", capabilities = setOf("card.balance")), factory("card"))
        runtime.register(descriptor("power", capabilities = setOf("card.balance")), factory("power"))

        val plan = runtime.plan()

        assertFalse(plan.isValid)
        assertTrue(plan.diagnostics.any { it.code == PluginDiagnosticCode.DUPLICATE_CAPABILITY })
    }

    @Test
    fun `declared and provided capabilities must match exactly`() {
        val runtime = PluginRuntime()
        runtime.register(descriptor("card", capabilities = setOf("card.balance")), factory("card"))

        val result = runtime.start()

        val diagnostics = (result as PluginStartResult.Invalid).diagnostics
        assertEquals(PluginDiagnosticCode.CAPABILITY_MISMATCH, diagnostics.single().code)
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
