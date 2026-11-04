# M5 · 页面、导航与视觉第一版

状态：**本地可评审第一版**（开发分支，未合并 main、未发布、未推送）。M5 不改变
M0—M4 的认证、缓存、清理与接口语义，只重新组织入口、页面层级与原生 Compose 视觉。
本轮补充“图书馆”统一入口、nspace 浏览/预约取消、个人预约表单、WebView 验证码回退和
真实凭据检查；用户手机已验收 nspace 会话与 A123 表单，agent 新设备仍受 CAS 凭据/验证码
限制，详见 [图书馆六入口](图书馆六入口.md)。

- 基线：`93967b1`（`m4-library-unified-auth`，M4 修正）
- 分支/工作树：`lody/m5-campus-home`；本会话工作树（独立于其他会话）
- 设计技能：`superdesign` 0.4.0 与本仓库 Android skills（`adaptive`、`edge-to-edge`）；
  外部设计服务没有承载任何学校账号、课程或 HAR。CLI 可用，但第一版使用本机设计上下文
  （`.superdesign/`，按 `.gitignore` 保留本机）和 Compose 原生实现，未把 Web/Tailwind 方案硬套到 Android。
- 范围：只新增/重排 UI 与必要导航测试；未引入 Navigation 3、AGP 9、Glance 或数据层迁移。

## 1. 这一版做了什么

### 1.1 信息结构：三个顶级目的地

底部导航从“首页 / 专注 / 更多”改为：

| Tab | 内容 | 来源 |
| --- | --- | --- |
| 首页 | 日期问候、校园卡摘要、四个常用服务 | 宿主 + 已激活插件 |
| 课表 | 学期/教学周、周网格、今日课程、课程详情 | `:feature:timetable` |
| 专注 | 计时、今日记录、学习统计 | `:feature:focus` |

设置不再作为 tab，统一从首页右上角进入。首页/课表/专注之外没有第二套“更多目录”。
**设置入口全局唯一，但不宣称每项功能只有一个快捷路径**：校园官方网页在首页快捷卡和设置目录
各有一个入口；学习统计在专注顶栏和今日卡片各有一个入口。两处都打开同一页面/路由，不是两套实现。

设置页固定分组：

```
学校账号
校园 → 图书馆登录 / 桌面组件 / 校园官方网页
个性化 → 配色与深色模式 / 关于
```

### 1.2 旧 → 新入口映射

| 旧路径（M4） | 新路径（M5） |
| --- | --- |
| 首页 | 首页 |
| 底部“专注” | 底部“专注” |
| 底部“更多” | 取消；设置从首页右上角进入 |
| 更多 → 学习 → 课表 | 底部“课表” |
| 更多 → 学习 → 学习统计 | 专注 → 顶部“统计” / 今日卡片“统计” |
| 更多 → 学校账号 | 首页右上角 → 设置 → 学校账号 |
| 更多 → 学校登录（图书馆） | 首页右上角 → 设置 → 图书馆登录 |
| 更多 → 外观 | 首页右上角 → 设置 → 配色与深色模式 |
| 更多 → 关于 | 首页右上角 → 设置 → 关于 UPC Pocket |
| 首页校园卡区 | 首页“校园卡”区（余额、脱敏绑定、账号、清除缓存、数据说明） |
| 首页电费 / 空教室 / 图书馆座位 | 首页“常用服务”的电费 / 空教室 / **图书馆**（图书馆内再分六入口） |
| 桌面组件由长按桌面添加 | 仍由系统桌面添加；新增设置 → 桌面组件说明与可选“添加到桌面” |
| 古镇口电费学校页面 | 电费页保留；设置 → 校园官方网页也提供入口 |
| 校园官方网页 | 首页“校园网页”快捷卡 / 设置 → 校园官方网页（同一页面） |
| 桌面组件点击 | 校园卡回首页；电量直达唐岛湾查询页，稳定路由不变 |

### 1.3 功能完整性

- 学校账号：保存/更新/删除、脱敏状态、`FLAG_SECURE`、离页清空密码输入、删除清理边界保留。
- 校园卡：查询目标仍只来自当前统一账号；累计余额/过渡余额、历史缓存标签、失败保留、
  同号改密标记、清除缓存与缓存清理失败状态保留。详细安全说明折叠，但不删除。
