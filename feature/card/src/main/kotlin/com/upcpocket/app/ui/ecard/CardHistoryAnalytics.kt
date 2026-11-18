package com.upcpocket.app.ui.ecard

import com.upcpocket.app.data.cardhistory.CardHistoryRow
import java.math.BigDecimal

internal enum class CardHistoryDirectionFilter(val label: String) {
    ALL("全部"),
    EXPENSE("仅支出"),
    INCOME("仅收入"),
}

internal data class CardHistoryCategoryTotal(
    val label: String,
    val expenseFen: Long,
    val count: Int,
)

internal data class CardHistoryDailyExpense(
    val dateKey: String,
    val shortLabel: String,
    val expenseFen: Long,
)

internal data class CardHistoryAnalytics(
    val loadedIncomeFen: Long,
    val loadedExpenseFen: Long,
    val categories: List<CardHistoryCategoryTotal>,
    val dailyExpenses: List<CardHistoryDailyExpense>,
) {
    companion object {
        fun fromRows(rows: List<CardHistoryRow>): CardHistoryAnalytics {
            var income = 0L
            var expense = 0L
            val categoryMap = LinkedHashMap<String, Pair<Long, Int>>()
            val dayMap = sortedMapOf<String, Long>()

            rows.forEach { row ->
                if (row.amountFen >= 0L) {
                    income += row.amountFen
                } else {
                    val spent = -row.amountFen
                    expense += spent
                    val category = classifyMerchant(row.title)
                    val prev = categoryMap[category] ?: (0L to 0)
                    categoryMap[category] = (prev.first + spent) to (prev.second + 1)
                    extractDateKey(row.time)?.let { dateKey ->
                        dayMap[dateKey] = (dayMap[dateKey] ?: 0L) + spent
                    }
                }
            }

            val categories = categoryMap.entries
                .map { (label, pair) -> CardHistoryCategoryTotal(label, pair.first, pair.second) }
                .sortedByDescending { it.expenseFen }

            val recentDays = dayMap.entries.toList().takeLast(7).map { (dateKey, amount) ->
                val shortLabel = if (dateKey.length >= 10) dateKey.substring(5, 10) else dateKey
                CardHistoryDailyExpense(dateKey = dateKey, shortLabel = shortLabel, expenseFen = amount)
            }

            return CardHistoryAnalytics(
                loadedIncomeFen = income,
                loadedExpenseFen = expense,
                categories = categories,
                dailyExpenses = recentDays,
            )
        }

        internal fun classifyMerchant(title: String): String {
            val normalized = title.trim()
            return when {
                listOf("餐", "食", "面", "饭", "饺", "粉", "饼", "茶", "咖啡", "饮", "奶", "烤", "锅").any {
                    normalized.contains(it, ignoreCase = true)
                } -> "餐饮美食"
                listOf("超", "店", "便利", "百货", "书", "文具", "印").any {
                    normalized.contains(it, ignoreCase = true)
                } -> "商超服务"
                listOf("浴", "澡", "水", "洗", "洗衣", "开水").any {
                    normalized.contains(it, ignoreCase = true)
                } -> "洗浴水控"
                listOf("电", "网", "费", "充").any {
                    normalized.contains(it, ignoreCase = true)
                } -> "电费网费"
                else -> "其他消费"
            }
        }

        private fun extractDateKey(time: String): String? {
            val match = Regex("(\\d{4}-\\d{2}-\\d{2})").find(time) ?: return null
            return match.groupValues[1]
        }

        internal fun formatYuan(fen: Long): String = BigDecimal.valueOf(fen, 2).toPlainString()
    }
}
