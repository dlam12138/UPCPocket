package com.upcpocket.app.data.repo

import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.power.PowerInput
import com.upcpocket.app.power.PowerInputStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 电费输入的持久层。
 *
 * 只负责本机配置，不发起任何网络请求：输入保存成功与否与学校页面是否可达无关，
 * 查询失败也不会清掉这里。宿主通过该实现向 power 插件提供输入能力。
 */
class PowerInputRepository(
    private val prefs: UiPrefsStore,
) : PowerInputStore {
    override val input: Flow<PowerInput> = prefs.flow
        .map { PowerInput(building = it.powerBuilding, room = it.powerRoom) }
        .distinctUntilChanged()

    override suspend fun save(building: String, room: String) {
        prefs.savePowerInput(building.trim(), room.trim())
    }

    override suspend fun clear() {
        prefs.clearPowerInput()
    }
}
