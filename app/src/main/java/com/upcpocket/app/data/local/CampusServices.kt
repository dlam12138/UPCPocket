package com.upcpocket.app.data.local

/**
 * 校园服务**官方入口**目录。
 *
 * 这一份表就是「校园服务」页的全部数据源。它只做三件事：
 *  1. 告诉用户这个服务归哪个校区（唐岛湾 / 古镇口 / 两校区通用）；
 *  2. 说清打开它需要什么条件（校内网？统一认证登录？）；
 *  3. 交出一个**官方域名**的 HTTPS/HTTP 地址，由系统浏览器打开。
 *
 * ⚠️ 这里的 [Entry.title] / [Entry.scope] / [Entry.note] 会**直接显示在界面上**，
 * Compose 不解析 Markdown —— 不要在里面写 **加粗** 或反引号，否则星号会原样上屏
 * （真机验证时踩到过）。强调靠单独的 UI 组件（标签、颜色），不靠文本标记。
 *
 * 约束（与总体计划书一致）：
 *  - 不内嵌 WebView，不读取页面内容，不代填账号，不碰 Cookie/Token ——
 *    登录发生在用户自己的浏览器里，App 全程不知道发生了什么；
 *  - 地址一律是学校公布的服务入口，**不带任何 token / code / ticket 参数**；
 *  - 这里是"导航"，不是"数据接入"：本应用没有、也不会为这些入口实现任何解析器。
 *
 * ⚠️ 入口会变。改了学校入口之后同步更新本文件，并在 [Entry.updatedNote] 里写明依据。
 */
object CampusServices {

    /** 校区。用于分组展示，并解释"为什么这个入口在校外打不开"。 */
    enum class Campus(val label: String) {
        TANGDAOWAN("唐岛湾校区"),
        GUZHENKOU("古镇口校区"),
        BOTH("两校区通用"),
    }

    /** 打开入口前需要知道的条件（展示成一行小字，不该让人点进去才发现打不开） */
    enum class Access(val label: String) {
        /** 公网可访问，通常不需要登录 */
        PUBLIC("公网可访问"),

        /** 需要统一身份认证（数字石大 / CAS）。App 不代填，跳过去由用户自己登录 */
        CAS("需统一认证登录"),

        /** 校园网内才稳定可用；校外可能超时或 503 */
        CAMPUS_NETWORK("建议校园网"),

        /** 既需要登录、也建议在校园网内 */
        BOTH("需登录 · 建议校园网"),
    }

    /**
     * 一条官方入口。
     *
     * [url] 必须在 `http(s)://` 下，且**不含** query 中的凭据参数；
     * [scope] 写清这个入口在 UPC 语境下的适用范围，而不是一句空泛的简介。
     */
    data class Entry(
        val id: String,
        val title: String,
        val subtitle: String,
        val campus: Campus,
        val access: Access,
        val url: String,
        /** 适用范围：这个入口对谁、在哪个校区、办什么事 */
        val scope: String,
        /** 已知的使用限制或注意事项；没有就写空串 */
        val note: String,
        /**
         * 依据与核对日期。入口地址会变，留下"什么时候根据什么核对的"比一句"以官方为准"有用。
         */
        val updatedNote: String,
    )

    /** 学习类入口 */
    val LEARNING: List<Entry> = listOf(
        Entry(
            id = "schedule",
            title = "课表（教务）",
            subtitle = "周课表 · 教学周",
            campus = Campus.BOTH,
            access = Access.CAS,
            url = "https://app.upc.edu.cn/site/weekschedule/index",
            scope = "两校区本科生与研究生的本人课表入口。唐岛湾、古镇口共用同一套教务系统，" +
                "课表里的上课地点会写明校区或楼名。",
            note = "本 App 不读取这里的课表；要把它变成可离线看的课表，请用「课表 → 手动补录」逐门添加。" +
                "打开后需要先在学校的统一身份认证里登录。",
            updatedNote = "地址来自《UPC Pocket 总体计划》v0.1 第 4 节的服务入口台账（核对于 2026-10-24）。",
        ),
        Entry(
            id = "classroom",
            title = "空闲教室",
            subtitle = "按楼看哪几节没课",
            campus = Campus.BOTH,
            access = Access.PUBLIC,
            url = "https://app.upc.edu.cn/freeclass/wap/default/all",
            scope = "两校区教学楼的分时占用查询。唐岛湾与古镇口的教学楼都在同一入口里，进入后按楼宇筛选。",
            note = "匿名可打开不代表数据实时；考试周、临时调课期间以门口公告为准。",
            updatedNote = "地址来自总体计划书第 4 节台账；2026-10-24 匿名访问返回 HTTP 200。",
        ),
        Entry(
            id = "library_seat",
            title = "图书馆座位预约",
            subtitle = "座位 · 研讨间",
            campus = Campus.BOTH,
            access = Access.BOTH,
            url = "https://libspace.upc.edu.cn/libseat/",
            scope = "两校区图书馆的座位与研讨间预约。馆区覆盖范围随图书馆系统调整，" +
                "古镇口西区求是图书馆已接入较新的智慧座位系统。",
            note = "需要登录，且部分馆区要求校园网。App 不做自动抢座、不轮询座位状态。",
            updatedNote = "地址来自总体计划书第 4 节；图书馆官网把该地址列为座位预约入口。",
        ),
        Entry(
            id = "library",
            title = "图书馆主页",
            subtitle = "检索 · 借阅 · 数据库",
            campus = Campus.BOTH,
            access = Access.PUBLIC,
            url = "https://library.upc.edu.cn/",
            scope = "图书馆总入口：馆藏检索、数据库、开馆时间、两校区馆舍信息。",
            note = "数据库等资源通常需要校园网或登录后使用。",
            updatedNote = "学校图书馆官网首页。",
        ),
    )

