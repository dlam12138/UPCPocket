# ADR 013 · PWA 校园业务降级为官方入口与服务端代登录暂停

- 状态：Accepted，记录于 2026-11-23。
- 前提：学校尚未给出第三方站点代登录/预约的书面授权；阿里云 ECS 到学校校内业务系统（`libspace`、`cas`、`app`、`service`，`211.87.17x/190`）的访问被校园边界按源 IP 静默丢弃，实测同一条客户端命令经教育网出口才可用。
- 更新 [ADR 012](012-multi-user-web-library-service.md) 的站内登录与预约部分；延续 [ADR 007](007-ios-pwa-local-data-and-school-links.md) 的本机数据与官方入口、[ADR 011](011-local-library-companion.md) 的本机接入历史。

## 背景

2026-11-23 的对照诊断确认：同一台 ECS、同一条 `curl 8.5.0 / OpenSSL 3.0.13` 命令直连 `libspace.upc.edu.cn`（`211.87.190.56:443`）时，TCP 可建连，发出 ClientHello 后服务端 0 字节返回、约 5 秒 FIN；TLS 1.2/1.3、无 SNI、指定曲线等变体结果完全一致。把出口换成 Mac 所在的教育网（出口地址略）后，同一命令连续 200。`app.upc.edu.cn`、`service.upc.edu.cn`、`cas.upc.edu.cn` 同样不可用，而 `jwc.upc.edu.cn`、`www.upc.edu.cn` 与超星系主机可达。

即：故障，以及空闲教室、e站通、图书馆在服务器侧的不可用，来自**源 IP/网络路径访问控制**，不是 TLS 栈、证书或登录协议问题；换 ECS 出口 IP 也无法保证通过。

用户决定：不再让服务器代替用户登录；不部署校园网中继主机（没有 7×24 常开设备）；不做需要发布成本的 iOS 原生 App。接受 PWA 校园业务降级为官方入口，等待学校授权。

## 决策

- 新增 `SCHOOL_ACCESS_MODE` 环境变量，默认 `official`：
  - `official`：`/api/auth/*`、`/api/library/*` 整体停用（403 `station_disabled`；`/api/library/session` 返回 `{state:"unavailable"}`），服务端不接收、不解密、不保存学校账号密码、Cookie、Ticket 或预约数据。
  - `station`：仅在取得学校授权且 ECS 能访问学校上游时显式开启，恢复 ADR 012 的站内登录/预约契约。
- 新增公开只读端点 `GET /api/config`，返回 `{schoolAccess}`。PWA 启动时读取；请求失败或返回未知值一律按 `official` 展示，避免误开站内入口。
- PWA：
  - 「图书馆选座」不再初始化站内登录与预约，改为三个馆区官方入口与“需校内网络/学校授权”的说明。
  - 「空闲教室」「e站通」改为学校官方入口。
  - 首页服务卡来源标注「官方入口」。
  - 学校校历继续由服务端读取 `jwc.upc.edu.cn`（公网可达、无需凭据）；课表、待办、专注仍为本机能力。
  - 前端保留 `station` 分支的完整实现，切换模式即可回切（需复查浏览器冒烟）。
- 服务端保留既有登录/预约实现与测试，由模式开关隔离；不删除代码，以便授权到位后恢复。
- Android 应用不变：其图书馆、空闲教室、e站通本来就是手机原生直连，手机在校园网内即可使用，不属于本次降级范围。

## 备选与理由

- **校园网中继 / 可信代理**：可在未获学校授权时恢复 PWA 站内能力，但需要 24 小时常开的校园网主机，并把多用户流量集中代理到校内系统，存在校园网使用规范风险；用户已排除。
- **iOS 原生 App（KMP 复用 Android 协议）**：手机 IP 直连、架构最干净，但需要签名与发布成本；用户已排除。
- **更换 ECS 出口 IP**：证据显示是校内业务系统对非授权源网络的策略，换 IP 不保证有效、成本高且违背最小改动原则。
- **继续服务端代登录并配白名单**：合规路径最正，但学校授权周期长，用户决定先降级等待。

## 影响与代价

- iOS/PWA 端失去图书馆、空闲教室、e站通的站内功能，只保留官方入口；这是产品范围收缩，页面文案必须如实说明，不能把“官方入口”伪装成站内能力。
- 服务端不再处理学校凭据，安全面与合规面显著缩小；切换后应清理已存的站内会话与用户数据（Redis 图书馆会话、PostgreSQL 用户/预约记录按保留策略处理），具体命令见部署手册。
- 待学校授权、且部署网络能访问学校上游后，按 `station` 模式恢复；恢复前需要重新验证真实 CAS、预约与浏览器冒烟。
- 本地预览 `tools/web/serve.py` 与合成入口 `server/tests/serve_fake.py` 仍以 `station` 运行，便于继续开发；生产默认 `official`。
- 校历仍依赖服务端访问 `jwc`；若该路径未来也受限，PWA 会如实显示失败，不伪造数据。

## 依据与验证

- 网络证据：同一 ECS 客户端经出口切换后的直连/隧道对照、TLS 变体、空闲 TCP、端口矩阵与主机矩阵见本轮诊断记录（对话与运维报告）。
- 实现：[配置](../../server/app/config.py)、[认证路由](../../server/app/routes/auth.py)、[图书馆路由](../../server/app/routes/library.py)、[公开路由](../../server/app/routes/public.py)、[PWA 启动](../../web/app.js)、[页面](../../web/index.html)。
- 测试：`server/.venv/bin/python -m pytest` 覆盖 `official`/`station` 两种模式；`node --test tools/web/test_login_crypto.mjs`；前端脚本 `node --check`。
- 未验证：真实学校授权与恢复验收、PWA 浏览器交互冒烟（当前环境无浏览器自动化时需人工）、真实 CAS/预约。合成与单测只证明其覆盖的代码路径。
