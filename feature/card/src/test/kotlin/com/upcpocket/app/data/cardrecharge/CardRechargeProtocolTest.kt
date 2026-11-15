package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.account.api.*
import com.upcpocket.app.data.cardhistory.CardRechargeSession

import java.net.URLEncoder
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CardRechargeProtocolTest {
    private val owner=SchoolStudentIdentity(SchoolAccountId("fixture_account"),"2099999999",AccountGeneration(1))
    private val session=CardRechargeSession(owner,"synthetic-session",{true})
    private val signer=RechargeSigner("12345","synthetic-only-material")
    private val prepared=RechargePrepared("401","100001",signer,RechargeRules(java.math.BigDecimal("1"),java.math.BigDecimal("1000"),emptyList(),false,false))
    private val order="1234567890123"
    private val pending=RechargePending(owner,"12345678-1234-1234-1234-123456789abc","20",order)
    private val uri="weixin://wap/pay?prepayid=synthetic-order&package=WAP&sign=synthetic"
    private fun failure(stage:String, block:()->Unit) {
        val error=assertThrows(RechargeFailure::class.java,block)
        assertEquals(stage,error.stage)
    }
    private fun envelope(data:JsonObject)=buildJsonObject { put("code",200);put("success",true);put("data",data) }.toString()
    private fun form(input:String="",callback:String="http://mb-xyk.upc.edu.cn/payment/?name=result&orderid=$order&payid=81"):String {
        val target="https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=synthetic&ct=1&sign=synthetic&package=1&redirect_url="+URLEncoder.encode(callback,"UTF-8")
        return "<form method=\"POST\" action=\"$target\">$input</form><script>document.forms[0].submit();</script>"
    }
    @Test fun `amount stays yuan and does not become integer fen`() {
        assertEquals("20",CardRechargeProtocol.amount("20.00"))
        assertEquals("1.25",CardRechargeProtocol.amount("1.25"))
        assertEquals("500",CardRechargeProtocol.amount("500.00"))
        listOf("0","01","1.001","-1","1e1","NaN").forEach { value -> failure("P-AMOUNT") { CardRechargeProtocol.amount(value) } }
    }
    @Test fun `SHA256 signature sorts raw fields drops empty and keeps zero`() {
        val result=signer.sign(mapOf("zero" to "0","amount" to "20","empty" to ""),"20261115123000","abcdefgh")
        assertEquals("85D9D7667F451E5C2B70C1ECC30CDB94D2AE84B56407959CB2D4252C3DBBBDA2",result["SIGN"])
        assertFalse(result.containsKey("SECRET_KEY"))
        assertFalse(signer.toString().contains("synthetic-only-material"))
    }
    @Test fun `runtime source parser accepts only unique known signing template`() {
        val source="confirm(){let t={appid:\"12345\"};e=Object(o[\"a\"])(e,\"12345\",\"${"A".repeat(32)}\",\"SHA256\");s.action=this.base+\"/charge/order/thirdOrder\"}"
        assertEquals("12345",CardRechargeProtocol.signer(source).appId)
        failure("P-SIGN-FORMAT") { CardRechargeProtocol.signer(source.replace("SHA256","SHA512")) }
        failure("P-SIGN-FORMAT") { CardRechargeProtocol.signer(source.replace("appid:\"12345\"","appid:\"99999\"")) }
        failure("P-SIGN-FORMAT") { CardRechargeProtocol.signer("confirm(){"+source.substringAfter("confirm(){").replace("s.action", "e=Object(o[\"a\"])(e,\"12345\",\"${"A".repeat(32)}\",\"SHA256\");s.action")) }
    }
    @Test fun `checkout form keeps raw query and never forwards dynamic fields`() {
        val result=CardRechargeProtocol.cashierForm(form("<input type=\"hidden\" name=\"\" value=\"\">"))
        assertTrue(result.first.contains("redirect_url=http%3A%2F%2F"));assertTrue(result.second.isEmpty())
        failure("P-WX-FORM") { CardRechargeProtocol.cashierForm(form("<input type='hidden' name='synjones-auth' value='synthetic'>")) }
        failure("P-WX-ORIGIN") { CardRechargeProtocol.cashierForm(form().replace("wx.tenpay.com","attacker.example")) }
        failure("P-ORIGIN") { CardRechargeProtocol.cashierForm(form(callback="https://attacker.example/payment/")) }
    }
    @Test fun `literal handoff is parsed without executing JavaScript`() {
        assertEquals(uri,CardRechargeProtocol.literalWechat("<script>var pay = '$uri'; window.location=pay;</script>"))
        assertEquals(uri,CardRechargeProtocol.literalWechat("<script>var pay=\"${uri.replace("&","\\u0026")}\";</script>"))
        failure("P-WX-SCRIPT") { CardRechargeProtocol.literalWechat("<script>eval(atob('unknown'))</script>") }
        failure("P-WX-SCRIPT") { CardRechargeProtocol.literalWechat("'$uri' 'weixin://wap/pay?other=1'") }
        listOf("intent://wap/pay?a=1","weixin://attacker/pay?a=1","weixin://wap/other?a=1","weixin://user@wap/pay?a=1","weixin://wap/pay?a=1#extra").forEach { value -> failure("P-WX-URI") { CardRechargeProtocol.wechatUri(value) } }
    }
    @Test fun `school callback upgrades only exact school without changing ticket bytes`() {
        assertEquals("https://mb-xyk.upc.edu.cn/payment/index?orderid=$order&token=a%2Bb",CardRechargeProtocol.redirect(CardRechargeProtocol.BASE,"http://mb-xyk.upc.edu.cn/payment/index?orderid=$order&token=a%2Bb"))
        failure("P-ORIGIN") { CardRechargeProtocol.redirect(CardRechargeProtocol.BASE,"https://mb-xyk.upc.edu.cn.attacker.example/payment/index") }
    }
    @Test fun `pending owner survives same-account password rotation without restoring a token`() {
        val raw=EncryptedRechargePendingStore.encode(pending)
        val rotated=owner.copy(credentialGeneration=AccountGeneration(2))
        assertEquals(pending,EncryptedRechargePendingStore.decode(raw,rotated))
        failure("P-ACCOUNT") { EncryptedRechargePendingStore.decode(raw,owner.copy(studentId="2099999998")) }
        failure("P-ACCOUNT") { EncryptedRechargePendingStore.decode(raw,owner.copy(accountId=SchoolAccountId("other_account"))) }
        assertFalse(raw.contains(session.token));assertFalse(pending.toString().contains(order))
    }
    @Test fun `unknown order reply is never retried or treated as successful create`() {
        var posts=0
        val api=CardRechargeApi(RechargeTransport { _, fields, _ -> if(fields!=null)posts++;RechargeResponse(200,"<html>unknown redirect script</html>") },{true})
        failure("P-ORDER-REDIRECT") { api.create(session,prepared,"20") }
        assertEquals(1,posts)
    }
    @Test fun `one order post yields only its explicit same-origin callback id`() {
        var calls=0
        val api=CardRechargeApi(RechargeTransport { _, fields, token ->
            calls++;assertEquals("20",fields?.get("tranamt"));assertEquals(session.token,token)
            RechargeResponse(302,"","http://mb-xyk.upc.edu.cn/payment/index?orderid=$order&token=synthetic")
        },{true})
        assertEquals(order,api.create(session,prepared,"20.00"));assertEquals(1,calls)
    }
    @Test fun `late account change cannot publish order response`() {
        var active=true
        val api=CardRechargeApi(RechargeTransport { _,_,_->active=false;RechargeResponse(302,"","${CardRechargeProtocol.BASE}/payment/index?orderid=$order") },{active})
        failure("P-ACCOUNT") { api.create(session,prepared,"20") }
    }
    @Test fun `H5 handoff uses real method from school and omits school credentials at Tencent`() {
        var calls=0;var saved=""
        val api=CardRechargeApi(RechargeTransport { url, fields, token ->
            calls++
            when {
                url.contains("getpayinfo?") -> RechargeResponse(200,"""{"order":{"orderid":"$order"},"payList":[{"payid":"81","code":"synthetic-wechat-code","name":"微信支付"}]}""")
                url.endsWith("/blade-pay/pay") -> {
                    assertEquals("synthetic-wechat-code",fields?.get("paytype"))
                    RechargeResponse(200,envelope(buildJsonObject { put("orderid",order);put("paysubmit",form()) }))
                }
                else -> { assertTrue(url.startsWith("https://wx.tenpay.com/"));assertNull(token);assertTrue(fields!!.isEmpty());RechargeResponse(200,"<script>var pay='$uri';</script>") }
            }
        },{true})
        assertEquals(uri,api.pay(session,prepared,pending){saved=it});assertEquals("81",saved);assertEquals(3,calls)
    }
    @Test fun `missing success envelope is never reported as successful status`() {
        val api=CardRechargeApi(RechargeTransport { _,_,_->RechargeResponse(200,"""{"orderList":[{"orderid":"$order","status":1}]}""") },{true})
        failure("P-STATUS") { api.status(session,pending) }
    }
    @Test fun `status rejects other order rather than misattributing success`() {
        val api=CardRechargeApi(RechargeTransport { _,_,_->RechargeResponse(200,"""{"code":200,"orderList":[{"orderid":"99999999999","status":1}]}""") },{true})
        failure("P-ORDER-OWNER") { api.status(session,pending) }
    }
    @Test fun `handoff is consumed once and revoked when no longer current`() {
        var current=true
        val first=RechargeHandoff(uri){current}
        assertEquals(uri,first.consume());assertNull(first.consume())
        val second=RechargeHandoff(uri){current};current=false;assertNull(second.consume())
    }
    private fun rules(flag:String="00000000",layout:String="20,50,200",min:String="1",max:String="500") = CardRechargeProtocol.rules(buildJsonObject {
        put("retain_money",min);put("maxmoney",max);put("layout",layout);put("flag",flag)
    })
    private fun configuredRules(min:JsonElement?=JsonNull,max:JsonElement?=JsonNull,front:JsonElement?=null,flag:String="00000000") =
        CardRechargeProtocol.rules(buildJsonObject {
            min?.let {put("retain_money",it)};max?.let {put("maxmoney",it)}
            put("layout","20,50,200");put("flag",flag)
        },buildJsonObject {front?.let {put("rechargeMaxAmount",it)}})
    @Test fun `school min and max replace the experimental twenty yuan cap`() {
        val rule=rules()
        assertEquals("500",rule.amount("500.00"));assertEquals("1",rule.amount("1"))
        listOf("0.99","500.01").forEach { failure("P-AMOUNT") { rule.amount(it) } }
    }
    @Test fun `presets compare numeric amounts and school can prohibit manual input`() {
        val rule=rules(flag="00000001",layout="20.00,200")
        assertEquals("20",rule.amount("20"));assertEquals("200",rule.amount("200.00"))
        failure("P-AMOUNT") {rule.amount("30")}
    }
    @Test fun `integer only restrictions apply to both presets and submitted amounts`() {
        val rule=rules(flag="00000002",layout="20,20.5,30")
        assertEquals(2,rule.presets.size)
        failure("P-AMOUNT") { rule.amount("20.5") }
        assertEquals("30",rule.amount("30.00"))
    }
    @Test fun `null fee bounds use the official input defaults without changing entered amount grammar`() {
        val rule=configuredRules()
        assertEquals("0.01",rule.minimum.toPlainString());assertEquals("9999",rule.maximum.toPlainString())
        assertEquals("0.01",rule.amount("0.01"));assertEquals("9999",rule.amount("9999"))
        listOf("0","9999.01","1.000","1e1").forEach {failure("P-AMOUNT") {rule.amount(it)}}
    }
    @Test fun `absent empty and zero fee bounds have the same no-front input defaults`() {
        for(value in listOf(null,JsonNull,JsonPrimitive(""),JsonPrimitive(0),JsonPrimitive("0.00"))) {
            val rule=configuredRules(min=value,max=value)
            assertEquals("0.01",rule.minimum.toPlainString());assertEquals("9999",rule.maximum.toPlainString())
        }
    }
    @Test fun `explicit fee bounds override the optional front maximum`() {
        val rule=configuredRules(min=JsonPrimitive("5"),max=JsonPrimitive("120"),front=JsonPrimitive("50"))
        assertEquals("5",rule.amount("5"));assertEquals("120",rule.amount("120"))
        listOf("4.99","120.01").forEach {failure("P-AMOUNT") {rule.amount(it)}}
    }
    @Test fun `missing fee maximum preserves front configuration and its integer conversion`() {
        for(value in listOf(null,JsonNull,JsonPrimitive(""),JsonPrimitive(0))) {
            val rule=configuredRules(max=value,front=JsonPrimitive("200.75"))
            assertEquals("200",rule.maximum.toPlainString());assertEquals("200",rule.amount("200"))
            failure("P-AMOUNT") {rule.amount("200.01")}
        }
        assertEquals("9999",configuredRules(front=JsonPrimitive("0")).maximum.toPlainString())
    }
    @Test fun `string zero fee maximum follows the official override before the input default`() {
        val rule=configuredRules(max=JsonPrimitive("0.00"),front=JsonPrimitive("100"))
        assertEquals("9999",rule.maximum.toPlainString())
    }
    @Test fun `config trailing zeroes are exact decimals without relaxing submitted cent precision`() {
        val rule=configuredRules(min=JsonPrimitive("0.0100"),max=Json.parseToJsonElement("500.0000"))
        assertEquals("0.01",rule.minimum.toPlainString());assertEquals("500",rule.maximum.toPlainString())
        assertEquals("500",rule.amount("500.00"))
        failure("P-AMOUNT") {rule.amount("500.0000")}
    }
    @Test fun `malformed typed or negative configuration never becomes an unrestricted default`() {
        val invalid=listOf<JsonElement>(JsonPrimitive(true),JsonPrimitive(false),buildJsonObject {},buildJsonArray {},
            JsonPrimitive("NaN"),JsonPrimitive("Infinity"),JsonPrimitive("-1"),JsonPrimitive("1e3"),
            JsonPrimitive("20oops"),JsonPrimitive(" 20"),JsonPrimitive("20 "),JsonPrimitive("0.001"))
        for(value in invalid) {
            failure("P-FEE-CONFIG") {configuredRules(min=value)}
            failure("P-FEE-CONFIG") {configuredRules(max=value)}
            failure("P-FEE-CONFIG") {configuredRules(front=value)}
        }
        failure("P-FEE-CONFIG") { rules(min="500",max="20") }
        failure("P-FEE-CONFIG") { rules(flag="00000001",layout="") }
    }
    @Test fun `official default bounds preserve integer and preset-only restrictions`() {
        val integer=configuredRules(flag="00000002")
        assertEquals("1",integer.amount("1"));failure("P-AMOUNT") {integer.amount("0.01")}
        val presets=configuredRules(flag="00000001")
        assertEquals("20",presets.amount("20"));failure("P-AMOUNT") {presets.amount("30")}
    }
    @Test fun `above school maximum is rejected before any post`() {
        var calls=0
        val api=CardRechargeApi(RechargeTransport { _,_,_->calls++;error("Must not request") },{true})
        failure("P-AMOUNT") {api.create(session,prepared.copy(rules=rules(max="200")),"200.01")}
        assertEquals(0,calls)
    }
}
