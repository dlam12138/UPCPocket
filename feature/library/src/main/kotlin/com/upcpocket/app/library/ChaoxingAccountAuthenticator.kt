package com.upcpocket.app.library

import com.upcpocket.app.account.api.SchoolAccountId

/** 自动认证返回给 ChaoxingService 的固定结果；不含密码、票据或原始响应。 */
sealed interface ChaoxingAccountAuthResult {
    data class Success(
        /** 只对 office.chaoxing.com 生效的 Cookie 头；调用方只保存在内存。 */
        val cookieHeader: String,
    ) : ChaoxingAccountAuthResult {
        override fun toString(): String = "Success(cookieHeader=<redacted>)"
    }

    data class Failure(
        val reason: ChaoxingAuthFailure,
        /** 仅 INTERACTION_REQUIRED 时为学校 CAS 登录页。 */
        val interactionUrl: String? = null,
    ) : ChaoxingAccountAuthResult
}

/**
 * 超星座位只消费这个认证边界。
 *
 * 实现由宿主装配，内部使用 core 的受控学校账密和公共 CAS 引擎；插件本身
 * 不依赖 core:account，也不能读取账号原文或密码。
 */
fun interface ChaoxingAccountAuthenticator {
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): ChaoxingAccountAuthResult
}
