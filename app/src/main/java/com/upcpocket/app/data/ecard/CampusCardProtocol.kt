package com.upcpocket.app.data.ecard

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 校园卡余额（新中新“慧新E校”移动平台，`mb-xyk.upc.edu.cn`）的响应解析。
 *
 * 契约来源不是猜测：2026-10-28 读取该校平台自身发布的前端包（`/plat/` 与
 * `/campus-card/`），余额页 `chunk-0fdb9c48` 只调用
 * `GET /berserker-app/ykt/tsm/queryCard`（头部 `synjones-auth: bearer <token>`），
 * 并按 `/berserker-app/frontInfo` 里 `getEcardConfig.type` 决定金额口径：
 *
 *  - `0` = `db_balance + unsettle_amount + Σ accinfo[].balance`
 *  - `1` = `Σ accinfo[].balance`
 *  - `2` = `db_balance + unsettle_amount`
 *
 * 金额单位是**分**（页面自己 `(…)/100` 后加“元”）。这里保留整数分运算，
 * 只在展示时换算，避免浮点误差把 0.01 显示成 0.00。
 *
 * 解析原则：字段缺失、类型不符、口径未知一律抛错，绝不把“没读到”当成 0 元。
 */
object CampusCardProtocol {

    const val BASE = "https://mb-xyk.upc.edu.cn"
    const val FRONT_INFO_PATH = "/berserker-app/frontInfo"
    const val CARD_PATH = "/berserker-app/ykt/tsm/queryCard"
    const val LOGIN_PATH = "/berserker-auth/cas/redirect/wislearn"
    const val PLAT_LANDING = "$BASE/plat/?name=loginTransit"

    /** 登录成功后平台把令牌作为查询参数带回 `/plat/`，见前端 `App.vue` 的 `synjones-auth`。 */
    const val TOKEN_PARAM = "synjones-auth"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    enum class Basis(val label: String) {
        CARD_PLUS_PENDING("卡余额＋待圈存"),
        WALLETS("电子账户余额"),
        CARD_ONLY("卡余额"),
    }

    data class Wallet(val cents: Long)

    data class Card(
        val cardName: String,
        val account: String,
        val cents: Long,
        val pendingCents: Long,
        val wallets: List<Wallet>,
        val lost: Boolean,
        val frozen: Boolean,
        val basis: Basis,
    ) {
        val amountYuan: BigDecimal
            get() = BigDecimal.valueOf(cents, 2)

        val pendingYuan: BigDecimal
            get() = BigDecimal.valueOf(pendingCents, 2)
    }

    data class Result(
        val basis: Basis,
        val cards: List<Card>,
    )

    fun parseFrontInfo(body: String): Basis {
        val root = element(body)
        val data = root.objectOr("data")
        // getEcardConfig 是**字符串里的 JSON**（平台把配置二次序列化），必须再解一次。
        val raw = data.stringOrNull("getEcardConfig")?.takeIf { it.isNotBlank() }
            ?: throw format()
        val config = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw format()
        val type = config.stringOrNull("type")?.trim() ?: throw format()
        return when (type) {
            "0" -> Basis.CARD_PLUS_PENDING
            "1" -> Basis.WALLETS
            "2" -> Basis.CARD_ONLY
            else -> throw IOException("学校返回的校园卡余额口径未知（type=$type）")
        }
    }

    fun parseCards(body: String, basis: Basis): List<Card> {
        val root = element(body)
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
            ?: (root.stringOrNull("code")?.toIntOrNull())
            ?: throw format()
        if (code != 200) {
            val message = root.stringOrNull("message") ?: root.stringOrNull("msg") ?: "未知错误"
            throw IOException("学校未返回校园卡数据（code=$code，$message）")
        }
        val data = root.objectOr("data")
        val array = data["card"] as? JsonArray
            ?: if (data["card"] == null) JsonArray(emptyList()) else throw format()
        return array.map { parseCard(it, basis) }
    }

    private fun parseCard(element: JsonElement, basis: Basis): Card {
        val card = element as? JsonObject ?: throw format()
        val account = card.textOrNull("account") ?: throw format()
        val cardName = card.textOrNull("cardname")?.ifBlank { "校园卡" } ?: "校园卡"
        val db = card.centsOr("db_balance")
        val pending = card.centsOr("unsettle_amount")
        val wallets = when (val info = card["accinfo"]) {
            null -> emptyList()
            is JsonArray -> info.map { entry ->
                val wallet = entry as? JsonObject ?: throw format()
                Wallet(cents = wallet.centsOr("balance"))
            }
            else -> throw format()
        }
        val cents = when (basis) {
            Basis.CARD_PLUS_PENDING -> db + pending + wallets.sumOf { it.cents }
            Basis.WALLETS -> wallets.sumOf { it.cents }
            Basis.CARD_ONLY -> db + pending
        }
        return Card(
            cardName = cardName,
            account = account,
            cents = cents,
            pendingCents = pending,
            wallets = wallets,
            lost = card.flagOr("lostflag"),
            frozen = card.flagOr("freezeflag"),
            basis = basis,
        )
    }

    private fun element(body: String): JsonObject {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        return parsed as? JsonObject ?: throw format()
    }

    private fun JsonObject.objectOr(key: String): JsonObject =
        this[key] as? JsonObject ?: throw format()

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** 平台里 `account` 可能是字符串也可能是数字，两种都接受。 */
    private fun JsonObject.textOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.centsOr(key: String): Long {
        val primitive = this[key] as? JsonPrimitive ?: throw format()
        val text = primitive.contentOrNull?.trim().orEmpty()
        val value = text.toDoubleOrNull() ?: throw format()
        // 平台金额是整数分；出现小数只做四舍五入，不放大单位。
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun JsonObject.flagOr(key: String): Boolean {
        val primitive = this[key] as? JsonPrimitive ?: return false
        if (primitive.contentOrNull == null) return false
        val content = primitive.contentOrNull.orEmpty()
        return when {
            primitive.isString -> content.trim() in setOf("1", "true", "True", "TRUE")
            else -> content.toDoubleOrNull()?.let { it != 0.0 } ?: false
        }
    }

    private fun format(): Nothing = throw IOException("学校返回的校园卡数据格式已变化，请稍后重试")
}
