# 更新与验证

## 2.0.1 · 课表网页登录空白修复（本地构建，未发布）· 2026-11-05

- 真实用户反馈：课表自动登录触发验证码后打开网页登录，WebView 像此前图书馆登录一样没有正常加载。排查确认不是 tab/Insets/遮罩，而是 Compose `AndroidView` 内 WebView 缺显式 `MATCH_PARENT`：修复前 `layoutParams=-2×-2`、CAS `body=0`、移动登录 iframe `0×0`，用户名顶部 `-124`；补齐后 iframe `412×839`、用户名/密码/`LOGIN` 均在视口内。
- 修复：显式 `MATCH_PARENT`、首次布局后再导航；新增课表专用 HTTPS host/path/端口策略（CAS `/cas/`，课表 `/a_upc/api/cas/login`、`/site/weekschedule/`、`/timetable/wap/default/`，不含 Chaoxing 域）；主框架非法跳转显示可恢复错误，SSL/HTTP/网络错误有明确提示；渲染进程退出可重建，旧代次回调按账号/代次/epoch 丢弃；只保留 4dp 顶部进度条，错误页可“重新加载”。未照搬图书馆的 CAS iframe JS 注入。
- 合成回归：`feature:timetable` 100 tests（新增 8 URL/目标码策略 + 2 挑战地址服务测试），全仓 482 tests / 0 failures / 1 skipped；`:app:lintDebug` 0 errors / 7 既有 warnings；`checkModuleBoundaries` 通过；`:app:assembleRelease` 成功。
- API 36 `upcpocket_api36`（`emulator-5556`）`install -r` 覆盖保留数据：CAS 空表单可见（Release 蓝色 `LOGIN` 按钮 `y=1403–1406`）；深色、1.5× 字体、键盘、断网错误→恢复网络重载、返回均实际检查。未故意触发真实验证码，不声称验证码流程成功；真实账号仅完成一次正常 CAS/只读课表加载，未预约/取消/签到。
- 本机诊断：关于页新增“导出诊断报告/清除诊断记录”和记录开关；私有 noBackup 目录滚动 24h/约 1 MiB 的固定枚举事件，用户主动生成 `summary.txt`+`events.jsonl` ZIP 后经系统分享面板发出；FileProvider 仅开放专用导出子目录，临时只读授权，不自动上传。事件不含账密/Cookie/URL/请求响应/课程或房间内容；换号/删号先清空诊断，诊断失败不影响业务。详见 [隐私说明](PRIVACY.md)。
- 合成回归新增诊断 schema/拒收/编码、TTL/容量/并发清理/坏文件/FileProvider 范围/分享 Intent 与课表目标码测试；`feature:classroom` 实机公开查询、`feature:library` 实机断网 WebView 错误均产生固定码报告。
- 精简包：`release/UPC-Pocket-2.0.1-diagnostics.apk`（构建产物 `app/build/outputs/apk/release/app-release.apk`），**4,675,365** bytes，SHA-256 `E94DF3AF40721BB0EEFC792F0AEFC9124D4501B2C8A63B3E28ED6A29CAD49022`；版本 `2.0.1`（21），证书 SHA-256 `07aaae87a235e3e1e99c364164a0a0774e27d8cbfd4bd68f32c03b31f1d06281`，只能标为“压缩 Release 构建、开发签名”。未改 v2.0 tag/附件，未 push/merge/publish。详见 [2.0.1 修复记录](releases/2.0.1.md)。

## 2.0 正式版 · 2026-11-05

- 用户验收通过，合并 M0–M5 到 main；版本 2.0（versionCode 20）。
- 包含统一学校账号、本人余额绑定、课表、图书馆座位认证与预约、空闲教室语义修复、三 tab 与三个简洁桌面组件。
- 默认签名 R8 精简 Release，沿用兼容开发证书；删除本地过时预览 APK，保留研究资料与有用途的 debug 测试工具。
- 详细范围、验证与已知限制见 [2.0 发布记录](releases/2.0.md)。以下 M0–M5 条目保留开发时的历史现场。

## M5.9 · 全应用文案精简与压缩 Release（开发分支，未发布）

- 在 `490914c` 上只改用户可见文案与少量无信息重复布局，不改组件视觉、认证协议、数据字段/账号隔离、后台更新和原生预约/状态守卫。
- 首页删除问候后半句、页脚长免责声明与服务卡泛化小字；校园卡改为“余额 / 已绑定 / 上次读取 / 更新时间”，删除常驻“查询目标/学号/读取于”重复。
- 课表首连、网络/失效、空周、今日未查询、课程详情与说明全部收短；删除“原始时间字段/单双周源信息/只读”等实现或自证文字，保留 13–14 节“时间待确认”、缓存/离线/失败区分和课表说明中的作息来源。
- 空闲教室页脚收为“绿色：所选节次均空闲。仅反映学校排课；临时占用或调课以现场为准。”；删除数据来源/只读/不提供预约的重复说明；查询按钮不再在出错时重复显示第二个“重试”。
- 电费入口删除两校区系统差异和证书细节，只保留“唐岛湾 楼号 + 房间号查询 / 查询电量”“古镇口 在学校页面查询 / 打开学校页面”；唐岛湾页删除流程教学与“不提供充值”。
- 图书馆入口删除 libseat/Chaoxing/原生等实现名，统一为唐岛湾/古镇口/求是 + 功能短句；登录页删除受控账号/密码不进入插件的自证；libseat 时间与预约说明收短。
- 超星预约保留“提交后会占用座位；超时或结果未知先到学校记录核对，不要连续重试”；删除“真实提交/官方预约记录/不签到”等重复；`原生预约` 改为“预约”，结果未知按钮改为“先查看学校记录”。
- 学校账号删除“凭据版本/整串识别/AES-GCM/FLAG_SECURE”等实现描述，保留“已保存，尚未验证”、余额学号、清理未完成门闩和删除后果；保存按钮从“加密保存到本机/保存新密码”统一为“保存”。
- 外观删除整段 Material You/种子色/算法说明；组件设置删除快照/M2/合成值自述，尺寸标签去重；关于页把长验证边界收成两条“使用边界”，技术事实仍在关于/数据说明/课表说明。
- 保留：真实离线/缓存/未验证/失败/验证码/清理未完成/未知提交状态，带后果的删除与预约确认，单位/日期/时间，课表 13–14 节限制，空闲教室只反映排课的限制，以及“超星结果未知先核对、勿连续重试”。
- 验证：8 个模块 **412 tests / 0 failures（1 skipped）**；`:app:lintDebug` **0 errors / 7 warnings**（均为既有 manifest/mipmap 与 debug-only 预览 warning）；`checkModuleBoundaries` 通过；`:app:assembleRelease` 成功。`emulator-5556` 覆盖安装遍历主要页面，断网检查错误态，1.0/1.5 字体无新增空白卡/按钮消失。
- 精简包：`release/UPC-Pocket-M5.9-copy-compact-release-debug-signed.apk` **4,625,793** bytes（上一版 4,642,177），SHA-256 `610E458C745A6800280D1E943F77C301995656EA6E9A2E93B7A1CB37202E124C`；版本 `0.2.7-lightly-preview`（19），证书仍为 debug keystore SHA-256 `07aaae87a235e3e1e99c364164a0a0774e27d8cbfd4bd68f32c03b31f1d06281`，只能标为“压缩 Release 构建、开发签名”。release manifest/APK 未发现 Synthetic provider/WidgetPreviewActivity。
- 未执行真实学校登录、查询、预约、取消或签到；未卸载/`pm clear`/改签名；release 课表真实数据分支未单独复测。详见 [文案精简验收](m5/2026-11-05-文案精简.md)。

