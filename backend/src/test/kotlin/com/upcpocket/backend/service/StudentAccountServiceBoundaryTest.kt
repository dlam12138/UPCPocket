package com.upcpocket.backend.service

import com.upcpocket.backend.contract.AccountStore
import com.upcpocket.backend.contract.AuditActionCode
import com.upcpocket.backend.contract.AuditPage
import com.upcpocket.backend.contract.AuditEventRecord
import com.upcpocket.backend.contract.AuditStore
import com.upcpocket.backend.contract.BlindIndexHasher
import com.upcpocket.backend.contract.CredentialLease
import com.upcpocket.backend.contract.CredentialUsePurpose
import com.upcpocket.backend.contract.CredentialVault
import com.upcpocket.backend.contract.EncryptedEnvelope
import com.upcpocket.backend.contract.MasterKeyProvider
import com.upcpocket.backend.contract.SessionRevocationPort
import com.upcpocket.backend.contract.UpcCasVerifier
import com.upcpocket.backend.contract.CasVerificationResult
import com.upcpocket.backend.contract.LoginTag
import com.upcpocket.backend.security.InMemoryAuthRateLimiter
import com.upcpocket.backend.session.InMemorySessionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import com.upcpocket.backend.storage.JdbcTestDatabase
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StudentAccountServiceBoundaryTest {
    @Test
    fun `custody acceptance is required before account lookup or CAS`() {
        val accounts = Mockito.mock(AccountStore::class.java)
        val cas = Mockito.mock(UpcCasVerifier::class.java)
        val service = StudentAccountService(
            accounts = accounts,
            audits = Mockito.mock(AuditStore::class.java),
            hasher = Mockito.mock(BlindIndexHasher::class.java),
            vault = Mockito.mock(CredentialVault::class.java),
            cas = cas,
            revocations = Mockito.mock(SessionRevocationPort::class.java),
            sessions = InMemorySessionRegistry(Clock.systemUTC()),
            rateLimiter = InMemoryAuthRateLimiter(Clock.systemUTC()),
            transactions = TransactionTemplate(Mockito.mock(PlatformTransactionManager::class.java)),
            masterKeys = Mockito.mock(MasterKeyProvider::class.java),
            clock = Clock.systemUTC(),
            initialWriteKeyVersion = "test-key",
        )

        val error = assertThrows(ApiException::class.java) {
            service.createSession(
                "sample-student".toCharArray(),
                "sample-password".toCharArray(),
                acceptCustody = false,
                device = com.upcpocket.backend.contract.DeviceDescriptor(
                    UUID.randomUUID(),
                    com.upcpocket.backend.contract.DevicePlatform.ANDROID,
                    com.upcpocket.backend.contract.DeviceKind.PHONE,
                ),
                clientIp = "192.0.2.4",
                requestId = UUID.randomUUID(),
            )
        }

        assertEquals(ApiErrorCode.CUSTODY_ACCEPTANCE_REQUIRED, error.code)
        verifyNoInteractions(accounts, cas)
    }

    @Test
    fun `failed wrong-password audit keeps pending attempt and blocks later automatic CAS`() {
        JdbcTestDatabase().use { database ->
            val account = JdbcTestDatabase.sampleAccount()
            assertTrue(database.transactionTemplate.execute { database.accounts.insertFirstBinding(account) } == true)

            val hasher = object : BlindIndexHasher {
                override fun loginTag(loginName: CharArray) = account.loginTag
                override fun auditRef(rawSubject: String) = ByteArray(32)
            }
            val audits = object : AuditStore {
                override fun append(event: AuditEventRecord) {
                    if (event.actionCode == AuditActionCode.TRIP_REAUTH) {
                        throw IllegalStateException("audit unavailable")
                    }
                }

                override fun list(cursor: String?, limit: Int) = AuditPage(emptyList(), null)
            }
            val casCalls = AtomicInteger()
            val casResult = AtomicReference<CasVerificationResult>(CasVerificationResult.InvalidCredentials)
            val cas = object : UpcCasVerifier {
                override fun verify(loginName: CharArray, password: CharArray): CasVerificationResult {
                    casCalls.incrementAndGet()
                    return casResult.get()
                }

                override fun isConfigured(): Boolean = true
            }
            val masterKeys = Mockito.mock(MasterKeyProvider::class.java)
            Mockito.`when`(masterKeys.configuredVersions()).thenReturn(setOf("test-key"))
            val sessions = InMemorySessionRegistry(Clock.systemUTC())
            val service = StudentAccountService(
                accounts = database.accounts,
                audits = audits,
                hasher = hasher,
                vault = fakeVault("boundary-student".toCharArray()),
                cas = cas,
                revocations = sessions,
                sessions = sessions,
                rateLimiter = InMemoryAuthRateLimiter(Clock.systemUTC()),
                transactions = database.transactionTemplate,
                masterKeys = masterKeys,
                clock = Clock.systemUTC(),
                initialWriteKeyVersion = "test-key",
            )

            val signInError = assertThrows(ApiException::class.java) {
                service.createSession(
                    "boundary-student".toCharArray(),
                    "wrong-boundary-password".toCharArray(),
                    acceptCustody = true,
                    device = com.upcpocket.backend.contract.DeviceDescriptor(
                        UUID.randomUUID(),
                        com.upcpocket.backend.contract.DevicePlatform.ANDROID,
                        com.upcpocket.backend.contract.DeviceKind.PHONE,
                    ),
                    clientIp = "192.0.2.5",
                    requestId = UUID.randomUUID(),
                )
            }
            assertEquals(ApiErrorCode.SIGN_IN_FAILED, signInError.code)

            val persisted = database.accounts.findByAccountId(account.accountId)
            assertNotNull(persisted)
            assertFalse(persisted!!.reauthRequired)
            assertNotNull(persisted.pendingAttemptId)
            val retainedPendingAttempt = persisted.pendingAttemptId

            casCalls.set(0)
            val blockedSignIn = assertThrows(ApiException::class.java) {
                service.createSession(
                    "boundary-student".toCharArray(),
                    "another-wrong-password".toCharArray(),
                    acceptCustody = true,
                    device = com.upcpocket.backend.contract.DeviceDescriptor(
                        UUID.randomUUID(),
                        com.upcpocket.backend.contract.DevicePlatform.ANDROID,
                        com.upcpocket.backend.contract.DeviceKind.PHONE,
                    ),
                    clientIp = "192.0.2.8",
                    requestId = UUID.randomUUID(),
                )
            }
            assertEquals(ApiErrorCode.SIGN_IN_FAILED, blockedSignIn.code)
            assertEquals(0, casCalls.get())
            assertEquals(retainedPendingAttempt, database.accounts.findByAccountId(account.accountId)!!.pendingAttemptId)

            listOf(
                CasVerificationResult.InteractiveChallenge to ApiErrorCode.INTERACTIVE_CHALLENGE,
                CasVerificationResult.UpstreamUnavailable to ApiErrorCode.UPSTREAM_UNAVAILABLE,
            ).forEachIndexed { index, (outcome, expectedCode) ->
                casResult.set(outcome)
                val updateError = assertThrows(ApiException::class.java) {
                    service.updateCredential(
                        "boundary-student".toCharArray(),
                        "manual-boundary-password-$index".toCharArray(),
                        clientIp = "192.0.2.${index + 6}",
                        requestId = UUID.randomUUID(),
                    )
                }
                assertEquals(expectedCode, updateError.code)
                val afterUpdate = database.accounts.findByAccountId(account.accountId)!!
                assertEquals(retainedPendingAttempt, afterUpdate.pendingAttemptId)
                assertFalse(afterUpdate.reauthRequired)

                casCalls.set(0)
                val autoError = assertThrows(ApiException::class.java) {
                    service.executeAutoBusinessLogin(
                        account.accountId,
                        UUID.randomUUID(),
                        "card",
                        UUID.randomUUID(),
                    ) { error("pending attempt must stop automatic login") }
                }
                assertEquals(ApiErrorCode.SIGN_IN_FAILED, autoError.code)
                assertEquals(0, casCalls.get())
            }

            casResult.set(CasVerificationResult.Verified("boundary-student".toCharArray()))
            service.updateCredential(
                "boundary-student".toCharArray(),
                "recovered-boundary-password".toCharArray(),
                clientIp = "192.0.2.9",
                requestId = UUID.randomUUID(),
            )
            val recovered = database.accounts.findByAccountId(account.accountId)!!
            assertNull(recovered.pendingAttemptId)
            assertFalse(recovered.reauthRequired)
            assertEquals(2, recovered.credentialGeneration)
        }
    }

    @Test
    fun `same login tag is busy across concurrent sign-in and credential update for every account state`() {
        JdbcTestDatabase().use { database ->
            val active = JdbcTestDatabase.sampleAccount(seed = 11)
            val disabled = JdbcTestDatabase.sampleAccount(seed = 22, disabled = true)
            assertTrue(database.transactionTemplate.execute {
                database.accounts.insertFirstBinding(active) && database.accounts.insertFirstBinding(disabled)
            } == true)

            val names = listOf(
                "active-concurrent".toCharArray(),
                "unknown-concurrent".toCharArray(),
                "disabled-concurrent".toCharArray(),
            )
            val unknownTag = LoginTag(ByteArray(LoginTag.BYTE_LENGTH) { 99.toByte() })
            val hasher = object : BlindIndexHasher {
                override fun loginTag(loginName: CharArray) = when {
                    loginName.contentEquals(names[0]) -> active.loginTag
                    loginName.contentEquals(names[2]) -> disabled.loginTag
                    else -> unknownTag
                }

                override fun auditRef(rawSubject: String) = ByteArray(LoginTag.BYTE_LENGTH)
            }
            val gate = AtomicReference(CountDownLatch(0) to CountDownLatch(0))
            val casCalls = AtomicInteger()
            val cas = object : UpcCasVerifier {
                override fun verify(loginName: CharArray, password: CharArray): CasVerificationResult {
                    casCalls.incrementAndGet()
                    val (entered, release) = gate.get()
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return CasVerificationResult.UpstreamUnavailable
                }

                override fun isConfigured(): Boolean = true
            }
            val audits = object : AuditStore {
                override fun append(event: AuditEventRecord) = Unit
                override fun list(cursor: String?, limit: Int) = AuditPage(emptyList(), null)
            }
            val sessions = InMemorySessionRegistry(Clock.systemUTC())
            val service = StudentAccountService(
                accounts = database.accounts,
                audits = audits,
                hasher = hasher,
                vault = fakeVault(names[0]),
                cas = cas,
                revocations = sessions,
                sessions = sessions,
                rateLimiter = InMemoryAuthRateLimiter(Clock.systemUTC()),
                transactions = database.transactionTemplate,
                masterKeys = Mockito.mock(MasterKeyProvider::class.java),
                clock = Clock.systemUTC(),
                initialWriteKeyVersion = "test-key",
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                names.forEachIndexed { index, loginName ->
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    gate.set(entered to release)
                    val casCountBefore = casCalls.get()
                    val firstRequest = executor.submit<ApiErrorCode> {
                        try {
                            service.createSession(
                                loginName.copyOf(),
                                "concurrent-password".toCharArray(),
                                acceptCustody = true,
                                device = com.upcpocket.backend.contract.DeviceDescriptor(
                                    UUID.randomUUID(),
                                    com.upcpocket.backend.contract.DevicePlatform.ANDROID,
                                    com.upcpocket.backend.contract.DeviceKind.PHONE,
                                ),
                                clientIp = "192.0.2.${index + 10}",
                                requestId = UUID.randomUUID(),
                            )
                            ApiErrorCode.INTERNAL_ERROR
                        } catch (error: ApiException) {
                            error.code
                        }
                    }
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        val duplicateSessionError = assertThrows(ApiException::class.java) {
                            service.createSession(
                                loginName.copyOf(),
                                "concurrent-password".toCharArray(),
                                acceptCustody = true,
                                device = com.upcpocket.backend.contract.DeviceDescriptor(
                                    UUID.randomUUID(),
                                    com.upcpocket.backend.contract.DevicePlatform.ANDROID,
                                    com.upcpocket.backend.contract.DeviceKind.PHONE,
                                ),
                                clientIp = "192.0.2.${index + 20}",
                                requestId = UUID.randomUUID(),
                            )
                        }
                        val duplicateUpdateError = assertThrows(ApiException::class.java) {
                            service.updateCredential(
                                loginName.copyOf(),
                                "concurrent-password".toCharArray(),
                                clientIp = "192.0.2.${index + 30}",
                                requestId = UUID.randomUUID(),
                            )
                        }
                        assertEquals(ApiErrorCode.VERIFICATION_BUSY, duplicateSessionError.code)
                        assertEquals(ApiErrorCode.VERIFICATION_BUSY, duplicateUpdateError.code)
                        assertEquals(casCountBefore + 1, casCalls.get())
                    } finally {
                        release.countDown()
                    }
                    assertEquals(ApiErrorCode.UPSTREAM_UNAVAILABLE, firstRequest.get(5, TimeUnit.SECONDS))
                }
            } finally {
                gate.get().second.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `anonymous sign-in masks an in-flight automatic attempt and keeps wrong-password outcomes aligned`() {
        JdbcTestDatabase().use { database ->
            val active = JdbcTestDatabase.sampleAccount(seed = 31)
            val disabled = JdbcTestDatabase.sampleAccount(seed = 42, disabled = true)
            assertTrue(database.transactionTemplate.execute {
                database.accounts.insertFirstBinding(active) && database.accounts.insertFirstBinding(disabled)
            } == true)

            val activeName = "active-auto-flight".toCharArray()
            val disabledName = "disabled-auto-flight".toCharArray()
            val unknownName = "unknown-auto-flight".toCharArray()
            val unknownTag = LoginTag(ByteArray(LoginTag.BYTE_LENGTH) { 77.toByte() })
            val hasher = object : BlindIndexHasher {
                override fun loginTag(loginName: CharArray) = when {
                    loginName.contentEquals(activeName) -> active.loginTag
                    loginName.contentEquals(disabledName) -> disabled.loginTag
                    else -> unknownTag
                }

                override fun auditRef(rawSubject: String) = ByteArray(LoginTag.BYTE_LENGTH)
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holdCas = AtomicReference(false)
            val casResult = AtomicReference<CasVerificationResult>(CasVerificationResult.UpstreamUnavailable)
            val casCalls = AtomicInteger()
            val cas = object : UpcCasVerifier {
                override fun verify(loginName: CharArray, password: CharArray): CasVerificationResult {
                    casCalls.incrementAndGet()
                    if (holdCas.get()) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return casResult.get()
                }

                override fun isConfigured(): Boolean = true
            }
            val audits = object : AuditStore {
                override fun append(event: AuditEventRecord) = Unit
                override fun list(cursor: String?, limit: Int) = AuditPage(emptyList(), null)
            }
            val sessions = InMemorySessionRegistry(Clock.systemUTC())
            val descriptor = com.upcpocket.backend.contract.DeviceDescriptor(
                UUID.randomUUID(),
                com.upcpocket.backend.contract.DevicePlatform.ANDROID,
                com.upcpocket.backend.contract.DeviceKind.PHONE,
            )
            val issued = sessions.issue(active, descriptor)
            val service = StudentAccountService(
                accounts = database.accounts,
                audits = audits,
                hasher = hasher,
                vault = fakeVault(activeName),
                cas = cas,
                revocations = sessions,
                sessions = sessions,
                rateLimiter = InMemoryAuthRateLimiter(Clock.systemUTC()),
                transactions = database.transactionTemplate,
                masterKeys = Mockito.mock(MasterKeyProvider::class.java),
                clock = Clock.systemUTC(),
                initialWriteKeyVersion = "test-key",
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                holdCas.set(true)
                val automatic = executor.submit<ApiErrorCode> {
                    try {
                        service.executeAutoBusinessLogin(
                            active.accountId,
                            issued.deviceId,
                            "card",
                            UUID.randomUUID(),
                        ) { error("upstream failure must stop before business login") }
                        ApiErrorCode.INTERNAL_ERROR
                    } catch (error: ApiException) {
                        error.code
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))

                val concurrentSignIn = assertThrows(ApiException::class.java) {
                    service.createSession(
                        activeName.copyOf(),
                        "another-password".toCharArray(),
                        acceptCustody = true,
                        device = descriptor,
                        clientIp = "192.0.2.51",
                        requestId = UUID.randomUUID(),
                    )
                }
                assertEquals(ApiErrorCode.SIGN_IN_FAILED, concurrentSignIn.code)
                assertEquals(1, casCalls.get())

                release.countDown()
                assertEquals(ApiErrorCode.UPSTREAM_UNAVAILABLE, automatic.get(5, TimeUnit.SECONDS))
                holdCas.set(false)
                casResult.set(CasVerificationResult.InvalidCredentials)

                val activeWrongPassword = assertThrows(ApiException::class.java) {
                    service.createSession(
                        activeName.copyOf(),
                        "wrong-active-password".toCharArray(),
                        acceptCustody = true,
                        device = descriptor,
                        clientIp = "192.0.2.52",
                        requestId = UUID.randomUUID(),
                    )
                }
                val unknownWrongPassword = assertThrows(ApiException::class.java) {
                    service.createSession(
                        unknownName.copyOf(),
                        "wrong-unknown-password".toCharArray(),
                        acceptCustody = true,
                        device = descriptor,
                        clientIp = "192.0.2.53",
                        requestId = UUID.randomUUID(),
                    )
                }
                val disabledWrongPassword = assertThrows(ApiException::class.java) {
                    service.createSession(
                        disabledName.copyOf(),
                        "wrong-disabled-password".toCharArray(),
                        acceptCustody = true,
                        device = descriptor,
                        clientIp = "192.0.2.54",
                        requestId = UUID.randomUUID(),
                    )
                }
                assertEquals(ApiErrorCode.SIGN_IN_FAILED, activeWrongPassword.code)
                assertEquals(activeWrongPassword.code, unknownWrongPassword.code)
                assertEquals(activeWrongPassword.code, disabledWrongPassword.code)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun fakeVault(leaseLoginName: CharArray): CredentialVault = object : CredentialVault {
        override fun seal(
            accountId: UUID,
            loginTag: LoginTag,
            credentialGeneration: Long,
            keyVersion: String,
            loginName: CharArray,
            password: CharArray,
        ) = EncryptedEnvelope(
            EncryptedEnvelope.CURRENT_FORMAT,
            ByteArray(EncryptedEnvelope.GCM_NONCE_LENGTH),
            ByteArray(EncryptedEnvelope.GCM_TAG_LENGTH),
            keyVersion,
            byteArrayOf(1),
        )

        override fun <T> withCredential(
            account: com.upcpocket.backend.contract.AccountRecord,
            purpose: CredentialUsePurpose,
            block: (CredentialLease) -> T,
        ): T {
            val lease = CredentialLease(leaseLoginName.copyOf(), "stored-test-password".toCharArray())
            return try {
                block(lease)
            } finally {
                lease.close()
            }
        }

        override fun matchesStoredCredential(account: com.upcpocket.backend.contract.AccountRecord, loginName: CharArray, password: CharArray) = false

        override fun rewrapDek(envelope: EncryptedEnvelope, targetKeyVersion: String) = EncryptedEnvelope(
            envelope.format,
            envelope.payloadNonce,
            envelope.payloadCiphertext,
            targetKeyVersion,
            envelope.wrappedDek,
        )
    }
}
