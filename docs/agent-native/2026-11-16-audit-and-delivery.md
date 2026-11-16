# 2026-11-16：Agent-Native 文档审计与建设报告

范围：本次只建设文档与指令体系，不改业务源码、公共接口、构建/测试配置、上游技能、签名和 APK，不访问校园服务、不登录 CAS、不提交/推送/发布。原工作区已有 `hs_err_pid73112.log` 与 `promo-video/`，不属于本轮范围。

## Phase 1：先审计后建设

在修改任何文档前，主协调与 planner 只读检查根 AGENTS/README、docs 索引、BUILD/PRIVACY/CHANGELOG、v0.1 工程方案、M0–M5/近期发布记录、settings/各模块 Gradle、version catalog/wrapper及相应源码/测试入口；researcher 单独调查 Agent 兼容事实。原仓库未提供当前独立 ARCHITECTURE、API、CONTRIBUTING 或 ADR；没有发现 PROJECT_SPEC，不为凑名称创建重复产品规格。

核心事实通过代码而非目录名确认：AppGraph/HostUiAssembly 的静态插件装配；PluginDescriptor/Runtime/UI registry 的契约和校验；SchoolAccount API/Repository/CredentialAccess 的账号代次及明文边界；各 feature 的服务、协议/存储、Room schema、Widget Worker/ticker 与系统入口。

| 审计项 | 发现与处理 |
| --- | --- |
| 项目结构 | settings 实际 18 模块：app + 8 core + 9 feature；新增当前架构模块/直接依赖表，保留现有实现 |
| 工具链 | AGP 8.7.3、Kotlin 2.0.21、KSP 2.0.21-1.0.28、Gradle 8.9、Compose BOM 2024.12.01、min31/compile&target36、Java 17；BUILD 原 Kotlin 2.0.31 错误已纠正 |
| 版本/身份 | 源码开发 2.0.7/28，公开 2.0.6/27，旧版线路 2.0.3/24；这是开发/发布状态差异，不能用工程版本替换下载页。新版包名与源码 namespace 不同 |
| 根指令 | 只有根 AGENTS，发布流水过长、缺任务发现矩阵；原文完整归档，根保留现行重要规则并建分层导航 |
| 历史文档 | v0.1 方案和阶段记录有旧模块清单、已移动路径、“M3/M5 未实现”等当时状态；不重写历史，由新入口标日期和当前依据 |
| 账号文案 | 历史“已保存，尚未验证”与当前 UI“已保存”并存；根明确当前展示优先，内部 SAVED_UNVERIFIED 与业务认证独立 |
| 自动化 | 根已存在 checkModuleBoundaries；JVM 与 Android 模块测试任务不同，app 单测不覆盖全部模块；未发现 .github/CI，不虚构 CI 或 API兼容检查。末轮发现 wrapper 的 Git mode 为100644，Unix入口改为经sh运行；不改文件mode |
| 业务风险 | 校园卡充值/座位提交是写操作，不能沿用只读证据；账号清理/代次、未知订单防重、系统入口与签名更新需独有指令 |
| 数据兼容 | focus Room v1 含停用课表兼容结构；todo 为独立 Room v1/提醒 revision；新课表不用旧表，文档明确所有权与清理范围 |
| 精确校验 | 课表索引无学校本人身份字段，只有 Cookie/索引与本机账号/代次上下文校验；图书馆未保存账号的手动会话可仅内存，不能写成所有登录都比对保存账号 |
| 隐私旧表述 | 流水“验证码不打开浏览器”未区分现有用户主动学校 WebView 回退；按 CardHistoryScreen/Client 与 2.0.5 记录，改为停止自动提交、用户选择受控网页登录且返回后继续校验 |

建设计划据此冻结：保留已有架构和历史资料；根/七份局部指令负责工作规则；架构/API/贡献文档承担当前知识；四条追溯 ADR 固化真实长期选择；Agent 手册与兼容记录说明显式加载和证据边界；现有 CHANGELOG 保持单一入口。随后才进入文件编辑。

