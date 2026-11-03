# M3 课表认证、查询、展示与离线缓存执行记录

本目录记录 M3 的实际实现、边界、验证结果与未完成项。方案依据见
[统一账号与课表工程方案 v0.1](../统一账号与课表工程方案-v0.1.md) 第 5、7、8、9 节；
原始总体计划不改写。M3 只做课表只读闭环，不进入 M4 图书馆统一认证或 M5 页面设计。

## 状态

| 项目 | 状态 |
| --- | --- |
| 独立 Gradle 课表插件 `:feature:timetable` | 完成；数据/协议/缓存/UI/插件入口分离 |
| 真实 Gradle 认证适配 `:core:upc-auth` | 完成；正常 CAS 表单、票据回调与主机会话校验 |
| 更多 → 课表最小功能页 | 完成；学期选择、教学周切换、周网格、今日课程、详情、主动刷新 |
| 首入只查当前周、切周按需查询 | 完成；不遍历整学期、不周期轮询 |
| 周日起算、ISO weekday、日期星期核对 | 完成；合成 fixture 与跨月/跨年用例 |
| 非连续节次、单双周源周次、同日多课/重叠 | 完成；解析保留 `sourcePeriods`/`weeks`，UI 拆段但不吞并 |
| 字段缺失/矛盾/未知协议 | 完成；缺失显示“未提供”，矛盾拒绝并保留旧缓存 |
| 完整成功空周与未查询/失败/离线区分 | 完成；JVM 与 Compose 测试 |
| 按 accountId + 学年/学期/教学周缓存 | 完成；账号不一致不展示 |
| 独立课表会话加密 | 完成；独立 Keystore alias、`noBackupFilesDir` |
| 缺会话/明确失效最多恢复一次 | 完成；错误密码不循环、验证码转正常网页登录 |
| 换号/删号/清缓存迟到响应 | 完成；账号/凭据/业务/选周代次复核 |
| 插件隔离时清理个人文件 | 完成；宿主静态清理路径 |
| Android 36 模拟器真实本人账号 CAS + 单周读取 | **实际通过**（2026-11-03，只读） |
| 模拟器真实页面、断网离线重开 | **实际通过**（未提交课程原文/截图） |
| 验证码 WebView 真实链路 | 代码与合成测试完成；真实验证码未出现，未单独做真机 WebView 验证 |
| 真机、release/R8、其他 ROM/WebView | 未执行 |
| 课程编辑、日历导出、提醒、课表组件 | 按范围未做 |

## 模块与边界

```text
:core:account-api
  SchoolAccountReferenceReader（任意合法已保存账号的 accountId + 凭据代次）
  SchoolAccountStudentIdentityReader（余额既有学号读取面，保持兼容）
        ↓
:core:account
  SchoolCredentialAccess（受控、opt-in、单次认证可清零的账密读取）
  SchoolAccountRepository（实现引用读取/凭据使用/失效协调）
        ↓
:core:upc-auth
  UpcTimetableAuthenticator（正常 CAS、严格主机/表单/票据/回调校验）
  TimetableAuthenticator（只返回 app.upc.edu.cn 业务 Cookie）
        ↓
:feature:timetable
  TimetableProtocol（严格解析）
  TimetableApi（固定主机/路径、只读 GET/POST）
  TimetableSessionStore（独立 alias 加密会话）
  TimetableCacheStore（账号归属成功周缓存）
  TimetableService（状态机、恢复一次、代次/并发门闩）
  TimetableScreen / TimetableLoginDialog（Compose + 学校网页登录回退）
  TimetablePlugin（更多 → 学习 入口 + 页面）
        ↓
:app
  AppGraph 装配、账号失效器、插件隔离清理、UI 图标/路由
```

`:feature:timetable` 只依赖 `:core:account-api`、`:core:upc-auth`、`:core:platform`、
`:core:plugin-api`、`:core:plugin-ui`、`:core:designsystem`，不依赖 `:core:account`。
`checkModuleBoundaries` 新增“feature 不能依赖 :core:account”检查；明文凭据只允许
`:core:upc-auth` 通过 `@RestrictedSchoolCredentialUse` 边界使用。`SchoolLoginCredential`
不放进 `:core:account-api`，普通页面/组件/Repository 不可读写密码或通用解密。

## 认证语义

- 只接受 `https://cas.upc.edu.cn/cas/login`，service 与 `from=wap&noAutoRedirect=1` 逐项核对；
  只把密码提交到核对后的官方表单 action，不自动跟随 OkHttp 重定向。
- 票据回调只接受 `https://app.upc.edu.cn/a_upc/api/cas/login`，校验 `ticket`、`redirect`、
  `from`、主机、端口、路径和单值；其他主机/路径立即拒绝，不发送密码或认证头。
- 登录页检测 `captchaParent`/`config.captcha`、错误提示与二次验证；验证码/二次验证直接进入
  `INTERACTION_REQUIRED`，不提交密码；网页登录回退打开官方 HTTPS 域名，完成后读取
  `app.upc.edu.cn` Cookie 并再次用 `get-index` 验证，绑定打开时账号/业务代次。
