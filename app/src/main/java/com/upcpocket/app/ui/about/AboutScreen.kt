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
 * 说明本地数据、用户导入文件和公开空闲教室查询各自的来源。
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
                            "番茄钟、学习统计与桌面小组件。支持 CSV 导入和公开空闲教室查询，不需要在 App 内登录。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AboutCard("当前能力") {
                Bullet("本地课表：手动添加或 CSV 批量导入，支持单双周、撞课提醒、课程总览。")
                Bullet("今天页：一条时间轴看课、日程与已完成的专注。")
                Bullet("番茄钟：计时引擎 + 前台服务 + 通知，标签可自定义。")
                Bullet("学习统计：今日/周/月/年汇总、热力图、标签分布。")
                Bullet("桌面小组件：下一节课（2×2）与今明课表（4×2），只读本地数据。")
                Bullet("空闲教室：按校区、日期、楼宇和节次查询学校公开数据，无预约功能。")
                Bullet("图书馆座位：官方 CAS 登录后原生选馆区、日期、房间、座位与时间；提交和取消都由你确认。")
                Bullet("校园卡余额：输入本人学号，直接读取校园卡余额与过渡余额，分别展示；无需先登录。")
                Bullet("唐岛湾宿舍电费：按楼号＋房间号查询学校公开页面，原生显示剩余电量（度）。")
                Bullet("课表导入：系统文件选择或剪贴板 CSV，预览校验后整批写入。")
                Bullet("校园服务：其余入口打开学校页面；古镇口电费因学校证书过期，本版仍交浏览器。")
            }

            AboutCard("数据与隐私") {
                Bullet("不保存账号密码。开启“记住学校登录”时，图书馆 token 与校园卡令牌用 Keystore 加密保存；" +
                    "课表会话由系统 WebView Cookie 保持（不受本应用加密）。")
                Bullet("空闲教室与唐岛湾电费只发送查询条件；唐岛湾的房间号不保存，只记楼号。")
                Bullet("课表、日程、专注记录都只存在本机数据库，没有自建服务器，也不会上传。")
                Bullet("卸载 App 会一并清掉所有本地数据。")
                Bullet("App 不提供充值、转账、绑定或流水查询，也不显示假余额。")
            }

            AboutCard("数据从哪来（重要）") {
                Bullet("课表内容：来自 App 内官方登录后的同步、你手动添加，或主动导入的 CSV。")
                Bullet("演示数据：虚构的示例课程，只在内存里生成、带「演示」标记，关掉即消失。")
                Bullet("空闲教室：来自学校公开页面的查询结果，显示查询条件与更新时间。")
                Bullet("图书馆：来自 libspace.upc.edu.cn 正常登录后的接口；结果和错误按学校返回显示。")
                Bullet("校园卡：来自 mb-xyk.upc.edu.cn 平台自己的余额接口，金额按“分”返回、按元显示。")
                Bullet("唐岛湾电费：来自 dfcz.upc.edu.cn 的公开查询表单，字段名为“当前剩余电量”。")
            }

            AboutCard("尚未实现 / 待验证") {
                Bullet("校园卡与图书馆的真实账号流程：需要在本人设备上完成一次登录与读取，" +
                    "模拟器与合成测试不能替代这一步。")
                Bullet("古镇口电费：学校域名证书已过期，页面也不是唐岛湾那套表单；本版仍交浏览器，不绕过 TLS。")
                Bullet("微信 SDK 直达：需要正式移动应用 AppID、正确包名/签名和目标页/小程序；当前未配置 AppID。")
                Bullet("UPC 实际 CSV 表头与登录后课表 DOM 仍需用脱敏样本真机核对；模板测试不等于学校格式已验证。")
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
