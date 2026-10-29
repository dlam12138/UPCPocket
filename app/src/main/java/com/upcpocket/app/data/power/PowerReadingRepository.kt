package com.upcpocket.app.data.power

import com.upcpocket.app.data.RefreshInvalidatedException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 唐岛湾剩余电量的领域仓库：把“页面查询”和“桌面组件刷新”收敛到同一份成功快照。
 *
 * 只有 [ElectricityResult.Success] 且结果确实属于请求的楼号/房间号时才写入；
 * 歧义候选、网络错误、页面结构变化都不会覆盖上一次成功值。
 *
 * 生产环境只保留一个实例（[com.upcpocket.app.core.AppGraph]），页面与组件共用，
 * 避免两个 AtomicFile 同时写 `power-reading-v1.json`。
 */
class PowerReadingRepository(
    private val store: PowerSnapshotStore,
    private val fetch: suspend (String, String) -> ElectricityResult,
) {
    private val mutableSnapshot = MutableStateFlow<PowerSnapshot?>(null)
    val snapshot: StateFlow<PowerSnapshot?> = mutableSnapshot.asStateFlow()

    private val fileLock = Mutex()
    private val refreshLock = Mutex()
    private val generation = AtomicLong(0)
    private val initialized = AtomicBoolean(false)

    suspend fun cached(): PowerSnapshot? = withContext(Dispatchers.IO) {
        mutableSnapshot.value?.let { return@withContext it }
        if (initialized.get()) return@withContext null
        val startGeneration = generation.get()
        val loaded = fileLock.withLock { store.load() }
        if (generation.get() != startGeneration) return@withContext null
        if (initialized.compareAndSet(false, true)) mutableSnapshot.value = loaded
        mutableSnapshot.value
    }

    /** 同步读取，仅供 RemoteViews 渲染缓存；不做网络请求。 */
    fun cachedBlocking(): PowerSnapshot? {
        mutableSnapshot.value?.let { return it }
        if (initialized.get()) return null
        val startGeneration = generation.get()
        val loaded = runCatching { store.load() }.getOrNull()
        if (generation.get() != startGeneration) return null
        if (initialized.compareAndSet(false, true)) mutableSnapshot.value = loaded
        return mutableSnapshot.value
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        generation.incrementAndGet()
        fileLock.withLock { store.clear() }
        mutableSnapshot.value = null
    }

    /** 页面查询：成功后把“最后一次成功目标”切换为本次楼层/房间。 */
    suspend fun query(building: String, room: String): ElectricityResult =
        queryInternal(building, room, requireSameTarget = false)

    /**
     * 组件刷新：只查询当前成功快照里的房间。
     *
     * 没有快照、快照在等待网络时被清除、或目标已被页面切换到别的房间，都返回 null；
     * 歧义结果抛出可展示的错误，绝不把候选或错误当成成功电量保存。
     */
    suspend fun refreshCurrent(): PowerSnapshot? {
        val current = cached() ?: return null
        return when (queryInternal(current.building, current.room, requireSameTarget = true)) {
            is ElectricityResult.Success -> mutableSnapshot.value
            is ElectricityResult.Ambiguous ->
                throw ElectricityException("学校返回多个匹配房间，请先在应用确认")
        }
    }

    private suspend fun queryInternal(
        building: String,
        room: String,
        requireSameTarget: Boolean,
    ): ElectricityResult = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val targetBuilding = building.trim()
            val targetRoom = room.trim()
            if (requireSameTarget && mutableSnapshot.value?.matches(targetBuilding, targetRoom) != true) {
                throw RefreshInvalidatedException()
            }

            val startGeneration = generation.get()
            val result = fetch(targetBuilding, targetRoom)
            if (result !is ElectricityResult.Success) return@withLock result

            val reading = result.reading
            if (!reading.matches(targetBuilding, targetRoom)) {
                throw ElectricityException("查询结果与请求的房间不一致，未保存")
            }
            val value = PowerSnapshot(
                building = reading.building,
                room = reading.room,
                kwh = reading.kwh.toPlainString(),
                fetchedAt = System.currentTimeMillis(),
            )
            if (!PowerSnapshot.valid(value.building, value.room, value.kwh, value.fetchedAt)) {
                throw ElectricityException("学校返回的剩余电量格式不可识别，未保存")
            }

            val committed = fileLock.withLock {
                when {
                    generation.get() != startGeneration -> false
                    requireSameTarget &&
                        mutableSnapshot.value?.matches(targetBuilding, targetRoom) != true -> false
                    else -> {
                        try {
                            store.save(value)
                        } catch (_: Exception) {
                            throw ElectricityException("已读取电量，但本机保存失败，请重试")
                        }
                        mutableSnapshot.value = value
                        true
                    }
                }
            }
            if (!committed) throw RefreshInvalidatedException()
            result
        }
    }
}
