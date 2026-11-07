package com.upcpocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.upcpocket.app.data.library.LibraryHiddenSeat
import kotlinx.coroutines.CancellationException
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
 * 所有写操作都在 DataStore 的单个原子 `edit` 里「解码当前值 → 修改 → 编码」，
 * 不读取 edit 外的旧快照再整体覆盖，因此并发 hide/hide、hide/restore 不会互相丢更新。
 * `edit` 在冲突时会用新的当前值重跑变换（transform 必须是纯函数）。
 *
 * 写入/解码失败返回 false，由 ViewModel 转成真实短提示，不假装已持久化；
 * 但 CancellationException 必须原样抛出，不能吞掉协程取消。
 */
interface LibraryHiddenSeatsStore {
    /** 当前房间全部隐藏项（含座号展示快照）；读取失败时抛出。 */
    suspend fun entries(buildingId: String, roomId: String): List<LibraryHiddenSeat>

    /** 当前房间隐藏的稳定 seatId 集合（与 entries 同一次读取派生，避免两份快照不一致）。 */
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

class DataStoreLibraryHiddenSeatsStore internal constructor(
    private val store: DataStore<Preferences>,
) : LibraryHiddenSeatsStore {

    constructor(context: Context) : this(context.applicationContext.libraryHiddenSeatsDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    /** 在原子变换内解码；损坏的 JSON 当作读取失败抛出，由 edit 回滚（不写入）。 */
    private fun decode(prefs: Preferences): MutableMap<String, StoredHiddenSeat> {
        val raw = prefs[KEY_HIDDEN_SEATS] ?: return linkedMapOf()
        val list = try {
            json.decodeFromString<List<StoredHiddenSeat>>(raw)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException("隐藏座位读取失败", e)
        }
        return list.associateBy { composite(it) }.toMutableMap()
    }

    private fun encode(all: Map<String, StoredHiddenSeat>): String =
        json.encodeToString(all.values.toList())

    /**
     * 单个原子写变换。`body` 只做纯内存修改；任何解码/编码/写入异常返回 false，
     * 取消异常原样抛出。edit 会串行化并发写，冲突时用新当前值重跑 body。
     */
    private suspend fun atomicEdit(
        body: (MutableMap<String, StoredHiddenSeat>) -> Unit,
    ): Boolean = try {
        store.edit { prefs ->
            val all = decode(prefs)
            body(all)
            prefs[KEY_HIDDEN_SEATS] = encode(all)
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override suspend fun entries(buildingId: String, roomId: String): List<LibraryHiddenSeat> {
        // 读取失败（IO 损坏/解码异常）抛出，由 ViewModel 显示「读取失败」而非假空列表。
        val prefs = try {
            store.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("隐藏座位读取失败", e)
        }
        val prefix = compositePrefix(LibraryHiddenSeat.SYSTEM_LIBSEAT, buildingId, roomId)
        return decode(prefs).values
            .filter { composite(it).startsWith(prefix) }
            .map { it.toModel() }
            .sortedBy { it.nameSnapshot }
    }

    override suspend fun hiddenIds(buildingId: String, roomId: String): Set<String> =
        entries(buildingId, roomId).mapTo(linkedSetOf()) { it.seatId }

    override suspend fun hide(entry: LibraryHiddenSeat): Boolean {
        require(entry.system == LibraryHiddenSeat.SYSTEM_LIBSEAT) { "未知来源" }
        val stored = entry.toStored()
        return atomicEdit { all -> all[composite(stored)] = stored }
    }

    override suspend fun restore(buildingId: String, roomId: String, seatId: String): Boolean =
        atomicEdit { all ->
            all.remove("${LibraryHiddenSeat.SYSTEM_LIBSEAT}|$buildingId|$roomId|$seatId")
        }

    override suspend fun restoreAll(buildingId: String, roomId: String): Boolean {
        val prefix = compositePrefix(LibraryHiddenSeat.SYSTEM_LIBSEAT, buildingId, roomId)
        return atomicEdit { all ->
            val toRemove = all.keys.filter { it.startsWith(prefix) }
            toRemove.forEach { all.remove(it) }
        }
    }

    override suspend fun count(buildingId: String, roomId: String): Int =
        entries(buildingId, roomId).size

    private companion object {
        val KEY_HIDDEN_SEATS = stringPreferencesKey("hidden_seats_v1")
    }
}
