# 开发与贡献

所有改动从 [AGENTS.md](AGENTS.md) 与目标路径的局部指令开始。产品安装见 [README](README.md)，机器配置/工具链/签名唯一操作入口是 [docs/BUILD.md](docs/BUILD.md)，架构和内部边界分别见 [ARCHITECTURE](ARCHITECTURE.md)、[API](API.md)。

## 开始任务

1. 查看 `git status --short`，识别已有修改与本轮范围，不覆盖其他人的工作。通过 `rg --files`/`rg` 找真实入口，再读源码、相关测试和任务矩阵指定文档正文。
2. 标出生产者/消费者、持久化、账号/业务代次、系统入口及外部服务影响。文档与代码不一致先调查原因；历史方案不是默认待实施清单，迁移状态要核实。
3. 简单任务直接完成；复杂/跨模块/重大技术决定先 planner，外部事实交 researcher，代码修改交 coder，重要改动分别交 tester/reviewer。交接明确目标、文件归属、限制与证据；同一文件不并行写，失败回 coder 修复后复核。
4. 保持手动 AppGraph 和现有库/导航/RemoteViews；需要迁移时由实际需求驱动并记录决策。不要借文档或 Bug 修复重构无关业务。

JDK/Android SDK 按 BUILD 配置，本机路径和 keystore 不入库。不要读取或提交 `官方入口.txt`、本机密钥、临时网络材料和未脱敏业务数据。已有 Android 技能按任务触发，来源/版本见[索引](docs/android-skills.md)，不直接改上游副本。

## 实现约定

保持 UI、业务服务、协议/持久化分工；命名跟随相邻代码。跨模块只经声明的窄接口，稳定 ID、路由、Provider/Worker/Intent、数据库文件名与存储格式视为兼容面。新增能力必须同时检查宿主装配、可选插件缺失和所有消费者。

用户文案短且具体，保留真实错误、离线/缓存、交互验证、清理失败和未知结果。账号“已保存”不改成学校登录成功。新增校园能力先核对 UPC 来源和用户授权，不从旧学校归档推导事实，不将只读验证扩写成写操作验收。

## 按影响选择验证

**不做极端状态测试，排除非必要测试。** 不机械跑全工程、压力/破坏性故障注入或穷举设备矩阵，也不删除旧测试。先选与改动直接相关的既有检查；检查通过后，只有新增改动、失败或未解决风险才继续扩大。纯规范/文档只查路径、来源、规则与差异，不构建 APK。

以下命令在仓库根执行，Windows 使用 `.\gradlew.bat`，Linux/macOS 替换为 `sh ./gradlew`，因为仓库 wrapper 未设可执行位。`<模块>`/`<测试类全名>` 是需要替换的示例参数。本轮 Android 命令验证在 Windows 完成；Web 服务与 Node 命令在 macOS 验证，Windows 下使用对应可执行路径。

| 影响 | 最小适用入口 | 说明 |
| --- | --- | --- |
| 文档 | `git diff --check`；检查新增/改动文档的相对链接与源码引用 | 不运行校园请求，不为文档制作 APK；历史归档原文另核哈希 |
| 依赖方向/插件装配 | `.\gradlew.bat checkModuleBoundaries` | 只查 Gradle 边；装配运行时还选 runtime/UI/app 的相关测试 |
| JVM 基础模块 | `.\gradlew.bat :core:plugin-api:test :core:plugin-runtime:test :core:account-api:test` | 三者均为 JVM；按实际影响选其中任务/测试类 |
| Android 业务/基础模块 | `.\gradlew.bat :feature:<模块>:testDebugUnitTest --tests <测试类全名>` | core 的 Android 模块同用 `testDebugUnitTest`；不是 JVM `test` |
| 宿主/导航/系统消费者 | `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` | 仅宿主单测不代表 feature 单测已跑 |
| 受影响 Android 静态检查 | `.\gradlew.bat :app:lintDebug` 或对应模块 `lintDebug` | 模块/UI/权限变化按范围选；不把 Lint 当设备验证 |
| APK 交付 | `.\gradlew.bat :app:assembleRelease` | R8、资源收缩、长期签名；再核实际包与必要安装冒烟 |
| Web 服务（`server/`） | `server/.venv/bin/python -m pytest`；`node --test tools/web/test_login_crypto.mjs` | 默认跳过 2 项 Redis/PostgreSQL 集成用例；设置 `UPCP_TEST_ALLOW_DANGEROUS=1` + `UPCP_TEST_REDIS_URL`/`UPCP_TEST_DATABASE_URL`（只指向一次性测试实例） 才运行它们 |
| Web 合成/本机验收 | `server/.venv/bin/python server/tests/serve_fake.py` | 仅本地合成学校，拒绝 `ENV=production` 且不进镜像；不能替代真实校园验收 |

