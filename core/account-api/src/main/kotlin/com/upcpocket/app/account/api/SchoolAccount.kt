package com.upcpocket.app.account.api

import kotlinx.coroutines.flow.StateFlow

/** 本机凭据版本号。每次成功保存都会生成一个完整的新版本。 */
@JvmInline
value class AccountGeneration(val value: Long) {
    init {
        require(value >= 0) { "账号代次不能为负数" }
    }

    companion object {
        val None = AccountGeneration(0)
    }
}

/**
 * 应用内部稳定账号标识。
 *
 * 由账号模块对规范化登录名做不可逆摘要生成，不包含登录名、学号或密码；
 * 用于给个人缓存标注归属。它不是学校账号或官方身份。
 */
@JvmInline
value class SchoolAccountId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "非法内部账号标识" }
    }
}

/** 余额等业务可接受的学号格式；与现有余额协议保持 8–16 位 ASCII 数字。 */
object StudentIdFormat {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 16

    fun isValid(value: String): Boolean =
        value.length in MIN_LENGTH..MAX_LENGTH && value.all { it in '0'..'9' }
}

/**
 * 受控的本人学号读取结果。
 *
 * 只有账号登录名整串符合学号格式时才生成；不从任意字符串截取、不把脱敏文本当学号。
 * 该对象不含密码、凭据载荷或解密能力。
 */
data class SchoolStudentIdentity(
    val accountId: SchoolAccountId,
    val studentId: String,
    val credentialGeneration: AccountGeneration,
) {
    init {
        require(StudentIdFormat.isValid(studentId)) { "学号格式无效" }
        require(credentialGeneration.value > 0) { "学号必须绑定到已保存的凭据代次" }
    }
}

/**
 * 统一账号的公开状态。
 *
 * 只描述“本机是否保存了凭据”和本机清理进度，不代表任何校园业务已经登录。
 * `SAVED_UNVERIFIED` 的准确含义是：密文已可靠写入本机，尚未向学校验证。
 */
enum class SchoolAccountStatus {
    LOADING,
    NOT_CONFIGURED,
    SAVED_UNVERIFIED,
    NEEDS_RECONFIGURATION,
    SWITCHING,
    UPDATING,
    REMOVING,
}

/** 固定错误码；异常原文、账号和密码不会进入这里。 */
enum class SchoolAccountErrorCode {
    INVALID_ACCOUNT,
    INVALID_PASSWORD,
    NOT_CONFIGURED,
    CREDENTIAL_UNAVAILABLE,
    CREDENTIAL_CORRUPTED,
    UNSUPPORTED_VERSION,
    ENCRYPTION_FAILED,
    STORAGE_FAILED,
    CLEANUP_FAILED,
    CLEANUP_REQUIRED,
    BUSY,
}

/** 页面与业务只能读取这个状态；密码不在状态里，也不能从这里反查。 */
data class SchoolAccountState(
    val status: SchoolAccountStatus = SchoolAccountStatus.LOADING,
    val generation: AccountGeneration = AccountGeneration.None,
    val maskedAccount: String? = null,
    /** 可从账号登录名整串识别的学号；仅在符合受控学号格式时存在。 */
    val maskedStudentId: String? = null,
    val studentIdAvailable: Boolean = false,
    val savedAtEpochMillis: Long? = null,
    /** true 表示换号/删号/恢复清理尚未完成，所有个人业务必须停止。 */
    val cleanupRequired: Boolean = false,
    /** 上一次本机操作或恢复失败的固定错误码。 */
    val errorCode: SchoolAccountErrorCode? = null,
) {
    val isCredentialSaved: Boolean
        get() = status == SchoolAccountStatus.SAVED_UNVERIFIED

    /** 个人请求只应在凭据可用时捕获；代次变化后必须拒绝迟到的写回。 */
    fun captureRequestToken(): AccountRequestToken? =
        if (isCredentialSaved && generation.value > 0) AccountRequestToken(generation) else null

    fun isCurrent(token: AccountRequestToken): Boolean =
        isCredentialSaved && token.generation == generation
}

/** 业务请求捕获的账号代次；不包含账号、密码或会话。 */
data class AccountRequestToken(val generation: AccountGeneration)

/** 业务插件只依赖这个读取面；恢复由宿主调用。 */
interface SchoolAccountGate {
    val state: StateFlow<SchoolAccountState>
}

/**
 * 任意合法已保存账号的受控引用。
 *
 * 与 [SchoolStudentIdentity] 不同，它不要求登录名整串是学号，供课表这类
 * 使用学校统一登录名（可能含字母）的业务确定缓存与会话归属。它只含不可逆
 * 内部 accountId 与凭据代次，不含登录名、密码、Cookie 或解密能力。
 */
data class SchoolAccountReference(
    val accountId: SchoolAccountId,
    val credentialGeneration: AccountGeneration,
) {
    init {
        require(credentialGeneration.value > 0) { "账号引用必须绑定已保存的凭据代次" }
    }
}