- 电费：唐岛湾原生查询，古镇口官方网页；两校区说明保留。
- 空教室：两校区、日期、楼栋、节次筛选和结果页不变。
- 图书馆：首页统一入口为“图书馆”（libseat 座位 + nspace 东馆研讨间 + 古镇口/求是四个
  Chaoxing 入口）。libseat 保留统一认证、座位查询/预约/取消、记住登录与会话清理；nspace
  新增独立 CAS/token、馆区/类型/日期/房间分页、本人预约列表与取消、个人预约
  表单（用途/时段/必填/最终确认）和官方 WebView 验证码回退；每个房间仍保留官方
  `#/detail?id=...&selectDate=...` 深链。真实 CAS 自动登录仍停在模拟器验证码挑战；用户手机
  已验收 nspace 会话、1 馆区/4 类型/3 日期切换和 A123 详情/表单可达，但未提交真实预约。
  A123 需多人组队，应用只引导官方页面，不伪造团队成员。空间类型 `status=0` 曾因解析
  反向被灰置；现按 `0` 可用/`1` 停用处理，四类型可切换。
  Chaoxing seat/seatengine/reservepc 使用从 HAR 提取的稳定入口（不含 uid/enc/code/state），
  在系统浏览器官方登录；仍未原生查询/预约。六入口矩阵见
  [图书馆六入口](图书馆六入口.md)。
- 课表：正常 CAS、单周按需查询、离线缓存、验证码网页登录回退、账号归属与迟到响应隔离不变。
- 专注：计时、今日记录、标签、统计、补录与前台服务不变；统计不再从“更多”重复出现。
- 官方网页：新增宿主只读目录，链接来自 `docs/upc/官方入口.md`；不预填账号、不代理登录。
  首页快捷卡与设置目录打开同一个 `OfficialWebScreen` 路由，不重复维护链接。
- 组件：两个 2×2 RemoteViews 组件、请求路径、身份和点击身份不变；新增设置页说明/可选 pin。

### 1.4 视觉方向

- 暖中性页面底 + 单一 Material 3 强调色；删除半透明玻璃叠层、渐变描边和重阴影。
- 卡片：近实底 `surfaceContainerLow`、20dp 圆角、1dp `outlineVariant` 细描边、零高度。
- 字体：显式 `Typography` 层级（28sp 页面标题、22sp 区块标题、16sp 项目标题、
  14sp 正文、12sp 元数据），字重跳级，不引入外部字体。
- 间距：页面 20dp 水平边距、卡片 16dp 内边距、条目 10–12dp。
- 图标：`UiIcon` 枚举由宿主统一映射；底部选中/未选中遵循 filled/outlined。
- 状态：文字永远存在，颜色只作辅助；离线、失败、空周、未查询、未登录分别有不同文案。

### 1.5 课表重点优化

- 教学周头部：学期菜单、上一周/下一周、日期范围、紧凑周次胶囊；不按开学日推算“本周”。
- 今日课程置于周网格之前；只使用服务已取得的状态，不在首页额外触发课表查询。
- 周网格保留 7 天、14 节，横向滚动查看周末；课程块支持重叠分栏、非连续节次和缺字段。
- 今日列有底色与日期强调；课程块字号/行高提升，长名称最多两行，地点在高度允许时显示。
- 周状态标签区分“在线成功 / 离线缓存 / 未更新”；空周、未查询、失败不会互相伪装。
- 课程详情用 AlertDialog 展示日期、原始节次、周次、教师、地点、时间、类型、编号；
  缺失显示“未提供”，并说明源节次拆段与不推测钟点/校区。

## 2. 插件契约与装配

复用现有 `PluginUiContract`，没有为 M5 新增字段：

- `TimetablePlugin`：新增 `PluginBottomNav(order = 5)`；删除旧的 `MORE_LEARNING` 入口。
- `FocusPlugin`：保留 `PluginBottomNav(order = 10)`；删除旧的“学习统计”`MORE_LEARNING` 入口。
- 图书馆：保留 `MORE_SCHOOL` 登录区；新增 `library.home` 统一入口和 `library.nspace`
  只读页。`LibraryCapabilities.seat` 与 `LibraryCapabilities.nspace` 是两个独立能力/会话，
  nspace token 不持久化、不与 seat token 混用。
- 空教室/电费/图书馆：继续贡献 `HOME_SERVICES`，首页不硬编码这些功能实现。
- `MORE_LEARNING` 枚举保留以维持源级契约兼容，但宿主不再挂载“更多”页；选择它不会产生
  可点入口。`PluginBottomNav` 文档由“宿主保底首页/更多”改为“宿主固定首页，插件 tab 排在其后”。

缺插件状态：

