package com.upcpocket.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class VerifiedUpdate(
    val manifest: UpdateManifest,
    val sourceId: String,
)

sealed interface UpdateCheckOutcome {
    data class Available(val update: VerifiedUpdate) : UpdateCheckOutcome
    data object UpToDate : UpdateCheckOutcome
    data class UnsupportedDevice(val update: VerifiedUpdate) : UpdateCheckOutcome
    data class Failure(val reason: CheckFailure) : UpdateCheckOutcome
}

enum class CheckFailure {
    SOURCE_UNAVAILABLE,
    SIGNATURE_INVALID,
    INVALID_METADATA,
    STALE_METADATA,
    TIMEOUT,
    INTERNAL,
}

enum class CheckStage {
    MANIFEST,
    SIGNATURE,
    VERIFY,
    PARSE,
    POLICY,
}

/**
 * 检查更新：同一候选源取 update-next.json + update-next.sig，先验签原始字节再解析。
 *
 * 最近成功源仅改变尝试顺序。某个源返回坏签名/不同步内容时切下一个源；已经验签但
 * 版本过旧的清单不能覆盖本机最高已见版本。公开镜像的 SHA 文本从不作为真实性依据。
 */
class UpdateRepository(
    private val transport: UpdateTransport,
    private val stateStore: UpdateStateStore,
    private val installedVersionCode: Int,
    private val installedVersionName: String,
    private val sdkInt: Int,
    private val totalTimeoutMs: Long = 25_000L,
    private val sourceTimeoutMs: Long = 8_000L,
    private val onStage: (sourceId: String, stage: CheckStage, result: String, httpStatus: Int?, elapsedMs: Long) -> Unit = { _, _, _, _, _ -> },
) {
    private val verifierCache = mutableMapOf<String, UpdateSignatureVerifier>()

    suspend fun check(sources: List<UpdateSource>): UpdateCheckOutcome {
        if (sources.isEmpty()) return UpdateCheckOutcome.Failure(CheckFailure.INTERNAL)
        val timed = withTimeoutOrNull(totalTimeoutMs) { checkSources(sources) }
        return timed ?: UpdateCheckOutcome.Failure(CheckFailure.TIMEOUT)
    }

    private suspend fun checkSources(sources: List<UpdateSource>): UpdateCheckOutcome {
        var sawValidMetadata = false
        var unsupportedUpdate: VerifiedUpdate? = null
        var sawStale = false
        var sawBadSignature = false
        var sawInvalidMetadata = false
        var lastTransportFailure: TransportFailure? = null
        var lastHttpStatus: Int? = null

        for (source in sources) {
            val started = System.nanoTime()
            val pair = try {
                withTimeoutOrNull(sourceTimeoutMs) {
                    val manifestBytes = transport.fetch(
                        source.metadataUrl(),
                        source,
                        UpdateManifestParser.MAX_MANIFEST_BYTES.toLong(),
                    )
                    onStage(
                        source.id,
                        CheckStage.MANIFEST,
                        "OK",
                        null,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                    val signature = transport.fetch(
                        source.signatureUrl(),
                        source,
                        MAX_SIGNATURE_BYTES,
                    )
                    onStage(
                        source.id,
                        CheckStage.SIGNATURE,
                        "OK",
                        null,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                    manifestBytes to signature
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateTransportException) {
                lastTransportFailure = e.failure
                lastHttpStatus = e.httpStatus
                onStage(
                    source.id,
                    CheckStage.MANIFEST,
                    e.failure.name,
                    e.httpStatus,
                    (System.nanoTime() - started) / 1_000_000,
                )
                continue
            } catch (_: Exception) {
                lastTransportFailure = TransportFailure.IO
                onStage(
                    source.id,
                    CheckStage.MANIFEST,
                    "IO",
                    null,
                    (System.nanoTime() - started) / 1_000_000,
                )
                continue
            }

            if (pair == null) {
                lastTransportFailure = TransportFailure.NETWORK
                onStage(
                    source.id,
                    CheckStage.MANIFEST,
                    "TIMEOUT",
                    null,
                    (System.nanoTime() - started) / 1_000_000,
                )
                continue
            }

            val verifier = verifierCache.getOrPut(source.publicKeyBase64) {
                UpdateSignatureVerifier(source.publicKeyBase64)
            }
            val verified = verifier.verify(pair.first, pair.second)
            onStage(
                source.id,
                CheckStage.VERIFY,
                if (verified) "OK" else "SIGNATURE_INVALID",
                null,
                (System.nanoTime() - started) / 1_000_000,
            )
            if (!verified) {
                sawBadSignature = true
                continue
            }

            val parse = UpdateManifestParser.parse(pair.first)
            if (parse is ManifestParseResult.Invalid) {
                sawInvalidMetadata = true
                onStage(
                    source.id,
                    CheckStage.PARSE,
                    parse.reason.name,
                    null,
                    (System.nanoTime() - started) / 1_000_000,
                )
                // 签名有效但内容不合法属于发布/升级配置错误；切源也不会产生不同可信内容。
                return UpdateCheckOutcome.Failure(CheckFailure.INVALID_METADATA)
            }
            val manifest = (parse as ManifestParseResult.Valid).manifest
            sawValidMetadata = true
            stateStore.update { state ->
                state.copy(
                    highestVerifiedVersionCode = maxOf(
                        state.highestVerifiedVersionCode,
                        manifest.versionCode,
                    ),
                    lastSuccessfulSourceId = source.id,
                )
            }

            val highest = stateStore.read().highestVerifiedVersionCode
            when (val decision = UpdateVersionPolicy.decide(
                installedVersionCode = installedVersionCode,
                sdkInt = sdkInt,
                highestVerifiedCode = highest,
                manifest = manifest,
            )) {
                is UpdateVersionDecision.Offer -> {
                    onStage(
                        source.id,
                        CheckStage.POLICY,
                        "UPDATE_AVAILABLE",
                        null,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                    return UpdateCheckOutcome.Available(
                        VerifiedUpdate(manifest = manifest, sourceId = source.id),
                    )
                }
                UpdateVersionDecision.Stale -> {
                    sawStale = true
                    onStage(
                        source.id,
                        CheckStage.POLICY,
                        "STALE",
                        null,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                }
                UpdateVersionDecision.UnsupportedDevice -> {
                    unsupportedUpdate = VerifiedUpdate(
                        manifest = manifest,
                        sourceId = source.id,
                    )
                    onStage(
                        source.id,
                        CheckStage.POLICY,
                        "UNSUPPORTED_SDK",
                        null,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                }
                UpdateVersionDecision.NotNewer -> {
                    onStage(
                        source.id,
                        CheckStage.POLICY,
                        "UP_TO_DATE",
                        null,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                }
            }
        }

        return when {
            unsupportedUpdate != null -> UpdateCheckOutcome.UnsupportedDevice(unsupportedUpdate!!)
            sawStale -> UpdateCheckOutcome.Failure(CheckFailure.STALE_METADATA)
            sawValidMetadata -> UpdateCheckOutcome.UpToDate
            sawInvalidMetadata -> UpdateCheckOutcome.Failure(CheckFailure.INVALID_METADATA)
            sawBadSignature -> UpdateCheckOutcome.Failure(CheckFailure.SIGNATURE_INVALID)
            lastTransportFailure != null || lastHttpStatus != null ->
                UpdateCheckOutcome.Failure(CheckFailure.SOURCE_UNAVAILABLE)
            else -> UpdateCheckOutcome.Failure(CheckFailure.SOURCE_UNAVAILABLE)
        }
    }

    private companion object {
        const val MAX_SIGNATURE_BYTES = 1024L
    }
}
