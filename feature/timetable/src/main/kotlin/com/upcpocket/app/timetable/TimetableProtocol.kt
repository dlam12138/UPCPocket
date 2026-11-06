package com.upcpocket.app.timetable

import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TimetableErrorCode {
    AUTH_REQUIRED,
    LOGIN_PAGE,
    UPSTREAM_ERROR,
    SCHEMA_CHANGED,
    INVALID_INPUT,
    RESPONSE_TOO_LARGE,
}

class TimetableParseException(
    val code: TimetableErrorCode,
    val detail: DiagnosticDetail = DiagnosticDetail.RESPONSE_NON_JSON,
) : Exception(code.name, null, false, false)

/**
 * UPC 校园 App 课表协议解析。
 *
 * 核心结构仍然严格：JSON/业务码、7 天、连续 7 日、日期合法、课程名、节次
 * 1–14、节次键与课程记录必须是数组。对真实用户数据中**不决定课程归属**的
 * 冗余字段（order/totalLength/声明 weekday/c_N 起始）以及单条不可展示记录，
 * 采用“保留其余课程 + 记录兼容规则”的策略，避免一条脏数据让整周课表消失。
 * 日期始终取自源 weekdays，节次始终取自 lessArr，不按猜测补课。
 */
object TimetableProtocol {

    const val MAX_WEEKS = 30
    const val MAX_PERIOD = 14
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private val TERM_LABELS = mapOf("1" to "秋季", "2" to "春季", "3" to "夏季")
    private val DATE = Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$")
    private val CELL_KEY = Regex("^c_(\\d{1,2})$")
    private val WEEK_TOKEN = Regex("\\d{1,2}")
    private val WEEK_RANGE = Regex("^(\\d{1,2})\\s*[-~—－]\\s*(\\d{1,2})$")

    fun parseIndex(raw: String): TimetableIndex = parseIndex(decode(raw))

    fun parseWeek(raw: String, term: TimetableTerm, week: Int): TimetableWeek =
        parseWeek(decode(raw), term, week)

