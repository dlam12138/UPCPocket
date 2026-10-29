package com.upcpocket.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.RefreshInvalidatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 组件刷新的一次性后台任务。
 *
 * 只查当前成功快照的目标（不读草稿输入）；成功才写入共享快照，失败保留旧值并记录
 * 一条短提示。任务本身不重试，避免断网时无限重试；结束一定清除“正在刷新”状态。
 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val kind = WidgetKind.fromKey(inputData.getString(WidgetWork.KEY_KIND))
            ?: return Result.failure()
        val state = WidgetStateStore(applicationContext)
        try {
            refresh(kind, state)
        } finally {
            state.clearRefreshing(kind)
            withContext(NonCancellable) {
                runCatching { WidgetUpdates.updateKind(applicationContext, kind) }
            }
        }
        return Result.success()
    }

    private suspend fun refresh(kind: WidgetKind, state: WidgetStateStore) {
        try {
            withTimeout(NETWORK_BUDGET_MS) {
                when (kind) {
                    WidgetKind.CARD -> AppGraph.directCardBalanceRepository.refreshCurrent()
                    WidgetKind.POWER -> AppGraph.powerReadingRepository.refreshCurrent()
                }
            }
            // 没有目标或目标已被清除：保持“待设置”，不编造失败。
            state.setError(kind, null)
        } catch (_: RefreshInvalidatedException) {
            // 用户清除/切换了目标；静默丢弃，不能复活已删除的数据。
            state.setError(kind, null)
        } catch (_: TimeoutCancellationException) {
            state.setError(kind, "刷新超时，请稍后重试")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state.setError(kind, friendly(failure))
        }
    }

    private fun friendly(failure: Throwable): String {
        val message = failure.message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        return when {
            message.isEmpty() -> "刷新失败，请稍后重试"
            message.length > MAX_MESSAGE_LENGTH -> message.take(MAX_MESSAGE_LENGTH)
            else -> message
        }
    }

    private companion object {
        /** 现有网络调用自身是 10s 连接 + 20s 读取，这里再兜一层总预算。 */
        const val NETWORK_BUDGET_MS = 45_000L
        const val MAX_MESSAGE_LENGTH = 60
    }
}
