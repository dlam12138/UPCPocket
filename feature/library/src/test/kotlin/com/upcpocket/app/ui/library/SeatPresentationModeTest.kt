package com.upcpocket.app.ui.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatRepositoryOps
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatPlace
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import com.upcpocket.app.data.prefs.NoOpLibraryHiddenSeatsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", application = Application::class)
class SeatPresentationModeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `presentation switch changes only the local view and preserves selected seat and time`() {
        val repository = LibrarySeatRepository()
        var startTimesLoads = 0
        val start = LibraryTimeOption("start-0800", "08:00")
        val end = LibraryTimeOption("end-1000", "10:00")
        val seat = LibrarySeat(
            id = "seat-006",
            name = "A-001-006",
            row = 3,
            col = 6,
            type = "seat",
            enabled = true,
            status = "FREE",
            localOnly = false,
        )
        val viewModel = LibrarySeatViewModel(
            repo = repository,
            hiddenSeatsStore = NoOpLibraryHiddenSeatsStore,
            ops = LibrarySeatRepositoryOps(repository),
            startTimesLoader = { _, _ ->
                startTimesLoads++
                listOf(start)
            },
            initialState = LibrarySeatViewModel.UiState(
                building = LibraryBuilding("building-1", "东馆"),
                date = "2026-11-17",
                room = LibraryRoom("room-1", "临窗学习区", 1, "1 楼"),
                layout = LibrarySeatLayout(rows = 8, cols = 10, seats = listOf(seat)),
                seat = seat,
                startTimes = listOf(start),
                endTimes = listOf(end),
                start = start,
                end = end,
                hiddenLoaded = true,
            ),
        )
        var fontScale by mutableStateOf(1f)

        rule.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(360.dp).height(800.dp)) {
                        LibrarySeatDetailPage(state = viewModel.state, vm = viewModel, onExit = {})
                    }
                }
            }
        }

        rule.onNodeWithTag("library_seat_list_${seat.id}")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        listOf(1f, 1.3f).forEach { scale ->
            fontScale = scale
            rule.waitForIdle()
            assertSeatLabelFullyVisible("library_seat_name_${seat.id}", seat.name)
        }
        rule.onNodeWithText(seat.name, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("seat_presentation_map").performScrollTo().performClick()
        rule.onNodeWithTag("seat_map_viewport").assertIsDisplayed()
        assertEquals(seat.id, viewModel.state.seat?.id)
        assertEquals(start, viewModel.state.start)
        assertEquals(end, viewModel.state.end)
        rule.onNodeWithTag("seat_presentation_list").performScrollTo().performClick()
        rule.onNodeWithTag("library_seat_list_${seat.id}").performScrollTo().assertIsDisplayed()
        assertEquals(seat.id, viewModel.state.seat?.id)
        assertEquals(start, viewModel.state.start)
        assertEquals(end, viewModel.state.end)

        assertEquals(0, startTimesLoads)
    }

    @Test
    fun `chaoxing seat numbers and whole tile actions stay readable at phone width for both halls`() {
        val seatNum = "Q-001-023"
        val syntheticDetail = ChaoxingSeatRoomDetail(
            roomId = 101,
            name = "合成阅览室",
            capacity = 1,
            timeUnit = 30,
            startMinute = 8 * 60,
            endMinute = 21 * 60,
            places = listOf(ChaoxingSeatPlace(seatNum, 0f, 0f, 40f, 24f, 0f)),
            seatRulesKnown = true,
            minDurationMinutes = 30,
            maxDurationMinutes = 6 * 60,
        )
        var state by mutableStateOf(
            ChaoxingSeatUiState(
                kind = ChaoxingSeatKind.SEAT,
                dates = listOf("2026-11-17"),
                selectedDate = "2026-11-17",
                detail = syntheticDetail,
                usedSeatsLoaded = true,
                hiddenLoaded = true,
            ),
        )
        var selections = 0
        var hides = 0
        var fontScale by mutableStateOf(1f)

        rule.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(360.dp).height(800.dp)) {
                        ChaoxingSeatDetailContent(
                            state = state,
                            onExit = {},
                            onOpenOfficial = {},
                            onMode = {},
                            onStart = {},
                            onEnd = {},
                            onSearch = {},
                            onSelect = { selections++ },
                            onHide = { hides++ },
                            onOpenHidden = {},
                            onCloseHidden = {},
                            onRestore = {},
                            onRestoreAll = {},
                            onReserve = {},
                            onClearHiddenNotice = {},
                            onReloadHidden = {},
                        )
                    }
                }
            }
        }

        listOf(ChaoxingSeatKind.SEAT, ChaoxingSeatKind.SEATENGINE).forEachIndexed { index, kind ->
            state = state.copy(kind = kind)
            listOf(1f, 1.3f).forEach { scale ->
                fontScale = scale
                rule.waitForIdle()
                assertSeatLabelFullyVisible("chaoxing_seat_name_$seatNum", seatNum)
            }
            rule.onNodeWithTag("chaoxing_seat_tile_$seatNum")
                .assertHasClickAction()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
                .assertContentDescriptionEquals("座位 $seatNum，可选")
                .performClick()
            assertEquals(index + 1, selections)

            rule.onNodeWithContentDescription("隐藏座位 $seatNum").performClick()
            assertEquals("the menu action must not select the tile", index + 1, selections)
            rule.onNodeWithText("隐藏此座位").assertIsDisplayed().performClick()
            assertEquals(index + 1, hides)
        }
    }

    private fun assertSeatLabelFullyVisible(tag: String, label: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(120.dp)

        val layout = textLayout(tag)
        assertEquals("TextLayoutResult must retain the source seat label", label, layout.layoutInput.text.text)
        assertFalse("Seat label must not overflow or be ellipsized: $label", layout.hasVisualOverflow)
        assertEquals(
            "The final character must be part of the visible line: $label",
            label.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true),
        )
        val finalCharacter = layout.getBoundingBox(label.lastIndex)
        assertTrue("The final character must have visible width: $label", finalCharacter.width > 0f)
        assertTrue(
            "The final character must fit in the text width: $label",
            finalCharacter.right <= layout.size.width.toFloat(),
        )
        assertTrue(
            "The final character must fit in the text height: $label",
            finalCharacter.bottom <= layout.size.height.toFloat(),
        )
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResult ->
                assertTrue("TextLayoutResult should be available for $tag", getResult(results))
            }
        return results.single()
    }

    @Test
    fun `presentation mode survives saved instance restoration`() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                SeatPresentationRestorationContent(system = "libseat", area = "building-1", room = "room-1")
            }
        }

        rule.onNodeWithTag("seat_presentation_map").performClick()
        rule.onNodeWithText("布局图视图").assertIsDisplayed()
        rule.onNodeWithTag("test_viewport_zoom_in").performClick()
        rule.onNodeWithText("视口倍率 2.0").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("布局图视图").assertIsDisplayed()
        rule.onNodeWithText("视口倍率 2.0").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun SeatPresentationRestorationContent(system: String, area: String, room: String) {
        var showMap by rememberSeatPresentationMapState(system, area, room)
        val viewport = rememberSeatMapViewportState(system, area, room)
        Column {
            SeatPresentationModeControl(showMap) { showMap = it }
            Text(if (showMap) "布局图视图" else "座位列表视图")
            Button(
                onClick = { viewport.zoomMultiplier.value += 1f },
                modifier = Modifier.testTag("test_viewport_zoom_in"),
            ) { Text("放大视口") }
            Text("视口倍率 ${viewport.zoomMultiplier.value}")
        }
    }
}
