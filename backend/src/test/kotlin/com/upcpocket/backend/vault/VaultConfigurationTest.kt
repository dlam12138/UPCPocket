package com.upcpocket.backend.vault

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class VaultConfigurationTest {
    @Test
    fun `startup validation accepts an initial key version that is configured`() {
        val properties = VaultProperties().apply { initialWriteKeyVersion = "v1" }
        val provider = EnvMasterKeyProvider("v1:${Base64.getEncoder().encodeToString(ByteArray(32))}", null)

        try {
            val validation = VaultConfiguration().validateInitialWriteKeyVersion(properties, provider)
            assertDoesNotThrow { validation.afterPropertiesSet() }
        } finally {
            provider.close()
        }
    }

    @Test
    fun `startup validation rejects a missing or unavailable initial key version`() {
        val properties = VaultProperties().apply { initialWriteKeyVersion = "v2" }
        val provider = EnvMasterKeyProvider("v1:${Base64.getEncoder().encodeToString(ByteArray(32))}", null)

        try {
            val validation = VaultConfiguration().validateInitialWriteKeyVersion(properties, provider)
            assertThrows(IllegalArgumentException::class.java) { validation.afterPropertiesSet() }

            properties.initialWriteKeyVersion = null
            val missing = VaultConfiguration().validateInitialWriteKeyVersion(properties, provider)
            assertThrows(IllegalArgumentException::class.java) { missing.afterPropertiesSet() }
        } finally {
            provider.close()
        }
    }
}
