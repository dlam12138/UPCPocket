package com.upcpocket.app.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.grades.GradesPlugin
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.services.ServicesScreen
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 服务页图钉回归：搜索 → 固定 → 首页显示与点击 → 取消 → 首页移除。
 *
 * 覆盖教务外链和使用同一固定流程的成绩二级页。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp", application = Application::class)
class OfficialWebPinUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `pinning jwxt from services shows it on home and unpin removes it`() {
        val classroomEntry = PluginEntry(
            entryId = UiEntryId.parse("classroom.free"),
            pluginId = PluginId.parse("classroom"),
            surface = UiSurface.HOME_SERVICES,
            order = 1,
            title = "空闲教室",
            subtitle = "按日期与节次",
            icon = UiIcon.CLASSROOM,
            route = null,
            category = UiCategory.STUDY,
        )
        var pinned by mutableStateOf(
            setOf("classroom.free", "official.free_classroom"),
        )
        var showHome by mutableStateOf(false)

        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    if (showHome) {
                        HomeScreen(
                            serviceEntries = listOf(classroomEntry),
                            timetableSections = emptyList(),
                            cardSections = emptyList(),
                            todoSections = emptyList(),
                            sectionHost = object : PluginSectionHost {
                                override fun openSchoolAccountSettings() = Unit
                            },
                            onOpenRoute = {},
                            onOpenSettings = {},
                            onOpenOfficialWeb = {},
                            pinnedServiceIds = pinned,
                        )
                    } else {
                        ServicesScreen(
                            entries = listOf(classroomEntry),
                            pinnedServiceIds = pinned,
                            preferredCampus = null,
                            onTogglePin = { id ->
                                pinned = if (id in pinned) pinned - id else pinned + id
                            },
                            onOpenRoute = {},
                            onOpenOfficialWeb = {},
                        )
                    }
                }
            }
        }

        rule.onNode(hasSetTextAction()).performTextReplacement("教务系统")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("固定到首页", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        rule.runOnIdle { showHome = true }
        rule.waitForIdle()
        rule.onNodeWithText("空闲教室").assertIsDisplayed()
        rule.onNodeWithText("空闲教室（网页）").assertIsDisplayed()
        rule.onNodeWithText("教务系统").assertIsDisplayed()

        rule.runOnIdle { showHome = false }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("教务系统")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("取消首页固定", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        rule.runOnIdle { showHome = true }
        rule.waitForIdle()
        rule.onNodeWithText("教务系统").assertDoesNotExist()
        rule.onNodeWithText("空闲教室（网页）").assertIsDisplayed()
    }

    @Test
    fun `pinning grades opens the same native route from services and home`() {
        AppGraph.init(rule.activity.application)
        AppGraph.startPlugins()
        val entries = AppGraph.ui.entriesIn(UiSurface.SERVICE_DIRECTORY)
            .filter { it.route == GradesPlugin.ROUTE }
        var pinned by mutableStateOf(emptySet<String>())
        var showHome by mutableStateOf(false)
        val openedRoutes = mutableListOf<PluginRouteId>()

        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    if (showHome) {
                        HomeScreen(
                            serviceEntries = entries,
                            timetableSections = emptyList(),
                            cardSections = emptyList(),
                            todoSections = emptyList(),
                            sectionHost = object : PluginSectionHost {
                                override fun openSchoolAccountSettings() = Unit
                            },
                            onOpenRoute = { openedRoutes += it },
                            onOpenSettings = {},
                            onOpenOfficialWeb = {},
                            pinnedServiceIds = pinned,
                        )
                    } else {
                        ServicesScreen(
                            entries = entries,
                            pinnedServiceIds = pinned,
                            preferredCampus = null,
                            onTogglePin = { id ->
                                pinned = if (id in pinned) pinned - id else pinned + id
                            },
                            onOpenRoute = { openedRoutes += it },
                            onOpenOfficialWeb = {},
                        )
                    }
                }
            }
        }

        rule.onNode(hasText("学习与空间") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("成绩查询")
        rule.waitForIdle()
        rule.onNode(hasText("成绩与学分") and hasClickAction()).assertIsDisplayed().performClick()
        rule.onNodeWithText("学校成绩网页").assertDoesNotExist()
        rule.onNodeWithContentDescription("固定到首页", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(setOf(GradesPlugin.ROUTE.value), pinned)

        rule.runOnIdle { showHome = true }
        rule.waitForIdle()
        rule.onNodeWithText("成绩与学分").assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(listOf(GradesPlugin.ROUTE, GradesPlugin.ROUTE), openedRoutes)

        rule.runOnIdle { showHome = false }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("成绩查询")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("取消首页固定", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(pinned.isEmpty())

        rule.runOnIdle { showHome = true }
        rule.waitForIdle()
        rule.onNodeWithText("成绩与学分").assertDoesNotExist()
    }
}
