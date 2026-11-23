# 仓库内部契约

本文索引宿主、基础模块和 feature 之间实际使用的 Kotlin 契约，以及 iOS 网页版使用的 Web 服务公共接口。核对日期：Android 契约 2026-11-17（对应 2.0.8 开发代码），Web 服务 2026-11-22。Android 客户端没有自建 REST 服务或对第三方承诺稳定的远程 API；iOS 网页版由独立的 [`server/`](server/) 多用户 Web 服务提供同源接口，部署与运维见 [部署手册](deploy/README.md)。学校私有协议细节属于各业务适配，不在此复制带身份的请求或会话。完整签名和默认值以链接源码为准；修改公共面同时更新本文、[架构](ARCHITECTURE.md)与所有消费者。

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

2.0.8 增加 `UiSurface.SERVICE_DIRECTORY`，`PluginEntry.category: UiCategory = GENERAL` 和 `keywords: String = ""` 作为分类/搜索元数据；分类为学习与空间、校园生活、校务与成长、常用工具。稳定 ID 和路由保持；服务目录与首页固定共用贡献，不复制业务状态。宿主 `official.calendar` 是固定公开外链服务，不塞入课表领域契约，也不要求账号或课表插件。

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

`SchoolAccountId` 为内部不可逆标识，不是学校官方身份。2.0.8 的 `StudentIdFormat` 统一接受整串 8–16 位 ASCII 数字，以及大写 S/Z/M 加 8 位数字、TS 加 9 位数字、BY 加 10 位数字。受控身份、卡业务 CAS 和流水本人核验共用该规则；不截取前缀、不自动改变大小写，学校返回身份必须完整一致。格式依据不是完整编码规范，也不证明学校接口已支持全部研究生；真实身份字段不得写入诊断。

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
| [TodoStore](feature/todo/src/main/kotlin/com/upcpocket/app/todo/TodoService.kt) | `tasks: Flow<List<TodoTask>>`；原 `add(title, reminderAt)` / `edit(id, title, reminderAt)` 保留，新增带 `dueAt: Long?` 的重载；`delete(id): TodoTask?` | 截止与提醒独立；原新增无截止，原编辑保留已有截止，新编辑可显式清除；写入维护 revision；失败不捏造已保存任务 |
| `TodoStore.setCompletedIfRevision(id: String, revision: Int, completed: Boolean): TodoTask?` | suspend；成功条件更新后返回任务，否则 null | 提醒/撤销旧动作必须守住 revision，不能退化成无条件更新 |
| [LibraryService](feature/library/src/main/kotlin/com/upcpocket/app/library/LibraryService.kt) / [ChaoxingService](feature/library/src/main/kotlin/com/upcpocket/app/library/ChaoxingService.kt) | 各系统会话、本人验证、读取与受控提交 | 两套协议/结果不混用，未知提交保持防重 |
| [FocusService](feature/focus/src/main/kotlin/com/upcpocket/app/focus/FocusService.kt) | 本机计时/记录能力 | 独立于学校账号，Room 历史兼容 |
| [ShiguangService](feature/shiguang/src/main/kotlin/com/upcpocket/app/shiguang/ShiguangService.kt) / [ServicePortalService](feature/service-portal/src/main/kotlin/com/upcpocket/app/serviceportal/ServicePortalService.kt) | 各自只读查询/分页与 UI 状态 | 失败、有效空、部分结果与登录回退分别表达 |

各能力键在所属 `*Plugin.kt` 中声明，修改前检查所有引用。稳定 route/entry/action/provider/Worker 标识也会被通知、桌面和系统持久化任务消费；修改需兼容策略，不能只改 UI 调用处。

Todo 新增 `pendingCreateRequest: StateFlow<String?>`、`openCreate(): String`、`openCreate(requestId: String): Boolean` 与 `consumeCreateRequest()`，与原任务打开请求分别消费；只有活动页面打开编辑器。`TodoLaunchContract` 的新建/打开只接受完整 UUID 分组，合法大小写规范为同一 UUID，拒绝 `UUID.fromString` 宽松接受的缩写。`readWidgetSnapshot(maxVisibleRows: Int = 4)` 异步读取本机库，区分 `NotRestored`、`ReadFailed` 与 `Ready(tasks, incompleteCount)`；Ready 空列表才是有效空，总数包含全部未完成任务。`onTasksChanged` 是宿主注入的窄本地刷新回调，覆盖存储/revision 的成功变化、首次恢复成功与首次恢复失败的状态更新，不向 feature 暴露 AppGraph；回调失败不改变已提交结果。

