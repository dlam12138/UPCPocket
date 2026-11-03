package com.upcpocket.app.timetable

import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TimetableErrorCode {
    AUTH_REQUIRED,
    UPSTREAM_ERROR,
    SCHEMA_CHANGED,
    INVALID_INPUT,
    RESPONSE_TOO_LARGE,
}

class TimetableParseException(val code: TimetableErrorCode) :
    Exception(code.name, null, false, false)

/**
 * UPC 校园 App 课表协议解析。
 *
 * 移植自 UPC-MCP 固定提交的严格语义：字段矛盾、日期与星期不符、节次越界、
 * 课程周次不含请求周等一律拒绝；不静默纠偏，也不把错误响应当空课表。
 */
object TimetableProtocol {

    const val MAX_WEEKS = 30
    const val MAX_PERIOD = 14
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private val TERM_LABELS = mapOf("1" to "秋季", "2" to "春季", "3" to "夏季")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val CELL_KEY = Regex("^c_(\\d{1,2})$")

    fun parseIndex(raw: String): TimetableIndex = parseIndex(decode(raw))

    fun parseWeek(raw: String, term: TimetableTerm, week: Int): TimetableWeek =
        parseWeek(decode(raw), term, week)

    internal fun parseIndex(root: JsonElement): TimetableIndex {
        val obj = root.obj("get-index 响应")
        val code = int(obj["e"], "get-index.e")
        if (code == 10013) throw TimetableParseException(TimetableErrorCode.AUTH_REQUIRED)
        if (code != 0) throw TimetableParseException(TimetableErrorCode.UPSTREAM_ERROR)

        val data = obj["d"].obj("get-index.d")
        val rawTerms = data["termInfo"].array("get-index.d.termInfo")
        if (rawTerms.isEmpty()) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        val terms = mutableListOf<TimetableTerm>()
        val seen = mutableSetOf<Pair<String, String>>()
        rawTerms.forEachIndexed { index, element ->
            val item = element.obj("termInfo[$index]")
            val year = requiredText(item["year"], "termInfo[$index].year")
            val term = requiredText(item["term"], "termInfo[$index].term")
            val startDay = date(item["startday"], "termInfo[$index].startday")
            val totalWeeks = int(item["countweek"], "termInfo[$index].countweek")
            if (totalWeeks !in 1..MAX_WEEKS) {
                throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
            }
            if (!seen.add(year to term)) {
                throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
            }
            terms += TimetableTerm(
                year = year,
                term = term,
                startDay = startDay,
                totalWeeks = totalWeeks,
                id = text(item["id"]),
                label = "$year ${TERM_LABELS[term] ?: "第${term}学期"}",
            )
        }

        val params = (data["params"] as? JsonObject) ?: JsonObject(emptyMap())
        val currentYear = text(params["year"])
        val currentTerm = text(params["term"])
        val currentWeek = params["week"]?.let {
            int(it, "params.week").also { value ->
                if (value !in 1..MAX_WEEKS) {
                    throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                }
            }
        }
        val currentWeekdays = data["weekdays"]?.let {
            checkDates(it, "get-index.d.weekdays")
        }
        val currentWeekday = data["weekday"]?.let {
            normalizeWeekday(it) ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        return TimetableIndex(
            terms = terms,
            currentYear = currentYear,
            currentTerm = currentTerm,
            currentWeek = currentWeek,
            currentWeekdays = currentWeekdays,
            currentWeekday = currentWeekday,
        )
    }

    internal fun parseWeek(root: JsonElement, term: TimetableTerm, week: Int): TimetableWeek {
        if (week !in 1..term.totalWeeks) {
            throw TimetableParseException(TimetableErrorCode.INVALID_INPUT)
        }
        val obj = root.obj("get-datatmp 响应")
        val code = int(obj["e"], "get-datatmp.e")
        if (code == 10013) throw TimetableParseException(TimetableErrorCode.AUTH_REQUIRED)
        if (code != 0) throw TimetableParseException(TimetableErrorCode.UPSTREAM_ERROR)

        val data = obj["d"].obj("get-datatmp.d")
        val classes = data["classes"].array("get-datatmp.d.classes")
        if (classes.size != 7) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        val dates = checkDates(data["weekdays"], "get-datatmp.d.weekdays")
        val expectedStart = term.startDay.plusDays((week - 1).toLong() * 7)
        if (dates.first() != expectedStart) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }

        val meetings = mutableListOf<TimetableMeeting>()
        classes.forEachIndexed { dayIndex, dayElement ->
            val day = dayElement.obj("classes[$dayIndex]")
            val date = dates[dayIndex]
            val weekday = date.dayOfWeek.value
            day.forEach { (key, value) ->
                val match = CELL_KEY.matchEntire(key)
                    ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                val cellStart = match.groupValues[1].toInt()
                if (cellStart !in 1..MAX_PERIOD) {
                    throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                }
                val entries = value.array("课表节次单元格")
                entries.forEach { entryElement ->
                    val entry = entryElement.obj("课程记录")
                    val name = requiredText(entry["course_name"], "课程 course_name")
                    val rawPeriods = entry["lessArr"].array("课程 lessArr")
                    if (rawPeriods.isEmpty()) {
                        throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                    }
                    val periods = rawPeriods.map { int(it, "课程 lessArr 项") }
                    if (periods.any { it !in 1..MAX_PERIOD }) {
                        throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                    }
                    if (periods.distinct().size != periods.size) {
                        throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                    }
                    if (cellStart != periods.min()) {
                        throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                    }
                    val weekLabel = requiredText(entry["week"], "课程 week")
                    val weeks = weekNumbers(weekLabel, term.totalWeeks)
                    if (week !in weeks) {
                        throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                    }
                    entry["weekday"]?.let { declared ->
                        val normalized = normalizeWeekday(declared)
                            ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                        if (normalized != weekday) {
                            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                        }
                    }
                    val totalLength = entry["totalLength"]?.let {
                        int(it, "课程 totalLength").also { value ->
                            if (value != periods.size) {
                                throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                            }
                        }
                    }
                    val order = entry["order"]?.let {
                        int(it, "课程 order").also { value ->
                            if (value != periods.min()) {
                                throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
                            }
                        }
                    }
                    val groups = consecutiveGroups(periods)
                    val split = groups.size > 1
                    groups.forEach { (start, end) ->
                        meetings += TimetableMeeting(
                            courseName = name,
                            weekday = weekday,
                            startPeriod = start,
                            endPeriod = end,
                            periods = (start..end).toList(),
                            sourcePeriods = periods,
                            weeks = weeks,
                            weekLabel = weekLabel,
                            teacher = text(entry["teacher"]),
                            location = text(entry["location"]),
                            courseId = text(entry["course_id"]),
                            weekType = text(entry["week_type"]),
                            order = order,
                            totalLength = totalLength,
                            lessons = text(entry["lessons"]),
                            courseTime = text(entry["course_time"]),
                            sectionsSource = if (split) {
                                "lessArr_split_noncontiguous"
                            } else {
                                "lessArr"
                            },
                            credit = text(entry["credit"]),
                            courseType = text(entry["course_type"]),
                            courseHour = text(entry["course_hour"]),
                            examType = text(entry["khfs"]),
                            date = date,
                        )
                    }
                }
            }
        }
        return TimetableWeek(
            term = term,
            week = week,
            dates = dates,
            meetings = meetings,
            fetchedAtEpochMillis = 0L,
            complete = true,
        )
    }

