package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.DirectCardSnapshot
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
 * - 页面查询成功 / 清除后由插件回调 [requestCard] / [requestPower]；
 * - WorkManager 在后台调用 [updateKind]；
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

    fun hasTargetNow(kind: WidgetKind): Boolean = when (kind) {
        WidgetKind.CARD -> AppGraph.cardService.cachedBlocking() != null
        WidgetKind.POWER -> AppGraph.powerService.cachedBlocking() != null
    }

    /** 只读缓存，供 BroadcastReceiver 主线程快速渲染；不做网络请求。 */
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
                val snapshot = AppGraph.cardService.cachedBlocking()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderCard(appContext, id, snapshot, refreshing, failed),
                    )
                }
            }
            WidgetKind.POWER -> {
                val snapshot = AppGraph.powerService.cachedBlocking()
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
                val snapshot = AppGraph.cardService.cached()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderCard(appContext, id, snapshot, refreshing, failed),
                    )
                }
            }
            WidgetKind.POWER -> {
                val snapshot = AppGraph.powerService.cached()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderPower(appContext, id, snapshot, refreshing, failed),
                    )
                }
            }
        }
    }

    private fun renderCard(
        context: Context,
        widgetId: Int,
        snapshot: DirectCardSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
    ) = CardWidgetRenderer(context).views(
        snapshot = snapshot,
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
