package com.upcpocket.backend.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CharArrayJsonConfigurationTest {
    @Test
    fun `credential request JSON becomes mutable char arrays with redacted text form`() {
        val builder = Jackson2ObjectMapperBuilder()
            .modulesToInstall(KotlinModule.Builder().build())
        CharArrayJsonConfiguration().charArrayJsonCustomizer().customize(builder)
        val mapper: ObjectMapper = builder.build()

        val request = mapper.readValue(
            """{"loginName":"sample-student","password":"sample-password"}""",
            CredentialUpdateRequest::class.java,
        )
        assertArrayEquals("sample-password".toCharArray(), request.password)
        assertFalse(request.toString().contains("sample-password"))

        request.password.fill('\u0000')
        assertTrue(request.password.all { it == '\u0000' })
        request.loginName.fill('\u0000')
        RequestSensitiveCharArrays.clearCurrentRequest()
    }

    @Test
    fun `request filter clears arrays allocated before malformed JSON parsing fails`() {
        val builder = Jackson2ObjectMapperBuilder()
            .modulesToInstall(KotlinModule.Builder().build())
        CharArrayJsonConfiguration().charArrayJsonCustomizer().customize(builder)
        val mapper: ObjectMapper = builder.build()
        val filter = SensitiveCharArrayCleanupFilter()
        var allocatedBeforeFailure: List<CharArray>? = null

        assertThrows(Exception::class.java) {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse()) { _, _ ->
                try {
                    mapper.readValue(
                        """{"loginName":"parsed-before-error"}""",
                        CredentialUpdateRequest::class.java,
                    )
                } catch (error: Exception) {
                    allocatedBeforeFailure = RequestSensitiveCharArrays.currentRequestValuesForTest()
                    throw error
                }
            }
        }

        assertNotNull(allocatedBeforeFailure)
        val allocated = requireNotNull(allocatedBeforeFailure)
        assertTrue(allocated.isNotEmpty())
        assertTrue(allocated.all { value -> value.all { it == '\u0000' } })
        RequestSensitiveCharArrays.clearCurrentRequest()
    }
}
