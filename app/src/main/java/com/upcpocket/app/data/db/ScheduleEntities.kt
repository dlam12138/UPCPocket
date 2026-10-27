package com.upcpocket.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// ── 学期 ────────────────────────────────────────────────────────────────

/**
 * 本地学期。
 *
 * 原项目由教务接口下发学期；本原型没有校园接口，学期是**用户自己定义**的：
 * 开学日、总周数、名称都在「课程表设置」里手填，或者由
 * [com.upcpocket.app.data.local.LocalDefaults] 在首次启动时按今天生成一个。
 * 没有任何远端会改写它。
 */
@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey val id: Int,
    val code: String,
    val nameZh: String,
    val schoolYear: String,
    val startDate: String?,
    val endDate: String?,
    val weekStartOnSunday: Boolean,
    /** 教学周号，逗号分隔，如 "1,2,3,...,18" */
    val weekIndicesCsv: String,
    val isCurrent: Boolean,
    val updatedAt: Long,
)

@Dao
interface SemesterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SemesterEntity>)

    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    suspend fun all(): List<SemesterEntity>

    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters WHERE isCurrent = 1 LIMIT 1")
    suspend fun current(): SemesterEntity?

    @Query("UPDATE semesters SET isCurrent = 0")
    suspend fun clearCurrent()

    @Query("UPDATE semesters SET isCurrent = 1 WHERE id = :id")
    suspend fun markCurrent(id: Int)
}

// ── 课程 ────────────────────────────────────────────────────────────────

/**
 * 课程聚合（课程总览用）。
 *
 * M1 只有一种来源：用户自己补录的课程（[CourseSessionEntity.source] = `custom`）。
 * 字段保留是为了不改动课表网格、课程详情与组件那条已经验证过的渲染路径。
 */
@Entity(tableName = "courses")
data class CourseEntity(
    /** "semesterId:courseCode" */
    @PrimaryKey val key: String,
    val semesterId: Int,
    val code: String,
    val nameZh: String,
    val credits: Double,
    val openDepartment: String?,
    /** 逗号分隔的教师名 */
    val teachersCsv: String,
    val updatedAt: Long,
)

@Dao
interface CourseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CourseEntity>)

    @Query("SELECT * FROM courses WHERE semesterId = :semesterId ORDER BY nameZh")
    suspend fun bySemester(semesterId: Int): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE semesterId = :semesterId ORDER BY nameZh")
    fun observeBySemester(semesterId: Int): Flow<List<CourseEntity>>

    @Query("DELETE FROM courses WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: Int)

    /** 删掉某一门课（用户删掉手动补录的课程时用；普通日程不会进这张表） */
    @Query("DELETE FROM courses WHERE semesterId = :semesterId AND code = :courseCode")
    suspend fun deleteOne(semesterId: Int, courseCode: String)

    @Query("SELECT COUNT(*) FROM courses WHERE semesterId = :semesterId")
    suspend fun countBySemester(semesterId: Int): Int

    /** 番茄钟的标签候选：所有学期出现过的课名（去重） */
    @Query("SELECT DISTINCT nameZh FROM courses ORDER BY nameZh ASC")
    fun observeAllNames(): Flow<List<String>>
}

// ── 上课记录 ────────────────────────────────────────────────────────────

/**
 * 课表格子里的一条记录（自定义日程 / 手动补录的课程）。
 *
 * 原项目这里还有 `source = 'eas'`（教务）与 `'gms'`（研究生）两种来源。
 * 本原型只保留 `custom`：**这条记录只能由用户在编辑器里创建和删除**，
 * 没有别的代码路径会写它，也不会被任何后台任务覆盖。
 */
@Entity(tableName = "course_sessions")
data class CourseSessionEntity(
    @PrimaryKey val id: String,
    val courseKey: String,
    val semesterId: Int,
    /** 目前恒为 "custom"；保留字段是为了让网格/今天/组件的渲染分支不用改 */
    val source: String,
    val courseName: String,
    val weekIndex: Int,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val date: String?,
    val startTime: String?,
    val endTime: String?,
    val teacherName: String?,
    val roomText: String?,
    val campusText: String?,
    val color: String?,
    /** 实验/见习类标记（自定义日程没有这个概念，恒为 false） */
    val experiment: Boolean = false,
    val updatedAt: Long,
)

