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
 * 这一版是 UPC 本地原型，所以"数据从哪来"必须写得比上游更直白：
 * 只有两种数据 —— 你自己输入的，和明确标注的演示数据。没有任何校园数据。
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
                    Text("UPC Pocket", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "中国石油大学（华东）校园助手的本地原型：课表、自定义日程、" +
                            "番茄钟、学习统计与桌面小组件。不连接任何校园系统，不需要账号。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AboutCard("当前能力") {
                Bullet("本地课表：自己添加课程与日程，支持单双周、撞课提醒、课程总览。")
                Bullet("今天页：一条时间轴看课、日程与已完成的专注。")
                Bullet("番茄钟：计时引擎 + 前台服务 + 通知，标签可自定义。")
                Bullet("学习统计：今日/周/月/年汇总、热力图、标签分布。")
                Bullet("桌面小组件：下一节课（2×2）与今明课表（4×2），只读本地数据。")
                Bullet("校园服务导航：把官方入口按唐岛湾 / 古镇口整理，交系统浏览器打开。")
            }

            AboutCard("数据与隐私") {
                Bullet("不需要账号，也不保存任何密码、Cookie 或 Token —— 应用没有网络权限。")
                Bullet("课表、日程、专注记录都只存在本机数据库，没有自建服务器，也不会上传。")
                Bullet("卸载 App 会一并清掉所有本地数据。")
                Bullet("登录、缴费等操作发生在系统浏览器里，由学校自己的网页完成。")
            }

            AboutCard("数据从哪来（重要）") {
                Bullet("课表内容：100% 由你自己在 App 内添加。")
                Bullet("演示数据：虚构的示例课程，只在内存里生成、带「演示」标记，关掉即消失。")
                Bullet("没有第三种来源 —— 本原型不读取教务系统、一卡通或图书馆的任何数据。")
            }

            AboutCard("尚未实现") {
                Bullet("教务课表导入、空教室实时查询、电费与校园卡查询。")
                Bullet("统一身份认证接入与任何形式的自动登录。")
                Bullet("后台定时同步 —— 应用没有周期性网络任务。")
            }

            AboutCard("开源与致谢") {
                Text(
                    text = "本项目以 MIT 许可证开源。Android 端的课表网格、番茄钟、统计与 " +
                        "RemoteViews 小组件参考了上游 ZZUPocket（MIT）的设计与实现；" +
                        "上游的郑州大学认证、接口与宿舍房间规则没有被复用。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { uriHandler.openUri(UPSTREAM_REPO) }) {
                    Text("上游项目 3beuml/ZZUPocket（MIT）")
                }
                Text(
                    text = "桌面小组件的版式参考了拾光课程表（Apache-2.0）的实现思路，" +
                        "只参考设计，没有拷贝代码。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）· 本地原型",
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
