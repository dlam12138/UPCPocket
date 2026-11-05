package com.upcpocket.app.power

import kotlinx.coroutines.flow.Flow

/** 唐岛湾电费的上次输入。楼号与房间号作为一份配置一起保存。 */
data class PowerInput(
    val building: String = "",
    val room: String = "",
) {
    val isEmpty: Boolean get() = building.isBlank() && room.isBlank()
}

/**
 * 电费输入的持久化边界。
 *
 * 宿主用现有 `UiPrefsStore` 实现；插件不直接读写宿主偏好文件。
 */
interface PowerInputStore {
    val input: Flow<PowerInput>

    suspend fun save(building: String, room: String)

    suspend fun clear()
}
