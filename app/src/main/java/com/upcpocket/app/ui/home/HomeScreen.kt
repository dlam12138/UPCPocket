package com.upcpocket.app.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.upcpocket.app.data.local.CampusServices
import com.upcpocket.app.data.wechat.WeChatDirect
import com.upcpocket.app.data.wechat.WeChatSendResult
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.nav.DOCK_CLEARANCE
import com.upcpocket.app.ui.campus.WeChatHandoff

/**
 * 「首页」= 校园服务总入口。
 *
 * 轻量版把课表整体移除后，默认首页改成本页：空闲教室、图书馆座位、校园卡余额、
 * 两个校区的宿舍电费都是 App 内原生查询；其余学校网页入口交给系统浏览器/微信打开。
 * 各入口的数据来源与适用条件都写在卡片上，不把"打开了页面"说成"查到了数据"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenClassrooms: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenCampusCard: () -> Unit,
    onOpenTangdaowanPower: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }
    var weChatEntry by remember { mutableStateOf<CampusServices.Entry?>(null) }

    /**
     * 打开官方入口。
     *
     * 只发隐式 ACTION_VIEW，不附加任何 extra、不授予 URI 权限；
     * 系统找不到浏览器时给一句提示，而不是崩溃。
     */
    fun openInBrowser(entry: CampusServices.Entry) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    fun open(entry: CampusServices.Entry) {
        when (entry.id) {
            "classroom" -> onOpenClassrooms()
            "library_seat" -> onOpenLibrary()
            "ecard" -> onOpenCampusCard()
            "power_tangdaowan" -> onOpenTangdaowanPower()
            // 古镇口仍是外部入口：证书过期且页面不是同一套查询表单，不绕过 TLS。
            "power_guzhenkou" -> openInBrowser(entry)
            else -> openInBrowser(entry)
        }
    }

    weChatEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { weChatEntry = null },
            title = { Text("通过微信打开 · ${entry.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将链接复制或分享到微信，再由你点击打开。不会自动发消息，也不会读取微信中的余额。")
                    Text("如果页面提示登录失效或无效 token，请从学校公众号的服务菜单进入；仅打开微信不能补齐学校的认证。")
                    if (WeChatDirect.isConfigured()) {
                        TextButton(onClick = {
                            val result = WeChatDirect.openWebPage(context, entry.url)
                            val message = when (result) {
                                WeChatSendResult.Sent ->
                                    "已向微信发送请求。请在微信里确认是否进入目标页，并完成学校认证；" +
                                        "发送成功不等于余额查询成功。"
                                WeChatSendResult.WeChatNotInstalled -> "未安装微信，已保留复制/分享兜底。"
                                WeChatSendResult.NotConfigured -> "未配置正式移动应用 AppID，直达不可用。"
                                WeChatSendResult.RegistrationFailed -> "微信 SDK 注册失败，请核对 AppID、包名与签名。"
                                WeChatSendResult.RequestRejected -> "微信未接受该请求，请使用复制/分享兜底并核对开放平台配置。"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }) { Text("微信直达（需要真机验证）") }
                    } else {
                        Text(
                            "尚未配置正式移动应用 AppID，微信 SDK 直达未启用；当前只提供复制/分享兜底，" +
                                "不把打开微信首页当作直达完成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = {
                        WeChatHandoff.copyAndOpen(context, entry.url)
                        weChatEntry = null
                    }) { Text("复制链接并打开微信") }
                    TextButton(onClick = {
                        WeChatHandoff.share(context, entry.url)
                        weChatEntry = null
                    }) { Text("分享链接到微信") }
                    TextButton(onClick = {
                        openInBrowser(entry)
                        weChatEntry = null
                    }) { Text("用外部浏览器打开") }
                }
            },
            confirmButton = {
                TextButton(onClick = { weChatEntry = null }) { Text("关闭") }
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("校园服务") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // 边界说明放最上面：用户点之前就该知道"这不是 App 在查数据"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "校园查询与学校服务",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "空闲教室、图书馆座位、校园卡余额、唐岛湾剩余电量都在 App 内原生查询；" +
                            "校园卡余额输入本人学号，图书馆用学校官方登录，唐岛湾电费按楼号＋房间号查询。" +
                            "开启“记住登录”时，图书馆 token 与校园卡令牌才用 Keystore 加密保存。" +
                            "不提供充值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // ── 两个校区的电费：最容易混淆，单独置顶并说明各自适用范围 ──
            // 从 ALL 里筛，而不是从 LEARNING —— 这两个入口属于生活类，写死 LEARNING
            // 会让置顶卡片整组消失（真机验证时踩到过一次）。
            SectionTitle("宿舍电费（两套系统，别拿错校区）", emphasis = true)
            CampusServices.ALL
                .filter { CampusServices.isTangdaowanPower(it) || CampusServices.isGuzhenkouPower(it) }
                .forEach { entry ->
                    PowerEntryCard(
                        entry = entry,
                        onOpen = { open(entry) },
                        onWeChat = { weChatEntry = entry },
                    )
                }

            SectionTitle("学习")
            CampusServices.LEARNING.filterNot {
                CampusServices.isTangdaowanPower(it) || CampusServices.isGuzhenkouPower(it)
            }.forEach { entry ->
                ServiceCard(
                    entry = entry,
                    expanded = expanded == entry.id,
                    onToggle = { expanded = if (expanded == entry.id) null else entry.id },
                    onOpen = { open(entry) },
                )
            }

            SectionTitle("生活与办事")
            CampusServices.LIFE.filterNot {
                CampusServices.isTangdaowanPower(it) || CampusServices.isGuzhenkouPower(it)
            }.forEach { entry ->
                ServiceCard(
                    entry = entry,
                    expanded = expanded == entry.id,
                    onToggle = { expanded = if (expanded == entry.id) null else entry.id },
                    onOpen = { open(entry) },
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "入口地址会随学校调整而变化，本页记录的是编写时公开的地址；" +
                    "以学校官网与信息服务部门的通知为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String, emphasis: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/**
 * 电费入口卡：适用范围直接摊开（不等用户点开），因为唐岛湾 / 古镇口真的不通用。
 */
@Composable
private fun PowerEntryCard(
    entry: CampusServices.Entry,
    onOpen: () -> Unit,
    onWeChat: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.campus.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = when (entry.id) {
                        "power_tangdaowan" -> "在应用中查询剩余电量"
                        else -> if (entry.access == CampusServices.Access.WECHAT) "微信打开选项" else "打开学校页面"
                    },
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(entry.scope, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "适用条件：${entry.access.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.note.isNotBlank()) {
                Text(
                    text = entry.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.id == "power_tangdaowan") {
                TextButton(onClick = onOpen) { Text("在应用中查询剩余电量") }
                TextButton(onClick = onWeChat) { Text("改用微信打开（备用）") }
            } else if (entry.access == CampusServices.Access.WECHAT) {
                TextButton(onClick = onWeChat) { Text("通过微信打开") }
            } else {
                TextButton(onClick = onOpen) { Text("打开学校页面") }
            }
        }
    }
}

/**
 * 普通服务卡：默认只显示一行摘要，点开才展开适用范围与注意事项。
 *
 * 为什么不一上来全展开：入口有七八个，全展开会变成一篇文档；
 * 但"适用范围"必须**可查**，所以做成一行摘要 + 可展开详情。
 */
@Composable
private fun ServiceCard(
    entry: CampusServices.Entry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${entry.campus.label} · ${entry.subtitle} · ${entry.access.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = when (entry.id) {
                        "classroom" -> "在应用中查询空闲教室"
                        "library_seat" -> "在应用中预约图书馆座位"
                        "ecard" -> "在应用中查询校园卡余额"
                        else -> "打开学校页面"
                    },
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onOpen),
                )
            }
            if (expanded) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("适用范围", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(entry.scope, style = MaterialTheme.typography.bodySmall)
                    if (entry.note.isNotBlank()) {
                        Text("注意", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(entry.note, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = entry.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
