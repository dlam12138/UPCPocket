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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomCampusStore
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
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

    private class RecordingRepository : FreeClassroomRepository {
        val queries = mutableListOf<FreeClassroomQuery>()

        override suspend fun query(query: FreeClassroomQuery): FreeClassroomResult {
            queries += query
            return FreeClassroomResult(
                query = query,
                buildings = listOf("全部"),
                rooms = emptyList(),
                fetchedAt = Instant.now(),
            )
        }
    }
}
