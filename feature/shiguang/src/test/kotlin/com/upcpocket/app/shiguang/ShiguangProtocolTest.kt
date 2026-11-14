package com.upcpocket.app.shiguang

import org.junit.Assert.*
import org.junit.Test

class ShiguangProtocolTest {
    private fun list(card: String = "", footer: String = "") = "<div class='active_2_list'>$card</div>$footer"
    private val card = "<div class='li'><a class='ellipsis' href='/actvMng/actvMngFront!jumpActvCampViewPageForWeb.action?actvModel.activityId=123'>合成活动</a><div>学时：0</div></div>"
    private fun summary(overrides: Map<String, String> = emptyMap(), omit: String = ""): String {
        val values = (0..4).flatMap { listOf("credit$it" to "$it", "credit0$it" to "0.$it") }.toMap() + mapOf("flag" to "1,20,2,20,,,,,,", "orderNum" to "5,1,2,3,4") + overrides
        return values.filterKeys { it != omit }.entries.joinToString("") { "<input id='${it.key}' value='${it.value}'>" }
    }
    private fun protocolFailure(block: () -> Unit) {
        try { block(); fail("expected protocol error") } catch (e: ShiguangException) { assertEquals(ShiguangFailure.PROTOCOL, e.reason) }
    }
    @Test fun containerIsRequiredAndMalformedCardCannotBecomeEmpty() {
        protocolFailure { ShiguangProtocol.list("<p>系统异常</p>", 1) }
        protocolFailure { ShiguangProtocol.list(list("<div class='li'>坏卡片</div>"), 1) }
        protocolFailure { ShiguangProtocol.list(list(card.replace("123", "abc")), 1) }
        protocolFailure { ShiguangProtocol.list(list(card.replace("/actvMng/", "https://example.com/actvMng/")), 1) }
        assertTrue(ShiguangProtocol.list(list(), 1).items.isEmpty())
    }
    @Test fun pagesRemainUnknownWhenNotSuppliedAndZeroHoursStayZero() {
        val unknown = ShiguangProtocol.list(list(card), 1)
        assertNull(unknown.totalPages); assertNull(unknown.nextPage)
        assertEquals("0", unknown.items.single().fields["学时"])
        val known = ShiguangProtocol.list(list(card, "共&nbsp;3&nbsp;页"), 1)
        assertEquals(3, known.totalPages); assertEquals(2, known.nextPage)
    }
    @Test fun acceptsMoreAnchorAndRejectsWrongDetailPath() {
        assertEquals("123", ShiguangProtocol.list(list(card.replace("ellipsis", "more")), 1).items.single().id)
        protocolFailure { ShiguangProtocol.list(list(card.replace("jumpActvCampViewPageForWeb", "goEnrollInForWeb")), 1) }
    }
    @Test fun titleLinkFallbackAndMetadataListsKeepTheOneActivityCard() {
        val withMetadata = card.replace("<div>学时：0</div>", "<ul><li>学时：0</li><li>地点：合成场地</li></ul><a class='more' href='javascript:void(0)'>查看详情</a>")
        val result = ShiguangProtocol.list(list(withMetadata), 1)
        assertEquals(1, result.items.size); assertEquals("123", result.items.single().id)
        assertEquals("0", result.items.single().fields["学时"])
        protocolFailure { ShiguangProtocol.list(list(card.replace("/actvMng/", "//example.com/actvMng/")), 1) }
        protocolFailure { ShiguangProtocol.list(list("<ul><li>无有效活动链接</li></ul>"), 1) }
    }
    @Test fun loginPageIsAuthRequired() {
        try { ShiguangProtocol.list("<form id='loginForm' action='/cas/login'><input type='password'></form>", 1); fail() }
        catch (e: ShiguangException) { assertEquals(ShiguangFailure.AUTH_REQUIRED, e.reason) }
    }
    @Test fun detailIsNativeTextAndExcludesScriptsAndContacts() {
        val result = ShiguangProtocol.detail("""<div class='activeD_l'><div class='t2'>合成详情</div><div class='li'>学时：0</div><div class='li'>联系人：测试</div><div class='djs'><p>安全文本</p><script>bad()</script><p>联系电话：13800138000</p><p>说明 &amp; 详情</p></div></div>""")
        assertEquals("合成详情", result.title); assertEquals(mapOf("学时" to "0"), result.fields)
        assertFalse(result.description!!.contains("bad")); assertFalse(result.description.contains("13800138000")); assertFalse(result.description.contains("联系电话"))
        assertTrue(result.description.contains("说明 & 详情"))
        val unclosed = ShiguangProtocol.detail("<div class='activeD_l'><div class='t2'>合成详情</div><div class='djs'>安全文本<script>bad()")
        assertEquals("安全文本", unclosed.description)
    }
    @Test fun summaryPreservesOrderUnitsRanksAndUnknownValues() {
        val result = ShiguangProtocol.summary(summary(mapOf("credit2" to "")))
        assertEquals(listOf(5, 1, 2, 3, 4), result.modules.map { it.id })
        assertNull(result.totalHours)
        val one = result.modules.first { it.id == 1 }
        assertEquals("0", one.hours!!.toPlainString()); assertEquals("0.0", one.credits!!.toPlainString())
        assertEquals(ShiguangRank(1, 20), one.rank)
        assertEquals("10", ShiguangProtocol.summary(summary()).totalHours!!.toPlainString())
    }
    @Test fun summaryRejectsMissingFieldsWrongOrderAndUnitContamination() {
        protocolFailure { ShiguangProtocol.summary(summary(omit = "credit04")) }
        protocolFailure { ShiguangProtocol.summary(summary(mapOf("orderNum" to "1,2,3,4,4"))) }
        protocolFailure { ShiguangProtocol.summary(summary(mapOf("orderNum" to "1,2,3,4,x,5"))) }
        protocolFailure { ShiguangProtocol.summary(summary(mapOf("credit0" to "2学分"))) }
        protocolFailure { ShiguangProtocol.summary(summary(mapOf("credit0" to "NaN"))) }
        protocolFailure { ShiguangProtocol.summary(summary(mapOf("flag" to "30,20"))) }
    }
    @Test fun summaryKeepsFiniteHighPrecisionDecimalsInsteadOfRejectingEightPlusPlaces() {
        val result = ShiguangProtocol.summary(summary(mapOf("credit0" to "1.3333333333333333", "credit1" to ".5", "credit2" to "1e1")))
        val modules = result.modules.associateBy { it.id }
        assertEquals("1.3333333333333333", modules.getValue(1).hours!!.toPlainString())
        assertEquals("0.5", modules.getValue(2).hours!!.toPlainString())
        assertEquals("10", modules.getValue(3).hours!!.toPlainString())
        protocolFailure { ShiguangProtocol.summary(summary(mapOf("credit0" to "1e999999999"))) }
    }
}
