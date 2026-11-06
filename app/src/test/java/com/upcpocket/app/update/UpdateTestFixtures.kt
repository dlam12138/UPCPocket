package com.upcpocket.app.update

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object UpdateTestFixtures {
    fun testKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    fun publicKeyBase64(keyPair: KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    fun manifest(
        versionName: String = "2.0.2",
        versionCode: Int = 23,
        minSdk: Int = 31,
        packageName: String = UpdateManifestParser.PACKAGE_NAME,
        sizeBytes: Long = 4_708_133L,
        sha256: String = "ab".repeat(32),
        tag: String = "v$versionName",
        assetName: String = "UPC-Pocket-$versionName.apk",
        notes: List<String> = listOf(
            "修复课表网页登录后的响应识别",
            "新增应用内检查、下载与校验更新",
            "安装仍由系统确认并保留本机数据",
        ),
        publishedAt: String = "2026-11-06T03:30:00Z",
        schemaVersion: Int = 1,
        extra: Map<String, Any> = emptyMap(),
    ): ByteArray {
        val json = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("packageName", packageName)
            .put("versionName", versionName)
            .put("versionCode", versionCode)
            .put("minSdk", minSdk)
            .put("tag", tag)
            .put("assetName", assetName)
            .put("sizeBytes", sizeBytes)
            .put("sha256", sha256)
            .put("publishedAt", publishedAt)
            .put("notes", JSONArray(notes))
        extra.forEach { (key, value) -> json.put(key, value) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun signature(payload: ByteArray, privateKey: java.security.PrivateKey): ByteArray {
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    fun signedManifest(
        keyPair: KeyPair = testKeyPair(),
        payload: ByteArray = manifest(),
    ): Triple<ByteArray, ByteArray, KeyPair> {
        return Triple(payload, signature(payload, keyPair.private), keyPair)
    }

    fun source(
        id: String,
        keyPair: KeyPair,
        baseUrl: String = "https://example.com/$id",
        cleartext: Boolean = false,
    ): UpdateSource = UpdateSource(
        id = id,
        label = id,
        baseUrl = baseUrl,
        publicKeyBase64 = publicKeyBase64(keyPair),
        cleartextAllowed = cleartext,
        allowedRedirectHosts = setOf("example.com"),
    )

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun readResource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test resource $name"
        }.use { it.readBytes() }
}
