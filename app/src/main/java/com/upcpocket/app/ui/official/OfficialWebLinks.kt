package com.upcpocket.app.ui.official

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.ui.graphics.vector.ImageVector
import com.upcpocket.app.data.prefs.PreferredCampus
import com.upcpocket.app.plugin.ui.UiCategory

data class OfficialLink(
    val title: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector,
    val group: String,
    val serviceId: String? = null,
    val serviceCategory: UiCategory? = null,
    /**
     * 仅此校区专属的入口；null 表示两校区通用或不属于校区范围。
     *
     * 只用于「服务」页按常用校区收敛候选；完整目录仍展示全部入口。
     */
    val campus: PreferredCampus? = null,
    /**
     * 非空表示该入口不在浏览器直接打开，而是展示官方微信公众号交接说明。
     *
     * 只保存学校公开的公众号名称、菜单路径与固定文案，不携带深链、
     * 小程序参数或账号信息。
     */
    val weChatHandoff: WeChatReservationHandoff? = null,
)

/**
 * 核对过的 UPC 官方只读外链目录（来源：docs/upc/官方入口.md）。
 * 供「校园官方网页」与「服务」广场共同使用，不预填账号、不代理登录。
 */
object OfficialWebCatalog {
    /**
     * 常规浏览器入口的稳定首页固定 ID。
     *
     * 已保存的固定集合只按这些字符串匹配，因此 ID 只新增、不随标题、URL 或
     * 分类调整重命名；微信交接入口没有 ID，不参与首页固定。
     */
    const val TIMETABLE_SERVICE_ID = "official.timetable"
    const val FREE_CLASSROOM_SERVICE_ID = "official.free_classroom"
    const val LIBRARY_SEAT_SERVICE_ID = "official.library_seat"
    const val CALENDAR_SERVICE_ID = "official.calendar"
    const val ACADEMIC_SYSTEM_SERVICE_ID = "official.academic_system"
    const val DIGITAL_UPC_SERVICE_ID = "official.digital_upc"
    const val JWC_SERVICE_ID = "official.jwc"
    const val SCHOOLS_SERVICE_ID = "official.schools"
    const val TANGDAOWAN_POWER_SERVICE_ID = "official.power_tangdaowan"
    const val GUZHENKOU_POWER_SERVICE_ID = "official.power_guzhenkou"
    const val CALENDAR_URL = "https://jwc.upc.edu.cn/603/list.htm"
    const val ACADEMIC_SCORES_URL = "https://app.upc.edu.cn/site/score/index"

    val calendar = OfficialLink(
        title = "校历",
        subtitle = "教务处 · 学年安排",
        url = CALENDAR_URL,
        icon = Icons.Filled.CalendarMonth,
        group = "学习",
        serviceId = CALENDAR_SERVICE_ID,
        serviceCategory = UiCategory.STUDY,
    )

    val academicScores = OfficialLink(
        title = "学校成绩网页",
        subtitle = "教务成绩查询",
        url = ACADEMIC_SCORES_URL,
        icon = Icons.Filled.Assessment,
        group = "学习",
    )

    val learning: List<OfficialLink> = listOf(
        OfficialLink(
            title = "课表",
            subtitle = "周课表",
            url = "https://app.upc.edu.cn/site/weekschedule/index",
            icon = Icons.Filled.CalendarMonth,
            group = "学习",
            serviceId = TIMETABLE_SERVICE_ID,
        ),
        OfficialLink(
            title = "空闲教室",
            subtitle = "两校区",
            url = "https://app.upc.edu.cn/freeclass/wap/default/all",
            icon = Icons.Filled.MeetingRoom,
            group = "学习",
            serviceId = FREE_CLASSROOM_SERVICE_ID,
        ),
        OfficialLink(
            title = "图书馆座位系统",
            subtitle = "学校登录",
            url = "https://libspace.upc.edu.cn/libseat/",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            group = "学习",
            serviceId = LIBRARY_SEAT_SERVICE_ID,
            campus = PreferredCampus.TANGDAOWAN,
        ),
        calendar,
        academicScores,
        OfficialLink(
            title = "教务系统",
            subtitle = "学校登录",
            url = "https://jwxt.upc.edu.cn/jsxsd/framework/xsMainV.htmlx",
            icon = Icons.Outlined.School,
            group = "学习",
            serviceId = ACADEMIC_SYSTEM_SERVICE_ID,
        ),
        OfficialLink(
            title = "数字石大",
            subtitle = "学校门户",
            url = "https://i.upc.edu.cn/",
            icon = Icons.Filled.Apps,
            group = "学习",
            serviceId = DIGITAL_UPC_SERVICE_ID,
        ),
        OfficialLink(
            title = "教务处官网",
            subtitle = "教务通知与办事指南",
            url = "https://jwc.upc.edu.cn/",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            group = "学习",
            serviceId = JWC_SERVICE_ID,
        ),
        OfficialLink(
            title = "学院官网",
            subtitle = "教学科研单位",
            url = "https://www.upc.edu.cn/jxkydw.htm",
            icon = Icons.Outlined.Public,
            group = "学习",
            serviceId = SCHOOLS_SERVICE_ID,
        ),
    )

