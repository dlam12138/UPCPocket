package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.SchoolAccountId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** nspace 空间预约的 CAS 入口、业务端点与固定主机。 */
object UpcNspaceAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val LIBRARY_HOST = "libspace.upc.edu.cn"
    const val ENTRY_URL =
        "https://libspace.upc.edu.cn/remote/static/sso/login" +
            "?redirectUrl=https%3A%2F%2Flibspace.upc.edu.cn%2Fnspace%2F%23%2Flogin"
    const val SERVICE =
        "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin"
    const val REMOTE_CAS_LOGIN_URL =
        "https://libspace.upc.edu.cn/spa/static/public/api/remoteCasLogin"
    const val MAX_REDIRECT_HOPS = 8
}

internal val NSPACE_CAS_SERVICE = UpcCasService(
    casHost = UpcNspaceAuthEndpoints.CAS_HOST,
    serviceUrl = UpcNspaceAuthEndpoints.SERVICE,
    callbackHost = UpcNspaceAuthEndpoints.LIBRARY_HOST,
    callbackPath = "/remote/static/casAuth/getServiceByVerifyTicket/casLogin",
    callbackParameterNames = setOf("ticket"),
    maxRedirectHops = UpcNspaceAuthEndpoints.MAX_REDIRECT_HOPS,
)

/** nspace 远程认证返回的本人标识；不包含密码、Cookie 或 token。 */
data class UpcNspaceUser(
    val id: String,
    val username: String,
    val name: String,
)

sealed interface UpcNspaceAuthResult {
    data class Success(
        val token: String,
        val user: UpcNspaceUser,
    ) : UpcNspaceAuthResult

    data class Failure(
        val reason: UpcLibraryAuthFailure,
        /** 仅 INTERACTION_REQUIRED 时给出学校正常登录页。 */
        val interactionUrl: String? = null,
    ) : UpcNspaceAuthResult
}

/**
 * 用统一账号完成 nspace 的正常 CAS 认证：
 *
 * 1. libspace 的 nspace SSO 入口跳官方 CAS；
 * 2. CAS 表单只提交一次密码，得到单次 service ticket；
 * 3. 跟随 libspace 回调，从 `redirectUrl`/fragment 取得 nspace app token；
 * 4. 调 `/spa/static/public/api/remoteCasLogin` 换取业务 token；
 * 5. 用服务端返回的 userInfo 核对本人。
 *
 * 业务 token 与 libseat token 是两套独立凭据，不能互换；本类不保存 token。
 */
class UpcNspaceAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) {
    constructor(credentials: SchoolCredentialAccess) : this(credentials, OkHttpUpcAuthTransport())