/**
 * 显示覆盖：用户对手动补录课程的显示信息做的自定义（课程名 / 各时段教室）。
 *
 * 存自己的表、按 key 定位；纯本地数据，不存在被远端刷新冲掉的问题。
 *
 * - `kind = "name"`：课程名覆盖，作用整门课；
 * - `kind = "room"`：教室覆盖，作用到「星期 + 起始节」这个时段
 *   [weeks] 为 null = 该时段**全部周**；否则只作用于逗号分隔的这些教学周——
 *   单双周换教室的课（单周 302、双周 405）靠它分成两条互不干扰。
 */
@Entity(tableName = "display_overrides")
data class DisplayOverrideEntity(
    @PrimaryKey val id: String,     // "<semesterId>|<courseCode>|name" 或 "|room|<weekday>|<startUnit>|<weeks|all>"
    val semesterId: Int,
    val courseCode: String,
    val kind: String,               // "name" | "room"
    val weekday: Int?,
    val startUnit: Int?,
    val weeks: String? = null,      // kind=room 时的教学周（如 "1,3,5"）；null = 全部周
    val value: String,
    val updatedAt: Long,
)

@Dao
interface DisplayOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DisplayOverrideEntity)

    @Query("SELECT * FROM display_overrides WHERE semesterId = :semesterId")
    suspend fun bySemester(semesterId: Int): List<DisplayOverrideEntity>

    @Query("DELETE FROM display_overrides WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清掉某时段的**全部**教室覆盖（不分周；保存「全部周」覆盖前先清场） */
    @Query(
        "DELETE FROM display_overrides WHERE semesterId = :semesterId AND courseCode = :courseCode " +
            "AND kind = 'room' AND weekday = :weekday AND startUnit = :startUnit",
    )
    suspend fun deleteRoomBySlot(semesterId: Int, courseCode: String, weekday: Int, startUnit: Int)

    @Query("DELETE FROM display_overrides WHERE semesterId = :semesterId AND courseCode = :courseCode")
    suspend fun deleteByCourse(semesterId: Int, courseCode: String)

    @Query("DELETE FROM display_overrides WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: Int)
}

@Dao
interface CourseSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CourseSessionEntity>)

    @Query("SELECT * FROM course_sessions WHERE semesterId = :semesterId")
    suspend fun bySemester(semesterId: Int): List<CourseSessionEntity>

    @Query("SELECT * FROM course_sessions WHERE semesterId = :semesterId")
    fun observeBySemester(semesterId: Int): Flow<List<CourseSessionEntity>>

    @Query("SELECT * FROM course_sessions WHERE semesterId = :semesterId AND source = :source")
    suspend fun bySemesterAndSource(semesterId: Int, source: String): List<CourseSessionEntity>

    /** 重写某个自定义日程：先删掉它的全部周次记录，再按新的周次插回去 */
    @Query("DELETE FROM course_sessions WHERE courseKey = :courseKey")
    suspend fun deleteByCourseKey(courseKey: String)

    @Query("SELECT COUNT(*) FROM course_sessions WHERE semesterId = :semesterId")
    suspend fun countBySemester(semesterId: Int): Int
}

// ── 节次 ────────────────────────────────────────────────────────────────

/**
 * 节次作息。
 *
 * 原项目从教务接口拉；本原型在首次启动时种一份 UPC 作息（见
 * [com.upcpocket.app.data.local.LocalDefaults]）。用户可以在「课程表设置」里改起止时间。
 */
@Entity(tableName = "periods")
data class PeriodEntity(
    @PrimaryKey val indexNo: Int,
    val nameZh: String,
    val startTime: Int,
    val endTime: Int,
    val dayPart: String,
    val segmentIndex: Int,
    val updatedAt: Long,
)

@Dao
interface PeriodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PeriodEntity>)

    @Query("SELECT * FROM periods ORDER BY indexNo")
    suspend fun all(): List<PeriodEntity>

    @Query("SELECT * FROM periods ORDER BY indexNo")
    fun observeAll(): Flow<List<PeriodEntity>>
}
