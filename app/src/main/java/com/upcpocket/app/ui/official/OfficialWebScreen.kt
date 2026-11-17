package com.upcpocket.app.ui.official

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.NavigationRow
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.liquid.LiquidTopAppBar
import com.upcpocket.app.ui.liquid.liquidScaffoldColor

/**
 * 学校官方网页入口。
 *
 * 只列出 docs/upc/官方入口.md 中已经核对的地址；页面交给浏览器，应用不预填
 * 账号、不代理登录、不把外部页面等同于已接入接口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficialWebScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        containerColor = liquidScaffoldColor(),
        topBar = {
            LiquidTopAppBar(
                title = { Text("校园官方网页") },
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
        ) {
            SectionHeader(
                title = "学习",
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    OfficialWebCatalog.learning.forEachIndexed { index, link ->
                        OfficialRow(link, onOpen = { OfficialWebCatalog.openUrl(context, link.url) })
                        if (index != OfficialWebCatalog.learning.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }

            SectionHeader(
                title = "生活",
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    OfficialWebCatalog.life.forEachIndexed { index, link ->
                        OfficialRow(link, onOpen = { OfficialWebCatalog.openUrl(context, link.url) })
                        if (index != OfficialWebCatalog.life.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfficialRow(link: OfficialLink, onOpen: () -> Unit) {
    NavigationRow(
        title = link.title,
        subtitle = link.subtitle,
        icon = link.icon,
        onClick = onOpen,
    )
}