- 缺会话或接口明确返回 `e=10013` 时最多认证一次并重试一次；错误密码、网络、TLS、上游异常、
  账号变化分别固定映射；不循环试密码。
- 认证成功只代表课表业务会话；不把统一账号的“已保存，尚未验证”改成“全部系统已登录”。

## 协议与展示

- 固定入口：`GET /timetable/wap/default/get-index`；`POST /timetable/wap/default/get-datatmp`，
  `type=2`，表单 `year/term/week/type`。不提供整学期批量、写接口或轮询。
- 教学周以学校显式 `startday`/`weekdays`/`weekday` 为准，周日起算；对外 ISO weekday
  周一=1、周日=7，UI 仅按 ISO 排序为周一在前，不重算学校周次。`Asia/Shanghai` 仅用于“今日”。
- 严格拒绝：日期不连续/不匹配、课程周次不含请求周、节次越界/重复、`lessArr` 与
  `cell_start`/`order`/`totalLength` 矛盾、`classes` 非 7 天、未知字段结构、非 JSON、
  认证错误和超大响应。错误不覆盖成功缓存，也不显示成“本周无课程”。
- 非连续 `lessArr` 拆成连续片段并在详情保留完整原始节次；同日多课与重叠课程都保留；
  `week`/`weekType` 原样保留，不推断单双周。
- 教师、地点、时间、课程编号等缺失显示“未提供”；不编造钟点、校区、教室，不把节次映射成
  未经来源确认的作息。

## 存储、并发与清理

| 文件 | 内容 | 规则 |
| --- | --- | --- |
| `noBackupFilesDir/timetable/timetable-session-v1.enc` | 账号/凭据代次/业务代次/保存时间/课表业务 Cookie，AES-GCM 外层 | 独立 alias `upcpocket.timetable-session.v1`；账号或代次不匹配不可用；换号/删号/改密清会话 |
| `noBackupFilesDir/timetable/timetable-cache-v1.json` | accountId、学期列表、完整成功周、查询时间/完整性 | 仅账号一致时展示；失败/矛盾不覆盖；换号/删号清空；同号改密保留历史周 |

- 所有操作捕获 `(accountId, credentialGeneration, businessEpoch, selectionEpoch)`；网络返回、
  缓存写入与 UI 发布前复核。取消协程不是唯一保护。
- 账号失效器不反向读取账号状态，避免与账号锁形成环；`SESSIONS_ONLY` 仅清会话，
  `PERSONAL_DATA` 清会话与缓存；插件被隔离时宿主直接调用 `TimetableFiles` 清理。
- 清理失败返回固定失败码，账号层保持清理门闩；LOADING/清理/切换期间页面不显示旧课表、
  不发个人请求。同账号改密历史课表保留但旧会话不可复用。
- 选周/选学期先递增选择代次，旧周响应不能覆盖新周；换号/删号旧响应不能写回 UI 或缓存。
- 不修改 Room 五张旧课表表；不触碰专注数据库/主题/教室/寝室。

## 验证与来源

自动化与设备结果见[验收记录](验收记录.md)。固定参考来源：

- 仓库：[dlam12138/UPC-MCP](https://github.com/dlam12138/UPC-MCP)
- 提交：`948f5f21ad7a12fd94b53403a07ac867209f7700`
- 许可证：MIT，Copyright (c) 2026 3beuml
- 只读阅读：`src/upc_mcp/timetable_protocol.py`、`timetable_service.py`、`timetable_tools.py`、
  `timetable_auth.py`、`auto_auth/cas.py`、`auto_auth/providers/timetable.py` 及对应测试；
  合成 fixture 用于解析测试，来源只读、未重绑、未删除。
- 不嵌入 Python/MCP/DPAPI 桌面设施，不套用郑州大学接口。官方入口更新见
  [官方入口](../upc/官方入口.md)。

## 明确未完成

- 未执行 release/R8、真机、其他 ROM/WebView 或校园网特殊策略；未在设备上注入“清理中杀进程”、
  “删除 Keystore 密钥”或“磁盘写满”。这些由 JVM 失败/并发测试覆盖，不代表设备故障注入。
- 真实验证码/二次验证未实际出现；WebView 回退仅通过合成握手测试和代码审阅，未做真实验证码
  真机链路。WebView `CookieManager.getCookie()` 不提供完整 domain/path/Secure/expiry 属性，
  回退会话按 host-only `/`、Secure 保存；来源属性不能完整复现，验收记录列为限制。
- 未抓取整学期、未做课程编辑/日历导出/提醒/桌面组件；首页课程摘要与整体页面安排留 M5。
- 真实课表只读验证只覆盖本人账号、单次会话和单周；未验证长时间会话、校方限流、频繁切周、
  跨学期多周缓存上限等运行条件。
