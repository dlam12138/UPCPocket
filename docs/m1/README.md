# M1 统一学校账号基础执行记录

本目录记录 M1 的实际实现、验证结果与未完成项。方案原文见
[统一账号与课表工程方案 v0.1](../统一账号与课表工程方案-v0.1.md)，原始总体计划不改写。
本阶段只做本机账号基础，不把保存成功表述成学校登录成功，也不提前实现 M2—M4 业务。

## 状态

| 项目 | 状态 |
| --- | --- |
| 一个当前学校账号的设置页（更多 → 学校账号） | 完成 |
| 本机加密保存、修改、删除与脱敏状态 | 完成 |
| 独立 Keystore alias、AES-GCM、随机 IV、版本化密文 | 完成 |
| pending + journal + state + 正式密文的原子/可恢复提交 | 完成 |
| 代次递增、旧会话失效、换号/删号清理协调 | 完成基础与图书馆/校园卡适配 |
| 清理中断后的进程恢复 | 代码与 JVM 测试完成；未做“清理中杀进程”的设备注入 |
| 密钥失效/密文损坏固定错误态 | 代码与 JVM 测试完成；未在设备上破坏 Keystore 实测 |
| 保存后重启读取 | 模拟器实际验证（合成账号） |
| 真实 Keystore 往返（真实本机凭据） | 模拟器 instrumentation 实际通过，未联网 |
| 在线学校认证 | **未做**，M1 不发送账密 |
| 校园卡/图书馆/课表统一认证 | **未接入**，分别留 M2/M3/M4 |

## 模块与边界

```text
:core:account-api   Kotlin/JVM；小型类型化读取、写入结果、代次/请求 token、
                    账号失效请求与结果契约。无 Android、无内部模块依赖。
:core:account       Android；凭据存储、状态机、清理协调、设置页与测试。
                    只依赖 core:account-api / core:platform / core:designsystem。
:core:platform      新增 ByteCrypto 与可指定 alias 的 Keystore AES-GCM；
                    原 KeystoreSessionCrypto 的 alias、Base64 与格式保持不变。
:app                只做宿主装配、路由、Application 恢复与业务失效适配。
```

`checkModuleBoundaries` 增加 `:core:account-api` 无内部依赖检查；`core` 仍不得依赖
`feature` 或 `app`，`feature` 实现之间仍不得互依赖。账号不是插件，也不塞进某个业务
插件；普通业务只面向 `SchoolAccountReader` 读取脱敏状态和捕获请求代次。

## 公开 API 与密码边界

- `SchoolAccountState` 只含状态、代次、脱敏账号、保存时间、清理标志与固定错误码；
  没有密码、原始账号、Keystore 密文或异常原文。
- `SchoolAccountReader` 只提供状态流与恢复；业务插件不接触密码。
- `SchoolAccountController` 的保存入口只接受调用方提供的 `CharArray`，密码不会从
  账号层读回；设置页之外没有把明文密码放回 UI 的接口。
- `SchoolAccountAdmin` 仅由宿主设置页持有。当前没有把该对象注册成插件能力。
- `AccountRequestToken` 捕获凭据代次；状态变化后 `isCurrent` 返回 false，为 M2/M3
  的迟到响应校验提供基础。
- `SchoolAccountInvalidator` 是业务清理接口；实现必须可重复执行，只返回固定失败码。

## 本机存储协议

目录：`noBackupFilesDir/school-account/`。

| 文件 | 内容 | 是否含明文 |
| --- | --- | --- |
| `school-account-v1.enc` | 外层格式/版本 + Base64 AES-GCM 密文；内层为账号、密码、代次、保存时间 | 否 |
| `school-account-pending-v1.enc` | 切换/更新时待提升的完整新版本 | 否 |
| `school-account-state-v1.json` | 状态、代次、保存时间、清理标志与固定错误码 | 否；不含账号/密码 |
| `school-account-transition-v1.json` | 操作、原因、来源/目标代次、清理范围、待提升标志 | 否 |

实现采用 `AtomicFile` 逐文件提交，并用 journal 串联跨文件恢复：

