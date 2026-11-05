package com.upcpocket.app.timetable

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 学校官方作息。
 *
 * 来源：中国石油大学（华东）《关于调整作息时间的通知》（2020-05-25），自 2020 年秋季
 * 学期起执行：https://news.upc.edu.cn/info/1446/98819.htm
 *
 * 官方只公布 1–12 小节；课表协议允许到 14 节，13–14 节没有可靠时刻，不能按等差推算，
 * 因此返回 null，界面显示“时间待确认”。不新增第 15 节。
 */
internal data class TimetableClockRange(
    val startMinute: Int,
    val endMinute: Int,
) {
    init {
        require(startMinute in 0..(24 * 60)) { "非法开始时间" }
        require(endMinute in 0..(24 * 60)) { "非法结束时间" }
        require(endMinute > startMinute) { "结束时间必须晚于开始时间" }
    }

    val startLabel: String get() = format(startMinute)
    val endLabel: String get() = format(endMinute)
    val label: String get() = "$startLabel–$endLabel"

    private fun format(value: Int): String =
        "%02d:%02d".format(value / 60, value % 60)
}

internal object UpcTimetableSchedule {
    const val SOURCE_URL = "https://news.upc.edu.cn/info/1446/98819.htm"

    private val official = mapOf(
        1 to TimetableClockRange(8 * 60, 8 * 60 + 45),
        2 to TimetableClockRange(8 * 60 + 50, 9 * 60 + 35),
        3 to TimetableClockRange(9 * 60 + 55, 10 * 60 + 40),
        4 to TimetableClockRange(10 * 60 + 45, 11 * 60 + 30),
        5 to TimetableClockRange(11 * 60 + 35, 12 * 60 + 20),
        6 to TimetableClockRange(14 * 60, 14 * 60 + 45),
        7 to TimetableClockRange(14 * 60 + 50, 15 * 60 + 35),
        8 to TimetableClockRange(15 * 60 + 55, 16 * 60 + 40),
        9 to TimetableClockRange(16 * 60 + 45, 17 * 60 + 30),
        10 to TimetableClockRange(19 * 60, 19 * 60 + 45),
        11 to TimetableClockRange(19 * 60 + 50, 20 * 60 + 35),
        12 to TimetableClockRange(20 * 60 + 40, 21 * 60 + 25),
    )

    fun period(period: Int): TimetableClockRange? = official[period]

    /**
     * 优先使用学校响应里的 course_time（它是学校对本次课程的实际时间）；缺失或格式
     * 不可确认时，才按起止节次查官方作息。仍无来源时返回 null，不推算。
     */
    fun range(meeting: TimetableMeeting): TimetableClockRange? {
        parseCourseTime(meeting.courseTime)?.let { return it }
        val start = period(meeting.startPeriod) ?: return null
        val end = period(meeting.endPeriod) ?: return null
        return TimetableClockRange(start.startMinute, end.endMinute)
    }

    fun label(meeting: TimetableMeeting): String? = range(meeting)?.label

    private val COURSE_TIME = Regex(
        """(\d{1,2})\s*[:：]\s*(\d{2})\s*[~～\-–—]\s*(\d{1,2})\s*[:：]\s*(\d{2})""",
    )

    private fun parseCourseTime(value: String?): TimetableClockRange? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = COURSE_TIME.find(raw) ?: return null
        val start = (match.groupValues[1].toIntOrNull() ?: return null) * 60 +
            (match.groupValues[2].toIntOrNull() ?: return null)
        val end = (match.groupValues[3].toIntOrNull() ?: return null) * 60 +
            (match.groupValues[4].toIntOrNull() ?: return null)
        if (start !in 0 until 24 * 60 || end !in 0..24 * 60 || end <= start) return null
        return TimetableClockRange(start, end)
    }

    private val UI_TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun format(minute: Int): String = LocalTime.of(minute / 60, minute % 60).format(UI_TIME)
}
