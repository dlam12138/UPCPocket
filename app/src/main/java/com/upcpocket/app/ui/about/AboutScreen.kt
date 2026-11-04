package com.upcpocket.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.upcpocket.app.BuildConfig
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.SectionHeader

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
                        "面向中国石油大学（华东）的校园日常工具：课表、校园卡、电费、空闲教室、" +
                            "图书馆座位与专注。非学校官方应用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）· M5 预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }

            AboutCard("现在能用") {
                Bullet("课表：当前学校账号正常认证后只读查询；学期、教学周、周网格、今日课程和详情。" +
                    "只在进入课表或切换周时查询，保留已成功周的离线缓存。")
                Bullet("校园卡余额：按当前账号中完整有效的本人学号查询，展示余额与过渡余额；" +
                    "查询缓存、2×2 组件和清除入口保持独立。")
                Bullet("电费：唐岛湾在 App 内选楼号与房间号查询；古镇口打开学校页面。")
                Bullet("空闲教室：两校区、日期、教学楼与节次筛选，原生只读结果。")
                Bullet("图书馆座位：学校统一认证后查询、预约与取消；验证码/二次验证走学校网页登录。")
                Bullet("专注：番茄钟、标签、今日记录、热力图与统计；计时可在前台服务中继续。")
                Bullet("桌面组件：校园卡余额与唐岛湾电量，2×2，仅点击刷新。")
            }

            SectionHeader(
                title = "数据与隐私",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bullet("账号密码只有你主动保存时才写入本机，使用 Android Keystore 中独立密钥的 AES-GCM 加密；" +
                        "文件位于应用私有、不参与系统备份的目录。未保存时不回显、不猜测。")
                    Bullet("课表/图书馆会话使用独立加密与会话代次，绑定当前账号；换号、删号、改密或清理失败时会阻止" +
                        "旧会话恢复，迟到响应不会覆盖新账号。")
                    Bullet("学校账号页在可见时设置 FLAG_SECURE，避免密码输入出现在系统截图/录屏中。")
                    Bullet("课表只按需查询单周，不自动遍历整学期；校园卡、电费与组件都没有周期后台任务。")
                    Bullet("查询会发送到对应学校系统，App 不经过自建服务器；卸载会清除本机数据。")
                }
            }

            SectionHeader(
                title = "数据来源",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bullet("课表来自 app.upc.edu.cn 的课表接口，由正常 CAS 认证获取。" +
                        "协议参考 UPC-MCP 固定提交，未嵌入 MCP 运行时。")
                    Bullet("校园卡余额来自学校 getCardInfo；唐岛湾电量来自 dfcz.upc.edu.cn 的公开查询表单。")
                    Bullet("空闲教室来自学校公开查询页；图书馆座位来自 libspace 统一认证后的只读与用户主动操作。")
                    Bullet("古镇口电费仍是学校外部页面；证书或网络异常时不绕过 TLS。")
                }
            }

            SectionHeader(
                title = "验证边界",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bullet("真实课表/图书馆只读链路有本人账号模拟器验证；验证码真实链路由合成测试与回退代码覆盖，" +
                        "不等于所有设备、网络或 ROM 均可用。")
                    Bullet("真机、release/R8、其他 WebView 与长期会话 TTL 未验证；本轮 M5 未重新进行真实学校登录。")
                    Bullet("图书馆空间预约（nspace）和超星入口仍未原生接入，保留学校官方网页。")
                    Bullet("不提供课程编辑、提醒、日历导出、课表组件、充值、转账或自动抢座。")
                }
            }

            SectionHeader(
                title = "开源与致谢",
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Android 工程以 MIT 许可证开源，参考 MIT 许可的上游 ZZUPocket。" +
                            "UPC 的认证、接口、课表与宿舍规则按本校系统重新实现，未套用其他学校事实。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { uriHandler.openUri(UPSTREAM_REPO) }) {
                        Text("上游项目 3beuml/ZZUPocket（MIT）")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "入口可访问、会话建立、数据读取和业务成功是不同事实；结果以学校系统为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
