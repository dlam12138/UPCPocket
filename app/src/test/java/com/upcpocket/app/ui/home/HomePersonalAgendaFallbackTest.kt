package com.upcpocket.app.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.prefs.HomeModuleId
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 课表插件缺失但待办提供个人日历时，首页仍以稳定的课表模块位置显示本机日程；
 * 已有课表首页区块时绝不重复渲染本机兜底卡。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class HomePersonalAgendaFallbackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun timetableSection(): PluginSection = PluginSection(
        entryId = UiEntryId.parse("timetable.today"),
        pluginId = PluginId.parse("timetable"),
        surface = UiSurface.HOME_TIMETABLE,
        order = 0,
    ) {
        Box(Modifier.fillMaxWidth().height(90.dp)) { Text("今日课程模块") }
    }

    private fun content(
        timetableSections: List<PluginSection>,
        personalAgendaHome: (@Composable () -> Unit)?,
    ) {
        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    HomeScreen(
                        serviceEntries = emptyList(),
                        timetableSections = timetableSections,
                        cardSections = emptyList(),
                        todoSections = emptyList(),
                        sectionHost = object : PluginSectionHost {
                            override fun openSchoolAccountSettings() = Unit
                        },
                        onOpenRoute = {},
                        onOpenSettings = {},
                        onOpenOfficialWeb = {},
                        pinnedServiceIds = emptySet(),
                        moduleOrder = listOf(HomeModuleId.TIMETABLE),
                        personalAgendaHome = personalAgendaHome,
                    )
                }
            }
        }
    }

    @Test
    fun agendaFallbackKeepsTimetableModuleWithoutTimetableSection() {
        content(
            timetableSections = emptyList(),
            personalAgendaHome = {
                Box(Modifier.fillMaxWidth().height(90.dp)) { Text("本机今日安排") }
            },
        )

        rule.onNodeWithTag("home_module_timetable").assertExists()
        rule.onNodeWithText("本机今日安排").assertExists()
    }

    @Test
    fun agendaFallbackNeverDuplicatesAnExistingTimetableSection() {
        content(
            timetableSections = listOf(timetableSection()),
            personalAgendaHome = { Text("本机今日安排") },
        )

        rule.onNodeWithText("今日课程模块").assertExists()
        rule.onNodeWithText("本机今日安排").assertDoesNotExist()
    }

    @Test
    fun timetableModuleStaysHiddenWithoutSectionOrAgenda() {
        content(timetableSections = emptyList(), personalAgendaHome = null)

        rule.onNodeWithTag("home_module_timetable").assertDoesNotExist()
    }
}
