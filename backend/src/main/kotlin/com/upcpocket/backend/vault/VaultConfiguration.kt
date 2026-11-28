package com.upcpocket.backend.vault

import com.upcpocket.backend.contract.BlindIndexHasher
import com.upcpocket.backend.contract.CredentialVault
import com.upcpocket.backend.contract.MasterKeyProvider
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VaultProperties::class)
class VaultConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(MasterKeyProvider::class)
    fun masterKeyProvider(properties: VaultProperties): MasterKeyProvider =
        EnvMasterKeyProvider(properties.masterKeys, properties.masterKeysFile)

    @Bean
    fun validateInitialWriteKeyVersion(
        properties: VaultProperties,
        masterKeyProvider: MasterKeyProvider,
    ): InitializingBean = InitializingBean {
        val version = properties.initialWriteKeyVersion
        require(!version.isNullOrBlank()) { "pocket.vault.initial-write-key-version is required" }
        require(version in masterKeyProvider.configuredVersions()) {
            "pocket.vault.initial-write-key-version is not present in the configured master keys"
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(BlindIndexHasher::class)
    fun blindIndexHasher(properties: VaultProperties): BlindIndexHasher =
        HmacBlindIndexHasher(
            loginIndexKey = properties.loginIndexKey,
            loginIndexKeyFile = properties.loginIndexKeyFile,
            auditIndexKey = properties.auditIndexKey,
            auditIndexKeyFile = properties.auditIndexKeyFile,
        )

    @Bean
    @ConditionalOnMissingBean(CredentialVault::class)
    fun credentialVault(masterKeyProvider: MasterKeyProvider): CredentialVault =
        AesGcmCredentialVault(masterKeyProvider)
}