## Phase 2/3：文件清单、职责与层级

新建 19 份 Markdown；修改 6 份已有 Markdown。没有删除原有文档，不另建根 CHANGELOG 或复制 CLAUDE 规则。

| 新建文件 | 职责 |
| --- | --- |
| [ARCHITECTURE.md](../../ARCHITECTURE.md) | 当前 18 模块/依赖、启动、账号、业务数据流、数据所有权、系统入口与安全边界 |
| [API.md](../../API.md) | 内部 Kotlin 契约、输入输出/错误语义、能力缺失与消费者索引，非虚构 REST 服务 |
| [CONTRIBUTING.md](../../CONTRIBUTING.md) | 环境入口、开发/协作、按影响验证、证据等级、提交/发布和文档同步 |
| [app/AGENTS.md](../../app/AGENTS.md) | 装配/导航、组件系统适配、诊断、更新信任链 |
| [core/AGENTS.md](../../core/AGENTS.md) | 插件、账号事务/凭据、CAS、平台与设计系统边界 |
| [feature/AGENTS.md](../../feature/AGENTS.md) | 九模块导航、共同业务边界、轻量模块与 focus schema 约束 |
| [card/AGENTS.md](../../feature/card/AGENTS.md) | 受控余额目标、本人流水、单次充值与未知待查防重 |
| [library/AGENTS.md](../../feature/library/AGENTS.md) | 多平台协议/会话、按时段找、个人隐藏、单次预约与未知结果 |
| [timetable/AGENTS.md](../../feature/timetable/AGENTS.md) | 真实日期/官方节次、查看周与今日分离、账号绑定缓存与组件联动 |
| [todo/AGENTS.md](../../feature/todo/AGENTS.md) | 独立 Room、taskId+revision、提醒权限/系统事件与可重建副作用 |
| [ADR 索引](../adr/README.md) | 状态、编号与追溯记录维护方式 |
| [ADR 001](../adr/001-module-and-plugin-boundaries.md) | 静态内置插件与模块方向 |
| [ADR 002](../adr/002-account-generation-and-business-sessions.md) | 本机账号、凭据代次与独立会话 |
| [ADR 003](../adr/003-data-ownership-and-schema-compatibility.md) | 数据所有权、旧 schema 与本机数据保留 |
| [ADR 004](../adr/004-release-identity-and-update-trust.md) | 独立包名、长期签名、新旧更新信任链 |
| [Agent 手册](README.md) | 文档发现、手动加载、协作交接、技能与长期维护 |
| [Agent 兼容记录](agent-compatibility.md) | 官方/安装包支持、CLI事实、本项目实际加载及未验证边界 |
| [旧根指令归档](legacy-agent-instructions-2026-11-16.md) | 原始根 AGENTS 原字节，保留历史与可追溯性，不作为当前指令入口 |
| [本报告](2026-11-16-audit-and-delivery.md) | 审计、计划、清单、差异、实际验证与风险 |

| 修改文件 | 改动职责 |
| --- | --- |
| [AGENTS.md](../../AGENTS.md) | 精简根入口，新增两条用户要求、读取矩阵、层级、角色规则/证据与持续维护；重要现行约束保留 |
| [README.md](../../README.md) | 仅开发区域补架构/API/贡献/Agent入口，公开下载与产品说明保留 |
| [docs/README.md](../README.md) | 当前知识导航与历史资料优先级说明 |
| [docs/BUILD.md](../BUILD.md) | Kotlin 纠错、Gradle/KSP/Java目标依据、定向验证与真实CAS规则 |
| [docs/PRIVACY.md](../PRIVACY.md) | 流水学校网页登录表述与实际代码对齐，不扩张数据处理或授权 |
| [docs/CHANGELOG.md](../CHANGELOG.md) | 文档建设条目与2.0.7本地/2.0.6公开状态，明确本轮不是应用发版 |

