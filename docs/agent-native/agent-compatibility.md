# Agent 指令加载兼容记录

调查日期：2026-11-16。区分官方/安装包机制、CLI 可运行与本项目模型会话实际加载；三者不是同一证据。本轮没有为兼容验证另开模型会话、修改用户配置或运行校园接口。

| Agent/环境 | 已核实机制 | 本轮实际证据 | 未验证与项目用法 |
| --- | --- | --- | --- |
| Codex | 官方说明从项目根到工作目录构建指令链，每目录 `AGENTS.override.md` 优先于 `AGENTS.md`；默认项目指令累计上限 32 KiB | 本机 `codex-cli 0.169.0` 的 version/help 可运行；本会话根规则由用户消息提供并可见 | 不能据此证明宿主自动扫描；新局部规则的启动/读文件加载未实测。根入口保持精简，按受影响路径显式读取 |
| Claude Code | 官方当前说明 >=2.1.277 原生支持 AGENTS；若当前/祖先存在 CLAUDE.md 或 CLAUDE.local.md，默认优先该体系；嵌套按访问加载 | 本机 `2.1.321` 的 version/help 可运行 | 未启动本项目模型会话、未检查所有全局私有设置；本轮不复制规则到 CLAUDE.md。旧版/关闭原生发现时可显式读 AGENTS，必要兼容入口只写 `@AGENTS.md` 引用 |
| DSH | 本机官方 npm 包 `@deepseek-ai/dsh-agent-instructions` 的 README/package.json/lib/index.js 说明根→cwd 的 AGENTS/CLAUDE 链与 read/write/edit 触及子级时刷新；profile 可关闭 | 本机 `0.2.2-rc.1` version/help 和安装包源码已核对 | 未核实当前 profile 或模型注入；包仓库元数据指向 deepseek-ai/deepseek-harness，线上对应 README 此次返回 404，不能称线上验证。显式读取是兜底 |
| Lody 当前宿主 | `lody_session_create_options` 可返回当前环境/Agent 选项，不等于证明文件注入 | 严格先成功读取其完整 `lody_help`，再只读查询；返回 builtin/codex、本地 E:\UPCpocket、main | 根/嵌套文件的宿主注入行为未独立验证；不得以已能调度 Codex 推断嵌套必加载。使用同一手动读取协议 |

Codex 机制依据：[OpenAI 官方 AGENTS 指南](https://learn.chatgpt.com/docs/agent-configuration/agents-md)。Claude 机制依据：[Claude Code 官方 memory / AGENTS.md](https://code.claude.com/docs/en/memory#agentsmd)。版本和官方说明会变化；升级后先查本机版本/配置及官方资料，再更新本表，不能凭此永久保证行为。DSH 本轮依据为本机已安装官方包，未公开机器专属安装路径、配置或连接值。

## 共同加载协议

将仓库根设为项目目录，显式读取根 AGENTS；确定受影响文件后，从根到对应目录读取每份局部 AGENTS，再按任务矩阵读普通文档正文。跨模块逐条路径执行，技能另按触发描述显式读取。遇到 override、私有指令或宿主规则冲突时报告来源与作用域，不能悄悄忽略项目安全约束。

示例：修改 `feature/card/...` 需根、feature、feature/card 三份指令；修改 `core/account/...` 需根与 core；修改更新器需根与 app，再读更新机制及 ADR 004。不要把三个路径组的所有文档都无条件塞入每次上下文。

## 后续验证方法（本轮未执行）

如未来任务需要证明自动加载，可在明确授权的测试会话中，要求 Agent 先报告实际生效指令来源及作用域，再读目标模块文件，检查是否识别嵌套规则；对同目录 override/全局优先级应查实际设置。只确认“能读文件”不算“启动自动发现”，手动提示读到也不能冒称自动注入。结果记录版本、宿主、工作目录、操作和局限，不能含私有用户配置内容。

当前验收采用可用的显式读取机制；嵌套自动加载、其他 Agent 的本项目模型会话以及全部技能自动发现均明确未验证。单一 AGENTS 内容源可避免规则副本分叉，兼容入口应引用它，不复制规则全文。
