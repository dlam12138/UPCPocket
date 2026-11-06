package com.upcpocket.app.ui.about

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.update.DownloadUiState
import com.upcpocket.app.update.InstallAction
import com.upcpocket.app.update.InstallUnavailable
import com.upcpocket.app.update.UpdateDownloadFailure
import com.upcpocket.app.update.UpdateStatus
import com.upcpocket.app.update.UpdateUiState
import java.util.Locale
import kotlinx.coroutines.launch

private const val RELEASES_URL = "https://github.com/dlam12138/UPCPocket/releases"

/**
 * 关于页唯一的更新入口。没有首页卡片，也不新增顶级导航。
 *
 * 自动检查只显示本机状态/轻提示；只有用户点“检查”或已有可更新版本点“查看”才打开
 * 详情弹窗。下载和安装都在弹窗内完成，系统安装器负责最终确认。
 */
@Composable
fun UpdateSection() {
    val controller = AppGraph.updates
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var installBusy by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // 返回后按实际系统授权复核；用户拒绝时只提示，不再次跳设置，避免死循环。
        if (!controller.canRequestInstallPermission()) {
            toast("未获得安装授权，更新包已保留。")
        } else {
            scope.launch {
                installBusy = true
                try {
                    when (val action = controller.prepareInstall()) {
                        InstallAction.Started -> toast("已交给系统安装，请确认。")
                        is InstallAction.NeedPermission -> toast("未获得安装授权，更新包已保留。")
                        is InstallAction.Unavailable -> toast(installUnavailableText(action.reason))
                    }
                } finally {
                    installBusy = false
                }
            }
        }
    }

    fun launchInstallFlow() {
        if (installBusy) return
        installBusy = true
        scope.launch {
            try {
                when (val action = controller.prepareInstall()) {
                    is InstallAction.NeedPermission -> permissionLauncher.launch(action.settingsIntent)
                    InstallAction.Started -> toast("已交给系统安装，请确认。")
                    is InstallAction.Unavailable -> toast(installUnavailableText(action.reason))
                }
            } finally {
                installBusy = false
            }
        }
    }

    fun onCheckOrView() {
        if (state.available != null) controller.openDialog() else controller.checkNow()
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = ::onCheckOrView)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = null,
                    size = 38.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("检查更新", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = updateSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(onClick = ::onCheckOrView) {
                        Text(if (state.available != null) "查看" else "检查")
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(start = 66.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("前台自动检查", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "每天最多一次，只检查版本，不自动下载。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoCheckEnabled,
                    onCheckedChange = { controller.setAutoCheckEnabled(it) },
                )
            }

            if (state.status == UpdateStatus.FAILED || state.status == UpdateStatus.UNSUPPORTED) {
                TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri(RELEASES_URL) }
                            .onFailure { toast("无法打开发布页。") }
                    },
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("打开发布页")
                }
            }
        }
    }

    if (state.dialogOpen && state.available != null) {
        UpdateDialog(
            state = state,
            controller = controller,
            onLaunchInstall = ::launchInstallFlow,
            installBusy = installBusy,
        )
    }
}

private fun updateSubtitle(state: UpdateUiState): String {
    val current = "当前 ${state.currentVersionName}（${state.currentVersionCode}）"
    return when (state.status) {
        UpdateStatus.CHECKING -> "正在检查… · $current"
        UpdateStatus.AVAILABLE -> {
            val version = state.available?.manifest?.versionName
            if (version != null) "可更新至 $version · $current" else "有新版本 · $current"
        }
        UpdateStatus.UP_TO_DATE -> "已是最新版本 · $current"
        UpdateStatus.UNSUPPORTED -> "此更新不支持当前系统 · $current"
        UpdateStatus.FAILED -> "检查失败 · $current"
        UpdateStatus.INSTALLED -> "更新已安装 · $current"
        UpdateStatus.IDLE -> current
    }
}

@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    controller: com.upcpocket.app.update.UpdateController,
    onLaunchInstall: () -> Unit,
    installBusy: Boolean,
) {
    val update = state.available ?: return
    when (val download = state.download) {
        null -> {
            AlertDialog(
                onDismissRequest = controller::dismissDialog,
                title = { Text("发现新版本 ${update.manifest.versionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "大小 ${formatBytes(update.manifest.sizeBytes)} · " +
                                "当前 ${state.currentVersionName}（${state.currentVersionCode}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        update.manifest.notes.forEach { note ->
                            Text("· $note", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = controller::startDownload) { Text("更新") }
                },
                dismissButton = {
                    TextButton(onClick = controller::dismissDialog) { Text("稍后") }
                },
            )
        }
        is DownloadUiState.Running -> {
            val fraction = (download.downloadedBytes.toDouble() / update.manifest.sizeBytes)
                .coerceIn(0.0, 1.0)
            val source = controller.sourceLabel(download.sourceId)
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
                title = { Text("正在下载更新") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(
                            progress = { fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${formatBytes(download.downloadedBytes)} / " +
                                "${formatBytes(update.manifest.sizeBytes)} · " +
                                "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "线路：$source",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = controller::switchSource,
                        enabled = download.sourceIndex + 1 < download.sourceCount,
                    ) {
                        Text("切换线路")
                    }
                },
                dismissButton = {
                    TextButton(onClick = controller::cancelDownload) { Text("取消") }
                },
            )
        }
        is DownloadUiState.Ready -> {
            AlertDialog(
                onDismissRequest = controller::dismissDialog,
                title = { Text("更新已准备好") },
                text = {
                    Text(
                        "已校验完整性与签名。安装由系统确认，现有账号和本机数据会保留。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = onLaunchInstall,
                        enabled = !installBusy,
                    ) {
                        Text(if (installBusy) "正在准备…" else "安装")
                    }
                },
                dismissButton = {
                    TextButton(onClick = controller::dismissDialog) { Text("稍后") }
                },
            )
        }
        is DownloadUiState.Failed -> {
            AlertDialog(
                onDismissRequest = controller::dismissDialog,
                title = { Text("下载未完成") },
                text = {
                    Text(
                        downloadFailureText(download.reason),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = controller::retryDownload) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = controller::dismissDialog) { Text("稍后") }
                },
            )
        }
    }
}

private fun downloadFailureText(reason: UpdateDownloadFailure): String = when (reason) {
    UpdateDownloadFailure.STORAGE -> "本机可用空间不足，请清理后重试。"
    UpdateDownloadFailure.TIMEOUT -> "下载超时，请稍后重试。"
    UpdateDownloadFailure.SOURCE_UNAVAILABLE -> "更新线路暂不可用，请稍后重试。"
    UpdateDownloadFailure.APK_INVALID -> "更新包校验失败，已删除，请稍后重试。"
    UpdateDownloadFailure.INTERNAL -> "下载失败，请稍后重试。"
}

private fun installUnavailableText(reason: InstallUnavailable): String = when (reason) {
    InstallUnavailable.NOT_READY,
    InstallUnavailable.FILE_MISSING,
    InstallUnavailable.FILE_OUTSIDE_UPDATE_DIR,
    -> "更新包不可用，请重新下载。"
    InstallUnavailable.NO_INSTALLER,
    InstallUnavailable.UNTRUSTED_INSTALLER,
    -> "系统安装器不可用。"
}

private fun formatBytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
