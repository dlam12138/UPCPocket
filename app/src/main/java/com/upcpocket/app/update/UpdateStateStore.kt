package com.upcpocket.app.update

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.json.JSONObject

/**
 * 更新偏好的私有落盘状态。
 *
 * - highestVerifiedVersionCode：仅由已验签且字段合法的清单提升，防止镜像重放旧版本；
 * - lastAutoCheckEpochDay：前台自动检查每天最多一次的日期门闩；
 * - lastSuccessfulSourceId：只影响线路性能排序，不提升任何来源的信任等级；
 * - autoCheckEnabled / dismissedVersionCode：用户明确选择。
 *
 * state.json 位于 noBackupFilesDir/updates，不参与系统备份；读写失败一律回退默认值，
 * 不能因为清理/磁盘异常阻断应用启动。
 */
data class UpdateState(
    val highestVerifiedVersionCode: Int = 0,
    val lastAutoCheckEpochDay: Long = 0L,
    val lastSuccessfulSourceId: String? = null,
    val autoCheckEnabled: Boolean = true,
    val dismissedVersionCode: Int = 0,
)

class UpdateStateStore(private val file: File) {

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun read(): UpdateState {
        if (!file.isFile) return UpdateState()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            UpdateState(
                highestVerifiedVersionCode = json.optInt("highestVerifiedVersionCode", 0)
                    .coerceAtLeast(0),
                lastAutoCheckEpochDay = json.optLong("lastAutoCheckEpochDay", 0L)
                    .coerceAtLeast(0L),
                lastSuccessfulSourceId = (json.opt("lastSuccessfulSourceId") as? String)
                    ?.takeIf { it.matches(Regex("^[a-z0-9.-]{1,40}$")) },
                autoCheckEnabled = json.optBoolean("autoCheckEnabled", true),
                dismissedVersionCode = json.optInt("dismissedVersionCode", 0)
                    .coerceAtLeast(0),
            )
        }.getOrDefault(UpdateState())
    }

    @Synchronized
    fun update(transform: (UpdateState) -> UpdateState): UpdateState {
        val next = transform(read())
        write(next)
        return next
    }

    @Synchronized
    fun write(state: UpdateState) {
        file.parentFile?.mkdirs()
        val json = JSONObject()
            .put("stateVersion", 1)
            .put("highestVerifiedVersionCode", state.highestVerifiedVersionCode.coerceAtLeast(0))
            .put("lastAutoCheckEpochDay", state.lastAutoCheckEpochDay.coerceAtLeast(0L))
            .put("lastSuccessfulSourceId", state.lastSuccessfulSourceId ?: JSONObject.NULL)
            .put("autoCheckEnabled", state.autoCheckEnabled)
            .put("dismissedVersionCode", state.dismissedVersionCode.coerceAtLeast(0))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val temp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            Files.write(
                temp.toPath(),
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            runCatching {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }.onFailure {
            temp.delete()
        }
    }
}
