package com.upcpocket.app.card

import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.data.ecard.CachedRead
import com.upcpocket.app.data.ecard.CardBalanceBinding
import com.upcpocket.app.data.ecard.CardBindingAvailability
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
 * 组件发布的完整版本。
 *
 * [bindingToken] 只含内部 accountId + 凭据代次，不含学号；[businessEpoch] 覆盖清缓存，
 * [snapshotRevision] 覆盖查询/迁移/清除造成的快照替换。三者与可用状态一起变化。
 */
data class CardWidgetRevision(
    val availability: CardBindingAvailability,
    val bindingToken: String?,
    val businessEpoch: Long,
    val snapshotRevision: Long,
)

/** 一次可校验的组件读取；[state] 与 [revision] 必须来自同一次稳定读取。 */
data class CardWidgetRead(
    val state: CardWidgetViewState,
    val revision: CardWidgetRevision,
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
    fun bindingToken(): String? = token(bindingStatus().binding)

    /** 旧版本已排队任务没有 token 时保持兼容，执行时仍会读取当前绑定并再次核对。 */
    fun isBindingTokenCurrent(token: String?): Boolean =
        token == null || token == bindingToken()

    /** 当前发布版本；组件在真正 updateAppWidget 前必须与读取时的版本比较。 */
    fun revisionNow(): CardWidgetRevision = revisionFor(bindingStatus())

    /**
     * 稳定读取组件状态：读取前后 status/业务代次/快照版本必须一致；任何变化重读，
     * 重试仍不稳定时退回“当前绑定 + 空快照”，绝不发布读到的旧值。
     */
    suspend fun readWidgetState(): CardWidgetRead {
        repeat(WIDGET_READ_ATTEMPTS) {
            val status = bindingStatus()
            val epoch = repository.businessEpochNow()
            val read = status.binding?.let { repository.cachedForWidget(it) }
                ?: CachedRead(
                    snapshot = null,
                    valid = true,
                    snapshotRevision = repository.snapshotRevisionNow(),
                )
            if (!read.valid) return@repeat
            val statusAfter = bindingStatus()
            val epochAfter = repository.businessEpochNow()
            val revisionAfter = repository.snapshotRevisionNow()
            if (statusAfter == status &&
                epochAfter == epoch &&
                revisionAfter == read.snapshotRevision
            ) {
                return CardWidgetRead(
                    state = CardWidgetViewState(status, read.snapshot),
                    revision = CardWidgetRevision(
                        availability = status.availability,
                        bindingToken = token(status.binding),
                        businessEpoch = epoch,
                        snapshotRevision = read.snapshotRevision,
                    ),
                )
            }
        }
        return fallbackWidgetRead()
    }

    /**
     * 同步读取（RemoteViews onUpdate/点击）。不迁移、不联网；只做 8 KB 内存/文件
     * 读取，读取后同样复核，失败退回空快照。
     */
    fun readWidgetStateBlocking(): CardWidgetRead {
        val status = bindingStatus()
        val epoch = repository.businessEpochNow()
        val read = status.binding?.let { repository.cachedBlockingForWidget(it) }
            ?: CachedRead(
                snapshot = null,
                valid = true,
                snapshotRevision = repository.snapshotRevisionNow(),
            )
        val statusAfter = bindingStatus()
        val epochAfter = repository.businessEpochNow()
        val revisionAfter = repository.snapshotRevisionNow()
        if (read.valid &&
            statusAfter == status &&
            epochAfter == epoch &&
            revisionAfter == read.snapshotRevision
        ) {
            return CardWidgetRead(
                state = CardWidgetViewState(status, read.snapshot),
                revision = CardWidgetRevision(
                    availability = status.availability,
                    bindingToken = token(status.binding),
                    businessEpoch = epoch,
                    snapshotRevision = read.snapshotRevision,
                ),
            )
        }
        return fallbackWidgetRead()
    }

    fun widgetStateNow(): CardWidgetViewState = readWidgetStateBlocking().state

    private fun fallbackWidgetRead(): CardWidgetRead {
        val status = bindingStatus()
        return CardWidgetRead(
            state = CardWidgetViewState(status, snapshot = null),
            revision = revisionFor(status),
        )
    }

    private fun revisionFor(status: CardBindingStatus): CardWidgetRevision = CardWidgetRevision(
        availability = status.availability,
        bindingToken = token(status.binding),
        businessEpoch = repository.businessEpochNow(),
        snapshotRevision = repository.snapshotRevisionNow(),
    )

    private fun token(binding: CardBalanceBinding?): String? = binding?.let {
        "${it.accountId.value}:${it.credentialGeneration.value}"
    }

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

    private companion object {
        const val WIDGET_READ_ATTEMPTS = 3
    }
}
