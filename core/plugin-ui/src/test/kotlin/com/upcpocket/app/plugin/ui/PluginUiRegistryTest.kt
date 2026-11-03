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
            registry.addEntry(entry("card.balance", card, UiSurface.HOME_SERVICES, 20, "card.page"))
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
            registry.addPage(page("card.page", card))
        }
    }

    @Test
    fun `sections share the entry namespace and preserve order`() {
        val registry = PluginUiRegistry()
        registry.addSection(section("card.balance.section", card, 20))
        registry.addSection(section("library.login.section", PluginId.parse("library"), 10))

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
    fun `bottom nav keeps plugin order and must point at own page`() {
        val registry = PluginUiRegistry()
        registry.addPage(page("card.main", card))
        registry.addPage(page("power.main", power))
        registry.addBottomNav(nav("power.tab", power, 20, "power.main"))
        registry.addBottomNav(nav("card.tab", card, 10, "card.main"))

        val snapshot = registry.build()

        assertEquals(
            listOf("card.tab", "power.tab"),
            snapshot.bottomNav.map { it.entryId.value },
        )
        assertThrows(PluginUiException::class.java) {
            registry.addBottomNav(nav("card.tab", card, 30, "card.main"))
        }
    }

    @Test
    fun `bottom nav cannot point at missing page`() {
        val missing = PluginUiRegistry()
        missing.addBottomNav(nav("card.tab", card, 10, "card.missing"))
        assertThrows(PluginUiException::class.java) { missing.build() }
    }

    @Test
    fun `id outside plugin namespace is rejected`() {
        val registry = PluginUiRegistry()
        registry.addPage(page("card.page", card))

        assertThrows(PluginUiException::class.java) {
            registry.addEntry(entry("other.entry", card, UiSurface.HOME_SERVICES, 10, "card.page"))
        }
    }

    @Test
    fun `fork does not mutate accepted snapshot until replaced`() {
        val accepted = PluginUiRegistry()
        accepted.addPage(page("card.page", card))
        val fork = accepted.fork()
        fork.addEntry(entry("card.entry2", card, UiSurface.HOME_SERVICES, 10, "card.page"))

        assertEquals(emptyList<PluginEntry>(), accepted.build().entries)
        accepted.replaceWith(fork)
        assertEquals(listOf("card.entry2"), accepted.build().entries.map { it.entryId.value })
    }

    private fun page(rawRoute: String, owner: PluginId) =
        PluginPage(PluginRouteId.parse(rawRoute), owner) { }

    private fun nav(rawId: String, owner: PluginId, order: Int, route: String) =
        PluginBottomNav(
            entryId = UiEntryId.parse(rawId),
            pluginId = owner,
            order = order,
            label = "专注",
            icon = UiIcon.TIMER,
            route = PluginRouteId.parse(route),
        )

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
        PluginSection(UiEntryId.parse(rawId), owner, UiSurface.HOME_CARD, order) { _ -> }
}
