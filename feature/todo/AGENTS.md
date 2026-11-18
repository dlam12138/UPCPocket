# 本机待办与一次性提醒

继承[根](../../AGENTS.md)与 [feature](../AGENTS.md)指令。先读[架构](../../ARCHITECTURE.md)的数据归属、[ADR 003](../../docs/adr/003-data-ownership-and-schema-compatibility.md)与[待办/导航验收](../../docs/releases/2.0.3-todo-home-dock.md)。

## 核心文件

| 文件 | 作用 |
| --- | --- |
| [TodoService.kt](src/main/kotlin/com/upcpocket/app/todo/TodoService.kt) | TodoStore、串行写入、提醒重建与旧动作守卫 |
| [TodoDatabase.kt](src/main/kotlin/com/upcpocket/app/todo/data/TodoDatabase.kt) | 独立 Room `upcpocket-todo.db` v2；v1 显式无损增加可空截止 |
| [TodoReminderPlanner.kt](src/main/kotlin/com/upcpocket/app/todo/reminder/TodoReminderPlanner.kt) | 提醒时间/可用权限派生 |
| [TodoAlarmScheduler.kt](src/main/kotlin/com/upcpocket/app/todo/reminder/TodoAlarmScheduler.kt) | AlarmManager 单次调度 |
| [TodoIntents.kt](src/main/kotlin/com/upcpocket/app/todo/reminder/TodoIntents.kt) | 显式 PendingIntent 与身份 |
| [TodoAlarmReceiver.kt](src/main/kotlin/com/upcpocket/app/todo/TodoAlarmReceiver.kt) | 闹钟系统入口；同包其他 Receiver 处理动作与重排 |

## 独有约束

- Room 是待办事实来源，提醒/通知是可重建副作用；首页摘要与待办页共用 Repository Flow，不另存两套任务或计数。
- `dueAt` 为独立可空截止，不能由 `reminderAt` 推断；旧任务迁移后截止为空，旧字段与 revision 保留。设置截止不自动申请通知或精确闹钟权限。
- 本机待办与学校账号独立，换号/删号不删除任务、提醒或专注记录；不为此接入校园接口或账号权限。
- 编辑、完成、删除与稍后提醒要取消旧调度并更新持久化 revision；通知/闹钟动作以 taskId+revision 条件更新，不允许迟到动作完成或改写新版本任务。
- `reconcileAll()` 基于当前数据库和真实权限恢复提醒；不依赖常驻 Worker。普通进程退出、重启、包更新、授权变化后的行为按现有系统事件恢复。
- 通知和精确闹钟权限分开表达，未授权或系统限制不能显示“已准时提醒”；强停后需重新打开的边界保持真实说明，不自动扩大权限。
- 组件/通知入口使用当前包的显式组件，不因 namespace 仍为旧名而指向旧应用。打开任务只消费当前有效目标，不重复导航。
- 数据库结构、文件名与持久化 revision 具有兼容性；不得使用清库或破坏性迁移解决 schema 问题，方案变化须更新 ADR。

## 验证与联动

按影响选 `:feature:todo:testDebugUnitTest --tests <相关测试类>`；首页/深链改动核对 app 消费者。提醒调度/权限/Receiver 变化按需设备冒烟，聚焦正常添加/编辑/完成及本轮风险，不跑无关极端状态矩阵。涉及权限或 Intent 时按根矩阵读对应 Android 技能。

联查宿主首页与导航、Manifest 中 Receiver/权限、通知渠道与设置说明；不更改 focus 的库或课表缓存。同步 API、ARCHITECTURE/ADR、PRIVACY；使用变化更新 README，真实验证与未覆盖系统分支分开记录。
