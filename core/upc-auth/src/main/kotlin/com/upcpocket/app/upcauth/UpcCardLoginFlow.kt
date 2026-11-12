package com.upcpocket.app.upcauth

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.*

internal data class CardCasForm(val url: String, val fields: Map<String, String>, val captchaId: String? = null) {
    override fun toString() = "CardCasForm([REDACTED])"
}
internal class CardCasRejected(val form: CardCasForm) : IOException("N-CAS：学校未接受本次登录，请核对账号、密码或验证码后重试")

internal object CardCasProtocol {
    fun redirect(base: String, location: String): String {
        val resolved = URI(base).resolve(location)
        val url = if (resolved.scheme == "http" && resolved.host == CardAuthOrigins.HOST &&
            resolved.rawUserInfo == null && resolved.port in setOf(-1, 80)) {
            "https://" + CardAuthOrigins.HOST + resolved.rawPath +
                (resolved.rawQuery?.let { "?$it" } ?: "") + (resolved.rawFragment?.let { "#$it" } ?: "")
        } else resolved.toString()
        if (!CardAuthOrigins.allowed(url)) throw IOException("N-CAS-REDIRECT：学校跳转到未核对的服务")
        return url
    }
    fun query(url: String, key: String): String? = URI(url).rawQuery?.split('&')?.firstNotNullOfOrNull {
        val pair = it.split('=', limit = 2)
        if (URLDecoder.decode(pair[0], "UTF-8") == key) URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8") else null
    }
    fun encode(fields: Map<String, String>) = fields.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
    private fun attribute(tag: String, name: String): String? =
        Regex("\\b" + name + "\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(2)?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")

    fun form(html: String, url: String): CardCasForm {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "cas.upc.edu.cn" || uri.path != "/cas/login" ||
            uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) throw IOException("N-CAS-URL：学校登录地址不匹配")
        val form = Regex("(?is)<form\\b[^>]*id=[\"']loginForm[\"'][^>]*>(.*?)</form>").find(html)?.value
            ?: throw IOException("N-CAS-FORM：学校登录表单已变化")
        val fields = Regex("(?is)<input\\b[^>]*>").findAll(form).mapNotNull {
            val name = attribute(it.value, "name") ?: return@mapNotNull null
            name to attribute(it.value, "value").orEmpty()
        }.toMap()
        if (fields["execution"].isNullOrBlank() || fields["_eventId"] != "submit" || fields["type"] != "username_password")
            throw IOException("N-CAS-FORM：学校登录表单未通过校验")
        val captcha = Regex("config\\.captcha\\s*=\\s*\\{[^}]*?['\"]?id['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if ((Regex("name=[\"']captcha[\"']").containsMatchIn(form) || html.contains("config.captcha =")) && captcha == null)
            throw IOException("N-CAS-CAPTCHA：学校验证码结构待核对，未提交密码")
        return CardCasForm(url, fields, captcha)
    }
    fun body(form: CardCasForm, username: String, password: String, captcha: String): String {
        require(username.matches(Regex("[0-9]{8,16}")) && password.isNotBlank())
        if (form.captchaId != null && captcha.isBlank()) throw IOException("N-CAS-CAPTCHA：请输入图片验证码")
        return encode(form.fields.filterKeys { it !in setOf("username", "password", "captcha") } +
            mapOf("username" to username, "password" to password, "submit" to "LOGIN") +
            if (form.captchaId != null) mapOf("captcha" to captcha.trim().lowercase()) else emptyMap())
    }
    fun ticket(url: String): String? = if (CardAuthOrigins.tokenPage(url)) query(url, "ticket")?.takeIf { it.isNotBlank() } else null

    // Official plat root created() adopts synjones-auth as its LoginAction token.
    // This is a completed login result, distinct from loginTransit's exchangeable ticket.
    fun directToken(url: String): String? {
        if (!CardAuthOrigins.tokenPage(url)) return null
        val raw = query(url, "synjones-auth") ?: return null
        if (query(url, "visitor") == "1") throw IOException("N-CAS-VISITOR：学校返回访客会话，尚未完成本人登录")
        val token = if (raw.startsWith("bearer ", ignoreCase = true)) raw.substring(7) else raw
        if (token.isBlank() || token.length > 16384 || token.any { it <= ' ' || it == '\u007f' })
            throw IOException("N-CAS-AUTH：学校返回的登录令牌格式不完整")
        return token
    }

    fun loginResult(url: String): Boolean = directToken(url) != null || ticket(url) != null

    /** Read only literal browser redirects from the official callback; never execute JavaScript. */
    fun pageRedirect(body: String, url: String): String? {
        if (URI(url).host != CardAuthOrigins.HOST) return null
        val text = Regex("(?is)<script\\b[^>]*>(.*?)</script>").findAll(body).joinToString("\n") { it.groupValues[1] }
        val assignment = Regex("(?:window\\.)?location\\.href\\s*=\\s*([\"'])([^\r\n]*?)\\1\\s*[;]?")
            .find(text)?.groupValues?.get(2)
        val call = Regex("(?:window\\.)?location\\.(?:replace|assign)\\s*\\(\\s*([\"'])([^\r\n]*?)\\1\\s*\\)")
            .find(text)?.groupValues?.get(2)
        val target = assignment ?: call ?: return null
        return redirect(url, target.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&"))
    }

    fun callbackDiagnostic(url: String, body: String): String {
        val uri = URI(url)
        val page = when {
            uri.path.startsWith("/berserker-auth/cas/login/") -> "校园卡回调"
            uri.path.startsWith("/plat") -> "校园卡前端"
            else -> "其它学校页面"
        }
        val keys = uri.rawQuery.orEmpty().split('&').map { it.substringBefore('=') }
            .filter { it.matches(Regex("[A-Za-z_][A-Za-z_0-9-]{0,31}")) }.distinct().take(8).joinToString(",")
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val code = (root?.get("code") as? JsonPrimitive)?.intOrNull
        val format = when { root != null -> "JSON"; body.contains("<script", true) -> "HTML脚本"; body.contains('<') -> "HTML"; else -> "文本" }
        return "$page/$format" + (code?.let { "/code=$it" } ?: "") + if (keys.isEmpty()) "" else "/参数:$keys"
    }
}

