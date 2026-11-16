# 只读课表与今日课程

继承[根](../../AGENTS.md)与 [feature](../AGENTS.md)指令。先读 [M3](../../docs/m3/README.md)、[课表与组件设计](../../docs/m5/2026-11-05-课表与组件设计.md)、[当前界面设计](../../docs/design/2.0.6-ui-refresh.md)与[隐私](../../docs/PRIVACY.md)的课表章节。

## 核心文件

| 文件 | 作用 |
| --- | --- |
| [TimetablePlugin.kt](src/main/kotlin/com/upcpocket/app/timetable/TimetablePlugin.kt) | 插件、认证注入与页面贡献 |
| [TimetableService.kt](src/main/kotlin/com/upcpocket/app/timetable/TimetableService.kt) | 账号/业务代次、索引、按需周查询、缓存及今日状态 |
| [TimetableProtocol.kt](src/main/kotlin/com/upcpocket/app/timetable/TimetableProtocol.kt) | 解析、日期/周次/节次一致性校验 |
| [TimetableModels.kt](src/main/kotlin/com/upcpocket/app/timetable/TimetableModels.kt) | 学期、课程、周与 UI 状态 |
| [TimetableWidgetRenderer.kt](src/main/kotlin/com/upcpocket/app/timetable/TimetableWidgetRenderer.kt) | 今日课表 RemoteViews，系统调度在 app |

## 独有约束

- 本模块是独立只读能力，不通过 focus 的停用课表表读写；不增加编辑、导出或课程提醒。
- 账号使用受控 `accountReference`，支持非纯数字学校登录名；不强行套余额学号规则。会话、索引和周查询绑定账号、凭据代次、业务与选周代次，提交锁内再次核对后才写入/发布。
- 首入/刷新先核对学校当前学期/教学周，仅查需要的周；旧缓存 currentWeek 仅作离线历史，不代替学校现状。不遍历整学期、不轮询；用户明确选周在刷新后保留。
- 周网格按真实日期连续周日至周六，ISO weekday 与日期核对；单双周、非连续节次和重叠课程不能吞并。左轴按学校官方 1–12 节，13–14 节未知时显示“时间待确认”。
- 今日课程以 Asia/Shanghai 的实际今天派生，独立于正在查看的周；首页今日卡与组件使用同一今日状态，不能切到历史周就改成“今天”。
- 缓存只保存完整成功周，按内部账号/学年/学期/教学周归属；失败不覆盖上次成功缓存，成功空周、离线缓存、未查过和协议失败分别表达。
- 缺会话或明确失效只恢复一次，不循环密码提交；学校网页返回仍校验业务 Cookie、索引、周数据及本机账号/凭据/业务上下文。索引没有学校本人身份字段，本机 reference 绑定不等于学校返回身份比对。详情/登录弹窗随账号变更、阻断或选周失效及时撤下。
- 组件读本地快照，冷启动任务有界恢复本机账号；本地时刻边界 ticker 与 30 分钟兜底不联网，不触发 CAS。依 2026-11-16 用户新要求移除组件刷新键，保持 2×2/4×2 自适应与整卡导航；首次同步和未缓存周在应用内处理。视觉见[组件修订](../../docs/m5/2026-11-05-组件视觉修订.md)。

## 验证与联动

按改动选 `:feature:timetable:testDebugUnitTest --tests <相关测试类>`；认证影响再选 upc-auth，首页/组件影响再核对 app 定向测试与必要安装冒烟。纯日期/展示修复无需为了验收额外请求学校；确需 CAS 的验收按根指令使用本人真实账密。

联查 app 的 HomeScreen、WidgetRefreshWorker、WidgetUpdates 与 TimetableWidgetTicker；账号清理联查 core/account 与宿主 invalidator。同步 API、ARCHITECTURE、PRIVACY 与当前设计/证据，保留 M3 历史现场的日期和限制。
