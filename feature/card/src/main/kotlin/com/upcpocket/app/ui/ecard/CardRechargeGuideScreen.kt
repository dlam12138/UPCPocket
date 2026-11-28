package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Checkout and payment are handled by the school's official channel. */
@Composable
internal fun CardRechargeGuideScreen(
    back: () -> Unit,
    openOfficialPortal: (() -> Unit)?,
) {
    Scaffold(
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = { Text("校园卡充值说明") },
                navigationIcon = {
                    com.upcpocket.app.ui.components.GlassIconButton(onClick = back) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                com.upcpocket.app.ui.components.GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("请通过学校官方渠道为校园卡充值。", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "请从学校官方应用或门户进入一卡通服务。UPC Pocket 只提供余额和本人流水查询，" +
                                "不会代登校园卡、创建充值订单或唤起付款。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (openOfficialPortal != null) {
                            com.upcpocket.app.ui.components.GlassButton(
                                onClick = openOfficialPortal,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("打开数字石大官方门户") }
                        }
                    }
                }
            }
        }
    }
}
