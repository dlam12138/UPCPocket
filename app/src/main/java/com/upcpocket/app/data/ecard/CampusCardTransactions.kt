package com.upcpocket.app.data.ecard

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.*

/** 本人账单契约来自学校 campus-card/chunk-24336d94；不使用记名卡查询的 uuid 接口。 */
object CampusCardTransactions {
    const val PATH = "/berserker-search/search/personal/turnover"
    const val PAGE_SIZE = 20
    data class Entry(
        val orderId: String,
        val cents: Long,
        val income: Boolean,
        val balanceCents: Long?,
        val summary: String,
        val occurredAt: String,
        val postedAt: String?,
        val payment: String?,
    ) {
        val amountText: String get() = (if (income) "+" else "-") + BigDecimal.valueOf(cents, 2).toPlainString()
    }
    data class Page(val entries: List<Entry>, val current: Int, val pages: Int, val total: Long)

    fun query(from: LocalDate, to: LocalDate, page: Int): String {
        require(page >= 1 && !to.isBefore(from) && to <= from.plusMonths(3)) { "请选择三个月以内的日期范围" }
        return "$PATH?size=$PAGE_SIZE&current=$page&timeFrom=$from&timeTo=$to&orderBy=jndatetime"
    }

    fun parse(body: String, requestedPage: Int): Page {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: invalid()
        val code = root.text("code")?.toIntOrNull() ?: invalid()
        if (code == 401 || code == 403) throw CampusCardSessionExpiredException("校园卡会话已失效，请重新登录")
        if (code != 200) throw CampusCardException("学校未返回流水（业务状态 $code）")
        val data = root["data"] as? JsonObject ?: invalid()
        val records = data["records"] as? JsonArray ?: invalid()
        if (records.size > PAGE_SIZE) invalid()
        val pages = data.text("pages")?.toIntOrNull()?.takeIf { it >= 0 } ?: invalid()
        val total = data.text("total")?.toLongOrNull()?.takeIf { it >= 0 } ?: invalid()
        val current = data.text("current")?.toIntOrNull() ?: requestedPage
        if (current != requestedPage || (total == 0L && records.isNotEmpty()) ||
            (records.isNotEmpty() && (pages < current || total < records.size)) ||
            (records.isEmpty() && current <= pages && total > 0)) invalid()
        val entries = records.map { raw ->
            val item = raw as? JsonObject ?: invalid()
            val cents = item.money("tranamt")?.takeIf { it >= 0 } ?: invalid()
            val direction = item.text("typeFrom")?.takeIf { it.isNotBlank() } ?: invalid()
            val time = item.text("jndatetimeStr")?.takeIf { it.isNotBlank() }
                ?: item.text("effectdateStr")?.takeIf { it.isNotBlank() } ?: invalid()
            Entry(
                orderId = item.text("orderId").orEmpty(), cents = cents, income = direction == "1",
                balanceCents = item.money("ebagamt"), summary = item.text("resume")?.takeIf { it.isNotBlank() } ?: "校园卡交易",
                occurredAt = time, postedAt = item.text("effectdateStr"), payment = item.text("payName"),
            )
        }
        return Page(entries, current, pages, total)
    }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.money(key: String): Long? {
        if (this[key] == null || this[key] is JsonNull) return null
        return try { BigDecimal(text(key) ?: invalid()).longValueExact() } catch (_: ArithmeticException) { invalid() }
            catch (_: NumberFormatException) { invalid() }
    }
    private fun invalid(): Nothing = throw CampusCardException("学校流水数据格式发生变化，本次结果未显示")
}
