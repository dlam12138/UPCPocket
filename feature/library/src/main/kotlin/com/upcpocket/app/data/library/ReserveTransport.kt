package com.upcpocket.app.data.library

/**
 * 预约提交的可注入传输边界（仅用于定向单测验证「提交前/提交时会话上下文」）。
 *
 * 生产路径由 [LibrarySeatRepository] 内部用同一套 `request`/签名实现；注入 fake 可挂起
 * 验证码预检、在预检期间切换会话代次/token，再断言：
 *  - 过期上下文：0 次 POST、不清理新会话、不在新代次登记防重键；
 *  - 同一会话：1 次 POST、重复仍拒绝；
 *  - 取消仍保持取消语义（不经过此处）。
 * 不扩大认证协议、不真实预约、不接触账密。
 */
internal interface ReserveTransport {
    /** 读取是否需要验证码（POST 前预检）；可挂起。 */
    suspend fun captchaEnabled(token: String): Boolean

    /** 真正发出预约 POST；token 必须来自进入 reserve 时捕获的会话。 */
    suspend fun postReservation(
        token: String,
        date: String,
        seatId: String,
        startId: String,
        endId: String,
        user: LibraryUser,
    ): String
}
