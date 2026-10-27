package com.upcpocket.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.db.AppDatabase
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.domain.CsvScheduleImport
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ScheduleRepositoryCsvImportTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val a = CsvScheduleImport.Row("数学", 1, 1, 2, listOf(1,3), "教师", "教室")
    @Before fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ScheduleRepository(db, UiPrefsStore(context)); repo.ensureSeeded()
    }
    @After fun close() { db.close() }
    @Test fun importPersistsAndDuplicateImportDoesNotMultiply() = runTest {
        assertEquals(1 to 1, repo.importCsvRows(1,listOf(a,a)))
        assertEquals(0 to 1, repo.importCsvRows(1,listOf(a)))
        assertEquals(2,db.courseSessionDao().bySemester(1).size)
        assertEquals(1,db.courseDao().bySemester(1).size)
        val sessions = db.courseSessionDao().bySemester(1)
        assertTrue(sessions.all { it.teacherName == "教师" && it.roomText == "教室" && it.startTime != null })
    }
    @Test fun distinctSlotsSurviveAndExistingDataRemains() = runTest {
        repo.savePlan(a.spec(1).copy(code="Cexisting",name="原有课程"))
        assertEquals(2 to 0,repo.importCsvRows(1,listOf(a,a.copy(weekday=2))))
        assertEquals(6,db.courseSessionDao().bySemester(1).size)
    }
    @Test fun sameCourseDifferentWeeksIsNotSwallowed() = runTest {
        assertEquals(2 to 0,repo.importCsvRows(1,listOf(a,a.copy(weeks=listOf(2,4)))))
        assertEquals(listOf(1,2,3,4),db.courseSessionDao().bySemester(1).map { it.weekIndex }.sorted())
    }
    @Test fun invalidLaterRowWritesNothing() = runTest {
        assertTrue(runCatching { repo.importCsvRows(1,listOf(a,a.copy(weeks=listOf(999)))) }.isFailure)
        assertTrue(db.courseSessionDao().bySemester(1).isEmpty())
    }
    @Test fun failureInSecondInsertRollsBackWholeBatch() = runTest {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON courses WHEN NEW.nameZh = '失败课程' BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
        assertTrue(runCatching { repo.importCsvRows(1,listOf(a,a.copy(name="失败课程"))) }.isFailure)
        assertTrue(db.courseSessionDao().bySemester(1).isEmpty())
        assertTrue(db.courseDao().bySemester(1).isEmpty())
    }
}
