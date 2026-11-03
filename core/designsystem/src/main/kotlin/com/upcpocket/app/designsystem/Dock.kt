package com.upcpocket.app.designsystem

import androidx.compose.ui.unit.dp

/**
 * 悬浮 dock 为可滚动内容预留的底部余量：胶囊 56 + 上下边距 16 = 72，再加 16 呼吸。
 *
 * 宿主决定 dock 的具体位置；页面只使用这个稳定常量，避免把布局写死在业务模块里。
 */
val DOCK_CLEARANCE = 88.dp
