package com.upcpocket.app.todo

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.todo.data.TodoDatabase
import com.upcpocket.app.todo.data.TodoDeletedTask
import com.upcpocket.app.todo.data.TodoImportance
import com.upcpocket.app.todo.data.TodoMoveDirection
import com.upcpocket.app.todo.data.TodoOrdering
import com.upcpocket.app.todo.data.TodoRepository
import com.upcpocket.app.todo.data.TodoSnapshot
import com.upcpocket.app.todo.data.TodoSortMode
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoWidgetSnapshotResult
import com.upcpocket.app.todo.reminder.TodoAlarmScheduler
import com.upcpocket.app.todo.reminder.TodoNotifier
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import com.upcpocket.app.todo.reminder.TodoReminderAccessProvider
import com.upcpocket.app.todo.reminder.TodoReminderPlan
import com.upcpocket.app.todo.reminder.TodoReminderPlanner
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TodoStore {
    val tasks: Flow<List<TodoTask>>
    val pendingOpenTask: StateFlow<String?>
    val pendingCreateRequest: StateFlow<String?> get() = EMPTY_PENDING_CREATE_REQUEST

    /** 真实实现覆盖为同一 Room 读事务的任务+排序快照；旧实现只映射日期顺序。 */
    val snapshot: Flow<TodoSnapshot> get() = tasks.map { TodoSnapshot(tasks = it) }

    /** 旧实现没有排序表；UI 据此隐藏排序与重要程度控件，不能把默认快照当成可编辑。 */
    val supportsOrdering: Boolean get() = false

    fun reminderAccess(): TodoReminderAccess

    suspend fun add(title: String, reminderAt: Long?): TodoTask

    suspend fun add(title: String, reminderAt: Long?, dueAt: Long?): TodoTask {
        check(dueAt == null) { "This TodoStore does not support deadlines" }
        return add(title, reminderAt)
    }

    suspend fun add(
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        importance: TodoImportance,
    ): TodoTask {
        // 旧 Store 没有排序表，不能静默丢弃非普通重要程度后冒充保存成功。
        check(importance == TodoImportance.NORMAL) {
            "This TodoStore does not support importance"
        }
        return add(title, reminderAt, dueAt)
    }

    suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean

    suspend fun edit(id: String, title: String, reminderAt: Long?, dueAt: Long?): Boolean {
        check(dueAt == null) { "This TodoStore does not support deadlines" }
        return edit(id, title, reminderAt)
    }

    suspend fun edit(
        id: String,
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        importance: TodoImportance,
    ): Boolean = if (importance == TodoImportance.NORMAL) {
        edit(id, title, reminderAt, dueAt)
    } else {
        false
    }

    /** 仅修改重要程度；不支持排序能力的旧 Store 返回 false，不伪造成功。 */
    suspend fun setImportance(id: String, importance: TodoImportance): Boolean = false

    suspend fun setSortMode(mode: TodoSortMode): Boolean = false

    /** [expectedNeighborId] 是点按时看到的同组相邻项；实现须在事务内复核后交换。 */
    suspend fun moveTask(
        id: String,
        direction: TodoMoveDirection,
        expectedNeighborId: String,
    ): Boolean = false

    suspend fun setCompleted(id: String, completed: Boolean): Boolean

    /** Revision compare-and-set for a user toggle and its undo path. */
    suspend fun setCompletedIfRevision(
        id: String,
        revision: Int,
        completed: Boolean,
    ): TodoTask?

    suspend fun delete(id: String): TodoTask?

    suspend fun restore(task: TodoTask): Boolean

    suspend fun deleteWithOrder(id: String): TodoDeletedTask? =
        delete(id)?.let { TodoDeletedTask(task = it) }

    suspend fun restoreWithOrder(deleted: TodoDeletedTask): Boolean = restore(deleted.task)

    fun openTask(taskId: String)

    fun consumeOpenTask()

    fun openCreate(): String = throw UnsupportedOperationException("Create requests are not supported")

    fun openCreate(requestId: String): Boolean = false

    fun consumeCreateRequest() = Unit

    /** Null plugin capability is handled by the host; this result separates not-restored, failed and valid-empty. */
    suspend fun readWidgetSnapshot(maxVisibleRows: Int = 4): TodoWidgetSnapshotResult =
        TodoWidgetSnapshotResult.NotRestored

    fun ensureNotificationChannel()

    suspend fun reconcileAll()
}

