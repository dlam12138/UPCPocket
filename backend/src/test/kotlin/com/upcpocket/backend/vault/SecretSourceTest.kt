package com.upcpocket.backend.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

class SecretSourceTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `reads a regular private secret file`() {
        val file = Files.writeString(tempDirectory.resolve("secret"), "test-key\n")
        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }

        assertEquals("test-key", SecretSource.read(null, file.toString(), "test secret"))
    }

    @Test
    fun `rejects a secret file readable by group on posix filesystems`() {
        val file = Files.writeString(tempDirectory.resolve("secret"), "test-key")
        val posixView = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        assumeTrue(posixView != null, "POSIX permissions are unavailable on this file system")
        Files.setPosixFilePermissions(
            file,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SecretSource.read(null, file.toString(), "test secret")
        }
    }

    @Test
    fun `rejects symbolic link secret files`() {
        val target = Files.writeString(tempDirectory.resolve("target"), "test-key")
        val link = tempDirectory.resolve("secret-link")
        try {
            Files.createSymbolicLink(link, target.fileName)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are unavailable on this file system")
        } catch (_: IOException) {
            assumeTrue(false, "Symbolic links are unavailable to this test user")
        }

        assertThrows(IllegalArgumentException::class.java) {
            SecretSource.read(null, link.toString(), "test secret")
        }
    }
}
