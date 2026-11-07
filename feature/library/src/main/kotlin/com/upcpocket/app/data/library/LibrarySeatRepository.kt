package com.upcpocket.app.data.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.coroutineContext

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
class LibrarySeatRepository private constructor(
    private val sessionAllowed: () -> Boolean,
    private val reserveTransport: ReserveTransport?,
) {
    // 保留原来的尾随 lambda 构造方式；测试传输只由模块内的定向测试注入。
    constructor(sessionAllowed: () -> Boolean = { true }) : this(sessionAllowed, null)

    internal constructor(
        reserveTransport: ReserveTransport,
        sessionAllowed: () -> Boolean = { true },
    ) : this(sessionAllowed, reserveTransport)

    private val _session = MutableStateFlow<LibrarySeatSession?>(null)
    val session: StateFlow<LibrarySeatSession?> = _session.asStateFlow()

    private val sessionLock = Any()
    private var sessionGeneration = 0L

    /** 会话范围防重闩；关闭重进/重建 ViewModel 只要 repository 仍是同一会话即生效。 */
    val attemptLatch = ReservationAttemptLatch()

    /** 一次 reserve 进入时的会话快照；整个预检→登记→POST 都绑定它。 */
    private data class SessionSnapshot(
        val token: String,
        val user: LibraryUser,
        val generation: Long,
    )

    /** 在同一把锁里原子捕获 token + 本人 + 代次，避免读到半更新状态。 */
    private fun captureSession(): SessionSnapshot? = synchronized(sessionLock) {
        val s = _session.value ?: return null
        SessionSnapshot(s.token, s.user, sessionGeneration)
    }

    /** 会话是否仍是捕获时的那一个（门闩 + token + 代次都一致）。 */
    private fun isSameSession(snapshot: SessionSnapshot): Boolean =
        sessionAllowed() && synchronized(sessionLock) {
            matchesSnapshotLocked(snapshot)
        }

    private fun matchesSnapshotLocked(snapshot: SessionSnapshot): Boolean {
        val current = _session.value ?: return false
        return sessionGeneration == snapshot.generation && current.token == snapshot.token &&
            current.user.id == snapshot.user.id && current.user.username == snapshot.user.username
    }

    /** 登记防重键时绑定**捕获代次**，迟到的旧 reserve 不得占用新会话的键。 */
    private fun registerAt(snapshot: SessionSnapshot, request: LibraryReservationRequest): Boolean =
        synchronized(sessionLock) {
            if (!sessionAllowed() || !matchesSnapshotLocked(snapshot)) {
                throw LibraryReservationContextStaleException()
            }
            attemptLatch.registerOrReject(request, snapshot.generation)
        }

    /** 仅供测试/诊断：当前会话是否已登记该键（不写业务内容）。 */
    internal fun hasAttempted(request: LibraryReservationRequest): Boolean =
        attemptLatch.hasAttempted(request, currentSessionGeneration())

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

    /**
     * 当前房间在目标完整区间可约的一页座位（只读）。
     *
     * beginMinute/endMinute 是 HH:mm 转成的当天分钟数（学校路径参数单位）。
     * page 从 1 起，batch 是该次查询使用的页大小；token/签名头与其它
     * `/rest/v2` 读取一致，不新增加密或查询来源。
     */
    suspend fun timeSearchSeats(
        key: LibraryTimeSearchKey,
        page: Int,
        batch: Int,
        guard: () -> Boolean = { true },
    ): LibraryTimeSearchPage = withToken { token ->
        if (!sessionAllowed() || !guard()) throw LibrarySessionExpiredException()
        LibrarySeatProtocol.parseTimeSearchPage(
            request(
                "GET",
                "/rest/v2/searchSeats/${path(key.date)}/${key.beginMinute}/${key.endMinute}",
                mapOf(
                    "roomId" to key.roomId,
                    "buildingId" to key.buildingId,
                    "batch" to batch.toString(),
                    "page" to page.toString(),
                    "token" to token,
                ),
            ),
            page,
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

    suspend fun reserve(
        date: String,
        seatId: String,
        startId: String,
        endId: String,
    ): LibraryReservationAttempt = withContext(Dispatchers.IO) {
        if (!sessionAllowed()) throw LibrarySessionExpiredException()
        // ── 进入时原子捕获会话：token + 本人 + 代次，整个流程都绑定它 ──
        val snapshot = captureSession() ?: throw LibrarySessionExpiredException()
        val token = snapshot.token
        val user = snapshot.user
        val requestKey = LibraryReservationRequest(
            system = LibraryReservationRequest.SYSTEM_LIBSEAT,
            userId = user.id,
            date = date,
            seatId = seatId,
            startId = startId,
            endId = endId,
        )

        // ── POST 前预检（验证码 / 设置读取）属于「未发出提交」阶段 ──
        // 任何失败都不得冒充已发送；若预检期间会话已变化，整体中止且不清新会话。
        val captcha = try {
            if (reserveTransport != null) {
                reserveTransport.captchaEnabled(token)
            } else {
                captchaEnabledWithToken(token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LibraryReservationContextStaleException) {
            throw e
        } catch (e: LibrarySessionExpiredException) {
            // 预检本身报会话失效：只在仍是捕获会话时才允许清理；否则视为上下文过期。
            if (!isSameSession(snapshot)) throw LibraryReservationContextStaleException()
            throw LibraryReservationPreflightFailedException(
                e.message ?: "提交前验证失败，请在学校页面预约。",
            )
        } catch (e: Exception) {
            if (!isSameSession(snapshot)) throw LibraryReservationContextStaleException()
            throw LibraryReservationPreflightFailedException(
                e.message ?: "提交前验证失败，请在学校页面预约。",
            )
        }
        // 预检返回后、登记前：必须仍是同一会话，否则不能占用/清理新会话。
        coroutineContext.ensureActive()
        if (!isSameSession(snapshot)) throw LibraryReservationContextStaleException()
        if (captcha) return@withContext LibraryReservationAttempt.NOT_SENT

        // ── 登记防重：绑定捕获代次，旧上下文不得写新会话的键 ──
        if (!registerAt(snapshot, requestKey)) {
            throw LibraryReservationDuplicateException()
        }
        // 登记后、真正写请求发出前再校验一次（防止登记与 POST 之间换号）。
        coroutineContext.ensureActive()
        if (!isSameSession(snapshot)) throw LibraryReservationContextStaleException()

        val body = if (reserveTransport != null) {
            reserveTransport.postReservation(token, date, seatId, startId, endId, user)
        } else {
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
                tokenOverride = token, // 签名 token 与 query 都来自捕获会话，不读当前 _session
            )
        }
        LibrarySeatProtocol.ensureBooked(body)
        LibraryReservationAttempt.SENT
    }

    /** 生产预检：用捕获 token 显式签名，不读当前 _session。 */
    private fun captchaEnabledWithToken(token: String): Boolean {
        val root = LibrarySeatProtocol.objectOf(
            request("GET", "/rest/v2/settings", tokenOverride = token),
        )
        val data = root["data"] as? JsonObject
        val raw = (data?.get("isCaptchaOpen") as? JsonPrimitive)?.contentOrNull
        return raw == "1" || raw.equals("true", ignoreCase = true)
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
