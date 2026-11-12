# UPC Pocket 项目开发规范

本文件适用于整个仓库。后续 Agent 开发、评审和验证以此作为项目入口。

## 项目背景与范围

- 产品目标、阶段与校园数据接入边界见根目录 `UPC-Pocket-总体计划-v0.1.md`。该计划书保留原文；阶段变化应另行记录。
- 当前正式发布为 UPC Pocket 2.0.3；main 已合入面向下一版本的原生校园卡登录与本人只读交易流水（宿主装配认证适配时启用，尚未发布、未变更 versionCode），应用标识 `com.upcpocket.app`。保留校园卡余额与本人只读交易流水、唐岛湾电量、两校区空闲教室、图书馆座位、本机待办及一次性提醒、专注、只读课表与三个 RemoteViews 组件；校园卡/电量默认 2×2，今日课表默认 2×2、可扩到 4×2。古镇口通过学校页面访问。数字石大和图书馆主页已移除；交易流水仅本人查询，不含充值、转账、预约等写操作，也不代表学校认证通过或已取得官方身份；课表为 M3 新增的只读能力，含周网格、今日课程与桌面组件，但不包含编辑、导出或提醒。原计划为历史原文，当前范围见 README。新增校园能力必须核对 UPC 来源，不能套用郑大接口、认证和宿舍规则。
- 用户的新指令优先于历史计划。校园受保护接口的授权范围以实际取得的授权为准；本地开发、构建或引入技能不代表获得校园接口授权。
- 当前实现进度：M0 模块化与插件化已完成并经模拟器回归，记录见 `docs/m0/`；M1 统一账号本机加密保存、代次与失效/清理基础已完成，记录见 `docs/m1/`；M2 校园卡余额强关联、旧快照迁移与页面/组件/Worker 同目标已完成，记录见 `docs/m2/`；M3 课表真实 CAS 认证、学期/教学周查询、周网格、今日课程、只读缓存与离线查看已实现并完成模拟器及本人账号只读验证，记录见 `docs/m3/`；M4 图书馆统一认证、本人校验与账号/代次绑定已完成，记录见 `docs/m4/`；M5 三 tab 首页/课表/专注、统一设置入口、课表周网格与视觉第一版已实现并在模拟器验收，记录见 `docs/m5/`。2026-11-05 晚完成三组件 Apple 式视觉修订、尺寸/1.5× 字体矩阵与 R8 精简 Release 本地验收，记录见 `docs/m5/2026-11-05-组件视觉修订.md`。2026-11-05 用户正式验收通过，M0–M5 合入 main，以 2.0 正式发布（versionCode 20，兼容原开发签名）。M1 保存成功只表示“已保存，尚未验证”，不得表述为学校认证通过或已取得官方身份。
- 当前导航为四 tab「首页 / 课表 / 待办 / 专注」，支持整页 HorizontalPager 与浮动胶囊联动；M5 第一版三 tab 为历史基线，账号/图书馆登录/组件/官方网页/外观/关于统一在首页右上角设置；设置入口唯一不等于每个功能只有一条快捷路径（校园网页还有首页卡，统计还有专注顶栏/今日卡，均指向同一路由）。改变入口时同步更新 `docs/m5/README.md` 的旧→新映射与缺插件说明。M5 不引入 Navigation 3、AGP 9、Glance 或数据层迁移，也不重启“更多”第二套目录。
- 2026-11-05 课表/组件/超星预约要求见 `docs/m5/2026-11-05-课表与组件设计.md`：周网格以真实日期连续周日至周六；左轴使用学校官方 1–12 节作息，13–14 节无官方时刻时显示“时间待确认”；今日课程按上海时区实际今天独立于查看周；今日课表组件使用统一近实底 RemoteViews 设计（24dp 圆角、20dp 细线刷新 + 44dp 透明热区、无玻璃/描边），支持 2×2/4×2 自适应与独立刷新；余额/课表组件冷启动刷新必须等待有界账号恢复；超星 seat/seatengine 原生提交只在用户确认后一次发送，超时/未知不回重试，验证码分支才回官方页。视觉细节以 `docs/m5/2026-11-05-组件视觉修订.md` 为准。
- 不将账号密码、Cookie、Token、私钥或未脱敏的学生业务数据写入源码、文档、日志和测试样本。

