# 更新与验证

## M5.1 · 图书馆统一入口与六入口审计（开发分支，未发布）

- 首页原“图书馆座位”改为统一“图书馆”入口，按唐岛湾/古镇口/求是分组展示六个平台；保留既有 `library.seat` 页面，新增 `library.home` 和 `library.nspace`，不改变三 tab 与设置入口。
- 新增独立 nspace 认证：libspace 的 nspace SSO 入口 → 正常 CAS → app token（query/fragment）→ `/spa/static/public/api/remoteCasLogin` → `userInfo` 本人核验；入口 302 到 CAS 已实测。nspace token 只存内存，使用独立 `library.nspace` 能力和 `/spa` Repository，绝不与 libseat seat token 混用。
- 新增 nspace 原生只读：馆区/空间类型/开放日期/房间分页，严格解析 `status/code/data`、区分 401 与空 `pageList`；每个房间提供官方 `#/detail?id=...&selectDate=...` 预约深链（只带房间/日期，不带 token/uid/enc）；新增原生“我的预约”列表与确认式取消（`GET cancelBook/{id}`）；新增单人预约表单（详情/用途/时段/必填/最终真实确认），对多人、选座、附件或未知字段明确拒绝。未点击真实最终提交；`stopBook`/团队/签到未实现。
- 新增 nspace 官方 WebView 验证码回退：学校 CAS 401 验证码页转 `INTERACTION_REQUIRED`；应用内加载固定学校入口，只捕获 `/nspace` app token，经 `remoteCasLogin`、本人核验和账号代次/epoch/revision 门闩发布；换号/退出后的迟到回调不发布。token 不持久化、不与 libseat 混用。
- 修复空间类型灰置：HAR 的 4 个空间类型 `status` 均为 `0`，旧解析把 `0` 当禁用；公开页面/用户手机可切换。现按 `status=0` 可用、`status=1` 停用处理，并新增协议测试。nspace 页面文案去掉“原生只读/不提交新预约”等与现有表单矛盾的实现术语；A123 等多人/选座房间不再显示会失败的个人表单入口，明确引导官方组队。
- Chaoxing：seat/seatengine 只保留 `fidEnc/mappId[/seatId]`；reservepc 恢复稳定租户入口 `reserveId/resvAppId/fidEnc/mappId`（古镇口 13492、求是 31841），不含 uid/enc/code/state/wfwEnc。2026-11-04 在已登录 Chaoxing 会话中核对：seat 东区 6 区域（48–66 座，08:00–21:30）、seatengine 求是 21 区域（16–240 座，08:00–22:00）、reservepc 古镇口 8 项/求是 23 项；仍是在系统浏览器打开官方页，不是原生查询。此前“落到 office 座位页”修正为租户参数不完整/不匹配，不是系统失效。
- 修复 CAS 挑战误判：CAS 以 401/403 返回登录/验证码页时，不再直接报 `ACCESS_DENIED`，而是解析页面并返回 `INTERACTION_REQUIRED` + 学校交互 URL；无表单才拒绝。模拟器真实凭据尝试到达验证码挑战后停止，不绕过、不重复失败密码。
- 独立 Chrome 使用修正后的文件解析（第一行首个冒号后账号、第二行首个冒号后密码；保留值内冒号、不交换）正常登录一次成功，读取到 1 馆区/4 类型/3 日期和 A123 详情/表单（11/05、11/06 有时段；11/04 当天无可用时段）。未执行真实预约/取消/签到，未添加成员/团队。
- 统一页标签区分“原生/原生只读/官方页”；reservepc 不再显示“查看缺口”；seat/seatengine/reservepc 都不是原生查询，不把入口页或 HTTP 200 当作业务成功。
- 账号门闩：nspace 登录与 libseat 一样绑定当前 accountId/凭据代次；换号/删号同时清理两面会话，迟到认证结果不发布；新增合成测试覆盖协议分页、401、本人不一致、代次变化和独立 token。
- 验证：`core:upc-auth` 28 tests、`feature:library` **102 tests**（新增 reservepc 稳定租户入口/一次性参数排除/主机白名单）、`app:testDebugUnitTest` 与 `assembleDebug` 通过；`feature:library:lintDebug` 无问题、`app:lintDebug` 0 errors / 2 既有 warnings。模拟器验收新版统一页（27/28 截图）、nspace 未登录态；独立 Chrome 完成真实 nspace 只读与四个 Chaoxing 官方页核对；未执行任何真实预约/取消/签到/团队。详见 [六入口记录](m5/图书馆六入口.md)。

