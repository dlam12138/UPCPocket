package com.upcpocket.app.data.upc

/**
 * 从用户授权的六入口 HAR 中提取的稳定学校/租户入口。
 *
 * 只保留 fidEnc/mappId/seatId/reserveId/resvAppId/appId 等入口标识；
 * 明确排除 uid/enc/code/state/wfwEnc/wfw_token 等一次性或会话值。
 * 这些地址仍需要学校正常登录，不包含任何账号、Cookie 或 token。
 */
object UpcOfficialEntryLinks {
    const val SEAT = "https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453"

    const val SEATENGINE = "https://office.chaoxing.com/front/third/apps/seatengine/index?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258"

    const val RESERVEPC_A = "https://reserve.chaoxing.com/front/web/apps/reservepc/index?reserveId=13492&resvAppId=13492&fidEnc=e32f8fbde51f9dba&appId=dc5127a1ae67437dbfa83600b9924451&mappId=10570653"

    const val RESERVEPC_B = "https://reserve.chaoxing.com/front/web/apps/reservepc/index?reserveId=31841&resvAppId=31841&fidEnc=e32f8fbde51f9dba&appId=6c23ed5e27264513ad1f8f1c4515b7e9&mappId=20875315"
}