## M5.8 · 三组件 Apple 式视觉、自适应与精简 Release（开发分支，未发布）

- 三组件统一为近实底浅白 `#F7F7F9` / 深炭 `#1C1C1E`、24dp 圆角、无 stroke；删除旧玻璃底、渐变描边、可见刷新圆盘和重阴影。刷新改为 20dp 细线 `rotate-cw` + 44×44dp 透明热区 + 轻 ripple；图标不吞整卡点击。
- 今日课表：标题 13sp、日期/状态 11sp、主课 16–17sp 最多两行、时间/地点 12–12.5sp 各一行、次课 11.5sp、页脚 10sp。色条从主课左侧改为日期行前 2×12dp 课程 tick，并在 dense 时隐藏，160dp 宽、1.0× 时也能完整显示 17 字主课。2×2 高 234dp 显示一条次课（只给开始时间），160–180dp 短卡保持单主课；4×2 显示完整主课/次课/更新时间。删除常驻“点按打开课表”。
- 校园卡：删除学号/账号常驻行，保留余额大数字 + 小“元” + 一行更新时间；真实空态/换号撤旧/失败保留旧值不变。电量：楼栋次文、房间主文、kWh 大数字；`kWh` 比“元”宽，长数值更早收缩。两组件标题 `paddingEnd=28dp`，header `wrap_content + minHeight=30dp`，1.5× 字体不再上下裁切。
- 删除课表页整行“周日至周六 · 上海时区”；删除首页“学校账号已保存，尚未验证学校密码；余额查询成功不代表 CAS 登录成功。”。真实“已保存，尚未验证”仍只在设置页展示。
- `TimetableWidgetLayout.forSize` 统一尺寸决策：dense/compact、日期、地点、次课、页脚各有阈值；160/172/180/234/360dp、1.0/1.5× 已用 `WidgetPreviewActivity`（debug-only）逐项测量行数、省略号、bounds 与上下裁切。完整矩阵见 [组件视觉修订](m5/2026-11-05-组件视觉修订.md)。
- 精简包：`app-release.apk` **4,642,177** bytes（debug `67,465,995`），SHA-256 `FE61368B88127FE092838F7D3A1601B8E8A60A60A10312DD9703DC57C44D3B3B`。R8 + resource shrink 沿用既有配置；universal ABI；无 `keystore.properties`，使用标准 debug keystore，证书 SHA-256 `07aaae87a235e3e1e99c364164a0a0774e27d8cbfd4bd68f32c03b31f1d06281`，只能标为“压缩 Release 构建、开发签名”。release manifest 无 synthetic provider/WidgetPreviewActivity；`adb install -r` 覆盖安装保留数据。
- 验证：`core:designsystem` 6、`feature:card` 53、`feature:power` 27、`feature:timetable` 90、`app` 36，合计 **212 tests / 0 failures**（debug）。Release 模拟器：三组件正常渲染；关网后 `am kill` + 点校园卡刷新，进程重建、`WidgetRefreshWorker` SUCCESS、旧缓存余额保留，页脚 `更新失败 · HH:mm`；整卡点击分别打开首页/课表 tab；设置页仍显示“已保存，尚未验证”。未执行真实学校登录/查询/预约/签到；release 课程数据分支由 debug fixture 覆盖，MIUI 网格未直接复测。
- 截图：`docs/m5/screenshots/80-85`（合成预览），README 组合图 `docs/images/widgets-light.png`；修订前参考 71/72。未 push/merge/publish。

## M5.7 · 课表周日起点/官方作息/今日组件、余额冷启动刷新与超星原生提交（开发分支，未发布）

> 历史记录：本节组件“玻璃底/48dp 圆盘刷新/67MB debug 包”已被上方 M5.8 取代，保留用于追溯。

