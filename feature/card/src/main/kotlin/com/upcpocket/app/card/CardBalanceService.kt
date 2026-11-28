package com.upcpocket.app.card

import android.content.Context
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.data.ecard.CachedRead
import com.upcpocket.app.data.ecard.CardBalanceBinding
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.data.ecard.cardBindingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** RemoteViews state; the snapshot and owner never contain a student ID. */
data class CardWidgetViewState(
    val status: CardBindingStatus,
    val snapshot: DirectCardSnapshot?,
)

data class CardWidgetRevision(
    val availability: CardBindingAvailability,
    val bindingToken: String?,
    val businessEpoch: Long,
    val snapshotRevision: Long,
)

data class CardWidgetRead(val state: CardWidgetViewState, val revision: CardWidgetRevision)

/** Shared page/widget/worker read entry point. All backend calls are supplied by the host. */
class CardBalanceService(
    context: Context,
    private val account: SchoolAccountReferenceReader,
    private val backend: CardBackendDataSource,
    private val onSnapshotChanged: () -> Unit,
) {
    private val repository by lazy {
        DirectCardBalanceRepository(
            store = DirectCardBalanceStore(context.applicationContext.noBackupFilesDir),
            account = account,
            backend = backend,
        )
    }

    val snapshot: Flow<DirectCardSnapshot?> get() = repository.snapshot
    val accountState: StateFlow<SchoolAccountState> get() = account.state

    val bindingStatus: Flow<CardBindingStatus> = combine(
        account.state,
        account.accountReference,
        ::cardBindingStatus,
    )

    fun bindingStatus(): CardBindingStatus = cardBindingStatus(account)
    fun canRefreshNow(): Boolean = bindingStatus().binding != null

    fun bindingToken(): String? = token(bindingStatus().binding)
    fun isBindingTokenCurrent(token: String?): Boolean = token == null || token == bindingToken()

    fun revisionNow(): CardWidgetRevision = revisionFor(bindingStatus())

    suspend fun readWidgetState(): CardWidgetRead {
        repeat(WIDGET_READ_ATTEMPTS) {
            val status = bindingStatus()
            val epoch = repository.businessEpochNow()
            val read = status.binding?.let { repository.cachedForWidget(it) } ?: CachedRead(
                snapshot = null,
                valid = true,
                snapshotRevision = repository.snapshotRevisionNow(),
            )
            if (!read.valid) return@repeat
            val after = bindingStatus()
            if (after == status && repository.businessEpochNow() == epoch &&
                repository.snapshotRevisionNow() == read.snapshotRevision
            ) {
                return CardWidgetRead(
                    CardWidgetViewState(status, read.snapshot),
                    CardWidgetRevision(
                        status.availability,
                        token(status.binding),
                        epoch,
                        read.snapshotRevision,
                    ),
                )
            }
        }
        return fallbackWidgetRead()
    }

    fun readWidgetStateBlocking(): CardWidgetRead {
        val status = bindingStatus()
        val epoch = repository.businessEpochNow()
        val read = status.binding?.let { repository.cachedBlockingForWidget(it) } ?: CachedRead(
            snapshot = null,
            valid = true,
            snapshotRevision = repository.snapshotRevisionNow(),
        )
        val after = bindingStatus()
        return if (read.valid && after == status && repository.businessEpochNow() == epoch &&
            repository.snapshotRevisionNow() == read.snapshotRevision
        ) {
            CardWidgetRead(
                CardWidgetViewState(status, read.snapshot),
                CardWidgetRevision(status.availability, token(status.binding), epoch, read.snapshotRevision),
            )
        } else {
            fallbackWidgetRead()
        }
    }

    fun widgetStateNow(): CardWidgetViewState = readWidgetStateBlocking().state

    private fun fallbackWidgetRead(): CardWidgetRead {
        val status = bindingStatus()
        return CardWidgetRead(CardWidgetViewState(status, null), revisionFor(status))
    }

    private fun revisionFor(status: CardBindingStatus) = CardWidgetRevision(
        availability = status.availability,
        bindingToken = token(status.binding),
        businessEpoch = repository.businessEpochNow(),
        snapshotRevision = repository.snapshotRevisionNow(),
    )

    private fun token(binding: CardBalanceBinding?): String? = binding?.let {
        "${it.accountReference.accountId.value}:${it.accountReference.credentialGeneration.value}"
    }

    /** Local cache recovery only. It makes no backend request. */
    suspend fun prepare() = repository.prepareAccountBinding()

    suspend fun cached(): DirectCardSnapshot? {
        prepare()
        return repository.cached()
    }

    suspend fun refreshCurrent(): DirectCardSnapshot = repository.refreshCurrent().also {
        onSnapshotChanged()
    }

    suspend fun clearCache() {
        repository.clearCache(onStarted = onSnapshotChanged)
        onSnapshotChanged()
    }

    suspend fun clearForAccountChange() {
        try {
            repository.clearForAccountChange(onStarted = onSnapshotChanged)
        } finally {
            onSnapshotChanged()
        }
    }

    private companion object {
        const val WIDGET_READ_ATTEMPTS = 3
    }
}
