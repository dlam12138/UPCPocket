package com.upcpocket.backend.acceptance

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.upcpocket.backend.PocketBackendApplication
import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.contract.BlindIndexHasher
import com.upcpocket.backend.contract.BusinessSessionKey
import com.upcpocket.backend.contract.CredentialVault
import com.upcpocket.backend.contract.AccountState
import com.upcpocket.backend.http.SanitizedAccessLogFilter
import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.service.ApiException
import com.upcpocket.backend.service.StudentAccountService
import com.upcpocket.backend.session.BusinessLoginResult
import com.upcpocket.backend.session.InMemorySessionRegistry
import com.upcpocket.backend.support.FakeCasOutcome
import com.upcpocket.backend.support.FakeUpcCasConfiguration
import com.upcpocket.backend.support.FakeUpcCasVerifier
import com.upcpocket.backend.support.TestAdminJwtSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [PocketBackendApplication::class],
    properties = [
        "pocket.vault.master-keys=test-key-1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=,test-key-2:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "pocket.vault.login-index-key=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
        "pocket.vault.audit-index-key=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=",
        "pocket.vault.initial-write-key-version=test-key-1",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeUpcCasConfiguration::class, TestAdminJwtSupport.Configuration::class)
class BackendAcceptanceTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var accountStore: AccountStore
    @Autowired private lateinit var hasher: BlindIndexHasher
    @Autowired private lateinit var vault: CredentialVault
    @Autowired private lateinit var fakeCas: FakeUpcCasVerifier
    @Autowired private lateinit var student: StudentAccountService
    @Autowired private lateinit var sessions: InMemorySessionRegistry
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired
    @Qualifier("orphanedPendingAttemptRecovery")
    private lateinit var orphanedPendingAttemptRecovery: SmartInitializingSingleton

    @BeforeEach
    fun resetRowsAndCasOutcomes() {
        jdbc.update("DELETE FROM pocket_audit.event")
        jdbc.update("DELETE FROM pocket_account")
        jdbc.update("DELETE FROM vault_policy")
        fakeCas.reset()
    }

    @Test
    fun `first bind and same-password sign in on another device keep generation unchanged`() {
        val first = bind("student-a", "school-pass-a")
        val second = signIn("student-a", "school-pass-a", UUID.randomUUID())

        val account = accountStore.findByAccountId(first.accountId)
        assertNotNull(account)
        assertEquals(1, account!!.credentialGeneration)
        assertEquals(2, sessions.devices(first.accountId).size)
        assertEquals(200, getMe(first.token).andReturn().response.status)
        assertEquals(200, getMe(second.token).andReturn().response.status)
    }

    @Test
    fun `first-bind CAS failures preserve their status and do not create an account`() {
        val cases = listOf(
            Triple(FakeCasOutcome.INVALID_CREDENTIALS, 401, "SIGN_IN_FAILED"),
            Triple(FakeCasOutcome.INTERACTIVE_CHALLENGE, 409, "INTERACTIVE_CHALLENGE"),
            Triple(FakeCasOutcome.UPSTREAM_UNAVAILABLE, 503, "UPSTREAM_UNAVAILABLE"),
        )
        cases.forEachIndexed { index, (outcome, expectedStatus, expectedCode) ->
            val loginName = "student-first-failure-$index"
            fakeCas.enqueue(outcome)
            mockMvc.perform(
                authPost("/api/v1/auth/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signInJson(loginName, "first-failure-pass-$index", UUID.randomUUID())),
            ).andExpect(status().`is`(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
            assertNull(accountStore.findByLoginTag(hasher.loginTag(loginName.toCharArray())))
        }
    }

    @Test
    fun `session responses match for active unknown and disabled accounts across CAS failures`() {
        val cases = listOf(
            Triple(FakeCasOutcome.UPSTREAM_UNAVAILABLE, 503, "UPSTREAM_UNAVAILABLE"),
            Triple(FakeCasOutcome.INTERACTIVE_CHALLENGE, 409, "INTERACTIVE_CHALLENGE"),
            Triple(FakeCasOutcome.INVALID_CREDENTIALS, 401, "SIGN_IN_FAILED"),
        )
        cases.forEachIndexed { index, (outcome, expectedStatus, expectedCode) ->
            val activeLoginName = "student-session-active-$index"
            bind(activeLoginName, "session-active-pass-$index")
            val disabledLoginName = "student-session-disabled-$index"
            val disabled = bind(disabledLoginName, "session-disabled-pass-$index")
            val disabledBefore = accountStore.findByAccountId(disabled.accountId)!!
            mockMvc.perform(
                put("/internal/admin/v1/accounts/{id}/disabled", disabled.accountId)
                    .header("Authorization", bearer(TestAdminJwtSupport.token("account.manage")))
                    .header("If-Match", disabledBefore.gateRevision.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"disabled":true}"""),
            ).andExpect(status().isOk)

            val targets = listOf(
                activeLoginName to "session-active-pass-$index",
                "unknown-session-$index" to "unknown-session-pass-$index",
                disabledLoginName to "session-disabled-pass-$index",
            )
            targets.forEach { (loginName, password) ->
                fakeCas.enqueue(outcome)
                mockMvc.perform(
                    authPost("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signInJson(loginName, password, UUID.randomUUID())),
                ).andExpect(status().`is`(expectedStatus))
                    .andExpect(jsonPath("$.code").value(expectedCode))
            }

            assertNull(accountStore.findByLoginTag(hasher.loginTag("unknown-session-$index".toCharArray())))
            val disabledAfter = accountStore.findByAccountId(disabled.accountId)!!
            assertTrue(disabledAfter.disabled)
            assertFalse(disabledAfter.reauthRequired)
            assertEquals(disabledBefore.credentialGeneration, disabledAfter.credentialGeneration)
            assertNull(disabledAfter.pendingAttemptId)
            assertTrue(sessions.devices(disabled.accountId).isEmpty())
        }

        val verifiedDisabled = bind("student-disabled-verified", "disabled-verified-pass")
        val beforeVerified = accountStore.findByAccountId(verifiedDisabled.accountId)!!
        mockMvc.perform(
            put("/internal/admin/v1/accounts/{id}/disabled", verifiedDisabled.accountId)
                .header("Authorization", bearer(TestAdminJwtSupport.token("account.manage")))
                .header("If-Match", beforeVerified.gateRevision.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"disabled":true}"""),
        ).andExpect(status().isOk)
        val disabledSnapshot = accountStore.findByAccountId(verifiedDisabled.accountId)!!
        fakeCas.enqueue(FakeCasOutcome.VERIFIED)
        mockMvc.perform(
            authPost("/api/v1/auth/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signInJson("student-disabled-verified", "disabled-verified-pass", UUID.randomUUID())),
        ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("SIGN_IN_FAILED"))
        val afterVerified = accountStore.findByAccountId(verifiedDisabled.accountId)!!
        assertTrue(afterVerified.disabled)
        assertEquals(disabledSnapshot.credentialGeneration, afterVerified.credentialGeneration)
        assertEquals(disabledSnapshot.gateRevision, afterVerified.gateRevision)
        assertNull(afterVerified.pendingAttemptId)
        assertTrue(sessions.devices(verifiedDisabled.accountId).isEmpty())
    }

    @Test
    fun `kicking one device keeps the other usable and a late login response cannot repopulate shared session`() {
        val first = bind("student-b", "school-pass-b")
        val second = signIn("student-b", "school-pass-b", UUID.randomUUID())
        mockMvc.perform(delete("/api/v1/me/devices/{deviceId}", first.deviceId).header("Authorization", bearer(second.token)))
            .andExpect(status().isNoContent)
        assertEquals(401, getMe(first.token).andReturn().response.status)
        assertEquals(200, getMe(second.token).andReturn().response.status)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                student.executeAutoBusinessLogin(first.accountId, UUID.fromString(second.deviceId), "card", UUID.randomUUID()) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    BusinessLoginResult(byteArrayOf(4, 5, 6), null)
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(sessions.revokeDevice(first.accountId, UUID.fromString(second.deviceId)))
            release.countDown()
            assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
            val key = BusinessSessionKey(first.accountId, 1, "card")
            assertNull(sessions.getBusinessSession(key))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `one invalid automatic credential trips reauth and prevents later CAS calls`() {
        val issued = bind("student-c", "school-pass-c")
        fakeCas.reset()
        fakeCas.enqueue(FakeCasOutcome.INVALID_CREDENTIALS)

        val firstError = assertThrows(ApiException::class.java) {
            student.executeAutoBusinessLogin(issued.accountId, UUID.fromString(issued.deviceId), "card", UUID.randomUUID()) {
                error("business login must not run after invalid CAS credentials")
            }
        }
        assertEquals(ApiErrorCode.SIGN_IN_FAILED, firstError.code)
        assertEquals(1, fakeCas.calls.get())
        assertEquals(401, getMe(issued.token).andReturn().response.status)
        val secondError = assertThrows(ApiException::class.java) {
            student.executeAutoBusinessLogin(issued.accountId, UUID.fromString(issued.deviceId), "card", UUID.randomUUID()) {
                error("reauth-required account must not invoke business login")
            }
        }
        assertEquals(ApiErrorCode.SIGN_IN_FAILED, secondError.code)
        assertEquals(1, fakeCas.calls.get())
        assertTrue(accountStore.findByAccountId(issued.accountId)!!.reauthRequired)
    }

    @Test
    fun `recreated session registry does not accept a token from the prior process`() {
        val issued = bind("student-restart", "school-pass-restart")
        val restarted = InMemorySessionRegistry(Clock.systemUTC())

        assertNull(restarted.authenticate(issued.token))
    }

    @Test
    fun `challenge and upstream failure clear attempt without changing active state`() {
        val issued = bind("student-d", "school-pass-d")
        for ((outcome, expectedStatus, expectedCode) in listOf(
            Triple(FakeCasOutcome.INTERACTIVE_CHALLENGE, 409, "INTERACTIVE_CHALLENGE"),
            Triple(FakeCasOutcome.UPSTREAM_UNAVAILABLE, 503, "UPSTREAM_UNAVAILABLE"),
        )) {
            fakeCas.enqueue(outcome)
            mockMvc.perform(
                authPost("/api/v1/auth/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signInJson("student-d", "school-pass-d", UUID.randomUUID())),
            )
                .andExpect(status().`is`(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
            val account = accountStore.findByAccountId(issued.accountId)!!
            assertFalse(account.reauthRequired)
            assertNull(account.pendingAttemptId)
        }
    }

    @Test
    fun `manual credential update rotates generation and invalidates every issued device token`() {
        val first = bind("student-e", "school-pass-e")
        val second = signIn("student-e", "school-pass-e", UUID.randomUUID())

        mockMvc.perform(
            authPost("/api/v1/auth/credential-updates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginName":"student-e","password":"new-school-pass-e"}"""),
        ).andExpect(status().isNoContent)

        assertEquals(401, getMe(first.token).andReturn().response.status)
        assertEquals(401, getMe(second.token).andReturn().response.status)
        val account = accountStore.findByLoginTag(hasher.loginTag("student-e".toCharArray()))!!
        assertEquals(2, account.credentialGeneration)
        assertTrue(vault.matchesStoredCredential(account, "student-e".toCharArray(), "new-school-pass-e".toCharArray()))
        assertFalse(vault.matchesStoredCredential(account, "student-e".toCharArray(), "school-pass-e".toCharArray()))
    }

    @Test
    fun `manual credential update recovers an account after one wrong password trip`() {
        val issued = bind("student-recovery", "school-pass-recovery")
        fakeCas.enqueue(FakeCasOutcome.INVALID_CREDENTIALS)
        mockMvc.perform(
            authPost("/api/v1/auth/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signInJson("student-recovery", "wrong-pass", UUID.randomUUID())),
        ).andExpect(status().isUnauthorized)

        mockMvc.perform(
            authPost("/api/v1/auth/credential-updates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginName":"student-recovery","password":"recovered-school-pass"}"""),
        ).andExpect(status().isNoContent)
        val account = accountStore.findByAccountId(issued.accountId)!!
        assertFalse(account.reauthRequired)
        assertEquals(2, account.credentialGeneration)
        assertEquals(401, getMe(issued.token).andReturn().response.status)
    }

    @Test
    fun `startup recovery blocks auto login and manual credential update recovers orphaned attempts`() {
        val issued = bind("student-orphan", "school-pass-orphan")
        val account = accountStore.findByAccountId(issued.accountId)!!
        val orphanedAttemptId = UUID.randomUUID()
        assertTrue(transactions.execute {
            accountStore.registerPendingAttempt(
                account.accountId,
                account.credentialGeneration,
                account.gateRevision,
                orphanedAttemptId,
            )
        } == true)

        orphanedPendingAttemptRecovery.afterSingletonsInstantiated()
        val recoveredState = accountStore.findByAccountId(issued.accountId)!!
        assertTrue(recoveredState.reauthRequired)
        assertNull(recoveredState.pendingAttemptId)
        assertEquals(AccountState.REAUTH_REQUIRED, recoveredState.effectiveState())

        fakeCas.reset()
        assertThrows(ApiException::class.java) {
            student.executeAutoBusinessLogin(
                issued.accountId,
                UUID.fromString(issued.deviceId),
                "card",
                UUID.randomUUID(),
            ) { error("orphaned pending attempt must stop automatic login") }
        }
        assertEquals(0, fakeCas.calls.get())

        mockMvc.perform(
            authPost("/api/v1/auth/credential-updates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginName":"student-orphan","password":"recovered-orphan-pass"}"""),
        ).andExpect(status().isNoContent)
        val updated = accountStore.findByAccountId(issued.accountId)!!
        assertFalse(updated.reauthRequired)
        assertNull(updated.pendingAttemptId)
        assertEquals(2, updated.credentialGeneration)
    }

    @Test
    fun `manual credential update accepts a persisted orphan pending attempt`() {
        val issued = bind("student-pending-recovery", "school-pass-pending-recovery")
        val account = accountStore.findByAccountId(issued.accountId)!!
        val orphanedAttemptId = UUID.randomUUID()
        assertTrue(transactions.execute {
            accountStore.registerPendingAttempt(
                account.accountId,
                account.credentialGeneration,
                account.gateRevision,
                orphanedAttemptId,
            )
        } == true)
        assertEquals(orphanedAttemptId, accountStore.findByAccountId(issued.accountId)!!.pendingAttemptId)

        mockMvc.perform(
            authPost("/api/v1/auth/credential-updates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginName":"student-pending-recovery","password":"new-pending-recovery-pass"}"""),
        ).andExpect(status().isNoContent)

        val updated = accountStore.findByAccountId(issued.accountId)!!
        assertNull(updated.pendingAttemptId)
        assertFalse(updated.reauthRequired)
        assertEquals(2, updated.credentialGeneration)
        assertEquals(401, getMe(issued.token).andReturn().response.status)
    }

    @Test
    fun `credential update responses match for active unknown and disabled accounts`() {
        val cases = listOf(
            Triple(FakeCasOutcome.UPSTREAM_UNAVAILABLE, 503, "UPSTREAM_UNAVAILABLE"),
            Triple(FakeCasOutcome.INTERACTIVE_CHALLENGE, 409, "INTERACTIVE_CHALLENGE"),
            Triple(FakeCasOutcome.INVALID_CREDENTIALS, 401, "SIGN_IN_FAILED"),
        )
        cases.forEachIndexed { index, (outcome, expectedStatus, expectedCode) ->
            val active = bind("student-response-$index", "school-pass-response-$index")
            val disabled = bind("student-disabled-response-$index", "school-pass-disabled-response-$index")
            val disabledAccount = accountStore.findByAccountId(disabled.accountId)!!
            mockMvc.perform(
                put("/internal/admin/v1/accounts/{id}/disabled", disabled.accountId)
                    .header("Authorization", bearer(TestAdminJwtSupport.token("account.manage")))
                    .header("If-Match", disabledAccount.gateRevision.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"disabled":true}"""),
            ).andExpect(status().isOk)

            val targets = listOf(
                "student-response-$index" to "school-pass-response-$index",
                "unknown-response-$index" to "arbitrary-pass-$index",
                "student-disabled-response-$index" to "school-pass-disabled-response-$index",
            )
            targets.forEach { (loginName, password) ->
                fakeCas.enqueue(outcome)
                mockMvc.perform(
                    authPost("/api/v1/auth/credential-updates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapOf("loginName" to loginName, "password" to password))),
                ).andExpect(status().`is`(expectedStatus))
                    .andExpect(jsonPath("$.code").value(expectedCode))
            }
            assertEquals(
                if (outcome == FakeCasOutcome.INVALID_CREDENTIALS) AccountState.REAUTH_REQUIRED else AccountState.ACTIVE,
                accountStore.findByAccountId(active.accountId)!!.effectiveState(),
            )
        }

        val disabledDuringOutage = bind("student-cas-disabled", "school-pass-cas-disabled")
        val disabledDuringOutageAccount = accountStore.findByAccountId(disabledDuringOutage.accountId)!!
        mockMvc.perform(
            put("/internal/admin/v1/accounts/{id}/disabled", disabledDuringOutage.accountId)
                .header("Authorization", bearer(TestAdminJwtSupport.token("account.manage")))
                .header("If-Match", disabledDuringOutageAccount.gateRevision.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"disabled":true}"""),
        ).andExpect(status().isOk)
        fakeCas.configured = false
        listOf("student-response-2", "student-cas-disabled", "unknown-cas-down").forEach { loginName ->
            mockMvc.perform(
                authPost("/api/v1/auth/credential-updates")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("loginName" to loginName, "password" to "test-pass"))),
            ).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
        }
    }

    @Test
    fun `admin disable immediately invalidates an active student token`() {
        val issued = bind("student-disabled", "school-pass-disabled")
        val account = accountStore.findByAccountId(issued.accountId)!!
        val disabled = mockMvc.perform(
            put("/internal/admin/v1/accounts/{id}/disabled", account.accountId)
                .header("Authorization", bearer(TestAdminJwtSupport.token("account.manage")))
                .header("If-Match", account.gateRevision.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"disabled":true}"""),
        ).andExpect(status().isOk).andReturn()

        assertEquals("DISABLED", mapper.readTree(disabled.response.contentAsString)["state"].asText())
        assertEquals(401, getMe(issued.token).andReturn().response.status)
    }

    @Test
    fun `restoring a disabled account preserves its reauth required state`() {
        bind("student-f", "school-pass-f")
        fakeCas.enqueue(FakeCasOutcome.INVALID_CREDENTIALS)
        mockMvc.perform(
            authPost("/api/v1/auth/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signInJson("student-f", "wrong-pass", UUID.randomUUID())),
        ).andExpect(status().isUnauthorized)
        val account = accountStore.findByLoginTag(hasher.loginTag("student-f".toCharArray()))!!
        assertTrue(account.reauthRequired)
        val admin = TestAdminJwtSupport.token("account.manage")

        val disabled = mockMvc.perform(
            put("/internal/admin/v1/accounts/{id}/disabled", account.accountId)
                .header("Authorization", bearer(admin))
                .header("If-Match", account.gateRevision.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"disabled":true}"""),
        ).andExpect(status().isOk).andReturn()
        val disabledJson = mapper.readTree(disabled.response.contentAsString)
        assertEquals("DISABLED", disabledJson["state"].asText())

        val restored = mockMvc.perform(
            put("/internal/admin/v1/accounts/{id}/disabled", account.accountId)
                .header("Authorization", bearer(admin))
                .header("If-Match", disabledJson["gateRevision"].asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"disabled":false}"""),
        ).andExpect(status().isOk).andReturn()
        assertEquals("REAUTH_REQUIRED", mapper.readTree(restored.response.contentAsString)["state"].asText())
        assertTrue(accountStore.findByAccountId(account.accountId)!!.reauthRequired)
    }

    @Test
    fun `unbind deletes encrypted account row and records audit event`() {
        val issued = bind("student-g", "school-pass-g")
        mockMvc.perform(delete("/api/v1/me").header("Authorization", bearer(issued.token)))
            .andExpect(status().isNoContent)

        assertNull(accountStore.findByAccountId(issued.accountId))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pocket_account WHERE account_id = ?", Int::class.javaObjectType, issued.accountId))
        val loginTag = hasher.loginTag("student-g".toCharArray()).copyBytes()
        try {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pocket_account WHERE login_tag = ?", Int::class.javaObjectType, loginTag))
        } finally {
            loginTag.fill(0)
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pocket_audit.event WHERE action_code = 'UNBIND'", Int::class.javaObjectType))
    }

    @Test
    fun `key rotation keeps payload bytes and wraps with the new key`() {
        val issued = bind("student-h", "school-pass-h")
        val before = accountStore.findByAccountId(issued.accountId)!!
        val response = mockMvc.perform(
            post("/internal/admin/v1/key-rotations")
                .header("Authorization", bearer(TestAdminJwtSupport.token("key.rotate")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetVersion":"test-key-2","expectedWriteVersion":"test-key-1"}"""),
        ).andExpect(status().isOk).andReturn()
        val progress = mapper.readTree(response.response.contentAsString)
        assertTrue(progress["complete"].asBoolean())
        assertEquals(1, progress["rewrapped"].asInt())

        val after = accountStore.findByAccountId(issued.accountId)!!
        assertEquals("test-key-2", after.envelope.keyVersion)
        assertTrue(before.envelope.payloadNonce.contentEquals(after.envelope.payloadNonce))
        assertTrue(before.envelope.payloadCiphertext.contentEquals(after.envelope.payloadCiphertext))
        assertTrue(vault.matchesStoredCredential(after, "student-h".toCharArray(), "school-pass-h".toCharArray()))
    }

    @Test
    fun `pocket bearer is rejected by admin chain and private responses are no-store with sanitized logs`() {
        val logger = LoggerFactory.getLogger(SanitizedAccessLogFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            fakeCas.enqueue(FakeCasOutcome.INVALID_CREDENTIALS)
            mockMvc.perform(
                authPost("/api/v1/auth/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(signInJson("log-failure-user", "log-failure-password", UUID.randomUUID())),
            ).andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))

            val issued = bind("log-check-user", "log-check-password")
            mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(issued.token)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
            mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            mockMvc.perform(
                get("/internal/admin/v1/health").header("Authorization", bearer(issued.token)),
            ).andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))

            val entries = appender.list.map(ILoggingEvent::getFormattedMessage)
            assertTrue(entries.isNotEmpty())
            assertTrue(entries.all { it.contains("requestId=") && it.contains("result=") && it.contains("elapsedMs=") })
            val joined = entries.joinToString(" ")
            assertFalse(joined.contains("log-check-user"))
            assertFalse(joined.contains("log-check-password"))
            assertFalse(joined.contains(issued.token))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `admin JWT requires MFA and exact route scope while valid scoped health passes`() {
        val missingMfa = TestAdminJwtSupport.token("health.read", mfa = false)
        mockMvc.perform(get("/internal/admin/v1/health").header("Authorization", bearer(missingMfa)))
            .andExpect(status().isUnauthorized)
        val wrongScope = TestAdminJwtSupport.token("account.read")
        mockMvc.perform(get("/internal/admin/v1/health").header("Authorization", bearer(wrongScope)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            get("/internal/admin/v1/health")
                .header("Authorization", bearer(TestAdminJwtSupport.token("health.read"))),
        ).andExpect(status().isOk)
    }

    private fun authPost(path: String) = post(path).with { request ->
        request.remoteAddr = "test-${UUID.randomUUID()}"
        request
    }

    private fun bind(loginName: String, password: String): Issued {
        val result = mockMvc.perform(
            authPost("/api/v1/auth/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signInJson(loginName, password, UUID.randomUUID())),
        ).andExpect(status().isOk).andReturn()
        val body = mapper.readTree(result.response.contentAsString)
        val token = body["token"].asText()
        val deviceId = body["deviceId"].asText()
        val account = accountStore.findByLoginTag(hasher.loginTag(loginName.toCharArray()))!!
        return Issued(token, deviceId, account.accountId)
    }

    private fun signIn(loginName: String, password: String, installationId: UUID): Issued {
        val result = mockMvc.perform(
            authPost("/api/v1/auth/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signInJson(loginName, password, installationId)),
        ).andExpect(status().isOk).andReturn()
        val body = mapper.readTree(result.response.contentAsString)
        val account = accountStore.findByLoginTag(hasher.loginTag(loginName.toCharArray()))!!
        return Issued(body["token"].asText(), body["deviceId"].asText(), account.accountId)
    }

    private fun getMe(token: String) =
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(token)))

    private fun signInJson(loginName: String, password: String, installationId: UUID): String =
        mapper.writeValueAsString(
            mapOf(
                "loginName" to loginName,
                "password" to password,
                "acceptCustody" to true,
                "installationId" to installationId,
                "platform" to "ANDROID",
                "kind" to "PHONE",
            ),
        )

    private fun bearer(token: String) = "Bearer $token"

    private data class Issued(val token: String, val deviceId: String, val accountId: UUID)
}
