package com.upcpocket.backend.storage

import com.upcpocket.backend.contract.AuditActionCode
import com.upcpocket.backend.contract.AuditEventRecord
import com.upcpocket.backend.contract.AuditResultCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class JdbcAuditStoreTest {
    private lateinit var database: JdbcTestDatabase

    @BeforeEach
    fun setUp() {
        database = JdbcTestDatabase()
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun `audit cursor provides stable url safe pagination`() {
        val time = Instant.parse("2026-01-02T03:04:05.123456Z")
        (1L..4L).forEach { id -> database.audits.append(event(id, time)) }

        val first = database.audits.list(null, 2)
        assertEquals(listOf(UUID(0, 1), UUID(0, 2)), first.events.map { it.eventId })
        assertNotNull(first.nextCursor)
        assertTrue(first.nextCursor!!.matches(Regex("[A-Za-z0-9_-]+")))

        val second = database.audits.list(first.nextCursor, 2)
        assertEquals(listOf(UUID(0, 3), UUID(0, 4)), second.events.map { it.eventId })
        assertEquals(null, second.nextCursor)
        assertEquals(1, database.audits.list(first.nextCursor, 0).events.size)
        assertThrows(IllegalArgumentException::class.java) { database.audits.list("not a cursor", 10) }
    }

    @Test
    fun `audit append and account mutation roll back in the same transaction`() {
        val account = JdbcTestDatabase.sampleAccount(id = UUID(0, 50), seed = 50)
        assertTrue(database.accounts.insertFirstBinding(account))
        assertThrows(IllegalStateException::class.java) {
            database.transactionTemplate.executeWithoutResult {
                assertNotNull(database.accounts.revokeAll(account.accountId, account.gateRevision))
                database.audits.append(event(99, Instant.parse("2026-01-02T03:04:05Z")))
                throw IllegalStateException("rollback")
            }
        }

        assertEquals(account.gateRevision, database.accounts.findByAccountId(account.accountId)?.gateRevision)
        assertTrue(database.audits.list(null, 100).events.isEmpty())
    }

    @Test
    fun `audit page limit caps at one hundred`() {
        val time = Instant.parse("2026-02-03T04:05:06Z")
        (1L..101L).forEach { id -> database.audits.append(event(id, time)) }
        val page = database.audits.list(null, 1_000)
        assertEquals(100, page.events.size)
        assertNotNull(page.nextCursor)
        assertFalse(database.audits.list(page.nextCursor, 100).events.isEmpty())
    }

    private fun event(id: Long, time: Instant) = AuditEventRecord(
        eventId = UUID(0, id),
        occurredAt = time,
        requestId = UUID(1, id),
        actionCode = AuditActionCode.LOOKUP,
        resultCode = AuditResultCode.OK,
        actorRef = ByteArray(32) { 1 },
        targetRef = ByteArray(32) { 2 },
    )
}
