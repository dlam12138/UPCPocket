package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.upcpocket.app.card.CardWidgetRead
import com.upcpocket.app.card.CardWidgetRevision
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

    private val cardPublisherLock = Any()

    @Volatile
    private var cardPublisherInstance: CardWidgetPublisher? = null

    private val unavailableCardRevision = CardWidgetRevision(
        availability = CardBindingAvailability.NO_ACCOUNT,
        bindingToken = null,
        businessEpoch = 0,
        snapshotRevision = 0,
    )

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
        when (kind) {
            WidgetKind.CARD -> cardPublisher(appContext).publishLatestNow()
            WidgetKind.POWER -> {
                val state = WidgetStateStore(appContext)
                val refreshing = state.isRefreshing(kind)
                val failed = !refreshing && state.error(kind) != null
                val snapshot = AppGraph.powerServiceOrNull?.cachedBlocking()
                val manager = AppWidgetManager.getInstance(appContext)
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
        when (kind) {
            WidgetKind.CARD -> cardPublisher(appContext).publishLatest()
            WidgetKind.POWER -> {
                val state = WidgetStateStore(appContext)
                val refreshing = state.isRefreshing(kind)
                val failed = !refreshing && state.error(kind) != null
                val snapshot = AppGraph.powerServiceOrNull?.cached()
                val manager = AppWidgetManager.getInstance(appContext)
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        renderPower(appContext, id, snapshot, refreshing, failed),
                    )
                }
            }
        }
    }

    /**
     * 所有校园卡发布共用的入口。Provider 的同步 onUpdate/resize、requestCard 的
     * suspend 刷新、Worker 的 finally 重绘都走这里；不存在直接 updateAppWidget 的旁路。
     */
    private fun cardPublisher(context: Context): CardWidgetPublisher {
        cardPublisherInstance?.let { return it }
        return synchronized(cardPublisherLock) {
            cardPublisherInstance ?: CardWidgetPublisher(
                readSuspending = {
                    AppGraph.cardServiceOrNull?.readWidgetState() ?: unavailableCardRead()
                },
                readBlocking = {
                    AppGraph.cardServiceOrNull?.readWidgetStateBlocking() ?: unavailableCardRead()
                },
                currentRevision = {
                    AppGraph.cardServiceOrNull?.revisionNow() ?: unavailableCardRevision
                },
                sink = { state -> publishCardWidgets(context, state) },
            ).also { cardPublisherInstance = it }
        }
    }

    private fun unavailableCardRead() = CardWidgetRead(
        state = CardWidgetViewState(
            status = CardBindingStatus(CardBindingAvailability.NO_ACCOUNT),
            snapshot = null,
        ),
        revision = unavailableCardRevision,
    )

    private fun publishCardWidgets(context: Context, cardState: CardWidgetViewState) {
        val ids = widgetIds(context, WidgetKind.CARD)
        if (ids.isEmpty()) return
        val state = WidgetStateStore(context)
        val refreshing = state.isRefreshing(WidgetKind.CARD)
        val failed = !refreshing && state.error(WidgetKind.CARD) != null
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            manager.updateAppWidget(
                id,
                renderCard(context, id, cardState, refreshing, failed),
            )
        }
    }

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
