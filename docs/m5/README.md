# M5 · 页面、导航与视觉第一版

状态：**本地可评审第一版**（开发分支，未合并 main、未发布、未推送）。M5 不改变
M0—M4 的认证、缓存、清理与接口语义，只重新组织入口、页面层级与原生 Compose 视觉。

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
| 首页电费 / 空教室 / 图书馆 | 首页“常用服务”（课表不重复出现在这里） |
| 桌面组件由长按桌面添加 | 仍由系统桌面添加；新增设置 → 桌面组件说明与可选“添加到桌面” |
| 古镇口电费学校页面 | 电费页保留；设置 → 校园官方网页也提供入口 |
| 桌面组件点击 | 校园卡回首页；电量直达唐岛湾查询页，稳定路由不变 |

### 1.3 功能完整性

- 学校账号：保存/更新/删除、脱敏状态、`FLAG_SECURE`、离页清空密码输入、删除清理边界保留。
- 校园卡：查询目标仍只来自当前统一账号；累计余额/过渡余额、历史缓存标签、失败保留、
  同号改密标记、清除缓存与缓存清理失败状态保留。详细安全说明折叠，但不删除。
- 电费：唐岛湾原生查询，古镇口官方网页；两校区说明保留。
- 空教室：两校区、日期、楼栋、节次筛选和结果页不变。
- 图书馆：统一认证、座位查询/预约/取消、记住登录、会话清理不变。
- 课表：正常 CAS、单周按需查询、离线缓存、验证码网页登录回退、账号归属与迟到响应隔离不变。
- 专注：计时、今日记录、标签、统计、补录与前台服务不变；统计不再从“更多”重复出现。
- 官方网页：新增宿主只读目录，链接来自 `docs/upc/官方入口.md`；不预填账号、不代理登录。
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
- 图书馆：保留 `MORE_SCHOOL` 区，由新的设置页承载；学校账号仍由宿主提供。
- 空教室/电费/图书馆：继续贡献 `HOME_SERVICES`，首页不硬编码这些功能实现。
- `MORE_LEARNING` 枚举保留以维持源级契约兼容，但宿主不再挂载“更多”页；选择它不会产生
  可点入口。`PluginBottomNav` 文档由“宿主保底首页/更多”改为“宿主固定首页，插件 tab 排在其后”。

缺插件状态：

- 课表/专注 tab 来自插件；插件未激活时不会出现空 tab，宿主只保留首页。
- 首页服务与卡片区来自 `PluginUiSnapshot`；适配器/插件故障由现有原子裁剪逻辑处理。
- 设置里的学校登录区只在图书馆插件贡献存在时显示。
- 旧 `MORE_LEARNING` 贡献被静默保留但不可达；现有自动化确认没有把课表/统计再放进“学习”列表。

## 3. 截图

截图均来自本会话模拟器 `emulator-5554`（Android 16 / API 36，1080×2400，Pixel 风格），
无登录账号、真实余额、真实课程或真实学生数据。

| 文件 | 内容 |
| --- | --- |
| [01-home-light.png](screenshots/01-home-light.png) | 首页浅色；未登录、无历史余额 |
| [02-settings-light.png](screenshots/02-settings-light.png) | 唯一设置入口；账号、图书馆、组件、官方网页、外观、关于 |
| [03-timetable-first-run-light.png](screenshots/03-timetable-first-run-light.png) | 课表未登录第一屏；说明不会在首页后台认证 |
| [04-focus-light.png](screenshots/04-focus-light.png) | 专注浅色；无专注记录 |
| [05-appearance-light.png](screenshots/05-appearance-light.png) | 配色与深浅色设置 |
| [06-home-dark.png](screenshots/06-home-dark.png) | 首页深色 |
| [07-timetable-preview.png](screenshots/07-timetable-preview.png) | **Debug-only 合成预览**：教学周头部 + 今日课程 |
| [08-timetable-preview-grid.png](screenshots/08-timetable-preview-grid.png) | **Debug-only 合成预览**：周网格、重叠/多课、来源状态 |
| [09-timetable-preview-detail.png](screenshots/09-timetable-preview-detail.png) | **Debug-only 合成预览**：课程详情与缺失字段 |
| [11-widgets-light.png](screenshots/11-widgets-light.png) | 桌面组件设置；2×2 边界、pin 入口 |
| [12-official-web-light.png](screenshots/12-official-web-light.png) | 学校官方网页目录 |
| [14-home-large-font.png](screenshots/14-home-large-font.png) | 首页 1.3× 字号 |
| [19-timetable-font-150.png](screenshots/19-timetable-font-150.png) | **Debug-only 合成预览**：课表 1.5× 字号 |

