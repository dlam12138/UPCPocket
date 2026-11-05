package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.SchoolAccountId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 超星座位 CAS 链路的固定入口；只允许官方 HTTPS 主机与路径。 */
object UpcChaoxingAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val TYRZFW_HOST = "tyrzfw.chaoxing.com"
    const val PASSPORT2_API_HOST = "passport2-api.chaoxing.com"
    const val OFFICE_HOST = "office.chaoxing.com"
    const val I_CHAOXING_HOST = "i.chaoxing.com"
    const val CHAOXING_COOKIE_DOMAIN = "chaoxing.com"

    const val FID_ENC = "e32f8fbde51f9dba"
    const val MAPP_ID = "4197453"

    const val SEAT_ENTRY =
        "https://office.chaoxing.com/front/third/apps/seat/index" +
            "?fidEnc=$FID_ENC&mappId=$MAPP_ID"
    const val LEGACY_SEAT_ENTRY =
        "https://office.chaoxing.com/front/apps/seat/index" +
            "?fidEnc=$FID_ENC&mappId=$MAPP_ID"
    const val TYRZFW_INIT_PATH = "/auth3/upc/cas/init"
    const val TYRZFW_CALLBACK_PATH = "/auth3/upc/cas/index"
    const val PASSPORT2_CASRECEIVE_PATH = "/api/v2/casreceive"
    const val OFFICE_SEAT_PATH = "/front/third/apps/seat/index"
    const val OFFICE_LEGACY_SEAT_PATH = "/front/apps/seat/index"
    const val CAS_SERVICE = "https://tyrzfw.chaoxing.com/auth3/upc/cas/index"
    const val MAX_REDIRECT_HOPS = 8
}

internal val CHAOXING_CAS_SERVICE = UpcCasService(
    casHost = UpcChaoxingAuthEndpoints.CAS_HOST,
    serviceUrl = UpcChaoxingAuthEndpoints.CAS_SERVICE,
    callbackHost = UpcChaoxingAuthEndpoints.TYRZFW_HOST,
    callbackPath = UpcChaoxingAuthEndpoints.TYRZFW_CALLBACK_PATH,
    callbackParameterNames = setOf("ticket"),
    maxRedirectHops = UpcChaoxingAuthEndpoints.MAX_REDIRECT_HOPS,
)

enum class UpcChaoxingAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    ACCESS_DENIED,
}

sealed interface UpcChaoxingAuthResult {
    /**
     * 只包含 office.chaoxing.com 可用的 Cookie 头；调用方必须只保存在内存，
     * 不写日志/偏好/文件，并在发布前复核账号代次。
     */
    data class Success(val cookieHeader: String) : UpcChaoxingAuthResult {
        override fun toString(): String = "Success(cookieHeader=<redacted>)"
    }

    data class Failure(
        val reason: UpcChaoxingAuthFailure,
        /** 仅 INTERACTION_REQUIRED 时为学校 CAS 登录页。 */
        val interactionUrl: String? = null,
    ) : UpcChaoxingAuthResult
}

/**
 * 用本机受控保存的学校账密走已确认的 UPC → 超星 SSO：
 *
 * `office 座位入口 -> tyrzfw cas/init -> cas.upc.edu.cn 表单 -> tyrzfw cas/index
 *  -> passport2-api casreceive -> office 座位入口`。
 *
 * 密码只提交给精确的 `https://cas.upc.edu.cn/cas/login`，不提交给 passport2 手机号/
 * 超星号表单。每一跳都校验固定 HTTPS host/path/端口、无 userinfo/fragment，并限制
 * 允许的查询键；票据、Cookie、密码不写日志、不持久化、不放进结果正文。
 */
class UpcChaoxingAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) {
    constructor(credentials: SchoolCredentialAccess) : this(credentials, OkHttpUpcAuthTransport())

