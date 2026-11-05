package com.upcpocket.app.data.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class LibrarySeatSession(
    val token: String,
    val user: LibraryUser,
)

/**
 * 学校图书馆座位系统的独立数据层。
 *
 * 只访问 `libspace.upc.edu.cn` 的公开前端使用的正常接口；登录由官方 CAS 完成
 * （统一账号原生认证或学校 WebView 回退），本类只接收并核验登录后的 token。
 * token 仅由 Service 的受控提交门闩发布到内存；本类不写数据库/文件/日志。
 */
class LibrarySeatRepository(
    private val sessionAllowed: () -> Boolean = { true },
) {
    private val _session = MutableStateFlow<LibrarySeatSession?>(null)
    val session: StateFlow<LibrarySeatSession?> = _session.asStateFlow()

    private val sessionLock = Any()
    private var sessionGeneration = 0L

    /**
     * 只完成 token 兑换/本人读取，不发布会话。
     *
     * 页面或 Service 必须先做账号身份校验，再通过同一个受控提交门闩发布；否则
     * WebView 中登录到另一个账号的 token 会短暂进入 UI/缓存。
     */
    suspend fun resolveSeatSession(
        token: String,
        alreadySeatToken: Boolean,
        guard: () -> Boolean = { true },
    ): LibrarySeatSession = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "登录 token 为空" }
        if (!allowed(guard)) throw LibrarySessionExpiredException()
        val seatToken = if (alreadySeatToken) {
            token
        } else {
            val body = request(
                method = "POST",
                path = "/rest/ssoAuth",
                query = mapOf("token" to token),
                form = mapOf("token" to token),
                tokenOverride = token,
            )
            LibrarySeatProtocol.parseToken(body)
        }
        if (!allowed(guard)) throw LibrarySessionExpiredException()
        val body = request(
            method = "GET",
            path = "/rest/v2/user",
            query = mapOf("token" to seatToken),
            tokenOverride = seatToken,
        )
        val user = LibrarySeatProtocol.parseUser(body)
        if (!allowed(guard)) throw LibrarySessionExpiredException()
        LibrarySeatSession(seatToken, user)
    }

    /**
     * 已由受控认证适配完成 token 兑换与本人校验时，只发布已经核验的会话。
     *
     * 调用方必须先在同一个提交门闩里确认账号身份；发布仍绑定到当前 repository
     * 代次和账号门闩，迟到的结果不能写回。
     */
    suspend fun publishAuthenticatedSession(
        session: LibrarySeatSession,
        guard: () -> Boolean = { true },
    ): LibrarySeatSession = withContext(Dispatchers.IO) {
        require(session.token.isNotBlank()) { "登录 token 为空" }
        require(session.user.id.isNotBlank() && session.user.username.isNotBlank()) {
            "本人标识为空"
        }
        if (!allowed(guard)) throw LibrarySessionExpiredException()
        val expectedGeneration = currentSessionGeneration()
        if (!publishSessionIfCurrent(session, expectedGeneration, guard)) {
            throw LibrarySessionExpiredException()
        }
        session
    }

    fun clearSession() {
        synchronized(sessionLock) {
            sessionGeneration += 1
            _session.value = null
        }
    }

    private fun allowed(guard: () -> Boolean): Boolean = sessionAllowed() && guard()

    internal fun currentSessionGeneration(): Long = synchronized(sessionLock) {
        sessionGeneration
    }

    internal fun publishSessionIfCurrent(
        session: LibrarySeatSession,
        expected: Long,
        guard: () -> Boolean,
    ): Boolean = synchronized(sessionLock) {
        if (sessionGeneration != expected || !allowed(guard)) {
            false
        } else {
            _session.value = session
            true
        }
    }

    suspend fun filters(): LibraryFilters = withToken { token ->
        LibrarySeatProtocol.parseFilters(
            request("GET", "/rest/v2/free/filters", mapOf("token" to token)),
        )
    }

    suspend fun floors(buildingId: String): List<LibraryFloor> = withToken { token ->
        LibrarySeatProtocol.parseFloors(
            request(
                "POST",
                "/rest/v2/room/floors/${path(buildingId)}",
                mapOf("token" to token),
            ),
        )
    }

    suspend fun rooms(buildingId: String, date: String): List<LibraryRoom> = withToken { token ->
        LibrarySeatProtocol.parseRooms(
            request(
                "GET",
                "/rest/v2/room/stats2/${path(buildingId)}/${path(date)}",
                mapOf("token" to token),
            ),
            LibraryBuilding(id = buildingId, name = ""),
        )
    }

    suspend fun layout(roomId: String, date: String): LibrarySeatLayout = withToken { token ->
        LibrarySeatProtocol.parseLayout(
            request(
                "GET",
                "/rest/v2/room/layoutByDate/${path(roomId)}/${path(date)}",
                mapOf("token" to token),
            ),
        )
    }

    suspend fun startTimes(seatId: String, date: String): List<LibraryTimeOption> = withToken { token ->
        LibrarySeatProtocol.parseStartTimes(
            request(
                "GET",
                "/rest/v2/startTimesForSeat/${path(seatId)}/${path(date)}",
                mapOf("token" to token),
            ),
        )
    }

    suspend fun endTimes(seatId: String, date: String, start: String): List<LibraryTimeOption> =
        withToken { token ->
            LibrarySeatProtocol.parseEndTimes(
                request(
                    "GET",
                    "/rest/v2/endTimesForSeat/${path(seatId)}/${path(date)}/${path(start)}",
                    mapOf("token" to token),
                ),
            )
        }

    suspend fun myReservations(): List<LibraryReservation> = withToken { token ->
        LibrarySeatProtocol.parseCurrentReservations(
            request("GET", "/rest/v2/user/reservations", mapOf("token" to token)),
        )
    }

    suspend fun history(page: Int = 1, pageSize: Int = 50): List<LibraryReservation> =
        withToken { token ->
            LibrarySeatProtocol.parseHistoryReservations(
                request(
                    "GET",
                    "/rest/v2/history/$page/$pageSize",
                    mapOf("token" to token, "page" to page.toString(), "pageSize" to pageSize.toString()),
                ),
            )
        }

    suspend fun captchaEnabled(): Boolean = withContext(Dispatchers.IO) {
        val root = LibrarySeatProtocol.objectOf(
            request("GET", "/rest/v2/settings"),
        )
        val data = root["data"] as? JsonObject
        val raw = (data?.get("isCaptchaOpen") as? JsonPrimitive)?.contentOrNull
        raw == "1" || raw.equals("true", ignoreCase = true)
    }

    suspend fun reserve(
        date: String,
        seatId: String,
        startId: String,
        endId: String,
    ): Unit = withToken { token ->
        if (captchaEnabled()) throw LibraryCaptchaRequiredException()
        val user = _session.value?.user ?: throw LibrarySessionExpiredException()
        LibrarySeatProtocol.ensureBooked(
            request(
                "POST",
                "/rest/v2/freeBook",
                mapOf("token" to token),
                form = mapOf(
                    "startTime" to if (startId == "now") "-1" else startId,
                    "endTime" to endId,
                    "seat" to seatId,
                    "date" to date,
                    "userId" to user.id,
                    "username" to user.username,
                    "authid" to "",
                ),
            ),
        )
    }

    suspend fun cancel(reservationId: String): Unit = withToken { token ->
        LibrarySeatProtocol.ensureBooked(
            request(
                "GET",
                "/rest/v2/cancel/${path(reservationId)}",
                mapOf("token" to token),
            ),
        )
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T = withContext(Dispatchers.IO) {
        if (!sessionAllowed()) {
            clearSession()
            throw LibrarySessionExpiredException()
        }
        val token = _session.value?.token ?: throw LibrarySessionExpiredException()
        try {
            val result = block(token)
            if (!sessionAllowed()) throw LibrarySessionExpiredException()
            result
        } catch (e: LibrarySessionExpiredException) {
            clearSession()
            throw e
        }
    }

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        tokenOverride: String? = null,
    ): String {
        val token = tokenOverride ?: _session.value?.token
        val queryString = if (query.isEmpty()) "" else "?" + LibrarySeatProtocol.queryEncode(query)
        val connection = URI(LibrarySeatProtocol.BASE_URL + path + queryString).toURL()
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            LibrarySeatProtocol.signedHeaders(token, method).forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            if (method == "POST") {
                val body = LibrarySeatProtocol.formEncode(form.orEmpty()).toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded;charset=UTF-8",
                )
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            when (status) {
                in 200..299 -> Unit
                401, 403 -> throw LibrarySessionExpiredException()
                in 300..399 -> throw LibrarySeatException("登录可能已失效，请重试")
                else -> throw LibrarySeatException("学校服务暂时不可用，请稍后重试")
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                        throw LibrarySeatException("学校响应过大，请稍后重试")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        } catch (e: LibrarySeatException) {
            throw e
        } catch (e: IOException) {
            throw LibrarySeatException("无法连接图书馆座位系统，请检查网络后重试", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun path(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
