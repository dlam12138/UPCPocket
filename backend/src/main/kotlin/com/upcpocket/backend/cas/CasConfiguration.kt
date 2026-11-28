package com.upcpocket.backend.cas

import com.upcpocket.backend.contract.UpcCasVerifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CasProperties::class)
class CasConfiguration {
    @Bean
    @ConditionalOnMissingBean(UpcCasVerifier::class)
    fun upcCasVerifier(properties: CasProperties): UpcCasVerifier = ConfiguredUpcCasVerifier(properties)
}
