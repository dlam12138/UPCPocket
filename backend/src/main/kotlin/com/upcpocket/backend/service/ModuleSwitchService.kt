package com.upcpocket.backend.service

import com.upcpocket.backend.contract.AuditActionCode
import com.upcpocket.backend.contract.AuditEventRecord
import com.upcpocket.backend.contract.AuditResultCode
import com.upcpocket.backend.contract.AuditStore
import com.upcpocket.backend.contract.BlindIndexHasher
import com.upcpocket.backend.contract.CampusModuleKey
import com.upcpocket.backend.contract.ModuleSwitchRecord
import com.upcpocket.backend.contract.ModuleSwitchStatus
import com.upcpocket.backend.proxy.CampusModuleAdapterRegistry
import com.upcpocket.backend.proxy.ModuleCircuitBreakers
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ModuleSwitchService(
    private val jdbc: JdbcTemplate,
    private val audits: AuditStore,
    private val hasher: BlindIndexHasher,
    private val transactions: TransactionTemplate,
    private val adapters: CampusModuleAdapterRegistry,
    private val circuits: ModuleCircuitBreakers,
    private val clock: Clock,
) {
    fun status(moduleKey: String): ModuleSwitchStatus {
        val key = parseModuleKey(moduleKey)
        val row = try {
            read(key) ?: throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        } catch (_: Exception) {
            throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        }
        return row.toStatus(adapters, circuits)
    }

    fun update(
        moduleKey: String,
        enabled: Boolean,
        expectedRevision: Long,
        actorSubject: String,
        requestId: UUID,
    ): ModuleSwitchStatus {
        val key = parseModuleKey(moduleKey)
        if (expectedRevision <= 0) throw ApiException(ApiErrorCode.VALIDATION_FAILED)
        try {
            val updated = transactions.execute {
                val current = read(key) ?: throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
                if (current.revision != expectedRevision) throw ApiException(ApiErrorCode.REVISION_CHANGED)
                val nextRevision = try {
                    Math.addExact(current.revision, 1L)
                } catch (_: ArithmeticException) {
                    throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
                }
                val changed = jdbc.update(
                    """
                    UPDATE pocket_module_switch
                    SET enabled = ?, revision = ?, updated_at = ?
                    WHERE module_key = ? AND revision = ?
                    """.trimIndent(),
                    enabled,
                    nextRevision,
                    OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC),
                    key.wireName,
                    current.revision,
                )
                if (changed != 1) throw ApiException(ApiErrorCode.REVISION_CHANGED)

                val eventId = UUID.randomUUID()
                val reference = hasher.auditRef(actorSubject)
                try {
                    audits.append(
                        AuditEventRecord(
                            eventId = eventId,
                            occurredAt = clock.instant(),
                            requestId = requestId,
                            actionCode = if (enabled) AuditActionCode.RESTORE else AuditActionCode.DISABLE,
                            resultCode = AuditResultCode.OK,
                            actorRef = reference,
                            targetRef = null,
                        ),
                    )
                } finally {
                    reference.fill(0)
                }
                jdbc.update(
                    """
                    INSERT INTO pocket_audit.module_switch_detail (
                        event_id, module_key, previous_enabled, new_enabled, revision
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    eventId,
                    key.wireName,
                    current.enabled,
                    enabled,
                    nextRevision,
                )
                ModuleSwitchRecord(key, enabled, nextRevision)
            } ?: throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
            return updated.toStatus(adapters, circuits)
        } catch (error: ApiException) {
            throw error
        } catch (_: Exception) {
            // Audit and state writes share this transaction. Any failure aborts the switch.
            throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        }
    }

    private fun read(key: CampusModuleKey): ModuleSwitchRecord? = jdbc.query(
        "SELECT module_key, enabled, revision FROM pocket_module_switch WHERE module_key = ?",
        { rs: ResultSet, _ ->
            ModuleSwitchRecord(
                CampusModuleKey.parse(rs.getString("module_key")) ?: throw IllegalStateException("Unknown module key"),
                rs.getBoolean("enabled"),
                rs.getLong("revision"),
            )
        },
        key.wireName,
    ).firstOrNull()

    private fun ModuleSwitchRecord.toStatus(
        adapters: CampusModuleAdapterRegistry,
        circuits: ModuleCircuitBreakers,
    ) = ModuleSwitchStatus(moduleKey.wireName, enabled, revision, adapters.ready(moduleKey), circuits.state(moduleKey))

    private fun parseModuleKey(value: String): CampusModuleKey =
        CampusModuleKey.parse(value) ?: throw ApiException(ApiErrorCode.VALIDATION_FAILED)
}
