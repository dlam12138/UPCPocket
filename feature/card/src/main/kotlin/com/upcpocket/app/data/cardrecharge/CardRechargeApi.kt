package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.data.cardhistory.CardRechargeSession
import com.upcpocket.app.data.cardhistory.CardHistoryProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.*

internal data class RechargeResponse(val status: Int, val body: String, val location: String? = null)
internal fun interface RechargeTransport {
    fun request(url: String, fields: Map<String, String>?, token: String?): RechargeResponse
}

/** No redirects/retries, cache, cookies, logs, scripts or WebView. School credentials stay on the school host. */
internal class RechargeHttpTransport(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build()) : RechargeTransport {
    override fun request(url: String, fields: Map<String,String>?, token: String?): RechargeResponse {
        val uri = URI(url)
        val school = uri.host == "mb-xyk.upc.edu.cn"
        val wx = uri.host == "wx.tenpay.com" && uri.path == "/cgi-bin/mmpayweb-bin/checkmweb"
        if (uri.scheme != "https" || uri.port !in setOf(-1,443) || uri.rawUserInfo != null || uri.rawFragment != null || (!school && !wx) || (!school && token != null)) throw RechargeFailure("P-ORIGIN")
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/json")
            .header("Referer", CardRechargeProtocol.BASE + if(school) "/campus-card/" else "/")
            .header("Cache-Control", "no-store")
        if (token != null) {
            if (token.isBlank() || token.any { it <= ' ' || it == '\u007f' }) throw RechargeFailure("P-SESSION")
            request.header("synjones-auth", "bearer $token").header("synAccessSource", "h5")
        }
        if (fields != null) {
            val body = FormBody.Builder().apply { fields.forEach { (k,v) -> add(k,v) } }.build()
            request.header("Origin", CardRechargeProtocol.BASE).post(body)
        }
        return client.newCall(request.build()).execute().use { response ->
            val bytes = if (response.code == 200) response.body?.byteStream()?.use { stream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break
                    if (output.size() + count > 2_000_000) throw RechargeFailure("P-SIZE")
                    output.write(buffer,0,count)
                }; output.toByteArray()
            } ?: byteArrayOf() else byteArrayOf()
            RechargeResponse(response.code, bytes.toString(Charsets.UTF_8), response.header("Location"))
        }
    }
}

