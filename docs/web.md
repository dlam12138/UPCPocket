# UPC Pocket iOS 网页版

源代码在 [`web/`](../web/)，面向 Safari 和 iPhone 主屏幕。导入的学期课表、待办与专注保存在本机，学校校历由同一来源的只读接口展示。

**当前学校接入模式为 `official`（默认）**：学校没有给出第三方站点代登录/预约的授权，且阿里云 ECS 无法访问学校校内业务系统（`libspace`、`cas`、`app`、`service` 被校园边界按源 IP 拦截，实测同一条客户端命令经教育网出口才可用），因此 PWA 的图书馆、空闲教室、e站通改为**学校官方入口**：站内不再收集学校账号密码，也不保存 Cookie、Ticket 或预约记录。服务端不再接收学校凭据，生产部署见 [部署手册](../deploy/README.md)，决策见 [ADR 013](adr/013-pwa-official-entry-and-station-login-pause.md)。

Android 应用不受影响：其图书馆、空闲教室、e站通本来就是手机原生直连，手机在校园网内即可使用。

## 本地开发与预览

旧的单用户预览仍在仓库根启动，只监听 `127.0.0.1`，用于本机静态页面与公开数据调试；默认启用回环图书馆，`--no-library` 只保留公开查询：

```sh
python3 -B tools/web/serve.py
```

