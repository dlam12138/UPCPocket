package com.upcpocket.app.card

import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 余额插件对页面与桌面组件暴露的唯一业务入口。
 *
 * 页面不再读取全局容器，也不直接调用组件刷新；成功或清除后由插件通过
 * [onSnapshotChanged] 通知宿主适配器刷新 RemoteViews。
 */
class CardBalanceService(
    repositoryProvider: () -> DirectCardBalanceRepository,
    private val input: CardInputStore,
    private val onSnapshotChanged: () -> Unit,
) {
    // 插件注册阶段只创建服务对象；首次读取快照/查询时才碰文件和锁。
    private val repository: DirectCardBalanceRepository by lazy(repositoryProvider)

    val snapshot: Flow<DirectCardSnapshot?> get() = repository.snapshot
    val savedStudentId: Flow<String> = input.studentId

    suspend fun cached(): DirectCardSnapshot? = repository.cached()

    fun cachedBlocking(): DirectCardSnapshot? = repository.cachedBlocking()

    suspend fun refresh(studentId: String): DirectCardSnapshot {
        val value = repository.refresh(studentId)
        onSnapshotChanged()
        return value
    }

    /** 组件刷新只针对最后一次成功快照的学号，不切换到草稿输入。 */
    suspend fun refreshCurrent(): DirectCardSnapshot? = repository.refreshCurrent()

    suspend fun clear() {
        repository.clear()
        input.clear()
        onSnapshotChanged()
    }

    suspend fun saveInput(studentId: String) = input.save(studentId)
}
