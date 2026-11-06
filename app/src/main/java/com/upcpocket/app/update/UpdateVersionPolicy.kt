package com.upcpocket.app.update

sealed interface UpdateVersionDecision {
    data object NotNewer : UpdateVersionDecision
    data object Stale : UpdateVersionDecision
    data object UnsupportedDevice : UpdateVersionDecision
    data class Offer(val manifest: UpdateManifest) : UpdateVersionDecision
}

/**
 * 版本判断以整数 versionCode 为准，不比较 versionName 字符串或发布时间。
 *
 * [highestVerifiedCode] 只来自已验签清单；镜像重放旧清单时，即使它比当前安装包新，
 * 也不能低于本机已经观察到的最高版本。
 */
object UpdateVersionPolicy {
    fun decide(
        installedVersionCode: Int,
        sdkInt: Int,
        highestVerifiedCode: Int,
        manifest: UpdateManifest,
    ): UpdateVersionDecision {
        if (manifest.versionCode <= installedVersionCode) return UpdateVersionDecision.NotNewer
        if (manifest.versionCode < highestVerifiedCode) return UpdateVersionDecision.Stale
        if (manifest.minSdk > sdkInt) return UpdateVersionDecision.UnsupportedDevice
        return UpdateVersionDecision.Offer(manifest)
    }
}
