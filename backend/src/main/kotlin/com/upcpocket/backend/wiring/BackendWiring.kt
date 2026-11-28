package com.upcpocket.backend.wiring

import com.upcpocket.backend.contract.AccountStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Configuration
class BackendWiring {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun backendClock(): Clock = Clock.systemUTC()

    @Bean
    fun orphanedPendingAttemptRecovery(
        accountStore: AccountStore,
        transactions: TransactionTemplate,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        transactions.execute { accountStore.convertOrphanedPendingAttemptsToReauthRequired() }
            ?: throw IllegalStateException("Orphaned-attempt recovery transaction returned no result")
    }
}
