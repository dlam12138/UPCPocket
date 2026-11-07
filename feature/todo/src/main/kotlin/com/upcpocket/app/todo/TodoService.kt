package com.upcpocket.app.todo

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.todo.data.TodoDatabase
import com.upcpocket.app.todo.data.TodoRepository
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.reminder.TodoAlarmScheduler
import com.upcpocket.app.todo.reminder.TodoNotifier
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import com.upcpocket.app.todo.reminder.TodoReminderAccessProvider
import com.upcpocket.app.todo.reminder.TodoReminderPlan
import com.upcpocket.app.todo.reminder.TodoReminderPlanner
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TodoStore {
    val tasks: Flow<List<TodoTask>>
    val pendingOpenTask: StateFlow<String?>

    fun reminderAccess(): TodoReminderAccess

    suspend fun add(title: String, reminderAt: Long?): TodoTask

    suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean

    suspend fun setCompleted(id: String, completed: Boolean): Boolean

    suspend fun delete(id: String): TodoTask?

    suspend fun restore(task: TodoTask): Boolean

    fun openTask(taskId: String)

    fun consumeOpenTask()

    fun ensureNotificationChannel()

    suspend fun reconcileAll()
}

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
 */
class TodoService internal constructor(
    private val dependencies: TodoServiceDependencies,
    private val clock: () -> Long = System::currentTimeMillis,
) : TodoStore {

    constructor(
        context: Context,
        openAppIntent: (String) -> PendingIntent,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(TodoServiceDependencies.forContext(context, openAppIntent), clock)

    private val repository by lazy { dependencies.repository() }
    private val alarms by lazy { dependencies.alarms() }
    private val notifier by lazy { dependencies.notifier() }
    private val accessProvider by lazy { dependencies.accessProvider() }

    private val mutex = Mutex()
    private val openTaskId = MutableStateFlow<String?>(null)

    override val tasks: Flow<List<TodoTask>> get() = repository.tasks
    override val pendingOpenTask: StateFlow<String?> = openTaskId.asStateFlow()

    override fun ensureNotificationChannel() = notifier.ensureChannel()

    override fun reminderAccess(): TodoReminderAccess {
        ensureNotificationChannel()
        return accessProvider.current()
    }

    override suspend fun add(title: String, reminderAt: Long?): TodoTask = mutex.withLock {
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
        )
        repository.insert(task)
        reconcileLocked(task)
        task
    }

    override suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean =
        mutex.withLock {
            val normalized = title.trim()
            if (normalized.isEmpty()) return@withLock false
            val old = repository.byId(id) ?: return@withLock false
            val reminderChanged = old.reminderAt != reminderAt
            val firedAt = if (reminderChanged) null else old.reminderFiredAt
            val updated = repository.updateContent(
                id = id,
                title = normalized,
                reminderAt = reminderAt,
                firedAt = firedAt,
                now = clock(),
            ) ?: return@withLock false
            alarms.cancel(id)
            notifier.cancel(id)
            if (!updated.completed) reconcileLocked(updated)
            true
        }

    override suspend fun setCompleted(id: String, completed: Boolean): Boolean =
        mutex.withLock {
            val updated = repository.setCompleted(id, completed, clock()) ?: return@withLock false
            alarms.cancel(id)
            notifier.cancel(id)
            if (!updated.completed) reconcileLocked(updated)
            true
        }

    override suspend fun delete(id: String): TodoTask? = mutex.withLock {
        val deleted = repository.delete(id) ?: return@withLock null
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
        alarms.cancel(restored.id)
        notifier.cancel(restored.id)
        reconcileLocked(restored)
        true
    }

    override fun openTask(taskId: String) {
        if (isUuid(taskId)) openTaskId.value = taskId
    }

    override fun consumeOpenTask() {
        openTaskId.value = null
    }

    /** 重启、包更新、时间/时区变化和授权变化后从数据库重建。 */
    override suspend fun reconcileAll(): Unit = mutex.withLock {
        val now = clock()
        val access = accessProvider.current()
        repository.all().forEach { task ->
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

    private suspend fun fireLocked(taskId: String, expectedRevision: Int, now: Long) {
        val marked = repository.markFiredIfRevision(
            id = taskId,
            revision = expectedRevision,
            firedAt = now,
            now = now,
        ) ?: return
        val posted = notifier.post(marked, marked.revision, now)
        if (!posted) {
            // 权限/channel 可能在调度后被关闭；回滚 firedAt，避免把未显示的通知当成已发送。
            repository.clearFiredIfRevision(marked.id, marked.revision, now)
        }
    }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.isSuccess

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
