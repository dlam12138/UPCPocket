package com.upcpocket.app.data.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 预约提交前/提交时的会话上下文边界（用可控 ReserveTransport，不联网、不真实预约）。
 *
 *  - 挂起验证码预检 → 期间换号（clear/publish 新会话）→ 恢复：0 次 POST、
 *    不清理新会话、不在新代次登记防重键；
 *  - 同一会话正常：1 次 POST、重复仍拒绝；
 *  - 迟到预检失败不清新账号会话；取消保持取消语义（不经过本 seam）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryReserveSessionContextTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var transport: FakeReserveTransport
    private lateinit var repo: LibrarySeatRepository

    private fun session(token: String, userId: String) = LibrarySeatSession(
        token = token,
        user = LibraryUser(id = userId, username = "u-$userId", name = "n-$userId"),
    )

    private suspend fun publish(s: LibrarySeatSession) =
        repo.publishAuthenticatedSession(s) { true }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        transport = FakeReserveTransport()
        repo = LibrarySeatRepository(
            sessionAllowed = { true },
            reserveTransport = transport,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `同一会话仅 1 次 POST 且重复仍拒绝`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        val first = repo.reserve("2026-11-07", "s1", "840", "960")
        assertEquals(LibraryReservationAttempt.SENT, first)
        assertEquals(1, transport.postCalls)
        // 同 key 再次提交：防重闩拒绝，不再 POST。
        val second = runCatching { repo.reserve("2026-11-07", "s1", "840", "960") }
        assertTrue(second.exceptionOrNull() is LibraryReservationDuplicateException)
        assertEquals(1, transport.postCalls)
    }

    @Test
    fun `预检期间换号恢复后 0 次 POST 不占新会话防重键`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        val gate = CompletableDeferred<Boolean>()
        transport.captchaGate = gate
        val job = async { runCatching { repo.reserve("2026-11-07", "s1", "840", "960") } }
        // 等待预检确实进入挂起点
        transport.captchaEntered.await()
        assertEquals("预检已发出但未完成", 0, transport.postCalls)

        // 期间换号：清会话 + 发布新账号/新 token（代次递增）
        repo.clearSession()
        publish(session("token-B", "user-B"))
        val newGenerationBefore = repo.currentSessionGeneration()

        // 恢复预检
        gate.complete(false)
        val result = job.await()
        advanceUntilIdle()

        val e = result.exceptionOrNull()
        assertTrue("过期上下文必须整体中止，实际=$e", e is LibraryReservationContextStaleException)
        assertEquals("不得发出任何 POST", 0, transport.postCalls)
        // 新会话仍在、未被迟到预检清理
        assertEquals("token-B", repo.session.value?.token)
        assertEquals(newGenerationBefore, repo.currentSessionGeneration())
        // 旧 key 不得占用新会话防重闩
        val key = LibraryReservationRequest(
            LibraryReservationRequest.SYSTEM_LIBSEAT, "user-A",
            "2026-11-07", "s1", "840", "960",
        )
        assertFalse(repo.hasAttempted(key))
        // 新会话可以正常登记（说明闩没被旧上下文污染）
        assertTrue(repo.attemptLatch.registerOrReject(key, newGenerationBefore))
    }

    @Test
    fun `验证码返回后立即换号则不登记不发 POST`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        val newGeneration = repo.currentSessionGeneration() + 1
        // 预检读取完成、即将返回时切换会话：repository 在「预检后/登记前」的
        // isSameSession 必须拒绝，不得登记、不得 POST、不得清新会话。
        transport.onCaptchaReturn = {
            repo.clearSession()
            kotlinx.coroutines.runBlocking { publish(session("token-B", "user-B")) }
        }
        val result = runCatching {
            repo.reserve("2026-11-07", "s1", "840", "960")
        }
        advanceUntilIdle()
        assertTrue(
            "预检后换号必须中止，实际=${result.exceptionOrNull()}",
            result.exceptionOrNull() is LibraryReservationContextStaleException,
        )
        assertEquals(0, transport.postCalls)
        assertEquals("token-B", repo.session.value?.token) // 新会话未被清理
        val key = LibraryReservationRequest(
            LibraryReservationRequest.SYSTEM_LIBSEAT, "user-A",
            "2026-11-07", "s1", "840", "960",
        )
        assertFalse("旧上下文不得占新会话防重键", repo.hasAttempted(key))
        assertEquals(newGeneration, repo.currentSessionGeneration())
    }

    @Test
    fun `POST 使用的 token 来自进入时捕获快照`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        // 即使 onBeforePost 里当前会话被改写，POST 也只能带捕获的 token-A。
        transport.onBeforePost = {
            repo.clearSession()
            kotlinx.coroutines.runBlocking { publish(session("token-B", "user-B")) }
        }
        val attempt = repo.reserve("2026-11-07", "s1", "840", "960")
        assertEquals(LibraryReservationAttempt.SENT, attempt)
        assertEquals(listOf("token-A"), transport.postTokens)
        assertEquals("token-B", repo.session.value?.token)
    }

    @Test
    fun `预检失败不冒充已发送不清新会话`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        transport.captchaFails = true
        val result = runCatching { repo.reserve("2026-11-07", "s1", "840", "960") }
        advanceUntilIdle()
        assertTrue(result.exceptionOrNull() is LibraryReservationPreflightFailedException)
        assertEquals(0, transport.postCalls)
        assertEquals("token-A", repo.session.value?.token) // 未被清理
    }

    @Test
    fun `验证码开启 NOT_SENT 不登记不 POST`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        transport.captchaResult = true
        val attempt = repo.reserve("2026-11-07", "s1", "840", "960")
        assertEquals(LibraryReservationAttempt.NOT_SENT, attempt)
        assertEquals(0, transport.postCalls)
        val key = LibraryReservationRequest(
            LibraryReservationRequest.SYSTEM_LIBSEAT, "user-A",
            "2026-11-07", "s1", "840", "960",
        )
        assertFalse(repo.hasAttempted(key))
    }

    @Test
    fun `旧预检失败不会清理后来发布的会话`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        val gate = CompletableDeferred<Boolean>()
        transport.captchaGate = gate
        val job = async { runCatching { repo.reserve("2026-11-07", "s1", "840", "960") } }
        transport.captchaEntered.await()
        repo.clearSession()
        publish(session("token-B", "user-B"))
        gate.completeExceptionally(IllegalStateException("preflight failed"))

        assertTrue(job.await().exceptionOrNull() is LibraryReservationContextStaleException)
        assertEquals(0, transport.postCalls)
        assertEquals("token-B", repo.session.value?.token)
        assertFalse(repo.hasAttempted(LibraryReservationRequest(
            LibraryReservationRequest.SYSTEM_LIBSEAT, "user-B", "2026-11-07", "s1", "840", "960",
        )))
    }

    @Test
    fun `预检期间关闭账号门闩不会登记或发出预约`() = runTest(dispatcher) {
        var accountAllowed = true
        repo = LibrarySeatRepository(reserveTransport = transport, sessionAllowed = { accountAllowed })
        publish(session("token-A", "user-A"))
        val gate = CompletableDeferred<Boolean>()
        transport.captchaGate = gate
        val job = async { runCatching { repo.reserve("2026-11-07", "s1", "840", "960") } }
        transport.captchaEntered.await()
        accountAllowed = false
        gate.complete(false)

        assertTrue(job.await().exceptionOrNull() is LibraryReservationContextStaleException)
        assertEquals(0, transport.postCalls)
        assertFalse(repo.hasAttempted(LibraryReservationRequest(
            LibraryReservationRequest.SYSTEM_LIBSEAT, "user-A", "2026-11-07", "s1", "840", "960",
        )))
    }

    @Test
    fun `预检取消保持取消且不登记不提交`() = runTest(dispatcher) {
        publish(session("token-A", "user-A"))
        transport.captchaGate = CompletableDeferred()
        val job = async { repo.reserve("2026-11-07", "s1", "840", "960") }
        transport.captchaEntered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(0, transport.postCalls)
        assertFalse(repo.hasAttempted(LibraryReservationRequest(
            LibraryReservationRequest.SYSTEM_LIBSEAT, "user-A", "2026-11-07", "s1", "840", "960",
        )))
    }

    private class FakeReserveTransport : ReserveTransport {
        val captchaEntered = CompletableDeferred<Unit>()
        var captchaGate: CompletableDeferred<Boolean>? = null
        var captchaFails = false
        var captchaResult = false
        var onCaptchaReturn: (() -> Unit)? = null
        var onBeforePost: (() -> Unit)? = null
        var postCalls = 0
        val postTokens = mutableListOf<String>()

        override suspend fun captchaEnabled(token: String): Boolean {
            captchaEntered.complete(Unit)
            if (captchaFails) throw RuntimeException("settings read failed")
            val result = captchaGate?.await() ?: captchaResult
            onCaptchaReturn?.invoke()
            return result
        }

        override suspend fun postReservation(
            token: String, date: String, seatId: String,
            startId: String, endId: String, user: LibraryUser,
        ): String {
            onBeforePost?.invoke()
            postCalls++
            postTokens += token
            return """{"status":true,"data":null}"""
        }
    }
}
