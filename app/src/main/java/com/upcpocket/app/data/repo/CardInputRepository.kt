package com.upcpocket.app.data.repo

import com.upcpocket.app.data.prefs.UiPrefsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 校园卡余额功能区的学号输入持久层。
 *
 * 与余额快照（`card-balance-v1.json`）分开：即使从未查询成功，甚至上次查询失败，
 * 只要用户输入过，重启后仍能恢复；清除动作会显式删除它。
 * 不保存查询结果，也不发起网络请求。
 */
class CardInputRepository(
    private val prefs: UiPrefsStore,
) {
    val studentId: Flow<String> = prefs.flow
        .map { it.cardStudentId }
        .distinctUntilChanged()

    suspend fun save(studentId: String) {
        prefs.setCardStudentId(studentId.trim())
    }

    suspend fun clear() {
        prefs.clearCardStudentId()
    }
}
