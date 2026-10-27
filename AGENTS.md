# UPC Pocket 项目开发规范

本文件适用于整个仓库。后续 Agent 开发、评审和验证以此作为项目入口。

## 项目背景与范围

- 产品目标、阶段与校园数据接入边界见根目录 `UPC-Pocket-总体计划-v0.1.md`。该计划书保留原文；阶段变化应另行记录。
- 本分支（`feat/native-business-loop`，基线 `53639b9`）已把包名与应用标识改为 `com.upcpocket.app`，删除郑大认证、教务/一卡通/研究生系统接口、宿舍房间表与自动更新链路；保留 Compose 课表、自定义日程、番茄钟、Room/DataStore、RemoteViews 组件和原生空闲教室查询。新增校园能力前必须确认 UPC 官方来源与真实会话，不能把郑大认证、接口、宿舍规则套用到 UPC。
- 用户的新指令优先于历史计划。校园受保护接口的授权范围以实际取得的授权为准；本地开发、构建或引入技能不代表获得校园接口授权。
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

## 实现与验证

- UI、领域模型与数据来源分离；本地能力与校园业务接口分别处理，错误和离线状态必须真实反映数据可用性。
- 保留上游版权与许可证；第三方技能的 Apache-2.0 许可证见 `.agents/android-skills-LICENSE.txt`。
- 机器专属 SDK 路径放入被 Git 忽略的 `local.properties`；JDK 路径使用本地环境或 IDE 配置，不能硬编码进共享 Gradle 配置。
- 代码改动运行与影响范围匹配的构建和测试。Android 常规验证：`./gradlew :app:assembleDebug :app:testDebugUnitTest`；Windows 使用 `./gradlew.bat`。
- 纯规范和文档变更检查路径、引用、来源及差异即可，无需重复构建 APK。
- 交付说明写清改动、实际验证结果与尚未验证的部分；不能把构建成功当作真机或校园接口验证成功。

## 文档归属与入口来源

- 当前目标学校为中国石油大学（华东），UPC 官方入口以 `docs/upc/官方入口.md` 和 `docs/upc/official-entrypoints.json` 为准。
- `docs/archive/zzu/` 仅为郑州大学历史参考；其中的域名、认证、Token、房号、校区和验收结论不得作为 UPC 事实。
- 跨学校可复用的研究方法见 `docs/research/校园系统探索方法.md`。
- 根目录 `官方入口.txt` 是本机原始材料，可能包含临时凭据；不要原样提交、复制进配置或输出日志。
- 入口可访问、会话建立、数据读取与业务成功分别记录，不能把 HTTP 200 当作接入完成。
