package com.upcpocket.app.data.prefs

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.upcpocket.app.ui.theme.MaterialYouScheme
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

sealed interface CustomBackgroundImportResult {
    data class Success(val extractedSeeds: List<Int>) : CustomBackgroundImportResult
    data class Failure(val reason: CustomBackgroundError) : CustomBackgroundImportResult
}

enum class CustomBackgroundError {
    UNREADABLE_IMAGE,
    SAVE_FAILED,
}

/**
 * 自定义背景图本机存储。
 *
 * 仅通过系统照片选择器读取用户主动选择的图片，下采样后保存在应用私有
 * `noBackupFilesDir/appearance/`，不申请存储权限、不上传，也不随学校账号清理。
 */
class CustomBackgroundStore internal constructor(
    private val directory: File,
    private val contentResolver: ContentResolver?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
        contentResolver = context.applicationContext.contentResolver,
    )

    private val backgroundFile: File
        get() = File(directory, FILE_NAME)

    private val tempFile: File
        get() = File(directory, TEMP_FILE_NAME)

    private val _revision = MutableStateFlow(0L)

    /**
     * 背景文件变更信号。
     *
     * 私有 JPEG 是背景图的唯一事实来源；导入或清除成功后递增，宿主与设置页据此
     * 重新读取同一路径，而不是依赖 DataStore 版本号与文件写入的先后顺序。
     * 进程重启后从 0 开始，使用方首次收集时自行读盘。
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    suspend fun importFromUri(uri: Uri): CustomBackgroundImportResult = withContext(ioDispatcher) {
        val resolver = contentResolver ?: return@withContext CustomBackgroundImportResult.Failure(
            CustomBackgroundError.UNREADABLE_IMAGE,
        )
        val bitmap = decodeUriBounded(resolver, uri)
            ?: return@withContext CustomBackgroundImportResult.Failure(
                CustomBackgroundError.UNREADABLE_IMAGE,
            )
        try {
            saveAndExtract(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    internal suspend fun importFromBitmap(bitmap: Bitmap): CustomBackgroundImportResult =
        withContext(ioDispatcher) {
            val bounded = downscaleIfNeeded(bitmap, MAX_EDGE_PX)
            try {
                saveAndExtract(bounded)
            } finally {
                if (bounded !== bitmap) bounded.recycle()
            }
        }

    suspend fun loadBitmap(): ImageBitmap? = withContext(ioDispatcher) {
        val file = backgroundFile
        if (!file.isFile) return@withContext null
        val bitmap = decodeFileBounded(file, MAX_EDGE_PX) ?: return@withContext null
        bitmap.asImageBitmap()
    }

    suspend fun extractSeedsFromSaved(maxColors: Int = 4): List<Int> = withContext(ioDispatcher) {
        val file = backgroundFile
        if (!file.isFile) return@withContext emptyList()
        val sampled = decodeFileBounded(file, PALETTE_SAMPLE_EDGE_PX) ?: return@withContext emptyList()
        try {
            extractSeedsFromBitmap(sampled, maxColors)
        } finally {
            sampled.recycle()
        }
    }

    suspend fun clear(): Boolean = withContext(ioDispatcher) {
        val tempDeleted = !tempFile.exists() || tempFile.delete()
        val fileDeleted = !backgroundFile.exists() || backgroundFile.delete()
        if (tempDeleted && fileDeleted) {
            _revision.update { it + 1L }
            true
        } else {
            false
        }
    }

    internal fun exists(): Boolean = backgroundFile.isFile

    private fun saveAndExtract(bitmap: Bitmap): CustomBackgroundImportResult {
        if (!directory.exists() && !directory.mkdirs()) {
            return CustomBackgroundImportResult.Failure(CustomBackgroundError.SAVE_FAILED)
        }
        val tmp = tempFile
        val target = backgroundFile
        val saved = try {
            FileOutputStream(tmp).use { out ->
                val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (!compressed) return CustomBackgroundImportResult.Failure(CustomBackgroundError.SAVE_FAILED)
                out.flush()
                runCatching { out.fd.sync() }
            }
            // 同目录临时文件 + 原子替换：先确认临时文件完整，替换失败时
            // 保留上一张可读背景，不先删除目标文件。
            val tmpReady = tmp.isFile && tmp.length() > 0L
            if (tmpReady) {
                replaceAtomically(tmp, target)
            } else {
                tmp.delete()
            }
            tmpReady && target.isFile && target.length() > 0L
        } catch (_: IOException) {
            tmp.delete()
            false
        } catch (_: SecurityException) {
            tmp.delete()
            false
        }
        if (!saved) {
            return CustomBackgroundImportResult.Failure(CustomBackgroundError.SAVE_FAILED)
        }
        _revision.update { it + 1L }
        val seeds = extractSeedsFromBitmap(bitmap, maxColors = 4)
        return CustomBackgroundImportResult.Success(extractedSeeds = seeds)
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun decodeUriBounded(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val srcW = info.size.width
                val srcH = info.size.height
                val (targetW, targetH) = computeTargetSize(srcW, srcH, MAX_EDGE_PX)
                if (targetW > 0 && targetH > 0 && (targetW != srcW || targetH != srcH)) {
                    decoder.setTargetSize(targetW, targetH)
                }
            }
        } catch (_: Throwable) {
            decodeUriWithBitmapFactory(resolver, uri)
        }
    }

    private fun decodeUriWithBitmapFactory(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // inJustDecodeBounds 模式即使成功解码也返回 null，这里只用流是否
            // 打开和 outWidth/outHeight 判断可读性与尺寸。
            val boundsStream = resolver.openInputStream(uri) ?: return null
            boundsStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null
            val bounded = downscaleIfNeeded(decoded, MAX_EDGE_PX)
            if (bounded !== decoded) decoded.recycle()
            bounded
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeFileBounded(file: File, maxEdgePx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val bounded = downscaleIfNeeded(decoded, maxEdgePx)
            if (bounded !== decoded) decoded.recycle()
            bounded
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractSeedsFromBitmap(bitmap: Bitmap, maxColors: Int): List<Int> {
        val sample = downscaleIfNeeded(bitmap, PALETTE_SAMPLE_EDGE_PX)
        return try {
            val width = sample.width
            val height = sample.height
            if (width <= 0 || height <= 0) return emptyList()
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            MaterialYouScheme.extractSeedColors(pixels = pixels, maxColors = maxColors)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val (targetW, targetH) = computeTargetSize(bitmap.width, bitmap.height, maxEdgePx)
        if (targetW <= 0 || targetH <= 0 || (targetW == bitmap.width && targetH == bitmap.height)) {
            return bitmap
        }
        return bitmap.scale(targetW, targetH)
    }

    companion object {
        private const val DIRECTORY_NAME = "appearance"
        private const val FILE_NAME = "custom_background.jpg"
        private const val TEMP_FILE_NAME = "custom_background.jpg.tmp"
        private const val MAX_EDGE_PX = 1920
        private const val PALETTE_SAMPLE_EDGE_PX = 64
        private const val JPEG_QUALITY = 90

        internal fun computeTargetSize(width: Int, height: Int, maxEdgePx: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 0 to 0
            val largest = max(width, height)
            if (largest <= maxEdgePx) return width to height
            val scale = maxEdgePx.toFloat() / largest.toFloat()
            val targetW = max(1, (width * scale).roundToInt())
            val targetH = max(1, (height * scale).roundToInt())
            return targetW to targetH
        }

        internal fun computeSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
            var sample = 1
            val largest = max(width, height)
            while (largest / (sample * 2) >= maxEdgePx && sample < 64) {
                sample *= 2
            }
            return min(sample, 64)
        }
    }
}