1. 先完整加密新版本到 pending；
2. 原子写 journal；
3. 原子写 UPDATING/SWITCHING/REMOVING 状态，立即阻止旧代次继续被当作正常账号；
4. 串行调用可重复的业务失效器；
5. 成功后才把 pending 提升为正式版本（或删除旧版本），写最终状态，最后删 journal。

进程在任意一步退出，下次 `restore()` 会先读 journal 并继续清理/提升。加密失败、
pending/journal/状态写入失败不会覆盖正式密文；清理失败则保留过渡状态和重试入口。
密钥失效、密文损坏、格式版本不支持时进入 `NEEDS_RECONFIGURATION`，以固定错误码
显示，不崩溃、不降级明文，并清理无法确认归属的个人会话/缓存。

## 失效协调

| 变更 | 代次 | 清理范围 | 保留 |
| --- | --- | --- | --- |
| 首次保存 | +1 | 不自动清理现有业务 | 现有图书馆会话/缓存 |
| 同账号更新密码 | +1 | 会话（图书馆 token、WebView 学校 Cookie） | 同账号历史缓存 |
| 更换账号 | +1 | 会话 + 校园卡个人快照/输入 + 组件内容 | 专注、主题、公开教室、寝室 |
| 删除账号 | +1 | 账密、会话 + 校园卡个人快照/输入 + 组件内容 | 专注、主题、公开教室、寝室 |
| 密钥/密文不可读 | +1 | 会话 + 无法确认归属的个人缓存 | 专注、主题、公开教室、寝室 |

- 图书馆的持久/内存会话清理使用同步 `commit`，WebView 学校 Cookie 清理由
  `clearAllSchoolCookiesAndAwait()` 轮询确认，最多等待 3 秒；超时使账号保持清理中，
  不会提前放行新登录。
- 图书馆登录页捕获登录代次；换号/删号后，迟到的 token 不写回内存或持久层。
- 校园卡失效器调用现有 `CardBalanceService.clear()`，同时刷新组件；不触碰专注数据库、
  主题 DataStore、公开教室数据或寝室配置。
- 任何失效器失败都会把账号留在清理中；用户可重试，进程重启后自动继续。
- 旧“记住学校登录”只控制图书馆 token，不是统一账号密码的保存同意；界面文案已明确。

## 页面与输入安全

- “更多 → 学校账号”只在宿主更多页增加一个分组，不重排首页、底部导航或其他交互。
- 页面区分“未设置 / 已保存，尚未验证 / 需要重新保存 / 切换中 / 更新中 / 删除中”；
  不显示“全部系统已登录”。
- 密码与确认密码默认隐藏，只存在当前 Compose 组合的 `remember`，不使用
  `rememberSaveable`/`SavedStateHandle`；保存尝试、离页与成功保存都会清空输入。
- 页面可见期间设置 `FLAG_SECURE`，并在 `decorView` 禁用自动填充；离页恢复原状。
- 页面明确标注校园卡/图书馆/课表尚未接入统一认证；M1 不发起网络请求。
- 系统备份仍为 `allowBackup=false`，账号目录位于 `noBackupFilesDir`；未新增备份包含项。

## 验证命令

```powershell
.\gradlew.bat --no-daemon checkModuleBoundaries `
  :core:account:testDebugUnitTest :core:account:lintDebug `
  :feature:library:testDebugUnitTest :feature:library:lintDebug `
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

完整模块回归、设备测试和实际结果见[验收记录](验收记录.md)。

## 明确未完成

- 没有在线学校认证；没有把账密发送到 CAS 或任何校园接口，也没有验证码/二次验证路径。
- 没有实现 M2 余额绑定、M3 课表、M4 图书馆统一认证；现有余额学号输入与图书馆网页登录
  保持原行为，页面已如实标注。
- 没有在真实设备、release/R8、不同厂商 WebView 或校园网完成验证。
- 没有设备级“清理中杀进程”“删除 Keystore 密钥”“写满磁盘”注入；这些由 JVM 失败注入
  测试覆盖，真实 Keystore 仅完成正常往返。
