package com.upcpocket.app.data.nspace

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class NspaceRequest(
    val method: String,
    val path: String,
    val token: String,
    val body: String? = null,
)

internal data class NspaceResponse(
    val status: Int,
    val body: String,
)

internal fun interface NspaceTransport {
    fun execute(request: NspaceRequest): NspaceResponse
}

/**
 * nspace 独立数据层。
 *
 * 与 libseat 共用 libspace 主机但使用不同业务 token、不同 `/spa` 路径。
 * 普通查询只读；预约提交/取消不在此层静默调用。
 */
class NspaceRepository internal constructor(
    private val transport: NspaceTransport = UrlConnectionNspaceTransport(),
) {
    suspend fun venues(token: String): List<NspaceVenue> = withContext(Dispatchers.IO) {
        NspaceProtocol.parseVenues(read(token, "/spa/static/api/book/getVenueList"))
    }

    suspend fun roomTypes(token: String, venueId: String): List<NspaceRoomType> =
        withContext(Dispatchers.IO) {
            NspaceProtocol.parseRoomTypes(
                read(
                    token,
                    "/spa/static/api/book/getRoomTypeList?venueId=" + enc(venueId),
                ),
            )
        }

    suspend fun openDates(token: String, venueId: String, roomTypeId: String): List<String> =
        withContext(Dispatchers.IO) {
            NspaceProtocol.parseDates(
                read(
                    token,
                    "/spa/static/api/book/getOpenDateList?venueId=" + enc(venueId) +
                        "&roomTypeId=" + enc(roomTypeId),
                ),
            )
        }

    suspend fun rooms(
        token: String,
        venueId: String,
        roomTypeId: String,
        date: String,
        page: Int,
    ): NspaceRoomPage = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("venueId", venueId)
            put("roomTypeId", roomTypeId)
            put("pageSize", 6)
            put("currentPage", page)
            put("selectDate", date)
        }.toString()
        NspaceProtocol.parseRoomPage(
            read(token, "/spa/static/api/book/getRoomList", body),
        )
    }

    private fun read(token: String, path: String, body: String? = null): String {
        val response = transport.execute(
            NspaceRequest(
                method = if (body == null) "GET" else "POST",
                path = path,
                token = token,
                body = body,
            ),
        )
        return when {
            response.status in setOf(401, 403) -> throw NspaceSessionExpiredException()
            response.status == 429 -> throw NspaceException("请求过于频繁", "nspace_rate_limited")
            response.status !in 200..299 -> throw NspaceException(
                "学校返回 HTTP ${response.status}",
                "nspace_upstream",
            )
            else -> response.body
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

private class UrlConnectionNspaceTransport : NspaceTransport {
    override fun execute(request: NspaceRequest): NspaceResponse {
        val path = request.path
        require(path.startsWith("/spa/static/api/book/")) { "非法 nspace 路径" }
        val url = URI(NspaceProtocol.BASE_URL + path).toURL()
        require(url.protocol == "https" && url.host == "libspace.upc.edu.cn") {
            "非法 nspace 主机"
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", request.token)
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36",
            )
            if (request.body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (request.body != null) {
                connection.outputStream.use { output ->
                    output.write(request.body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
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
                        throw NspaceException("学校响应过大", "nspace_protocol")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            return NspaceResponse(status, bytes.toString(StandardCharsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
