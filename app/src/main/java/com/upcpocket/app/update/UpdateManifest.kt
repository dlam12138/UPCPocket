package com.upcpocket.app.update

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** 更新清单只接受本项目的固定形状；任何未知字段都拒绝。 */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val tag: String,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String,
    val publishedAt: String,
    val notes: List<String>,
)

sealed interface ManifestParseResult {
    data class Valid(val manifest: UpdateManifest) : ManifestParseResult
    data class Invalid(val reason: ManifestError) : ManifestParseResult
}

enum class ManifestError {
    TOO_LARGE,
    MALFORMED_UTF8,
    MALFORMED_JSON,
    FIELD_SET,
    SCHEMA_VERSION,
    PACKAGE_NAME,
    VERSION,
    TAG,
    ASSET_NAME,
    SIZE,
    SHA256,
    PUBLISHED_AT,
    NOTES,
    MIN_SDK,
}

/**
 * 应用内更新清单解析器。
 *
 * 先验签原始 UTF-8 字节，再调用这里；因此解析规则不能因为 JSON 重新序列化而
 * 改变签名含义。这里不读取 URL、不下载、不执行 notes，只做有界字段校验。
 */
object UpdateManifestParser {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 50L * 1024L * 1024L
    const val MIN_APK_BYTES = 256L * 1024L
    const val SCHEMA_VERSION = 1
    const val PACKAGE_NAME = "com.upcpocket.next"
    private const val MAX_NOTES = 5
    private const val MIN_NOTES = 3
    private const val MAX_NOTE_CHARS = 180
    private const val MAX_NOTES_CHARS = 900
    private val ROOT_KEYS = setOf(
        "schemaVersion",
        "packageName",
        "versionName",
        "versionCode",
        "minSdk",
        "tag",
        "assetName",
        "sizeBytes",
        "sha256",
        "publishedAt",
        "notes",
    )
    private val VERSION_NAME = Regex("^[0-9]+(\\.[0-9]+){1,3}(-[0-9A-Za-z.-]{1,32})?$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val PUBLISHED_AT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")

    fun parse(raw: ByteArray): ManifestParseResult {
        if (raw.size > MAX_MANIFEST_BYTES) return ManifestParseResult.Invalid(ManifestError.TOO_LARGE)
        val text = decodeUtf8(raw) ?: return ManifestParseResult.Invalid(ManifestError.MALFORMED_UTF8)
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return ManifestParseResult.Invalid(ManifestError.MALFORMED_JSON)
        }

        val keys = json.keys().asSequence().toSet()
        if (keys != ROOT_KEYS) return ManifestParseResult.Invalid(ManifestError.FIELD_SET)

        if (strictInt(json, "schemaVersion") != SCHEMA_VERSION) {
            return ManifestParseResult.Invalid(ManifestError.SCHEMA_VERSION)
        }
        if (strictString(json, "packageName") != PACKAGE_NAME) {
            return ManifestParseResult.Invalid(ManifestError.PACKAGE_NAME)
        }

        val versionName = strictString(json, "versionName") ?: return invalid(ManifestError.VERSION)
        if (!VERSION_NAME.matches(versionName)) return invalid(ManifestError.VERSION)
        val versionCode = strictInt(json, "versionCode") ?: return invalid(ManifestError.VERSION)
        if (versionCode <= 0) return invalid(ManifestError.VERSION)

        val tag = strictString(json, "tag") ?: return invalid(ManifestError.TAG)
        if (tag != "v$versionName") return invalid(ManifestError.TAG)

        val assetName = strictString(json, "assetName") ?: return invalid(ManifestError.ASSET_NAME)
        if (assetName != "UPC-Pocket-$versionName.apk") return invalid(ManifestError.ASSET_NAME)

        val minSdk = strictInt(json, "minSdk") ?: return invalid(ManifestError.MIN_SDK)
        if (minSdk !in 21..100) return invalid(ManifestError.MIN_SDK)

        val sizeBytes = strictLong(json, "sizeBytes") ?: return invalid(ManifestError.SIZE)
        if (sizeBytes !in MIN_APK_BYTES..MAX_APK_BYTES) return invalid(ManifestError.SIZE)

        val sha256 = strictString(json, "sha256") ?: return invalid(ManifestError.SHA256)
        if (!SHA256.matches(sha256)) return invalid(ManifestError.SHA256)

        val publishedAt = strictString(json, "publishedAt") ?: return invalid(ManifestError.PUBLISHED_AT)
        if (!PUBLISHED_AT.matches(publishedAt)) return invalid(ManifestError.PUBLISHED_AT)
        val instant = runCatching { Instant.parse(publishedAt) }.getOrNull()
            ?: return invalid(ManifestError.PUBLISHED_AT)
        if (instant.atZone(ZoneOffset.UTC).year !in 2024..2100) {
            return invalid(ManifestError.PUBLISHED_AT)
        }

        val notesValue = json.opt("notes")
        if (notesValue !is JSONArray || notesValue.length() !in MIN_NOTES..MAX_NOTES) {
            return invalid(ManifestError.NOTES)
        }
        val notes = ArrayList<String>(notesValue.length())
        var totalChars = 0
        for (i in 0 until notesValue.length()) {
            val value = notesValue.opt(i)
            if (value !is String) return invalid(ManifestError.NOTES)
            totalChars += value.length
            if (value.isBlank() || value.length > MAX_NOTE_CHARS || value != value.trim()) {
                return invalid(ManifestError.NOTES)
            }
            if (value.any { it.code < 0x20 || it.code == 0x7F }) return invalid(ManifestError.NOTES)
            notes += value
        }
        if (totalChars > MAX_NOTES_CHARS) return invalid(ManifestError.NOTES)

        return ManifestParseResult.Valid(
            UpdateManifest(
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                tag = tag,
                assetName = assetName,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                publishedAt = publishedAt,
                notes = List(notes.size) { notes[it] },
            ),
        )
    }

    private fun invalid(reason: ManifestError): ManifestParseResult.Invalid =
        ManifestParseResult.Invalid(reason)

    private fun strictString(json: JSONObject, name: String): String? =
        json.opt(name) as? String

    /** 只接受 JSON 整数；22.0、字符串 "22" 和布尔都不算 versionCode。 */
    private fun strictInt(json: JSONObject, name: String): Int? {
        return when (val value = json.opt(name)) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
    }

    private fun strictLong(json: JSONObject, name: String): Long? {
        return when (val value = json.opt(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }

    private fun decodeUtf8(raw: ByteArray): String? {
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        }.getOrNull()
    }
}
