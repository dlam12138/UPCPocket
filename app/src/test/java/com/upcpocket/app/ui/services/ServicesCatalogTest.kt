package com.upcpocket.app.ui.services

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.PreferredCampus
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.grades.GradesPlugin
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.official.GymReservationHandoff
import com.upcpocket.app.ui.official.OfficialLink
import com.upcpocket.app.ui.official.OfficialWebCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun `grades has one native service route and an official directory fallback`() {
        val entry = AppGraph.ui.entriesIn(UiSurface.HOME_SERVICES, UiSurface.SERVICE_DIRECTORY)
            .single { it.title == "成绩与学分" }
        assertEquals(UiSurface.SERVICE_DIRECTORY, entry.surface)
        assertEquals(UiCategory.STUDY, entry.category)
        assertEquals(GradesPlugin.ROUTE, entry.route)
        assertEquals(GradesPlugin.ROUTE.value, entry.entryId.value)
        assertTrue(AppGraph.ui.page(GradesPlugin.ROUTE) != null)
        assertFalse(entry.entryId.value in UiPrefs.DEFAULT_PINNED_SERVICES)

        val fallback = OfficialWebCatalog.academicScores
        assertEquals("学校成绩网页", fallback.title)
        assertNull(fallback.serviceId)
        assertTrue(fallback in OfficialWebCatalog.all)
        assertFalse(fallback in OfficialWebCatalog.serviceLinks)
        assertFalse(fallback in OfficialWebCatalog.serviceLinksFor(UiCategory.STUDY))

        for (campus in listOf(PreferredCampus.TANGDAOWAN, PreferredCampus.GUZHENKOU)) {
            assertTrue(fallback in OfficialWebCatalog.visibleFor(OfficialWebCatalog.all, campus))
        }

        val intent = OfficialWebCatalog.browserIntent(fallback)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://app.upc.edu.cn/site/score/index"), intent.data)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.extras)
    }

    @Test
    fun `official study directory appends four general school links after calendar`() {
        val expected = listOf(
            Triple("教务系统", "https://jwxt.upc.edu.cn/jsxsd/framework/xsMainV.htmlx", "official.academic_system"),
            Triple("数字石大", "https://i.upc.edu.cn/", "official.digital_upc"),
            Triple("教务处官网", "https://jwc.upc.edu.cn/", "official.jwc"),
            Triple("学院官网", "https://www.upc.edu.cn/jxkydw.htm", "official.schools"),
        )
        val studyLinks = OfficialWebCatalog.learning
        val appended = studyLinks.takeLast(expected.size)

        assertEquals(expected.map { it.first }, appended.map { it.title })
        assertTrue(
            studyLinks.dropLast(expected.size)
                .any { it.serviceId == OfficialWebCatalog.CALENDAR_SERVICE_ID },
        )

        expected.forEachIndexed { index, entry ->
            val (title, url, serviceId) = entry
            val link = appended[index]
            assertSame(link, OfficialWebCatalog.all.single { it.title == title })
            assertEquals("学习", link.group)
            assertEquals(url, link.url)
            assertEquals(serviceId, link.serviceId)
            assertNull(link.serviceCategory)
            assertNull(link.campus)

            val intent = OfficialWebCatalog.browserIntent(link)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertNull(intent.extras)
        }

        val studyServices = OfficialWebCatalog.serviceLinksFor(UiCategory.STUDY)
        assertTrue(expected.none { (title, _, _) -> studyServices.any { it.title == title } })
    }

    @Test
    fun `pinnable browser entries have stable unique home pin ids`() {
        val expected = mapOf(
            "课表" to "official.timetable",
            "空闲教室" to "official.free_classroom",
            "图书馆座位系统" to "official.library_seat",
            "校历" to "official.calendar",
            "教务系统" to "official.academic_system",
            "数字石大" to "official.digital_upc",
            "教务处官网" to "official.jwc",
            "学院官网" to "official.schools",
            "唐岛湾剩余电量" to "official.power_tangdaowan",
            "古镇口电费" to "official.power_guzhenkou",
        )
        val browserLinks = OfficialWebCatalog.all.filter { it.serviceId != null }

        assertEquals(expected.keys, browserLinks.map { it.title }.toSet())
        assertEquals(expected, browserLinks.associate { it.title to it.serviceId })
        assertEquals(
            expected.size,
            browserLinks.mapNotNull { it.serviceId }.toSet().size,
        )
    }

    @Test
    fun `pinned other-campus page stays visible for unpin when preferred campus changes`() {
        val pinnedTangdaowan = OfficialWebCatalog.TANGDAOWAN_POWER_SERVICE_ID

        val withPin = OfficialWebCatalog.visibleFor(
            OfficialWebCatalog.all,
            PreferredCampus.GUZHENKOU,
            keepServiceIds = setOf(pinnedTangdaowan),
        )
        assertEquals(
            "唐岛湾剩余电量",
            withPin.single { it.serviceId == pinnedTangdaowan }.title,
        )
        assertFalse(withPin.any { it.title == "游泳馆预约" })
        assertTrue(withPin.any { it.title == "古镇口电费" })

        val afterUnpin = OfficialWebCatalog.visibleFor(
            OfficialWebCatalog.all,
            PreferredCampus.GUZHENKOU,
            keepServiceIds = emptySet(),
        )
        assertFalse(afterUnpin.any { it.serviceId == pinnedTangdaowan })
    }

    @Test
    fun `venue reservations are searchable life handoffs without home pins`() {
        val gym = OfficialWebCatalog.all.single { it.title == "体育馆预约" }
        val pool = OfficialWebCatalog.all.single { it.title == "游泳馆预约" }

        assertEquals(listOf(gym, pool), OfficialWebCatalog.life.filter { it.weChatHandoff != null })
        for (link in listOf(gym, pool)) {
            assertEquals("生活", link.group)
            assertEquals(UiCategory.LIFE, link.serviceCategory)
            assertNull(link.serviceId)
            assertTrue(link in OfficialWebCatalog.serviceLinksFor(UiCategory.LIFE))
            assertFalse(link in OfficialWebCatalog.serviceLinksFor(UiCategory.STUDY))
        }
        assertNull(gym.campus)
        assertEquals(GymReservationHandoff.gym, gym.weChatHandoff)
        assertEquals(GymReservationHandoff.RULES_URL, gym.url)
        assertEquals(PreferredCampus.TANGDAOWAN, pool.campus)
        assertEquals("唐岛湾 · 官方微信", pool.subtitle)
        assertEquals(GymReservationHandoff.pool, pool.weChatHandoff)
        assertEquals(GymReservationHandoff.POOL_INTRO_URL, pool.url)

        val context = ApplicationProvider.getApplicationContext<Application>()
        for (link in listOf(gym, pool)) {
            var handoff: OfficialLink? = null
            OfficialWebCatalog.activate(context, link) { handoff = it }
            assertSame(link, handoff)
        }
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `single preferred campus filters campus-owned links but keeps generic ones`() {
        val all = OfficialWebCatalog.all
        val tangdaowan = OfficialWebCatalog.visibleFor(all, PreferredCampus.TANGDAOWAN)
        val guzhenkou = OfficialWebCatalog.visibleFor(all, PreferredCampus.GUZHENKOU)

        assertTrue(tangdaowan.any { it.title == "图书馆座位系统" })
        assertTrue(tangdaowan.any { it.title == "唐岛湾剩余电量" })
        assertTrue(tangdaowan.any { it.title == "游泳馆预约" })
        assertFalse(tangdaowan.any { it.title == "古镇口电费" })
        assertFalse(guzhenkou.any { it.title == "图书馆座位系统" })
        assertFalse(guzhenkou.any { it.title == "唐岛湾剩余电量" })
        assertFalse(guzhenkou.any { it.title == "游泳馆预约" })
        assertTrue(guzhenkou.any { it.title == "古镇口电费" })

        for (links in listOf(tangdaowan, guzhenkou)) {
            assertTrue(links.any { it.title == "空闲教室" })
            assertTrue(links.any { it.serviceId == OfficialWebCatalog.CALENDAR_SERVICE_ID })
        }

        assertEquals(all, OfficialWebCatalog.visibleFor(all, PreferredCampus.ALL))
        assertEquals(all, OfficialWebCatalog.visibleFor(all, null))
    }
}
