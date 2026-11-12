package com.upcpocket.app.data.cardhistory

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.*

data class CardHistoryRow(val id: String, val title: String, val time: String, val amountFen: Long)
data class CardHistoryPage(val rows: List<CardHistoryRow>, val total: Int, val pages: Int)

internal object CardHistoryProtocol {
    const val HOST = "mb-xyk.upc.edu.cn"
    const val BASE = "https://$HOST"
    const val ENTRY = "$BASE/berserker-auth/cas/redirect/wislearn?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit"

    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.rawUserInfo != null) return@runCatching false
        when {
            uri.scheme == "https" && uri.port in setOf(-1, 443) -> uri.host in setOf(HOST, "cas.upc.edu.cn", "i.upc.edu.cn")
            else -> false
        }
    }.getOrDefault(false)

    fun tokenPage(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == HOST && uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
            (uri.path == "/plat" || uri.path.startsWith("/plat/") || uri.path.startsWith("/campus-card/"))
    }.getOrDefault(false)

    fun data(body: String): JsonObject {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IOException("N-FORMAT：学校未返回有效数据")
        if ((root["code"] as? JsonPrimitive)?.intOrNull != 200) throw IOException("N-RESULT：学校未确认查询成功")
        return root["data"] as? JsonObject ?: throw IOException("N-DATA：学校数据结构待核对")
    }

    fun verifyIdentity(body: String, expected: String) {
        require(expected.matches(Regex("[0-9]{8,16}")))
        val info = data(body)
        val ids = mutableSetOf<String>()
        fun visit(value: JsonObject, depth: Int) {
            if (depth > 3) return
            for ((key, item) in value) {
                if (key in setOf("sno", "username", "userName", "studentId", "studentNo")) {
                    (item as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[0-9]{8,16}")) }?.let(ids::add)
                }
                if (item is JsonObject) visit(item, depth + 1)
            }
        }
        visit(info, 0)
        if (ids.isEmpty()) throw IOException("N-ID-FORMAT：尚不能核对登录学号，请反馈此错误码")
        if (ids != setOf(expected)) throw IOException("N-ID：登录账号与饭卡页学号不一致，请核对后重试")
    }

    fun parseRows(body: String): CardHistoryPage {
        val info = data(body)
        val records = info["records"] as? JsonArray ?: throw IOException("N-ROWS：流水结构待核对")
        val total = (info["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: throw IOException("N-PAGE：分页结构待核对")
        val pages = (info["pages"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: throw IOException("N-PAGE：分页结构待核对")
        val rows = records.map { value ->
            val row = value as? JsonObject ?: throw IOException("N-ROW：流水格式待核对")
            fun field(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val amount = field("tranamt").toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IOException("N-MONEY：金额单位待核对")
            val type = field("typeFrom").takeIf { it.isNotBlank() }
                ?: throw IOException("N-TYPE：收支方向待核对")
            val id = field("orderId").takeIf { it.isNotBlank() } ?: throw IOException("N-ORDER：交易标识缺失")
            val time = field("jndatetimeStr").ifBlank { field("effectdateStr") }
            if (time.isBlank()) throw IOException("N-TIME：交易时间缺失")
            CardHistoryRow(id, field("resume").ifBlank { "校园卡交易" }, time, if (type == "1") amount else -amount)
        }
        if (rows.size > total || rows.map { it.id }.distinct().size != rows.size || (rows.isNotEmpty() && pages == 0))
            throw IOException("N-PAGE：流水分页数据不一致")
        return CardHistoryPage(rows, total, pages)
    }
}
