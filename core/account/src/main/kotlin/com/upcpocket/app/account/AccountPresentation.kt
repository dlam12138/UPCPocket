package com.upcpocket.app.account

import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus

/** 账号页与设置页共用的脱敏状态文案；不包含账号、密码或异常原文。 */
fun SchoolAccountState.summaryText(): String {
    val currentError = errorCode
    if (status == SchoolAccountStatus.UNBIND_PENDING) {
        return when {
            cleanupRequired -> "本机个人数据清理未完成，请重试。"
            currentError == SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN -> currentError.userMessage()
            else -> "解除托管待重试，本机个人数据已封闭，请先重试解除托管。"
        }
    }
    if (status == SchoolAccountStatus.SIGN_OUT_PENDING) {
        return if (cleanupRequired) {
            "本机个人数据清理未完成，请重试。"
        } else {
            "退出本机待重试，本机个人数据已封闭，请先重试退出本机。"
        }
    }
    val errorMessage = errorCode?.userMessage()
    if (cleanupRequired) {
        return when {
            errorMessage == null -> "清理未完成，请重试。"
            errorCode == SchoolAccountErrorCode.CLEANUP_FAILED -> errorMessage
            else -> "清理未完成 · $errorMessage"
        }
    }
    if (errorMessage != null) return errorMessage

    return when (status) {
        SchoolAccountStatus.LOADING -> "正在读取账号…"
        SchoolAccountStatus.NOT_CONFIGURED -> "未设置"
        SchoolAccountStatus.MANAGED_ACTIVE -> "校方托管已连接${deviceId?.let { " · 本机已绑定" }.orEmpty()}"
        SchoolAccountStatus.MANAGED_REAUTH_REQUIRED -> "需要重新验证学校账号"
        SchoolAccountStatus.MANAGED_DISABLED -> "校方托管账号已停用"
        SchoolAccountStatus.UNBIND_PENDING -> "解绑待重试，本机个人数据已封闭。"
        SchoolAccountStatus.SIGN_OUT_PENDING -> "退出待重试，本机个人数据已封闭。"
        SchoolAccountStatus.BACKEND_NOT_READY -> "校方服务未就绪"
        SchoolAccountStatus.SAVED_UNVERIFIED ->
            "已保存${maskedAccount?.let { " · $it" }.orEmpty()}"
        SchoolAccountStatus.NEEDS_RECONFIGURATION -> "需要重新保存账号"
        SchoolAccountStatus.SWITCHING -> "正在切换账号"
        SchoolAccountStatus.UPDATING -> "正在更新账号"
        SchoolAccountStatus.REMOVING -> "正在删除账号"
    }
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
    SchoolAccountErrorCode.BACKEND_NOT_READY -> "校方服务未就绪。"
    SchoolAccountErrorCode.CUSTODY_REQUIRED -> "请先同意校方托管说明。"
    SchoolAccountErrorCode.SIGN_IN_FAILED -> "学校账号或密码不正确。"
    SchoolAccountErrorCode.UNAUTHENTICATED,
    SchoolAccountErrorCode.TOKEN_INVALID -> "托管会话已失效，请重新绑定。"
    SchoolAccountErrorCode.INTERACTIVE_CHALLENGE -> "请先打开学校官方页面完成验证。"
    SchoolAccountErrorCode.CREDENTIAL_UPDATE_REQUIRED -> "请先更新校方托管密码。"
    SchoolAccountErrorCode.VERIFICATION_BUSY -> "学校验证正在进行，请稍后重试。"
    SchoolAccountErrorCode.MODULE_DISABLED -> "校方暂未开放此功能，已保留本机缓存。"
    SchoolAccountErrorCode.CIRCUIT_OPEN -> "校方服务暂不可用，已保留本机缓存。"
    SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE,
    SchoolAccountErrorCode.DEPENDENCY_UNAVAILABLE -> "校方服务暂不可用，已保留本机缓存。"
    SchoolAccountErrorCode.UPSTREAM_TIMEOUT -> "校方服务响应超时，已保留本机缓存。"
    SchoolAccountErrorCode.UPSTREAM_PROTOCOL_ERROR -> "校方服务返回的数据暂时无法读取。"
    SchoolAccountErrorCode.VALIDATION_FAILED -> "请求信息不符合校方服务要求。"
    SchoolAccountErrorCode.IDENTITY_UNSUPPORTED -> "当前账号暂不支持此功能。"
    SchoolAccountErrorCode.RATE_LIMITED -> "操作过于频繁，请稍后重试。"
    SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN ->
        "解绑结果未知，本机个人数据已封闭，请重试解除托管以确认服务端状态。"
}
