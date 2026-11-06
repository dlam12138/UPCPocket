package com.upcpocket.app.ui.about

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.upcpocket.app.BuildConfig
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.diagnostics.DiagnosticExportResult
import com.upcpocket.app.diagnostics.DiagnosticShare
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.SectionHeader
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 上游项目（本项目的 Android 参考底座，MIT） */
private const val UPSTREAM_REPO = "https://github.com/3beuml/ZZUPocket"

/**
 * 「关于」页：这个 App 是什么、数据从哪来、怎么用你的数据、开源与边界。
 *
 * M5 不再把页面当作功能清单的“第二首页”；只保留用户需要理解数据归属和责任边界
 * 的事实，避免出现“已接入统一认证”这类超出实际验证的表述。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val rootView = LocalView.current
    val scope = rememberCoroutineScope()
    var diagnosticsEnabled by remember { mutableStateOf(AppGraph.diagnostics.isEnabled()) }
    var exporting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun exportDiagnostics() {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
                val widthDp = if (rootView.width > 0) {
                    (rootView.width / density).roundToInt()
                } else {
                    0
                }
                val heightDp = if (rootView.height > 0) {
                    (rootView.height / density).roundToInt()
                } else {
                    0
                }
                when (val result = AppGraph.diagnostics.export(
                    AppGraph.diagnostics.deviceInfo(widthDp, heightDp),
                )) {
                    DiagnosticExportResult.NoRecords -> toast("还没有诊断记录。")
                    is DiagnosticExportResult.Failure -> toast("导出失败，请稍后重试。")
                    is DiagnosticExportResult.Success -> {
                        val started = runCatching {
                            context.startActivity(DiagnosticShare.chooser(context, result.uri))
                            true
                        }.getOrDefault(false)
                        toast(
                            if (started) "已生成报告，请选择应用分享。"
                            else "没有可用的分享应用。",
                        )
                    }
                }
            } finally {
                exporting = false
            }
        }
    }

    fun clearDiagnostics() {
        if (clearing) return
        clearing = true
        scope.launch {
            try {
                val cleared = AppGraph.diagnostics.clearNow()
                toast(if (cleared) "诊断记录已清除。" else "清除未完成，请稍后重试。")
            } finally {
                clearing = false
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconBadge(
                        icon = Icons.Outlined.Info,
                        contentDescription = null,
                        containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        size = 44.dp,
                    )
                    Text(
                        "UPC Pocket",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "中国石油大学（华东）的校园日常工具。非学校官方应用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }

            SectionHeader(
                title = "更新",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            UpdateSection()

            AboutCard("使用指南") {
                Bullet("学校账号：首页右上角设置 → 学校账号，填写账号、密码并保存；返回课表后点击右上角刷新。保存不代表验证通过。")
                Bullet("课表：点击学期或周次切换，点击课程查看详情；右上角刷新重新读取，需验证码时点击“学校登录”。")
                Bullet("校园卡：首页点击“查询余额”；未设置账号时先保存学校账号。唐岛湾电量需选楼号、填房间，再点“查询”。")
                Bullet("空闲教室：会记住上次校区；切换校区、日期或上午/下午/晚上会自动查询，自选节次后点“查询”。图书馆：登录后按馆区、房间、座位和时间逐步选择，提交后核对“我的预约”。")
                Bullet("桌面组件：设置 → 桌面组件，先在应用内查询，再添加对应组件；点击刷新更新，失败时留意上次成功时间。")
            }

            AboutCard("现在能用") {
                Bullet("课表：查看学期、教学周、周网格与今日课程；已查询的周可离线查看。")
                Bullet("校园卡：查看余额与过渡余额；组件显示上次成功读取。")
                Bullet("电费：唐岛湾在 App 内查询；古镇口打开学校页面。")
                Bullet("空闲教室：按校区、日期、教学楼与节次筛选。")
                Bullet("图书馆：查询、预约和取消座位；验证码时打开学校网页。")
                Bullet("专注：番茄钟、记录、热力图与统计。")
                Bullet("桌面组件：校园卡、电量、课表；默认 2×2，课表可拉到 4×2。")
            }

            SectionHeader(
                title = "数据与隐私",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bullet("账号密码只在你保存时加密存放在本机，不参与系统备份。")
                    Bullet("换号、删号或改密后，旧登录会失效。")
                    Bullet("账号页禁止截图，避免密码出现在截图或录屏中。")
                    Bullet("课表按需查询单周；校园卡、电费和组件不做周期后台查询。")
                    Bullet("查询直接发往学校系统，不经过本项目服务器；卸载会清除本机数据。")
                }
            }

            SectionHeader(
                title = "诊断",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("本机诊断记录", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "只记录固定状态码；保存在本机，由你选择是否分享。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = diagnosticsEnabled,
                            onCheckedChange = { enabled ->
                                diagnosticsEnabled = enabled
                                scope.launch {
                                    AppGraph.uiPrefsStore.setDiagnosticsEnabled(enabled)
                                    AppGraph.diagnostics.setEnabled(enabled)
                                    if (!enabled) {
                                        AppGraph.diagnostics.clearNow()
                                    }
                                }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { exportDiagnostics() },
                            enabled = !exporting && !clearing,
                        ) {
                            Text(if (exporting) "正在生成…" else "导出诊断报告")
                        }
                        TextButton(
                            onClick = { clearDiagnostics() },
                            enabled = !exporting && !clearing,
                        ) {
                            Text(if (clearing) "正在清除…" else "清除诊断记录")
                        }
                    }
                }
            }

            SectionHeader(
                title = "数据来源",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bullet("课表：学校课表服务，使用学校登录。")
                    Bullet("校园卡：学校余额服务；电量：唐岛湾查询页。")
                    Bullet("空闲教室：学校公开查询页；图书馆：学校座位服务。")
                    Bullet("古镇口电费仍使用学校页面；页面不可用时请稍后再试。")
                }
            }

            SectionHeader(
                title = "使用边界",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bullet("验证码、网络或设备限制可能导致学校页面不可用。")
                    Bullet("校园服务只提供查看与预约，不提供编辑、提醒、充值或自动抢座。")
                }
            }

            SectionHeader(
                title = "开源与致谢",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Android 工程以 MIT 许可证开源，参考 MIT 许可的上游 ZZUPocket。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { uriHandler.openUri(UPSTREAM_REPO) }) {
                        Text("上游项目 3beuml/ZZUPocket（MIT）")
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    SectionHeader(
        title = title,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
