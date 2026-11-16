# ADR 003：数据归属与持久化兼容

- 状态：Accepted（追溯既有实现）
- 记录日期：2026-11-16
- 依据：当前 Room 定义、各业务私有存储、M0–M4 与待办实施记录。

## 背景

工程从旧版演进，既有本机专注数据与旧 schema 需要保留；新课表、学校会话、余额、充值待查与本机待办拥有不同归属、生命周期和敏感程度。模块拆分不能变成删库或学校换号误删本机记录。

## 决策

业务模型与存储归所属 feature，platform 仅给基础加密/诊断等能力。focus 保持 Room `upcpocket.db` v1，包括停用课表兼容表结构；这些表没有当前课表业务读写路径。新只读课表属于 timetable，用独立账号绑定会话/周缓存，不回写旧表。

todo 使用独立 `upcpocket-todo.db` v1；Room 任务和 revision 是事实来源，Alarm/通知是可重建副作用，旧动作按 taskId+revision 拒绝。学校账号换号/删除不清专注、待办、个人隐藏、主题或寝室配置。

学校凭据与需要持久化的业务会话/充值待查使用各自独立加密边界；余额和课表缓存当前为应用私有 noBackup JSON，未单独加密，不宣称所有数据均密文。流水列表为内存。个人缓存带归属/代次，只有完整成功结果写入，失败不冒充有效空数据。

文件名、schema/version、实体兼容、Keystore alias、DataStore 文件/键与已持久化系统标识均为兼容面。不得以清库、改名或重复打开同一 DataStore 文件绕开迁移。

## 备选与理由

本次对照分析：所有数据共用数据库会混淆清理范围；删除停用表可让源码更短但改变 Room 校验值；迁回旧课表表会混合历史负担与新只读模型；只靠内存通知状态无法恢复任务。按所有权隔离、保留旧 schema 和可重建提醒更适合现有用户数据。

## 影响与代价

需维护少量停用实体和多种存储形式，无法用一次全量清理替代业务失效。引入格式变化前须设计兼容/恢复并说明影响；备份边界和加密能力应与实际代码一致，不能用统一宣传语替代逐项事实。

## 证据与验证

[focus AppDatabase](../../feature/focus/src/main/kotlin/com/upcpocket/app/data/db/AppDatabase.kt)、[LegacyScheduleTables](../../feature/focus/src/main/kotlin/com/upcpocket/app/data/db/LegacyScheduleTables.kt)、[TodoDatabase](../../feature/todo/src/main/kotlin/com/upcpocket/app/todo/data/TodoDatabase.kt)、[TodoService](../../feature/todo/src/main/kotlin/com/upcpocket/app/todo/TodoService.kt)、[M2](../m2/README.md)、[PRIVACY](../PRIVACY.md)。验证按实际结构变更选择既有 schema/Repository 测试与必要保留数据安装；本轮只建文档，不迁移或清数据。
