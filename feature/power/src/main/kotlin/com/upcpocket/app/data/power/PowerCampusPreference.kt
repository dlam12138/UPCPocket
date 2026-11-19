package com.upcpocket.app.data.power

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 电费页面的校区范围。 */
enum class PowerCampus(val label: String) {
    TANGDAOWAN("唐岛湾"),
    GUZHENKOU("古镇口"),
}

/**
 * 宿主读取到的常用校区。
 *
 * [Unread] 只作为页面初始状态使用，宿主不会发射；未读取完成前页面不渲染任何校区
 * 内容，也不会加载唐岛湾楼号。[All] 表示“全部校区”，保留原来的两个入口。
 */
sealed interface PowerCampusPreference {
    data object Unread : PowerCampusPreference
    data object All : PowerCampusPreference
    data class Selected(val campus: PowerCampus) : PowerCampusPreference
}

/**
 * 宿主注入的窄接口：只提供常用校区，不暴露账号、业务或全局偏好。
 *
 * 宿主在 DataStore 真实读取完成后才发射；页面不能用“初始默认值”代替读取结果。
 */
fun interface PowerCampusPreferenceProvider {
    fun preferredCampus(): Flow<PowerCampusPreference>
}

/** 未注入宿主偏好时的默认实现：等同“全部校区”。 */
object AllPowerCampusPreferenceProvider : PowerCampusPreferenceProvider {
    override fun preferredCampus(): Flow<PowerCampusPreference> =
        flowOf(PowerCampusPreference.All)
}
