package com.upcpocket.app.todo

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.todo.data.TodoDatabase
import com.upcpocket.app.todo.data.TodoOrdering
import com.upcpocket.app.todo.data.TodoRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TodoStore {
    val tasks: Flow<List<TodoTask>>
    val pendingOpenTask: StateFlow<String?>
    val pendingCreateRequest: StateFlow<String?> get() = EMPTY_PENDING_CREATE_REQUEST

    fun reminderAccess(): TodoReminderAccess

    suspend fun add(title: String, reminderAt: Long?): TodoTask

    suspend fun add(title: String, reminderAt: Long?, dueAt: Long?): TodoTask {
        check(dueAt == null) { "This TodoStore does not support deadlines" }
        return add(title, reminderAt)
    }

    suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean

    suspend fun edit(id: String, title: String, reminderAt: Long?, dueAt: Long?): Boolean {
        check(dueAt == null) { "This TodoStore does not support deadlines" }
        return edit(id, title, reminderAt)
    }

    suspend fun setCompleted(id: String, completed: Boolean): Boolean

    /** Revision compare-and-set for a user toggle and its undo path. */
    suspend fun setCompletedIfRevision(
        id: String,
        revision: Int,
        completed: Boolean,
    ): TodoTask?

    suspend fun delete(id: String): TodoTask?

    suspend fun restore(task: TodoTask): Boolean

    suspend fun setPinned(id: String, pinned: Boolean): Boolean = false

    suspend fun reorderIncomplete(orderedIds: List<String>): Boolean = false

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
                repository = { TodoRepository(TodoDatabase.get(appContext).taskDao()) },
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
    override val pendingOpenTask: StateFlow<String?> = openTaskId.asStateFlow()
    override val pendingCreateRequest: StateFlow<String?> = createRequestId.asStateFlow()

    override fun ensureNotificationChannel() = notifier.ensureChannel()

    override fun reminderAccess(): TodoReminderAccess {
        ensureNotificationChannel()
        return accessProvider.current()
    }

    override suspend fun add(title: String, reminderAt: Long?): TodoTask =
        add(title, reminderAt, dueAt = null)

    override suspend fun add(title: String, reminderAt: Long?, dueAt: Long?): TodoTask = mutex.withLock {
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
        repository.insert(task)
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

    private suspend fun editLocked(
        id: String,
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        preserveExistingDueAt: Boolean,
    ): Boolean {
        val normalized = title.trim()
        if (normalized.isEmpty()) return false
        val old = repository.byId(id) ?: return false
        val reminderChanged = old.reminderAt != reminderAt
        val firedAt = if (reminderChanged) null else old.reminderFiredAt
        val updated = repository.updateContent(
            id = id,
            title = normalized,
            reminderAt = reminderAt,
            dueAt = if (preserveExistingDueAt) old.dueAt else dueAt,
            firedAt = firedAt,
            now = clock(),
        ) ?: return false
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

    override suspend fun delete(id: String): TodoTask? = mutex.withLock {
        val deleted = repository.delete(id) ?: return@withLock null
        notifyTasksChangedSafely()
        alarms.cancel(id)
        notifier.cancel(id)
        deleted
    }

    override suspend fun restore(task: TodoTask): Boolean = mutex.withLock {
        val now = clock()
        val restored = task.copy(
            revision = task.revision + 1,
            updatedAt = now,
        )
        repository.insert(restored)
        notifyTasksChangedSafely()
        alarms.cancel(restored.id)
        notifier.cancel(restored.id)
        reconcileLocked(restored)
        true
    }

    override suspend fun setPinned(id: String, pinned: Boolean): Boolean = mutex.withLock {
        val current = repository.byId(id) ?: return@withLock false
        if (pinned && current.completed) return@withLock false
        if (current.pinned == pinned) return@withLock true
        val pinnedAt = if (pinned) {
            val now = clock()
            val maxExisting = repository.all().mapNotNull { it.pinnedAt }.maxOrNull()
            if (maxExisting != null && maxExisting >= now) maxExisting + 1L else now
        } else {
            null
        }
        val updated = repository.setPinned(id, pinnedAt) != null
        if (updated) notifyTasksChangedSafely()
        updated
    }

    override suspend fun reorderIncomplete(orderedIds: List<String>): Boolean = mutex.withLock {
        val uniqueIds = orderedIds.distinct()
        if (uniqueIds.isEmpty()) return@withLock false
        val updated = repository.updateSortOrders(uniqueIds)
        if (updated > 0) notifyTasksChangedSafely()
        updated > 0
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
