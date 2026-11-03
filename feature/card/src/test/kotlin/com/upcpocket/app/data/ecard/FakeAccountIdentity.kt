package com.upcpocket.app.data.ecard

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolStudentIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 测试替身：只模拟受控身份面，不包含密码或任意解密能力。 */
class FakeAccountIdentity(
    initialStatus: SchoolAccountStatus = SchoolAccountStatus.NOT_CONFIGURED,
    initialGeneration: AccountGeneration = AccountGeneration.None,
) : SchoolAccountStudentIdentityReader {
    private val mutableState = MutableStateFlow(
        SchoolAccountState(
            status = initialStatus,
            generation = initialGeneration,
            cleanupRequired = initialStatus == SchoolAccountStatus.LOADING,
        ),
    )
    private val mutableIdentity = MutableStateFlow<SchoolStudentIdentity?>(null)

    override val state: StateFlow<SchoolAccountState> = mutableState
    override val studentIdentity: StateFlow<SchoolStudentIdentity?> = mutableIdentity

    fun bind(
        accountId: String = "acct_test_account_a",
        studentId: String = "2099000999",
        generation: Long = 1,
    ) {
        val id = SchoolAccountId(accountId)
        val gen = AccountGeneration(generation)
        mutableIdentity.value = SchoolStudentIdentity(id, studentId, gen)
        mutableState.value = SchoolAccountState(
            status = SchoolAccountStatus.SAVED_UNVERIFIED,
            generation = gen,
            maskedAccount = "20••••99",
            maskedStudentId = "20••••99",
            studentIdAvailable = true,
            savedAtEpochMillis = 1_700_000_000_000,
        )
    }

    fun savedWithoutStudentId(generation: Long = 1) {
        mutableIdentity.value = null
        mutableState.value = SchoolAccountState(
            status = SchoolAccountStatus.SAVED_UNVERIFIED,
            generation = AccountGeneration(generation),
            maskedAccount = "alias••••",
            studentIdAvailable = false,
        )
    }

    fun cleaning(
        status: SchoolAccountStatus = SchoolAccountStatus.UPDATING,
        generation: Long = 2,
        errorCode: com.upcpocket.app.account.api.SchoolAccountErrorCode? = null,
    ) {
        mutableIdentity.value = null
        mutableState.value = SchoolAccountState(
            status = status,
            generation = AccountGeneration(generation),
            cleanupRequired = true,
            errorCode = errorCode,
        )
    }

    fun unbind(generation: Long = 2) {
        mutableIdentity.value = null
        mutableState.value = SchoolAccountState(
            status = SchoolAccountStatus.NOT_CONFIGURED,
            generation = AccountGeneration(generation),
        )
    }
}
