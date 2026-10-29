package com.upcpocket.app.data.ecard

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 令牌缺失或被服务端拒绝：需要重新走官方登录。 */
class CampusCardSessionExpiredException(message: String) : IOException(message)

/** 有响应但业务没成功（口径不明、字段变化、服务端错误）。 */
class CampusCardException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 校园卡余额的只读客户端。
 *
 * 只做两件事：带平台自己签发的 bearer 令牌读 `/berserker-app/ykt/tsm/queryCard`，
 * 以及读公开的 `/berserker-app/frontInfo` 确认金额口径。**没有**充值、绑定、改密、
 * 解绑等写操作。流水只读取本人账单。
 *
 * 令牌只在本类与调用方的内存/加密存储之间传递，不写入日志与导出。
 */
open class CampusCardClient(
    private val base: String = CampusCardProtocol.BASE,
) {

    open suspend fun fetch(accessToken: String): CampusCardProtocol.Result = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "登录令牌为空" }
        val basis = CampusCardProtocol.parseFrontInfo(get(CampusCardProtocol.FRONT_INFO_PATH))
        val cards = CampusCardProtocol.parseCards(
            body = get(CampusCardProtocol.CARD_PATH, accessToken),
            basis = basis,
        )
        CampusCardProtocol.Result(basis = basis, cards = cards)
    }

    open suspend fun transactions(token: String, from: java.time.LocalDate, to: java.time.LocalDate, page: Int): CampusCardTransactions.Page = withContext(Dispatchers.IO) {
        CampusCardTransactions.parse(get(CampusCardTransactions.query(from, to, page), token), page)
    }

    private fun get(path: String, token: String? = null): String {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            // 平台前端自己也会带这个来源标记；缺省时服务端按 h5 处理。
            connection.setRequestProperty("synAccessSource", "h5")
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("synjones-auth", authorizationValue(token))
            }
            val status = connection.responseCode
            when (status) {
                in 200..299 -> Unit
                401, 403 -> throw CampusCardSessionExpiredException("校园卡会话已失效，需要重新登录")
                in 300..399 -> throw CampusCardSessionExpiredException("校园卡服务要求重新登录")
                else -> throw CampusCardException("校园卡服务暂时不可用（HTTP $status）")
            }
            return connection.inputStream.use { readBounded(it) }
        } catch (e: CampusCardSessionExpiredException) {
            throw e
        } catch (e: CampusCardException) {
            throw e
        } catch (e: IOException) {
            throw CampusCardException("无法连接校园卡服务，请检查网络后重试", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                throw CampusCardException("校园卡服务响应过大，请稍后重试")
            }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024


    }
}
/**
 * 把令牌整理成 `bearer <token>`。
 *
 * 平台在 URL 里传递令牌时值可能是 `bearer xxx`（前端写过
 * `"&synjones-auth=bearer "+this.token`），而页面路由守卫又把 URL 里的值直接当令牌用
 * （`state.token || getRequest(...)["synjones-auth"]`）。两种形态都可能到我们手里；
 * 先剥掉已有前缀再拼一次，避免生成 `bearer bearer xxx` 这种必然 401 的头。
 */
internal fun authorizationValue(raw: String): String {
    var bare = raw.trim()
    // 反复剥离：回调里可能出现 `bearer bearer xxx`（服务端与前端各加一次）。
    while (bare.startsWith("bearer", ignoreCase = true)) {
        bare = bare.substring(6).trim()
    }
    return "bearer $bare"
}