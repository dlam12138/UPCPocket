package com.upcpocket.app.card

import kotlinx.coroutines.flow.Flow

/**
 * 旧版校园卡学号草稿的持久化边界。
 *
 * 由宿主提供实现（当前是 app 的 DataStore 适配器）。M2 起它不再是查询来源：
 * 只能用于账号设置提示，并在账号绑定/迁移或删除/换号时清理。
 */
interface CardInputStore {
    val studentId: Flow<String>

    suspend fun save(studentId: String)

    suspend fun clear()
}
