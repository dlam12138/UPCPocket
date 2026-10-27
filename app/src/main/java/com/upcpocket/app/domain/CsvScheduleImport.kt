package com.upcpocket.app.domain

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * 通用 CSV 课表解析。
 *
 * 学校实际导出的表头尚未取得，所以这里只接受**能明确解释**的列与字段：
 * 无法识别的列忽略，缺关键列、歧义重复列或任何一条记录无法解释时整批拒绝，
 * 不猜测、不静默丢弃。解析结果只是“待预览的时段”，真正写入由
 * [com.upcpocket.app.data.repo.ScheduleRepository.importCsvRows] 在同一事务内完成。
 */
object CsvScheduleImport {
    const val MAX_BYTES = 2 * 1024 * 1024

    data class Row(
        val name: String,
        val weekday: Int,
        val start: Int,
        val end: Int,
        val weeks: List<Int>,
        val teacher: String = "",
        val room: String = "",
    ) {
        fun spec(semesterId: Int): PlanSpec {
            val canonical = listOf(
                name, weekday.toString(), start.toString(), end.toString(),
                weeks.sorted().distinct().joinToString(","), teacher, room,
            ).joinToString("") { "${it.length}:$it" }
            val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return PlanSpec(
                code = "Ccsv$hash", semesterId = semesterId, name = name,
                weekday = weekday, startUnit = start, endUnit = end, weeks = weeks,
                teacherName = teacher, roomText = room, kind = PlanKind.COURSE,
            )
        }
    }

    data class Preview(val rows: List<Row>, val errors: List<String>, val encoding: String)

