# 基础能力与认证边界

适用 `core/`，继承[根指令](../AGENTS.md)。先读[架构](../ARCHITECTURE.md)、[内部契约](../API.md)与受影响模块源码；这里只补充基础模块独有约束。

## 模块与入口

| 模块 | 核心文件与工作边界 |
| --- | --- |
| `plugin-api` | [PluginDescriptor.kt](plugin-api/src/main/kotlin/com/upcpocket/app/plugin/api/PluginDescriptor.kt)、[PluginAppLifecycle.kt](plugin-api/src/main/kotlin/com/upcpocket/app/plugin/api/PluginAppLifecycle.kt)：纯 JVM 的标识、能力和生命周期 |
| `plugin-runtime` | [PluginRuntime.kt](plugin-runtime/src/main/kotlin/com/upcpocket/app/plugin/runtime/PluginRuntime.kt)：依赖排序、兼容检查、注册与激活 |
| `plugin-ui` | [PluginUi.kt](plugin-ui/src/main/kotlin/com/upcpocket/app/plugin/ui/PluginUi.kt)：Compose 贡献与 registry，不拥有业务数据 |
| `account-api` | [SchoolAccount.kt](account-api/src/main/kotlin/com/upcpocket/app/account/api/SchoolAccount.kt)：状态、身份引用、代次、失效结果；无明文密码 |
| `account` | [SchoolAccountRepository.kt](account/src/main/kotlin/com/upcpocket/app/account/SchoolAccountRepository.kt)、[AccountFileStore.kt](account/src/main/kotlin/com/upcpocket/app/account/AccountFileStore.kt)：本机账号事务与恢复 |
| `upc-auth` | [UpcCasLogin.kt](upc-auth/src/main/kotlin/com/upcpocket/app/upcauth/UpcCasLogin.kt)、各 `Upc*Authenticator`：学校正常认证与业务会话适配 |
| `platform` | [Diagnostics.kt](platform/src/main/kotlin/com/upcpocket/app/platform/diagnostics/Diagnostics.kt)、`crypto/`：固定诊断模型与基础加密，不含业务实体 |
| `designsystem` | `ui/theme/`、`ui/components/`、`designsystem/`：通用视觉与组件支持，不承担登录、查询或业务缓存 |

## 插件契约

- 静态注册不联网、不采样、不读写业务数据；插件工厂不是反射扫描或动态代码加载。稳定 ID/route/capability 不随类重命名变化。
- 描述符能力与实际能力一致，键类型匹配；宿主 API 不兼容、重复 ID、缺必需依赖、依赖环及初始化失败不能留下半套注册。UI 贡献同样校验后原子提交。
- 改契约先核对 app 与九个 feature 的消费者，再更新 API 和 [ADR 001](../docs/adr/001-module-and-plugin-boundaries.md)。不要仅修改 Kotlin/JVM 层就宣称 Android UI 装配可用。

## 账号与认证

- 修改账号持久化先读 [M1](../docs/m1/README.md)、[隐私](../docs/PRIVACY.md)与 [ADR 002](../docs/adr/002-account-generation-and-business-sessions.md)。保留 AES-GCM、独立 alias、pending/journal/state 与完成 tombstone 的恢复协议；不能重放已完成的个人数据失效。
- `SchoolAccountState` 是本机状态；受控 `studentIdentity` 只接受登录名整串的有效学号，`accountReference` 可支持非纯数字登录名。禁止从脱敏文本反推查询目标。
- 同号改密与换号/删号的清理范围不同；代次变化和 `blocksPersonalSessions` 要在网络结果发布/落盘前复核。网络期间不持有账号事务锁，清理失败保持阻断与可重试状态。
- [SchoolCredentialAccess.kt](account/src/main/kotlin/com/upcpocket/app/account/SchoolCredentialAccess.kt) 的明文读取只供 `upc-auth` 适配 opt-in；一次认证结束释放/清零，不泄漏给 UI、Worker 或 feature。不得以新增公共 decrypt/getPassword API 绕过。
- CAS 密码提交和一次性票据不通用重试；验证码/二次验证停止自动认证，交已有学校正常网页流程。各业务 Cookie/token 边界分离，已实现的业务本人核验和账号代次屏障不能省略；课表索引无本人字段，不宣称它完成了学校身份比对。
- 连接恢复只允许源码已约束的固定只读 GET、共享总预算和取消语义；不可推广到密码、票据、预约或充值写请求。变更先读[2.0.7 证据](../docs/releases/2.0.7-development.md)。

## 验证与同步

JVM 模块用 `:core:plugin-api:test`、`:core:plugin-runtime:test`、`:core:account-api:test`；Android 模块用各自 `:core:<模块>:testDebugUnitTest`。按实际改动选择相关类和 `checkModuleBoundaries`，不机械全部运行。认证修改的 Mock/协议测试与本人真实 CAS 验收分开记录；遵守根指令的真实账密要求。

账号变更联查宿主 invalidator 与个人业务；诊断枚举变更联查宿主汇总/导出及 feature 记录器；加密/文件格式变更联查兼容读取。同步 ARCHITECTURE、API、PRIVACY 与相关 ADR，保持 Android 技能上游副本不变。
