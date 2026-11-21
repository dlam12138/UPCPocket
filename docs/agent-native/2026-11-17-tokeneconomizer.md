# 2026-11-17：TokenEconomizer 本机接入记录

状态：**安装与项目配置完成；已信任五项 hooks，新 Lody 与原生 CLI 会话均已观察到插件 startup 执行**。尚未观察到经验注入、循环干预或可归因的节省；启动执行、自动学习和节省分别记录。

## 环境与来源

| 项目 | 实际检查结果 |
| --- | --- |
| 试点 | 本项目 checkout，`main` 分支；保留原有未提交修改 |
| Lody | `0.112.0-nightly.6`；nightly 安装目录，版本取安装包 package.json |
| ACP | 随包 `acp-extension-codex 1.12.0`，入口为包内 `resources\cli\codex-acp.js`，通过 App Server 运行；适配器不是 Codex CLI |
| Lody Codex | `0.166.0`；位于 Lody 托管的运行时目录 |
| 终端 Codex | `0.169.0`；全局 npm 安装，与 Lody 运行时不同 |
| 配置环境 | 实际 Codex / ACP 的 `CODEX_HOME` 为独立 Codex 数据目录，`CODEX_PATH` 指向上述 0.166.0；`CODEX_CONFIG` 未设 |
| Python | 实际 Codex / ACP 进程环境的 PATH 解析到独立 Python 3.13.9，满足 3.10+；不是只检查终端或设置 TE_PYTHON |
| 源码 | 本地 TokenEconomizer checkout；官方 commit `fb853981ef439db813bf96ec42eaa82822962e1e`；保留 checkout，安装副本仍依赖它 |
| 安装副本 | 位于 `CODEX_HOME` 下的插件缓存目录 |

已核对版本、进程父子链、环境白名单、实际 `--help` 和安装包源码；未替换 Lody 自带运行时、改系统 PATH、角色绑定、provider 或账号。上游声明的 Codex 验证范围为 0.153.1–0.164.0，本机 0.166.0 需实际验收。

