package com.upcpocket.app.data.school

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 强智教务（`jwxt.upc.edu.cn/jsxsd`）当前页课表解析。
 *
 * 只在用户明确点击「读取当前页」后，由已登录的 WebView 读取**当前显示**的课表表格
 * （主文档或同源 iframe），不请求任何固定 URL、不跳转、不猜接口。
 *
 * 解析原则：
 *  - 学期、节次、周次必须是页面里明确写出的；缺字段、非法值、无法归属的片段一律报错，
 *    不默认、不截断、不丢弃半门课；
 *  - 教师/教室只在页面带「教师：」「地点：」这类标签时采用，不按行序猜；
 *  - 一处解析失败就整批阻止入库，并指出位置（星期/节次/课程名）。
 */
internal object JwxtScheduleProtocol {

    const val BASE = "https://jwxt.upc.edu.cn"
    const val ENTRY = "$BASE/jsxsd/"
    const val MAX_PAYLOAD_CHARS = 512 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Payload(
        val ok: Boolean = false,
        val error: String? = null,
        val detail: String? = null,
        val term: TermOption? = null,
        val weekSelectorMax: Int = 0,
        val frames: List<FrameInfo> = emptyList(),
        val cells: List<RawCell> = emptyList(),
        val tableCount: Int = 0,
        val rejectedFrames: Int = 0,
    )

    @Serializable
    data class TermOption(val value: String = "", val label: String = "")

    @Serializable
    data class FrameInfo(val kind: String = "main", val sameOrigin: Boolean = true)

    @Serializable
    data class RawCell(
        val day: Int = 0,
        val startUnit: Int = 0,
        val endUnit: Int = 0,
        val unitText: String = "",
        val rowSpan: Int = 1,
        val colSpan: Int = 1,
        val courses: List<RawCourse> = emptyList(),
    )

    @Serializable
    data class RawCourse(val lines: List<String> = emptyList())

    data class Parsed(
        /** 页面明确给出的合法学期；为空表示需要用户确认。 */
        val suggestedTerm: SchoolTerm?,
        val termLabel: String,
        val totalWeeks: Int,
        val blocks: List<List<SchoolClassBlock>>,
        val courses: List<CourseView>,
        val rejectedFrames: Int,
    )

    data class CourseView(
        val name: String,
        val weeksLabel: String,
        val teacher: String?,
        val room: String?,
        val slots: List<Slot>,
    )

    data class Slot(val day: Int, val startUnit: Int, val endUnit: Int)

