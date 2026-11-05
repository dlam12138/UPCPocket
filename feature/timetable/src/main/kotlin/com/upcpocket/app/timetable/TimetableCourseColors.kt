package com.upcpocket.app.timetable

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 课程颜色注册表。
 *
 * 颜色不再由哈希直接取模决定：每个“账号 + 学期 + 课程身份”在首次出现时分配一个
 * 未被本术语占用的调色板槽位，并写入独立的颜色注册文件。这样：
 * - 12 门示例课程可以拿到 12 个不同槽位；
 * - 同一课程跨日/跨周、冷启动保持同一槽位；
 * - 本周课程集合顺序变化或新增课程时，已有课程槽位不变。
 *
 * 调色板是 20 个在 CIE Lab 空间有足够分离度的手工色（见测试），不是等距 HSL；
 * 超过 20 门时按身份哈希复用槽位，不承诺无限唯一。课程名/地点文字始终保留。
 */
internal data class ToneColors(val container: Color, val content: Color)

internal const val COURSE_COLOR_CAPACITY = 20
private const val COLOR_FORMAT = "upcpocket.timetable.colors"
private const val COLOR_VERSION = 1
private const val MAX_COLOR_ENTRIES = 512
private const val MAX_COLOR_BYTES = 64 * 1024

private val COURSE_CONTAINERS = listOf(
    "#8dd3c7", "#ffffb3", "#bebada", "#fb8072", "#80b1d3",
    "#fdb462", "#b3de69", "#fccde5", "#e6ab02", "#bc80bd",
    "#ccebc5", "#ffed6f", "#c40063", "#d01010", "#9d7660",
    "#ff7f00", "#6a3d9a", "#386cb0", "#33a02c", "#1b9e77",
).map { Color(0xFF000000 or it.removePrefix("#").toLong(16)) }

private val COURSE_BLACK = Color(0xFF111318)
private val COURSE_WHITE = Color(0xFFF7F7FA)

internal fun courseColorKey(meeting: TimetableMeeting): String =
    meeting.courseId?.trim()?.takeIf { it.isNotEmpty() }
        ?: meeting.courseName.trim()

