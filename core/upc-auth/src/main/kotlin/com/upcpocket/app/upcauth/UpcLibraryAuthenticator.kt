package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.SchoolAccountId
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** libseat 认证涉及的统一入口、CAS service 与两个业务端点。 */
object UpcLibraryAuthEndpoints {
    const val CAS_HOST = "cas.upc.edu.cn"
    const val LIBRARY_HOST = "libspace.upc.edu.cn"
    const val ENTRY_URL =
        "https://libspace.upc.edu.cn/remote/static/sso/login" +
            "?redirectUrl=https%3A%2F%2Flibspace.upc.edu.cn%2Flibseat%2F%23%2Flogin"
    const val SERVICE =
        "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin"
    const val SSO_AUTH_URL = "https://libspace.upc.edu.cn/rest/ssoAuth"
    const val USER_URL = "https://libspace.upc.edu.cn/rest/v2/user"
    const val MAX_REDIRECT_HOPS = 8
}

internal val LIBRARY_CAS_SERVICE = UpcCasService(
    casHost = UpcLibraryAuthEndpoints.CAS_HOST,
    serviceUrl = UpcLibraryAuthEndpoints.SERVICE,
    callbackHost = UpcLibraryAuthEndpoints.LIBRARY_HOST,
    callbackPath = "/remote/static/casAuth/getServiceByVerifyTicket/casLogin",
    callbackParameterNames = setOf("ticket"),
    maxRedirectHops = UpcLibraryAuthEndpoints.MAX_REDIRECT_HOPS,
)

/** 图书馆座位系统返回的本人标识；不包含密码、Cookie 或 token。 */
data class UpcLibraryUser(
    val id: String,
    val username: String,
    val name: String,
)

enum class UpcLibraryAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    ACCESS_DENIED,
    IDENTITY_MISMATCH,
}

sealed interface UpcLibraryAuthResult {
    data class Success(
        val seatToken: String,
        val user: UpcLibraryUser,
    ) : UpcLibraryAuthResult

    data class Failure(
        val reason: UpcLibraryAuthFailure,
        /** 仅 INTERACTION_REQUIRED 时给出学校正常登录页。 */
        val interactionUrl: String? = null,
    ) : UpcLibraryAuthResult
}

/**
 * 用统一账号的受控凭据完成 libseat 正常认证：
 *
 * 1. 图书馆 SSO 入口建立 JSESSIONID 并跳转官方 CAS；
 * 2. CAS 表单只提交一次密码，拿到单次 service ticket；
 * 3. 跟随 libspace 回调取得 app token；
 * 4. `POST /rest/ssoAuth`（签名头 + query/form token）换 seat token；
 * 5. `GET /rest/v2/user` 校验本人，返回 seat token 与真实返回的用户标识。
 *
 * 本类不保存 token、Cookie、密码或 CAS 检查点；持久化由 feature 的加密存储完成。
 * 调用方必须再次把结果绑定到同一个账号/凭据代次，并在发布前复核。
 */
