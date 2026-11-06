package com.upcpocket.app.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.upcpocket.app.BuildConfig
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticExceptionType
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticSink
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticDeviceInfo(
    val appVersion: String,
    val appVersionCode: Int,
    val buildType: String,
    val androidRelease: String,
    val sdkInt: Int,
    val brand: String,
    val model: String,
    val webViewPackage: String,
    val fontScale: Float,
    val windowWidthDp: Int,
    val windowHeightDp: Int,
)

sealed interface DiagnosticExportResult {
    data class Success(
        val uri: Uri,
        val eventCount: Int,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val bytes: Long,
    ) : DiagnosticExportResult

    data object NoRecords : DiagnosticExportResult
    data class Failure(val reason: DiagnosticExportFailure) : DiagnosticExportResult
}

enum class DiagnosticExportFailure {
    IO,
    TOO_LARGE,
    PROVIDER,
}

/**
 * Private, bounded diagnostic event store.
 *
 * - One daemon worker serializes all file work; callers only enqueue.
 * - At most [MAX_QUEUE] events may wait, so a burst cannot grow unbounded.
 * - The event file is capped near 1 MiB and filtered to the last 24 hours.
 * - Account invalidation increments an epoch before enqueueing a clear; any
 *   record queued under an older epoch is dropped by the worker.
 * - All failures are swallowed: diagnostics must never affect login or feature
 *   behavior.
 */
class DiagnosticStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rootDirectory: File = File(context.noBackupFilesDir, "diagnostics"),
    private val exportDirectory: File = File(context.cacheDir, "diagnostic_exports"),
    private val maxEventBytes: Long = MAX_EVENT_BYTES,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val maxQueue: Int = MAX_QUEUE,
    private val maxExports: Int = MAX_EXPORTS,
    private val maxExportBytes: Long = MAX_EXPORT_BYTES,
    /** Test seam; production always uses the app's scoped FileProvider. */
    private val uriFactory: ((File) -> Uri)? = null,
) : DiagnosticSink {

    private val appContext = context.applicationContext
    private val enabled = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val epoch = AtomicLong(1)
    private val pending = AtomicInteger(0)
    private val commands = LinkedBlockingQueue<Command>()
    private val worker = Thread({ processCommands() }, "upc-pocket-diagnostics").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    private val eventsFile = File(rootDirectory, EVENTS_FILE)
    private var lastCompactionAt = 0L

    init {
        runCatching { rootDirectory.mkdirs() }
        worker.start()
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun isEnabled(): Boolean = enabled.get()

    fun close() {
        closed.set(true)
        worker.interrupt()
    }

    override fun record(event: DiagnosticEvent) {
        if (!enabled.get() || closed.get()) return
        if (pending.incrementAndGet() > maxQueue) {
            pending.decrementAndGet()
            return
        }
        if (!commands.offer(Command.Record(epoch.get(), event))) {
            pending.decrementAndGet()
        }
    }

    /** Non-blocking; old queued events are dropped by the epoch check. */
    fun clearForAccountChange() {
        epoch.incrementAndGet()
        commands.offer(Command.Clear(epoch.get(), null))
    }

    suspend fun clearNow(): Boolean = withContext(Dispatchers.IO) {
        val future = CompletableFuture<Boolean>()
        epoch.incrementAndGet()
        commands.offer(Command.Clear(epoch.get(), future))
        runCatching { future.get(CLEAR_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    suspend fun export(device: DiagnosticDeviceInfo): DiagnosticExportResult =
        withContext(Dispatchers.IO) {
            val future = CompletableFuture<DiagnosticExportResult>()
            commands.offer(Command.Export(epoch.get(), device, future))
            runCatching {
                future.get(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrElse { DiagnosticExportResult.Failure(DiagnosticExportFailure.IO) }
        }

    fun deviceInfo(windowWidthDp: Int, windowHeightDp: Int): DiagnosticDeviceInfo {
        val configuration = appContext.resources.configuration
        val webViewPackage = runCatching {
            WebView.getCurrentWebViewPackage()?.let { info ->
                "${info.packageName} ${info.versionName.orEmpty()}".trim()
            }
        }.getOrNull().orEmpty().ifBlank { "unknown" }
        return DiagnosticDeviceInfo(
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            brand = Build.BRAND.orEmpty().ifBlank { "unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "unknown" },
            webViewPackage = webViewPackage,
            fontScale = configuration.fontScale,
            windowWidthDp = windowWidthDp.takeIf { it > 0 }
                ?: configuration.screenWidthDp,
            windowHeightDp = windowHeightDp.takeIf { it > 0 }
                ?: configuration.screenHeightDp,
        )
    }

    private fun processCommands() {
        while (!closed.get()) {
            val command = runCatching { commands.take() }.getOrNull() ?: continue
            runCatching {
                when (command) {
                    is Command.Record -> {
                        try {
                            if (command.epoch == epoch.get() && enabled.get()) {
                                appendLocked(command.event)
                            }
                        } finally {
                            pending.decrementAndGet()
                        }
                    }
                    is Command.Clear -> {
                        val result = runCatching { clearLocked() }.getOrDefault(false)
                        command.future?.complete(result)
                    }
                    is Command.Export -> {
                        val result = if (command.epoch != epoch.get()) {
                            DiagnosticExportResult.NoRecords
                        } else {
                            runCatching { exportLocked(command.device) }
                                .getOrElse {
                                    DiagnosticExportResult.Failure(DiagnosticExportFailure.IO)
                                }
                        }
                        command.future.complete(result)
                    }
                }
            }
        }
    }

    private fun appendLocked(event: DiagnosticEvent) {
        runCatching { rootDirectory.mkdirs() }
        val line = encode(event).toByteArray(Charsets.UTF_8)
        if (line.size + 1 > maxEventBytes) return
        val now = clock()
        if (now - lastCompactionAt >= COMPACT_INTERVAL_MS ||
            eventsFile.length() + line.size + 1 > maxEventBytes
        ) {
            compactLocked(now)
            lastCompactionAt = now
        }
        if (eventsFile.length() + line.size + 1 > maxEventBytes) {
            compactLocked(now, maxKeepBytes = maxEventBytes - line.size - 1)
            lastCompactionAt = now
        }
        if (eventsFile.length() + line.size + 1 > maxEventBytes) return
        runCatching {
            FileOutputStream(eventsFile, true).use { output ->
                output.write(line)
                output.write('\n'.code)
                output.flush()
            }
        }
    }

    /**
     * Keeps newest parseable events within [maxAgeMs] and [maxKeepBytes].
     * Malformed lines are dropped rather than exported or allowed to break I/O.
     */
    private fun compactLocked(now: Long, maxKeepBytes: Long = maxEventBytes) {
        val parsed = readParsedLocked()
        val cutoff = now - maxAgeMs
        val recent = parsed.filter { it.timestampEpochMillis >= cutoff }
        val keep = ArrayList<ParsedLine>(recent.size)
        var bytes = 0L
        for (line in recent.asReversed()) {
            val size = line.raw.toByteArray(Charsets.UTF_8).size + 1L
            if (bytes + size > maxKeepBytes) break
            keep += line
            bytes += size
        }
        keep.reverse()
        writeAtomicLocked(keep.map { it.raw })
    }

    private fun readParsedLocked(): List<ParsedLine> {
        if (!eventsFile.isFile) return emptyList()
        val lines = runCatching { eventsFile.readLines(Charsets.UTF_8) }
            .getOrElse {
                runCatching { eventsFile.delete() }
                return emptyList()
            }
        return lines.mapNotNull { raw ->
            runCatching {
                val value = JSONObject(raw)
                val timestamp = value.optLong("timestampEpochMillis", 0L)
                if (timestamp <= 0L) null else ParsedLine(timestamp, raw)
            }.getOrNull()
        }
    }

    private fun writeAtomicLocked(lines: List<String>) {
        val temporary = File(rootDirectory, "$EVENTS_FILE.tmp")
        runCatching {
            rootDirectory.mkdirs()
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                lines.forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
            }
            if (eventsFile.exists() && !eventsFile.delete()) {
                temporary.delete()
                return
            }
            if (!temporary.renameTo(eventsFile)) {
                temporary.copyTo(eventsFile, overwrite = true)
                temporary.delete()
            }
        }.onFailure {
            runCatching { temporary.delete() }
        }
    }

    private fun clearLocked(): Boolean {
        lastCompactionAt = 0L
        var success = true
        runCatching {
            if (eventsFile.exists() && !eventsFile.delete()) success = false
            File(rootDirectory, "$EVENTS_FILE.tmp").delete()
        }.onFailure { success = false }
        runCatching {
            exportDirectory.listFiles()?.forEach { file ->
                if (!file.delete()) success = false
            }
        }.onFailure { success = false }
        return success
    }

    private fun exportLocked(device: DiagnosticDeviceInfo): DiagnosticExportResult {
        val now = clock()
        compactLocked(now)
        val lines = readParsedLocked()
        if (lines.isEmpty()) return DiagnosticExportResult.NoRecords
        runCatching { exportDirectory.mkdirs() }
        pruneExportsLocked(now)
        val name = "upc-pocket-diagnostics-${now}-${randomSuffix()}.zip"
        val target = File(exportDirectory, name)
        val temporary = File(exportDirectory, "$name.tmp")
        val eventBytes = lines.sumOf { it.raw.toByteArray(Charsets.UTF_8).size + 1L }
        val summary = buildSummary(device, lines)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
                zip.putNextEntry(ZipEntry("summary.txt"))
                zip.write(summary.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("events.jsonl"))
                lines.forEach { line ->
                    zip.write(line.raw.toByteArray(Charsets.UTF_8))
                    zip.write('\n'.code)
                }
                zip.closeEntry()
            }
            if (temporary.length() > maxExportBytes || eventBytes > maxEventBytes) {
                temporary.delete()
                return DiagnosticExportResult.Failure(DiagnosticExportFailure.TOO_LARGE)
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return DiagnosticExportResult.Failure(DiagnosticExportFailure.IO)
            }
            val uri = runCatching {
                uriFactory?.invoke(target) ?: FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}$AUTHORITY_SUFFIX",
                    target,
                )
            }.getOrElse {
                target.delete()
                return DiagnosticExportResult.Failure(DiagnosticExportFailure.PROVIDER)
            }
            pruneExportsLocked(now)
            return DiagnosticExportResult.Success(
                uri = uri,
                eventCount = lines.size,
                startEpochMillis = lines.first().timestampEpochMillis,
                endEpochMillis = lines.last().timestampEpochMillis,
                bytes = target.length(),
            )
        } catch (_: Exception) {
            temporary.delete()
            target.delete()
            return DiagnosticExportResult.Failure(DiagnosticExportFailure.IO)
        }
    }

    private fun pruneExportsLocked(now: Long) {
        val files = runCatching {
            exportDirectory.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        }.getOrDefault(emptyList())
        var kept = 0
        files.forEach { file ->
            val expired = now - file.lastModified() > MAX_EXPORT_AGE_MS
            if (expired || kept >= maxExports) {
                runCatching { file.delete() }
            } else {
                kept++
            }
        }
    }

    private fun buildSummary(
        device: DiagnosticDeviceInfo,
        lines: List<ParsedLine>,
    ): String {
        val start = lines.first().timestampEpochMillis
        val end = lines.last().timestampEpochMillis
        val recentErrors = lines.asReversed()
            .mapNotNull { line -> parseSummaryError(line.raw) }
            .take(5)
        return buildString {
            appendLine("UPC Pocket diagnostic report")
            appendLine("schemaVersion=${DiagnosticEvent.SCHEMA_VERSION}")
            appendLine("appVersion=${device.appVersion}")
            appendLine("appVersionCode=${device.appVersionCode}")
            appendLine("buildType=${device.buildType}")
            appendLine("android=${device.androidRelease} (SDK ${device.sdkInt})")
            appendLine("brand=${device.brand}")
            appendLine("model=${device.model}")
            appendLine("webView=${device.webViewPackage}")
            appendLine("fontScale=${device.fontScale}")
            appendLine("windowDp=${device.windowWidthDp}x${device.windowHeightDp}")
            appendLine("records=${lines.size}")
            appendLine("range=${Instant.ofEpochMilli(start)} .. ${Instant.ofEpochMilli(end)}")
            appendLine("limits=last24h/${maxEventBytes}bytes; queue=$maxQueue; exports=$maxExports")
            appendLine()
            appendLine("recentErrors:")
            if (recentErrors.isEmpty()) {
                appendLine("- none")
            } else {
                recentErrors.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("No URLs, query/fragment, headers, bodies, HTML, credentials,")
            appendLine("account identifiers, course/room names or exception messages are stored.")
        }
    }

    private fun parseSummaryError(raw: String): String? = runCatching {
        val value = JSONObject(raw)
        val result = value.optString("result", "UNKNOWN")
        if (result == DiagnosticResult.OK.name || result == DiagnosticResult.PENDING.name) {
            return@runCatching null
        }
        val module = value.optString("module", "UNKNOWN")
        val action = value.optString("action", "UNKNOWN")
        val reason = value.optString("reason", "UNKNOWN")
        val exception = value.optString("exceptionType", "")
        val phase = value.optString("phase", "")
        val detail = value.optString("detail", "")
        buildString {
            append(module)
            append(' ')
            append(action)
            append(' ')
            append(result)
            append(' ')
            append(reason)
            if (phase.isNotBlank()) {
                append(" phase=")
                append(phase)
            }
            if (detail.isNotBlank()) {
                append(" detail=")
                append(detail)
            }
            if (exception.isNotBlank()) {
                append(" exception=")
                append(exception)
            }
        }
    }.getOrNull()

    private fun encode(event: DiagnosticEvent): String {
        val value = JSONObject()
        value.put("schemaVersion", DiagnosticEvent.SCHEMA_VERSION)
        value.put("timestampEpochMillis", event.timestampEpochMillis)
        value.put("module", event.module.name)
        value.put("action", event.action.name)
        value.put("result", event.result.name)
        event.reason?.let { value.put("reason", it.name) }
        event.destination?.let { value.put("destination", it.name) }
        event.httpStatus?.let { value.put("httpStatus", it) }
        event.durationMs?.let { value.put("durationMs", it) }
        event.widthDp?.let { value.put("widthDp", it) }
        event.heightDp?.let { value.put("heightDp", it) }
        event.exceptionType?.let { value.put("exceptionType", it.name) }
        event.operationId?.let { value.put("operationId", it) }
        event.phase?.let { value.put("phase", it.name) }
        event.detail?.let { value.put("detail", it.name) }
        event.responseBytes?.let { value.put("responseBytes", it) }
        event.itemCount?.let { value.put("itemCount", it) }
        if (event.frames.isNotEmpty()) {
            value.put("frames", JSONArray(event.frames))
        }
        return value.toString()
    }

    private fun randomSuffix(): String =
        java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000)
            .toString(16).padStart(4, '0')

    private data class ParsedLine(val timestampEpochMillis: Long, val raw: String)

    private sealed interface Command {
        val epoch: Long

        data class Record(override val epoch: Long, val event: DiagnosticEvent) : Command
        data class Clear(
            override val epoch: Long,
            val future: CompletableFuture<Boolean>?,
        ) : Command

        data class Export(
            override val epoch: Long,
            val device: DiagnosticDeviceInfo,
            val future: CompletableFuture<DiagnosticExportResult>,
        ) : Command
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".diagnostics"
        const val EVENTS_FILE = "events.jsonl"
        const val MAX_EVENT_BYTES = 1_048_576L
        const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val MAX_QUEUE = 256
        const val MAX_EXPORTS = 3
        const val MAX_EXPORT_BYTES = 4L * 1024L * 1024L
        const val MAX_EXPORT_AGE_MS = 24L * 60L * 60L * 1000L
        private const val CLEAR_TIMEOUT_SECONDS = 10L
        private const val EXPORT_TIMEOUT_SECONDS = 20L
        private const val COMPACT_INTERVAL_MS = 60_000L
    }
}
