package com.upcpocket.app.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginEntry
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

/**
 * 首页触边回弹的离线回归：普通上滑后回到顶部，快速下拉并立即反向上滑，
 * 首页仍应继续滚动。Robolectric 不渲染 Android 12+ 的硬件拉伸，
 * 这里只跟踪 ScrollState 的语义滚动范围，不冒充真机伸展验收。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class HomeScrollGestureTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `continued scroll works after a fast pull down at the top`() {
        val entries = (1..24).map { index ->
            PluginEntry(
                entryId = UiEntryId.parse("power.service$index"),
                pluginId = PluginId.parse("power"),
                surface = UiSurface.HOME_SERVICES,
                order = index,
                title = "快捷入口 $index",
                subtitle = "离线测试",
                icon = UiIcon.ELECTRICITY,
                route = null,
                category = UiCategory.LIFE,
            )
        }
        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    HomeScreen(
                        serviceEntries = entries,
                        timetableSections = emptyList(),
                        cardSections = emptyList(),
                        todoSections = emptyList(),
                        sectionHost = object : PluginSectionHost {
                            override fun openSchoolAccountSettings() = Unit
                        },
                        onOpenRoute = {},
                        onOpenSettings = {},
                        onOpenOfficialWeb = {},
                        pinnedServiceIds = entries.map { it.entryId.value }.toSet(),
                    )
                }
            }
        }

        val scrollNode = rule.onNode(hasScrollAction())
        fun scrollValue(): Float = scrollNode.fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

        assertEquals(0f, scrollValue(), 0.5f)

        scrollNode.performTouchInput {
            swipeUp(startY = bottom - 8f, endY = top + 8f, durationMillis = 120)
        }
        rule.waitForIdle()
        assertTrue("普通上滑应能滚动首页", scrollValue() > 0f)

        scrollNode.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -2_000f) }
        rule.waitForIdle()
        assertEquals(0f, scrollValue(), 0.5f)

        scrollNode.performTouchInput {
            swipeDown(startY = top + 8f, endY = bottom - 8f, durationMillis = 40)
        }
        scrollNode.performTouchInput {
            swipeUp(startY = bottom - 8f, endY = top + 8f, durationMillis = 120)
        }
        rule.waitForIdle()
        assertTrue("顶部快速下拉后反向上滑应恢复滚动", scrollValue() > 0f)
    }
}
