package com.upcpocket.app.power

import com.upcpocket.app.data.power.ElectricityResult
import com.upcpocket.app.data.power.PowerReadingRepository
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.data.power.TangdaowanElectricityService
import kotlinx.coroutines.flow.Flow

/**
 * 唐岛湾电量插件对页面与组件暴露的业务入口。
 *
 * 页面不再直接持有 Repository 或组件刷新器；成功查询与清除由插件统一回调宿主。
 */
class PowerService(
    repositoryProvider: () -> PowerReadingRepository,
    private val electricity: TangdaowanElectricityService,
    private val input: PowerInputStore,
    private val onSnapshotChanged: () -> Unit,
) {
    private val repository: PowerReadingRepository by lazy(repositoryProvider)

    val snapshot: Flow<PowerSnapshot?> get() = repository.snapshot
    val savedInput: Flow<PowerInput> = input.input

    suspend fun cached(): PowerSnapshot? = repository.cached()

    fun cachedBlocking(): PowerSnapshot? = repository.cachedBlocking()

    suspend fun buildings(): List<String> = electricity.buildings()

    suspend fun query(building: String, room: String): ElectricityResult {
        val result = repository.query(building, room)
        if (result is ElectricityResult.Success) onSnapshotChanged()
        return result
    }

    /** 组件刷新只针对最后一次成功快照的楼号/房间。 */
    suspend fun refreshCurrent(): PowerSnapshot? = repository.refreshCurrent()

    suspend fun clear() {
        repository.clear()
        input.clear()
        onSnapshotChanged()
    }

    suspend fun saveInput(building: String, room: String) = input.save(building, room)
}
