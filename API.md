# 仓库内部契约

本文索引宿主、基础模块和 feature 之间实际使用的 Kotlin 契约，核对日期 2026-11-16。项目没有自建 REST 服务或对第三方承诺稳定的远程 API。学校私有协议细节属于各业务适配，不在此复制带身份的请求或会话。完整签名和默认值以链接源码为准；修改公共面同时更新本文、[架构](ARCHITECTURE.md)与所有消费者。

## 插件、能力与 UI

来源：[PluginDescriptor.kt](core/plugin-api/src/main/kotlin/com/upcpocket/app/plugin/api/PluginDescriptor.kt)、[PluginId.kt](core/plugin-api/src/main/kotlin/com/upcpocket/app/plugin/api/PluginId.kt)、[PluginRuntime.kt](core/plugin-runtime/src/main/kotlin/com/upcpocket/app/plugin/runtime/PluginRuntime.kt)。

| 契约/调用 | 输入与返回 | 约定/错误 |
| --- | --- | --- |
| `PluginDescriptor` | id、version、hostApi 区间、dependencies、capabilities | 当前宿主 API 为 1；描述静态公开信息；稳定 ID 带归属；不含业务数据 |
| `Plugin.descriptor` / `capabilities()` | 描述符；`List<PluginCapability>` | 实际能力 ID 与描述符一致，值类型匹配 `CapabilityKey<T>` |
| `PluginFactory.create(): Plugin` | 无参数，由宿主闭包注入依赖 | 仅内存装配，不联网或写业务文件；不反射发现 |
| `PluginRuntime.register(descriptor, factory): PluginRuntime` | 描述符与工厂 | 重复注册不覆盖；后续 plan/start 检查兼容、依赖与初始化 |
| `PluginRuntime.plan(): PluginPlan` | 有序插件与固定诊断 | `isValid` 表示诊断为空；不是业务可用性证明 |
| `PluginRuntime.start(): PluginStartResult.Started` | 激活集合及诊断 | 失败插件/传递必需依赖被隔离；宿主决定 debug 报错或 release 降级 |
| `ActivePlugins.capability(key: CapabilityKey<T>): T?` | 具体类型的能力键 | 可选插件缺失返回 null；系统入口处理不可用，不绕过停用直接实例化 |

[PluginAppLifecycle](core/plugin-api/src/main/kotlin/com/upcpocket/app/plugin/api/PluginAppLifecycle.kt) 有 `fun onAppCreated()`、`suspend fun onAppStart()` 默认空实现，只对最终激活插件调用。轻量初始化与本机恢复分阶段，异常按插件隔离。

[PluginUi.kt](core/plugin-ui/src/main/kotlin/com/upcpocket/app/plugin/ui/PluginUi.kt) 的 `PluginUiContributor.contribute(registry)` 添加 `PluginEntry`、`PluginPage`、`PluginSection`、`PluginBottomNav`；registry 校验重复 ID/路由和归属后构建 `PluginUiSnapshot`。宿主负责 fork/原子合并及失败隔离。`PluginPageHost.open(route: PluginRouteId)`、`back()`、账号设置入口是窄导航面；feature 不拿任意宿主 NavController。`LocalPluginPageActive` 表示可执行前台动作，`LocalDockClearance` 表示宿主悬浮导航留白。

## 账号状态与受控身份

所有公开类型来自 [SchoolAccount.kt](core/account-api/src/main/kotlin/com/upcpocket/app/account/api/SchoolAccount.kt)。账号 API 不返回密码或通用解密器。

