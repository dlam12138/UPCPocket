package com.upcpocket.backend.http

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Tracks mutable credential arrays allocated while the current request body is being read. */
object RequestSensitiveCharArrays {
    private val values = ThreadLocal.withInitial { mutableListOf<CharArray>() }

    fun register(value: CharArray): CharArray {
        values.get().add(value)
        return value
    }

    fun clearCurrentRequest() {
        val requestValues = values.get()
        requestValues.forEach { it.fill('\u0000') }
        requestValues.clear()
        values.remove()
    }

    internal fun currentRequestValuesForTest(): List<CharArray> = values.get().toList()
}

@Configuration
class CharArrayJsonConfiguration {
    @Bean
    fun charArrayJsonCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            val module = SimpleModule("sensitive-char-array")
            module.addDeserializer(CharArray::class.java, object : JsonDeserializer<CharArray>() {
                override fun deserialize(parser: JsonParser, context: DeserializationContext): CharArray {
                    if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) {
                        return context.handleUnexpectedToken(CharArray::class.java, parser) as CharArray
                    }
                    val length = parser.textLength
                    if (length > MAX_SENSITIVE_FIELD_LENGTH) {
                        throw JsonMappingException.from(parser, "Sensitive field exceeds maximum length")
                    }
                    val start = parser.textOffset
                    return RequestSensitiveCharArrays.register(
                        parser.textCharacters.copyOfRange(start, start + length),
                    )
                }
            })
            builder.modulesToInstall(module)
        }

    companion object {
        private const val MAX_SENSITIVE_FIELD_LENGTH = 1_024
    }
}
