package com.upcpocket.app.data.power

import java.io.IOException
import java.math.BigDecimal

/**
 * 唐岛湾宿舍电费查询（`dfcz.upc.edu.cn`，ASP.NET WebForms）的页面契约。
 *
 * 2026-10-28 匿名核验得到的真实请求链（只读）：
 *
 *  1. `GET /ShiYou/Default.aspx`：必须带 `code` 查询参数，否则 302 回微信 OAuth；
 *     服务端并不校验它（`code=0`、`code=app` 都能拿到查询表单），表单里带
 *     `__VIEWSTATE` / `__VIEWSTATEGENERATOR` / `__EVENTVALIDATION` 与楼号下拉。
 *  2. `POST /ShiYou/Default.aspx`，`lou=<楼号>`、`yonghu=<房间号>`、`chaxun=查询`
 *     ＋上面三个隐藏字段：返回同一页面。
 *  3. 命中唯一房间时，页面里出现
 *     `<table id="yongdianGrid">…当前剩余电量:</span></td><td>17.34度</td>…`；
 *     命中多个房间时改为 `yonghuGridView` 候选表（系统编号／楼号／房间号）＋“选择”按钮。
 *
 * 这里只解析**电量（度）**与候选房间，不碰“充值”“缴费记录”，也不复用任何写操作。
 * 学校页面自己的字段叫“当前剩余电量”，所以界面必须写“剩余电量”，不能改写成金额余额。
 */
object ElectricityProtocol {

    const val HOST = "http://dfcz.upc.edu.cn"
    const val PAGE_PATH = "/ShiYou/Default.aspx"

    /** 服务端不校验该参数，但缺省时会 302 到微信 OAuth；用一个固定占位值走正常页面。 */
    const val NEUTRAL_CODE = "0"

    data class Form(
        val viewState: String,
        val viewStateGenerator: String,
        val eventValidation: String,
        val buildings: List<String>,
    )

    data class Candidate(
        val systemNo: String,
        val building: String,
        val room: String,
    )

    data class Reading(
        val building: String,
        val room: String,
        val systemNo: String,
        val kwh: BigDecimal,
    )

    sealed interface Outcome {
        data class Success(val reading: Reading) : Outcome

        /** 同一房间号命中多个系统编号，需要用户选一个；不是错误。 */
        data class Ambiguous(val candidates: List<Candidate>) : Outcome
    }

    class PageException(message: String, val kind: Kind) : IOException(message) {
        enum class Kind {
            /** 页面要求微信 OAuth / 会话被拒。 */
            AUTH_REQUIRED,

            /** 页面本身结构变了（找不到表单）。 */
            FORMAT_CHANGED,

            /** 正常页面，但该条件没有房间。 */
            ROOM_NOT_FOUND,
        }
    }

