package com.upcpocket.app.ui.learning

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.CampusTopAppBar
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassIconButton as IconButton
import com.upcpocket.app.ui.components.NavigationRow
import com.upcpocket.app.ui.official.OfficialWebCatalog

/**
 * 学习平台聚合页。
 *
 * 只把已核对的公开网页交给系统浏览器；页面不登录、不同步任务、不读取账号，
 * 也不把网页登录结果当作应用内状态。返回由宿主导航原地弹栈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningPlatformsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        topBar = {
            CampusTopAppBar(
                title = { Text(LearningPlatformCatalog.TITLE) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "网页由各平台提供，登录在网页内完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
            )

            LearningPlatformCatalog.sections.forEach { section ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Column {
                        section.links.forEachIndexed { index, link ->
                            NavigationRow(
                                title = link.title,
                                subtitle = link.subtitle,
                                icon = link.icon,
                                onClick = { OfficialWebCatalog.open(context, link) },
                            )
                            if (index != section.links.lastIndex) {
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
}
