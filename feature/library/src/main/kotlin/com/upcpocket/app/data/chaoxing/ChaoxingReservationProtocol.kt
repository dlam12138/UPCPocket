package com.upcpocket.app.data.chaoxing

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

internal data class ChaoxingSelectSecrets(
    val submitEnc: String,
    val wfwEngineEnc: String,
    val riskCheckRequired: Boolean,
)

sealed interface ChaoxingReservationResult {
    data class Success(val seatNum: String, val day: String) : ChaoxingReservationResult
    data class Rejected(val message: String) : ChaoxingReservationResult
    data class InteractionRequired(val message: String) : ChaoxingReservationResult
    /** 请求可能已经到达学校，但客户端没有拿到可确认的成功/失败；禁止自动重试。 */
    data class Unknown(val message: String) : ChaoxingReservationResult
}

/**
 * 超星 seat / seatengine 原生提交协议。
 *
 * 官方选座页的 `submit_enc` 是页面级校验值，提交的 `enc` 由官方 `submitVerify` 定义为：
 * 按参数对象插入顺序拼接 `[key=value]`，再追加 `[submit_enc]`，取小写 MD5。
 * 这里只实现该文档化形状；不复制旧版第三方的 token/enc 算法。
 */
internal object ChaoxingReservationProtocol {

    private val SUBMIT_ENC = Regex(
        """id\s*=\s*["']submit_enc["'][^>]*value\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val VALUE_FIRST_SUBMIT_ENC = Regex(
        """value\s*=\s*["']([^"']+)["'][^>]*id\s*=\s*["']submit_enc["']""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val WFW_ENGINE_ENC = Regex(
        """wfwEngineEnc\s*=\s*['"]([^'"]*)['"]""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val RISK_CHECK = Regex(
        """riskCheckOpen\s*[:=]\s*true""",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun parseSelectPage(html: String): ChaoxingSelectSecrets {
        val submit = SUBMIT_ENC.find(html)?.groupValues?.get(1)
            ?: VALUE_FIRST_SUBMIT_ENC.find(html)?.groupValues?.get(1)
            ?: throw ChaoxingException("选座页缺少 submit_enc", "chaoxing_protocol")
        if (submit.isBlank() || submit.length > 512) {
            throw ChaoxingException("选座页 submit_enc 不可用", "chaoxing_protocol")
        }
        return ChaoxingSelectSecrets(
            submitEnc = submit,
            wfwEngineEnc = WFW_ENGINE_ENC.find(html)?.groupValues?.get(1).orEmpty(),
            riskCheckRequired = RISK_CHECK.containsMatchIn(html),
        )
    }

    fun enc(
        kind: ChaoxingSeatKind,
        deptIdEnc: String,
        roomId: Int,
        day: String,
        startTime: String,
        endTime: String,
        seatNum: String,
        submitEnc: String,
        wfwEngineEnc: String,
    ): String {
        val params = orderedParams(
            kind = kind,
            deptIdEnc = deptIdEnc,
            roomId = roomId,
            day = day,
            startTime = startTime,
            endTime = endTime,
            seatNum = seatNum,
            captcha = "",
            wyToken = "",
            wfwEngineEnc = wfwEngineEnc,
        )
        val canonical = buildString {
            params.forEach { (key, value) ->
                append('[').append(key).append('=').append(value).append(']')
            }
            append('[').append(submitEnc).append(']')
        }
        return MessageDigest.getInstance("MD5")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun submitParams(
        kind: ChaoxingSeatKind,
        deptIdEnc: String,
        roomId: Int,
        day: String,
        startTime: String,
        endTime: String,
        seatNum: String,
        submitEnc: String,
        wfwEngineEnc: String,
    ): LinkedHashMap<String, String> {
        val params = orderedParams(
            kind = kind,
            deptIdEnc = deptIdEnc,
            roomId = roomId,
            day = day,
            startTime = startTime,
            endTime = endTime,
            seatNum = seatNum,
            captcha = "",
            wyToken = "",
            wfwEngineEnc = wfwEngineEnc,
        )
        params["enc"] = enc(
            kind = kind,
            deptIdEnc = deptIdEnc,
            roomId = roomId,
            day = day,
            startTime = startTime,
            endTime = endTime,
            seatNum = seatNum,
            submitEnc = submitEnc,
            wfwEngineEnc = wfwEngineEnc,
        )
        return params
    }

    private fun orderedParams(
        kind: ChaoxingSeatKind,
        deptIdEnc: String,
        roomId: Int,
        day: String,
        startTime: String,
        endTime: String,
        seatNum: String,
        captcha: String,
        wyToken: String,
        wfwEngineEnc: String,
    ): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        if (kind == ChaoxingSeatKind.SEAT) put("deptIdEnc", deptIdEnc)
        put("roomId", roomId.toString())
        put("day", day)
        put("startTime", startTime)
        put("endTime", endTime)
        put("seatNum", seatNum)
        put("captcha", captcha)
        put("wyToken", wyToken)
        if (kind == ChaoxingSeatKind.SEATENGINE) put("wfwEngineEnc", wfwEngineEnc)
    }

    /** External envelopes are untrusted. Missing/wrong types never mean a known rejection. */
    fun parseSubmitResponse(body: String, seatNum: String, day: String): ChaoxingReservationResult {
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return unknownChaoxingSubmission()
        val flag = json["success"] as? JsonPrimitive ?: return unknownChaoxingSubmission()
        if (flag.isString) return unknownChaoxingSubmission()
        val success = flag.booleanOrNull ?: return unknownChaoxingSubmission()
        val message = if ("msg" in json) {
            val raw = json["msg"] as? JsonPrimitive ?: return unknownChaoxingSubmission()
            if (!raw.isString) return unknownChaoxingSubmission()
            raw.contentOrNull?.trim() ?: return unknownChaoxingSubmission()
        } else null
        val data = json["data"]
        if (data != null && data != JsonNull && data !is JsonObject) return unknownChaoxingSubmission()
        if (data is JsonObject && "seatReserve" in data) {
            val reserve = data["seatReserve"] as? JsonObject ?: return unknownChaoxingSubmission()
            for ((key, expected) in listOf("seatNum" to seatNum, "day" to day)) {
                if (key in reserve) {
                    val value = reserve[key] as? JsonPrimitive ?: return unknownChaoxingSubmission()
                    if (!value.isString || value.contentOrNull != expected) return unknownChaoxingSubmission()
                }
            }
        }
        if (success) return ChaoxingReservationResult.Success(seatNum, day)
        // The verified failure envelope is an explicit boolean false plus a textual business message.
        if (message.isNullOrBlank()) return unknownChaoxingSubmission()
        return if (message.contains("验证码") || message.contains("安全验证") ||
            message.contains("captcha", ignoreCase = true)) {
            ChaoxingReservationResult.InteractionRequired("学校要求安全验证；请在官方页完成本次预约。")
        } else ChaoxingReservationResult.Rejected(message)
    }
}

internal fun unknownChaoxingSubmission() = ChaoxingReservationResult.Unknown(
    "提交结果未知；请到学校记录核对，勿重复提交。",
)