- 课表周网格改为真实日期连续周日至周六；UPC 学期从周日起，源周顺序直接保留，不再按 ISO weekday 把周日排到最右。若源周不是周日起，按真实日期构造视窗并标出未覆盖日期，不旋转/重标课程。今日课程移到网格之前，查看其他教学周不再清空实际今天的快照；今日日期固定 `Asia/Shanghai`。
- 左轴 48dp，按学校官方 1–12 节显示起止时间（响应 `course_time` 优先）；13–14 节无官方时刻显示“时间待确认”，不推算、不新增第 15 节。页面顺序固定为页头 → 单行学期/周次 → 今日 → 周网格，连接成功条和大段只读说明移除，说明/作息来源/网页登录收进信息按钮。
- 新增今日课表 RemoteViews：默认 2×2，可拉宽 4×2；复用玻璃底/圆角/内边距/课程色槽；整卡进课表，右上 48dp 刷新独立 PendingIntent；无账号/恢复中/清理中/未同步/非教学周/今天无课/缓存日期不符分别显示，未知不显示成“无课”。只读账号归属的当前周缓存，不新建整份桌面快照。
- 余额/课表组件冷启动：`hasTargetNow` 不再在 LOADING 时丢弃点击；Worker 有界等待 `account.restore()`，CARD 冷启动 token 为空时仍由服务内部绑定/代次门闩保护。API 36 模拟器 `emulator-5556` 用户 0 实测：添加余额 2×2 → `am force-stop` → 不打开 Activity 直接点刷新 → 进程重建、Worker `SUCCESS`、组件更新为一次真实查询并显示时间；未使用 `pm clear`/卸载。
- 超星 seat/seatengine：从当前官方选座页脚本核对到 `data/apps/{seat,seatengine}/submit` 与页面级 `submit_enc`；`enc` 按官方 `submitVerify` 的 `[key=value]…[submit_enc]` 小写 MD5 形状生成，seat 含 `deptIdEnc`、seatengine 含 `wfwEngineEnc`。原生 UI 增加时段/座位选择、确认对话框、成功/拒绝/需验证/未知状态；已用未知或已用座位不能确认；只提交一次，超时/无法解析提示核对记录而不重试；验证码/风控只回官方页。`ChaoxingService` 还会在会话内按用户+房间+日期+时段+座位记住 `Unknown`，关闭/重进详情或重建 ViewModel 后同一请求不再发 GET/POST，退出会话才清空。取消/签到未实现。
- 今日课表视觉验收使用 **debug-only** `SyntheticTimetableWidgetProvider` / `SyntheticTimetableWideWidgetProvider`（`app/src/debug/`），不读账号/Service/缓存；release manifest 已确认不含 Synthetic 组件。真实 Launcher 已验证 2×2/4×2 的上课中、下一堂、今日已结束、长名称/地点、次课、浅深色和 1.5× 字体；2×2 当前场景同时显示主课和次课，48dp 刷新按钮不挤占标题；窄 2×2 在 1.5× 隐藏日期，4×2 保留日期。截图见验收记录 71–79。
- 新增本地 `TimetableWidgetTicker` + `TimetableWidgetTickWorker`：只重绘缓存，不联网；先从本机恢复账号绑定；按当前课结束/下一课开始/上海午夜安排唯一一次性 WorkManager。实测 `dumpsys jobscheduler` 有 `TimetableWidgetTickWorker`（无数据时延迟到午夜），`am kill` 后 job 仍在；`am force-stop` 会移除系统任务，Doze/厂商调度也可能延后，因此不承诺分钟级自动刷新。
- 验证：`feature:timetable` **87 tests / 0 failures**（含周日起点、周一边界不重标、跨月/学期、官方 12 节、13–14 待确认、组件状态、今日跨周保留）；`feature:library` **114 tests / 0 failures**（含 5 个原生提交协议合成测试 + 1 个服务层 Unknown 防重，均 fake transport，无真实写）；`app` **36 tests / 0 failures**（含 4 个本地边界调度测试）。`checkModuleBoundaries`、`:app:assembleDebug` 通过；timetable/library lint 0 errors/0 warnings，app lint 0 errors/2 个既有 warnings。
- 模拟器：真实今日课表 2×2 添加、拖动右边框到 4×2、整卡点击打开课表 tab、刷新启动 Worker；因本机用户 0 的课表 CAS 要求交互，真实组件保持“未同步”，另有 debug 合成 Launcher 实例完成 71–79 视觉验收。未执行真实预约/取消/签到，未恢复研讨间，未 push/merge/publish。
- APK：`release/UPC-Pocket-M5-timetable-widget-reservation-debug.apk`，`67,466,009` bytes，SHA-256 `5F923DA1DC17FED0FAD4B65FF7B4B524C3D7421E2EC71DBA40D05B95FF0CB4D8`。

## M5.6 · 空闲教室假空闲与超星保存学校账号自动登录（开发分支，未发布）

- 空闲教室假空闲根因是字段语义反转：学校 `search-all` 的 `d.js` 是**空闲教室列表**（多节为“每一节都空闲”的交集），`d.all` 是全部目录；旧代码把 `d.js` 当占用列表，B328 第 1–2 节有课、第 3–5 节空闲，查询 1–5 节时未命中 `js` 反而显示绿色“空闲”。现改为 `available = code in js`，未命中/空列表/缺字段/未知码/解析失败/网络失败一律不默认空闲；默认日期固定 `Asia/Shanghai`，筛选变化取消在途请求并递增代次，只展示完整查询键相同的缓存与上海时区读取时间。
- 交叉核对：2026-11-05 10:54 当前同一天/同校区/同楼/同节次响应 `js=89`、`all=179`、B328 不在 `js`；App UI 显示“空闲 89 / 全部 179”，B328“占用”、B341“空闲”。这是当前响应，不冒充 09:19 历史重现；09:21 官方截图不是同教室对照。合成回归覆盖混合时段、空/缺失 `js`、错误码、日期/校区/楼、1–13 边界、默认时区与迟到响应。
- 超星自动认证：新增 `UpcChaoxingAuthenticator`，复用保存的学校账密与公共 `UpcCasLogin`，走已确认的 `office → tyrzfw cas/init → cas.upc.edu.cn → tyrzfw cas/index → passport2-api casreceive → office` 链路；密码只提交给精确 CAS 地址，不提交给 passport2 手机号/超星号表单。逐跳校验 HTTPS host/path/端口、无 userinfo/fragment 与允许 query；只取 `office.chaoxing.com` 可用 Cookie 头，不持久化/不写日志；`userLoginInfo.sno` 本人核验后按 accountId/代次/epoch/revision/清理门闩发布，自动成功使旧 WebView entry 失效，失败不清理已有会话；验证码/二次验证回退受限 WebView。旧“必须独立学习通密码”的结论撤回。
- 验证：`feature:classroom` **26 tests**、`core:upc-auth` **27 tests**、`feature:library` **108 tests（1 skipped）**、`feature:timetable` **77 tests**、`app` **32 tests**，均 0 failures；`RealChaoxingAutoAuthTest` 在 API 36 模拟器独立用户内 **1 test / 5.343s / 0 failures（最终 APK）**（无 Cookie 注入，CAS→office、本人核验、seat/seatengine 只读；测后删除私有凭据/账号并移除临时用户）；App UI 已验证空闲教室 B328 占用与 Chaoxing 自动登录入口/验证码回退；用户 0 的 UI 自动登录成功未复现，不冒充。`checkModuleBoundaries`、`:app:assembleDebug`、classroom/upc-auth/library lint 通过；app lint 0 errors/2 既有 warnings。
- APK：`release/UPC-Pocket-M5-classroom-chaoxing-debug.apk`，`65,540,522` bytes，SHA-256 `159EFC4A137A50B1D6BDAC69AE64563C2CB91E3B1E8C30D4DFF4CBAA0872A277`。未 push/merge/publish；未执行真实预约/取消/签到；未恢复研讨间。

