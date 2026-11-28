package com.upcpocket.backend.contract

import java.time.Instant
import java.util.UUID

/** Audit references are fixed-width blind references, not raw account or subject values. */
class AuditEventRecord(
    val eventId: UUID,
    val occurredAt: Instant,
    val requestId: UUID,
    val actionCode: AuditActionCode,
    val resultCode: AuditResultCode,
    actorRef: ByteArray?,
    targetRef: ByteArray?,
) {
    private val actorValue = actorRef?.copyOf()
    private val targetValue = targetRef?.copyOf()

    val actorRef: ByteArray?
        get() = actorValue?.copyOf()

    val targetRef: ByteArray?
        get() = targetValue?.copyOf()

    init {
        require(actorValue == null || actorValue.size == LoginTag.BYTE_LENGTH) {
            "Actor reference must be null or ${LoginTag.BYTE_LENGTH} bytes"
        }
        require(targetValue == null || targetValue.size == LoginTag.BYTE_LENGTH) {
            "Target reference must be null or ${LoginTag.BYTE_LENGTH} bytes"
        }
    }

    override fun toString(): String = "AuditEventRecord([REDACTED])"
}

class AuditPage(events: List<AuditEventRecord>, val nextCursor: String?) {
    val events: List<AuditEventRecord> = events.toList()

    override fun toString(): String = "AuditPage(eventCount=${events.size}, hasNext=${nextCursor != null})"
}