## M5 · 首页/课表/专注与统一设置入口（开发分支，未发布）

- 顶级导航收束为 **首页 / 课表 / 专注**：课表从“更多 → 学习”提升为插件 tab（order 5），专注 tab 保留（order 10）；删除宿主“更多”页与旧学习统计入口。
- 首页改为日期问候、校园卡摘要和“常用服务”四宫格；账号、图书馆登录、桌面组件、官方网页、外观、关于统一收进首页右上角设置；校园网页同时保留首页快捷卡，学习统计同时保留专注顶栏/今日卡入口（同一路由，不是两套实现）。
- 新增原生设置页、桌面组件说明/可选系统 pin、学校官方网页外链目录；官方链接来自既有 `docs/upc/官方入口.md`，不预填账号、不代理登录。
- 视觉系统去掉半透明玻璃叠层/重阴影，统一 `surfaceContainerLow + 1dp outlineVariant + 20dp` 卡片、字号层级、20dp 页面边距和 filled/outlined 图标状态。
- 课表重做教学周头部、今日课程、7 天×14 节横向周网格、重叠分栏、当前日强调、在线/缓存/空周/未查询/失败标签和课程详情；缺失字段仍显示“未提供”，不推测量纲/钟点/校区。
- 校园卡卡片保留账号绑定、脱敏、历史值、失败、清除缓存和安全说明；安全细节改为可展开，不删除语义。账号页 `FLAG_SECURE` 与密码清空保留。
- 插件契约未新增字段；课表/专注改用既有 `PluginBottomNav`，`MORE_LEARNING` 保留枚举兼容但不再挂载。缺插件仍由宿主原子裁剪，无空 tab。
- 修复宿主底部导航与内层 Scaffold 重复消费底部 Insets；`adjustResize` 已加入 Manifest。
- 验收修正：初始专注截图此前在页面已滚动后截取，重拍 1.0×/1.3×/1.5× 初始进入；实测环顶、状态、开始按钮完整，不需裁掉文案。标签按钮从“图标 + ＋ 新建”改为单加号“图标 + 新建”。补测 360×640dp + 1.5× 可滚动。文档明确校园网页/统计存在快捷入口重复，并审计 `CampusScreen`（仅入口壳）与 `WeChatHandoff`（基线无调用点）删除不丢功能；稳定插件路由和组件身份不变。
- 验证：按项目基线 `testDebugUnitTest` **50 套件 / 357 tests，0 failures/errors/skipped**；JVM 16 tests 通过；`assembleDebug`、`lintDebug` 通过（0 errors / 2 warnings）；模拟器 Android 16 实测浅/深色、1.0×/1.3×/1.5× 字号和主要页面/返回。详见 [M5 记录](m5/README.md)。
- 限制：未重跑真实学校登录/只读查询，未做 release/R8、真机、其他 ROM 或第三方桌面 pin；截图中的课表为 Debug-only 明确标注的合成预览。

## M4 · 图书馆 libseat 统一认证（开发分支，未发布）