依据：[官方源码](https://github.com/microsoft/TokenEconomizer/tree/fb853981ef439db813bf96ec42eaa82822962e1e)、[安装说明](https://github.com/microsoft/TokenEconomizer/blob/fb853981ef439db813bf96ec42eaa82822962e1e/plugin/README.md)、[设计与限制](https://github.com/microsoft/TokenEconomizer/blob/fb853981ef439db813bf96ec42eaa82822962e1e/docs/PLUGIN.md)、[Lody 信任实现](https://github.com/LodyAI/acp-extension-codex/blob/f2a140b5a630454f3707c48d93ea162ecbad82e7/src/CodexHookTrust.ts)、[Codex hooks](https://learn.chatgpt.com/docs/hooks)。Lody 会话编排网页本次返回 403，实际接口以成功读取的命令专属 lody_help 为依据。上游根 LICENSE 为 MIT，插件/包元数据仍标 Apache-2.0；原文件保留，未代为改判。

## 配置、经验与角色边界

用户级 `config.toml`（位于 Codex 数据目录）的本轮注册目标增量为 `marketplaces.tokeneconomizer` 本地来源及 `plugins."tokeneconomizer@tokeneconomizer".enabled=true`。注册使用实际 0.166.0 的 `plugin marketplace add` / `plugin add`；完整非目标配置一致性的验证限制见下文。

项目策略实际位于 TokenEconomizer 状态根下按仓库身份分离的 `config.json`：

```text
toolprune=off
microskills=envfacts
loopguard=off
toolprune_protect=collaboration,goals,image_media
```

原保护名单为空，写回的是并集；先关闭裁剪，再执行 `toolprune restore collaboration goals image_media`，返回无已应用项。`codex\untool_applied.json` 的 by_path / kept 为空，项目 `.codex/config.toml` 仍不存在；没有强开用户原本禁用的功能。

未使用 `--global`。插件注册是用户级，其他项目可发现它；上述策略仅属于此仓库身份。当前源码的策略文件在 repo 层，按 host 分离的是学习/运行状态，不能误写为 `codex\config.json`。同一 origin 的 clone / worktree 共享 repo key，不能视作隔离。

状态根为 TokenEconomizer 私有状态目录，host 为 codex。六个原角色绑定同一 Agent Config 和项目；主角色、coder、tester 的运行环境已实际检查，未逐角色重复检查全部工具链。角色模型和职责各异，继续由既有角色说明管理。当前无已学经验或已知冲突，无必要修改 `TOKENECONOMIZER_HOME` 做隔离。

**TokenEconomizer 自动学习的经验仅作为操作参考。项目工作仍遵守宿主指令优先级、用户当前要求、适用的 AGENTS.md 和角色约束。自动经验不能成为跳过必读文档、架构约束、测试或视觉验收的理由。**

发现冲突时先对本项目 `config set microskills=off` 停止注入，用 `microskills show` 检查，再对对应 ID 执行 `microskills forget ID` 并记录原因；这些接口已核对本机帮助。不修改项目规则迁就经验，首轮不启用 distiller。

loopguard 继续关闭：其源码另起 `codex exec --json --disable hooks --ephemeral -s read-only`；实际进程 PATH 会解析到终端 0.169.0。它共享配置目录，沿用现有默认模型与自定义 provider 配置；认证状态只分类为 ChatGPT，未输出身份或凭据。尚未执行侧调用、未证明继承各 Lody 角色的模型/provider/权限边界，因此不能启用守卫。

## 实际验证与未验证项

| 检查 | 证据与边界 |
| --- | --- |
| CLI 安装/配置 | 0.166.0 的版本、帮助、注册、列表及安装副本 bootstrap 展示命令成功 |
| CLI 实际插件执行 | 同一 0.166.0 新持久 exec 会话，read-only、正常信任、既有配置与默认模型；会话退出码 0，读取和哈希命令均 0，会话与 TE startup session / pin 对应 |
| App Server hooks | 安装前 hooks/list 为空；安装后五项为 enabled / untrusted。用户正常信任后复查 SessionStart、UserPromptSubmit、PostToolUse、Stop、SessionEnd 均 enabled / trusted，无警告/错误 |
| Lody 实际插件执行 | 信任后新建 reviewer 会话，底层 acp-extension-codex / 0.166.0 的线程与真实任务、工作目录、时间和 TE session pin / startup 状态对应；证明 startup 已执行，不扩大为五类事件逐项执行均通过 |
| Lody 角色协作 | 主角色调用 planner/researcher/coder；安装后 tester 与 reviewer 的只读结果均已收回，主对话继续工作；创建、执行、追加任务、返回链路通过 |
| 根/局部规则 | reviewer 显式读根→feature→todo 并返回 taskId+revision 约束，主协调对照文件核实；不据此声称子目录规则自动注入 |
| 原生子代理 | 注册前、后及用户信任后各有一次只读创建、执行、返回；文件哈希命令退出码均为 0，根 AGENTS 的 SHA-256 在信任前后一致。主协调核对实际工具输出；此项只证明原生协作可用 |
| 工具 | 原会话有 40 个 Lody 工具、3 个 node_repl 工具及图像/目标工具；信任后新 reviewer 实际枚举仍有 40 个 Lody 工具，创建、查询、追加、历史四接口及 image_gen / create_goal 可见，主协调核对实际工具输出；未执行图像生成或目标写入 |
| 配置保留 | tester 独立复核旧 plugins/marketplaces/features/memories 快照与 tracked 文件哈希匹配；MCP 名称与数量仍与安装前一致；完整其余配置哈希未复现，不能声称所有 model/provider/MCP 值均逐项验证未变；角色配置未改 |
| 学习 | envfacts 与真实 startup hooks 已启用，尚无符合条件的经验或注入记录；后续折叠状态另按实际证据记录，不手工添加事实冒充学习 |
| 无关验证 | 未构建 APK、访问 CAS、运行校园业务、提交、推送或发布 |

信任前 tester 从固定 checkout 运行 CLI 模块（先读帮助），config show、toolprune show、status --explain、microskills 均退出码 0：0 sessions / 0 prompts / 0 injected facts，无 proposal / applied / learning snapshot。当时该会话没有对应 TokenEconomizer session pin，因此未找到可归因的 hooks 运行证据，不能据此判定 hooks 未运行或作为实际学习验收。

信任后 Lody reviewer 会话实际环境读取到 CODEX_THREAD_ID；底层元数据 originator=acp-extension-codex、工作目录为本项目。随后自动生成同 ID 的 TE startup session 和 pin；主协调核对真实任务文本对应、文档读取和 Get-FileHash 工具退出码 0。随后主角色追加工具枚举任务并收回结果，继续工作。该记录没有注入事实；未手工调用 hooks。Lody 隐藏 hook 通知，因此不据此声称每个事件均有独立完成回执。

实际 CLI 单次启动并正常结束；线程元数据为 codex_exec / exec / 0.166.0、工作目录为本项目。运行中自动产生同 ID 的 TE startup session / pin；两个命令退出码均 0，实际输出含预期 AGENTS 哈希。CLI JSON 没有逐 hook 回执，不把它解释为所有五类事件均已分别执行。本轮未覆盖终端默认的 0.169.0。

首次验收采集包装在启动 CLI 前发生语法错误，已停止该轮；coder 修复本机独立采集脚本、纠正状态路径并通过语法检查后，主协调只执行一次真实 CLI。原始 stdout / stderr 仅在内存中过滤，不写入证据文件；没有手工制造 hooks、经验或失败历史。

tester 已独立复核本次 CLI 的线程、命令结果和 startup 对应；其只读 status --explain 退出码 0，当前为 3 个已折叠会话、0 注入事实，尚无可用经验。主协调核对实际工具输出后记录结论。reviewer 对最终文档和证据的定向复核未发现范围内 P0–P3，前轮两处 P3 已闭环。两份文档的本地链接无缺失、空白问题为 0、git diff --check 通过，八份 AGENTS 无差异。

安装者记录的 `untouched_parsed_config_hash_matches=true` 缺少可复现算法；tester 和主协调未能独立复现。当前用户配置原字节哈希也已不同于安装记录；后续观察到配置写入晚于安装记录，尚不能定位差异或归因插件。保留当前配置，不为追求哈希一致覆盖期间修改。此处以独立核实的快照、模型名和 MCP 名称/数量为限；完整非目标配置一致性仍未验证。

用户已通过同一 0.166.0 CLI 的正常 `/hooks` 流程完成信任，主协调以实际 hooks/list 复核。没有手写 trusted_hash、使用 bypass 参数或把插件塞入 AGENTS。Lody 1.12.0 的信任列表仅保留 sessionFlags，并忽略 hook/started、hook/completed 展示；本轮以实际插件状态与会话对应验收。

终端默认 0.169.0 的模型会话未验证，也未替换为它；本轮 CLI 验收固定使用 Lody 的 0.166.0。无历史/未触发干预与安装失败分别报告，不编造会话、经验或失败历史。

## 修改、备份与回滚

项目仅新增本记录，并在 [Agent 手册](README.md) 增加一条链接。所有 AGENTS / override 位置和内容保留，不新增角色指令副本；原 docs/README、CHANGELOG、upc 文档及 design/research/promo 修改保留。安装配置由 coder 完成，文档由主协调汇总并经过独立只读审查；随后将 tester 的哈希复现限制补入本记录，不沿用安装者的完整一致性声明。

备份/证据目录位于仓库外。baseline 保存新增项的原值（不存在）、旧表快照、未涉及部分解析哈希和原不存在路径；安装后证据保存安装对比；信任前证据保存清单与原生协作检查；信任后证据保存清单、startup、工具输出核对及五个原 tracked 文件与四表快照的复查；补充证据覆盖不可复现的完整哈希一致性声明，不对后续差异作归因。原配置含敏感字段，**没有注册前的整份原字节副本**；这是定向原值备份，不是完整文件备份。未复制认证文件。

本机独立采集脚本用于实际 CLI 的过滤结果、底层元数据与 TE startup 对应。它们不属于业务源码，不修改插件代码、用户配置或信任记录。

其他策略下，插件裁剪能力可修改项目 `.codex/config.toml`、恢复记录及 `.git/info/exclude`；本轮没有生成项目 Codex 配置。学习/事件/index/launcher 写入上述状态根，不写 AGENTS。状态为本机私有数据，不提交未脱敏会话。

回滚如下，**本轮未执行**。先检查当前配置是否有后续修改并另存定向快照，保留正常信任和其他有效变化；不整份覆盖用户配置、不删除学习状态、checkout 或备份。

```powershell
$tePython = '<python 解释器>'
$teClone = '<TokenEconomizer checkout>'
$teProject = '<本项目 checkout>'
$teCodexExe = '<Lody 托管的 codex 可执行文件>'
$teBootstrap = Join-Path $teClone 'plugin\scripts\te_bootstrap.py'
# 当前 PowerShell 的 CODEX_HOME 应仍指向原 Codex 数据目录；不另设主目录。
& $tePython -X utf8 $teBootstrap cli --host codex --cwd $teProject config set microskills=off toolprune=off loopguard=off
& $tePython -X utf8 $teBootstrap cli --host codex --cwd $teProject toolprune restore
# 如需卸载，再执行同一运行时的正常移除命令。
& $teCodexExe plugin remove 'tokeneconomizer@tokeneconomizer'
& $teCodexExe plugin marketplace remove tokeneconomizer
```

默认保留 off 的项目策略，避免落回上游自动裁剪/守卫默认值。若必须还原本轮新增四键，先核对它们仍属于本轮且无其他安装在使用，再用 `config unset microskills toolprune loopguard toolprune_protect` 逐项撤销；不无条件删除整个 config。只按插件记录恢复，保留用户主动禁用项。

实读依据：根 AGENTS；[Agent 手册](README.md)、[兼容记录](agent-compatibility.md)、[审计报告](2026-11-16-audit-and-delivery.md)和 docs 索引；规则验收另读 feature/todo 两级 AGENTS、ARCHITECTURE 数据归属、ADR 003、2.0.3 待办验收相关章节；上游固定 commit 的配置、路径、Windows hooks、bootstrap、CLI、sidecall 与本机适配器源码。历史 Android 验收不算本轮测试。
