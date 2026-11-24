package com.upcpocket.app.todo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalScheduleDao {

    /**
     * 半开窗口 `[fromInclusive, untilExclusive)` 的重叠查询。
     *
     * 零点整结束的日程在本日 `endAt == 次日 fromInclusive`，不会进入次日结果。
     */
    @Query(
        """
        SELECT * FROM personal_schedules
        WHERE startAt < :untilExclusive AND endAt > :fromInclusive
        ORDER BY startAt ASC, endAt ASC, id ASC
        """,
    )
    fun observeWindow(fromInclusive: Long, untilExclusive: Long): Flow<List<PersonalScheduleEntity>>

    @Query("SELECT * FROM personal_schedules WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PersonalScheduleEntity?

    /** 新建只使用新 UUID；重复主键直接失败，不能静默覆盖既有日程。 */
    @Insert
    suspend fun insert(schedule: PersonalScheduleEntity)

    @Query(
        """
        UPDATE personal_schedules
        SET title = :title,
            startAt = :startAt,
            endAt = :endAt,
            location = :location,
            note = :note,
            revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision
        """,
    )
    suspend fun updateIfRevision(
        id: String,
        expectedRevision: Int,
        title: String,
        startAt: Long,
        endAt: Long,
        location: String?,
        note: String?,
    ): Int

    @Query("DELETE FROM personal_schedules WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteIfRevision(id: String, expectedRevision: Int): Int
}
