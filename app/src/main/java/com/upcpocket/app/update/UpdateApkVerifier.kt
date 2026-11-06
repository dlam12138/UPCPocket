package com.upcpocket.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

sealed interface ApkVerification {
    data object Valid : ApkVerification
    data class Invalid(val reason: ApkInvalidReason) : ApkVerification
}

enum class ApkInvalidReason {
    SIZE,
    HASH,
    PARSE,
    PACKAGE_NAME,
    VERSION_CODE,
    VERSION_NAME,
    MIN_SDK,
    SIGNER,
}

interface ApkVerifier {
    fun verify(file: File, manifest: UpdateManifest): ApkVerification
}

/**
 * 下载后、交给系统安装器前的 APK 静态检查。
 *
 * 这里只证明文件和“当前已安装应用”兼容；真正的 APK 证书密码学校验仍由 Android
 * 安装器完成。检查不匹配就删除临时文件，不建议卸载旧版绕过。
 */
class AndroidApkVerifier(private val context: Context) : ApkVerifier {
    override fun verify(file: File, manifest: UpdateManifest): ApkVerification {
        if (!file.isFile || file.length() != manifest.sizeBytes) {
            return ApkVerification.Invalid(ApkInvalidReason.SIZE)
        }
        if (sha256(file) != manifest.sha256) {
            return ApkVerification.Invalid(ApkInvalidReason.HASH)
        }

        val packageManager = context.packageManager
        val archiveInfo = runCatching {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return ApkVerification.Invalid(ApkInvalidReason.PARSE)
        val archiveApplication = archiveInfo.applicationInfo
            ?: return ApkVerification.Invalid(ApkInvalidReason.PARSE)

        if (archiveInfo.packageName != UpdateManifestParser.PACKAGE_NAME ||
            archiveInfo.packageName != context.packageName
        ) {
            return ApkVerification.Invalid(ApkInvalidReason.PACKAGE_NAME)
        }
        if (archiveInfo.longVersionCode != manifest.versionCode.toLong()) {
            return ApkVerification.Invalid(ApkInvalidReason.VERSION_CODE)
        }
        if (archiveInfo.versionName != manifest.versionName) {
            return ApkVerification.Invalid(ApkInvalidReason.VERSION_NAME)
        }
        if (archiveApplication.minSdkVersion != manifest.minSdk) {
            return ApkVerification.Invalid(ApkInvalidReason.MIN_SDK)
        }
        if (manifest.minSdk > Build.VERSION.SDK_INT) {
            return ApkVerification.Invalid(ApkInvalidReason.MIN_SDK)
        }

        val archiveSigners = archiveInfo.signingInfo?.apkContentsSigners
            ?: return ApkVerification.Invalid(ApkInvalidReason.SIGNER)
        val installedInfo = runCatching {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return ApkVerification.Invalid(ApkInvalidReason.SIGNER)
        val installedSigners = installedInfo.signingInfo?.apkContentsSigners
            ?: return ApkVerification.Invalid(ApkInvalidReason.SIGNER)

        val archiveDigests = archiveSigners.map(::certificateSha256).toSet()
        val installedDigests = installedSigners.map(::certificateSha256).toSet()
        if (archiveDigests.isEmpty() || archiveDigests != installedDigests) {
            return ApkVerification.Invalid(ApkInvalidReason.SIGNER)
        }
        return ApkVerification.Valid
    }

    private fun certificateSha256(signature: android.content.pm.Signature): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
