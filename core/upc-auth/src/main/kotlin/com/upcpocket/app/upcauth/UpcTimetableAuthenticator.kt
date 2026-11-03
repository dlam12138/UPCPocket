package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.SchoolAccountId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 课表正常登录与数据入口；只允许 HTTPS 官方主机。 */
object UpcTimetableAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val APP_HOST = "app.upc.edu.cn"
    const val PAGE_URL = "https://app.upc.edu.cn/site/weekschedule/index"
    const val INDEX_URL = "https://app.upc.edu.cn/timetable/wap/default/get-index"

    /**
     * 与已审阅 UPC-MCP 固定提交一致的 service 形状：内层 redirect 不再次编码，
     * 外层带 from=wap 与 noAutoRedirect=1。不要改成普通 URL 编码形式。
     */
    const val SERVICE =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex"
    const val LOGIN_URL =
        "https://cas.upc.edu.cn/cas/login?service=$SERVICE&from=wap&noAutoRedirect=1"

    internal const val SERVICE_DECODED =
        "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?redirect=https://app.upc.edu.cn/site/weekschedule/index"

    internal const val APP_CALLBACK_PATH = "/a_upc/api/cas/login"
    internal const val APP_INDEX_PATH = "/timetable/wap/default/get-index"
    internal const val MAX_REDIRECT_HOPS = 8
}

/**
 * 用当前统一账号的正常 CAS 表单登录建立课表业务 Cookie。
 *
 * 边界：
 *  - 只在 `cas.upc.edu.cn/cas/login` 的 HTTPS 官方表单提交一次密码；
 *  - 表单 action、service、票据回调和业务跳转逐跳核对，不自动跟随 OkHttp 重定向；
 *  - 检测到验证码/二次验证时立即返回交互登录，不提交密码、不循环重试；
 *  - 返回的 Cookie 只属于 `app.upc.edu.cn`，由课表功能以自己的加密存储保存。
 */
class UpcTimetableAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) : TimetableAuthenticator {

    constructor(
        credentials: SchoolCredentialAccess,
    ) : this(credentials, OkHttpUpcAuthTransport())

    @OptIn(RestrictedSchoolCredentialUse::class)
    override suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcAuthResult {
        val result = try {
            credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    UpcAuthResult.Failure(UpcAuthFailure.ACCOUNT_CHANGED)
                } else {
                    performLogin(credential)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        }
        return when (result) {
            is SchoolCredentialUseResult.Success -> result.value
            is SchoolCredentialUseResult.Failure -> when (result.reason) {
                SchoolCredentialFailure.CHANGED_DURING_USE ->
                    UpcAuthResult.Failure(UpcAuthFailure.ACCOUNT_CHANGED)
                SchoolCredentialFailure.BLOCKED_BY_CLEANUP,
                SchoolCredentialFailure.NOT_CONFIGURED,
                SchoolCredentialFailure.UNAVAILABLE,
                -> UpcAuthResult.Failure(UpcAuthFailure.ACCOUNT_UNAVAILABLE)
            }
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private fun performLogin(credential: SchoolLoginCredential): UpcAuthResult {
        val jar = AuthCookieJar()
        val page = try {
            request(jar, "GET", UpcTimetableAuthEndpoints.LOGIN_URL)
        } catch (failure: UpcAuthTransportException) {
            return failure.toAuthFailure()
        }
        if (page.status in 300..399) {
            return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        }
        if (page.status == 401 || page.status == 403 || page.status == 429) {
            return UpcAuthResult.Failure(UpcAuthFailure.ACCESS_DENIED)
        }
        if (page.status != 200) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }

        val form = parseLoginForm(page.text(), UpcTimetableAuthEndpoints.LOGIN_URL)
        if (form.captchaRequired) {
            return UpcAuthResult.Failure(
                UpcAuthFailure.INTERACTION_REQUIRED,
                UpcTimetableAuthEndpoints.LOGIN_URL,
            )
        }
        if (!form.hasPassword || form.execution.isNullOrBlank()) {
            val interaction = form.hasSecondFactor
            return UpcAuthResult.Failure(
                if (interaction) UpcAuthFailure.INTERACTION_REQUIRED else UpcAuthFailure.PROTOCOL_ERROR,
                if (interaction) UpcTimetableAuthEndpoints.LOGIN_URL else null,
            )
        }
        val action = resolveCasFormAction(form.action, UpcTimetableAuthEndpoints.LOGIN_URL)
            ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)

        val body = credential.usePassword { password ->
            buildFormBody(
                username = credential.loginName,
                password = String(password),
                execution = form.execution,
                type = form.type,
                eventId = form.eventId,
                submit = form.submit,
            )
        }
        val submitted = try {
            request(
                jar,
                "POST",
                action,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to "https://${UpcTimetableAuthEndpoints.CAS_HOST}",
                    "Referer" to UpcTimetableAuthEndpoints.LOGIN_URL,
                ),
                body = body,
            )
        } catch (failure: UpcAuthTransportException) {
            return failure.toAuthFailure()
        }

