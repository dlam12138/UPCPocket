package com.upcpocket.app.update

import com.upcpocket.app.BuildConfig
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateSignatureVerifierTest {

    @Test
    fun verifiesDotNetGeneratedFixtureAgainstRawManifestBytes() {
        // fixture 由 tools/release_update.ps1 同类的 .NET
        // Rfc3279DerSequence 签名路径生成；不是 Java 自己签给自己。
        val key = UpdateTestFixtures.readResource("updates/fixture-update-public-key.b64")
            .toString(Charsets.UTF_8)
            .trim()
        val payload = UpdateTestFixtures.readResource("updates/fixture-update.json")
        val signature = UpdateTestFixtures.readResource("updates/fixture-update.sig")
        val verifier = UpdateSignatureVerifier(key)
        assertTrue(verifier.verify(payload, signature))

        val parsed = UpdateManifestParser.parse(payload)
        // 历史旧包签名仍有效，但新版必须拒绝旧应用身份。
        assertEquals(ManifestParseResult.Invalid(ManifestError.PACKAGE_NAME), parsed)
    }

    @Test
    fun rejectsTamperedBytesAndWrongKey() {
        val pair = UpdateTestFixtures.testKeyPair()
        val payload = UpdateTestFixtures.manifest()
        val signature = UpdateTestFixtures.signature(payload, pair.private)
        val verifier = UpdateSignatureVerifier(UpdateTestFixtures.publicKeyBase64(pair))
        assertTrue(verifier.verify(payload, signature))
        val tampered = payload.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }
        assertFalse(verifier.verify(tampered, signature))

        val other = UpdateTestFixtures.testKeyPair()
        assertFalse(UpdateSignatureVerifier(UpdateTestFixtures.publicKeyBase64(other)).verify(payload, signature))
    }

    @Test
    fun rejectsTrailingDerBytesAndMalformedLengths() {
        val pair = UpdateTestFixtures.testKeyPair()
        val payload = UpdateTestFixtures.manifest()
        val signature = UpdateTestFixtures.signature(payload, pair.private)
        val verifier = UpdateSignatureVerifier(UpdateTestFixtures.publicKeyBase64(pair))
        assertFalse(verifier.verify(payload, signature + byteArrayOf(0x00)))
        assertFalse(verifier.verify(payload, byteArrayOf(0x30, 0x00)))
        assertFalse(verifier.verify(payload, signature.copyOf().also { it[0] = 0x31 }))
    }

    @Test
    fun verifiesProductionReleaseFixtureWithRepositoryPublicKey() {
        // 该 fixture 是最终 2.0.1(22) 清单的原始字节与发布脚本生成的 DER 签名；
        // 用 BuildConfig 中的真实生产公钥验证，确认发布脚本与 Android 侧互通。
        val payload = UpdateTestFixtures.readResource("updates/production-update.json")
        val signature = UpdateTestFixtures.readResource("updates/production-update.sig")
        val verifier = UpdateSignatureVerifier(BuildConfig.UPDATE_PUBLIC_KEY)
        assertTrue(verifier.verify(payload, signature))
        val parsed = UpdateManifestParser.parse(payload)
        // 保留真实签名字节，验证密码学兼容性与包名隔离是两个独立边界。
        assertEquals(ManifestParseResult.Invalid(ManifestError.PACKAGE_NAME), parsed)
    }

    @Test
    fun productionPublicKeyIsP256AndNotBlank() {
        assertTrue(BuildConfig.UPDATE_PUBLIC_KEY.isNotBlank())
        val key = UpdateSignatureVerifier.decodeP256PublicKey(BuildConfig.UPDATE_PUBLIC_KEY)
        assertTrue(key is ECPublicKey)
        assertNotNull((key as ECPublicKey).params)
        assertTrue(key.params.curve.field.fieldSize == 256)
    }

    @Test
    fun rejectsNonEcdsaPublicKeyMaterial() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val rsa = generator.generateKeyPair().public.encoded
        val encoded = Base64.getEncoder().encodeToString(rsa)
        val failed = runCatching { UpdateSignatureVerifier.decodeP256PublicKey(encoded) }.isFailure
        assertTrue(failed)
    }
}
