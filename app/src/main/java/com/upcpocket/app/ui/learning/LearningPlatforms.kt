package com.upcpocket.app.ui.learning

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.ui.graphics.vector.ImageVector
import com.upcpocket.app.ui.official.OfficialLink

/**
 * 「学习平台」聚合页的一组入口。
 *
 * 同一平台需要附带第二个网页时放在同一组，例如知到与惟真学堂、石大云课堂与
 * 校外访问帮助；页面按组呈现，不拆分登录或课程能力。
 */
data class LearningPlatformSection(val links: List<OfficialLink>)

/**
 * 已核对的 UPC 学习平台公开网页（来源：docs/upc/官方入口.md）。
 *
 * 只做聚合导航：由系统浏览器打开固定 HTTPS 地址，不预填账号、不代理登录、
 * 不读取课程或任务，也不依赖学校账号和业务插件。聚合入口自身使用稳定 ID
 * 参与现有首页图钉机制；各平台条目不再单独固定。
 */
object LearningPlatformCatalog {
    /** 聚合入口的稳定首页固定 ID；只新增，不随标题或平台调整重命名。 */
    const val SERVICE_ID = "official.learning_platforms"
    const val TITLE = "学习平台"
    const val SUBTITLE = "四个平台 · 网页登录"

    val icon: ImageVector = Icons.Outlined.School

    val zhidao = OfficialLink(
        title = "知到（智慧树）",
        subtitle = "智慧树 · 网页登录",
        url = "https://passport.zhihuishu.com/login",
        icon = Icons.Outlined.School,
        group = TITLE,
    )

    val weizhen = OfficialLink(
        title = "惟真学堂",
        subtitle = "石大专属 · 智慧树",
        url = "https://upcwzedu.zhihuishu.com/",
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        group = TITLE,
    )

    val yuketang = OfficialLink(
        title = "雨课堂",
        subtitle = "网页版",
        url = "https://www.yuketang.cn/web",
        icon = Icons.Outlined.Public,
        group = TITLE,
    )

    val ketangpai = OfficialLink(
        title = "课堂派",
        subtitle = "通用入口",
        url = "https://www.ketangpai.com/",
        icon = Icons.Filled.Apps,
        group = TITLE,
    )

    val cloudClassroom = OfficialLink(
        title = "石大云课堂",
        subtitle = "学校登录",
        url = "https://learn.upc.edu.cn/meol/index.do",
        icon = Icons.Outlined.School,
        group = TITLE,
    )

    val webVpnHelp = OfficialLink(
        title = "校外访问帮助",
        subtitle = "WebVPN 说明",
        url = "https://appnic.upc.edu.cn/_s457/2026/0107/c25391a278495/page.psp",
        icon = Icons.Outlined.Info,
        group = TITLE,
    )

    /** 四个平台各一组；组内第二个网页随平台一起展示。 */
    val sections: List<LearningPlatformSection> = listOf(
        LearningPlatformSection(listOf(zhidao, weizhen)),
        LearningPlatformSection(listOf(yuketang)),
        LearningPlatformSection(listOf(ketangpai)),
        LearningPlatformSection(listOf(cloudClassroom, webVpnHelp)),
    )

    val all: List<OfficialLink> = sections.flatMap { it.links }

    /**
     * 服务页搜索：命中「学习平台」、任一平台名或常用别名。
     *
     * 别名只用于本机检索，不改变条目标题；空查询视为匹配。
     */
    fun matches(query: String): Boolean {
        val term = query.trim()
        if (term.isEmpty()) return true
        return SEARCH_TERMS.any { it.contains(term, ignoreCase = true) } ||
            all.any {
                it.title.contains(term, ignoreCase = true) ||
                    it.subtitle.contains(term, ignoreCase = true)
            }
    }

    private val SEARCH_TERMS: List<String> = listOf(
        TITLE,
        "知到",
        "智慧树",
        "惟真",
        "惟真学堂",
        "雨课堂",
        "课堂派",
        "石大",
        "云课堂",
        "石大云课堂",
        "校外访问",
        "WebVPN",
        "VPN",
    )
}
