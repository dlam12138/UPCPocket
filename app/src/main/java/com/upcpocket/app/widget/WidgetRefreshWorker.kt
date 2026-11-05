package com.upcpocket.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.platform.RefreshInvalidatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 组件刷新的一次性后台任务。
 *
 * 执行时读取当前统一账号的受控学号，不携带、也不接受 Intent/任务参数里的学号；
 * 已绑定但尚无成功快照也可以查询。换号/删号/清缓存/改密后旧任务不能发布结果。
 * 任务本身不重试，避免断网时无限重试；结束一定清除“正在刷新”状态。
 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val kind = WidgetKind.fromKey(inputData.getString(WidgetWork.KEY_KIND))
            ?: return Result.failure()
        val expectedBindingToken = inputData.getString(WidgetWork.KEY_BINDING_TOKEN)
        val state = WidgetStateStore(applicationContext)
        try {
            refresh(kind, state, expectedBindingToken)
        } finally {
            state.clearRefreshing(kind)
            withContext(NonCancellable) {
                runCatching { WidgetUpdates.updateKind(applicationContext, kind) }
            }
        }
        return Result.success()
    }

    private suspend fun refresh(
        kind: WidgetKind,
        state: WidgetStateStore,
        expectedBindingToken: String?,
    ) {
        try {
            var pluginAvailable = true
            withTimeout(NETWORK_BUDGET_MS) {
                when (kind) {
                    WidgetKind.CARD -> {
                        // 冷启动时点击发生在账号 restore 之前，bindingToken 可能还是 null。
                        // 先有界等待恢复；null token 只表示“点击时尚未恢复”，服务内部仍会
                        // 用账号代际校验阻止跨账号发布。
                        withTimeout(ACCOUNT_RESTORE_BUDGET_MS) {
                            AppGraph.account.restore()
                        }
                        val service = AppGraph.cardServiceOrNull
                        if (service == null) {
                            pluginAvailable = false
                        } else if (expectedBindingToken != null &&
                            !service.isBindingTokenCurrent(expectedBindingToken)
                        ) {
                            // 点击后账号已切换/改密：旧任务退出，不能把新账号当成旧目标。
                            return@withTimeout
                        } else {
                            service.refreshCurrent()
                        }
                    }
                    WidgetKind.POWER -> {
                        val service = AppGraph.powerServiceOrNull
                        if (service == null) {
                            pluginAvailable = false
                        } else {
                            service.refreshCurrent()
                        }
                    }
                    WidgetKind.TIMETABLE -> {
                        val service = AppGraph.timetableServiceOrNull
                        if (service == null) {
                            pluginAvailable = false
                        } else {
                            // 冷启动时 Application.onCreate 只异步恢复账号；这里必须有界
                            // 等到 restore 完成，否则会误判“没有目标”。restore 本身是幂等的。
                            withTimeout(ACCOUNT_RESTORE_BUDGET_MS) {
                                AppGraph.account.restore()
                            }
                            service.open()
                            service.refreshToday()
                            val snapshot = service.readWidgetSnapshot()
                            if (!snapshot.hasReliableData) {
                                val message = service.state.value.message
                                    ?: "课表未同步，请打开应用完成登录或同步"
                                state.setError(kind, friendly(Exception(message)))
                            }
                        }
                    }
                }
            }
            if (!pluginAvailable) {
                state.setError(kind, "功能未启用")
                return
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
        const val ACCOUNT_RESTORE_BUDGET_MS = 10_000L
        const val MAX_MESSAGE_LENGTH = 60
    }
}
