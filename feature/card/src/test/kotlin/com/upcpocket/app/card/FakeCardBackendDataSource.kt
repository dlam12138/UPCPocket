package com.upcpocket.app.card

import java.time.YearMonth

internal open class FakeCardBackendDataSource : CardBackendDataSource {
    var balanceResult = CardBackendBalance(
        balanceCents = 12_345,
        transitionCents = 678,
        fetchedAtEpochMillis = 1_700_000_000_000,
    )
    var balanceFailure: Exception? = null
    var transactionsFailure: Exception? = null
    var summaryFailure: Exception? = null
    var transactionRows = emptyList<CardBackendTransaction>()
    var incomeFen = 0L
    var expenseFen = 0L
    var balanceCalls = 0
    val requestedPages = mutableListOf<Int>()
    val requestedMonths = mutableListOf<YearMonth?>()

    open override suspend fun balance(): CardBackendBalance {
        balanceCalls++
        balanceFailure?.let { throw it }
        return balanceResult
    }

    open override suspend fun transactions(
        month: YearMonth?,
        page: Int,
        size: Int,
    ): CardBackendTransactionPage {
        requestedPages += page
        requestedMonths += month
        transactionsFailure?.let { throw it }
        val start = (page - 1) * size
        val rows = transactionRows.drop(start).take(size)
        val pages = if (transactionRows.isEmpty()) 0 else (transactionRows.size + size - 1) / size
        return CardBackendTransactionPage(page, size, transactionRows.size, pages, rows)
    }

    open override suspend fun transactionsSummary(month: YearMonth?): CardBackendTransactionsSummary {
        summaryFailure?.let { throw it }
        requestedMonths += month
        return CardBackendTransactionsSummary(incomeFen, expenseFen)
    }
}
