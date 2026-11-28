package com.upcpocket.backend.service

import com.upcpocket.backend.contract.AuditPage
import com.upcpocket.backend.contract.AuditStore
import com.upcpocket.backend.proxy.AccountRateLimitProperties
import com.upcpocket.backend.proxy.CampusModuleAdapterRegistry
import com.upcpocket.backend.proxy.CampusProxyProperties
import com.upcpocket.backend.proxy.ModuleCircuitBreaker
import com.upcpocket.backend.proxy.ModuleRuntimeProperties
import com.upcpocket.backend.storage.JdbcTestDatabase
import com.upcpocket.backend.support.FakeCardAdapter
import com.upcpocket.backend.support.FakeGradesAdapter
import com.upcpocket.backend.support.FakeTimetableAdapter
import com.upcpocket.backend.vault.HmacBlindIndexHasher
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ModuleSwitchServiceTest {
    @Test
    fun `V1 database with existing policy row upgrades to seeded V2 and validates`() {
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.h2.Driver")
            setUrl(
                "jdbc:h2:mem:module_upgrade_${UUID.randomUUID().toString().replace("-", "")};" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            )
            username = "sa"
            password = ""
        }
        try {
            val v1 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load()
            v1.migrate()
            v1.validate()
            JdbcTemplate(dataSource).update(
                "INSERT INTO vault_policy (singleton_id, write_key_version) VALUES (1, 'existing-key')",
            )

            val full = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load()
            full.migrate()
            full.validate()
            val jdbc = JdbcTemplate(dataSource)
            assertEquals("existing-key", jdbc.queryForObject("SELECT write_key_version FROM vault_policy WHERE singleton_id = 1", String::class.java))
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM pocket_module_switch", Int::class.javaObjectType))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pocket_module_switch WHERE enabled = TRUE", Int::class.javaObjectType))
        } finally {
            runCatching { dataSource.connection.use { it.createStatement().use { statement -> statement.execute("SHUTDOWN") } } }
        }
    }

    @Test
    fun `audit append failure rolls back the module state change`() {
        JdbcTestDatabase().use { database ->
            val failingAudit = object : AuditStore {
                override fun append(event: com.upcpocket.backend.contract.AuditEventRecord) {
                    throw IllegalStateException("injected audit failure")
                }

                override fun list(cursor: String?, limit: Int) = AuditPage(emptyList(), null)
            }
            val hasher = HmacBlindIndexHasher(
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                null,
                "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
                null,
            )
            try {
                val clock = Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC)
                val properties = CampusProxyProperties(accountRateLimit = AccountRateLimitProperties())
                val adapters = CampusModuleAdapterRegistry(
                    listOf(FakeTimetableAdapter(), FakeCardAdapter(), FakeGradesAdapter()),
                )
                val service = ModuleSwitchService(
                    JdbcTemplate(database.dataSource),
                    failingAudit,
                    hasher,
                    database.transactionTemplate,
                    adapters,
                    com.upcpocket.backend.proxy.ModuleCircuitBreakers(properties, clock),
                    clock,
                )

                assertThrows(ApiException::class.java) {
                    service.update("timetable", true, 1, "test-admin", UUID.randomUUID())
                }
                assertFalse(
                    JdbcTemplate(database.dataSource).queryForObject(
                        "SELECT enabled FROM pocket_module_switch WHERE module_key = 'timetable'",
                        Boolean::class.javaObjectType,
                    ),
                )
                assertEquals(1L, JdbcTemplate(database.dataSource).queryForObject(
                    "SELECT revision FROM pocket_module_switch WHERE module_key = 'timetable'",
                    Long::class.javaObjectType,
                ))
                assertEquals(0, JdbcTemplate(database.dataSource).queryForObject(
                    "SELECT COUNT(*) FROM pocket_audit.event",
                    Int::class.javaObjectType,
                ))
            } finally {
                hasher.close()
            }
        }
    }

    @Test
    fun `circuit breaker opens cools probes once and recovers`() {
        val clock = MutableClock(Instant.parse("2026-11-28T00:00:00Z"))
        val breaker = ModuleCircuitBreaker(
            ModuleRuntimeProperties(circuitFailureThreshold = 2, circuitCooldownMs = 1_000),
            clock,
        )
        breaker.failure(breaker.acquire()!!, counted = true)
        assertEquals(com.upcpocket.backend.contract.ModuleCircuitState.CLOSED, breaker.state())
        breaker.failure(breaker.acquire()!!, counted = true)
        assertEquals(com.upcpocket.backend.contract.ModuleCircuitState.OPEN, breaker.state())
        assertTrue(breaker.acquire() == null)

        clock.advanceMillis(1_000)
        assertEquals(com.upcpocket.backend.contract.ModuleCircuitState.HALF_OPEN, breaker.state())
        val probe = breaker.acquire()!!
        assertTrue(probe.halfOpenProbe)
        assertTrue(breaker.acquire() == null)
        breaker.success(probe)
        assertEquals(com.upcpocket.backend.contract.ModuleCircuitState.CLOSED, breaker.state())

        val ignored = breaker.acquire()!!
        breaker.failure(ignored, counted = false)
        assertEquals(com.upcpocket.backend.contract.ModuleCircuitState.CLOSED, breaker.state())
    }

    @Test
    fun `non counted half open failure allows the next request to probe immediately`() {
        val clock = MutableClock(Instant.parse("2026-11-28T00:00:00Z"))
        val breaker = ModuleCircuitBreaker(
            ModuleRuntimeProperties(circuitFailureThreshold = 1, circuitCooldownMs = 1_000),
            clock,
        )
        breaker.failure(breaker.acquire()!!, counted = true)
        clock.advanceMillis(1_000)

        val probe = breaker.acquire()!!
        assertTrue(probe.halfOpenProbe)
        breaker.failure(probe, counted = false)
        assertEquals(com.upcpocket.backend.contract.ModuleCircuitState.HALF_OPEN, breaker.state())
        val recoveryProbe = breaker.acquire()!!
        assertTrue(recoveryProbe.halfOpenProbe)
        assertTrue(breaker.acquire() == null)
        breaker.success(recoveryProbe)
        assertEquals(com.upcpocket.backend.contract.ModuleCircuitState.CLOSED, breaker.state())
    }

    private class MutableClock(private var current: Instant) : Clock() {
        fun advanceMillis(millis: Long) { current = current.plusMillis(millis) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
    }
}
