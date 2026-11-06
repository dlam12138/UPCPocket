package com.upcpocket.app.update

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 手动真实网络验收；默认跳过，避免常规单测依赖第三方公共线路。
 * 运行：设置 UPC_UPDATE_REAL_NETWORK=1 后执行本测试。
 */
class RealUpdateNetworkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun downloadsPublishedV2ApkThroughFixedPrimarySource() = runBlocking {
        assumeTrue(System.getenv("UPC_UPDATE_REAL_NETWORK") == "1")
        val source = UpdateSources.production.first { it.id == UpdateSources.PRIMARY_ID }
        val expectedBytes = 4_625_769L
        val expectedSha = "00edd4e78ed2e0fc3436a21960d6a66205559946848e0cde514ada08dfc47eef"
        val destination = File(temporaryFolder.newFolder(), "UPC-Pocket-2.0.apk")
        HttpUpdateTransport().download(
            url = "${source.baseUrl}/releases/download/v2.0/UPC-Pocket-2.0.apk",
            source = source,
            expectedBytes = expectedBytes,
            destination = destination,
            onProgress = {},
        )
        assertEquals(expectedBytes, destination.length())
        assertEquals(expectedSha, UpdateTestFixtures.sha256(destination.readBytes()))
    }
}
