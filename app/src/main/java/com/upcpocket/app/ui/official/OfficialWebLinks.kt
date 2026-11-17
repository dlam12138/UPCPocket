package com.upcpocket.app.ui.official

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.ui.graphics.vector.ImageVector

data class OfficialLink(
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector,
    val group: String,
)

/**
 * 核对过的 UPC 官方只读外链目录（来源：docs/upc/官方入口.md）。
 * 供「校园官方网页」与「服务」广场共同使用，不预填账号、不代理登录。
 */
object OfficialWebCatalog {
    val learning: List<OfficialLink> = listOf(
        OfficialLink(
            title = "课表",
            subtitle = "周课表",
            url = "https://app.upc.edu.cn/site/weekschedule/index",
            icon = Icons.Filled.CalendarMonth,
            group = "学习",
        ),
        OfficialLink(
            title = "空闲教室",
            subtitle = "两校区",
            url = "https://app.upc.edu.cn/freeclass/wap/default/all",
            icon = Icons.Filled.MeetingRoom,
            group = "学习",
        ),
        OfficialLink(
            title = "图书馆座位系统",
            subtitle = "学校登录",
            url = "https://libspace.upc.edu.cn/libseat/",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            group = "学习",
        ),
    )

    val life: List<OfficialLink> = listOf(
        OfficialLink(
            title = "唐岛湾剩余电量",
            subtitle = "查询页",
            url = "http://dfcz.upc.edu.cn/ShiYou/Default.aspx",
            icon = Icons.Filled.Bolt,
            group = "生活",
        ),
        OfficialLink(
            title = "古镇口电费",
            subtitle = "查询页",
            url = "https://gzkdf.upc.edu.cn/acmswx/index.aspx",
            icon = Icons.Filled.Bolt,
            group = "生活",
        ),
    )

    val all: List<OfficialLink> = learning + life

    fun openUrl(context: Context, url: String) {
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
}