[TodoWidgetRenderer](feature/todo/src/main/kotlin/com/upcpocket/app/todo/TodoWidgetRenderer.kt) 只接收本机摘要、实际尺寸派生的 `TodoWidgetLayout` 和宿主提供的 `TodoWidgetActions`；最多四行，总数不限于可见行。标题/数量、正文、新建与完成分别绑定动作，完成使用 `setOnCheckedChangeResponse`，PendingIntent 的身份/可变性与实例验证归宿主；null 摘要表示插件不可用，不等同有效空。

[WidgetAppearance](core/designsystem/src/main/kotlin/com/upcpocket/app/designsystem/WidgetAppearance.kt) 的全局偏好识别 `SOLID`、`TRANSLUCENT`、`GLASS`，缺键或未知值回退近实底。宿主按 Widget ID 的 Provider 给独立玻璃版传入 `FROSTED_GLASS`，给原组件传入 `UiPrefsStore` 异步恢复的全局偏好。`WidgetRenderSupport.applyAppearance(views, rootViewId, appearance)` 设置静态背景，`WidgetAppearance.textColorResource` 为磨砂背景选择次要文字与状态颜色资源。两种 Provider 共用 renderer、数据发布屏障和每类刷新任务。电量的显式刷新动作指向实际 Provider，待办完成校验两个同类 Provider 的当前实例归属。待办文字用资源 ID 交 Launcher 解析，校园卡、电量和课表的部分文字由应用渲染时解析。保存全局外观只触发本地重绘。

2.0.7 第二部分组件调度仍由宿主拥有：课表边界和 30 分钟兜底只调用本地快照/恢复路径，不能把 `open()` 或前台联网刷新放入自动周期；校园卡 30 分钟联网读取仅在有组件时保留，目标执行时从当前受控绑定取得。移除课表/校园卡刷新按钮时须联查正式宿主、debug 预览及 renderer 测试消费者；旧系统组件和动作身份保持兼容，电量刷新及全部整卡导航保留。

## Web 配置与学校接入模式

`GET /api/config` 无参数、无凭据，返回 `{schoolAccess}`。`schoolAccess` 为 `official`（默认）或 `station`：`official` 时服务端不接收任何学校凭据，PWA 只展示学校官方入口；`station` 仅在取得学校授权且 ECS 能访问学校上游时显式开启。响应 `Cache-Control: no-store`。前端读取失败或看到其它值时一律按 `official` 处理。

## Web 登录与会话

来源：[登录路由](server/app/routes/auth.py)、[加密](server/app/crypto.py)、[会话](server/app/sessions.py)、[请求安全](server/app/security.py)、[站内登录前端](web/login.js)。所有响应 `Cache-Control: no-store`，并带 `X-Request-Id`。所有非 GET 请求（含登录）要求 `Origin` 精确等于 `PUBLIC_ORIGIN`（存在时）且 `Sec-Fetch-Site` 为 `same-origin`/`none`，否则返回 403 `invalid_request`。

> `SCHOOL_ACCESS_MODE=official`（默认）时，下列 `/api/auth/*`、`/api/library/*` 端点返回 403 `station_disabled`；`GET /api/library/session` 返回 `{state:"unavailable"}`。只有 `station` 模式且取得学校授权后才恢复下文契约。