        if (submitted.status in 300..399) {
            val location = submitted.header("Location")
                ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            val target = resolveHttpUrl(action, location)
                ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            return when {
                isCasLoginUrl(target) -> classifyCasReturn(jar, target)
                isAppCallback(target) -> followCallbackAndVerify(jar, target)
                else -> UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            }
        }
        if (submitted.status == 401 || submitted.status == 403 || submitted.status == 429) {
            return UpcAuthResult.Failure(UpcAuthFailure.ACCESS_DENIED)
        }
        if (submitted.status != 200) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        val returned = parseLoginForm(
            submitted.text(),
            UpcTimetableAuthEndpoints.LOGIN_URL,
        )
        if (returned.captchaRequired) {
            return UpcAuthResult.Failure(
                UpcAuthFailure.INTERACTION_REQUIRED,
                UpcTimetableAuthEndpoints.LOGIN_URL,
            )
        }
        return UpcAuthResult.Failure(
            if (returned.hasPassword) {
                UpcAuthFailure.INVALID_CREDENTIALS
            } else {
                UpcAuthFailure.UPSTREAM_ERROR
            },
        )
    }

    private fun classifyCasReturn(jar: AuthCookieJar, url: HttpUrl): UpcAuthResult {
        val response = try {
            request(jar, "GET", url.toString())
        } catch (failure: UpcAuthTransportException) {
            return failure.toAuthFailure()
        }
        if (response.status != 200) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        val form = parseLoginForm(response.text(), url.toString())
        return when {
            form.errorText != null && form.errorText.isNotBlank() ->
                UpcAuthResult.Failure(UpcAuthFailure.INVALID_CREDENTIALS)
            form.captchaRequired -> UpcAuthResult.Failure(
                UpcAuthFailure.INTERACTION_REQUIRED,
                UpcTimetableAuthEndpoints.LOGIN_URL,
            )
            form.hasPassword -> UpcAuthResult.Failure(UpcAuthFailure.INVALID_CREDENTIALS)
            else -> UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
    }

    private fun followCallbackAndVerify(jar: AuthCookieJar, start: HttpUrl): UpcAuthResult {
        var current = start
        repeat(UpcTimetableAuthEndpoints.MAX_REDIRECT_HOPS) {
            if (!isAllowedAppPage(current)) {
                return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
            }
            val response = try {
                request(jar, "GET", current.toString())
            } catch (failure: UpcAuthTransportException) {
                return failure.toAuthFailure()
            }
            if (response.status in 300..399) {
                val location = response.header("Location")
                    ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
                current = resolveHttpUrl(current.toString(), location)
                    ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
                return@repeat
            }
            if (response.status == 401 || response.status == 403) {
                return UpcAuthResult.Failure(UpcAuthFailure.ACCESS_DENIED)
            }
            if (response.status != 200) {
                return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
            }
            return verifyIndexAndExtractCookies(jar)
        }
        return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
    }

    private fun verifyIndexAndExtractCookies(jar: AuthCookieJar): UpcAuthResult {
        val response = try {
            request(
                jar,
                "GET",
                UpcTimetableAuthEndpoints.INDEX_URL,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to UpcTimetableAuthEndpoints.PAGE_URL,
                ),
            )
        } catch (failure: UpcAuthTransportException) {
            return failure.toAuthFailure()
        }
        if (response.status != 200) {
            return UpcAuthResult.Failure(
                if (response.status == 401) UpcAuthFailure.INVALID_CREDENTIALS
                else UpcAuthFailure.UPSTREAM_ERROR,
            )
        }
        val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
        if (!contentType.contains("application/json")) {
            return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        }
        val code = StrictJson.intField(response.text(), "e")
            ?: return UpcAuthResult.Failure(UpcAuthFailure.PROTOCOL_ERROR)
        if (code == 10013) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        if (code != 0) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        val cookies = jar.cookiesForApp()
        if (cookies.isEmpty()) {
            return UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }
        return UpcAuthResult.Success(cookies)
    }

    private fun request(
        jar: AuthCookieJar,
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

    private fun UpcAuthHttpResponse.text(): String =
        body.toString(StandardCharsets.UTF_8)

    private fun UpcAuthTransportException.toAuthFailure(): UpcAuthResult =
        when (failure) {
            UpcAuthTransportFailure.NETWORK,
            UpcAuthTransportFailure.TIMEOUT,
            -> UpcAuthResult.Failure(UpcAuthFailure.NETWORK_UNAVAILABLE)
            UpcAuthTransportFailure.TLS ->
                UpcAuthResult.Failure(UpcAuthFailure.TLS_ERROR)
            UpcAuthTransportFailure.TOO_LARGE,
            UpcAuthTransportFailure.PROTOCOL,
            -> UpcAuthResult.Failure(UpcAuthFailure.UPSTREAM_ERROR)
        }

    private fun resolveCasFormAction(raw: String?, pageUrl: String): String? {
        val action = resolveHttpUrl(pageUrl, raw ?: pageUrl) ?: return null
        return if (isCasLoginUrl(action)) action.toString() else null
    }

    private fun isCasLoginUrl(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcTimetableAuthEndpoints.CAS_HOST ||
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
        if (url.queryParameter("service") != UpcTimetableAuthEndpoints.SERVICE_DECODED) return false
        url.queryParameter("from")?.let { if (it != "wap") return false }
        url.queryParameter("noAutoRedirect")?.let { if (it != "1") return false }
        return true
    }

    private fun isAppCallback(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcTimetableAuthEndpoints.APP_HOST ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != UpcTimetableAuthEndpoints.APP_CALLBACK_PATH ||
            url.fragment != null
        ) {
            return false
        }
        val names = url.queryParameterNames
        if (names.any { it !in setOf("ticket", "redirect", "from") }) return false
        names.forEach { name ->
            if (url.queryParameterValues(name).size != 1) return false
        }
        val ticket = url.queryParameter("ticket") ?: return false
        if (!ticket.startsWith("ST-") || ticket.length !in 8..4096) return false
        url.queryParameter("redirect")?.let {
            if (it != UpcTimetableAuthEndpoints.PAGE_URL) return false
        }
        url.queryParameter("from")?.let { if (it != "wap") return false }
        return true
    }

    private fun isAllowedAppPage(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcTimetableAuthEndpoints.APP_HOST ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null
        ) {
            return false
        }
        return when (url.encodedPath) {
            UpcTimetableAuthEndpoints.APP_CALLBACK_PATH -> isAppCallback(url)
            UpcTimetableAuthEndpoints.APP_INDEX_PATH,
            "/site/weekschedule/index",
            -> true
            else -> false
        }
    }

    private fun resolveHttpUrl(base: String, raw: String): HttpUrl? {
        val baseUrl = base.toHttpUrlOrNull() ?: return null
        return try {
            baseUrl.resolve(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private data class LoginForm(
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

    private fun parseLoginForm(html: String, pageUrl: String): LoginForm {
        val forms = FORM.findAll(html).map { it.value }.toList()
        val chosen = forms.firstOrNull { PASSWORD_INPUT.containsMatchIn(it) }
            ?: forms.firstOrNull()
        val fields = linkedMapOf<String, String>()
        var hasPassword = false
        var action: String? = null
        chosen?.let { form ->
            val open = FORM_OPEN.find(form)?.groupValues?.get(1).orEmpty()
            action = attribute(open, "action")
            INPUT.findAll(form).forEach { match ->
                val attrs = match.groupValues[1]
                val name = attribute(attrs, "name") ?: return@forEach
                if (attribute(attrs, "type").equals("password", true)) hasPassword = true
                fields.putIfAbsent(name, attribute(attrs, "value").orEmpty())
            }
        }
        val error = ERROR_DIV.find(html)?.groupValues?.get(1)
            ?.replace(TAG, " ")
            ?.let(::decodeEntities)
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val captcha = CAPTCHA_BLOCK.find(html)?.groupValues?.get(1)
            ?.replace(" ", "")
            ?.lowercase(Locale.ROOT)
            ?.contains("display:block") == true ||
            html.contains("config.captcha") ||
            fields.containsKey("captcha")
        val secondFactor = html.contains("username_smstoken") ||
            html.contains("扫码登录") ||
            html.contains("验证码登录")
        return LoginForm(
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

    private fun attribute(attrs: String, name: String): String? =
        ATTRIBUTE.findAll(attrs).firstOrNull {
            it.groupValues[1].equals(name, true)
        }?.groupValues?.let { groups ->
            groups[2].ifEmpty { groups[3].ifEmpty { groups[4] } }
        }?.let(::decodeEntities)

    private fun buildFormBody(
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

    private companion object {
        val FORM = Regex("<form\\b[^>]*>.*?</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val FORM_OPEN = Regex("<form\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val INPUT = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val PASSWORD_INPUT = Regex(
            "<input\\b[^>]*type\\s*=\\s*[\"']password[\"']",
            RegexOption.IGNORE_CASE,
        )
        val ERROR_DIV = Regex(
            "<div\\b[^>]*id\\s*=\\s*[\"']errorDiv[\"'][^>]*>(.*?)</div>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val CAPTCHA_BLOCK = Regex(
            "<div\\b[^>]*id\\s*=\\s*[\"']captchaParent[\"'][^>]*style\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        )
        val TAG = Regex("<[^>]+>")
        val WHITESPACE = Regex("\\s+")
        val ATTRIBUTE = Regex(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
        )
    }
}

/** 只保存认证过程中实际收到的 Cookie；发送时按域名、路径、Secure、到期过滤。 */
private class AuthCookieJar {
    private val cookies = mutableListOf<Cookie>()

    fun save(url: String, setCookieHeaders: List<String>) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        setCookieHeaders.forEach { header ->
            val parsed = Cookie.parse(httpUrl, header) ?: return@forEach
            val domain = parsed.domain.lowercase(Locale.ROOT).trimStart('.')
            if (domain != UpcTimetableAuthEndpoints.CAS_HOST &&
                domain != UpcTimetableAuthEndpoints.APP_HOST
            ) {
                return@forEach
            }
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

    fun cookiesForApp(): List<UpcAuthCookie> {
        val now = System.currentTimeMillis()
        return cookies.asSequence()
            .filter { it.domain.lowercase(Locale.ROOT).trimStart('.') == UpcTimetableAuthEndpoints.APP_HOST }
            .filter { it.expiresAt > now }
            .mapNotNull { cookie ->
                runCatching {
                    UpcAuthCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = UpcTimetableAuthEndpoints.APP_HOST,
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

/** 严格的 JSON 整数读取；不接受布尔、浮点或范围外的字符串。 */
internal object StrictJson {
    fun intField(json: String, name: String): Int? = try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json)
            as? kotlinx.serialization.json.JsonObject ?: return null
        val element = root[name] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (element.isString) {
            element.content.takeIf { it.matches(Regex("[+-]?\\d+")) }?.toIntOrNull()
        } else {
            element.content.toIntOrNull()
        }
    } catch (_: Exception) {
        null
    }
}

private fun decodeEntities(value: String): String = value
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
