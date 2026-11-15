package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.account.api.*
import com.upcpocket.app.data.cardhistory.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CardRechargeServiceTest {
    internal class Account : SchoolAccountStudentIdentityReader {
        override val state=MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED,AccountGeneration(1)))
        override val studentIdentity=MutableStateFlow<SchoolStudentIdentity?>(SchoolStudentIdentity(SchoolAccountId("fixture_account"),"2099999999",AccountGeneration(1)))
        fun rotate() { studentIdentity.value=studentIdentity.value!!.copy(credentialGeneration=AccountGeneration(2)) }
    }
    internal class Store : RechargePendingStore {
        var value:RechargePending?=null
        override fun read(owner:SchoolStudentIdentity)=value?.takeIf {it.owner.accountId==owner.accountId && it.owner.studentId==owner.studentId}
        override fun save(record:RechargePending){value=record}
        override fun remove(record:RechargePending){if(value?.attemptId==record.attemptId)value=null}
        override fun forget(owner:SchoolStudentIdentity){if(read(owner)!=null)value=null}
        override fun clearAll(){value=null}
    }
    internal class Fixture {
        val account=Account();val store=Store()
        var authentications=0;var createPosts=0;var payPosts=0
        var maximum="500";var min="1";var flag="00000000";var status="0"
        var failPay=false;var changeOnCreate=false;var failCreate=false
        val order="1234567890123"
        val user="""{"code":200,"data":{"sno":"2099999999"}}"""
        val history=CardHistoryClient(account,CardHistoryAuthenticator { authentications++;"fixture-token-$authentications" },OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(user.toResponseBody("application/json".toMediaType())).build()
        }.build())
        private fun envelope(data:JsonObject)=buildJsonObject {put("code",200);put("data",data);put("success",true)}.toString()
        private fun obj(vararg pairs:Pair<String,String>)=buildJsonObject {pairs.forEach {(k,v)->put(k,v)}}
        val transport=RechargeTransport { url,fields,token ->
            val body=when {
                url.contains("/berserker-base/user") -> user
                url.contains("/frontInfo?") -> envelope(obj("getFrontConfig" to """{"recharge":"401"}""","getEcardConfig" to """{"type":"2"}"""))
                url.contains("/queryCard?") -> envelope(buildJsonObject {put("card",buildJsonArray {add(obj("sno" to "2099999999","account" to "100001","lostflag" to "0","freezeflag" to "0"))})})
                url.contains("/singleFeeitem?") -> buildJsonObject {put("feeitem",obj("retain_money" to min,"maxmoney" to maximum,"flag" to flag,"layout" to "20,50,200"))}.toString()
                url==CardRechargeProtocol.SIGNER_SOURCE -> "confirm(){let t={appid:\"12345\"};e=Object(o[\"a\"])(e,\"12345\",\"${"A".repeat(32)}\",\"SHA256\");s.action=\"/charge/order/thirdOrder\"}"
                url.endsWith("/thirdOrder") -> {
                    createPosts++
                    assertNotNull(store.value);assertNull(store.value!!.orderId)
                    assertEquals("bearer fixture-token-$authentications",fields?.get("synjones-auth"))
                    if(changeOnCreate) account.rotate()
                    if(failCreate) "<html>unknown reply</html>" else return@RechargeTransport RechargeResponse(302,"",CardRechargeProtocol.BASE+"/payment/index?orderid=$order&token=fixture")
                }
                url.contains("/getpayinfo?") -> """{"order":{"orderid":"$order"},"payList":[{"payid":"81","code":"fixture_wechat","name":"微信支付"}]}"""
                url.endsWith("/blade-pay/pay") -> {
                    payPosts++
                    if(failPay) return@RechargeTransport RechargeResponse(500,"")
                    val callback=java.net.URLEncoder.encode("http://mb-xyk.upc.edu.cn/payment/?name=result&orderid=$order&payid=81","UTF-8")
                    envelope(obj("orderid" to order,"paysubmit" to "<form method='POST' action='https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fixture&sign=fixture&package=1&redirect_url=$callback'><input type='hidden' name='' value=''></form>"))
                }
                url.startsWith("https://wx.tenpay.com/") -> {assertNull(token);assertTrue(fields!!.isEmpty());"<script>var next='weixin://wap/pay?fixture=1';</script>"}
                url.contains("/personal_data?") -> buildJsonObject {put("code",200);put("orderList",buildJsonArray {add(obj("orderid" to order,"status" to status))})}.toString()
                else -> error("Unexpected fixture route")
            }
            RechargeResponse(200,body)
        }
        val service=CardRechargeService(history,store,transport)
    }
    @Test fun `opening only loads school limits and never creates an order`()=runTest {
        val f=Fixture();f.service.open()
        assertEquals("500",f.service.state.value.rules!!.maximum.toPlainString())
        assertEquals("500",f.service.validateAmount("500.00"));assertEquals(0,f.createPosts);assertEquals(0,f.payPosts)
    }
    @Test fun `amount above live school maximum cannot post or reserve an order`()=runTest {
        val f=Fixture();f.service.open();f.maximum="200"
        assertNull(f.service.submit("200.01"));assertEquals(0,f.createPosts);assertNull(f.store.value)
        assertEquals("200",f.service.state.value.rules!!.maximum.toPlainString())
    }
    @Test fun `confirmed submission creates once then produces a one-time automatic handoff`()=runTest {
        val f=Fixture();f.service.open()
        val handoff=f.service.submit("200.00")!!
        assertEquals(1,f.createPosts);assertEquals(1,f.payPosts)
        assertEquals("200",f.store.value!!.yuan)
        assertEquals("weixin://wap/pay?fixture=1",handoff.consume());assertNull(handoff.consume())
    }
    @Test fun `pending order prevents another post even after reopening the service`()=runTest {
        val f=Fixture();f.service.open();f.failPay=true
        assertNull(f.service.submit("20"));assertEquals(1,f.createPosts)
        val restored=CardRechargeService(f.history,f.store,f.transport);restored.open()
        assertTrue(restored.state.value.recoveryRequired)
        assertNull(restored.submit("20"));assertEquals(1,f.createPosts)
    }
    @Test fun `unknown create reply leaves a durable marker and is never retried`()=runTest {
        val f=Fixture();f.service.open();f.failCreate=true
        assertNull(f.service.submit("20"));assertNotNull(f.store.value);assertNull(f.store.value!!.orderId)
        assertNull(f.service.submit("20"));assertEquals(1,f.createPosts)
    }
    @Test fun `credential change during create cannot save late order id or launch`()=runTest {
        val f=Fixture();f.service.open();f.changeOnCreate=true
        assertNull(f.service.submit("20"));assertEquals(1,f.createPosts);assertNull(f.store.value!!.orderId)
        assertEquals(0,f.payPosts)
    }
    @Test fun `successful query revokes an unconsumed handoff and clears exact pending`()=runTest {
        val f=Fixture();f.service.open();val handoff=f.service.submit("20")!!
        f.status="1";f.service.refresh()
        assertNull(handoff.consume());assertNull(f.store.value);assertFalse(f.service.state.value.recoveryRequired)
    }
    @Test fun `failed timed out and unknown orders retain protection for manual verification`()=runTest {
        for(status in listOf("0","2","unknown")) {
            val f=Fixture();f.service.open();f.service.submit("20");f.status=status;f.service.refresh()
            assertNotNull(f.store.value);assertTrue(f.service.state.value.recoveryRequired)
        }
    }
    @Test fun `same account credential rotation can query its old order with fresh authentication`()=runTest {
        val f=Fixture();f.service.open();val handoff=f.service.submit("20")!!
        f.account.rotate();f.service.invalidateForAccountChange(AccountDataScope.SESSIONS_ONLY)
        assertNotNull(f.store.value);assertNull(handoff.consume())
        f.status="1";f.service.open()
        assertEquals(2,f.authentications);assertNull(f.store.value)
        assertEquals(AccountGeneration(2),f.service.state.value.owner!!.credentialGeneration)
    }
    @Test fun `account deletion removes pending and revokes payment event`()=runTest {
        val f=Fixture();f.service.open();val handoff=f.service.submit("20")!!
        assertTrue(f.service.invalidateForAccountChange(AccountDataScope.PERSONAL_DATA))
        assertNull(f.store.value);assertNull(handoff.consume());assertNull(f.service.state.value.owner)
    }
}
