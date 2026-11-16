# ADR 001：模块边界与内置插件

- 状态：Accepted（追溯既有实现）
- 记录日期：2026-11-16
- 依据：M0 方案/实施记录、当前 Gradle、插件及宿主源码；本轮不进行架构迁移。

## 背景

多个校园与本机功能需要独立维护，同时共用宿主、设计系统与基础能力。页面、组件和后台入口应复用业务能力；移除可选功能不能让其他独立模块失效。原始 [M0 方案](../模块化与插件化工程方案-v0.1.md) 和 [实施记录](../m0/README.md) 保留各自阶段状态。

## 决策

采用 Gradle 多模块、随 APK 编译的内置插件、手动 AppGraph 显式装配。plugin-api/runtime 为 JVM，plugin-ui 独立承担 Compose 贡献；feature 纵向拥有业务/数据/UI，无 feature 实现互依赖、core→feature/app 反向依赖。账号实现仅供受控认证等既有边界使用，普通 feature 不依赖它。

插件描述符声明兼容区间、依赖和能力；启动校验与依赖排序，UI 贡献在 fork 中校验后合并。最终活动集合决定生命周期和系统能力；失败插件及其必需依赖者被隔离，独立插件可继续。debug 暴露错误，release 降级。插件构造不联网/写业务数据，不做下载代码、反射发现或插件市场。

## 备选与理由

本次对照分析：单体 app 更易起步但放大耦合；跨 feature 直接调用更短但难以删减装配；动态 DEX/独立进程插件显著增加信任、安装和通信成本；为所有类拆 API 则增加空模块。现行方案保留 Kotlin 类型检查、明确依赖和可选能力，满足当前产品规模。

## 影响与代价

新增插件需同时维护 Gradle、静态工厂、能力和 UI 贡献；不能只新增页面。稳定 ID/路由成为兼容面。Gradle 边界检查不等于网络安全或运行时沙箱；同 APK 插件共享进程/权限，仍需类型、opt-in 和审查约束。

## 证据与验证

[settings](../../settings.gradle.kts)、[边界任务](../../build.gradle.kts)、[PluginDescriptor](../../core/plugin-api/src/main/kotlin/com/upcpocket/app/plugin/api/PluginDescriptor.kt)、[PluginRuntime](../../core/plugin-runtime/src/main/kotlin/com/upcpocket/app/plugin/runtime/PluginRuntime.kt)、[HostUiAssembly](../../app/src/main/java/com/upcpocket/app/core/HostUiAssembly.kt)、[AppGraph](../../app/src/main/java/com/upcpocket/app/core/AppGraph.kt)。变更按影响运行 `checkModuleBoundaries` 及 runtime/UI/宿主定向测试；本记录本身不宣称本轮测试通过。
