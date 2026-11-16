# 业务模块通用入口

适用全部 `feature/`，继承[根指令](../AGENTS.md)。先读[架构](../ARCHITECTURE.md)的模块表、数据归属与相关流程，再读目标模块的源码和测试。

## 边界与局部入口

每个 feature 纵向拥有自己的模型、协议、Repository/Service、UI、存储及插件贡献。公开能力通过宿主装配；不能相互依赖实现、反向依赖 app 或直接读取 `core:account` 明文凭据。允许的认证适配依赖以实际 Gradle 为准。

| 模块 | 核心入口（相对本目录） | 独有规则/资料 |
| --- | --- | --- |
| `card` | [CardPlugin.kt](card/src/main/kotlin/com/upcpocket/app/card/CardPlugin.kt) | 必读 [card/AGENTS.md](card/AGENTS.md) |
| `library` | [LibraryPlugin.kt](library/src/main/kotlin/com/upcpocket/app/library/LibraryPlugin.kt) | 必读 [library/AGENTS.md](library/AGENTS.md) |
| `timetable` | [TimetablePlugin.kt](timetable/src/main/kotlin/com/upcpocket/app/timetable/TimetablePlugin.kt) | 必读 [timetable/AGENTS.md](timetable/AGENTS.md) |
| `todo` | [TodoPlugin.kt](todo/src/main/kotlin/com/upcpocket/app/todo/TodoPlugin.kt) | 必读 [todo/AGENTS.md](todo/AGENTS.md) |
| `power` | [PowerService.kt](power/src/main/kotlin/com/upcpocket/app/power/PowerService.kt) | 唐岛湾协议与电量组件；古镇口仍走学校页面，不能套用旧学校楼号规则 |
| `classroom` | [FreeClassroomRepository.kt](classroom/src/main/kotlin/com/upcpocket/app/data/classroom/FreeClassroomRepository.kt) | 两校区查询；失败/不完整不等于空闲；[2.0.6 查询设计](../docs/design/2.0.6-ui-refresh.md) |
| `focus` | [FocusPlugin.kt](focus/src/main/kotlin/com/upcpocket/app/focus/FocusPlugin.kt) | 本机计时/Room/服务/统计；数据修改读 [ADR 003](../docs/adr/003-data-ownership-and-schema-compatibility.md) |
| `shiguang` | [ShiguangService.kt](shiguang/src/main/kotlin/com/upcpocket/app/shiguang/ShiguangService.kt) | 只读活动/学分/本人记录；[登录回退证据](../docs/research/2026-11-14-login-verification.md) |
| `service-portal` | [ServicePortalService.kt](service-portal/src/main/kotlin/com/upcpocket/app/serviceportal/ServicePortalService.kt) | 只读指南/待处理/在办；[接入计划与边界](../docs/research/ezt-integration-plan-20261113.md) |

## 业务共同约束

- 插件构造只做内存装配，首次使用才建立数据资源。注册/页面预加载不能自动登录或遍历全部数据；遵守活动页门控。
- 新功能同时核对 PluginDescriptor、能力、UI entry/route、宿主注入及缺插件行为。用户可见入口位置由宿主决定，不写进协议层。
- 个人结果绑定账号与凭据/业务/查询代次；返回、写缓存和发布时复核。失败、无缓存、缓存、完整有效空结果分别表达；不能吞异常后给空列表当成功。
- 学校业务会话归对应模块；本机待办、专注、个人隐藏座位、主题、寝室配置不随学校换号误删。清理接口需可重复执行且拒绝迟到写回。
- `classroom` 保留完整查询键缓存、请求取消及 `d.js` 空闲交集语义；校区/日期/预设自动查，自选手动查，卓越 A/B 是本地结果过滤，不能额外发请求。
- `focus` 的 [AppDatabase.kt](focus/src/main/kotlin/com/upcpocket/app/data/db/AppDatabase.kt) 保持 `upcpocket.db` v1 及停用的旧课表表结构。新课表属于 timetable，不能借整理代码删除兼容表、改文件名或破坏既有专注历史。
- 石光/e站通只读范围不能因已能登录而扩大。网页验证只走已核对的学校入口，返回后仍需业务校验；不把页面加载成功当本人数据可用。

## 验证与文档联动

按影响选择 `:feature:<模块>:testDebugUnitTest`、相关 Lint 与 `checkModuleBoundaries`；宿主消费者另选 app 定向测试。普通 UI 文案/文档修改不新增镜像实现的测试，不跑无关全套或极端矩阵。

系统 Service/通知/组件行为变更按需做安装冒烟，真实校园读取与本机夹具分别报告。更新模块职责/能力时同步 ARCHITECTURE/API；使用入口同步 README 与 M5 映射；数据处理同步 PRIVACY；相关证据归 `docs/releases/` 或 `docs/research/`，不要写成新的永久指令。
