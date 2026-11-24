package com.upcpocket.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalUiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReorderableModuleColumnTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun normalizeAndMergePreserveUnknownAndHiddenModules() {
        val defaults = listOf("A", "B", "C", "D")
        assertEquals(
            listOf("C", "A", "B", "D"),
            normalizeModuleOrder(listOf("C", "X", "A", "C"), defaults),
        )
        assertEquals(
            listOf("D", "B", "C", "A"),
            mergeReorderedSubset(
                fullOrder = listOf("A", "B", "C", "D"),
                reorderedVisible = listOf("D", "C", "A"),
            ),
        )
    }

    @Test
    fun longPressAndDragReordersModulesWithoutTriggeringChildClick() {
        var style by mutableStateOf(UiStyle.MATERIAL3)
        var order by mutableStateOf(listOf("A", "B", "C"))
        var committed: List<String>? = null
        var clicks = 0

        rule.setContent {
            CompositionLocalProvider(LocalUiStyle provides style) {
                MaterialTheme {
                    ReorderableModuleColumn(
                        items = order,
                        key = { it },
                        spacing = 8.dp,
                        enabled = true,
                        itemLabel = { "模块$it" },
                        itemTestTag = { "mod_$it" },
                        itemGrouped = { it == "A" },
                        onReorderFinished = {
                            committed = it
                            order = it
                        },
                    ) { item, _ ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clickable { clicks++ },
                        ) {
                            Text("模块$item")
                        }
                    }
                }
            }
        }

        // 先验证普通点击不受长按拖动监听干扰
        rule.onNodeWithTag("mod_A").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)

        // Material 3 下长按分组模块 A，并向下拖过模块 B 的中线。
        rule.onNodeWithTag("mod_A").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(0f, height * 1.4f), delayMillis = 120L)
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf("B", "A", "C"), committed)
        assertEquals("长按拖动松手不应误触发子项点击", 1, clicks)

        // 切换到轻量玻璃后，未分组模块仍能完成真实拖动入口并持久化。
        style = UiStyle.LIQUID_GLASS
        rule.waitForIdle()
        committed = null
        rule.onNodeWithTag("mod_B").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(0f, height * 1.4f), delayMillis = 120L)
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf("A", "B", "C"), committed)
        assertEquals("切换材质后拖动仍不应误触发子项点击", 1, clicks)
    }

    @Test
    fun dragVisualSpecSeparatesMaterial3FromLiquidGlass() {
        val scheme = lightColorScheme()
        val m3Spec = resolveReorderDragVisualSpec(UiStyle.MATERIAL3, scheme)
        val glassSpec = resolveReorderDragVisualSpec(UiStyle.LIQUID_GLASS, scheme)

        assertFalse(m3Spec.isGlass)
        assertEquals(1f, m3Spec.layerAlpha, 0.0001f)
        assertEquals(8.dp, m3Spec.maxShadowElevationDp)
        assertEquals(1f, m3Spec.m3TrayColor.alpha, 0.0001f)
        assertEquals(1f, m3Spec.m3CardColor.alpha, 0.0001f)

        assertTrue(glassSpec.isGlass)
        assertEquals(1f, glassSpec.layerAlpha, 0.0001f)
        assertEquals(0.dp, glassSpec.maxShadowElevationDp)
        assertTrue(glassSpec.glassCarrierColor.alpha in 0.2f..0.6f)
    }

    @Test
    fun disabledColumnKeepsOrderAndLeavesClicksAlone() {
        var committed: List<String>? = null
        var clicks = 0

        rule.setContent {
            MaterialTheme {
                ReorderableModuleColumn(
                    items = listOf("A", "B", "C"),
                    key = { it },
                    spacing = 8.dp,
                    enabled = false,
                    itemLabel = { "模块$it" },
                    itemTestTag = { "mod_$it" },
                    onReorderFinished = { committed = it },
                ) { item, _ ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clickable { clicks++ },
                    ) {
                        Text("模块$item")
                    }
                }
            }
        }

        rule.onNodeWithTag("mod_A").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)

        rule.onNodeWithTag("mod_A").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(0f, height * 1.4f), delayMillis = 120L)
            up()
        }
        rule.waitForIdle()

        assertNull("关闭拖动后不应提交排序", committed)
        assertEquals("关闭拖动不影响普通点击", 1, clicks)
        val topA = rule.onNodeWithTag("mod_A").fetchSemanticsNode().boundsInRoot.top
        val topB = rule.onNodeWithTag("mod_B").fetchSemanticsNode().boundsInRoot.top
        assertTrue("关闭拖动后仍按持久顺序渲染", topA < topB)
    }

    @Test
    fun cancelledDragDiscardsTheDraftAndKeepsOrder() {
        var committed: List<String>? = null

        rule.setContent {
            MaterialTheme {
                ReorderableModuleColumn(
                    items = listOf("A", "B", "C"),
                    key = { it },
                    spacing = 8.dp,
                    enabled = true,
                    itemLabel = { "模块$it" },
                    itemTestTag = { "mod_$it" },
                    onReorderFinished = { committed = it },
                ) { item, _ ->
                    Box(Modifier.fillMaxWidth().height(80.dp)) {
                        Text("模块$item")
                    }
                }
            }
        }

        // 长按后拖过 B，再由系统取消（ACTION_CANCEL），不应提交草稿。
        rule.onNodeWithTag("mod_A").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(0f, height * 1.4f), delayMillis = 120L)
            cancel()
        }
        rule.waitForIdle()

        assertNull("系统取消拖动时不应提交草稿", committed)
        val topA = rule.onNodeWithTag("mod_A").fetchSemanticsNode().boundsInRoot.top
        val topB = rule.onNodeWithTag("mod_B").fetchSemanticsNode().boundsInRoot.top
        assertTrue("取消后应回到持久顺序", topA < topB)
    }
}