| 方法与路径 | 请求与结果 |
| --- | --- |
| `GET /api/config` | 无参数。返回 `{schoolAccess:"official"|"station"}`，见上节。 |
| `GET /api/auth/key` | 无参数。返回 `{kid, spki, alg, enc}`：`kid` 为当前登录密钥编号，`spki` 为 SPKI DER 的标准 base64（带填充，不是 base64url），`alg` 固定 `RSA-OAEP-256`，`enc` 固定 `AES-256-GCM`。端点只返回公开参数，不写服务端状态。 |
| `POST /api/auth/login` | JSON `{kid, nonce, wrapped, iv, ciphertext, branch, consent}`。`nonce` 由浏览器用 `crypto.getRandomValues` 生成（32 字节 base64url，1–64 字符），必须与密文内 nonce 一致；`wrapped`/`iv`/`ciphertext` 为无填充 base64url；`ciphertext` 用一次性 AES-256-GCM 密钥加密 JSON `{"username","password","nonce"}`（密文内也要带同一个 nonce），密钥再以 RSA-OAEP-SHA256 包裹。`branch` 为 `tangdaowan`、`east` 或 `qiushi`；`consent` 必须为 `true`。成功返回 `{state:"ready", branch, revision, csrf, bookingAvailable, recordsAvailable}` 并下发会话 Cookie；`recordsAvailable` 仅唐岛湾为 `true`。 |
| `GET /api/session` | 无有效 Cookie 时返回 `{state:"anonymous"}`；有效时返回 `{state:"ready", revision, csrf, branches, bookingAvailable}`，`branches` 为三个馆区是否已有学校会话。 |
| `POST /api/logout` | 需 `X-CSRF-Token`。清理该用户三个馆区的学校会话与浏览器会话，返回 `{state:"anonymous"}` 并删除 Cookie。 |

登录 nonce 是一次性防重放标识：服务端解密后核对密文内 nonce 与请求顶层 nonce 一致，再在 Redis 以 `SET NX` 把该 nonce 登记 600 秒（`upcp:nonce:*`，本地开发的内存后端用等价过期表）；首次提交成功，重复提交或已登记都返回 400 `invalid_request`。因此即使 TLS 终止点或反向代理拿到完整信封密文，也不能把它重放成受害者的会话；每个登录请求都应生成新的 nonce，失败后不能复用旧信封。公钥端点不写服务端状态。

生产 Cookie 名为 `__Host-upcp_session`，`Secure`、`HttpOnly`、`SameSite=Lax`、`Path=/`；本地开发可为 `upcp_session`。浏览器会话默认空闲 30 分钟、绝对 8 小时（`SESSION_IDLE_SECONDS=1800`、`SESSION_ABSOLUTE_SECONDS=28800`，可由环境变量调整）；登录成功后轮换会话 ID 与 revision，更换用户时清理旧用户的馆区会话。登录默认限流 5 次/10 分钟/IP 与 5 次/10 分钟/登录名（登录名经 HMAC 计数），超限返回 429 `rate_limited`。登录请求体上限 8 KB，Caddy 统一限制 16 KB。

部署在 Caddy 后时（`TRUST_PROXY=true`），应用只信任 `X-Forwarded-For` 的**最后一项**（Caddy 写入的真实连接地址），不信任客户端自带的前缀；该地址只参与登录限流键与审计的 HMAC（IPv6 聚合到 /64），不保存明文 IP。该假设适用于「单层 Caddy、前面没有 CDN/透传代理」；若增加前置代理，需显式配置 Caddy `trusted_proxies` 并复核取址逻辑。

## Web 公开查询

来源：[公开数据路由](server/app/routes/public.py)、[缓存](server/app/cache.py)。`server/` 在 PWA 同源提供公开数据的只读 GET 路径。成功返回规范化 JSON，错误返回固定 `{"error":"…"}` 和 HTTP 状态。接口响应标记 `no-store`，缓存状态另由 `X-Cache` 表示。

| 路径 | 请求与结果 |
| --- | --- |
| `/api/classrooms` | `campus` 为 `tangdaowan` 或 `guzhenkou`，`date` 为 ISO 日期，`building` 为楼栋名，`period` 重复传 1–13 节；四个字段均为必填。返回查询、楼栋选项、公开教室清单和查询时间。仅学校明确返回的交集条目标为空闲。 |
| `/api/calendar` | 无参数。返回教务处最近发布的可识别学年、发布日期、官方原文和两学期原图地址。 |
| `/api/service-catalog` | 无参数。返回 e站通公开目录中的事项编号、名称、部门与同号歧义。 |
| `/api/service-catalog/guide` | `id` 为公开目录中的事项编号，须为 1–19 位非零开头数字。返回指南的文本字段和截断提示，移除流程提交与附件链接。 |