/** 用于持久化和稳定排序的身份摘要；不把课程名写进颜色文件。 */
internal fun courseColorDigest(identity: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** 仅作为无注册表时的视觉兜底；生产状态使用注册表槽位。 */
internal fun coursePaletteIndex(identity: String, size: Int = COURSE_COLOR_CAPACITY): Int {
    require(size > 0) { "palette size must be positive" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
    var hash = 0
    for (index in 0 until 4) {
        hash = (hash shl 8) or (digest[index].toInt() and 0xff)
    }
    return Math.floorMod(hash, size)
}

internal fun courseToneColors(index: Int, @Suppress("UNUSED_PARAMETER") dark: Boolean): ToneColors {
    val container = COURSE_CONTAINERS[Math.floorMod(index, COURSE_CONTAINERS.size)]
    // 同一个课程在浅/深主题使用同一容器色，避免切主题后“换了一门课”的错觉；
    // 前景色按实际对比度选择，浅色块用深字、深色块用浅字。
    val content = if (contrastRatio(container, COURSE_BLACK) >= contrastRatio(container, COURSE_WHITE)) {
        COURSE_BLACK
    } else {
        COURSE_WHITE
    }
    return ToneColors(container, content)
}

private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

@Composable
internal fun courseTone(meeting: TimetableMeeting, slot: Int?): ToneColors {
    val index = slot ?: coursePaletteIndex(courseColorKey(meeting))
    return courseToneColors(index, MaterialTheme.colorScheme.surface.luminance() < 0.5f)
}

@Serializable
private data class ColorFile(
    val format: String = COLOR_FORMAT,
    val version: Int = COLOR_VERSION,
    val accountId: String,
    val entries: List<ColorEntry> = emptyList(),
)

@Serializable
private data class ColorEntry(
    val term: String,
    val digest: String,
    val slot: Int,
)

/**
 * 账号 + 学期归属的课程配色注册文件。
 *
 * 文件只保存课程身份摘要与槽位，不保存课程名、教师、地点或课表正文；账号不一致时
 * 不读取旧账号映射。账号/个人数据清理由宿主调用 [clear]；冷启动从同一目录恢复。
 */
class TimetableColorStore internal constructor(private val directory: File?) {
    constructor(context: Context) : this(TimetableStorage.directory(context.applicationContext))

    companion object {
        fun inMemory(): TimetableColorStore = TimetableColorStore(null)
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val lock = Any()
    private val memory = linkedMapOf<String, ColorEntry>()

    fun assign(
        accountId: String,
        termKey: String,
        identities: List<String>,
    ): Map<String, Int> = synchronized(lock) {
        if (!accountId.matches(Regex("[A-Za-z0-9_-]{8,128}")) || termKey.isBlank()) {
            return emptyMap()
        }
        val entries = load(accountId).toMutableMap()
        val used = entries.values
            .filter { it.term == termKey }
            .mapTo(mutableSetOf()) { it.slot }
        val result = linkedMapOf<String, Int>()
        var changed = false
        identities.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy(::courseColorDigest)
            .forEach { identity ->
                val digest = courseColorDigest(identity)
                val key = entryKey(termKey, digest)
                val existing = entries[key]
                if (existing != null) {
                    result[identity] = existing.slot
                } else {
                    val free = (0 until COURSE_COLOR_CAPACITY).firstOrNull { it !in used }
                    // 超过调色板容量时如实复用：不再假装所有课程都唯一。
                    val slot = free ?: coursePaletteIndex(identity, COURSE_COLOR_CAPACITY)
                    entries[key] = ColorEntry(termKey, digest, slot)
                    used += slot
                    result[identity] = slot
                    changed = true
                }
            }
        if (changed) save(accountId, termKey, entries)
        result
    }

    fun clear(): Boolean = synchronized(lock) {
        memory.clear()
        if (directory == null) {
            true
        } else {
            TimetableStorage.deleteAtomic(directory, TimetableStorage.COLOR_FILE)
        }
    }

    private fun load(accountId: String): Map<String, ColorEntry> {
        if (directory == null) {
            val prefix = "$accountId\u0000"
            return memory
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { it.key.removePrefix(prefix) }
        }
        val file = File(directory, TimetableStorage.COLOR_FILE)
        val bytes = TimetableStorage.readAtomic(file, MAX_COLOR_BYTES) ?: return emptyMap()
        return try {
            val parsed = json.decodeFromString(ColorFile.serializer(), bytes.toString(Charsets.UTF_8))
            if (parsed.format != COLOR_FORMAT ||
                parsed.version != COLOR_VERSION ||
                parsed.accountId != accountId
            ) {
                emptyMap()
            } else {
                parsed.entries
                    .filter {
                        it.term.isNotBlank() &&
                            it.digest.matches(Regex("[0-9a-f]{64}")) &&
                            it.slot in 0 until COURSE_COLOR_CAPACITY
                    }
                    .associateBy { entryKey(it.term, it.digest) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun save(accountId: String, termKey: String, entries: Map<String, ColorEntry>) {
        val ordered = entries.values
            .sortedWith(compareBy({ it.term }, { it.digest }))
            .let { all ->
                if (all.size <= MAX_COLOR_ENTRIES) {
                    all
                } else {
                    val current = all.filter { it.term == termKey }
                    val other = all.filter { it.term != termKey }
                        .take((MAX_COLOR_ENTRIES - current.size).coerceAtLeast(0))
                    current + other
                }
            }
        if (directory == null) {
            val prefix = "$accountId\u0000"
            memory.keys.removeAll { it.startsWith(prefix) }
            ordered.forEach { memory["$prefix${entryKey(it.term, it.digest)}"] = it }
            return
        }
        try {
            val bytes = json.encodeToString(
                ColorFile.serializer(),
                ColorFile(accountId = accountId, entries = ordered),
            ).toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_COLOR_BYTES) {
                TimetableStorage.writeAtomic(File(directory, TimetableStorage.COLOR_FILE), bytes)
            }
        } catch (_: Exception) {
            // 颜色注册失败不影响课表数据；本轮回退到哈希兜底，不覆盖课程缓存。
        }
    }

    private fun entryKey(term: String, digest: String): String = "$term\u0000$digest"
}