| 契约 | 参数/返回 | 调用约定 |
| --- | --- | --- |
| `SchoolAccountGate.state` | `StateFlow<SchoolAccountState>` | 本机状态、代次、脱敏信息、清理标志和固定错误码；非学校认证状态 |
| `SchoolAccountState.captureRequestToken()` | `AccountRequestToken?` | 仅已保存且代次有效时捕获；发布前配合 `isCurrent(token)` 与业务清理门闩检查 |
| `SchoolAccountStudentIdentityReader.studentIdentity` | `StateFlow<SchoolStudentIdentity?>` | 内部 accountId、受控本人学号、凭据代次；无效学号或阻断时 null |
| `SchoolAccountReferenceReader.accountReference` | `StateFlow<SchoolAccountReference?>` | 内部 accountId 与代次；不要求登录名为纯数字，供课表等隔离归属 |
| `SchoolAccountIdentityVerifier.verifyLoginName(expectedAccountId, expectedCredentialGeneration, candidateLoginName)` | suspend；`SchoolAccountIdentityCheck` | 比较学校返回身份；MATCH/MISMATCH/CHANGED/UNAVAILABLE，不暴露已存登录名 |
| `SchoolAccountReader.restore()` | suspend；Unit | 宿主触发本机恢复，不主动登录全部业务 |
| `SchoolAccountAdmin.save(account: String?, password: CharArray)` | suspend；`SchoolAccountWriteResult` | 当前输入交设置页持有；Success 仅本机提交成功，Failure 为固定 `SchoolAccountErrorCode` |
| `SchoolAccountAdmin.delete()` / `retryCleanup()` | suspend；`SchoolAccountWriteResult` | 由账号设置持有；完成必要失效协调，失败不能假装清理已完成 |
| `SchoolAccountInvalidator.invalidate(request: AccountInvalidationRequest)` | suspend；`AccountInvalidationResult` | request 含原因、范围、新旧代次；幂等，只返回固定失败，不携带异常原文 |

`SchoolAccountController` 合并 reader/admin，但不作为业务插件的通用管理能力。`blocksPersonalSessions` 在 LOADING、cleanupRequired、SWITCHING、UPDATING、REMOVING 时阻断个人会话。`SESSIONS_ONLY` 与 `PERSONAL_DATA` 决定清会话或连同归属个人数据清理；消费者不能按界面短文案推断范围。

`SchoolAccountId` 为内部不可逆标识，不是学校官方身份。`SchoolStudentIdentity` 的学号校验为整串 8–16 位 ASCII 数字，不能从任意或脱敏字符串截取。真实身份字段仅用于受控业务，不得写入诊断。

## 受控认证

[SchoolCredentialAccess.withLoginCredential(block)](core/account/src/main/kotlin/com/upcpocket/app/account/SchoolCredentialAccess.kt) 只供 `upc-auth` 以 `RestrictedSchoolCredentialUse` opt-in 调用，返回 `SchoolCredentialUseResult<T>`；暂用 `SchoolLoginCredential` 由作用域释放/清零。普通 feature、页面、组件和 Worker 不得获得它。

[TimetableAuthenticator](core/upc-auth/src/main/kotlin/com/upcpocket/app/upcauth/UpcAuthModels.kt) 的精确签名：

```kotlin
suspend fun authenticate(
    expectedAccountId: SchoolAccountId,
    expectedCredentialGeneration: Long,
): UpcAuthResult
```

`Success` 返回业务 Cookie 列表；`Failure` 返回固定 `UpcAuthFailure`，仅 `INTERACTION_REQUIRED` 可带学校正常登录入口。失败枚举区分凭据、交互、网络、TLS、上游、协议、账号不可用/变化、访问拒绝。结果到达后调用方仍复核账号及代次，不能只凭 Success 持久化。

校园卡、libseat、超星、石光与 e站通各有业务结果/适配接口，不强制套用课表结果类型。入口索引：[UpcCardAuthenticator](core/upc-auth/src/main/kotlin/com/upcpocket/app/upcauth/UpcCardAuthenticator.kt)、[LibraryAccountAuthenticator](feature/library/src/main/kotlin/com/upcpocket/app/library/LibraryAccountAuthenticator.kt)、[ChaoxingAccountAuthenticator](feature/library/src/main/kotlin/com/upcpocket/app/library/ChaoxingAccountAuthenticator.kt)、[石光认证契约](core/upc-auth/src/main/kotlin/com/upcpocket/app/upcauth/UpcShiguangAuthModels.kt)、[e站通认证契约](core/upc-auth/src/main/kotlin/com/upcpocket/app/upcauth/UpcServicePortalAuthModels.kt)。业务 token/Cookie 均为敏感值，不输出结果对象作调试记录。

## 业务能力与系统消费者

这些能力由 app 装配并交给页面、首页或系统入口，不是 feature 之间直接引用实现的许可。新增跨 feature 协作应先设计小型独立契约。

