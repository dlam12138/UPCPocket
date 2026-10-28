package com.upcpocket.app.data.balance

import java.time.Instant

/**
 * 余额类业务的统一状态。
 *
 * 刻意把这几件事分开，避免界面把“没读到”显示成 0：
 *  - [NeverQueried]：还没查过；
 *  - [NeedsLogin]：需要用户在学校官方页面登录；
 *  - [Failed]：这次失败了，但可能还有上一次成功的值（见 [lastSuccess]）；
 *  - [Ready]：本次查询成功。
 */
sealed interface BalanceStatus {
    data object NeverQueried : BalanceStatus
    data class NeedsLogin(val reason: String) : BalanceStatus
    data class Failed(val message: String) : BalanceStatus
    data object Ready : BalanceStatus
}

/** 上一次成功的值：**只用于展示**，永远带着时间，绝不冒充实时结果。 */
data class BalanceSnapshot<T>(
    val value: T,
    val fetchedAt: Instant,
    val account: String,
)

data class BalanceUiState<T>(
    val status: BalanceStatus = BalanceStatus.NeverQueried,
    val loading: Boolean = false,
    val value: T? = null,
    val fetchedAt: Instant? = null,
    val lastSuccess: BalanceSnapshot<T>? = null,
    val account: String = "",
) {
    val hasValue: Boolean get() = value != null
    val isStale: Boolean get() = value == null && lastSuccess != null
}
