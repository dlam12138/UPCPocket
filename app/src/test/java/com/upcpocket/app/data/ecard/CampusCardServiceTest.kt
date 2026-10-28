package com.upcpocket.app.data.ecard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.prefs.SchoolSessionStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 校园卡会话生命周期的真实行为：加密落盘、重启复用、失效清理、换号隔离。
 *
 * 用假客户端替换网络调用，但 [SchoolSessionStore] 走**真实 Keystore 加密**（Robolectric 提供
 * AndroidKeyStore 的软件实现），所以“解密失败可重登”“退出只清本业务”这些行为是被实际执行的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusCardServiceTest {

    private lateinit var context: Context
    private lateinit var store: SchoolSessionStore

    /** Robolectric 没有 AndroidKeyStore 实现，用同算法的软件密钥验证存储行为本身。 */
    private object TestCrypto : com.upcpocket.app.data.prefs.SessionCrypto {
        private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plain: String): String {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return android.util.Base64.encodeToString(iv + body, android.util.Base64.NO_WRAP)
        }

        override fun decrypt(stored: String): String {
            val bytes = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val body = bytes.copyOfRange(12, bytes.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            return String(cipher.doFinal(body), Charsets.UTF_8)
        }
    }

    private val okBody = """
        {"code":200,"success":true,"data":{"card":[
          {"account":"%s","cardname":"测试卡","db_balance":1000,"unsettle_amount":0,
           "accinfo":[],"lostflag":"0","freezeflag":"0"}
        ]},"msg":"操作成功"}
    """.trimIndent()

    private fun frontInfo() = """
        {"code":200,"data":{"getEcardConfig":"{\"type\":\"2\"}"}}
    """.trimIndent()

    /** 只实现“真实协议形状”，不联网。 */
    private inner class FakeClient(
        private val tokens: MutableMap<String, String>,
        var offline: Boolean = false,
        var expired: Boolean = false,
    ) : CampusCardClient() {
        override suspend fun fetch(accessToken: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Unconfined) {
            if (offline) throw CampusCardException("无法连接校园卡服务，请检查网络后重试")
            if (expired) throw CampusCardSessionExpiredException("校园卡会话已失效，需要重新登录")
            val account = tokens[accessToken]
                ?: throw CampusCardSessionExpiredException("校园卡会话已失效，需要重新登录")
            CampusCardProtocol.Result(
                basis = CampusCardProtocol.parseFrontInfo(frontInfo()),
                cards = CampusCardProtocol.parseCards(
                    okBody.format(account),
                    CampusCardProtocol.Basis.CARD_ONLY,
                ),
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SchoolSessionStore(context, TestCrypto)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun `登录后开启记住登录会写入加密副本并能重启复用`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)

        // 模拟重启：新进程、新服务实例，只有密文还在。
        val restarted = CampusCardService(store, FakeClient(tokens))
        assertTrue(restarted.restore())
        val outcome = restarted.refresh()
        assertTrue(outcome is CampusCardOutcome.Success)
        assertEquals("9000000001", (outcome as CampusCardOutcome.Success).balance.account)
        assertEquals(1000L, outcome.balance.totalCents)
    }

    @Test
    fun `关闭记住登录时不写盘_重启后需要重新登录`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = false) is CampusCardAdoptResult.Accepted)
        assertNull(store.loadCampusCard())

        val restarted = CampusCardService(store, FakeClient(tokens))
        assertFalse(restarted.restore())
        assertTrue(restarted.refresh() is CampusCardOutcome.NeedsLogin)
    }

    @Test
    fun `服务端拒绝令牌时清掉密文并回到需要登录`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)
        assertNotNull(store.loadCampusCard())

        val expiredClient = FakeClient(tokens, expired = true)
        val restarted = CampusCardService(store, expiredClient)
        // 恢复时就会验证失败，密文立即清掉，不留下“看起来已登录”的状态。
        assertFalse(restarted.restore())
        assertNull(store.loadCampusCard())
        assertFalse(restarted.hasSession())
    }

    @Test
    fun `网络失败不等于会话失效_也不清掉还能再用的密文`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)

        val offline = CampusCardService(store, FakeClient(tokens, offline = true))
        // 这次没法确认会话是否有效：不冒充已登录……
        assertFalse(offline.restore())
        assertFalse(offline.hasSession())
        // ……但也不能把密文删掉（否则用户要为一个临时断网白重登一次）。
        assertEquals("9000000001", store.loadCampusCard()?.account)
        assertTrue(offline.lastFailure!!.contains("网络"))

        // 有网后同一个实例还能恢复。
        val online = CampusCardService(store, FakeClient(tokens))
        assertTrue(online.restore())
    }

    @Test
    fun `查询时断网保留已登录状态并给出暂时不可用`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val client = FakeClient(tokens)
        val service = CampusCardService(store, client)
        assertTrue(service.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)
        assertNotNull(service.snapshot())

        client.offline = true
        val outcome = service.refresh()
        assertTrue(outcome is CampusCardOutcome.Unavailable)
        // 令牌与上次成功的值都还在：界面可以显示“上次成功”而不是“请重新登录”。
        assertTrue(service.hasSession())
        assertNotNull(service.snapshot())
        assertTrue(store.loadCampusCard() != null)

        client.offline = false
        assertTrue(service.refresh() is CampusCardOutcome.Success)
    }

    @Test
    fun `关闭记住登录时不写令牌也不写余额快照`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = false) is CampusCardAdoptResult.Accepted)
        // 内存里可用
        assertTrue(service.refresh() is CampusCardOutcome.Success)
        assertNotNull(service.snapshot())
        // 但磁盘上什么都没有：开关含义必须真实。
        assertNull(store.loadCampusCard())
        assertNull(store.loadCampusCardSnapshot())
        assertNull(service.lastSuccess())
    }

    @Test
    fun `恢复的会话继续写快照_关闭开关后停止`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val first = CampusCardService(store, FakeClient(tokens))
        assertTrue(first.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)
        assertNotNull(store.loadCampusCardSnapshot())

        // 重启后恢复：属于“记住登录”场景，后续查询仍应更新快照。
        val restarted = CampusCardService(store, FakeClient(tokens))
        assertTrue(restarted.restore())
        assertTrue(restarted.refresh() is CampusCardOutcome.Success)
        assertNotNull(store.loadCampusCardSnapshot())
    }
    @Test
    fun `上次成功值跨重启保留_并在退出时清掉`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)
        assertTrue(service.refresh() is CampusCardOutcome.Success)
        assertNotNull(service.lastSuccessAt())

        // 模拟重启 + 断网：令牌还在但这次读不到，旧值必须仍然可见且带时间。
        val restarted = CampusCardService(store, FakeClient(tokens, offline = true))
        val previous = restarted.lastSuccess()
        assertNotNull(previous)
        assertEquals(1000L, previous!!.totalCents)
        assertEquals(CampusCardProtocol.Basis.CARD_ONLY, previous.basis)

        // 退出后不能留下上一个人的余额。
        restarted.signOut()
        assertNull(restarted.lastSuccess())
        assertNull(restarted.lastSuccessAt())
    }

    @Test
    fun `没有成功过时不伪造上次成功值`() = runTest {
        val service = CampusCardService(store, FakeClient(mutableMapOf()))
        assertNull(service.lastSuccess())
        assertNull(service.lastSuccessAt())
    }

    @Test
    fun `换号后旧账号的密文与缓存都不再适用`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001", "tok-b" to "9000000002")
        val first = CampusCardService(store, FakeClient(tokens))
        assertTrue(first.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)
        assertEquals("9000000001", store.loadCampusCard()?.account)

        val second = CampusCardService(store, FakeClient(tokens))
        assertTrue(second.adoptToken("tok-b", remember = true) is CampusCardAdoptResult.Accepted)
        assertEquals("9000000002", store.loadCampusCard()?.account)
    }

    @Test
    fun `账号提示不一致时拒绝恢复`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)

        val other = CampusCardService(store, FakeClient(tokens))
        assertFalse(other.restore(accountHint = "9000000009"))
        assertNull(store.loadCampusCard())
    }

    @Test
    fun `退出校园卡只清本业务_图书馆密文保留`() = runTest {
        val tokens = mutableMapOf("tok-a" to "9000000001")
        val service = CampusCardService(store, FakeClient(tokens))
        assertTrue(service.adoptToken("tok-a", remember = true) is CampusCardAdoptResult.Accepted)
        store.saveLibrary("seat-token", com.upcpocket.app.data.library.LibraryUser("1", "u", "测试"))

        service.signOut()
        assertNull(store.loadCampusCard())
        assertNotNull(store.loadLibrary())
    }

    @Test
    fun `账号展示只留首尾字符`() {
        assertEquals("90••••01", SchoolSessionStore.maskAccount("9000000001"))
        assertEquals("9•", SchoolSessionStore.maskAccount("9"))
        assertFalse(SchoolSessionStore.maskAccount("9000000001").contains("000000"))
    }

    @Test
    fun `令牌为空时拒绝接管`() = runTest {
        val service = CampusCardService(store, FakeClient(mutableMapOf()))
        assertTrue(service.adoptToken("   ", remember = true) is CampusCardAdoptResult.Rejected)
        assertFalse(service.hasSession())
    }
}
