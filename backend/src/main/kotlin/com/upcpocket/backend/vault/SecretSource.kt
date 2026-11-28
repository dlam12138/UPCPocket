package com.upcpocket.backend.vault

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal object SecretSource {
    private const val MAX_SECRET_FILE_SIZE = 16 * 1024L

    fun read(inlineValue: String?, fileValue: String?, label: String): String? {
        val inline = inlineValue?.takeIf(String::isNotBlank)
        val file = fileValue?.takeIf(String::isNotBlank)
        require(inline == null || file == null) { "Configure $label through either an environment value or a file" }
        if (inline != null) return inline.trim()
        if (file == null) return null

        return try {
            val path = Path.of(file)
            require(!Files.isSymbolicLink(path)) { "$label file must not be a symbolic link" }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "$label file is not a regular file" }
            require(Files.size(path) <= MAX_SECRET_FILE_SIZE) { "$label file is too large" }
            val posixView = Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val permissions = posixView?.readAttributes()?.permissions()
            if (permissions != null) {
                require(
                    PosixFilePermission.GROUP_READ !in permissions &&
                        PosixFilePermission.OTHERS_READ !in permissions,
                ) { "$label file must not be readable by group or others" }
            }
            Files.readString(path, StandardCharsets.UTF_8).trim()
        } catch (e: IOException) {
            throw IllegalArgumentException("Unable to read $label file", e)
        }
    }
}
