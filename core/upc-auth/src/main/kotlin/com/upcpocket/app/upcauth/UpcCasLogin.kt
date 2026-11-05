package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolLoginCredential
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 一个正常 CAS service 的严格边界。
 *
 * [serviceUrl] 是 CAS 回调 service 的解码值；[callbackHost]/[callbackPath] 是该 service
 * 唯一允许的业务回跳目标。CAS 登录页和业务回调都必须精确匹配，不能只按域名放行。
 */
internal data class UpcCasService(
    val casHost: String,
    val serviceUrl: String,
    val callbackHost: String,
    val callbackPath: String,
    val callbackParameterNames: Set<String>,
    val maxRedirectHops: Int = 8,
) {
    fun isCasLoginUrl(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != casHost ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/cas/login" || url.fragment != null
        ) {
            return false
        }
        val names = url.queryParameterNames
        if (names.any { it !in setOf("service", "from", "noAutoRedirect") }) return false
        names.forEach { name ->
            if (url.queryParameterValues(name).size != 1) return false
        }
        if (url.queryParameter("service") != serviceUrl) return false
        url.queryParameter("from")?.let { if (it != "wap") return false }
        url.queryParameter("noAutoRedirect")?.let { if (it != "1") return false }
        return true
    }

    fun isCallbackUrl(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != callbackHost ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != callbackPath || url.fragment != null
        ) {
            return false
        }
        val names = url.queryParameterNames
        if (names.any { it !in callbackParameterNames }) return false
        names.forEach { name ->
            if (url.queryParameterValues(name).size != 1) return false
        }
        val ticket = url.queryParameter("ticket") ?: return false
        return ticket.startsWith("ST-") && ticket.length in 8..4096
    }

}

internal sealed interface UpcCasLoginResult {
    data class Callback(val url: HttpUrl, val session: UpcCasHttpSession) : UpcCasLoginResult

    data class Failure(
        val reason: UpcAuthFailure,
        val interactionUrl: String? = null,
    ) : UpcCasLoginResult
}

/**
 * CAS 表单登录的公共部分：只向精确的 CAS HTTPS 表单提交一次密码，拿到 service 回调
 * 票据后交给业务 provider 继续。密码、Cookie、票据都不离开本模块。
 */
