package com.upcpocket.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.upcpocket.app.card.CardWidgetRead
import com.upcpocket.app.card.CardWidgetRevision
import com.upcpocket.app.card.CardWidgetViewState
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import com.upcpocket.app.timetable.TIMETABLE_ZONE
import com.upcpocket.app.timetable.TimetableService
import com.upcpocket.app.timetable.TimetableTodayTomorrowWidgetLayout
import com.upcpocket.app.timetable.TimetableTodayTomorrowWidgetRenderer
import com.upcpocket.app.timetable.TimetableTodayTomorrowWidgetSnapshot
import com.upcpocket.app.timetable.TimetableTodayTomorrowWidgetStatus
import com.upcpocket.app.timetable.TimetableWeekWidgetLayout
import com.upcpocket.app.timetable.TimetableWeekWidgetRenderer
import com.upcpocket.app.timetable.TimetableWeekWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWeekWidgetStatus
import com.upcpocket.app.timetable.TimetableWidgetLayout
import com.upcpocket.app.timetable.TimetableWidgetRenderer
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import com.upcpocket.app.todo.TodoWidgetActions
import com.upcpocket.app.todo.TodoWidgetLayout
import com.upcpocket.app.todo.TodoWidgetRenderer
import com.upcpocket.app.todo.data.TodoWidgetSnapshotResult
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 只在内存保留最近一次提交锁内复核过的周快照；不新增持久化。 */
    @Volatile
    private var cachedWeekSnapshot: TimetableWeekWidgetSnapshot? = null
    /** 今明版同样只保留提交锁内复核过的快照，与周版共享读取与发布锁。 */
    @Volatile
    private var cachedTodayTomorrowSnapshot: TimetableTodayTomorrowWidgetSnapshot? = null
    /** 串行课表磁盘读取与等待；周版和今明版同时存在时只读一次。读取期间不持有发布锁。 */
    private val calendarSnapshotReadLock = Mutex()
    /**
     * 周版与今明版共用的发布短锁。撤下旧内容与读后发布都必须在锁内复核绑定/
     * 凭据代次/业务代次/日期/缓存写入代次再提交 RemoteViews，避免旧读取在清理
     * 画空之后又画回旧课名。锁内只做内存复核和 RemoteViews 构建，不做磁盘 I/O。
     */
    private val calendarPublicationLock = Any()

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
        scope.launch {
            runCatching {
                updateKind(appContext, WidgetKind.TIMETABLE, forceTimetableCalendarRead = true)
            }
        }
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
            WidgetKind.TIMETABLE -> publishTimetableNow(appContext)
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
        forceTimetableCalendarRead: Boolean = false,
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
            WidgetKind.TIMETABLE -> publishTimetable(
                appContext,
                forceCalendarRead = forceTimetableCalendarRead,
            )
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

    /** 挂起刷新：今日、今明与周版都完成本地读取后再安排下一次边界。 */
    private suspend fun publishTimetable(context: Context, forceCalendarRead: Boolean) {
        val instances = widgetEntries(context, WidgetKind.TIMETABLE)
        if (instances.isEmpty()) return
        val todayInstances = instances.filter { it.second.isTimetableTodayVariant }
        if (todayInstances.isNotEmpty()) publishTodayWidgets(context, todayInstances)
        val weekInstances = instances.filter {
            it.second == WidgetProviderVariant.TIMETABLE_WEEK
        }
        val todayTomorrowInstances = instances.filter {
            it.second == WidgetProviderVariant.TIMETABLE_TODAY_TOMORROW
        }
        if (weekInstances.isEmpty() && todayTomorrowInstances.isEmpty()) return

        // 先撤下不再可信的旧内容，再等待本次缓存读取；读取不在 Receiver 线程。
        if (weekInstances.isNotEmpty()) publishImmediateWeek(context)
        if (todayTomorrowInstances.isNotEmpty()) {
            publishImmediateTodayTomorrow(context)
        }
        refreshCalendarWidgets(context, forceRead = forceCalendarRead)
    }

    /**
     * Receiver 同步路径：今日快照沿用原有同步读取；今明与周版只立即发布内存中
     * 仍有效的快照或占位状态，把缓存重读放到后台协程，避免阻塞主线程。两种扩展
     * 组件同时存在时后台只读一次缓存。
     */
    private fun publishTimetableNow(context: Context) {
        val instances = widgetEntries(context, WidgetKind.TIMETABLE)
        if (instances.isEmpty()) return
        val todayInstances = instances.filter { it.second.isTimetableTodayVariant }
        if (todayInstances.isNotEmpty()) publishTodayWidgets(context, todayInstances)
        val weekInstances = instances.filter {
            it.second == WidgetProviderVariant.TIMETABLE_WEEK
        }
        val todayTomorrowInstances = instances.filter {
            it.second == WidgetProviderVariant.TIMETABLE_TODAY_TOMORROW
        }
        if (weekInstances.isEmpty() && todayTomorrowInstances.isEmpty()) return
        if (weekInstances.isNotEmpty()) publishImmediateWeek(context)
        if (todayTomorrowInstances.isNotEmpty()) {
            publishImmediateTodayTomorrow(context)
        }
        val appContext = context.applicationContext
        scope.launch {
            runCatching { refreshCalendarWidgets(appContext, forceRead = false) }
        }
    }

    private fun publishTodayWidgets(
        context: Context,
        instances: List<Pair<Int, WidgetProviderVariant>>,
    ) {
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

    private fun publishWeekWidgets(
        context: Context,
        instances: List<Pair<Int, WidgetProviderVariant>>,
        snapshot: TimetableWeekWidgetSnapshot,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val fontScale = context.resources.configuration.fontScale
        instances.forEach { (id, variant) ->
            val size = calendarWidgetSize(manager, id)
            val layout = TimetableWeekWidgetLayout.forSize(
                widthDp = size.widthDp,
                heightDp = size.heightDp,
                fontScale = fontScale,
            )
            Log.d("UPCWidgetSize", "week layout=$layout size=$size")
            manager.updateAppWidget(
                id,
                TimetableWeekWidgetRenderer.render(
                    context = context,
                    snapshot = snapshot,
                    layout = layout,
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

    private fun publishTodayTomorrowWidgets(
        context: Context,
        instances: List<Pair<Int, WidgetProviderVariant>>,
        snapshot: TimetableTodayTomorrowWidgetSnapshot,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val fontScale = context.resources.configuration.fontScale
        instances.forEach { (id, variant) ->
            val openIntent = WidgetIntents.openApp(
                context,
                WidgetLaunchTarget.TIMETABLE,
                id,
            )
            val appearance = appearanceFor(variant)
            val options = manager.getAppWidgetOptions(id)
            val sizes = todayTomorrowWidgetSizeCandidates(
                reportedSizes = readAppWidgetSizes(options),
                minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
                minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
                maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
            )
            val remoteViews = if (sizes.size >= 2) {
                val responsiveViews = linkedMapOf<SizeF, RemoteViews>()
                sizes.forEach { size ->
                    responsiveViews[size] = renderTodayTomorrowWidget(
                        context = context,
                        widgetId = id,
                        snapshot = snapshot,
                        size = size,
                        fontScale = fontScale,
                        openIntent = openIntent,
                        appearance = appearance,
                    )
                }
                RemoteViews(responsiveViews)
            } else {
                renderTodayTomorrowWidget(
                    context = context,
                    widgetId = id,
                    snapshot = snapshot,
                    size = sizes.singleOrNull(),
                    fontScale = fontScale,
                    openIntent = openIntent,
                    appearance = appearance,
                )
            }
            manager.updateAppWidget(id, remoteViews)
        }
    }

    private fun renderTodayTomorrowWidget(
        context: Context,
        widgetId: Int,
        snapshot: TimetableTodayTomorrowWidgetSnapshot,
        size: SizeF?,
        fontScale: Float,
        openIntent: PendingIntent,
        appearance: WidgetAppearance,
    ): RemoteViews {
        // SizeF retains fractional dp for RemoteViews selection; layout sizing rounds down so
        // the row count never assumes more space than the host actually supplied.
        val widthDp = size?.width?.toInt() ?: 0
        val heightDp = size?.height?.toInt() ?: 0
        val layout = TimetableTodayTomorrowWidgetLayout.forSize(
            widthDp = widthDp,
            heightDp = heightDp,
            fontScale = fontScale,
        )
        Log.d(
            "UPCWidgetSize",
            "id=$widgetId todayTomorrow layout=$layout size=${size ?: "default"}",
        )
        return TimetableTodayTomorrowWidgetRenderer.render(
            context = context,
            snapshot = snapshot,
            layout = layout,
            openIntent = openIntent,
            appearance = appearance,
        )
    }

    /**
     * 宿主拿不到课表服务时的今明状态：保留真实上海今明日期，但明确呈现
     * “课表功能不可用”，不能伪装成未同步或两天无课，也不读缓存/不联网。
     */
    private fun unavailableTodayTomorrowSnapshot(): TimetableTodayTomorrowWidgetSnapshot {
        val today = LocalDate.now(TIMETABLE_ZONE)
        return TimetableTodayTomorrowWidgetSnapshot(
            status = TimetableTodayTomorrowWidgetStatus.UNAVAILABLE,
            today = today,
            tomorrow = today.plusDays(1),
        )
    }

    /** 今明版立即发布：与周版共用发布锁，失效快照必须换成日期真实的状态。 */
    private fun publishImmediateTodayTomorrow(context: Context) =
        synchronized(calendarPublicationLock) {
            val appContext = context.applicationContext
            val service = AppGraph.timetableServiceOrNull
            val cached = cachedTodayTomorrowSnapshot
            val snapshot = when {
                service == null -> {
                    cachedTodayTomorrowSnapshot = null
                    unavailableTodayTomorrowSnapshot()
                }
                cached != null && service.isTodayTomorrowWidgetSnapshotCurrent(cached) -> cached
                else -> {
                    cachedTodayTomorrowSnapshot = null
                    service.todayTomorrowWidgetPlaceholderNow()
                }
            }
            publishTodayTomorrowWidgets(
                appContext,
                currentCalendarInstances(appContext, WidgetProviderVariant.TIMETABLE_TODAY_TOMORROW),
                snapshot,
            )
        }

    /** 今明版读后提交：锁内复核候选快照，过期读取只发布占位状态。 */
    private fun commitTodayTomorrowRead(
        context: Context,
        read: TimetableTodayTomorrowWidgetSnapshot,
    ) = synchronized(calendarPublicationLock) {
        val appContext = context.applicationContext
        val service = AppGraph.timetableServiceOrNull
        val snapshot = if (!read.isReadResult) {
            read
        } else if (service != null && service.isTodayTomorrowWidgetSnapshotCurrent(read)) {
            cachedTodayTomorrowSnapshot = read
            read
        } else {
            cachedTodayTomorrowSnapshot = null
            service?.todayTomorrowWidgetPlaceholderNow() ?: unavailableTodayTomorrowSnapshot()
        }
        publishTodayTomorrowWidgets(
            appContext,
            currentCalendarInstances(appContext, WidgetProviderVariant.TIMETABLE_TODAY_TOMORROW),
            snapshot,
        )
    }

    private fun unavailableWeekSnapshot(): TimetableWeekWidgetSnapshot {
        val today = LocalDate.now(TIMETABLE_ZONE)
        return TimetableWeekWidgetSnapshot(
            status = TimetableWeekWidgetStatus.NEEDS_SYNC,
            weekStart = TimetableWeekWidgetSnapshot.weekStartOf(today),
            today = today,
        )
    }

    /**
     * 立即撤下/重绘：在发布锁内复核内存快照，失效就改发不含课程的占位状态。
     * 与读后发布共用同一把锁，保证清理后的空状态不会被迟到的旧读取覆盖。
     */
    private fun publishImmediateWeek(context: Context) =
        synchronized(calendarPublicationLock) {
            val appContext = context.applicationContext
            val service = AppGraph.timetableServiceOrNull
            val cached = cachedWeekSnapshot
            val snapshot = when {
                service == null -> {
                    cachedWeekSnapshot = null
                    unavailableWeekSnapshot()
                }
                cached != null && service.isWeekWidgetSnapshotCurrent(cached) -> cached
                else -> {
                    cachedWeekSnapshot = null
                    service.weekWidgetPlaceholderNow()
                }
            }
            publishWeekWidgets(
                appContext,
                currentCalendarInstances(appContext, WidgetProviderVariant.TIMETABLE_WEEK),
                snapshot,
            )
        }

    /** 读后提交：锁内复核候选快照，过期读取只发布占位状态。 */
    private fun commitWeekRead(
        context: Context,
        read: TimetableWeekWidgetSnapshot,
    ) = synchronized(calendarPublicationLock) {
        val appContext = context.applicationContext
        val service = AppGraph.timetableServiceOrNull
        val snapshot = if (!read.isReadResult) {
            read
        } else if (service != null && service.isWeekWidgetSnapshotCurrent(read)) {
            cachedWeekSnapshot = read
            read
        } else {
            cachedWeekSnapshot = null
            service?.weekWidgetPlaceholderNow() ?: unavailableWeekSnapshot()
        }
        publishWeekWidgets(
            appContext,
            currentCalendarInstances(appContext, WidgetProviderVariant.TIMETABLE_WEEK),
            snapshot,
        )
    }

    /**
     * 发布锁内重新查询当前实例。读取/等待期间新增的实例可能只收到占位，
     * 已移除的实例也不应再收到整卡更新；因此不能沿用读取开始时捕获的列表。
     */
    private fun currentCalendarInstances(
        context: Context,
        variant: WidgetProviderVariant,
    ): List<Pair<Int, WidgetProviderVariant>> =
        widgetEntries(context, WidgetKind.TIMETABLE).filter { it.second == variant }

    /**
     * 串行读取课表缓存。周版与今明版同时需要刷新时只调用一次服务读取，两个
     * 快照来自同一份磁盘结果；磁盘读取和等待都在发布锁之外。读后分别提交，
     * 再复核绑定、凭据代次、业务代次、日期与缓存写入代次，被撤下或已被新
     * 缓存取代的读取不能把旧课程画回去。
     */
    private suspend fun refreshCalendarWidgets(context: Context, forceRead: Boolean) {
        val appContext = context.applicationContext
        calendarSnapshotReadLock.withLock {
            val instances = widgetEntries(appContext, WidgetKind.TIMETABLE)
            val weekInstances = instances.filter {
                it.second == WidgetProviderVariant.TIMETABLE_WEEK
            }
            val todayTomorrowInstances = instances.filter {
                it.second == WidgetProviderVariant.TIMETABLE_TODAY_TOMORROW
            }
            if (weekInstances.isEmpty() && todayTomorrowInstances.isEmpty()) return
            val service = AppGraph.timetableServiceOrNull
            if (service == null) {
                if (weekInstances.isNotEmpty()) publishImmediateWeek(appContext)
                if (todayTomorrowInstances.isNotEmpty()) {
                    publishImmediateTodayTomorrow(appContext)
                }
                return
            }
            val weekCurrent = weekInstances.isNotEmpty() && hasCurrentWeekSnapshot(service)
            val todayTomorrowCurrent = todayTomorrowInstances.isNotEmpty() &&
                hasCurrentTodayTomorrowSnapshot(service)
            val weekNeedsRead = weekInstances.isNotEmpty() && (forceRead || !weekCurrent)
            val todayTomorrowNeedsRead = todayTomorrowInstances.isNotEmpty() &&
                (forceRead || !todayTomorrowCurrent)
            if (!weekNeedsRead && !todayTomorrowNeedsRead) {
                // 排队等待期间新增的实例可能只收到占位；缓存已有效时也要按
                // 当前实例重绘，而不是直接返回把新实例留在读取中。
                if (weekInstances.isNotEmpty()) publishImmediateWeek(appContext)
                if (todayTomorrowInstances.isNotEmpty()) {
                    publishImmediateTodayTomorrow(appContext)
                }
                return
            }

            val read = try {
                service.readCalendarWidgetSnapshots(
                    includeWeek = weekNeedsRead,
                    includeTodayTomorrow = todayTomorrowNeedsRead,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (weekNeedsRead) {
                commitWeekRead(
                    appContext,
                    read?.week ?: service.weekWidgetPlaceholderNow(),
                )
            }
            if (todayTomorrowNeedsRead) {
                commitTodayTomorrowRead(
                    appContext,
                    read?.todayTomorrow ?: service.todayTomorrowWidgetPlaceholderNow(),
                )
            }
        }
    }

    private fun hasCurrentWeekSnapshot(
        service: TimetableService,
    ): Boolean = synchronized(calendarPublicationLock) {
        cachedWeekSnapshot?.let(service::isWeekWidgetSnapshotCurrent) == true
    }

    private fun hasCurrentTodayTomorrowSnapshot(
        service: TimetableService,
    ): Boolean = synchronized(calendarPublicationLock) {
        cachedTodayTomorrowSnapshot?.let(service::isTodayTomorrowWidgetSnapshotCurrent) == true
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
     * 本周课表保守采用组件上下界中的较小正值。横屏高度较矮时避免高估空间，
     * 保证七日行在 Launcher 提供的最小尺寸下仍能完整显示。
     */
    private fun calendarWidgetSize(
        manager: AppWidgetManager,
        widgetId: Int,
    ): WidgetSize {
        val options = manager.getAppWidgetOptions(widgetId)
        return conservativeWidgetSize(
            minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
            minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
        )
    }

    /**
     * Prefer API 31+ Launcher sizes. A lone size is supplemented by all valid legacy bounds;
     * if its lower-height bound is unknown, retain an additional safe-height layout when needed.
     * Without usable Launcher sizes, incomplete bounds use a conservative fallback.
     */
    internal fun todayTomorrowWidgetSizeCandidates(
        reportedSizes: List<SizeF?>?,
        minWidth: Int,
        maxWidth: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<SizeF> {
        val supplied = ArrayList<SizeF>(MAX_RESPONSIVE_WIDGET_SIZE_OPTIONS)
        val seen = HashSet<SizeF>()
        for (size in reportedSizes.orEmpty()) {
            if (size != null && isUsableWidgetSize(size) && seen.add(size)) {
                supplied += size
                if (supplied.size == MAX_RESPONSIVE_WIDGET_SIZE_OPTIONS) break
            }
        }
        if (supplied.size >= 2) return supplied

        val hasBothHeightBounds = minHeight > 0 && maxHeight > 0
        if (supplied.size == 1) {
            val candidates = (supplied + legacyResponsiveWidgetSizes(
                minWidth = minWidth,
                maxWidth = maxWidth,
                minHeight = minHeight,
                maxHeight = maxHeight,
            )).distinct()
            if (minHeight <= 0 &&
                candidates.all { it.height > DEFAULT_SAFE_WIDGET_HEIGHT_DP.toFloat() }
            ) {
                val safeWidth = candidates.minOf { it.width }
                return (candidates + SizeF(safeWidth, DEFAULT_SAFE_WIDGET_HEIGHT_DP.toFloat()))
                    .distinct()
                    .take(MAX_RESPONSIVE_WIDGET_SIZE_OPTIONS)
            }
            return candidates
                .distinct()
                .take(MAX_RESPONSIVE_WIDGET_SIZE_OPTIONS)
        }

        return when {
            hasBothHeightBounds -> legacyResponsiveWidgetSizes(
                minWidth = minWidth,
                maxWidth = maxWidth,
                minHeight = minHeight,
                maxHeight = maxHeight,
            )
            minHeight <= 0 && maxHeight > 0 -> legacyResponsiveWidgetSizes(
                minWidth = minWidth,
                maxWidth = maxWidth,
                minHeight = minOf(maxHeight, DEFAULT_SAFE_WIDGET_HEIGHT_DP),
                maxHeight = minOf(maxHeight, DEFAULT_SAFE_WIDGET_HEIGHT_DP),
            )
            minHeight > 0 && maxHeight <= 0 -> {
                val width = positiveOrFallback(maxWidth, minWidth)
                if (width > 0) listOf(SizeF(width.toFloat(), minHeight.toFloat())) else emptyList()
            }
            else -> emptyList()
        }.take(MAX_RESPONSIVE_WIDGET_SIZE_OPTIONS)
    }

    private fun legacyResponsiveWidgetSizes(
        minWidth: Int,
        maxWidth: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<SizeF> {
        val portraitWidth = positiveOrFallback(minWidth, maxWidth)
        val landscapeWidth = positiveOrFallback(maxWidth, minWidth)
        return buildList {
            if (portraitWidth > 0 && maxHeight > 0) {
                add(SizeF(portraitWidth.toFloat(), maxHeight.toFloat()))
            }
            if (landscapeWidth > 0 && minHeight > 0) {
                add(SizeF(landscapeWidth.toFloat(), minHeight.toFloat()))
            }
        }.distinct()
    }

    private fun isUsableWidgetSize(size: SizeF): Boolean =
        size.width.isFinite() && size.height.isFinite() && size.width > 0f && size.height > 0f

    @Suppress("DEPRECATION")
    private fun readAppWidgetSizes(options: Bundle): List<SizeF>? {
        val key = AppWidgetManager.OPTION_APPWIDGET_SIZES
        return runCatching<List<SizeF>?> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.getParcelableArrayList(key, SizeF::class.java)
            } else {
                options.getParcelableArrayList<Parcelable>(key)?.mapNotNull { it as? SizeF }
            }
        }.getOrNull()
    }

    private fun positiveOrFallback(primary: Int, fallback: Int): Int =
        primary.takeIf { it > 0 } ?: fallback.takeIf { it > 0 } ?: 0

    /** 仅供本周课表选择保守尺寸；纯函数便于单测覆盖上下界组合。 */
    internal fun conservativeWidgetSize(
        minWidth: Int,
        maxWidth: Int,
        minHeight: Int,
        maxHeight: Int,
    ): WidgetSize = WidgetSize(
        widthDp = smallerPositive(minWidth, maxWidth),
        heightDp = smallerPositive(minHeight, maxHeight),
    )

    private fun smallerPositive(first: Int, second: Int): Int = when {
        first > 0 && second > 0 -> minOf(first, second)
        first > 0 -> first
        else -> second
    }

    /**
     * 2×2 的默认密度：完整主课 + 日期/更新时间；4×2 才加次课。
     * 具体阈值集中在 [TimetableWidgetLayout.forSize]，由单测覆盖尺寸/字体组合。
     */
    internal data class WidgetSize(
        val widthDp: Int,
        val heightDp: Int,
    )

    private const val MAX_RESPONSIVE_WIDGET_SIZE_OPTIONS = 16
    private const val DEFAULT_SAFE_WIDGET_HEIGHT_DP = 140
    private const val TODO_READ_BUDGET_MS = 5_000L
}
