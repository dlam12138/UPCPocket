package com.upcpocket.app.todo

import com.upcpocket.app.todo.data.TodoRepository
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoTaskDao
import com.upcpocket.app.todo.data.TodoTaskEntity
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Service 级副作用/并发回归。Room 由可控的 DAO 替代，Alarm/通知只记录调用，
 * 以便验证“旧 revision 完全无副作用”和“串行后最终状态符合当前持久状态”。
 */
class TodoServiceTest {

    @Test
    fun `stale complete snooze and alarm do not cancel the current revision`() = runTest {
        val harness = Harness()
        val reminderAt = harness.now + 60_000
        val task = harness.service.add("A", reminderAt)
        assertTrue(harness.service.edit(task.id, "A2", reminderAt))
        val current = harness.repository.byId(task.id)!!
        assertEquals(2, current.revision)

        val scheduledBefore = harness.scheduler.scheduled[task.id]
        harness.notifier.active[task.id] = current.revision
        val cancelBefore = harness.scheduler.cancelCalls
        val notifierCancelBefore = harness.notifier.cancelCalls
        val postsBefore = harness.notifier.posts

        harness.service.onActionComplete(task.id, current.revision - 1)
        harness.service.onActionSnooze(task.id, current.revision - 1)
        harness.service.onAlarm(task.id, current.revision - 1, reminderAt + 1)

        assertEquals(scheduledBefore, harness.scheduler.scheduled[task.id])
        assertEquals(current.revision, harness.notifier.active[task.id])
        assertEquals(cancelBefore, harness.scheduler.cancelCalls)
        assertEquals(notifierCancelBefore, harness.notifier.cancelCalls)
        assertEquals(postsBefore, harness.notifier.posts)
        assertFalse(harness.repository.byId(task.id)!!.completed)
    }

    @Test
    fun `current complete cancels only its current schedule and notification`() = runTest {
        val harness = Harness()
        val task = harness.service.add("A", harness.now + 60_000)
        val revision = harness.repository.byId(task.id)!!.revision
        harness.notifier.active[task.id] = revision

        harness.service.onActionComplete(task.id, revision)

        assertTrue(harness.repository.byId(task.id)!!.completed)
        assertNull(harness.scheduler.scheduled[task.id])
        assertNull(harness.notifier.active[task.id])
    }

    @Test
    fun `concurrent completion writes once and revision guarded undo restores reminders`() = runTest {
        val harness = Harness()
        val reminderAt = harness.now + 60_000
        val task = harness.service.add("A", reminderAt)
        val revision = harness.repository.byId(task.id)!!.revision

        val results = coroutineScope {
            listOf(
                async { harness.service.setCompletedIfRevision(task.id, revision, true) },
                async { harness.service.setCompletedIfRevision(task.id, revision, true) },
            ).map { it.await() }
        }

        val completed = results.single { it != null }!!
        assertEquals(2, harness.repository.byId(task.id)!!.revision)
        assertNull(harness.scheduler.scheduled[task.id])
        val reopened = harness.service.setCompletedIfRevision(
            task.id,
            completed.revision,
            false,
        )!!
        assertFalse(reopened.completed)
        assertNull(harness.service.setCompletedIfRevision(task.id, completed.revision, false))
        val current = harness.repository.byId(task.id)!!
        assertEquals(reminderAt, current.reminderAt)
        assertEquals(current.revision, harness.scheduler.scheduled[task.id]?.revision)
    }

    @Test
    fun `started completion and undo finish reminder coordination after caller cancellation`() = runTest {
        val harness = Harness()
        val reminderAt = harness.now + 60_000
        val task = harness.service.add("A", reminderAt)
        val initialRevision = harness.repository.byId(task.id)!!.revision

        val (completeReadStarted, releaseCompleteRead) = harness.dao.pauseNextById()
        val completing = async {
            harness.service.setCompletedIfRevision(task.id, initialRevision, true)
        }
        completeReadStarted.await()
        completing.cancel()
        releaseCompleteRead.complete(Unit)
        assertTrue(runCatching { completing.await() }.exceptionOrNull() is CancellationException)

        val completed = harness.repository.byId(task.id)!!
        assertTrue(completed.completed)
        assertNull(harness.scheduler.scheduled[task.id])
        assertNull(harness.notifier.active[task.id])

        val (undoReadStarted, releaseUndoRead) = harness.dao.pauseNextById()
        val undoing = async {
            harness.service.setCompletedIfRevision(task.id, completed.revision, false)
        }
        undoReadStarted.await()
        undoing.cancel()
        releaseUndoRead.complete(Unit)
        assertTrue(runCatching { undoing.await() }.exceptionOrNull() is CancellationException)

        val reopened = harness.repository.byId(task.id)!!
        assertFalse(reopened.completed)
        assertEquals(reminderAt, reopened.reminderAt)
        assertEquals(reopened.revision, harness.scheduler.scheduled[task.id]?.revision)
        assertEquals(reminderAt, harness.scheduler.scheduled[task.id]?.atMillis)
        assertNull(harness.notifier.active[task.id])
    }