/**
 * 只读受控身份面。
 *
 * 业务只能获得当前绑定账号的内部标识、本人学号和凭据代次；不能读取账号原文、
 * 密码、`CredentialPayload`，也没有任意解密入口。LOADING、清理中、未保存或
 * 登录名不是有效学号时，[studentIdentity] 必须为 null。
 */
interface SchoolAccountStudentIdentityReader : SchoolAccountGate {
    val studentIdentity: StateFlow<SchoolStudentIdentity?>
}

/**
 * 只读账号引用面，供不需要学号但需要账号隔离的业务使用。
 *
 * 只有状态为 `SAVED_UNVERIFIED`、代次大于 0 且未处于清理门闩时才发布引用。
 */
interface SchoolAccountReferenceReader : SchoolAccountGate {
    val accountReference: StateFlow<SchoolAccountReference?>
}

/**
 * 受控的登录名一致性检查。
 *
 * 业务只提交“学校刚刚返回的本人登录名”，由账号模块在本机与当前保存账号比较；
 * 不暴露账号原文、密码、`CredentialPayload` 或通用解密能力。比较必须绑定
 * accountId 与凭据代次；账号变化、清理中或不可用时不得返回 [SchoolAccountIdentityCheck.MATCH]。
 */
enum class SchoolAccountIdentityCheck {
    MATCH,
    MISMATCH,
    CHANGED,
    UNAVAILABLE,
}

fun interface SchoolAccountIdentityVerifier {
    suspend fun verifyLoginName(
        expectedAccountId: SchoolAccountId,
        expectedCredentialGeneration: AccountGeneration,
        candidateLoginName: String,
    ): SchoolAccountIdentityCheck
}

/**
 * 账号状态是否禁止建立/恢复个人业务会话。
 *
 * - LOADING：启动恢复尚未完成，不能假定旧清理已经结束；
 * - cleanupRequired：换号/删号/恢复清理还在进行或失败待重试；
 * - SWITCHING/UPDATING/REMOVING：事务已经把旧代次置为不可用。
 *
 * 未配置、已保存未验证、需要重新保存且清理已完成时，图书馆仍可按原有方式手动登录；
 * 这不代表已接入统一认证。
 */
val SchoolAccountState.blocksPersonalSessions: Boolean
    get() = cleanupRequired || status == SchoolAccountStatus.LOADING ||
        status == SchoolAccountStatus.SWITCHING ||
        status == SchoolAccountStatus.UPDATING ||
        status == SchoolAccountStatus.REMOVING

/** 业务插件只依赖这个读取面；恢复由宿主调用。 */
interface SchoolAccountReader : SchoolAccountGate {
    suspend fun restore()
}

/** 保存/删除/重试等编辑操作只交给宿主的账号设置页。 */
interface SchoolAccountAdmin {
    suspend fun save(account: String?, password: CharArray): SchoolAccountWriteResult

    suspend fun delete(): SchoolAccountWriteResult

    suspend fun retryCleanup(): SchoolAccountWriteResult
}

interface SchoolAccountController : SchoolAccountReader, SchoolAccountAdmin

sealed interface SchoolAccountWriteResult {
    data object Success : SchoolAccountWriteResult

    data class Failure(val code: SchoolAccountErrorCode) : SchoolAccountWriteResult
}

/**
 * 统一账号失效广播的业务范围。
 *
 * `SESSIONS_ONLY` 用于同账号更新密码：清掉旧会话，但允许保留同账号历史缓存。
 * `PERSONAL_DATA` 用于换号、删号或凭据不可读：会话与无法确认归属的个人缓存都要清理。
 */
enum class AccountDataScope {
    SESSIONS_ONLY,
    PERSONAL_DATA,
}

enum class AccountInvalidationReason {
    FIRST_SAVE,
    PASSWORD_UPDATED,
    ACCOUNT_SWITCHED,
    ACCOUNT_REMOVED,
    CREDENTIAL_UNREADABLE,
}

data class AccountInvalidationRequest(
    val reason: AccountInvalidationReason,
    val scope: AccountDataScope,
    val previousGeneration: AccountGeneration,
    val nextGeneration: AccountGeneration,
)

enum class AccountInvalidationFailure {
    SESSION_CLEAR_FAILED,
    COOKIE_CLEAR_TIMEOUT,
    PERSONAL_DATA_CLEAR_FAILED,
    UNAVAILABLE,
}

data class AccountInvalidationResult(
    val ok: Boolean,
    val failure: AccountInvalidationFailure? = null,
) {
    init {
        require(ok == (failure == null)) { "清理结果与失败码矛盾" }
    }

    companion object {
        val Success = AccountInvalidationResult(ok = true)

        fun failure(code: AccountInvalidationFailure): AccountInvalidationResult =
            AccountInvalidationResult(ok = false, failure = code)
    }
}

/**
 * 业务插件实现的失效接口。
 *
 * 实现必须可重复执行；账号层在清理失败、进程重启或用户重试时会再次调用。
 * 只允许返回固定失败码，不能把 Cookie、token 或异常原文放进结果。
 */
fun interface SchoolAccountInvalidator {
    suspend fun invalidate(request: AccountInvalidationRequest): AccountInvalidationResult
}