    private fun decode(raw: String): JsonElement {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw TimetableParseException(TimetableErrorCode.RESPONSE_TOO_LARGE)
        }
        return try {
            Json.parseToJsonElement(raw)
        } catch (_: Exception) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
    }

    private fun JsonElement?.obj(what: String): JsonObject =
        this as? JsonObject ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)

    private fun JsonElement?.array(what: String): JsonArray =
        this as? JsonArray ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)

    private fun int(value: JsonElement?, what: String): Int {
        val primitive = value as? JsonPrimitive
            ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        if (primitive is JsonNull) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        if (primitive.isString) {
            if (!primitive.content.matches(Regex("[+-]?\\d+"))) {
                throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
            }
            return primitive.content.toIntOrNull()
                ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        return primitive.content.toIntOrNull()
            ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
    }

    private fun text(value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString && primitive.content.isBlank()) return null
        return primitive.content.trim().ifEmpty { null }
    }

    private fun requiredText(value: JsonElement?, what: String): String =
        text(value) ?: throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)

    private fun date(value: JsonElement?, what: String): LocalDate {
        val raw = requiredText(value, what)
        if (!DATE.matches(raw)) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        return try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
    }

    private fun checkDates(value: JsonElement?, what: String): List<LocalDate> {
        val values = value.array(what)
        if (values.size != 7) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        val dates = values.mapIndexed { index, element ->
            date(element, "$what[$index]")
        }
        if (dates.zipWithNext().any { (a, b) -> a.plusDays(1) != b }) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        return dates
    }

    private fun normalizeWeekday(value: JsonElement?): Int? {
        val number = try {
            int(value, "weekday")
        } catch (_: TimetableParseException) {
            return null
        }
        return when (number) {
            0 -> 7
            in 1..7 -> number
            else -> null
        }
    }

    private fun weekNumbers(raw: String, totalWeeks: Int): List<Int> {
        val parts = raw.split(",").map { it.trim() }
        if (parts.isEmpty() || parts.any { !it.matches(Regex("\\d+")) }) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        val numbers = parts.map { it.toInt() }
        if (numbers.distinct().size != numbers.size) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        if (numbers.any { it !in 1..totalWeeks }) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        return numbers
    }

    private fun consecutiveGroups(periods: List<Int>): List<Pair<Int, Int>> {
        val ordered = periods.distinct().sorted()
        val groups = mutableListOf<Pair<Int, Int>>()
        ordered.forEach { period ->
            val last = groups.lastOrNull()
            if (last == null || period != last.second + 1) {
                groups += period to period
            } else {
                groups[groups.lastIndex] = last.first to period
            }
        }
        return groups
    }
}
