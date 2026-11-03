package com.upcpocket.app.library

import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.data.library.LibraryUser

/** 统一账号认证适配返回给图书馆的固定结果；不含密码、Cookie 或凭据能力。 */
sealed interface LibraryAccountAuthResult {
    data class Success(
        val seatToken: String,
        val user: LibraryUser,
    ) : LibraryAccountAuthResult

    data class Failure(
        val reason: LibraryAccountAuthFailure,
        /** 仅 INTERACTION_REQUIRED 时为学校正常登录页。 */
        val interactionUrl: String? = null,
    ) : LibraryAccountAuthResult
}

enum class LibraryAccountAuthFailure {
    INVALID_CREDENTIALS,
    INTERACTION_REQUIRED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UPSTREAM_ERROR,
    PROTOCOL_ERROR,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    ACCESS_DENIED,
    IDENTITY_MISMATCH,
    PERSISTENCE_FAILED,
    SESSION_EXPIRED,
}

/**
 * 图书馆只消费这个认证边界。
 *
 * 实现由宿主装配，内部可以使用 core 的受控账密；插件本身不依赖 core:account，
 * 也不能读取账号原文或密码。
 */
fun interface LibraryAccountAuthenticator {
    suspend fun authenticate(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: Long,
    ): LibraryAccountAuthResult
}