    val gymReservation = OfficialLink(
        title = GymReservationHandoff.gym.title,
        subtitle = "官方微信 · 场地预定",
        url = GymReservationHandoff.RULES_URL,
        icon = Icons.Filled.SportsTennis,
        group = "生活",
        serviceCategory = UiCategory.LIFE,
        weChatHandoff = GymReservationHandoff.gym,
    )

    val poolReservation = OfficialLink(
        title = GymReservationHandoff.pool.title,
        subtitle = "唐岛湾 · 官方微信",
        url = GymReservationHandoff.POOL_INTRO_URL,
        icon = Icons.Filled.Pool,
        group = "生活",
        serviceCategory = UiCategory.LIFE,
        campus = PreferredCampus.TANGDAOWAN,
        weChatHandoff = GymReservationHandoff.pool,
    )

    val life: List<OfficialLink> = listOf(
        OfficialLink(
            title = "唐岛湾剩余电量",
            subtitle = "查询页",
            url = "http://dfcz.upc.edu.cn/ShiYou/Default.aspx",
            icon = Icons.Filled.Bolt,
            group = "生活",
            serviceId = TANGDAOWAN_POWER_SERVICE_ID,
            campus = PreferredCampus.TANGDAOWAN,
        ),
        OfficialLink(
            title = "古镇口电费",
            subtitle = "查询页",
            url = "https://gzkdf.upc.edu.cn/acmswx/index.aspx",
            icon = Icons.Filled.Bolt,
            group = "生活",
            serviceId = GUZHENKOU_POWER_SERVICE_ID,
            campus = PreferredCampus.GUZHENKOU,
        ),
        gymReservation,
        poolReservation,
    )

    val all: List<OfficialLink> = learning + life
    val serviceLinks: List<OfficialLink> = all - academicScores

    /**
     * 「服务」页（全部/分类）候选：按 [OfficialLink.serviceCategory] 归类。
     *
     * 图钉能力只由 [OfficialLink.serviceId] 是否存在决定，分类不要求可固定；
     * 体育馆、游泳馆预约等交接入口可以出现在校园生活目录但不出现在首页固定项。
     */
    fun serviceLinksFor(category: UiCategory): List<OfficialLink> =
        serviceLinks.filter { it.serviceCategory == category }

    /**
     * 「服务」页候选：常用校区为单一校区时，隐藏另一校区专属入口，保留通用入口。
     *
     * 已固定在首页的入口不因常用校区变化而消失，否则用户无法在服务页取消固定；
     * 未固定的条目仍按校区收敛。null（尚未读取）与 [PreferredCampus.ALL] 都原样
     * 返回；完整目录不经过这里。
     */
    fun visibleFor(
        links: List<OfficialLink>,
        preferredCampus: PreferredCampus?,
        keepServiceIds: Set<String> = emptySet(),
    ): List<OfficialLink> {
        if (preferredCampus == null || preferredCampus == PreferredCampus.ALL) return links
        return links.filter { link ->
            link.campus == null || link.campus == preferredCampus ||
                (link.serviceId != null && link.serviceId in keepServiceIds)
        }
    }

    fun browserIntent(link: OfficialLink): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    /**
     * 目录点击分发：普通外链交系统浏览器；带 [OfficialLink.weChatHandoff] 的入口
     * 交宿主 UI 展示交接说明，不自动复制名称、不自动启动微信。
     */
    fun activate(
        context: Context,
        link: OfficialLink,
        onWeChatHandoff: (OfficialLink) -> Unit,
    ) {
        if (link.weChatHandoff != null) onWeChatHandoff(link) else open(context, link)
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