| 公开入口/源码 | 典型调用与结果 | 注意事项 |
| --- | --- | --- |
| [CardBalanceService](feature/card/src/main/kotlin/com/upcpocket/app/card/CardBalanceService.kt) | `suspend prepare()`、`cached(): DirectCardSnapshot?`、`refreshCurrent(): DirectCardSnapshot`、`readWidgetState(): CardWidgetRead`、`clearCache()` | 无任意学号参数；快照归属/稳定 revision 必须校验，失败不返回伪造成功 |
| [DirectCardSnapshot](feature/card/src/main/kotlin/com/upcpocket/app/data/ecard/DirectCardBalance.kt) | 原始 `balanceCents`/`transitionCents`、格式化 `balanceText`/`transitionText`；新增派生 `totalBalanceText` | 总额以十进制相加，页面与组件用总额为主值、小字展示过渡余额；不改原始字段含义或持久化格式 |
| [PowerService](feature/power/src/main/kotlin/com/upcpocket/app/power/PowerService.kt) | 电量查询与快照能力 | 唐岛湾输入/协议，组件与页面同源；完整签名以源码为准 |
| [TimetableService](feature/timetable/src/main/kotlin/com/upcpocket/app/timetable/TimetableService.kt) | `state`/`todayState`，`suspend open()`、`refresh()`、`selectWeek(week: Int)`、`refreshToday()`；`readWidgetSnapshot()` | 查看周与今日分离；refreshToday 为本地恢复，不等同周联网刷新 |
| [TodoStore](feature/todo/src/main/kotlin/com/upcpocket/app/todo/TodoService.kt) | `tasks: Flow<List<TodoTask>>`；`suspend add(title, reminderAt): TodoTask`、`edit(...): Boolean`、`delete(id): TodoTask?` | Room 事实源；标题/时间验证由服务；失败不能捏造已保存任务 |
| `TodoStore.setCompletedIfRevision(id: String, revision: Int, completed: Boolean): TodoTask?` | suspend；成功条件更新后返回任务，否则 null | 提醒/撤销旧动作必须守住 revision，不能退化成无条件更新 |
| [LibraryService](feature/library/src/main/kotlin/com/upcpocket/app/library/LibraryService.kt) / [ChaoxingService](feature/library/src/main/kotlin/com/upcpocket/app/library/ChaoxingService.kt) | 各系统会话、本人验证、读取与受控提交 | 两套协议/结果不混用，未知提交保持防重 |
| [FocusService](feature/focus/src/main/kotlin/com/upcpocket/app/focus/FocusService.kt) | 本机计时/记录能力 | 独立于学校账号，Room 历史兼容 |
| [ShiguangService](feature/shiguang/src/main/kotlin/com/upcpocket/app/shiguang/ShiguangService.kt) / [ServicePortalService](feature/service-portal/src/main/kotlin/com/upcpocket/app/serviceportal/ServicePortalService.kt) | 各自只读查询/分页与 UI 状态 | 失败、有效空、部分结果与登录回退分别表达 |

各能力键在所属 `*Plugin.kt` 中声明，修改前检查所有引用。稳定 route/entry/action/provider/Worker 标识也会被通知、桌面和系统持久化任务消费；修改需兼容策略，不能只改 UI 调用处。

2.0.7 第二部分组件调度仍由宿主拥有：课表边界和 30 分钟兜底只调用本地快照/恢复路径，不能把 `open()` 或前台联网刷新放入自动周期；校园卡 30 分钟联网读取仅在有组件时保留，目标执行时从当前受控绑定取得。移除课表/校园卡刷新按钮时须联查正式宿主、debug 预览及 renderer 测试消费者；旧系统组件和动作身份保持兼容，电量刷新及全部整卡导航保留。

## 错误与验证约定

边界返回固定错误或清晰异常语义，禁止用任意上游自由文本作为公共错误或诊断。空值表示的不可用、未查过或条件更新失败，以对应接口定义为准；不能跨接口假设 null 等于成功空集合。异步结果在最终提交前重新核对上下文。

修改契约后，按 [CONTRIBUTING](CONTRIBUTING.md) 运行受影响生产者/消费者验证及模块边界；本机/Mock、设备和真实校园证据分别记录。公开面变化同步本文；重大边界变化记录 ADR，避免以一次接口整理扩大产品或学校写操作权限。
