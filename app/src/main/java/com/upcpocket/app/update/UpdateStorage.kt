package com.upcpocket.app.update

import android.annotation.SuppressLint
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 更新 APK 专用目录。位于应用私有 cache/updates：
 * - 下载先写 .part，完整大小/哈希/包信息验证后才重命名为最终文件；
 * - 启动时清掉中断残留，最终文件 24 小时后清理，最多保留 2 个；
 * - 不申请外部存储权限，FileProvider 也只暴露这一个子目录。
 */
class UpdateStorage(
    private val root: File,
    private val maxCompletedFiles: Int = 2,
    private val maxTotalBytes: Long = 150L * 1024L * 1024L,
    private val maxAgeMs: Long = 24L * 60L * 60L * 1000L,
    private val freeSpaceReserveBytes: Long = 10L * 1024L * 1024L,
) {
    init {
        root.mkdirs()
    }

    @Synchronized
    fun cleanup(nowMillis: Long = System.currentTimeMillis()) {
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part")) {
                file.delete()
            }
        }
        val completed = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        var kept = 0
        var totalBytes = 0L
        completed.forEach { file ->
            val tooOld = nowMillis - file.lastModified() > maxAgeMs
            val overCount = kept >= maxCompletedFiles
            val overSize = totalBytes + file.length() > maxTotalBytes
            if (tooOld || overCount || overSize) {
                file.delete()
            } else {
                kept += 1
                totalBytes += file.length()
            }
        }
    }

    @Synchronized
    fun partFile(versionCode: Int): File {
        require(versionCode > 0)
        root.mkdirs()
        return File(root, "UPC-Pocket-$versionCode.apk.part")
    }

    @Synchronized
    fun finalFile(versionCode: Int): File {
        require(versionCode > 0)
        root.mkdirs()
        return File(root, "UPC-Pocket-$versionCode.apk")
    }

    @Synchronized
    fun preparePart(versionCode: Int): File {
        val part = partFile(versionCode)
        part.delete()
        return part
    }

    @Synchronized
    fun publish(part: File, versionCode: Int): File {
        require(isInsideRoot(part)) { "part file outside update directory" }
        val destination = finalFile(versionCode)
        destination.delete()
        runCatching {
            Files.move(
                part.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(
                part.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return destination
    }

    @SuppressLint("UsableSpace")
    fun hasSpace(requiredBytes: Long): Boolean {
        root.mkdirs()
        // 这里只做保守预检，避免向 5 MiB 私有缓存目录写入明显超出空间的包；
        // 真正写入仍按流式大小上限和 I/O 异常处理，不申请外部存储。
        val usable = runCatching { root.usableSpace }.getOrDefault(0L)
        val available = usable - freeSpaceReserveBytes
        return requiredBytes > 0L && available > 0L && requiredBytes <= available
    }

    fun isInsideRoot(file: File): Boolean {
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val canonicalFile = file.canonicalFile
            canonicalFile.path.startsWith(canonicalRoot.path + File.separator)
        }.getOrDefault(false)
    }
}
