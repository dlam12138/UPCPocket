package com.upcpocket.app.ui.power

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upcpocket.app.data.local.CampusServices
import com.upcpocket.app.ui.components.GlassCard

/**
 * 「电费」= 一个入口，先选校区。
 *
 * 唐岛湾走 App 内原生查询；古镇口仍打开学校自己的页面（域名证书已过期，不绕过 TLS）。
 * 两个校区不是一个系统，也不把古镇口说成已原生接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricityScreen(
    onBack: () -> Unit,
    onOpenTangdaowan: () -> Unit,
) {
    val context = LocalContext.current
    val tangdaowan = CampusServices.ALL.first { CampusServices.isTangdaowanPower(it) }
    val guzhenkou = CampusServices.ALL.first { CampusServices.isGuzhenkouPower(it) }

    fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
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
                title = { Text("电费") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "两个校区的宿舍电费是不同系统。唐岛湾在 App 内查询；古镇口仍打开学校页面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("唐岛湾校区", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "应用内查询 · 选择楼号并填写房间号，查询剩余电量（度）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "不需要登录；只读、不充值。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onOpenTangdaowan,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text("进入唐岛湾查询") }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("古镇口校区", style = MaterialTheme.typography.titleMedium)
                    Text(guzhenkou.scope, style = MaterialTheme.typography.bodySmall)
                    Text(
                        guzhenkou.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { openInBrowser(guzhenkou.url) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text("打开学校页面") }
                }
            }

            Text(
                text = "唐岛湾为 App 内查询；古镇口为学校外部页面，未做原生接入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
