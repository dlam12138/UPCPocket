package com.upcpocket.app.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.prefs.HomeAnniversaryMode
import com.upcpocket.app.data.prefs.HomeAnniversarySettings
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class HomeAnniversaryHomeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun content(homeAnniversary: HomeAnniversarySettings) {
        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    HomeScreen(
                        serviceEntries = emptyList(),
                        timetableSections = emptyList(),
                        cardSections = emptyList(),
                        todoSections = emptyList(),
                        sectionHost = object : PluginSectionHost {
                            override fun openSchoolAccountSettings() = Unit
                        },
                        onOpenRoute = {},
                        onOpenSettings = {},
                        onOpenOfficialWeb = {},
                        pinnedServiceIds = emptySet(),
                        homeAnniversary = homeAnniversary,
                    )
                }
            }
        }
    }

    @Test
    fun hiddenAnniversaryAddsNoHomeRow() {
        content(
            HomeAnniversarySettings(
                title = "入学纪念日",
                date = LocalDate.of(2026, 11, 24),
                mode = HomeAnniversaryMode.COUNT_UP,
                visible = false,
            ),
        )

        rule.onNodeWithTag("home_anniversary").assertDoesNotExist()
        rule.onNodeWithText("入学纪念日").assertDoesNotExist()
    }

    @Test
    fun visibleAnniversaryShowsTitleBelowTheDate() {
        content(
            HomeAnniversarySettings(
                title = "入学纪念日",
                date = LocalDate.of(2026, 11, 24),
                mode = HomeAnniversaryMode.COUNT_UP,
                visible = true,
            ),
        )

        rule.onNodeWithTag("home_anniversary").assertExists()
        rule.onNodeWithText("入学纪念日").assertExists()
    }
}
