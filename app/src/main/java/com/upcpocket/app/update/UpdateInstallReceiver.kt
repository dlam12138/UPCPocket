package com.upcpocket.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * PackageInstaller 会话状态接收器（仅应用内显式 PendingIntent 可达）。
 *
 * 部分系统会在 commit 后回调 STATUS_PENDING_USER_ACTION，要求应用启动系统确认页；
 * 这里只转发系统给出的 Intent，不读取 APK、不拼接 URL、不静默安装。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmation) }
                    .onFailure { Log.w(TAG, "install confirmation unavailable") }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "session installed")
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            -> Log.w(TAG, "session failed status=$status")
            else -> Log.w(TAG, "session unknown status=$status")
        }
    }

    private companion object {
        const val TAG = "UPCUpdateInstall"
    }
}
