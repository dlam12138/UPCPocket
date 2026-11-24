package com.upcpocket.app.todo

import kotlinx.coroutines.flow.Flow

/**
 * 本机个人日程的稳定只读模型。
 *
 * [startAt] / [endAt] 是已确定的时点毫秒值，不由设备当前时区重新解释；录入与编辑统一
 * 使用 `Asia/Shanghai`。日程与学校账号、待办截止和一次性提醒相互独立。
 */
data class PersonalSchedule(
    val id: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val location: String?,
    val note: String?,
    val revision: Int,
)

/**
 * 个人日程的唯一读写入口。
 *
 * 时间窗口为半开区间 `[fromInclusive, untilExclusive)`：开始早于窗口结束且结束晚于
 * 窗口开始即视为重叠，零点整结束不会落到次日。写入不接受空标题或结束不晚于开始
 * 的值；更新与删除均以 [PersonalSchedule.revision] 做条件写入，旧版本不能覆盖或
 * 删除新版本。
 */
interface PersonalScheduleStore {

    /** 观察与窗口相交的全部日程，按开始时间排序。 */
    fun observeWindow(fromInclusive: Long, untilExclusive: Long): Flow<List<PersonalSchedule>>

    suspend fun byId(id: String): PersonalSchedule?

    suspend fun add(
        title: String,
        startAt: Long,
        endAt: Long,
        location: String?,
        note: String?,
    ): PersonalSchedule

    /** 条件更新；日程不存在或 [expectedRevision] 已过期时返回 null，不写入。 */
    suspend fun update(
        id: String,
        expectedRevision: Int,
        title: String,
        startAt: Long,
        endAt: Long,
        location: String?,
        note: String?,
    ): PersonalSchedule?

    /** 条件删除；日程不存在或 [expectedRevision] 已过期时返回 null，不删除。 */
    suspend fun delete(id: String, expectedRevision: Int): PersonalSchedule?
}
