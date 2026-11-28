package com.upcpocket.app.card

import java.io.IOException
import java.time.YearMonth

/** Read only campus card data returned by the Pocket backend. These models never contain a student ID. */
data class CardBackendBalance(
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAtEpochMillis: Long,
)

data class CardBackendTransaction(
    val rowKey: String,
    val title: String,
    val time: String,
    val amountFen: Long,
)

data class CardBackendTransactionPage(
    val page: Int,
    val size: Int,
    val total: Int,
    val pages: Int,
    val rows: List<CardBackendTransaction>,
)

data class CardBackendTransactionsSummary(
    val incomeFen: Long,
    val expenseFen: Long,
)

/**
 * Narrow host supplied read interface. The host attaches the Pocket bearer token; callers in this
 * module never receive it and no operation accepts a student ID.
 */
interface CardBackendDataSource {
    suspend fun balance(): CardBackendBalance

    suspend fun transactions(
        month: YearMonth?,
        page: Int,
        size: Int,
    ): CardBackendTransactionPage

    suspend fun transactionsSummary(month: YearMonth?): CardBackendTransactionsSummary
}

/** A fixed backend failure code; the adapter must never pass response bodies or raw URLs here. */
enum class CardBackendFailureCode {
    BACKEND_NOT_READY,
    TOKEN_INVALID,
    UNAUTHENTICATED,
    DISABLED,
    SIGN_IN_FAILED,
    INTERACTIVE_CHALLENGE,
    CREDENTIAL_UPDATE_REQUIRED,
    VERIFICATION_BUSY,
    MODULE_DISABLED,
    CIRCUIT_OPEN,
    UPSTREAM_UNAVAILABLE,
    DEPENDENCY_UNAVAILABLE,
    UPSTREAM_TIMEOUT,
    VALIDATION_FAILED,
    IDENTITY_UNSUPPORTED,
    UPSTREAM_PROTOCOL_ERROR,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    TLS_ERROR,
    UNKNOWN,
}

/** Stable failure boundary between the host REST adapter and the card feature. */
class CardBackendException(
    val code: CardBackendFailureCode,
    val retryAfterMillis: Long? = null,
) : IOException(code.name) {
    init {
        require(retryAfterMillis == null || retryAfterMillis >= 0L)
    }
}
