package com.upcpocket.app.data.nspace

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * nspace 官方网页入口。
 *
 * 只构造学校公开前端自己的 `#/detail` 路由；只带房间 id 与日期，不带用户 token、
 * 一次性 uid/enc 或学校会话。登录/预约/取消仍由官方页面处理。
 */
object NspaceOfficialLinks {
    const val HOST = "libspace.upc.edu.cn"
    const val BASE = "https://libspace.upc.edu.cn/nspace/"

    fun roomDetailUrl(roomId: String, date: String): String {
        require(roomId.isNotBlank()) { "roomId 不能为空" }
        require(date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) { "日期格式不合法" }
        return "https://$HOST/nspace/#/detail?id=${encode(roomId)}" +
            "&selectDate=${encode(date)}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