教室缓存 60 秒，校历、目录和指南缓存 1 小时。命中成功缓存时 `X-Cache: hit`；重新读取成功时为 `miss`；上游失败且存在上次成功缓存时返回该缓存并在 JSON 加 `stale:true`、`X-Cache: stale`；没有可用缓存时返回 503 `upstream_unavailable`，不返回成功空结果。[`tools/web/serve.py`](tools/web/serve.py) 保留为旧的本地单用户预览，不是生产入口。

## Web 图书馆预约

来源：[图书馆路由](server/app/routes/library.py)、[图书馆编排](server/app/library/service.py)、[预约日志](server/app/library/journal.py)、[图书馆前端](web/library.js)。`server/` 默认启用三个馆区站内预约，`BOOKING_ENABLED=false` 时 `GET /api/library/session` 返回 `{"state":"unavailable"}` 且写接口返回 403 `booking_disabled`。唐岛湾使用 `/api/library/…`，古镇口东区和求是使用 `/api/library/{east|qiushi}/…`。所有响应使用 `Cache-Control: no-store`；浏览器会话过期返回 401 `session_expired`，学校会话失效返回 401 `library_session_expired` 并需重新登录，revision 不匹配返回 409 `library_session_changed`。`GET /api/library/login`（含馆区路径）只为 PWA 兼容返回 303 跳转到 `/?library=1#library`（东区 `#library-east`、求是 `#library-qiushi`），不提供登录表单。

`/api/library/session` 在 `sign_in` 时提示登录，`ready` 时返回 `revision`、`csrf`、`bookingAvailable` 和 `recordsAvailable`（仅唐岛湾为 `true`）。读取 `catalog`、`reservations` 需带 `X-Library-Session`（当前 revision）；写请求另需 `X-CSRF-Token`。

| 方法与路径 | 请求与结果 |
| --- | --- |
| `GET session` | 返回 `{state}`；`unavailable` 使用官方入口，`sign_in` 可发起站内登录，`ready` 返回 `revision`、`csrf`、`bookingAvailable`、`recordsAvailable`。 |
| `GET catalog` | 无参数返回 `{buildings, dates}`。按顺序增加 `building,date`、`room`、`seat`、`start`，依次返回 `{rooms}`、`{seats,layout}`、`{starts}`、`{ends}`；最多 6 个参数。`dates` 为 ISO 日期列表，其他列表由字符串 `{id, label}` 组成，可附房间数量、座位状态、坐标和布局尺寸。沿用学校座位、日期和起止时间标识，选项以学校此次返回为准。 |
| `POST book` | 使用 `X-Library-Session`、`X-CSRF-Token` 和 JSON `{id,building,date,room,seat,start,end}`，`id` 为客户端本次确认生成的 UUID。结果 `status` 为 `challenge`、`rejected`、`submitted`、`unknown` 或超星 `accepted`。超星明确接受的响应显示已受理，正式记录请在官方页查看；超时或无法辨认的结果按未知处理。 |
| `GET reservations` | 唐岛湾返回 `{pending,records,cancellationPending}`。记录带 `cancellable` 与 `cancelPending`，仅本人当前待履约记录可取消。可传 `attempt=<id>` 或 `cancelAttempt=<id>` 查询操作状态。预约 `confirmed` 含与之匹配的 `recordId`。超星返回 `{available:false}`。 |
| `POST cancel` | 唐岛湾使用 `X-CSRF-Token` 和 JSON `{id,reservationId}`，`id` 为取消请求 UUID。服务复核本人当前预约后向学校发送一次取消。学校明确显示已取消，或接受取消且重查记录确认目标已消失，返回 `cancelled`；明确拒绝返回 `rejected`，结果未知返回 `unknown`；取消结果未知且当前记录已无该预约时返回 `absent`。 |
| `POST logout` | 需 `X-Library-Session` 与 `X-CSRF-Token`。清理该馆区学校会话，返回 `{state:"sign_in"}`。全局退出使用 `POST /api/logout`。 |

