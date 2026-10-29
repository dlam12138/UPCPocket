package com.upcpocket.app.ui.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.ecard.DirectCardBalanceSection
import com.upcpocket.app.ui.nav.DOCK_CLEARANCE

/**
 * 「首页」= 校园服务 + 校园卡余额功能区。
 *
 * 轻量版第三步把电费收拢成一个入口（进去再选唐岛湾/古镇口），并把校园卡余额从
 * “目录卡片 → 第二页”改成直接嵌在首页的功能区：不再有中间跳转，也没有平台登录、
 * 流水或充值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenElectricity: () -> Unit,
    onOpenClassrooms: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "空闲教室、图书馆座位、唐岛湾电费与校园卡余额都在 App 内查询；" +
                    "校园卡余额输入本人学号，不需要登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("校园服务")
            ServiceEntry(
                title = "电费",
                subtitle = "唐岛湾 · 古镇口",
                onClick = onOpenElectricity,
            )
            ServiceEntry(
                title = "空闲教室",
                subtitle = "按校区、日期、楼宇和节次查询",
                onClick = onOpenClassrooms,
            )
            ServiceEntry(
                title = "图书馆座位预约",
                subtitle = "学校统一认证登录后选馆区、座位与时间",
                onClick = onOpenLibrary,
            )

            SectionTitle("校园卡余额")
            DirectCardBalanceSection()

            Text(
                text = "入口地址与开放条件会随学校调整；以学校官网通知为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun ServiceEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
