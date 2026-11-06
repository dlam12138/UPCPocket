package com.upcpocket.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface InstallAction {
    data class NeedPermission(val settingsIntent: Intent) : InstallAction
    data object Started : InstallAction
    data class Unavailable(val reason: InstallUnavailable) : InstallAction
}

enum class InstallUnavailable {
    NOT_READY,
    FILE_MISSING,
    FILE_OUTSIDE_UPDATE_DIR,
    NO_INSTALLER,
    UNTRUSTED_INSTALLER,
}

/**
 * 系统安装边界。
 *
 * 使用 PackageInstaller 会话把**已完整校验的 APK**交给系统安装器，由系统显示最终
 * 确认页；应用不静默安装、不卸载旧版、不清数据。未知来源设置只在用户点“安装”后
 * 打开，返回时由调用方重新检查。FileProvider 不再作为安装数据通道，避免跨进程
 * URI 授权在部分系统安装器上失效；APK 仍只位于私有 cache/updates。
 */
class UpdateInstaller(
    private val context: Context,
    private val storage: UpdateStorage,
) {
    fun canRequestPackageInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    suspend fun install(file: File): InstallAction = withContext(Dispatchers.IO) {
        if (!storage.isInsideRoot(file)) {
            return@withContext InstallAction.Unavailable(InstallUnavailable.FILE_OUTSIDE_UPDATE_DIR)
        }
        if (!file.isFile) {
            return@withContext InstallAction.Unavailable(InstallUnavailable.FILE_MISSING)
        }
        if (!canRequestPackageInstalls()) {
            return@withContext InstallAction.NeedPermission(unknownSourcesIntent())
        }

        val packageInstaller = context.packageManager.packageInstaller
        val sessionId = try {
            packageInstaller.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                },
            )
        } catch (_: Exception) {
            return@withContext InstallAction.Unavailable(InstallUnavailable.NO_INSTALLER)
        }

        var session: PackageInstaller.Session? = null
        try {
            session = packageInstaller.openSession(sessionId)
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
            InstallAction.Started
        } catch (_: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            InstallAction.Unavailable(InstallUnavailable.NO_INSTALLER)
        } finally {
            runCatching { session?.close() }
        }
    }

    /** 供 UI 在系统安装页返回后确认，不用 Activity resultCode 猜测安装成功。 */
    fun installedVersionCode(): Long? {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrNull()
    }

    private companion object {
        const val ACTION_INSTALL_STATUS = "com.upcpocket.app.action.UPDATE_INSTALL_STATUS"
    }
}
