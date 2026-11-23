# ADR 012 · 公网多用户 Web 图书馆服务

- 状态：Accepted，记录于 2026-11-22；站内登录与预约部分由 [ADR 013](013-pwa-official-entry-and-station-login-pause.md) 于 2026-11-23 暂停（Superseded），待学校授权后按 `station` 模式恢复。
- 前提：学校已同意第三方站点代学生完成 CAS 登录与图书馆预约。部署方只有在取得并遵守该授权、且取得必要的域名备案与安全条件后，才能开启站内登录和预约写操作；没有该授权时只能提供公开查询与官方入口。
- 更新 [ADR 011](011-local-library-companion.md) 的本机接入方式；延续 [ADR 010](010-web-library-reservation.md) 的同源前端契约，以及 [ADR 007](007-ios-pwa-local-data-and-school-links.md) 的 PWA 本机数据、[ADR 008](008-web-public-campus-data.md) 的公开与需授权数据边界。

## 背景

[ADR 011](011-local-library-companion.md) 让 iPhone 模拟器经 Mac 回环完成站内登录、选座和预约：服务跑在 Mac 上，浏览器与学校会话同源，所有数据留在单台机器。这解决了交互验证，但要求 Mac 持续运行，只能服务同一台 Mac 的浏览器或模拟器，也没有账号隔离、多用户会话、限流和运维边界，无法面向任意 iPhone 真机在 HTTPS 下使用。

同时，图书馆学校协议、座位解析、预约核对与取消语义已经在 `tools/web/` 的同步模块中实现并通过本机验证。本轮需要在不重写学校协议的前提下，把接入方式升级为可公网部署的多用户服务：`server/`（FastAPI）+ Redis + PostgreSQL，生产由 Caddy 终止 TLS 并提供 PWA 静态文件，也可用 `SERVE_STATIC=true` 由应用直接提供 `web/` 用于本地预览。入口为 `app.main:create_app`，生产以 `uvicorn --factory` 启动。 [部署手册](../../deploy/README.md) · [Web 说明](../web.md)

## 决策

- **服务边界**：`server/` 只负责 Web 服务的认证编排、会话、限流、缓存、审计和部署集成；学校认证、座位/预约协议仍由 `tools/web/` 的同步模块提供，经 `server/app/upstream/bridge.py` 加载，不在 `server/` 重写协议，也不修改 Android 模块。`server/` 是独立 Python 服务，与 Android Gradle 模块边界无关。
- **登录只做一次性内存解密**：浏览器先 `GET /api/auth/key` 取得 RSA-OAEP-SHA256 公钥（密钥生成脚本默认 3072 位，加载接受 ≥2048 位；`kid` 支持轮换），随后用 `crypto.getRandomValues` 自行生成 32 字节 base64url 一次性 `nonce`，用 WebCrypto 生成一次性 AES-256-GCM 密钥加密 `{username,password,nonce}`，再用 RSA-OAEP 包裹密钥，向 `POST /api/auth/login` 提交信封；请求顶层也带同一个 `nonce`。服务端解密一次后核对密文内 nonce 与顶层一致，并以 `SET NX` 在 Redis 登记该 nonce 600 秒：首次提交成功，重放返回 400 `invalid_request`；随后调用既有 CAS/libspace 或超星认证，丢弃明文。密码不落盘、不进日志。公钥端点只返回公开参数、不写服务端状态，避免未认证 GET 造成共享缓存写放大。学校登录接口只接受明文凭据，因此服务必须完成这一次解密；信封的作用是避免密码出现在 TLS 终止点、代理日志、请求体采集和 APM 中。Python 字符串无法保证清零，代码只对可擦除的 `bytearray` 尽力擦除，残余风险由进程隔离、最小权限和审计覆盖。
- **加密边界**：图书馆业务令牌/Cookie 以 AES-256-GCM 密文存 Redis，AAD 绑定 `user_id + redis + library + branch`，密文不能跨用户或跨馆区搬运；PostgreSQL 的预约选择、提交前记录和记录 ID 按列 AES-256-GCM 加密，AAD 绑定列名；用户身份只保存 `HMAC-SHA256(session_secret, "identity" + 登录名)`，数据库里没有明文登录名；审计只保存固定动作、IP/UA 的 HMAC 与可选的最小加密上下文，不保存密码或业务明文。
- **会话与写入约束**：生产浏览器 Cookie 为 `__Host-upcp_session`（Secure/HttpOnly/SameSite=Lax/Path=/），空闲 30 分钟、绝对 8 小时；登录成功后轮换会话 ID 与 revision，更换用户时清理旧用户的馆区会话。馆区预约/取消与馆区退出要求 `X-Library-Session`（revision）与 `X-CSRF-Token`，全局退出校验 CSRF；所有非 GET 请求校验 `Origin`/`Sec-Fetch-Site` 同源；登录默认限流 5 次/10 分钟/IP 与 5 次/10 分钟/登录名（登录名经 HMAC 计数），预约/取消另按用户串行加锁，锁超时返回固定上游不可用。部署在 Caddy 后时只信任 `X-Forwarded-For` 的最后一项（Caddy 写入的真实连接地址），忽略客户端自带前缀；IPv6 地址聚合到 /64 后再 HMAC，该值只用于限流与审计，不存明文 IP。该假设限定为单层 Caddy、无前置 CDN；增加代理层时必须配置 Caddy `trusted_proxies` 并复核。
- **多用户隔离与幂等**：每个用户、每个馆区独立学校会话；会话整体按用户 ID 隔离，任何读取都要求本人浏览器会话。预约沿用客户端 UUID 幂等，状态按 `prepared→sending→submitted→confirmed/rejected/unknown` 推进（服务端记录另有 `challenge`、超星 `accepted` 与超时 `elapsed` 等状态）；同 UUID 不重复发送；只有与本人、日期、房间、座位、完整时段唯一匹配的学校新记录才标记 `confirmed`；取消另行登记，结果未知提示核对官方记录，不自动重发。
- **公开数据与降级**：`/api/classrooms`、`/api/calendar`、`/api/service-catalog`、`/api/service-catalog/guide` 经 Redis 缓存（教室 60 秒，校历/目录/指南 1 小时）；上游失败可返回上次成功缓存并在 JSON 加 `stale:true`、响应头 `X-Cache: hit|miss|stale`；无可用缓存时返回 503 `upstream_unavailable`，不把失败伪装成空结果。
- **数据最小化、保留与删除**：密码不落盘；图书馆业务令牌只在加密的 Redis 会话中存在；预约/取消记录保留 90 天、审计保留 180 天，应用启动时自动清理；`server/scripts/delete_user.py --confirm` 按登录名重算身份 HMAC 删除用户，并级联预约/取消记录，审计保留动作但解除用户引用。数据库备份经 `pg_dump | age` 加密，age 私钥离线保管。
- **密钥轮换与应急**：登录密钥按 `kid` 轮换并保留旧密钥过渡；数据密钥用 `DATA_KEY_VERSION`/`DATA_KEY_OLD` 版本化，新写用新版本、旧数据用旧密钥解；`session_secret` 用于身份哈希、审计 IP/UA 哈希、登录限流键与取消标识 HMAC，轮换会改变身份关联，只在怀疑泄露时使用。`BOOKING_ENABLED=false` 可一键关闭写入；清 Redis 会话可强制全部用户重新登录。见 [部署手册](../../deploy/README.md)。