在 Mac 或同一台 Mac 的 iPhone 模拟器中，用 Safari 打开 [本机预览](http://127.0.0.1:8000/)。使用同一个地址和端口以保留原浏览器存储。结束预览时在终端按 Control+C。该模式是历史本机用途，不能作为公网多用户服务。

`server/` 自带**仅本地**的合成学校验证入口，用合成的认证和座位响应启动真实应用，方便在浏览器或 Playwright 中检查登录对话框和预约流程：

```sh
server/.venv/bin/python server/tests/serve_fake.py
```

默认监听 `127.0.0.1:8080`，使用临时密钥与内存后端，拒绝 `ENV=production`，且被 `.dockerignore` 排除、不进入镜像。它不能用于真实账号或真实预约；生产入口是 `app.main:create_app`（`uvicorn --factory`）。

## 部署到公网

生产拓扑为 Caddy（自动 HTTPS、静态 `web/`、`/api/*` 反代）+ FastAPI 应用 + Redis（会话/限流/公开数据缓存）+ PostgreSQL（预约记录与审计）。`SERVE_STATIC=false` 时 Caddy 提供 PWA 静态文件，`SERVE_STATIC=true` 时可由应用直接提供，便于本地排查。密钥通过 Docker secrets/只读挂载注入，备份为 `pg_dump | age` 密文，详见 [部署手册](../deploy/README.md)。

上线前需要已解析的域名、可用 80/443 端口、ICP 备案（境内服务器）和按属地要求的公安联网备案。`SCHOOL_ACCESS_MODE=official`（默认）下服务端不接收学校凭据，只提供静态 PWA 与校历等公开只读数据；只有取得学校授权、且 ECS 能访问学校上游后才可改为 `station`。会话、Cookie、同源校验、登录 nonce 防重放、限流和加密边界的决策见 [ADR 012](adr/012-multi-user-web-library-service.md)，暂停决定见 [ADR 013](adr/013-pwa-official-entry-and-station-login-pause.md)。

真机从稳定的 HTTPS 地址打开。点 Safari「分享 → 添加到主屏幕」，保持「作为网页 App 打开」开启。首次从主屏幕启动时保持联网，让静态资源完成初始化，之后可从图标离线重开。在 Safari 创建的记录保留在 Safari，主屏幕应用使用自己的本机存储。 [Apple 安装指南](https://support.apple.com/guide/iphone/iphea86e5236/ios) · [WebKit 存储说明](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/)

## 使用与维护

- 首页右上角「设置」可以降低底栏透明度。网页跟随系统浅色／深色外观和浏览器支持的无障碍偏好。
- 截止时间按设备本地时区填写与展示。专注计时根据时间恢复，关闭页面期间的结算会在回到应用时补做。首版的截止和计时均无系统通知。
- 「空闲教室」只保留学校官方入口；查询需要校内网络，本站不代理学校接口，实际占用以学校页面为准。 [学校空闲教室](https://app.upc.edu.cn/freeclass/wap/default/all)
- 「学校校历」展示教务处最近发布的学年和原图，点「放大」可看全图。 [教务处校历目录](https://jwc.upc.edu.cn/603/list.htm)
- 「e站通」只保留学校官方服务大厅入口；站内目录与本人事项需要校内网络或学校授权，当前不由本站代理。 [学校服务大厅](https://service.upc.edu.cn/)
- 「前往学校课表」在新窗口打开学校网页。图书馆入口见下文。 [仓库官方目录](upc/官方入口.md)
- 周课表沿用 Android 原版的紧凑布局。学期和周次在同一张控制卡上，节次轴与七日列对齐。点击日期查看当天课程，点击色块从底部查看课程详情。 [Android 参考画面](images/2.0.6/promo/02-timetable.png) · [Android 2.0.8 布局记录](releases/2.0.8-development.md#手机反馈后的磨砂组件与紧凑课表)
- 发布需在同一 HTTPS 来源提供 [`web/`](../web/)、[Web 服务](../server/)与 [配置/公开查询接口](../API.md#web-配置与学校接入模式)，保持站点地址和安装标识稳定。前端静态更新需同步 [Service Worker](../web/sw.js) 的离线资源列表与版本；后端更新按 [部署手册](../deploy/README.md) 构建并重启应用。在线启动优先读取新版页面，断网时使用离线缓存。首页照常加载全部页面样式和功能脚本；`.ics` 解析器与书签脚本按需加载，Service Worker 在首次使用时缓存并随版本加查询参数，不随安装预下载。

## iPhone 导入学期课表

1. 首次在 Pocket 打开「课表 → 课表选项 → 设置 Safari 书签」，点「复制书签脚本」。在 Safari 长按书签并编辑，把网址换成复制的内容。可以使用原来只支持下载的 V3 书签，也可以添加一个普通书签再编辑。以后可重复使用同一书签。 [Apple 书签指南](https://support.apple.com/guide/iphone/iph42ab2f3a7/ios)
2. 在 Safari 登录学校课表页，从书签列表运行该书签。核对中文预览并确认导出整学期。全部周成功后，点击「复制课表 JSON」。
3. 回到平时使用的 Pocket 网页或主屏幕版，进入「课表」后点「粘贴导入」。已有课表时，从标题旁的「课表选项」打开导入入口。如果 iOS 显示「粘贴」，点它确认。Pocket 会校验整学期并保存，随后显示周表。主屏幕版和 Safari 分别保存导入记录。 [WebKit 剪贴板规则](https://webkit.org/blog/10855/async-clipboard-api/) · [WebKit 主屏幕存储说明](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/)

如果系统没有完成粘贴，按 Pocket 的提示在输入框长按并选「粘贴」。也可以在学校页下载 JSON 到「文件」，回到 Pocket 点击「导入 JSON」选择该文件。两种入口使用相同的校验和存储。完整的新课表保存成功后才会更新同一学期，校验失败时保留上次课表。

已有课表时，在页标题旁的「课表选项」里粘贴或选取更新文件。这里也提供学校入口、`.ics` 临时预览和本机课表删除。已导入课表保存在当前浏览环境，清除浏览器或网页 App 的站点数据可能移除导入记录，可用「文件」里的原始 JSON 重新导入。

## 图书馆、空闲教室与 e站通（官方入口）

服务端处于 `official` 模式时，PWA 不收集学校账号密码，也不经服务器访问学校接口。三个校园业务页直接给出学校官方入口，并提示需要校内网络或学校允许的网络：

- 唐岛湾：[图书馆座位预约系统](https://libspace.upc.edu.cn/libseat/)。
- 古镇口东区：[超星座位入口](https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453)。
- 求是图书馆：[超星 seatengine 入口](https://office.chaoxing.com/front/third/apps/seatengine/index?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258)。
- 空闲教室：[学校查询页](https://app.upc.edu.cn/freeclass/wap/default/all)。
- e站通：[学校服务大厅](https://service.upc.edu.cn/)。

登录、验证码、选座、预约、取消与记录都在学校官方页面完成，本站不接触这些数据。待学校给出书面授权、且部署网络能访问学校上游后，可按 ADR 013 将 `SCHOOL_ACCESS_MODE` 改为 `station` 恢复站内能力；在此之前不提供站内登录入口。

### 历史记录：站内登录与本机回环模式

以下验收记录属于已由 [ADR 012](adr/012-multi-user-web-library-service.md) 建立、现由 [ADR 013](adr/013-pwa-official-entry-and-station-login-pause.md) 暂停的站内登录模式，保留作历史与本地开发参考，不代表当前公网服务的验收结论。

2026-11-21，用户在 iPhone 模拟器亲自登录，Mac 服务完成了真实 CAS 和图书馆本人核验。WebKit 合成会话覆盖选房间、按坐标渲染座位布局、起止时间、单次提交、核对及退出，SQLite 复核覆盖未知结果跨重启防重。v14 的登录返回地址与离线资源单独通过合成会话验证。实际预约写入和 iPhone 真机仍待验收。

2026-11-22，本机以普通命令启动后，在现有 Safari 标签页从来源重新加载，确认可以从「图书馆选座」打开站内登录表单。WebKit 定向测试确认主页在线重开取新版，断网使用离线页面，恢复联网后可从主页进入站内登录。

2026-11-22，WebKit 合成验证通过唐岛湾取消确认、单次发送、刷新核对，以及超星两馆分路和官方记录入口。两套超星请求参数、座位占用、校验分支和真实坐标以合成输入验证。本轮真实超星身份核验、学校预约及取消仍待验收。

## Kotlin 功能对齐状态

| 能力 | Web 状态 |
| --- | --- |
| 空闲教室、校历 | 校历由同源只读接口展示；空闲教室因服务器无法访问学校上游，当前只给官方入口 |
| 个人课表 | 可生成 Safari 书签，支持粘贴或文件导入 V3 学期课表、周表和离线恢复。后台读取和账号绑定待接校方 Web 授权 |
| 图书馆 | 当前只给三个馆区官方入口（站内登录与预约已按 ADR 013 暂停，等待学校授权）；历史站内模式保留在代码与 ADR 012 记录中 |
| e站通 | 当前只给官方服务大厅入口（服务器无法访问学校上游）；公开目录与本人事项目前不在站内代理 |
| 校园卡、石光 | 本人余额、流水、充值与石光只读业务待接 |
| 宿舍电量 | 唐岛湾现有数据来源使用 HTTP，本机验证其 HTTPS 证书已过期。古镇口的 Kotlin 路径为学校页面，Web 站内读取待接 |
| 待办与专注 | 本机新增、编辑、截止、勾选、删除与撤销可用。专注包含恢复、历史、今日和本周统计。系统提醒待实现 |
| Android 桌面组件 | 需要 iOS 原生系统入口，当前 PWA 支持主屏幕图标 |

学校列出的《信息系统建设集成规范》此次仅向校内网络开放，公开共享目录也未给出可用于本应用的本人课表授权。 [信息化建设处集成规范入口](https://nic.upc.edu.cn/2026/0312/c7408a484506/pagem.htm) · [数据资源共享目录](https://nic.upc.edu.cn/2023/0315/c7408a398207/pagem.htm)

课表在线同步仍需可用的校方本人课表授权。应用将用户主动导入的完整课表保存到本机，实时座位和预约状态以学校本次查询为准。

`.ics` 预览使用 [ical.js 2.2.1](https://registry.npmjs.org/ical.js/2.2.1)，随站点提供完整的[上游 MPL-2.0 许可证](../web/vendor/ical-LICENSE)。解析器在 Service Worker 接管后第一次在线预览时加载并进入离线缓存，Service Worker 升级后需重新在线加载一次；从未在线预览过时，离线预览会提示先联网加载。

## 启动优化 · 2026-11-22

- iPhone 18 Pro / iOS 27.2 模拟器，Safari 打开本机服务实测首屏静态载荷 703 KB、21 个请求；优化后约 303 KB、19 个请求。
- `.ics` 解析器（约 261 KB）与 Safari 书签脚本（约 11 KB）改为按需导入，首页启动不再请求；Service Worker 不再随安装预下载，首次使用时缓存。
- 首页主图 250 KB → 122 KB（1000×667 WebP，按手机 3× 展示尺寸），并加 `preload` 提前开始下载。
- 本机服务改用 HTTP/1.1 keep-alive；Service Worker 注册推迟到 `load` 之后，避免离线缓存与首屏争用连接。
- 模拟器里 Safari/WebKit 冷启动仍占主要时间：空白页与 PWA 的启动差距只有页面本身的 0.1–1.6 s，其余是模拟器启动 Safari 的开销（通常 2.5–9 s，长时间闲置后首次启动更久）。该部分不由网页代码改善。
- 验证：`node --test tools/web/test_timetable_import.mjs tools/web/test_calendar_import.mjs` 的 8 项解析测试通过；Playwright WebKit 检查确认首屏不请求 ICAL 与书签脚本、`.ics` 预览时按需加载、离线二次预览命中缓存、首页可离线重开。

## 学期课表验收 · 2026-11-20

- iPhone 17e 模拟器，iOS 27.2。Safari 下载 19 周合成文件，通过「文件」选取后显示本周周表，点开课程可见中文教师和教室。停止本地服务并退出 Safari 后，重开仍能恢复周表。
- WebKit 27.2，375 CSS px。验证周日到周六同时可见、首周重叠及拆段、成功空周、末周、课表详情和 `.ics` 预览。验证 v1 待办和专注记录保留、失败保留原课表、同学期重新导入、断网后新开和本机删除。
- `node --test tools/web/test_timetable_import.mjs` 运行 5 项解析测试。用 `node tools/web/test_timetable_import.mjs --fixture` 可生成合成的 V3 课表。真机本人文件验收待执行。

### Safari 书签与粘贴导入

- iPhone 17e 模拟器，iOS 27.2。用系统「粘贴」确认将合成的 19 周课表导入 Safari 中的 Pocket，验证本周周表和中文教师详情。
- WebKit 27.2。用合成学校响应验证生成的书签地址可执行、完整导出可复制、登录页响应中止、剪贴板权限失败后可手动粘贴。验证粘贴失败保留旧课表和断网重开，复测文件导入。解析单测增至 6 项。真实学校页、真机书签安装和主屏幕版粘贴待验收。

### 周表视觉对齐

- WebKit 27.2 的 375 和 390 CSS px 视口下，七列同屏，星期栏高 40 px，有课程的周显示 14 节 × 44 px。核对浅色、深色和大字号横滑，手机详情从底部展开。重叠课程在同组内点开逐门查看。
- 用合成课表复测了周次跳转、日视图、标题旁的导入菜单、剪贴板导入和离线重开。参考画面与当前节高依据见 [Android 课表截图](images/2.0.6/promo/02-timetable.png) 和 [2.0.8 布局记录](releases/2.0.8-development.md#手机反馈后的磨砂组件与紧凑课表)。

## 站内数据验收 · 2026-11-19

- 匿名查询确认两校区均返回学校数据。375 px WebKit 集成验证了首屏预取、站内楼栋分组与仅看空闲。
- 用合成 `.ics` 验证七日周表、课程详情与首页今日摘要。未请求本人课程，也未测试任何真实预约或充值。
- 教务处 2026–2027 学年的两张原图在 WebKit 加载，并验证站内放大和关闭。静态缓存复核保留应用模块，排除数据接口。
- e站通实际匿名目录返回 166 项。抽查一条真实指南，学校响应没有公开说明，页面如实标注。含正文、编号歧义和链接清理的指南用合成数据验收。
- 验证待办编辑、删除和撤销，以及专注统计显示。专注统计的凌晨 4 点跨日、周一分周和提前结束计时用合成记录核对。

## 首版 PWA 验收记录 · 2026-11-19

| 环境 | 实际验证 |
| --- | --- |
| iPhone 17 模拟器 · iOS 27.2 | Safari 导航及后退、添加到主屏幕和独立打开。停止本地服务后从图标冷启动，使用原生日期控件添加有截止的测试待办，勾选后再次冷启动，标题、截止和完成状态仍在。主屏幕应用显示一条提前结束的专注记录。 |
| WebKit 27.2 · 最终版 | 375 CSS px 视口的导航、底栏最小点击区、截止控件宽度、深色、减少动态效果与降低透明度设置。临时子路径部署停服务后新开、写待办及刷新哈希地址均通过。测试时钟验证专注切页恢复、暂停跨刷新、到点单次结算与提前结束。 |
| WebKit 27.2 更新 | 在隔离的临时站点升级静态缓存，重开后使用新样式，原待办、外观偏好和无关缓存均保留。 |
| 学校入口 | 模拟器 Safari 匿名访问课表入口后进入校方 CAS 页面，可以后退返回 Pocket。真实账号认证、本人本周课程以及站内读取均未验证。 |

首版截止控件的宽度修复通过 WebKit 验证。模拟器专注切后台后的时差、真机和正式 HTTPS 站点仍待验证。

开发依据包括桌面《UPC Pocket · iOS 网页版开发计划与设计指南》、[M5 导航与视觉](m5/README.md)、[课表设计](m5/2026-11-05-课表与组件设计.md)、[数据归属](adr/003-data-ownership-and-schema-compatibility.md)及[账号会话边界](adr/002-account-generation-and-business-sessions.md)。

## 2026-11-22 · 多用户 Web 服务验证

本轮把图书馆从 Mac 回环单用户服务升级为公网多用户服务（[ADR 012](adr/012-multi-user-web-library-service.md)）。以下为开发侧实际执行与本机证据；合成学校、本机服务和 WebKit 测试都不能替代真实校园验收。

- `server/.venv/bin/python -m pytest`：47 passed、2 skipped（Redis/PostgreSQL 集成用例默认跳过）；覆盖信封解密、nonce 绑定与字段校验、登录/会话、同源与 CSRF、预约/取消及幂等、公开数据缓存与 503、跨用户隔离、遗留登录 303、限流、客户端地址取 `X-Forwarded-For` 最后一项及日志字段。`server/tests/test_webcrypto_interop.py` 在该轮通过（Node WebCrypto 生成的信封由 Python 解密）。
- 本机真实 Redis 8.10 + PostgreSQL 18 上运行 `server/tests/test_redis_postgres.py`：2 passed，覆盖 Redis 密文值、锁超时、PostgreSQL 列级密文不含明文座位、用户隔离与级联删除。
- Node v24.22.3：`node --test tools/web/test_login_crypto.mjs` 6 passed。
- 本机 uvicorn + `server/tests/serve_fake.py` 合成学校：通过真实 HTTP 完成 `key → login → session → catalog → book → reservations(confirmed) → cancel`；Playwright 1.65.1 WebKit、390×844、iPhone UA 验证站内登录对话框、错误密码提示、正确登录、选择馆区/日期/房间/座位/时段、核对确认、预约成功核对、取消、切换到古镇口东区登录和退出登录。加入登录 nonce 后已按新流程复测通过，同一信封重放返回 400 `invalid_request`。
- 未验证：真实 CAS/学校账号与真实预约写入、iPhone 真机 Safari/主屏幕 PWA、Docker 镜像构建与 `docker compose up`、真实 ACME 证书、ICP/公安备案、真实 `pg_dump`+`age` 恢复演练。这些项目不能写成已验收。
