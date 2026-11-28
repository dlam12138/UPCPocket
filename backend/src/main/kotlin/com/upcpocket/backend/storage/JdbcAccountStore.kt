package com.upcpocket.backend.storage

import com.upcpocket.backend.contract.AccountRecord
import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.contract.EncryptedEnvelope
import com.upcpocket.backend.contract.LoginTag
import com.upcpocket.backend.contract.VaultPolicyRecord
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Savepoint
import java.sql.Types
import java.util.UUID

class JdbcAccountStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : AccountStore {
    override fun findByLoginTag(loginTag: LoginTag): AccountRecord? {
        val bytes = loginTag.copyBytes()
        return try {
            jdbc.query(SELECT_ACCOUNT + " WHERE login_tag = :loginTag", mapOf("loginTag" to bytes), ACCOUNT_ROW_MAPPER)
                .firstOrNull()
        } finally {
            bytes.fill(0)
        }
    }

    override fun findByAccountId(accountId: UUID): AccountRecord? =
        jdbc.query(
            SELECT_ACCOUNT + " WHERE account_id = :accountId",
            mapOf("accountId" to accountId),
            ACCOUNT_ROW_MAPPER,
        ).firstOrNull()

    override fun insertFirstBinding(account: AccountRecord): Boolean {
        val parameters = accountParameters(account)
        return try {
            jdbc.update(
                """
                INSERT INTO pocket_account (
                    account_id, login_tag, disabled, reauth_required,
                    credential_generation, gate_revision, pending_attempt_id,
                    envelope_format, payload_nonce, payload_ciphertext, key_version, wrapped_dek
                ) VALUES (
                    :accountId, :loginTag, :disabled, :reauthRequired,
                    :credentialGeneration, :gateRevision, :pendingAttemptId,
                    :envelopeFormat, :payloadNonce, :payloadCiphertext, :keyVersion, :wrappedDek
                )
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                parameters.source,
            ) == 1
        } catch (_: DuplicateKeyException) {
            false
        } finally {
            parameters.close()
        }
    }

    override fun registerPendingAttempt(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        expectedGateRevision: Long,
        attemptId: UUID,
    ): Boolean = jdbc.update(
        """
        UPDATE pocket_account
        SET pending_attempt_id = :attemptId
        WHERE account_id = :accountId
          AND disabled = FALSE
          AND reauth_required = FALSE
          AND credential_generation = :credentialGeneration
          AND gate_revision = :gateRevision
          AND pending_attempt_id IS NULL
        """.trimIndent(),
        mapOf(
            "accountId" to accountId,
            "credentialGeneration" to expectedCredentialGeneration,
            "gateRevision" to expectedGateRevision,
            "attemptId" to attemptId,
        ),
    ) == 1

    override fun clearPendingAttempt(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        attemptId: UUID,
    ): Boolean = jdbc.update(
        """
        UPDATE pocket_account
        SET pending_attempt_id = NULL
        WHERE account_id = :accountId
          AND credential_generation = :credentialGeneration
          AND pending_attempt_id = :attemptId
        """.trimIndent(),
        mapOf(
            "accountId" to accountId,
            "credentialGeneration" to expectedCredentialGeneration,
            "attemptId" to attemptId,
        ),
    ) == 1

    override fun tripReauthRequired(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        attemptId: UUID,
    ): Boolean = jdbc.update(
        """
        UPDATE pocket_account
        SET reauth_required = TRUE,
            pending_attempt_id = NULL,
            gate_revision = gate_revision + 1
        WHERE account_id = :accountId
          AND disabled = FALSE
          AND reauth_required = FALSE
          AND credential_generation = :credentialGeneration
          AND pending_attempt_id = :attemptId
        """.trimIndent(),
        mapOf(
            "accountId" to accountId,
            "credentialGeneration" to expectedCredentialGeneration,
            "attemptId" to attemptId,
        ),
    ) == 1

    override fun updateCredential(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        expectedGateRevision: Long,
        expectedPendingAttemptId: UUID?,
        loginTag: LoginTag,
        envelope: EncryptedEnvelope,
    ): AccountRecord? {
        val bytes = envelopeParameters(loginTag, envelope)
        val parameters = MapSqlParameterSource()
            .addValue("accountId", accountId)
            .addValue("credentialGeneration", expectedCredentialGeneration)
            .addValue("gateRevision", expectedGateRevision)
            .addValue("expectedPendingAttemptId", expectedPendingAttemptId, Types.OTHER)
            .addValue("loginTag", bytes.loginTag)
            .addValue("envelopeFormat", envelope.format)
            .addValue("payloadNonce", bytes.nonce)
            .addValue("payloadCiphertext", bytes.ciphertext)
            .addValue("keyVersion", envelope.keyVersion)
            .addValue("wrappedDek", bytes.wrappedDek)

        return try {
            val changed = withUniqueConflictRecovery(onConflict = { false }) {
                jdbc.update(
                    """
                    UPDATE pocket_account
                    SET login_tag = :loginTag,
                        credential_generation = credential_generation + 1,
                        gate_revision = gate_revision + 1,
                        pending_attempt_id = NULL,
                        reauth_required = FALSE,
                        envelope_format = :envelopeFormat,
                        payload_nonce = :payloadNonce,
                        payload_ciphertext = :payloadCiphertext,
                        key_version = :keyVersion,
                        wrapped_dek = :wrappedDek
                    WHERE account_id = :accountId
                      AND credential_generation = :credentialGeneration
                      AND gate_revision = :gateRevision
                      AND pending_attempt_id IS NOT DISTINCT FROM CAST(:expectedPendingAttemptId AS UUID)
                    """.trimIndent(),
                    parameters,
                ) == 1
            }
            if (changed) findByAccountId(accountId) else null
        } finally {
            bytes.close()
        }
    }

    override fun disable(accountId: UUID, expectedGateRevision: Long): AccountRecord? =
        updateAndRead(
            """
            UPDATE pocket_account
            SET disabled = TRUE,
                pending_attempt_id = NULL,
                gate_revision = gate_revision + 1
            WHERE account_id = :accountId
              AND gate_revision = :gateRevision
              AND disabled = FALSE
            """.trimIndent(),
            accountId,
            expectedGateRevision,
        )

    override fun restore(accountId: UUID, expectedGateRevision: Long): AccountRecord? =
        updateAndRead(
            """
            UPDATE pocket_account
            SET disabled = FALSE,
                pending_attempt_id = NULL,
                gate_revision = gate_revision + 1
            WHERE account_id = :accountId
              AND gate_revision = :gateRevision
              AND disabled = TRUE
            """.trimIndent(),
            accountId,
            expectedGateRevision,
        )

    override fun revokeAll(accountId: UUID, expectedGateRevision: Long): AccountRecord? =
        updateAndRead(
            """
            UPDATE pocket_account
            SET pending_attempt_id = NULL,
                gate_revision = gate_revision + 1
            WHERE account_id = :accountId
              AND gate_revision = :gateRevision
            """.trimIndent(),
            accountId,
            expectedGateRevision,
        )

    override fun convertOrphanedPendingAttemptsToReauthRequired(): Int = jdbc.update(
        """
        UPDATE pocket_account
        SET reauth_required = TRUE,
            pending_attempt_id = NULL,
            gate_revision = gate_revision + 1
        WHERE pending_attempt_id IS NOT NULL
        """.trimIndent(),
        emptyMap<String, Any>(),
    )

    override fun unbind(accountId: UUID, expectedGateRevision: Long): Boolean = jdbc.update(
        "DELETE FROM pocket_account WHERE account_id = :accountId AND gate_revision = :gateRevision",
        mapOf("accountId" to accountId, "gateRevision" to expectedGateRevision),
    ) == 1

    override fun findVaultPolicy(): VaultPolicyRecord? = jdbc.query(
        "SELECT write_key_version FROM vault_policy WHERE singleton_id = 1",
        RowMapper { rs, _ -> VaultPolicyRecord(rs.getString("write_key_version")) },
    ).firstOrNull()

    override fun lockVaultPolicyForWrite(): VaultPolicyRecord? {
        requireActiveJdbcTransaction()
        return jdbc.query(
            "SELECT write_key_version FROM vault_policy WHERE singleton_id = 1 FOR UPDATE",
            RowMapper { rs, _ -> VaultPolicyRecord(rs.getString("write_key_version")) },
        ).firstOrNull()
    }

    override fun insertVaultPolicyIfAbsent(policy: VaultPolicyRecord): Boolean = try {
        jdbc.update(
            "INSERT INTO vault_policy (singleton_id, write_key_version) VALUES (1, :version) ON CONFLICT DO NOTHING",
            mapOf("version" to policy.writeKeyVersion),
        ) == 1
    } catch (_: DuplicateKeyException) {
        false
    }

    override fun updateVaultWriteKeyVersion(
        expectedWriteKeyVersion: String,
        newWriteKeyVersion: String,
    ): Boolean = jdbc.update(
        """
        UPDATE vault_policy
        SET write_key_version = :newVersion
        WHERE singleton_id = 1 AND write_key_version = :expectedVersion
        """.trimIndent(),
        mapOf("expectedVersion" to expectedWriteKeyVersion, "newVersion" to newWriteKeyVersion),
    ) == 1

    override fun findAccountsNeedingRewrap(
        targetKeyVersion: String,
        afterAccountId: UUID?,
        limit: Int,
    ): List<AccountRecord> {
        val boundedLimit = limit.coerceIn(MIN_REWRAP_BATCH_SIZE, MAX_REWRAP_BATCH_SIZE)
        val sql = if (afterAccountId == null) {
            "$SELECT_ACCOUNT WHERE key_version <> :targetVersion ORDER BY account_id ASC LIMIT :limit"
        } else {
            "$SELECT_ACCOUNT WHERE key_version <> :targetVersion AND account_id > :afterAccountId " +
                "ORDER BY account_id ASC LIMIT :limit"
        }
        val parameters = MapSqlParameterSource()
            .addValue("targetVersion", targetKeyVersion)
            .addValue("limit", boundedLimit)
        if (afterAccountId != null) parameters.addValue("afterAccountId", afterAccountId)
        return jdbc.query(sql, parameters, ACCOUNT_ROW_MAPPER)
    }

    override fun countAccountsNotUsingKeyVersion(targetKeyVersion: String): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pocket_account WHERE key_version <> :targetVersion",
            mapOf("targetVersion" to targetKeyVersion),
            Long::class.javaObjectType,
        ) ?: 0L

    override fun updateWrappedDekIfUnchanged(
        accountId: UUID,
        expectedCredentialGeneration: Long,
        expectedKeyVersion: String,
        expectedWrappedDek: ByteArray,
        newKeyVersion: String,
        newWrappedDek: ByteArray,
    ): Boolean {
        val expected = expectedWrappedDek.copyOf()
        val replacement = newWrappedDek.copyOf()
        return try {
            jdbc.update(
                """
                UPDATE pocket_account
                SET key_version = :newKeyVersion, wrapped_dek = :newWrappedDek
                WHERE account_id = :accountId
                  AND credential_generation = :credentialGeneration
                  AND key_version = :expectedKeyVersion
                  AND wrapped_dek = :expectedWrappedDek
                """.trimIndent(),
                mapOf(
                    "accountId" to accountId,
                    "credentialGeneration" to expectedCredentialGeneration,
                    "expectedKeyVersion" to expectedKeyVersion,
                    "expectedWrappedDek" to expected,
                    "newKeyVersion" to newKeyVersion,
                    "newWrappedDek" to replacement,
                ),
            ) == 1
        } finally {
            expected.fill(0)
            replacement.fill(0)
        }
    }

    private fun updateAndRead(sql: String, accountId: UUID, expectedGateRevision: Long): AccountRecord? {
        val changed = jdbc.update(
            sql,
            mapOf("accountId" to accountId, "gateRevision" to expectedGateRevision),
        ) == 1
        return if (changed) findByAccountId(accountId) else null
    }

    private fun requireActiveJdbcTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "lockVaultPolicyForWrite requires an active JDBC transaction"
        }
        val dataSource = requireNotNull(jdbc.jdbcTemplate.dataSource) {
            "lockVaultPolicyForWrite requires a configured data source"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            check(DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
                "lockVaultPolicyForWrite requires an active JDBC transaction"
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    /** A PostgreSQL unique violation aborts the active transaction until it is rolled back. */
    private inline fun <T> withUniqueConflictRecovery(onConflict: () -> T, operation: () -> T): T {
        val dataSource = jdbc.jdbcTemplate.dataSource
        if (dataSource == null) {
            return try {
                operation()
            } catch (_: DuplicateKeyException) {
                onConflict()
            }
        }

        val connection = DataSourceUtils.getConnection(dataSource)
        var savepoint: Savepoint? = null
        var savepointResolved = false
        try {
            if (DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
                savepoint = connection.setSavepoint()
            }
            return try {
                val result = operation()
                savepointResolved = true
                result
            } catch (e: DuplicateKeyException) {
                if (savepoint != null) {
                    connection.rollback(savepoint)
                    savepointResolved = true
                } else if (!connection.autoCommit) {
                    throw e
                }
                onConflict()
            }
        } finally {
            try {
                if (savepointResolved) savepoint?.let(connection::releaseSavepoint)
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource)
            }
        }
    }

    private fun accountParameters(account: AccountRecord): AccountParameters =
        AccountParameters(account, envelopeParameters(account.loginTag, account.envelope))

    private fun envelopeParameters(loginTag: LoginTag, envelope: EncryptedEnvelope): EnvelopeParameters =
        EnvelopeParameters(
            loginTag = loginTag.copyBytes(),
            nonce = envelope.payloadNonce,
            ciphertext = envelope.payloadCiphertext,
            wrappedDek = envelope.wrappedDek,
        )

    private class AccountParameters(
        private val account: AccountRecord,
        private val envelope: EnvelopeParameters,
    ) : AutoCloseable {
        val source = MapSqlParameterSource()
            .addValue("accountId", account.accountId)
            .addValue("disabled", account.disabled)
            .addValue("reauthRequired", account.reauthRequired)
            .addValue("credentialGeneration", account.credentialGeneration)
            .addValue("gateRevision", account.gateRevision)
            .addValue("pendingAttemptId", account.pendingAttemptId, Types.OTHER)
            .addValue("envelopeFormat", account.envelope.format)
            .addValue("keyVersion", account.envelope.keyVersion)
            .addValue("loginTag", envelope.loginTag)
            .addValue("payloadNonce", envelope.nonce)
            .addValue("payloadCiphertext", envelope.ciphertext)
            .addValue("wrappedDek", envelope.wrappedDek)

        override fun close() = envelope.close()
    }

    private class EnvelopeParameters(
        val loginTag: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val wrappedDek: ByteArray,
    ) : AutoCloseable {
        override fun close() {
            loginTag.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
            wrappedDek.fill(0)
        }
    }

    companion object {
        private const val MIN_REWRAP_BATCH_SIZE = 1
        private const val MAX_REWRAP_BATCH_SIZE = 500

        private val SELECT_ACCOUNT = """
            SELECT account_id, login_tag, disabled, reauth_required,
                   credential_generation, gate_revision, pending_attempt_id,
                   envelope_format, payload_nonce, payload_ciphertext, key_version, wrapped_dek
            FROM pocket_account
        """.trimIndent()

        private val ACCOUNT_ROW_MAPPER = RowMapper<AccountRecord> { rs, _ -> mapAccount(rs) }

        private fun mapAccount(rs: ResultSet): AccountRecord {
            val tag = rs.getBytes("login_tag")
            val nonce = rs.getBytes("payload_nonce")
            val ciphertext = rs.getBytes("payload_ciphertext")
            val wrappedDek = rs.getBytes("wrapped_dek")
            return try {
                AccountRecord(
                    accountId = rs.getObject("account_id", UUID::class.java),
                    loginTag = LoginTag(tag),
                    disabled = rs.getBoolean("disabled"),
                    reauthRequired = rs.getBoolean("reauth_required"),
                    credentialGeneration = rs.getLong("credential_generation"),
                    gateRevision = rs.getLong("gate_revision"),
                    pendingAttemptId = rs.getObject("pending_attempt_id", UUID::class.java),
                    envelope = EncryptedEnvelope(
                        format = rs.getShort("envelope_format"),
                        payloadNonce = nonce,
                        payloadCiphertext = ciphertext,
                        keyVersion = rs.getString("key_version"),
                        wrappedDek = wrappedDek,
                    ),
                )
            } finally {
                tag.fill(0)
                nonce.fill(0)
                ciphertext.fill(0)
                wrappedDek.fill(0)
            }
        }
    }
}
