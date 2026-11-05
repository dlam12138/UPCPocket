package com.upcpocket.app.data.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费校区入口的边界测试。
 *
 * 只钉住已核实的事实：两个校区是不同系统；地址不带凭据；古镇口仍走学校页面，
 * 且不得为了“原生体验”描述成已绕过证书或已接入。
 */
class PowerCampusEntriesTest {

    @Test
    fun `both campuses use distinct official https or http entries without credentials`() {
        val urls = listOf(
            PowerCampusEntries.TANGDAOWAN_URL,
            PowerCampusEntries.GUZHENKOU_URL,
        )
        urls.forEach { url ->
            assertTrue(url.startsWith("https://") || url.startsWith("http://"))
            assertFalse(url.any { it.isWhitespace() })
            val query = url.substringAfter('?', "")
            listOf("token=", "code=", "ticket=", "session=", "auth=", "key=").forEach { banned ->
                assertFalse("地址含凭据参数 $banned：$url", query.contains(banned, ignoreCase = true))
            }
        }
        assertTrue(hostOf(urls[0]) != hostOf(urls[1]))
    }

    @Test
    fun `guzhenkou remains an external school page with an expired certificate warning`() {
        assertTrue(PowerCampusEntries.GUZHENKOU_NOTE.contains("证书"))
        assertTrue(PowerCampusEntries.GUZHENKOU_NOTE.contains("过期"))
        assertTrue(PowerCampusEntries.GUZHENKOU_NOTE.contains("不会绕过"))
        assertTrue(PowerCampusEntries.GUZHENKOU_SCOPE.contains("古镇口"))
        assertTrue(PowerCampusEntries.GUZHENKOU_UPDATED_NOTE.contains("总体计划书"))
    }

    @Test
    fun `guzhenkou copy does not claim native integration`() {
        val blob = PowerCampusEntries.GUZHENKOU_SCOPE + PowerCampusEntries.GUZHENKOU_NOTE
        assertFalse(blob.contains("已接入"))
        assertFalse(blob.contains("官方合作"))
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
}