- 课表/专注 tab 来自插件；插件未激活时不会出现空 tab，宿主只保留首页。
- 首页服务与卡片区来自 `PluginUiSnapshot`；适配器/插件故障由现有原子裁剪逻辑处理。
- 设置里的学校登录区只在图书馆插件贡献存在时显示。
- 旧 `MORE_LEARNING` 贡献被静默保留但不可达；现有自动化确认没有把课表/统计再放进“学习”列表。

### 2.1 路由与删除文件审计

- 稳定插件页路由：`power.electricity`、`power.tangdaowan`、`classroom.free`、
  `library.home`、`library.seat`、`library.login`、`library.nspace`、
  `library.nspace.login`、`library.nspace.reservations`、`focus`、`focus.stats`、
  `timetable.week` 均注册。`library.seat` 没有被删除，只是从首页入口改为统一图书馆内的
  libseat 子页；`HostPluginUiTest` 额外断言所有首页入口/底部 tab 的目标页存在。
- `CampusScreen` 是旧“更多”宿主页，只承载入口列表，不包含任一官方 URL 或浏览器跳转；
  其账号/课表/统计/图书馆登录/外观/关于能力已分别迁移到三 tab 与设置。删除的是入口页壳，
  不是功能实现。
- `WeChatHandoff` 在删除前的基线中没有任何调用点（`git grep` 只命中文件自身）；它既没有
  路由，也没有 UI 按钮。删除的是不可达工具代码；没有移除任何可达的微信登录、分享或官方入口能力。
- 既有学校外链仍保留在原功能内（如古镇口电量页、图书馆学校登录）；新增
  `OfficialWebScreen` 只是把已核对的公开页面集中列出。没有把外链说成原生查询，也没有
  新增未实现的校园能力。
- 桌面组件身份未变：校园卡仍打开 `HOME`，唐岛湾电量仍打开稳定路由
  `power.tangdaowan`；设置页只是新增组件说明与系统 pin 入口。

## 3. 截图

截图均来自本会话模拟器 `emulator-5554`（Android 16 / API 36，1080×2400，Pixel 风格），
无登录账号、真实余额、真实课程或真实学生数据。

| 文件 | 内容 |
| --- | --- |
| [01-home-light.png](screenshots/01-home-light.png) | 首页浅色；未登录、无历史余额 |
| [02-settings-light.png](screenshots/02-settings-light.png) | 唯一设置入口；账号、图书馆、组件、官方网页、外观、关于（校园网页另有首页快捷卡） |
| [03-timetable-first-run-light.png](screenshots/03-timetable-first-run-light.png) | 课表未登录第一屏；说明不会在首页后台认证 |
| [04-focus-light.png](screenshots/04-focus-light.png) | 专注初始进入浅色；完整计时环、状态、开始按钮，无专注记录 |
| [05-appearance-light.png](screenshots/05-appearance-light.png) | 配色与深浅色设置 |
| [06-home-dark.png](screenshots/06-home-dark.png) | 首页深色 |
| [07-timetable-preview.png](screenshots/07-timetable-preview.png) | **Debug-only 合成预览**：教学周头部 + 今日课程 |
| [08-timetable-preview-grid.png](screenshots/08-timetable-preview-grid.png) | **Debug-only 合成预览**：周网格、重叠/多课、来源状态 |
| [09-timetable-preview-detail.png](screenshots/09-timetable-preview-detail.png) | **Debug-only 合成预览**：课程详情与缺失字段 |
| [11-widgets-light.png](screenshots/11-widgets-light.png) | 桌面组件设置；2×2 边界、pin 入口 |
| [12-official-web-light.png](screenshots/12-official-web-light.png) | 学校官方网页目录 |
| [14-home-large-font.png](screenshots/14-home-large-font.png) | 首页 1.3× 字号 |
| [19-timetable-font-150.png](screenshots/19-timetable-font-150.png) | **Debug-only 合成预览**：课表 1.5× 字号 |
| [20-focus-font-130.png](screenshots/20-focus-font-130.png) | 专注初始进入 1.3×；完整计时环、状态、开始按钮 |
| [21-focus-font-150.png](screenshots/21-focus-font-150.png) | 专注初始进入 1.5×；完整计时环、状态、开始按钮 |
| [22-home-font-150.png](screenshots/22-home-font-150.png) | 首页 1.5×；标题/服务卡文字完整，可滚动到两组服务与页脚 |
| [23-library-hub.png](screenshots/23-library-hub.png) | 图书馆统一入口；六入口分组、学校数据/官方登录标注；reservepc 已使用稳定租户入口 |
| [24-library-nspace.png](screenshots/24-library-nspace.png) | 东馆研讨间 nspace 未登录态；说明个人房间可填预约信息，多人/选座/附件走官方页面 |
| [25-library-hub-font-150.png](screenshots/25-library-hub-font-150.png) | 图书馆统一入口 1.5× 顶部；长文案不截断 |
| [26-library-hub-font-150-bottom.png](screenshots/26-library-hub-font-150-bottom.png) | 1.5× 滚动到底部；六入口/官方登录说明/学校官网均可到达 |

