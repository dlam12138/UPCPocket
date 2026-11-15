package com.upcpocket.app.ui.ecard

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.upcpocket.app.data.cardrecharge.CardRechargeProtocol

internal interface CardRechargeWeChatLauncher {
    fun isAvailable(): Boolean
    fun launch(uri: String): Boolean
}

/** Fresh ACTION_VIEW intents only: no remote extras, selectors, grant flags or arbitrary handlers. */
internal class AndroidCardRechargeWeChatLauncher(private val context: Context) : CardRechargeWeChatLauncher {
    private fun resolve(uri: String): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(WECHAT_PACKAGE)
        val target = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return null
        if (target.packageName != WECHAT_PACKAGE || !target.exported) return null
        if (target.permission != null && context.checkSelfPermission(target.permission) != PackageManager.PERMISSION_GRANTED) return null
        return intent.setComponent(ComponentName(target.packageName, target.name))
    }

    override fun isAvailable(): Boolean = try {
        resolve("weixin://wap/pay") != null
    } catch (_: SecurityException) {
        false
    }

    override fun launch(uri: String): Boolean = try {
        val safe = CardRechargeProtocol.wechatUri(uri)
        val intent = resolve(safe)
        if (intent == null) false else {
            context.startActivity(intent)
            true
        }
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: java.io.IOException) {
        false
    } catch (_: java.net.URISyntaxException) {
        false
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
