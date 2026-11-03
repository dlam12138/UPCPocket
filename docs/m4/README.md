# M4 图书馆统一认证执行记录

本目录记录 M4 的实际实现、边界、验证结果与未完成项。方案依据见
[统一账号与课表工程方案 v0.1](../统一账号与课表工程方案-v0.1.md) 第 6、7、8、9 节；
原始总体计划不改写。M4 只做 `libseat` 统一认证、本人校验与既有会话迁移，不进入 M5
页面设计，也不把 `nspace` 或 Chaoxing 四入口强行并入本次原生认证。

## 状态

| 项目 | 状态 |
| --- | --- |
| `:core:upc-auth` 公共 CAS 表单引擎 | 完成；从课表认证中提取表单、错误页、票据与 Cookie 逐跳校验 |
| 课表认证回归 | 完成；M3 的 10 个认证测试保持通过，公开端点/成功结果未改 |
| libseat 统一入口 → CAS → service ticket | 完成；入口只接受官方 HTTPS，CAS 主机/路径/service 精确匹配 |
| app token 获取 | 完成；CAS 回调逐跳限定 `libspace.upc.edu.cn`，同时识别 query 与 Vue hash 路由 fragment 中的 `token` |
| `/rest/ssoAuth` 兑换 seat token | 完成；query + form token，复用公开页面 HMAC 签名请求头 |
| `/rest/v2/user` 本人校验 | 完成；返回 `id/username/name`，与受控凭据的登录名精确比对，不一致不发布 |
| 业务 token 只交给 feature | 完成；`LibraryAccountAuthenticator` 是 feature 的窄接口，feature 不依赖 `:core:account`、不读密码 |
| 一键使用已保存账号自动认证 | 完成；登录门提供“使用已保存学校账号登录”，不静默后台重复尝试 |
| 正常学校 WebView 验证码/二次验证回退 | 保留；自动认证明确返回 `INTERACTION_REQUIRED` 时提示走学校网页登录 |
| 会话绑定 accountId + 凭据代次 | 完成；加密副本新增归属字段，旧格式/旧账号/旧代次读取即删除 |
| 换号/删号/密码更新/清理失败门闩 | 完成；捕获登录上下文、Repository 代次与账号门闩共同阻止迟到结果 |
| 自动登录/手动登录/恢复的发布原子性 | 完成；网络在登录锁外，最后发布与持久化均由同一上下文复核 |
| 不存在账号时的手动登录 | 保留；只保留内存会话，不把无法归属的 token 写入持久层 |
| Android 36 模拟器真实本人 CAS + 只读 filters/当前预约 | **实际通过**（2026-11-03，`RealLibraryAuthTest`，1 test；未记录 token/账号/预约原文） |
| 真机、release/R8、其他 ROM/WebView | 未执行 |
| 真实验证码/二次验证链路 | 自动认证失败分支与测试完成；本轮真实登录未触发验证码，未单独做真实 WebView 验证 |
| `nspace`、Chaoxing 四入口 | 按范围未接入；保留官方网页入口与研究记录 |
| 座位预约/取消/签到/支付等写操作 | 按范围未做；真实验收只读 |

## 模块与边界

```text
:core:account-api
  SchoolAccountReferenceReader（accountId + 凭据代次，不含密码）
        ↓
:core:account
  SchoolCredentialAccess（受控、opt-in、单次使用后清零）
  SchoolAccountRepository（状态机、加密账密、失效协调）
        ↓
:core:upc-auth
  UpcCasLogin（公共 CAS 表单/错误页/票据/Cookie 引擎）
  UpcTimetableAuthenticator（M3 课表 provider，回归保持）
  UpcLibraryAuthenticator（libseat provider：入口→CAS→app token→seat token→本人）
  UpcLibrarySigning（公开页面配置 HMAC）
        ↓
:feature:library
  LibraryAccountAuthenticator（窄接口，只接收 seat token + 本人标识）
  LibrarySeatRepository（会话、只读 API、代次门闩）
  SchoolSessionStore（accountId/代次绑定的 Keystore 加密副本）
  LibraryService（自动认证、恢复、失效、持久化协调）
  LibrarySeatScreen / LibrarySeatLoginScreen（最小 UI + 官方网页回退）
        ↓
:app
  AppGraph 适配器：core 认证结果 → feature 窄接口
```

`feature:library` 只依赖 `core:account-api`、`core:upc-auth`、设计/插件/平台模块，
不依赖 `core:account`，也没有读取 `SchoolLoginCredential` 的入口。密码只存在于
`core:upc-auth` 的受控 `withLoginCredential` 调用栈内；`LibraryService` 拿不到密码。

## 认证语义

- 入口固定为
  `https://libspace.upc.edu.cn/remote/static/sso/login?redirectUrl=...`，只接受 301/302/303/307/308
  且 Location 必须指向 `https://cas.upc.edu.cn/cas/login`。
