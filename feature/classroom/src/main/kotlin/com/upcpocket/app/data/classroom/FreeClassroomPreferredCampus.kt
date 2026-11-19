package com.upcpocket.app.data.classroom

/**
 * 宿主注入的常用校区，只用于本次进入空闲教室时的初始校区。
 *
 * 返回具体校区：首次查询直接使用该校区；页内临时切换不写共用偏好，也不覆盖本模块
 * 原有的“上次校区”记录。返回 null 表示“全部校区”：继续读取并在页内切换时更新
 * [FreeClassroomCampusStore] 的上次校区记录。
 */
fun interface FreeClassroomPreferredCampus {
    suspend fun read(): FreeClassroomCampus?
}

/** 未注入宿主偏好时的默认实现：等同“全部校区”。 */
object AllFreeClassroomPreferredCampus : FreeClassroomPreferredCampus {
    override suspend fun read(): FreeClassroomCampus? = null
}
