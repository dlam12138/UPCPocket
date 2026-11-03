package com.upcpocket.app.account

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AtomicAccountFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `atomic store replaces reads and deletes only its own file`() {
        val directory = File(temporaryFolder.root, "school-account")
        val store = AtomicAccountFileStore(directory)
        val file = File(directory, AccountFormat.ACCOUNT_FILE)

        assertNull(store.read(AccountFormat.ACCOUNT_FILE, 1024))
        store.write(AccountFormat.ACCOUNT_FILE, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read(AccountFormat.ACCOUNT_FILE, 1024))

        store.delete(AccountFormat.ACCOUNT_FILE)
        assertNull(store.read(AccountFormat.ACCOUNT_FILE, 1024))
        assertFalse(file.exists())
    }
}
