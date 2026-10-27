package com.upcpocket.app.data.school

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 学校教务课表（`app.upc.edu.cn` 公开课表页）的协议解析。
 *
 * 端点与结构来自学校课表页的公开前端代码（2026-10-27 核验）：
 *  - `GET  /timetable/wap/default/get-index` → `d.termInfo[]` + `d.params`
 *  - `POST /timetable/wap/default/get-data`（`year`、`term`、`week`、`type=1`）
 *      → `d.classes[]`，一条 = 某个教学周的一门课（`course_name`、`weekday`、
 *        `lessArr`、`teacher`、`location`、`course_id`）
 *
 * 这里**只做解析与校验**，不发请求；请求由 WebView 内固定脚本完成（见
 * `ui/schedule/SchoolScheduleSyncScreen.kt`），这样不需要把任何 Cookie/Token
 * 取出来交给 Kotlin。
 *
 * 校验口径：
 *  - 学校明确返回的空周（`classes: []`）是合法结果，返回空列表；
 *  - `classes` 不是数组、课程不是对象、缺 `course_name`/`weekday`/`lessArr`、
 *    节次不是 1–14 的整数，都抛 [SchoolScheduleFormatException]；
 *  - 不把无法识别的结构过滤成空列表，也不静默丢弃非法节次。
 */
internal object SchoolScheduleProtocol {
    const val BASE_URL = "https://app.upc.edu.cn"
    const val PAGE_URL = "$BASE_URL/site/weekschedule/"
    const val INDEX_PATH = "/timetable/wap/default/get-index"
    const val DATA_PATH = "/timetable/wap/default/get-data"

    /** 学校页面组件最多 14 个节次槽（公开前端的时间表也是 14 项）。 */
    const val MAX_UNITS = 14

