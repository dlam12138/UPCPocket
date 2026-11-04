package com.upcpocket.app.data.chaoxing

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实 reservepc 租户上下文桥接回放（默认跳过）。
 *
 * 上下文来自本机已登录会话页面中已经渲染的 `reserveIdEnc`，只写入私有临时文件；
 * 测试验证“WebView 捕获上下文 -> Repository 直接调用 item/list/time”的桥接路径，
 * 不访问入口页面、不提交预约。
 */
class ChaoxingLiveReserveContextTest {

    @Test
    fun `captured tenant context drives the reserve list without page discovery`() = runBlocking {
        val path = System.getenv("CHAOXING_RESERVE_CONTEXT_FILE")
        val file = path?.let(::File)
        assumeTrue("manual reserve context test requires a private file", file?.isFile == true)
        val root = Json.parseToJsonElement(file!!.readText()).jsonObject
        val cookie = root.getValue("cookie").jsonPrimitive.content
        assumeTrue(cookie.isNotBlank())
        val contexts = root.getValue("contexts").jsonArray
        assertTrue(contexts.isNotEmpty())
        val repository = ChaoxingRepository()
        contexts.forEach { raw ->
            val item = raw.jsonObject
            val tenant = ChaoxingTenant.valueOf(item.getValue("tenant").jsonPrimitive.content)
            val context = ChaoxingReserveContext(
                tenant = tenant,
                reserveIdEnc = item.getValue("reserveIdEnc").jsonPrimitive.content,
                sourcePath = "/front/web/apps/reservepc/index",
            )
            val page = repository.reserveRooms(tenant, cookie, context)
            assertTrue("${tenant.name} 未返回研讨间", page.rooms.isNotEmpty())
        }
    }
}
