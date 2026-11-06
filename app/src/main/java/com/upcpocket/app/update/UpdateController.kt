package com.upcpocket.app.update

import android.content.Intent
import java.io.File
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    UNSUPPORTED,
    FAILED,
    INSTALLED,
}

sealed interface DownloadUiState {
    val update: VerifiedUpdate

    data class Running(
        override val update: VerifiedUpdate,
        val sourceId: String?,
        val sourceIndex: Int,
        val sourceCount: Int,
        val downloadedBytes: Long,
    ) : DownloadUiState

    data class Ready(
        override val update: VerifiedUpdate,
        val file: File,
    ) : DownloadUiState

    data class Failed(
        override val update: VerifiedUpdate,
        val reason: UpdateDownloadFailure,
    ) : DownloadUiState
}

data class UpdateUiState(
    val currentVersionName: String,
    val currentVersionCode: Int,
    val autoCheckEnabled: Boolean,
    val checking: Boolean = false,
    val status: UpdateStatus = UpdateStatus.IDLE,
    val available: VerifiedUpdate? = null,
    val dialogOpen: Boolean = false,
    val download: DownloadUiState? = null,
)

/**
 * 关于页/前台自动检查共用的唯一更新状态机。
 *
 * - 自动检查只取元数据，发现新版只用 Toast 提示；不自动下载、不弹窗打断登录/预约。
 * - 手动检查才打开弹窗；同一 versionCode 在“稍后”后不再自动提示，手动仍可查看/更新。
 * - 检查和下载都做单任务合并，重复点击不会并发请求或重复写文件。
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val installedVersionName: String,
    private val installedVersionCode: Int,
    private val sources: List<UpdateSource>,
    private val stateStore: UpdateStateStore,
    private val storage: UpdateStorage,
    private val checker: suspend (List<UpdateSource>) -> UpdateCheckOutcome,
    private val downloader: suspend (
        UpdateManifest,
        List<UpdateSource>,
        (String, Int, Int) -> Unit,
        (Long) -> Unit,
    ) -> UpdateDownloadOutcome,
    private val apkVerifier: ApkVerifier?,
    private val installer: UpdateInstaller?,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val initialState = stateStore.read()
    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersionName = installedVersionName,
            currentVersionCode = installedVersionCode,
            autoCheckEnabled = initialState.autoCheckEnabled,
        ),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var checkJob: Job? = null
    private var manualPending = false
    private var downloadJob: Job? = null
    private var downloadToken = 0
    private var currentDownloadSources: List<UpdateSource> = emptyList()

    init {
        storage.cleanup()
    }

    fun onForeground() {
        if (!_state.value.autoCheckEnabled) return
        val today = LocalDate.now(clock).toEpochDay()
        val persisted = stateStore.read()
        if (persisted.lastAutoCheckEpochDay == today) return
        stateStore.update { it.copy(lastAutoCheckEpochDay = today) }
        checkInternal(manual = false)
    }

    fun checkNow() {
        checkInternal(manual = true)
    }

    private fun checkInternal(manual: Boolean) {
        if (checkJob?.isActive == true) {
            if (manual) manualPending = true
            return
        }
        manualPending = manual
        _state.update { it.copy(checking = true, status = UpdateStatus.CHECKING) }
        val ordered = UpdateSources.ordered(
            sources = sources,
            lastSuccessfulSourceId = stateStore.read().lastSuccessfulSourceId,
        )
        checkJob = scope.launch {
            val outcome = try {
                checker(ordered)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateCheckOutcome.Failure(CheckFailure.INTERNAL)
            }
            val shouldShowManual = manual || manualPending
            manualPending = false
            when (outcome) {
                is UpdateCheckOutcome.Available -> {
                    _state.update {
                        it.copy(
                            checking = false,
                            status = UpdateStatus.AVAILABLE,
                            available = outcome.update,
                            dialogOpen = shouldShowManual,
                        )
                    }
                    if (!shouldShowManual) {
                        val dismissed = stateStore.read().dismissedVersionCode
                        if (dismissed != outcome.update.manifest.versionCode) {
                            _notices.tryEmit(
                                "发现新版本 ${outcome.update.manifest.versionName}，可在“设置 → 关于”查看。",
                            )
                        }
                    }
                }
                UpdateCheckOutcome.UpToDate -> {
                    _state.update {
                        it.copy(checking = false, status = UpdateStatus.UP_TO_DATE)
                    }
                    if (shouldShowManual) _notices.tryEmit("当前已是最新版本。")
                }
                is UpdateCheckOutcome.UnsupportedDevice -> {
                    _state.update {
                        it.copy(checking = false, status = UpdateStatus.UNSUPPORTED)
                    }
                    if (shouldShowManual) _notices.tryEmit("此更新不支持当前 Android 版本。")
                }
                is UpdateCheckOutcome.Failure -> {
                    _state.update {
                        it.copy(checking = false, status = UpdateStatus.FAILED)
                    }
                    if (shouldShowManual) _notices.tryEmit(checkFailureText(outcome.reason))
                }
            }
        }
    }

    fun openDialog() {
        _state.update { state ->
            if (state.available != null) {
                state.copy(dialogOpen = true, download = state.download)
            } else {
                state
            }
        }
    }

    fun dismissDialog() {
        val update = _state.value.available
        if (update != null) {
            stateStore.update { it.copy(dismissedVersionCode = update.manifest.versionCode) }
        }
        if (downloadJob?.isActive == true) {
            cancelDownload()
        }
        _state.update { it.copy(dialogOpen = false) }
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        stateStore.update { it.copy(autoCheckEnabled = enabled) }
        _state.update { it.copy(autoCheckEnabled = enabled) }
    }

    fun startDownload() {
        val state = _state.value
        val update = state.available ?: return
        if (state.download is DownloadUiState.Running) return
        if (state.download is DownloadUiState.Ready) return
        val ordered = UpdateSources.ordered(sources, update.sourceId)
        startDownloadWith(update, ordered)
    }

    fun retryDownload() {
        val update = _state.value.available ?: return
        if (_state.value.download is DownloadUiState.Running) return
        startDownloadWith(update, UpdateSources.ordered(sources, update.sourceId))
    }

    private fun startDownloadWith(update: VerifiedUpdate, ordered: List<UpdateSource>) {
        if (ordered.isEmpty()) return
        val token = ++downloadToken
        currentDownloadSources = ordered
        _state.update {
            it.copy(
                dialogOpen = true,
                download = DownloadUiState.Running(
                    update = update,
                    sourceId = null,
                    sourceIndex = 0,
                    sourceCount = ordered.size,
                    downloadedBytes = 0,
                ),
            )
        }
        downloadJob = scope.launch {
            val outcome = try {
                downloader(
                    update.manifest,
                    ordered,
                    { sourceId, index, count ->
                        if (token == downloadToken) {
                            _state.update { state ->
                                val running = state.download as? DownloadUiState.Running
                                if (running == null) {
                                    state
                                } else {
                                    state.copy(
                                        download = running.copy(
                                            sourceId = sourceId,
                                            sourceIndex = index,
                                            sourceCount = count,
                                            downloadedBytes = 0,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    { bytes ->
                        if (token == downloadToken) {
                            _state.update { state ->
                                val running = state.download as? DownloadUiState.Running
                                if (running == null) {
                                    state
                                } else {
                                    state.copy(download = running.copy(downloadedBytes = bytes))
                                }
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateDownloadOutcome.Failure(UpdateDownloadFailure.INTERNAL)
            }
            if (token != downloadToken) return@launch
            when (outcome) {
                is UpdateDownloadOutcome.Success -> {
                    _state.update { state ->
                        state.copy(
                            download = DownloadUiState.Ready(update, outcome.file),
                            dialogOpen = true,
                        )
                    }
                }
                is UpdateDownloadOutcome.Failure -> {
                    _state.update { state ->
                        state.copy(
                            download = DownloadUiState.Failed(update, outcome.reason),
                            dialogOpen = true,
                        )
                    }
                }
                UpdateDownloadOutcome.Cancelled -> Unit
            }
        }
    }

    fun cancelDownload() {
        if (downloadJob?.isActive != true) {
            _state.update {
                if (it.download is DownloadUiState.Running) it.copy(download = null) else it
            }
            return
        }
        downloadToken += 1
        downloadJob?.cancel()
        _state.update { state ->
            state.copy(
                download = if (state.download is DownloadUiState.Running) null else state.download,
                dialogOpen = true,
            )
        }
        _notices.tryEmit("下载已取消。")
    }

    fun switchSource() {
        val state = _state.value
        val running = state.download as? DownloadUiState.Running ?: return
        val next = currentDownloadSources.drop(running.sourceIndex + 1)
        if (next.isEmpty()) {
            _notices.tryEmit("没有其他可用线路。")
            return
        }
        val oldJob = downloadJob
        val token = ++downloadToken
        _state.update {
            it.copy(
                download = running.copy(
                    sourceId = null,
                    downloadedBytes = 0,
                    sourceCount = next.size,
                    sourceIndex = 0,
                ),
            )
        }
        scope.launch {
            oldJob?.cancel()
            oldJob?.join()
            if (token != downloadToken) return@launch
            startDownloadWith(state.available ?: return@launch, next)
        }
    }

    /**
     * 在用户点击“安装”时才重新复核文件并检查未知来源授权；不做静默安装。
     */
    suspend fun prepareInstall(): InstallAction {
        val ready = _state.value.download as? DownloadUiState.Ready
            ?: return InstallAction.Unavailable(InstallUnavailable.NOT_READY)
        val verifier = apkVerifier
            ?: return InstallAction.Unavailable(InstallUnavailable.NOT_READY)
        val verification = withContext(Dispatchers.IO) {
            verifier.verify(ready.file, ready.update.manifest)
        }
        if (verification is ApkVerification.Invalid) {
            ready.file.delete()
            _state.update {
                it.copy(download = DownloadUiState.Failed(ready.update, UpdateDownloadFailure.APK_INVALID))
            }
            return InstallAction.Unavailable(InstallUnavailable.FILE_MISSING)
        }
        val install = installer
            ?: return InstallAction.Unavailable(InstallUnavailable.NO_INSTALLER)
        return install.install(ready.file)
    }

    fun permissionSettingsIntent(): Intent? = installer?.unknownSourcesIntent()

    fun canRequestInstallPermission(): Boolean = installer?.canRequestPackageInstalls() == true

    /**
     * 安装器/设置页返回后只查实际包版本；不把 Activity resultCode 当安装成功。
     */
    fun onInstallerReturn(): Boolean {
        val installedNow = installer?.installedVersionCode() ?: return false
        val target = (_state.value.download as? DownloadUiState.Ready)?.update?.manifest?.versionCode
            ?: _state.value.available?.manifest?.versionCode
            ?: return false
        if (installedNow < target) return false
        _state.update {
            it.copy(
                status = UpdateStatus.INSTALLED,
                available = null,
                download = null,
                dialogOpen = false,
                checking = false,
            )
        }
        storage.cleanup()
        _notices.tryEmit("更新已安装。")
        return true
    }

    fun notice(message: String) {
        _notices.tryEmit(message)
    }

    fun sourceLabel(sourceId: String?): String =
        sources.firstOrNull { it.id == sourceId }?.label ?: "更新线路"

    private fun checkFailureText(reason: CheckFailure): String = when (reason) {
        CheckFailure.TIMEOUT -> "检查更新超时，请稍后重试。"
        CheckFailure.STALE_METADATA -> "更新信息暂时过期，请稍后重试。"
        CheckFailure.SIGNATURE_INVALID, CheckFailure.INVALID_METADATA,
        CheckFailure.INTERNAL,
        -> "检查更新失败，请稍后重试。"
        CheckFailure.SOURCE_UNAVAILABLE -> "暂时无法获取更新信息，请稍后重试。"
    }
}
