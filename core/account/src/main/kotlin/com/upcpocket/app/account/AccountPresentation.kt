package com.upcpocket.app.account

import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus

/** 「更多」入口与设置页共用的脱敏状态文案；不包含账号、密码或异常原文。 */
fun SchoolAccountState.summaryText(): String = when (status) {
    SchoolAccountStatus.LOADING -> "正在读取本机账号…"
    SchoolAccountStatus.NOT_CONFIGURED -> "未设置；保存后仅在本机加密存储"
    SchoolAccountStatus.SAVED_UNVERIFIED ->
        "已保存，尚未验证${maskedAccount?.let { " · $it" }.orEmpty()}"
    SchoolAccountStatus.NEEDS_RECONFIGURATION -> "本机凭据无法读取，需要重新保存"
    SchoolAccountStatus.SWITCHING -> "正在切换账号并清理旧数据"
    SchoolAccountStatus.UPDATING -> "正在更新凭据并清理旧会话"
    SchoolAccountStatus.REMOVING -> "正在删除账号并清理个人数据"
}

fun SchoolAccountErrorCode.userMessage(): String = when (this) {
    SchoolAccountErrorCode.INVALID_ACCOUNT -> "学校账号格式无效，请检查后重试。"
    SchoolAccountErrorCode.INVALID_PASSWORD -> "密码不能为空，且不能超过 512 个字符。"
    SchoolAccountErrorCode.NOT_CONFIGURED -> "本机尚未保存学校账号。"
    SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE -> "本机凭据无法读取，需要重新保存。"
    SchoolAccountErrorCode.CREDENTIAL_CORRUPTED -> "本机凭据格式无效，需要重新保存。"
    SchoolAccountErrorCode.UNSUPPORTED_VERSION -> "本机凭据版本不受支持，需要重新保存。"
    SchoolAccountErrorCode.ENCRYPTION_FAILED -> "本机加密失败，旧版本未改动。"
    SchoolAccountErrorCode.STORAGE_FAILED -> "本机写入未完成，请重试；不会降级为明文。"
    SchoolAccountErrorCode.CLEANUP_FAILED -> "旧账号数据清理未完成，请重试。"
    SchoolAccountErrorCode.CLEANUP_REQUIRED -> "请先完成旧账号数据清理。"
    SchoolAccountErrorCode.BUSY -> "另一个账号操作正在进行，请稍后重试。"
}