- 从 `:core:upc-auth` 课表认证中提取公共 `UpcCasLogin`：官方 CAS 表单、错误页、验证码/二次验证识别、service/callback 主机与路径、Cookie 作用域共用；课表 M3 认证回归保持通过。
- 新增 `UpcLibraryAuthenticator`：固定 libseat SSO 入口 → CAS service ticket → app token（query 或 `#/login?token=...` fragment）→ `/rest/ssoAuth` seat token → `/rest/v2/user` 本人校验；只接受官方 HTTPS 主机/路径，不跟随 OkHttp 自动重定向，密码只提交一次。
- `feature:library` 通过窄接口 `LibraryAccountAuthenticator` 接收核验后的 seat token/本人标识，不依赖 `:core:account`、不读取密码；公开 HMAC 签名由 `UpcLibrarySigning` 共用。新增 `SchoolAccountIdentityVerifier` 受控契约，只比较候选登录名，不暴露密码/通用解密。
- 手动 WebView 登录改由 Service 先兑换本人、再核验身份、最后统一提交；A 账号下登录 B 不会短暂发布或持久化。登录门新增“使用已保存学校账号登录”，验证码/二次验证时明确提示并保留学校官方 WebView 登录；没有保存账号时手动会话只保留内存。
- `SchoolSessionStore` 图书馆密文新增 accountId/凭据代次绑定；旧格式、跨账号、跨代次读取即删除。恢复时服务端返回的本人标识还要与副本一致。
- 自动认证/手动登录捕获登录上下文与会话修订；网络/身份核验在登录锁外，最后持久化、发布与修订递增在同一提交门闩内复核账号门闩、Repository 代次与当前账号引用。旧恢复/旧自动/旧手动失败只做条件清理，旧查询失效只能按当前 token 清理，迟到操作不能删除新登录。换号/删号/改密/清理失败沿用 M1 失效器并清理图书馆会话。
- 验证：**Android 50 套件/357 tests + JVM 16 tests**，均 0 failures/errors/skipped；`checkModuleBoundaries`、`assembleDebug`、`assembleDebugAndroidTest`、相关 lint 通过。`RealLibraryAuthTest` 在 `emulator-5554` 的历史现场（`c1299da`）曾以本人账号完成真实 CAS、只读 `filters`/`myReservations`；本次初审修正未重跑真实 CAS。详见 [M4 执行记录](m4/README.md) 与 [验收记录](m4/验收记录.md)。
- 限制：未做真机/release/R8、真实验证码/二次验证、长期 TTL；`nspace`/Chaoxing 四入口与所有座位写操作不在本轮范围。

## M3 · 真实课表认证、查询、展示与离线缓存（开发分支，未发布）

- 新增 `:core:upc-auth` 与 `:feature:timetable`：课表认证只由受控 CAS 适配使用统一账号凭据；页面/Repository 不接触密码或通用解密能力。新增 `SchoolAccountReferenceReader` 提供任意合法已保存账号的内部 accountId/代次，保留原学号读取面。
- “更多 → 课表”提供学期选择、教学周切换、周网格、页内今日课程、课程详情与主动刷新；入口只增加在“学习”分组，不改首页内容、底部导航或整体页面安排。
- 移植 UPC-MCP 固定提交的正常协议：`GET /timetable/wap/default/get-index`、`POST /timetable/wap/default/get-datatmp`（`type=2`）；严格校验学期、起止日期、周日起算、日期/ISO weekday、节次 1–14、课程周次、非连续节次与重叠课程，字段矛盾不静默纠偏。
- 认证：核对 CAS 登录页/表单 action/service/ticket 回调/主机与 HTTPS，密码只提交一次；区分错误密码、网络、验证码/二次验证、协议与账号变化；缺会话或明确 10013 最多恢复一次，不循环试密码；验证码走学校正常网页登录并绑定账号/业务代次。
- 会话：独立 Keystore alias `upcpocket.timetable-session.v1` 加密，绑定内部 accountId 与凭据代次；缓存只保存完整成功教学周，按 accountId + 学年/学期/教学周归属，未查询/失败/空周分开展示，离线只显示成功缓存。
- 并发与清理：所有认证、索引、周查询、会话保存与缓存发布捕获账号/凭据/业务/选周代次；换号/删号/清缓存/选周旧响应被拒绝，插件隔离时宿主仍清理个人文件；同号改密保留历史课表但清旧会话。
- 审阅修正：terms 为空时首次验证码/二次验证入口与正常网页登录可达；冷启动/刷新重取 index，更新 currentTerm/currentWeek，同时保留手选历史周；session/cache/UI 发布与账号失效共用 commitMutex，网络在锁外，文件/crypto 在 IO；账号切换立即撤下旧详情/旧登录弹窗。
- 验证：**353 tests，0 failures/errors/skipped**（含新增 83 项）；`checkModuleBoundaries`、`assembleDebug`、`assembleDebugAndroidTest`、全部相关 lint 通过。上述真实设备 CAS/单周读取/离线重开是 `e22e60d` 初次实现的历史记录，本次审阅修正未重跑线上认证。详见 [M3 执行记录](m3/README.md) 与 [验收记录](m3/验收记录.md)。
- 限制：release/R8、真机、其他 ROM/WebView、校园网特殊策略、设备级“清理中杀进程”未验证；WebView 回退读取的 Cookie 属性由系统接口简化，详见验收记录。

