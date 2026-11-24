package com.upcpocket.app.todo.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.upcpocket.app.todo.PersonalSchedule

/**
 * 个人日程独立表；与待办任务、排序记录及学校账号数据均无外键或清理联动。
 *
 * 窗口查询同时使用开始与结束时间，两个索引分别服务 `startAt < until` 与
 * `endAt > from` 两个条件。
 */
@Entity(
    tableName = "personal_schedules",
    indices = [
        Index(value = ["startAt"]),
        Index(value = ["endAt"]),
    ],
)
data class PersonalScheduleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val location: String?,
    val note: String?,
    val revision: Int,
)

fun PersonalScheduleEntity.toDomain(): PersonalSchedule = PersonalSchedule(
    id = id,
    title = title,
    startAt = startAt,
    endAt = endAt,
    location = location,
    note = note,
    revision = revision,
)
