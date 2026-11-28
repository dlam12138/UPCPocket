package com.upcpocket.app.account

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId

/**
 * 已关闭的旧认证适配兼容边界。
 *
 * 手机端不再保存学校密码，生产 [SchoolCredentialAccess] 实现始终返回失败；
 * 此类型暂时保留给尚未移除的旧协议适配器，使其在运行时安全失败。
 */
@RequiresOptIn(
    message = "明文学校凭据只允许由认证适配在单次认证期间使用，业务页面与缓存不得读取",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class RestrictedSchoolCredentialUse

/**
 * 旧式认证适配曾使用的一次性登录凭据。
 *
 * - 只暴露登录名与一次性密码数组；不提供复制为长期 `String` 的 getter；
 * - [usePassword] 结束或对象被 [close] 后数组清零；
 * - `toString` 固定脱敏，避免日志/崩溃框架带出任何值。
 */
class SchoolLoginCredential @RestrictedSchoolCredentialUse constructor(
    val accountId: SchoolAccountId,
    val credentialGeneration: AccountGeneration,
    val loginName: String,
    private var password: CharArray,
) : AutoCloseable {

    /**
     * 在回调内逐字符使用密码（例如构造表单）。回调返回后不自动清零，
     * 调用方应只在本方法内完成表单编码；最迟由 [close] 清零。
     */
    @RestrictedSchoolCredentialUse
    fun <T> usePassword(block: (CharArray) -> T): T {
        check(password.isNotEmpty()) { "登录凭据已释放" }
        return block(password)
    }

    override fun close() {
        password.fill('\u0000')
        password = CharArray(0)
    }

    override fun toString(): String =
        "SchoolLoginCredential(accountId=$accountId, generation=$credentialGeneration, loginName=[REDACTED], password=[REDACTED])"
}

/** 认证适配只能得到“可用”或固定不可用原因，不会得到底层异常或密文。 */
enum class SchoolCredentialFailure {
    NOT_CONFIGURED,
    BLOCKED_BY_CLEANUP,
    UNAVAILABLE,
    CHANGED_DURING_USE,
}

sealed interface SchoolCredentialUseResult<out T> {
    data class Success<T>(val value: T) : SchoolCredentialUseResult<T>

    data class Failure(val reason: SchoolCredentialFailure) : SchoolCredentialUseResult<Nothing>
}

/**
 * 兼容旧认证适配的读取面；托管账号实现不得返回凭据。
 *
 * 实现必须在返回前复核当前账号与代次；读取期间不持有账号锁，认证网络请求
 * 不得阻塞账号保存/删除。认证完成后由调用方再次核对代次。
 */
interface SchoolCredentialAccess {
    @RestrictedSchoolCredentialUse
    suspend fun <T> withLoginCredential(
        block: suspend (SchoolLoginCredential) -> T,
    ): SchoolCredentialUseResult<T>
}