/** An isolated in-memory CAS cookie jar. Password POSTs are sent only to the exact HTTPS CAS form. */
internal class UpcCardLoginFlow(
    private val transport: UpcAuthTransport,
    private val progress: (String) -> Unit = {},
) {
    private val cookies = UpcAuthCookieJar(setOf("cas.upc.edu.cn", CardAuthOrigins.HOST))
    private data class Response(val url: String, val status: Int, val location: String?, val bytes: ByteArray)

    fun begin(): CardCasForm {
        val response = follow(CardAuthOrigins.ENTRY)
        val form = CardCasProtocol.form(response.bytes.toString(Charsets.UTF_8), response.url)
        CardCasProtocol.query(form.url, "service")?.let { service ->
            cookies.save(form.url, listOf("service=$service; Path=/; Secure"))
        }
        return form
    }

    fun login(form: CardCasForm, username: String, password: String, captcha: String): String {
        val loginUri = URI(form.url)
        if (loginUri.scheme != "https" || loginUri.host != "cas.upc.edu.cn" || loginUri.path != "/cas/login" ||
            loginUri.rawUserInfo != null || loginUri.port !in setOf(-1, 443)) throw IOException("N-CAS-URL：登录地址不匹配")
        val response = follow(form.url, CardCasProtocol.body(form, username, password, captcha))
        CardCasProtocol.directToken(response.url)?.let {
            progress("已收到学校直接返回的校园卡令牌…")
            return it
        }
        val ticket = CardCasProtocol.ticket(response.url) ?: run {
            if (URI(response.url).host == "cas.upc.edu.cn")
                throw CardCasRejected(CardCasProtocol.form(response.bytes.toString(Charsets.UTF_8), response.url))
            throw IOException("N-CAS-TICKET：未取得新系统票据（" + CardCasProtocol.callbackDiagnostic(response.url, response.bytes.toString(Charsets.UTF_8)) + "）")
        }
        val body = CardCasProtocol.encode(mapOf("username" to ticket, "password" to ticket,
            "grant_type" to "password", "scope" to "all", "loginFrom" to "h5", "logintype" to "sso",
            "device_token" to "h5", "synAccessSource" to "h5"))
        val result = request(CardAuthOrigins.BASE + "/berserker-auth/oauth/token", body,
            mapOf("Authorization" to "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"))
        val root = runCatching { Json.parseToJsonElement(result.bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw IOException("N-CAS-TOKEN：新系统未返回登录令牌")
        return (root["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("N-CAS-TOKEN：学校票据兑换未成功")
    }

    private fun follow(start: String, post: String? = null): Response {
        var url = start
        var body = post
        repeat(12) {
            val response = request(url, body)
            if (response.status !in 300..399) {
                val target = CardCasProtocol.pageRedirect(response.bytes.toString(Charsets.UTF_8), response.url)
                    ?: return response
                url = target; body = null
                if (CardCasProtocol.loginResult(url)) return Response(url, 200, null, byteArrayOf())
                return@repeat
            }
            if (response.status !in setOf(301, 302, 303)) throw IOException("N-CAS-REDIRECT：学校登录跳转方式待核对")
            // The server redirects its HTTP callback to HTTPS. Go directly to HTTPS while
            // preserving the CAS service parameter and ticket query byte-for-byte.
            url = CardCasProtocol.redirect(url, response.location ?: throw IOException("N-CAS-REDIRECT：缺少跳转地址"))
            body = null
            if (CardCasProtocol.loginResult(url)) return Response(url, 200, null, byteArrayOf())
        }
        throw IOException("N-CAS-REDIRECT：学校登录跳转次数过多")
    }

    private fun request(url: String, body: String?, extra: Map<String, String> = emptyMap()): Response {
        if (!CardAuthOrigins.allowed(url)) throw IOException("N-CAS-URL：登录地址不匹配")
        val uri = URI(url)
        val (step, description) = when {
            uri.host == "cas.upc.edu.cn" && body != null -> "CAS-SUBMIT" to "正在提交学校账号验证…"
            uri.host == "cas.upc.edu.cn" && uri.path == "/cas/captcha" -> "CAPTCHA" to "正在获取学校验证码…"
            uri.host == "cas.upc.edu.cn" -> "CAS-FORM" to "正在获取学校登录表单…"
            uri.path == "/berserker-auth/oauth/token" -> "TOKEN" to "正在将学校票据换为校园卡令牌…"
            uri.path == "/berserker-auth/cas/login/wislearn" -> "CAS-CALLBACK" to "正在请求校园卡登录回调…"
            else -> "CAS-ENTRY" to "正在连接校园卡登录服务…"
        }
        progress(description)
        if (body != null && !((uri.scheme == "https" && uri.host == "cas.upc.edu.cn" && uri.path == "/cas/login") ||
                (uri.scheme == "https" && uri.host == CardAuthOrigins.HOST && uri.path == "/berserker-auth/oauth/token")))
            throw IOException("N-CAS-URL：拒绝向此地址提交登录资料")
        val headers = linkedMapOf(
            "Accept" to "text/html,application/json,image/*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        cookies.header(url)?.let { headers["Cookie"] = it }
        headers.putAll(extra)
        if (body != null) {
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            headers["Origin"] = "https://" + uri.host
            headers["Referer"] = if (uri.host == "cas.upc.edu.cn") url else CardAuthOrigins.BASE + "/plat/"
            if (uri.host == CardAuthOrigins.HOST) headers["synAccessSource"] = "h5"
        }
        val response = transport.execute(UpcAuthHttpRequest(if (body == null) "GET" else "POST", url, headers, body))
        cookies.save(url, response.headers["set-cookie"].orEmpty())
        if (response.status >= 400) throw IOException("N-CAS-HTTP：学校登录服务暂不可用")
        return Response(url, response.status, response.header("Location"), response.body)
    }
}

internal object CardAuthOrigins {
    const val HOST = "mb-xyk.upc.edu.cn"
    const val BASE = "https://$HOST"
    const val ENTRY = "$BASE/berserker-auth/cas/redirect/wislearn?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit"

    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.rawUserInfo != null) return@runCatching false
        when {
            uri.scheme == "https" && uri.port in setOf(-1, 443) -> uri.host in setOf(HOST, "cas.upc.edu.cn", "i.upc.edu.cn")
            else -> false
        }
    }.getOrDefault(false)

    fun tokenPage(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == HOST && uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
            (uri.path == "/plat" || uri.path.startsWith("/plat/") || uri.path.startsWith("/campus-card/"))
    }.getOrDefault(false)

}
