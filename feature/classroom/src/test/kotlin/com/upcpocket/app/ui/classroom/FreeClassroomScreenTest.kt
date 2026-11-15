package com.upcpocket.app.ui.classroom

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
import com.upcpocket.app.data.classroom.ClassroomQueryMode
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomCampusStore
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.FreeClassroomRoom
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class FreeClassroomScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `wing selection filters cells and counts locally and restores all on campus change`() {
        val repository = RecordingRepository(withRooms = true)
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))
        rule.waitUntil(5_000) { repository.queries.size == 1 }
        waitForCurrentResult()
        openFilters()
        assertSheetTagSelected("classroom_wing_ALL")
        clickSheetTag("classroom_wing_A")
        assertVisibleSheetTagSelected("classroom_wing_A")
        clickSheetText("应用")
        rule.waitForIdle()
        scrollToText("共 2 间")
        rule.onNodeWithText("空闲 1").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("卓越A233，卓越教学楼，空闲"))
        rule.onNodeWithContentDescription("卓越A233，卓越教学楼，空闲").assertIsDisplayed()
        scrollToText("仅看空闲")
        rule.onNodeWithContentDescription("卓越B328，卓越教学楼，占用").assertDoesNotExist()
        rule.onNodeWithText("仅看空闲").performClick()
        scrollToText("空闲 1 / 共 2")
        rule.onNodeWithContentDescription("卓越A234，卓越教学楼，占用").assertDoesNotExist()
        assertEquals(1, repository.queries.size)

        applyWingFilter("B")
        scrollToText("共 1 间")
        rule.onNodeWithText("空闲 0").assertIsDisplayed()
        scrollToText("没有空闲教室。关闭“仅看空闲”查看占用。")
        applyWingFilter("ALL")
        scrollToText("共 3 间")
        assertEquals(1, repository.queries.size)

        applyWingFilter("A")
        rule.onNodeWithContentDescription("后一天").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 2 }
        openFilters()
        assertSheetTagSelected("classroom_wing_A")
        clickSheetText("取消")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("唐岛湾校区").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 3 }
        openFilters()
        rule.onNodeWithTag("classroom_wing_ALL").assertDoesNotExist()
        clickSheetText("取消")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("古镇口校区").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 4 }
        openFilters()
        assertSheetTagSelected("classroom_wing_ALL")
        clickSheetText("取消")
    }

    @Test
    fun `empty wing is distinct from an empty school directory`() {
        val repository = FreeClassroomRepository { query ->
            FreeClassroomResult(
                query = query,
                buildings = listOf("全部", "卓越教学楼"),
                rooms = listOf(FreeClassroomRoom("opaque-1", "卓越A233", "卓越教学楼", query.campus.label, 60, true)),
                fetchedAt = Instant.now(),
            )
        }
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))
        waitForCurrentResult()
        applyWingFilter("B")
        scrollToText("没有符合分类的教室。")
        rule.onNodeWithText("共 0 间").assertIsDisplayed()
        rule.onNodeWithText("学校未返回教室目录，无法判断空闲情况。").assertDoesNotExist()
        rule.onNodeWithText("所选节次无空闲，以下房间均为占用。").assertDoesNotExist()
    }

    @Test
    fun `wing selection survives saved state recreation`() {
        val repository = RecordingRepository(withRooms = true)
        val store = FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU)
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme { FreeClassroomScreen(onBack = {}, repository = repository, campusStore = store) }
        }
        rule.waitUntil(5_000) { repository.queries.size == 1 }
        waitForCurrentResult()
        applyWingFilter("B")
        openFilters()
        assertSheetTagSelected("classroom_wing_B")
        clickSheetText("取消")
        restoration.emulateSavedInstanceStateRestore()
        openFilters()
        assertSheetTagSelected("classroom_wing_B")
    }

    @Test
    fun `filter draft cancellation changes neither conditions nor requests`() {
        val repository = RecordingRepository()
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))
        rule.waitUntil(5_000) { repository.queries.size == 1 }
        waitForCurrentResult()

        openFilters()
        clickSheetTag("classroom_period_2")
        clickSheetTag("classroom_wing_A")
        clickSheetText("取消")
        rule.waitForIdle()

        assertEquals(1, repository.queries.size)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, repository.queries.single().periods)
        rule.onNodeWithContentDescription("后一天").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 2 }
        assertEquals(repository.queries.first().date.plusDays(1), repository.queries.last().date)
        openFilters()
        assertSheetTagSelected("classroom_period_2")
        assertSheetTagSelected("classroom_wing_ALL")
        clickSheetText("取消")
    }

    @Test
    fun `explicitly selecting current preset keeps manual mode after apply`() {
        val repository = RecordingRepository()
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))
        rule.waitUntil(5_000) { repository.queries.size == 1 }
        waitForCurrentResult()

        openFilters()
        clickSheetTag("classroom_period_6")
        assertVisibleSheetTagSelected("classroom_period_6")
        clickSheetTag("classroom_period_6")
        assertSheetTagSelected("classroom_period_1")
        clickSheetText("应用")
        rule.waitForIdle()
        lateinit var model: FreeClassroomViewModel
        rule.runOnIdle {
            model = ViewModelProvider(rule.activity).get(FreeClassroomViewModel::class.java)
        }
        assertEquals(ClassroomQueryMode.CUSTOM, model.mode)
        assertEquals(1, repository.queries.size)

        rule.onNodeWithContentDescription("后一天").performClick()
        rule.waitForIdle()
        assertEquals(1, repository.queries.size)

        scrollToQueryActions()
        rule.onNodeWithText("查询").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 2 }
        assertEquals(ClassroomPeriodGroup.MORNING.periods, repository.queries.last().periods)
    }

    @Test
    fun `building draft waits for explicit query and query all sends one request`() {
        val repository = RecordingRepository(withRooms = true)
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))
        rule.waitUntil(5_000) { repository.queries.size == 1 }
        waitForCurrentResult()

        openFilters()
        clickSheetTag("classroom_building_picker")
        rule.onNodeWithText("选择教学楼").assertIsDisplayed()
        rule.onNode(
            hasText("卓越教学楼") and hasAnyAncestor(isDialog()),
        ).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        clickSheetText("应用")
        rule.waitForIdle()
        assertEquals(1, repository.queries.size)

        scrollToQueryActions()
        rule.onNodeWithText("查询").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 2 }
        assertEquals("卓越教学楼", repository.queries.last().building)

        openFilters()
        clickSheetTag("classroom_building_picker")
        rule.onNodeWithTag("classroom_query_all_buildings")
            .assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { repository.queries.size == 3 }
        assertEquals("全部", repository.queries.last().building)
    }

    @Test
    fun `same key refresh failure keeps old room result visible`() {
        var calls = 0
        val repository = FreeClassroomRepository { query ->
            calls++
            if (calls == 2) throw java.io.IOException("synthetic raw response detail")
            FreeClassroomResult(
                query = query,
                buildings = listOf("全部", "卓越教学楼"),
                rooms = listOf(FreeClassroomRoom("synthetic-room", "卓越A233", "卓越教学楼", query.campus.label, 36, true)),
                fetchedAt = Instant.parse("2026-11-15T02:00:00Z"),
            )
        }
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))
        rule.waitUntil(5_000) { calls == 1 }
        waitForCurrentResult()
        scrollToQueryActions()
        rule.onNodeWithText("刷新").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("更新失败 · 已保留上次结果").fetchSemanticsNodes().isNotEmpty()
        }
        scrollToText("学校结果暂不可确认，请稍后重试")
        scrollToText("共 1 间")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("卓越A233，卓越教学楼，空闲"))
        rule.onNodeWithContentDescription("卓越A233，卓越教学楼，空闲").assertIsDisplayed()
        assertEquals(2, calls)
    }

    @Test
    fun `room detail reflects availability after same key refresh when only free is off`() {
        var calls = 0
        val repository = FreeClassroomRepository { query ->
            calls++
            FreeClassroomResult(
                query = query,
                buildings = listOf("全部", "卓越教学楼"),
                rooms = listOf(
                    FreeClassroomRoom(
                        "synthetic-room",
                        "卓越A233",
                        "卓越教学楼",
                        query.campus.label,
                        36,
                        available = calls == 1,
                    ),
                ),
                fetchedAt = Instant.parse(if (calls == 1) "2026-11-15T02:00:00Z" else "2026-11-15T02:01:00Z"),
            )
        }
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))
        rule.waitUntil(5_000) { calls == 1 }
        waitForCurrentResult()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("卓越A233，卓越教学楼，空闲"))
        rule.onNodeWithContentDescription("卓越A233，卓越教学楼，空闲").performClick()
        rule.onNodeWithTag("classroom_detail_status").assertTextEquals("空闲")

        lateinit var model: FreeClassroomViewModel
        rule.runOnIdle {
            model = ViewModelProvider(rule.activity).get(FreeClassroomViewModel::class.java)
            model.search()
        }
        rule.waitUntil(5_000) {
            calls == 2 && rule.onAllNodesWithText("占用").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("classroom_detail_status").assertTextEquals("占用")
        rule.onNode(isDialog() and hasAnyDescendant(hasText("卓越A233"))).assertIsDisplayed()
    }

    @Test
    fun `large font keeps room grid content reachable`() {
        val repository = RecordingRepository(withRooms = true)
        val store = FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    FreeClassroomScreen(
                        onBack = {},
                        repository = repository,
                        campusStore = store,
                    )
                }
            }
        }
        rule.waitUntil(5_000) { repository.queries.size == 1 }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("卓越A233，卓越教学楼，空闲"))
        rule.onNodeWithContentDescription("卓越A233，卓越教学楼，空闲").assertIsDisplayed()
    }

    @Test
    fun `first entry queries once and has no today shortcut`() {
        val repository = RecordingRepository()
        setContent(repository, FakeCampusStore())

        rule.waitUntil(5_000) { repository.queries.size == 1 }
        rule.onNodeWithText("今天").assertDoesNotExist()
        rule.onNodeWithContentDescription("前一天").assertIsDisplayed()
        rule.onNodeWithContentDescription("后一天").assertIsDisplayed()

        rule.onNodeWithTag("classroom_date").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()
        assertEquals(1, repository.queries.size)
    }

    @Test
    fun `saved campus is used for the single initial query`() {
        val repository = RecordingRepository()
        setContent(repository, FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU))

        rule.waitUntil(5_000) { repository.queries.size == 1 }
        assertEquals(FreeClassroomCampus.GUZHENKOU, repository.queries.single().campus)
        rule.onNodeWithText("古镇口校区").assertIsSelected()
        assertEquals(ClassroomPeriodGroup.MORNING.periods, repository.queries.single().periods)
    }

    @Test
    fun `preset campus change is automatic while custom periods wait for query`() {
        val repository = RecordingRepository()
        val store = FakeCampusStore()
        setContent(repository, store)
        rule.waitUntil(5_000) { repository.queries.size == 1 }
        waitForCurrentResult()

        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("古镇口校区").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 2 }
        waitForCurrentResult()
        assertEquals(FreeClassroomCampus.GUZHENKOU, repository.queries[1].campus)
        assertEquals(FreeClassroomCampus.GUZHENKOU, store.saved)

        openFilters()
        clickSheetText("清空")
        clickSheetTag("classroom_period_2")
        assertVisibleSheetTagSelected("classroom_period_2")
        clickSheetText("应用")
        rule.waitForIdle()
        lateinit var model: FreeClassroomViewModel
        rule.runOnIdle {
            model = ViewModelProvider(rule.activity).get(FreeClassroomViewModel::class.java)
        }
        assertEquals(ClassroomQueryMode.CUSTOM, model.mode)
        assertEquals(2, repository.queries.size)

        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("唐岛湾校区")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        rule.waitForIdle()
        assertEquals(2, repository.queries.size)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, store.saved)

        scrollToQueryActions()
        rule.onNodeWithText("查询").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 3 }
        assertEquals(setOf(2), repository.queries.last().periods)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, repository.queries.last().campus)

        // 切回预设后恢复自动查询。
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("上午").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 4 }
        assertEquals(ClassroomPeriodGroup.MORNING.periods, repository.queries.last().periods)
    }

    private fun scrollToText(text: String) {
        val index = when {
            text == "结果已就绪" || text == "更新失败 · 已保留上次结果" || text == "学校结果暂不可确认，请稍后重试" -> 1
            text.contains(" / 共 ") -> 3
            text.startsWith("空闲 ") || text.startsWith("共 ") || text == "仅看空闲" -> 2
            text == "没有空闲教室。关闭“仅看空闲”查看占用。" -> 3
            else -> 3
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun openFilters() {
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("筛选").performScrollTo().assertIsDisplayed().performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("详细筛选").assertIsDisplayed()
        expandFilterSheetIfAvailable()
    }

    private fun expandFilterSheetIfAvailable() {
        val expand = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        val expandNodes = rule.onAllNodes(expand, useUnmergedTree = true).fetchSemanticsNodes()
        check(expandNodes.size <= 1) { "Expected at most one filter sheet expand action" }
        if (expandNodes.isNotEmpty()) {
            rule.onNode(expand, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.Expand)
            rule.waitForIdle()
        }
    }

    private fun waitForCurrentResult() {
        scrollToQueryActions()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("结果已就绪").fetchSemanticsNodes().isNotEmpty()
        }
        scrollToText("结果已就绪")
    }

    private fun scrollToQueryActions() {
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
    }

    private fun applyWingFilter(filter: String) {
        openFilters()
        clickSheetTag("classroom_wing_$filter")
        clickSheetText("应用")
        rule.waitForIdle()
    }

    private fun clickSheetTag(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun assertSheetTagSelected(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsSelected()
    }

    private fun assertVisibleSheetTagSelected(tag: String) {
        rule.onNodeWithTag(tag).assertIsDisplayed().assertIsSelected()
    }

    private fun clickSheetText(text: String) {
        rule.onNodeWithText(text).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun setContent(repository: FreeClassroomRepository, store: FreeClassroomCampusStore) {
        rule.setContent {
            MaterialTheme {
                FreeClassroomScreen(
                    onBack = {},
                    repository = repository,
                    campusStore = store,
                )
            }
        }
    }

    private class FakeCampusStore(
        var saved: FreeClassroomCampus? = null,
    ) : FreeClassroomCampusStore {
        override suspend fun read(): FreeClassroomCampus? = saved

        override suspend fun write(campus: FreeClassroomCampus) {
            saved = campus
        }
    }

    private class RecordingRepository(private val withRooms: Boolean = false) : FreeClassroomRepository {
        val queries = mutableListOf<FreeClassroomQuery>()

        override suspend fun query(query: FreeClassroomQuery): FreeClassroomResult {
            queries += query
            val allRooms = if (withRooms) listOf(
                FreeClassroomRoom("opaque-1", "卓越A233", "卓越教学楼", query.campus.label, 60, true),
                FreeClassroomRoom("opaque-2", "卓越A234", "卓越教学楼", query.campus.label, 60, false),
                FreeClassroomRoom("opaque-3", "卓越B328", "卓越教学楼", query.campus.label, 60, false),
            ) else emptyList()
            return FreeClassroomResult(
                query = query,
                buildings = if (withRooms) listOf("全部", "卓越教学楼") else listOf("全部"),
                rooms = allRooms.filter { query.building == "全部" || it.building == query.building },
                fetchedAt = Instant.now(),
            )
        }
    }
}
