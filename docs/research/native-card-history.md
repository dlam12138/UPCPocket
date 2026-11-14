# 校园卡本人流水接入

2.0.4 首次将已在独立 Android 客户端由本人账号验证成功的 UPC 新校园卡协议移植至当前插件架构，原生查询与展示仍为主流程。2.0.5 开发增加了仅在原生认证遇验证码或额外验证时出现的受限学校 WebView 回退。

## 结构

- `core:upc-auth`：受控读取一次学校凭据，独立 CAS CookieJar，动态 `execution` 表单与单次密码提交。沿固定学校域逐跳检查重定向；校园卡 HTTP 回调直接升级为 HTTPS，保留原始 query。
- 必须区分前端 `synjones-auth`（已完成的令牌）与 `ticket`（需要兑换的票据）。前者直接使用，后者才向固定 OAuth 地址兑换；不能把所有成功跳转都当作 ticket。
- `feature:card`：只接收宿主认证适配返回的 token；先查本人身份，再分页读取流水、按月过滤、显式加载全部、搜索已加载记录与显示学校收支汇总。金额保持整数分。
- 会话绑定内部 accountId、本人学号、凭据代次及本次业务 epoch。换号/删号/改密触发失效，响应发布前再次核对；不新增持久化。
- 页面路由 `card.history` 由校园卡插件注册，首页余额卡按钮进入。认证适配缺失时保留原有余额插件行为。

## 官方地址与字段

源自 UPC 新校园卡官方前端及本人授权查询过程。地址不包含真实票据、学号或业务记录。

| 用途 | 固定 HTTPS 主机与路径 |
| --- | --- |
| 入口 | `mb-xyk.upc.edu.cn/berserker-auth/cas/redirect/wislearn` |
| 认证 | `cas.upc.edu.cn/cas/login` |
| 可选票据兑换 | `mb-xyk.upc.edu.cn/berserker-auth/oauth/token` |
| 本人核验 | `mb-xyk.upc.edu.cn/berserker-base/user` |
| 流水 | `mb-xyk.upc.edu.cn/berserker-search/search/personal/turnover` |
| 收支汇总 | `mb-xyk.upc.edu.cn/berserker-search/statistics/turnover/count` |

业务使用 `synjones-auth` 请求头与 `synAccessSource=h5`。分页为 `size/current`；月范围为 `timeFrom/timeTo`。解析 `records/total/pages`，交易字段为 `orderId/resume/tranamt/typeFrom/jndatetimeStr`（或 `effectdateStr`）。不提交其他账号标识来查询他人流水。

同一 Synjones `berserker-search` 平台的多份公开客户端与官方新校园卡前端均以 `typeFrom = "1"` 表示收入、`"2"` 表示支出。本项目只接受这两个已知值；缺失或其他值直接报 `N-TYPE`，不把未知方向默认当作支出。`tranamt` 与汇总 `income/expenses` 以整数分保存，可无损接受 `820` 或 `820.0` 这类整数写法，小数分或其他单位拒绝而不是四舍五入。

## 验证与边界

- 独立客户端的正常无验证码登录及本人流水已由用户真机验证；这不能替代移植后的本项目真机验收。
- 本项目验证：`:core:upc-auth:testDebugUnitTest` 54 项通过；`:feature:card:testDebugUnitTest` 78 项通过；`:app:testDebugUnitTest` 99 项中 98 项通过、1 项既有真实更新联网测试跳过。`checkModuleBoundaries` 与 R8/resource shrink Release 构建通过；合成数据覆盖已知/未知收支方向、整数与小数金额、单次密码提交、验证码提前停止和迟到结果隔离。所有新增回放样本为合成数据，不依赖真实账号或校园网络。
- 2026-11-12 在无既有账号的隔离模拟器上，经正常账号设置录入用户授权的本机学校账号，使用 R8 Release 完成本人只读验收：CAS 登录与 `/berserker-base/user` 本人核验通过；全部日期首屏、手动“加载更多”、按月查询及汇总均成功；该月有界“加载全部”完成后不再显示“加载更多”；搜索无匹配后，清空可恢复结果；1.5× 字体下深浅色均显示真实数据行、汇总与遮挡后的账号，文本未越界；删除账号后页面回到“尚未设置可用学校账号”，查询/加载全部均禁用。验收后已删除账号，未卸载应用、未清除既有数据。
- 真实验收中，空闲后首个只读请求偶尔返回 NETWORK（“学校服务暂时无法连接”），手动再查即成功；客户端保持 `retryOnConnectionFailure(false)`，不自动重试。本次未对全部日期执行全量加载，只做了上述单月的有界“加载全部”。验证码/二次验证未在真实登录中出现，只由合成测试覆盖“停止且不提交/不重试密码”；改密、换号和迟到结果隔离由合成测试覆盖，未用真实账号制造这些状态。
- 会话和列表仅在内存中保留，没有离线流水、后台轮询或写操作。本改动只增加流水能力，不代表新系统的全部功能均已接入。