private val EMPTY_PENDING_CREATE_REQUEST: StateFlow<String?> = MutableStateFlow(null)

internal interface TodoAlarmSchedulerApi {
    fun schedule(taskId: String, revision: Int, atMillis: Long, exact: Boolean)
    fun cancel(taskId: String)
}

internal interface TodoNotifierApi {
    fun ensureChannel()
    fun post(task: TodoTask, revision: Int, firedAt: Long): Boolean
    fun cancel(taskId: String)
}

internal interface TodoReminderAccessApi {
    fun current(): TodoReminderAccess
}

/**
 * 依赖只在真正使用时才创建，避免 AppGraph 装配阶段打开 Room 或通知渠道。
 * internal 构造只给同模块测试注入 fake scheduler/notifier/clock。
 */
internal class TodoServiceDependencies(
    val repository: () -> TodoRepository,
    val alarms: () -> TodoAlarmSchedulerApi,
    val notifier: () -> TodoNotifierApi,
    val accessProvider: () -> TodoReminderAccessApi,
) {
    companion object {
        fun forContext(
            context: Context,
            openAppIntent: (String) -> PendingIntent,
        ): TodoServiceDependencies {
            val appContext = context.applicationContext
            return TodoServiceDependencies(
                repository = { TodoRepository(TodoDatabase.get(appContext)) },
                alarms = { TodoAlarmScheduler(appContext) },
                notifier = { TodoNotifier(appContext, openAppIntent) },
                accessProvider = { TodoReminderAccessProvider(appContext) },
            )
        }
    }
}

/**
 * 待办数据、提醒调度和通知动作的唯一业务入口。
 *
 * Room 是事实来源；AlarmManager 与通知都是可重建的副作用。所有读写、调度和
 * 通知副作用在同一 service Mutex 内串行，外部接收器协程不会用旧快照覆盖新状态。
 * 旧 taskId+revision 动作在数据库条件更新失败时直接返回，不取消当前 Alarm/通知。
 * onTasksChanged 在 Mutex 内调用；宿主必须异步排入组件更新，不能同步重入本服务。
 */
