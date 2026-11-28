package com.upcpocket.backend.storage

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

internal class JdbcTestDatabase : AutoCloseable {
    val dataSource = DriverManagerDataSource().apply {
        setDriverClassName("org.h2.Driver")
        setUrl(
            "jdbc:h2:mem:pocket_${UUID.randomUUID().toString().replace("-", "")};" +
                "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        )
        username = "sa"
        password = ""
    }
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val accounts = JdbcAccountStore(jdbc)
    val audits = JdbcAuditStore(jdbc)
    val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))

    init {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
        flyway.migrate()
        flyway.validate()
    }

    override fun close() {
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SHUTDOWN") }
            }
        }
    }

    companion object {
        fun sampleAccount(
            id: UUID = UUID.randomUUID(),
            seed: Int = 1,
            keyVersion: String = "v1",
            credentialGeneration: Long = 1,
            gateRevision: Long = 1,
            disabled: Boolean = false,
            reauthRequired: Boolean = false,
            pendingAttemptId: UUID? = null,
        ): com.upcpocket.backend.contract.AccountRecord {
            val loginTag = ByteArray(32) { (seed + it).toByte() }
            val envelope = com.upcpocket.backend.contract.EncryptedEnvelope(
                format = 1,
                payloadNonce = ByteArray(12) { (seed + it).toByte() },
                payloadCiphertext = ByteArray(16) { (seed + it + 1).toByte() },
                keyVersion = keyVersion,
                wrappedDek = ByteArray(48) { (seed + it + 2).toByte() },
            )
            return com.upcpocket.backend.contract.AccountRecord(
                accountId = id,
                loginTag = com.upcpocket.backend.contract.LoginTag(loginTag),
                disabled = disabled,
                reauthRequired = reauthRequired,
                credentialGeneration = credentialGeneration,
                gateRevision = gateRevision,
                pendingAttemptId = pendingAttemptId,
                envelope = envelope,
            )
        }
    }
}