    @OptIn(RestrictedSchoolCredentialUse::class)
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcChaoxingAuthResult {
        val result = try {
            credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.ACCOUNT_CHANGED)
                } else {
                    try {
                        performLogin(credential)
                    } catch (failure: ChaoxingTransportFailure) {
                        UpcChaoxingAuthResult.Failure(failure.reason)
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.PROTOCOL_ERROR)
        }
        return when (result) {
            is SchoolCredentialUseResult.Success -> result.value
            is SchoolCredentialUseResult.Failure -> when (result.reason) {
                SchoolCredentialFailure.CHANGED_DURING_USE ->
                    UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.ACCOUNT_CHANGED)
                SchoolCredentialFailure.BLOCKED_BY_CLEANUP,
                SchoolCredentialFailure.NOT_CONFIGURED,
                SchoolCredentialFailure.UNAVAILABLE,
                -> UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.ACCOUNT_UNAVAILABLE)
            }
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private fun performLogin(credential: SchoolLoginCredential): UpcChaoxingAuthResult {
        val jar = UpcAuthCookieJar(
            setOf(
                UpcChaoxingAuthEndpoints.CAS_HOST,
                UpcChaoxingAuthEndpoints.TYRZFW_HOST,
                UpcChaoxingAuthEndpoints.PASSPORT2_API_HOST,
                UpcChaoxingAuthEndpoints.OFFICE_HOST,
                UpcChaoxingAuthEndpoints.CHAOXING_COOKIE_DOMAIN,
            ),
        )
        val session = UpcCasHttpSession(transport, jar)

        val entry = request(session, "GET", UpcChaoxingAuthEndpoints.SEAT_ENTRY)
        if (entry.status !in 300..399) {
            return failureForStatus(entry.status)
        }
        val init = entry.header("Location")
            ?.let { resolveUrl(UpcChaoxingAuthEndpoints.SEAT_ENTRY, it) }
            ?: return protocolFailure()
        if (!isAllowedTyrzfwInit(init)) return protocolFailure()
        val initPage = request(session, "GET", init.toString())
        if (initPage.status != 200) return failureForStatus(initPage.status)
        val casUrl = extractJsLocation(initPage.text())
            ?.let { resolveUrl(init.toString(), it) }
            ?: return protocolFailure()
        if (!CHAOXING_CAS_SERVICE.isCasLoginUrl(casUrl)) return protocolFailure()

        val cas = UpcCasLogin(CHAOXING_CAS_SERVICE, transport)
            .login(credential, casUrl.toString(), session)
        val callback = when (cas) {
            is UpcCasLoginResult.Failure ->
                return UpcChaoxingAuthResult.Failure(
                    cas.reason.toChaoxingFailure(),
                    cas.interactionUrl,
                )
            is UpcCasLoginResult.Callback -> cas
        }

        var callbackPage = request(session, "GET", callback.url.toString())
        if (callbackPage.status in 300..399) {
            val next = callbackPage.header("Location")
                ?.let { resolveUrl(callback.url.toString(), it) }
                ?: return protocolFailure()
            if (!isAllowedTyrzfwCallback(next, requireTicket = false)) return protocolFailure()
            callbackPage = request(session, "GET", next.toString())
        }
        if (callbackPage.status != 200) return failureForStatus(callbackPage.status)

        val passportRaw = extractJsLocation(callbackPage.text())
        if (passportRaw == null) return protocolFailure()
        val passportUrl = resolveUrl(callback.url.toString(), passportRaw)
            ?: return protocolFailure()
        if (!isAllowedPassport2Casreceive(passportUrl)) return protocolFailure()

        var receive = request(session, "GET", passportUrl.toString())
        if (receive.status in 300..399) {
            val next = receive.header("Location")
                ?.let { resolveUrl(passportUrl.toString(), it) }
                ?: return protocolFailure()
            if (!isAllowedIChaoxing(next)) return protocolFailure()
            receive = request(session, "GET", next.toString())
        }
        if (receive.status != 200) return failureForStatus(receive.status)

        val officeUrl = extractHiddenInput(receive.text(), "refer")
            ?.let(::decodeHtmlEntities)
            ?.toHttpUrlOrNull()
            ?: return protocolFailure()
        if (!isAllowedOfficeSeatEntry(officeUrl)) return protocolFailure()

        var currentOffice = officeUrl
        var office = request(session, "GET", currentOffice.toString())
        var hops = 0
        while (office.status in 300..399 && hops++ < UpcChaoxingAuthEndpoints.MAX_REDIRECT_HOPS) {
            val next = office.header("Location")
                ?.let { resolveUrl(currentOffice.toString(), it) }
                ?: return protocolFailure()
            if (!isAllowedOfficeSeatEntry(next)) return protocolFailure()
            currentOffice = next
            office = request(session, "GET", next.toString())
        }
        if (office.status != 200) return failureForStatus(office.status)
        if (!office.text().contains("userLoginInfo")) return protocolFailure()

        val cookie = session.cookieHeader(UpcChaoxingAuthEndpoints.SEAT_ENTRY)
            ?: return protocolFailure()
        if (!isPlausibleCookieHeader(cookie)) return protocolFailure()
        return UpcChaoxingAuthResult.Success(cookie)
    }

    private fun request(
        session: UpcCasHttpSession,
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): UpcAuthHttpResponse = try {
        session.request(method, url, headers)
    } catch (failure: UpcAuthTransportException) {
        throw ChaoxingTransportFailure(failure.toChaoxingFailure())
    }

    private fun failureForStatus(status: Int): UpcChaoxingAuthResult.Failure = when {
        status == 401 || status == 403 || status == 429 ->
            UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.ACCESS_DENIED)
        status >= 500 ->
            UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.UPSTREAM_ERROR)
        else ->
            UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.PROTOCOL_ERROR)
    }

    private fun protocolFailure(): UpcChaoxingAuthResult.Failure =
        UpcChaoxingAuthResult.Failure(UpcChaoxingAuthFailure.PROTOCOL_ERROR)
}