合成预览由 `feature/timetable/src/debug/.../TimetablePreviewActivity.kt` 明确标注
“本地预览 · 合成内容”，只读取内存中的 `TimetableUiState`，不创建账号/会话/缓存、不发网络请求，
不会进入 release APK。它用于在无真实账号数据时评审周网格与详情，而不是冒充真实课表。

学校账号页因 `FLAG_SECURE` 不提供截图；已通过 `dumpsys window` 确认窗口 flag 含 `SECURE`，
并只核对空表单 UI 层级。没有截取任何已填账号或密码。

## 4. 验证
- 设备：`emulator-5554` / `upcpocket_api36` / Android 16（API 36）；实际安装 Debug APK。
- 页面：首页、设置、账号空态、课表未登录、专注、桌面组件、官方网页、外观；底部三 tab 切换。
- 系统 Insets：宿主底部 `NavigationBar` + 各 tab 内 `Scaffold` 只消费顶部/横向 Insets，
  修正了内层重复底部 inset；手势导航栏/状态栏无遮挡。
- 深浅色：首页浅色、深色；外观页切换“始终深色/始终浅色”后重启生效。
- 字号：1.0×、1.3×、1.5× 实测；首页、设置、课表、专注未出现按钮/文字重叠。
- 返回：设置 → 账号/外观/组件/官方/关于均能返回；官方网页实际打开 Chrome 后返回应用。
- 边界：未登录、无数据、合成周网格、空“今天”、离线/失败状态代码路径；没有触发真实学校登录、
  预约、取消、签到、充值或电量查询。
- 账号：`FLAG_SECURE` 已确认，但未创建账号、未走真实登录。

## 5. 自动化结果

| 检查 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 通过 |
| Android Debug 单元/Compose 测试 | **50 套件 / 357 tests，0 failures / 0 errors / 0 skipped** |
| JVM 测试（`:core:plugin-runtime:test`） | **16 tests，0 failures / 0 errors / 0 skipped** |
| `:app:lintDebug` | **0 errors / 2 warnings**（`RedundantLabel`、`ObsoleteSdkInt`，均为既有资源提示） |
| 模块边界 | `checkModuleBoundaries` 随构建通过 |
| 基线 | M4 357 Android + 16 JVM 全部保留；插件 tab 断言更新为课表(5)+专注(10) |

`./gradlew test` 还会触发 release unit test；其中 Compose UI 测试依赖 debug-only
`ui-test-manifest`，release 变体无法启动 instrumentation，属于现有测试基础设施限制，
不是本轮代码失败。本轮以项目既有基线 `testDebugUnitTest` + JVM 16 为准，未把 release
测试结果冒充为通过。

### 5.1 本轮 APK

- 评审副本：`release/UPC-Pocket-M5-preview-debug.apk`
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：`65,112,090` bytes
- SHA-256：`D6F673DADAE63C0E727039F113DADD7FABC11E46F3FD9D279BC79479AE43005B`
- 该 APK 为 debug 构建、含 debug-only 合成课表预览 Activity；release 构建不会合并它。未上传、未发布。

## 6. 未验证 / 限制

- 未重新执行真实学校 CAS、课表单周读取、图书馆本人只读或任何写操作；真实登录/真实课程不进入
  本轮截图。
- 未执行 release/R8、真机、其他 ROM/WebView、真实组件 pin 到第三方桌面；桌面组件仅验证了设置页
  与既有测试，未新增自动刷新/周期任务。
- 截图中的合成课表只覆盖视觉状态，不等于真实协议数据；真实数据仍受 M3 的账号、会话、缓存与
  服务端校验约束。
- 1.5× 下教学周头部计数会自然换行；布局可滚动、不重叠，但还未针对 2.0× 单独适配。
- 未重排空教室、图书馆、电费内部页面；本轮只统一外层视觉/入口。它们沿用 M0—M4 的页面与交互，
  因此仍可能保留旧 spacing/文案密度，作为下一轮迭代项。
- 这不代表最终设计定稿；需要用户先看实际第一版，再决定是否继续细化。
