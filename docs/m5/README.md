# M5 · 页面、导航与视觉第一版

状态：**本地可评审第一版**（开发分支，未合并 main、未发布、未推送）。M5 不改变
M0—M4 的认证、缓存、清理与接口语义，只重新组织入口、页面层级与原生 Compose 视觉。
**2026-11-04 用户决定撤下全部研讨间/空间预约产品功能**：唐岛湾东馆 nspace、古镇口东区
reservepc、求是 reservepc 的页面、路由、协议、Service、表单、队伍/租户桥接和相关装配
均已删除；图书馆统一页只保留三个座位入口（唐岛湾 libseat、古镇口 Chaoxing seat、求是
Chaoxing seatengine）。研究记录保留为归档，不代表现行产品能力；详见
[图书馆六入口（历史研究）](图书馆六入口.md)。

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
| 首页电费 / 空教室 / 图书馆座位 | 首页“常用服务”的电费 / 空教室 / **图书馆**（图书馆内只分三个座位入口） |
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
- 图书馆：首页统一入口为“图书馆”，只展示三个座位入口：
  1. 唐岛湾 libseat：统一认证、座位查询/预约/取消、记住登录与会话清理；
  2. 古镇口 Chaoxing seat：原生只读区域/座位图/已用座位/30 分钟时段；
  3. 求是 Chaoxing seatengine：同协议，保留 `seatId`/参数差异，原生只读。
  Chaoxing 座位首次需在应用内受限官方 WebView 登录；真实预约仍走学校页面。旧研讨间
  路由只落到图书馆首页，不保留房间/预约接口。历史研究见
  [图书馆六入口（归档）](图书馆六入口.md)。
- 座位页重做（2026-11-04）：区域页把馆区/日期/楼层放在顶部紧凑筛选区，区域行显示
  名称、楼层、总数/余座和进度，动作按钮按余座显示“选座/查看”；支持横向/纵向滚动的
  真实 row/col 座位图、文字+颜色的状态图例、选中座位和时段底栏；选时段改为底部面板，
  不再用拥挤弹窗。返回区域页保留筛选、日期、楼层和滚动位置；“我的预约”作为同一入口的
  第二个 tab，取消仍需确认。
  超星区域页使用校区/日期条和开放时间/座位数/余座/进度；座位详情使用学校 x/y 真实的
  楼层坐标图（可横向/纵向滑动）、编号、时段底部面板和与选中状态联动的“官方预约”动作；
  只读不伪装原生提交。无数据、失败、无余座和当前不在开放时段分别显示真实状态。
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
- 图书馆：保留 `MORE_SCHOOL` 登录区；`library.home` 只展示三个座位入口。
  `LibraryCapabilities.seat` 与 `LibraryCapabilities.chaoxing` 是两个独立能力/会话；
  nspace 与 reservepc 能力/页面/协议已删除。
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
  `library.home`、`library.seat`、`library.login`、`library.chaoxing.seat`、
  `library.chaoxing.seatengine`、`library.chaoxing.login`、`focus`、`focus.stats`、
  `timetable.week` 均注册。`library.seat` 没有被删除，只是从首页入口改为统一图书馆内的
  libseat 子页；`HostPluginUiTest` 额外断言所有首页入口/底部 tab 的目标页存在。
- 旧研讨间路由 `library.nspace*`、`library.chaoxing.reserve*` 仅注册为**兼容落点**，
  页面直接显示图书馆首页；不注册房间/预约页面、不提供 Service/Repository/深链，
  避免旧返回栈或深链崩溃，同时不重新暴露研讨间能力。
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
| [23-library-hub.png](screenshots/23-library-hub.png) | **历史研究截图**：六入口分组；已不代表现行产品 |
| [24-library-nspace.png](screenshots/24-library-nspace.png) | **历史研究截图**：东馆研讨间 nspace；功能已撤下 |
| [25-library-hub-font-150.png](screenshots/25-library-hub-font-150.png) | **历史研究截图**：六入口统一页 1.5× |
| [26-library-hub-font-150-bottom.png](screenshots/26-library-hub-font-150-bottom.png) | **历史研究截图**：六入口统一页底部 |
| [27-library-hub-chaoxing.png](screenshots/27-library-hub-chaoxing.png) | **历史研究截图**：Chaoxing 六入口页 |
| [28-library-hub-chaoxing-bottom.png](screenshots/28-library-hub-chaoxing-bottom.png) | **历史研究截图**：reservepc 两租户/seatengine 底部 |
| [29-chaoxing-seatengine-native.png](screenshots/29-chaoxing-seatengine-native.png) | **原生只读**：求是 21 个区域、08:00–22:00、真实已用人数 |
| [30-chaoxing-seat-map-native.png](screenshots/30-chaoxing-seat-map-native.png) | **原生只读**：座位图、30 分钟时段、已用座位图例 |
| [31-chaoxing-reserve-context-blocked.png](screenshots/31-chaoxing-reserve-context-blocked.png) | **历史研究截图**：reservepc 租户上下文错误；功能已撤下 |
| [32-library-hub-chaoxing-native.png](screenshots/32-library-hub-chaoxing-native.png) | **历史研究截图**：六入口“原生只读”状态 |
| [33-library-hub-three-seats.png](screenshots/33-library-hub-three-seats.png) | **现行**：图书馆只保留三个座位入口 |
| [34-library-seat-chaoxing.png](screenshots/34-library-seat-chaoxing.png) | **现行**：Chaoxing 座位未登录/区域入口 |
| [35-library-seat-area.png](screenshots/35-library-seat-area.png) | **Debug 合成预览**：libseat 紧凑馆区/日期/楼层与区域余座 |
| [36-library-seat-map.png](screenshots/36-library-seat-map.png) | **Debug 合成预览**：libseat 全页座位图、文字图例、选中/底栏 |
| [37-library-seat-time.png](screenshots/37-library-seat-time.png) | **Debug 合成预览**：libseat 时段选择与确认动作 |
| [38-chaoxing-seat-area.png](screenshots/38-chaoxing-seat-area.png) | **Debug 合成预览**：Chaoxing 区域/开放时间/座位/余座 |
| [39-chaoxing-seat-map.png](screenshots/39-chaoxing-seat-map.png) | **Debug 合成预览**：Chaoxing 真实坐标座位图、编号、时段 |
| [40-library-seat-dark.png](screenshots/40-library-seat-dark.png) | **Debug 合成预览**：libseat 区域页深色 |
| [41-library-seat-large-font.png](screenshots/41-library-seat-large-font.png) | **Debug 合成预览**：libseat 区域页 1.5× |
| [42-library-seat-detail-large-font.png](screenshots/42-library-seat-detail-large-font.png) | **Debug 合成预览**：libseat 座位页 1.5× |
| [43-library-seat-detail-dark.png](screenshots/43-library-seat-detail-dark.png) | **Debug 合成预览**：libseat 座位页深色 |