    /** 解析 WebView 回传的 JSON；任何结构问题都抛出可显示的格式错误。 */
    fun parse(body: String): Parsed {
        val payload = try {
            json.decodeFromString(Payload.serializer(), body)
        } catch (e: Exception) {
            throw SchoolScheduleFormatException("教务页面返回的数据无法解析，请重新读取")
        }
        if (!payload.ok) throw failure(payload)
        if (payload.frames.any { !it.sameOrigin }) {
            throw SchoolScheduleFormatException("检测到非同源框架中的课表，已停止读取，避免读到不可信内容")
        }
        if (payload.cells.isEmpty()) {
            throw SchoolScheduleFormatException("当前页面没有识别到课表内容，请打开「学期理论课表」后再读取")
        }

        val (totalWeeksFromSelector, selectorPresent) =
            payload.weekSelectorMax.takeIf { it in 1..MAX_WEEK }?.let { it to true } ?: (0 to false)

        val weekSets = ArrayList<Pair<RawCell, Pair<RawCourse, Set<Int>>>>()
        payload.cells.forEach { cell ->
            val where = location(cell)
            if (cell.colSpan > 1) {
                throw SchoolScheduleFormatException("$where 存在跨列合并单元格，无法确定属于哪一天，已停止读取")
            }
            if (cell.day !in 1..7) {
                throw SchoolScheduleFormatException("$where 的星期无法识别（${cell.day}），已停止读取")
            }
            if (cell.unitText.isBlank()) {
                throw SchoolScheduleFormatException("$where 缺少明确的节次信息，已停止读取")
            }
            if (cell.startUnit < 1 || cell.endUnit < cell.startUnit) {
                throw SchoolScheduleFormatException("$where 的节次无法识别（${cell.unitText}），已停止读取")
            }
            cell.courses.forEach { original ->
                val course = normalizeMetadata(original, cell, where)
                val name = course.lines.firstOrNull()?.trim().orEmpty()
                val courseWhere = if (name.isBlank()) where else "$where《$name》"
                if (name.isBlank() || name == "无" || name == "本节无课") {
                    throw SchoolScheduleFormatException("$courseWhere 的课程名为空或为占位文字，已停止读取")
                }
                val weeks = parseWeeks(course.lines, courseWhere)
                weekSets += cell to (course to weeks)
            }
        }

        val maxWeek = weekSets.flatMap { it.second.second }.maxOrNull() ?: 0
        if (maxWeek <= 0) throw SchoolScheduleFormatException("当前课表没有可识别的上课周次，已停止读取")
        val totalWeeks = if (selectorPresent) totalWeeksFromSelector else maxWeek
        if (maxWeek > totalWeeks) {
            throw SchoolScheduleFormatException(
                "课表出现第 $maxWeek 周，超出页面周次范围（1–$totalWeeks），已停止读取；请核对页面后再试",
            )
        }

        val byWeek = MutableList(totalWeeks) { mutableListOf<SchoolClassBlock>() }
        val views = LinkedHashMap<String, MutableCourse>()
        weekSets.forEach { (cell, pair) ->
            val (course, weeks) = pair
            val parsed = parseCourse(course.lines)
            weeks.forEach { week ->
                byWeek[week - 1] += SchoolClassBlock(
                    name = parsed.name,
                    weekday = cell.day,
                    startUnit = cell.startUnit,
                    endUnit = cell.endUnit,
                    teacher = parsed.teacher,
                    room = parsed.room,
                    courseId = null,
                )
            }
            val key = listOf(parsed.name, parsed.weeksLabel, parsed.teacher.orEmpty(), parsed.room.orEmpty()).joinToString("|")
            val view = views.getOrPut(key) { MutableCourse(parsed.name, parsed.weeksLabel, parsed.teacher, parsed.room) }
            val slot = Slot(cell.day, cell.startUnit, cell.endUnit)
            if (slot !in view.slots) view.slots += slot
        }

        val term = payload.term?.let { option ->
            val value = option.value.trim()
            if (value.isEmpty()) null else {
                // 页面给了学期就必须是合法完整学年；不认识就停，不能默认第 1 学期。
                val parsed = parseTermValue(value)
                    ?: throw SchoolScheduleFormatException(
                        "教务页面的学期「${value}」无法识别，请确认学期后再读取（不会自动改成第 1 学期）",
                    )
                SchoolTerm(parsed.first, parsed.second, totalWeeks, option.label.ifBlank { "$value 学期" })
            }
        }
        return Parsed(
            suggestedTerm = term,
            termLabel = payload.term?.label?.trim().orEmpty(),
            totalWeeks = totalWeeks,
            blocks = byWeek,
            courses = views.values.map { it.freeze() },
            rejectedFrames = payload.rejectedFrames,
        )
    }

    /** 用户在预览里手动确认的学期文本（`2026-2027-1`）。非法就返回 null，不默认。 */
    fun parseTermInput(value: String): Pair<String, String>? = parseTermValue(value.trim())

    /** 用户确认的第 1 周周一；不存在或不是真实日期就返回 null，不用今天代替。 */
    fun parseStartDate(value: String): java.time.LocalDate? =
        runCatching { java.time.LocalDate.parse(value.trim()) }.getOrNull()

    /** 预览框写的是「周一」，就必须真的是周一；周二/周日不能混进来当第 1 周。 */
    fun isMonday(value: String): Boolean =
        parseStartDate(value)?.dayOfWeek == java.time.DayOfWeek.MONDAY

    /**
     * `evaluateJavascript` 的 ValueCallback 拿到的是脚本返回值的 JSON 字面量。
     * 只接受字符串（我们脚本 return 的是 JSON.stringify 的结果）；null/对象/畸形一律视为没读到。
     */
    fun decodeEvaluateResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    internal fun parseTermValue(value: String): Pair<String, String>? {
        val m = TERM_FORMAT.matchEntire(value) ?: return null
        val first = m.groupValues[1].toIntOrNull() ?: return null
        val second = m.groupValues[2].toIntOrNull() ?: return null
        if (second != first + 1) return null
        return m.groupValues[1] to m.groupValues[3]
    }

    private data class ParsedCourse(val name: String, val teacher: String?, val room: String?, val weeksLabel: String)

