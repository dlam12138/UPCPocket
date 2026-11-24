package com.upcpocket.app.ui.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.CustomBackgroundError
import com.upcpocket.app.data.prefs.CustomBackgroundImportResult
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.MAX_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.MIN_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusIconButton as IconButton
import com.upcpocket.app.ui.components.CampusOutlinedButton
import com.upcpocket.app.ui.components.CampusTonalButton
import com.upcpocket.app.ui.components.CampusTopAppBar
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassSurface as Surface
import com.upcpocket.app.ui.theme.MaterialYouScheme
import com.upcpocket.app.ui.theme.appScaffoldContainerColor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 预设的中文名（放 UI 层，偏好存储里只存枚举） */
private fun ThemePreset.label(): String = when (this) {
    ThemePreset.DYNAMIC -> "跟随壁纸"
    ThemePreset.PURPLE -> "紫"
    ThemePreset.BLUE -> "蓝"
    ThemePreset.TEAL -> "青"
    ThemePreset.GREEN -> "绿"
    ThemePreset.AMBER -> "琥珀"
    ThemePreset.PINK -> "玫红"
    ThemePreset.GRAPHITE -> "石墨"
    ThemePreset.CUSTOM -> "取色卡"
}

private fun DarkMode.label(): String = when (this) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}

private fun UiStyle.label(): String = when (this) {
    UiStyle.MATERIAL3 -> "Material 3"
    UiStyle.LIQUID_GLASS -> "轻量玻璃"
}

internal data class AppearanceUiState(
    val prefs: UiPrefs = UiPrefs(),
    val backgroundPreview: ImageBitmap? = null,
    val extractedBackgroundSeeds: List<Int> = emptyList(),
    /** 是否已保存背景文件；以私有 JPEG 是否可读为准，不以 DataStore 版本号推断。 */
    val hasCustomBackground: Boolean = false,
    val isImportingBackground: Boolean = false,
    val backgroundError: String? = null,
)

class AppearanceViewModel : ViewModel() {

    private val store = AppGraph.uiPrefsStore
    private val backgroundStore = AppGraph.customBackgroundStore

    private val _state = MutableStateFlow(AppearanceUiState())
    internal val state: StateFlow<AppearanceUiState> = _state.asStateFlow()

    /** 只用于丢弃过期读盘结果；读盘都在同一私有文件上完成。 */
    private var previewGeneration = 0

    init {
        viewModelScope.launch {
            store.flow.collect { prefs ->
                _state.update { it.copy(prefs = prefs) }
            }
        }
        // 背景文件是唯一事实来源；首次收集（0L）也读盘，导入/清除成功后重新读盘。
        viewModelScope.launch {
            backgroundStore.revision.collect {
                refreshBackgroundPreview()
            }
        }
    }

    private fun refreshBackgroundPreview() {
        val generation = ++previewGeneration
        viewModelScope.launch {
            val bitmap = backgroundStore.loadBitmap()
            val seeds = if (bitmap != null) backgroundStore.extractSeedsFromSaved() else emptyList()
            if (generation != previewGeneration) return@launch
            _state.update {
                it.copy(
                    backgroundPreview = bitmap,
                    extractedBackgroundSeeds = seeds,
                    hasCustomBackground = bitmap != null,
                )
            }
        }
    }

    fun setPreset(preset: ThemePreset) {
        viewModelScope.launch { store.setThemePreset(preset) }
    }

