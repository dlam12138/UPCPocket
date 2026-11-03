package com.upcpocket.app.data.ecard

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.account.api.blocksPersonalSessions

/**
 * 余额查询的唯一目标。
 *
 * 只由当前统一账号的受控身份面生成；不含密码、Cookie 或任意可解密入口。
 * `accountId` 用于个人缓存归属，`studentId` 是整串校验后的本人学号，
 * `credentialGeneration` 用于拒绝换号/改密前发出的迟到结果。
 */
data class CardBalanceBinding(
    val accountId: SchoolAccountId,
    val studentId: String,
    val credentialGeneration: AccountGeneration,
)

/**
 * 余额界面/组件的绑定状态。它只描述“能否按当前账号查询”，不代表 CAS 或
 * 学校认证成功；余额接口本身按学号查询，不接收密码。
 */
enum class CardBindingAvailability {
    LOADING,
    NO_ACCOUNT,
    NEEDS_RECONFIGURATION,
    CLEANING,
    STUDENT_ID_UNAVAILABLE,
    READY,
}

data class CardBindingStatus(
    val availability: CardBindingAvailability,
    val binding: CardBalanceBinding? = null,
)

internal fun currentCardBinding(
    account: SchoolAccountStudentIdentityReader,
): CardBalanceBinding? = currentCardBinding(
    state = account.state.value,
    identity = account.studentIdentity.value,
)

internal fun currentCardBinding(
    state: SchoolAccountState,
    identity: SchoolStudentIdentity?,
): CardBalanceBinding? {
    if (state.blocksPersonalSessions || !state.isCredentialSaved) return null
    val current = identity ?: return null
    if (current.credentialGeneration != state.generation) return null
    return CardBalanceBinding(
        accountId = current.accountId,
        studentId = current.studentId,
        credentialGeneration = current.credentialGeneration,
    )
}

internal fun cardBindingStatus(
    account: SchoolAccountStudentIdentityReader,
): CardBindingStatus = cardBindingStatus(
    state = account.state.value,
    identity = account.studentIdentity.value,
)

internal fun cardBindingStatus(
    state: SchoolAccountState,
    identity: SchoolStudentIdentity?,
): CardBindingStatus {
    val availability = when {
        state.status == SchoolAccountStatus.LOADING -> CardBindingAvailability.LOADING
        state.blocksPersonalSessions -> CardBindingAvailability.CLEANING
        state.status == SchoolAccountStatus.NOT_CONFIGURED -> CardBindingAvailability.NO_ACCOUNT
        state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION ->
            CardBindingAvailability.NEEDS_RECONFIGURATION
        state.status == SchoolAccountStatus.SAVED_UNVERIFIED -> {
            when {
                currentCardBinding(state, identity) != null -> CardBindingAvailability.READY
                // state 与 identity 是两条流，短暂代次错位时按“仍在读取”处理，
                // 不把一次换密过程误报成“登录名不是学号”。
                identity != null && identity.credentialGeneration != state.generation ->
                    CardBindingAvailability.LOADING
                identity == null && state.studentIdAvailable ->
                    CardBindingAvailability.LOADING
                else -> CardBindingAvailability.STUDENT_ID_UNAVAILABLE
            }
        }
        else -> CardBindingAvailability.CLEANING
    }
    return CardBindingStatus(availability, currentCardBinding(state, identity))
}
