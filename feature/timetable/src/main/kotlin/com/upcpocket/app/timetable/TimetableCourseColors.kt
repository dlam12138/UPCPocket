package com.upcpocket.app.timetable

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.security.MessageDigest

internal data class ToneColors(val container: Color, val content: Color)

/**
 * 16 个固定色相（约 22.5° 间隔）。课程颜色只由稳定课程身份决定，不随日期/节次/
 * 周次变化；4 色轮转让同一周大量课程撞色，16 色 + SHA-256 显著降低碰撞。
 * 色相只是底色，文字始终保留，深浅主题分别使用独立容器/前景色。
 */
private val COURSE_HUES = floatArrayOf(
    6f, 28.5f, 51f, 73.5f, 96f, 118.5f, 141f, 163.5f,
    186f, 208.5f, 231f, 253.5f, 276f, 298.5f, 321f, 343.5f,
)

/**
 * 课程身份优先使用学校课程号；缺课程号时才退回课程名。教师/地点/节次不参与，
 * 因此同一门课跨日、跨周颜色一致。
 */
internal fun courseColorKey(meeting: TimetableMeeting): String =
    meeting.courseId?.trim()?.takeIf { it.isNotEmpty() }
        ?: meeting.courseName.trim()

/**
 * 对 UTF-8 身份做 SHA-256，取前 4 字节作为 32 位无符号值再落桶。这里不再使用
 * String.hashCode 的 4 色取模；同一身份在任何进程/设备上得到同一颜色。
 */
internal fun coursePaletteIndex(key: String, size: Int = COURSE_HUES.size): Int {
    require(size > 0) { "palette size must be positive" }
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    var hash = 0
    for (index in 0 until 4) {
        hash = (hash shl 8) or (digest[index].toInt() and 0xff)
    }
    return Math.floorMod(hash, size)
}

internal fun courseToneColors(index: Int, dark: Boolean): ToneColors {
    val hue = COURSE_HUES[Math.floorMod(index, COURSE_HUES.size)]
    return if (dark) {
        ToneColors(
            container = Color.hsl(hue, 0.45f, 0.27f),
            content = Color.hsl(hue, 0.25f, 0.95f),
        )
    } else {
        ToneColors(
            container = Color.hsl(hue, 0.55f, 0.88f),
            content = Color.hsl(hue, 0.45f, 0.17f),
        )
    }
}

@Composable
internal fun courseTone(meeting: TimetableMeeting): ToneColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return courseToneColors(coursePaletteIndex(courseColorKey(meeting)), dark)
}