- CAS 表单只接受精确的官方主机、`/cas/login`、单一 `service` 参数和与 libseat 固定的
  service 值；表单 action 也要再次通过同一校验。
- 只提交一次密码。检测到验证码/二次验证时返回 `INTERACTION_REQUIRED`，不提交密码；
  错误密码、账号变化、网络、TLS、上游、协议错误分别固定映射，不循环试密码。
- 票据回调只接受 `https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin`
  的 `ticket`，不自动跟随 OkHttp 重定向；后续最多 8 跳，只允许官方 libspace 主机和
  `/libseat` 路径。app token 可从 query 或 `#/login?token=...` 的 fragment 查询串取得。
- `POST /rest/ssoAuth` 同时携带 query/form token 和公开页面 HMAC 头；只接受 200，
  从 `data.token` 取 seat token。`GET /rest/v2/user` 同样签名并只接受 200。
- 返回的 `user.username` 必须与受控凭据的登录名完全一致；否则返回 `IDENTITY_MISMATCH`，
  不发布、不持久化。seat token 只交给 feature；它的持久副本绑定 accountId 与凭据代次。
- 没有已保存账号时仍可走原有学校网页登录，但该会话只在内存中，不写入持久层，避免
  换号后恢复无归属 token。自动认证只在账号状态允许、存在当前账号引用时可用。

## 并发与失效

- 打开登录页/开始自动认证时捕获 `loginEpoch`、`accountGeneration`、`accountId`；网络
  返回后再检查账号门闩、Repository 代次和当前账号引用，迟到结果不能发布。
- `LibraryService` 的登录锁只包住最后的持久化与失效递增；CAS/业务网络不在锁内，
  避免账号清理等待网络，也避免锁内重入造成的死锁。
- 换号/删号/密码更新由统一账号失效器调用 `signOutForAccountChange()`：先递增业务
  代次，再同步清加密副本/内存会话，最后等待学校 Cookie 清理。清理失败时账号层保持
  `cleanupRequired`，登录页、恢复和发布都被门闩阻断。
- 加密副本格式新增 `accountId` 与 `credentialGeneration`；旧格式或不属于当前账号/代次
  的副本在读取时删除。恢复时还会用 `/rest/v2/user` 返回的本人标识与副本复核，不一致
  即清掉。
- 现有 M3 课表路径、M1 tombstone/清理重试、M2 组件隔离未被改写；M3 认证测试保持通过。

## 验证

| 命令/场景 | 结果 |
| --- | --- |
| `./gradlew.bat testDebugUnitTest` | 50 个测试套件、350 个测试，0 失败/0 错误/0 跳过（含 M4 新增认证、身份不一致、迟到结果、持久化绑定与清理测试） |
| `./gradlew.bat checkModuleBoundaries` | 通过 |
| `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest` | 通过 |
| `./gradlew.bat :app:lintDebug :core:upc-auth:lintDebug :feature:library:lintDebug` | 通过；app 2 条既有 manifest/资源警告，core/feature 无问题 |
| `RealLibraryAuthTest` on `emulator-5554` | `OK (1 test)`；真实本人 CAS、`filters`、`myReservations` 只读通过；测试后删除临时凭据、会话与账号，状态回到 `NOT_CONFIGURED` |

真实验收说明：测试 APK 从本机 `E:\UPC-MCP-private` 的 DPAPI 凭据通过标准输入写入
target app 私有临时文件，凭据未进入命令行参数、聊天、日志、源码或 Git；测试结束删除
该文件。未记录账号、密码、Cookie、ticket、app/seat token、预约原文或截图。真实
`filters` 返回非空日期/馆区，当前预约只读调用成功；未提交任何预约/取消/签到。

## 未验证与限制

- 未做真机、release/R8、其他 ROM/WebView 与长期 token TTL 验证；模拟器通过不等于
  release 或其他设备通过。
- 本轮真实登录未出现验证码/二次验证；该分支由合成测试和代码路径覆盖，未单独做真实
  WebView 验证。
- `nspace` 的远程 CAS 登录/本人记录仍未取得；Chaoxing seat/seatengine 只有入口配置与空
  current，reservepc 有列表但没有登录获取/写请求。它们继续保留官方网页入口，不因 M4
  原生 libseat 通过而宣称已接入。
- 六份 HAR 是既有已登录页面的只读证据，不是端到端认证证据；原始 HAR 未修改、未提交。
- 座位布局、时段、历史预约和任何写接口不在本轮范围；M4 验收只证明认证、本人绑定与
  只读列表/当前预约可用。
- 测试后的账号/会话清理是本地清理，不等同于撤销学校服务器上已经签发的会话；后者由
  学校会话过期或用户在学校页面退出决定。