合成预览由 `feature/timetable/src/debug/.../TimetablePreviewActivity.kt` 明确标注
“本地预览 · 合成内容”，只读取内存中的 `TimetableUiState`，不创建账号/会话/缓存、不发网络请求，
不会进入 release APK。它用于在无真实账号数据时评审周网格与详情，而不是冒充真实课表。

学校账号页因 `FLAG_SECURE` 不提供截图；已通过 `dumpsys window` 确认窗口 flag 含 `SECURE`，
并只核对空表单 UI 层级。没有截取任何已填账号或密码。

验收修正：此前交付的 `04-focus-light.png` 是验收脚本在专注页已向上滑动后截取的，环顶被
固定顶栏遮住；它不是初始进入状态。本轮先强制停止并从首页进入专注，再重新截图，未通过删除
状态文案或裁切视图规避问题。初始进入实测：顶栏副标题底边 y=273/286/293（1.0/1.3/1.5），
计时环顶 y=338，开始按钮底边 y=1083/1091/1096，环、状态和主按钮均完整。

## 4. 验证
- 设备：`emulator-5554` / `upcpocket_api36` / Android 16（API 36）；实际安装 Debug APK。
- 页面：首页、设置、账号空态、课表未登录、专注、桌面组件、官方网页、外观；底部三 tab 切换。
- 系统 Insets：宿主底部 `NavigationBar` + 各 tab 内 `Scaffold` 只消费顶部/横向 Insets，
  修正了内层重复底部 inset；手势导航栏/状态栏无遮挡。
- 深浅色：首页浅色、深色；外观页切换“始终深色/始终浅色”后重启生效。
- 字号：1.0×、1.3×、1.5× 实测；首页、设置、课表、专注未出现按钮/文字重叠。专注 1.0/1.3/1.5
  初始进入的环顶、状态与开始按钮均完整；1.5× 下 360×640dp 视口可滚动到标签/今日记录。
- 小屏：`wm size 720x1280`、`wm density 320`（360×640dp）+ 1.5× 实测，初始显示计时环与开始按钮，
  上滑后显示标签和今日区域；已恢复 1080×2400/420dpi。
- 文案：标签按钮原为图标加号 + “＋ 新建”双加号，现为 Add 图标 + “新建”单加号。
- 返回：设置 → 账号/外观/组件/官方/关于均能返回；官方网页实际打开 Chrome 后返回应用。
- 边界：未登录、无数据、合成周网格、空“今天”、离线/失败状态代码路径；没有触发真实学校登录、
  预约、取消、签到、充值或电量查询。
- 账号：`FLAG_SECURE` 已确认，但未创建账号、未走真实登录。
- 图书馆统一页：首页“图书馆”进入；唐岛湾两卡、古镇口两卡、求是两卡均可达；nspace 未登录态
  与官方页按钮可达；统一页 1.5× 顶部/底部滚动截图已保存。Chaoxing 官方按钮在系统浏览器打开，
  没有把浏览器页面计入原生成功。
- 图书馆写操作：未触发任何真实 libseat/nspace 预约、取消、签到；用户手机只验收了 nspace
  浏览/A123 表单可达，没有点击最终预约；“我的预约/取消”仍无真实记录验收，界面取消有二次
  确认，模拟器未点击确认。原因与最小协助见
  [图书馆六入口](图书馆六入口.md)。

## 5. 自动化结果

