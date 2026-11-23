package com.upcpocket.app.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.prefs.HomeModuleId
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class HomeModuleReorderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `long press dragging home modules reorders them and reports updated order`() {
        var order by mutableStateOf(HomeModuleId.DEFAULT_ORDER)
        val entries = listOf(
            PluginEntry(
                entryId = UiEntryId.parse("classroom.free"),
                pluginId = PluginId.parse("classroom"),
                surface = UiSurface.HOME_SERVICES,
                order = 1,
                title = "空教室",
                subtitle = "查询",
                icon = UiIcon.CLASSROOM,
                route = null,
                category = UiCategory.STUDY,
            ),
        )
        val timetableSection = PluginSection(
            entryId = UiEntryId.parse("timetable.today"),
            pluginId = PluginId.parse("timetable"),
            surface = UiSurface.HOME_TIMETABLE,
            order = 0,
        ) {
            Box(Modifier.fillMaxWidth().height(90.dp)) { Text("今日课程模块") }
        }
        val todoSection = PluginSection(
            entryId = UiEntryId.parse("todo.home"),
            pluginId = PluginId.parse("todo"),
            surface = UiSurface.HOME_TODO,
            order = 0,
        ) {
            Box(Modifier.fillMaxWidth().height(90.dp)) { Text("待办摘要模块") }
        }
        val cardSection = PluginSection(
            entryId = UiEntryId.parse("card.balance"),
            pluginId = PluginId.parse("card"),
            surface = UiSurface.HOME_CARD,
            order = 0,
        ) {
            Box(Modifier.fillMaxWidth().height(90.dp)) { Text("校园卡模块") }
        }

        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    HomeScreen(
                        serviceEntries = entries,
                        timetableSections = listOf(timetableSection),
                        cardSections = listOf(cardSection),
                        todoSections = listOf(todoSection),
                        sectionHost = object : PluginSectionHost {
                            override fun openSchoolAccountSettings() = Unit
                        },
                        onOpenRoute = {},
                        onOpenSettings = {},
                        onOpenOfficialWeb = {},
                        pinnedServiceIds = setOf("classroom.free"),
                        moduleOrder = order,
                        onModuleOrderChange = { order = it },
                    )
                }
            }
        }

        val initialTimetableTop = rule.onNodeWithTag("home_module_timetable")
            .fetchSemanticsNode().boundsInRoot.top
        val initialTodoTop = rule.onNodeWithTag("home_module_todo")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(initialTimetableTop < initialTodoTop)

        rule.onNodeWithTag("home_module_timetable").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(0f, height * 1.4f), delayMillis = 120L)
            up()
        }
        rule.waitForIdle()

        assertEquals(
            listOf(
                HomeModuleId.TODO,
                HomeModuleId.TIMETABLE,
                HomeModuleId.SERVICES,
                HomeModuleId.CARD,
            ),
            order,
        )
        val newTimetableTop = rule.onNodeWithTag("home_module_timetable")
            .fetchSemanticsNode().boundsInRoot.top
        val newTodoTop = rule.onNodeWithTag("home_module_todo")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("拖放后今日课程应位于待办下方", newTimetableTop > newTodoTop)
    }
}