## M2 · 校园卡余额强关联（开发分支，未发布）

- 余额查询目标唯一来自当前统一账号的受控本人学号：只接受登录名整串 8–16 位数字，不截取、不猜测；账号未保存、恢复中、换号/删号清理中、密文不可用或学号无效时不联网。
- 首页取消独立可编辑学号，展示脱敏绑定、账号设置入口、查询/刷新和“仅清除余额缓存”；余额与过渡余额分别展示，失败保留同账号历史值和绝对时间并明确标注。
- 页面、2×2 RemoteViews 组件和 WorkManager Worker 共用 `CardBalanceService`；查询捕获 accountId、学号、凭据代次和业务代次，响应发布、落盘与组件刷新前再次校验，迟到的换号/改密/清缓存结果不能复活。
- 已绑定但无历史快照的组件也能直接刷新；换号/删号立即撤下旧值，不等新账号查询成功；同号改密保留历史余额，但旧请求被拒绝。
- 组件发布增加 ticket + 完整 revision（绑定/账号代次/业务代次/快照版本）门闩：Provider onUpdate/resize、requestCard、Worker finally 重绘统一走同一发布器，读取可挂起但旧读取不能在换号/删除/清缓存的新空态之后写回；`cached()`/`cachedBlocking()` 读后复核，迟到读取不回填旧内存。
- 余额缓存升级为带 accountId/学号/代次的 v2；旧 v1 未绑定不展示，仅同号且用户主动绑定时迁移并保留原时间，异号/损坏/未知归属隔离。FIRST_SAVE 同样核对旧快照。
- 新增迁移 marker 与清理门闩：迁移幂等、可中断恢复；清理失败时旧值立即隐藏，重启后继续隐藏并重试，不自动查询。
- `cardStudentId` 旧草稿只作为账号设置脱敏提示，不再作为数据源；账号绑定后删除，绝不自动建号。
- 保留既有 Provider/Worker/Service/PendingIntent FQCN、刷新 action、唯一任务名和组件实例；旧任务执行时重新读取当前绑定，任务参数不含学号/密码。
- 账号失效器清理余额时不反向读取账号读取面，避免账号锁与卡片锁顺序死锁；“仅清除余额缓存”不删除学校账号、图书馆会话、专注、电量或主题数据。

验证结果与限制见 [M2 执行记录](m2/README.md) 和 [M2 验收记录](m2/验收记录.md)。

## M1 · 统一学校账号基础（开发分支，未发布）