## 2.0.5 开发：验证码网页回退（未发布）

- 原生账号登录流程遇到明确验证码/二次验证标记时，不提交密码或自动重试，页面显示“前往验证”。用户在固定校园卡登录入口手动完成学校验证；应用不读取或自动填写网页账号密码。
- WebView 初始地址及可观察到的导航/资源请求按固定入口、已核对的 CAS 登录/验证码路径、校园卡 CAS 回调和已知登录结果页检查。仅指定校园卡 HTTP 回调可升级到 HTTPS，原 query 字节保留；外站、其它 HTTP、异常端口、userinfo、未核对路径、结果子框架及非 CAS 登录 POST 拒绝。精确 CAS 登录表单支持 iframe POST，成功结果只从当前主框架截停。Android 的资源拦截不报告资源重定向后续地址，因此不保证子资源重定向每一跳均在发送前阻断；不为此添加携带学校 Cookie 的资源代理。
- `synjones-auth` 与校园卡平台 `ticket` 继续由 `core:upc-auth` 的共同解析器处理：直接 token 不兑换，平台 ticket 只兑换一次；CAS service ticket 不能作为平台 ticket。捕获到网页结果后先校验当前凭据账号和代次，再由 `feature:card` 调用本人接口核验学号并沿用原会话提交路径。流水只刷新当前日期筛选的第一页（包括用户选择的“全部日期”）；“加载全部”需再次由用户点击。
- WebView 不启用 DOM storage、数据库、文件/内容访问、混合内容或第三方 Cookie；不注入 JS bridge。账号失效先废弃交互 attempt 并等待活动 WebView 停止/销毁，再清理 `mb-xyk.upc.edu.cn` 和学校域 Cookie。关闭或成功不全局清 Cookie，避免影响课表和图书馆会话。
- 合成覆盖包含验证码结构不完整时不提交密码、直接 token/平台 ticket 共用处理、service ticket/歧义参数拒绝、入口/主框架/子框架/POST/资源/HTTP 回调跳转策略，以及 attempt 单次消费、换号和清理后的迟到结果隔离。
- 最终定向 JVM 测试：`:core:upc-auth:testDebugUnitTest` 65 项、`:feature:card:testDebugUnitTest` 85 项，共 150 项通过；包含销毁失败、重载与迟到 `onRelease` 的组合回归。其余相关模块合并汇总为 607 项，605 项通过、2 项真实联网用例（`ChaoxingLiveReadTest`、`RealUpdateNetworkTest`）跳过、0 项失败或错误；Kotlin 编译、模块边界、Debug 与 R8/resource shrink 长期签名 Release 最终构建均通过。独立 reviewer 最终差异复核无阻断问题。
- 仅 Debug 的合成 Activity 已在隔离模拟器以真实 WebView 完成 iframe POST、键盘可见性、取消、账号变化、受阻导航错误遮罩 → 重载 → 单次成功；Release 检查排除夹具的 manifest/dex 条目。Release 实际安装验证了同签覆盖保留组件、进程重建、无账号流水空态，以及课表 RemoteViews 进程死亡后刷新和整卡跳转。未使用校园账号、未访问校园业务接口，未卸载或清除用户数据。
- 交付为未发布的 `com.upcpocket.next` 2.0.5 / versionCode 26 候选：`artifacts/UPC-Pocket-2.0.5-candidate.apk`，5,185,057 bytes，四 ABI。具体 SHA-256、签名、步骤与本机证据入口见 [2.0.5 开发记录](../releases/2.0.5-development.md)。真实学校挑战和票据兑换、HTTP/TLS/网络失败、renderer 崩溃、完整字体/尺寸/旋转矩阵、全部三个组件及真机未验证；intercepted HTTP 503 未触发实际错误回调，不能把导航错误验收当作 HTTP 错误回调验收。
