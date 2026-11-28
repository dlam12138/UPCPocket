package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.card.CardBackendDataSource
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.CardBackendTransaction
import java.io.IOException
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class CardHistoryRow(val id: String, val title: String, val time: String, val amountFen: Long)
data class CardHistoryPage(val rows: List<CardHistoryRow>, val total: Int, val pages: Int)
data class CardHistorySummary(val incomeFen: Long, val expenseFen: Long)
data class BoundCardHistoryPage(
    val owner: SchoolAccountReference,
    val month: YearMonth?,
    val current: Int,
    val data: CardHistoryPage,
)

enum class CardHistoryFailure {
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CHANGED,
    NETWORK,
    PROTOCOL,
    SERVICE_NOT_READY,
    MODULE_DISABLED,
    CIRCUIT_OPEN,
    TIMEOUT,
    RATE_LIMITED,
    IDENTITY_UNSUPPORTED,
    CREDENTIAL_UPDATE_REQUIRED,
    INTERACTIVE_CHALLENGE,
    VERIFICATION_BUSY,
    UNAUTHENTICATED,
}

class CardHistoryException(
    val reason: CardHistoryFailure,
    val retryAfterMillis: Long? = null,
) : IOException(reason.name)

/** Backend only; this client owns no token, Cookie, web session, or disk persistence. */
class CardHistoryClient(
    private val account: SchoolAccountReferenceReader,
    private val backend: CardBackendDataSource,
) {
    val accountReference get() = account.accountReference
    val accountState get() = account.state

    suspend fun invalidateForAccountChange(): Boolean = true

    fun isCurrent(owner: SchoolAccountReference): Boolean = currentOwner() == owner

    suspend fun page(
        month: YearMonth? = null,
        current: Int = 1,
        size: Int = 50,
    ): BoundCardHistoryPage {
        if (current < 1 || size !in 1..100) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        val owner = currentOwner() ?: throw CardHistoryException(CardHistoryFailure.ACCOUNT_UNAVAILABLE)
        val result = try {
            backend.transactions(month, current, size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: CardHistoryException) {
            throw known
        } catch (failure: CardBackendException) {
            throw failure.toHistoryException()
        } catch (_: Exception) {
            throw CardHistoryException(CardHistoryFailure.NETWORK)
        }
        val expectedPages = if (result.total <= 0) 0 else
            ((result.total.toLong() + size - 1L) / size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (!isCurrent(owner)) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
        if (result.page != current || result.size != size || result.total < result.rows.size ||
            result.pages < 0 || result.rows.size > result.size ||
            result.pages != expectedPages ||
            result.rows.map { it.rowKey }.toSet().size != result.rows.size
        ) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        val rows = result.rows.map(::toRow)
        if (!isCurrent(owner)) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
        return BoundCardHistoryPage(
            owner = owner,
            month = month,
            current = current,
            data = CardHistoryPage(rows, result.total, result.pages),
        )
    }

    /** User initiated only; rows remain in the screen's memory and never enter a disk store. */
    suspend fun all(
        month: YearMonth? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BoundCardHistoryPage {
        val first = page(month, current = 1)
        val owner = first.owner
        val rows = LinkedHashMap<String, CardHistoryRow>()
        var result = first
        result.data.rows.forEach { rows[it.id] = it }
        onProgress(rows.size, result.data.total)
        for (pageNumber in 2..first.data.pages) {
            if (!isCurrent(owner)) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
            result = page(month, pageNumber)
            if (result.owner != owner || result.data.rows.isEmpty()) {
                throw CardHistoryException(CardHistoryFailure.PROTOCOL)
            }
            val previous = rows.size
            result.data.rows.forEach { rows.putIfAbsent(it.id, it) }
            if (rows.size == previous && rows.size < result.data.total) {
                throw CardHistoryException(CardHistoryFailure.PROTOCOL)
            }
            onProgress(rows.size, result.data.total)
            if (pageNumber < first.data.pages) delay(250)
        }
        if (!isCurrent(owner) || rows.size != first.data.total) {
            throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        }
        return result.copy(data = result.data.copy(rows = rows.values.toList()))
    }

    suspend fun summary(month: YearMonth? = null): CardHistorySummary {
        val owner = currentOwner() ?: throw CardHistoryException(CardHistoryFailure.ACCOUNT_UNAVAILABLE)
        val summary = try {
            backend.transactionsSummary(month)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: CardHistoryException) {
            throw known
        } catch (failure: CardBackendException) {
            throw failure.toHistoryException()
        } catch (_: Exception) {
            throw CardHistoryException(CardHistoryFailure.NETWORK)
        }
        if (!isCurrent(owner)) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
        if (summary.incomeFen < 0 || summary.expenseFen < 0) {
            throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        }
        return CardHistorySummary(summary.incomeFen, summary.expenseFen)
    }

    private fun currentOwner(): SchoolAccountReference? {
        val state = account.state.value
        if (state.status != SchoolAccountStatus.MANAGED_ACTIVE || state.blocksPersonalSessions) return null
        return account.accountReference.value?.takeIf { it.credentialGeneration == state.generation }
    }

    private fun toRow(value: CardBackendTransaction): CardHistoryRow {
        if (value.rowKey.isBlank() || value.rowKey.length > 256 || value.title.length > 512 ||
            value.time.isBlank() || value.time.length > 128
        ) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        return CardHistoryRow(
            id = value.rowKey,
            title = value.title.ifBlank { "校园卡交易" },
            time = value.time.ifBlank { "时间未知" },
            amountFen = value.amountFen,
        )
    }
}

private fun CardBackendException.toHistoryException(): CardHistoryException = CardHistoryException(
    reason = when (code) {
        CardBackendFailureCode.BACKEND_NOT_READY -> CardHistoryFailure.SERVICE_NOT_READY
        CardBackendFailureCode.TOKEN_INVALID,
        CardBackendFailureCode.UNAUTHENTICATED,
        CardBackendFailureCode.DISABLED -> CardHistoryFailure.UNAUTHENTICATED
        CardBackendFailureCode.INTERACTIVE_CHALLENGE -> CardHistoryFailure.INTERACTIVE_CHALLENGE
        CardBackendFailureCode.CREDENTIAL_UPDATE_REQUIRED -> CardHistoryFailure.CREDENTIAL_UPDATE_REQUIRED
        CardBackendFailureCode.VERIFICATION_BUSY -> CardHistoryFailure.VERIFICATION_BUSY
        CardBackendFailureCode.MODULE_DISABLED -> CardHistoryFailure.MODULE_DISABLED
        CardBackendFailureCode.CIRCUIT_OPEN -> CardHistoryFailure.CIRCUIT_OPEN
        CardBackendFailureCode.UPSTREAM_UNAVAILABLE,
        CardBackendFailureCode.DEPENDENCY_UNAVAILABLE -> CardHistoryFailure.SERVICE_NOT_READY
        CardBackendFailureCode.UPSTREAM_TIMEOUT -> CardHistoryFailure.TIMEOUT
        CardBackendFailureCode.RATE_LIMITED -> CardHistoryFailure.RATE_LIMITED
        CardBackendFailureCode.IDENTITY_UNSUPPORTED -> CardHistoryFailure.IDENTITY_UNSUPPORTED
        CardBackendFailureCode.UPSTREAM_PROTOCOL_ERROR,
        CardBackendFailureCode.VALIDATION_FAILED -> CardHistoryFailure.PROTOCOL
        CardBackendFailureCode.SIGN_IN_FAILED -> CardHistoryFailure.UNAUTHENTICATED
        CardBackendFailureCode.NETWORK_UNAVAILABLE,
        CardBackendFailureCode.TLS_ERROR,
        CardBackendFailureCode.UNKNOWN -> CardHistoryFailure.NETWORK
    },
    retryAfterMillis = retryAfterMillis,
)
