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
        val repository = TodoRepository(db)
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
        val repository = TodoRepository(db)
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
        val repository = TodoRepository(db)
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
        val repository = TodoRepository(db)
        val task = sampleTask()
        repository.insert(task)

        val deleted = repository.deleteWithOrder(task.id)!!
        assertEquals(task, deleted.task)
        assertEquals(TodoImportance.NORMAL, deleted.importance)
        assertNull(deleted.manualRank)
        assertTrue(repository.tasks.first().isEmpty())

        val restored = repository.restoreWithOrder(deleted, now = 500)!!
        assertEquals(2, restored.revision)
        assertEquals(restored, repository.tasks.first().single())
        assertNull(repository.byId("missing"))
        assertNull(repository.restoreWithOrder(deleted, now = 600))
        db.close()
    }

    @Test
    fun `snapshot reads tasks and ordering as one consistent result`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db)
        val undated = sampleTask(id = "11111111-1111-4111-8111-111111111111")
        val deadline = sampleTask(
            id = "22222222-2222-4222-8222-222222222222",
            dueAt = 2_000,
        )
        repository.insertWithImportance(undated, TodoImportance.HIGH)
        repository.insertWithImportance(deadline, TodoImportance.LOW)
        assertTrue(repository.setSortMode(TodoSortMode.MANUAL))

        val snapshot = repository.snapshot.first()

        assertEquals(
            setOf(undated.id, deadline.id),
            snapshot.tasks.map(TodoTask::id).toSet(),
        )
        assertEquals(TodoSortMode.MANUAL, snapshot.ordering.mode)
        assertEquals(TodoImportance.HIGH, snapshot.ordering.importanceOf(undated.id))
        assertEquals(TodoImportance.LOW, snapshot.ordering.importanceOf(deadline.id))
        // 手动初始化沿用完整列表的日期顺序：有截止的在前，无截止的在后。
        assertEquals(1, snapshot.ordering.manualRankOf(deadline.id))
        assertEquals(2, snapshot.ordering.manualRankOf(undated.id))
        db.close()
    }

    @Test
    fun `manual ordering initializes once appends new tasks and restores deleted rank`() =
        runBlocking {
            val db = inMemory()
            val repository = TodoRepository(db)
            val later = sampleTask(id = "11111111-1111-4111-8111-111111111111", dueAt = 300)
            val earlier = sampleTask(id = "22222222-2222-4222-8222-222222222222", dueAt = 100)
            val undated = sampleTask(id = "33333333-3333-4333-8333-333333333333")
            repository.insert(later)
            repository.insert(earlier)
            repository.insert(undated)

            assertTrue(repository.setSortMode(TodoSortMode.MANUAL))
            var snapshot = repository.snapshot.first()
            assertEquals(1, snapshot.ordering.manualRankOf(earlier.id))
            assertEquals(2, snapshot.ordering.manualRankOf(later.id))
            assertEquals(3, snapshot.ordering.manualRankOf(undated.id))

            val appended = sampleTask(
                id = "44444444-4444-4444-8444-444444444444",
                dueAt = 50,
            )
            repository.insertWithImportance(appended, TodoImportance.LOW)
            snapshot = repository.snapshot.first()
            assertEquals(4, snapshot.ordering.manualRankOf(appended.id))
            assertEquals(TodoImportance.LOW, snapshot.ordering.importanceOf(appended.id))

            // 切走再切回不重置已有手排。
            assertTrue(repository.setSortMode(TodoSortMode.IMPORTANCE))
            assertTrue(repository.setSortMode(TodoSortMode.MANUAL))
            snapshot = repository.snapshot.first()
            assertEquals(1, snapshot.ordering.manualRankOf(earlier.id))
            assertEquals(2, snapshot.ordering.manualRankOf(later.id))
            assertEquals(3, snapshot.ordering.manualRankOf(undated.id))
            assertEquals(4, snapshot.ordering.manualRankOf(appended.id))

            // 删除后新建先占新位次，撤销删除项恢复原排名。
            val deleted = repository.deleteWithOrder(earlier.id)!!
            assertEquals(1, deleted.manualRank)
            val fresh = sampleTask(
                id = "55555555-5555-4555-8555-555555555555",
                dueAt = 60,
            )
            repository.insertWithImportance(fresh, TodoImportance.HIGH)
            val restored = repository.restoreWithOrder(deleted, now = 500)!!
            assertEquals(earlier.id, restored.id)
            snapshot = repository.snapshot.first()
            assertEquals(1, snapshot.ordering.manualRankOf(earlier.id))
            assertEquals(5, snapshot.ordering.manualRankOf(fresh.id))
            assertEquals(TodoImportance.NORMAL, snapshot.ordering.importanceOf(earlier.id))
            assertEquals(6, db.orderDao().state()!!.nextManualRank)

            // 迟到/重复恢复不能覆盖仍存在的任务，也不能占用已用位次。
            assertNull(repository.restoreWithOrder(deleted, now = 600))
            val late = TodoDeletedTask(
                task = sampleTask(id = "66666666-6666-4666-8666-666666666666"),
                importance = TodoImportance.HIGH,
                manualRank = 5,
            )
            assertTrue(repository.restoreWithOrder(late, now = 700) != null)
            snapshot = repository.snapshot.first()
            assertEquals(6, snapshot.ordering.manualRankOf(late.task.id))
            assertEquals(TodoImportance.HIGH, snapshot.ordering.importanceOf(late.task.id))
            assertEquals(7, db.orderDao().state()!!.nextManualRank)
            db.close()
        }

    @Test
    fun `content and importance save keeps the manual rank`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db)
        val task = sampleTask(dueAt = 2_000)
        repository.insertWithImportance(task, TodoImportance.NORMAL)
        assertTrue(repository.setSortMode(TodoSortMode.MANUAL))
        val rank = repository.snapshot.first().ordering.manualRankOf(task.id)

        val updated = repository.updateContentWithImportance(
            id = task.id,
            title = "改标题",
            reminderAt = task.reminderAt,
            dueAt = task.dueAt,
            firedAt = task.reminderFiredAt,
            now = 500,
            importance = TodoImportance.HIGH,
        )!!

        assertEquals("改标题", updated.title)
        assertEquals(2, updated.revision)
        val snapshot = repository.snapshot.first()
        assertEquals(TodoImportance.HIGH, snapshot.ordering.importanceOf(task.id))
        assertEquals(rank, snapshot.ordering.manualRankOf(task.id))
        assertNull(
            repository.updateContentWithImportance(
                id = "missing",
                title = "无",
                reminderAt = null,
                dueAt = null,
                firedAt = null,
                now = 600,
                importance = TodoImportance.LOW,
            ),
        )
        db.close()
    }

    @Test
    fun `manual move only swaps the expected adjacent task in the same group`() = runBlocking {
        val db = inMemory()
        val repository = TodoRepository(db)
        val first = sampleTask(id = "11111111-1111-4111-8111-111111111111", dueAt = 100)
        val second = sampleTask(id = "22222222-2222-4222-8222-222222222222", dueAt = 200)
        val third = sampleTask(id = "33333333-3333-4333-8333-333333333333", dueAt = 300)
        repository.insert(first)
        repository.insert(second)
        repository.insert(third)
        assertTrue(repository.setSortMode(TodoSortMode.MANUAL))

        // 越界、跳过相邻项、跨完成分组、邻居已删除时都拒绝，且不改动次序。
        assertFalse(repository.moveTask(first.id, TodoMoveDirection.UP, second.id))
        assertFalse(repository.moveTask(first.id, TodoMoveDirection.DOWN, third.id))
        repository.setCompleted(second.id, true, 250)
        assertFalse(repository.moveTask(first.id, TodoMoveDirection.DOWN, second.id))
        repository.setCompleted(second.id, false, 260)
        val deletedThird = repository.deleteWithOrder(third.id)!!
        assertFalse(repository.moveTask(second.id, TodoMoveDirection.DOWN, third.id))
        repository.restoreWithOrder(deletedThird, now = 270)

        assertTrue(repository.moveTask(first.id, TodoMoveDirection.DOWN, second.id))
        var snapshot = repository.snapshot.first()
        assertEquals(2, snapshot.ordering.manualRankOf(first.id))
        assertEquals(1, snapshot.ordering.manualRankOf(second.id))
        assertEquals(3, snapshot.ordering.manualRankOf(third.id))

        // 点按后原邻居已反向移动：同一个预期邻居不再相邻，必须拒绝。
        assertFalse(repository.moveTask(first.id, TodoMoveDirection.DOWN, second.id))
        assertTrue(repository.moveTask(first.id, TodoMoveDirection.UP, second.id))
        snapshot = repository.snapshot.first()
        assertEquals(1, snapshot.ordering.manualRankOf(first.id))
        assertEquals(2, snapshot.ordering.manualRankOf(second.id))

        assertTrue(repository.setSortMode(TodoSortMode.DATE))
        assertFalse(repository.moveTask(first.id, TodoMoveDirection.DOWN, second.id))
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
        val task = sampleTask()
        TodoRepository(first).insertWithImportance(task, TodoImportance.HIGH)
        assertTrue(TodoRepository(first).setSortMode(TodoSortMode.MANUAL))
        first.close()

        val second = Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        val reopenedRepository = TodoRepository(second)
        val reopened = reopenedRepository.tasks.first().single()
        assertEquals("买牛奶", reopened.title)
        val snapshot = reopenedRepository.snapshot.first()
        assertEquals(TodoSortMode.MANUAL, snapshot.ordering.mode)
        assertEquals(TodoImportance.HIGH, snapshot.ordering.importanceOf(task.id))
        assertEquals(1, snapshot.ordering.manualRankOf(task.id))
        second.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun `migration from v1 through v3 preserves fields and seeds date ordering`() = runBlocking {
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
        createVersionDatabase(name, version = 1, rows = legacyTasks)

        val migrated = Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .addMigrations(TodoDatabase.MIGRATION_1_2, TodoDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = TodoRepository(migrated)
            val rows = repository.all().associateBy(TodoTask::id)
            assertEquals(legacyTasks.associateBy(TodoTask::id), rows)
            assertEquals(TodoOrderStateEntity(mode = "DATE", nextManualRank = 0), migrated.orderDao().state())
            assertTrue(migrated.orderDao().orders().isEmpty())
            assertEquals(TodoSortMode.DATE, repository.snapshot.first().ordering.mode)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migration from v2 to v3 preserves fields and seeds date ordering`() = runBlocking {
        val name = "t-${UUID.randomUUID().toString().take(8)}.db"
        val legacyTasks = listOf(
            sampleTask(id = "with-deadline", dueAt = 4_000),
            sampleTask(id = "without-deadline", dueAt = null).copy(completed = true, completedAt = 400),
        )
        createVersionDatabase(name, version = 2, rows = legacyTasks)

        val migrated = Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .addMigrations(TodoDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = TodoRepository(migrated)
            assertEquals(
                legacyTasks.associateBy(TodoTask::id),
                repository.all().associateBy(TodoTask::id),
            )
            assertEquals(
                TodoOrderStateEntity(mode = "DATE", nextManualRank = 0),
                migrated.orderDao().state(),
            )
            assertTrue(migrated.orderDao().orders().isEmpty())
            assertEquals(TodoImportance.NORMAL, repository.importanceOf("with-deadline"))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersionDatabase(name: String, version: Int, rows: List<TodoTask>) {
        val schemaRoot = Json.parseToJsonElement(versionSchemaFile(version).readText()).jsonObject
        val databaseSchema = schemaRoot.getValue("database").jsonObject
        val tableSchema = databaseSchema.getValue("entities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == "todo_tasks" }
        val tableSql = tableSchema.getValue("createSql").jsonPrimitive.content
            .replace("\${TABLE_NAME}", "todo_tasks")
        val schemaVersion = databaseSchema.getValue("version").jsonPrimitive.content.toInt()
        check(schemaVersion == version) { "Expected committed Todo schema v$version" }

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
                    if (version >= 2) {
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

    private fun versionSchemaFile(version: Int): File {
        val relativePaths = listOf(
            "schemas/com.upcpocket.app.todo.data.TodoDatabase/$version.json",
            "feature/todo/schemas/com.upcpocket.app.todo.data.TodoDatabase/$version.json",
        )
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory: File? = File(workingDirectory).canonicalFile
        while (true) {
            val currentDirectory = directory ?: break
            relativePaths.firstNotNullOfOrNull { relative ->
                File(currentDirectory, relative).takeIf { it.isFile }
            }?.let { return it }
            directory = currentDirectory.parentFile
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
