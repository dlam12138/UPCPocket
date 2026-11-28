package com.upcpocket.backend.storage

import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.contract.AuditStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountStore::class)
    fun accountStore(jdbc: NamedParameterJdbcTemplate): AccountStore = JdbcAccountStore(jdbc)

    @Bean
    @ConditionalOnMissingBean(AuditStore::class)
    fun auditStore(jdbc: NamedParameterJdbcTemplate): AuditStore = JdbcAuditStore(jdbc)
}
