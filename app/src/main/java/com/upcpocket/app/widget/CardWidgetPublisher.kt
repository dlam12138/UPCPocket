package com.upcpocket.app.widget

import com.upcpocket.app.card.CardWidgetRead
import com.upcpocket.app.card.CardWidgetRevision
import com.upcpocket.app.card.CardWidgetViewState
import kotlinx.coroutines.CancellationException

/** RemoteViews 的最终写入点；测试可注入内存 sink 复现发布时序。 */
fun interface CardWidgetPublishSink {
    fun publish(state: CardWidgetViewState)
}

/**
 * 校园卡组件的唯一发布门闩。
 *
 * - 每次发布先取得递增 ticket，再在锁外读取状态；读取可以挂起/读文件。
 * - 真正 updateAppWidget 前在短临界区内同时核对 ticket 与完整 revision
 *   （可用状态 + 绑定/账号代次 + 业务代次 + 快照版本）。
 * - 更新状态会开启更大的 ticket；旧读取即使最后返回，也会因 ticket/revision
 *   不匹配被丢弃。若旧发布已先写入，新发布会在同一把锁之后覆盖它。
 *
 * 读缓存与渲染都不在锁内；锁内只做版本比较和一次 sink 写入，供主线程
 * Provider 同步路径使用，不等待协程 Mutex 或大文件操作。
 */
class CardWidgetPublisher(
    private val readSuspending: suspend () -> CardWidgetRead,
    private val readBlocking: () -> CardWidgetRead,
    private val currentRevision: () -> CardWidgetRevision,
    private val sink: CardWidgetPublishSink,
) {
    private val lock = Any()
    private var ticket = 0L

    suspend fun publishLatest(): Boolean {
        val mine = nextTicket()
        val read = try {
            readSuspending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return commit(mine, read)
    }

    fun publishLatestNow(): Boolean {
        val mine = nextTicket()
        val read = runCatching { readBlocking() }.getOrNull() ?: return false
        return commit(mine, read)
    }

    private fun nextTicket(): Long = synchronized(lock) { ++ticket }

    private fun commit(mine: Long, read: CardWidgetRead): Boolean = synchronized(lock) {
        if (mine != ticket) return false
        if (currentRevision() != read.revision) return false
        sink.publish(read.state)
        true
    }
}
