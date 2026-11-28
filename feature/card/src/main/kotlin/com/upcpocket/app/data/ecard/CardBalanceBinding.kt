package com.upcpocket.app.data.ecard

import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions

/** Full managed reference fences requests; the account ID also keeps same-owner offline history. */
data class CardBalanceBinding(val accountReference: SchoolAccountReference)

enum class CardBindingAvailability {
    LOADING,
    NO_ACCOUNT,
    NEEDS_RECONFIGURATION,
    CLEANING,
    BACKEND_NOT_READY,
    READY,
}

data class CardBindingStatus(
    val availability: CardBindingAvailability,
    val binding: CardBalanceBinding? = null,
)

internal fun currentCardBinding(account: SchoolAccountReferenceReader): CardBalanceBinding? =
    currentCardBinding(account.state.value, account.accountReference.value)

internal fun currentCardBinding(
    state: SchoolAccountState,
    reference: SchoolAccountReference?,
): CardBalanceBinding? {
    if (state.status != SchoolAccountStatus.MANAGED_ACTIVE || state.blocksPersonalSessions) return null
    val current = reference ?: return null
    if (current.credentialGeneration != state.generation) return null
    return CardBalanceBinding(current)
}

internal fun cardBindingStatus(account: SchoolAccountReferenceReader): CardBindingStatus =
    cardBindingStatus(account.state.value, account.accountReference.value)

internal fun cardBindingStatus(
    state: SchoolAccountState,
    reference: SchoolAccountReference?,
): CardBindingStatus {
    val availability = when {
        state.status == SchoolAccountStatus.LOADING -> CardBindingAvailability.LOADING
        state.blocksPersonalSessions -> CardBindingAvailability.CLEANING
        state.status == SchoolAccountStatus.NOT_CONFIGURED -> CardBindingAvailability.NO_ACCOUNT
        state.status == SchoolAccountStatus.NEEDS_RECONFIGURATION ->
            CardBindingAvailability.NEEDS_RECONFIGURATION
        state.status == SchoolAccountStatus.BACKEND_NOT_READY -> CardBindingAvailability.BACKEND_NOT_READY
        state.errorCode?.name == "BACKEND_NOT_READY" -> CardBindingAvailability.BACKEND_NOT_READY
        state.status == SchoolAccountStatus.MANAGED_ACTIVE &&
            currentCardBinding(state, reference) != null -> CardBindingAvailability.READY
        else -> CardBindingAvailability.NO_ACCOUNT
    }
    return CardBindingStatus(availability, currentCardBinding(state, reference))
}
