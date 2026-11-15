package com.upcpocket.app.data.cardrecharge

import android.util.AtomicFile
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.platform.crypto.ByteCrypto
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RechargePendingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = CardRechargeServiceTest.Account().studentIdentity.value!!
    private val record = RechargePending(owner, "12345678-1234-1234-1234-123456789abc", "20")

    /** Synthetic envelope only, independent of Android Keystore and real credentials. */
    private val crypto = object : ByteCrypto {
        override fun encrypt(plain: ByteArray) = byteArrayOf(0x51, 0x41) + plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(stored: ByteArray): ByteArray {
            require(stored.size > 2 && stored[0] == 0x51.toByte() && stored[1] == 0x41.toByte())
            return stored.copyOfRange(2, stored.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    private fun file(directory: File) = File(directory, CardRechargeProtocol.hash(owner.accountId.value + ":" + owner.studentId) + ".enc")
    private fun failure(block: () -> Unit) {
        assertEquals("P-PENDING-STORE", assertThrows(RechargeFailure::class.java, block).stage)
    }

    @Test fun committed_marker_is_recovered_by_a_new_store_with_all_fields_and_without_plaintext() {
        val directory = temporary.newFolder()
        val expected = record.copy(orderId = "1234567890123", payId = "81")
        EncryptedRechargePendingStore(directory, crypto).save(expected)
        assertFalse(file(directory).readText().contains(owner.studentId))
        val restored = EncryptedRechargePendingStore(directory, crypto)
        assertEquals(expected, restored.read(owner))
        // Credential rotation permits read-only recovery of this account's old order.
        assertEquals(expected, restored.read(owner.copy(credentialGeneration = AccountGeneration(2))))
    }

    @Test fun sync_failure_is_observable_and_keeps_the_previous_committed_marker() {
        val directory = temporary.newFolder()
        EncryptedRechargePendingStore(directory, crypto).save(record)
        val store = EncryptedRechargePendingStore(directory, crypto, atomicFiles = { path ->
            object : AtomicFile(path) {
                override fun startWrite(): FileOutputStream = object : FileOutputStream(File(path.path + ".new")) {
                    // Simulate a stream whose write returns but whose descriptor cannot sync.
                    override fun write(bytes: ByteArray) = Unit
                }.apply { close() }
            }
        })
        failure { store.save(record.copy(orderId = "1234567890123")) }
        assertEquals(record, EncryptedRechargePendingStore(directory, crypto).read(owner))
    }

    @Test fun a_silent_uncommitted_write_cannot_be_acknowledged_as_a_saved_marker() {
        val directory = temporary.newFolder()
        val store = EncryptedRechargePendingStore(directory, crypto, atomicFiles = { path ->
            object : AtomicFile(path) {
                override fun finishWrite(stream: FileOutputStream?) { stream?.close() }
            }
        })
        failure { store.save(record) }
        assertNull(EncryptedRechargePendingStore(directory, crypto).read(owner))
    }

    @Test fun readback_compares_every_record_field_and_retains_the_old_base_after_a_silent_commit_failure() {
        val directory = temporary.newFolder()
        EncryptedRechargePendingStore(directory, crypto).save(record)
        val store = EncryptedRechargePendingStore(directory, crypto, atomicFiles = { path ->
            object : AtomicFile(path) {
                override fun finishWrite(stream: FileOutputStream?) { stream?.close() }
            }
        })
        for (updated in listOf(
            record.copy(orderId = "1234567890123"),
            record.copy(payId = "81"),
            record.copy(yuan = "50"),
            record.copy(owner = owner.copy(credentialGeneration = AccountGeneration(2))),
            record.copy(attemptId = "12345678-1234-1234-1234-123456789abd"),
        )) {
            failure { store.save(updated) }
            assertEquals(record, EncryptedRechargePendingStore(directory, crypto).read(owner))
        }
    }

    @Test fun unknown_create_is_still_protected_after_both_service_and_disk_store_are_recreated() = runTest {
        val directory = temporary.newFolder()
        val first = EncryptedRechargePendingStore(directory, crypto)
        val fixture = CardRechargeServiceTest.Fixture(first)
        fixture.failCreate = true
        fixture.service.open()
        assertNull(fixture.service.submit("20"))
        val committed = first.read(owner)!!
        val restoredStore = EncryptedRechargePendingStore(directory, crypto)
        val restoredService = CardRechargeService(fixture.history, restoredStore, fixture.transport)
        restoredService.open()
        assertEquals(committed, restoredStore.read(owner))
        assertTrue(restoredService.state.value.recoveryRequired)
        assertNull(restoredService.submit("20"))
        assertEquals(1, fixture.createPosts)
        assertEquals(0, fixture.payPosts)
    }

    @Test fun corrupt_committed_marker_blocks_all_writes_and_is_preserved_for_recovery() = runTest {
        val directory = temporary.newFolder()
        val store = EncryptedRechargePendingStore(directory, crypto)
        store.save(record)
        file(directory).writeText("synthetic-corruption")
        val fixture = CardRechargeServiceTest.Fixture(store)
        fixture.service.open()
        assertNull(fixture.service.submit("20"))
        assertEquals(0, fixture.createPosts)
        assertEquals(0, fixture.payPosts)
        assertTrue(fixture.service.state.value.recoveryRequired)
        assertEquals("synthetic-corruption", file(directory).readText())
    }

    @Test fun directory_write_failure_prevents_the_first_order_POST() = runTest {
        val directoryIsAFile = temporary.newFile()
        val store = EncryptedRechargePendingStore(directoryIsAFile, crypto)
        val fixture = CardRechargeServiceTest.Fixture(store)
        fixture.service.open()
        assertNull(fixture.service.submit("20"))
        assertEquals(0, fixture.createPosts)
        assertEquals(0, fixture.payPosts)
        assertTrue(fixture.service.state.value.recoveryRequired)
        assertTrue(directoryIsAFile.isFile)
    }

    @Test fun any_silent_commit_failure_blocks_the_next_protected_POST_and_preserves_prior_progress() = runTest {
        // Marker, returned order ID and selected pay method are all persisted before proceeding.
        for (failAt in 1..3) {
            val directory = temporary.newFolder()
            var commits = 0
            val store = EncryptedRechargePendingStore(directory, crypto, atomicFiles = { path ->
                object : AtomicFile(path) {
                    override fun finishWrite(stream: FileOutputStream?) {
                        commits++
                        if (commits == failAt) stream?.close() else super.finishWrite(stream)
                    }
                }
            })
            val fixture = CardRechargeServiceTest.Fixture(store)
            fixture.service.open()
            assertNull(fixture.service.submit("20"))
            assertEquals(if (failAt == 1) 0 else 1, fixture.createPosts)
            assertEquals(0, fixture.payPosts)
            assertTrue(fixture.service.state.value.recoveryRequired)
            val retained = EncryptedRechargePendingStore(directory, crypto).read(owner)
            if (failAt == 1) assertNull(retained) else {
                assertNotNull(retained)
                assertEquals(if (failAt == 3) fixture.order else null, retained!!.orderId)
                assertNull(retained.payId)
                assertNull(fixture.service.submit("20"))
                assertEquals(1, fixture.createPosts)
                assertEquals(0, fixture.payPosts)
            }
        }
    }
}