    @Test
    fun `completion undo cannot overwrite edits or resurrect deleted task`() = runTest {
        val harness = Harness()
        val task = harness.service.add("A", harness.now + 60_000)
        val completed = harness.service.setCompletedIfRevision(
            task.id,
            harness.repository.byId(task.id)!!.revision,
            true,
        )!!
        harness.service.edit(task.id, "B", task.reminderAt)

        assertNull(harness.service.setCompletedIfRevision(task.id, completed.revision, false))
        assertEquals("B", harness.repository.byId(task.id)!!.title)

        val current = harness.repository.byId(task.id)!!
        harness.service.delete(task.id)
        assertNull(harness.service.setCompletedIfRevision(task.id, current.revision, false))
        assertNull(harness.repository.byId(task.id))
    }

    @Test
    fun `early matched alarm rebuilds the current schedule without notifying`() = runTest {
        val harness = Harness()
        val reminderAt = harness.now + 60_000
        val task = harness.service.add("A", reminderAt)
        val revision = harness.repository.byId(task.id)!!.revision

        harness.service.onAlarm(task.id, revision, harness.now + 1_000)

        assertEquals(revision, harness.scheduler.scheduled[task.id]?.revision)
        assertEquals(0, harness.notifier.posts)
        assertNull(harness.repository.byId(task.id)!!.reminderFiredAt)
    }

    @Test
    fun `reconcile snapshot cannot overwrite a concurrent edit schedule`() = runTest {
        val harness = Harness()
        val reminderAt = harness.now + 60_000
        val task = harness.service.add("A", reminderAt)
        harness.dao.allGate = CompletableDeferred()

        coroutineScope {
            val reconcile = async { harness.service.reconcileAll() }
            yield()
            val edit = async { harness.service.edit(task.id, "B", reminderAt) }
            yield()
            harness.dao.allGate!!.complete(Unit)
            reconcile.await()
            edit.await()
        }

        val current = harness.repository.byId(task.id)!!
        assertEquals(2, current.revision)
        assertEquals(current.revision, harness.scheduler.scheduled[task.id]?.revision)
    }

    @Test
    fun `alarm and completion serialize to a completed task with no live notification`() = runTest {
        val harness = Harness()
        repeat(20) { index ->
            val reminderAt = harness.now + 1_000 + index
            val task = harness.service.add("A$index", reminderAt)
            val revision = harness.repository.byId(task.id)!!.revision
            harness.notifier.active.clear()

            coroutineScope {
                val alarm = async {
                    harness.service.onAlarm(task.id, revision, reminderAt + 1)
                }
                val complete = async {
                    harness.service.setCompleted(task.id, true)
                }
                alarm.await()
                complete.await()
            }

            assertTrue(harness.repository.byId(task.id)!!.completed)
            assertFalse(harness.notifier.active.containsKey(task.id))
        }
    }

    @Test
    fun `failed post rolls back fired state and a later reconcile posts once`() = runTest {
        val harness = Harness()
        harness.notifier.result = false
        val task = harness.service.add("A", harness.now - 1_000)
        val afterFailure = harness.repository.byId(task.id)!!
        assertNull(afterFailure.reminderFiredAt)
        assertFalse(harness.notifier.active.containsKey(task.id))

        harness.notifier.result = true
        harness.service.reconcileAll()
        assertTrue(harness.notifier.active.containsKey(task.id))
        assertEquals(1, harness.notifier.active.size)

        harness.service.reconcileAll()
        assertEquals(2, harness.notifier.posts)
        assertEquals(1, harness.notifier.active.size)
    }

    private class Harness {
        val dao = FakeTodoDao()
        val repository = TodoRepository(dao)
        val scheduler = FakeScheduler()
        val notifier = FakeNotifier()
        val access = FakeAccess(TodoReminderAccess(true, true))
        var now = 1_000L
        val service = TodoService(
            TodoServiceDependencies(
                repository = { repository },
                alarms = { scheduler },
                notifier = { notifier },
                accessProvider = { access },
            ),
            clock = { now },
        )
    }

    private class FakeAccess(private var value: TodoReminderAccess) : TodoReminderAccessApi {
        override fun current(): TodoReminderAccess = value
    }

    private class FakeScheduler : TodoAlarmSchedulerApi {
        data class Entry(
            val taskId: String,
            val revision: Int,
            val atMillis: Long,
            val exact: Boolean,
        )

        val scheduled = linkedMapOf<String, Entry>()
        var cancelCalls = 0

        override fun schedule(taskId: String, revision: Int, atMillis: Long, exact: Boolean) {
            scheduled[taskId] = Entry(taskId, revision, atMillis, exact)
        }

