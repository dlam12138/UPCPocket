package com.upcpocket.app.timetable

import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

val TIMETABLE_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

object LocalDateIsoSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDateIso", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.format(DateTimeFormatter.ISO_LOCAL_DATE))
    }

    override fun deserialize(decoder: Decoder): LocalDate =
        LocalDate.parse(decoder.decodeString(), DateTimeFormatter.ISO_LOCAL_DATE)
}

@Serializable
data class TimetableTerm(
    val year: String,
    val term: String,
    @Serializable(with = LocalDateIsoSerializer::class) val startDay: LocalDate,
    val totalWeeks: Int,
    val id: String? = null,
    val label: String,
) {
    init {
        require(year.isNotBlank() && term.isNotBlank()) { "学期标识为空" }
        require(totalWeeks in 1..30) { "教学周数超出 1–30" }
        require(label.isNotBlank()) { "学期标签为空" }
    }
}

@Serializable
data class TimetableIndex(
    val terms: List<TimetableTerm>,
    val currentYear: String? = null,
    val currentTerm: String? = null,
    val currentWeek: Int? = null,
    val currentWeekdays: List<@Serializable(with = LocalDateIsoSerializer::class) LocalDate>? = null,
    val currentWeekday: Int? = null,
) {
    fun find(year: String, term: String): TimetableTerm? =
        terms.firstOrNull { it.year == year && it.term == term }

    fun current(): TimetableTerm? {
        val year = currentYear ?: return null
        val term = currentTerm ?: return null
        return find(year, term)
    }
}

/**
 * 一段连续节次的课程。
 *
 * 源协议对非连续节次会拆成多条会议，但 [sourcePeriods] 保留完整的原始
 * `lessArr`；单双周只保留源 `weeks`/`weekType`，不另做推断。
 */
@Serializable
data class TimetableMeeting(
    val courseName: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val periods: List<Int>,
    val sourcePeriods: List<Int>,
    val weeks: List<Int>,
    val weekLabel: String,
    val teacher: String? = null,
    val location: String? = null,
    val courseId: String? = null,
    val weekType: String? = null,
    val order: Int? = null,
    val totalLength: Int? = null,
    val lessons: String? = null,
    val courseTime: String? = null,
    val sectionsSource: String,
    val credit: String? = null,
    val courseType: String? = null,
    val courseHour: String? = null,
    val examType: String? = null,
    @Serializable(with = LocalDateIsoSerializer::class) val date: LocalDate,
) {
    init {
        require(courseName.isNotBlank()) { "课程名不能为空" }
        require(weekday in 1..7) { "ISO weekday 必须在 1–7" }
        require(periods.isNotEmpty() && periods == (startPeriod..endPeriod).toList()) {
            "展示节次必须连续"
        }
        require(sourcePeriods.containsAll(periods)) { "原始节次必须包含展示节次" }
        require(periods.all { it in 1..14 }) { "节次超出 1–14" }
        require(weeks.isNotEmpty()) { "课程周次不能为空" }
        require(date.dayOfWeek.value == weekday) { "课程日期与 ISO weekday 不一致" }
    }

    val instanceId: String
        get() {
            val key = listOf(
                courseId.orEmpty(),
                courseName,
                date.toString(),
                "$startPeriod-$endPeriod",
                order?.toString().orEmpty(),
                teacher.orEmpty(),
                location.orEmpty(),
            ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
        }

    /** 仅用于详情：完整原始节次，不把拆分后的区间当成原始事实。 */
    fun sourcePeriodsLabel(): String = sourcePeriods.joinToString("、") { "第${it}节" }
}

@Serializable
data class TimetableWeek(
    val term: TimetableTerm,
    val week: Int,
    val dates: List<@Serializable(with = LocalDateIsoSerializer::class) LocalDate>,
    val meetings: List<TimetableMeeting>,
    val fetchedAtEpochMillis: Long,
    val complete: Boolean = true,
) {
    init {
        require(week in 1..term.totalWeeks) { "教学周超出学期范围" }
        require(dates.size == 7) { "教学周必须恰好 7 天" }
        require(dates.zipWithNext().all { (a, b) -> a.plusDays(1) == b }) { "教学周日期不连续" }
        require(meetings.all { it.weeks.contains(week) }) {
            "课程周次不包含当前教学周"
        }
    }

    val isEmpty: Boolean get() = complete && meetings.isEmpty()
}

/** UI 需要区分的真实状态；不能把未查询或失败显示成空课表。 */
enum class TimetableConnection {
    LOADING,
    NOT_CONFIGURED,
    BLOCKED_BY_CLEANUP,
    IDLE,
    CONNECTING,
    CONNECTED,
    INTERACTION_REQUIRED,
    AUTH_FAILED,
    NETWORK_UNAVAILABLE,
    PROTOCOL_ERROR,
    SESSION_EXPIRED,
    DEGRADED_CACHE,
}

enum class TimetableWeekState {
    NOT_QUERIED,
    LOADING,
    READY,
    EMPTY,
    CACHED,
    ERROR,
}

data class TimetableUiState(
    val accountStatusLabel: String = "",
    val accountEpoch: Long = 0,
    val maskedAccount: String? = null,
    val accountSavedUnverified: Boolean = false,
    val connection: TimetableConnection = TimetableConnection.LOADING,
    val terms: List<TimetableTerm> = emptyList(),
    val selectedYear: String? = null,
    val selectedTerm: String? = null,
    val selectedWeek: Int? = null,
    val weekDates: List<LocalDate> = emptyList(),
    val meetings: List<TimetableMeeting> = emptyList(),
    /** 由账号/学期配色注册表分配；key 是课程号或课程名。 */
    val courseColorSlots: Map<String, Int> = emptyMap(),
    val weekState: TimetableWeekState = TimetableWeekState.NOT_QUERIED,
    val weekFetchedAtEpochMillis: Long? = null,
    val today: LocalDate? = null,
    val todayMeetings: List<TimetableMeeting> = emptyList(),
    val todayLoaded: Boolean = false,
    /** 今日卡片实际使用的缓存/查询时间；查其他教学周时不会跟着变。 */
    val todayFetchedAtEpochMillis: Long? = null,
    val message: String? = null,
    val interactionUrl: String? = null,
    val isBusy: Boolean = false,
    val canRetry: Boolean = false,
    val accountBlocked: Boolean = false,
) {
    val selectedTermOrNull: TimetableTerm?
        get() {
            val year = selectedYear ?: return null
            val term = selectedTerm ?: return null
            return terms.firstOrNull { it.year == year && it.term == term }
        }

    val selectedWeekEmpty: Boolean
        get() = weekState == TimetableWeekState.EMPTY

    val hasWeekData: Boolean
        get() = weekState == TimetableWeekState.READY ||
            weekState == TimetableWeekState.EMPTY ||
            weekState == TimetableWeekState.CACHED
}