internal class UpcCasLogin(
    private val service: UpcCasService,
    private val transport: UpcAuthTransport,
) {
    @OptIn(RestrictedSchoolCredentialUse::class)
    fun login(
        credential: SchoolLoginCredential,
        casUrl: String,
        existingSession: UpcCasHttpSession? = null,
    ): UpcCasLoginResult {
        val cas = casUrl.toHttpUrlOrNull()
            ?: return UpcCasLoginResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        if (!service.isCasLoginUrl(cas)) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        }
        val session = existingSession ?: UpcCasHttpSession(
            transport,
            UpcAuthCookieJar(setOf(service.casHost, service.callbackHost)),
        )
        val page = try {
            session.request("GET", casUrl)
        } catch (failure: UpcAuthTransportException) {
            return UpcCasLoginResult.Failure(failure.toAuthFailure())
        }
        if (page.status in 300..399) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        }
        if (page.status == 401 || page.status == 403) {
            return challengeOrDenied(page.text(), casUrl)
        }
        if (page.status == 429) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.ACCESS_DENIED)
        }
        if (page.status != 200) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }

        val form = parseCasLoginForm(page.text())
        if (form.captchaRequired) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.INTERACTION_REQUIRED, casUrl)
        }
        if (!form.hasPassword || form.execution.isNullOrBlank()) {
            val interaction = form.hasSecondFactor
            return UpcCasLoginResult.Failure(
                if (interaction) UpcAuthFailure.INTERACTION_REQUIRED else UpcAuthFailure.PROTOCOL_ERROR,
                if (interaction) casUrl else null,
            )
        }
        val action = resolveCasFormAction(form.action, casUrl, service)
            ?: return UpcCasLoginResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)

        val body = credential.usePassword { password ->
            buildCasFormBody(
                username = credential.loginName,
                password = String(password),
                execution = form.execution,
                type = form.type,
                eventId = form.eventId,
                submit = form.submit,
            )
        }
        val submitted = try {
            session.request(
                "POST",
                action,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to "https://${service.casHost}",
                    "Referer" to casUrl,
                ),
                body = body,
            )
        } catch (failure: UpcAuthTransportException) {
            return UpcCasLoginResult.Failure(failure.toAuthFailure())
        }

        if (submitted.status in 300..399) {
            val location = submitted.header("Location")
                ?: return UpcCasLoginResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            val target = resolveHttpUrl(action, location)
                ?: return UpcCasLoginResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            return when {
                service.isCasLoginUrl(target) -> classifyCasReturn(session, target, casUrl)
                service.isCallbackUrl(target) -> UpcCasLoginResult.Callback(target, session)
                else -> UpcCasLoginResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            }
        }
        if (submitted.status == 401 || submitted.status == 403) {
            return challengeOrDenied(submitted.text(), casUrl)
        }
        if (submitted.status == 429) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.ACCESS_DENIED)
        }
        if (submitted.status != 200) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        val returned = parseCasLoginForm(submitted.text())
        if (returned.captchaRequired) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.INTERACTION_REQUIRED, casUrl)
        }
        return UpcCasLoginResult.Failure(
            if (returned.hasPassword) {
                UpcAuthFailure.INVALID_CREDENTIALS
            } else {
                UpcAuthFailure.UPSTREAM_ERROR
            },
        )
    }

    /**
     * CAS 用 401/403 返回登录页/验证码页是正常的挑战，不是“账号被拒绝”。
     * 必须保留 interactionUrl，让用户在学校页面完成验证码/二次验证；
     * 只有没有可交互表单时才返回 ACCESS_DENIED。
     */
    private fun challengeOrDenied(
        html: String,
        casUrl: String,
    ): UpcCasLoginResult {
        val challenge = runCatching { parseCasLoginForm(html) }.getOrNull()
        return if (
            challenge != null &&
            (challenge.captchaRequired || challenge.hasPassword || challenge.hasSecondFactor)
        ) {
            UpcCasLoginResult.Failure(UpcAuthFailure.INTERACTION_REQUIRED, casUrl)
        } else {
            UpcCasLoginResult.Failure(UpcAuthFailure.ACCESS_DENIED)
        }
    }

    private fun classifyCasReturn(
        session: UpcCasHttpSession,
        url: HttpUrl,
        interactionUrl: String,
    ): UpcCasLoginResult {
        val response = try {
            session.request("GET", url.toString())
        } catch (failure: UpcAuthTransportException) {
            return UpcCasLoginResult.Failure(failure.toAuthFailure())
        }
        if (response.status != 200) {
            return UpcCasLoginResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        val form = parseCasLoginForm(response.text())
        return when {
            form.errorText != null && form.errorText.isNotBlank() ->
                UpcCasLoginResult.Failure(UpcAuthFailure.INVALID_CREDENTIALS)
            form.captchaRequired ->
                UpcCasLoginResult.Failure(UpcAuthFailure.INTERACTION_REQUIRED, interactionUrl)
            form.hasPassword ->
                UpcCasLoginResult.Failure(UpcAuthFailure.INVALID_CREDENTIALS)
            else ->
                UpcCasLoginResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
    }
}

/** 认证期间的内存 Cookie，只保存 CAS 与目标业务主机。 */
internal class UpcAuthCookieJar(private val allowedHosts: Set<String>) {
    private val cookies = mutableListOf<Cookie>()

    fun save(url: String, setCookieHeaders: List<String>) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        setCookieHeaders.forEach { header ->
            val parsed = Cookie.parse(httpUrl, header) ?: return@forEach
            val domain = parsed.domain.lowercase(Locale.ROOT).trimStart('.')
            if (domain !in allowedHosts) return@forEach
            cookies.removeAll {
                it.name == parsed.name && it.domain == parsed.domain && it.path == parsed.path
            }
            if (cookies.size < 64) cookies += parsed
        }
    }

    fun header(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        return cookies
            .filter { it.matches(httpUrl) }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
    }

    fun appCookies(): List<UpcAuthCookie> {
        val now = System.currentTimeMillis()
        return cookies.asSequence()
            .filter { it.domain.lowercase(Locale.ROOT).trimStart('.') == UpcAuthCookie.HOST }
            .filter { it.expiresAt > now }
            .mapNotNull { cookie ->
                runCatching {
                    UpcAuthCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = UpcAuthCookie.HOST,
                        path = cookie.path,
                        secure = cookie.secure,
                        httpOnly = cookie.httpOnly,
                        expiresAtEpochSeconds = cookie.expiresAt
                            .takeIf { it > 0 && it != Long.MAX_VALUE }
                            ?.div(1000),
                    )
                }.getOrNull()
            }
            .distinctBy { it.name to it.path }
            .toList()
    }
}

/** 带 Cookie 与逐跳校验的认证请求会话；不自动跟随重定向。 */
internal class UpcCasHttpSession(
    private val transport: UpcAuthTransport,
    private val jar: UpcAuthCookieJar,
) {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): UpcAuthHttpResponse {
        val cookie = jar.header(url)
        val merged = if (cookie.isNullOrBlank()) headers else headers + ("Cookie" to cookie)
        val response = transport.execute(UpcAuthHttpRequest(method, url, merged, body))
        jar.save(url, response.headers["set-cookie"].orEmpty())
        return response
    }

    fun appCookies(): List<UpcAuthCookie> = jar.appCookies()

    /** 只返回与目标 URL 的 host/path/secure 匹配的 Cookie；调用方不得记录返回值。 */
    fun cookieHeader(url: String): String? = jar.header(url)
}

internal fun UpcAuthHttpResponse.text(): String = body.toString(StandardCharsets.UTF_8)

