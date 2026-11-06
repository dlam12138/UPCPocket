package com.upcpocket.app.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

sealed interface UpdateDownloadOutcome {
    data class Success(val file: File, val sourceId: String) : UpdateDownloadOutcome
    data class Failure(val reason: UpdateDownloadFailure) : UpdateDownloadOutcome
    data object Cancelled : UpdateDownloadOutcome
}

enum class UpdateDownloadFailure {
    STORAGE,
    TIMEOUT,
    SOURCE_UNAVAILABLE,
    APK_INVALID,
    INTERNAL,
}

/**
 * 单任务下载：按候选线路逐个从头下载，不并行、不跨源拼接断点。
 *
 * 只有完整 size + SHA-256 + 包名/versionCode/minSdk/签名兼容都通过后才把 .part
 * 重命名为最终文件。任一线路失败自动换下一条；哈希错误视为线路内容损坏，可以换源。
 * 包名或签名不匹配是发布包错误，立即停止并删除，不诱导用户卸载绕过。
 */
class UpdateDownloader(
    private val transport: UpdateTransport,
    private val storage: UpdateStorage,
    private val apkVerifier: ApkVerifier,
    private val perSourceTimeoutMs: Long = 90_000L,
    private val totalTimeoutMs: Long = 240_000L,
    private val onStage: (sourceId: String, stage: String, result: String, elapsedMs: Long) -> Unit =
        { _, _, _, _ -> },
) {
    suspend fun download(
        manifest: UpdateManifest,
        sources: List<UpdateSource>,
        onSource: (sourceId: String, index: Int, total: Int) -> Unit,
        onProgress: (downloadedBytes: Long) -> Unit,
    ): UpdateDownloadOutcome {
        if (sources.isEmpty()) return UpdateDownloadOutcome.Failure(UpdateDownloadFailure.INTERNAL)
        if (!storage.hasSpace(manifest.sizeBytes)) {
            return UpdateDownloadOutcome.Failure(UpdateDownloadFailure.STORAGE)
        }

        // 进程重建后仍可能保留同一份已完整校验的包；先复核，避免重复下载。
        val reusable = storage.finalFile(manifest.versionCode)
        if (reusable.isFile && apkVerifier.verify(reusable, manifest) == ApkVerification.Valid) {
            return UpdateDownloadOutcome.Success(reusable, sources.first().id)
        }
        reusable.delete()

        val timed = withTimeoutOrNull(totalTimeoutMs) {
            for ((index, source) in sources.withIndex()) {
                onSource(source.id, index, sources.size)
                onProgress(0L)
                val part = storage.preparePart(manifest.versionCode)
                val started = System.nanoTime()
                var published = false
                try {
                    val completed = try {
                        withTimeoutOrNull(perSourceTimeoutMs) {
                            transport.download(
                                url = source.apkUrl(manifest),
                                source = source,
                                expectedBytes = manifest.sizeBytes,
                                destination = part,
                                onProgress = onProgress,
                            )
                            true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UpdateTransportException) {
                        onStage(
                            source.id,
                            "DOWNLOAD",
                            e.failure.name,
                            (System.nanoTime() - started) / 1_000_000,
                        )
                        false
                    } catch (_: Exception) {
                        onStage(
                            source.id,
                            "DOWNLOAD",
                            "IO",
                            (System.nanoTime() - started) / 1_000_000,
                        )
                        false
                    }
                    if (completed != true) {
                        part.delete()
                        continue
                    }
                    if (part.length() != manifest.sizeBytes || sha256(part) != manifest.sha256) {
                        part.delete()
                        onStage(
                            source.id,
                            "CHECKSUM",
                            "MISMATCH",
                            (System.nanoTime() - started) / 1_000_000,
                        )
                        continue
                    }
                    when (val verification = apkVerifier.verify(part, manifest)) {
                        ApkVerification.Valid -> Unit
                        is ApkVerification.Invalid -> {
                            part.delete()
                            onStage(
                                source.id,
                                "APK",
                                verification.reason.name,
                                (System.nanoTime() - started) / 1_000_000,
                            )
                            return@withTimeoutOrNull UpdateDownloadOutcome.Failure(
                                UpdateDownloadFailure.APK_INVALID,
                            )
                        }
                    }
                    val finalFile = storage.publish(part, manifest.versionCode)
                    published = true
                    storage.cleanup()
                    onStage(
                        source.id,
                        "DOWNLOAD",
                        "OK",
                        (System.nanoTime() - started) / 1_000_000,
                    )
                    return@withTimeoutOrNull UpdateDownloadOutcome.Success(finalFile, source.id)
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    if (!published) part.delete()
                }
            }
            UpdateDownloadOutcome.Failure(UpdateDownloadFailure.SOURCE_UNAVAILABLE)
        }
        return timed ?: UpdateDownloadOutcome.Failure(UpdateDownloadFailure.TIMEOUT)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
