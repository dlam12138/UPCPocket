package com.upcpocket.app.data.power

/**
 * 电费入口的校区元数据。
 *
 * 唐岛湾在插件内用原生查询；古镇口仍交回学校页面。两个校区是不同地址、不同系统，
 * 不能把唐岛湾的楼号/房间号规则套到古镇口，也不能为了“统一体验”绕过证书问题。
 */
object PowerCampusEntries {

    const val TANGDAOWAN_URL = "http://dfcz.upc.edu.cn/ShiYou/Default.aspx"

    const val GUZHENKOU_URL = "https://gzkdf.upc.edu.cn/acmswx/index.aspx"

    const val GUZHENKOU_SCOPE =
        "只适用于古镇口校区。它和唐岛湾是两个不同地址的系统，" +
            "请按本页面自己的说明选楼与填房间号，不要套用另一个校区的入口。"

    const val GUZHENKOU_NOTE =
        "本版仍打开学校页面：该校域名证书已过期（2026-07-11 到期），且页面不是" +
            "唐岛湾那套查询表单，App 不会绕过证书校验。证书修复或学校提供新入口后另行接入。"

    const val GUZHENKOU_UPDATED_NOTE =
        "地址来自总体计划书第 4 节；2026-10-24 校外匿名访问 HTTP 503；" +
            "2026-10-28 复核：证书链有效但已过期，忽略校验时 acmswx 返回 HTTP 500（不推断系统停用）。"
}