class TodoService internal constructor(
    private val dependencies: TodoServiceDependencies,
    private val clock: () -> Long,
    private val onTasksChanged: () -> Unit,
) : TodoStore {

    internal constructor(
        dependencies: TodoServiceDependencies,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(dependencies, clock, {})

    constructor(
        context: Context,
        openAppIntent: (String) -> PendingIntent,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(TodoServiceDependencies.forContext(context, openAppIntent), clock, {})

    constructor(
        context: Context,
        onTasksChanged: () -> Unit,
        openAppIntent: (String) -> PendingIntent,
    ) : this(
        TodoServiceDependencies.forContext(context, openAppIntent),
        System::currentTimeMillis,
        onTasksChanged,
    )

    private val repository by lazy { dependencies.repository() }
    private val alarms by lazy { dependencies.alarms() }
    private val notifier by lazy { dependencies.notifier() }
    private val accessProvider by lazy { dependencies.accessProvider() }

    private val mutex = Mutex()
    private val openTaskId = MutableStateFlow<String?>(null)
    private val createRequestId = MutableStateFlow<String?>(null)
    private var restored = false
    private var restoreFailed = false

    override val tasks: Flow<List<TodoTask>> get() = repository.tasks
    override val snapshot: Flow<TodoSnapshot> get() = repository.snapshot
    override val supportsOrdering: Boolean get() = true
    override val pendingOpenTask: StateFlow<String?> = openTaskId.asStateFlow()
    override val pendingCreateRequest: StateFlow<String?> = createRequestId.asStateFlow()

    override fun ensureNotificationChannel() = notifier.ensureChannel()

    override fun reminderAccess(): TodoReminderAccess {
        ensureNotificationChannel()
        return accessProvider.current()
    }

    override suspend fun add(title: String, reminderAt: Long?): TodoTask =
        add(title, reminderAt, dueAt = null)

    override suspend fun add(title: String, reminderAt: Long?, dueAt: Long?): TodoTask =
        add(title, reminderAt, dueAt, TodoImportance.NORMAL)

    override suspend fun add(
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        importance: TodoImportance,
    ): TodoTask = mutex.withLock {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "待办标题不能为空" }
        val now = clock()
        val task = TodoTask(
            id = UUID.randomUUID().toString(),
            title = normalized,
            completed = false,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            reminderAt = reminderAt,
            reminderFiredAt = null,
            revision = 1,
            dueAt = dueAt,
        )
        repository.insertWithImportance(task, importance)
        notifyTasksChangedSafely()
        reconcileLocked(task)
        task
    }

    override suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean =
        mutex.withLock { editLocked(id, title, reminderAt, dueAt = null, preserveExistingDueAt = true) }

    override suspend fun edit(
        id: String,
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
    ): Boolean = mutex.withLock {
        editLocked(id, title, reminderAt, dueAt, preserveExistingDueAt = false)
    }

    override suspend fun edit(
        id: String,
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        importance: TodoImportance,
    ): Boolean = mutex.withLock {
        editLocked(
            id = id,
            title = title,
            reminderAt = reminderAt,
            dueAt = dueAt,
            preserveExistingDueAt = false,
            importance = importance,
        )
    }

    override suspend fun setImportance(id: String, importance: TodoImportance): Boolean =
        mutex.withLock { repository.setImportance(id, importance) }

    override suspend fun setSortMode(mode: TodoSortMode): Boolean =
        mutex.withLock { repository.setSortMode(mode) }

    override suspend fun moveTask(
        id: String,
        direction: TodoMoveDirection,
        expectedNeighborId: String,
    ): Boolean = mutex.withLock {
        repository.moveTask(id, direction, expectedNeighborId)
    }

    private suspend fun editLocked(
        id: String,
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        preserveExistingDueAt: Boolean,
        importance: TodoImportance? = null,
    ): Boolean {
        val normalized = title.trim()
        if (normalized.isEmpty()) return false
        val old = repository.byId(id) ?: return false
        val resolvedDueAt = if (preserveExistingDueAt) old.dueAt else dueAt
        // 纯改重要程度由服务在锁内识别：不增加 revision、不改提醒时间、不重建调度。
        if (
            importance != null &&
            normalized == old.title &&
            reminderAt == old.reminderAt &&
            resolvedDueAt == old.dueAt
        ) {
            return repository.setImportance(id, importance)
        }
        val reminderChanged = old.reminderAt != reminderAt
        val firedAt = if (reminderChanged) null else old.reminderFiredAt
        val updated = if (importance == null) {
            repository.updateContent(
                id = id,
                title = normalized,
                reminderAt = reminderAt,
                dueAt = resolvedDueAt,
                firedAt = firedAt,
                now = clock(),
            )
        } else {
            repository.updateContentWithImportance(
                id = id,
                title = normalized,
                reminderAt = reminderAt,
                dueAt = resolvedDueAt,
                firedAt = firedAt,
                now = clock(),
                importance = importance,
            )
        } ?: return false
        notifyTasksChangedSafely()
        alarms.cancel(id)
        notifier.cancel(id)
        if (!updated.completed) reconcileLocked(updated)
        return true
    }

    override suspend fun setCompleted(id: String, completed: Boolean): Boolean =
        mutex.withLock {
            val current = repository.byId(id) ?: return@withLock false
            setCompletedLocked(id, current.revision, completed) != null
        }

    override suspend fun setCompletedIfRevision(
        id: String,
        revision: Int,
        completed: Boolean,
    ): TodoTask? = mutex.withLock {
        setCompletedLocked(id, revision, completed)
    }

    override suspend fun delete(id: String): TodoTask? = deleteWithOrder(id)?.task

    override suspend fun deleteWithOrder(id: String): TodoDeletedTask? = mutex.withLock {
        val deleted = repository.deleteWithOrder(id) ?: return@withLock null
        notifyTasksChangedSafely()
        alarms.cancel(id)
        notifier.cancel(id)
        deleted
    }

    /** 旧恢复调用没有排序快照；统一走带顺序的恢复，初始化后无位次项仍会追加。 */
    override suspend fun restore(task: TodoTask): Boolean =
        restoreWithOrder(TodoDeletedTask(task = task))

    override suspend fun restoreWithOrder(deleted: TodoDeletedTask): Boolean = mutex.withLock {
        val restored = repository.restoreWithOrder(deleted, clock()) ?: return@withLock false
        notifyTasksChangedSafely()
        alarms.cancel(restored.id)
        notifier.cancel(restored.id)
        reconcileLocked(restored)
        true
    }

    override fun openTask(taskId: String) {
        TodoLaunchContract.canonicalUuid(taskId)?.let { openTaskId.value = it }
    }

    override fun consumeOpenTask() {
        openTaskId.value = null
    }

    override fun openCreate(): String {
        val requestId = UUID.randomUUID().toString()
        createRequestId.value = requestId
        return requestId
    }

    override fun openCreate(requestId: String): Boolean {
        val canonical = TodoLaunchContract.canonicalUuid(requestId) ?: return false
        createRequestId.value = canonical
        return true
    }

    override fun consumeCreateRequest() {
        createRequestId.value = null
    }

    /** 重启、包更新、时间/时区变化和授权变化后从数据库重建。 */
    override suspend fun readWidgetSnapshot(maxVisibleRows: Int): TodoWidgetSnapshotResult {
        require(maxVisibleRows in 0..4)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!restored) {
                    return@withLock if (restoreFailed) {
                        TodoWidgetSnapshotResult.ReadFailed
                    } else {
                        TodoWidgetSnapshotResult.NotRestored
                    }
                }
                try {
                    val incomplete = TodoOrdering.incomplete(repository.all(), clock())
                    TodoWidgetSnapshotResult.Ready(
                        tasks = incomplete.take(maxVisibleRows),
                        incompleteCount = incomplete.size,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    TodoWidgetSnapshotResult.ReadFailed
                }
            }
        }
    }

    override suspend fun reconcileAll(): Unit = mutex.withLock {
        try {
            val now = clock()
            val access = accessProvider.current()
            val tasks = repository.all()
            tasks.forEach { task ->
                alarms.cancel(task.id)
                when (val plan = TodoReminderPlanner.plan(task, now, access)) {
                    is TodoReminderPlan.Schedule -> alarms.schedule(
                        task.id,
                        task.revision,
                        plan.at,
                        plan.exact,
                    )
                    is TodoReminderPlan.NotifyNow -> fireLocked(task.id, task.revision, now)
                    is TodoReminderPlan.Blocked -> Unit
                    TodoReminderPlan.Nothing -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!restored && !restoreFailed) {
                restoreFailed = true
                notifyTasksChangedSafely()
            }
            throw failure
        }

        restoreFailed = false
        if (!restored) {
            restored = true
            notifyTasksChangedSafely()
        }
    }

    /**
     * Alarm 到点入口。
     *
     * - revision 不匹配、任务已完成/已发送/无提醒：完全无副作用；
     * - revision 匹配但当前 reminderAt 还在未来（过早事件）：只重建当前有效调度；
     * - 到期且通知可用：标记 fired 后发一次通知。
     */
    suspend fun onAlarm(taskId: String, revision: Int, now: Long = clock()) {
        mutex.withLock {
            val task = repository.byId(taskId) ?: return@withLock
            if (task.completed || task.reminderAt == null || task.reminderFiredAt != null) {
                return@withLock
            }
            if (task.revision != revision) return@withLock
            if (task.reminderAt > now) {
                reconcileLocked(task)
                return@withLock
            }
            if (!accessProvider.current().notificationsEnabled) return@withLock
            fireLocked(taskId, revision, now)
        }
    }

    suspend fun onActionComplete(taskId: String, revision: Int) {
        mutex.withLock {
            // 条件更新失败 = 旧动作/任务不存在；绝不再按 taskId 取消当前调度。
            repository.completeIfRevision(taskId, revision, clock()) ?: return@withLock
            notifyTasksChangedSafely()
            alarms.cancel(taskId)
            notifier.cancel(taskId)
        }
    }

    suspend fun onActionSnooze(taskId: String, revision: Int) {
        mutex.withLock {
            val now = clock()
            val updated = repository.snoozeIfRevision(
                id = taskId,
                revision = revision,
                until = now + SNOOZE_MILLIS,
                now = now,
            ) ?: return@withLock
            notifyTasksChangedSafely()
            alarms.cancel(taskId)
            notifier.cancel(taskId)
            reconcileLocked(updated)
        }
    }

    private suspend fun reconcileLocked(task: TodoTask) {
        val now = clock()
        val access = accessProvider.current()
        when (val plan = TodoReminderPlanner.plan(task, now, access)) {
            is TodoReminderPlan.Schedule -> alarms.schedule(
                task.id,
                task.revision,
                plan.at,
                plan.exact,
            )
            is TodoReminderPlan.NotifyNow -> fireLocked(task.id, task.revision, now)
            is TodoReminderPlan.Blocked -> {
                // 过去未发送且通知不可用：不标记已发送，授权恢复后由重建补一次。
                alarms.cancel(task.id)
            }
            TodoReminderPlan.Nothing -> alarms.cancel(task.id)
        }
    }

    // The caller may leave its Compose scope after Room commits. Keep the CAS and
    // matching Alarm/notification reconciliation together before propagating cancellation.
    private suspend fun setCompletedLocked(
        id: String,
        revision: Int,
        completed: Boolean,
    ): TodoTask? = withContext(NonCancellable) {
        val updated = repository.setCompletedIfRevision(
            id = id,
            revision = revision,
            completed = completed,
            now = clock(),
        ) ?: return@withContext null
        notifyTasksChangedSafely()
        alarms.cancel(id)
        notifier.cancel(id)
        if (!updated.completed) reconcileLocked(updated)
        updated
    }

    private suspend fun fireLocked(taskId: String, expectedRevision: Int, now: Long) {
        val marked = repository.markFiredIfRevision(
            id = taskId,
            revision = expectedRevision,
            firedAt = now,
            now = now,
        ) ?: return
        notifyTasksChangedSafely()
        val posted = notifier.post(marked, marked.revision, now)
        if (!posted) {
            // 权限/channel 可能在调度后被关闭；回滚 firedAt，避免把未显示的通知当成已发送。
            if (repository.clearFiredIfRevision(marked.id, marked.revision, now) != null) {
                notifyTasksChangedSafely()
            }
        }
    }

    private fun notifyTasksChangedSafely() {
        runCatching { onTasksChanged() }
    }

    companion object {
        const val SNOOZE_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * 进程内桥：Alarm/通知接收器只操作已经激活的待办插件；未注册时不落盘、不通知。
 */
object TodoRuntime {
    @Volatile
    private var active: TodoService? = null

    fun install(service: TodoService) {
        active = service
    }

    fun serviceOrNull(): TodoService? = active

    internal fun clearForTest() {
        active = null
    }
}
