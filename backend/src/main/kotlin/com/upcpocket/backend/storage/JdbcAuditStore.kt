package com.upcpocket.backend.storage

import com.upcpocket.backend.contract.AuditActionCode
import com.upcpocket.backend.contract.AuditEventRecord
import com.upcpocket.backend.contract.AuditPage
import com.upcpocket.backend.contract.AuditResultCode
import com.upcpocket.backend.contract.AuditStore
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class JdbcAuditStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : AuditStore {
    override fun append(event: AuditEventRecord) {
        val actorRef = event.actorRef
        val targetRef = event.targetRef
        try {
            jdbc.update(
                """
                INSERT INTO pocket_audit.event (
                    event_id, occurred_at, request_id, action_code, result_code, actor_ref, target_ref
                ) VALUES (
                    :eventId, :occurredAt, :requestId, :actionCode, :resultCode, :actorRef, :targetRef
                )
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("eventId", event.eventId, Types.OTHER)
                    .addValue("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("requestId", event.requestId, Types.OTHER)
                    .addValue("actionCode", event.actionCode.name)
                    .addValue("resultCode", event.resultCode.name)
                    .addValue("actorRef", actorRef)
                    .addValue("targetRef", targetRef),
            )
        } finally {
            actorRef?.fill(0)
            targetRef?.fill(0)
        }
    }

    override fun list(cursor: String?, limit: Int): AuditPage {
        val pageSize = limit.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val position = cursor?.let(::decodeCursor)
        val parameters = MapSqlParameterSource().addValue("limit", pageSize + 1)
        val sql = if (position == null) {
            "$SELECT_EVENTS ORDER BY occurred_at ASC, event_id ASC LIMIT :limit"
        } else {
            parameters
                .addValue("occurredAt", position.occurredAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("eventId", position.eventId, Types.OTHER)
            "$SELECT_EVENTS WHERE occurred_at > :occurredAt " +
                "OR (occurred_at = :occurredAt AND event_id > :eventId) " +
                "ORDER BY occurred_at ASC, event_id ASC LIMIT :limit"
        }
        val rows = jdbc.query(sql, parameters, EVENT_ROW_MAPPER)
        val hasMore = rows.size > pageSize
        val pageEvents = rows.take(pageSize)
        val nextCursor = if (hasMore) pageEvents.lastOrNull()?.let(::encodeCursor) else null
        return AuditPage(pageEvents, nextCursor)
    }

    private fun encodeCursor(event: AuditEventRecord): String {
        val bytes = ByteBuffer.allocate(CURSOR_BYTE_LENGTH)
            .putLong(event.occurredAt.epochSecond)
            .putInt(event.occurredAt.nano)
            .putLong(event.eventId.mostSignificantBits)
            .putLong(event.eventId.leastSignificantBits)
            .array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun decodeCursor(cursor: String): Cursor {
        if (cursor.isEmpty() || cursor.length > MAX_CURSOR_LENGTH || cursor.any { !it.isBase64UrlCharacter() }) {
            throw IllegalArgumentException("Invalid audit cursor")
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(cursor)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid audit cursor")
        }
        if (bytes.size != CURSOR_BYTE_LENGTH || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != cursor) {
            bytes.fill(0)
            throw IllegalArgumentException("Invalid audit cursor")
        }
        return try {
            val input = ByteBuffer.wrap(bytes)
            val seconds = input.long
            val nanos = input.int
            require(nanos in 0..999_999_999) { "Invalid audit cursor" }
            Cursor(
                occurredAt = Instant.ofEpochSecond(seconds, nanos.toLong()),
                eventId = UUID(input.long, input.long),
            )
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Invalid audit cursor")
        } finally {
            bytes.fill(0)
        }
    }

    private fun Char.isBase64UrlCharacter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'

    private data class Cursor(val occurredAt: Instant, val eventId: UUID)

    companion object {
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 100
        private const val CURSOR_BYTE_LENGTH = 28
        private const val MAX_CURSOR_LENGTH = 64

        private val SELECT_EVENTS = """
            SELECT event_id, occurred_at, request_id, action_code, result_code, actor_ref, target_ref
            FROM pocket_audit.event
        """.trimIndent()

        private val EVENT_ROW_MAPPER = RowMapper<AuditEventRecord> { rs, _ -> mapEvent(rs) }

        private fun mapEvent(rs: ResultSet): AuditEventRecord {
            val actorRef = rs.getBytes("actor_ref")
            val targetRef = rs.getBytes("target_ref")
            return try {
                AuditEventRecord(
                    eventId = rs.getObject("event_id", UUID::class.java),
                    occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                    requestId = rs.getObject("request_id", UUID::class.java),
                    actionCode = AuditActionCode.valueOf(rs.getString("action_code")),
                    resultCode = AuditResultCode.valueOf(rs.getString("result_code")),
                    actorRef = actorRef,
                    targetRef = targetRef,
                )
            } finally {
                actorRef?.fill(0)
                targetRef?.fill(0)
            }
        }
    }
}
