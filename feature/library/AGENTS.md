# 图书馆：多套会话与座位协议

继承[根](../../AGENTS.md)与 [feature](../AGENTS.md)指令。先读[架构](../../ARCHITECTURE.md)的认证/写操作流程、[M4](../../docs/m4/README.md)、[当前馆区对齐记录](../../docs/releases/2.0.4-chaoxing-library-parity.md)及[时间优先设计](../../docs/design/2.0.3-library-time-search.md)。

## 入口与边界

| 文件 | 作用 |
| --- | --- |
| [LibraryPlugin.kt](src/main/kotlin/com/upcpocket/app/library/LibraryPlugin.kt) | 插件及认证/设置适配注入 |
| [LibraryService.kt](src/main/kotlin/com/upcpocket/app/library/LibraryService.kt) | libseat 会话、恢复与本人校验 |
| [ChaoxingService.kt](src/main/kotlin/com/upcpocket/app/library/ChaoxingService.kt) | 超星独立会话及提交入口 |
| [LibrarySeatRepository.kt](src/main/kotlin/com/upcpocket/app/data/library/LibrarySeatRepository.kt) | libseat 读取协议与查询 |
| [ChaoxingRepository.kt](src/main/kotlin/com/upcpocket/app/data/chaoxing/ChaoxingRepository.kt) | seat / seatengine 适配 |
| [ReservationAttemptLatch.kt](src/main/kotlin/com/upcpocket/app/data/library/ReservationAttemptLatch.kt) | 单次提交与未知结果防重 |
| [LibraryHiddenSeatsStore.kt](src/main/kotlin/com/upcpocket/app/data/prefs/LibraryHiddenSeatsStore.kt) | 本机按系统/馆区/房间/座位隔离的隐藏偏好 |

## 独有约束

- libseat 与超星 seat/seatengine 分别维护认证、时间 ID、占用查询和提交协议；不能拿一个系统的规则推断另一个。UPC 官方来源见[入口](../../docs/upc/官方入口.md)，六入口历史研究只是有日期的证据。
- 已保存学校账号时，自动/手动登录都校验学校返回的本人身份与保存账号、凭据代次；换号与清理前先递增业务代次，阻断迟到发布。Cookie 清理失败保持门闩；无已保存账号的手动登录允许仅内存会话，不做与保存账号的一致性比对、不持久化无归属会话。
- 按时段找与按座位选保留独立确认守卫；占用当前座位不自动否决未来时段。候选选中后使用学校原始时间 ID，最终预检核对房间、座号、时长、账号与会话。
- 分页按服务器 page/hasMore 与稳定 ID，部分失败保留同条件结果但不宣称完整总数；完整读取后才给完整数量。超星使用其实际完整占用协议，不套用 libseat 分页。
- 个人隐藏只影响本机展示，不上传、不改学校状态、不重排座位图；隐藏后清选，保留撤销与恢复。DataStore 单次 edit 原子变换，换号不误删此个人偏好。
- 预约/取消/签到均为写操作，必须在授权及对应用户确认范围内；原生预约一次发送，POST 后畸形/异常响应保持未知。超时/未知不自动重试或回官方页再提交；验证码分支才回已有官方网页流程。
- 防重与预检上下文绑定账号/会话/房间/查询代次；离页、重建 ViewModel 不得把已发送未知结果当作未提交。不得用只读查询成功代替真实预约验收。

## 验证与联动

按影响选 `:feature:library:testDebugUnitTest --tests <相关测试类>`、相关 Lint、模块边界。改认证同时核对 `core:upc-auth` 与宿主清理；改 UI 同步两种超星馆区和 libseat，但不机械跑无关极端矩阵。真实预约、取消、签到仍未完成项目开发侧验收，不能因 Mock 通过修改此结论。

涉及入口同步 M5 映射；数据/认证同步 PRIVACY、API 与 M4/当前研究记录；扩展馆区须有 UPC 来源和实际协议证据，架构变化更新 ARCHITECTURE/ADR。
