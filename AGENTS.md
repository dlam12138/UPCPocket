# UPC Pocket：Agent 工作入口

本文件适用于整个仓库。先识别受影响模块，读取本文件及目标路径上的局部 `AGENTS.md`，再按下表读取文档正文。简单改动可轻量执行；跨模块、架构、安全与数据结构任务必须完整执行。

## 本轮新增的长期要求

- **不做极端状态测试，排除非必要测试。** 按本轮改动与实际风险选择最小必要验证；不新增或主动执行无关的压力、破坏性故障注入、穷举设备矩阵或重复全量回归。不为此删除已有测试。纯文档任务检查来源、路径、规则和差异，不构建 APK。
- **需要 CAS 验收时使用已授权的本人真实账密。** 通过既有安全输入和认证入口完成；合成身份、Mock、离线测试不能代替真实 CAS 验收，不向学校提交虚构账号。缺少可用凭据时明确记为未验证。真实凭据、会话及未脱敏业务内容不得进入源码、文档、日志或测试样本。文档建设不触发学校登录。

## 项目与当前状态

- 面向中国石油大学（华东）的非官方 Android 校园工具。当前产品范围、安装和使用见 [README](README.md)。保留校园卡余额/本人流水/微信充值、唐岛湾电量、两校区空闲教室、图书馆座位、只读课表、本机待办及一次性提醒、专注。2.0.8 新增待办组件与独立截止，四类 RemoteViews 组件均可选独立静态磨砂版。2.0.9 新增常用校区、可选轻量玻璃及官方网页中的教务系统、数字石大、教务处官网和学院官网。石光与 e站通只读，不扩展到报名、签到、事项提交等写操作。课表不含编辑、导出或提醒。古镇口电量走学校页面，图书馆主页入口已移除。
- 当前正式版本为 **2.0.9 / 34**，已于 2026-11-19 公开并设为 latest，正式 APK 沿用同版本送审包原字节。旧版线路为 **2.0.3 / 24**。详情及产物哈希见 [2.0.9 正式记录](docs/releases/2.0.9.md)，此前版本见 [2.0.8 正式记录](docs/releases/2.0.8.md)与[开发记录](docs/releases/2.0.8-development.md)。已验收 APK 保留原字节，不能重编后冒充同一验收产物。
- 新版 applicationId 为 `com.upcpocket.next`，Kotlin/资源 namespace 为 `com.upcpocket.app`；与旧包独立安装，不迁移、卸载或清除旧包数据。下载入口为 GitHub 与 gh-proxy。
- 用户新指令优先于历史计划。原始 [总体计划](UPC-Pocket-总体计划-v0.1.md)、M0–M5、发布候选记录保留当时状态；旧根指令已[完整归档](docs/agent-native/legacy-agent-instructions-2026-11-16.md)。现行规则以本入口及适用局部指令为准，历史“未实现/未发布”不覆盖当前记录。

## 技术栈、目录与边界

2.0.8 基线见 [构建指南](docs/BUILD.md)：Kotlin 2.3.10、AGP 8.13.2、Gradle 8.13、JDK 17+、minSdk 31 / compileSdk 与 targetSdk 36，兼容决定见 [ADR 005](docs/adr/005-2.0.8-toolchain-and-widget-state.md)。保留 Compose / Material 3、Navigation Compose、手动 AppGraph、Room / DataStore、OkHttp、WorkManager 与 RemoteViews；不因技能推荐而迁移 Hilt、Navigation 3、AGP 9 或 Glance。开发与验证范围见 [2.0.8 记录](docs/releases/2.0.8-development.md)。

| 目录/入口 | 职责与必读局部指令 |
| --- | --- |
| `app/` | Application、AppGraph、宿主导航、组件系统入口、更新与诊断；[app/AGENTS.md](app/AGENTS.md) |
| `core/` | 插件契约/运行时/UI契约、账号与认证、平台能力、设计系统；[core/AGENTS.md](core/AGENTS.md) |
| `feature/` | 9 个纵向业务模块；[feature/AGENTS.md](feature/AGENTS.md)；校园卡/图书馆/课表/待办还有各自局部指令 |
| `docs/` | 当前知识、ADR、来源与历史证据；[文档索引](docs/README.md) |
| `tools/release_update.ps1` | 签名更新附件准备；不自动上传或发布 |
| `.agents/skills/` | 固定版本 Android 技能；[来源与索引](docs/android-skills.md) |

