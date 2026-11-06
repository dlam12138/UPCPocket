package com.upcpocket.app.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun publishMovesPartAndRejectsOutsideFile() {
        val root = temporaryFolder.newFolder("updates")
        val storage = UpdateStorage(root)
        val part = storage.preparePart(22)
        part.writeBytes(byteArrayOf(1, 2, 3))
        val final = storage.publish(part, 22)
        assertTrue(final.isFile)
        assertFalse(part.exists())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(final.readBytes()))

        val outside = temporaryFolder.newFile("outside.apk")
        assertFalse(storage.isInsideRoot(outside))
    }

    @Test
    fun cleanupDropsInterruptedPartsAndKeepsBoundedRecentApks() {
        val root = temporaryFolder.newFolder("updates")
        val storage = UpdateStorage(root, maxCompletedFiles = 2, maxAgeMs = 24 * 60 * 60 * 1000L)
        val part = File(root, "old.apk.part").apply { writeBytes(byteArrayOf(9)) }
        val old = File(root, "old.apk").apply { writeBytes(ByteArray(16)) }
        old.setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
        val newer = (1..3).map { index ->
            File(root, "new$index.apk").apply {
                writeBytes(ByteArray(16))
                setLastModified(System.currentTimeMillis() - index * 1000L)
            }
        }
        storage.cleanup()
        assertFalse(part.exists())
        assertFalse(old.exists())
        assertEquals(2, root.listFiles().orEmpty().count { it.name.endsWith(".apk") })
        assertTrue(newer.count { it.exists() } == 2)
    }

    @Test
    fun freeSpaceCheckHandlesAbsurdRequiredSize() {
        val storage = UpdateStorage(temporaryFolder.newFolder("updates"))
        assertTrue(storage.hasSpace(1_000_000L))
        assertFalse(storage.hasSpace(Long.MAX_VALUE))
    }

    @Test
    fun filesAreConfinedToCanonicalRoot() {
        val root = temporaryFolder.newFolder("updates")
        val storage = UpdateStorage(root)
        val nested = File(root, "sub").apply { mkdirs() }
        assertTrue(storage.isInsideRoot(File(nested, "a.apk")))
        val sibling = temporaryFolder.newFolder("other")
        assertFalse(storage.isInsideRoot(File(sibling, "a.apk")))
    }
}
