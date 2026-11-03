package com.upcpocket.app.plugin.ui

import com.upcpocket.app.plugin.api.PluginId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginUiRegistryTest {

    private val card = PluginId.parse("card")
    private val power = PluginId.parse("power")

    @Test
    fun `entries keep stable order per surface`() {
        val registry = PluginUiRegistry()
        registry.addPage(page("card.page", card))
        registry.addPage(page("power.page", power))
        registry.addEntry(entry("power.tangdaowan", power, UiSurface.HOME_SERVICES, 20, "power.page"))
        registry.addEntry(entry("card.balance", card, UiSurface.HOME_SERVICES, 10, "card.page"))

        val snapshot = registry.build()

        assertEquals(
            listOf("card.balance", "power.tangdaowan"),
            snapshot.entriesFor(UiSurface.HOME_SERVICES).map { it.entryId.value },
        )
    }

    @Test
    fun `duplicate entry id is rejected instead of overwritten`() {
        val registry = PluginUiRegistry()
        registry.addPage(page("card.page", card))
        registry.addEntry(entry("card.balance", card, UiSurface.HOME_SERVICES, 10, "card.page"))

        assertThrows(PluginUiException::class.java) {
            registry.addEntry(entry("card.balance", power, UiSurface.HOME_SERVICES, 20, "card.page"))
        }
    }

    @Test
    fun `entry without registered page fails build`() {
        val registry = PluginUiRegistry()
        registry.addEntry(entry("card.balance", card, UiSurface.HOME_SERVICES, 10, "card.page"))

        val error = assertThrows(PluginUiException::class.java) { registry.build() }

        assertEquals(true, error.message.orEmpty().contains("未注册页面"))
    }

    @Test
    fun `duplicate page route is rejected at registration`() {
        val registry = PluginUiRegistry()
        registry.addPage(page("card.page", card))

        assertThrows(PluginUiException::class.java) {
            registry.addPage(page("card.page", power))
        }
    }

    @Test
    fun `sections share the entry namespace and preserve order`() {
        val registry = PluginUiRegistry()
        registry.addSection(section("card.balance.section", card, 20))
        registry.addSection(section("library.login.section", power, 10))

        val snapshot = registry.build()

        assertEquals(
            listOf("library.login.section", "card.balance.section"),
            snapshot.sectionsFor(UiSurface.HOME_CARD).map { it.entryId.value },
        )
        assertThrows(PluginUiException::class.java) {
            registry.addSection(section("card.balance.section", card, 30))
        }
    }

    @Test
    fun `entry cannot point at another plugin page`() {
        val registry = PluginUiRegistry()
        registry.addPage(page("card.page", card))
        registry.addEntry(entry("card.balance", power, UiSurface.HOME_SERVICES, 10, "card.page"))

        val error = assertThrows(PluginUiException::class.java) { registry.build() }

        assertEquals(true, error.message.orEmpty().contains("属于"))
    }

    private fun page(rawRoute: String, owner: PluginId) =
        PluginPage(PluginRouteId.parse(rawRoute), owner) { }

    private fun entry(
        rawId: String,
        owner: PluginId,
        surface: UiSurface,
        order: Int,
        route: String,
    ) = PluginEntry(
        entryId = UiEntryId.parse(rawId),
        pluginId = owner,
        surface = surface,
        order = order,
        title = "标题",
        subtitle = "副标题",
        icon = UiIcon.LIBRARY,
        route = PluginRouteId.parse(route),
    )

    private fun section(rawId: String, owner: PluginId, order: Int) =
        PluginSection(UiEntryId.parse(rawId), owner, UiSurface.HOME_CARD, order) { }
}
