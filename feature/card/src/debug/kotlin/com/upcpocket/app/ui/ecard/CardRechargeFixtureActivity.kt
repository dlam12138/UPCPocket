package com.upcpocket.app.ui.ecard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.data.cardhistory.CardHistoryAuthenticator
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.data.cardrecharge.CardRechargeProtocol
import com.upcpocket.app.data.cardrecharge.CardRechargeService
import com.upcpocket.app.data.cardrecharge.RechargePending
import com.upcpocket.app.data.cardrecharge.RechargePendingStore
import com.upcpocket.app.data.cardrecharge.RechargeResponse
import com.upcpocket.app.data.cardrecharge.RechargeTransport
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Synthetic transport, account and payment target; no request can leave this fixture. Debug only. */
class CardRechargeFixtureActivity : ComponentActivity() {
    private var counts by mutableStateOf("下单 0 · 发起付款 0 · 跳转 0")
    private var createCount = 0
    private var payCount = 0
    private var launchCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val promo = intent.getBooleanExtra("promo", false)
        if (promo) enableEdgeToEdge()
        val account = object : SchoolAccountStudentIdentityReader {
            override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)))
            override val studentIdentity = MutableStateFlow<SchoolStudentIdentity?>(
                SchoolStudentIdentity(SchoolAccountId("debug_recharge_fixture"), "2099999999", AccountGeneration(1)),
            )
        }
        val store = object : RechargePendingStore {
            var record: RechargePending? = null
            override fun read(owner: SchoolStudentIdentity) = record?.takeIf { it.owner == owner }
            override fun save(record: RechargePending) { this.record = record }
            override fun remove(record: RechargePending) { if (this.record?.attemptId == record.attemptId) this.record = null }
            override fun forget(owner: SchoolStudentIdentity) { if (record?.owner == owner) record = null }
            override fun clearAll() { record = null }
        }
        val user = """{"code":200,"data":{"sno":"2099999999"}}"""
        val history = CardHistoryClient(account, CardHistoryAuthenticator { "debug-fixture-session" },
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(user.toResponseBody()).build()
            }.build(),
        )
        fun obj(vararg fields: Pair<String, String>) = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }
        fun envelope(data: JsonObject) = buildJsonObject { put("code", 200); put("success", true); put("data", data) }.toString()
        val order = "1234567890123"
        val transport = RechargeTransport { url, _, _ ->
            val body = when {
                url.contains("/berserker-base/user") -> user
                url.contains("/frontInfo?") -> envelope(obj("getFrontConfig" to """{"recharge":"401"}""", "getEcardConfig" to """{"type":"2"}"""))
                url.contains("/queryCard?") -> envelope(buildJsonObject { put("card", buildJsonArray { add(obj("sno" to "2099999999", "account" to "100001", "lostflag" to "0", "freezeflag" to "0")) }) })
                url.contains("/singleFeeitem?") -> buildJsonObject { put("feeitem", obj("retain_money" to "1", "maxmoney" to "500", "flag" to "00000000", "layout" to "20,50,200")) }.toString()
                url == CardRechargeProtocol.SIGNER_SOURCE -> "confirm(){let t={appid:\"12345\"};e=Object(o[\"a\"])(e,\"12345\",\"${"A".repeat(32)}\",\"SHA256\");s.action=\"/charge/order/thirdOrder\"}"
                url.endsWith("/thirdOrder") -> {
                    createCount++
                    updateCounts()
                    if (intent.getBooleanExtra("unknown", false)) "<html>synthetic unknown</html>"
                    else return@RechargeTransport RechargeResponse(302, "", CardRechargeProtocol.BASE + "/payment/index?orderid=$order")
                }
                url.contains("/getpayinfo?") -> """{"order":{"orderid":"$order"},"payList":[{"payid":"81","code":"fixture_wechat","name":"微信支付"}]}"""
                url.endsWith("/blade-pay/pay") -> {
                    payCount++
                    updateCounts()
                    val callback = URLEncoder.encode("https://mb-xyk.upc.edu.cn/payment/?orderid=$order", "UTF-8")
                    envelope(obj("orderid" to order, "paysubmit" to "<form method='POST' action='https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fixture&amp;sign=fixture&amp;package=1&amp;redirect_url=$callback'></form>"))
                }
                url.startsWith("https://wx.tenpay.com/") -> "<script>var next='weixin://wap/pay?fixture=1';</script>"
                url.contains("/personal_data?") -> buildJsonObject { put("code", 200); put("orderList", buildJsonArray { add(obj("orderid" to order, "status" to "fixture_unknown")) }) }.toString()
                else -> error("Unknown synthetic route")
            }
            RechargeResponse(200, body)
        }
        val service = CardRechargeService(history, store, transport)
        val launcher = object : CardRechargeWeChatLauncher {
            override fun isAvailable() = !intent.getBooleanExtra("no_wechat", false)
            override fun launch(uri: String): Boolean {
                CardRechargeProtocol.wechatUri(uri)
                if (promo) return false // Promotion captures never launch another Activity.
                launchCount++
                updateCounts()
                startActivity(Intent(this@CardRechargeFixtureActivity, CardRechargePaymentFixtureActivity::class.java))
                return true
            }
        }
        setContent {
            val content: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    if (!promo) {
                        Text("充值合成演示 · 不联网")
                        Text(counts)
                    }
                    CardRechargeScreen(service, { finish() }, {}, launcher)
                }
            }
            if (promo) UpcPocketTheme(darkMode = DarkMode.LIGHT, preset = ThemePreset.BLUE) { content() }
            else MaterialTheme { content() }
        }
    }

    private fun updateCounts() = runOnUiThread {
        counts = "下单 $createCount · 发起付款 $payCount · 跳转 $launchCount"
    }
}

/** A separate local activity proves the screen dispatches once; it is not the WeChat application. */
class CardRechargePaymentFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column {
                    Text("微信付款跳转 · 合成演示")
                    Text("未调用真实微信或付款")
                    Button(onClick = { finish() }) { Text("返回充值") }
                }
            }
        }
    }
}