## M5.5 · 座位图浮点坐标、登录落点、课表整周/配色与首页顺序（开发分支，未发布）

- 超星座位图空白根因是真实 `getdrawseat` 返回浮点 `xleft/ytop/width/height`（如 `166.0/75.0`），旧 Int 解析失败后退化为 0，所有座位叠在左上角；改为 Float、旋转归一化、真实包围盒缩放，并保留可读下限/横滑。已用座位查询与座位图分开，失败时明确“已用未知/已用座位未取得”，不再用 0 个已用掩盖未取得。
- 超星登录后误拦的实际落点是 `passport2-api.chaoxing.com/api/v2/casreceive` 成功页 → `https://i.chaoxing.com/base`；精确放行 `i.chaoxing.com` 的 `/`、`/base`、`/base/`，仍强制 HTTPS/443/无 userinfo。到达落点且无 office 会话时只回跳一次座位入口，再走本人核验、账号代次/epoch/revision/清理门闩。
- 课表默认竖屏七天同屏：按可用宽度均分日列（最小 34dp，极窄才横滑），节高 52→34dp、星期头 46→34dp、周次 chips 改为下拉、今日课程移到网格后；保留重叠分栏、非连续节次源语义、课程详情，长名称/1.5× 字体用裁剪与省略避免重叠。
- 课程颜色由 `String.hashCode()%4` 改为账号 + 学期归属的持久注册表：20 色手工调色板、首次出现分配未占用槽位、只存身份摘要/槽位；同课跨日/周、冷启动、输入重排、新增课程保持，12 门示例课逐块采样 12 色，两两最小 CIE76 ΔE=18.1，浅/深对比度 ≥4.5:1；超过 20 门如实复用。
- 首页“校园卡”区块移到“常用服务”之后，余额查询/绑定/历史/清缓存/组件能力不变。
- 验证：`feature:library` **104 tests（1 skipped，真实回放）**、`feature:timetable` **77 tests**、`app` **32 tests**；`RealChaoxingAuthTest` 在 **API 36 模拟器进程内**通过（本人核验→seat 6 区域/48 座详情→已用座位→seatengine 21 区域，3.173s，未跳过）；`:app:assembleDebug`、三个模块 `lintDebug` 通过（app 0 errors/2 既有 warnings）。App 内完整 WebView 登录仍未重放，第 2 项按部分验证记录；未执行真实预约/取消/签到，未恢复研讨间。
- APK：`release/UPC-Pocket-M5-five-fixes-debug.apk`，`65,531,778` bytes，SHA-256 `AA1435165805B1B06840B5E905E8166931E66F245D9C3F1254CC47E57B15F4B3`。验收与截图见 [2026-11-05 验收记录](m5/2026-11-05-验收.md)。

## M5.4 · 座位预约时长选择回归（开发分支，未发布）

- 用户反馈“预约时间好像只能约一个小时”；排查不是学校接口被写死，而是 libseat 在拿到 `endTimes` 后自动把 `ends.firstOrNull()` 设为结束时间，首个选项通常是 1 小时；Chaoxing 只读查看则固定 `+60` 分钟，且只显示开始时间。
- libseat：开始时间变化后保留学校返回的全部结束选项，不再自动选第一个；未选结束不能确认；结束时间变化立即更新底栏、时长和确认参数；开始时间变化后清除旧结束并重新请求有效结束；切换座位/日期/楼层/区域清除旧时段。学校 `maxHour` 仅作为真实提示展示，不用它臆造上限，最终以 `endTimesForSeat` 返回为准。
- Chaoxing：只读查看不再固定 1 小时；按 `timeUnit` 生成开始/结束选项，结束不超过开放结束时间；开始变化重取可用结束和已用座位；结束变化按所选区间重取已用座位；仍只打开官方页，不新增原生写协议。
- 新增回归：30/60/90/120 分钟与数小时结束选项、超过 1 小时的学校选项、结束到关门时间、无效步长、切换座位/日期清空结束、maxHour 只展示不截断、Chaoxing 开始/结束选项和时长格式；不可用/暂离座位即使被旧状态选中也不能确认，结束必须属于当前学校返回列表。
- 预览与生产共用有效性规则：Debug 预览使用同一个 `UiState` 与 `canConfirmReservation()`，选中 A01 可用座位和同一组 08:00–10:00；不再出现 A03 暂离却被选/可确认，或开始结束高亮与摘要不一致。
- 验证：`feature:library` **99 tests（1 skipped，真实座位回放）**、`app` 32 tests、`core:upc-auth` 18 tests；`checkModuleBoundaries`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过；library lint 无问题、app lint 0 errors/2 既有 warnings。
- APK：`release/UPC-Pocket-M5-library-seat-pages-debug.apk`，`65,502,318` bytes，SHA-256 `D65C3AE1FB4826DA94C0380A78A192A5D77BCA3AE37B9A6407BF2E5E01C2EF76`。

## M5.3 · 图书馆三个座位页重做（开发分支，未发布）

