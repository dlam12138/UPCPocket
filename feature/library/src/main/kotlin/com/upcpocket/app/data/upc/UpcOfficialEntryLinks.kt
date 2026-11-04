package com.upcpocket.app.data.upc

/**
 * 从用户授权的六入口 HAR 和 2026-11-04 已登录 Chaoxing 会话中提取的稳定入口。
 *
 * 只保留学校/应用级标识：`fidEnc`、`mappId`、`seatId` 和 reservepc 的
 * `reserveId/resvAppId`。明确排除 `uid/enc/code/state/wfwEnc/wfw_token/wxAppid`
 * 等用户或一次性上下文。reservepc 两个租户的稳定入口已在已登录会话中重新打开，
 * 分别显示古镇口东区 8 个研讨间、求是图书馆 23 个研讨间；这不是原生查询，
 * 也不包含账号、Cookie 或 token。
 */
object UpcOfficialEntryLinks {
    private const val FID_ENC = "e32f8fbde51f9dba"

    const val SEAT =
        "https://office.chaoxing.com/front/third/apps/seat/index" +
            "?fidEnc=$FID_ENC&mappId=4197453"

    const val SEATENGINE =
        "https://office.chaoxing.com/front/third/apps/seatengine/index" +
            "?fidEnc=$FID_ENC&mappId=20853788&seatId=4258"

    /**
     * 古镇口东区研讨间（reservepc 租户 A）。
     *
     * `reserveId/resvAppId/mappId` 是应用级稳定标识；不含 `uid`、`enc`、`code`、
     * `state` 或 `wfwEnc`。在已登录 Chaoxing 会话中实测打开研讨室列表（8 项）。
     */
    const val RESERVE_GUZHENKOU =
        "https://reserve.chaoxing.com/front/web/apps/reservepc/index" +
            "?reserveId=13492&resvAppId=13492&fidEnc=$FID_ENC&mappId=10570653"

    /**
     * 求是图书馆研讨间（reservepc 租户 B）。
     *
     * 同一协议、独立租户；在已登录会话中实测打开研讨室列表（23 项）。
     */
    const val RESERVE_QIUSHI =
        "https://reserve.chaoxing.com/front/web/apps/reservepc/index" +
            "?reserveId=31841&resvAppId=31841&fidEnc=$FID_ENC&mappId=20875315"

    val chaoxingEntries: List<String> = listOf(
        SEAT,
        SEATENGINE,
        RESERVE_GUZHENKOU,
        RESERVE_QIUSHI,
    )

    /** 只允许固定学校应用主机；官方页面仍在系统浏览器中打开。 */
    fun isAllowedChaoxingHost(raw: String): Boolean {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" &&
            (host == "office.chaoxing.com" || host == "reserve.chaoxing.com")
    }
}