    @OptIn(RestrictedSchoolCredentialUse::class)
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcNspaceAuthResult {
        val result = try {
            credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_CHANGED)
                } else {
                    performLogin(credential)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        return when (result) {
            is SchoolCredentialUseResult.Success -> result.value
            is SchoolCredentialUseResult.Failure -> when (result.reason) {
                SchoolCredentialFailure.CHANGED_DURING_USE ->
                    UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_CHANGED)
                SchoolCredentialFailure.BLOCKED_BY_CLEANUP,
                SchoolCredentialFailure.NOT_CONFIGURED,
                SchoolCredentialFailure.UNAVAILABLE,
                -> UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_UNAVAILABLE)
            }
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    suspend fun exchangeWebToken(
        appToken: String,
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcNspaceAuthResult {
        if (appToken.isBlank() || appToken.length > 4096 ||
            appToken.any { it == '\r' || it == '\n' || it == '\u0000' }
        ) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        val result = try {
            credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_CHANGED)
                } else {
                    exchangeWebTokenWithCredential(credential, appToken)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        return when (result) {
            is SchoolCredentialUseResult.Success -> result.value
            is SchoolCredentialUseResult.Failure -> when (result.reason) {
                SchoolCredentialFailure.CHANGED_DURING_USE ->
                    UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_CHANGED)
                SchoolCredentialFailure.BLOCKED_BY_CLEANUP,
                SchoolCredentialFailure.NOT_CONFIGURED,
                SchoolCredentialFailure.UNAVAILABLE,
                -> UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_UNAVAILABLE)
            }
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private fun exchangeWebTokenWithCredential(
        credential: SchoolLoginCredential,
        appToken: String,
    ): UpcNspaceAuthResult {
        val session = UpcCasHttpSession(
            transport,
            UpcAuthCookieJar(setOf(UpcNspaceAuthEndpoints.LIBRARY_HOST)),
        )
        val remote = try {
            session.request(
                "GET",
                UpcNspaceAuthEndpoints.REMOTE_CAS_LOGIN_URL + "?token=" + encode(appToken),
                headers = mapOf("Accept" to "application/json"),
            )
        } catch (failure: UpcAuthTransportException) {
            return UpcNspaceAuthResult.Failure(failure.toNspaceFailure())
        }
        if (remote.status in 401..403 || remote.status == 429) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (remote.status != 200) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
        }
        val parsed = try {
            parseNspaceLogin(remote.text())
        } catch (failure: NspaceJsonFailure) {
            return UpcNspaceAuthResult.Failure(failure.reason)
        }
        if (parsed.user.username.trim() != credential.loginName.trim()) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.IDENTITY_MISMATCH)
        }
        return UpcNspaceAuthResult.Success(parsed.token, parsed.user)
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private fun performLogin(credential: SchoolLoginCredential): UpcNspaceAuthResult {
        val jar = UpcAuthCookieJar(
            setOf(UpcNspaceAuthEndpoints.CAS_HOST, UpcNspaceAuthEndpoints.LIBRARY_HOST),
        )
        val session = UpcCasHttpSession(transport, jar)
        val entry = try {
            session.request("GET", UpcNspaceAuthEndpoints.ENTRY_URL)
        } catch (failure: UpcAuthTransportException) {
            return UpcNspaceAuthResult.Failure(failure.toNspaceFailure())
        }
        if (entry.status in 401..403 || entry.status == 429) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (entry.status !in setOf(301, 302, 303, 307, 308)) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        val casUrl = entry.header("Location")
            ?.let { resolveNspaceUrl(UpcNspaceAuthEndpoints.ENTRY_URL, it) }
            ?: return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        if (!NSPACE_CAS_SERVICE.isCasLoginUrl(casUrl)) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }

        val cas = UpcCasLogin(NSPACE_CAS_SERVICE, transport)
            .login(credential, casUrl.toString(), session)
        val callback = when (cas) {
            is UpcCasLoginResult.Failure ->
                return UpcNspaceAuthResult.Failure(
                    cas.reason.toNspaceFailure(),
                    cas.interactionUrl,
                )
            is UpcCasLoginResult.Callback -> cas
        }
        val appToken = when (val follow = followToNspaceToken(callback.session, callback.url)) {
            is NspaceTokenResult.Found -> follow.token
            is NspaceTokenResult.Failure ->
                return UpcNspaceAuthResult.Failure(follow.reason)
        }

        val remote = try {
            callback.session.request(
                "GET",
                UpcNspaceAuthEndpoints.REMOTE_CAS_LOGIN_URL + "?token=" + encode(appToken),
                headers = mapOf("Accept" to "application/json"),
            )
        } catch (failure: UpcAuthTransportException) {
            return UpcNspaceAuthResult.Failure(failure.toNspaceFailure())
        }
        if (remote.status in 401..403 || remote.status == 429) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (remote.status != 200) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
        }
        val parsed = try {
            parseNspaceLogin(remote.text())
        } catch (failure: NspaceJsonFailure) {
            return UpcNspaceAuthResult.Failure(failure.reason)
        }
        if (parsed.user.username.trim() != credential.loginName.trim()) {
            return UpcNspaceAuthResult.Failure(UpcLibraryAuthFailure.IDENTITY_MISMATCH)
        }
        return UpcNspaceAuthResult.Success(parsed.token, parsed.user)
    }

    private fun followToNspaceToken(
        session: UpcCasHttpSession,
        start: HttpUrl,
    ): NspaceTokenResult {
        var current = start
        repeat(UpcNspaceAuthEndpoints.MAX_REDIRECT_HOPS) {
            if (!isAllowedNspacePage(current)) {
                return NspaceTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            }
            tokenFromUrl(current.toString())?.let { return NspaceTokenResult.Found(it) }
            val response = try {
                session.request("GET", current.toString())
            } catch (failure: UpcAuthTransportException) {
                return NspaceTokenResult.Failure(failure.toNspaceFailure())
            }
            if (response.status in 300..399) {
                val location = response.header("Location")
                    ?: return NspaceTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
                val target = resolveNspaceUrl(current.toString(), location)
                    ?: return NspaceTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
                if (!isAllowedNspacePage(target)) {
                    return NspaceTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
                }
                tokenFromUrl(target.toString())?.let { return NspaceTokenResult.Found(it) }
                current = target
                return@repeat
            }
            if (response.status in 401..403 || response.status == 429) {
                return NspaceTokenResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
            }
            if (response.status != 200) {
                return NspaceTokenResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
            }
            return tokenFromUrl(current.toString())
                ?.let { NspaceTokenResult.Found(it) }
                ?: NspaceTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        return NspaceTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    }

    private fun isAllowedNspacePage(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcNspaceAuthEndpoints.LIBRARY_HOST ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) {
            return false
        }
        return url.encodedPath == NSPACE_CAS_SERVICE.callbackPath ||
            url.encodedPath == "/nspace" ||
            url.encodedPath.startsWith("/nspace/")
    }

    private fun tokenFromUrl(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val candidates = buildList {
            url.queryParameter("token")?.let(::add)
            val fragment = url.encodedFragment.orEmpty()
            if (fragment.isNotBlank()) {
                val fragmentQuery = fragment.substringAfter('?', fragment)
                fragmentQuery.split('&').forEach { part ->
                    if (part.startsWith("token=")) {
                        add(
                            java.net.URLDecoder.decode(
                                part.substringAfter('='),
                                StandardCharsets.UTF_8.name(),
                            ),
                        )
                    }
                }
            }
        }
        return candidates.firstOrNull { it.isNotBlank() && it.length <= 4096 }
    }

    private fun resolveNspaceUrl(base: String, raw: String): HttpUrl? =
        try {
            base.toHttpUrlOrNull()?.resolve(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun UpcAuthTransportException.toNspaceFailure(): UpcLibraryAuthFailure =
        when (failure) {
            UpcAuthTransportFailure.NETWORK,
            UpcAuthTransportFailure.TIMEOUT,
            -> UpcLibraryAuthFailure.NETWORK_UNAVAILABLE
            UpcAuthTransportFailure.TLS -> UpcLibraryAuthFailure.TLS_ERROR
            UpcAuthTransportFailure.TOO_LARGE,
            UpcAuthTransportFailure.PROTOCOL,
            -> UpcLibraryAuthFailure.UPSTREAM_ERROR
        }

    private fun UpcAuthFailure.toNspaceFailure(): UpcLibraryAuthFailure = when (this) {
        UpcAuthFailure.INVALID_CREDENTIALS -> UpcLibraryAuthFailure.INVALID_CREDENTIALS
        UpcAuthFailure.INTERACTION_REQUIRED -> UpcLibraryAuthFailure.INTERACTION_REQUIRED
        UpcAuthFailure.NETWORK_UNAVAILABLE -> UpcLibraryAuthFailure.NETWORK_UNAVAILABLE
        UpcAuthFailure.TLS_ERROR -> UpcLibraryAuthFailure.TLS_ERROR
        UpcAuthFailure.UPSTREAM_ERROR -> UpcLibraryAuthFailure.UPSTREAM_ERROR
        UpcAuthFailure.PROTOCOL_ERROR -> UpcLibraryAuthFailure.PROTOCOL_ERROR
        UpcAuthFailure.ACCOUNT_UNAVAILABLE -> UpcLibraryAuthFailure.ACCOUNT_UNAVAILABLE
        UpcAuthFailure.ACCOUNT_CHANGED -> UpcLibraryAuthFailure.ACCOUNT_CHANGED
        UpcAuthFailure.ACCESS_DENIED -> UpcLibraryAuthFailure.ACCESS_DENIED
    }
}

private sealed interface NspaceTokenResult {
    data class Found(val token: String) : NspaceTokenResult
    data class Failure(val reason: UpcLibraryAuthFailure) : NspaceTokenResult
}

private class NspaceJsonFailure(val reason: UpcLibraryAuthFailure) :
    Exception(null, null, false, false)

private val nspaceJson = Json { ignoreUnknownKeys = true; isLenient = true }

private data class ParsedNspaceLogin(
    val token: String,
    val user: UpcNspaceUser,
)

private fun parseNspaceLogin(body: String): ParsedNspaceLogin {
    val root = try {
        nspaceJson.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: throw NspaceJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    val code = (root["code"] as? JsonPrimitive)?.let {
        it.intOrNull ?: it.contentOrNull?.toIntOrNull()
    }
    if (code == 401 || code == 403) {
        throw NspaceJsonFailure(UpcLibraryAuthFailure.ACCESS_DENIED)
    }
    val status = root["status"] as? JsonPrimitive
    if (status?.booleanOrNull == false ||
        status?.contentOrNull?.lowercase() in setOf("fail", "error")
    ) {
        throw NspaceJsonFailure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
    }
    if (code != null && code != 0 && code != 200) {
        throw NspaceJsonFailure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
    }
    val data = root["data"] as? JsonObject
        ?: throw NspaceJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    val token = (data["token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val info = data["userInfo"] as? JsonObject
        ?: throw NspaceJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    val username = (info["username"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    if (token.isBlank() || username.isBlank()) {
        throw NspaceJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    }
    val id = (info["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        .ifBlank { username }
    val name = (info["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        .ifBlank { (info["fullName"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        .ifBlank { (info["nickName"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        .ifBlank { username }
    return ParsedNspaceLogin(token, UpcNspaceUser(id, username, name))
}
