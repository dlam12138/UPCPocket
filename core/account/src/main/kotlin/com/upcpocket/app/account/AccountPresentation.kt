package com.upcpocket.app.account

import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus

/** 「更多」入口与设置页共用的脱敏状态文案；不包含账号、密码或异常原文。 */
fun SchoolAccountState.summaryText(): String = when (status) {
    SchoolAccountStatus.LOADING -> "正在读取账号…"
    SchoolAccountStatus.NOT_CONFIGURED -> "未设置"
    SchoolAccountStatus.SAVED_UNVERIFIED ->
        "已保存，尚未验证${maskedAccount?.let { " · $it" }.orEmpty()}"
    SchoolAccountStatus.NEEDS_RECONFIGURATION -> "需要重新保存账号"
    SchoolAccountStatus.SWITCHING -> "正在切换账号"
    SchoolAccountStatus.UPDATING -> "正在更新账号"
    SchoolAccountStatus.REMOVING -> "正在删除账号"
}

fun SchoolAccountErrorCode.userMessage(): String = when (this) {
    SchoolAccountErrorCode.INVALID_ACCOUNT -> "账号格式不正确。"
    SchoolAccountErrorCode.INVALID_PASSWORD -> "密码不能为空或过长。"
    SchoolAccountErrorCode.NOT_CONFIGURED -> "尚未设置账号。"
    SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE,
    SchoolAccountErrorCode.CREDENTIAL_CORRUPTED,
    SchoolAccountErrorCode.UNSUPPORTED_VERSION,
    -> "账号信息无法读取，请重新保存。"
    SchoolAccountErrorCode.ENCRYPTION_FAILED,
    SchoolAccountErrorCode.STORAGE_FAILED,
    -> "保存失败，请重试。"
    SchoolAccountErrorCode.CLEANUP_FAILED -> "清理未完成，请重试。"
    SchoolAccountErrorCode.CLEANUP_REQUIRED -> "请先完成清理。"
    SchoolAccountErrorCode.BUSY -> "正在处理另一个账号操作，请稍后。"
}