    /** 生活与办事类入口 */
    val LIFE: List<Entry> = listOf(
        Entry(
            id = "power_tangdaowan",
            title = "宿舍电费 · 唐岛湾",
            subtitle = "唐岛湾宿舍电费查询",
            campus = Campus.TANGDAOWAN,
            access = Access.BOTH,
            url = "http://dfcz.upc.edu.cn/ShiYou/Default.aspx",
            // ⚠️ 只写能确认的事实。最初这里写过"照明与空调是两套独立表计、余额不能互抵、
            // 各有一个号"—— 那是上游 ZZUPocket 针对**郑州大学**的规则，总体计划书并未确认
            // UPC 也如此。未经学校来源确认的计费规则不该上界面（会直接引导用户填错号码），
            // 因此全部删除，改为把"以学校页面说明为准"讲清楚。
            scope = "只适用于唐岛湾校区宿舍。具体怎么选楼栋、填哪个房间号、有几个表计，" +
                "都以学校页面上的说明为准。",
            note = "原入口由学校系统带 code 参数跳转；这里只保留去掉 code 后的公开地址，" +
                "登录、选楼与选房都在学校页面里完成。本 App 不保存房间号与余额。",
            updatedNote = "地址来自总体计划书第 4 节（已去除 code 参数）。注意该站是 HTTP，" +
                "计划书记录「去除参数后未得到可靠的有效业务响应」。",
        ),
        Entry(
            id = "power_guzhenkou",
            title = "宿舍电费 · 古镇口",
            subtitle = "古镇口公寓电费",
            campus = Campus.GUZHENKOU,
            access = Access.BOTH,
            url = "https://gzkdf.upc.edu.cn/acmswx/index.aspx",
            // 两个校区是**两个不同域名/不同系统**，这一点从地址本身即可确认（也见计划书第 4 节）。
            // 但"账户/余额互不相通"只是合理推断，所以写成"按各自页面办理"而不做断言。
            scope = "只适用于古镇口校区。它和唐岛湾是两个不同地址的系统，" +
                "请按本页面自己的说明选楼与填房间号，不要套用另一个校区的入口。",
            note = "校外匿名访问曾返回 503，通常需要先连校园网再打开；离开校园网时请以学校现场公告为准。",
            updatedNote = "地址来自总体计划书第 4 节；2026-10-24 校外匿名访问为 HTTP 503。",
        ),
        Entry(
            id = "ecard",
            title = "校园卡",
            subtitle = "余额 · 消费流水",
            campus = Campus.BOTH,
            access = Access.CAS,
            url = "https://i.upc.edu.cn/",
            scope = "统一身份认证后进入「数字石大」，再从门户里的校园卡应用查余额与流水。" +
                "两校区共用同一张校园卡。",
            note = "App 只做入口跳转，不查询、不保存任何卡号、余额或流水；不做充值、转账。",
            updatedNote = "数字石大门户是学校统一入口；校园卡应用挂在其内部。",
        ),
        Entry(
            id = "portal",
            title = "数字石大",
            subtitle = "统一门户 · 办事大厅",
            campus = Campus.BOTH,
            access = Access.CAS,
            url = "https://i.upc.edu.cn/",
            scope = "学校的统一门户：两校区通用的办事、通知、各业务系统入口都在这里。",
            note = "App 不接入 CAS，不做单点登录代理；账号密码只输在学校自己的页面上。",
            updatedNote = "学校门户地址；未登录访问会跳转统一认证。",
        ),
        Entry(
            id = "nic",
            title = "信息化建设处",
            subtitle = "网络与信息服务",
            campus = Campus.BOTH,
            access = Access.PUBLIC,
            url = "https://nic.upc.edu.cn/",
            scope = "校园网、统一认证、信息系统集成的归口部门；服务指南与咨询电话在这里。",
            note = "遇到统一认证、校园网问题优先看这里，而不是找一个第三方客户端。",
            updatedNote = "学校信息化建设处官网。",
        ),
        Entry(
            id = "gzk",
            title = "古镇口校区综合办公室",
            subtitle = "校区服务 · 班车",
            campus = Campus.GUZHENKOU,
            access = Access.PUBLIC,
            url = "https://gzk.upc.edu.cn/",
            scope = "古镇口校区的服务指南、班车与校区通知。唐岛湾的同学用不到。",
            note = "",
            updatedNote = "学校古镇口校区综合办公室官网。",
        ),
    )

    /** 全部入口（按展示顺序） */
    val ALL: List<Entry> get() = LEARNING + LIFE

    /** 按校区筛选；[Campus.BOTH] 在校区页里始终显示 */
    fun forCampus(campus: Campus): List<Entry> =
        ALL.filter { it.campus == campus || it.campus == Campus.BOTH }

    /**
     * 这条入口是否属于"唐岛湾宿舍电费"—— 用来在界面上把两个极易混淆的入口标开。
     *
     * 唐岛湾与古镇口是两套互不相通的电费系统，用户在校园里最常犯的错就是拿错校区。
     */
    fun isTangdaowanPower(entry: Entry): Boolean = entry.id == "power_tangdaowan"

    fun isGuzhenkouPower(entry: Entry): Boolean = entry.id == "power_guzhenkou"
}