- 只优化唐岛湾 libseat、古镇口 Chaoxing seat、求是 Chaoxing seatengine，不恢复研讨间、不新增认证/预约协议、不执行真实预约。
- libseat 区域页：馆区/日期/楼层集中为紧凑筛选，区域行展示名称、楼层、总数/余座、进度和“选座/查看”；请求自动刷新并防止迟到响应覆盖新筛选。
- libseat 座位页：真实 row/col 网格改为可横纵滚动的全页布局，加文字+颜色图例、选中座位、持续可见的日期/座位/时段底栏；选时段改为底部面板，确认仍是原有二次确认；返回区域保留筛选、楼层和列表滚动位置。
- Chaoxing 区域页：校区/日期条、开放时间、座位总数/余座/进度、当前非开放时段提示；座位页使用学校 x/y 真实坐标绘制可横纵滑动的楼层座位图，显示编号、已用/已选图例、时段面板和官方预约动作；只读，不伪装原生提交。
- 增加稳定纯状态测试：楼层筛选、关闭座位页保留馆区/日期/楼层/列表、关闭 Chaoxing 详情保留校区/日期；区域统计解析测试。
- 新增 Debug-only 座位视觉预览 Activity，明确标注合成内容；用于浅色/深色/1.5× 截图，不进入 release，不创建账号/会话/缓存或网络请求。
- 验证：`feature:library` **88 tests（1 skipped，真实座位回放）**、`app` 32 tests、`core:upc-auth` 18 tests；`checkModuleBoundaries`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过；library lint 无问题、app lint 0 errors/2 既有 warnings。
- M5.3 的本地 APK 已被 M5.4 替换，不再作为现行交付物。

## M5.2 · 图书馆研讨间/空间预约撤下（开发分支，未发布）

- 用户 2026-11-04 明确决定：研讨间研究文档保留，相关产品功能直接删除。范围包括唐岛湾东馆 nspace、古镇口东区 reservepc、求是 reservepc。
- 已删除 nspace 的协议/模型/Repository/Service/认证 provider/WebView 回退/表单/本人预约/取消/队伍与 UI；删除 reservepc 的模型/解析/Repository/Service/租户上下文桥接/页面；删除 `core:upc-auth` 的 nspace 认证实现与测试、`LibraryPlugin` 的 nspace 能力/装配、`AppGraph` 的 nspace 失效器与认证适配。
- 图书馆统一页只保留三个座位入口：唐岛湾 libseat、古镇口 Chaoxing seat、求是 Chaoxing seatengine。宿主首页/设置/官方网页目录不再提供研讨间专属可点入口；旧研讨间路由仅作为兼容落点打开图书馆首页，不注册房间/预约接口。
- 保留 libseat 座位查询/预约/取消、Chaoxing seat/seatengine 原生只读、Chaoxing WebView 登录/身份核验/清理门闩/退出并发修复，以及 CAS 课表/账号清理。
- 研究资料保留：`docs/research/图书馆与空间预约六入口接口研究-20261103.md`、`docs/m5/图书馆六入口.md`（加归档说明）、`E:\UPC-MCP\docs\LIBRARY_M5_RESEARCH.md` 不删除；原始 HAR 未改。旧 nspace/reservepc 专属测试随功能删除，座位/CAS/清理测试保留。
- 验证：`core:upc-auth` 18 tests、`feature:library` 85 tests（1 skipped，座位真实回放）、`app` 32 tests；删除范围为测试数下降原因。未执行真实预约/取消/签到，未 `pm clear`。
- 当时 APK（历史文件，已删除）：`release/UPC-Pocket-M5-library-seats-only-debug.apk`，`64,426,840` bytes，SHA-256 `5CCEF1C1DADC409DD7C30FA71EE34A3FAD86EDC5BA8C20E54BFB3A83CD447F21`。

## M5.1 · 图书馆统一入口与六入口审计（历史记录，2026-11-04 撤下研讨间）

