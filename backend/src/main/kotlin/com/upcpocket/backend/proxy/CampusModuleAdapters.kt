package com.upcpocket.backend.proxy

import com.upcpocket.backend.contract.CampusAdapterException
import com.upcpocket.backend.contract.CampusModuleKey
import com.upcpocket.backend.contract.CardAdapter
import com.upcpocket.backend.contract.GradesAdapter
import com.upcpocket.backend.contract.TimetableAdapter
import com.upcpocket.backend.service.ApiErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Production defaults deliberately contain no HTTP client, URL, or CAS behavior. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pocket.proxy", name = ["not-configured-adapters"], havingValue = "true", matchIfMissing = true)
class CampusModuleAdapterConfiguration {
    @Bean
    @ConditionalOnMissingBean(TimetableAdapter::class)
    fun notConfiguredTimetableAdapter(): TimetableAdapter = NotConfiguredTimetableAdapter()

    @Bean
    @ConditionalOnMissingBean(CardAdapter::class)
    fun notConfiguredCardAdapter(): CardAdapter = NotConfiguredCardAdapter()

    @Bean
    @ConditionalOnMissingBean(GradesAdapter::class)
    fun notConfiguredGradesAdapter(): GradesAdapter = NotConfiguredGradesAdapter()
}

private class NotConfiguredTimetableAdapter : TimetableAdapter {
    override val moduleKey = CampusModuleKey.TIMETABLE
    override val adapterReady = false
    override fun login(credential: com.upcpocket.backend.contract.CredentialLease) = unavailable()
    override fun readIndex(session: com.upcpocket.backend.session.BusinessSessionValue) = unavailable()
    override fun readWeek(
        session: com.upcpocket.backend.session.BusinessSessionValue,
        year: String,
        term: String,
        week: Int,
    ) = unavailable()
}

private class NotConfiguredCardAdapter : CardAdapter {
    override val moduleKey = CampusModuleKey.CARD
    override val adapterReady = false
    override fun login(credential: com.upcpocket.backend.contract.CredentialLease) = unavailable()
    override fun readBalance(
        session: com.upcpocket.backend.session.BusinessSessionValue,
        studentId: CharArray,
    ) = unavailable()
    override fun readTransactions(
        session: com.upcpocket.backend.session.BusinessSessionValue,
        month: String?,
        page: Int,
        size: Int,
    ) = unavailable()
    override fun readTransactionsSummary(
        session: com.upcpocket.backend.session.BusinessSessionValue,
        month: String?,
    ) = unavailable()
}

private class NotConfiguredGradesAdapter : GradesAdapter {
    override val moduleKey = CampusModuleKey.GRADES
    override val adapterReady = false
    override fun login(credential: com.upcpocket.backend.contract.CredentialLease) = unavailable()
    override fun readTerms(session: com.upcpocket.backend.session.BusinessSessionValue) = unavailable()
    override fun readCourses(
        session: com.upcpocket.backend.session.BusinessSessionValue,
        year: String,
        term: String,
    ) = unavailable()
}

private fun unavailable(): Nothing = throw CampusAdapterException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
