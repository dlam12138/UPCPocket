package com.upcpocket.app.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.SharedPreferences

/**
 * 两个桌面组件各自的刷新身份。
 *
 * 组件通知、唯一任务名、状态键和 PendingIntent action 全部按类型隔离：
 * 刷校园卡不会碰电费，反之亦然。
 */
enum class WidgetKind(
    val key: String,
    val refreshAction: String,
    val providerClass: Class<out AppWidgetProvider>,
    val uniqueWorkName: String,
) {
    CARD(
        key = "card",
        refreshAction = "com.upcpocket.app.action.REFRESH_CARD_WIDGET",
        providerClass = CardBalanceWidgetProvider::class.java,
        uniqueWorkName = "widget-refresh-card",
    ),
    POWER(
        key = "power",
        refreshAction = "com.upcpocket.app.action.REFRESH_POWER_WIDGET",
        providerClass = PowerWidgetProvider::class.java,
        uniqueWorkName = "widget-refresh-power",
    ),
    ;

    companion object {
        fun fromKey(value: String?): WidgetKind? = entries.firstOrNull { it.key == value }
    }
}

/**
 * 组件按钮的小状态：是否正在刷新、上一次失败提示。
 *
 * 只是界面反馈，不是业务数据；写入应用私有 SharedPreferences。刷新开始时间也用于
 * 进程被杀后的恢复：超过 [STALE_MS] 视为过期，避免永久卡在“正在刷新”。
 */
@SuppressLint("ApplySharedPref")
class WidgetStateStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun tryBegin(kind: WidgetKind, now: Long = System.currentTimeMillis()): Boolean {
        val started = prefs.getLong(refreshingKey(kind), 0L)
        if (started > 0L && now - started in 0 until STALE_MS) return false
        prefs.edit()
            .putLong(refreshingKey(kind), now)
            .remove(errorKey(kind))
            .commit()
        return true
    }

    @Synchronized
    fun isRefreshing(kind: WidgetKind, now: Long = System.currentTimeMillis()): Boolean {
        val started = prefs.getLong(refreshingKey(kind), 0L)
        if (started <= 0L) return false
        if (now - started !in 0 until STALE_MS) {
            prefs.edit().remove(refreshingKey(kind)).commit()
            return false
        }
        return true
    }

    @Synchronized
    fun clearRefreshing(kind: WidgetKind) {
        prefs.edit().remove(refreshingKey(kind)).commit()
    }

    @Synchronized
    fun error(kind: WidgetKind): String? =
        prefs.getString(errorKey(kind), null)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun setError(kind: WidgetKind, message: String?) {
        val safe = message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_ERROR_LENGTH)
            ?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (safe == null) remove(errorKey(kind)) else putString(errorKey(kind), safe)
        }.commit()
    }

    @Synchronized
    fun clear(kind: WidgetKind) {
        prefs.edit()
            .remove(refreshingKey(kind))
            .remove(errorKey(kind))
            .commit()
    }

    private fun refreshingKey(kind: WidgetKind) = "refreshing_${kind.key}"

    private fun errorKey(kind: WidgetKind) = "error_${kind.key}"

    companion object {
        /** 网络请求总预算 45 秒；留出调度余量后超过 90 秒的状态一律视为失效。 */
        const val STALE_MS = 90_000L
        private const val MAX_ERROR_LENGTH = 80
        private const val PREFS_NAME = "widget_state"
    }
}
