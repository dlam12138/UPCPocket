package com.upcpocket.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.upcpocket.app.BuildConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManifestParserTest {

    @Test
    fun newIdentityRejectsOldAppManifest() {
        assertEquals("com.upcpocket.next", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, UpdateManifestParser.PACKAGE_NAME)
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.PACKAGE_NAME),
            UpdateManifestParser.parse(UpdateTestFixtures.manifest(packageName = "com.upcpocket.app")),
        )
    }

    @Test
    fun fixedSourcesUseSeparateNextMetadataChannel() {
        UpdateSources.production.forEach { source ->
            assertTrue(source.metadataUrl().endsWith("/dlam12138/UPCPocket/releases/latest/download/update-next.json"))
            assertTrue(source.signatureUrl().endsWith("/dlam12138/UPCPocket/releases/latest/download/update-next.sig"))
        }
    }

    @Test
    fun acceptsStrictSupportedManifest() {
        val result = UpdateManifestParser.parse(UpdateTestFixtures.manifest())
        assertTrue(result is ManifestParseResult.Valid)
        val manifest = (result as ManifestParseResult.Valid).manifest
        assertEquals("2.0.2", manifest.versionName)
        assertEquals(23, manifest.versionCode)
        assertEquals(31, manifest.minSdk)
        assertEquals("v2.0.2", manifest.tag)
        assertEquals(3, manifest.notes.size)
    }

    @Test
    fun rejectsHtmlEvenIfSmall() {
        val result = UpdateManifestParser.parse("<html>release</html>".toByteArray())
        assertEquals(ManifestParseResult.Invalid(ManifestError.MALFORMED_JSON), result)
    }

    @Test
    fun rejectsUnknownFieldsAndMissingFields() {
        val unknown = UpdateTestFixtures.manifest(extra = mapOf("downloadUrl" to "https://evil"))
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.FIELD_SET),
            UpdateManifestParser.parse(unknown),
        )
    }

    @Test
    fun rejectsStringAndFloatingVersionCode() {
        val stringCode = UpdateTestFixtures.manifest()
            .toString(Charsets.UTF_8)
            .replace("\"versionCode\":23", "\"versionCode\":\"23\"")
            .toByteArray()
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.VERSION),
            UpdateManifestParser.parse(stringCode),
        )
        val floatCode = UpdateTestFixtures.manifest()
            .toString(Charsets.UTF_8)
            .replace("\"versionCode\":23", "\"versionCode\":23.0")
            .toByteArray()
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.VERSION),
            UpdateManifestParser.parse(floatCode),
        )
    }

    @Test
    fun rejectsWrongPackageTagAssetAndSchema() {
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.PACKAGE_NAME),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(packageName = "com.example.app"),
            ),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.TAG),
            UpdateManifestParser.parse(UpdateTestFixtures.manifest(tag = "v2.0.3")),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.ASSET_NAME),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(assetName = "other.apk"),
            ),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.SCHEMA_VERSION),
            UpdateManifestParser.parse(UpdateTestFixtures.manifest(schemaVersion = 2)),
        )
    }

    @Test
    fun rejectsUnsupportedNotesCountsAndControlText() {
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.NOTES),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(notes = listOf("only one", "only two")),
            ),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.NOTES),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(
                    notes = (1..6).map { "note$it" },
                ),
            ),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.NOTES),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(notes = listOf("a\nb", "c", "d")),
            ),
        )
    }

    @Test
    fun rejectsSizeHashAndTimestampProblems() {
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.SIZE),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(sizeBytes = 50L * 1024 * 1024 + 1),
            ),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.SHA256),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(sha256 = "A".repeat(64)),
            ),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.PUBLISHED_AT),
            UpdateManifestParser.parse(
                UpdateTestFixtures.manifest(publishedAt = "2026-11-06 03:30:00"),
            ),
        )
    }

    @Test
    fun rejectsOversizedAndMalformedUtf8() {
        val oversized = ByteArray(UpdateManifestParser.MAX_MANIFEST_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.TOO_LARGE),
            UpdateManifestParser.parse(oversized),
        )
        assertEquals(
            ManifestParseResult.Invalid(ManifestError.MALFORMED_UTF8),
            UpdateManifestParser.parse(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)),
        )
    }

    @Test
    fun rejectsTooHighMinSdkAsDataShapeOnly() {
        val result = UpdateManifestParser.parse(UpdateTestFixtures.manifest(minSdk = 101))
        assertEquals(ManifestParseResult.Invalid(ManifestError.MIN_SDK), result)
    }
}
