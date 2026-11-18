package com.upcpocket.app.ui.services

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.official.OfficialWebCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ServicesCatalogTest {

    @Before
    fun setUp() {
        AppGraph.init(ApplicationProvider.getApplicationContext<Application>())
        AppGraph.startPlugins()
    }

    @Test
    fun `all service entries carry non-general categories and valid pages`() {
        val entries = AppGraph.ui.entriesIn(UiSurface.HOME_SERVICES, UiSurface.SERVICE_DIRECTORY)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.category != UiCategory.GENERAL })
        assertEquals(
            setOf(UiCategory.STUDY, UiCategory.LIFE, UiCategory.CAMPUS),
            entries.map { it.category }.toSet(),
        )
        entries.forEach { entry ->
            assertTrue("Missing page for ${entry.entryId}", AppGraph.ui.page(entry.route!!) != null)
        }
        assertTrue(OfficialWebCatalog.all.isNotEmpty())
    }

    @Test
    fun `host calendar is a study service independent of plugin entries`() {
        val calendar = OfficialWebCatalog.serviceLinksFor(UiCategory.STUDY).single()

        assertEquals(OfficialWebCatalog.CALENDAR_SERVICE_ID, calendar.serviceId)
        assertEquals("https://jwc.upc.edu.cn/603/list.htm", calendar.url)
        assertEquals("学习", calendar.group)
        assertFalse(OfficialWebCatalog.CALENDAR_SERVICE_ID in UiPrefs.DEFAULT_PINNED_SERVICES)

        val intent = OfficialWebCatalog.browserIntent(calendar)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(OfficialWebCatalog.CALENDAR_URL), intent.data)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
}
