package com.upcpocket.app.todo.data

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TodoRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `crud and revision guarded reminder actions stay consistent`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db.taskDao())
        val task = sampleTask()
        repository.insert(task)

        assertEquals(task, repository.tasks.first().single())

        val completed = repository.setCompleted(task.id, true, 200)
        assertTrue(completed!!.completed)
        assertEquals(2, completed.revision)
        assertFalse(repository.setCompleted(task.id, false, 300)!!.completed)

        val edited = repository.updateContent(task.id, "新标题", task.reminderAt, null, 400)
        assertEquals("新标题", edited!!.title)
        assertEquals(4, edited.revision)

        val marked = repository.markFiredIfRevision(task.id, 4, 500, 500)
        assertEquals(500L, marked!!.reminderFiredAt)
        assertEquals(5, marked.revision)
        assertNull(repository.markFiredIfRevision(task.id, 4, 600, 600))
        assertNull(repository.completeIfRevision(task.id, 4, 600))
        assertTrue(repository.completeIfRevision(task.id, 5, 600)!!.completed)

        db.close()
    }

    @Test
    fun `completion undo is compare and set and never restores an old row`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db.taskDao())
        val task = sampleTask()
        repository.insert(task)

        val completed = repository.setCompletedIfRevision(
            id = task.id,
            revision = task.revision,
            completed = true,
            now = 200,
        )!!
        assertTrue(completed.completed)
        assertEquals(2, completed.revision)
        assertNull(
            repository.setCompletedIfRevision(
                task.id,
                task.revision,
                completed = true,
                now = 201,
            ),
        )

        val edited = repository.updateContent(
            id = task.id,
            title = "后续编辑",
            reminderAt = task.reminderAt,
            firedAt = null,
            now = 300,
        )!!
        assertNull(
            repository.setCompletedIfRevision(
                task.id,
                completed.revision,
                completed = false,
                now = 400,
            ),
        )
        assertEquals("后续编辑", repository.byId(task.id)!!.title)
        assertTrue(repository.byId(task.id)!!.completed)

        repository.insert(sampleTask(id = "22222222-2222-4222-8222-222222222222"))
        val toDelete = repository.byId("22222222-2222-4222-8222-222222222222")!!
        val deletedCompleted = repository.setCompletedIfRevision(
            toDelete.id,
            toDelete.revision,
            completed = true,
            now = 500,
        )!!
        repository.delete(toDelete.id)
        assertNull(
            repository.setCompletedIfRevision(
                deletedCompleted.id,
                deletedCompleted.revision,
                completed = false,
                now = 600,
            ),
        )
        assertNull(repository.byId(deletedCompleted.id))
        assertEquals(3, edited.revision)
        db.close()
    }

    @Test
    fun `delete and restore keeps the same library task and a new revision`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db.taskDao())
        val task = sampleTask()
        repository.insert(task)

        val deleted = repository.delete(task.id)
        assertEquals(task, deleted)
        assertTrue(repository.tasks.first().isEmpty())

        val restored = deleted!!.copy(revision = deleted.revision + 1)
        repository.insert(restored)
        assertEquals(restored, repository.tasks.first().single())
        assertNull(repository.byId("missing"))
        db.close()
    }

    @Test
    fun `file database reopens with todo data and is separate from account db`() = runBlocking {
        assertNotEquals(TodoDatabase.DB_NAME, "upcpocket.db")
        val name = "todo-test-${UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        TodoRepository(first.taskDao()).insert(sampleTask())
        first.close()

        val second = Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        val reopened = TodoRepository(second.taskDao()).tasks.first().single()
        assertEquals("买牛奶", reopened.title)
        second.close()
        context.deleteDatabase(name)
        Unit
    }

    private fun inMemory(): TodoDatabase = Room.inMemoryDatabaseBuilder(
        context,
        TodoDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun sampleTask(
        id: String = "11111111-1111-4111-8111-111111111111",
    ) = TodoTask(
        id = id,
        title = "买牛奶",
        completed = false,
        createdAt = 100,
        updatedAt = 100,
        completedAt = null,
        reminderAt = 1_000,
        reminderFiredAt = null,
        revision = 1,
    )
}
