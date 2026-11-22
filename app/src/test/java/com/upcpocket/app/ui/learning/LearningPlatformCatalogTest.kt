package com.upcpocket.app.ui.learning

import android.app.Application
import android.content.Intent
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.official.OfficialWebCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class LearningPlatformCatalogTest {

    @Test
    fun `four sections keep the verified platform and helper links`() {
        val expected = listOf(
            "知到（智慧树）" to "https://passport.zhihuishu.com/login",
            "惟真学堂" to "https://upcwzedu.zhihuishu.com/",
            "雨课堂" to "https://www.yuketang.cn/web",
            "课堂派" to "https://www.ketangpai.com/",
            "石大云课堂" to "https://learn.upc.edu.cn/meol/index.do",
            "校外访问帮助" to
                "https://appnic.upc.edu.cn/_s457/2026/0107/c25391a278495/page.psp",
        )

        assertEquals(expected, LearningPlatformCatalog.all.map { it.title to it.url })
        assertEquals(listOf(2, 1, 1, 2), LearningPlatformCatalog.sections.map { it.links.size })
        assertEquals(
            listOf("知到（智慧树）", "惟真学堂"),
            LearningPlatformCatalog.sections.first().links.map { it.title },
        )
        assertEquals(
            listOf("石大云课堂", "校外访问帮助"),
            LearningPlatformCatalog.sections.last().links.map { it.title },
        )
        assertEquals("通用入口", LearningPlatformCatalog.ketangpai.subtitle)
        assertTrue(LearningPlatformCatalog.all.all { it.url.startsWith("https://") })
        assertTrue(LearningPlatformCatalog.all.all { it.serviceId == null })
        assertTrue(LearningPlatformCatalog.all.all { it.weChatHandoff == null })
        assertTrue(LearningPlatformCatalog.all.all { it.campus == null })

        // 平台条目只在聚合页展示，不重复进入「校园官方网页」完整目录。
        assertTrue(
            OfficialWebCatalog.all.none { official ->
                LearningPlatformCatalog.all.any { it.url == official.url }
            },
        )
    }

    @Test
    fun `search matches platform names and aliases`() {
        listOf(
            "",
            "学习平台",
            "平台",
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
            "webvpn",
            "VPN",
        ).forEach { term ->
            assertTrue("应匹配：$term", LearningPlatformCatalog.matches(term))
        }
        assertTrue(LearningPlatformCatalog.matches(" 智慧树 "))

        listOf("图书馆", "电费", "校园卡", "体育馆").forEach { term ->
            assertFalse("不应匹配：$term", LearningPlatformCatalog.matches(term))
        }
    }

    @Test
    fun `aggregate pin id is stable host owned and opt in`() {
        assertEquals("official.learning_platforms", LearningPlatformCatalog.SERVICE_ID)
        assertEquals("学习平台", LearningPlatformCatalog.TITLE)
        assertFalse(LearningPlatformCatalog.SERVICE_ID in UiPrefs.DEFAULT_PINNED_SERVICES)
        assertTrue(
            OfficialWebCatalog.all.none {
                it.serviceId == LearningPlatformCatalog.SERVICE_ID
            },
        )
    }

    @Test
    fun `every link opens a fixed browsable https intent without extras`() {
        LearningPlatformCatalog.all.forEach { link ->
            val intent = OfficialWebCatalog.browserIntent(link)

            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertEquals(link.url, intent.dataString)
            assertEquals("https", intent.data?.scheme)
            assertNull(intent.getPackage())
            assertNull(intent.component)
            assertNull(intent.extras)
        }
    }
}
