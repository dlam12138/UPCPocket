package com.upcpocket.app.ui.campus

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.upcpocket.app.ui.components.GlassCard

/**
 * 「校园服务」—— 官方入口导航。
 *
 * 这一页**只做一件事**：把学校的官方服务地址按校区整理好，点一下交给系统浏览器。
 *
 * 刻意不做的事（与总体计划书 §2/§3 一致）：
 *  - 不用 WebView 内嵌：登录页留在浏览器里，App 拿不到任何 Cookie / Token；
 *  - 不代填账号、不做单点登录代理；
 *  - 不请求任何校园接口：本应用连 INTERNET 权限都没有。
 *
 * 唐岛湾与古镇口的电费是**两套互不相通的系统**，所以那一组单独排在最前面，
 * 并在卡面上直接写明适用范围 —— 拿错校区是这个页面最可能犯的错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusServicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }

    /**
     * 打开官方入口。
     *
     * 只发隐式 ACTION_VIEW，不附加任何 extra、不授予 URI 权限；
     * 系统找不到浏览器时给一句提示，而不是崩溃。
     */
    fun open(entry: CampusServices.Entry) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校园服务") },
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
                        text = "这些入口用系统浏览器打开",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "登录、查询、缴费都在学校自己的网页里完成。App 不保存账号、" +
                            "Cookie 或 Token，也不读取页面内容；本应用没有网络权限。",
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
                    PowerEntryCard(entry = entry, onOpen = { open(entry) })
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
private fun PowerEntryCard(entry: CampusServices.Entry, onOpen: () -> Unit) {
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
                    contentDescription = "在浏览器中打开",
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
                    contentDescription = "在浏览器中打开",
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