- 首页原“图书馆座位”改为统一“图书馆”入口，按唐岛湾/古镇口/求是分组展示六个平台；保留既有 `library.seat` 页面，新增 `library.home` 和 `library.nspace`，不改变三 tab 与设置入口。
- 新增独立 nspace 认证：libspace 的 nspace SSO 入口 → 正常 CAS → app token（query/fragment）→ `/spa/static/public/api/remoteCasLogin` → `userInfo` 本人核验；入口 302 到 CAS 已实测。nspace token 只存内存，使用独立 `library.nspace` 能力和 `/spa` Repository，绝不与 libseat seat token 混用。
- 新增 nspace 原生只读：馆区/空间类型/开放日期/房间分页，严格解析 `status/code/data`、区分 401 与空 `pageList`；每个房间提供官方 `#/detail?id=...&selectDate=...` 预约深链（只带房间/日期，不带 token/uid/enc）；新增原生“我的预约”列表与确认式取消（`GET cancelBook/{id}`）；新增单人预约表单（详情/用途/时段/必填/最终真实确认），对多人、选座、附件或未知字段明确拒绝。未点击真实最终提交；`stopBook`/团队/签到未实现。
- 新增 nspace 官方 WebView 验证码回退：学校 CAS 401 验证码页转 `INTERACTION_REQUIRED`；应用内加载固定学校入口，只捕获 `/nspace` app token，经 `remoteCasLogin`、本人核验和账号代次/epoch/revision 门闩发布；换号/退出后的迟到回调不发布。token 不持久化、不与 libseat 混用。
- 修复空间类型灰置：HAR 的 4 个空间类型 `status` 均为 `0`，旧解析把 `0` 当禁用；公开页面/用户手机可切换。现按 `status=0` 可用、`status=1` 停用处理，并新增协议测试。nspace 页面文案去掉“原生只读/不提交新预约”等与现有表单矛盾的实现术语；A123 等多人/选座房间不再显示会失败的个人表单入口，明确引导官方组队。
- Chaoxing 原生只读（新增）：seat/seatengine 使用固定 `fidEnc/mappId[/seatId]`，类型化解析 room-list/room/info/seat-map/used-seat/used-times；新增 `ChaoxingService` 内存会话、受限官方 WebView 登录、`userLoginInfo.sno` 本人核验、账号代次/epoch/revision 门闩和 WebView Cookie 清理。seat 东区 6 区域（48/44/52/44/66/66 座，08:00–21:30，30 分钟）、seatengine 求是 21 区域（16–240 座，08:00–22:00，30 分钟）已在真实 Android 进程内只读验证；模拟器加载真实区域/座位图/已用人数。真实预约仍走官方页。
- reservepc 原生解析/UI 已实现，但稳定 URL 当前 302 到“未查询到应用”：列表要求学校业务目录页面渲染的 `reserveIdEnc`，不是用户票据；HAR 中的 `enc/code/state/wfwEnc` 不硬编码。无上下文时应用显示精确错误并回退官方页，不伪造 8/23 个房间。此前“稳定 URL 可打开列表”的浏览器结论被本轮真实请求纠正。
- 新增 `ChaoxingReserveContext` 内存桥接：`adoptReserveContext` 只接受固定 reservepc 来源、匹配租户、16 位上下文，绑定 accountId/代次/epoch/revision/来源路径；Repository 可直接用该上下文调用 `item/list/time`。用两个租户页面 `reserveIdEnc` + 当前 Cookie 的私有回放分别读到 8/23 项；尚未接入 WebView 自动采集，因为还没有当前可加载的学校/微信入口链接。
- Chaoxing 会话安全回归：WebView 打开时捕获 `entryId/epoch/revision/accountId/代次`，旧 onPageFinished 不能重绑定；`signOut`/`signOutForAccountChange` 进入单飞清理门闩，清理挂起期间拒绝新登录，旧清理完成后不会删除新会话；清理失败/超时保持 `FAILED` 并阻止登录，`retryCookieCleanup` 成功后才恢复。新增确定性挂起/并发/失败/旧回调测试。
- 修复 CAS 挑战误判：CAS 以 401/403 返回登录/验证码页时，不再直接报 `ACCESS_DENIED`，而是解析页面并返回 `INTERACTION_REQUIRED` + 学校交互 URL；无表单才拒绝。模拟器真实凭据尝试到达验证码挑战后停止，不绕过、不重复失败密码。
- 独立 Chrome 使用修正后的文件解析（第一行首个冒号后账号、第二行首个冒号后密码；保留值内冒号、不交换）正常登录一次成功，读取到 1 馆区/4 类型/3 日期和 A123 详情/表单（11/05、11/06 有时段；11/04 当天无可用时段）。未执行真实预约/取消/签到，未添加成员/团队。
- 统一页标签区分“原生/原生只读”；Chaoxing 四卡不再跳系统浏览器，而是进入原生只读页；seat/seatengine 已原生查询，reservepc 明确受租户上下文阻塞，不把入口页、HTTP 200 或浏览器列表当作业务成功。
- 账号门闩：nspace 登录与 libseat 一样绑定当前 accountId/凭据代次；换号/删号同时清理两面会话，迟到认证结果不发布；新增合成测试覆盖协议分页、401、本人不一致、代次变化和独立 token。
- 验证：`core:upc-auth` 28 tests、`feature:library` **129 tests（2 skipped，需显式 Cookie/租户上下文的真实回放）**、`app:testDebugUnitTest` 32 tests、`assembleDebug` 通过；`feature:library:lintDebug` 无问题、`app:lintDebug` 0 errors / 2 既有 warnings。`RealChaoxingAuthTest` 在 API 36 模拟器进程内通过（账号保存→本人核验→seat/engine 真实只读；reserve 返回租户上下文错误）；模拟器验收原生 seat/seatengine 区域/座位图/reserve 错误态（29–32 截图），测试进程只清理私有账号/Cookie，本轮未再 `pm clear`；未执行任何真实预约/取消/签到/团队。详见 [六入口记录](m5/图书馆六入口.md)。

## M5 · 首页/课表/专注与统一设置入口（开发分支，未发布）

- 顶级导航收束为 **首页 / 课表 / 专注**：课表从“更多 → 学习”提升为插件 tab（order 5），专注 tab 保留（order 10）；删除宿主“更多”页与旧学习统计入口。
- 首页改为日期问候、校园卡摘要和“常用服务”四宫格；账号、图书馆登录、桌面组件、官方网页、外观、关于统一收进首页右上角设置；校园网页同时保留首页快捷卡，学习统计同时保留专注顶栏/今日卡入口（同一路由，不是两套实现）。
- 新增原生设置页、桌面组件说明/可选系统 pin、学校官方网页外链目录；官方链接来自既有 `docs/upc/官方入口.md`，不预填账号、不代理登录。
- 视觉系统去掉半透明玻璃叠层/重阴影，统一 `surfaceContainerLow + 1dp outlineVariant + 20dp` 卡片、字号层级、20dp 页面边距和 filled/outlined 图标状态。
- 课表重做教学周头部、今日课程、7 天×14 节横向周网格、重叠分栏、当前日强调、在线/缓存/空周/未查询/失败标签和课程详情；缺失字段仍显示“未提供”，不推测量纲/钟点/校区。
- 校园卡卡片保留账号绑定、脱敏、历史值、失败、清除缓存和安全说明；安全细节改为可展开，不删除语义。账号页 `FLAG_SECURE` 与密码清空保留。
- 插件契约未新增字段；课表/专注改用既有 `PluginBottomNav`，`MORE_LEARNING` 保留枚举兼容但不再挂载。缺插件仍由宿主原子裁剪，无空 tab。
- 修复宿主底部导航与内层 Scaffold 重复消费底部 Insets；`adjustResize` 已加入 Manifest。
- 验收修正：初始专注截图此前在页面已滚动后截取，重拍 1.0×/1.3×/1.5× 初始进入；实测环顶、状态、开始按钮完整，不需裁掉文案。标签按钮从“图标 + ＋ 新建”改为单加号“图标 + 新建”。补测 360×640dp + 1.5× 可滚动。文档明确校园网页/统计存在快捷入口重复，并审计 `CampusScreen`（仅入口壳）与 `WeChatHandoff`（基线无调用点）删除不丢功能；稳定插件路由和组件身份不变。
- 验证：按项目基线 `testDebugUnitTest` **50 套件 / 357 tests，0 failures/errors/skipped**；JVM 16 tests 通过；`assembleDebug`、`lintDebug` 通过（0 errors / 2 warnings）；模拟器 Android 16 实测浅/深色、1.0×/1.3×/1.5× 字号和主要页面/返回。详见 [M5 记录](m5/README.md)。
- 限制：未重跑真实学校登录/只读查询，未做 release/R8、真机、其他 ROM 或第三方桌面 pin；截图中的课表为 Debug-only 明确标注的合成预览。

