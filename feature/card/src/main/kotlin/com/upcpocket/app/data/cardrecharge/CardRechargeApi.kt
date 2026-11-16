package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.data.cardhistory.CardRechargeSession
import com.upcpocket.app.data.cardhistory.CardHistoryProtocol
import com.upcpocket.app.data.card.CardDiagnostics
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal data class RechargeResponse(
    val status: Int,
    val body: String,
    val location: String? = null,
    val transportSnapshot: DiagnosticTransportSnapshot? = null,
)
internal fun interface RechargeTransport {
    fun request(url: String, fields: Map<String, String>?, token: String?): RechargeResponse

    /** Test transports may use the synchronous fake; production overrides with a cancellable Call. */
    suspend fun preparationGet(
        url: String,
        token: String?,
        onTransport: (DiagnosticTransportSnapshot?) -> Unit,
    ): RechargeResponse = request(url, null, token)
}

/** No redirects/retries, cache, cookies, logs, scripts or WebView. School credentials stay on the school host. */
internal class RechargeHttpTransport(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build()) : RechargeTransport {
    private val preparationTransport = CardRechargeReadTransport(client)

    private fun buildRequest(url: String, fields: Map<String, String>?, token: String?): Request {
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
        return request.build()
    }

    override suspend fun preparationGet(
        url: String,
        token: String?,
        onTransport: (DiagnosticTransportSnapshot?) -> Unit,
    ): RechargeResponse {
        val request = try { buildRequest(url, fields = null, token = token) }
        catch (known: RechargeFailure) { throw known }
        catch (_: Exception) { throw CardRechargeReadFailure("P-ORIGIN", null) }
        return preparationTransport.get(request, onTransport)
    }

