package com.upcpocket.app.update

import com.upcpocket.app.BuildConfig
import java.net.URI

/**
 * 固定的公开更新线路。
 *
 * 线路只是传输候选，不提升信任等级：每个源都仍要提供可被内置公钥验证的清单与签名，
 * 再按清单内的固定 tag/assetName 下载。清单不能提供或改变主机。
 */
data class UpdateSource(
    val id: String,
    val label: String,
    val baseUrl: String,
    val publicKeyBase64: String,
    val cleartextAllowed: Boolean,
    val allowedRedirectHosts: Set<String>,
) {
    init {
        require(id.matches(Regex("^[a-z0-9.-]{1,40}$")))
        val uri = URI(baseUrl)
        require(uri.scheme == "https" || (cleartextAllowed && uri.scheme == "http")) {
            "update source must use https"
        }
        require(uri.userInfo == null) { "update source must not contain userinfo" }
        require(uri.query == null && uri.fragment == null) { "update source must not contain query/fragment" }
        require(uri.host != null && uri.host.lowercase() in allowedRedirectHosts) {
            "update source host must be in its fixed allowlist"
        }
    }

    fun metadataUrl(): String = "$baseUrl/releases/latest/download/update.json"

    fun signatureUrl(): String = "$baseUrl/releases/latest/download/update.sig"

    fun apkUrl(manifest: UpdateManifest): String =
        "$baseUrl/releases/download/${manifest.tag}/${manifest.assetName}"
}

object UpdateSources {
    const val OFFICIAL_ID = "official"
    const val PRIMARY_ID = "gh-proxy.org"
    const val BACKUP_ID = "ghproxy.net"
    const val TEST_ID = "test"

    private const val REPOSITORY = "dlam12138/UPCPocket"
    private val GITHUB_ASSET_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    private val official = UpdateSource(
        id = OFFICIAL_ID,
        label = "官方线路",
        baseUrl = "https://github.com/$REPOSITORY",
        publicKeyBase64 = BuildConfig.UPDATE_PUBLIC_KEY,
        cleartextAllowed = false,
        allowedRedirectHosts = GITHUB_ASSET_HOSTS,
    )

    private val primary = UpdateSource(
        id = PRIMARY_ID,
        label = "主加速线路",
        baseUrl = "https://gh-proxy.org/https://github.com/$REPOSITORY",
        publicKeyBase64 = BuildConfig.UPDATE_PUBLIC_KEY,
        cleartextAllowed = false,
        allowedRedirectHosts = GITHUB_ASSET_HOSTS + "gh-proxy.org",
    )

    private val backup = UpdateSource(
        id = BACKUP_ID,
        label = "备用加速线路",
        baseUrl = "https://ghproxy.net/https://github.com/$REPOSITORY",
        publicKeyBase64 = BuildConfig.UPDATE_PUBLIC_KEY,
        cleartextAllowed = false,
        allowedRedirectHosts = GITHUB_ASSET_HOSTS + setOf(
            "ghproxy.net",
            "cdn.jsdelivr.net",
            "fastly.jsdelivr.net",
        ),
    )

    val production: List<UpdateSource> = listOf(primary, backup, official)

    /**
     * 只有 debug 构建且构建时显式传入 updateTestBase/updateTestPublicKey 才存在。
     * Release 的 BuildConfig.DEBUG 为 false 且两个字段为空，代码路径不可达。
     */
    fun debugTestSource(): UpdateSource? {
        if (!BuildConfig.DEBUG) return null
        val base = BuildConfig.UPDATE_TEST_BASE_URL.trim().trimEnd('/')
        val key = BuildConfig.UPDATE_TEST_PUBLIC_KEY.trim()
        if (base.isBlank() || key.isBlank()) return null
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in setOf("10.0.2.2", "127.0.0.1", "localhost")) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return UpdateSource(
            id = TEST_ID,
            label = "测试线路",
            baseUrl = base,
            publicKeyBase64 = key,
            cleartextAllowed = uri.scheme == "http",
            allowedRedirectHosts = setOf(host),
        )
    }

    fun activeSources(): List<UpdateSource> {
        return debugTestSource()?.let { listOf(it) } ?: production
    }

    /**
     * 最近成功源 → 主加速 → 备用 → 官方；按 id 去重。
     * 自动线路不并行测速，也不启动时下载整包测速。
     */
    fun ordered(sources: List<UpdateSource>, lastSuccessfulSourceId: String?): List<UpdateSource> {
        val preferred = sources.firstOrNull { it.id == lastSuccessfulSourceId }
        val ordered = buildList {
            preferred?.let(::add)
            sources.firstOrNull { it.id == PRIMARY_ID }?.let(::add)
            sources.firstOrNull { it.id == BACKUP_ID }?.let(::add)
            sources.firstOrNull { it.id == OFFICIAL_ID }?.let(::add)
            addAll(sources)
        }
        return ordered.distinctBy { it.id }
    }
}
