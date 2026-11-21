package com.upcpocket.app.ui.home

import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePinnedServicesTest {

    @Test
    fun `empty and unavailable pin ids do not fall back to all services`() {
        val available = listOf(
            PluginEntry(
                entryId = UiEntryId.parse("power.electricity"),
                pluginId = PluginId.parse("power"),
                surface = UiSurface.SERVICE_DIRECTORY,
                order = 0,
                title = "电量",
                subtitle = "唐岛湾",
                icon = UiIcon.ELECTRICITY,
                route = null,
                category = UiCategory.LIFE,
            ),
        )

        assertEquals(emptyList<PluginEntry>(), pinnedPluginEntries(available, emptySet()))
        assertEquals(emptyList<PluginEntry>(), pinnedPluginEntries(available, setOf("library.home")))
        assertEquals(available, pinnedPluginEntries(available, setOf("power.electricity")))
    }

    @Test
    fun `official web ids map to home shortcuts and drop after unpin`() {
        val pinned = setOf(
            "classroom.free",
            "official.calendar",
            "official.academic_system",
        )

        assertEquals(
            listOf("校历", "教务系统"),
            pinnedOfficialWebLinks(pinned).map { it.title },
        )
        assertEquals(
            listOf("校历"),
            pinnedOfficialWebLinks(pinned - "official.academic_system").map { it.title },
        )
        assertTrue(pinnedOfficialWebLinks(emptySet()).isEmpty())
    }
}
