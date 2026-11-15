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
    @Test fun `missing or malformed school limits never create a fallback maximum`() {
        failure("P-FEE-CONFIG") { CardRechargeProtocol.rules(buildJsonObject { put("retain_money",1);put("flag","00000000") }) }
        failure("P-FEE-CONFIG") { rules(min="500",max="20") }
        failure("P-FEE-CONFIG") { rules(max="NaN") }
        failure("P-FEE-CONFIG") { rules(flag="00000001",layout="") }
    }
    @Test fun `above school maximum is rejected before any post`() {
        var calls=0
        val api=CardRechargeApi(RechargeTransport { _,_,_->calls++;error("Must not request") },{true})
        failure("P-AMOUNT") {api.create(session,prepared.copy(rules=rules(max="200")),"200.01")}
        assertEquals(0,calls)
    }
}