internal fun UpcAuthTransportException.toAuthFailure(): UpcAuthFailure =
    when (failure) {
        UpcAuthTransportFailure.NETWORK,
        UpcAuthTransportFailure.TIMEOUT,
        -> UpcAuthFailure.NETWORK_UNAVAILABLE
        UpcAuthTransportFailure.TLS -> UpcAuthFailure.TLS_ERROR
        UpcAuthTransportFailure.TOO_LARGE,
        UpcAuthTransportFailure.PROTOCOL,
        -> UpcAuthFailure.UPSTREAM_ERROR
    }

private data class CasLoginForm(
    val action: String?,
    val execution: String?,
    val type: String,
    val eventId: String,
    val submit: String,
    val captchaRequired: Boolean,
    val errorText: String?,
    val hasPassword: Boolean,
    val hasSecondFactor: Boolean,
)

private fun resolveCasFormAction(
    raw: String?,
    pageUrl: String,
    service: UpcCasService,
): String? {
    val action = resolveHttpUrl(pageUrl, raw ?: pageUrl) ?: return null
    return if (service.isCasLoginUrl(action)) action.toString() else null
}

private fun resolveHttpUrl(base: String, raw: String): HttpUrl? {
    val baseUrl = base.toHttpUrlOrNull() ?: return null
    return try {
        baseUrl.resolve(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun parseCasLoginForm(html: String): CasLoginForm {
    val forms = CAS_FORM.findAll(html).map { it.value }.toList()
    val chosen = forms.firstOrNull { CAS_PASSWORD_INPUT.containsMatchIn(it) }
        ?: forms.firstOrNull()
    val fields = linkedMapOf<String, String>()
    var hasPassword = false
    var action: String? = null
    chosen?.let { form ->
        val open = CAS_FORM_OPEN.find(form)?.groupValues?.get(1).orEmpty()
        action = casAttribute(open, "action")
        CAS_INPUT.findAll(form).forEach { match ->
            val attrs = match.groupValues[1]
            val name = casAttribute(attrs, "name") ?: return@forEach
            if (casAttribute(attrs, "type").equals("password", true)) hasPassword = true
            fields.putIfAbsent(name, casAttribute(attrs, "value").orEmpty())
        }
    }
    val error = CAS_ERROR_DIV.find(html)?.groupValues?.get(1)
        ?.replace(CAS_TAG, " ")
        ?.let(::decodeCasEntities)
        ?.replace(CAS_WHITESPACE, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val captcha = CAS_CAPTCHA_BLOCK.find(html)?.groupValues?.get(1)
        ?.replace(" ", "")
        ?.lowercase(Locale.ROOT)
        ?.contains("display:block") == true ||
        html.contains("config.captcha") ||
        fields.containsKey("captcha")
    val secondFactor = html.contains("username_smstoken") ||
        html.contains("扫码登录") ||
        html.contains("验证码登录")
    return CasLoginForm(
        action = action,
        execution = fields["execution"],
        type = fields["type"] ?: "username_password",
        eventId = fields["_eventId"] ?: "submit",
        submit = fields["submit"] ?: "登录",
        captchaRequired = captcha,
        errorText = error,
        hasPassword = hasPassword,
        hasSecondFactor = secondFactor,
    )
}

private fun casAttribute(attrs: String, name: String): String? =
    CAS_ATTRIBUTE.findAll(attrs).firstOrNull {
        it.groupValues[1].equals(name, true)
    }?.groupValues?.let { groups ->
        groups[2].ifEmpty { groups[3].ifEmpty { groups[4] } }
    }?.let(::decodeCasEntities)

private fun buildCasFormBody(
    username: String,
    password: String,
    execution: String,
    type: String,
    eventId: String,
    submit: String,
): String = listOf(
    "username" to username,
    "password" to password,
    "type" to type,
    "execution" to execution,
    "_eventId" to eventId,
    "submit" to submit,
).joinToString("&") { (name, value) ->
    URLEncoder.encode(name, StandardCharsets.UTF_8.name()) + "=" +
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private fun decodeCasEntities(value: String): String = value
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")

private val CAS_FORM = Regex(
    "<form\\b[^>]*>.*?</form>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val CAS_FORM_OPEN = Regex("<form\\b([^>]*)>", RegexOption.IGNORE_CASE)
private val CAS_INPUT = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
private val CAS_PASSWORD_INPUT = Regex(
    "<input\\b[^>]*type\\s*=\\s*[\"']password[\"']",
    RegexOption.IGNORE_CASE,
)
private val CAS_ERROR_DIV = Regex(
    "<div\\b[^>]*id\\s*=\\s*[\"']errorDiv[\"'][^>]*>(.*?)</div>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val CAS_CAPTCHA_BLOCK = Regex(
    "<div\\b[^>]*id\\s*=\\s*[\"']captchaParent[\"'][^>]*style\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>",
    RegexOption.IGNORE_CASE,
)
private val CAS_TAG = Regex("<[^>]+>")
private val CAS_WHITESPACE = Regex("\\s+")
private val CAS_ATTRIBUTE = Regex(
    "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
)
