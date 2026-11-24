package com.upcpocket.app.todo.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Room 之上的唯一任务读写入口。
 *
 * 列表 Flow 是事实来源；通知、Alarm 和 UI 都从这里读取，不各自维护副本。
 * 任务与排序的公开快照在同一次 Room 读事务中产生，避免跨表组合出现半新半旧数据。
 * 同模块测试可注入无数据库的 DAO 替身，此时写入按调用顺序直接生效。
 */
class TodoRepository private constructor(
    private val database: TodoDatabase?,
    private val dao: TodoTaskDao,
    private val orderDao: TodoOrderDao?,
) {
    constructor(database: TodoDatabase) : this(
        database = database,
        dao = database.taskDao(),
        orderDao = database.orderDao(),
    )

    internal constructor(dao: TodoTaskDao, orderDao: TodoOrderDao? = null) : this(
        database = null,
        dao = dao,
        orderDao = orderDao,
    )

    val tasks: Flow<List<TodoTask>> = dao.observeAll().map { entities ->
        entities.map(TodoTaskEntity::toDomain)
    }

    /**
     * 任务与排序的唯一一致快照。
     *
     * Room 失效通知在事务提交后触发，随后在同一读事务内重新读取三张表；
     * 旧 Store/无排序表的替身映射为默认日期顺序。
     */
    val snapshot: Flow<TodoSnapshot> = run {
        val orders = orderDao
        val db = database
        when {
            orders != null && db != null -> combine(
                dao.observeAll(),
                orders.observeOrders(),
                orders.observeState(),
            ) { _, _, _ -> Unit }
                .map { readSnapshot() }
                .distinctUntilChanged()

            orders != null -> combine(
                dao.observeAll(),
                orders.observeOrders(),
                orders.observeState(),
            ) { tasks, taskOrders, state ->
                toSnapshot(tasks, taskOrders, state)
            }.distinctUntilChanged()

            else -> tasks.map { TodoSnapshot(tasks = it) }
        }
    }

    suspend fun all(): List<TodoTask> = dao.all().map(TodoTaskEntity::toDomain)

    suspend fun byId(id: String): TodoTask? = dao.byId(id)?.toDomain()

    suspend fun importanceOf(id: String): TodoImportance =
        TodoImportance.fromStorage(orderDao?.orderFor(id)?.importance)

    suspend fun insert(task: TodoTask) = dao.upsert(task.toEntity())

    /** 新建任务与重要程度同事务；手动排序已初始化时在同一事务内追加全局位次。 */
    suspend fun insertWithImportance(
        task: TodoTask,
        importance: TodoImportance,
    ): TodoTask = inTransaction {
        dao.upsert(task.toEntity())
        orderDao?.let { orders ->
            val state = orders.state()
            val next = state?.nextManualRank ?: 0
            val rank = if (state != null && next > 0) {
                orders.upsertState(state.copy(nextManualRank = next + 1))
                next
            } else {
                null
            }
            orders.upsertOrder(
                TodoTaskOrderEntity(
                    taskId = task.id,
                    importance = importance.name,
                    manualRank = rank,
                ),
            )
        }
        task
    }

    suspend fun updateContent(
        id: String,
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        firedAt: Long?,
        now: Long,
    ): TodoTask? {
        if (dao.updateContent(id, title, reminderAt, firedAt, dueAt, now) == 0) return null
        return byId(id)
    }

    /** 内容与重要程度同事务保存；旧调用仍可不带重要程度，保留已有排序记录。 */
    suspend fun updateContentWithImportance(
        id: String,
        title: String,
        reminderAt: Long?,
        dueAt: Long?,
        firedAt: Long?,
        now: Long,
        importance: TodoImportance,
    ): TodoTask? = inTransaction {
        if (dao.updateContent(id, title, reminderAt, firedAt, dueAt, now) == 0) {
            return@inTransaction null
        }
        val orders = orderDao
        if (orders != null) {
            orders.upsertOrder(
                TodoTaskOrderEntity(
                    taskId = id,
                    importance = importance.name,
                    manualRank = orders.orderFor(id)?.manualRank,
                ),
            )
        }
        dao.byId(id)?.toDomain()
    }

    /** Legacy edit path: update reminder content without clearing a deadline it cannot express. */
    suspend fun updateContent(
        id: String,
        title: String,
        reminderAt: Long?,
        firedAt: Long?,
        now: Long,
    ): TodoTask? {
        val current = byId(id) ?: return null
        return updateContent(
            id = id,
            title = title,
            reminderAt = reminderAt,
            dueAt = current.dueAt,
            firedAt = firedAt,
            now = now,
        )
    }

    suspend fun setCompleted(id: String, completed: Boolean, now: Long): TodoTask? {
        val completedAt = now.takeIf { completed }
        if (dao.setCompleted(id, completed, completedAt, now) == 0) return null
        return byId(id)
    }

    suspend fun setCompletedIfRevision(
        id: String,
        revision: Int,
        completed: Boolean,
        now: Long,
    ): TodoTask? {
        val completedAt = now.takeIf { completed }
        if (dao.setCompletedIfRevision(id, revision, completed, completedAt, now) == 0) {
            return null
        }
        return byId(id)
    }

    suspend fun completeIfRevision(id: String, revision: Int, now: Long): TodoTask? {
        if (dao.completeIfRevision(id, revision, now) == 0) return null
        return byId(id)
    }

    suspend fun snoozeIfRevision(
        id: String,
        revision: Int,
        until: Long,
        now: Long,
    ): TodoTask? {
        if (dao.snoozeIfRevision(id, revision, until, now) == 0) return null
        return byId(id)
    }

    suspend fun markFiredIfRevision(
        id: String,
        revision: Int,
        firedAt: Long,
        now: Long,
    ): TodoTask? {
        if (dao.markFiredIfRevision(id, revision, firedAt, now) == 0) return null
        return byId(id)
    }

    suspend fun clearFiredIfRevision(id: String, revision: Int, now: Long): TodoTask? {
        if (dao.clearFiredIfRevision(id, revision, now) == 0) return null
        return byId(id)
    }

    /** 只改重要程度，不触碰任务自身字段、修订号或提醒状态。 */
    suspend fun setImportance(id: String, importance: TodoImportance): Boolean =
        inTransaction {
            val orders = orderDao ?: return@inTransaction false
            if (dao.byId(id) == null) return@inTransaction false
            orders.upsertOrder(
                TodoTaskOrderEntity(
                    taskId = id,
                    importance = importance.name,
                    manualRank = orders.orderFor(id)?.manualRank,
                ),
            )
            true
        }

    /**
     * 切换排序模式。首次进入手动模式时按完整列表日期顺序分配全局位次；
     * 已初始化时只补有缺口的任务，绝不重置用户已有手排。
     */
    suspend fun setSortMode(mode: TodoSortMode): Boolean = inTransaction {
        val orders = orderDao ?: return@inTransaction false
        val state = orders.state()
        if (mode != TodoSortMode.MANUAL && state?.mode == mode.name) {
            return@inTransaction true
        }

        var next = state?.nextManualRank ?: 0
        if (mode == TodoSortMode.MANUAL) {
            val existing = orders.orders().associateBy { it.taskId }
            val tasks = dao.all().map(TodoTaskEntity::toDomain)
            val dateOrdered = TodoOrdering.incomplete(tasks, 0) + TodoOrdering.completed(tasks)
            val unranked = dateOrdered.filter { existing[it.id]?.manualRank == null }
            if (state?.mode == mode.name && unranked.isEmpty()) {
                return@inTransaction true
            }
            val maxRank = existing.values.mapNotNull { it.manualRank }.maxOrNull() ?: 0
            var rank = maxOf(next, maxRank + 1, 1)
            unranked.forEach { task ->
                orders.upsertOrder(
                    TodoTaskOrderEntity(
                        taskId = task.id,
                        importance = existing[task.id]?.importance
                            ?: TodoImportance.NORMAL.name,
                        manualRank = rank,
                    ),
                )
                rank++
            }
            // 空列表也记为已初始化，后续新增任务从此位次继续追加。
            next = maxOf(rank, 1)
        }
        orders.upsertState(
            TodoOrderStateEntity(
                id = 0,
                mode = mode.name,
                nextManualRank = next,
            ),
        )
        true
    }

    /**
     * 手动模式内交换用户看到的相邻两项。
     *
     * 事务内按最新数据复核手动模式、方向、两人同组且仍实际相邻；点按后若原邻居
     * 已被删除、完成或反向移动，则拒绝而不是改换另一项。其他任务位次保持不变。
     */
    suspend fun moveTask(
        id: String,
        direction: TodoMoveDirection,
        expectedNeighborId: String,
    ): Boolean = inTransaction {
        val orders = orderDao ?: return@inTransaction false
        if (orders.state()?.mode != TodoSortMode.MANUAL.name) return@inTransaction false
        val target = dao.byId(id) ?: return@inTransaction false
        val neighbor = dao.byId(expectedNeighborId) ?: return@inTransaction false
        if (neighbor.completed != target.completed) return@inTransaction false
        val byTask = orders.orders().associateBy { it.taskId }
        val targetRank = byTask[id]?.manualRank ?: return@inTransaction false
        val neighborRank = byTask[expectedNeighborId]?.manualRank
            ?: return@inTransaction false
        if (targetRank == neighborRank) return@inTransaction false
        val group = dao.all()
            .asSequence()
            .filter { it.completed == target.completed }
            .mapNotNull { entity ->
                byTask[entity.id]?.manualRank?.let { rank -> entity.id to rank }
            }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            .toList()
        val index = group.indexOfFirst { it.first == id }
        val neighborIndex = group.indexOfFirst { it.first == expectedNeighborId }
        if (index < 0 || neighborIndex < 0) return@inTransaction false
        val expectedIndex = when (direction) {
            TodoMoveDirection.UP -> index - 1
            TodoMoveDirection.DOWN -> index + 1
        }
        if (neighborIndex != expectedIndex) return@inTransaction false

        orders.upsertOrder(byTask.getValue(id).copy(manualRank = neighborRank))
        orders.upsertOrder(
            byTask.getValue(expectedNeighborId).copy(manualRank = targetRank),
        )
        true
    }

    /** 删除任务与排序记录同事务，并把重要程度/位次交给撤销路径。 */
    suspend fun deleteWithOrder(id: String): TodoDeletedTask? = inTransaction {
        val task = dao.byId(id)?.toDomain() ?: return@inTransaction null
        val order = orderDao?.orderFor(id)
        if (dao.delete(id) == 0) return@inTransaction null
        orderDao?.deleteOrder(id)
        TodoDeletedTask(
            task = task,
            importance = TodoImportance.fromStorage(order?.importance),
            manualRank = order?.manualRank,
        )
    }

    suspend fun delete(id: String): TodoTask? = deleteWithOrder(id)?.task

    /** 撤销删除与排序记录同事务；任务仍存在时拒绝，避免迟到恢复覆盖新状态。 */
    suspend fun restoreWithOrder(deleted: TodoDeletedTask, now: Long): TodoTask? =
        inTransaction {
            if (dao.byId(deleted.task.id) != null) return@inTransaction null
            val restored = deleted.task.copy(
                revision = deleted.task.revision + 1,
                updatedAt = now,
            )
            dao.upsert(restored.toEntity())
            orderDao?.let { orders ->
                val state = orders.state()
                val savedRank = deleted.manualRank
                val rankOccupied = savedRank != null &&
                    orders.orders().any {
                        it.taskId != restored.id && it.manualRank == savedRank
                    }
                // 初始化后恢复且原快照无位次（或位次已被占用）时追加到全局末尾。
                val rank = when {
                    savedRank != null && !rankOccupied -> savedRank
                    state != null && state.nextManualRank > 0 -> state.nextManualRank
                    else -> null
                }
                orders.upsertOrder(
                    TodoTaskOrderEntity(
                        taskId = restored.id,
                        importance = deleted.importance.name,
                        manualRank = rank,
                    ),
                )
                if (rank != null && (state == null || state.nextManualRank <= rank)) {
                    orders.upsertState(
                        TodoOrderStateEntity(
                            id = 0,
                            mode = state?.mode ?: TodoSortMode.DATE.name,
                            nextManualRank = rank + 1,
                        ),
                    )
                }
            }
            restored
        }

    private suspend fun readSnapshot(): TodoSnapshot {
        val db = database
        val orders = orderDao
        if (db == null || orders == null) return TodoSnapshot(tasks = all())
        return db.withTransaction {
            toSnapshot(dao.all(), orders.orders(), orders.state())
        }
    }

    private fun toSnapshot(
        tasks: List<TodoTaskEntity>,
        taskOrders: List<TodoTaskOrderEntity>,
        state: TodoOrderStateEntity?,
    ): TodoSnapshot = TodoSnapshot(
        tasks = tasks.map(TodoTaskEntity::toDomain),
        ordering = TodoOrderingSnapshot(
            mode = TodoSortMode.fromStorage(state?.mode),
            orders = taskOrders.associate { entity ->
                val order = entity.toDomain()
                order.taskId to order
            },
        ),
    )

    private suspend fun <T> inTransaction(block: suspend () -> T): T {
        val db = database ?: return block()
        return db.withTransaction { block() }
    }
}