        override fun cancel(taskId: String) {
            cancelCalls++
            scheduled.remove(taskId)
        }
    }

    private class FakeNotifier : TodoNotifierApi {
        val active = mutableMapOf<String, Int>()
        var posts = 0
        var cancelCalls = 0
        var result = true

        override fun ensureChannel() = Unit

        override fun post(task: TodoTask, revision: Int, firedAt: Long): Boolean {
            posts++
            if (!result) return false
            active[task.id] = revision
            return true
        }

        override fun cancel(taskId: String) {
            cancelCalls++
            active.remove(taskId)
        }
    }

    private class FakeTodoDao : TodoTaskDao {
        private val state = MutableStateFlow<List<TodoTaskEntity>>(emptyList())
        var allGate: CompletableDeferred<Unit>? = null
        private var nextByIdStarted: CompletableDeferred<Unit>? = null
        private var nextByIdGate: CompletableDeferred<Unit>? = null

        fun pauseNextById(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            nextByIdStarted = started
            nextByIdGate = gate
            return started to gate
        }

        override fun observeAll(): Flow<List<TodoTaskEntity>> = state

        override suspend fun all(): List<TodoTaskEntity> {
            allGate?.await()
            return state.value
        }

        override suspend fun byId(id: String): TodoTaskEntity? {
            nextByIdStarted?.also { nextByIdStarted = null }?.complete(Unit)
            nextByIdGate?.also { nextByIdGate = null }?.await()
            return state.value.firstOrNull { it.id == id }
        }

        override suspend fun upsert(task: TodoTaskEntity) {
            state.value = state.value.filterNot { it.id == task.id } + task
        }

        override suspend fun delete(id: String): Int {
            val current = state.value
            val remaining = current.filterNot { it.id == id }
            val affectedRows = current.size - remaining.size
            if (affectedRows == 0) return 0
            state.value = remaining
            return affectedRows
        }

        override suspend fun updateContent(
            id: String,
            title: String,
            reminderAt: Long?,
            firedAt: Long?,
            now: Long,
        ): Int = mutate(id) {
            it.copy(
                title = title,
                reminderAt = reminderAt,
                reminderFiredAt = firedAt,
                updatedAt = now,
                revision = it.revision + 1,
            )
        }

        override suspend fun setCompleted(
            id: String,
            completed: Boolean,
            completedAt: Long?,
            now: Long,
        ): Int = mutate(id) {
            it.copy(
                completed = completed,
                completedAt = completedAt,
                updatedAt = now,
                revision = it.revision + 1,
            )
        }

        override suspend fun setCompletedIfRevision(
            id: String,
            revision: Int,
            completed: Boolean,
            completedAt: Long?,
            now: Long,
        ): Int = mutate(id) { current ->
            if (current.revision != revision || current.completed == completed) {
                return@mutate null
            }
            current.copy(
                completed = completed,
                completedAt = completedAt,
                updatedAt = now,
                revision = current.revision + 1,
            )
        }

        override suspend fun completeIfRevision(id: String, revision: Int, now: Long): Int =
            mutate(id) { current ->
                if (current.revision != revision || current.completed) return@mutate null
                current.copy(
                    completed = true,
                    completedAt = now,
                    updatedAt = now,
                    revision = current.revision + 1,
                )
            }

        override suspend fun snoozeIfRevision(
            id: String,
            revision: Int,
            until: Long,
            now: Long,
        ): Int = mutate(id) { current ->
            if (
                current.revision != revision ||
                current.completed ||
                current.reminderAt == null
            ) {
                return@mutate null
            }
            current.copy(
                reminderAt = until,
                reminderFiredAt = null,
                updatedAt = now,
                revision = current.revision + 1,
            )
        }

        override suspend fun markFiredIfRevision(
            id: String,
            revision: Int,
            firedAt: Long,
            now: Long,
        ): Int = mutate(id) { current ->
            if (
                current.revision != revision ||
                current.completed ||
                current.reminderAt == null ||
                current.reminderFiredAt != null
            ) {
                return@mutate null
            }
            current.copy(
                reminderFiredAt = firedAt,
                updatedAt = now,
                revision = current.revision + 1,
            )
        }

        override suspend fun clearFiredIfRevision(id: String, revision: Int, now: Long): Int =
            mutate(id) { current ->
                if (current.revision != revision || current.reminderFiredAt == null) {
                    return@mutate null
                }
                current.copy(
                    reminderFiredAt = null,
                    updatedAt = now,
                    revision = current.revision + 1,
                )
            }

        private fun mutate(
            id: String,
            transform: (TodoTaskEntity) -> TodoTaskEntity?,
        ): Int {
            val current = state.value.firstOrNull { it.id == id } ?: return 0
            val next = transform(current) ?: return 0
            state.value = state.value.map { if (it.id == id) next else it }
            return 1
        }
    }
}