## M4 · 图书馆 libseat 统一认证（开发分支，未发布）

- 从 `:core:upc-auth` 课表认证中提取公共 `UpcCasLogin`：官方 CAS 表单、错误页、验证码/二次验证识别、service/callback 主机与路径、Cookie 作用域共用；课表 M3 认证回归保持通过。
- 新增 `UpcLibraryAuthenticator`：固定 libseat SSO 入口 → CAS service ticket → app token（query 或 `#/login?token=...` fragment）→ `/rest/ssoAuth` seat token → `/rest/v2/user` 本人校验；只接受官方 HTTPS 主机/路径，不跟随 OkHttp 自动重定向，密码只提交一次。
- `feature:library` 通过窄接口 `LibraryAccountAuthenticator` 接收核验后的 seat token/本人标识，不依赖 `:core:account`、不读取密码；公开 HMAC 签名由 `UpcLibrarySigning` 共用。新增 `SchoolAccountIdentityVerifier` 受控契约，只比较候选登录名，不暴露密码/通用解密。
- 手动 WebView 登录改由 Service 先兑换本人、再核验身份、最后统一提交；A 账号下登录 B 不会短暂发布或持久化。登录门新增“使用已保存学校账号登录”，验证码/二次验证时明确提示并保留学校官方 WebView 登录；没有保存账号时手动会话只保留内存。
- `SchoolSessionStore` 图书馆密文新增 accountId/凭据代次绑定；旧格式、跨账号、跨代次读取即删除。恢复时服务端返回的本人标识还要与副本一致。
- 自动认证/手动登录捕获登录上下文与会话修订；网络/身份核验在登录锁外，最后持久化、发布与修订递增在同一提交门闩内复核账号门闩、Repository 代次与当前账号引用。旧恢复/旧自动/旧手动失败只做条件清理，旧查询失效只能按当前 token 清理，迟到操作不能删除新登录。换号/删号/改密/清理失败沿用 M1 失效器并清理图书馆会话。
- 验证：**Android 50 套件/357 tests + JVM 16 tests**，均 0 failures/errors/skipped；`checkModuleBoundaries`、`assembleDebug`、`assembleDebugAndroidTest`、相关 lint 通过。`RealLibraryAuthTest` 在 `emulator-5554` 的历史现场（`c1299da`）曾以本人账号完成真实 CAS、只读 `filters`/`myReservations`；本次初审修正未重跑真实 CAS。详见 [M4 执行记录](m4/README.md) 与 [验收记录](m4/验收记录.md)。
- 限制：未做真机/release/R8、真实验证码/二次验证、长期 TTL；`nspace`/Chaoxing 四入口与所有座位写操作不在本轮范围。

## M3 · 真实课表认证、查询、展示与离线缓存（开发分支，未发布）

- 新增 `:core:upc-auth` 与 `:feature:timetable`：课表认证只由受控 CAS 适配使用统一账号凭据；页面/Repository 不接触密码或通用解密能力。新增 `SchoolAccountReferenceReader` 提供任意合法已保存账号的内部 accountId/代次，保留原学号读取面。
- “更多 → 课表”提供学期选择、教学周切换、周网格、页内今日课程、课程详情与主动刷新；入口只增加在“学习”分组，不改首页内容、底部导航或整体页面安排。
- 移植 UPC-MCP 固定提交的正常协议：`GET /timetable/wap/default/get-index`、`POST /timetable/wap/default/get-datatmp`（`type=2`）；严格校验学期、起止日期、周日起算、日期/ISO weekday、节次 1–14、课程周次、非连续节次与重叠课程，字段矛盾不静默纠偏。
- 认证：核对 CAS 登录页/表单 action/service/ticket 回调/主机与 HTTPS，密码只提交一次；区分错误密码、网络、验证码/二次验证、协议与账号变化；缺会话或明确 10013 最多恢复一次，不循环试密码；验证码走学校正常网页登录并绑定账号/业务代次。
- 会话：独立 Keystore alias `upcpocket.timetable-session.v1` 加密，绑定内部 accountId 与凭据代次；缓存只保存完整成功教学周，按 accountId + 学年/学期/教学周归属，未查询/失败/空周分开展示，离线只显示成功缓存。
- 并发与清理：所有认证、索引、周查询、会话保存与缓存发布捕获账号/凭据/业务/选周代次；换号/删号/清缓存/选周旧响应被拒绝，插件隔离时宿主仍清理个人文件；同号改密保留历史课表但清旧会话。
- 审阅修正：terms 为空时首次验证码/二次验证入口与正常网页登录可达；冷启动/刷新重取 index，更新 currentTerm/currentWeek，同时保留手选历史周；session/cache/UI 发布与账号失效共用 commitMutex，网络在锁外，文件/crypto 在 IO；账号切换立即撤下旧详情/旧登录弹窗。
- 验证：**353 tests，0 failures/errors/skipped**（含新增 83 项）；`checkModuleBoundaries`、`assembleDebug`、`assembleDebugAndroidTest`、全部相关 lint 通过。上述真实设备 CAS/单周读取/离线重开是 `e22e60d` 初次实现的历史记录，本次审阅修正未重跑线上认证。详见 [M3 执行记录](m3/README.md) 与 [验收记录](m3/验收记录.md)。
- 限制：release/R8、真机、其他 ROM/WebView、校园网特殊策略、设备级“清理中杀进程”未验证；WebView 回退读取的 Cookie 属性由系统接口简化，详见验收记录。

## M2 · 校园卡余额强关联（开发分支，未发布）

