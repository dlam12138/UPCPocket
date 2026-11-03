package com.upcpocket.app.account

import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 账号目录的最小文件边界。
 *
 * 每个文件单独使用 AtomicFile 提交；账号层再用 journal + state 把多个文件串成一个
 * 可恢复的提交协议。测试可替换实现，在任意一步注入写失败而不用真实磁盘。
 */
internal interface AccountFileStore {
    fun read(name: String, maxBytes: Int): ByteArray?

    fun write(name: String, bytes: ByteArray)

    fun delete(name: String)

    fun exists(name: String): Boolean
}

internal class AtomicAccountFileStore(
    private val directory: File,
) : AccountFileStore {

    init {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建账号私有目录")
        }
        if (!directory.isDirectory) throw IOException("账号私有路径不是目录")
    }

    override fun read(name: String, maxBytes: Int): ByteArray? {
        val atomic = atomic(name)
        if (!atomic.baseFile.exists() && !backup(name).exists()) return null
        val input = atomic.openRead()
        return input.use {
            val bytes = it.readAtMost(maxBytes)
            if (bytes == null) {
                throw IOException("账号文件超过本地长度限制")
            }
            bytes
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        val atomic = atomic(name)
        val output: FileOutputStream = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    override fun delete(name: String) {
        // AtomicFile.delete() only removes base+bak, and does not verify the result.
        // Also remove the in-progress .new file; otherwise a later write can inherit
        // a stale partial artifact. The caller may keep a journal tombstone on failure.
        val candidates = listOf(
            File(directory, name),
            backup(name),
            File(directory, "$name.new"),
        )
        var failed = false
        candidates.forEach { file ->
            if (file.exists() && !file.delete()) failed = true
        }
        if (failed || candidates.any { it.exists() }) {
            throw IOException("删除账号文件失败")
        }
    }

    override fun exists(name: String): Boolean =
        atomic(name).baseFile.exists() || backup(name).exists()

    private fun atomic(name: String): AtomicFile = AtomicFile(File(directory, name))

    private fun backup(name: String): File = File(directory, "$name.bak")

    private fun FileInputStream.readAtMost(maxBytes: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
