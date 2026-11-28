package com.upcpocket.app.account

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountInvalidationFailure
import com.upcpocket.app.account.api.AccountInvalidationReason
import com.upcpocket.app.account.api.AccountInvalidationRequest
import com.upcpocket.app.account.api.AccountInvalidationResult
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountInvalidator
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.campusbackend.BackendOriginPolicy
import com.upcpocket.app.campusbackend.CampusBackendClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SchoolAccountRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun upgradePurgesLegacyAliasAndArtifactsWithoutReadingThem() = runBlocking {
        val root = temporary.newFolder("no-backup")
        val legacy = File(root, "school-account").apply { mkdirs() }
        LegacySchoolCredentialPurger.legacyNames.forEach { name ->
            listOf(name, "$name.bak", "$name.new").forEach { File(legacy, it).writeText("opaque-old-bytes") }
        }
        val aliases = TestAliases(mutableSetOf(LegacySchoolCredentialPurger.LEGACY_KEY_ALIAS))
        val requests = mutableListOf<com.upcpocket.app.account.api.AccountInvalidationRequest>()
        val account = repository(
            root,
            aliases = aliases,
            invalidators = listOf(SchoolAccountInvalidator { request ->
                requests += request
                AccountInvalidationResult.Success
            }),
        )

        assertTrue(account.beginLegacyCredentialUpgrade())
        assertTrue(account.state.value.cleanupRequired)
        assertTrue(account.purgeLegacyPasswordMaterial())
        assertFalse(aliases.contains(LegacySchoolCredentialPurger.LEGACY_KEY_ALIAS))
        assertTrue(legacy.listFiles().orEmpty().isEmpty())
        assertEquals(SchoolAccountWriteResult.Success, account.finishLegacyCredentialUpgrade())
        assertEquals(false, account.state.value.cleanupRequired)
        assertEquals(SchoolAccountStatus.BACKEND_NOT_READY, account.state.value.status)
        assertEquals(1, requests.size)
        assertEquals(AccountDataScope.PERSONAL_DATA, requests.single().scope)
        assertFalse(requireNotNull(ManagedSessionStorage(root, crypto = TestCrypto()).readMigrationMarker()).pending)
    }

    @Test
    fun interruptedLegacyCleanupRemainsGatedAndRetriesOnNextAttempt() = runBlocking {
        val root = temporary.newFolder("interrupted")
        var attempts = 0
        val account = repository(
            root,
            invalidators = listOf(SchoolAccountInvalidator {
                attempts++
                if (attempts == 1) AccountInvalidationResult.failure(AccountInvalidationFailure.COOKIE_CLEAR_TIMEOUT)
                else AccountInvalidationResult.Success
            }),
        )
        account.beginLegacyCredentialUpgrade()
        account.purgeLegacyPasswordMaterial()

        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED),
            account.finishLegacyCredentialUpgrade(),
        )
        assertTrue(account.state.value.cleanupRequired)
        assertTrue(requireNotNull(ManagedSessionStorage(root, crypto = TestCrypto()).readMigrationMarker()).pending)
        assertEquals(SchoolAccountWriteResult.Success, account.retryCleanup())
        assertFalse(account.state.value.cleanupRequired)
        assertEquals(2, attempts)
    }

    @Test
    fun missingLegacyMigrationMarkerCannotBeTreatedAsCompleted() = runBlocking {
        val account = repository(temporary.newFolder("missing-migration-marker"))

        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED),
            account.finishLegacyCredentialUpgrade(),
        )
        assertTrue(account.state.value.cleanupRequired)
        assertEquals(SchoolAccountStatus.REMOVING, account.state.value.status)
    }

    @Test
    fun passwordAccessIsClosedAndBindClearsInputWhenCustodyWasNotAccepted() = runBlocking {
        val root = temporary.newFolder("closed-password-path")
        val account = repository(root)
        account.beginLegacyCredentialUpgrade()
        account.purgeLegacyPasswordMaterial()
        assertEquals(SchoolAccountWriteResult.Success, account.finishLegacyCredentialUpgrade())

        val password = "synthetic-passphrase".toCharArray()
        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CUSTODY_REQUIRED),
            account.bind("synthetic-user", password, acceptCustody = false),
        )
        assertTrue(password.all { it == '\u0000' })

        var callbackInvoked = false
        @OptIn(RestrictedSchoolCredentialUse::class)
        val result = account.withLoginCredential {
            callbackInvoked = true
            "unexpected"
        }
        assertEquals(SchoolCredentialUseResult.Failure(SchoolCredentialFailure.NOT_CONFIGURED), result)
        assertFalse(callbackInvoked)
        assertTrue(root.walkTopDown().filter(File::isFile).none { it.readText().contains("synthetic-passphrase") })
    }

    @Test
    fun failedMeRevokesOnlyTheNewDeviceSessionAndDoesNotCommit() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val deviceId = UUID.randomUUID().toString()
            server.enqueue(jsonResponse("""{"token":"synthetic-pocket-token","deviceId":"$deviceId","expiresAt":"2026-12-01T00:00:00Z"}"""))
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_UNAVAILABLE","requestId":"request-1"}""", 503))
            server.enqueue(MockResponse().setResponseCode(204))
            val root = temporary.newFolder("failed-me")
            val origin = server.url("/").toString().removeSuffix("/")
            val account = repository(root, client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug), origin = origin)
            completeMigration(account)

            val password = "synthetic-passphrase".toCharArray()
            val result = account.bind("synthetic-user", password, acceptCustody = true)
            assertEquals(SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE), result)
            assertTrue(password.all { it == '\u0000' })
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, account.state.value.status)
            assertNull(account.accountReference.value)

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/v1/auth/sessions", create.path)
            assertTrue(create.body.readUtf8().contains("\"acceptCustody\":true"))
            val getMe = server.takeRequest()
            assertEquals("GET", getMe.method)
            assertEquals("Bearer synthetic-pocket-token", getMe.getHeader("Authorization"))
            val revoke = server.takeRequest()
            assertEquals("DELETE", revoke.method)
            assertEquals("/api/v1/me/session", revoke.path)
            assertEquals("Bearer synthetic-pocket-token", revoke.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun staleSessionInvalidationCannotCloseTheNewlyBoundAccount() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val deviceA = UUID.randomUUID().toString()
            val deviceB = UUID.randomUUID().toString()
            val accountA = UUID.randomUUID().toString()
            val accountB = UUID.randomUUID().toString()
            server.enqueue(jsonResponse("""{"token":"token-a","deviceId":"$deviceA","expiresAt":"2026-12-01T00:00:00Z"}"""))
            server.enqueue(jsonResponse("""{"accountId":"$accountA","state":"ACTIVE","credentialGeneration":1,"gateRevision":1}"""))
            server.enqueue(jsonResponse("""{"token":"token-b","deviceId":"$deviceB","expiresAt":"2026-12-01T00:00:00Z"}"""))
            server.enqueue(jsonResponse("""{"accountId":"$accountB","state":"ACTIVE","credentialGeneration":2,"gateRevision":2}"""))
            val root = temporary.newFolder("session-fencing")
            val origin = server.url("/").toString().removeSuffix("/")
            val account = repository(root, client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug), origin = origin)
            completeMigration(account)

            assertEquals(SchoolAccountWriteResult.Success, account.bind("synthetic-a", "pass-a".toCharArray(), true))
            val oldLease = requireNotNull(account.captureSessionLease())
            assertEquals(SchoolAccountWriteResult.Success, account.bind("synthetic-b", "pass-b".toCharArray(), true))
            val currentLease = requireNotNull(account.captureSessionLease())

            assertNotEquals(oldLease.accountId, currentLease.accountId)
            assertFalse(account.isSessionLeaseCurrent(oldLease))
            assertFalse(account.invalidateSessionIfCurrent(oldLease))
            assertTrue(account.isSessionLeaseCurrent(currentLease))
            assertEquals(SchoolAccountStatus.MANAGED_ACTIVE, account.state.value.status)
            assertEquals(accountB, account.accountReference.value?.accountId?.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun lateBindingAAfterBindingBDoesNotInvalidateOrOverwriteB() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val deviceA = UUID.randomUUID().toString()
            val deviceB = UUID.randomUUID().toString()
            val accountA = UUID.randomUUID().toString()
            val accountB = UUID.randomUUID().toString()
            server.enqueue(jsonResponse("""{"token":"token-a","deviceId":"$deviceA","expiresAt":"2026-12-01T00:00:00Z"}"""))
            server.enqueue(
                jsonResponse("""{"accountId":"$accountA","state":"ACTIVE","credentialGeneration":1,"gateRevision":1}""")
                    .setBodyDelay(1500, TimeUnit.MILLISECONDS),
            )
            server.enqueue(jsonResponse("""{"token":"token-b","deviceId":"$deviceB","expiresAt":"2026-12-01T00:00:00Z"}"""))
            server.enqueue(jsonResponse("""{"accountId":"$accountB","state":"ACTIVE","credentialGeneration":2,"gateRevision":2}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val root = temporary.newFolder("late-binding")
            val origin = server.url("/").toString().removeSuffix("/")
            val invalidations = mutableListOf<com.upcpocket.app.account.api.AccountInvalidationRequest>()
            val account = repository(
                root,
                client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug),
                origin = origin,
                invalidators = listOf(SchoolAccountInvalidator { request ->
                    invalidations += request
                    AccountInvalidationResult.Success
                }),
            )
            completeMigration(account)

            val bindingA = async(Dispatchers.Default) {
                account.bind("synthetic-a", "pass-a".toCharArray(), acceptCustody = true)
            }
            assertEquals("POST", server.takeRequestRequired().method)
            assertEquals("GET", server.takeRequestRequired().method)

            val bindingB = async(Dispatchers.Default) {
                account.bind("synthetic-b", "pass-b".toCharArray(), acceptCustody = true)
            }
            assertEquals(SchoolAccountWriteResult.Success, withTimeout(5_000) { bindingB.await() })
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
                withTimeout(5_000) { bindingA.await() },
            )

            assertEquals(2, invalidations.size) // legacy purge and B's single bind commit
            assertEquals(accountB, account.accountReference.value?.accountId?.value)
            assertEquals(SchoolAccountStatus.MANAGED_ACTIVE, account.state.value.status)
            val stored = requireNotNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
            assertEquals("token-b", stored.accessToken)
            assertEquals(deviceB, stored.deviceId)
            val createB = server.takeRequestRequired()
            assertEquals("POST", createB.method)
            assertEquals("/api/v1/auth/sessions", createB.path)
            val getMeB = server.takeRequestRequired()
            assertEquals("GET", getMeB.method)
            assertEquals("Bearer token-b", getMeB.getHeader("Authorization"))
            val revokeA = server.takeRequestRequired()
            assertEquals("DELETE", revokeA.method)
            assertEquals("Bearer token-a", revokeA.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bindingHoldsCommitLockAcrossInvalidationFailureAndDoesNotLetWaitingBindClobberIt() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val deviceA = UUID.randomUUID().toString()
            val deviceB = UUID.randomUUID().toString()
            val accountA = UUID.randomUUID().toString()
            val accountB = UUID.randomUUID().toString()
            server.enqueue(jsonResponse("""{"token":"token-a","deviceId":"$deviceA","expiresAt":"2026-12-01T00:00:00Z"}"""))
            server.enqueue(jsonResponse("""{"accountId":"$accountA","state":"ACTIVE","credentialGeneration":1,"gateRevision":1}"""))
            server.enqueue(MockResponse().setResponseCode(204)) // revoke A after the failed local commit
            val root = temporary.newFolder("commit-lock")
            val origin = server.url("/").toString().removeSuffix("/")
            val invalidationEntered = CompletableDeferred<Unit>()
            val releaseInvalidation = CompletableDeferred<Unit>()
            val invalidations = mutableListOf<com.upcpocket.app.account.api.AccountInvalidationRequest>()
            var interceptNextInvalidation = false
            val account = repository(
                root,
                client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug),
                origin = origin,
                invalidators = listOf(SchoolAccountInvalidator { request ->
                    invalidations += request
                    if (interceptNextInvalidation) {
                        interceptNextInvalidation = false
                        invalidationEntered.complete(Unit)
                        releaseInvalidation.await()
                        AccountInvalidationResult.failure(AccountInvalidationFailure.PERSONAL_DATA_CLEAR_FAILED)
                    } else {
                        AccountInvalidationResult.Success
                    }
                }),
            )
            completeMigration(account)
            invalidations.clear()
            interceptNextInvalidation = true

            val bindingA = async(Dispatchers.Default) {
                account.bind("synthetic-a", "pass-a".toCharArray(), acceptCustody = true)
            }
            assertEquals("POST", server.takeRequestRequired().method)
            assertEquals("GET", server.takeRequestRequired().method)
            withTimeout(5_000) { invalidationEntered.await() }

            val bindingB = async(Dispatchers.Default) {
                account.bind("synthetic-b", "pass-b".toCharArray(), acceptCustody = true)
            }
            delay(100)
            assertFalse(bindingA.isCompleted)
            assertFalse(bindingB.isCompleted)
            assertEquals(2, server.requestCount)

            releaseInvalidation.complete(Unit)
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED),
                withTimeout(5_000) { bindingA.await() },
            )
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_REQUIRED),
                withTimeout(5_000) { bindingB.await() },
            )
            assertEquals(SchoolAccountStatus.REMOVING, account.state.value.status)
            assertTrue(account.state.value.cleanupRequired)
            assertEquals("DELETE", server.takeRequestRequired().method)

            assertEquals(SchoolAccountWriteResult.Success, account.retryCleanup())
            server.enqueue(jsonResponse("""{"token":"token-b","deviceId":"$deviceB","expiresAt":"2026-12-01T00:00:00Z"}"""))
            server.enqueue(jsonResponse("""{"accountId":"$accountB","state":"ACTIVE","credentialGeneration":2,"gateRevision":2}"""))
            assertEquals(SchoolAccountWriteResult.Success, account.bind("synthetic-b", "pass-b".toCharArray(), true))
            assertEquals(SchoolAccountStatus.MANAGED_ACTIVE, account.state.value.status)
            assertEquals(accountB, account.accountReference.value?.accountId?.value)
            assertEquals("token-b", ManagedSessionStorage(root, crypto = TestCrypto()).loadSession()?.accessToken)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unbindTimeoutKeepsEncryptedClosedSessionAcrossRestoreAndRetriesUntilConfirmed() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val root = temporary.newFolder("unbind-pending")
            val invalidations = mutableListOf<com.upcpocket.app.account.api.AccountInvalidationRequest>()
            val account = bindManagedSession(server, root, "token-unbind", deviceId, accountId) { request ->
                invalidations += request
                AccountInvalidationResult.Success
            }
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_TIMEOUT","requestId":"request-timeout"}""", 504))

            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN),
                account.unbind(confirmed = true),
            )
            assertNull(account.captureSessionLease())
            assertNull(account.accountReference.value)
            assertTrue(account.state.value.blocksPersonalSessions)
            assertEquals(SchoolAccountStatus.UNBIND_PENDING, account.state.value.status)
            val storage = ManagedSessionStorage(root, crypto = TestCrypto())
            val pending = requireNotNull(storage.loadSession())
            assertEquals(ManagedSessionState.UNBIND_PENDING.name, pending.state)
            assertTrue(pending.personalDataCleared)
            assertTrue(File(root, "managed-session/managed-session-v1.enc").isFile)
            val unbind = server.takeRequestRequired()
            assertEquals("DELETE", unbind.method)
            assertEquals("/api/v1/me", unbind.path)
            assertEquals("Bearer token-unbind", unbind.getHeader("Authorization"))

            val origin = server.url("/").toString().removeSuffix("/")
            val restored = repository(
                root,
                client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug),
                origin = origin,
            )
            restored.restore()
            assertEquals(SchoolAccountStatus.UNBIND_PENDING, restored.state.value.status)
            assertFalse(restored.state.value.isManagedActive)
            assertNull(restored.captureSessionLease())

            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(SchoolAccountWriteResult.Success, restored.unbind(confirmed = true))
            val retry = server.takeRequestRequired()
            assertEquals("DELETE", retry.method)
            assertEquals("/api/v1/me", retry.path)
            assertEquals("Bearer token-unbind", retry.getHeader("Authorization"))
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, restored.state.value.status)
            assertNull(storage.loadSession())
            assertFalse(File(root, "managed-session/managed-session-v1.enc").exists())
            assertEquals(AccountInvalidationReason.ACCOUNT_REMOVED, invalidations.last().reason)
            assertEquals(AccountDataScope.PERSONAL_DATA, invalidations.last().scope)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unbindTreatsExplicitInvalidTokenResponseAsConfirmedAndClearsPendingSession() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val root = temporary.newFolder("unbind-idempotent")
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val account = bindManagedSession(server, root, "token-unbind-idempotent", deviceId, accountId)
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_TIMEOUT","requestId":"request-timeout"}""", 504))
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN),
                account.unbind(confirmed = true),
            )
            server.takeRequestRequired()

            server.enqueue(jsonResponse("""{"code":"TOKEN_INVALID","requestId":"request-invalid"}""", 401))
            assertEquals(SchoolAccountWriteResult.Success, account.unbind(confirmed = true))
            val retry = server.takeRequestRequired()
            assertEquals("DELETE", retry.method)
            assertEquals("Bearer token-unbind-idempotent", retry.getHeader("Authorization"))
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, account.state.value.status)
            assertNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signOutFailureKeepsEncryptedSessionClosedAndRetriesAfterRestore() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val root = temporary.newFolder("signout-pending")
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val account = bindManagedSession(server, root, "token-signout", deviceId, accountId)
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_UNAVAILABLE","requestId":"request-5xx"}""", 503))

            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE),
                account.signOut(),
            )
            val first = server.takeRequestRequired()
            assertEquals("DELETE", first.method)
            assertEquals("/api/v1/me/session", first.path)
            assertNull(account.captureSessionLease())
            assertEquals(SchoolAccountStatus.SIGN_OUT_PENDING, account.state.value.status)
            assertEquals(
                ManagedSessionState.SIGN_OUT_PENDING.name,
                ManagedSessionStorage(root, crypto = TestCrypto()).loadSession()?.state,
            )

            val origin = server.url("/").toString().removeSuffix("/")
            val restored = repository(
                root,
                client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug),
                origin = origin,
            )
            restored.restore()
            assertEquals(SchoolAccountStatus.SIGN_OUT_PENDING, restored.state.value.status)
            assertNull(restored.captureSessionLease())
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(SchoolAccountWriteResult.Success, restored.signOut())
            val retry = server.takeRequestRequired()
            assertEquals("DELETE", retry.method)
            assertEquals("/api/v1/me/session", retry.path)
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, restored.state.value.status)
            assertNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ownDeviceRevocationFailureKeepsOriginalActionForRetryAfterRestore() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val root = temporary.newFolder("device-revoke-pending")
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val account = bindManagedSession(server, root, "token-device-revoke", deviceId, accountId)
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_UNAVAILABLE","requestId":"request-5xx"}""", 503))

            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UPSTREAM_UNAVAILABLE),
                account.revokeDevice(deviceId),
            )
            val first = server.takeRequestRequired()
            assertEquals("DELETE", first.method)
            assertEquals("/api/v1/me/devices/$deviceId", first.path)
            assertEquals(SchoolAccountStatus.SIGN_OUT_PENDING, account.state.value.status)
            assertNull(account.captureSessionLease())
            assertEquals(
                ManagedSessionState.DEVICE_REVOKE_PENDING.name,
                ManagedSessionStorage(root, crypto = TestCrypto()).loadSession()?.state,
            )

            val origin = server.url("/").toString().removeSuffix("/")
            val restored = repository(
                root,
                client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug),
                origin = origin,
            )
            restored.restore()
            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(SchoolAccountWriteResult.Success, restored.revokeDevice(deviceId))
            val retry = server.takeRequestRequired()
            assertEquals("DELETE", retry.method)
            assertEquals("/api/v1/me/devices/$deviceId", retry.path)
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, restored.state.value.status)
            assertNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bindIsRejectedWhileUnbindPendingAndPreservesRetryableSession() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val root = temporary.newFolder("bind-blocked-while-unbind-pending")
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val account = bindManagedSession(server, root, "token-unbind-block", deviceId, accountId)
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_TIMEOUT","requestId":"request-timeout"}""", 504))
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN),
                account.unbind(confirmed = true),
            )
            server.takeRequestRequired()
            val requestsBeforeBind = server.requestCount

            val password = "synthetic-rebind-pass".toCharArray()
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
                account.bind("synthetic-rebind", password, acceptCustody = true),
            )
            assertTrue(password.all { it == '\u0000' })
            assertEquals(requestsBeforeBind, server.requestCount)
            assertEquals(SchoolAccountStatus.UNBIND_PENDING, account.state.value.status)
            val stored = requireNotNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
            assertEquals("token-unbind-block", stored.accessToken)
            assertEquals(ManagedSessionState.UNBIND_PENDING.name, stored.state)

            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(SchoolAccountWriteResult.Success, account.unbind(confirmed = true))
            val retry = server.takeRequestRequired()
            assertEquals("DELETE", retry.method)
            assertEquals("/api/v1/me", retry.path)
            assertEquals("Bearer token-unbind-block", retry.getHeader("Authorization"))
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, account.state.value.status)
            assertNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bindIsRejectedWhileRemoteUnbindIsInFlight() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val root = temporary.newFolder("bind-blocked-during-inflight-unbind")
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val account = bindManagedSession(server, root, "token-inflight-unbind", deviceId, accountId)
            server.enqueue(MockResponse().setResponseCode(204).setBodyDelay(1500, TimeUnit.MILLISECONDS))

            val unbindJob = async(Dispatchers.Default) { account.unbind(confirmed = true) }
            val deleteRequest = server.takeRequestRequired()
            assertEquals("DELETE", deleteRequest.method)
            assertEquals("/api/v1/me", deleteRequest.path)
            val countDuringUnbind = server.requestCount

            val password = "synthetic-rebind-pass".toCharArray()
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
                account.bind("synthetic-rebind", password, acceptCustody = true),
            )
            assertTrue(password.all { it == '\u0000' })
            assertEquals(countDuringUnbind, server.requestCount)

            assertEquals(SchoolAccountWriteResult.Success, withTimeout(5_000) { unbindJob.await() })
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, account.state.value.status)
            assertNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun updateManagedCredentialIsRejectedWhileUnbindPendingAndPreservesRetryableSession() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val root = temporary.newFolder("update-blocked-while-unbind-pending")
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val account = bindManagedSession(server, root, "token-unbind-update-block", deviceId, accountId)
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_TIMEOUT","requestId":"request-timeout"}""", 504))
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN),
                account.unbind(confirmed = true),
            )
            server.takeRequestRequired()
            val requestsBeforeUpdate = server.requestCount

            val newPassword = "synthetic-new-pass".toCharArray()
            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
                account.updateManagedCredential("synthetic-user", newPassword),
            )
            assertTrue(newPassword.all { it == '\u0000' })
            assertEquals(requestsBeforeUpdate, server.requestCount)
            assertEquals(SchoolAccountStatus.UNBIND_PENDING, account.state.value.status)
            val stored = requireNotNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
            assertEquals("token-unbind-update-block", stored.accessToken)
            assertEquals(ManagedSessionState.UNBIND_PENDING.name, stored.state)

            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(SchoolAccountWriteResult.Success, account.unbind(confirmed = true))
            val retry = server.takeRequestRequired()
            assertEquals("DELETE", retry.method)
            assertEquals("/api/v1/me", retry.path)
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, account.state.value.status)
            assertNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun lateCredentialUpdateResultDoesNotClearPendingRevocationSession() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val root = temporary.newFolder("late-update-does-not-clear-pending-unbind")
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            val account = bindManagedSession(server, root, "token-late-update-unbind", deviceId, accountId)
            server.enqueue(MockResponse().setResponseCode(204).setBodyDelay(1500, TimeUnit.MILLISECONDS))
            server.enqueue(jsonResponse("""{"code":"UPSTREAM_TIMEOUT","requestId":"request-timeout"}""", 504))

            val updateJob = async(Dispatchers.Default) {
                account.updateManagedCredential("synthetic-user", "synthetic-new-pass".toCharArray())
            }
            val updateReq = server.takeRequestRequired()
            assertEquals("POST", updateReq.method)
            assertEquals("/api/v1/auth/credential-updates", updateReq.path)

            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN),
                account.unbind(confirmed = true),
            )
            val unbindReq = server.takeRequestRequired()
            assertEquals("DELETE", unbindReq.method)
            assertEquals("/api/v1/me", unbindReq.path)

            assertEquals(
                SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.BUSY),
                withTimeout(5_000) { updateJob.await() },
            )
            assertEquals(SchoolAccountStatus.UNBIND_PENDING, account.state.value.status)
            val stored = requireNotNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
            assertEquals("token-late-update-unbind", stored.accessToken)
            assertEquals(ManagedSessionState.UNBIND_PENDING.name, stored.state)

            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(SchoolAccountWriteResult.Success, account.unbind(confirmed = true))
            val retry = server.takeRequestRequired()
            assertEquals("DELETE", retry.method)
            assertEquals("/api/v1/me", retry.path)
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, account.state.value.status)
            assertNull(ManagedSessionStorage(root, crypto = TestCrypto()).loadSession())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun changingBackendOriginClearsTheOldSessionAndPersonalDataBeforeReuse() = runBlocking {
        val originalServer = MockWebServer()
        val replacementServer = MockWebServer()
        originalServer.start()
        replacementServer.start()
        try {
            val deviceId = UUID.randomUUID().toString()
            val accountId = UUID.randomUUID().toString()
            originalServer.enqueue(jsonResponse("""{"token":"origin-bound-token","deviceId":"$deviceId","expiresAt":"2026-12-01T00:00:00Z"}"""))
            originalServer.enqueue(jsonResponse("""{"accountId":"$accountId","state":"ACTIVE","credentialGeneration":1,"gateRevision":1}"""))
            val root = temporary.newFolder("backend-origin-change")
            val originalOrigin = originalServer.url("/").toString().removeSuffix("/")
            val originalAccount = repository(
                root,
                client = CampusBackendClient.create(originalOrigin, BackendOriginPolicy.Debug),
                origin = originalOrigin,
            )
            completeMigration(originalAccount)
            assertEquals(
                SchoolAccountWriteResult.Success,
                originalAccount.bind("synthetic-user", "synthetic-passphrase".toCharArray(), acceptCustody = true),
            )
            assertTrue(originalAccount.hasUsableManagedSession())

            val replacementOrigin = replacementServer.url("/").toString().removeSuffix("/")
            val invalidated = mutableListOf<AccountDataScope>()
            val replacementAccount = repository(
                root,
                client = CampusBackendClient.create(replacementOrigin, BackendOriginPolicy.Debug),
                origin = replacementOrigin,
                invalidators = listOf(SchoolAccountInvalidator { request ->
                    invalidated += request.scope
                    AccountInvalidationResult.Success
                }),
            )
            replacementAccount.restore()

            assertFalse(replacementAccount.hasUsableManagedSession())
            assertEquals(SchoolAccountStatus.NOT_CONFIGURED, replacementAccount.state.value.status)
            assertEquals(listOf(AccountDataScope.PERSONAL_DATA), invalidated)
            assertEquals(0, replacementServer.requestCount)
            assertEquals(
                replacementOrigin,
                ManagedSessionStorage(root, crypto = TestCrypto()).loadOrCreateConfig(replacementOrigin).backendOrigin,
            )
        } finally {
            originalServer.shutdown()
            replacementServer.shutdown()
        }
    }

    private suspend fun completeMigration(account: SchoolAccountRepository) {
        assertTrue(account.beginLegacyCredentialUpgrade())
        assertTrue(account.purgeLegacyPasswordMaterial())
        assertEquals(SchoolAccountWriteResult.Success, account.finishLegacyCredentialUpgrade())
    }

    private suspend fun bindManagedSession(
        server: MockWebServer,
        root: File,
        token: String,
        deviceId: String,
        accountId: String,
        invalidator: (AccountInvalidationRequest) -> AccountInvalidationResult = {
            AccountInvalidationResult.Success
        },
    ): SchoolAccountRepository {
        val origin = server.url("/").toString().removeSuffix("/")
        val account = repository(
            root,
            client = CampusBackendClient.create(origin, BackendOriginPolicy.Debug),
            origin = origin,
            invalidators = listOf(SchoolAccountInvalidator { request -> invalidator(request) }),
        )
        completeMigration(account)
        server.enqueue(jsonResponse("""{"token":"$token","deviceId":"$deviceId","expiresAt":"2026-12-01T00:00:00Z"}"""))
        server.enqueue(jsonResponse("""{"accountId":"$accountId","state":"ACTIVE","credentialGeneration":1,"gateRevision":1}"""))
        assertEquals(
            SchoolAccountWriteResult.Success,
            account.bind("synthetic-user", "synthetic-pass".toCharArray(), acceptCustody = true),
        )
        assertEquals("POST", server.takeRequestRequired().method)
        assertEquals("GET", server.takeRequestRequired().method)
        return account
    }

    private fun repository(
        root: File,
        aliases: TestAliases = TestAliases(),
        invalidators: List<SchoolAccountInvalidator> = emptyList(),
        client: CampusBackendClient = CampusBackendClient.create(null, BackendOriginPolicy.Debug),
        origin: String? = null,
    ) = SchoolAccountRepository(
        storage = ManagedSessionStorage(root, crypto = TestCrypto()),
        legacyPurger = LegacySchoolCredentialPurger(File(root, "school-account"), aliases),
        backendClient = client,
        backendOrigin = origin,
        invalidators = invalidators,
        onSessionClosed = {},
        deviceKindProvider = { com.upcpocket.app.campusbackend.CampusDeviceKind.PHONE },
        clock = { 1_764_000_000_000L },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun MockWebServer.takeRequestRequired(): okhttp3.mockwebserver.RecordedRequest =
        requireNotNull(takeRequest(5, TimeUnit.SECONDS)) { "Expected HTTP request within 5 seconds" }

    private fun jsonResponse(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private class TestAliases(private val values: MutableSet<String> = mutableSetOf()) : LegacyAliasStore {
        override fun contains(alias: String): Boolean = alias in values
        override fun delete(alias: String) { values.remove(alias) }
    }

    private class TestCrypto : ManagedSessionCrypto {
        override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> =
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12) to plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