    fun parse(bytes: ByteArray, maxWeek: Int, allowedPeriods: Set<Int>): Preview {
        require(bytes.size <= MAX_BYTES) { "文件超过 2 MiB，请拆分后导入" }
        require(maxWeek >= 1) { "当前学期还没有有效周次，请先设置教学周" }
        require(allowedPeriods.isNotEmpty()) { "当前作息没有可用的节次，请先在设置里配置" }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        val encoding = if (utf8.isSuccess) "UTF-8" else "GB18030"
        val text = utf8.getOrElse {
            Charset.forName("GB18030").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.removePrefix("\uFEFF")
        val records = records(text)
        require(records.isNotEmpty()) { "CSV 为空" }
        require(records.size <= 2001) { "最多导入 2000 条时段" }
        val headers = records.first().map { normalizeHeader(it) }
        fun column(vararg names: String): Int? {
            val matches = headers.indices.filter { headers[it] in names }
            require(matches.size <= 1) { "重复列：${names.first()}" }
            return matches.singleOrNull()
        }

        val name = column("课程名称", "课程名", "课程", "科目", "教学班", "教学班名称")
            ?: error("缺少课程名称列（课程名称/课程名/课程/科目）")
        val day = column("星期", "星期几", "周几", "上课星期", "上课日")
        val week = column("周次", "上课周次", "教学周", "周数", "起止周")
            ?: error("缺少周次列（周次/上课周次/教学周）")
        val start = column("开始节次", "起始节次", "起始节", "开始节", "起始节数", "起节")
        val end = column("结束节次", "终止节次", "结束节", "结束节数", "止节")
        val slot = column("节次", "上课节次", "课节", "节数")
        val combinedTime = column("上课时间", "排课时间", "时间")
        val teacher = column("教师", "任课教师", "教师姓名", "授课教师", "老师")
        val room = column("教室", "上课地点", "地点", "教学地点", "教室名称")
        val parity = column("单双周", "周类型", "单双")

        val standardMode = day != null && ((start != null && end != null) || slot != null)
        val combinedMode = combinedTime != null && day == null && slot == null && start == null
        require(standardMode || combinedMode) {
            "缺少星期与节次列；可用“星期 + 开始/结束节次（或节次）”或单列“上课时间”"
        }

        val rows = mutableListOf<Row>()
        val errors = mutableListOf<String>()
        records.drop(1).forEachIndexed { index, fields ->
            if (fields.all { it.isBlank() }) return@forEachIndexed
            val rowNo = index + 2
            runCatching {
                require(fields.size == headers.size) { "列数与表头不一致" }
                fun value(i: Int?) = i?.let { fields[it].trim() }.orEmpty()
                val title = value(name)
                require(title.isNotBlank()) { "课程名称为空" }

                val weeks = parseWeeks(value(week), value(parity), maxWeek)
                if (combinedMode) {
                    val blocks = parseCombinedTime(value(combinedTime), allowedPeriods)
                    require(blocks.isNotEmpty()) { "上课时间无法解析" }
                    blocks.forEach { block ->
                        rows += Row(title, block.first, block.second, block.third, weeks, value(teacher), value(room))
                    }
                } else {
                    val weekday = parseWeekday(value(day))
                        ?: error("星期必须为 1–7 或星期一至星期日")
                    val slots = if (start != null && end != null) {
                        listOf(parseUnit(value(start)) to parseUnit(value(end)))
                    } else {
                        parseSlots(value(slot), allowedPeriods)
                    }
                    require(slots.isNotEmpty()) { "节次为空" }
                    slots.forEach { (first, last) ->
                        require(first <= last) { "开始节次不能晚于结束节次" }
                        require((first..last).all { it in allowedPeriods }) {
                            "当前作息未配置第 $first–$last 节；请先在“节次时间”里补齐，不会丢弃超出节次"
                        }
                        rows += Row(title, weekday, first, last, weeks, value(teacher), value(room))
                    }
                }
            }.onFailure {
                errors += "第 $rowNo 条记录：${it.message ?: "格式无效"}"
            }
        }
        if (rows.isEmpty() && errors.isEmpty()) errors += "没有课程记录"
        require(rows.size <= 2000) { "解析后超过 2000 条时段，请拆分文件后导入" }
        return Preview(rows, errors, encoding)
    }

    private fun normalizeHeader(raw: String): String =
        raw.trim().removePrefix("\uFEFF").replace(" ", "").replace("　", "")
            .replace("（", "(").replace("）", ")")

    private fun parseWeekday(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it.takeIf { n -> n in 1..7 } }
        val key = text.removePrefix("星期").removePrefix("周").removeSuffix("曜日")
        return mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6,
            "日" to 7, "天" to 7, "1" to 1, "2" to 2, "3" to 3, "4" to 4,
            "5" to 5, "6" to 6, "7" to 7,
        )[key]
    }

    private fun parseUnit(raw: String): Int {
        val digits = raw.filter { it.isDigit() }
        require(digits.isNotEmpty()) { "节次必须为数字" }
        return digits.toInt()
    }

    /**
     * 节次单元格支持 `1-2`、`第1-2节`、`1-2,3-4`、`1~2；5-6`。
     * 每个分号/逗号分隔的片段都是一个独立时段，最终拆成多条记录。
     */
    private fun parseSlots(raw: String, allowedPeriods: Set<Int>): List<Pair<Int, Int>> {
        val normalized = raw.replace("（", "(").replace("）", ")")
            .replace("第", "").replace("节", "").replace(" ", "")
            .replace("～", "-").replace("~", "-").replace("至", "-")
            .replace("，", ",").replace("、", ",").replace("；", ",").replace(";", ",")
        return normalized.split(",").filter { it.isNotBlank() }.map { part ->
            val bounds = part.split("-").filter { it.isNotBlank() }
            require(bounds.size in 1..2) { "节次请填写 1-2 或单节" }
            val first = parseUnit(bounds.first())
            val last = parseUnit(bounds.last())
            require(first <= last) { "开始节次不能晚于结束节次" }
            require((first..last).all { it in allowedPeriods }) {
                "当前作息未配置第 $first–$last 节；请先在“节次时间”里补齐，不会丢弃超出节次"
            }
            first to last
        }
    }

    /**
     * 单列“上课时间”的保守格式：`星期一第1-2节`、`周一 1-2`、`星期三 5-6节`；
     * 多个时段用逗号/分号分隔。后续片段可省略星期（沿用上一段），例如
     * `星期一1-2节,3-4节`。周次仍从独立周次列读取，不从这段文本推断整学期。
     */
    private fun parseCombinedTime(raw: String, allowedPeriods: Set<Int>): List<Triple<Int, Int, Int>> {
        val text = raw.replace("（", "(").replace("）", ")")
            .replace("，", ",").replace("、", ",").replace("；", ",").replace(";", ",")
        val blocks = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<Triple<Int, Int, Int>>()
        var lastWeekday: Int? = null
        blocks.forEach { block ->
            val weekdayMatch = Regex("(?:星期|周)([一二三四五六日天])").find(block)
            val weekday = weekdayMatch?.let { parseWeekday(it.value) } ?: lastWeekday
            require(weekday != null) { "时段“$block”没有可识别的星期" }
            lastWeekday = weekday
            val cleaned = if (weekdayMatch != null) block.replace(weekdayMatch.value, "") else block
            val periodMatch = Regex("(\\d+)\\s*(?:-\\s*(\\d+))?").find(cleaned.replace("第", "").replace("节", ""))
            require(periodMatch != null) { "时段“$block”没有可识别的节次" }
            val first = periodMatch.groupValues[1].toInt()
            val last = periodMatch.groupValues[2].ifBlank { first.toString() }.toInt()
            require(first <= last) { "时段“$block”的起止节次颠倒" }
            require((first..last).all { it in allowedPeriods }) {
                "当前作息未配置第 $first–$last 节；请先在“节次时间”里补齐，不会丢弃超出节次"
            }
            result += Triple(weekday, first, last)
        }
        return result
    }

    /**
     * 周次与单双周。
     *
     * 支持 `1-16`、`第1-16周`、`1-16(单)`、`1-8单,10-16双`、`1,3,5`、`2-16周(双)`。
     * 单双周可以写在片段里，也可以写在独立的“单双周”列；片段自己的标记优先。
     * 不接受“整学期/全周”这类需要推断的写法 —— 没有明确数字就不猜。
     */
    private fun parseWeeks(raw: String, parityText: String, maxWeek: Int): List<Int> {
        val normalized = raw.replace("（", "(").replace("）", ")")
            .replace("，", ",").replace("、", ",").replace("；", ",").replace(";", ",")
            .replace("～", "-").replace("~", "-").replace("至", "-")
            .replace(" ", "").replace("　", "")
        require(normalized.isNotBlank()) { "周次为空；空白不代表整学期" }

        val columnParity = when (parityText.trim()) {
            "", "每周", "全部", "全周", "每周都上" -> null
            "单", "单周" -> 1
            "双", "双周" -> 0
            else -> error("无法识别单双周列：$parityText")
        }

        val weeks = normalized.split(",").filter { it.isNotBlank() }.flatMap { segment ->
            var text = segment.removePrefix("第").removeSuffix("周")
            val odd = text.contains("单")
            val even = text.contains("双")
            require(!(odd && even)) { "同一周次片段不能同时标单周和双周" }
            text = text.replace("单", "").replace("双", "").replace("周", "").replace("(", "").replace(")", "")
            require(text.isNotBlank()) { "周次片段无效" }
            val bounds = text.split("-").filter { it.isNotBlank() }
            require(bounds.size in 1..2) { "周次范围无效：$segment" }
            val first = bounds.first().toIntOrNull() ?: error("周次必须为数字：$segment")
            val last = (bounds.last().toIntOrNull() ?: error("周次必须为数字：$segment"))
            require(first <= last) { "周次范围颠倒：$segment" }
            require(first >= 1 && last <= maxWeek) { "周次超出当前学期 1–$maxWeek 周：$segment" }
            val parity = when {
                odd -> 1
                even -> 0
                else -> columnParity
            }
            (first..last).filter { parity == null || it % 2 == parity }
        }.distinct().sorted()
        require(weeks.isNotEmpty()) { "周次为空" }
        return weeks
    }

    private fun records(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        fun cell() {
            row += field.toString()
            field.setLength(0)
            closed = false
        }
        fun line() {
            cell()
            rows += row
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                        closed = true
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        require(field.isEmpty() && !closed) { "CSV 引号位置无效" }
                        quoted = true
                    }
                    ',' -> cell()
                    '\r', '\n' -> {
                        line()
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    }
                    else -> {
                        require(!closed) { "CSV 结束引号后存在额外字符" }
                        field.append(c)
                    }
                }
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) line()
        return rows.filterNot { it.all(String::isBlank) }
    }
}