| 检查 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 通过（图书馆改动后复跑） |
| `:core:upc-auth:testDebugUnitTest` | **28 tests，0 failures/errors/skipped**（CAS 验证码挑战、Web token 兑换/身份错配/非法 token） |
| `:feature:library:testDebugUnitTest` | **101 tests，0 failures/errors/skipped**（nspace 协议/分页/账号隔离/Web 回调/取消/表单/稳定入口） |
| `:app:testDebugUnitTest` | **32 tests，0 failures/errors/skipped**；首页入口/页面集合断言包含 `library.home` 与 `library.nspace.login` |
| Android Debug 单元/Compose 测试 | M5 首轮全量 **50 套件 / 357 tests，0 failures / 0 errors / 0 skipped**；本轮按影响范围复跑 auth/library/app 用例 |
| JVM 测试（`:core:plugin-runtime:test`） | **16 tests，0 failures / 0 errors / 0 skipped**（M5 首轮；本轮未改该模块，未无故重跑） |
| `:feature:library:lintDebug` / `:app:lintDebug` | 通过；app **0 errors / 2 warnings**（既有 `RedundantLabel`、`ObsoleteSdkInt`），library 无问题 |
| 模块边界 | `checkModuleBoundaries` 随构建通过 |
| 基线 | M4 357 Android + 16 JVM 全部保留；库页面/首页入口断言已随统一入口更新 |

`./gradlew test` 还会触发 release unit test；其中 Compose UI 测试依赖 debug-only
`ui-test-manifest`，release 变体无法启动 instrumentation，属于现有测试基础设施限制，
不是本轮代码失败。本轮以项目既有基线 `testDebugUnitTest` + JVM 16 为准，未把 release
测试结果冒充为通过。

### 5.1 本轮 APK

- 评审副本：`release/UPC-Pocket-M5-library-preview-debug.apk`
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：`66,399,393` bytes
- SHA-256：`25719C20E891988A4D011167E25CD6A4CC3B22B0BB3CC94A91F3DEED77A28E7D`
- 该 APK 为 debug 构建、含 debug-only 合成课表预览 Activity；release 构建不会合并它。未上传、未发布。
- 上一版 M5 评审 APK `release/UPC-Pocket-M5-preview-debug.apk` 保留为对照；本轮图书馆改动在该新副本中。

## 6. 未验证 / 限制

- 模拟器上的真实凭据尝试到达 CAS 后被验证码挑战拦下；后续独立 Chrome 正常打开 CAS
  表单（无验证码），但本地凭据两行均被 CAS 返回“用户名或密码错误”，随即停止、未继续
  重试。两者说明：验证码是模拟器路径的阻碍，而本机凭据文件与本次 CAS 登录也不匹配；
  不能据此反推用户手机会话无效。未绕过、未重复失败密码、未把挑战或页面可达当认证成功。
- **用户手机真机验收（用户提供，不是 agent 新设备）**：nspace 会话已建立，1 馆区、4 空间
  类型、3 日期可显示切换，A123 详情/表单已加载（3–6 人、08:00–21:50、60–120 分钟、使用
  类型、手机号/主题/大纲）。没有点击最终预约；这不等于真实预约成功，也不等于 agent 新设备
  已完成本人读取。
- 未提交任何真实预约、取消、签到、团队或写请求；未打开真实座位图。nspace 个人预约表单
  已接入并有合成 JSON/规则测试，但未点击最终“确认提交”；A123 需多人组队，应用明确引导
  官方页面，没有伪造参与人/团队。
- 未在真机/其他 WebView 完成 nspace CAS 验证码后的回跳、Chaoxing/reservepc 的登录后页面；
  Chaoxing 稳定入口只验证到官方登录/CAS。
- 截图中的合成课表只覆盖视觉状态，不等于真实协议数据；真实数据仍受 M3 的账号、会话、缓存与
  服务端校验约束。
- 1.5× 下教学周头部计数会自然换行；布局可滚动、不重叠，但还未针对 2.0× 单独适配。
- 未重排空教室、图书馆、电费内部页面；本轮只统一外层视觉/入口。它们沿用 M0—M4 的页面与交互，
  因此仍可能保留旧 spacing/文案密度，作为下一轮迭代项。
- 图书馆统一页的 nspace 认证/表单虽然已实现，用户手机也已验收浏览/表单可达，但 agent
  新设备尚未完成端到端；Chaoxing 四入口使用稳定入口后仍是官方浏览器回退，不是原生查询。
  六入口矩阵和具体阻塞/最小协助见 [图书馆六入口](图书馆六入口.md)，不能据此声称六个平台
  都已原生接入。
- 未执行 release/R8、真机、其他 ROM/WebView；桌面组件仅验证设置页与既有测试。
- 本轮没有执行任何真实预约/取消/签到；nspace 取消和预约提交仅在代码/合成测试中验证。
- 这不代表最终设计定稿；需要用户先看实际第一版，再决定是否继续细化。
