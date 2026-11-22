# 架构决策记录

ADR 记录影响模块边界、数据兼容或信任链的长期决定，不记录普通 Bug 修复。001–004 于 2026-11-16 对既有代码和项目要求作追溯记录。005 与 006 记录 2.0.8 的实施决定，构建与设备验收见开发记录。

| 编号 | 决策 | 状态 |
| --- | --- | --- |
| [001](001-module-and-plugin-boundaries.md) | Gradle 模块、内置插件与显式装配 | Accepted（追溯） |
| [002](002-account-generation-and-business-sessions.md) | 本机账号、凭据代次与独立业务会话 | Accepted（追溯） |
| [003](003-data-ownership-and-schema-compatibility.md) | 数据归属与既有 schema 兼容 | Accepted（追溯） |
| [004](004-release-identity-and-update-trust.md) | 独立新版身份、长期签名与更新信任链 | Accepted（追溯） |
| [005](005-2.0.8-toolchain-and-widget-state.md) | 2.0.8 工具链、待办截止迁移与桌面状态边界 | Accepted（实施决定） |
| [006](006-2.0.8-material-theme-and-glass-widgets.md) | Material 3 主题与独立玻璃组件 | Accepted（更新 005 的外观决定） |
| [007](007-ios-pwa-local-data-and-school-links.md) | iOS PWA 本机数据与首版学校入口 | Accepted（学校入口决策由 008 更新） |
| [008](008-web-public-campus-data.md) | Web 站内公开数据读取与本地课表 | Accepted（课表决策由 009 更新） |
| [009](009-web-semester-timetable-import.md) | Web 学期课表本地文件导入 | Accepted |
| [010](010-web-library-reservation.md) | Web 图书馆预约与校方会话边界 | 前端契约保留，本机接入由 011、公网接入由 012 更新 |
| [011](011-local-library-companion.md) | Mac 本机图书馆预约服务 | Accepted（本机接入由 012 更新） |
| [012](012-multi-user-web-library-service.md) | 公网多用户 Web 图书馆服务 | Accepted |

新增重大决策使用递增编号，写明日期、状态、背景、决策、备选、理由、影响/代价和源码/需求依据。Accepted 表示当前生效；Proposed 尚未采纳；Superseded 需链接替代记录；Deprecated 说明停用原因。变更既有选择时新增关联记录并更新旧记录状态，不重写原决策内容或假造过去讨论。

本批“备选方案”是此次对现行选择的对照分析，不表示当年确实讨论过。当前结构以 [ARCHITECTURE](../../ARCHITECTURE.md) 为入口，API 变化同步 [API](../../API.md)，开发步骤见 [CONTRIBUTING](../../CONTRIBUTING.md)。
