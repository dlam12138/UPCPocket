package com.upcpocket.app.ui.ecard

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.upcpocket.app.data.card.CardDiagnostics
import com.upcpocket.app.data.cardrecharge.CardRechargeProtocol
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult

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

    override fun isAvailable(): Boolean {
        val diagnostic = CardDiagnostics.begin(DiagnosticDetail.CARD_WECHAT_AVAILABILITY, DiagnosticPhase.PUBLISH, pending = false)
        val available = try {
            resolve("weixin://wap/pay") != null
        } catch (_: SecurityException) {
            false
        }
        diagnostic.finish(if (available) DiagnosticResult.OK else DiagnosticResult.BLOCKED,
            if (available) null else DiagnosticReason.NOT_CONFIGURED)
        return available
    }

    override fun launch(uri: String): Boolean {
        val diagnostic = CardDiagnostics.begin(DiagnosticDetail.CARD_WECHAT_HANDOFF, DiagnosticPhase.PUBLISH)
        val accepted = try {
            val safe = CardRechargeProtocol.wechatUri(uri)
            val intent = resolve(safe)
            if (intent == null) {
                diagnostic.finish(DiagnosticResult.BLOCKED, DiagnosticReason.NOT_CONFIGURED)
                false
            } else {
                context.startActivity(intent)
                true
            }
        } catch (_: ActivityNotFoundException) {
            diagnostic.finish(DiagnosticResult.FAIL, DiagnosticReason.NOT_CONFIGURED)
            false
        } catch (_: SecurityException) {
            diagnostic.finish(DiagnosticResult.BLOCKED, DiagnosticReason.POLICY)
            false
        } catch (_: IllegalArgumentException) {
            diagnostic.finish(DiagnosticResult.BLOCKED, DiagnosticReason.POLICY)
            false
        } catch (_: java.io.IOException) {
            diagnostic.finish(DiagnosticResult.BLOCKED, DiagnosticReason.POLICY)
            false
        } catch (_: java.net.URISyntaxException) {
            diagnostic.finish(DiagnosticResult.BLOCKED, DiagnosticReason.POLICY)
            false
        }
        // Accepted means only that Android accepted this dispatch, never that money was paid.
        if (accepted) diagnostic.finish()
        return accepted
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
