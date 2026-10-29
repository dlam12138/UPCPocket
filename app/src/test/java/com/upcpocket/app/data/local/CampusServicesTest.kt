package com.upcpocket.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园服务入口目录的约束测试。
 *
 * 这一份表是 App 里**唯一**会接触外部世界的东西（交给浏览器打开）。
 * 所以测试要钉住两类边界：
 *
 *  1. **安全**：地址必须是 http(s)、不带任何凭据参数（token/code/ticket…）；
 *     两个校区的电费必须是**不同**的系统；
 *  2. **表达**：每个入口都必须写清适用范围与适用条件，唐岛湾/古镇口不能混。
 */
class CampusServicesTest {

    @Test
    fun `服务列表不再展示两个部门导航`() {
        assertTrue(CampusServices.ALL.none {
            it.id in setOf("nic", "gzk") || it.title in setOf("信息化建设处", "古镇口校区综合办公室")
        })
    }

    @Test
    fun `校园卡提供本人余额与流水但不充值`() {
        val card = CampusServices.ALL.single { it.id == "ecard" }
        assertEquals("校园卡余额与流水", card.title)
        assertEquals("http://jhzf.upc.edu.cn:20081/wechat/query/balance.html", card.url)
        assertEquals(CampusServices.Access.PUBLIC, card.access)
        assertTrue(card.subtitle.contains("流水"))
        assertTrue(card.note.contains("流水"))
        assertTrue(card.note.contains("不提供充值"))
        assertTrue(card.scope.contains("过渡余额"))
    }

    @Test
    fun `唐岛湾电费是无需登录的公开查询_并记录真实入口条件`() {
        val power = CampusServices.ALL.single { CampusServices.isTangdaowanPower(it) }
        // 页面对缺 code 的请求会 302 到微信 OAuth，但带占位 code 就能拿到公开查询表单；
        // 因此它既不是“必须微信”，也不是“需要登录”，如实标成公网可访问。
        assertEquals(CampusServices.Access.PUBLIC, power.access)
        assertTrue(power.note.contains("不需要登录"))
        assertTrue(power.note.contains("房间号"))
        assertTrue(power.updatedNote.contains("302"))
        assertTrue(power.updatedNote.contains("当前剩余电量"))
        // 旧的“必须微信打开”结论已被本轮核验推翻，不能留在界面文案里。
        assertFalse(power.note.contains("需要微信打开"))
    }

    @Test
    fun `每个入口都是可打开的 http 或 https 地址`() {
        CampusServices.ALL.forEach { entry ->
            assertTrue(
                "非 http(s) 地址：${entry.id} → ${entry.url}",
                entry.url.startsWith("https://") || entry.url.startsWith("http://"),
            )
            assertTrue("地址含空白：${entry.id}", !entry.url.any { it.isWhitespace() })
        }
    }