- 新增 `:core:account-api` 小型类型化账号读取/结果/失效契约，以及 `:core:account` Android 实现和设置页。
- “更多 → 学校账号”支持主动保存一个当前学校账号、更新密码和删除；默认不保存，保存成功固定显示“已保存，尚未验证”。
- 凭据使用 Android Keystore 独立 alias `upcpocket.school-account.v1` 的 AES-256-GCM、随机 IV 和版本化密文，写入 `noBackupFilesDir/school-account/`，不降级明文。
- 保存采用 pending + journal + state + 正式密文的可恢复提交协议；加密、写盘或清理失败保留旧可用版本或停留在固定清理状态。
- 同账号更新递增凭据代次并失效旧业务会话；换号/删号先落盘切换/清理状态，再清理图书馆会话、学校 Cookie 与校园卡个人缓存，可跨进程恢复。
- 删除不触碰专注历史、主题、公开教室与独立寝室配置；旧“记住学校登录”明确只是图书馆 token 偏好，不是保存密码的同意。
- 账号设置页使用 `FLAG_SECURE`、离页/保存清空密码输入、不进入 `rememberSaveable`/SavedStateHandle；页面对未接入业务给出真实说明。
- 审阅修正：WebView Cookie 按根/`/cas`/`/libseat/`/`/remote/...` 与 `.upc.edu.cn` 域作用域清理并确认；图书馆登录上下文绑定打开时账号代次，清理中/失败重试时 Repository 与 UI 同步阻断；journal 增加已提交 tombstone，收尾删文件失败不重放换号/删号失效，且显式删除 base/`.bak`/`.new`。
- M2 余额绑定、M3 课表认证与 M4 图书馆认证见上方各节；M1 本阶段保存时尚未发送账密，之后只由课表/图书馆认证适配在需要时使用。仍不把本机保存状态标成学校已认证或全部业务已连接。

验证结果与限制见 [M1 执行记录](m1/README.md)。

## v0.2.7 · Lightly 封板

- 校园卡余额、唐岛湾剩余电量提供默认 **2×2** 桌面小组件。
- 半透明渐变、细描边与紧凑布局，保留 48dp 刷新按钮、账号/房间、主数值与绝对查询时间。
- 修复深浅色切换后的次要文字颜色；大字号优先保留寝室号与主数值。
- 组件仅点击刷新，无周期轮询；失败保留最后成功值。页面与组件共用持久快照，清除后不接受迟到请求复活旧记录。
- 保留原生校园卡余额、唐岛湾电量、两校区空闲教室、图书馆座位及专注功能；古镇口继续使用学校页面。
- 移除课表、校园卡流水、数字石大、图书馆主页等轻量版范围外入口。
- 整理公开文档、演示图与发布目录，保留上游版权、原计划和开发技能来源。

## 已完成验证

此次发布沿用封板源码已完成的检查，没有因纯文档整理重复生成安装包。

| 检查 | 结果 |
| --- | --- |
| assembleDebug | 通过 |
| testDebugUnitTest | 20 类、163 项，0 失败、0 错误 |
| lintDebug | 0 errors、7 warnings；其中 3 条为紧凑次要文字的 SmallSp 提示 |
| Android 36 / Pixel Launcher | 覆盖安装；实际新增两个 2×2 实例，约 172×224dp |
| 显示与失败状态 | 浅/深色，1.0×/1.3×/2.0× 字号；离线刷新保留旧值和成功时间 |

组件测试使用虚构数据，没有为此向学校提交虚构学号或寝室。历史手机验收确认过图书馆功能；本次没有重新执行本人账号预约、校园卡或电量查询。

## 已知限制

- 仅实测 Pixel Launcher；其他桌面的透明度、圆角和网格尺寸可能不同。
- 旧 2×3 实例不会自动变成 2×2，需重新添加。大字号或极窄尺寸可能省略楼号等次要文字，可拉大组件。
- 半透明磨砂外观不等于实时模糊壁纸。
- 古镇口未实现原生查询，学校证书、网络与页面变化可能影响访问。
- Room 中保留旧课表表结构仅用于覆盖升级兼容；M3 课表使用独立 JSON 缓存，不读写这五张旧表，也不把它们当作 UPC 当前账号课表。
- APK 内部版本 `0.2.7-lightly-preview`（19），沿用此前调试证书；本次以 GitHub Release 形式分发同一已验证包。
