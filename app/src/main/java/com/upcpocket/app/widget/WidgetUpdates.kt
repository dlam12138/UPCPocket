package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.upcpocket.app.card.CardWidgetViewState
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.power.PowerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 把插件快照渲染到桌面。
 *
 * 宿主只负责稳定组件标识、刷新状态和 PendingIntent；余额/电量的布局、字段与
 * 文案分别由 `CardWidgetRenderer`、`PowerWidgetRenderer` 渲染。这里不再认识
 * 两个业务模型。
 *
 * - 只有用户点击刷新才入队任务；已绑定但无历史余额也允许点击查询；
 * - 页面查询成功 / 清除后由插件回调 [requestCard] / [requestPower]；
 * - 账号状态变化由宿主触发 [requestCard]，先撤下旧身份数值再等待新查询；
 * - AppWidgetProvider 的 onUpdate / resize 调用 [updateKindNow]，只读本地缓存，不联网。
 */
object WidgetUpdates {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requestCard(context: Context) {
        val appContext = context.applicationContext
        scope.launch { runCatching { updateKind(appContext, WidgetKind.CARD) } }
    }

    fun requestPower(context: Context) {
        val appContext = context.applicationContext
        scope.launch { runCatching { updateKind(appContext, WidgetKind.POWER) } }
    }

    /**
     * 是否允许点击刷新。余额看当前绑定而不是旧成功快照；无绑定/清理中不得联网。
     */
    fun hasTargetNow(kind: WidgetKind): Boolean = when (kind) {
        WidgetKind.CARD -> AppGraph.cardServiceOrNull?.canRefreshNow() == true
        WidgetKind.POWER -> AppGraph.powerServiceOrNull?.cachedBlocking() != null
    }

    /** 只读缓存，供 BroadcastReceiver 主线程快速渲染；不做网络请求、不迁移旧文件。 */
    fun updateKindNow(context: Context, kind: WidgetKind) {
        val appContext = context.applicationContext
        val ids = widgetIds(appContext, kind)
        if (ids.isEmpty()) return
        val state = WidgetStateStore(appContext)
        val refreshing = state.isRefreshing(kind)
        val failed = !refreshing && state.error(kind) != null
        val manager = AppWidgetManager.getInstance(appContext)

        when (kind) {
            WidgetKind.CARD -> {
                val cardState = AppGraph.cardServiceOrNull?.widgetStateNow() ?: unavailableCardState()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderCard(appContext, id, cardState, refreshing, failed),
                    )
                }
            }
            WidgetKind.POWER -> {
                val snapshot = AppGraph.powerServiceOrNull?.cachedBlocking()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderPower(appContext, id, snapshot, refreshing, failed),
                    )
                }
            }
        }
    }

    suspend fun updateKind(context: Context, kind: WidgetKind) {
        val appContext = context.applicationContext
        val ids = widgetIds(appContext, kind)
        if (ids.isEmpty()) return
        val state = WidgetStateStore(appContext)
        val refreshing = state.isRefreshing(kind)
        val failed = !refreshing && state.error(kind) != null
        val manager = AppWidgetManager.getInstance(appContext)

        when (kind) {
            WidgetKind.CARD -> {
                val service = AppGraph.cardServiceOrNull
                val cardState = if (service == null) {
                    unavailableCardState()
                } else {
                    CardWidgetViewState(service.bindingStatus(), service.cached())
                }
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderCard(appContext, id, cardState, refreshing, failed),
                    )
                }
            }
            WidgetKind.POWER -> {
                val snapshot = AppGraph.powerServiceOrNull?.cached()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderPower(appContext, id, snapshot, refreshing, failed),
                    )
                }
            }
        }
    }

    private fun unavailableCardState() = CardWidgetViewState(
        status = CardBindingStatus(CardBindingAvailability.NO_ACCOUNT),
        snapshot = null,
    )

    private fun renderCard(
        context: Context,
        widgetId: Int,
        state: CardWidgetViewState,
        refreshing: Boolean,
        failed: Boolean,
    ) = CardWidgetRenderer(context).views(
        status = state.status,
        snapshot = state.snapshot,
        refreshing = refreshing,
        failed = failed,
        openApp = { WidgetIntents.openApp(context, WidgetLaunchTarget.CARD_BALANCE, widgetId) },
        refresh = { WidgetIntents.refresh(context, WidgetKind.CARD, widgetId) },
    )

    private fun renderPower(
        context: Context,
        widgetId: Int,
        snapshot: PowerSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
    ) = PowerWidgetRenderer(context).views(
        snapshot = snapshot,
        refreshing = refreshing,
        failed = failed,
        openApp = {
            WidgetIntents.openApp(context, WidgetLaunchTarget.POWER_TANGDAOWAN, widgetId)
        },
        refresh = { WidgetIntents.refresh(context, WidgetKind.POWER, widgetId) },
    )

    private fun widgetIds(context: Context, kind: WidgetKind): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, kind.providerClass))
}
