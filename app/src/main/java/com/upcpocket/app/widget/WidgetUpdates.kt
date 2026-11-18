package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import com.upcpocket.app.card.CardWidgetRead
import com.upcpocket.app.card.CardWidgetRevision
import com.upcpocket.app.card.CardWidgetViewState
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import com.upcpocket.app.timetable.TimetableWidgetLayout
import com.upcpocket.app.timetable.TimetableWidgetRenderer
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import com.upcpocket.app.todo.TodoWidgetActions
import com.upcpocket.app.todo.TodoWidgetLayout
import com.upcpocket.app.todo.TodoWidgetRenderer
import com.upcpocket.app.todo.data.TodoWidgetSnapshotResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 把插件快照渲染到桌面。
 *
 * 宿主只负责稳定组件标识、刷新状态和 PendingIntent；余额/电量的布局、字段与
 * 文案分别由 `CardWidgetRenderer`、`PowerWidgetRenderer` 渲染。这里不再认识
 * 两个业务模型。
 *
 * - 校园卡自动取数、课表本地重绘由独立任务调度，电量仍由用户点击刷新；
 * - 页面查询成功 / 清除后由插件回调 [requestCard] / [requestPower]；
 * - 账号状态变化由宿主触发 [requestCard]，先撤下旧身份数值再等待新查询；
 * - AppWidgetProvider 的 onUpdate / resize 调用 [updateKindNow]，只读本地缓存，不联网。
 */
object WidgetUpdates {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val todoSnapshotUpdateGate = TodoWidgetSnapshotUpdateGate()
    private val todoSnapshotPublicationLock = Any()

    private val cardPublisherLock = Any()

    @Volatile
    private var cardPublisherInstance: CardWidgetPublisher? = null

    @Volatile
    private var cachedTodoSnapshot: TodoWidgetSnapshotResult? = null

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