    fun setCustomThemeSeed(argb: Int) {
        viewModelScope.launch { store.setCustomThemeSeed(argb) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { store.setDarkMode(mode) }
    }

    fun setUiStyle(style: UiStyle) {
        viewModelScope.launch { store.setUiStyle(style) }
    }

    fun setBackgroundOpacity(opacity: Float) {
        viewModelScope.launch { store.setCustomBackgroundOpacity(opacity) }
    }

    fun importBackground(uri: Uri) {
        if (_state.value.isImportingBackground) return
        viewModelScope.launch {
            _state.update { it.copy(isImportingBackground = true, backgroundError = null) }
            when (val result = backgroundStore.importFromUri(uri)) {
                is CustomBackgroundImportResult.Success -> {
                    val preview = backgroundStore.loadBitmap()
                    _state.update {
                        it.copy(
                            backgroundPreview = preview,
                            extractedBackgroundSeeds = result.extractedSeeds,
                            hasCustomBackground = preview != null,
                            isImportingBackground = false,
                            backgroundError = null,
                        )
                    }
                }
                is CustomBackgroundImportResult.Failure -> {
                    val message = when (result.reason) {
                        CustomBackgroundError.UNREADABLE_IMAGE -> "无法读取所选图片，请换一张重试"
                        CustomBackgroundError.SAVE_FAILED -> "保存背景图片失败，请重试"
                    }
                    _state.update {
                        it.copy(
                            isImportingBackground = false,
                            backgroundError = message,
                        )
                    }
                }
            }
        }
    }

    fun clearBackground() {
        if (_state.value.isImportingBackground) return
        viewModelScope.launch {
            _state.update { it.copy(isImportingBackground = true, backgroundError = null) }
            val cleared = backgroundStore.clear()
            if (cleared) {
                _state.update {
                    it.copy(
                        backgroundPreview = null,
                        extractedBackgroundSeeds = emptyList(),
                        hasCustomBackground = false,
                        isImportingBackground = false,
                        backgroundError = null,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isImportingBackground = false,
                        backgroundError = "清除背景失败，请重试",
                    )
                }
            }
        }
    }

    fun reportPickerUnavailable() {
        _state.update { it.copy(backgroundError = "无法打开系统图片选择器") }
    }
}

/**
 * 「外观」页：设置应用内界面风格、深浅色、自定义背景图与配色（含取色卡模式）；
 * 不改变桌面组件外观。
 */
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: AppearanceViewModel = viewModel(),
) {
    val uiState by vm.state.collectAsStateWithLifecycle()
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            vm.importBackground(uri)
        }
    }

    AppearanceScreenContent(
        uiState = uiState,
        onBack = onBack,
        onUiStyleChange = vm::setUiStyle,
        onDarkModeChange = vm::setDarkMode,
        onPresetChange = vm::setPreset,
        onCustomSeedChange = vm::setCustomThemeSeed,
        onPickBackground = {
            try {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            } catch (_: ActivityNotFoundException) {
                vm.reportPickerUnavailable()
            }
        },
        onClearBackground = vm::clearBackground,
        onBackgroundOpacityChange = vm::setBackgroundOpacity,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceScreenContent(
    uiState: AppearanceUiState,
    onBack: () -> Unit,
    onUiStyleChange: (UiStyle) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
    onPresetChange: (ThemePreset) -> Unit,
    onCustomSeedChange: (Int) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onBackgroundOpacityChange: (Float) -> Unit,
) {
    val prefs = uiState.prefs
    val effectiveDark = when (prefs.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }

    Scaffold(
        containerColor = appScaffoldContainerColor(),
        topBar = {
            CampusTopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "界面风格",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiStyle.entries.forEach { style ->
                    CampusFilterChip(
                        selected = prefs.uiStyle == style,
                        onClick = { onUiStyleChange(style) },
                        label = { Text(style.label()) },
                    )
                }
            }

            Text(
                text = "深浅色",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkMode.entries.forEach { mode ->
                    CampusFilterChip(
                        selected = prefs.darkMode == mode,
                        onClick = { onDarkModeChange(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }

            Text(
                text = "自定义背景",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            CustomBackgroundCard(
                hasCustomBackground = uiState.hasCustomBackground,
                previewBitmap = uiState.backgroundPreview,
                opacity = prefs.customBackgroundOpacity,
                isImporting = uiState.isImportingBackground,
                errorMessage = uiState.backgroundError,
                onPickBackground = onPickBackground,
                onClearBackground = onClearBackground,
                onOpacityChange = onBackgroundOpacityChange,
            )

            Text(
                text = "配色",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    ThemePreset.entries.forEach { preset ->
                        PresetRow(
                            preset = preset,
                            customSeedArgb = prefs.customThemeSeedArgb,
                            selected = prefs.themePreset == preset,
                            dark = effectiveDark,
                            onClick = { onPresetChange(preset) },
                        )
                    }
                    AnimatedVisibility(visible = prefs.themePreset == ThemePreset.CUSTOM) {
                        Column(Modifier.fillMaxWidth()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                            ColorPickerPanel(
                                selectedArgb = prefs.customThemeSeedArgb,
                                dark = effectiveDark,
                                extractedBackgroundSeeds = uiState.extractedBackgroundSeeds,
                                onColorSelected = onCustomSeedChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomBackgroundCard(
    hasCustomBackground: Boolean,
    previewBitmap: ImageBitmap?,
    opacity: Float,
    isImporting: Boolean,
    errorMessage: String?,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onOpacityChange: (Float) -> Unit,
) {
    var draftOpacity by remember { mutableFloatStateOf(opacity) }
    LaunchedEffect(opacity) { draftOpacity = opacity }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasCustomBackground && previewBitmap != null) {
                val previewShape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clip(previewShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, previewShape)
                        .testTag("custom_background_preview"),
                ) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "自定义背景预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = draftOpacity },
                    )
                    Text(
                        text = "已启用自定义背景",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else {
                Text(
                    text = if (hasCustomBackground && !isImporting) {
                        "背景图片不可用，请重新上传或恢复默认"
                    } else {
                        "上传本地图片作为应用背景，仅保存在本机"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (hasCustomBackground) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "背景可见度",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${(draftOpacity * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("custom_background_opacity_label"),
                        )
                    }
                    Slider(
                        value = draftOpacity,
                        onValueChange = { draftOpacity = it },
                        onValueChangeFinished = { onOpacityChange(draftOpacity) },
                        valueRange = MIN_CUSTOM_BACKGROUND_OPACITY..MAX_CUSTOM_BACKGROUND_OPACITY,
                        modifier = Modifier.testTag("custom_background_opacity_slider"),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CampusTonalButton(
                    onClick = onPickBackground,
                    enabled = !isImporting,
                    modifier = Modifier.testTag("custom_background_upload_button"),
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasCustomBackground) "更换图片" else "上传图片")
                }

                if (hasCustomBackground) {
                    CampusOutlinedButton(
                        onClick = onClearBackground,
                        enabled = !isImporting,
                        modifier = Modifier.testTag("custom_background_clear_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("恢复默认")
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("custom_background_error"),
                )
            }
        }
    }
}

/** 一行配色：左边三色块（主色 / 主色容器 / 第三色），右边名字，选中打勾 */
@Composable
private fun PresetRow(
    preset: ThemePreset,
    customSeedArgb: Int,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val swatches = rememberPresetSwatches(preset, customSeedArgb, dark)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("theme_preset_${preset.name.lowercase()}"),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(width = 56.dp, height = 28.dp)) {
                swatches.forEachIndexed { index, color ->
                    Swatch(color = color, offsetIndex = index)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.label(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (preset == ThemePreset.CUSTOM) {
                    Text(
                        text = formatHexColor(customSeedArgb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 三个重叠的圆点，用来看这套配色长什么样 */
@Composable
private fun Swatch(color: Color, offsetIndex: Int) {
    Box(
        Modifier
            .padding(start = (offsetIndex * 14).dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** 某个预设（按当前深浅档）的三个代表色 */
@Composable
private fun rememberPresetSwatches(
    preset: ThemePreset,
    customSeedArgb: Int,
    dark: Boolean,
): List<Color> {
    val context = LocalContext.current
    return remember(preset, customSeedArgb, dark) {
        val seed = when (preset) {
            ThemePreset.CUSTOM -> customSeedArgb
            else -> preset.seedArgb
        }
        if (seed != null) {
            MaterialYouScheme.previewSwatches(seedArgb = seed, dark = dark)
        } else {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary)
        }
    }
}

@Preview(showBackground = true, heightDp = 1280)
@Composable
private fun AppearanceScreenColorPickerPreview() {
    com.upcpocket.app.ui.theme.UpcPocketTheme(
        darkMode = DarkMode.LIGHT,
        preset = ThemePreset.CUSTOM,
        customSeedArgb = 0xFF1A73E8.toInt(),
    ) {
        AppearanceScreenContent(
            uiState = AppearanceUiState(
                prefs = UiPrefs(
                    themePreset = ThemePreset.CUSTOM,
                    customThemeSeedArgb = 0xFF1A73E8.toInt(),
                ),
            ),
            onBack = {},
            onUiStyleChange = {},
            onDarkModeChange = {},
            onPresetChange = {},
            onCustomSeedChange = {},
            onPickBackground = {},
            onClearBackground = {},
            onBackgroundOpacityChange = {},
        )
    }
}
