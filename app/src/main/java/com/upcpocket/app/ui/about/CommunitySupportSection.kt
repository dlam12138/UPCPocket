package com.upcpocket.app.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upcpocket.app.R
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val QQ_GROUP_NUMBER = "1128474392"

@Composable
internal fun CommunitySupportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRewardCode by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("UPC Pocket 交流群", style = MaterialTheme.typography.titleSmall)
                Text(
                    "QQ群 $QQ_GROUP_NUMBER",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CampusTextButton(onClick = { copyGroupNumber(context) }) {
                Text("复制群号")
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("支持开发", style = MaterialTheme.typography.titleSmall)
            Text(
                "自愿支持开发，所有功能免费使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CampusTextButton(onClick = { showRewardCode = !showRewardCode }) {
                Text(if (showRewardCode) "收起微信赞赏码" else "查看微信赞赏码")
            }
            if (showRewardCode) {
                RewardCodeImage(Modifier.align(Alignment.CenterHorizontally))
                Text(
                    "保存后，在微信「扫一扫」中从相册选取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CampusTextButton(
                    onClick = {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                try {
                                    val saved = saveRewardCode(context.applicationContext)
                                    val message = if (saved) "赞赏码已保存到相册。" else "保存失败，请稍后重试。"
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                    enabled = !saving,
                ) {
                    Text(if (saving) "正在保存…" else "保存赞赏码")
                }
            }
        }
    }
}

@Composable
private fun RewardCodeImage(modifier: Modifier) {
    val resources = LocalContext.current.resources
    val loaded by produceState<Result<ImageBitmap>?>(initialValue = null, key1 = resources) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resources.openRawResource(R.raw.wechat_reward_qr).use { input ->
                    checkNotNull(BitmapFactory.decodeStream(input)).asImageBitmap()
                }
            }
        }
    }
    val bitmap = loaded?.getOrNull()
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "微信赞赏码",
            modifier = modifier.widthIn(max = 420.dp).fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height),
        )
    } else {
        Text(
            if (loaded == null) "正在加载赞赏码…" else "暂时无法显示赞赏码。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun copyGroupNumber(context: Context) {
    val copied = runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return@runCatching false
        clipboard.setPrimaryClip(ClipData.newPlainText("QQ 群号", QQ_GROUP_NUMBER))
        true
    }.getOrDefault(false)
    if (!copied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        val message = if (copied) "已复制群号。" else "复制群号失败，请稍后重试。"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private suspend fun saveRewardCode(context: Context): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val metadata = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "UPC-Pocket-WeChat-Reward.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/UPC Pocket/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val imageUri = resolver.insert(images, metadata) ?: return@runCatching false
        var published = false
        try {
            val output = resolver.openOutputStream(imageUri) ?: return@runCatching false
            output.use { destination ->
                context.resources.openRawResource(R.raw.wechat_reward_qr).use { input ->
                    input.copyTo(destination)
                }
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            published = resolver.update(imageUri, ready, null, null) == 1
            published
        } finally {
            if (!published) runCatching { resolver.delete(imageUri, null, null) }
        }
    }.getOrDefault(false)
}