    fun parseForm(html: String): Form {
        if (html.contains("open.weixin.qq.com") && !html.contains("id=\"form1\"")) {
            throw PageException("学校电费页面要求先完成微信授权，当前会话不可用", PageException.Kind.AUTH_REQUIRED)
        }
        val viewState = hidden(html, "__VIEWSTATE")
        val generator = hidden(html, "__VIEWSTATEGENERATOR") ?: ""
        val validation = hidden(html, "__EVENTVALIDATION")
        if (viewState == null || validation == null || !html.contains("id=\"form1\"")) {
            throw PageException("学校电费页面结构已变化，暂时无法查询", PageException.Kind.FORMAT_CHANGED)
        }
        val buildings = Regex("<option\\s+value=\"([^\"]+)\"").findAll(html)
            .map { decodeEntities(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (buildings.isEmpty()) {
            throw PageException("学校电费页面没有返回楼号列表，稍后再试", PageException.Kind.FORMAT_CHANGED)
        }
        return Form(viewState, generator, validation, buildings)
    }

    /**
     * 解析查询结果。
     *
     * ⚠️ 2026-10-28 真机实测发现的坑：学校按**模糊匹配**选房。输入 `101` 时，页面会
     * 同时给出候选表（`1101`、`商铺101校园文化`）**和**第一间房的电量。只看
     * `yongdianGrid` 就会把 `1101` 的电量当成 `101` 的结果显示给用户。
     *
     * 所以必须拿“用户输入的房间号”做精确比对：
     *  - 候选表里只有一条且与输入完全一致 → 这才是用户要的房间；
     *  - 其余情况一律回到 [Outcome.Ambiguous]，让用户自己选，绝不代猜。
     */
    fun parseResult(html: String, requestedRoom: String): Outcome {
        val requested = requestedRoom.trim()
        val grid = parseGrid(html)
        val candidates = parseCandidates(html)

        if (candidates.isNotEmpty()) {
            val exact = candidates.filter { it.room.trim() == requested }
            val plotted = grid?.reading
            val consistent = plotted == null || plotted.room.trim().isEmpty() ||
                plotted.room.trim() == requested
            if (exact.size == 1 && consistent) {
                val hit = exact.single()
                val kwh = plotted?.kwh ?: throw notFound()
                return Outcome.Success(
                    Reading(
                        building = hit.building,
                        room = hit.room,
                        systemNo = hit.systemNo,
                        kwh = kwh,
                    ),
                )
            }
            // 模糊命中（输入 101、学校给出 1101）必须交给用户确认。
            return Outcome.Ambiguous(candidates)
        }

        if (grid != null) {
            val reading = grid.reading
            // 没有候选表却给了别的房间号，说明学校换了另一间：同样不代猜。
            if (reading.room.isNotBlank() && reading.room.trim() != requested) {
                return Outcome.Ambiguous(
                    listOf(
                        Candidate(
                            systemNo = reading.systemNo,
                            building = reading.building,
                            room = reading.room,
                        ),
                    ),
                )
            }
            return Outcome.Success(
                Reading(
                    building = reading.building,
                    room = reading.room.ifBlank { requested },
                    systemNo = reading.systemNo,
                    kwh = reading.kwh,
                ),
            )
        }

        if (html.contains("楼列表为空")) {
            throw PageException("学校电费页面没有可用楼号，请稍后再试", PageException.Kind.FORMAT_CHANGED)
        }
        throw notFound()
    }

    private fun notFound() =
        PageException("没有找到该房间，请核对楼号与房间号", PageException.Kind.ROOM_NOT_FOUND)

    private data class Grid(val reading: Reading)

    private fun parseGrid(html: String): Grid? {
        val table = tableById(html, "yongdianGrid") ?: return null
        val cells = cells(table)
        val labelIndex = cells.indexOfFirst { it.contains("当前剩余电量") }
        if (labelIndex < 0 || labelIndex + 1 >= cells.size) {
            throw PageException("学校电费页面已变化，未能读到剩余电量", PageException.Kind.FORMAT_CHANGED)
        }
        val raw = cells[labelIndex + 1]
        val hidden = cells.drop(labelIndex + 2).filter { it.isNotBlank() }
        return Grid(
            Reading(
                building = "",
                room = hidden.getOrNull(1).orEmpty(),
                systemNo = hidden.getOrNull(0).orEmpty(),
                kwh = parseKwh(raw),
            ),
        )
    }

    fun parseCandidates(html: String): List<Candidate> {
        val table = tableById(html, "yonghuGridView") ?: return emptyList()
        val rows = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(table).map { it.groupValues[1] }.toList()
        val result = ArrayList<Candidate>()
        rows.forEach { row ->
            val cells = cells(row)
            if (cells.size < 3) return@forEach
            if (cells.any { it.contains("系统编号") }) return@forEach
            val systemNo = cells[0].trim()
            val building = cells[1].trim()
            val room = cells[2].trim()
            if (systemNo.isEmpty() || room.isEmpty()) return@forEach
            result += Candidate(systemNo = systemNo, building = building, room = room)
        }
        return result
    }

    fun parseKwh(raw: String): BigDecimal {
        val text = decodeEntities(raw).replace("\u00a0", " ").trim()
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*度$").find(text)
            ?: throw PageException("剩余电量字段格式已变化：$text", PageException.Kind.FORMAT_CHANGED)
        return runCatching { BigDecimal(match.groupValues[1]) }
            .getOrElse { throw PageException("剩余电量不是有效数字", PageException.Kind.FORMAT_CHANGED) }
    }

    fun formBody(form: Form, building: String, room: String): String = listOf(
        "__VIEWSTATE" to form.viewState,
        "__VIEWSTATEGENERATOR" to form.viewStateGenerator,
        "__EVENTVALIDATION" to form.eventValidation,
        "lou" to building,
        "yonghu" to room,
        "chaxun" to "查询",
    ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun hidden(html: String, name: String): String? {
        val match = Regex(
            "name=\"" + Regex.escape(name) + "\"[^>]*value=\"([^\"]*)\"",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null
        return decodeEntities(match.groupValues[1])
    }

    private fun tableById(html: String, id: String): String? {
        val open = Regex(
            "<table[^>]*id=\"" + Regex.escape(id) + "\"[^>]*>",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null
        val end = html.indexOf("</table>", open.range.last)
        if (end < 0) return null
        return html.substring(open.range.first, end)
    }

    /** 只取 `<td>` 单元格；表格里的 `&nbsp;` 归一到空串，方便按位置读隐藏列。 */
    private fun cells(fragment: String): List<String> =
        Regex("(?is)<td[^>]*>(.*?)</td>").findAll(fragment)
            .map { stripTags(it.groupValues[1]) }
            .toList()

    private fun stripTags(raw: String): String =
        decodeEntities(raw.replace(Regex("(?is)<[^>]+>"), " "))
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    internal fun decodeEntities(raw: String): String = raw
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
