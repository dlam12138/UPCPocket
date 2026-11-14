package com.upcpocket.app.serviceportal

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.upcauth.ServicePortalAuthenticator
import com.upcpocket.app.upcauth.UpcServicePortalAuthFailure
import com.upcpocket.app.upcauth.UpcServicePortalAuthResult
import com.upcpocket.app.upcauth.UpcServicePortalAuthSource
import com.upcpocket.app.upcauth.UpcServicePortalCookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServicePortalServiceTest {
    private class Account : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(
            SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)),
        )
        override val accountReference = MutableStateFlow<SchoolAccountReference?>(
            SchoolAccountReference(SchoolAccountId("syntheticA"), AccountGeneration(1)),
        )

        fun change(id: String, generation: Long, available: Boolean = true) {
            state.value = SchoolAccountState(
                if (available) SchoolAccountStatus.SAVED_UNVERIFIED else SchoolAccountStatus.UPDATING,
                AccountGeneration(generation),
                cleanupRequired = !available,
            )
            accountReference.value = if (available) {
                SchoolAccountReference(SchoolAccountId(id), AccountGeneration(generation))
            } else {
                null
            }
        }
    }

    private class Auth : ServicePortalAuthenticator {
        var calls = 0
        var clearCalls = 0
        var result: UpcServicePortalAuthResult? = null

        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcServicePortalAuthResult {
            calls++
            return result ?: UpcServicePortalAuthResult.Success(
                binding = SchoolAccountReference(expectedAccountId, AccountGeneration(expectedCredentialGeneration)),
                cookies = listOf(syntheticCookie()),
                source = UpcServicePortalAuthSource.PASSWORD,
            )
        }

        override fun clearSession() {
            clearCalls++
        }
    }

    private class DelayedAuth(
        private val result: (SchoolAccountId, Long) -> UpcServicePortalAuthResult = { id, generation ->
            UpcServicePortalAuthResult.Success(
                binding = SchoolAccountReference(id, AccountGeneration(generation)),
                cookies = listOf(syntheticCookie()),
                source = UpcServicePortalAuthSource.PASSWORD,
            )
        },
    ) : ServicePortalAuthenticator {
        var calls = 0
        var clearCalls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcServicePortalAuthResult {
            calls++
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            return result(expectedAccountId, expectedCredentialGeneration)
        }

        override fun clearSession() {
            clearCalls++
        }
    }

    private class LateFirstGenerationAuth : ServicePortalAuthenticator {
        var calls = 0
        var clearCalls = 0
        val firstGenerationStarted = CompletableDeferred<Unit>()
        val releaseFirstGeneration = CompletableDeferred<Unit>()

        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcServicePortalAuthResult {
            calls++
            if (expectedCredentialGeneration == 1L) {
                firstGenerationStarted.complete(Unit)
                try {
                    withContext(NonCancellable) { releaseFirstGeneration.await() }
                } catch (_: CancellationException) {
                    // Simulate an authenticator result that arrives after its owner operation was cancelled.
                }
            }
            return UpcServicePortalAuthResult.Success(
                binding = SchoolAccountReference(expectedAccountId, AccountGeneration(expectedCredentialGeneration)),
                cookies = listOf(syntheticCookie()),
                source = UpcServicePortalAuthSource.PASSWORD,
            )
        }

        override fun clearSession() {
            clearCalls++
        }
    }

    private class EpochRaceAuth(private val afterInitialCookieCheck: () -> Unit) : ServicePortalAuthenticator {
        var calls = 0
        var clearCalls = 0

        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcServicePortalAuthResult {
            calls++
            val cookies = if (calls == 1) {
                object : AbstractList<UpcServicePortalCookie>() {
                    override val size: Int get() = 0
                    override fun get(index: Int): UpcServicePortalCookie = throw IndexOutOfBoundsException(index)
                    override fun isEmpty(): Boolean {
                        afterInitialCookieCheck()
                        return true
                    }
                }
            } else {
                listOf(syntheticCookie())
            }
            return UpcServicePortalAuthResult.Success(
                binding = SchoolAccountReference(expectedAccountId, AccountGeneration(expectedCredentialGeneration)),
                cookies = cookies,
                source = UpcServicePortalAuthSource.PASSWORD,
            )
        }

        override fun clearSession() {
            clearCalls++
        }
    }

    private open class Api : ServicePortalDataSource {
        var publicCatalogCalls = 0
        var accountCatalogCalls = 0
        var guideCalls = 0
        var todoCalls = 0
        var applicationCalls = 0
        val accountCatalogFailures = ArrayDeque<ServicePortalFailure>()
        val personalFailures = ArrayDeque<ServicePortalFailure>()

        override suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog {
            if (session == null) {
                publicCatalogCalls++
                return catalog(ServicePortalScope.PUBLIC)
            }
            accountCatalogCalls++
            accountCatalogFailures.removeFirstOrNull()?.let { throw ServicePortalException(it) }
            return catalog(ServicePortalScope.ACCOUNT)
        }

        override suspend fun guide(session: ServicePortalSession?, item: ServicePortalItem): ServicePortalGuide {
            guideCalls++
            return guide(item, if (session == null) ServicePortalScope.PUBLIC else ServicePortalScope.ACCOUNT)
        }

        override suspend fun todos(
            session: ServicePortalSession,
            page: Int,
            pageSize: Int,
        ): ServicePortalDataPage<ServicePortalTodo> {
            todoCalls++
            personalFailures.removeFirstOrNull()?.let { throw ServicePortalException(it) }
            val inRange = page == 1
            return ServicePortalDataPage(0, page, pageSize, emptyList(), false, inRange, inRange, inRange, ServicePortalScope.ACCOUNT)
        }

        override suspend fun applications(
            session: ServicePortalSession,
            page: Int,
            pageSize: Int,
        ): ServicePortalDataPage<ServicePortalApplication> {
            applicationCalls++
            personalFailures.removeFirstOrNull()?.let { throw ServicePortalException(it) }
            val inRange = page == 1
            return ServicePortalDataPage(0, page, pageSize, emptyList(), false, inRange, inRange, inRange, ServicePortalScope.ACCOUNT)
        }

        protected open fun catalog(scope: ServicePortalScope) = ServicePortalCatalog(
            items = listOf(
                ServicePortalItem(
                    rowKey = 0,
                    detailId = 17,
                    name = "Student Leave",
                    department = "Student Affairs",
                    access = ServicePortalAccess.GUIDE_ONLY,
                    externalHost = null,
                    idAmbiguous = false,
                ),
            ),
            folderCount = 0,
            source = scope,
        )

        protected open fun guide(item: ServicePortalItem, scope: ServicePortalScope) = ServicePortalGuide(
            title = item.name,
            department = item.department,
            description = "Synthetic guide",
            descriptionTruncated = false,
            materials = emptyList(),
            materialsState = ServicePortalMaterialsState.EMPTY,
            materialsTruncated = false,
            handlingPlace = null,
            handlingTimes = null,
            chargeType = null,
            charge = null,
            formCount = null,
            startNodes = emptyList(),
            access = item.access,
            externalHost = null,
            externalUrl = null,
            selectionMayBeAmbiguous = false,
            scope = scope,
        )
    }

    private class ResizableApi(var itemCount: Int) : Api() {
        override suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog {
            val scope = if (session == null) {
                publicCatalogCalls++
                ServicePortalScope.PUBLIC
            } else {
                accountCatalogCalls++
                ServicePortalScope.ACCOUNT
            }
            val items = (0 until itemCount).map { index ->
                ServicePortalItem(
                    rowKey = index,
                    detailId = index + 1L,
                    name = "Leave item $index",
                    department = "Student Affairs",
                    access = ServicePortalAccess.GUIDE_ONLY,
                    externalHost = null,
                    idAmbiguous = false,
                )
            }
            return ServicePortalCatalog(items, folderCount = 0, source = scope)
        }
    }

    private class DelayedCatalogApi(private val failure: ServicePortalFailure? = null) : Api() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog {
            if (session != null) {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                failure?.let { throw ServicePortalException(it) }
            }
            return super.catalog(session)
        }
    }

    private class LateCatalogApi(private val firstFailure: ServicePortalFailure? = null) : Api() {
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog {
            val request = ++requests
            if (session != null && request == 1) {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                firstFailure?.let { throw ServicePortalException(it) }
            }
            val value = super.catalog(session)
            val name = if (request == 1) "Stale leave result" else "Fresh leave result"
            val items = value.items.map { item ->
                ServicePortalItem(
                    rowKey = item.rowKey,
                    detailId = item.detailId,
                    name = name,
                    department = item.department,
                    access = item.access,
                    externalHost = item.externalHost,
                    idAmbiguous = item.idAmbiguous,
                )
            }
            return ServicePortalCatalog(items, value.folderCount, value.source)
        }
    }

    @Test
    fun `constructing and observing the plugin service does not authenticate or query`() = runTest {
        val auth = Auth()
        val api = Api()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        runCurrent()

        assertEquals(0, auth.calls)
        assertEquals(0, api.publicCatalogCalls + api.accountCatalogCalls)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `catalog fallback after interaction is public and personal requests never fall back`() = runTest {
        val account = Account()
        val auth = Auth().apply {
            result = UpcServicePortalAuthResult.Failure(UpcServicePortalAuthFailure.INTERACTION_REQUIRED)
        }
        val api = Api()
        val service = ServicePortalService(account, auth, api, backgroundScope)

        service.search("leave")
        runCurrent()

        assertEquals(1, api.publicCatalogCalls)
        assertEquals(ServicePortalScope.PUBLIC, service.state.value.results?.scope)
        assertEquals(ServicePortalFailure.INTERACTION_REQUIRED, service.state.value.authenticationNotice)

        service.selectTab(ServicePortalTab.TODOS)
        runCurrent()

        assertEquals(0, api.todoCalls)
        assertEquals(1, api.publicCatalogCalls)
        assertEquals(ServicePortalFailure.INTERACTION_REQUIRED, service.state.value.error)

        auth.result = null
        service.todos()
        runCurrent()

        assertEquals(2, auth.calls)
        assertEquals(1, api.todoCalls)
        assertNull(service.state.value.error)
    }

    @Test
    fun `explicit login retry clears cached failure and reloads the visible catalog`() = runTest {
        val account = Account()
        var authCalls = 0
        val auth = object : ServicePortalAuthenticator {
            override suspend fun authenticate(
                expectedAccountId: SchoolAccountId,
                expectedCredentialGeneration: Long,
            ): UpcServicePortalAuthResult {
                authCalls++
                if (authCalls == 1) return UpcServicePortalAuthResult.Failure(
                    UpcServicePortalAuthFailure.INTERACTION_REQUIRED,
                )
                return UpcServicePortalAuthResult.Success(
                    binding = SchoolAccountReference(expectedAccountId, AccountGeneration(expectedCredentialGeneration)),
                    cookies = listOf(syntheticCookie()),
                    source = UpcServicePortalAuthSource.PASSWORD,
                )
            }

            override fun clearSession() = Unit
        }
        val api = Api()
        val service = ServicePortalService(account, auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertEquals(1, authCalls)
        assertEquals(ServicePortalLoginStatus.FAILED, service.state.value.loginStatus)
        assertEquals(ServicePortalScope.PUBLIC, service.state.value.results?.scope)

        service.loginOrRetry()
        runCurrent()

        assertEquals(2, authCalls)
        assertEquals(1, api.accountCatalogCalls)
        assertEquals(ServicePortalScope.ACCOUNT, service.state.value.results?.scope)
        assertEquals(ServicePortalLoginStatus.SUCCEEDED, service.state.value.loginStatus)
        assertNull(service.state.value.error)
    }

    @Test
    fun `explicit login retry from a public guide authenticates through the directory read`() = runTest {
        val account = Account()
        val auth = Auth().apply {
            result = UpcServicePortalAuthResult.Failure(UpcServicePortalAuthFailure.INTERACTION_REQUIRED)
        }
        val api = Api()
        val service = ServicePortalService(account, auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertEquals(ServicePortalLoginStatus.FAILED, service.state.value.loginStatus)
        assertEquals(ServicePortalScope.PUBLIC, service.state.value.results?.scope)

        val item = service.state.value.results!!.items.single()
        service.openGuide(item)
        runCurrent()
        assertEquals(ServicePortalScope.PUBLIC, service.state.value.guide?.scope)
        assertEquals(1, api.guideCalls)

        auth.result = null
        service.loginOrRetry()
        runCurrent()

        assertEquals(2, auth.calls)
        assertEquals(1, api.accountCatalogCalls)
        assertEquals(1, api.publicCatalogCalls)
        assertEquals(1, api.guideCalls)
        assertEquals(ServicePortalScope.ACCOUNT, service.state.value.results?.scope)
        assertNull(service.state.value.selected)
        assertNull(service.state.value.guide)
        assertEquals(ServicePortalLoginStatus.SUCCEEDED, service.state.value.loginStatus)
        assertFalse(service.state.value.busy)
        assertNull(service.state.value.error)
    }

    @Test
    fun `authentication cookies are rejected when returned account binding differs`() = runTest {
        val auth = Auth().apply {
            result = UpcServicePortalAuthResult.Success(
                binding = SchoolAccountReference(SchoolAccountId("syntheticOther"), AccountGeneration(9)),
                cookies = listOf(syntheticCookie()),
                source = UpcServicePortalAuthSource.PASSWORD,
            )
        }
        val api = Api()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.search("leave")
        runCurrent()

        assertEquals(0, api.accountCatalogCalls)
        assertEquals(1, api.publicCatalogCalls)
        assertEquals(ServicePortalScope.PUBLIC, service.state.value.results?.scope)
        assertEquals(ServicePortalFailure.IDENTITY_MISMATCH, service.state.value.authenticationNotice)
        assertEquals(1, auth.clearCalls)
    }

    @Test
    fun `access denied and network errors do not retry anonymously and same query preserves old data`() = runTest {
        val auth = Auth()
        val api = Api()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)
        service.search("leave")
        runCurrent()
        val oldPage = service.state.value.results
        assertEquals(ServicePortalScope.ACCOUNT, oldPage?.scope)

        api.accountCatalogFailures += ServicePortalFailure.ACCESS_DENIED
        service.search("leave")
        runCurrent()
        assertEquals(ServicePortalFailure.ACCESS_DENIED, service.state.value.error)
        assertTrue(service.state.value.refreshFailed)
        assertEquals(oldPage, service.state.value.results)
        assertEquals(0, api.publicCatalogCalls)

        api.accountCatalogFailures += ServicePortalFailure.NETWORK
        service.search("leave")
        runCurrent()
        assertEquals(ServicePortalFailure.NETWORK, service.state.value.error)
        assertEquals(0, api.publicCatalogCalls)
        assertEquals(1, auth.calls)
    }

    @Test
    fun `expired existing catalog session gets one recovery and one retry`() = runTest {
        val auth = Auth()
        val api = Api()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)
        service.search("leave")
        runCurrent()
        api.accountCatalogFailures += ServicePortalFailure.AUTH_REQUIRED

        service.search("leave")
        runCurrent()

        assertEquals(2, auth.calls)
        assertEquals(3, api.accountCatalogCalls)
        assertEquals(0, api.publicCatalogCalls)
        assertEquals(ServicePortalScope.ACCOUNT, service.state.value.results?.scope)
        assertNull(service.state.value.error)
    }

    @Test
    fun `newly authenticated catalog session is not reauthenticated after an immediate 401`() = runTest {
        val auth = Auth()
        val api = Api().apply { accountCatalogFailures += ServicePortalFailure.AUTH_REQUIRED }
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.search("leave")
        runCurrent()

        assertEquals(1, auth.calls)
        assertEquals(1, api.accountCatalogCalls)
        assertEquals(1, api.publicCatalogCalls)
        assertEquals(ServicePortalScope.PUBLIC, service.state.value.results?.scope)
        assertEquals(ServicePortalFailure.AUTH_REQUIRED, service.state.value.authenticationNotice)
    }

    @Test
    fun `repeated expiry after one recovery falls back only for catalog`() = runTest {
        val auth = Auth()
        val api = Api()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)
        service.search("leave")
        runCurrent()
        api.accountCatalogFailures += ServicePortalFailure.AUTH_REQUIRED
        api.accountCatalogFailures += ServicePortalFailure.AUTH_REQUIRED

        service.search("leave")
        runCurrent()

        assertEquals(2, auth.calls)
        assertEquals(1, api.publicCatalogCalls)
        assertEquals(ServicePortalScope.PUBLIC, service.state.value.results?.scope)
        assertEquals(ServicePortalFailure.AUTH_REQUIRED, service.state.value.authenticationNotice)
    }

    @Test
    fun `synchronous cache access detects account change before collector runs`() = runTest {
        val account = Account()
        val auth = Auth()
        val api = Api()
        val service = ServicePortalService(account, auth, api, backgroundScope)
        service.search("leave")
        runCurrent()
        assertNotNull(service.state.value.catalog)

        account.change("syntheticB", generation = 2)
        service.pageSearch(2)

        assertNull(service.state.value.catalog)
        assertNull(service.state.value.results)
        assertEquals(1, auth.clearCalls)
    }

    @Test
    fun `late catalog response cannot repopulate after account change and invalidation`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val account = Account()
        val api = object : Api() {
            override suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog {
                if (session != null) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                return super.catalog(session)
            }
        }
        val service = ServicePortalService(account, Auth(), api, backgroundScope)
        service.search("leave")
        runCurrent()
        assertTrue(started.isCompleted)

        account.change("syntheticB", generation = 2)
        service.invalidateForAccountChange()
        release.complete(Unit)
        runCurrent()

        assertNull(service.state.value.catalog)
        assertNull(service.state.value.results)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `switching tabs while catalog loads waits for the final tab and accepts only its result`() = runTest {
        val api = DelayedCatalogApi()
        val auth = Auth()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(api.started.isCompleted)

        service.selectTab(ServicePortalTab.TODOS)
        service.selectTab(ServicePortalTab.APPLICATIONS)
        assertEquals(ServicePortalTab.APPLICATIONS, service.state.value.tab)
        assertTrue(service.state.value.busy)
        assertEquals(0, api.todoCalls + api.applicationCalls)
        assertEquals(1, auth.calls)

        api.release.complete(Unit)
        runCurrent()

        assertEquals(0, api.todoCalls)
        assertEquals(1, api.applicationCalls)
        assertEquals(1, auth.calls)
        assertEquals(ServicePortalTab.APPLICATIONS, service.state.value.tab)
        assertNotNull(service.state.value.applications)
        assertNull(service.state.value.error)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `late catalog error is not shown after switching to personal tab`() = runTest {
        val api = DelayedCatalogApi(ServicePortalFailure.NETWORK)
        val auth = Auth()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(api.started.isCompleted)

        service.selectTab(ServicePortalTab.TODOS)
        api.release.complete(Unit)
        runCurrent()

        assertEquals(ServicePortalTab.TODOS, service.state.value.tab)
        assertEquals(1, api.todoCalls)
        assertEquals(0, api.applicationCalls)
        assertEquals(1, auth.calls)
        assertNotNull(service.state.value.todos)
        assertNull(service.state.value.error)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `late auth required catalog result after tab switch cannot start another login or retry`() = runTest {
        val api = DelayedCatalogApi(ServicePortalFailure.AUTH_REQUIRED)
        val auth = Auth()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(api.started.isCompleted)
        assertEquals(1, auth.calls)

        service.selectTab(ServicePortalTab.TODOS)
        api.release.complete(Unit)
        runCurrent()

        assertEquals(ServicePortalTab.TODOS, service.state.value.tab)
        assertEquals(1, auth.calls)
        assertEquals(0, api.accountCatalogCalls)
        assertEquals(0, api.publicCatalogCalls)
        assertEquals(1, api.todoCalls)
        assertNotNull(service.state.value.todos)
    }

    @Test
    fun `late successful result cannot publish after switching away and back to its tab`() = runTest {
        val api = LateCatalogApi()
        val service = ServicePortalService(Account(), Auth(), api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(api.started.isCompleted)

        service.selectTab(ServicePortalTab.TODOS)
        service.selectTab(ServicePortalTab.SERVICES)
        api.release.complete(Unit)
        runCurrent()

        assertEquals(2, api.requests)
        assertEquals("Fresh leave result", service.state.value.results?.items?.single()?.name)
        assertNull(service.state.value.error)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `late error cannot publish after switching away and back to its tab`() = runTest {
        val api = LateCatalogApi(ServicePortalFailure.NETWORK)
        val service = ServicePortalService(Account(), Auth(), api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(api.started.isCompleted)

        service.selectTab(ServicePortalTab.TODOS)
        service.selectTab(ServicePortalTab.SERVICES)
        api.release.complete(Unit)
        runCurrent()

        assertEquals(2, api.requests)
        assertEquals("Fresh leave result", service.state.value.results?.items?.single()?.name)
        assertNull(service.state.value.error)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `in flight authentication is reused by the newly selected personal tab`() = runTest {
        val auth = DelayedAuth()
        val api = Api()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(auth.started.isCompleted)
        service.selectTab(ServicePortalTab.TODOS)
        service.selectTab(ServicePortalTab.APPLICATIONS)
        assertEquals(1, auth.calls)
        assertEquals(0, api.todoCalls + api.applicationCalls)

        auth.release.complete(Unit)
        runCurrent()

        assertEquals(1, auth.calls)
        assertEquals(0, api.todoCalls)
        assertEquals(1, api.applicationCalls)
        assertNotNull(service.state.value.applications)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `failed in flight authentication is surfaced without a queued second password attempt`() = runTest {
        val auth = DelayedAuth { _, _ ->
            UpcServicePortalAuthResult.Failure(UpcServicePortalAuthFailure.INVALID_CREDENTIALS)
        }
        val api = Api()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(auth.started.isCompleted)
        service.selectTab(ServicePortalTab.TODOS)
        auth.release.complete(Unit)
        runCurrent()

        assertEquals(1, auth.calls)
        assertEquals(ServicePortalFailure.INVALID_CREDENTIALS, service.state.value.error)
        assertEquals(0, api.todoCalls)
        assertFalse(service.state.value.busy)
    }

    @Test
    fun `late old account authentication cannot clear the new account session`() = runTest {
        val account = Account()
        val auth = LateFirstGenerationAuth()
        val api = Api()
        val service = ServicePortalService(account, auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(auth.firstGenerationStarted.isCompleted)

        account.change("syntheticB", generation = 2)
        service.search("leave")
        runCurrent()
        assertEquals(ServicePortalScope.ACCOUNT, service.state.value.results?.scope)
        val clearCountAfterBAuthentication = auth.clearCalls

        auth.releaseFirstGeneration.complete(Unit)
        runCurrent()

        assertEquals(clearCountAfterBAuthentication, auth.clearCalls)
        assertEquals(2, auth.calls)
        service.todos()
        runCurrent()
        assertEquals(1, api.todoCalls)
        assertEquals(2, auth.calls)
        assertNull(service.state.value.error)
    }

    @Test
    fun `A to B to A keeps the newest account session when the first A result arrives late`() = runTest {
        val account = Account()
        val auth = LateFirstGenerationAuth()
        val api = Api()
        val service = ServicePortalService(account, auth, api, backgroundScope)

        service.search("leave")
        runCurrent()
        assertTrue(auth.firstGenerationStarted.isCompleted)

        account.change("syntheticB", generation = 2)
        service.search("leave")
        runCurrent()
        assertEquals(ServicePortalScope.ACCOUNT, service.state.value.results?.scope)

        account.change("syntheticA", generation = 3)
        service.search("leave")
        runCurrent()
        assertEquals(ServicePortalScope.ACCOUNT, service.state.value.results?.scope)
        val clearCountAfterNewestAAuthentication = auth.clearCalls

        auth.releaseFirstGeneration.complete(Unit)
        runCurrent()

        assertEquals(clearCountAfterNewestAAuthentication, auth.clearCalls)
        assertEquals(3, auth.calls)
        service.todos()
        runCurrent()
        assertEquals(1, api.todoCalls)
        assertEquals(3, auth.calls)
        assertNull(service.state.value.error)
    }

    @Test
    fun `late invalid cookie result cannot clear same reference session from a newer business epoch`() = runTest {
        val account = Account()
        lateinit var service: ServicePortalService
        val auth = EpochRaceAuth {
            service.invalidateForAccountChange()
            service.search("leave")
        }
        val api = Api()
        val originalReference = account.accountReference.value
        service = ServicePortalService(
            account,
            auth,
            api,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )

        service.search("leave")
        runCurrent()

        assertEquals(originalReference, account.accountReference.value)
        assertEquals(2, auth.calls)
        assertEquals(1, auth.clearCalls)
        assertEquals(ServicePortalScope.ACCOUNT, service.state.value.results?.scope)

        service.todos()
        runCurrent()

        assertEquals(2, auth.calls)
        assertEquals(1, auth.clearCalls)
        assertEquals(1, api.todoCalls)
        assertNull(service.state.value.error)
    }

    @Test
    fun `refreshing a later search page after the directory shrinks to empty returns to page one`() = runTest {
        val api = ResizableApi(itemCount = 45)
        val service = ServicePortalService(Account(), Auth(), api, backgroundScope)

        service.search("leave")
        runCurrent()
        service.pageSearch(3)
        assertEquals(3, service.state.value.searchPage)
        assertEquals(3, service.state.value.results?.page)

        api.itemCount = 0
        service.search("leave")
        runCurrent()

        assertEquals(1, service.state.value.searchPage)
        assertEquals(1, service.state.value.results?.page)
        assertEquals(0, service.state.value.results?.total)
        assertTrue(service.state.value.results?.pageInRange == true)
        assertTrue(service.state.value.results?.items?.isEmpty() == true)
    }

    @Test
    fun `refreshing a later search page after the directory shrinks to nonempty returns to page one`() = runTest {
        val api = ResizableApi(itemCount = 45)
        val service = ServicePortalService(Account(), Auth(), api, backgroundScope)

        service.search("leave")
        runCurrent()
        service.pageSearch(3)
        assertEquals(3, service.state.value.searchPage)

        api.itemCount = 5
        service.search("leave")
        runCurrent()

        assertEquals(1, service.state.value.searchPage)
        assertEquals(1, service.state.value.results?.page)
        assertEquals(5, service.state.value.results?.total)
        assertEquals(5, service.state.value.results?.items?.size)
        assertTrue(service.state.value.results?.pageInRange == true)
    }

    @Test
    fun `closing guide keeps search query and page and rejects a late detail`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = object : Api() {
            override suspend fun guide(session: ServicePortalSession?, item: ServicePortalItem): ServicePortalGuide {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return super.guide(session, item)
            }
        }
        val service = ServicePortalService(Account(), Auth(), api, backgroundScope)
        service.search("leave")
        runCurrent()
        val page = service.state.value.results
        val selected = page!!.items.single()

        service.openGuide(selected)
        runCurrent()
        assertTrue(started.isCompleted)
        service.closeGuide()
        release.complete(Unit)
        runCurrent()

        assertNull(service.state.value.selected)
        assertNull(service.state.value.guide)
        assertEquals(page, service.state.value.results)
        assertEquals("leave", service.state.value.keyword)
    }

    @Test
    fun `personal requests use status filtered account session and never public catalog`() = runTest {
        val api = Api()
        val auth = Auth()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.todos(page = 2, pageSize = 10)
        runCurrent()
        service.applications(page = 3, pageSize = 15)
        runCurrent()

        assertEquals(1, api.todoCalls)
        assertEquals(1, api.applicationCalls)
        assertEquals(0, api.publicCatalogCalls)
        assertEquals(0, api.accountCatalogCalls)
        assertNull(service.state.value.error)
        assertEquals(1, auth.calls)
    }

    @Test
    fun `personal request does not resubmit credentials after a fresh session is immediately denied`() = runTest {
        val api = Api().apply { personalFailures += ServicePortalFailure.AUTH_REQUIRED }
        val auth = Auth()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.todos()
        runCurrent()

        assertEquals(1, auth.calls)
        assertEquals(1, api.todoCalls)
        assertEquals(0, api.publicCatalogCalls)
        assertNull(service.state.value.todos)
        assertEquals(ServicePortalFailure.AUTH_REQUIRED, service.state.value.error)
        assertEquals(ServicePortalLoginStatus.FAILED, service.state.value.loginStatus)
    }

    @Test
    fun `personal request recovers an expired already bound session once`() = runTest {
        val api = Api()
        val auth = Auth()
        val service = ServicePortalService(Account(), auth, api, backgroundScope)

        service.todos()
        runCurrent()
        assertEquals(1, auth.calls)
        assertEquals(1, api.todoCalls)

        api.personalFailures += ServicePortalFailure.AUTH_REQUIRED
        service.todos()
        runCurrent()

        assertEquals(2, auth.calls)
        assertEquals(3, api.todoCalls)
        assertNotNull(service.state.value.todos)
        assertNull(service.state.value.error)
    }

    private fun assertNotNull(value: Any?) {
        assertTrue(value != null)
    }
}

private fun syntheticCookie() = UpcServicePortalCookie(
    name = "SESSION",
    value = "syntheticCookie",
    domain = UpcServicePortalCookie.HOST,
    path = "/",
    secure = true,
    httpOnly = true,
    expiresAtEpochSeconds = null,
    hostOnly = true,
)
