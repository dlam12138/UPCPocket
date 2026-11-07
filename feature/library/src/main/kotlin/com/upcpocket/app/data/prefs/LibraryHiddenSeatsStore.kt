package com.upcpocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.upcpocket.app.data.library.LibraryHiddenSeat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.libraryHiddenSeatsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "library_hidden_seats")

/**
 * 本机个人隐藏座位的独立偏好层。
 *
 * 这是设备本地偏好，不是学校账号数据：不上传、不影响他人，退出登录/学校换号
 * 不应删除它，恢复/清理入口由页面明确提供。身份至少包含
 * system=libseat、buildingId、roomId、稳定 seatId；不同房间同编号不会串联。
 * 只额外保存座号展示快照，学校更新编号时以当前官方布局名称优先。
 *
 * 读写失败会抛出，由 ViewModel 转成「保存失败/读取失败」短提示，不假装已持久化。
 */
interface LibraryHiddenSeatsStore {
    /** 当前房间全部隐藏项（含座号展示快照）；读取失败时抛出。 */
    suspend fun entries(buildingId: String, roomId: String): List<LibraryHiddenSeat>

    /** 当前房间隐藏的稳定 seatId 集合。 */
    suspend fun hiddenIds(buildingId: String, roomId: String): Set<String>

    /** 隐藏一个座位；返回是否真的写入成功。 */
    suspend fun hide(entry: LibraryHiddenSeat): Boolean

    /** 恢复一个座位；返回是否真的写入成功。 */
    suspend fun restore(buildingId: String, roomId: String, seatId: String): Boolean

    /** 恢复当前房间全部隐藏项；返回是否真的写入成功。 */
    suspend fun restoreAll(buildingId: String, roomId: String): Boolean

    /** 当前房间隐藏数量，用于「隐藏的座位（N）」。 */
    suspend fun count(buildingId: String, roomId: String): Int
}

/** 不接入持久层的空实现，供独立合成预览使用。 */
object NoOpLibraryHiddenSeatsStore : LibraryHiddenSeatsStore {
    override suspend fun entries(buildingId: String, roomId: String): List<LibraryHiddenSeat> = emptyList()
    override suspend fun hiddenIds(buildingId: String, roomId: String): Set<String> = emptySet()
    override suspend fun hide(entry: LibraryHiddenSeat): Boolean = true
    override suspend fun restore(buildingId: String, roomId: String, seatId: String): Boolean = true
    override suspend fun restoreAll(buildingId: String, roomId: String): Boolean = true
    override suspend fun count(buildingId: String, roomId: String): Int = 0
}

@Serializable
private data class StoredHiddenSeat(
    val system: String,
    val buildingId: String,
    val roomId: String,
    val seatId: String,
    val nameSnapshot: String,
)

private fun LibraryHiddenSeat.toStored() = StoredHiddenSeat(
    system = system,
    buildingId = buildingId,
    roomId = roomId,
    seatId = seatId,
    nameSnapshot = nameSnapshot,
)

private fun StoredHiddenSeat.toModel() = LibraryHiddenSeat(
    system = system,
    buildingId = buildingId,
    roomId = roomId,
    seatId = seatId,
    nameSnapshot = nameSnapshot,
)

private fun composite(entry: StoredHiddenSeat) =
    "${entry.system}|${entry.buildingId}|${entry.roomId}|${entry.seatId}"

private fun compositePrefix(system: String, buildingId: String, roomId: String) =
    "$system|$buildingId|$roomId|"

/**
 * 用单个 DataStore 字符串键保存一份 JSON 列表；所有修改都在 `edit` 里读-改-写，
 * 每项身份带完整命名空间。DataStore 保证原子提交与跨重启持久化。
 */
class DataStoreLibraryHiddenSeatsStore internal constructor(
    private val store: DataStore<Preferences>,
) : LibraryHiddenSeatsStore {

    constructor(context: Context) : this(context.applicationContext.libraryHiddenSeatsDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun loadAll(): MutableMap<String, StoredHiddenSeat> {
        val raw = store.data.first()[KEY_HIDDEN_SEATS] ?: return linkedMapOf()
        val list = runCatching { json.decodeFromString<List<StoredHiddenSeat>>(raw) }
            .getOrElse { throw IllegalStateException("隐藏座位读取失败", it) }
        return list.associateBy { composite(it) }.toMutableMap()
    }

    private suspend fun writeAll(all: Map<String, StoredHiddenSeat>): Boolean {
        val raw = json.encodeToString(all.values.toList())
        return try {
            store.edit { prefs -> prefs[KEY_HIDDEN_SEATS] = raw }
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun entries(buildingId: String, roomId: String): List<LibraryHiddenSeat> {
        val prefix = compositePrefix(LibraryHiddenSeat.SYSTEM_LIBSEAT, buildingId, roomId)
        return loadAll().values
            .filter { composite(it).startsWith(prefix) }
            .map { it.toModel() }
            .sortedBy { it.nameSnapshot }
    }

    override suspend fun hiddenIds(buildingId: String, roomId: String): Set<String> =
        entries(buildingId, roomId).mapTo(linkedSetOf()) { it.seatId }

    override suspend fun hide(entry: LibraryHiddenSeat): Boolean {
        require(entry.system == LibraryHiddenSeat.SYSTEM_LIBSEAT) { "未知来源" }
        val all = loadAll()
        all[composite(entry.toStored())] = entry.toStored()
        return writeAll(all)
    }

    override suspend fun restore(buildingId: String, roomId: String, seatId: String): Boolean {
        val all = loadAll()
        val key = "${LibraryHiddenSeat.SYSTEM_LIBSEAT}|$buildingId|$roomId|$seatId"
        if (all.remove(key) == null) return true
        return writeAll(all)
    }

    override suspend fun restoreAll(buildingId: String, roomId: String): Boolean {
        val prefix = compositePrefix(LibraryHiddenSeat.SYSTEM_LIBSEAT, buildingId, roomId)
        val all = loadAll()
        val toRemove = all.keys.filter { it.startsWith(prefix) }
        if (toRemove.isEmpty()) return true
        toRemove.forEach { all.remove(it) }
        return writeAll(all)
    }

    override suspend fun count(buildingId: String, roomId: String): Int =
        entries(buildingId, roomId).size

    private companion object {
        val KEY_HIDDEN_SEATS = stringPreferencesKey("hidden_seats_v1")
    }
}
