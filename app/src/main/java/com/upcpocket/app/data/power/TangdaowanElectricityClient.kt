package com.upcpocket.app.data.power

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ElectricityQuery(val campus: ElectricityCampus, val building: String, val room: String)

enum class ElectricityCampus(val label: String, val host: String?) {
    TANGDAOWAN("唐岛湾", ElectricityProtocol.HOST),

    /**
     * 古镇口：2026-10-28 复核，证书链本身有效到 2026-07-11，已过期；
     * 且 `acmswx/index.aspx` 在忽略证书校验时返回 500，页面不是同一套查询表单。
     * 这里保留枚举以便界面如实说明状态，**不提供**抓取实现（不绕过 TLS 校验）。
     */
    GUZHENKOU("古镇口", null),
}

data class ElectricityReading(
    val building: String,
    val room: String,
    val systemNo: String,
    val kwh: java.math.BigDecimal,
)

sealed interface ElectricityResult {
    data class Success(val reading: ElectricityReading) : ElectricityResult
    data class Ambiguous(val candidates: List<ElectricityProtocol.Candidate>) : ElectricityResult
}

class ElectricityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 唐岛湾宿舍剩余电量的只读客户团。
 *
 * 只发两次请求：取页面表单，再提交查询表单。没有充值、缴费记录或批量遍历。
 * 一次查询结束就丢弃页面附带的会话状态，不后台保活。
 */
class TangdaowanElectricityClient(
    private val host: String = ElectricityProtocol.HOST,
) {

    suspend fun query(query: ElectricityQuery): ElectricityResult = withContext(Dispatchers.IO) {
        require(query.campus == ElectricityCampus.TANGDAOWAN) { "不支持的校区" }
        require(query.building.isNotBlank()) { "请选择楼号" }
        require(query.room.isNotBlank()) { "请填写房间号" }
        val form = ElectricityProtocol.parseForm(fetchPage())
        if (form.buildings.none { it == query.building }) {
            throw ElectricityException("学校页面没有该楼号，请重新选择")
        }
        val html = postForm(form, query.building, query.room.trim())
        when (val outcome = ElectricityProtocol.parseResult(html, query.room.trim())) {
            is ElectricityProtocol.Outcome.Success -> ElectricityResult.Success(
                ElectricityReading(
                    building = query.building,
                    room = outcome.reading.room.ifBlank { query.room.trim() },
                    systemNo = outcome.reading.systemNo,
                    kwh = outcome.reading.kwh,
                ),
            )
            is ElectricityProtocol.Outcome.Ambiguous -> ElectricityResult.Ambiguous(outcome.candidates)
        }
    }

    /** 楼号列表：只读表单，不提交查询。 */
    suspend fun buildings(): List<String> = withContext(Dispatchers.IO) {
        ElectricityProtocol.parseForm(fetchPage()).buildings
    }

    private fun pageUrl(): String =
        "$host${ElectricityProtocol.PAGE_PATH}?code=${ElectricityProtocol.NEUTRAL_CODE}"

    private fun open(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        return connection
    }

    private fun fetchPage(): String {
        val connection = open(pageUrl())
        try {
            connection.requestMethod = "GET"
            when (val status = connection.responseCode) {
                in 200..299 -> Unit
                in 300..399 -> throw ElectricityException("学校电费页面要求先完成微信授权，当前无法直接查询")
                else -> throw ElectricityException("电费查询暂时不可用（HTTP $status）")
            }
            return connection.inputStream.use { read(it) }
        } catch (e: ElectricityException) {
            throw e
        } catch (e: ElectricityProtocol.PageException) {
            throw ElectricityException(e.message ?: "电费页面解析失败", e)
        } catch (e: IOException) {
            throw ElectricityException("无法连接电费查询系统，请检查网络后重试", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun postForm(form: ElectricityProtocol.Form, building: String, room: String): String {
        val connection = open("$host${ElectricityProtocol.PAGE_PATH}")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            val body = ElectricityProtocol.formBody(form, building, room).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            when (val status = connection.responseCode) {
                in 200..299 -> Unit
                in 300..399 -> throw ElectricityException("电费查询被要求跳转，请稍后重试")
                else -> throw ElectricityException("电费查询暂时不可用（HTTP $status）")
            }
            return connection.inputStream.use { read(it) }
        } catch (e: ElectricityProtocol.PageException) {
            throw ElectricityException(e.message ?: "电费页面解析失败", e)
        } catch (e: IOException) {
            if (e is ElectricityException) throw e
            throw ElectricityException("无法连接电费查询系统，请检查网络后重试", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(stream: java.io.InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                throw ElectricityException("电费页面响应过大，请稍后重试")
            }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
