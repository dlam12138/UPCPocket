package com.upcpocket.app.data.wechat

import android.content.Context
import com.tencent.mm.opensdk.modelbiz.OpenWebview
import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.upcpocket.app.BuildConfig

/**
 * 微信开放平台 SDK 的独立适配层。
 *
 * 只调用公开 SDK：
 *  - `OpenWebview.Req` 打开一个 H5；
 *  - `WXLaunchMiniProgram.Req` 拉起已发布的小程序页面（需要目标方提供原始 ID 与路径）。
 *
 * 没有正式移动应用 AppID 时 [isConfigured] 为 false，调用方必须只显示复制/分享兜底，
 * 不能把“打开微信首页”或 `sendReq` 的布尔值当作目标页/学校认证成功。
 * 不使用私有 Activity、不手工调 Provider、不借用其他应用身份。
 */
object WeChatDirect {
    const val PACKAGE_NAME = "com.tencent.mm"

    val appId: String get() = BuildConfig.WECHAT_APP_ID

    fun isConfigured(): Boolean = appId.matches(Regex("wx[0-9a-f]{16}"))

    fun openWebPage(context: Context, url: String): WeChatSendResult =
        send(context) { api ->
            OpenWebview.Req().apply { this.url = url }
        }

    /**
     * 目标小程序必须由学校/目标方提供 `gh_...` 原始 ID 和已发布页面路径。
     * 没有目标时不要调用；不要把 H5 地址当小程序标识。
     */
    fun openMiniProgram(
        context: Context,
        userName: String,
        path: String = "",
    ): WeChatSendResult = send(context) { _ ->
        WXLaunchMiniProgram.Req().apply {
            this.userName = userName
            this.path = path
            this.miniprogramType = WXLaunchMiniProgram.Req.MINIPTOGRAM_TYPE_RELEASE
        }
    }

    private fun send(
        context: Context,
        request: (IWXAPI) -> com.tencent.mm.opensdk.modelbase.BaseReq,
    ): WeChatSendResult {
        if (!isConfigured()) return WeChatSendResult.NotConfigured
        return try {
            val api = WXAPIFactory.createWXAPI(context.applicationContext, appId, true)
            if (!api.registerApp(appId)) return WeChatSendResult.RegistrationFailed
            if (!api.isWXAppInstalled()) return WeChatSendResult.WeChatNotInstalled
            if (!api.sendReq(request(api))) {
                WeChatSendResult.RequestRejected
            } else {
                WeChatSendResult.Sent
            }
        } catch (_: Throwable) {
            // 不记录 URL、AppID 或异常细节到日志；调用方只显示可操作的结果。
            WeChatSendResult.RequestRejected
        }
    }
}

enum class WeChatSendResult {
    Sent,
    NotConfigured,
    RegistrationFailed,
    WeChatNotInstalled,
    RequestRejected,
}
