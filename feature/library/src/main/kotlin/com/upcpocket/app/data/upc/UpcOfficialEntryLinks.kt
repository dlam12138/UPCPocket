package com.upcpocket.app.data.upc

/**
 * 从用户授权的六入口 HAR 中提取的稳定学校/应用入口。
 *
 * 只保留 seat/seatengine 的 fidEnc/mappId/seatId；明确排除 uid/enc/code/state/
 * wfwEnc/wfw_token 等一次性或会话值。reservepc 的参数入口在登录后实测落到座位页，
 * 不作为可用研讨间入口暴露。这些地址仍需要学校正常登录，不包含账号、Cookie 或 token。
 */
object UpcOfficialEntryLinks {
    const val SEAT = "https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453"

    const val SEATENGINE = "https://office.chaoxing.com/front/third/apps/seatengine/index?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258"
}
