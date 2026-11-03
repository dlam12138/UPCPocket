package com.upcpocket.app.card

import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.data.ecard.cardBindingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** RemoteViews 渲染所需的绑定 + 缓存状态；不包含账号密码或任意查询目标。 */
data class CardWidgetViewState(
    val status: CardBindingStatus,
    val snapshot: DirectCardSnapshot?,
)

/**
 * 余额插件对页面与桌面组件暴露的唯一业务入口。
 *
 * 页面、组件和 Worker 不各存学号，也不接受外部目标；所有查询都从当前统一账号的
 * 受控学号读取。成功/清除后由插件通过 [onSnapshotChanged] 通知宿主刷新 RemoteViews。
 */
class CardBalanceService(
    repositoryProvider: () -> DirectCardBalanceRepository,
    private val account: SchoolAccountStudentIdentityReader,
    private val legacyInput: CardInputStore,
    private val onSnapshotChanged: () -> Unit,
) {
    // 插件注册阶段只创建服务对象；首次读取快照/查询时才碰文件和锁。
    private val repository: DirectCardBalanceRepository by lazy(repositoryProvider)

    val snapshot: Flow<DirectCardSnapshot?> get() = repository.snapshot

    val accountState: StateFlow<SchoolAccountState> get() = account.state

    val bindingStatus: Flow<CardBindingStatus> = combine(
        account.state,
        account.studentIdentity,
    ) { state, identity -> cardBindingStatus(state, identity) }

    fun bindingStatus(): CardBindingStatus = cardBindingStatus(account)

    fun canRefreshNow(): Boolean = bindingStatus().binding != null

    /**
     * 组件排队任务的绑定指纹：只有内部 accountId + 凭据代次，不含学号/密码。
     * 旧任务可据此在换号/改密后退出，而不是把新账号当作旧点击目标。
     */
    fun bindingToken(): String? = bindingStatus().binding?.let { binding ->
        "${binding.accountId.value}:${binding.credentialGeneration.value}"
    }

    /** 旧版本已排队任务没有 token 时保持兼容，执行时仍会读取当前绑定并再次核对。 */
    fun isBindingTokenCurrent(token: String?): Boolean =
        token == null || token == bindingToken()

    fun widgetStateNow(): CardWidgetViewState =
        CardWidgetViewState(bindingStatus(), repository.cachedBlocking())

    /**
     * 账号变化或页面打开时调用：迁移同号旧快照并清除旧明文草稿。
     * 只读写本机文件，不联网；无绑定/清理中不做任何事。
     */
    suspend fun prepare() {
        val binding = repository.currentBinding() ?: return
        repository.prepareAccountBinding()
        if (repository.currentBinding() == binding) {
            // 草稿清理失败不应阻止余额缓存展示；账号层会在启动/状态变化时继续尝试。
            runCatching { withContext(Dispatchers.IO) { legacyInput.clear() } }
        }
    }

    suspend fun cached(): DirectCardSnapshot? {
        prepare()
        return repository.cached()
    }

    /** 组件首次无历史快照但已有绑定账号时也可以直接查询当前绑定。 */
    suspend fun refreshCurrent(): DirectCardSnapshot {
        val value = repository.refreshCurrent()
        // 余额已经成功落盘；草稿清理失败不能把本次查询误报为失败。
        runCatching { withContext(Dispatchers.IO) { legacyInput.clear() } }
        onSnapshotChanged()
        return value
    }

    /** 仅清除余额缓存，不删除学校账号、密码或其他业务状态。 */
    suspend fun clearCache() {
        // 门闩落盘后立即重绘，组件在删除过程中就撤下旧值。
        repository.clearCache(onStarted = onSnapshotChanged)
        onSnapshotChanged()
    }

    /**
     * 换号/删号失效器专用：清缓存和旧学号草稿，但不反向读取账号仓库。
     * 否则账号锁内回调会与账号状态读取互相等待。
     */
    suspend fun clearForAccountChange() {
        try {
            repository.clearForAccountChange(onStarted = onSnapshotChanged)
        } finally {
            // 即使余额文件删除失败，也要先删除明文旧草稿；失败仍由失效器返回。
            withContext(Dispatchers.IO) { legacyInput.clear() }
            onSnapshotChanged()
        }
    }
}
