package com.upcpocket.backend.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class EnvMasterKeyProviderTest {
    @Test
    fun `wrap unwrap and rewrap use versioned aes gcm envelopes`() {
        val key1 = ByteArray(32) { (it + 1).toByte() }
        val key2 = ByteArray(32) { (it + 51).toByte() }
        val provider = EnvMasterKeyProvider(
            "v1:${Base64.getEncoder().encodeToString(key1)},v2:${Base64.getEncoder().encodeToString(key2)}",
            null,
        )
        val dek = ByteArray(32) { (it + 101).toByte() }
        val originalDek = dek.copyOf()
        try {
            val wrappedV1 = provider.wrap("v1", dek)
            assertEquals(12 + 32 + 16, wrappedV1.size)
            assertTrue(provider.healthCheck())
            assertEquals(setOf("v1", "v2"), provider.configuredVersions())
            val unwrappedV1 = provider.unwrap("v1", wrappedV1)
            try {
                assertArrayEquals(originalDek, unwrappedV1)
            } finally {
                unwrappedV1.fill(0)
            }

            val wrappedV2 = provider.rewrap("v1", "v2", wrappedV1)
            val unwrappedV2 = provider.unwrap("v2", wrappedV2)
            try {
                assertArrayEquals(originalDek, unwrappedV2)
            } finally {
                unwrappedV2.fill(0)
            }
            assertArrayEquals(originalDek, dek)
            wrappedV1.fill(0)
            wrappedV2.fill(0)
        } finally {
            dek.fill(0)
            originalDek.fill(0)
            key1.fill(0)
            key2.fill(0)
            provider.close()
        }
        assertFalse(provider.healthCheck())
    }
}