    internal fun parseIndex(root: JsonElement): TimetableIndex {
        val obj = root.obj("get-index 响应", DiagnosticDetail.INDEX_PARSE_TERM_INFO)
        val code = int(obj["e"], "get-index.e", DiagnosticDetail.INDEX_PARSE_TERM_INFO)
        if (code == 10013) {
            throw TimetableParseException(
                TimetableErrorCode.AUTH_REQUIRED,
                DiagnosticDetail.AUTH_REQUIRED,
            )
        }
        if (code != 0) {
            throw TimetableParseException(
                TimetableErrorCode.UPSTREAM_ERROR,
                DiagnosticDetail.RESPONSE_BUSINESS,
            )
        }

        val data = obj["d"].obj("get-index.d", DiagnosticDetail.INDEX_PARSE_TERM_INFO)
        val rawTerms = data["termInfo"].array(
            "get-index.d.termInfo",
            DiagnosticDetail.INDEX_PARSE_TERM_INFO,
        )
        if (rawTerms.isEmpty()) {
            throw TimetableParseException(
                TimetableErrorCode.SCHEMA_CHANGED,
                DiagnosticDetail.INDEX_PARSE_TERM_INFO,
            )
        }
        val compatibility = linkedSetOf<TimetableCompatibility>()
        val terms = mutableListOf<TimetableTerm>()
        val seen = mutableSetOf<Pair<String, String>>()
        rawTerms.forEachIndexed { index, element ->
            // 历史/暑期学期可能是宽松字段；单条无效不应让当前学期失效。
            val parsed = runCatching {
                val item = element.obj(
                    "termInfo[$index]",
                    DiagnosticDetail.INDEX_PARSE_TERM_INFO,
                )
                val year = requiredText(
                    item["year"],
                    "termInfo[$index].year",
                    DiagnosticDetail.INDEX_PARSE_TERM_INFO,
                )
                val term = requiredText(
                    item["term"],
                    "termInfo[$index].term",
                    DiagnosticDetail.INDEX_PARSE_TERM_INFO,
                )
                val startDay = date(
                    item["startday"],
                    "termInfo[$index].startday",
                    DiagnosticDetail.INDEX_PARSE_TERM_DATE,
                )
                val totalWeeks = int(
                    item["countweek"],
                    "termInfo[$index].countweek",
                    DiagnosticDetail.INDEX_PARSE_TERM_WEEKS,
                )
                if (totalWeeks !in 1..MAX_WEEKS) {
                    throw TimetableParseException(
                        TimetableErrorCode.SCHEMA_CHANGED,
                        DiagnosticDetail.INDEX_PARSE_TERM_WEEKS,
                    )
                }
                TimetableTerm(
                    year = year,
                    term = term,
                    startDay = startDay,
                    totalWeeks = totalWeeks,
                    id = text(item["id"]),
                    label = "$year ${TERM_LABELS[term] ?: "第${term}学期"}",
                )
            }.getOrNull()
            if (parsed == null) {
                compatibility += TimetableCompatibility.INDEX_TERM_SKIPPED
                return@forEachIndexed
            }
            if (!seen.add(parsed.year to parsed.term)) {
                compatibility += TimetableCompatibility.INDEX_TERM_SKIPPED
                return@forEachIndexed
            }
            terms += parsed
        }
        if (terms.isEmpty()) {
            throw TimetableParseException(
                TimetableErrorCode.SCHEMA_CHANGED,
                DiagnosticDetail.INDEX_PARSE_TERM_INFO,
            )
        }

        val params = (data["params"] as? JsonObject) ?: JsonObject(emptyMap())
        val currentYear = text(params["year"])
        val currentTerm = text(params["term"])
        if (currentYear != null && currentTerm != null &&
            terms.none { it.year == currentYear && it.term == currentTerm }
        ) {
            // 当前学期本身不可用时不能悄悄回退到历史学期。
            throw TimetableParseException(
                TimetableErrorCode.SCHEMA_CHANGED,
                DiagnosticDetail.INDEX_PARSE_CURRENT,
            )
        }
        val currentWeek = params["week"]?.let { value ->
            val parsed = runCatching {
                int(value, "params.week", DiagnosticDetail.INDEX_PARSE_CURRENT)
            }.getOrNull()
            if (parsed == null || parsed !in 1..MAX_WEEKS) {
                compatibility += TimetableCompatibility.INDEX_CURRENT_IGNORED
                null
            } else {
                parsed
            }
        }
        val currentWeekdays = data["weekdays"]?.let { value ->
            runCatching {
                checkDates(value, "get-index.d.weekdays", DiagnosticDetail.INDEX_PARSE_CURRENT)
            }.getOrNull().also { parsed ->
                if (parsed == null) {
                    compatibility += TimetableCompatibility.INDEX_CURRENT_IGNORED
                }
            }
        }
        val currentWeekday = data["weekday"]?.let { value ->
            normalizeWeekday(value).also { parsed ->
                if (parsed == null) {
                    compatibility += TimetableCompatibility.INDEX_CURRENT_IGNORED
                }
            }
        }
        return TimetableIndex(
            terms = terms,
            currentYear = currentYear,
            currentTerm = currentTerm,
            currentWeek = currentWeek,
            currentWeekdays = currentWeekdays,
            currentWeekday = currentWeekday,
            compatibility = compatibility.toList(),
        )
    }