局部层级按独有知识与风险选择，不逐 Gradle 模块铺文件；core/account 与 upc-auth 目前由 core 的独立短节覆盖，focus 兼容约束由 feature 和 ADR 003覆盖，不新增模块内部的更深指令。根全局规则不逐字复制到子级。各业务资料通过相对链接和真实源码入口查阅，避免建立又一套重复模块百科。

原根 AGENTS 与归档在复制时 SHA-256 均为：

```text
ceb5780a6bca68da82f3f71d346b9e16f244ca9dedab7761c75fdc0b13d5b5e8
```

归档为原字节，内部相对路径按原仓库根解释，不作为新目录下的活动导航；所有新导航从当前根/局部指令和索引进入。发布流水移入归档是知识重组，未删除记录。原根的校园来源/授权、短文案真实状态、诊断窄模型、签名更新/新旧线路、密钥与APK/安装约束保留在新根，并由局部指令指向具体设计。

## 文档发现与维护机制

根任务矩阵强制：识别影响模块 → 读取适用根/局部指令 → 按任务读普通文档实际章节 → 核源码/测试/配置 → 冲突调查 → 实施 → 同步文档 → 最终报告实读依据。简单任务轻量，跨模块、安全/架构/数据结构严格执行；局部指令链接核心文件、关联模块、定向验证与需同步资料。

发现长期反复误解才固化最合适层级；不把临时 Bug 写入 AGENTS，不随意加层或重复父级。架构/API/用户使用/版本分别维护 ARCHITECTURE、API、README、既有 CHANGELOG；重大选择新增关联 ADR，历史决策不覆盖。指令与普通文档均需要独立审查，交接不复制秘密或聊天全文。

当前四条 ADR 标为 Accepted（追溯），基于既有代码与项目要求；“备选”明确是此次对照分析，不冒充过去讨论。本轮无新增需要迁移的架构决策。

## 自动化现状与本轮验证

已有机制：Kotlin 类型/凭据 opt-in、根 checkModuleBoundaries、各模块现有测试、Android Lint、按需 Release 成品/设备冒烟。Gradle检查仅检查指定依赖方向，不替代源码/网络白名单检查；app单测不代表所有feature；Mock/单测不代表真实CAS或校园写成功。

当前缺口：没有仓库 CI、通用文档链接检查任务或自动 API 兼容基线。若今后要持续阻止链接失效/公开契约漂移，需要新增验证脚本/构建或CI配置，属于代码与基础设施变更，本轮不擅自扩大。可先以本次检查方法定向复核，后续另行评估标准库链接检查及现有边界任务接入CI。

已取得的 tester 记录由主协调核对原始 JSON/日志；以下仅声明实际覆盖，不把 dry-run 或只读审查写成业务测试通过：

| 项目 | 本轮状态 |
| --- | --- |
| 原根归档原字节 | 独立复核与git HEAD原根逐字节相同，SHA-256 与上文既定值一致 |
| 活动文档相对链接/引用路径 | tester核对24份活动文档396个本地链接（含HTML图片/链接）、37个具体反引号源码/配置路径，均无缺失；本地锚点0项；历史归档单列原根语境 |
| 外链与非具体路径 | tester的链接检查未请求22处外链；兼容官方资料由researcher另行核实。示例/模板/运行时/机器路径已分类，不声称这些路径已作为仓库文件逐项验证 |
| 版本、模块、公共签名 | 按源码/配置人工核对，独立语义review覆盖全部25份文档，无新增P级问题或阻断项 |
| Gradle 依赖边界 | 实际执行下述 offline checkModuleBoundaries，退出码0；任务已执行、边界检查通过 |
| 22 个构建/单测/Lint任务解析 | offline --dry-run 退出码0，目标均SKIPPED；仅任务可解析，未实际构建或运行测试 |
| 发布脚本示例 | 只读 PowerShell AST 解析0错误，9参数与BUILD示例匹配；未执行发布脚本 |
| 父子规则与安全语义 | 首轮课表/图书馆身份范围两项P2已修并独立复核闭环；终审包含手册/兼容/报告与6份旧文档差异，无阻断项 |
| 仅 Markdown / 无其他工作区覆盖 | 19新+6改=25份范围一致、无tracked删除；696项非Markdown基线0变化；原工作区未跟踪内容保留 |
| 差异与空白 | git diff --check退出码0；活动文档空白问题0项 |
| APK、单元/设备测试、真实 CAS、校园业务 | 本轮未执行；纯文档无需触发 |
| git commit/push、部署/公开发布 | 本轮未执行 |

