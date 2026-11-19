package com.upcpocket.app.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 图书馆首页可切换的校区；求是座位归属古镇口。 */
enum class LibraryCampus(val label: String) {
    TANGDAOWAN("唐岛湾"),
    GUZHENKOU("古镇口"),
}

/**
 * 宿主读取到的常用校区。
 *
 * [Unread] 只作为页面初始状态使用，宿主不会发射；页面在未读取完成前不按任何
 * 默认范围裁剪入口。[All] 表示“全部校区”，保留原来的三个入口。
 */
sealed interface LibraryCampusPreference {
    data object Unread : LibraryCampusPreference
    data object All : LibraryCampusPreference
    data class Selected(val campus: LibraryCampus) : LibraryCampusPreference
}

/**
 * 宿主注入的窄接口：只提供常用校区，不暴露账号、业务或全局偏好。
 *
 * 宿主在 DataStore 真实读取完成后才发射；页面不能用“初始默认值”代替读取结果。
 */
fun interface LibraryCampusPreferenceProvider {
    fun preferredCampus(): Flow<LibraryCampusPreference>
}

/** 未注入宿主偏好时的默认实现：等同“全部校区”。 */
object AllLibraryCampusPreferenceProvider : LibraryCampusPreferenceProvider {
    override fun preferredCampus(): Flow<LibraryCampusPreference> =
        flowOf(LibraryCampusPreference.All)
}
