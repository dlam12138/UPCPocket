package com.upcpocket.app.grades

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class GradesProtocolException(
    val failure: GradesRemoteFailure,
) : Exception(failure.name, null, false, false)

/**
 * 成绩只读协议解析。
 *
 * 索引只接受 `d.terms` 中实际存在且结构完整的学期；任何坏条目、重复条目或
 * `d.params` 指向列表外学期都会让整个索引失败，不能把残缺数组当成“全部可查”。
 * 成绩列表必须存在 `d.list`，空数组是完整空结果；任一行结构不完整时整个学期
 * 失败，绝不把坏行静默丢弃后当成“少了几门课”的成功。
 */
internal object GradesProtocol {

    const val MAX_COURSES_PER_TERM = 5_000
    private const val MAX_TERMS = 200

    fun parseIndex(raw: String): GradesIndex {
        val root = parseObject(raw)
        checkEnvelope(root)
        val data = root["d"] as? JsonObject ?: throw schema()
        val rawTerms = data["terms"] as? JsonArray ?: throw schema()
        if (rawTerms.isEmpty() || rawTerms.size > MAX_TERMS) throw schema()

        val terms = rawTerms.map { element ->
            val item = element as? JsonObject ?: throw schema()
            val year = requiredText(item, "year")
            val termText = requiredText(item, "term")
            try {
                GradesTerm(year = year, term = termText)
            } catch (_: IllegalArgumentException) {
                throw schema()
            }
        }
        val keys = terms.map { it.key }
        if (keys.toSet().size != keys.size) throw schema()

        val params = when (val element = data["params"]) {
            null, is JsonNull -> null
            is JsonObject -> element
            else -> throw schema()
        }
        val paramsYear = params?.let { optionalText(it, "year") }
        val paramsTerm = params?.let { optionalText(it, "xq") }
        val current = if (paramsYear != null && paramsTerm != null) {
            terms.firstOrNull { it.year == paramsYear && it.term == paramsTerm }
                ?: throw schema()
        } else {
            null
        }
        return GradesIndex(terms = terms, currentTerm = current)
    }

    fun parseTerm(raw: String, term: GradesTerm): List<GradesCourse> {
        val root = parseObject(raw)
        checkEnvelope(root)
        val data = root["d"] as? JsonObject ?: throw schema()
        val rows = data["list"] as? JsonArray ?: throw schema()
        if (rows.size > MAX_COURSES_PER_TERM) throw schema()

        return rows.map { element ->
            val row = element as? JsonObject ?: throw schema()
            val courseName = requiredText(row, "kcmc").trim()
            if (courseName.isEmpty()) throw schema()
            GradesCourse(
                term = term,
                courseName = courseName,
                // 学校显式给 null/空串表示未出分或未知；键缺失/类型错误仍是坏行。
                score = nullableText(row, "kccj")?.trim()?.takeIf { it.isNotEmpty() },
                credits = optionalText(row, "xf")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 },
                courseType = nullableText(row, "kclx")?.trim()?.takeIf { it.isNotEmpty() },
                examType = optionalText(row, "exame_type")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun checkEnvelope(root: JsonObject) {
        val code = intField(root["e"]) ?: throw schema()
        when (code) {
            0 -> Unit
            10013 -> throw GradesProtocolException(GradesRemoteFailure.AUTH_REQUIRED)
            else -> throw GradesProtocolException(GradesRemoteFailure.UPSTREAM)
        }
    }

    private fun parseObject(raw: String): JsonObject = try {
        Json.parseToJsonElement(raw) as? JsonObject ?: throw schema()
    } catch (failure: GradesProtocolException) {
        throw failure
    } catch (_: Exception) {
        throw schema()
    }

    private fun requiredText(row: JsonObject, name: String): String {
        if (name !in row) throw schema()
        return textField(row[name]) ?: throw schema()
    }

    /** 键必须存在；显式 JSON null 或空文本可表示未出分/未知。 */
    private fun nullableText(row: JsonObject, name: String): String? {
        if (name !in row) throw schema()
        val element = row[name]
        if (element is JsonNull) return null
        return textField(element) ?: throw schema()
    }

    private fun optionalText(row: JsonObject, name: String): String? {
        if (name !in row) return null
        val element = row[name]
        if (element is JsonNull) return null
        return textField(element) ?: throw schema()
    }

    private fun textField(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) {
            // 学校对 year/term/xf 等字段可能给 JSON 数字；boolean/对象/数组不接受。
            val raw = primitive.content
            if (raw == "true" || raw == "false" || raw == "null") return null
            return raw
        }
        return primitive.content
    }

    private fun intField(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val raw = primitive.content
        if (!primitive.isString && (raw == "true" || raw == "false")) return null
        if (!raw.matches(Regex("[+-]?\\d{1,10}"))) return null
        return raw.toIntOrNull()
    }

    private fun schema() = GradesProtocolException(GradesRemoteFailure.SCHEMA)
}