    override fun request(url: String, fields: Map<String,String>?, token: String?): RechargeResponse {
        val request = buildRequest(url, fields, token)
        return client.newCall(request).execute().use { response ->
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
    private fun request(url: String, fields: Map<String,String>? = null, token: String? = null, beforeDispatch: () -> Unit = {}): RechargeResponse {
        checkCurrent()
        beforeDispatch()
        val response = try { transport.request(url,fields,token) } catch (cancelled: CancellationException) { throw cancelled }
            catch (known: RechargeFailure) { throw known }
            catch (_: Exception) { throw RechargeFailure("P-NETWORK") }
        checkCurrent()
        if (response.status in setOf(401,403)) throw RechargeFailure("P-LOGIN")
        if (response.status !in setOf(200,301,302,303)) throw RechargeFailure("P-HTTP")
        // A school session may expire inside an HTTP 200 envelope. Invalidate its lease,
        // but never replay this request (in particular a create/pay POST).
        val origin = URI(url)
        if (response.status == 200 && token != null && origin.scheme == "https" &&
            origin.host == "mb-xyk.upc.edu.cn" && origin.port in setOf(-1,443) && origin.rawUserInfo == null) {
            val code = runCatching {
                (Json.parseToJsonElement(response.body).jsonObject["code"] as? JsonPrimitive)?.intOrNull
            }.getOrNull()
            if (code in setOf(401,403)) throw RechargeFailure("P-LOGIN")
        }
        return response
    }
    private suspend fun getPreparation(
        url: String,
        session: CardRechargeSession?,
        diagnostic: CardDiagnostics?,
    ): String {
        diagnostic?.clearTransport()
        currentCoroutineContext().ensureActive()
        session?.checkCurrent()
        checkCurrent()
        val result = try {
            transport.preparationGet(url, session?.token) { snapshot ->
                runCatching { snapshot?.let { diagnostic?.transport(it) } }
            }.also { response ->
                response.transportSnapshot?.let { diagnostic?.transport(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CardRechargeReadFailure) {
            failure.transport?.let { diagnostic?.transport(it) }
            throw RechargeFailure(failure.stage)
        } catch (known: RechargeFailure) {
            throw known
        } catch (_: Exception) {
            throw RechargeFailure("P-NETWORK")
        }
        currentCoroutineContext().ensureActive()
        session?.checkCurrent()
        checkCurrent()
        if (result.status in setOf(401, 403)) throw RechargeFailure("P-LOGIN")
        if (result.status !in setOf(200, 301, 302, 303)) throw RechargeFailure("P-HTTP")
        if (result.status != 200) throw RechargeFailure("P-API-REDIRECT")
        // A school session may expire in an HTTP 200 response envelope. This is not replayed.
        if (session != null) {
            val code = runCatching {
                (Json.parseToJsonElement(result.body).jsonObject["code"] as? JsonPrimitive)?.intOrNull
            }.getOrNull()
            if (code in setOf(401, 403)) throw RechargeFailure("P-LOGIN")
        }
        return result.body
    }
    private fun get(path: String, session: CardRechargeSession): String {
        val result = request(CardRechargeProtocol.BASE + path, token = session.token)
        if (result.status != 200) throw RechargeFailure("P-API-REDIRECT")
        return result.body
    }

    private suspend fun getPreparationPath(
        path: String,
        session: CardRechargeSession,
        diagnostic: CardDiagnostics?,
    ): String =
        getPreparation(CardRechargeProtocol.BASE + path, session, diagnostic)

    private suspend fun ensurePreparationCurrent(session: CardRechargeSession) {
        currentCoroutineContext().ensureActive()
        session.checkCurrent()
        checkCurrent()
    }

    suspend fun prepare(session: CardRechargeSession, diagnostic: CardDiagnostics? = null): RechargePrepared {
        var step = DiagnosticDetail.CARD_IDENTITY_VERIFY
        try {
            ensurePreparationCurrent(session)
            diagnostic?.phase = DiagnosticPhase.PERSONAL_VERIFY
            CardHistoryProtocol.verifyIdentity(getPreparationPath("/berserker-base/user?synAccessSource=h5",session,diagnostic), session.owner.studentId)
            ensurePreparationCurrent(session)
            diagnostic?.mark(step, DiagnosticResult.OK)
            step = DiagnosticDetail.CARD_RECHARGE_CONFIG
            diagnostic?.phase = DiagnosticPhase.RESPONSE
            val frontBody = getPreparationPath("/berserker-app/frontInfo?synAccessSource=h5",session,diagnostic)
            diagnostic?.phase = DiagnosticPhase.PARSE
            val front = CardRechargeProtocol.data(frontBody)
            val settings = CardRechargeProtocol.json(CardRechargeProtocol.field(front,"getFrontConfig"))
            val ecard = CardRechargeProtocol.json(CardRechargeProtocol.field(front,"getEcardConfig"))
            if (CardRechargeProtocol.field(ecard,"type") != "2") throw RechargeFailure("P-CARD-TYPE")
            val fee = CardRechargeProtocol.field(settings,"recharge").takeIf { it.matches(Regex("[0-9]{1,10}")) } ?: throw RechargeFailure("P-CONFIG")
            ensurePreparationCurrent(session)
            diagnostic?.mark(step, DiagnosticResult.OK)
            step = DiagnosticDetail.CARD_RECHARGE_CARD
            diagnostic?.phase = DiagnosticPhase.RESPONSE
            val cardBody = getPreparationPath("/berserker-app/ykt/tsm/queryCard?scene=recharge",session,diagnostic)
            diagnostic?.phase = DiagnosticPhase.PARSE
            val cards = CardRechargeProtocol.data(cardBody)["card"] as? JsonArray ?: throw RechargeFailure("P-CARDS")
            val own = cards.map { it as? JsonObject ?: throw RechargeFailure("P-CARDS") }.filter { CardRechargeProtocol.field(it,"sno") == session.owner.studentId }
            if (own.size != 1) throw RechargeFailure("P-CARD-OWNER")
            val card = own.single()
            for (flag in listOf("lostflag","freezeflag")) {
                if (CardRechargeProtocol.field(card,flag) !in setOf("0","false")) throw RechargeFailure("P-CARD-BLOCKED")
            }
            val account = CardRechargeProtocol.field(card,"account").takeIf { it.matches(Regex("[0-9]{1,24}")) } ?: throw RechargeFailure("P-CARD-ACCOUNT")
            ensurePreparationCurrent(session)
            diagnostic?.mark(step, DiagnosticResult.OK)
            step = DiagnosticDetail.CARD_RECHARGE_RULES
            diagnostic?.phase = DiagnosticPhase.RESPONSE
            val itemBody = getPreparationPath("/charge/feeitem/singleFeeitem?feeitemid=$fee",session,diagnostic)
            val itemTransport = diagnostic?.currentTransport()
            diagnostic?.phase = DiagnosticPhase.PARSE
            val item = CardRechargeProtocol.json(itemBody)
            if (item["feeitem"] !is JsonObject) throw RechargeFailure("P-FEE-CONFIG")
            ensurePreparationCurrent(session)
            step = DiagnosticDetail.CARD_RECHARGE_SIGNER
            diagnostic?.phase = DiagnosticPhase.RESPONSE
            val script = getPreparation(CardRechargeProtocol.SIGNER_SOURCE, null, diagnostic)
            diagnostic?.phase = DiagnosticPhase.PARSE
            val signer = CardRechargeProtocol.signer(script)
            ensurePreparationCurrent(session)
            diagnostic?.mark(step, DiagnosticResult.OK)
            // Preserve the existing request and parser order: rules are parsed after the signer.
            step = DiagnosticDetail.CARD_RECHARGE_RULES
            diagnostic?.clearTransport()
            itemTransport?.let { diagnostic?.transport(it) }
            ensurePreparationCurrent(session)
            val rules = CardRechargeProtocol.rules(item["feeitem"]!!.jsonObject,settings)
            ensurePreparationCurrent(session)
            diagnostic?.mark(step, DiagnosticResult.OK)
            return RechargePrepared(fee,account,signer,rules)
        } catch (error: Throwable) {
            diagnostic?.failedStep(step, error)
            throw error
        }
    }
    private fun signed(signer: RechargeSigner, fields: Map<String,String>) = signer.sign(fields,
        LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
        UUID.randomUUID().toString().replace("-", ""))

    /** Exactly one create-order POST. Unknown responses are not retried or executed. */
    fun create(
        session: CardRechargeSession,
        prepared: RechargePrepared,
        yuan: String,
        diagnostic: CardDiagnostics = CardDiagnostics.begin(DiagnosticDetail.CARD_ORDER_CREATE, DiagnosticPhase.RESPONSE, pending = false),
    ): String {
        val amount = prepared.rules.amount(yuan)
        val fields = linkedMapOf("feeitemid" to prepared.feeItem, "appid" to prepared.signer.appId,
            "tranamt" to amount, "source" to "app", "synjones-auth" to "bearer ${session.token}",
            "yktcard" to prepared.account, "synAccessSource" to "h5", "abstracts" to "{\"type\":\"recharge\",\"othersRecharge\":false}")
        var url = CardRechargeProtocol.BASE + "/charge/order/thirdOrder"
        val signedFields = signed(prepared.signer,fields)
        // The service has already committed the durable pending marker before this point.
        diagnostic.start(DiagnosticPhase.RESPONSE)
        var dispatched = false
        try {
            var response = request(url,signedFields,session.token) { dispatched = true }
            repeat(8) {
                diagnostic.phase = DiagnosticPhase.PARSE
                val next = if (response.status in setOf(301,302,303)) response.location else CardRechargeProtocol.literalRedirect(response.body)
                if (next == null) throw RechargeFailure("P-ORDER-REDIRECT")
                url = CardRechargeProtocol.redirect(url,next)
                CardRechargeProtocol.orderFromUrl(url)?.let { diagnostic.finish(); return it }
                diagnostic.phase = DiagnosticPhase.RESPONSE
                response = request(url,token=session.token)
            }
            throw RechargeFailure("P-ORDER-REDIRECT")
        } catch (error: Throwable) {
            diagnostic.fail(error, uncertain = dispatched)
            throw error
        }
    }
    fun pay(session: CardRechargeSession, prepared: RechargePrepared, pending: RechargePending, onMethod: (String) -> Unit): String {
        val preparation = CardDiagnostics.begin(DiagnosticDetail.CARD_PAYMENT_PREPARE, DiagnosticPhase.RESPONSE)
        var submission: CardDiagnostics? = null
        var dispatched = false
        try {
            val orderId = pending.orderId ?: throw RechargeFailure("P-ORDER-UNKNOWN")
            val infoBody = get("/charge/pay/getpayinfo?orderid=$orderId&userAgent=h5&synAccessSource=h5",session)
            preparation.phase = DiagnosticPhase.PARSE
            val info = CardRechargeProtocol.json(infoBody)
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
            val signedFields = signed(prepared.signer,fields)
            preparation.finish()
            val payment = CardDiagnostics.begin(DiagnosticDetail.CARD_PAYMENT_SUBMIT, DiagnosticPhase.RESPONSE)
            submission = payment
            val result = request(CardRechargeProtocol.BASE + "/blade-pay/pay", signedFields,session.token) { dispatched = true }
            payment.phase = DiagnosticPhase.PARSE
            if (result.status != 200) throw RechargeFailure("P-PAY-RESPONSE")
            if (CardRechargeProtocol.json(result.body)["success"]?.jsonPrimitive?.booleanOrNull != true) throw RechargeFailure("P-PAY-RESPONSE")
            val data = CardRechargeProtocol.data(result.body)
            if (CardRechargeProtocol.field(data,"orderid") != orderId) throw RechargeFailure("P-ORDER-OWNER")
            val form = CardRechargeProtocol.cashierForm(CardRechargeProtocol.field(data,"paysubmit"))
            val callback = CardRechargeProtocol.query(form.first).getValue("redirect_url")
            if (CardRechargeProtocol.query(callback)["orderid"] != orderId) throw RechargeFailure("P-ORDER-OWNER")
            var url = form.first
            payment.phase = DiagnosticPhase.RESPONSE
            var wx = request(url,form.second) // No campus token/Cookie is ever sent to Tencent.
            repeat(4) {
                payment.phase = DiagnosticPhase.PARSE
                if (wx.status == 200) return CardRechargeProtocol.literalWechat(wx.body).also { payment.finish() }
                val next = URI(url).resolve(wx.location ?: throw RechargeFailure("P-WX-REDIRECT")).toString()
                if (next.startsWith("weixin:")) return CardRechargeProtocol.wechatUri(next).also { payment.finish() }
                val uri = URI(next)
                if (uri.scheme != "https" || uri.host != "wx.tenpay.com" || uri.path != "/cgi-bin/mmpayweb-bin/checkmweb" || uri.rawUserInfo != null || uri.port !in setOf(-1,443)) throw RechargeFailure("P-WX-REDIRECT")
                url = next
                payment.phase = DiagnosticPhase.RESPONSE
                wx = request(url)
            }
            throw RechargeFailure("P-WX-REDIRECT")
        } catch (error: Throwable) {
            preparation.fail(error)
            submission?.fail(error, uncertain = dispatched)
            throw error
        }
    }
    fun status(session: CardRechargeSession, pending: RechargePending, diagnostic: CardDiagnostics? = null): RechargeStatus {
        val order = pending.orderId ?: throw RechargeFailure("P-ORDER-UNKNOWN")
        diagnostic?.phase = DiagnosticPhase.RESPONSE
        val body = get("/charge/order/personal_data?orderid=$order" + (pending.payId?.let { "&payid=$it" } ?: "") + "&synAccessSource=h5",session)
        diagnostic?.phase = DiagnosticPhase.PARSE
        val root = CardRechargeProtocol.json(body)
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
