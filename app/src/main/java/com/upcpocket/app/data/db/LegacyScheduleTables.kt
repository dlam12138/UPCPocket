package com.upcpocket.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────────────────────
// 轻量版：课表功能已整体移除，这里只保留**旧的表结构声明**。
//
// 为什么还留着：这些表与专注记录（focus_tags / focus_sessions）在同一个
// Room 数据库里。把表从 @Database 的 entities 里删掉会改变 Room 的 schema
// 身份校验值，已安装用户的数据库在升级后会被判定为"结构不一致"，除非做一次
// 破坏性迁移。本版本的用户指令是"不能为了删功能而清数据"，所以选择：
//
//   · 表结构原样保留（schema 不变、version 仍是 1），老用户升级后数据库可直接打开；
//   · 删掉全部 DAO、Repository、ViewModel、导入、展示和组件代码 —— 应用里没有任何
//     读写这些表的路径，它们只是占位，等将来确认可以安全迁移时再单独处理；
//   · 专注标签不再从课程名派生，历史课程数据也不会被读取或展示。
//
// 不要在这些类上新增字段或索引：那会改变 schema，等于强制所有老用户走迁移。
// ─────────────────────────────────────────────────────────────────────────────

/** 旧「本地学期」表（停用，仅为保持数据库结构不变） */
@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey val id: Int,
    val code: String,
    val nameZh: String,
    val schoolYear: String,
    val startDate: String?,
    val endDate: String?,
    val weekStartOnSunday: Boolean,
    val weekIndicesCsv: String,
    val isCurrent: Boolean,
    val updatedAt: Long,
)

/** 旧「课程聚合」表（停用，仅为保持数据库结构不变） */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val key: String,
    val semesterId: Int,
    val code: String,
    val nameZh: String,
    val credits: Double,
    val openDepartment: String?,
    val teachersCsv: String,
    val updatedAt: Long,
)

/** 旧「上课记录 / 自定义日程」表（停用，仅为保持数据库结构不变） */
@Entity(tableName = "course_sessions")
data class CourseSessionEntity(
    @PrimaryKey val id: String,
    val courseKey: String,
    val semesterId: Int,
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
    val experiment: Boolean = false,
    val updatedAt: Long,
)

/** 旧「课表格子显示覆盖」表（停用，仅为保持数据库结构不变） */
@Entity(tableName = "display_overrides")
data class DisplayOverrideEntity(
    @PrimaryKey val id: String,
    val semesterId: Int,
    val courseCode: String,
    val kind: String,
    val weekday: Int?,
    val startUnit: Int?,
    val weeks: String? = null,
    val value: String,
    val updatedAt: Long,
)

/** 旧「节次作息」表（停用，仅为保持数据库结构不变） */
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