    internal fun parseWeek(root: JsonElement, term: TimetableTerm, week: Int): TimetableWeek {
        if (week !in 1..term.totalWeeks) {
            throw TimetableParseException(TimetableErrorCode.INVALID_INPUT)
        }
        val compatibility = linkedSetOf<TimetableCompatibility>()
        val obj = root.obj("get-datatmp 响应", DiagnosticDetail.WEEK_PARSE_CLASSES)
        val code = int(obj["e"], "get-datatmp.e", DiagnosticDetail.WEEK_PARSE_CLASSES)
        if (code == 10013) {
            throw TimetableParseException(
                TimetableErrorCode.AUTH_REQUIRED,
                DiagnosticDetail.AUTH_REQUIRED,
            )
        }
        if (code != 0) {
            throw TimetableParseException(
                TimetableErrorCode.UPSTREAM_ERROR,
                DiagnosticDetail.RESPONSE_BUSINESS,
            )
        }

        val data = obj["d"].obj("get-datatmp.d", DiagnosticDetail.WEEK_PARSE_CLASSES)
        val classes = data["classes"].array(
            "get-datatmp.d.classes",
            DiagnosticDetail.WEEK_PARSE_CLASSES,
        )
        if (classes.size != 7) {
            throw TimetableParseException(
                TimetableErrorCode.SCHEMA_CHANGED,
                DiagnosticDetail.WEEK_PARSE_CLASSES,
            )
        }
        val dates = checkDates(
            data["weekdays"],
            "get-datatmp.d.weekdays",
            DiagnosticDetail.WEEK_PARSE_DATES,
        )
        val expectedStart = term.startDay.plusDays((week - 1).toLong() * 7)
        if (dates.first() != expectedStart) {
            // 没有该用户或官方协议证据支持“相邻周可接受”。日期与请求周不一致
            // 时明确失败，避免把 N±1 周数据按 N 周缓存成成功。
            throw TimetableParseException(
                TimetableErrorCode.SCHEMA_CHANGED,
                DiagnosticDetail.WEEK_PARSE_DATE_ALIGN,
            )
        }

        val meetings = mutableListOf<TimetableMeeting>()
        classes.forEachIndexed { dayIndex, dayElement ->
            val day = when (dayElement) {
                is JsonObject -> dayElement
                else -> throw TimetableParseException(
                    TimetableErrorCode.SCHEMA_CHANGED,
                    DiagnosticDetail.WEEK_PARSE_DAY_TYPE,
                )
            }
            val date = dates[dayIndex]
            val weekday = date.dayOfWeek.value
            day.forEach { (key, value) ->
                val match = CELL_KEY.matchEntire(key)
                if (match == null) {
                    // 非 c_N 的元数据键不承载课程；忽略，避免新增元数据让整周失败。
                    if (key.startsWith("c_")) {
                        throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_CELL_KEY,
                        )
                    }
                    return@forEach
                }
                val cellStart = match.groupValues[1].toInt()
                val entries = when (value) {
                    is JsonNull -> return@forEach
                    is JsonArray -> value
                    else -> throw TimetableParseException(
                        TimetableErrorCode.SCHEMA_CHANGED,
                        DiagnosticDetail.WEEK_PARSE_CELL_KEY,
                    )
                }
                if (cellStart !in 1..MAX_PERIOD) {
                    if (entries.isNotEmpty()) {
                        throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_PERIOD_RANGE,
                        )
                    }
                    return@forEach
                }
                entries.forEach { entryElement ->
                    val entry = entryElement as? JsonObject
                        ?: throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_ENTRY_TYPE,
                        )
                    val name = text(entry["course_name"])
                    if (name.isNullOrBlank()) {
                        throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_NAME,
                        )
                    }
                    val rawPeriods = entry["lessArr"] as? JsonArray
                    if (rawPeriods == null || rawPeriods.isEmpty()) {
                        throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_LESS_ARR,
                        )
                    }
                    val sourcePeriods = rawPeriods.map { element ->
                        integralIntOrNull(element) ?: throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_LESS_ARR,
                        )
                    }
                    if (sourcePeriods.any { it !in 1..MAX_PERIOD }) {
                        throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_PERIOD_RANGE,
                        )
                    }
                    if (rawPeriods.any(::isDecimalForm)) {
                        compatibility += TimetableCompatibility.INTEGRAL_FLOAT_ACCEPTED
                    }
                    val periods = sourcePeriods.distinct().sorted()
                    if (periods.size != sourcePeriods.size) {
                        compatibility += TimetableCompatibility.DUPLICATE_PERIODS_DEDUPED
                    }
                    if (cellStart != periods.min()) {
                        compatibility += TimetableCompatibility.CELL_START_IGNORED
                    }

                    val weekLabel = text(entry["week"])
                        ?: throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_WEEK_LABEL,
                        )
                    val parsedWeeks = weekNumbers(weekLabel, term.totalWeeks)
                    if (parsedWeeks == null || parsedWeeks.isEmpty() || week !in parsedWeeks) {
                        // 不把请求周补进源周列表；源标签明确排除或无法解释时
                        // 这条课程不能当作已核实的当前周课程。
                        throw TimetableParseException(
                            TimetableErrorCode.SCHEMA_CHANGED,
                            DiagnosticDetail.WEEK_PARSE_WEEK_LABEL,
                        )
                    }
                    val weeks = parsedWeeks

                    entry["weekday"]?.let { declared ->
                        val normalized = normalizeWeekday(declared)
                        if (normalized != weekday) {
                            compatibility += TimetableCompatibility.WEEKDAY_IGNORED
                        }
                    }

                    val rawTotalLength = entry["totalLength"]
                    val totalLength = optionalInt(rawTotalLength)
                    if (rawTotalLength != null && rawTotalLength !is JsonNull &&
                        (totalLength == null || totalLength != periods.size)
                    ) {
                        compatibility += TimetableCompatibility.TOTAL_LENGTH_IGNORED
                    }
                    val rawOrder = entry["order"]
                    val order = optionalInt(rawOrder)
                    if (rawOrder != null && rawOrder !is JsonNull &&
                        (order == null || order != periods.min())
                    ) {
                        compatibility += TimetableCompatibility.ORDER_IGNORED
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
                            sourcePeriods = sourcePeriods,
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
            compatibility = compatibility.toList(),
        )
    }

    private fun decode(raw: String): JsonElement {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw TimetableParseException(
                TimetableErrorCode.RESPONSE_TOO_LARGE,
                DiagnosticDetail.RESPONSE_TOO_LARGE,
            )
        }
        val text = raw.trimStart('\uFEFF', '\u200B').trim()
        if (text.isEmpty()) {
            throw TimetableParseException(
                TimetableErrorCode.SCHEMA_CHANGED,
                DiagnosticDetail.RESPONSE_EMPTY,
            )
        }
        return try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw TimetableParseException(
                TimetableErrorCode.SCHEMA_CHANGED,
                if (text.first() == '{' || text.first() == '[') {
                    DiagnosticDetail.RESPONSE_MALFORMED_JSON
                } else {
                    DiagnosticDetail.RESPONSE_NON_JSON
                },
            )
        }
    }

    private fun JsonElement?.obj(what: String, detail: DiagnosticDetail): JsonObject =
        this as? JsonObject ?: throw TimetableParseException(
            TimetableErrorCode.SCHEMA_CHANGED,
            detail,
        )

    private fun JsonElement?.array(what: String, detail: DiagnosticDetail): JsonArray =
        this as? JsonArray ?: throw TimetableParseException(
            TimetableErrorCode.SCHEMA_CHANGED,
            detail,
        )

    private fun int(value: JsonElement?, what: String, detail: DiagnosticDetail): Int =
        integralIntOrNull(value) ?: throw TimetableParseException(
            TimetableErrorCode.SCHEMA_CHANGED,
            detail,
        )

    private fun optionalInt(value: JsonElement?): Int? = integralIntOrNull(value)

    /**
     * Accept JSON numbers, numeric strings and integral decimals such as `6.0`
     * or `"6.0"`. Fractional values are still rejected rather than rounded.
     */
    private fun integralIntOrNull(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val text = primitive.content.trim()
        if (text.isEmpty()) return null
        val number = text.toDoubleOrNull() ?: return null
        if (!number.isFinite() || number % 1.0 != 0.0) return null
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
        return number.toInt()
    }

    private fun isDecimalForm(value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        if (primitive is JsonNull) return false
        return primitive.content.contains('.')
    }

    private fun text(value: JsonElement?): String? {
        if (value == null || value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString && primitive.content.isBlank()) return null
        return primitive.content.trim().ifEmpty { null }
    }

    private fun requiredText(
        value: JsonElement?,
        what: String,
        detail: DiagnosticDetail,
    ): String = text(value) ?: throw TimetableParseException(
        TimetableErrorCode.SCHEMA_CHANGED,
        detail,
    )

    private fun date(value: JsonElement?, what: String, detail: DiagnosticDetail): LocalDate {
        val raw = requiredText(value, what, detail)
        if (!DATE.matches(raw)) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED, detail)
        }
        return try {
            val parts = raw.split("-")
            LocalDate.of(
                parts[0].toInt(),
                parts[1].toInt(),
                parts[2].toInt(),
            )
        } catch (_: Exception) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED, detail)
        }
    }

    private fun checkDates(
        value: JsonElement?,
        what: String,
        detail: DiagnosticDetail,
    ): List<LocalDate> {
        val values = value.array(what, detail)
        if (values.size != 7) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED, detail)
        }
        val dates = values.mapIndexed { index, element ->
            date(element, "$what[$index]", detail)
        }
        if (dates.zipWithNext().any { (a, b) -> a.plusDays(1) != b }) {
            throw TimetableParseException(TimetableErrorCode.SCHEMA_CHANGED, detail)
        }
        return dates
    }

    private fun normalizeWeekday(value: JsonElement?): Int? {
        val number = integralIntOrNull(value) ?: return null
        return when (number) {
            0 -> 7
            in 1..7 -> number
            else -> null
        }
    }

    /**
     * Tolerant week label: comma/Chinese comma/space separated numbers and
     * ranges such as `1-16`, `1~16`. Returns null when the label cannot be
     * interpreted. The caller fails the week; it never appends the requested
     * week to source facts.
     */
    private fun weekNumbers(raw: String, totalWeeks: Int): List<Int>? {
        val tokens = raw.trim()
            .split(Regex("[,，、;；\\s]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val numbers = linkedSetOf<Int>()
        tokens.forEach { original ->
            val token = original.removeSuffix("周").trim()
            if (token.isEmpty()) return null
            val range = WEEK_RANGE.matchEntire(token)
            if (range != null) {
                val start = range.groupValues[1].toIntOrNull() ?: return null
                val end = range.groupValues[2].toIntOrNull() ?: return null
                if (start > end) return null
                (start..end).forEach { if (it in 1..totalWeeks) numbers += it }
            } else if (WEEK_TOKEN.matches(token)) {
                val value = token.toIntOrNull() ?: return null
                if (value in 1..totalWeeks) numbers += value
            } else {
                return null
            }
        }
        return numbers.sorted()
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
