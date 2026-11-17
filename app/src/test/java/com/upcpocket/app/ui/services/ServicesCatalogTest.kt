package com.upcpocket.app.ui.services

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.official.OfficialWebCatalog
import org.junit.Assert.assertEquals
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
}