    private class MutableCourse(
        val name: String,
        val weeksLabel: String,
        val teacher: String?,
        val room: String?,
    ) {
        val slots = mutableListOf<Slot>()
        fun freeze() = CourseView(name, weeksLabel, teacher, room, slots.toList())
    }

    private fun normalizeMetadata(course: RawCourse, cell: RawCell, where: String): RawCourse =
        RawCourse(course.lines.map { line ->
            val match = Regex("""^(.+?)[(（]周[)）]\s*\[([0-9]{1,2})(?:-([0-9]{1,2}))?节]$""").matchEntire(line.trim())
            if (match == null) line else {
                val start = match.groupValues[2].toInt()
                val end = match.groupValues[3].toIntOrNull() ?: start
                if (start != cell.startUnit || end != cell.endUnit) {
                    throw SchoolScheduleFormatException("$where 的课程节次与表格节次不一致，已停止读取")
                }
                match.groupValues[1] + "周"
            }
        })

    private fun parseWeeks(lines: List<String>, where: String): Set<Int> {
        val rest = lines.map { it.trim() }.filter { it.isNotEmpty() }.drop(1)
        val candidates = rest.filter { weekSegments(it) != null }
        if (candidates.isEmpty()) throw SchoolScheduleFormatException("$where 没有周次信息，已停止读取")
        if (candidates.size > 1) throw SchoolScheduleFormatException("$where 有多行周次信息，无法确定，已停止读取")
        val text = candidates.single()
        val weeks = weekSegments(text) ?: throw SchoolScheduleFormatException("$where 的周次无法识别，已停止读取")
        if (weeks.isEmpty()) throw SchoolScheduleFormatException("$where 的周次为空，已停止读取")
        return weeks
    }

    /**
     * 严格解析 `1-8周(单),10-16周(双)`、`1-16周`、`第1,3,5周`。
     * 每段独立判定单双；倒序、越界、夹带其它文字都返回 null（由调用方报错）。
     */
    internal fun weekSegments(text: String): Set<Int>? {
        val cleaned = text.trim().replace('（', '(').replace('）', ')')
        if (cleaned.isEmpty()) return null
        val pieces = cleaned.split(',', '，', '、', ';', '；').map { it.trim() }
        if (pieces.any { it.isEmpty() }) return null
        val result = sortedSetOf<Int>()
        var index = 0
        while (index < pieces.size) {
            var group = pieces[index]
            // 「1,3,5周」只在最后一段带“周”，把前面没带的并进来；并完仍没有就失败。
            while (!group.contains('周') && index + 1 < pieces.size) {
                index++
                group += "," + pieces[index]
            }
            val m = WEEK_GROUP.matchEntire(group) ?: return null
            val parity = m.groupValues[2]
            val segmentWeeks = sortedSetOf<Int>()
            m.groupValues[1].split(',', '，', '、').forEach { part ->
                val range = NUMBER_RANGE.matchEntire(part.trim()) ?: return null
                val first = range.groupValues[1].toIntOrNull() ?: return null
                val last = range.groupValues[2].toIntOrNull() ?: first
                if (last < first) return null
                for (w in first..last) {
                    if (w < 1 || w > MAX_WEEK) return null
                    segmentWeeks += w
                }
            }
            if (segmentWeeks.isEmpty()) return null
            // 单双周只作用于本段，不能拿整段文本的修饰词去过滤已累计的其它段。
            val filtered = when (parity) {
                "单" -> segmentWeeks.filter { it % 2 == 1 }
                "双" -> segmentWeeks.filter { it % 2 == 0 }
                else -> segmentWeeks
            }
            result.addAll(filtered)
            index++
        }
        return result
    }

    private fun parseCourse(lines: List<String>): ParsedCourse {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val name = clean.first()
        val rest = clean.drop(1)
        var teacher: String? = null
        var room: String? = null
        rest.forEach { line ->
            when {
                TEACHER_LABEL.containsMatchIn(line) -> teacher = TEACHER_LABEL.replace(line, "").trim().ifBlank { null }
                ROOM_LABEL.containsMatchIn(line) -> room = ROOM_LABEL.replace(line, "").trim().ifBlank { null }
            }
        }
        val weekText = rest.firstOrNull { weekSegments(it) != null }.orEmpty()
        val weeks = weekSegments(weekText) ?: emptySet()
        return ParsedCourse(name, teacher, room, weeksLabel(weeks))
    }