`app` 显式装配，feature 之间不得互依赖，core 不依赖 feature/app，业务 feature 不得依赖 `core:account` 或获取明文密码。`plugin-api`、`account-api` 无内部模块依赖；`plugin-runtime`、`plugin-ui` 仅依赖 `plugin-api`。不得通过反射、全局强转或公共模块堆积规避边界。完整职责、数据流及实际依赖见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 按任务读取

所有任务先读根及目标路径上适用的局部指令；以下是额外必读资料。读取实际相关章节，不能仅凭文件名判断已经理解。

| 任务 | 必须查阅 |
| --- | --- |
| 普通 Bug | 对应源代码与相关测试；局部指令引用的业务记录 |
| 新功能/跨模块改动 | [架构](ARCHITECTURE.md)、[内部契约](API.md)、相关 [ADR](docs/adr/README.md)、[贡献流程](CONTRIBUTING.md) |
| 插件/公开接口 | [API](API.md)、架构的依赖/扩展章节、ADR 001；核对所有消费者 |
| 账号/CAS/学校接入 | 架构的账号流程、[隐私](docs/PRIVACY.md)、ADR 002、[官方入口](docs/upc/官方入口.md)及[入口 JSON](docs/upc/official-entrypoints.json)、相关 research |
| 持久化/迁移/清理 | 架构的数据归属、ADR 003、所属模块模型/schema与测试；账号清理还读 ADR 002 |
| 组件/课表/导航/视觉 | app 与对应 feature 指令、[M5 入口映射](docs/m5/README.md)、[课表与组件设计](docs/m5/2026-11-05-课表与组件设计.md)、[组件视觉](docs/m5/2026-11-05-组件视觉修订.md) |
| 权限/Intent/诊断/更新安全 | [隐私](docs/PRIVACY.md)、app/core 指令、相关 Android 技能；更新另读 [更新机制](docs/updates/README.md)与 ADR 004 |
| 构建/依赖/验证环境 | [BUILD](docs/BUILD.md)、[CONTRIBUTING](CONTRIBUTING.md)、version catalog与受影响模块 Gradle |
| 发布/分发 | BUILD、更新机制、ADR 004、[CHANGELOG](docs/CHANGELOG.md)、对应 `docs/releases/` 记录与用户授权 |
| 文档/指令/Agent 兼容 | [Agent 手册](docs/agent-native/README.md)、[兼容记录](docs/agent-native/agent-compatibility.md)、本轮[审计报告](docs/agent-native/2026-11-16-audit-and-delivery.md) |

文档与代码冲突时调查并报告；先确认是否是历史现场或尚未完成的迁移，不自行猜测。改动后检查文档同步，最终说明实际查阅的关键文档。

## 产品、安全与数据约束

- UI、领域模型与数据来源分离。失败、离线、缓存、未验证、验证码、清理未完成、结果未知和有后果的确认必须真实表达；不把缺数据或请求失败显示成成功空结果。账号保存显示“已保存”，内部仍为 `SAVED_UNVERIFIED`，不得称“已登录”或“学校认证通过”。
- 当前五 tab 为“首页 / 课表 / 服务 / 待办 / 专注”，Pager 与浮动胶囊联动；统一设置入口在首页右上角，不重建“更多”目录。快捷路径可以多处指向同一路由；改入口同步 M5 的旧→新映射与缺插件行为。用户文案短且具体，技术约束收进已有“数据说明/关于”，不新增说明入口。
- 新增校园能力核对 UPC 来源，禁止套用 `docs/archive/zzu/` 中的域名、认证、Token、宿舍规则或验证结论。校园受保护接口以实际取得的授权为限；本地开发、构建和技能安装不构成学校接口授权。只读准备、下单、微信分发、付款与到账分别记录，不能互相替代。
- 账号密码、Cookie、Token、私钥、未脱敏学生业务数据不写入源码、文档、日志、样本。根 `官方入口.txt` 可能含临时凭据，不原样提交、复制配置或输出日志。SDK 路径只放被忽略的 `local.properties`，JDK 走本机环境，不硬编码共享配置。
- 诊断只允许固定枚举、有界数字、短随机 operationId 与过滤后的 `com.upcpocket.app.*` 帧；禁止账号标识、URL/query、请求响应、HTML、课程/房间/预约内容、异常消息及自由文本。用户主动导出，专用目录和临时 FileProvider 授权；诊断失败不得影响业务。
- 更新仅固定仓库 `dlam12138/UPCPocket` 和内置候选域；内置 ECDSA P-256 公钥先验原始 UTF-8 字节再解析固定字段。禁止 GitHub API/PAT 依赖、清单指定任意主机/算法、镜像 SHA 文本代替真实性、静默安装、卸载/清数据换签名。更新 provider 独立且仅暴露 `cache/updates/`；用 PackageInstaller 交系统确认。
- 新版发布同批提供 APK、`update-next.json`、`update-next.sig`、`SHA256SUMS.txt`；设 latest 时原字节附带旧 `update.json`/`update.sig`，旧线路继续指向 2.0.3，旧 Release 不变。公开后不原地替换资产；私钥只在仓库外，发布脚本只读环境变量。release 不得引入 debug 测试源或降低 TLS 策略。
- 保留上游版权与许可证，Android 技能另适用 [Apache-2.0](.agents/android-skills-LICENSE.txt)。