## Android Skills 使用规则

本项目引入 [android/skills](https://github.com/android/skills)，技能位于 `.agents/skills/<技能名>/SKILL.md`。来源、版本和完整索引见 `docs/android-skills.md`。

- 开始 Android 相关任务时，先按技能描述选择与任务直接相关的技能，阅读对应 `SKILL.md`，并按需读取其附带资源。
- 技能用于具体任务的技术指导；不要为执行全部技能而扩大任务范围。
- 技能提出的新 API、工具、依赖或迁移步骤，需要先核对本项目的 SDK、AGP、Kotlin、Compose 与 Gradle 版本。
- 保留当前已工作的架构：Compose / Material 3、手动 AppGraph、Room / DataStore、OkHttp，以及 RemoteViews 桌面小组件。技术迁移应由实际任务需求驱动。
- 上游技能按固定提交引入。更新时显式审查差异，同步版本记录和许可证，避免直接修改上游副本来存放项目特有规则。

| 任务 | 优先阅读的技能 |
| --- | --- |
| Compose 重组、滚动或渲染性能 | `jetpack-compose-performance` |
| 大屏、窗口尺寸与自适应布局 | `adaptive` |
| 状态栏、导航栏、Insets、边到边布局 | `edge-to-edge` |
| 权限申请与清单权限审查 | `android-permissions-security` |
| Intent、导出组件和跨应用通信 | `android-intent-security` |
| 测试环境或测试基础设施调整 | `testing-setup` |
| ANR 排查 / 性能分析 / R8 问题 | `anr-audit-and-fix` / `android-profiler` / `r8-analyzer` |

其他技能按其描述触发。例如，Glance 技能适用于使用或迁移到 Glance 的任务，不要求现有 RemoteViews 组件改用 Glance；`agp-9-upgrade`、`navigation-3` 仅在对应迁移任务中使用。

## 当前发布

当前开发应用身份为 **UPC Pocket 新版 / `com.upcpocket.next`**，使用独立长期 Release 签名，可与旧版 `com.upcpocket.app` 并存；Kotlin/资源 namespace 仍为 `com.upcpocket.app`。不迁移或清除旧包数据。新版更新文件固定为同仓库 `update-next.json` / `update-next.sig`，旧 `update.json` / `update.sig` 及已发布资产保持原样。上文旧标识、下文历史发布记录均保留其当时状态。

main 的下一版本开发代码为 **2.0.4 / versionCode 25**，整合古镇口东区、求是的按时段找座位/个人隐藏/布局对齐及校园卡原生本人流水；尚未发布。图书馆真实预约、取消、签到仍未验证，历史候选 APK 不包含流水。见 `docs/releases/2.0.4-chaoxing-library-parity.md` 与 `docs/research/native-card-history.md`。上文流水合入时未改 versionCode 的描述是当时状态。

当前正式版为 **2.0.3 / versionCode 24**，包含就地引导、空闲教室自动查询、本机待办提醒、首页摘要、四项整页滑动导航，以及唐岛湾按时段找座位/个人隐藏。当前导航为「首页 / 课表 / 待办 / 专注」，胶囊跟随 Pager 进度；上文 M5 三 tab 记录保留为历史。最新图书馆设计与验证见 `docs/design/2.0.3-library-time-search.md`、`docs/releases/2.0.3-library-time-search.md`；正式发布记录见 `docs/releases/2.0.3.md`，APK 与签名更新四件套同批发布；图书馆新增筛选和隐藏本轮仅适配唐岛湾。热力图改造属于另一个项目，本项目保持原样。

2026-11-06 用户验收通过课表修复、诊断导出与应用内更新，以 **2.0.2 / versionCode 23** 正式发布。2.0.1（21/22）为本地复测候选，不作为独立正式 Release。所有已提交支线成果合入 main；历史阶段文档保留当时状态。

## 实现与验证

- UI、领域模型与数据来源分离；本地能力与校园业务接口分别处理，错误和离线状态必须真实反映数据可用性。
- 保留上游版权与许可证；第三方技能的 Apache-2.0 许可证见 `.agents/android-skills-LICENSE.txt`。
- 机器专属 SDK 路径放入被 Git 忽略的 `local.properties`；JDK 路径使用本地环境或 IDE 配置，不能硬编码进共享 Gradle 配置。
- 代码改动运行与影响范围匹配的构建和测试。Android 常规验证：`./gradlew :app:assembleDebug :app:testDebugUnitTest`；Windows 使用 `./gradlew.bat`。
- 纯规范和文档变更检查路径、引用、来源及差异即可，无需重复构建 APK。
- 本机诊断只写固定枚举、数字、短随机 operationId 与过滤后的 `com.upcpocket.app.*` 帧；禁止写入账密、Cookie、URL/query、请求响应、HTML、课程/房间/预约内容、异常消息或自由文本。导出必须由用户主动触发，FileProvider 只开放专用导出子目录并临时授权；诊断失败不得影响业务。
- 应用内更新只允许固定 GitHub 仓库 `dlam12138/UPCPocket` 和内置候选域；清单必须用内置 ECDSA P-256 公钥验签原始 UTF-8 字节，再解析固定字段。不得依赖 GitHub API/PAT、不得让清单决定任意主机/算法、不得把镜像 SHA 文本当真实性、不得自动静默安装或为换签名卸载/清数据。新版每次发布必须同批提供 APK + `update-next.json` + `update-next.sig` + `SHA256SUMS.txt`（旧包历史线路仍用 `update.json` / `update.sig`），公开后不原地替换资产。更新私钥只在仓库外，发布脚本只读环境变量；`src/debug` 受控测试源不得进入 release，release TLS/网络安全配置不得为测试降低。更新 FileProvider 独立且只暴露 `cache/updates/`；最终安装用 PackageInstaller 会话交由系统确认，不静默安装。
- 交付说明写清改动、实际验证结果与尚未验证的部分；不能把构建成功当作真机或校园接口验证成功。

## 精简交付规范

- **面向用户文案少且具体**：标题、按钮、空态优先短句；不写协议名、实现名、开发者自证、重复作用说明或长期免责声明；技术约束收进现有“数据说明/关于”，不新增入口。失败、离线、缓存、未验证、验证码、清理未完成、结果未知和带后果的确认必须保留。
- 面向用户的默认最终产物是 **R8 + resource shrink 的已签名 Release APK**；debug APK 只用于开发、视觉夹具和定向测试，不把 60MB+ debug 包当交付。若用户明确要调试包，再单独说明。
- 保留现有全部功能，优先用 R8/资源收缩减小体积；默认 universal APK，不擅自改 ABI 多包或删功能。用实际 `assembleRelease` 产物报告字节数、SHA-256、签名证书指纹和 ABI；不凭经验承诺“几 MB”。
- 新版 Release 固定使用仓库外长期 keystore；缺少有效 `keystore.properties`/release key 时必须构建失败，禁止自动回退 debug 签名。debug 仅用于开发。不得为了换签名而卸载、`pm clear` 或清除用户数据；覆盖安装前比对包名、versionCode 与证书指纹。旧包历史开发签名记录保留原文。
- `src/debug` 的合成 provider、预览 Activity、调试开关和测试数据不得进入 release manifest/APK；构建后检查 merged manifest 和 APK 条目，不只依赖 source set 约定。
- R8 后的 RemoteViews、WorkManager、插件发现、序列化、账号恢复、Room/DataStore、WebView 和点击导航需要真实安装冒烟，至少覆盖进程死亡、组件刷新、整卡导航；`assembleRelease` 成功不等于这些路径可用。
- 已知 `:app:testReleaseUnitTest` 可能因 debug-only `ui-test-manifest` 配置失败；这是测试基础设施问题，不要为此改架构。采用 debug 定向单测 + release 真机/模拟器冒烟，并如实报告未覆盖分支。

## 文档归属与入口来源

- 当前目标学校为中国石油大学（华东），UPC 官方入口以 `docs/upc/官方入口.md` 和 `docs/upc/official-entrypoints.json` 为准。
- `docs/archive/zzu/` 仅为郑州大学历史参考；其中的域名、认证、Token、房号、校区和验收结论不得作为 UPC 事实。
- 跨学校可复用的研究方法见 `docs/research/校园系统探索方法.md`。
- 根目录 `官方入口.txt` 是本机原始材料，可能包含临时凭据；不要原样提交、复制进配置或输出日志。
- 入口可访问、会话建立、数据读取与业务成功分别记录，不能把 HTTP 200 当作接入完成。
