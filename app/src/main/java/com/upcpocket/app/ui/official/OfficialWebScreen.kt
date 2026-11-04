package com.upcpocket.app.ui.official

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.NavigationRow
import com.upcpocket.app.ui.components.SectionHeader

private data class OfficialLink(
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector,
)

/**
 * 学校官方网页入口。
 *
 * 只列出 docs/upc/官方入口.md 中已经核对的地址；页面交给浏览器，应用不预填
 * 账号、不代理登录、不把外部页面等同于已接入接口。课表/座位在应用内已有原生
 * 查询时，这里仍保留“学校页面”作为只读兜底和来源说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficialWebScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "系统未允许打开此网页", Toast.LENGTH_SHORT).show()
        }
    }

    val learning = listOf(
        OfficialLink(
            title = "课表",
            subtitle = "学校课表页面 · 只读",
            url = "https://app.upc.edu.cn/site/weekschedule/index",
            icon = Icons.Filled.CalendarMonth,
        ),
        OfficialLink(
            title = "空闲教室",
            subtitle = "学校公开查询页面 · 两校区",
            url = "https://app.upc.edu.cn/freeclass/wap/default/all",
            icon = Icons.Filled.MeetingRoom,
        ),
        OfficialLink(
            title = "图书馆座位系统",
            subtitle = "libspace · 学校统一认证",
            url = "https://libspace.upc.edu.cn/libseat/",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
        ),
    )
    val life = listOf(
        OfficialLink(
            title = "唐岛湾剩余电量",
            subtitle = "学校电量页 · App 内已有原生查询",
            url = "http://dfcz.upc.edu.cn/ShiYou/Default.aspx",
            icon = Icons.Filled.Bolt,
        ),
        OfficialLink(
            title = "古镇口电费",
            subtitle = "学校外部页面 · 证书异常时不绕过校验",
            url = "https://gzkdf.upc.edu.cn/acmswx/index.aspx",
            icon = Icons.Filled.Bolt,
        ),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
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
            Text(
                text = "以下地址来自学校现有服务。点击后在浏览器打开；应用不预填账号，" +
                    "也不代理这些页面的登录。页面可用性、证书和登录规则以学校为准。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(
                title = "学习",
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    learning.forEachIndexed { index, link ->
                        OfficialRow(link, onOpen = { open(link.url) })
                        if (index != learning.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(
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
                    life.forEachIndexed { index, link ->
                        OfficialRow(link, onOpen = { open(link.url) })
                        if (index != life.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "这些页面是学校现有入口，不代表本应用已获得校方客户端身份或全部接口授权。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