- 余额查询目标唯一来自当前统一账号的受控本人学号：只接受登录名整串 8–16 位数字，不截取、不猜测；账号未保存、恢复中、换号/删号清理中、密文不可用或学号无效时不联网。
- 首页取消独立可编辑学号，展示脱敏绑定、账号设置入口、查询/刷新和“仅清除余额缓存”；余额与过渡余额分别展示，失败保留同账号历史值和绝对时间并明确标注。
- 页面、2×2 RemoteViews 组件和 WorkManager Worker 共用 `CardBalanceService`；查询捕获 accountId、学号、凭据代次和业务代次，响应发布、落盘与组件刷新前再次校验，迟到的换号/改密/清缓存结果不能复活。
- 已绑定但无历史快照的组件也能直接刷新；换号/删号立即撤下旧值，不等新账号查询成功；同号改密保留历史余额，但旧请求被拒绝。
- 组件发布增加 ticket + 完整 revision（绑定/账号代次/业务代次/快照版本）门闩：Provider onUpdate/resize、requestCard、Worker finally 重绘统一走同一发布器，读取可挂起但旧读取不能在换号/删除/清缓存的新空态之后写回；`cached()`/`cachedBlocking()` 读后复核，迟到读取不回填旧内存。
- 余额缓存升级为带 accountId/学号/代次的 v2；旧 v1 未绑定不展示，仅同号且用户主动绑定时迁移并保留原时间，异号/损坏/未知归属隔离。FIRST_SAVE 同样核对旧快照。
- 新增迁移 marker 与清理门闩：迁移幂等、可中断恢复；清理失败时旧值立即隐藏，重启后继续隐藏并重试，不自动查询。
- `cardStudentId` 旧草稿只作为账号设置脱敏提示，不再作为数据源；账号绑定后删除，绝不自动建号。
- 保留既有 Provider/Worker/Service/PendingIntent FQCN、刷新 action、唯一任务名和组件实例；旧任务执行时重新读取当前绑定，任务参数不含学号/密码。
- 账号失效器清理余额时不反向读取账号读取面，避免账号锁与卡片锁顺序死锁；“仅清除余额缓存”不删除学校账号、图书馆会话、专注、电量或主题数据。

验证结果与限制见 [M2 执行记录](m2/README.md) 和 [M2 验收记录](m2/验收记录.md)。

## M1 · 统一学校账号基础（开发分支，未发布）

- 新增 `:core:account-api` 小型类型化账号读取/结果/失效契约，以及 `:core:account` Android 实现和设置页。
- “更多 → 学校账号”支持主动保存一个当前学校账号、更新密码和删除；默认不保存，保存成功固定显示“已保存，尚未验证”。
- 凭据使用 Android Keystore 独立 alias `upcpocket.school-account.v1` 的 AES-256-GCM、随机 IV 和版本化密文，写入 `noBackupFilesDir/school-account/`，不降级明文。
- 保存采用 pending + journal + state + 正式密文的可恢复提交协议；加密、写盘或清理失败保留旧可用版本或停留在固定清理状态。
- 同账号更新递增凭据代次并失效旧业务会话；换号/删号先落盘切换/清理状态，再清理图书馆会话、学校 Cookie 与校园卡个人缓存，可跨进程恢复。
- 删除不触碰专注历史、主题、公开教室与独立寝室配置；旧“记住学校登录”明确只是图书馆 token 偏好，不是保存密码的同意。
- 账号设置页使用 `FLAG_SECURE`、离页/保存清空密码输入、不进入 `rememberSaveable`/SavedStateHandle；页面对未接入业务给出真实说明。
- 审阅修正：WebView Cookie 按根/`/cas`/`/libseat/`/`/remote/...` 与 `.upc.edu.cn` 域作用域清理并确认；图书馆登录上下文绑定打开时账号代次，清理中/失败重试时 Repository 与 UI 同步阻断；journal 增加已提交 tombstone，收尾删文件失败不重放换号/删号失效，且显式删除 base/`.bak`/`.new`。
- M2 余额绑定、M3 课表认证与 M4 图书馆认证见上方各节；M1 本阶段保存时尚未发送账密，之后只由课表/图书馆认证适配在需要时使用。仍不把本机保存状态标成学校已认证或全部业务已连接。

验证结果与限制见 [M1 执行记录](m1/README.md)。

## v0.2.7 · Lightly 封板

- 校园卡余额、唐岛湾剩余电量提供默认 **2×2** 桌面小组件。
- 半透明渐变、细描边与紧凑布局，保留 48dp 刷新按钮、账号/房间、主数值与绝对查询时间。
- 修复深浅色切换后的次要文字颜色；大字号优先保留寝室号与主数值。
- 组件仅点击刷新，无周期轮询；失败保留最后成功值。页面与组件共用持久快照，清除后不接受迟到请求复活旧记录。
- 保留原生校园卡余额、唐岛湾电量、两校区空闲教室、图书馆座位及专注功能；古镇口继续使用学校页面。
- 移除课表、校园卡流水、数字石大、图书馆主页等轻量版范围外入口。
- 整理公开文档、演示图与发布目录，保留上游版权、原计划和开发技能来源。

## 已完成验证

此次发布沿用封板源码已完成的检查，没有因纯文档整理重复生成安装包。

| 检查 | 结果 |
| --- | --- |
| assembleDebug | 通过 |
| testDebugUnitTest | 20 类、163 项，0 失败、0 错误 |
| lintDebug | 0 errors、7 warnings；其中 3 条为紧凑次要文字的 SmallSp 提示 |
| Android 36 / Pixel Launcher | 覆盖安装；实际新增两个 2×2 实例，约 172×224dp |
| 显示与失败状态 | 浅/深色，1.0×/1.3×/2.0× 字号；离线刷新保留旧值和成功时间 |

组件测试使用虚构数据，没有为此向学校提交虚构学号或寝室。历史手机验收确认过图书馆功能；本次没有重新执行本人账号预约、校园卡或电量查询。

## 已知限制

- 仅实测 Pixel Launcher；其他桌面的透明度、圆角和网格尺寸可能不同。
- 旧 2×3 实例不会自动变成 2×2，需重新添加。大字号或极窄尺寸可能省略楼号等次要文字，可拉大组件。
- 半透明磨砂外观不等于实时模糊壁纸。
- 古镇口未实现原生查询，学校证书、网络与页面变化可能影响访问。
- Room 中保留旧课表表结构仅用于覆盖升级兼容；M3 课表使用独立 JSON 缓存，不读写这五张旧表，也不把它们当作 UPC 当前账号课表。
- APK 内部版本 `0.2.7-lightly-preview`（19），沿用此前调试证书；本次以 GitHub Release 形式分发同一已验证包。





