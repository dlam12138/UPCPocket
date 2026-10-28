package com.upcpocket.app.data.ecard

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园卡协议解析的结构来自 2026-10-28 读取的平台前端包（`chunk-0fdb9c48` 与
 * `frontInfo.getEcardConfig`）。**样例数值全部虚构**，不包含任何真实账户或余额。
 */
class CampusCardProtocolTest {

    private fun frontInfo(type: String) = """
        {"code":200,"success":true,"data":{
          "getFrontConfig":"{\"plat\":\"https://example.test/plat/\"}",
          "getEcardConfig":"{\"freezeRecharge\":\"1\",\"showSno\":\"2\",\"type\":\"$type\",\"showLost\":\"1\"}"
        },"msg":"操作成功"}
    """.trimIndent()

    private fun card(
        account: String = "9000000001",
        db: String = "1234",
        pending: String = "0",
        wallets: String = "[]",
        lost: String = "0",
        frozen: String = "0",
    ) = """
        {"code":200,"success":true,"data":{"card":[
          {"account":"$account","cardname":"测试卡","db_balance":$db,
           "unsettle_amount":$pending,"accinfo":$wallets,
           "lostflag":$lost,"freezeflag":$frozen}
        ]},"msg":"操作成功"}
    """.trimIndent()

    @Test
    fun `三种金额口径都按平台公式计算`() {
        assertEquals(CampusCardProtocol.Basis.CARD_PLUS_PENDING, CampusCardProtocol.parseFrontInfo(frontInfo("0")))
        assertEquals(CampusCardProtocol.Basis.WALLETS, CampusCardProtocol.parseFrontInfo(frontInfo("1")))
        assertEquals(CampusCardProtocol.Basis.CARD_ONLY, CampusCardProtocol.parseFrontInfo(frontInfo("2")))

        val wallets = """[{"balance":500},{"balance":250}]"""
        val plus = CampusCardProtocol.parseCards(card(db = "1234", pending = "100", wallets = wallets), CampusCardProtocol.Basis.CARD_PLUS_PENDING)
        assertEquals(2084L, plus.single().cents)

        val onlyWallets = CampusCardProtocol.parseCards(card(db = "1234", pending = "100", wallets = wallets), CampusCardProtocol.Basis.WALLETS)
        assertEquals(750L, onlyWallets.single().cents)

        val cardOnly = CampusCardProtocol.parseCards(card(db = "1234", pending = "100", wallets = wallets), CampusCardProtocol.Basis.CARD_ONLY)
        assertEquals(1334L, cardOnly.single().cents)
    }

    @Test
    fun `金额按分换算成元并保留两位`() {
        val parsed = CampusCardProtocol.parseCards(card(db = "1734"), CampusCardProtocol.Basis.CARD_ONLY)
        assertEquals("17.34", parsed.single().amountYuan.toPlainString())
    }

    @Test
    fun `合法的零余额是成功结果而不是错误`() {
        val parsed = CampusCardProtocol.parseCards(card(db = "0"), CampusCardProtocol.Basis.CARD_ONLY)
        assertEquals(0L, parsed.single().cents)
        assertEquals("0.00", parsed.single().amountYuan.toPlainString())
    }

    @Test
    fun `口径未知时报错而不是猜一个`() {
        val error = runCatching { CampusCardProtocol.parseFrontInfo(frontInfo("9")) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("口径未知"))
    }

    @Test
    fun `getEcardConfig 不是字符串时报格式错误`() {
        val body = """{"code":200,"data":{"getEcardConfig":{"type":"0"}}}"""
        assertTrue(runCatching { CampusCardProtocol.parseFrontInfo(body) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `业务码非 200 时报错并带出服务端消息`() {
        val body = """{"code":401,"success":false,"data":null,"message":"缺失令牌,鉴权失败"}"""
        val error = runCatching {
            CampusCardProtocol.parseCards(body, CampusCardProtocol.Basis.CARD_ONLY)
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("401"))
    }

    @Test
    fun `缺 account 或金额字段不会被当成 0 元`() {
        val missingAccount = """{"code":200,"data":{"card":[{"db_balance":100,"unsettle_amount":0,"accinfo":[]}]}}"""
        assertTrue(
            runCatching { CampusCardProtocol.parseCards(missingAccount, CampusCardProtocol.Basis.CARD_ONLY) }
                .exceptionOrNull() is IOException,
        )

        val missingAmount = """{"code":200,"data":{"card":[{"account":"1","unsettle_amount":0,"accinfo":[]}]}}"""
        assertTrue(
            runCatching { CampusCardProtocol.parseCards(missingAmount, CampusCardProtocol.Basis.CARD_ONLY) }
                .exceptionOrNull() is IOException,
        )
    }

    @Test
    fun `accinfo 不是数组时报格式错误`() {
        val body = """{"code":200,"data":{"card":[{"account":"1","db_balance":1,"unsettle_amount":0,"accinfo":{}}]}}"""
        assertTrue(
            runCatching { CampusCardProtocol.parseCards(body, CampusCardProtocol.Basis.CARD_PLUS_PENDING) }
                .exceptionOrNull() is IOException,
        )
    }

    @Test
    fun `空卡列表是合法的空结果`() {
        val body = """{"code":200,"data":{"card":[]}}"""
        assertEquals(0, CampusCardProtocol.parseCards(body, CampusCardProtocol.Basis.CARD_ONLY).size)
    }

    @Test
    fun `挂失与冻结标记按平台字段读取`() {
        val parsed = CampusCardProtocol.parseCards(
            card(lost = "1", frozen = "1"),
            CampusCardProtocol.Basis.CARD_ONLY,
        ).single()
        assertTrue(parsed.lost)
        assertTrue(parsed.frozen)

        val normal = CampusCardProtocol.parseCards(card(), CampusCardProtocol.Basis.CARD_ONLY).single()
        assertFalse(normal.lost)
        assertFalse(normal.frozen)
    }

    @Test
    fun `登录令牌来自平台带回的查询参数名`() {
        assertEquals("synjones-auth", CampusCardProtocol.TOKEN_PARAM)
        assertTrue(CampusCardProtocol.LOGIN_PATH.startsWith("/berserker-auth/cas/redirect/"))
    }
}