座位合成预览由 `feature/library/src/debug/.../LibrarySeatPreviewActivity.kt` 提供，页面顶部
始终显示“合成预览 · 仅视觉，不连接学校、无账号/真实数据”；它只使用内存假数据，不创建账号、
会话、缓存或网络请求，也不在 release 变体存在。真实 libseat/Chaoxing 区域仍由学校接口返回。

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
- 图书馆统一页：首页“图书馆”进入，只显示唐岛湾、古镇口东区、求是三个座位卡；新版统一页
  已在模拟器复核，无账号/姓名/预约数据。Chaoxing 座位卡打开原生只读页，页内保留官方预约
  回退；没有研讨间卡片或深链。
- Chaoxing 原生只读：seat 东区 6 区域/48 座位/08:00–21:30、seatengine 求是 21 区域/
  30 座位/08:00–22:00 已在真实 Android 进程内读取；模拟器从“求是座位”卡直接进入求是，
  加载真实区域和已用人数；座位图 30/48 座；没有点击最终预约。
- 图书馆写操作：未触发任何真实 libseat/Chaoxing 预约、取消、签到；libseat 座位取消功能
  保留，但没有用真实记录执行。研讨间/空间预约已撤下，不再有可调用的预约、取消、团队、
  成员或租户上下文接口。

## 5. 自动化结果

| 检查 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 通过（图书馆改动后复跑） |
| `:core:upc-auth:testDebugUnitTest` | **18 tests，0 failures/errors/skipped**（nspace 认证测试随功能删除；CAS/课表/座位认证用例保留） |
| `:feature:library:testDebugUnitTest` | **88 tests，0 failures/errors，1 skipped**（libseat 座位/筛选/返回状态、区域统计、Chaoxing seat 解析/身份/会话/清理门闩；需显式 Cookie 的真实座位回放默认跳过。nspace/reservepc 专属测试随功能删除） |
| `:app:testDebugUnitTest` | **32 tests，0 failures/errors/skipped**；页面集合保留旧研讨间路由的兼容落点断言 |
| Android 真实只读 | `RealChaoxingAuthTest` 在 API 36 模拟器进程内通过：账号保存→本人核验→seat/engine 区域/座位图/已用座位；不再包含 reserve 请求 |
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

- 评审副本：`release/UPC-Pocket-M5-library-seat-pages-debug.apk`
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：`65,474,558` bytes
- SHA-256：`543CE18D15D1AB55D08270FAF70AB1DDDBAFAE840B7557E3D8AA9034B693A2CA`
- 该 APK 为 debug 构建，含 debug-only 合成课表预览与座位预览 Activity；release 构建不会合并它们。未上传、未发布。
- 文件名明确为 seat-pages；旧 seats-only 构建与研讨间 APK 不再作为现行交付物。

## 6. 未验证 / 限制

- 研讨间/空间预约已按用户 2026-11-04 决定撤下；历史 nspace/reservepc 研究证据仍保留在
  [图书馆六入口（归档）](图书馆六入口.md) 和 `docs/research/`，不代表现行产品能力。
- 截图 35–43 是 Debug 合成预览，用于评审布局、深色和 1.5×，不连接学校、没有账号/真实数据；
  真实 libseat 区域/座位/时段仍需登录后由学校接口返回，本轮没有使用真实账密重验。
- Chaoxing 真实 App 内登录仍未验收：`passport2` 要求手机号/超星号 + 学习通密码，不是学校
  CAS；不能把统一学校账号密码自动提交。受控 UI 验收只在测试进程注入过一次本机已登录会话
  Cookie，测试结束只清理私有文件；本轮未再 `pm clear`。
- 截图中的合成课表只覆盖视觉状态，不等于真实协议数据；真实数据仍受 M3 的账号、会话、缓存与
  服务端校验约束。
- 1.5× 下教学周头部计数会自然换行；布局可滚动、不重叠，但还未针对 2.0× 单独适配。
- 未重排空教室、图书馆、电费内部页面；本轮只统一外层视觉/入口。它们沿用 M0—M4 的页面与交互，
  因此仍可能保留旧 spacing/文案密度，作为下一轮迭代项。
- 未执行 release/R8、真机、其他 ROM/WebView；桌面组件仅验证设置页与既有测试。
- 本轮没有执行任何真实预约/取消/签到；libseat 座位取消功能保留但未用真实记录验收。
- 这不代表最终设计定稿；需要用户先看实际第一版，再决定是否继续细化。
