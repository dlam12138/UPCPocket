package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.upcpocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 把共享快照渲染到桌面。
 *
 * - 页面查询成功 / 清除后调用 [requestCard] / [requestPower]，异步读取同一份仓库快照；
 * - WorkManager 在后台调用 [updateKind]；
 * - AppWidgetProvider 的 onUpdate / resize 调用 [updateKindNow]，只读本地 4 KB 缓存，不联网。
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
        WidgetKind.CARD -> AppGraph.directCardBalanceRepository.cachedBlocking() != null
        WidgetKind.POWER -> AppGraph.powerReadingRepository.cachedBlocking() != null
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
                val snapshot = AppGraph.directCardBalanceRepository.cachedBlocking()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        WidgetRenderer.views(
                            context = appContext,
                            kind = kind,
                            widgetId = id,
                            card = snapshot,
                            power = null,
                            refreshing = refreshing,
                            failed = failed,
                        ),
                    )
                }
            }
            WidgetKind.POWER -> {
                val snapshot = AppGraph.powerReadingRepository.cachedBlocking()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        WidgetRenderer.views(
                            context = appContext,
                            kind = kind,
                            widgetId = id,
                            card = null,
                            power = snapshot,
                            refreshing = refreshing,
                            failed = failed,
                        ),
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
                val snapshot = AppGraph.directCardBalanceRepository.cached()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        WidgetRenderer.views(
                            context = appContext,
                            kind = kind,
                            widgetId = id,
                            card = snapshot,
                            power = null,
                            refreshing = refreshing,
                            failed = failed,
                        ),
                    )
                }
            }
            WidgetKind.POWER -> {
                val snapshot = AppGraph.powerReadingRepository.cached()
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        WidgetRenderer.views(
                            context = appContext,
                            kind = kind,
                            widgetId = id,
                            card = null,
                            power = snapshot,
                            refreshing = refreshing,
                            failed = failed,
                        ),
                    )
                }
            }
        }
    }

    private fun widgetIds(context: Context, kind: WidgetKind): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, kind.providerClass))
}
