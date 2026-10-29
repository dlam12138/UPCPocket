package com.upcpocket.app.ui.campus

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Uses public Android intents only. Never targets WeChat's private browser activities. */
internal object WeChatHandoff {
    private const val PACKAGE_NAME = "com.tencent.mm"

    fun copyAndOpen(context: Context, url: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("学校服务链接", url))
        val launcher = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        if (launcher == null || !launch(context, launcher)) {
            message(context, "链接已复制，未能打开微信；请手动打开微信后粘贴链接")
        } else {
            message(context, "链接已复制，请在文件传输助手中粘贴并点击打开")
        }
    }

    fun share(context: Context, url: String) {
        // The user chooses a conversation and confirms sending in WeChat. No automatic messages.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(PACKAGE_NAME)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        if (!launch(context, intent)) {
            copyAndOpen(context, url)
        }
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun message(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
