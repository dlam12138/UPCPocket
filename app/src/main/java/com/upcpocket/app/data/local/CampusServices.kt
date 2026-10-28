package com.upcpocket.app.data.local

/**
 * 校园服务**官方入口**目录。
 *
 * 这一份表是「校园服务」页的入口目录。它只做三件事：
 *  1. 告诉用户这个服务归哪个校区（唐岛湾 / 古镇口 / 两校区通用）；
 *  2. 说清打开它需要什么条件（校内网？统一认证登录？）；
 *  3. 交出学校服务地址；空闲教室进入原生查询，其余交外部浏览器或微信。
 *
 * ⚠️ 这里的 [Entry.title] / [Entry.scope] / [Entry.note] 会**直接显示在界面上**，
 * Compose 不解析 Markdown —— 不要在里面写 **加粗** 或反引号，否则星号会原样上屏
 * （真机验证时踩到过）。强调靠单独的 UI 组件（标签、颜色），不靠文本标记。
 *
 * 约束（与总体计划书一致）：
 *  - 不代填账号，不碰持久 Cookie/Token；图书馆登录只使用官方 CAS 网页，
 *    空闲教室使用单独的公开查询数据源，不访问其他受保护业务；
 *  - 地址一律是学校公布的服务入口，**不带任何 token / code / ticket 参数**；
 *  - 电费/校园卡目前仅为外部交接，不声称已经实现原生余额。
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

        /** 用户实测该入口依赖微信环境；外部浏览器跳转不保证可用 */
        WECHAT("需要微信打开"),

        /** 仅有页面地址，登录与网络要求尚未独立验证 */
        SCHOOL_PAGE("使用条件以学校页面为准"),
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
            note = "可在 App 内「导入 → 登录并同步学校课表」用官方登录读取整学期，先预览再写入；" +
                "也可导出 CSV 后导入。CSV 实际列名仍需与导出文件核对。App 不读取密码。",
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
            note = "在 App 内按校区、楼宇、日期和节次查询，不提供预约。临时调课、考试和现场开放情况以学校公告为准。",
            updatedNote = "2026-10-27 复核公开页面及正常查询返回；来源为总体计划书第 4 节台账。",
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
            note = "使用学校统一认证登录后，在 App 内查询馆区、选座、提交预约和取消；" +
                "不自动抢座、不轮询。部分馆区要求校园网，图形验证码启用时原生提交会停止并提示。",
            updatedNote = "地址来自总体计划书第 4 节；图书馆官网把该地址列为座位预约入口。" +
                "2026-10-27 核验公开前端启用 CAS 与会话签名。",
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
            subtitle = "余额查询 · 剩余电量",
            campus = Campus.TANGDAOWAN,
            access = Access.PUBLIC,
            url = "http://dfcz.upc.edu.cn/ShiYou/Default.aspx",
            // ⚠️ 只写能确认的事实。最初这里写过"照明与空调是两套独立表计、余额不能互抵、
            // 各有一个号"—— 那是上游 ZZUPocket 针对**郑州大学**的规则，总体计划书并未确认
            // UPC 也如此。未经学校来源确认的计费规则不该上界面（会直接引导用户填错号码），
            // 因此全部删除，改为把"以学校页面说明为准"讲清楚。
            scope = "只适用于唐岛湾校区宿舍。按学校页面自己的楼号与房间号查询，不要套用古镇口的入口。",
            note = "在 App 内按楼号＋房间号查询剩余电量，不需要登录，也没有充值或缴费记录。" +
                "房间号不保存；空调与照明是否分表以学校页面为准。",
            updatedNote = "地址来自总体计划书第 4 节（已去除 code 参数）。2026-10-28 核验：" +
                "页面缺 code 时 302 到微信 OAuth，带占位 code 即可拿到公开查询表单；" +
                "查询后返回“当前剩余电量 xx度”。",
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
            note = "本版仍打开学校页面：该校域名证书已过期（2026-07-11 到期），且页面不是" +
                "唐岛湾那套查询表单，App 不会绕过证书校验。证书修复或学校提供新入口后另行接入。",
            updatedNote = "地址来自总体计划书第 4 节；2026-10-24 校外匿名访问 HTTP 503；" +
                "2026-10-28 复核：证书链有效但已过期，忽略校验时 acmswx 返回 HTTP 500（不推断系统停用）。",
        ),
        Entry(
            id = "ecard",
            title = "校园卡余额",
            subtitle = "卡余额 · 待圈存",
            campus = Campus.BOTH,
            access = Access.CAS,
            url = "https://mb-xyk.upc.edu.cn/plat/",
            scope = "校园卡余额查询。账号、网络及打开环境等要求以学校页面说明为准。",
            note = "在 App 内用学校统一认证登录后，读取本人卡余额与待圈存，不做充值、绑定、改密或流水。" +
                "关掉“记住学校登录”或退出校园卡时清除令牌。",
            updatedNote = "2026-10-28 核验：平台前端余额页调用 /berserker-app/ykt/tsm/queryCard，" +
                "口径取自 frontInfo 的 ecardConfig。原微信专用地址 jhzf 只返回“无效token”，不再作为入口。",
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
    )

    /** 全部入口（按展示顺序） */
    val ALL: List<Entry> get() = LEARNING + LIFE

    /** 按校区筛选；[Campus.BOTH] 在校区页里始终显示 */
    fun forCampus(campus: Campus): List<Entry> =
        ALL.filter { it.campus == campus || it.campus == Campus.BOTH }

    /**
     * 这条入口是否属于"唐岛湾宿舍电费"—— 用来在界面上把两个极易混淆的入口标开。
     *
     * 唐岛湾与古镇口的电费入口地址不同，界面应明确区分校区。
     */
    fun isTangdaowanPower(entry: Entry): Boolean = entry.id == "power_tangdaowan"

    fun isGuzhenkouPower(entry: Entry): Boolean = entry.id == "power_guzhenkou"
}