internal class CardRechargeApi(
    private val transport: RechargeTransport = RechargeHttpTransport(),
    private val current: () -> Boolean,
) {
    private fun checkCurrent() { if (!current()) throw RechargeFailure("P-ACCOUNT") }
    private fun request(url: String, fields: Map<String,String>? = null, token: String? = null): RechargeResponse {
        checkCurrent()
        val response = try { transport.request(url,fields,token) } catch (known: RechargeFailure) { throw known }
            catch (_: Exception) { throw RechargeFailure("P-NETWORK") }
        checkCurrent()
        if (response.status in setOf(401,403)) throw RechargeFailure("P-LOGIN")
        if (response.status !in setOf(200,301,302,303)) throw RechargeFailure("P-HTTP")
        return response
    }
    private fun get(path: String, session: CardRechargeSession): String {
        val result = request(CardRechargeProtocol.BASE + path, token = session.token)
        if (result.status != 200) throw RechargeFailure("P-API-REDIRECT")
        return result.body
    }
    fun prepare(session: CardRechargeSession): RechargePrepared {
        CardHistoryProtocol.verifyIdentity(get("/berserker-base/user?synAccessSource=h5",session), session.owner.studentId)
        val front = CardRechargeProtocol.data(get("/berserker-app/frontInfo?synAccessSource=h5",session))
        val settings = CardRechargeProtocol.json(CardRechargeProtocol.field(front,"getFrontConfig"))
        val ecard = CardRechargeProtocol.json(CardRechargeProtocol.field(front,"getEcardConfig"))
        if (CardRechargeProtocol.field(ecard,"type") != "2") throw RechargeFailure("P-CARD-TYPE")
        val fee = CardRechargeProtocol.field(settings,"recharge").takeIf { it.matches(Regex("[0-9]{1,10}")) } ?: throw RechargeFailure("P-CONFIG")
        val cards = CardRechargeProtocol.data(get("/berserker-app/ykt/tsm/queryCard?scene=recharge",session))["card"] as? JsonArray ?: throw RechargeFailure("P-CARDS")
        val own = cards.map { it as? JsonObject ?: throw RechargeFailure("P-CARDS") }.filter { CardRechargeProtocol.field(it,"sno") == session.owner.studentId }
        if (own.size != 1) throw RechargeFailure("P-CARD-OWNER")
        val card = own.single()
        for (flag in listOf("lostflag","freezeflag")) {
            if (CardRechargeProtocol.field(card,flag) !in setOf("0","false")) throw RechargeFailure("P-CARD-BLOCKED")
        }
        val account = CardRechargeProtocol.field(card,"account").takeIf { it.matches(Regex("[0-9]{1,24}")) } ?: throw RechargeFailure("P-CARD-ACCOUNT")
        val item = CardRechargeProtocol.json(get("/charge/feeitem/singleFeeitem?feeitemid=$fee",session))
        if (item["feeitem"] !is JsonObject) throw RechargeFailure("P-FEE-CONFIG")
        val script = request(CardRechargeProtocol.SIGNER_SOURCE).takeIf { it.status == 200 }?.body ?: throw RechargeFailure("P-SIGN-DOWNLOAD")
        return RechargePrepared(fee,account,CardRechargeProtocol.signer(script),CardRechargeProtocol.rules(item["feeitem"]!!.jsonObject))
    }
    private fun signed(signer: RechargeSigner, fields: Map<String,String>) = signer.sign(fields,
        LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
        UUID.randomUUID().toString().replace("-", ""))

    /** Exactly one create-order POST. Unknown responses are not retried or executed. */
    fun create(session: CardRechargeSession, prepared: RechargePrepared, yuan: String): String {
        val amount = prepared.rules.amount(yuan)
        val fields = linkedMapOf("feeitemid" to prepared.feeItem, "appid" to prepared.signer.appId,
            "tranamt" to amount, "source" to "app", "synjones-auth" to "bearer ${session.token}",
            "yktcard" to prepared.account, "synAccessSource" to "h5", "abstracts" to "{\"type\":\"recharge\",\"othersRecharge\":false}")
        var url = CardRechargeProtocol.BASE + "/charge/order/thirdOrder"
        var response = request(url,signed(prepared.signer,fields),session.token)
        repeat(8) {
            val next = if (response.status in setOf(301,302,303)) response.location else CardRechargeProtocol.literalRedirect(response.body)
            if (next == null) throw RechargeFailure("P-ORDER-REDIRECT")
            url = CardRechargeProtocol.redirect(url,next)
            CardRechargeProtocol.orderFromUrl(url)?.let { return it }
            response = request(url,token=session.token)
        }
        throw RechargeFailure("P-ORDER-REDIRECT")
    }
    fun pay(session: CardRechargeSession, prepared: RechargePrepared, pending: RechargePending, onMethod: (String) -> Unit): String {
        val orderId = pending.orderId ?: throw RechargeFailure("P-ORDER-UNKNOWN")
        val info = CardRechargeProtocol.json(get("/charge/pay/getpayinfo?orderid=$orderId&userAgent=h5&synAccessSource=h5",session))
        val order = info["order"] as? JsonObject ?: throw RechargeFailure("P-ORDER-INFO")
        if (CardRechargeProtocol.field(order,"orderid") != orderId) throw RechargeFailure("P-ORDER-OWNER")
        // The order is only the one returned by this session's single create call. Do not infer money units here.
        val list = info["payList"] as? JsonArray ?: throw RechargeFailure("P-PAY-METHOD")
        val methods = list.mapNotNull { it as? JsonObject }.filter { CardRechargeProtocol.field(it,"name").contains("微信") }
        if (methods.size != 1) throw RechargeFailure("P-PAY-METHOD")
        val method = methods.single()
        val payId = CardRechargeProtocol.field(method,"payid").takeIf { it.matches(Regex("[0-9]{1,8}")) } ?: throw RechargeFailure("P-PAY-METHOD")
        val code = CardRechargeProtocol.field(method,"code").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: throw RechargeFailure("P-PAY-METHOD")
        onMethod(payId)
        val fields = mapOf("paytypeid" to payId, "paytype" to code, "paystep" to "2", "orderid" to orderId,
            "redirect_url" to (CardRechargeProtocol.BASE + "/payment/?name=result"), "userAgent" to "h5")
        val result = request(CardRechargeProtocol.BASE + "/blade-pay/pay", signed(prepared.signer,fields),session.token)
        if (result.status != 200) throw RechargeFailure("P-PAY-RESPONSE")
        if (CardRechargeProtocol.json(result.body)["success"]?.jsonPrimitive?.booleanOrNull != true) throw RechargeFailure("P-PAY-RESPONSE")
        val data = CardRechargeProtocol.data(result.body)
        if (CardRechargeProtocol.field(data,"orderid") != orderId) throw RechargeFailure("P-ORDER-OWNER")
        val form = CardRechargeProtocol.cashierForm(CardRechargeProtocol.field(data,"paysubmit"))
        val callback = CardRechargeProtocol.query(form.first).getValue("redirect_url")
        if (CardRechargeProtocol.query(callback)["orderid"] != orderId) throw RechargeFailure("P-ORDER-OWNER")
        var url = form.first
        var wx = request(url,form.second) // No campus token/Cookie is ever sent to Tencent.
        repeat(4) {
            if (wx.status == 200) return CardRechargeProtocol.literalWechat(wx.body)
            val next = URI(url).resolve(wx.location ?: throw RechargeFailure("P-WX-REDIRECT")).toString()
            if (next.startsWith("weixin:")) return CardRechargeProtocol.wechatUri(next)
            val uri = URI(next)
            if (uri.scheme != "https" || uri.host != "wx.tenpay.com" || uri.path != "/cgi-bin/mmpayweb-bin/checkmweb" || uri.rawUserInfo != null || uri.port !in setOf(-1,443)) throw RechargeFailure("P-WX-REDIRECT")
            url = next; wx = request(url)
        }
        throw RechargeFailure("P-WX-REDIRECT")
    }
    fun status(session: CardRechargeSession, pending: RechargePending): RechargeStatus {
        val order = pending.orderId ?: throw RechargeFailure("P-ORDER-UNKNOWN")
        val root = CardRechargeProtocol.json(get("/charge/order/personal_data?orderid=$order" + (pending.payId?.let { "&payid=$it" } ?: "") + "&synAccessSource=h5",session))
        if (root["code"]?.jsonPrimitive?.intOrNull != 200) throw RechargeFailure("P-STATUS")
        val list = root["orderList"] as? JsonArray ?: throw RechargeFailure("P-STATUS")
        val info = list.singleOrNull() as? JsonObject ?: throw RechargeFailure("P-STATUS")
        if (CardRechargeProtocol.field(info,"orderid") != order) throw RechargeFailure("P-ORDER-OWNER")
        return when (CardRechargeProtocol.field(info,"status")) {
            "1" -> RechargeStatus.SUCCEEDED
            "0" -> RechargeStatus.FAILED
            "2" -> RechargeStatus.EXPIRED
            else -> RechargeStatus.UNKNOWN
        }
    }
}
