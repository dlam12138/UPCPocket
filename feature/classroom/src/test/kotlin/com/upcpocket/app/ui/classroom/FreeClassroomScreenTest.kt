package com.upcpocket.app.ui.classroom

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
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
        rule.onNodeWithTag("classroom_wing_ALL").assertIsSelected()
        rule.onNodeWithTag("classroom_wing_A").performClick()
        scrollToText("全部 2")
        rule.onNodeWithText("空闲 1").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("卓越A233，卓越教学楼，空闲"))
        rule.onNodeWithContentDescription("卓越A233，卓越教学楼，空闲").assertIsDisplayed()
        scrollToText("仅看空闲")
        rule.onNodeWithContentDescription("卓越B328，卓越教学楼，占用").assertDoesNotExist()
        rule.onNodeWithText("仅看空闲").performClick()
        scrollToText("空闲 1 / 共 2")
        rule.onNodeWithContentDescription("卓越A234，卓越教学楼，占用").assertDoesNotExist()
        assertEquals(1, repository.queries.size)

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("classroom_wing_B"))
        rule.onNodeWithTag("classroom_wing_B").performClick()
        scrollToText("全部 1")
        rule.onNodeWithText("空闲 0").assertIsDisplayed()
        scrollToText("没有空闲教室。关闭「仅看空闲」查看占用。")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("classroom_wing_ALL"))
        rule.onNodeWithTag("classroom_wing_ALL").performClick()
        scrollToText("全部 3")
        assertEquals(1, repository.queries.size)

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("classroom_wing_A"))
        rule.onNodeWithTag("classroom_wing_A").performClick()
        rule.onNodeWithContentDescription("后一天").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 2 }
        rule.onNodeWithTag("classroom_wing_A").assertIsSelected()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("唐岛湾校区").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 3 }
        rule.onNodeWithTag("classroom_wing_A").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("古镇口校区").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 4 }
        rule.onNodeWithTag("classroom_wing_ALL").assertIsSelected()
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
        rule.onNodeWithTag("classroom_wing_B").performClick()
        scrollToText("暂无卓越 B教室。")
        rule.onNodeWithText("全部 0").assertIsDisplayed()
        rule.onNodeWithText("未返回教室目录，不代表全部空闲。请调整日期或教学楼。").assertDoesNotExist()
        rule.onNodeWithText("所选节次无空闲教室，以下为占用。").assertDoesNotExist()
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
        rule.onNodeWithTag("classroom_wing_B").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("classroom_wing_B").assertIsSelected()
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

        rule.onNodeWithText("古镇口校区").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 2 }
        assertEquals(FreeClassroomCampus.GUZHENKOU, repository.queries[1].campus)
        assertEquals(FreeClassroomCampus.GUZHENKOU, store.saved)

        rule.onNodeWithText("自选节次").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("清空").performClick()
        rule.onNodeWithText("第 2 节").performClick()
        rule.onNodeWithText("应用").performClick()
        rule.waitForIdle()
        assertEquals(2, repository.queries.size)

        rule.onNodeWithText("唐岛湾校区").performClick()
        rule.waitForIdle()
        assertEquals(2, repository.queries.size)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, store.saved)

        rule.onNodeWithText("查询").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 3 }
        assertEquals(setOf(2), repository.queries.last().periods)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, repository.queries.last().campus)

        // 切回预设后恢复自动查询。
        rule.onNodeWithText("上午").performClick()
        rule.waitUntil(5_000) { repository.queries.size == 4 }
        assertEquals(ClassroomPeriodGroup.MORNING.periods, repository.queries.last().periods)
    }

    private fun scrollToText(text: String) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).assertIsDisplayed()
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
            return FreeClassroomResult(
                query = query,
                buildings = listOf("全部"),
                rooms = if (withRooms) listOf(
                    FreeClassroomRoom("opaque-1", "卓越A233", "卓越教学楼", query.campus.label, 60, true),
                    FreeClassroomRoom("opaque-2", "卓越A234", "卓越教学楼", query.campus.label, 60, false),
                    FreeClassroomRoom("opaque-3", "卓越B328", "卓越教学楼", query.campus.label, 60, false),
                ) else emptyList(),
                fetchedAt = Instant.now(),
            )
        }
    }
}