Web 服务改动至少运行 `server/.venv/bin/python -m pytest` 与 `node --test tools/web/test_login_crypto.mjs`；涉及多用户会话、Redis 或 PostgreSQL 时再按需设置 `UPCP_TEST_ALLOW_DANGEROUS=1` + `UPCP_TEST_REDIS_URL`/`UPCP_TEST_DATABASE_URL`（只指向一次性测试实例） 运行集成用例，默认跳过不代表已覆盖。合成学校、Playwright WebKit 与本机 Redis/PostgreSQL 只证明其覆盖的本机路径，不等于真实 CAS、真实预约写入、Docker 部署或真机验收。

可用 `--tests` 选择现有类；不为低影响可逆调整编写与实现一一对应的冗余测试。`:app:testReleaseUnitTest` 已知可能受 debug-only `ui-test-manifest` 配置影响，不为绕过它改架构；采用 debug 定向测试与必要的 release 成品验证，并如实报告限制。

文档命令验证可用 `--dry-run`，但它仅说明 Gradle 能解析任务，不是测试或构建通过。缓存结果/旧 XML 不算本轮重新执行；报告退出码、任务是否执行/缓存、有效 XML 的数量/失败/跳过与覆盖范围，不虚构测试数字。

系统入口/R8/持久化改动按影响安装冒烟，核对实际 merged manifest/APK 不包含 debug 合成 provider、预览 Activity、开关或测试服务，检查相关组件刷新/点击、普通进程恢复、存储与 WebView 路径。安装前比对 applicationId、versionCode、证书，不能卸载/清数据解决签名问题。完整 APK 交付规则在根指令和 BUILD。

## 真实校园验收与证据

需要 CAS 验收使用本人已授权真实账密，通过已有安全入口；合成身份/Mock/离线不能替代，不向学校提交虚构账号。凭据缺失记未验证，不把账密加入命令、日志或测试样本。本地开发权限不授予任何校园写操作权限。

| 证据 | 可以说明 | 不能说明 |
| --- | --- | --- |
| 静态检查/任务 dry-run | 路径/规则/任务解析等实际检查范围 | 已编译、测试或真实登录成功 |
| Mock/单元测试 | 覆盖逻辑在指定输入下的行为 | 学校当前协议/账号/设备全可用 |
| 本机集成/设备冒烟 | 实际产物/设备和所执行流程可用 | 未执行的验证码、写操作或全部设备 |
| 本人真实 CAS/只读业务 | 本次正常认证/本人读取完成 | 预约、下单、微信付款或到账 |
| 用户验收 | 用户明确验收的产物及范围 | 开发侧独立完成所有业务验证 |

校园卡准备、订单创建、微信分发、付款、学校订单确认及余额变化逐阶段记录；图书馆预约/取消/签到需要各自证据。HTTP 200、入口可达与会话建立均不能单独证明业务成功。脱敏结论进文档，原始个人证据仅依既有本机隔离约定保存。

## 文档与决策维护

架构变化同步 ARCHITECTURE 与相关 [ADR](docs/adr/README.md)；契约变化同步 API；用户操作变化检查 README/M5 映射；数据与权限变化同步 PRIVACY；发布维护现有 docs/CHANGELOG 和对应 release 记录。重要指令变化说明缘由并 Review，不能静默删弱；只有经验证、反复有用的经验才进入适当层级 AGENTS。

重大决策新增 ADR，包含状态、背景、决策、备选、理由与代价；替代旧决策时建立关联，不覆盖旧记录。历史研究、原始总体计划和旧发布现场保留；新入口说明适用日期与当前事实。文档路径/API/命令和父子规则也需要审查，不因是文档跳过正确性检查。

## 提交、发布与交接

提交前核对 diff 范围、无秘密/真实业务样本、文档同步和实际检查证据。遵循相邻提交风格，不自动替用户提交、push 或发布；当前文档任务没有这些授权。PR 描述写具体问题、最终行为、验证和未覆盖范围，避免叙述弃用过程。

生产发布单独遵守用户授权和 [更新机制](docs/updates/README.md)。`tools/release_update.ps1` 仅准备附件；已验收 APK 保留原字节、不得重编冒充。同一 latest 六附件及旧版线路按 ADR 004，公开后不覆盖资产。

交接至少写：目标/已完成、文件与写入归属、实际阅读、关键决策、执行命令与证据、未验证/风险、下一步。最终由主协调核对证据，不直接把子角色声明当事实。跨会话可用[Agent 手册](docs/agent-native/README.md)的短交接格式。
