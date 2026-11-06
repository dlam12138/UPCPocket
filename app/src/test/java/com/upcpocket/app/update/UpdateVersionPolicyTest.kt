package com.upcpocket.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateVersionPolicyTest {

    private fun manifest(code: Int, minSdk: Int = 31): UpdateManifest {
        val parsed = UpdateManifestParser.parse(
            UpdateTestFixtures.manifest(versionCode = code, versionName = "2.0.${code - 20}", minSdk = minSdk),
        )
        return (parsed as ManifestParseResult.Valid).manifest
    }

    @Test
    fun usesIntegerCodeNotVersionNameOrPublishedAt() {
        val decision = UpdateVersionPolicy.decide(
            installedVersionCode = 21,
            sdkInt = 36,
            highestVerifiedCode = 0,
            manifest = manifest(22),
        )
        assertTrue(decision is UpdateVersionDecision.Offer)
    }

    @Test
    fun rejectsSameAndOlderCode() {
        assertEquals(
            UpdateVersionDecision.NotNewer,
            UpdateVersionPolicy.decide(22, 36, 0, manifest(22)),
        )
    }

    @Test
    fun rejectsPublishedV2ApkAsDowngrade() {
        // 已公开 v2.0 APK（code 20）不能被应用内更新当作“新版本”降级安装。
        assertEquals(
            UpdateVersionDecision.NotNewer,
            UpdateVersionPolicy.decide(22, 36, 0, manifest(20)),
        )
    }

    @Test
    fun rejectsUnsupportedMinSdk() {
        assertEquals(
            UpdateVersionDecision.UnsupportedDevice,
            UpdateVersionPolicy.decide(21, 31, 0, manifest(22, minSdk = 35)),
        )
    }

    @Test
    fun rejectsReplayBelowHighestVerifiedButNewerThanInstalled() {
        assertEquals(
            UpdateVersionDecision.Stale,
            UpdateVersionPolicy.decide(21, 36, 23, manifest(22)),
        )
    }

    @Test
    fun highestVersionStillOffersUntilInstalled() {
        val decision = UpdateVersionPolicy.decide(21, 36, 23, manifest(23))
        assertTrue(decision is UpdateVersionDecision.Offer)
    }
}
