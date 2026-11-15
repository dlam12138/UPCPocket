package com.upcpocket.app.data.cardrecharge

import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal class RechargeFailure(val stage: String) : IOException(stage)
internal class RechargeSigner(val appId: String, private val material: String) {
    override fun toString() = "RechargeSigner([REDACTED])"
    fun sign(fields: Map<String, String>, timestamp: String, nonce: String): Map<String, String> {
        require(timestamp.matches(Regex("[0-9]{14}")) && nonce.matches(Regex("[a-z0-9]{8,64}")))
        val all = fields + mapOf("APP_ID" to appId, "TIMESTAMP" to timestamp, "SIGN_TYPE" to "SHA256", "NONCE" to nonce)
        val raw = all.filterKeys { it != "SIGN" && it != "SECRET_KEY" }.filterValues { it.isNotEmpty() }
            .toSortedMap().entries.joinToString("&", postfix = "&SECRET_KEY=$material") { "${it.key}=${it.value}" }
        return all + ("SIGN" to MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) })
    }
}

internal data class RechargePrepared(val feeItem: String, val account: String, val signer: RechargeSigner, val rules: RechargeRules) {
    override fun toString() = "RechargePrepared([REDACTED])"
}

/** School values are yuan; neither a test cap nor a guessed fallback is used. */
data class RechargeRules(val minimum: BigDecimal, val maximum: BigDecimal, val presets: List<BigDecimal>, val presetOnly: Boolean, val integerOnly: Boolean) {
    fun amount(value: String): String {
        val amount = CardRechargeProtocol.amount(value)
        val decimal = BigDecimal(amount)
        if(decimal < minimum || decimal > maximum ||
            (integerOnly && decimal.stripTrailingZeros().scale() > 0) ||
            (presetOnly && presets.none { it.compareTo(decimal) == 0 })) throw RechargeFailure("P-AMOUNT")
        return amount
    }
}

