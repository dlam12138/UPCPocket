package com.upcpocket.app.data.ecard

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test-only managed account surface. It never models a login name, password, or student ID. */
class FakeAccountIdentity(
    initialStatus: SchoolAccountStatus = SchoolAccountStatus.NOT_CONFIGURED,
    initialGeneration: AccountGeneration = AccountGeneration.None,
) : SchoolAccountReferenceReader {
    private val mutableState = MutableStateFlow(
        SchoolAccountState(
            status = initialStatus,
            generation = initialGeneration,
            cleanupRequired = initialStatus == SchoolAccountStatus.LOADING,
        ),
    )
    private val mutableReference = MutableStateFlow<SchoolAccountReference?>(null)

    override val state: StateFlow<SchoolAccountState> = mutableState
    override val accountReference: StateFlow<SchoolAccountReference?> = mutableReference

    fun bind(
        accountId: String = "acct_test_account_a",
        generation: Long = 1,
    ) {
        val gen = AccountGeneration(generation)
        mutableReference.value = SchoolAccountReference(SchoolAccountId(accountId), gen)
        mutableState.value = SchoolAccountState(
            status = SchoolAccountStatus.MANAGED_ACTIVE,
            generation = gen,
            deviceId = "device_test",
        )
    }

    fun setUnavailable(
        status: SchoolAccountStatus = SchoolAccountStatus.NOT_CONFIGURED,
        generation: Long = 0,
        cleanupRequired: Boolean = false,
    ) {
        mutableReference.value = null
        mutableState.value = SchoolAccountState(
            status = status,
            generation = AccountGeneration(generation),
            cleanupRequired = cleanupRequired,
        )
    }

    fun cleaning(generation: Long = 2) = setUnavailable(
        status = SchoolAccountStatus.UPDATING,
        generation = generation,
        cleanupRequired = true,
    )

    fun unbind(generation: Long = 2) = setUnavailable(
        status = SchoolAccountStatus.NOT_CONFIGURED,
        generation = generation,
    )
}