    fun requestTodo(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (widgetIds(appContext, WidgetKind.TODO).isEmpty()) return@launch
            runCatching { updateTodoSnapshot(appContext) }
        }
    }

    /** Appearance changes only re-render local snapshots and do not alter refresh cadence. */
    fun requestAppearanceRedraw(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            listOf(WidgetKind.CARD, WidgetKind.POWER, WidgetKind.TIMETABLE, WidgetKind.TODO)
                .forEach { kind ->
                    runCatching { updateKind(appContext, kind, scheduleTicker = false) }
                }
        }
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
        WidgetKind.TODO -> AppGraph.todoStoreOrNull != null
    }

    /** 只给本地跨日/上下课边界重排使用；只读缓存，不联网。 */
    fun timetableSnapshotForSchedule(context: Context): TimetableWidgetSnapshot? =
        AppGraph.timetableServiceOrNull?.readWidgetSnapshot()

    internal fun todoSnapshotForSchedule(): TodoWidgetSnapshotResult? = cachedTodoSnapshot

    /** 只读缓存，供 BroadcastReceiver 主线程快速渲染；不做网络请求、不迁移旧文件。 */
    fun updateKindNow(context: Context, kind: WidgetKind, scheduleTicker: Boolean = true) {
        val appContext = context.applicationContext
        val ids = widgetIds(appContext, kind)
        if (ids.isEmpty()) {
            if (kind == WidgetKind.TIMETABLE && scheduleTicker) TimetableWidgetTicker.schedule(appContext)
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
                widgetEntries(appContext, kind).forEach { (id, variant) ->
                    manager.updateAppWidget(
                        id,
                        renderPower(appContext, id, variant, snapshot, refreshing, failed),
                    )
                }
            }
            WidgetKind.TIMETABLE -> publishTimetable(appContext)
            WidgetKind.TODO -> {
                val snapshot = publishCachedTodoWidgets(appContext)
                if (snapshot == TodoWidgetSnapshotResult.NotRestored) {
                    TodoWidgetTicker.requestLocalRecovery(appContext)
                }
            }
        }
        if (kind == WidgetKind.TIMETABLE && scheduleTicker) TimetableWidgetTicker.schedule(appContext)
    }

    suspend fun updateKind(
        context: Context,
        kind: WidgetKind,
        scheduleTicker: Boolean = true,
    ) {
        val appContext = context.applicationContext
        val ids = widgetIds(appContext, kind)
        if (ids.isEmpty()) {
            if (kind == WidgetKind.TIMETABLE && scheduleTicker) {
                TimetableWidgetTicker.schedule(appContext)
            }
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
                widgetEntries(appContext, kind).forEach { (id, variant) ->
                    manager.updateAppWidget(
                        id,
                        renderPower(appContext, id, variant, snapshot, refreshing, failed),
                    )
                }
            }
            WidgetKind.TIMETABLE -> publishTimetable(appContext)
            WidgetKind.TODO -> updateTodoSnapshot(appContext, scheduleTicker = scheduleTicker)
        }
        if (kind == WidgetKind.TIMETABLE && scheduleTicker) {
            TimetableWidgetTicker.schedule(appContext)
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
        val instances = widgetEntries(context, WidgetKind.CARD)
        if (instances.isEmpty()) return
        val state = WidgetStateStore(context)
        val refreshing = state.isRefreshing(WidgetKind.CARD)
        val failed = !refreshing && state.error(WidgetKind.CARD) != null
        val manager = AppWidgetManager.getInstance(context)
        instances.forEach { (id, variant) ->
            manager.updateAppWidget(
                id,
                renderCard(context, id, variant, cardState, refreshing, failed),
            )
        }
    }

    private fun renderCard(
        context: Context,
        widgetId: Int,
        variant: WidgetProviderVariant,
        state: CardWidgetViewState,
        refreshing: Boolean,
        failed: Boolean,
    ) = CardWidgetRenderer(context).views(
        status = state.status,
        snapshot = state.snapshot,
        refreshing = refreshing,
        failed = failed,
        openApp = { WidgetIntents.openApp(context, WidgetLaunchTarget.CARD_BALANCE, widgetId) },
        appearance = appearanceFor(variant),
    )

    private fun renderPower(
        context: Context,
        widgetId: Int,
        variant: WidgetProviderVariant,
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
        refresh = { WidgetIntents.refresh(context, WidgetKind.POWER, widgetId, variant) },
        appearance = appearanceFor(variant),
    )

    private fun publishTimetable(context: Context) {
        val instances = widgetEntries(context, WidgetKind.TIMETABLE)
        if (instances.isEmpty()) return
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
        instances.forEach { (id, variant) ->
            val size = widgetSize(manager, id)
            val layout = TimetableWidgetLayout.forSize(
                widthDp = size.widthDp,
                heightDp = size.heightDp,
                fontScale = context.resources.configuration.fontScale,
            )
            Log.d("UPCWidgetSize", "layout=$layout size=$size")
            Diagnostics.record(
                module = DiagnosticModule.WIDGET,
                action = DiagnosticAction.WIDGET_LAYOUT,
                result = DiagnosticResult.OK,
                reason = DiagnosticReason.LAYOUT,
                widthDp = size.widthDp,
                heightDp = size.heightDp,
            )
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
                    appearance = appearanceFor(variant),
                ),
            )
        }
    }

    /** Read the active Todo capability off the receiver thread, retain the snapshot in memory, and redraw every live instance. */
    internal suspend fun updateTodoSnapshot(
        context: Context,
        scheduleTicker: Boolean = true,
        restoreIfNotRestored: Boolean = false,
    ) {
        val appContext = context.applicationContext
        todoSnapshotUpdateGate.withSerializedUpdate {
            val store = AppGraph.todoStoreOrNull
            val snapshot = if (store == null) {
                null
            } else {
                try {
                    withTimeout(TODO_READ_BUDGET_MS) {
                        var current = store.readWidgetSnapshot()
                        if (restoreIfNotRestored && current == TodoWidgetSnapshotResult.NotRestored) {
                            store.reconcileAll()
                            current = store.readWidgetSnapshot()
                        }
                        current
                    }
                } catch (timeout: TimeoutCancellationException) {
                    TodoWidgetSnapshotResult.ReadFailed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    TodoWidgetSnapshotResult.ReadFailed
                }
            }
            publishTodoSnapshot(appContext, snapshot, scheduleTicker)
        }
    }

    private fun publishTodoSnapshot(
        context: Context,
        snapshot: TodoWidgetSnapshotResult?,
        scheduleTicker: Boolean,
    ) {
        synchronized(todoSnapshotPublicationLock) {
            cachedTodoSnapshot = snapshot
            publishTodoWidgets(context.applicationContext, snapshot)
        }
        if (scheduleTicker) {
            if (snapshot == TodoWidgetSnapshotResult.NotRestored) {
                TodoWidgetTicker.requestLocalRecovery(context)
            } else {
                TodoWidgetTicker.schedule(context)
            }
        }
    }

    private fun publishCachedTodoWidgets(context: Context): TodoWidgetSnapshotResult? =
        synchronized(todoSnapshotPublicationLock) {
            val snapshot = if (AppGraph.todoStoreOrNull == null) {
                null
            } else {
                cachedTodoSnapshot ?: TodoWidgetSnapshotResult.NotRestored
            }
            publishTodoWidgets(context.applicationContext, snapshot)
            snapshot
        }

    private fun publishTodoWidgets(
        context: Context,
        snapshot: TodoWidgetSnapshotResult?,
    ) {
        val instances = widgetEntries(context, WidgetKind.TODO)
        if (instances.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        instances.forEach { (id, variant) ->
            val size = widgetSize(manager, id)
            val layout = TodoWidgetLayout.forSize(
                widthDp = size.widthDp,
                heightDp = size.heightDp,
                fontScale = context.resources.configuration.fontScale,
            )
            val views = TodoWidgetRenderer(context).views(
                snapshot = snapshot,
                layout = layout,
                actions = TodoWidgetActions(
                    openTodo = WidgetIntents.openApp(context, WidgetLaunchTarget.TODO, id),
                    createTask = WidgetIntents.createTodoTask(context, id),
                    openTask = { taskId -> WidgetIntents.openTodoTask(context, id, taskId) },
                    changeCompleted = { taskId, revision ->
                        WidgetIntents.completeTodoTask(context, id, taskId, revision)
                    },
                ),
                appearance = appearanceFor(variant),
            )
            manager.updateAppWidget(id, views)
        }
    }

    private fun widgetEntries(
        context: Context,
        kind: WidgetKind,
    ): List<Pair<Int, WidgetProviderVariant>> {
        val manager = AppWidgetManager.getInstance(context)
        return WidgetProviderVariant.forKind(kind).flatMap { variant ->
            manager.getAppWidgetIds(
                android.content.ComponentName(context, variant.providerClass),
            ).map { widgetId -> widgetId to variant }
        }
    }

    private fun appearanceFor(variant: WidgetProviderVariant) =
        variant.resolveAppearance(AppGraph.uiPrefsStore.widgetAppearanceNow())

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

    private const val TODO_READ_BUDGET_MS = 5_000L
}
