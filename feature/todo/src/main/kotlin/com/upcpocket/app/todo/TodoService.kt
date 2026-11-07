package com.upcpocket.app.todo

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.todo.data.TodoRepository
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoDatabase
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

/**
 * 待办数据、提醒调度和通知动作的唯一业务入口。
 *
 * Room 是事实来源；AlarmManager 与通知都是可重建的副作用。所有通知动作都先按
 * taskId + revision 条件更新，旧动作在数据库层被拒绝。
 */
class TodoService(
    context: Context,
    private val openAppIntent: (String) -> PendingIntent,
    private val clock: () -> Long = System::currentTimeMillis,
) : TodoStore {

    private val appContext = context.applicationContext

    private val database: TodoDatabase by lazy { TodoDatabase.get(appContext) }
    private val repository: TodoRepository by lazy { TodoRepository(database.taskDao()) }
    private val accessProvider: TodoReminderAccessProvider by lazy {
        TodoReminderAccessProvider(appContext)
    }
    private val notifier: TodoNotifier by lazy {
        TodoNotifier(appContext, openAppIntent)
    }
    private val alarms: TodoAlarmScheduler by lazy { TodoAlarmScheduler(appContext) }

    private val openTaskId = MutableStateFlow<String?>(null)

    override val tasks: Flow<List<TodoTask>> get() = repository.tasks
    override val pendingOpenTask: StateFlow<String?> = openTaskId.asStateFlow()

    override fun ensureNotificationChannel() = notifier.ensureChannel()

    override fun reminderAccess(): TodoReminderAccess {
        ensureNotificationChannel()
        return accessProvider.current()
    }

    override suspend fun add(title: String, reminderAt: Long?): TodoTask {
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
        reconcile(task)
        return task
    }

    override suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean {
        val normalized = title.trim()
        if (normalized.isEmpty()) return false
        val old = repository.byId(id) ?: return false
        val reminderChanged = old.reminderAt != reminderAt
        val firedAt = if (reminderChanged) null else old.reminderFiredAt
        val updated = repository.updateContent(
            id = id,
            title = normalized,
            reminderAt = reminderAt,
            firedAt = firedAt,
            now = clock(),
        ) ?: return false
        alarms.cancel(id)
        notifier.cancel(id)
        if (!updated.completed) reconcile(updated)
        return true
    }

    override suspend fun setCompleted(id: String, completed: Boolean): Boolean {
        val updated = repository.setCompleted(id, completed, clock()) ?: return false
        alarms.cancel(id)
        notifier.cancel(id)
        if (!updated.completed) reconcile(updated)
        return true
    }

    override suspend fun delete(id: String): TodoTask? {
        val deleted = repository.delete(id) ?: return null
        alarms.cancel(id)
        notifier.cancel(id)
        return deleted
    }

    override suspend fun restore(task: TodoTask): Boolean {
        val now = clock()
        val restored = task.copy(
            revision = task.revision + 1,
            updatedAt = now,
        )
        repository.insert(restored)
        alarms.cancel(restored.id)
        notifier.cancel(restored.id)
        reconcile(restored)
        return true
    }

    override fun openTask(taskId: String) {
        if (isUuid(taskId)) openTaskId.value = taskId
    }

    override fun consumeOpenTask() {
        openTaskId.value = null
    }

    /** 重启、包更新、时间/时区变化和授权变化后从数据库重建。 */
    override suspend fun reconcileAll() {
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
                is TodoReminderPlan.NotifyNow -> fire(task.id, task.revision, now)
                is TodoReminderPlan.Blocked -> Unit
                TodoReminderPlan.Nothing -> Unit
            }
        }
    }

    /** AlarmReceiver 到点入口；revision 不匹配时静默丢弃。 */
    suspend fun onAlarm(taskId: String, revision: Int, now: Long = clock()) {
        val task = repository.byId(taskId) ?: run {
            alarms.cancel(taskId)
            return
        }
        if (!TodoReminderPlanner.acceptsAlarm(task, revision)) {
            alarms.cancel(taskId)
            return
        }
        val access = accessProvider.current()
        if (!access.notificationsEnabled) return
        fire(taskId, revision, now)
    }

    suspend fun onActionComplete(taskId: String, revision: Int) {
        repository.completeIfRevision(taskId, revision, clock())
        alarms.cancel(taskId)
        notifier.cancel(taskId)
    }

    suspend fun onActionSnooze(taskId: String, revision: Int) {
        val now = clock()
        val updated = repository.snoozeIfRevision(
            id = taskId,
            revision = revision,
            until = now + SNOOZE_MILLIS,
            now = now,
        )
        alarms.cancel(taskId)
        notifier.cancel(taskId)
        updated?.let { reconcile(it) }
    }

    private suspend fun reconcile(task: TodoTask) {
        val now = clock()
        val access = accessProvider.current()
        when (val plan = TodoReminderPlanner.plan(task, now, access)) {
            is TodoReminderPlan.Schedule -> alarms.schedule(
                task.id,
                task.revision,
                plan.at,
                plan.exact,
            )
            is TodoReminderPlan.NotifyNow -> fire(task.id, task.revision, now)
            is TodoReminderPlan.Blocked -> {
                // 过去未发送且通知不可用：不标记已发送，授权恢复后由重建补一次。
                alarms.cancel(task.id)
            }
            TodoReminderPlan.Nothing -> alarms.cancel(task.id)
        }
    }

    private suspend fun fire(taskId: String, expectedRevision: Int, now: Long) {
        val marked = repository.markFiredIfRevision(
            id = taskId,
            revision = expectedRevision,
            firedAt = now,
            now = now,
        ) ?: return
        val posted = notifier.post(marked, marked.revision, now)
        if (!posted) {
            // 权限可能在调度后被撤回；回滚 firedAt，避免把未显示的通知当成已发送。
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
