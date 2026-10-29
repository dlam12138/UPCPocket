package com.upcpocket.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.upcpocket.app.BuildConfig
import com.upcpocket.app.ui.components.GlassCard

/** 上游项目（本项目的 Android 参考底座，MIT） */
private const val UPSTREAM_REPO = "https://github.com/3beuml/ZZUPocket"

/**
 * 「关于」页：**这个 App 是什么、数据从哪来、怎么用你的数据、开源与致谢**。
 *
 * 轻量版移除了课表与桌面组件，这里只描述仍在提供的能力，避免把已删除的功能
 * 写成现状。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("UPC Pocket · 轻量版", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "中国石油大学（华东）校园查询与专注助手：空闲教室、图书馆座位、" +
                            "校园卡余额与唐岛湾电费应用内查询、古镇口电费外部页面，加番茄钟与学习统计。" +
                            "本版不含课表。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AboutCard("当前能力") {
                Bullet("空闲教室：按校区、日期、楼宇和节次查询学校公开数据，无预约功能。")
                Bullet("图书馆座位：官方 CAS 登录后原生选馆区、日期、房间、座位与时间；提交和取消都由你确认。")
                Bullet("校园卡余额：首页直接输入本人学号，读取余额与过渡余额并分别展示；无需登录。")
                Bullet("电费：首页只有一个入口，进入后选校区。唐岛湾在 App 内选楼号＋房间号查询剩余电量（度）；" +
                    "古镇口仍打开学校页面（证书过期，不绕过 TLS）。")
                Bullet("输入记忆：唐岛湾的楼号与房间号、校园卡的学号保存在本机，可随时清除；不会自动发起查询。")
                Bullet("番茄钟：计时引擎 + 前台服务 + 通知，标签可自定义。")
                Bullet("学习统计：今日/周/月/年汇总、热力图、标签分布。")
            }

            AboutCard("数据与隐私") {
                Bullet("不保存账号密码。开启“记住学校登录”时，只有图书馆座位 token 用 Keystore 加密保存；校园卡余额按学号直接查询，不登录。")
                Bullet("空闲教室只发送查询条件；唐岛湾的楼号与房间号、校园卡的学号保存在本机私有空间，" +
                    "可在对应功能区清除，不会随输入自动查询。")
                Bullet("专注记录与标签只存在本机数据库，没有自建服务器，也不会上传。")
                Bullet("卸载 App 会一并清掉所有本地数据。")
                Bullet("App 不提供充值、转账或绑定，也不显示假余额。")
            }

            AboutCard("数据从哪来（重要）") {
                Bullet("空闲教室：来自学校公开页面的查询结果，显示查询条件与更新时间。")
                Bullet("图书馆：来自 libspace.upc.edu.cn 正常登录后的接口；结果和错误按学校返回显示。")
                Bullet("校园卡：来自学校校园卡余额接口的 getCardInfo，按本人学号查询，金额按“分”返回、按元显示。")
                Bullet("唐岛湾电费：来自 dfcz.upc.edu.cn 的公开查询表单，字段名为“当前剩余电量”。")
            }

            AboutCard("尚未实现 / 待验证") {
                Bullet("图书馆的真实账号与预约流程：需要在本机完成一次登录、选座与取消，" +
                    "模拟器与合成测试不能替代这一步。")
                Bullet("古镇口电费：学校域名证书已过期，页面也不是唐岛湾那套表单；本版仍交浏览器，不绕过 TLS。")
                Bullet("微信 SDK 直达：需要正式移动应用 AppID、正确包名/签名和目标页/小程序；当前未配置 AppID。")
                Bullet("后台定时同步 —— 应用没有周期性网络任务。")
            }

            AboutCard("开源与致谢") {
                Text(
                    text = "本项目以 MIT 许可证开源。Android 端的番茄钟、统计与原生查询页面参考了" +
                        "上游 ZZUPocket（MIT）的设计与实现；上游的郑州大学认证、接口、课表与宿舍" +
                        "房间规则没有被复用。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { uriHandler.openUri(UPSTREAM_REPO) }) {
                    Text("上游项目 3beuml/ZZUPocket（MIT）")
                }
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）· 轻量版",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
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
