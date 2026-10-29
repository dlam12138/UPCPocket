package com.upcpocket.app.data.repo

import com.upcpocket.app.data.prefs.UiPrefsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 唐岛湾电费的上次输入。楼号与房间号作为一份配置一起保存。 */
data class PowerInput(
    val building: String = "",
    val room: String = "",
) {
    val isEmpty: Boolean get() = building.isBlank() && room.isBlank()
}

/**
 * 电费输入的持久层。
 *
 * 只负责本机配置，不发起任何网络请求：输入保存成功与否与学校页面是否可达无关，
 * 查询失败也不会清掉这里。将来做 RemoteViews 组件时可直接复用 [input]。
 */
class PowerInputRepository(
    private val prefs: UiPrefsStore,
) {
    val input: Flow<PowerInput> = prefs.flow
        .map { PowerInput(building = it.powerBuilding, room = it.powerRoom) }
        .distinctUntilChanged()

    suspend fun save(building: String, room: String) {
        prefs.savePowerInput(building.trim(), room.trim())
    }

    suspend fun clear() {
        prefs.clearPowerInput()
    }
}