    @Test
    fun `入口地址不带任何凭据参数`() {
        val forbidden = listOf("token=", "code=", "ticket=", "session=", "auth=", "key=")
        CampusServices.ALL.forEach { entry ->
            val q = entry.url.substringAfter('?', "")
            forbidden.forEach { param ->
                assertFalse(
                    "入口 ${entry.id} 的地址带凭据参数 $param：${entry.url}",
                    q.contains(param, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `入口 id 唯一`() {
        val ids = CampusServices.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `电费入口能被独立筛出来_不依赖它属于哪个分组`() {
        // 回归测试：界面把两个电费入口置顶时，曾经错误地从 LEARNING 里筛，
        // 而电费其实属于 LIFE —— 结果置顶那组整块消失（真机验证时踩到）。
        val powers = CampusServices.ALL.filter {
            CampusServices.isTangdaowanPower(it) || CampusServices.isGuzhenkouPower(it)
        }
        assertEquals(2, powers.size)
        assertEquals(
            setOf("power_tangdaowan", "power_guzhenkou"),
            powers.map { it.id }.toSet(),
        )

        // 两个电费入口都不在 LEARNING 里；如果以后有人把它们挪进去，
        // 上面那种"从 LEARNING 筛"的写法就会重新变得"看起来能用"——
        // 所以这里明确钉住"按 id 判断，不按分组判断"。
        assertTrue(CampusServices.LEARNING.none { it.id.startsWith("power_") })
        assertTrue(CampusServices.LIFE.any { it.id == "power_tangdaowan" })
        assertTrue(CampusServices.LIFE.any { it.id == "power_guzhenkou" })
    }

    @Test
    fun `除了电费之外每个入口都恰好出现在一个分组里`() {
        val learning = CampusServices.LEARNING.map { it.id }
        val life = CampusServices.LIFE.map { it.id }
        // 两组之间没有重复 id（否则界面上会出现两张一模一样的卡）
        assertTrue("分组之间有重复入口", learning.intersect(life.toSet()).isEmpty())
        assertEquals(CampusServices.ALL.size, (learning + life).distinct().size)
    }

    @Test
    fun `电费说明只说已核实的事实_不夹带未确认的计费规则`() {
        // 回归测试（复核报告 P2-3）：最初这里写过"照明与空调是两套独立表计、余额不能互抵、
        // 各有一个号"—— 那是上游 ZZUPocket 针对**郑州大学**的规则，总体计划书并未确认
        // UPC 也如此。这类断言会直接引导用户去填错的房间号，属于**不能出现**的内容。
        val unverified = listOf(
            "独立表计", "两套表计", "不能互抵", "互不相通",
            "照明号", "空调号", "加前导", "各有一个号",
        )
        CampusServices.ALL
            .filter { CampusServices.isTangdaowanPower(it) || CampusServices.isGuzhenkouPower(it) }
            .forEach { entry ->
                val blob = entry.title + entry.subtitle + entry.scope + entry.note + entry.updatedNote
                unverified.forEach { phrase ->
                    assertFalse(
                        "电费入口 ${entry.id} 出现未核实的规则「$phrase」：${entry.scope}",
                        blob.contains(phrase),
                    )
                }
            }
    }

    @Test
    fun `电费说明把判断权交回学校页面`() {
        // 删掉未经核实的断言之后，必须留下"以学校页面为准"这句指引，
        // 否则用户不知道该按什么填房间号。
        val tdw = CampusServices.ALL.first { CampusServices.isTangdaowanPower(it) }
        assertTrue(
            "唐岛湾电费缺少「以学校页面为准」的引导",
            tdw.scope.contains("学校页面"),
        )
    }

    @Test
    fun `电费入口记录了核对依据`() {
        CampusServices.ALL
            .filter { CampusServices.isTangdaowanPower(it) || CampusServices.isGuzhenkouPower(it) }
            .forEach { entry ->
                assertTrue(
                    "${entry.id} 的核对依据没有指向计划书：${entry.updatedNote}",
                    entry.updatedNote.contains("总体计划书"),
                )
            }
    }

    @Test
    fun `两校区电费是两个不同的系统`() {
        val tdw = CampusServices.ALL.first { CampusServices.isTangdaowanPower(it) }
        val gzk = CampusServices.ALL.first { CampusServices.isGuzhenkouPower(it) }

        assertEquals(CampusServices.Campus.TANGDAOWAN, tdw.campus)
        assertEquals(CampusServices.Campus.GUZHENKOU, gzk.campus)
        // 必须是不同域名：这是"两套独立系统"的事实体现
        assertTrue(
            "两个校区的电费不该指向同一个域名",
            domainOf(tdw.url) != domainOf(gzk.url),
        )
    }

    @Test
    fun `电费入口的适用范围写明了校区`() {
        CampusServices.ALL
            .filter { CampusServices.isTangdaowanPower(it) || CampusServices.isGuzhenkouPower(it) }
            .forEach { entry ->
                assertTrue(
                    "电费入口 ${entry.id} 的适用范围没写校区：${entry.scope}",
                    entry.scope.contains("唐岛湾") || entry.scope.contains("古镇口"),
                )
                // 唐岛湾已核实为无需登录的公开查询；古镇口仍需校园网/浏览器条件。
                // 这里只钉住“条件必须如实写出来”，不把某一项钉死成必须登录。
                assertTrue(
                    "电费入口 ${entry.id} 没写使用条件",
                    entry.note.isNotBlank() && entry.access != CampusServices.Access.SCHOOL_PAGE,
                )
            }
    }

    @Test
    fun `每个入口都有适用范围与核对依据`() {
        CampusServices.ALL.forEach { entry ->
            assertTrue("${entry.id} 缺标题", entry.title.isNotBlank())
            assertTrue("${entry.id} 缺副标题", entry.subtitle.isNotBlank())
            assertTrue("${entry.id} 缺适用范围", entry.scope.length >= 10)
            assertTrue("${entry.id} 缺核对依据", entry.updatedNote.isNotBlank())
        }
    }

    @Test
    fun `按校区筛选时通用入口始终出现`() {
        listOf(CampusServices.Campus.TANGDAOWAN, CampusServices.Campus.GUZHENKOU).forEach { campus ->
            val list = CampusServices.forCampus(campus)
            assertTrue("$campus 里应有通用入口", list.any { it.campus == CampusServices.Campus.BOTH })
            assertTrue("$campus 里应包含本校区入口", list.any { it.campus == campus })
            // 不该混进另一个校区
            val other = if (campus == CampusServices.Campus.TANGDAOWAN) {
                CampusServices.Campus.GUZHENKOU
            } else {
                CampusServices.Campus.TANGDAOWAN
            }
            assertTrue(
                "$campus 的列表里混进了 $other 的入口",
                list.none { it.campus == other },
            )
        }
    }

    @Test
    fun `轻量版不再提供课表入口`() {
        // 课表功能整体删除后，入口目录里不能残留任何教务/课表跳转，
        // 否则用户仍可以从首页绕回已删除的页面。
        assertTrue(CampusServices.ALL.none { it.id == "schedule" })
        assertTrue(
            CampusServices.ALL.none {
                it.title.contains("课表") || it.url.contains("weekschedule")
            },
        )
    }

    @Test
    fun `空闲教室指向学校的公开查询入口`() {
        val classroom = CampusServices.ALL.first { it.id == "classroom" }
        assertTrue(domainOf(classroom.url).endsWith("upc.edu.cn"))
        assertEquals(CampusServices.Access.PUBLIC, classroom.access)
    }

    @Test
    fun `没有一条入口自称官方授权或校方背书`() {
        val suspicious = listOf("官方合作", "学校认证", "校方授权", "官方指定")
        CampusServices.ALL.forEach { entry ->
            val blob = entry.title + entry.subtitle + entry.scope + entry.note
            suspicious.forEach { word ->
                assertFalse("入口 ${entry.id} 出现越权表述：$word", blob.contains(word))
            }
        }
    }

    private fun domainOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
}