    internal fun weeksLabel(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        if (sorted.size >= 2) {
            val step = sorted[1] - sorted[0]
            if (step == 2 && sorted.indices.all { sorted[it] == sorted[0] + it * 2 }) {
                return "${sorted.first()}-${sorted.last()}周(${if (sorted.first() % 2 == 1) "单" else "双"})"
            }
        }
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        sorted.drop(1).forEach { week ->
            if (week == prev + 1) {
                prev = week
            } else {
                parts += if (start == prev) "$start" else "$start-$prev"
                start = week
                prev = week
            }
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(",") + "周"
    }

    private fun location(cell: RawCell): String {
        val day = if (cell.day in 1..7) "星期${"一二三四五六日"[cell.day - 1]}" else "未知星期"
        val unit = cell.unitText.take(20).ifBlank { "${cell.startUnit}-${cell.endUnit}" }
        return "$day 节次「$unit」"
    }

    private fun failure(payload: Payload): SchoolScheduleFormatException = when (payload.error) {
        "NOT_JWXT" -> SchoolScheduleFormatException("当前页面不是教务系统，请先完成登录")
        "NO_TABLE" -> SchoolScheduleFormatException(
            "当前页面没有找到课表表格，请先在教务里打开「学期理论课表」再读取",
        )
        "MULTIPLE_TABLES" -> SchoolScheduleFormatException("当前页面存在多个课表表格，无法确定使用哪一个，已停止读取")
        "TOO_LARGE" -> SchoolScheduleFormatException("课表表格过大，已停止读取")
        else -> SchoolScheduleFormatException(
            "读取教务课表失败" + (payload.detail?.take(60)?.let { "（$it）" } ?: "") + "，请重试",
        )
    }

    private const val MAX_WEEK = 30
    private val TERM_FORMAT = Regex("(\\d{4})-(\\d{4})-([123])")
    private val WEEK_GROUP = Regex(
        "^第?\\s*([0-9]{1,2}(?:\\s*[-~—–至]\\s*[0-9]{1,2})?" +
            "(?:\\s*[,，、]\\s*[0-9]{1,2}(?:\\s*[-~—–至]\\s*[0-9]{1,2})?)*)" +
            "\\s*周\\s*(?:\\(\\s*([单双])\\s*\\))?\\s*$",
    )
    private val NUMBER_RANGE = Regex("^([0-9]{1,2})(?:\\s*[-~—–至]\\s*([0-9]{1,2}))?$")
    private val TEACHER_LABEL = Regex("^(?:任课教师|教师|老师)\\s*[:：]\\s*")
    private val ROOM_LABEL = Regex("^(?:上课地点|地点|教室|上课教室)\\s*[:：]\\s*")

    /**
     * 在当前页面执行：只读 DOM，不 fetch、不跳转。
     * 仅采用与主文档同源的 iframe；跨源框架直接跳过并计数。
     */
    fun extractionScript(): String = EXTRACTION_TEMPLATE

    private val EXTRACTION_TEMPLATE = """
(function () {
  function payload(o) { return JSON.stringify(o); }
  function fail(code, detail) { return payload({ ok: false, error: code, detail: detail || '' }); }
  try {
    if (location.hostname !== 'jwxt.upc.edu.cn') { return fail('NOT_JWXT'); }

    function textOf(el) {
      var c = el.cloneNode(true);
      var labels = c.querySelectorAll('font[title],span[title]');
      for (var li = 0; li < labels.length; li++) {
        var label = labels[li].getAttribute('title') || '';
        if (/^(教师|任课教师|老师|教室|地点|上课地点|上课教室)$/.test(label)) {
          labels[li].textContent = label + '：' + labels[li].textContent;
        }
      }
      var br = c.getElementsByTagName('br');
      for (var b = br.length - 1; b >= 0; b--) {
        br[b].parentNode.replaceChild(el.ownerDocument.createTextNode('\n'), br[b]);
      }
      return (c.textContent || '').replace(/\u00a0/g, ' ').trim();
    }

    function scheduleTable(doc) {
      var tables = doc.getElementsByTagName('table');
      var hits = [];
      for (var i = 0; i < tables.length; i++) {
        var t = tables[i];
        var txt = t.textContent || '';
        if (txt.indexOf('星期一') >= 0 && (txt.indexOf('星期二') >= 0 || txt.indexOf('星期三') >= 0)) {
          hits.push(t);
        }
      }
      if (hits.length === 1) { return hits[0]; }
      if (hits.length > 1) { return { multiple: hits.length }; }
      return null;
    }

    var docs = [{ kind: 'main', doc: document }];
    var rejected = 0;
    var frameEls = document.querySelectorAll('iframe,frame');
    for (var f = 0; f < frameEls.length; f++) {
      try {
        var w = frameEls[f].contentWindow;
        var d = frameEls[f].contentDocument;
        if (!d) {
          // 读不到文档：按声明地址判断是否跨源，只用于提示；未加载的同源框架不计数。
          try {
            var srcUrl = new URL(frameEls[f].src || '', location.href);
            if (srcUrl.origin !== location.origin) { rejected++; }
          } catch (e2) {}
          continue;
        }
        if (w && w.location.origin !== location.origin) { rejected++; continue; }
        docs.push({ kind: 'iframe', doc: d });
      } catch (e) { rejected++; }
    }

    var found = null, tableCount = 0, candidates = 0;
    for (var k = 0; k < docs.length; k++) {
      var result = scheduleTable(docs[k].doc);
      tableCount += docs[k].doc.getElementsByTagName('table').length;
      if (result && result.multiple) { return fail('MULTIPLE_TABLES'); }
      if (result) { candidates++; found = result; }
    }
    if (candidates > 1) { return fail('MULTIPLE_TABLES'); }
    if (!found) { return fail('NO_TABLE', 'tables=' + tableCount + ', frames=' + docs.length); }

    var owner = null;
    for (var d2 = 0; d2 < docs.length; d2++) {
      if (docs[d2].doc.contains(found)) { owner = docs[d2].doc; break; }
    }
    if (!owner) { owner = document; }

    var termEl = owner.querySelector('select[name="xnxq01id"]') || owner.querySelector('#xnxq01id');
    var term = null;
    if (termEl && termEl.options) {
      for (var oi = 0; oi < termEl.options.length; oi++) {
        var op = termEl.options[oi];
        if (op.selected) { term = { value: op.value || '', label: (op.textContent || '').replace(/\s+/g, ' ').trim() }; }
      }
      if (!term && termEl.options.length === 1) {
        var only = termEl.options[0];
        term = { value: only.value || '', label: (only.textContent || '').replace(/\s+/g, ' ').trim() };
      }
    }

    var zc = owner.querySelector('select[name="zc"]') || owner.querySelector('#zc');
    var maxWeek = 0;
    if (zc && zc.options) {
      for (var zi = 0; zi < zc.options.length; zi++) {
        var zv = parseInt(zc.options[zi].value, 10);
        if (zv > maxWeek) { maxWeek = zv; }
      }
    }

    var rows = found.rows, occ = [], placed = [];
    function put(r, c, obj) { occ[r] = occ[r] || []; occ[r][c] = obj; }
    for (var r = 0; r < rows.length; r++) {
      var row = rows[r], col = 0;
      for (var ci = 0; ci < row.cells.length; ci++) {
        var td = row.cells[ci];
        while (occ[r] && occ[r][col]) { col++; }
        var rs = td.rowSpan || 1, cs = td.colSpan || 1;
        var cell = { row: r, col: col, rowSpan: rs, colSpan: cs, text: textOf(td) };
        for (var rr = 0; rr < rs; rr++) {
          for (var cc = 0; cc < cs; cc++) { put(r + rr, col + cc, cell); }
        }
        placed.push(cell);
        col += cs;
      }
    }

    function weekdayOf(text) {
      var t = (text || '').replace(/\s+/g, '');
      var m = /^(?:星期|周)([一二三四五六日天])$/.exec(t);
      if (!m) { return 0; }
      var map = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
      return map[m[1]] || 0;
    }

    // 先找“星期几”出现最多的那一行做表头，再从这一行取星期列；
    // 课程名里也可能出现“周一/周日”，不能让它参与列映射。
    var rowHits = {}, headerRow = -1, bestHits = 0;
    for (var p = 0; p < placed.length; p++) {
      if (!weekdayOf(placed[p].text)) { continue; }
      var hits = (rowHits[placed[p].row] || 0) + 1;
      rowHits[placed[p].row] = hits;
      if (hits > bestHits) { bestHits = hits; headerRow = placed[p].row; }
    }
    if (headerRow < 0 || bestHits < 2) { return fail('NO_HEADER', '没有找到星期表头'); }

    var dayCols = {};
    for (var hp = 0; hp < placed.length; hp++) {
      if (placed[hp].row !== headerRow) { continue; }
      var hd = weekdayOf(placed[hp].text);
      if (hd && dayCols[hd] === undefined) { dayCols[hd] = placed[hp].col; }
    }
    if (dayCols[1] === undefined) {
      // 没有“星期一”就无法可靠定位节次列与第一天。
      return fail('NO_HEADER', '表头里没有星期一');
    }
    var firstDayCol = 99;
    for (var dc in dayCols) { if (dayCols[dc] < firstDayCol) { firstDayCol = dayCols[dc]; } }
    // 节次列固定取“星期一”左边紧邻的那一列；上午/下午等分组列在它更左边，不能取第 0 列。
    var unitCol = firstDayCol - 1;
    function unitCellAt(row) {
      if (unitCol < 0 || unitCol >= firstDayCol) { return null; }
      return occ[row] ? occ[row][unitCol] : null;
    }

    function parseUnitText(txt) {
      var m = /第?\s*(\d{1,2})\s*[-~—–至]\s*(\d{1,2})\s*节?/.exec(txt || '');
      if (m) { return [parseInt(m[1], 10), parseInt(m[2], 10)]; }
      var s = /^\s*第?\s*(\d{1,2})\s*节?\s*$/.exec(txt || '');
      if (s) { var a = parseInt(s[1], 10); return [a, a]; }
      return null;
    }

    // 课程单元格跨多行时，节次必须覆盖它实际占用的所有行；不连续/有行没写节次就判为未知。
    function unitsForRows(startRow, span) {
      var ranges = [], lastText = null, lastCell = null;
      for (var rr = startRow; rr < startRow + span; rr++) {
        var uc = unitCellAt(rr);
        var txt = uc ? uc.text.replace(/\s+/g, ' ').trim() : '';
        if (txt === lastText && uc === lastCell) { continue; }
        if (!txt) { return null; }
        var one = parseUnitText(txt);
        if (!one) { return null; }
        // 一个“节次”格横向合并了多行、却只写单个数字：无法判断它代表几节。
        if (one[0] === one[1] && uc && (uc.rowSpan || 1) > 1) { return null; }
        ranges.push(one);
        lastText = txt;
        lastCell = uc;
      }
      if (!ranges.length) { return null; }
      for (var i = 1; i < ranges.length; i++) {
        if (ranges[i][0] !== ranges[i - 1][1] + 1) { return null; }
      }
      return [ranges[0][0], ranges[ranges.length - 1][1]];
    }

    var cells = [];
    for (var q = 0; q < placed.length; q++) {
      var cell2 = placed[q];
      if (cell2.row <= headerRow || !cell2.text) { continue; }
      var day = 0;
      for (var key in dayCols) {
        if (dayCols[key] === cell2.col) { day = parseInt(key, 10); }
      }
      if (!day) { continue; }
      if (cell2.colSpan > 1) {
        cells.push({ day: day, startUnit: 0, endUnit: 0, unitText: '', rowSpan: cell2.rowSpan, colSpan: cell2.colSpan, courses: [{ lines: [cell2.text] }] });
        continue;
      }
      var unitTop = unitCellAt(cell2.row);
      var unitText = unitTop ? unitTop.text.replace(/\s+/g, ' ').trim() : '';
      var units = unitsForRows(cell2.row, cell2.rowSpan || 1);
      var parts = cell2.text.split(/-{5,}/);
      var courses = [];
      for (var z = 0; z < parts.length; z++) {
        var lines = parts[z].split('\n').map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });
        if (lines.length) { courses.push({ lines: lines }); }
      }
      if (!courses.length) { continue; }
      cells.push({
        day: day,
        startUnit: units ? units[0] : 0,
        endUnit: units ? units[1] : 0,
        unitText: unitText,
        rowSpan: cell2.rowSpan,
        colSpan: cell2.colSpan,
        courses: courses
      });
    }

    return payload({
      ok: true,
      term: term,
      weekSelectorMax: maxWeek,
      frames: docs.map(function (x) { return { kind: x.kind, sameOrigin: true }; }),
      rejectedFrames: rejected,
      tableCount: tableCount,
      cells: cells
    });
  } catch (e) { return fail('SCRIPT_ERROR', e && e.message ? String(e.message).slice(0, 80) : ''); }
})()
""".trimIndent()
}
