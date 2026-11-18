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
import com.upcpocket.app.plugin.ui.UiCategory

data class OfficialLink(
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector,
    val group: String,
    val serviceId: String? = null,
    val serviceCategory: UiCategory? = null,
)

/**
 * 核对过的 UPC 官方只读外链目录（来源：docs/upc/官方入口.md）。
 * 供「校园官方网页」与「服务」广场共同使用，不预填账号、不代理登录。
 */
object OfficialWebCatalog {
    const val CALENDAR_SERVICE_ID = "official.calendar"
    const val CALENDAR_URL = "https://jwc.upc.edu.cn/603/list.htm"

    val calendar = OfficialLink(
        title = "校历",
        subtitle = "教务处 · 学年安排",
        url = CALENDAR_URL,
        icon = Icons.Filled.CalendarMonth,
        group = "学习",
        serviceId = CALENDAR_SERVICE_ID,
        serviceCategory = UiCategory.STUDY,
    )

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
        calendar,
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

    /** Host-owned official pages that can be pinned alongside plugin service entries. */
    fun serviceLinksFor(category: UiCategory): List<OfficialLink> =
        all.filter { it.serviceId != null && it.serviceCategory == category }

    fun browserIntent(link: OfficialLink): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    fun open(context: Context, link: OfficialLink) {
        val intent = browserIntent(link)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "系统未允许打开此网页", Toast.LENGTH_SHORT).show()
        }
    }
}
