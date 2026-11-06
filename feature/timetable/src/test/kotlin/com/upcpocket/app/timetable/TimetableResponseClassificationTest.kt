package com.upcpocket.app.timetable

import com.upcpocket.app.platform.diagnostics.DiagnosticBodyType
import com.upcpocket.app.platform.diagnostics.DiagnosticMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableResponseClassificationTest {

    @Test
    fun mediaTypesAreClassifiedWithoutKeepingHeaderText() {
        assertEquals(
            DiagnosticMediaType.JSON,
            TimetableResponseClassifier.mediaType(listOf(" Application/JSON ; charset=UTF-8 ")),
        )
        assertEquals(
            DiagnosticMediaType.JSON_SUFFIX,
            TimetableResponseClassifier.mediaType(listOf("application/problem+json")),
        )
        assertEquals(
            DiagnosticMediaType.HTML,
            TimetableResponseClassifier.mediaType(listOf("text/html; charset=UTF-8")),
        )
        assertEquals(
            DiagnosticMediaType.TEXT,
            TimetableResponseClassifier.mediaType(listOf("text/plain")),
        )
        assertEquals(
            DiagnosticMediaType.MISSING,
            TimetableResponseClassifier.mediaType(emptyList()),
        )
        assertEquals(
            DiagnosticMediaType.OTHER,
            TimetableResponseClassifier.mediaType(listOf("application/octet-stream")),
        )
        assertEquals(
            DiagnosticMediaType.OTHER,
            TimetableResponseClassifier.mediaType(
                listOf("application/json", "text/html"),
            ),
        )
    }

    @Test
    fun bodyCategoriesSeparateJsonHtmlLoginMalformedAndEmpty() {
        assertEquals(
            DiagnosticBodyType.JSON_OBJECT,
            TimetableResponseClassifier.body("{\"e\":0}".toByteArray()).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.JSON_ARRAY,
            TimetableResponseClassifier.body("[1]".toByteArray()).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.HTML,
            TimetableResponseClassifier.body(
                "<html><body>maintenance</body></html>".toByteArray(),
            ).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.LOGIN_LIKE,
            TimetableResponseClassifier.body(
                "<html><body>CAS login</body></html>".toByteArray(),
            ).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.LOGIN_LIKE,
            TimetableResponseClassifier.body(
                "<html><form><input type=\"password\"></form></html>".toByteArray(),
            ).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.EMPTY,
            TimetableResponseClassifier.body(" \r\n\t".toByteArray()).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.MALFORMED,
            TimetableResponseClassifier.body("{\"e\":0".toByteArray()).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.OTHER,
            TimetableResponseClassifier.body("plain text".toByteArray()).bodyType,
        )
        assertEquals(
            DiagnosticBodyType.OTHER,
            TimetableResponseClassifier.body("callback({\"e\":0})".toByteArray()).bodyType,
        )
    }

    @Test
    fun utf8BomAndUtf16JsonAreParsedWithoutLeakingBytes() {
        val json = "{\"e\":0}"
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            json.toByteArray(Charsets.UTF_8)
        val utf8Shape = TimetableResponseClassifier.body(utf8Bom)
        assertEquals(DiagnosticBodyType.JSON_OBJECT, utf8Shape.bodyType)
        assertTrue(utf8Shape.validJson)
        assertEquals(json, utf8Shape.text)

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            json.toByteArray(Charsets.UTF_16LE)
        val utf16Shape = TimetableResponseClassifier.body(utf16)
        assertEquals(DiagnosticBodyType.JSON_OBJECT, utf16Shape.bodyType)
        assertTrue(utf16Shape.validJson)
        assertEquals(json, utf16Shape.text)
    }

    @Test
    fun jsonScalarIsValidJsonButNotAnObjectEnvelope() {
        val shape = TimetableResponseClassifier.body("\"text\"".toByteArray())
        assertEquals(DiagnosticBodyType.OTHER, shape.bodyType)
        assertTrue(shape.validJson)
        assertFalse(shape.text.contains('\uFEFF'))
    }
}
