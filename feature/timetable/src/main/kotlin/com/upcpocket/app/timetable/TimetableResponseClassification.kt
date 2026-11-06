package com.upcpocket.app.timetable

import com.upcpocket.app.platform.diagnostics.DiagnosticBodyType
import com.upcpocket.app.platform.diagnostics.DiagnosticMediaType
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A bounded, non-exporting classification of one timetable body.
 *
 * [validJson] means the whole body parsed as JSON. It does not mean the JSON
 * envelope or business schema is valid; [TimetableProtocol] still applies the
 * complete fixed schema. HTML/JSONP/plain text are never accepted just because
 * they contain JSON-looking fragments.
 */
internal data class TimetableBodyShape(
    val bodyType: DiagnosticBodyType,
    val validJson: Boolean,
    /** Decoded body text with leading BOM/whitespace removed; never exported. */
    val text: String,
)

internal object TimetableResponseClassifier {

    private const val PREFIX_CHARS = 4096
    private val CAS_LOGIN_HINT = Regex(
        "\\bcas\\b[^<>]{0,40}\\blogin\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PASSWORD_INPUT = Regex(
        "<input\\b[^>]*type\\s*=\\s*[\"']password[\"']",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Classify all Content-Type header values. Multiple disagreeing values are
     * [DiagnosticMediaType.OTHER]; the body still has to be valid JSON and pass
     * the strict protocol before it can be accepted.
     */
    fun mediaType(values: List<String>?): DiagnosticMediaType {
        val normalized = values.orEmpty()
            .map { it.substringBefore(';').trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalized.isEmpty()) return DiagnosticMediaType.MISSING
        if (normalized.size != 1) return DiagnosticMediaType.OTHER
        val value = normalized.single()
        return when {
            value == "application/json" -> DiagnosticMediaType.JSON
            value.contains('/') && value.endsWith("+json") -> DiagnosticMediaType.JSON_SUFFIX
            value == "text/html" || value == "application/xhtml+xml" ->
                DiagnosticMediaType.HTML
            value.startsWith("text/") -> DiagnosticMediaType.TEXT
            else -> DiagnosticMediaType.OTHER
        }
    }

    fun body(bytes: ByteArray): TimetableBodyShape {
        val text = decode(bytes)
        val trimmed = text.trimStart('\uFEFF', '\u200B').trim()
        if (trimmed.isEmpty()) {
            return TimetableBodyShape(DiagnosticBodyType.EMPTY, validJson = false, text = "")
        }
        val first = trimmed.first()
        if (first == '<') {
            val html = looksLikeHtml(trimmed)
            return TimetableBodyShape(
                when {
                    html && looksLikeLoginPage(trimmed) -> DiagnosticBodyType.LOGIN_LIKE
                    html -> DiagnosticBodyType.HTML
                    else -> DiagnosticBodyType.OTHER
                },
                validJson = false,
                text = trimmed,
            )
        }
        // Only values that can start a JSON token are handed to the parser.
        // This prevents a lenient parser from treating HTML/JSONP-looking text
        // as a JSON string.
        val mayBeJson = first == '{' || first == '[' || first == '"' || first == '-' ||
            first.isDigit() || first == 't' || first == 'f' || first == 'n'
        if (!mayBeJson) {
            return TimetableBodyShape(DiagnosticBodyType.OTHER, validJson = false, text = trimmed)
        }
        val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
        if (parsed == null) {
            return TimetableBodyShape(
                if (first == '{' || first == '[') {
                    DiagnosticBodyType.MALFORMED
                } else {
                    DiagnosticBodyType.OTHER
                },
                validJson = false,
                text = trimmed,
            )
        }
        return when (parsed) {
            is JsonObject -> TimetableBodyShape(
                DiagnosticBodyType.JSON_OBJECT,
                validJson = true,
                text = trimmed,
            )
            is JsonArray -> TimetableBodyShape(
                DiagnosticBodyType.JSON_ARRAY,
                validJson = true,
                text = trimmed,
            )
            // A scalar is valid JSON but cannot satisfy an object envelope;
            // let the strict protocol report the exact shape rule.
            else -> TimetableBodyShape(
                DiagnosticBodyType.OTHER,
                validJson = true,
                text = trimmed,
            )
        }
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun looksLikeHtml(text: String): Boolean {
        if (!text.startsWith("<")) return false
        val prefix = text.take(PREFIX_CHARS).lowercase(Locale.ROOT)
        return prefix.contains("<html") ||
            prefix.contains("<!doctype html") ||
            prefix.contains("<head") ||
            prefix.contains("<body") ||
            prefix.contains("<form") ||
            prefix.contains("<div") ||
            prefix.contains("<table") ||
            prefix.contains("<script")
    }

    private fun looksLikeLoginPage(text: String): Boolean {
        val prefix = text.take(PREFIX_CHARS).lowercase(Locale.ROOT)
        if (prefix.contains("cas.upc.edu.cn") || prefix.contains("/cas/login")) return true
        if (prefix.contains("统一身份认证")) return true
        if (prefix.contains("id=\"loginform\"") || prefix.contains("id='loginform'")) return true
        if (prefix.contains("id=\"errordiv\"") || prefix.contains("id='errordiv'")) return true
        if (PASSWORD_INPUT.containsMatchIn(prefix)) return true
        return CAS_LOGIN_HINT.containsMatchIn(prefix)
    }
}
