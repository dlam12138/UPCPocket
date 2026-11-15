package com.upcpocket.app.ui.classroom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.FreeClassroomRoom
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.io.IOException
import java.time.Instant

/**
 * Debug-only visual fixture. Pass `scenario=normal|empty|failed`, optionally `largeFont=true` or `dark=true`.
 * Every response is synthetic; this Activity has no account, cookie, network, or school data path.
 */
class ClassroomPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario").orEmpty()
        val largeFont = intent.getBooleanExtra("largeFont", false)
        val dark = intent.getBooleanExtra("dark", false)
        val promo = intent.getBooleanExtra("promo", false)
        if (promo) enableEdgeToEdge()
        var calls = 0
        val repository = FreeClassroomRepository { query ->
            calls++
            if (scenario == "failed" && calls > 1) {
                throw IOException("学校服务暂时不可用，请稍后重试")
            }
            previewResult(query, empty = scenario == "empty", promo = promo)
        }
        setContent {
            val baseDensity = LocalDensity.current
            val previewDensity = if (largeFont) Density(baseDensity.density, 1.5f) else baseDensity
            CompositionLocalProvider(LocalDensity provides previewDensity) {
                UpcPocketTheme(
                    darkMode = if (dark) DarkMode.DARK else DarkMode.LIGHT,
                    preset = ThemePreset.BLUE,
                ) {
                    FreeClassroomScreen(onBack = ::finish, repository = repository)
                }
            }
        }
    }
}

private fun previewResult(query: FreeClassroomQuery, empty: Boolean, promo: Boolean): FreeClassroomResult {
    val allRooms = if (empty) emptyList() else if (promo) listOf(
        room("promotion-101", "南教 101", "南教", query, available = true),
        room("promotion-102", "南教 102", "南教", query, available = true),
        room("promotion-103", "南教 103", "南教", query, available = false),
        room("promotion-104", "南教 104", "南教", query, available = true),
        room("promotion-201", "东教 201", "东教", query, available = true),
        room("promotion-202", "东教 202", "东教", query, available = false),
        room("promotion-203", "东教 203", "东教", query, available = true),
        room("promotion-204", "东教 204", "东教", query, available = true),
    ) else when (query.campus) {
        FreeClassroomCampus.TANGDAOWAN -> listOf(
            room("fixture-tw-101", "合成教室 101", "合成教学楼 A", query, available = true),
            room("fixture-tw-102", "合成教室 102", "合成教学楼 A", query, available = false),
            room("fixture-tw-201", "合成教室 201", "合成教学楼 B", query, available = true),
        )
        FreeClassroomCampus.GUZHENKOU -> listOf(
            room("fixture-gz-a000", "卓越 A000", "卓越教学楼", query, available = true),
            room("fixture-gz-a001", "卓越 A001", "卓越教学楼", query, available = false),
            room("fixture-gz-b000", "卓越 B000", "卓越教学楼", query, available = false),
            room("fixture-gz-unknown", "待分类 000", "实验教学楼", query, available = false),
        )
    }
    val rooms = if (query.building == "全部") allRooms else allRooms.filter { it.building == query.building }
    val buildings = if (promo) listOf("全部", "南教", "东教") else listOf("全部", "合成教学楼 A", "合成教学楼 B", "卓越教学楼", "实验教学楼")
    return FreeClassroomResult(query, buildings, rooms, if (promo) Instant.now() else Instant.parse("2026-11-15T02:00:00Z"))
}

private fun room(
    code: String,
    name: String,
    building: String,
    query: FreeClassroomQuery,
    available: Boolean,
) = FreeClassroomRoom(
    code = code,
    name = name,
    building = building,
    campus = query.campus.label,
    capacity = 36,
    available = available,
)
