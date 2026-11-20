# 宿主与系统入口

适用 `app/`，继承[根指令](../AGENTS.md)。修改前读[架构](../ARCHITECTURE.md)的启动、组件与更新章节；契约改动读 [API](../API.md)。

## 职责与入口

本模块拥有 Application、手动装配、导航、统一设置、Android 系统组件、更新与诊断导出；校园业务模型和协议归 feature。

| 文件（相对本目录） | 作用 |
| --- | --- |
| [UpcPocketApp.kt](src/main/java/com/upcpocket/app/UpcPocketApp.kt) | 诊断安装、插件启动、本机账号恢复后再恢复业务 |
| [AppGraph.kt](src/main/java/com/upcpocket/app/core/AppGraph.kt) | 显式装配、认证适配注入、账号清理协调、可选能力解析 |
| [HostUiAssembly.kt](src/main/java/com/upcpocket/app/core/HostUiAssembly.kt) | UI 贡献校验、原子合并和失败插件隔离 |
| [AppNav.kt](src/main/java/com/upcpocket/app/ui/nav/AppNav.kt) | Pager、胶囊、课表与待办合页、二级路由与系统入口导航 |
| [WidgetRefreshWorker.kt](src/main/java/com/upcpocket/app/widget/WidgetRefreshWorker.kt) | 用户刷新调度与账号有界恢复 |
| [TimetableWidgetTicker.kt](src/main/java/com/upcpocket/app/widget/TimetableWidgetTicker.kt) | 一次性本地时刻边界重绘，不联网 |
| [TodoWidgetProvider.kt](src/main/java/com/upcpocket/app/widget/TodoWidgetProvider.kt) / [TodoWidgetCompleteReceiver.kt](src/main/java/com/upcpocket/app/widget/TodoWidgetCompleteReceiver.kt) | 待办组件与私有 revision 条件完成入口 |
| [TodoWidgetTicker.kt](src/main/java/com/upcpocket/app/widget/TodoWidgetTicker.kt) | 截止、设备本地午夜与有界恢复，只读本机待办 |
| [WidgetAutoRefresh.kt](src/main/java/com/upcpocket/app/widget/WidgetAutoRefresh.kt) | 按组件存在情况维护校园卡联网/课表本地 30 分钟周期，重绘不重置周期 |
| [WidgetTimeReceiver.kt](src/main/java/com/upcpocket/app/widget/WidgetTimeReceiver.kt) | 非导出系统时间、时区、开机与包更新恢复，只排本地任务和重建调度 |
| [UpdateRepository.kt](src/main/java/com/upcpocket/app/update/UpdateRepository.kt) | 已签清单获取与更新流程；其余步骤在同包 |

## 独有约束

- 业务只取得注入的窄接口；不要把 AppGraph 传给 feature 或把业务协议搬进宿主。新增插件同时检查 Gradle、装配、UI 贡献和缺插件路径。
- 能力从最终 `activePlugins` 解析；Provider、Worker、Service 和深链必须承受插件不可用，不能绕过停用状态直接创建业务实现。debug 快速暴露装配错误；release 隔离失败插件。
- `UpcPocketApp` 先恢复账号及未完成清理，再恢复插件；启动恢复不自动遍历校园业务。账号变更立即撤下旧身份组件内容；即使插件缺失，宿主仍需清除对应个人持久化。
- Pager 预加载不能触发隐藏页登录、查询或权限申请；保留 `LocalPluginPageActive` 门控与 `LocalDockClearance` 底部余量。通知/组件点击与选中 tab 同步；二级页不翻一级 Pager。
- RemoteViews 渲染在 feature，Provider/WorkManager 在宿主。card/timetable 冷启动任务有界恢复本机账号，目标从受控身份取得。2.0.7 第二部分课表按边界及 30 分钟兜底只读本地快照，不周期登录学校；校园卡仅在有组件时按 30 分钟联网条件读取本人余额。周期不因重绘重置，最后组件删除取消；旧身份和旧代次任务不得写回。
- 原组件默认采用[视觉修订](../docs/m5/2026-11-05-组件视觉修订.md)的 24dp 圆角近实底，2×2/4×2 按实际 dp 与字体适配。依用户 2.0.8 [外观决定](../docs/design/2.0.8-library-layout-and-widget-appearance.md)，保留原 Provider 身份与其 UiPrefsStore 全局外观，另给四类组件各加一个固定静态玻璃背景的 Launcher Provider。两种外观共用 renderer、业务状态和每类调度，最后一个同类实例移除后才取消任务。保存全局偏好仅本地重绘，保留校园卡发布屏障和周期。课表/校园卡保持整卡导航，电量保留 20dp 刷新图标和 44dp 热区。桌面玻璃仅使用本地静态背景资源。
- 待办组件从最终激活能力异步读本机 Room，未恢复、读取失败、缺插件和有效空列表分开表达；不在 Receiver 主线程阻塞读库。完成只接受受限可变 PendingIntent 的动态勾选值，以当前组件、实例归属、完整 taskId 和 revision 条件完成；普通导航/新建保持 immutable。截止及午夜重绘不触发校园请求，删除最后实例取消本地调度。
- 修改更新前完整读[更新机制](../docs/updates/README.md)与 [ADR 004](../docs/adr/004-release-identity-and-update-trust.md)。保持验签→解析→APK 内容/证书校验→系统确认顺序；不复用诊断 FileProvider。
- 诊断采集模型归 `core:platform`，宿主负责有界存储、导出和清除。导出仅主动触发，固定专用子目录；账号与诊断清理后拒绝迟到事件。

## 验证与联动

按改动选 `:app:testDebugUnitTest`、`:app:lintDebug`、`checkModuleBoundaries`；公共契约还运行相关 core/feature 定向测试。纯文档不跑业务测试。系统入口/R8 改动按实际影响进行 Release 安装冒烟、组件刷新及点击导航；不得为验证卸载或清数据。

导航改动同步[入口映射](../docs/m5/README.md)；账号清理联查 card/library/timetable/shiguang/service-portal；更新与导出联查 Manifest、provider XML、[隐私](../docs/PRIVACY.md)。修改架构、契约或发布流程分别同步 ARCHITECTURE、API、ADR/BUILD/更新机制。

已知源码旧注释称 WorkManager 已移除，与现有组件任务不符；以实际 Worker/ticker 与构建依赖为准，见[审计](../docs/agent-native/2026-11-16-audit-and-delivery.md)。
