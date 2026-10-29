package com.upcpocket.app.data.ecard

import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.json.*

/**
 * jhzf 的 myBill.js → queryHisTotal 实际契约。与 mb-xyk turnover 是两个系统。
 * 此解析器不创建或声称拥有有效会话；会话接入完成前不接到用户查询按钮。
 */
internal object JhzfBillProtocol {
    const val ENDPOINT = "http://jhzf.upc.edu.cn:20081/wechat/callinterface/queryHisTotal.html"
    const val PAGE_SIZE = 10
    private val timestamp = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val date = DateTimeFormatter.BASIC_ISO_DATE

    data class Entry(
        val signedCents: Long,
        val occurredAt: LocalDateTime,
        val transactionName: String,
        val merchant: String,
        val accountType: String,
    ) {
        val amountText: String get() = (if (signedCents > 0) "+" else "") +
            BigDecimal.valueOf(signedCents, 2).toPlainString()
    }

    data class Page(val month: YearMonth, val page: Int, val entries: List<Entry>, val nextPage: Int?) {
        // nextpage=0 只表示本月结束，不能据此声称所有历史已经读取完毕。
        val monthComplete: Boolean get() = nextPage == null
    }

    fun form(ownStudentId: String, cardAccount: String, month: YearMonth, page: Int): String {
        require(DirectCardBalanceProtocol.validStudentId(ownStudentId)) { "请先确认本人学号" }
        require(cardAccount.matches(Regex("[0-9]{1,32}"))) { "缺少学校返回的本人校园卡账户号" }
        require(page in 1..10_000 && month.year in 1900..2100) { "查询月份或页码无效" }
        // 账户号必须来自已经核对所属学号的学校响应，不能用学号代替。
        return linkedMapOf(
            "sno" to ownStudentId, "xxbh" to "synjones", "curpage" to page.toString(),
            "pagesize" to PAGE_SIZE.toString(), "account" to cardAccount, "acctype" to "",
            "query_start" to month.atDay(1).format(date), "query_end" to month.atEndOfMonth().format(date),
        ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    }

    fun parse(body: String, month: YearMonth, requestedPage: Int): Page {
        require(requestedPage in 1..10_000) { "查询页码无效" }
        if (body.toByteArray(Charsets.UTF_8).size > 256 * 1024) invalid()
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: invalid()
        val status = root.value("retcode")?.takeIf { it.matches(Regex("-?[0-9]+")) } ?: invalid()
        if (status != "0") {
            // 不显示可能含身份或凭据的 errmsg；未知业务码不自动解释成登录过期。
            throw CampusCardException("学校未返回流水（业务状态 ${status.take(12)}），请检查会话或稍后重试")
        }
        val records = root["total"] as? JsonArray ?: invalid()
        if (records.size > PAGE_SIZE) invalid()
        val next = root.value("nextpage")?.toIntOrNull()?.takeIf { it == 0 || it == requestedPage + 1 } ?: invalid()
        if (records.isEmpty() && next != 0) invalid()
        val entries = records.map { raw ->
            val item = raw as? JsonObject ?: invalid()
            val amount = item.value("sign_tranamt")?.takeIf { it.matches(Regex("[+-]?[0-9]+")) }?.toLongOrNull() ?: invalid()
            val occurredAt = runCatching {
                LocalDateTime.parse(item.value("occtime")?.takeIf { it.matches(Regex("[0-9]{14}")) } ?: invalid(), timestamp)
            }.getOrNull() ?: invalid()
            if (YearMonth.from(occurredAt) != month) invalid()
            Entry(amount, occurredAt, item.requiredText("tranname", 256, allowEmpty = false),
                item.requiredText("mercname", 512), item.requiredText("acctype", 64, allowEmpty = false))
        }
        // 不按金额/时间去重：同秒同额的两笔真实交易可能同时存在。
        return Page(month, requestedPage, entries, next.takeIf { it != 0 })
    }

    private fun JsonObject.value(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.requiredText(key: String, limit: Int, allowEmpty: Boolean = true): String {
        val primitive = this[key] as? JsonPrimitive ?: invalid()
        if (!primitive.isString) invalid()
        val value = primitive.content
        if (value.length > limit || (!allowEmpty && value.isBlank()) || value.any { it.isISOControl() }) invalid()
        return value
    }

    private fun invalid(): Nothing = throw CampusCardException("学校账单数据格式不符，本次未更新流水")
}
