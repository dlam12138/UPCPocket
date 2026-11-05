package com.upcpocket.app.data.repo

import com.upcpocket.app.card.CardInputStore
import com.upcpocket.app.data.prefs.UiPrefsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 旧版校园卡学号草稿的持久层。
 *
 * M2 起不再从页面写入或读取为查询目标，只用于账号设置提示以及账号绑定/换号/删除
 * 后的迁移清理。余额目标来自统一账号的受控学号。
 */
class CardInputRepository(
    private val prefs: UiPrefsStore,
) : CardInputStore {
    override val studentId: Flow<String> = prefs.flow
        .map { it.cardStudentId }
        .distinctUntilChanged()

    override suspend fun save(studentId: String) {
        prefs.setCardStudentId(studentId.trim())
    }

    override suspend fun clear() {
        prefs.clearCardStudentId()
    }
}
