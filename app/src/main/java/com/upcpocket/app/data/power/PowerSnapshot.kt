package com.upcpocket.app.data.power

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 唐岛湾剩余电量的“最后一次成功查询”快照。
 *
 * 只保存桌面组件和离线查看真正需要的最小信息：楼号、房间号、剩余电量与成功时间。
 * 不保存原始 HTML、表单会话或任何个人账号；单位固定为“度”，由界面渲染，不写进数值。
 *
 * 校验：
 *  - 楼号/房间号非空且有长度上限；
 *  - 电量是有限十进制字符串，不接受 NaN/Infinity/科学计数法；
 *  - 时间必须为正。
 */
@Serializable
data class PowerSnapshot(
    val building: String,
    val room: String,
    val kwh: String,
    val fetchedAt: Long,
) {
    fun matches(building: String, room: String): Boolean =
        this.building == building.trim() && this.room == room.trim()

    companion object {
        const val MAX_TARGET_LENGTH = 64
        const val MAX_KWH_LENGTH = 32
        private val KWH = Regex("-?[0-9]{1,16}(?:\\.[0-9]{1,8})?")

        fun valid(building: String, room: String, kwh: String, fetchedAt: Long): Boolean =
            building.isNotBlank() && building.length <= MAX_TARGET_LENGTH &&
                room.isNotBlank() && room.length <= MAX_TARGET_LENGTH &&
                kwh.length <= MAX_KWH_LENGTH && KWH.matches(kwh) &&
                fetchedAt > 0
    }
}

/** 应用私有、不参与备份的快照文件；损坏时返回 null，不崩溃。 */
class PowerSnapshotStore(file: File) {
    private val atomic = AtomicFile(file)

    fun load(): PowerSnapshot? = runCatching {
        val text = atomic.openRead().use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= 4096)
            bytes.toString(Charsets.UTF_8)
        }
        Json.decodeFromString<PowerSnapshot>(text).also {
            require(
                PowerSnapshot.valid(
                    building = it.building,
                    room = it.room,
                    kwh = it.kwh,
                    fetchedAt = it.fetchedAt,
                ),
            )
        }
    }.getOrNull()

    fun save(value: PowerSnapshot) {
        require(
            PowerSnapshot.valid(
                building = value.building,
                room = value.room,
                kwh = value.kwh,
                fetchedAt = value.fetchedAt,
            ),
        )
        val stream = atomic.startWrite()
        try {
            stream.write(Json.encodeToString(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun clear() = atomic.delete()
}
