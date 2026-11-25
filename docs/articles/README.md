# UPC Pocket 技术系列

五篇第一人称中文稿，从一款面向中国石油大学（华东）的非官方校园 App 出发，聊端侧架构、学校协议和维护决策。以主线 `a8864e8` 的开发源码与 2026-11-23 公开的 2.1.0 正式版为取材基线。

| 篇目 | 主题 |
| --- | --- |
| [01 · 架构演进](01-architecture.md) | 零自建业务后端、22 个 Gradle 模块、依赖门禁与 Release 装配隔离 |
| [02 · 端侧安全与状态机](02-account-safety.md) | Keystore AES-256-GCM、账号代次、清理事务与防串号 |
| [03 · 协议打通与克制边界](03-protocols.md) | CAS、官方网页登录回退与校园卡微信充值防重 |
| [04 · 离线空间与 UI 取舍](04-offline-and-ui.md) | 校园步骑混合路线、卓越楼平面图与静态磨砂组件 |
| [05 · 工程治理与 AI 协同](05-engineering.md) | iOS 静态 PWA、ADR、AGENTS.md 与开源协作 |

宣传界面、地图和平面图沿用可核对的模拟器画面，网页来自当前仓库的移动尺寸预览。各图标明环境与演示数据范围。 [2.1.0 宣传素材来源](../../media/promo-2.1.0/public/screens/README.md)

[仓库与项目介绍](../../README.md) · [Android 正式版下载](https://github.com/dlam12138/UPCPocket/releases/latest)