    /** 与页面一致：超过 30 周不可能是正常学期，视为结构异常。 */
    const val MAX_WEEKS = 30

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parseIndex(body: String): SchoolTermPage {
        val data = successData(body, "学期信息")
        val rawTerms = data["termInfo"] as? JsonArray
            ?: fail("学校未返回学期列表（termInfo 缺失或不是数组）")
        if (rawTerms.isEmpty()) fail("学校返回的学期列表为空")
        val terms = rawTerms.map { item ->
            val obj = item as? JsonObject ?: fail("学期记录格式异常")
            val year = stringOf(obj, "year") ?: fail("学期记录缺少 year")
            val term = stringOf(obj, "term") ?: fail("学期记录缺少 term")
            val weeks = intOf(obj, "countweek") ?: fail("学期记录缺少 countweek")
            if (weeks !in 1..MAX_WEEKS) fail("学期周数异常（$weeks）")
            SchoolTerm(
                year = year,
                term = term,
                totalWeeks = weeks,
                label = labelOf(year, term),
            )
        }.distinctBy { it.year to it.term }
        val params = data["params"] as? JsonObject
        val currentYear = params?.let { stringOf(it, "year") }
        val currentTerm = params?.let { stringOf(it, "term") }
        val currentWeek = params?.let { intOf(it, "week") }
        val termStart = params?.let { stringOf(it, "startday") }
            ?.take(10)
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it.year in 2000..2100 }
        val current = terms.firstOrNull { it.year == currentYear && it.term == currentTerm }
        // `params.startday` 目前只观察到当前学期的线索，不能推给其它学期。
        return SchoolTermPage(
            terms = terms,
            current = current,
            currentWeek = currentWeek?.takeIf { it in 1..(current?.totalWeeks ?: 0) },
            currentTermStart = termStart,
        )
    }

    /**
     * 解析某一周。返回按「连续节次」拆好的课程块；[week] 只用于错误信息。
     * 该周没有课是正常结果（空列表），结构不符则抛错。
     */
    fun parseWeek(body: String, week: Int): List<SchoolClassBlock> {
        val data = successData(body, "第 $week 周课表")
        val classes = data["classes"] as? JsonArray
            ?: fail("第 $week 周的课表缺少 classes 数组，不能确认本周是否为空")
        return classes.map { item ->
            val obj = item as? JsonObject ?: fail("第 $week 周存在非对象的课程记录")
            val name = stringOf(obj, "course_name") ?: fail("第 $week 周存在缺少 course_name 的课程")
            val weekday = intOf(obj, "weekday") ?: fail("第 $week 周《$name》缺少 weekday")
            if (weekday !in 1..7) fail("第 $week 周《$name》的 weekday=$weekday 超出 1–7")
            val rawUnits = obj["lessArr"] as? JsonArray
                ?: fail("第 $week 周《$name》缺少 lessArr 节次数组")
            if (rawUnits.isEmpty()) fail("第 $week 周《$name》的 lessArr 为空")
            val units = rawUnits.map { element ->
                val primitive = element as? JsonPrimitive
                val value = primitive?.takeIf { !it.isString }?.intOrNull
                    ?: fail("第 $week 周《$name》的节次不是整数")
                if (value !in 1..MAX_UNITS) {
                    fail("第 $week 周《$name》的节次 $value 超出 1–$MAX_UNITS")
                }
                value
            }.distinct().sorted()
            val teacher = stringOf(obj, "teacher")?.takeIf { it.isNotBlank() }
            val room = stringOf(obj, "location")?.takeIf { it.isNotBlank() }
            val code = stringOf(obj, "course_id")?.takeIf { it.isNotBlank() }
            consecutive(units).map { (start, end) ->
                SchoolClassBlock(
                    name = name,
                    weekday = weekday,
                    startUnit = start,
                    endUnit = end,
                    teacher = teacher,
                    room = room,
                    courseId = code,
                )
            }
        }.flatten().distinct()
    }

    private fun consecutive(units: List<Int>): List<Pair<Int, Int>> {
        if (units.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var start = units[0]
        var prev = units[0]
        for (u in units.drop(1)) {
            if (u == prev + 1) {
                prev = u
            } else {
                out += start to prev
                start = u
                prev = u
            }
        }
        out += start to prev
        return out
    }

    /** 业务失败/登录过期与“格式已变化”分开抛，界面才能给出不同指引。 */
    private fun successData(body: String, what: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body) }
            .getOrNull() as? JsonObject
            ?: fail("$what 的响应不是 JSON 对象，可能仍是登录页")
        val rawCode = (root["e"] as? JsonPrimitive)?.let {
            it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull()
        } ?: fail("$what 的响应缺少 e 状态码")
        if (rawCode == 10013) throw SchoolSessionExpiredException()
        if (rawCode != 0) {
            val message = (root["m"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: "学校返回错误（e=$rawCode）"
            throw SchoolScheduleException("$what 读取失败：$message")
        }
        return root["d"] as? JsonObject ?: fail("$what 的响应缺少 d 数据")
    }

    private fun stringOf(obj: JsonObject, key: String): String? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        val value = primitive.contentOrNull?.trim() ?: return null
        return value.takeIf { it.isNotEmpty() }
    }

    private fun intOf(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

    private fun fail(message: String): Nothing = throw SchoolScheduleFormatException(message)

    private fun labelOf(year: String, term: String): String {
        val termName = when (term) {
            "1" -> "秋季学期"
            "2" -> "春季学期"
            "3" -> "夏季学期"
            else -> "第 $term 学期"
        }
        return "$year $termName"
    }
}

data class SchoolTerm(
    val year: String,
    val term: String,
    val totalWeeks: Int,
    val label: String,
) {
    /** 学期身份：只由学校学年 + 学期号决定，重复同步必须命中同一条学期记录。 */
    val key: String get() = "school:$year:$term"

    /** 可读的学期名，例如 `2026-2027 第一学期`；年份不是四位时退回学校原始写法。 */
    val displayName: String
        get() {
            val y = year.toIntOrNull() ?: return label
            val idx = when (term) {
                "1" -> "第一学期"
                "2" -> "第二学期"
                "3" -> "第三学期"
                else -> return label
            }
            return "$y-${y + 1} $idx"
        }
}

data class SchoolTermPage(
    val terms: List<SchoolTerm>,
    /** 学校页面 params 指出的当前学期；可能为空。 */
    val current: SchoolTerm?,
    /** 学校页面 params 里的当前教学周；可能为空。 */
    val currentWeek: Int?,
    /** 学校页面 params.startday（仅当前学期线索）；字段不存在或格式异常时为 null。 */
    val currentTermStart: java.time.LocalDate? = null,
)

data class SchoolClassBlock(
    val name: String,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val teacher: String?,
    val room: String?,
    val courseId: String?,
)

/** 学校返回的业务/格式错误：必须显示给用户，不能当成空数据。 */
open class SchoolScheduleException(message: String) : IOException(message)

class SchoolSessionExpiredException :
    SchoolScheduleException("学校教务登录已失效，请重新登录后再同步")

open class SchoolScheduleFormatException(message: String) : SchoolScheduleException(message)
