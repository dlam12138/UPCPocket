package com.upcpocket.app.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.upcpocket.app.BuildConfig
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope

private const val UPDATE_LOG_TAG = "UPCUpdate"

object UpdateControllerFactory {
    fun create(context: Context, scope: CoroutineScope): UpdateController {
        val appContext = context.applicationContext
        val sources = UpdateSources.activeSources()
        val stateStore = UpdateStateStore(
            File(appContext.noBackupFilesDir, "updates/state.json"),
        )
        val storage = UpdateStorage(
            File(appContext.cacheDir, "updates"),
        )
        val repository = UpdateRepository(
            transport = HttpUpdateTransport(),
            stateStore = stateStore,
            installedVersionCode = BuildConfig.VERSION_CODE,
            installedVersionName = BuildConfig.VERSION_NAME,
            sdkInt = Build.VERSION.SDK_INT,
            onStage = { sourceId, stage, result, status, elapsed ->
                // 只固定记录线路 ID/阶段/结果/耗时/HTTP 状态，不记录 URL/query/文件路径。
                Log.d(
                    UPDATE_LOG_TAG,
                    "check source=$sourceId stage=${stage.name} result=$result " +
                        "http=${status ?: 0} ms=$elapsed",
                )
            },
        )
        val apkVerifier = AndroidApkVerifier(appContext)
        val downloader = UpdateDownloader(
            transport = HttpUpdateTransport(),
            storage = storage,
            apkVerifier = apkVerifier,
            onStage = { sourceId, stage, result, elapsed ->
                Log.d(
                    UPDATE_LOG_TAG,
                    "download source=$sourceId stage=$stage result=$result ms=$elapsed",
                )
            },
        )
        val installer = UpdateInstaller(appContext, storage)
        return UpdateController(
            scope = scope,
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE,
            sources = sources,
            stateStore = stateStore,
            storage = storage,
            checker = repository::check,
            downloader = { manifest, ordered, onSource, onProgress ->
                downloader.download(manifest, ordered, onSource, onProgress)
            },
            apkVerifier = apkVerifier,
            installer = installer,
            clock = Clock.systemDefaultZone(),
        )
    }
}
