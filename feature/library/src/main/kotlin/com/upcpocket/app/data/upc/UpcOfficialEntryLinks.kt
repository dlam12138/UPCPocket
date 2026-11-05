package com.upcpocket.app.data.upc

/**
 * Chaoxing 座位入口。
 *
 * 只保留学校/应用级标识：`fidEnc`、`mappId`、`seatId`；不含账号、Cookie、token
 * 或任何一次性上下文。研讨间/空间预约入口已按用户决定撤下，不在此对象保留。
 */
object UpcOfficialEntryLinks {
    private const val FID_ENC = "e32f8fbde51f9dba"

    const val SEAT =
        "https://office.chaoxing.com/front/third/apps/seat/index" +
            "?fidEnc=$FID_ENC&mappId=4197453"

    const val SEATENGINE =
        "https://office.chaoxing.com/front/third/apps/seatengine/index" +
            "?fidEnc=$FID_ENC&mappId=20853788&seatId=4258"

    val chaoxingEntries: List<String> = listOf(SEAT, SEATENGINE)

    /** 只允许固定座位主机；官方页面仍在系统浏览器中打开。 */
    fun isAllowedChaoxingHost(raw: String): Boolean {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && host == "office.chaoxing.com"
    }
}
