package com.upcpocket.app.data.power

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唐岛湾电费页面解析。
 *
 * 结构来自 2026-10-28 匿名核验的真实页面（`Default.aspx?code=…` 与查询后的
 * `yongdianGrid` / `yonghuGridView`）；**数值与房间号全部替换为虚构样例**。
 */
class ElectricityProtocolTest {

    private fun page(
        viewState: String = "VS-TOKEN",
        validation: String = "EV-TOKEN",
        buildings: List<String> = listOf("01号楼(1)", "02号楼(2)"),
    ) = buildString {
        append("<!DOCTYPE html><html><head><title>用电查询系统</title></head><body>")
        append("<form method=\"post\" action=\"./Default.aspx\" id=\"form1\">")
        append("<input type=\"hidden\" name=\"__VIEWSTATE\" id=\"__VIEWSTATE\" value=\"$viewState\" />")
        append("<input type=\"hidden\" name=\"__VIEWSTATEGENERATOR\" id=\"__VIEWSTATEGENERATOR\" value=\"AAAA1111\" />")
        append("<input type=\"hidden\" name=\"__EVENTVALIDATION\" id=\"__EVENTVALIDATION\" value=\"$validation\" />")
        append("<select name=\"lou\" id=\"lou\">")
        buildings.forEach { append("<option value=\"$it\">$it</option>") }
        append("</select>")
        append("<input name=\"yonghu\" type=\"text\" id=\"yonghu\" /></form></body></html>")
    }

    private fun successPage(kwh: String, systemNo: String = "0000000001", room: String = "1101") = """
        <form id="form1">
        <table cellspacing="0" rules="all" id="yongdianGrid" width="200">
          <tr class="yincangtd"><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td></tr>
          <tr><td width="110"><span>当前剩余电量:</span></td><td>$kwh</td>
              <td class="yincangtd">$systemNo</td><td class="yincangtd">$room</td></tr>
        </table>
        </form>
    """.trimIndent()

    private fun ambiguousPage(vararg rows: Triple<String, String, String>) = buildString {
        append("<form id=\"form1\"><table id=\"yonghuGridView\">")
        append("<tr><th>系统编号</th><th>楼号</th><th>房间号</th><th>查询</th></tr>")
        rows.forEach { (systemNo, building, room) ->
            append("<tr><td>$systemNo</td><td>$building</td><td>$room</td>")
            append("<td><input type=\"submit\" name=\"yonghuGridView\$ctl02\$chaxunxuanze\" value=\"选择\" /></td></tr>")
        }
        append("</table></form>")
    }

    @Test
    fun `解析表单三个隐藏字段与楼号列表`() {
        val form = ElectricityProtocol.parseForm(page())
        assertEquals("VS-TOKEN", form.viewState)
        assertEquals("AAAA1111", form.viewStateGenerator)
        assertEquals("EV-TOKEN", form.eventValidation)
        assertEquals(listOf("01号楼(1)", "02号楼(2)"), form.buildings)
    }

    @Test
    fun `页面被引导到微信授权时按需登录处理`() {
        val wechat = "<html><body>正在跳转 open.weixin.qq.com 授权</body></html>"
        val error = runCatching { ElectricityProtocol.parseForm(wechat) }.exceptionOrNull()
        assertTrue(error is ElectricityProtocol.PageException)
        assertEquals(
            ElectricityProtocol.PageException.Kind.AUTH_REQUIRED,
            (error as ElectricityProtocol.PageException).kind,
        )
    }

    @Test
    fun `缺少事件验证字段时报格式变化而不是静默继续`() {
        val broken = page().replace("<input type=\"hidden\" name=\"__EVENTVALIDATION\" id=\"__EVENTVALIDATION\" value=\"EV-TOKEN\" />", "")
        val error = runCatching { ElectricityProtocol.parseForm(broken) }.exceptionOrNull()
        assertEquals(
            ElectricityProtocol.PageException.Kind.FORMAT_CHANGED,
            (error as ElectricityProtocol.PageException).kind,
        )
    }

    @Test
    fun `唯一房间返回剩余电量并带出系统编号`() {
        val outcome = ElectricityProtocol.parseResult(successPage("17.34度"), "1101")
        val success = outcome as ElectricityProtocol.Outcome.Success
        assertEquals(BigDecimal("17.34"), success.reading.kwh)
        assertEquals("0000000001", success.reading.systemNo)
        assertEquals("1101", success.reading.room)
    }

