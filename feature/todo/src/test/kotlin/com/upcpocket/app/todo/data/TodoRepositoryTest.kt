package com.upcpocket.app.todo.data

import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `deadline edits preserve the independent reminder and increment revision`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db.taskDao())
        val task = sampleTask(dueAt = 2_000)
        repository.insert(task)

        val edited = repository.updateContent(
            id = task.id,
            title = task.title,
            reminderAt = task.reminderAt,
            dueAt = 3_000,
            firedAt = task.reminderFiredAt,
            now = 400,
        )!!

        assertEquals(2, edited.revision)
        assertEquals(3_000L, edited.dueAt!!)
        assertEquals(task.reminderAt, edited.reminderAt)
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

    @Test
    fun `pin and atomic sort order preserve revision and ignore completed rows`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db.taskDao())
        val first = sampleTask(id = "11111111-1111-4111-8111-111111111111", dueAt = 2_000)
        val second = sampleTask(id = "22222222-2222-4222-8222-222222222222", dueAt = 3_000)
        val done = sampleTask(id = "33333333-3333-4333-8333-333333333333")
            .copy(completed = true, completedAt = 150)
        repository.insert(first)
        repository.insert(second)
        repository.insert(done)

        val pinned = repository.setPinned(first.id, 9_000L)!!
        assertEquals(9_000L, pinned.pinnedAt)
        assertNull(pinned.sortOrder)
        assertEquals(first.revision, pinned.revision)
        assertEquals(first.dueAt, pinned.dueAt)
        assertEquals(first.reminderAt, pinned.reminderAt)

        val updatedCount = repository.updateSortOrders(listOf(second.id, first.id, done.id))
        assertEquals(2, updatedCount)
        assertEquals(0L, repository.byId(second.id)!!.sortOrder)
        assertEquals(1L, repository.byId(first.id)!!.sortOrder)
        assertNull(repository.byId(done.id)!!.sortOrder)
        assertEquals(first.revision, repository.byId(first.id)!!.revision)

        val unpinned = repository.setPinned(first.id, null)!!
        assertNull(unpinned.pinnedAt)
        assertNull(unpinned.sortOrder)
        db.close()
    }

    @Test
    fun `migration preserves v1 fields through v3`() = runBlocking {
        val name = "t-${UUID.randomUUID().toString().take(8)}.db"
        val legacyTasks = listOf(
            TodoTask(
                id = "open-reminder",
                title = "未完成提醒",
                completed = false,
                createdAt = 101,
                updatedAt = 102,
                completedAt = null,
                reminderAt = 1_100,
                reminderFiredAt = null,
                revision = 3,
            ),
            TodoTask(
                id = "open-fired-reminder",
                title = "未完成已提醒",
                completed = false,
                createdAt = 201,
                updatedAt = 202,
                completedAt = null,
                reminderAt = 2_100,
                reminderFiredAt = 2_150,
                revision = 6,
            ),
            TodoTask(
                id = "completed-fired-reminder",
                title = "已完成已提醒",
                completed = true,
                createdAt = 301,
                updatedAt = 302,
                completedAt = 303,
                reminderAt = 3_100,
                reminderFiredAt = 3_150,
                revision = 9,
            ),
        )
        createSchemaDatabase(name, schemaVersion = 1, rows = legacyTasks)

        val migrated = Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .addMigrations(TodoDatabase.MIGRATION_1_2, TodoDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = TodoRepository(migrated.taskDao()).all().associateBy(TodoTask::id)
            assertEquals(legacyTasks.associateBy(TodoTask::id), rows)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migration preserves v2 fields through v3`() = runBlocking {
        val name = "t2-${UUID.randomUUID().toString().take(8)}.db"
        val v2Tasks = listOf(
            TodoTask(
                id = "v2-deadline",
                title = "有截止待办",
                completed = false,
                createdAt = 101,
                updatedAt = 102,
                completedAt = null,
                reminderAt = 1_100,
                reminderFiredAt = null,
                revision = 4,
                dueAt = 2_200,
            ),
            TodoTask(
                id = "v2-undated",
                title = "无截止待办",
                completed = false,
                createdAt = 201,
                updatedAt = 202,
                completedAt = null,
                reminderAt = null,
                reminderFiredAt = null,
                revision = 1,
                dueAt = null,
            ),
        )
        createSchemaDatabase(name, schemaVersion = 2, rows = v2Tasks)

        val migrated = Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .addMigrations(TodoDatabase.MIGRATION_1_2, TodoDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = TodoRepository(migrated.taskDao()).all().associateBy(TodoTask::id)
            assertEquals(v2Tasks.associateBy(TodoTask::id), rows)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun createSchemaDatabase(name: String, schemaVersion: Int, rows: List<TodoTask>) {
        val schemaRoot = Json.parseToJsonElement(schemaFile(schemaVersion).readText()).jsonObject
        val databaseSchema = schemaRoot.getValue("database").jsonObject
        val tableSchema = databaseSchema.getValue("entities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == "todo_tasks" }
        val tableSql = tableSchema.getValue("createSql").jsonPrimitive.content
            .replace("\${TABLE_NAME}", "todo_tasks")
        val version = databaseSchema.getValue("version").jsonPrimitive.content.toInt()
        check(version == schemaVersion) { "Expected committed Todo schema v$schemaVersion" }

        val databaseFile = context.getDatabasePath(name)
        val parent = requireNotNull(databaseFile.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Todo migration test directory is unavailable" }
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            legacy.execSQL(tableSql)
            databaseSchema.getValue("setupQueries").jsonArray.forEach { query ->
                legacy.execSQL(query.jsonPrimitive.content)
            }
            rows.forEach { task ->
                val values = ContentValues().apply {
                    put("id", task.id)
                    put("title", task.title)
                    put("completed", if (task.completed) 1 else 0)
                    put("createdAt", task.createdAt)
                    put("updatedAt", task.updatedAt)
                    if (task.completedAt == null) putNull("completedAt") else put("completedAt", task.completedAt)
                    if (task.reminderAt == null) putNull("reminderAt") else put("reminderAt", task.reminderAt)
                    if (task.reminderFiredAt == null) putNull("reminderFiredAt") else put("reminderFiredAt", task.reminderFiredAt)
                    put("revision", task.revision)
                    if (schemaVersion >= 2) {
                        if (task.dueAt == null) putNull("dueAt") else put("dueAt", task.dueAt)
                    }
                }
                legacy.insertOrThrow("todo_tasks", null, values)
            }
            legacy.execSQL("PRAGMA user_version = $version")
        } finally {
            legacy.close()
        }
    }

    private fun schemaFile(version: Int): File {
        val relativePaths = listOf(
            "schemas/com.upcpocket.app.todo.data.TodoDatabase/$version.json",
            "feature/todo/schemas/com.upcpocket.app.todo.data.TodoDatabase/$version.json",
        )
        var directory: File? = File(System.getProperty("user.dir")).canonicalFile
        while (directory != null) {
            val currentDirectory = directory ?: break
            relativePaths.firstNotNullOfOrNull { relative ->
                File(currentDirectory, relative).takeIf { it.isFile }
            }?.let { return it }
            directory = directory.parentFile
        }
        error("Committed Todo schema v$version was not found")
    }

    private fun inMemory(): TodoDatabase = Room.inMemoryDatabaseBuilder(
        context,
        TodoDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun sampleTask(
        id: String = "11111111-1111-4111-8111-111111111111",
        dueAt: Long? = null,
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
        dueAt = dueAt,
    )
}
