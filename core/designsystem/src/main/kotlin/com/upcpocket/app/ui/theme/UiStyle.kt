package com.upcpocket.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.upcpocket.app.designsystem.UiStyle

/** 宿主与业务界面共用的风格；独立页面默认使用 Material 3。 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MATERIAL3 }
