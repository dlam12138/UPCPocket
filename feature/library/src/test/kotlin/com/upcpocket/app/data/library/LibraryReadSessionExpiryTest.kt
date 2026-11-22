package com.upcpocket.app.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 读取请求的会话失效边界。旧会话的请求在换号后才报过期时只能清理它捕获的会话，
 * 不得清掉并发建立的新会话。当前会话自身过期仍会清理，交给页面按过期 token 上报。
 * 用 timeSearchSeats 的会话门闩在请求内触发，全部为本地逻辑，不发出网络请求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryReadSessionExpiryTest {

    private val dispatcher = StandardTestDispatcher()
    private val key = LibraryTimeSearchKey(
        buildingId = "b1",
        roomId = "r1",
        date = "2026-11-22",
        beginMinute = 480,
        endMinute = 600,
        beginText = "08:00",
        endText = "10:00",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `旧会话请求过期不清掉换号后的新会话`() = runTest(dispatcher) {
        var checks = 0
        var switchAt = Int.MAX_VALUE
        var generationAfterSwitch = -1L
        lateinit var repo: LibrarySeatRepository
        repo = LibrarySeatRepository(sessionAllowed = {
            checks++
            if (checks == switchAt) {
                repo.clearSession()
                runBlocking { publish(repo, session("token-B", "user-B")) }
                generationAfterSwitch = repo.currentSessionGeneration()
                false
            } else {
                true
            }
        })
        publish(repo, session("token-A", "user-A"))
        switchAt = checks + 2 // 入口检查放行，请求内检查触发换号

        val result = runCatching { repo.timeSearchSeats(key, page = 1, batch = 10) }

        assertTrue(result.exceptionOrNull() is LibrarySessionExpiredException)
        assertEquals("token-B", repo.session.value?.token)
        assertEquals(
            "旧请求的过期不得推进新会话代次",
            generationAfterSwitch,
            repo.currentSessionGeneration(),
        )
    }

    @Test
    fun `当前会话自身过期仍会清理并上报`() = runTest(dispatcher) {
        var checks = 0
        var denyAt = Int.MAX_VALUE
        val repo = LibrarySeatRepository(sessionAllowed = {
            checks++
            checks != denyAt
        })
        publish(repo, session("token-A", "user-A"))
        val generationBefore = repo.currentSessionGeneration()
        denyAt = checks + 2

        val result = runCatching { repo.timeSearchSeats(key, page = 1, batch = 10) }

        assertTrue(result.exceptionOrNull() is LibrarySessionExpiredException)
        assertNull(repo.session.value)
        assertEquals(generationBefore + 1, repo.currentSessionGeneration())
    }

    private suspend fun publish(repo: LibrarySeatRepository, s: LibrarySeatSession) {
        repo.publishAuthenticatedSession(s, guard = { true })
    }

    private fun session(token: String, userId: String) = LibrarySeatSession(
        token = token,
        user = LibraryUser(id = userId, username = "u-$userId", name = "n-$userId"),
    )
}
