package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.upcpocket.app.card.CardWidgetRead
import com.upcpocket.app.card.CardWidgetRevision
import com.upcpocket.app.card.CardWidgetViewState
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.timetable.TimetableWidgetLayout
import com.upcpocket.app.timetable.TimetableWidgetRenderer
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
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

    fun requestTimetable(context: Context) {
        val appContext = context.applicationContext
        scope.launch { runCatching { updateKind(appContext, WidgetKind.TIMETABLE) } }
    }

    /**
     * 是否允许点击刷新。余额看当前绑定而不是旧成功快照；无绑定/清理中不得联网。
     * 课表在账号仍在恢复时也必须入队，由 Worker 有界等待 restore；否则冷启动点击会被
     * 这里的同步快照误判为“没有目标”而什么都不做。
     */
    fun hasTargetNow(kind: WidgetKind): Boolean = when (kind) {
        // 进程冷启动时账号状态还是 LOADING，canRefreshNow() 必然为 false；此时若直接
        // 丢弃点击，桌面刷新就永远要等用户先打开 Activity。先入队，由 Worker 有界等待
        // restore；真正的账号/绑定校验仍在服务和 Worker 内。
        WidgetKind.CARD -> AppGraph.cardServiceOrNull != null
        WidgetKind.POWER -> AppGraph.powerServiceOrNull?.cachedBlocking() != null
        WidgetKind.TIMETABLE -> AppGraph.timetableServiceOrNull != null
    }

    /** 只给本地跨日/上下课边界重排使用；只读缓存，不联网。 */
    fun timetableSnapshotForSchedule(context: Context): TimetableWidgetSnapshot? =
        AppGraph.timetableServiceOrNull?.readWidgetSnapshot()

    /** 只读缓存，供 BroadcastReceiver 主线程快速渲染；不做网络请求、不迁移旧文件。 */
    fun updateKindNow(context: Context, kind: WidgetKind) {
        val appContext = context.applicationContext
        val ids = widgetIds(appContext, kind)
        if (ids.isEmpty()) {
            if (kind == WidgetKind.TIMETABLE) TimetableWidgetTicker.schedule(appContext)
            return
        }
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
            WidgetKind.TIMETABLE -> publishTimetable(appContext)
        }
        if (kind == WidgetKind.TIMETABLE) TimetableWidgetTicker.schedule(appContext)
    }

    suspend fun updateKind(context: Context, kind: WidgetKind) {
        val appContext = context.applicationContext
        val ids = widgetIds(appContext, kind)
        if (ids.isEmpty()) {
            if (kind == WidgetKind.TIMETABLE) TimetableWidgetTicker.schedule(appContext)
            return
        }
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
            WidgetKind.TIMETABLE -> publishTimetable(appContext)
        }
        if (kind == WidgetKind.TIMETABLE) TimetableWidgetTicker.schedule(appContext)
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

    private fun publishTimetable(context: Context) {
        val ids = widgetIds(context, WidgetKind.TIMETABLE)
        if (ids.isEmpty()) return
        val state = WidgetStateStore(context)
        val snapshot = AppGraph.timetableServiceOrNull?.readWidgetSnapshot()
            ?: TimetableWidgetSnapshot(
                status = TimetableWidgetStatus.NEEDS_SYNC,
                date = null,
            )
        // 无账号/清理中本身已是明确状态，不再叠加“刷新失败”误导用户。
        val canShowRefreshState = snapshot.status == TimetableWidgetStatus.OK ||
            snapshot.status == TimetableWidgetStatus.NEEDS_SYNC
        val refreshing = canShowRefreshState && state.isRefreshing(WidgetKind.TIMETABLE)
        val failed = canShowRefreshState && !refreshing &&
            state.error(WidgetKind.TIMETABLE) != null
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            val size = widgetSize(manager, id)
            val layout = TimetableWidgetLayout.forSize(
                widthDp = size.widthDp,
                heightDp = size.heightDp,
                fontScale = context.resources.configuration.fontScale,
            )
            Log.d("UPCWidgetSize", "layout=$layout size=$size")
            manager.updateAppWidget(
                id,
                TimetableWidgetRenderer.render(
                    context = context,
                    snapshot = snapshot,
                    layout = layout,
                    refreshing = refreshing,
                    failed = failed,
                    openIntent = WidgetIntents.openApp(
                        context,
                        WidgetLaunchTarget.TIMETABLE,
                        id,
                    ),
                    refreshIntent = WidgetIntents.refresh(
                        context,
                        WidgetKind.TIMETABLE,
                        id,
                    ),
                ),
            )
        }
    }

    private fun widgetIds(context: Context, kind: WidgetKind): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, kind.providerClass))

    /**
     * 宿主给出的实际尺寸区间。当前尺寸优先用上限（横向/纵向的当前格宽），
     * 单元格估算值只作为下限兜底；绝不把 2×2 写死成某个像素高度。
     */
    private fun widgetSize(
        manager: AppWidgetManager,
        widgetId: Int,
    ): WidgetSize {
        val options = manager.getAppWidgetOptions(widgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        // 竖屏当前尺寸：宽度取下限、高度取上限（宿主预计算横竖两套范围）。
        val width = minWidth.takeIf { it > 0 } ?: maxWidth
        val height = maxHeight.takeIf { it > 0 } ?: minHeight
        Log.d(
            "UPCWidgetSize",
            "id=$widgetId min=${minWidth}x${minHeight} max=${maxWidth}x${maxHeight}",
        )
        return WidgetSize(width, height)
    }

    /**
     * 2×2 的默认密度：完整主课 + 日期/更新时间；4×2 才加次课。
     * 具体阈值集中在 [TimetableWidgetLayout.forSize]，由单测覆盖尺寸/字体组合。
     */
    private data class WidgetSize(
        val widthDp: Int,
        val heightDp: Int,
    )
}
