package com.upcpocket.app.data.chaoxing

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ChaoxingHttpRequest(
    val method: String,
    val url: String,
    val cookieHeader: String,
    val formBody: String? = null,
)

internal data class ChaoxingHttpResponse(
    val status: Int,
    val body: String,
    val location: String = "",
)

internal fun interface ChaoxingTransport {
    fun execute(request: ChaoxingHttpRequest): ChaoxingHttpResponse
}

/**
 * Chaoxing 只读数据层。
 *
 * Cookie 来自受限官方 WebView 的一次登录，仅保存在 Service 内存；这里不写账号库、
 * 不持久化、不读取密码。所有 URL 在发出前校验固定 host/path。
 */
class ChaoxingRepository internal constructor(
    private val transport: ChaoxingTransport = UrlConnectionChaoxingTransport(),
) {
    constructor() : this(UrlConnectionChaoxingTransport())

    suspend fun identity(cookieHeader: String): ChaoxingUser = withContext(Dispatchers.IO) {
        val html = read(cookieHeader, seatIndexUrl())
        ChaoxingProtocol.parseIdentity(html)
    }

    suspend fun seatRooms(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        date: String,
    ): ChaoxingSeatRoomPage = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "time" to "",
            "cpage" to "1",
            "pageSize" to "100",
            "firstLevelName" to "",
            "secondLevelName" to "",
            "thirdLevelName" to "",
            "day" to date,
            "deptIdEnc" to FID_ENC,
        )
        if (kind == ChaoxingSeatKind.SEATENGINE) params["seatId"] = SEAT_ID
        val body = read(
            cookieHeader,
            "https://office.chaoxing.com/data/apps/${kind.api}/room/list?" + query(params),
        )
        ChaoxingProtocol.parseSeatRooms(body, kind, date)
    }

    suspend fun seatRoomDetail(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        date: String,
    ): ChaoxingSeatRoomDetail = withContext(Dispatchers.IO) {
        val info = read(
            cookieHeader,
            "https://office.chaoxing.com/data/apps/${kind.api}/room/info",
            formBody = form(
                linkedMapOf(
                    "id" to roomId.toString(),
                    "fidEnc" to FID_ENC,
                ),
            ),
        )
        val detail = ChaoxingProtocol.parseSeatRoomDetail(info, date, roomId)
        val map = read(
            cookieHeader,
            "https://office.chaoxing.com/data/apps/${kind.api}/getdrawseat?" +
                query(linkedMapOf("id" to roomId.toString())),
        )
        detail.copy(places = ChaoxingProtocol.parseSeatPlaces(map))
    }

    suspend fun usedSeatNums(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        startMinute: Int,
        endMinute: Int,
        date: String,
    ): Set<String> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "roomId" to roomId.toString(),
            "startTime" to clock(startMinute),
            "endTime" to clock(endMinute),
            "day" to date,
        )
        if (kind == ChaoxingSeatKind.SEATENGINE) {
            params["seatId"] = SEAT_ID
        } else {
            params["fidEnc"] = FID_ENC
        }
        val body = read(
            cookieHeader,
            "https://office.chaoxing.com/data/apps/${kind.api}/getusedseatnums?" +
                query(params),
        )
        ChaoxingProtocol.parseUsedSeatNums(body)
    }

    /**
     * 原生提交一次预约。只允许由用户明确确认后调用；不重试、不轮询、不取消、不签到。
     * 先 GET 官方选座页取得页面级 submit_enc，再按官方 submitVerify 规则计算 enc 后 POST。
     * 网络中断/无法解析响应一律返回 Unknown，绝不把“可能已成功”报成失败再让用户重试。
     */
    suspend fun submitReservation(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        date: String,
        startMinute: Int,
        endMinute: Int,
        seatNum: String,
        beforePost: () -> Boolean = { true },
    ): ChaoxingReservationResult = withContext(Dispatchers.IO) {
        val selectParams = linkedMapOf(
            "id" to roomId.toString(),
            "deptIdEnc" to FID_ENC,
            "fidEnc" to FID_ENC,
            "day" to date,
        )
        if (kind == ChaoxingSeatKind.SEATENGINE) selectParams["seatId"] = SEAT_ID
        val secrets = try {
            val html = read(
                cookieHeader,
                "https://office.chaoxing.com/front/third/apps/${kind.api}/select?" +
                    query(selectParams),
            )
            ChaoxingReservationProtocol.parseSelectPage(html)
        } catch (expired: ChaoxingSessionExpiredException) {
            throw expired
        } catch (failure: IOException) {
            return@withContext ChaoxingReservationResult.Rejected(
                "提交前检查失败，未提交，请重试。",
            )
        }
        if (secrets.riskCheckRequired) {
            return@withContext ChaoxingReservationResult.InteractionRequired(
                "学校要求安全验证；请在官方页完成本次预约。",
            )
        }
        if (kind == ChaoxingSeatKind.SEATENGINE && secrets.wfwEngineEnc.length > 256) {
            return@withContext ChaoxingReservationResult.Rejected(
                "选座页校验字段异常，未提交。",
            )
        }
        val params = ChaoxingReservationProtocol.submitParams(
            kind = kind,
            deptIdEnc = FID_ENC,
            roomId = roomId,
            day = date,
            startTime = clock(startMinute),
            endTime = clock(endMinute),
            seatNum = seatNum,
            submitEnc = secrets.submitEnc,
            wfwEngineEnc = secrets.wfwEngineEnc,
        )
        if (!beforePost()) return@withContext ChaoxingReservationResult.Rejected("登录或选择已变化，未重复提交。")
        val response = try {
            fetch(
                cookieHeader,
                "https://office.chaoxing.com/data/apps/${kind.api}/submit",
                formBody = form(params),
            )
        } catch (failure: Exception) {
            return@withContext ChaoxingReservationResult.Unknown(
                "网络中断，提交结果未知；请到官方预约记录核对，勿连续重试。",
            )
        }
        when {
            response.status in setOf(401, 403, 302, 303) ->
                ChaoxingReservationResult.Unknown("登录在提交时失效，结果未知；请核对学校记录。")
            response.status == 429 -> ChaoxingReservationResult.Unknown(
                "请求过于频繁，提交结果未知；请到学校记录核对，不要连续重试。",
            )
            response.status !in 200..299 -> ChaoxingReservationResult.Unknown(
                "学校服务暂时不可用，提交结果未知；请到学校记录核对。",
            )
            else -> ChaoxingReservationProtocol.parseSubmitResponse(
                response.body,
                seatNum,
                date,
            )
        }
    }

    suspend fun usedTimes(
        kind: ChaoxingSeatKind,
        cookieHeader: String,
        roomId: Int,
        seatNum: String,
        date: String,
    ): List<ChaoxingTimeInterval> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "roomId" to roomId.toString(),
            "seatNum" to seatNum,
            "day" to date,
        )
        if (kind == ChaoxingSeatKind.SEATENGINE) {
            params["seatId"] = SEAT_ID
        } else {
            params["fidEnc"] = FID_ENC
        }
        val body = read(
            cookieHeader,
            "https://office.chaoxing.com/data/apps/${kind.api}/getusedtimes?" +
                query(params),
        )
        ChaoxingProtocol.parseUsedTimes(body)
    }

    private fun fetch(
        cookieHeader: String,
        url: String,
        formBody: String? = null,
    ): ChaoxingHttpResponse {
        requireChaoxingUrl(url)
        return transport.execute(
            ChaoxingHttpRequest(
                method = if (formBody == null) "GET" else "POST",
                url = url,
                cookieHeader = cookieHeader,
                formBody = formBody,
            ),
        )
    }

    private fun read(
        cookieHeader: String,
        url: String,
        formBody: String? = null,
    ): String {
        val response = fetch(cookieHeader, url, formBody)
        return when {
            response.status in setOf(401, 403, 302, 303) ->
                throw ChaoxingSessionExpiredException()
            response.status == 429 -> throw ChaoxingException("请求过于频繁", "chaoxing_rate_limited")
            response.status !in 200..299 ->
                throw ChaoxingException("学校返回 HTTP ${response.status}", "chaoxing_upstream")
            else -> response.body
        }
    }

    private fun seatIndexUrl(): String =
        "https://office.chaoxing.com/front/third/apps/seat/index?" +
            query(linkedMapOf("fidEnc" to FID_ENC, "mappId" to SEAT_MAPP_ID))

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) -> "$key=" + enc(value) }

    private fun form(params: Map<String, String>): String = query(params)

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun clock(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)

    private fun requireChaoxingUrl(raw: String) {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw ChaoxingException("非法 URL", "chaoxing_protocol")
        if (uri.scheme != "https" || uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            throw ChaoxingException("非法 Chaoxing URL", "chaoxing_protocol")
        }
        val host = uri.host?.lowercase()
        val path = uri.path.orEmpty()
        val allowed = when (host) {
            "office.chaoxing.com" ->
                path.startsWith("/data/apps/seat/") ||
                    path.startsWith("/data/apps/seatengine/") ||
                    path == "/front/third/apps/seat/index" ||
                    path == "/front/third/apps/seatengine/index" ||
                    path == "/front/third/apps/seat/select" ||
                    path == "/front/third/apps/seatengine/select"
            else -> false
        }
        if (!allowed) throw ChaoxingException("未允许的 Chaoxing 路径", "chaoxing_protocol")
    }

    private companion object {
        const val FID_ENC = "e32f8fbde51f9dba"
        const val SEAT_ID = "4258"
        const val SEAT_MAPP_ID = "4197453"
    }
}

private class UrlConnectionChaoxingTransport : ChaoxingTransport {
    override fun execute(request: ChaoxingHttpRequest): ChaoxingHttpResponse {
        val url = URI(request.url).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json, text/html;q=0.9")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36",
            )
            if (request.cookieHeader.isNotBlank()) {
                setRequestProperty("Cookie", request.cookieHeader)
            }
            if (request.formBody != null) {
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
            }
        }
        try {
            if (request.formBody != null) {
                connection.outputStream.use { output ->
                    output.write(request.formBody.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val location = connection.getHeaderField("Location").orEmpty()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 2 * 1024 * 1024) {
                        throw ChaoxingException("学校响应过大", "chaoxing_protocol")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            return ChaoxingHttpResponse(
                status,
                bytes.toString(StandardCharsets.UTF_8),
                location,
            )
        } finally {
            connection.disconnect()
        }
    }
}