服务按用户串行处理其预约/取消，锁超时返回 503 `upstream_unavailable`。写入时核验同源、CSRF、当前账户、原始座位与时段、验证码要求，查询学校当前有效预约，持久登记本次 UUID 及提交前记录 ID，再向学校发起单次提交；重复请求同一 UUID 返回已知状态。学校仍有相同有效预约时拒绝再次发送，已取消或仍待核对的历史尝试可与新预约并存。只有出现此前未见且可唯一归入本次尝试、匹配本人、日期、房间、座位及完整时段的学校记录，才能返回 `confirmed`；结果未知时提示核对官方记录，不自动重发。见 [ADR 012](docs/adr/012-multi-user-web-library-service.md)。

## Web 本机课表

[V3 解析器](web/timetable-import.js)对粘贴文本和严格 UTF-8 文件使用同一套完整学期校验，涵盖学期信息、唯一且连续的周编号、每周七天日期与课程字段。星期取实际日期，节次取 `lessArr`，课程保留原始字段。错误或缺失周不能作为成功空周导入。[本机存储](web/timetable-store.js)以学年和学期为键，在 IndexedDB 事务提交后返回结果。

## 错误与验证约定

边界返回固定错误或清晰异常语义，禁止用任意上游自由文本作为公共错误或诊断。空值表示的不可用、未查过或条件更新失败，以对应接口定义为准；不能跨接口假设 null 等于成功空集合。异步结果在最终提交前重新核对上下文。

Web 服务实际使用的固定错误码如下（响应体为 `{"error":"…"}`，上游自由文本不进入响应）：

| HTTP | 错误码 | 触发场景 |
| --- | --- | --- |
| 400 | `invalid_request` | 参数、JSON、信封字段、UUID 或登录 nonce 校验失败（含 nonce 不一致、已登记/重放） |
| 400 | `consent_required` | 登录未勾选同意 |
| 401 | `invalid_credentials` | 学校明确判定账号或密码不正确（网络/TLS/协议故障不使用该码） |
| 401 | `session_expired` | 浏览器会话缺失、过期或已退出 |
| 401 | `library_session_expired` | 馆区学校会话失效，需重新登录 |
| 403 | `invalid_request` | 非同源写入、缺少或错误的 `X-CSRF-Token` |
| 403 | `booking_disabled` | 部署关闭了预约写操作 |
| 403 | `station_disabled` | 部署为 `official` 模式，站内登录/预约端点整体停用（不接收学校凭据） |
| 403 | `upstream_access_denied` | 学校侧拒绝访问（访问限制、账号状态或校内网络要求） |
| 404 | `unknown_route` | 未定义路径或不存在的馆区 |
| 409 | `interaction_required` | 学校要求验证码或额外验证，需转官方页面 |
| 409 | `library_session_changed` | `X-Library-Session` 与当前 revision 不一致 |
| 409 | `challenge_required` | 上游要求验证码，当前请求未完成 |
| 413 | `invalid_request` | 登录或业务请求体超限 |
| 415 | `invalid_request` | 写请求 `Content-Type` 不是 `application/json` |
| 429 | `rate_limited` | 登录超出频率限制 |
| 500 | `internal` | 未处理的服务端异常 |
| 502 | `upstream_tls_error` | 与学校上游的 TLS 握手被中断或失败（非账号密码问题） |
| 502 | `upstream_protocol_error` | 学校上游返回内容无法解析（页面或接口变更） |
| 502/503 | `upstream_unavailable` | 学校上游网络不可达、超时、熔断、上游 5xx 或预约锁超时 |

修改契约后，按 [CONTRIBUTING](CONTRIBUTING.md) 运行受影响生产者/消费者验证及模块边界；本机/Mock、设备和真实校园证据分别记录。Web 服务涉及 Redis、PostgreSQL 与会话时，至少运行 `server/.venv/bin/python -m pytest`、`node --test tools/web/test_login_crypto.mjs`，并按需启用 `UPCP_TEST_REDIS_URL`/`UPCP_TEST_DATABASE_URL` 集成用例；`server/tests/serve_fake.py` 只是本地合成验证入口，不代表真实学校验收。公开面变化同步本文；重大边界变化记录 ADR，避免以一次接口整理扩大产品或学校写操作权限。