## 备选与理由

- **官方 SSO / A 路线**：由学校统一身份或正式 Web 授权回调签发会话，是更干净的长期方案。当前没有可用的校方 Web SSO 授权与回调登记，无法覆盖本人记录和预约写操作，因此作为后续目标保留，而不是本轮实现。
- **原生 iOS App / D 路线**：可复刻 Android 的认证与协议，但需要另建工程、签名、分发和审核，且 PWA 已经承担课表、待办和专注，成本与维护面明显更大。
- **继续回环或隧道**：实现最省，但依赖 Mac 常开，只能服务同机浏览器/模拟器，没有多用户隔离、限流、审计和 HTTPS 运维边界，不能作为面向任意 iPhone 的公开服务。
- **重写学校协议**：把同步协议改成异步/服务端专用实现会增加与学校接口漂移的风险；本轮保留既有协议层，只替换接入与会话外壳。
- **服务端保存密码或长期明文会话**：便于自动续登，但违反最小化原则并扩大泄露面；本轮明确不保存密码，业务令牌加密存储并按空闲/绝对时间过期。

## 影响与代价

- 新增公网服务、域名、TLS、备案、Redis/PostgreSQL 与备份运维，安全责任从单台 Mac 扩展到服务器、密钥和发布流程；需要单独的安全事件响应与轮换演练。
- 服务器被完全控制时无法做到零知识：服务必须在登录时接触一次性明文，也能解密在线用户的书馆会话；只用隔离、最小权限、审计与保留策略降低影响，不声称零知识。nonce 只阻止信封重放，不阻止服务器或终端在首次登录时读取明文；Python 无法可靠清零字符串，内存转储仍是残余风险。
- 单机部署无高可用：Redis 丢失会导致重新登录，PostgreSQL 依赖备份恢复；没有内置指标端点，容量和多副本不在本轮范围。开发用内存后端按进程隔离，不能替代生产 Redis/PostgreSQL。应用也没有全局内存熔断，依赖请求体上限（登录 8 KB、Caddy 16 KB）、上游并发池与超时限制。
- 合成/本机验证只证明其覆盖的代码路径。真实 CAS/libspace 与超星账号登录、真实预约/取消写入、iPhone 真机 Safari/主屏幕 PWA、Docker 镜像构建与 compose 启动、真实 ACME 证书、ICP/公安备案、真实 `pg_dump`+age 恢复演练均未验证，不能写成已验收。

## 依据与验证

- 服务实现：[配置](../../server/app/config.py)、[加密](../../server/app/crypto.py)、[会话](../../server/app/sessions.py)、[请求安全](../../server/app/security.py)、[缓存](../../server/app/cache.py)、[预约日志](../../server/app/library/journal.py)、[图书馆编排](../../server/app/library/service.py)、[登录路由](../../server/app/routes/auth.py)、[图书馆路由](../../server/app/routes/library.py)、[公开数据路由](../../server/app/routes/public.py)、[应用入口](../../server/app/main.py)、[数据库迁移](../../server/migrations/001_init.sql)
- 前端与协议：[站内登录](../../web/login.js)、[登录信封加密](../../web/login-crypto.js)、[图书馆前端](../../web/library.js)、[旧本地预览服务](../../tools/web/serve.py)、[Web 预约契约](../../API.md#web-图书馆预约)
- 部署与运维：[部署手册](../../deploy/README.md)、[Caddyfile](../../deploy/Caddyfile)、[compose](../../deploy/docker-compose.yml)
- 上一轮本机决策：[ADR 011](011-local-library-companion.md)、[iOS 网页版](../web.md)
- 本轮实际验证范围与未验证项集中记录于 [iOS 网页版](../web.md)，本 ADR 不把实现或局部测试当作真实验收。
