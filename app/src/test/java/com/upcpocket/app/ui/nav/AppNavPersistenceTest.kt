package com.upcpocket.app.ui.nav

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 验证 [MainNavHostContainer] 在进入与退出二级页时的常驻行为：
 * - 一级页不被销毁或重建，内部状态完整保留；
 * - 一级页的 [LocalDockClearance] 不因进入二级页或二级页弹出输入法而跳变，二级页保持 0.dp；
 * - [LocalPluginPageActive] 在进入二级页时关闭，返回一级页后恢复；
 * - 二级页展示期间底层语义被清除，且底层按钮不会被透传点击。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class AppNavPersistenceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `secondary navigation keeps main layer mounted isolates dock clearance and blocks pass through`() {
        lateinit var navController: NavHostController
        val imeVisible = mutableStateOf(false)
        var mainMountCount = 0
        var mainDisposeCount = 0
        val mainClearanceHistory = mutableListOf<Dp>()
        var latestMainActive: Boolean? = null
        var latestSecondaryClearance: Dp? = null
        var hiddenButtonClicks = 0

        rule.setContent {
            UpcPocketTheme {
                navController = rememberNavController()
                val backStackEntryState = navController.currentBackStackEntryAsState()
                val onMainState = remember(backStackEntryState) {
                    derivedStateOf {
                        val route = backStackEntryState.value?.destination?.route
                        route == null || route == MAIN_ROUTE
                    }
                }
                MainNavHostContainer(
                    navController = navController,
                    onMainState = onMainState,
                    imeVisibleState = imeVisible,
                    rootContainerColor = Color.White,
                    mainContent = { mainActiveState, _ ->
                        var counter by remember { mutableIntStateOf(0) }
                        val clearance = LocalDockClearance.current
                        if (mainClearanceHistory.lastOrNull() != clearance) {
                            mainClearanceHistory += clearance
                        }
                        DisposableEffect(Unit) {
                            mainMountCount += 1
                            onDispose { mainDisposeCount += 1 }
                        }
                        CompositionLocalProvider(
                            LocalPluginPageActive provides mainActiveState.value,
                        ) {
                            latestMainActive = LocalPluginPageActive.current
                            Box(Modifier.fillMaxSize()) {
                                Button(
                                    onClick = {
                                        counter += 1
                                        hiddenButtonClicks += 1
                                    },
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .testTag("main_counter_button"),
                                ) {
                                    Text("count=$counter")
                                }
                            }
                        }
                    },
                    secondaryRoutes = {
                        composable("secondary") {
                            latestSecondaryClearance = LocalDockClearance.current
                            Box(Modifier.fillMaxSize().testTag("secondary_surface")) {
                                Text(
                                    text = "secondary_screen",
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .clickable {}
                                        .testTag("secondary_title"),
                                )
                            }
                        }
                    },
                )
            }
        }

        rule.waitForIdle()
        assertEquals(1, mainMountCount)
        assertEquals(0, mainDisposeCount)
        assertEquals(true, latestMainActive)
        assertEquals(listOf(DOCK_CLEARANCE), mainClearanceHistory)

        rule.onNodeWithTag("main_counter_button").performClick()
        rule.waitForIdle()
        assertEquals(1, hiddenButtonClicks)

        // 进入二级页
        rule.runOnUiThread { navController.navigate("secondary") }
        rule.waitForIdle()

        rule.onNodeWithTag("secondary_title").assertIsDisplayed()
        rule.onNodeWithTag("main_counter_button").assertDoesNotExist()
        assertEquals(1, mainMountCount)
        assertEquals(0, mainDisposeCount)
        assertEquals(false, latestMainActive)
        assertEquals(0f, requireNotNull(latestSecondaryClearance).value, 0f)
        assertEquals(listOf(DOCK_CLEARANCE), mainClearanceHistory)

        // 二级页弹出输入法不应扰动隐藏一级页的 LocalDockClearance
        imeVisible.value = true
        rule.waitForIdle()
        assertEquals(listOf(DOCK_CLEARANCE), mainClearanceHistory)

        // 点击二级页空白中心（正对底层 main_counter_button 位置）不应穿透触发底层按钮
        rule.onNodeWithTag("secondary_surface").performClick()
        rule.waitForIdle()
        assertEquals(1, hiddenButtonClicks)

        // 返回一级页：不应重建，原计数与活动态恢复
        imeVisible.value = false
        rule.runOnUiThread { navController.popBackStack() }
        rule.waitForIdle()

        assertEquals(1, mainMountCount)
        assertEquals(0, mainDisposeCount)
        assertEquals(true, latestMainActive)
        assertEquals(listOf(DOCK_CLEARANCE), mainClearanceHistory)
        rule.onNodeWithTag("main_counter_button").assertIsDisplayed()
        rule.onNodeWithTag("main_counter_button").performClick()
        rule.waitForIdle()
        assertEquals(2, hiddenButtonClicks)
        assertFalse(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("secondary_title")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(mainDisposeCount == 0)
    }
}