private class ChaoxingTransportFailure(
    val reason: UpcChaoxingAuthFailure,
) : Exception(null, null, false, false)

private fun UpcAuthFailure.toChaoxingFailure(): UpcChaoxingAuthFailure = when (this) {
    UpcAuthFailure.INVALID_CREDENTIALS -> UpcChaoxingAuthFailure.INVALID_CREDENTIALS
    UpcAuthFailure.INTERACTION_REQUIRED -> UpcChaoxingAuthFailure.INTERACTION_REQUIRED
    UpcAuthFailure.NETWORK_UNAVAILABLE -> UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE
    UpcAuthFailure.TLS_ERROR -> UpcChaoxingAuthFailure.TLS_ERROR
    UpcAuthFailure.UPSTREAM_ERROR -> UpcChaoxingAuthFailure.UPSTREAM_ERROR
    UpcAuthFailure.PROTOCOL_ERROR -> UpcChaoxingAuthFailure.PROTOCOL_ERROR
    UpcAuthFailure.ACCOUNT_UNAVAILABLE -> UpcChaoxingAuthFailure.ACCOUNT_UNAVAILABLE
    UpcAuthFailure.ACCOUNT_CHANGED -> UpcChaoxingAuthFailure.ACCOUNT_CHANGED
    UpcAuthFailure.ACCESS_DENIED -> UpcChaoxingAuthFailure.ACCESS_DENIED
}

private fun UpcAuthTransportException.toChaoxingFailure(): UpcChaoxingAuthFailure = when (failure) {
    UpcAuthTransportFailure.NETWORK,
    UpcAuthTransportFailure.TIMEOUT,
    -> UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE
    UpcAuthTransportFailure.TLS -> UpcChaoxingAuthFailure.TLS_ERROR
    UpcAuthTransportFailure.TOO_LARGE,
    UpcAuthTransportFailure.PROTOCOL,
    -> UpcChaoxingAuthFailure.UPSTREAM_ERROR
}

private fun isAllowedHttpsHost(url: HttpUrl, host: String): Boolean =
    url.scheme == "https" && url.host == host &&
        (url.port == -1 || url.port == 443) &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null

private fun isAllowedTyrzfwInit(url: HttpUrl): Boolean {
    if (!isAllowedHttpsHost(url, UpcChaoxingAuthEndpoints.TYRZFW_HOST)) return false
    if (url.encodedPath != UpcChaoxingAuthEndpoints.TYRZFW_INIT_PATH) return false
    val names = url.queryParameterNames
    if (names.any { it !in setOf("refer", "mappId") }) return false
    if (names.any { url.queryParameterValues(it).size != 1 }) return false
    val refer = url.queryParameter("refer") ?: return false
    if (!isAllowedSeatRefer(refer)) return false
    val mappId = url.queryParameter("mappId")
    return mappId == null || mappId == UpcChaoxingAuthEndpoints.MAPP_ID
}

internal fun isAllowedTyrzfwCallback(url: HttpUrl, requireTicket: Boolean): Boolean {
    if (!isAllowedHttpsHost(url, UpcChaoxingAuthEndpoints.TYRZFW_HOST)) return false
    if (url.encodedPath != UpcChaoxingAuthEndpoints.TYRZFW_CALLBACK_PATH) return false
    val names = url.queryParameterNames
    if (requireTicket) {
        if (names != setOf("ticket")) return false
        val ticket = url.queryParameter("ticket") ?: return false
        return ticket.startsWith("ST-") && ticket.length in 8..4096
    }
    return names.isEmpty()
}

