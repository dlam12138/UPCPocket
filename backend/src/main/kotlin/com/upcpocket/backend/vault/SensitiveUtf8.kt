package com.upcpocket.backend.vault

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays
import kotlin.math.ceil

/** UTF-8 conversion helpers that clear their temporary backing buffers. */
internal object SensitiveUtf8 {
    fun encode(characters: CharArray): ByteArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val required = ceil(characters.size * encoder.maxBytesPerChar().toDouble()).toLong()
        require(required <= Int.MAX_VALUE) { "Input is too large" }
        val output = ByteBuffer.allocate(required.toInt())
        return try {
            val input = CharBuffer.wrap(characters)
            var result = encoder.encode(input, output, true)
            while (result.isOverflow) result = encoder.encode(input, output, true)
            result.throwExceptionIfError()
            result = encoder.flush(output)
            while (result.isOverflow) result = encoder.flush(output)
            result.throwExceptionIfError()
            output.flip()
            ByteArray(output.remaining()).also { output.get(it) }
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Input is not valid Unicode", e)
        } finally {
            Arrays.fill(output.array(), 0.toByte())
        }
    }

    fun decode(bytes: ByteArray, offset: Int, length: Int): CharArray {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid UTF-8 slice" }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = CharBuffer.allocate(length)
        return try {
            val input = ByteBuffer.wrap(bytes, offset, length)
            var result = decoder.decode(input, output, true)
            while (result.isOverflow) result = decoder.decode(input, output, true)
            result.throwExceptionIfError()
            result = decoder.flush(output)
            while (result.isOverflow) result = decoder.flush(output)
            result.throwExceptionIfError()
            output.flip()
            CharArray(output.remaining()).also { output.get(it) }
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Stored credential is not valid UTF-8", e)
        } finally {
            Arrays.fill(output.array(), '\u0000')
        }
    }

    private fun java.nio.charset.CoderResult.throwExceptionIfError() {
        if (isError) throwException()
    }
}