    @Test
    fun `整数电量与零电量都能解析`() {
        assertEquals(BigDecimal("0"), ElectricityProtocol.parseKwh("0度"))
        assertEquals(BigDecimal("146"), ElectricityProtocol.parseKwh("146度"))
        assertEquals(BigDecimal("0.00"), ElectricityProtocol.parseResult(successPage("0.00度"), "1101").let {
            (it as ElectricityProtocol.Outcome.Success).reading.kwh
        })
    }

    @Test
    fun `电量字段被改成金额单位时报错而不是当成度`() {
        val error = runCatching { ElectricityProtocol.parseKwh("17.34元") }.exceptionOrNull()
        assertTrue(error is ElectricityProtocol.PageException)
    }

    @Test
    fun `多房间命中返回候选而不是随便取一个`() {
        val outcome = ElectricityProtocol.parseResult(
            ambiguousPage(
                Triple("0000000003", "01号楼(1)", "1101"),
                Triple("0000000015", "01号楼(1)", "商铺101"),
            ),
            requestedRoom = "101",
        )
        val ambiguous = outcome as ElectricityProtocol.Outcome.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        assertEquals("0000000003", ambiguous.candidates[0].systemNo)
        assertEquals("1101", ambiguous.candidates[0].room)
        assertEquals("商铺101", ambiguous.candidates[1].room)
    }

    @Test
    fun `模糊命中时即使页面带着别的房间电量也不能当成用户的结果`() {
        // 真机实测的真实形态：输入 101，学校同时给出候选表 + 1101 的电量。
        val html = buildString {
            append(successPage("17.34度", systemNo = "0022250003", room = "1101"))
            append(ambiguousPage(Triple("0022250003", "01号楼(1)", "1101")).replace("<form id=\"form1\">", ""))
        }
        val outcome = ElectricityProtocol.parseResult(html, requestedRoom = "101")
        assertTrue(
            "输入 101 却直接返回 1101 的电量会串号，必须回到候选确认",
            outcome is ElectricityProtocol.Outcome.Ambiguous,
        )
    }

    @Test
    fun `候选里恰好有与输入完全一致的房间时才直接返回`() {
        val html = buildString {
            append(successPage("17.34度", systemNo = "0022250003", room = "1101"))
            append(ambiguousPage(Triple("0022250003", "01号楼(1)", "1101")).replace("<form id=\"form1\">", ""))
        }
        val outcome = ElectricityProtocol.parseResult(html, requestedRoom = "1101")
        val success = outcome as ElectricityProtocol.Outcome.Success
        assertEquals("1101", success.reading.room)
        assertEquals("0022250003", success.reading.systemNo)
        assertEquals(BigDecimal("17.34"), success.reading.kwh)
    }

    @Test
    fun `页面返回了另一个房间且没有候选表时也不代猜`() {
        val outcome = ElectricityProtocol.parseResult(
            successPage("5.00度", systemNo = "0000000009", room = "2202"),
            requestedRoom = "1101",
        )
        val ambiguous = outcome as ElectricityProtocol.Outcome.Ambiguous
        assertEquals("2202", ambiguous.candidates.single().room)
    }

    @Test
    fun `没有房间时给出明确错误而不是空结果`() {
        val error = runCatching { ElectricityProtocol.parseResult("<form id=\"form1\"></form>", "1101") }
            .exceptionOrNull()
        assertEquals(
            ElectricityProtocol.PageException.Kind.ROOM_NOT_FOUND,
            (error as ElectricityProtocol.PageException).kind,
        )
    }

    @Test
    fun `查询表单按真实字段名与顺序编码`() {
        val form = ElectricityProtocol.Form(
            viewState = "a b",
            viewStateGenerator = "G",
            eventValidation = "E",
            buildings = emptyList(),
        )
        val body = ElectricityProtocol.formBody(form, "01号楼(1)", " 1101 ")
        assertTrue(body.startsWith("__VIEWSTATE=a+b&__VIEWSTATEGENERATOR=G&__EVENTVALIDATION=E"))
        assertTrue(body.contains("lou="))
        assertTrue(body.contains("yonghu=+1101+"))
        assertTrue(body.endsWith("chaxun=%E6%9F%A5%E8%AF%A2"))
        // 充值按钮的字段名绝不能出现在查询请求里。
        assertTrue(!body.contains("chongzhi"))
    }

    @Test
    fun `古镇口没有抓取实现`() {
        assertEquals(null, ElectricityCampus.GUZHENKOU.host)
        assertEquals(ElectricityProtocol.HOST, ElectricityCampus.TANGDAOWAN.host)
    }
}