class UpcLibraryAuthenticator internal constructor(
    private val credentials: SchoolCredentialAccess,
    private val transport: UpcAuthTransport,
) {
    constructor(credentials: SchoolCredentialAccess) : this(credentials, OkHttpUpcAuthTransport())

    @OptIn(RestrictedSchoolCredentialUse::class)
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): UpcLibraryAuthResult {
        val result = try {
            credentials.withLoginCredential { credential ->
                if (credential.accountId != expectedAccountId ||
                    credential.credentialGeneration.value != expectedCredentialGeneration
                ) {
                    UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_CHANGED)
                } else {
                    performLogin(credential)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        return when (result) {
            is SchoolCredentialUseResult.Success -> result.value
            is SchoolCredentialUseResult.Failure -> when (result.reason) {
                SchoolCredentialFailure.CHANGED_DURING_USE ->
                    UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_CHANGED)
                SchoolCredentialFailure.BLOCKED_BY_CLEANUP,
                SchoolCredentialFailure.NOT_CONFIGURED,
                SchoolCredentialFailure.UNAVAILABLE,
                -> UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCOUNT_UNAVAILABLE)
            }
        }
    }

    @OptIn(RestrictedSchoolCredentialUse::class)
    private fun performLogin(credential: SchoolLoginCredential): UpcLibraryAuthResult {
        val jar = UpcAuthCookieJar(
            setOf(UpcLibraryAuthEndpoints.CAS_HOST, UpcLibraryAuthEndpoints.LIBRARY_HOST),
        )
        val session = UpcCasHttpSession(transport, jar)
        val entry = try {
            session.request("GET", UpcLibraryAuthEndpoints.ENTRY_URL)
        } catch (failure: UpcAuthTransportException) {
            return UpcLibraryAuthResult.Failure(failure.toLibraryFailure())
        }
        if (entry.status in 401..403 || entry.status == 429) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (entry.status !in setOf(301, 302, 303, 307, 308)) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        val casUrl = entry.header("Location")
            ?.let { resolveLibraryUrl(UpcLibraryAuthEndpoints.ENTRY_URL, it) }
            ?: return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        if (!LIBRARY_CAS_SERVICE.isCasLoginUrl(casUrl)) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }

        val cas = UpcCasLogin(LIBRARY_CAS_SERVICE, transport)
            .login(credential, casUrl.toString(), session)
        val callback = when (cas) {
            is UpcCasLoginResult.Failure ->
                return UpcLibraryAuthResult.Failure(
                    cas.reason.toLibraryFailure(),
                    cas.interactionUrl,
                )
            is UpcCasLoginResult.Callback -> cas
        }
        val appToken = when (val follow = followToAppToken(callback.session, callback.url)) {
            is FollowTokenResult.Found -> follow.token
            is FollowTokenResult.Failure ->
                return UpcLibraryAuthResult.Failure(follow.reason)
        }

        val exchange = try {
            callback.session.request(
                "POST",
                UpcLibraryAuthEndpoints.SSO_AUTH_URL + "?token=" + encode(appToken),
                headers = UpcLibrarySigning.signedHeaders(appToken, "POST") + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                ),
                body = "token=" + encode(appToken),
            )
        } catch (failure: UpcAuthTransportException) {
            return UpcLibraryAuthResult.Failure(failure.toLibraryFailure())
        }
        if (exchange.status in 401..403 || exchange.status == 429) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (exchange.status != 200) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
        }
        val seatToken = try {
            parseLibraryToken(exchange.text())
        } catch (failure: LibraryJsonFailure) {
            return UpcLibraryAuthResult.Failure(failure.reason)
        }

        val userResponse = try {
            callback.session.request(
                "GET",
                UpcLibraryAuthEndpoints.USER_URL + "?token=" + encode(seatToken),
                headers = UpcLibrarySigning.signedHeaders(seatToken, "GET"),
            )
        } catch (failure: UpcAuthTransportException) {
            return UpcLibraryAuthResult.Failure(failure.toLibraryFailure())
        }
        if (userResponse.status in 401..403 || userResponse.status == 429) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
        }
        if (userResponse.status != 200) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
        }
        val user = try {
            parseLibraryUser(userResponse.text())
        } catch (failure: LibraryJsonFailure) {
            return UpcLibraryAuthResult.Failure(failure.reason)
        }
        if (user.username.trim() != credential.loginName.trim()) {
            return UpcLibraryAuthResult.Failure(UpcLibraryAuthFailure.IDENTITY_MISMATCH)
        }
        return UpcLibraryAuthResult.Success(seatToken, user)
    }

    private fun followToAppToken(
        session: UpcCasHttpSession,
        start: HttpUrl,
    ): FollowTokenResult {
        var current = start
        repeat(UpcLibraryAuthEndpoints.MAX_REDIRECT_HOPS) {
            tokenFromUrl(current.toString())?.let { return FollowTokenResult.Found(it) }
            if (!isAllowedLibraryPage(current)) {
                return FollowTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
            }
            val response = try {
                session.request("GET", current.toString())
            } catch (failure: UpcAuthTransportException) {
                return FollowTokenResult.Failure(failure.toLibraryFailure())
            }
            if (response.status in 300..399) {
                val location = response.header("Location")
                    ?: return FollowTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
                val target = resolveLibraryUrl(current.toString(), location)
                    ?: return FollowTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
                tokenFromUrl(target.toString())?.let { return FollowTokenResult.Found(it) }
                current = target
                return@repeat
            }
            if (response.status in 401..403 || response.status == 429) {
                return FollowTokenResult.Failure(UpcLibraryAuthFailure.ACCESS_DENIED)
            }
            if (response.status != 200) {
                return FollowTokenResult.Failure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
            }
            return tokenFromUrl(current.toString())
                ?.let { FollowTokenResult.Found(it) }
                ?: FollowTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
        }
        return FollowTokenResult.Failure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    }

    private fun isAllowedLibraryPage(url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.host != UpcLibraryAuthEndpoints.LIBRARY_HOST ||
            (url.port != -1 && url.port != 443) ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) {
            return false
        }
        return url.encodedPath == LIBRARY_CAS_SERVICE.callbackPath ||
            url.encodedPath == "/libseat" ||
            url.encodedPath.startsWith("/libseat/")
    }

    private fun tokenFromUrl(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val candidates = buildList {
            url.queryParameter("token")?.let(::add)
            val fragment = url.encodedFragment.orEmpty()
            if (fragment.isNotBlank()) {
                // Vue hash 路由常见形状：`#/login?token=...`，token 在 fragment 的
                // 查询串里，而不是 fragment 的最外层。
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

    private fun resolveLibraryUrl(base: String, raw: String): HttpUrl? =
        try {
            base.toHttpUrlOrNull()?.resolve(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun UpcAuthTransportException.toLibraryFailure(): UpcLibraryAuthFailure =
        when (failure) {
            UpcAuthTransportFailure.NETWORK,
            UpcAuthTransportFailure.TIMEOUT,
            -> UpcLibraryAuthFailure.NETWORK_UNAVAILABLE
            UpcAuthTransportFailure.TLS -> UpcLibraryAuthFailure.TLS_ERROR
            UpcAuthTransportFailure.TOO_LARGE,
            UpcAuthTransportFailure.PROTOCOL,
            -> UpcLibraryAuthFailure.UPSTREAM_ERROR
        }

    private fun UpcAuthFailure.toLibraryFailure(): UpcLibraryAuthFailure = when (this) {
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

private sealed interface FollowTokenResult {
    data class Found(val token: String) : FollowTokenResult
    data class Failure(val reason: UpcLibraryAuthFailure) : FollowTokenResult
}

internal class LibraryJsonFailure(val reason: UpcLibraryAuthFailure) :
    Exception(null, null, false, false)

private val libraryJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseLibraryToken(body: String): String {
    val root = parseLibraryRoot(body)
    val data = root["data"] as? JsonObject ?: throw LibraryJsonFailure(
        UpcLibraryAuthFailure.PROTOCOL_ERROR,
    )
    val token = (data["token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (token.isBlank()) throw LibraryJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    return token
}

private fun parseLibraryUser(body: String): UpcLibraryUser {
    val root = parseLibraryRoot(body)
    val data = root["data"] as? JsonObject ?: throw LibraryJsonFailure(
        UpcLibraryAuthFailure.PROTOCOL_ERROR,
    )
    val id = (data["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val username = (data["username"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (id.isBlank() || username.isBlank()) {
        throw LibraryJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    }
    val name = (data["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        .ifBlank { username }
    return UpcLibraryUser(id = id, username = username, name = name)
}

private fun parseLibraryRoot(body: String): JsonObject {
    val root = try {
        libraryJson.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: throw LibraryJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    val code = (root["code"] as? JsonPrimitive)?.let {
        it.intOrNull ?: it.contentOrNull?.toIntOrNull()
    }
    if (code == 12) throw LibraryJsonFailure(UpcLibraryAuthFailure.ACCESS_DENIED)
    val status = root["status"] as? JsonPrimitive
    if (status?.booleanOrNull == false ||
        status?.contentOrNull?.lowercase() in setOf("fail", "error")
    ) {
        throw LibraryJsonFailure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
    }
    if (code != null && code != 0 && code != 200) {
        throw LibraryJsonFailure(UpcLibraryAuthFailure.UPSTREAM_ERROR)
    }
    if (root["status"] == null && root["data"] == null && root["success"] == null) {
        throw LibraryJsonFailure(UpcLibraryAuthFailure.PROTOCOL_ERROR)
    }
    return root
}
