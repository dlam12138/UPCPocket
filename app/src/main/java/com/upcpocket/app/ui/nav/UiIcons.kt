package com.upcpocket.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import com.upcpocket.app.plugin.ui.UiIcon

/** 业务插件只提供稳定图标枚举；Compose 图标由宿主统一映射，避免泄漏 UI 类型。 */
fun UiIcon.filledIcon(): ImageVector = when (this) {
    UiIcon.ELECTRICITY -> Icons.Filled.Bolt
    UiIcon.CLASSROOM -> Icons.Filled.MeetingRoom
    UiIcon.LIBRARY -> Icons.AutoMirrored.Filled.MenuBook
    UiIcon.INSIGHTS -> Icons.Filled.Insights
    UiIcon.TIMER -> Icons.Filled.Timer
    UiIcon.TIMETABLE -> Icons.Filled.CalendarMonth
    UiIcon.TODO -> Icons.Filled.Checklist
    UiIcon.WALLET -> Icons.Filled.AccountBalanceWallet
    UiIcon.RECHARGE -> Icons.Filled.Payments
    UiIcon.APPS -> Icons.Filled.Apps
    UiIcon.MAP -> Icons.Filled.Map
}

fun UiIcon.outlinedIcon(): ImageVector = when (this) {
    UiIcon.ELECTRICITY -> Icons.Outlined.Bolt
    UiIcon.CLASSROOM -> Icons.Outlined.MeetingRoom
    UiIcon.LIBRARY -> Icons.AutoMirrored.Outlined.MenuBook
    UiIcon.INSIGHTS -> Icons.Outlined.Insights
    UiIcon.TIMER -> Icons.Outlined.Timer
    UiIcon.TIMETABLE -> Icons.Outlined.CalendarMonth
    UiIcon.TODO -> Icons.Outlined.Checklist
    UiIcon.WALLET -> Icons.Outlined.AccountBalanceWallet
    UiIcon.RECHARGE -> Icons.Outlined.Payments
    UiIcon.APPS -> Icons.Outlined.Apps
    UiIcon.MAP -> Icons.Outlined.Map
}
