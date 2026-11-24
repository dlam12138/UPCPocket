package com.upcpocket.app.todo

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.todo.data.PersonalScheduleTime
import com.upcpocket.app.todo.data.TodoDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PersonalScheduleStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `file database reopens and rejects stale revisions`() = runBlocking {
        val name = "s-${UUID.randomUUID()}.db"
        val first = openDatabase(name)
        val store = PersonalScheduleService { first }
        val created = store.add(
            title = " 例会 ",
            startAt = shanghai("2026-11-24T09:00"),
            endAt = shanghai("2026-11-24T10:30"),
            location = " 教学楼 ",
            note = " 带材料 ",
        )

        assertEquals("例会", created.title)
        assertEquals("教学楼", created.location)
        assertEquals("带材料", created.note)
        assertEquals(1, created.revision)
        assertEquals(created, store.byId(created.id))

        val updated = store.update(
            id = created.id,
            expectedRevision = 1,
            title = "改期",
            startAt = shanghai("2026-11-24T11:00"),
            endAt = shanghai("2026-11-24T12:00"),
            location = null,
            note = "   ",
        )!!
        assertEquals("改期", updated.title)
        assertEquals(2, updated.revision)
        assertNull(updated.note)
        assertNull(
            store.update(
                id = created.id,
                expectedRevision = 1,
                title = "旧表单",
                startAt = updated.startAt,
                endAt = updated.endAt,
                location = null,
                note = null,
            ),
        )
        assertEquals(updated, store.byId(created.id))

        first.close()
        val second = openDatabase(name)
        val reopened = PersonalScheduleService { second }
        assertEquals(updated, reopened.byId(created.id))

        assertNull(reopened.delete(created.id, expectedRevision = 1))
        assertNotNull(reopened.byId(created.id))
        assertEquals(updated, reopened.delete(created.id, expectedRevision = 2))
        assertNull(reopened.byId(created.id))
        assertNull(reopened.delete(created.id, expectedRevision = 2))
        assertNull(
            reopened.update(
                id = created.id,
                expectedRevision = 2,
                title = "迟到写入",
                startAt = updated.startAt,
                endAt = updated.endAt,
                location = null,
                note = null,
            ),
        )
        assertNull(reopened.byId(created.id))
        assertTrue(reopened.observeWindow(0, Long.MAX_VALUE).first().isEmpty())

        second.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun `blank title and non-positive duration are rejected without writing`() = runBlocking {
        val db = inMemory()
        val store = PersonalScheduleService { db }
        val start = shanghai("2026-11-24T09:00")
        val end = shanghai("2026-11-24T10:00")

        assertRejected { store.add("   ", start, end, null, null) }
        assertRejected { store.add("零长度", start, start, null, null) }
        assertRejected { store.add("倒序", end, start, null, null) }
        assertTrue(store.observeWindow(0, Long.MAX_VALUE).first().isEmpty())
        db.close()
    }

    @Test
    fun `half-open device windows keep midnight end out of the next day`() = runBlocking {
        val db = inMemory()
        val store = PersonalScheduleService { db }
        val endsAtMidnight = store.addAt(
            title = "零点结束",
            start = "2026-11-24T21:00",
            end = "2026-11-25T00:00",
        )
        val crossMidnight = store.addAt(
            title = "跨午夜",
            start = "2026-11-24T23:00",
            end = "2026-11-25T00:30",
        )
        val startsAtMidnight = store.addAt(
            title = "零点开始",
            start = "2026-11-25T00:00",
            end = "2026-11-25T01:00",
        )

        val shanghai = PersonalScheduleTime.SHANGHAI
        assertEquals(
            setOf(endsAtMidnight.id, crossMidnight.id),
            windowIds(store, LocalDate.parse("2026-11-24"), shanghai),
        )
        assertEquals(
            setOf(crossMidnight.id, startsAtMidnight.id),
            windowIds(store, LocalDate.parse("2026-11-25"), shanghai),
        )

        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(
            setOf(endsAtMidnight.id),
            windowIds(store, LocalDate.parse("2026-11-24"), tokyo),
        )
        assertEquals(
            setOf(endsAtMidnight.id, crossMidnight.id, startsAtMidnight.id),
            windowIds(store, LocalDate.parse("2026-11-25"), tokyo),
        )
        db.close()
    }

    private suspend fun windowIds(
        store: PersonalScheduleStore,
        date: LocalDate,
        zone: ZoneId,
    ): Set<String> {
        val window = PersonalScheduleTime.dayWindow(date, zone)
        return store.observeWindow(window.fromInclusive, window.untilExclusive)
            .first()
            .map(PersonalSchedule::id)
            .toSet()
    }

    private suspend fun PersonalScheduleService.addAt(
        title: String,
        start: String,
        end: String,
    ): PersonalSchedule = add(
        title = title,
        startAt = shanghai(start),
        endAt = shanghai(end),
        location = null,
        note = null,
    )

    private fun shanghai(text: String): Long =
        requireNotNull(PersonalScheduleTime.resolve(LocalDateTime.parse(text)))

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected IllegalArgumentException")
    }

    private fun openDatabase(name: String): TodoDatabase =
        Room.databaseBuilder(context, TodoDatabase::class.java, name)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

    private fun inMemory(): TodoDatabase = Room.inMemoryDatabaseBuilder(
        context,
        TodoDatabase::class.java,
    ).allowMainThreadQueries().build()
}