## 执行、验证与交付

简单任务直接处理；复杂需求、跨模块或重大技术决定先 planner；外部事实、官方文档或文献交 researcher；实际代码修改交 coder。重要变更分别由 tester 测试、reviewer 独立审查。任务须给目标、文件范围、限制与验收标准；独立任务可并行，同一文件只有一个写入者。检查返回证据，失败或审查问题回 coder 修复并定向复核，不把角色声明直接当验证事实。删除、覆盖、迁移、权限扩大、生产部署和高成本计算严格遵守用户授权；不扩大已授权范围。

Windows 常用入口（按影响选择，不机械全跑）：

```powershell
.\gradlew.bat checkModuleBoundaries
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
```

Linux/macOS 使用 `sh ./gradlew`（仓库 wrapper 未设可执行位）；模块单测与 Lint 选择见 CONTRIBUTING。`:app:testDebugUnitTest` 只代表宿主测试，不代表所有模块。`--dry-run` 只验证任务解析，不是测试通过。已知 `:app:testReleaseUnitTest` 可能受 debug-only `ui-test-manifest` 影响；用 debug 定向单测与必要 Release 安装冒烟，不为此改架构。

用户需要 APK 时默认交付 **R8 + resource shrink、长期 Release 签名的 universal APK**，保留全部功能；不擅改 ABI 多包或删功能。缺有效 `keystore.properties`/release key 必须失败，不能回退 debug 签名。报告实际字节数、SHA-256、证书指纹和 ABI；覆盖安装前比对包名、versionCode 和证书，不卸载、`pm clear` 或清用户数据。检查实际 merged manifest/APK 条目，确认 debug 合成 provider/预览 Activity/开关不进入 release。涉及 R8/系统入口的改动按实际影响安装冒烟，核查账号恢复、组件刷新/整卡导航及相关存储、WebView、插件、Worker 路径；必要时包含普通进程重启，不扩大为无关极端状态测试。

交付写清改动、实际验证、未验证部分、遗留风险及建议；区分 Mock/单测、集成、设备、真实校园与用户验收。不得把构建成功、HTTP 200、入口可达或会话建立当业务全部成功。纯文档任务交付文档报告，不构建 APK、不触发 CAS、不推送或发布。

## 技能与长期维护

Android 任务先按[技能描述](docs/android-skills.md)读取直接相关的 `SKILL.md`，首次使用时告知用户，按需读资源；新 API/工具/依赖先核对现有工具链。性能读 `jetpack-compose-performance`；自适应读 `adaptive`；Insets 读 `edge-to-edge`；权限/Intent 分别读相应 security 技能；测试基础设施读 `testing-setup`；ANR/性能/R8 按任务选相应技能。不要扩大范围执行全部技能、直接改上游副本或静默更新固定提交；更新需审查差异、来源和许可证。其他 Agent 未确认自动发现技能时按手册显式读取。Lody 命令必须先读成功的命令专属 `lody_help({command: TOOL_NAME})`，帮助与命令顺序执行；上下文丢失后重读。

发现规则错误及时报告，反复出现且已验证的长期经验才固化到最合适层级；不把每次临时 Bug 写入指令，不重复父级、不随意新增 AGENTS。重要规则不得未经说明删除或弱化。架构变化同步 ARCHITECTURE 与相关 ADR；公共契约同步 API；使用方式同步 README；发布维护现有 docs/CHANGELOG；文档本身也需 Review。所有文档改动和实读依据纳入最终报告。无 CI 的项目不得声称 CI 已保障约束，自动化覆盖与缺口见审计报告。