internal fun isAllowedPassport2Casreceive(url: HttpUrl): Boolean {
    if (!isAllowedHttpsHost(url, UpcChaoxingAuthEndpoints.PASSPORT2_API_HOST)) return false
    if (url.encodedPath != UpcChaoxingAuthEndpoints.PASSPORT2_CASRECEIVE_PATH) return false
    val allowedNames = setOf(
        "thirdinfo",
        "refer",
        "setmain",
        "time",
        "signature",
        "appid",
        "casfrom",
        "casinfo",
        "casenc",
    )
    val names = url.queryParameterNames
    if (names.isEmpty() || names.any { it !in allowedNames }) return false
    if (names.any { url.queryParameterValues(it).size != 1 }) return false
    if (url.queryParameter("thirdinfo").isNullOrBlank()) return false
    if (url.queryParameter("refer")?.let(::isAllowedSeatRefer) != true) return false
    if (url.queryParameter("setmain") != "true") return false
    if (url.queryParameter("casfrom") != null && url.queryParameter("casfrom") != "login") {
        return false
    }
    return true
}

internal fun isAllowedIChaoxing(url: HttpUrl): Boolean {
    if (!isAllowedHttpsHost(url, UpcChaoxingAuthEndpoints.I_CHAOXING_HOST)) return false
    val path = url.encodedPath
    return path.isEmpty() || path == "/" || path == "/base" || path == "/base/"
}

internal fun isAllowedOfficeSeatEntry(url: HttpUrl): Boolean {
    if (!isAllowedHttpsHost(url, UpcChaoxingAuthEndpoints.OFFICE_HOST)) return false
    val path = url.encodedPath
    if (path != UpcChaoxingAuthEndpoints.OFFICE_SEAT_PATH &&
        path != UpcChaoxingAuthEndpoints.OFFICE_LEGACY_SEAT_PATH
    ) {
        return false
    }
    val names = url.queryParameterNames
    if (names != setOf("fidEnc", "mappId")) return false
    if (names.any { url.queryParameterValues(it).size != 1 }) return false
    return url.queryParameter("fidEnc") == UpcChaoxingAuthEndpoints.FID_ENC &&
        url.queryParameter("mappId") == UpcChaoxingAuthEndpoints.MAPP_ID
}

/** tyrzfw init 的 refer 可能被上游截断成只含 fidEnc 的相对业务地址。 */
internal fun isAllowedSeatRefer(raw: String): Boolean {
    val url = raw.toHttpUrlOrNull() ?: return false
    if (!isAllowedHttpsHost(url, UpcChaoxingAuthEndpoints.OFFICE_HOST)) return false
    val path = url.encodedPath
    if (path != UpcChaoxingAuthEndpoints.OFFICE_SEAT_PATH &&
        path != UpcChaoxingAuthEndpoints.OFFICE_LEGACY_SEAT_PATH
    ) {
        return false
    }
    if (url.queryParameter("fidEnc") != UpcChaoxingAuthEndpoints.FID_ENC) return false
    val allowedNames = setOf("fidEnc", "mappId", "seatId")
    return url.queryParameterNames.all { it in allowedNames }
}

private fun resolveUrl(base: String, raw: String): HttpUrl? =
    try {
        base.toHttpUrlOrNull()?.resolve(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun extractJsLocation(html: String): String? {
    val match = JS_LOCATION.find(html) ?: return null
    return decodeJsString(match.groupValues[1])
}

private fun extractHiddenInput(html: String, id: String): String? {
    val tag = HIDDEN_INPUT.findAll(html).firstOrNull { match ->
        ATTRIBUTE_ID.find(match.value)?.groupValues?.get(1) == id
    }?.value ?: return null
    return ATTRIBUTE_VALUE.find(tag)?.groupValues?.get(1)
}

private fun decodeJsString(value: String): String {
    var out = value.replace("\\/", "/")
    out = UNICODE_ESCAPE.replace(out) { match ->
        match.groupValues[1].toInt(16).toChar().toString()
    }
    out = HEX_ESCAPE.replace(out) { match ->
        match.groupValues[1].toInt(16).toChar().toString()
    }
    return out.replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun decodeHtmlEntities(value: String): String = value
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")

private fun isPlausibleCookieHeader(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= 8 * 1024 &&
        value.contains('=') &&
        !value.contains('\r') &&
        !value.contains('\n')

private val JS_LOCATION = Regex(
    """window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE,
)
private val HIDDEN_INPUT = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
private val ATTRIBUTE_ID = Regex(
    """\bid\s*=\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE,
)
private val ATTRIBUTE_VALUE = Regex(
    """\bvalue\s*=\s*["']([^"']*)["']""",
    RegexOption.IGNORE_CASE,
)
private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
private val HEX_ESCAPE = Regex("""\\x([0-9a-fA-F]{2})""")