internal object CardRechargeProtocol {
    const val BASE = "https://mb-xyk.upc.edu.cn"
    const val SIGNER_SOURCE = "$BASE/campus-card/js/chunk-1186b329.e153ac09.js"
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun amount(value: String): String {
        if (!value.matches(Regex("(?:0|[1-9][0-9]{0,10})(?:\\.[0-9]{1,2})?"))) throw RechargeFailure("P-AMOUNT")
        val decimal = BigDecimal(value)
        if (decimal <= BigDecimal.ZERO) throw RechargeFailure("P-AMOUNT")
        return decimal.stripTrailingZeros().toPlainString()
    }
    fun rules(item: JsonObject): RechargeRules {
        fun bound(key: String): BigDecimal {
            val raw = field(item, key)
            if (!raw.matches(Regex("(?:0|[1-9][0-9]{0,10})(?:\\.[0-9]{1,2})?"))) throw RechargeFailure("P-FEE-CONFIG")
            return BigDecimal(raw)
        }
        val minimum = bound("retain_money")
        val maximum = bound("maxmoney")
        if (minimum < BigDecimal.ZERO || maximum <= BigDecimal.ZERO || minimum > maximum) throw RechargeFailure("P-FEE-CONFIG")
        val flag = field(item, "flag")
        if (flag.length < 8 || flag[7] !in "012") throw RechargeFailure("P-FEE-CONFIG")
        val onlyPresets = flag[7] == '1'
        val integerOnly = flag[7] == '2'
        val presets = field(item,"layout").split(',').filter { it.isNotBlank() }.map { raw ->
            val amount = runCatching { BigDecimal(amount(raw.trim())) }.getOrElse { throw RechargeFailure("P-FEE-CONFIG") }
            amount
        }.filter { it >= minimum && it <= maximum && (!integerOnly || it.stripTrailingZeros().scale() <= 0) }
            .distinctBy { it.stripTrailingZeros() }
        if (presets.size > 30 || onlyPresets && presets.isEmpty()) throw RechargeFailure("P-FEE-CONFIG")
        return RechargeRules(minimum, maximum, presets, onlyPresets, integerOnly)
    }
    fun signer(source: String): RechargeSigner {
        val start = source.indexOf("confirm(){")
        val end = source.indexOf("/charge/order/thirdOrder", start)
        if (start < 0 || end < start || end - start > 20_000) throw RechargeFailure("P-SIGN-FORMAT")
        val scope = source.substring(start, end)
        val matches = Regex("Object\\(o\\[\"a\"\\]\\)\\(e,\"([0-9]{3,10})\",\"([A-Za-z0-9]{32})\",\"SHA256\"\\)").findAll(scope).toList()
        if (matches.size != 1) throw RechargeFailure("P-SIGN-FORMAT")
        val groups = matches.single().groupValues
        if (!scope.contains("appid:\"${groups[1]}\"")) throw RechargeFailure("P-SIGN-FORMAT")
        return RechargeSigner(groups[1], groups[2])
    }
    fun json(body: String): JsonObject = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw RechargeFailure("P-JSON") }
    fun data(body: String): JsonObject {
        val root = json(body)
        if (root["code"]?.jsonPrimitive?.intOrNull != 200) throw RechargeFailure("P-RESULT")
        return root["data"] as? JsonObject ?: throw RechargeFailure("P-DATA")
    }
    fun field(data: JsonObject, name: String) = (data[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun query(url: String): Map<String, String> {
        val fields = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        if (fields.map { it.first }.distinct().size != fields.size) throw RechargeFailure("P-URL")
        return fields.toMap()
    }
    fun schoolUrl(value: String): String {
        val uri = URI(value)
        if (uri.host != "mb-xyk.upc.edu.cn" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443) || uri.scheme != "https") throw RechargeFailure("P-ORIGIN")
        return value
    }
    fun redirect(from: String, value: String): String {
        val resolved = URI(from).resolve(value)
        val result = if (resolved.scheme == "http" && resolved.host == "mb-xyk.upc.edu.cn" && resolved.port in setOf(-1, 80) && resolved.rawUserInfo == null)
            "https://mb-xyk.upc.edu.cn${resolved.rawPath}" + (resolved.rawQuery?.let { "?$it" } ?: "") else resolved.toString()
        return schoolUrl(result)
    }
    fun orderFromUrl(url: String): String? {
        schoolUrl(url)
        if (URI(url).path !in setOf("/payment/", "/payment", "/payment/index")) return null
        return query(url)["orderid"]?.takeIf { it.matches(Regex("[0-9]{8,40}")) }
    }
    fun literalRedirect(html: String): String? {
        val script = Regex("(?is)<script\\b[^>]*>(.*?)</script>").findAll(html).joinToString("\n") { it.groupValues[1] }
        return Regex("(?:window\\.)?location(?:\\.href)?\\s*=\\s*([\"'])([^'\"\\r\\n]+)\\1").find(script)?.groupValues?.get(2)
            ?: Regex("(?:window\\.)?location\\.(?:replace|assign)\\(\\s*([\"'])([^'\"\\r\\n]+)\\1\\s*\\)").find(script)?.groupValues?.get(2)
    }
    fun cashierForm(html: String): Pair<String, Map<String, String>> {
        val forms = Regex("(?is)<form\\b[^>]*>.*?</form>").findAll(html).toList()
        if (forms.size != 1) throw RechargeFailure("P-WX-FORM")
        fun attr(tag: String, key: String) = Regex("(?i)\\b$key\\s*=\\s*([\"'])(.*?)\\1").find(tag)?.groupValues?.get(2)?.replace("&amp;", "&")
        val form = forms.single().value
        if (!attr(form.substringBefore('>'), "method").orEmpty().equals("POST", true)) throw RechargeFailure("P-WX-FORM")
        val action = attr(form.substringBefore('>'), "action") ?: throw RechargeFailure("P-WX-FORM")
        val uri = URI(action)
        if (uri.scheme != "https" || uri.host != "wx.tenpay.com" || uri.path != "/cgi-bin/mmpayweb-bin/checkmweb" || uri.port !in setOf(-1,443) || uri.rawUserInfo != null || uri.rawFragment != null) throw RechargeFailure("P-WX-ORIGIN")
        val keys = query(action)
        if (!keys.keys.containsAll(setOf("prepay_id", "sign", "package", "redirect_url")) || keys.values.any { it.length > 8192 }) throw RechargeFailure("P-WX-FORM")
        val callback = redirect(BASE + "/payment/",keys.getValue("redirect_url"))
        if (URI(callback).path !in setOf("/payment/","/payment","/payment/result")) throw RechargeFailure("P-WX-CALLBACK")
        val inputs = Regex("(?is)<input\\b[^>]*>").findAll(form).mapNotNull {
            if (!attr(it.value, "type").orEmpty().equals("hidden", true)) throw RechargeFailure("P-WX-FORM")
            val name = attr(it.value, "name").orEmpty()
            if (name.isEmpty()) null else name to attr(it.value, "value").orEmpty()
        }.toList()
        // The observed Tencent handoff has only an empty-name/empty-value input.
        // Never relay arbitrary dynamic hidden fields (especially school credentials).
        if (inputs.isNotEmpty()) throw RechargeFailure("P-WX-FORM")
        return action to inputs.toMap()
    }
    fun wechatUri(value: String): String {
        val uri = URI(value)
        if (value.length > 16384 || value.any { it <= ' ' || it == '\u007f' } || uri.scheme != "weixin" || uri.host != "wap" || uri.path != "/pay" || uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery.isNullOrBlank()) throw RechargeFailure("P-WX-URI")
        return value
    }
    fun literalWechat(html: String): String {
        val values = Regex("([\"'])(weixin:(?:\\\\.|[^\\\\\\r\\n])*?)\\1").findAll(html).map { match ->
            match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&").replace("\\x26", "&").replace("&amp;", "&")
        }.distinct().toList()
        if (values.size != 1) throw RechargeFailure("P-WX-SCRIPT")
        return wechatUri(values.single())
    }
}