实际依赖检查命令：`.\gradlew.bat --offline checkModuleBoundaries --console=plain --no-parallel --max-workers=2`。任务解析检查包含 app 的 Debug/Release构建、宿主测试/Lint，三个 JVM core 的 test，其余 Android core/九个feature的 testDebugUnitTest及card Lint；统一以 `--offline --dry-run` 检查，不计为22项测试通过。沿用现有 JAVA_HOME 的 JDK21.0.12.1，未修改机器环境/共享配置。

原始检查JSON/日志在本机被忽略的 `.local/verification/agent-native-2026-11-16/`，主协调已对照原始证据，不作为公开文档附件或校园验证证据。coder首轮自查为388个Markdown本地链接；tester最终396项包含HTML引用，统计范围不同。

根据Git index mode100644将三份活动入口改为 `sh ./gradlew`，Windows入口不变。末轮定向静态、链接及范围复核完成；未重跑Gradle，Linux/macOS运行仍未验证。

## Agent 兼容与 Skills

researcher 核实本机 Codex 0.169.0、Claude Code 2.1.321、DSH 0.2.2-rc.1 的版本/帮助，以及官方/本机官方包机制；严格先读 Lody 命令完整帮助再只读查询当前 builtin/codex 环境。根规则在本会话可见是用户消息提供，不能据此声称宿主自动扫描；新嵌套自动加载、其他 Agent 本项目模型会话和全部技能自动发现均未实测。

采用显式根→目标路径读取兜底；Claude 当前已有原生 AGENTS 支持，因此不机械创建 CLAUDE 副本。DSH官方包已检查，线上相应README返回404，保留验证限制。官方来源、优先级和逐工具边界见[兼容记录](agent-compatibility.md)。

保留已有固定版本 Android 技能及许可证；当前发布准备/校园研究方法已有专用文档与工具，未发现需要重复封装的新独立专业流程，故不新增 SKILL.md。跨 Agent 未验证自动发现时手动读取，不假设共享同一技能目录加载机制。

## 仍保留的历史问题与限制

- [UpcPocketApp.kt](../../app/src/main/java/com/upcpocket/app/UpcPocketApp.kt) 旧注释称 WorkManager 依赖已移除，与现有一次性组件刷新和本地课表 ticker 不符；本轮不改源码，架构和局部指令按实际代码写明。
- [AppDatabase.kt](../../feature/focus/src/main/kotlin/com/upcpocket/app/data/db/AppDatabase.kt) 的旧包身份、停用课表表数量等注释过时；当前实体声明与 applicationId 是事实依据。保留兼容结构，不因文档任务改 schema。
- 原始计划/阶段文档仍含旧路径与当时“未实现/未发布”，原字节归档也含原根相对路径；已明确历史身份，不视为未完迁移。未对所有历史链接逐项重写或保证其仍可执行。
- 无法把官方 Agent 支持说明、CLI version/help 或手动读取扩大为已验证自动注入；新层级后续会话需实际识别/显式读取。
- 本轮不重跑历史 APK/CAS/设备/校园写操作验收；公开与候选的真实验证范围继续以对应 release 记录为准，不能因文档完成扩大结论。

交付应说明全部文档改动、实际检查和上述遗留；不将建设文档等同代码重构、校园授权或正式发布。
