package com.upcpocket.backend.service

import com.upcpocket.backend.contract.AccountRecord
import com.upcpocket.backend.contract.AccountState
import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.contract.AuditActionCode
import com.upcpocket.backend.contract.AuditEventRecord
import com.upcpocket.backend.contract.AuditPage
import com.upcpocket.backend.contract.AuditResultCode
import com.upcpocket.backend.contract.AuditStore
import com.upcpocket.backend.contract.BlindIndexHasher
import com.upcpocket.backend.contract.CredentialVault
import com.upcpocket.backend.contract.MasterKeyProvider
import com.upcpocket.backend.contract.KeyRotationBatchResult
import com.upcpocket.backend.contract.SessionRevocationPort
import com.upcpocket.backend.contract.UpcCasVerifier
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

data class AdminAccountSummary(
    val accountId: UUID,
    val state: AccountState,
    val credentialGeneration: Long,
    val gateRevision: Long,
)

data class AdminHealthSummary(
    val database: Boolean,
    val migrations: Boolean,
    val masterKeyProvider: Boolean,
    val casVerifier: Boolean,
)

@Service
class AdminOperationsService(
    private val accounts: AccountStore,
    private val audits: AuditStore,
    private val hasher: BlindIndexHasher,
    private val masterKeys: MasterKeyProvider,
    private val vault: CredentialVault,
    private val cas: UpcCasVerifier,
    private val revocations: SessionRevocationPort,
    private val transactions: TransactionTemplate,
    private val jdbc: JdbcTemplate,
    private val flyway: Flyway,
    private val clock: Clock,
) {
    fun lookupAccount(loginName: CharArray, actorSubject: String, requestId: UUID): AdminAccountSummary? {
        if (loginName.isEmpty() || loginName.size > MAX_LOGIN_NAME_CHARS) {
            throw ApiException(ApiErrorCode.VALIDATION_FAILED)
        }
        val tag = hasher.loginTag(loginName)
        return transaction {
            val account = accounts.findByLoginTag(tag)
            appendAudit(
                AuditActionCode.LOOKUP,
                if (account == null) AuditResultCode.DENIED else AuditResultCode.OK,
                actorSubject,
                requestId,
                targetRef = account?.let { hasher.auditRef(it.accountId.toString()) },
            )
            LookupResult(account?.toSummary())
        }.account
    }

    fun setDisabled(
        accountId: UUID,
        disabled: Boolean,
        expectedGateRevision: Long,
        actorSubject: String,
        requestId: UUID,
    ): AdminAccountSummary {
        val result = transaction {
            val current = accounts.findByAccountId(accountId) ?: throw ApiException(ApiErrorCode.NOT_FOUND)
            if (current.gateRevision != expectedGateRevision) throw ApiException(ApiErrorCode.REVISION_CHANGED)
            val updated = if (disabled) accounts.disable(accountId, expectedGateRevision)
            else accounts.restore(accountId, expectedGateRevision)
            val changed = updated ?: throw ApiException(ApiErrorCode.REVISION_CHANGED)
            appendAudit(
                if (disabled) AuditActionCode.DISABLE else AuditActionCode.RESTORE,
                AuditResultCode.OK,
                actorSubject,
                requestId,
                accountId = accountId,
            )
            changed
        }
        if (disabled) revocations.revokeAccountAll(accountId)
        return result.toSummary()
    }

    fun revokeAll(
        accountId: UUID,
        expectedGateRevision: Long,
        actorSubject: String,
        requestId: UUID,
    ) {
        transaction {
            val current = accounts.findByAccountId(accountId) ?: throw ApiException(ApiErrorCode.NOT_FOUND)
            if (current.gateRevision != expectedGateRevision) throw ApiException(ApiErrorCode.REVISION_CHANGED)
            val updated = accounts.revokeAll(accountId, expectedGateRevision)
                ?: throw ApiException(ApiErrorCode.REVISION_CHANGED)
            appendAudit(AuditActionCode.REVOKE_ALL, AuditResultCode.OK, actorSubject, requestId, updated.accountId)
            true
        }
        revocations.revokeAccountAll(accountId)
    }

    fun rotateKeys(
        targetVersion: String,
        expectedWriteVersion: String,
        actorSubject: String,
        requestId: UUID,
    ): KeyRotationBatchResult {
        return try {
            transaction {
                val current = accounts.lockVaultPolicyForWrite()?.writeKeyVersion
                if (current == null || current != expectedWriteVersion) {
                    throw ApiException(ApiErrorCode.KEY_VERSION_CONFLICT)
                }
                val configuredVersions = try {
                    masterKeys.configuredVersions()
                } catch (_: Exception) {
                    throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
                }
                if (targetVersion !in configuredVersions) throw ApiException(ApiErrorCode.KEY_VERSION_CONFLICT)
                if (!accounts.updateVaultWriteKeyVersion(expectedWriteVersion, targetVersion)) {
                    throw ApiException(ApiErrorCode.KEY_VERSION_CONFLICT)
                }

                var lastAccountId: UUID? = null
                var rewrapped = 0
                var remainingCapacity = ROTATION_BATCH_SIZE
                while (remainingCapacity > 0) {
                    val requested = remainingCapacity
                    val page = accounts.findAccountsNeedingRewrap(targetVersion, lastAccountId, requested)
                    if (page.isEmpty()) break
                    for (account in page) {
                        val oldEnvelope = account.envelope
                        val replacement = vault.rewrapDek(oldEnvelope, targetVersion)
                        if (replacement.keyVersion != targetVersion ||
                            replacement.format != oldEnvelope.format ||
                            !replacement.payloadNonce.contentEquals(oldEnvelope.payloadNonce) ||
                            !replacement.payloadCiphertext.contentEquals(oldEnvelope.payloadCiphertext)
                        ) throw IllegalStateException("Key rewrap changed encrypted payload")
                        if (accounts.updateWrappedDekIfUnchanged(
                                account.accountId,
                                account.credentialGeneration,
                                oldEnvelope.keyVersion,
                                oldEnvelope.wrappedDek,
                                targetVersion,
                                replacement.wrappedDek,
                            )
                        ) rewrapped += 1
                        lastAccountId = account.accountId
                        remainingCapacity -= 1
                    }
                    if (page.size < requested) break
                }
                val remaining = accounts.countAccountsNotUsingKeyVersion(targetVersion)
                val result = KeyRotationBatchResult(targetVersion, rewrapped, remaining, remaining == 0L)
                appendAudit(
                    AuditActionCode.ROTATE_KEY,
                    if (result.complete) AuditResultCode.OK else AuditResultCode.PARTIAL,
                    actorSubject,
                    requestId,
                )
                result
            }
        } catch (error: ApiException) {
            if (error.code == ApiErrorCode.KEY_VERSION_CONFLICT) throw error
            transaction {
                appendAudit(AuditActionCode.ROTATE_KEY, AuditResultCode.FAILED, actorSubject, requestId)
                true
            }
            throw error
        } catch (_: Exception) {
            transaction {
                appendAudit(AuditActionCode.ROTATE_KEY, AuditResultCode.FAILED, actorSubject, requestId)
                true
            }
            throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        }
    }

    fun health(): AdminHealthSummary {
        val databaseOk = try {
            jdbc.queryForObject("SELECT 1", Int::class.javaObjectType) == 1
        } catch (_: Exception) {
            false
        }
        val migrationsOk = try {
            flyway.info().current() != null
        } catch (_: Exception) {
            false
        }
        val keyProviderOk = try {
            masterKeys.healthCheck()
        } catch (_: Exception) {
            false
        }
        val casOk = try {
            cas.isConfigured()
        } catch (_: Exception) {
            false
        }
        val state = AdminHealthSummary(databaseOk, migrationsOk, keyProviderOk, casOk)
        if (!databaseOk || !migrationsOk || !keyProviderOk || !casOk) {
            throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        }
        return state
    }

    fun readAudit(cursor: String?, limit: Int, actorSubject: String, requestId: UUID): AuditPage {
        if (limit !in 1..100) throw ApiException(ApiErrorCode.VALIDATION_FAILED)
        return transaction {
            appendAudit(AuditActionCode.READ_AUDIT, AuditResultCode.OK, actorSubject, requestId)
            audits.list(cursor, limit)
        }
    }

    private fun appendAudit(
        action: AuditActionCode,
        result: AuditResultCode,
        actorSubject: String,
        requestId: UUID,
        accountId: UUID? = null,
        targetRef: ByteArray? = accountId?.let { hasher.auditRef(it.toString()) },
    ) {
        audits.append(
            AuditEventRecord(
                eventId = UUID.randomUUID(),
                occurredAt = clock.instant(),
                requestId = requestId,
                actionCode = action,
                resultCode = result,
                actorRef = hasher.auditRef(actorSubject),
                targetRef = targetRef,
            ),
        )
    }

    private fun AccountRecord.toSummary() =
        AdminAccountSummary(accountId, effectiveState(), credentialGeneration, gateRevision)

    private data class LookupResult(val account: AdminAccountSummary?)

    private fun <T : Any> transaction(block: () -> T): T =
        transactions.execute { block() } ?: throw IllegalStateException("Transaction returned no result")

    companion object {
        private const val ROTATION_BATCH_SIZE = 100
        private const val MAX_LOGIN_NAME_CHARS = 256
    }
}
