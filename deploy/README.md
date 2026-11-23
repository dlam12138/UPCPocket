# UPC Pocket 多用户 Web 服务 · 部署与运维手册

本目录是 `server/`（FastAPI 多用户 Web 服务）的公网部署工件：Caddy 终止 TLS 并提供 PWA
静态文件，`/api/*` 与 `/healthz` 反向代理到应用，PostgreSQL 存业务数据，Redis 存会话与限流。
PWA 本身仍是 `web/` 的静态站点，部署方式见 [Web 说明](../docs/web.md)。

> 本手册不含真实域名、密码或私钥：示例统一使用 `upc-pocket.example.com` 与占位符。
> 部署产物只允许写在 `deploy/`；`server/`、`web/`、`docs/` 由各自模块维护。

## 目录与边界

| 文件 | 作用 |
| --- | --- |
| `Dockerfile` | `python:3.12-slim` 应用镜像；非 root（uid/gid 10001）；`PYTHONPATH=/app/server`；CMD 用 `sh -c` 展开 `WEB_CONCURRENCY` 后 `exec uvicorn --factory` |
| `docker-compose.yml` | `caddy` / `app` / `redis` / `db` 四服务；只有 Caddy 发布 80/443；app 只 `expose`；redis/db 在 internal 网络；secrets 只读挂载 |
| `Caddyfile` | 自动 HTTPS、HTTP→HTTPS、安全头与 CSP、16KB 请求体上限、`/api/*` 与 `/healthz` 反代、静态站点与 SPA 回退、访问日志去 query |
| `.env.example` | 全部环境变量模板；复制为 `deploy/.env` 后填写，敏感项留空 |
| `backup.sh` | `pg_dump --no-owner --no-privileges` 流式交给 `age` 加密，输出 `deploy/backup/upcpocket-<UTC>.sql.age`，保留 30 天 |
| `restore.sh` | 从 `.age` 备份恢复；必须显式 `--yes`，终端内二次输入 `restore`；会停 app、重建库、流式导入、重启 app |
| `secrets/`（运行时生成，Git 忽略） | `login_keys/<kid>.pem`、`data_key`、`session_secret`，属主 10001、权限 600 |
| `backup/`（运行时生成，Git 忽略） | 仅存放 age 密文备份 |

`deploy/secrets/`、`deploy/backup/`、`deploy/*.env` 已在根 `.gitignore` 忽略；只提交
`deploy/.env.example`。**不要 `git commit` 任何运行期文件或 key。**

## 架构与安全边界

```
浏览器 / iOS PWA
   │  HTTPS(443) + HTTP/3
   ▼
 caddy ──────────────── edge 网络（唯一发布 80/443；承载 ACME 出站）
   │  /api/*、/healthz → app:8080
   ▼
 app ────────────────── edge（出站访问学校上游）+ internal
   │                                 │
   ▼                                 ▼
 redis（会话/限流，AOF）        db（PostgreSQL 数据卷）
   └──────────── internal（internal: true，无外网、无宿主端口）────────────┘
```

- 只有 `caddy` 映射宿主 `80/443`（含 `443/udp` 供 HTTP/3）；`app` 只 `expose: 8080`；
  `redis`/`db` 不发布任何宿主端口，且只在 `internal` 网络。宿主机上 5432/6379/8080 均不可从外部访问。
- `app` 是唯一同时能访问学校上游与内部数据的服务；rootfs 只读，只有 `/tmp` tmpfs，
  `cap_drop: ALL`、`no-new-privileges`、`core: 0`，以 uid/gid 10001 运行。
- 密钥只经 Compose `secrets`（只读 bind mount）注入 `/run/secrets/`，不写入镜像、
  Compose 环境变量、进程参数或 Git。`db`/`redis` 密码只通过 `.env` 注入对应容器。
- 不给 db/redis 映射端口、不 `privileged`、不挂 Docker socket；Caddy 不加载第三方插件，
  限流由应用实现（`RATE_LIMIT_ENABLED=true`）。
- 应用做严格同源校验（`Origin` 精确等于 `PUBLIC_ORIGIN`）、HTTPS-only Cookie
  （`__Host-upcp_session`）、登录请求体上限与加密信封；Caddy 再做 HSTS/CSP/nosniff 等边缘防护。
- 备份只落 age 密文；age 私钥离线保管，不放在仓库或服务器可被服务进程读取的位置。

> 环境变量含义以 `server/app/config.py` 为准：`ENV=production` 时强制 `PUBLIC_ORIGIN` 为
> `https://`、Cookie 必须 Secure、必须提供 `DATABASE_URL`/`REDIS_URL` 与密钥文件。

## 1. 前置条件

### 域名与备案

1. 一个已解析到本机公网 IP 的域名（A/AAAA）。Caddy 用 80 端口做 ACME HTTP-01 验证，
   会自动申请与续期 Let's Encrypt/ZeroSSL 证书；DNS 未生效或 80 不通时首次签发会失败。
2. 中国大陆境内服务器必须先完成 **ICP 备案**，否则 80/443 会被服务商拦截；面向公众提供
   交互服务时按属地要求完成 **公安联网备案**，并按要求展示备案号（改动 `web/` 属于前端范围，
   本部署不自动改页面）。
3. 若使用 CDN/反向代理，需同步调整：当前配置按"Caddy 直接终止 TLS"设计，客户端 IP、
   `Origin` 与 HSTS 都以本站为准；套 CDN 时必须改为 CDN 回源 HTTP 并重新评估同源校验。

### 网络与安全组

- 入方向：`80/tcp`、`443/tcp`、`443/udp` 对所有用户放行；`22/tcp` 只放行管理员固定 IP；
  **不要**放行 5432、6379、8080。
- 出方向：允许默认出站（Caddy 访问 ACME、app 访问学校上游、`age`/系统更新除外不主动外联）。
- 主机防火墙默认拒绝、仅放行上述端口；`deploy/.env` 与 `deploy/secrets/` 仅 root（或部署用户）可读。

### 主机与软件

- Linux 服务器（x86_64/arm64），建议 ≥ 2 vCPU / 4 GB 内存 / 20 GB 磁盘 + 备份空间；
  生产不要与其它服务混部。
- Docker Engine + Compose（需支持 Compose 规范的 `secrets`、`depends_on.condition`、
  `tmpfs` 选项；本仓库在 Compose v5.8.0 CLI 上做过静态校验）。
- 宿主机需要：`bash`、`age`（备份用）、`openssl`/`curl`（验证用）、可选 `jq`。
- 系统时间同步（chrony/systemd-timesyncd）；Caddy 证书与 Cookie 有效期判断依赖正确时间。
- 一个用于 `docker compose` 的目录（下文以 `/srv/upcpocket` 为例），把仓库检出到该目录。

## 2. 快速开始（TL;DR）

```sh
cd /srv/upcpocket                     # 仓库根
cp deploy/.env.example deploy/.env && chmod 600 deploy/.env
# 编辑 deploy/.env：填 PUBLIC_ORIGIN、PUBLIC_HOST、两处密码与两条 URL、AGE_RECIPIENT
docker compose -f deploy/docker-compose.yml --env-file deploy/.env config -q   # 预检，退出码 0

# 生成密钥（依赖 cryptography：用宿主 venv，见第 3 节；镜像构建后也可用 docker run）
python3 -m venv server/.venv && server/.venv/bin/pip install ./server
server/.venv/bin/python server/scripts/generate_keys.py --out deploy/secrets --kid 2026-11
sudo chown -R 10001:10001 deploy/secrets
sudo chmod 700 deploy/secrets deploy/secrets/login_keys
sudo chmod 600 deploy/secrets/data_key deploy/secrets/session_secret deploy/secrets/login_keys/*.pem

docker compose -f deploy/docker-compose.yml --env-file deploy/.env build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
curl -fsS "https://$(grep -E '^PUBLIC_HOST=' deploy/.env | cut -d= -f2)/healthz"; echo
```

下文统一约定：
>
> ```sh
> cd /srv/upcpocket
> COMPOSE="docker compose -f deploy/docker-compose.yml --env-file deploy/.env"
> ```

## 3. 生成密钥（一次性）

`LOGIN_KEY_DIR`、`DATA_KEY_FILE`、`SESSION_SECRET_FILE` 指向三个密钥：RSA 登录密钥（登录密码
加密信封）、32 字节数据加密密钥（AES-256-GCM，带版本号）、32 字节会话签名密钥。
生成脚本 `server/scripts/generate_keys.py` **依赖 `cryptography`**（非纯标准库），并会：

- 创建 `deploy/secrets/`（0700）与 `deploy/secrets/login_keys/`（0700）；
- 写入 `deploy/secrets/login_keys/<kid>.pem`（PKCS#8 未加密私钥，0600）、
  `deploy/secrets/data_key`（32 原始字节，0600）、`deploy/secrets/session_secret`（32 字节，0600）；
- 只打印 kid 与 SPKI base64；**绝不打印私钥**；文件已存在时拒绝覆盖（需显式 `--force`）。

方式一：宿主 venv（首次、镜像尚未构建时）

```sh
python3 -m venv server/.venv
server/.venv/bin/pip install ./server
server/.venv/bin/python server/scripts/generate_keys.py --out deploy/secrets --kid 2026-11
```

方式二：使用已构建的镜像（依赖已装，推荐在首次构建之后使用）

```sh
mkdir -p deploy/secrets && sudo chown 10001:10001 deploy/secrets
docker run --rm --user 10001:10001 \
  -v "$PWD/deploy/secrets:/secrets" \
  upcpocket-web:local \
  python scripts/generate_keys.py --out /secrets --kid 2026-11
```

生成后修正属主/权限（两种方式都要做；app 以 uid 10001 读取，secrets 为只读 bind mount，
不会改变宿主属主）：

```sh
sudo chown -R 10001:10001 deploy/secrets
sudo chmod 700 deploy/secrets deploy/secrets/login_keys
sudo chmod 600 deploy/secrets/data_key deploy/secrets/session_secret deploy/secrets/login_keys/*.pem
ls -la deploy/secrets deploy/secrets/login_keys        # 目录 700、文件 600、owner 10001
```

验证布局与权限（不打印私钥内容）：

```sh
ls -lR deploy/secrets
git status --porcelain deploy | grep secrets || echo "secrets 已被 Git 忽略"
```

**备份用 age 密钥对**（与数据库备份相关，和上面的服务密钥无关）：

```sh
age-keygen -o ~/upcpocket-backup.key          # 私钥离线保管，可打印到安全介质
chmod 600 ~/upcpocket-backup.key
grep '^# public key:' ~/upcpocket-backup.key  # 把 age1... 公钥填到 deploy/.env 的 AGE_RECIPIENT
```

age 私钥**不得**放到仓库、`deploy/secrets/`（会被服务容器可读）、或备份服务器上；
建议离线介质 + 至少一处异机副本，并做恢复演练。

## 4. 配置环境变量

```sh
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

必填项与要求：

| 变量 | 要求 |
| --- | --- |
| `PUBLIC_ORIGIN` | `https://<域名>`，与 `PUBLIC_HOST` 域名一致，不带路径/端口/末尾斜杠；应用按它做 `Origin` 精确匹配与 HSTS |
| `PUBLIC_HOST` | 仅域名（无 `https://`），只给 Caddy 用于签发证书与站点匹配 |
| `DATABASE_URL` | `postgresql://upcpocket:<密码>@db:5432/upcpocket`，密码必须与 `POSTGRES_PASSWORD` 完全一致 |
| `REDIS_URL` | `redis://:<密码>@redis:6379/0`，密码必须与 `REDIS_PASSWORD` 完全一致；密码含 `@ : /` 时做 URL 编码或直接用 URL 安全字符 |
| `POSTGRES_PASSWORD` / `REDIS_PASSWORD` | 32 字节以上随机值，例如 `openssl rand -hex 24`（hex 天然 URL 安全） |
| `AGE_RECIPIENT` | age 公钥 `age1...`（备份脚本读取 `deploy/.env`，不进入 app 容器） |
| `SCHOOL_ACCESS_MODE` | `official`（默认）或 `station`。`official` 下服务端不接收学校凭据，PWA 只给学校官方入口；只有取得学校授权且本机/本网络能访问学校上游时才改为 `station`，见 [ADR 013](../docs/adr/013-pwa-official-entry-and-station-login-pause.md) |

其余变量保持模板默认：`ENV=production`、`SERVE_STATIC=false`、`DB_AUTO_MIGRATE=true`、
`LOGIN_KEY_ID=2026-11`（与生成的 kid 一致）、`SCHOOL_ACCESS_MODE=official`、
`BOOKING_ENABLED=false`、`RATE_LIMIT_ENABLED=true`、
`TRUST_PROXY=true`、`WEB_CONCURRENCY=2`、`LOG_LEVEL=INFO`。

预检（静态解析，不连 daemon、不启动容器）：

```sh
docker compose -f deploy/docker-compose.yml --env-file deploy/.env config -q; echo $?   # 期望 0
```

> `.env` 属主保持部署用户、权限 600；**只有** compose 读取它，app 拿到的只是注入的
> `environment`，`POSTGRES_PASSWORD`/`REDIS_PASSWORD` 不会进入 app 容器。

### 学校接入模式（`SCHOOL_ACCESS_MODE`）

当前生产默认 `official`：阿里云 ECS 到学校校内业务系统（`libspace`/`cas`/`app`/`service`）的访问被校园边界按源 IP 拦截，服务端代登录不可用，PWA 的图书馆、空闲教室、e站通只展示学校官方入口，服务端不接收学校凭据。该模式下 `/api/auth/*`、`/api/library/*` 返回 403 `station_disabled`，`/api/library/session` 返回 `{"state":"unavailable"}`。

只有同时满足以下条件才可改为 `station`：① 学校书面授权第三方站点代登录/预约；② 部署网络（或经授权的出口）能访问学校上游；③ 复查真实 CAS、预约与浏览器冒烟。切换与回滚只改 `deploy/.env` 的该值并重建 app；切回 `official` 后建议清理已存的站内会话与用户数据：

```sh
# 清理浏览器会话与图书馆学校会话；限流计数和公开缓存可保留。
$COMPOSE exec -T redis sh -c '
  export REDISCLI_AUTH="$REDIS_PASSWORD"
  for pattern in "upcp:sess:*" "upcp:lib:*" "upcp:libabs:*"; do
    redis-cli --no-auth-warning --scan --pattern "$pattern" | xargs -r redis-cli DEL
  done
'
```

PostgreSQL 中的用户与预约记录不会因此删除，按 `retention_*` 保留策略处理；需要删除某个用户时按第 16 节执行 [`delete_user.py`](../server/scripts/delete_user.py)，不要直接清库。

## 5. 构建与启动

```sh
cd /srv/upcpocket
docker compose -f deploy/docker-compose.yml --env-file deploy/.env build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps   # 四个服务应 healthy
```

说明：

- 构建上下文是仓库根；仓库根 `.dockerignore`（由主 agent 维护）排除 `server/.venv`、
  `__pycache__`、`.git`、`build/` 等，保留 `server/` 与 `tools/web/`。镜像不需要 `web/`。
- 首次启动 `DB_AUTO_MIGRATE=true` 会在 app 启动时执行幂等迁移
  `server/migrations/001_init.sql`；也可手工执行（工作目录 `/app/server`）：

  ```sh
  $COMPOSE exec -T app python scripts/migrate.py        # 输出 database schema is up to date
  ```

  宿主 venv 方式：`server/.venv/bin/python server/scripts/migrate.py`（需在宿主注入与 app
  一致的 `DATABASE_URL`、`DATA_KEY_FILE` 等环境变量，一般用容器内命令更省事）。
- Caddy 首次签发证书通常 10–60 秒；失败先看 `logs caddy` 与 DNS/80 端口/备案。
- `app` 的 uvicorn 使用 `WEB_CONCURRENCY` 个 worker；worker 数不超过 vCPU 的 2 倍为宜。
- 应用健康检查：`GET /healthz` 返回 `{"status":"ok","checks":{"keys":true,"sessions":true,"database":true}}`
  与 HTTP 200；依赖异常时返回 503 + `degraded`，compose 会标记 unhealthy。

## 6. 验证：HTTPS、安全头、CSP 与 PWA

```sh
HOST="$(grep -E '^PUBLIC_HOST=' deploy/.env | cut -d= -f2)"

# HTTP → HTTPS（自动跳转）
curl -sS -D - -o /dev/null "http://$HOST/" | grep -Ei '^(HTTP/|location:)'

# HTTPS 安全头（期望 200；HSTS 仅 https；无 Server 头）
curl -sS -D - -o /dev/null "https://$HOST/" | grep -Ei '^(HTTP/|strict-transport-security|content-security-policy|x-content-type-options|referrer-policy|x-frame-options|server:)'

# 证书有效期与主题
openssl s_client -connect "$HOST:443" -servername "$HOST" </dev/null 2>/dev/null |
  openssl x509 -noout -subject -issuer -dates

# Manifest MIME（iOS 安装 PWA 依赖）
curl -sSI "https://$HOST/manifest.webmanifest" | grep -i '^content-type'   # application/manifest+json

# 健康检查与公开接口（同源、no-store）
curl -fsS "https://$HOST/healthz"; echo
curl -sS -D - -o /dev/null "https://$HOST/api/calendar" | grep -Ei '^(HTTP/|cache-control)'
```

CSP 验证（当前 `Caddyfile` 下发）：`default-src 'self'; connect-src 'self'; img-src 'self' data:;
style-src 'self'; script-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'none';
form-action 'self'`。页面与脚本不得使用内联 `<script>`/`<style>`、内联事件属性或 `eval`
（当前 `web/` 已满足；前端改动需保持）。Safari 用「开发 → 显示 Web 检查器 → 控制台」确认无
CSP 违规、无混合内容（全部资源 https）。

iOS PWA 安装验证（真机）：

1. Safari 打开 `https://<域名>/`，确认首页、空闲教室、校历、e站通公开目录可用。
2. 「分享 → 添加到主屏幕」，保持「作为网页 App 打开」。
3. 从主屏幕图标启动，确认独立窗口、底栏与本地待办/专注可用；断网后冷启动应出现离线壳
   （首次安装需先在线完成 Service Worker 缓存）。
4. 更新验证：前端发布新 `web/sw.js` VERSION 后，重开两次页面确认取到新版本；
   旧版本静态缓存不应残留。

## 7. 访问日志与脱敏

**Caddy 访问日志**（JSON，输出到容器 stdout，由 compose 的 `json-file` 驱动按 10 MB×3 轮转）：

- 通过 `format filter` 的 `regexp` 过滤器删除 URI 的查询串（`[?].*` → 空），保留路径；
  账号、座位、日期等查询参数不会落盘。
- Caddy 默认把 `Cookie`、`Set-Cookie`、`Authorization` 记为 `REDACTED`；不记录请求体。
- 不记录业务响应内容；`/api/*` 的 POST 路径只出现在路径字段。

**应用日志**（`LOG_LEVEL=INFO`，`upcp.access` logger）：

- uvicorn 访问日志已关闭（`--no-access-log`），由应用中间件输出固定字段：
  `request request_id=... method=... path=... status=... duration_ms=...`；
  `path` 会折叠 `/api/library/<馆区>` 的任意段，不包含 query、请求体、Cookie、账号、IP 或上游内容。
- 异常只记录 `request_id` 与异常类型名；启动/迁移/保留清理失败记录固定短字段。
- 不要把 `LOG_LEVEL` 调成 DEBUG 后把日志长期留存；排障结束后恢复 INFO。

访问日志验证（先触发一次带 query 的请求，再确认日志无 query）：

```sh
curl -sS -o /dev/null "https://$HOST/healthz?probe=LOGSECRET"
$COMPOSE logs --no-log-prefix --since 5m caddy | grep -o '"uri":"[^"]*"' | tail -5
$COMPOSE logs --no-log-prefix --since 5m caddy | grep -c 'LOGSECRET' || true   # 期望 0
$COMPOSE logs --no-log-prefix --since 5m app | grep -c 'probe=' || true        # 期望 0
```

## 8. 备份与恢复

### 8.1 备份

```sh
cd /srv/upcpocket
./deploy/backup.sh
# 输出示例：备份完成：/srv/upcpocket/deploy/backup/upcpocket-20261122T031500Z.sql.age（123456 字节，保留 30 天）
ls -l deploy/backup/
```

- 备份在 db 容器内执行 `pg_dump --no-owner --no-privileges`，流式经宿主机 `age -r "$AGE_RECIPIENT"`
  加密，明文 SQL 不落盘；脚本不打印 DSN/密码。
- 默认目录 `deploy/backup`（`BACKUP_DIR` 可改，例如固定到 `/backup`：
  `BACKUP_DIR=/backup ./deploy/backup.sh`）、保留 30 天（`RETENTION_DAYS` 可改）；
  失败非零退出并删除半成品。`AGE_RECIPIENT` 默认从 `deploy/.env` 读取。
- Redis **不备份**：其中只有会话、限流计数与加密后的图书馆会话，重启/重建只会导致用户重新登录
  （见 10.2 的会话清理说明），不是业务数据。
- 备份文件是密文，可安全复制到另一台机器/对象存储；但**本仓库不自动上传**，异地同步由运维在
  授权后自行配置，并保证 age 私钥不在同一位置。

定时（cron，示例 03:15，仓库根）：

```cron
15 3 * * * cd /srv/upcpocket && ./deploy/backup.sh >> /var/log/upcpocket-backup.log 2>&1
```

或 systemd（更易看失败状态；本仓库不附带该单元文件，按需创建）：

```ini
# /etc/systemd/system/upcpocket-backup.service
[Unit]
Description=UPC Pocket encrypted PostgreSQL backup
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=/srv/upcpocket
ExecStart=/srv/upcpocket/deploy/backup.sh
User=root
```

```ini
# /etc/systemd/system/upcpocket-backup.timer
[Unit]
Description=Daily UPC Pocket backup

[Timer]
OnCalendar=*-*-* 03:15:00
Persistent=true

[Install]
WantedBy=timers.target
```

### 8.2 恢复演练（建议每季度一次，至少发布前演练）

```sh
cd /srv/upcpocket
AGE_IDENTITY_FILE=~/upcpocket-backup.key ./deploy/restore.sh \
  deploy/backup/upcpocket-20261122T031500Z.sql.age --yes
```

- 必须显式传 `--yes`；在终端交互时还会要求输入 `restore` 二次确认。脚本会：停止 app →
  断开连接并 `DROP`/`CREATE` 数据库 → `age -d` 流式解密导入（`psql -v ON_ERROR_STOP=1`）→
  重启 app。
- 恢复后检查：`ps` 全部 healthy；`curl https://$HOST/healthz` 为 ok；浏览器完成一次学校登录、
  只读查询与预约列表；核对关键表行数/时间范围（`pg_dump --schema-only` 或只读 SQL）。
- 若导入中途失败，数据库可能不完整：修复后重新执行脚本（会再次从干净库重放）。
- 恢复使用 age 私钥；演练结束把私钥放回离线介质，不要把私钥留在服务器上。

## 9. 密钥轮换

### 9.1 登录签名密钥（`LOGIN_KEY_ID` / `login_keys/`）

应用会加载 `LOGIN_KEY_DIR` 下所有 `*.pem`（kid = 文件名去扩展名），当前 `LOGIN_KEY_ID` 用于加密
登录信封；旧 kid 保留期间用于解密旧客户端仍在提交的信封。

1. 生成新 kid 到临时目录（**不要**对 `deploy/secrets` 直接 `--force`，那会同时重写
   `data_key`/`session_secret`，导致加密数据不可用）：

   ```sh
   TMP="$(mktemp -d)"; chmod 700 "$TMP"
   server/.venv/bin/python server/scripts/generate_keys.py --out "$TMP" --kid 2027-03
   sudo install -m 600 -o 10001 -g 10001 "$TMP/login_keys/2027-03.pem" deploy/secrets/login_keys/
   rm -rf "$TMP"                     # 临时目录里的 data_key/session_secret 未使用，一并删除
   ```

   若部署机没有保留宿主 venv，用第 3 节方式二（`docker run`）把 `--out` 指向 `$TMP` 等价生成，
   再执行同样的 `install` 与清理步骤。

2. 修改 `deploy/.env`：`LOGIN_KEY_ID=2027-03`。
3. 重建 app：`$COMPOSE up -d app`（会重建容器并重新读取密钥目录）。
4. 验证：`curl https://$HOST/api/auth/key` 返回的 `kid` 为新值；新登录成功；核对 `ps` 健康。
5. 保留旧 `<kid>.pem` **24 小时–30 天**（覆盖用户仍打开旧页面的窗口），之后删除旧 pem：
   `sudo rm deploy/secrets/login_keys/2026-11.pem`。删除后旧客户端会收到 `invalid_envelope`，
   刷新页面即可重新取公钥。

### 9.2 数据加密密钥（`DATA_KEY_VERSION` / `DATA_KEY_OLD`）

`data_key` 用于 AES-256-GCM 加密数据库敏感列与 Redis 中的图书馆会话，密文头带版本字节。
轮换是"新写用新版本、旧数据用旧密钥解"：

1. 生成新 32 字节密钥；把旧密钥转 hex（`xxd -p -c 64 deploy/secrets/data_key`）。
2. 新密钥写入 `deploy/secrets/data_key`；`deploy/.env` 升 `DATA_KEY_VERSION`（如 2），并追加
   `DATA_KEY_OLD=1:<旧 key 的 64 位 hex>`（格式 `版本:hex`，可逗号分隔多个旧版本）。
3. `$COMPOSE up -d app` 重建。验证登录、预约记录与只读查询。
4. 观察 ≥ 一个数据保留周期（见 `server/app/config.py` 的 `retention_*`）后，确认不再有旧版本密文，
   再移除 `DATA_KEY_OLD` 并重建。

> `data_key` 丢失 = 加密数据不可恢复；轮换前必须先做一次可恢复的备份演练。不要在
> `deploy/secrets` 上使用 `generate_keys.py --force`。

### 9.3 会话签名密钥（`session_secret`）

该密钥同时派生 `identity_hmac`、IP/UA 哈希与限流键。**更换会让新旧用户的身份哈希改变，
现有数据库里的按用户数据无法再关联**，因此只在怀疑密钥泄露时使用，并先备份数据库、
评估历史数据；日常"强制下线"请用 10.2 的 Redis 会话清理，不要轮换该密钥。

确需轮换：备份 DB → 生成新 `session_secret`（`openssl rand -out deploy/secrets/session_secret 32`
或重新运行 `generate_keys.py` 到临时目录后 `install`）→ `$COMPOSE up -d app`。轮换后：
所有用户需重新登录，且旧 `identity_hmac` 对应的历史记录不再归属新身份（按数据保留策略处理）。

## 10. 应急开关

### 10.1 一键关闭预约

```sh
cd /srv/upcpocket
# 编辑 deploy/.env：BOOKING_ENABLED=false
$COMPOSE up -d app
curl -sS "https://$HOST/api/library/session"     # 期望 {"state":"unavailable"}
$COMPOSE logs --since 2m app | tail -20
```

关闭后只读查询仍可用，前端会回退到学校官方入口；预约/取消接口返回 `booking_disabled`（403），
不会向学校写入。恢复时改回 `true` 并 `up -d app`。

### 10.2 强制失效会话（全部用户下线）

Redis 键约定：`upcp:sess:*`（浏览器会话）、`upcp:lib:*` / `upcp:libabs:*`（按用户的图书馆会话），
`upcp:rl:*`（限流计数）。只清浏览器会话即可让所有人重新登录：

```sh
$COMPOSE exec -T redis sh -c '
  export REDISCLI_AUTH="$REDIS_PASSWORD"
  redis-cli --no-auth-warning --scan --pattern "upcp:sess:*" | xargs -r redis-cli DEL
'
```

需要同时清掉图书馆会话（强制重新进行学校认证）时，把 pattern 依次换成 `upcp:lib:*`、
`upcp:libabs:*`；限流计数 `upcp:rl:*` 一般无需清（清理会短暂放宽登录限流）。

清空整个 Redis（会话 + 限流 + 缓存；会让所有用户重新登录，但**不破坏**数据库业务数据）：

```sh
$COMPOSE exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHDB'
```

## 11. 安全事件响应

按"止损 → 保留证据 → 轮换 → 通知 → 复盘"执行，顺序可按影响调整：

1. **止损**：`BOOKING_ENABLED=false` 关闭写入；严重时 `$COMPOSE stop app` 停站（证书续期会暂停，
   尽快恢复或保持 Caddy 运行只停 app）。保留 `docker compose ps`、容器启动时间、`logs` 片段。
2. **评估影响面**：看 Caddy JSON 日志的 `status`/`path`（不含 query）与应用固定字段日志；
   不要在聊天/工单里粘贴未脱敏的账号、Cookie 或业务内容。日志已按第 7 节脱敏，不要用
   `docker inspect` 导出含 `environment` 的完整容器配置（含数据库/Redis 密码）。
3. **轮换凭据**（按怀疑范围选择）：
   - 登录私钥：9.1；旧 pem 删除前先确认没有未完成排障。
   - 数据密钥：9.2（必须先备份且验证恢复流程）。
   - 会话密钥：9.3（会断开身份关联，慎重）。
   - 数据库/Redis 密码：
     - PostgreSQL：先 `ALTER USER upcpocket WITH PASSWORD '<新密码>';`，再同步
       `deploy/.env` 的 `POSTGRES_PASSWORD` 与 `DATABASE_URL`，`$COMPOSE up -d`（重建 db/app；
       已有数据卷不受影响）。
     - Redis：更新 `REDIS_PASSWORD` 与 `REDIS_URL` 后 `$COMPOSE up -d redis app`；Redis 重新以新
       密码启动，客户端重连。**不要**只改一处导致 app 连不上。
   - 同时强制失效会话（10.2）。
4. **通知**：若确认个人信息被访问/泄露，按适用法律法规与学校要求通知受影响用户和主管部门；
   说明时间范围、数据类型、已采取措施。不要公开他人学号、预约内容或日志原文。
5. **复盘**：时间线、根因、影响面、检测与响应是否及时、改进项；更新本手册与 `docs/PRIVACY.md`
   的相关说明（文档归主仓库维护）。

## 12. 明文扫描验收（定期执行）

目标是确认日志、Redis、数据库与备份中**没有**明文密码、明文业务凭据或未脱敏的个人数据。
所有命令只读或输出到终端，不把明文写入文件。

### 日志

```sh
$COMPOSE logs --no-log-prefix --since 24h app caddy |
  grep -Ein '(password|passwd|secret|token|authorization|set-cookie|cookie=|学号)' | head -20
# 期望：无匹配，或仅出现固定字段名/脱敏占位（如 REDACTED）；发现真实值立即按第 11 节处理。
$COMPOSE logs --no-log-prefix --since 24h caddy | grep -c '"uri":"[^"]*?' || true   # 期望 0
```

### Redis

```sh
# 键名（应为不透明 ID/哈希；不应出现学号、密码、Cookie 值）
$COMPOSE exec -T redis sh -c '
  export REDISCLI_AUTH="$REDIS_PASSWORD"
  redis-cli --no-auth-warning --scan | head -50
'
# 抽样值（图书馆会话应为 AES-GCM 密文；浏览器会话为不透明 ID）
$COMPOSE exec -T redis sh -c '
  export REDISCLI_AUTH="$REDIS_PASSWORD"
  redis-cli --no-auth-warning --scan | head -20 | while read -r k; do
    redis-cli --no-auth-warning TYPE "$k"; done | sort | uniq -c
'
```

### 数据库

```sh
# 表清单与敏感列定义（密码/token 列应为哈希/密文/不存在）
$COMPOSE exec -T db psql -U upcpocket -d upcpocket -c '\dt'
$COMPOSE exec -T db psql -U upcpocket -d upcpocket -c '\d+ users'
# plain SQL 只走管道，不落盘；期望除列名/哈希/密文外无明文凭据
$COMPOSE exec -T db pg_dump -U upcpocket --no-owner --no-privileges upcpocket |
  grep -Ein '(password|passwd|secret|token|BEGIN .*PRIVATE)' | head -20
```

数据库以 `identity_hmac`（HMAC-SHA256）标识用户，图书馆凭据以 AES-GCM 加密列保存，审计表保存
哈希后的 IP/UA 与固定动作枚举；如上述扫描出现明文，按安全事件处理。

### 备份

```sh
ls -l deploy/backup/                                  # 只应有 upcpocket-*.sql.age
file deploy/backup/*.sql.age                          # 应为 data/age 加密文件，不是明文 SQL
head -c 32 deploy/backup/*.sql.age | grep -q age-encryption && echo "age 头正常"
find deploy/backup -maxdepth 1 -type f ! -name '*.sql.age' || echo "无其它文件"
grep -RIl 'INSERT INTO' deploy/backup/ && echo "发现明文 SQL！" || echo "备份目录无明文 SQL"
```

### 仓库与进程

```sh
git status --short deploy | head                       # 不应出现 secrets/backup/.env
git check-ignore -v deploy/.env deploy/secrets/login_keys/2026-11.pem
git ls-files deploy | grep -E 'secrets/|backup/|\.env$' || echo "无运行期文件被跟踪"
$COMPOSE exec -T app sh -c 'ps -o user,pid,args'      # 进程不应出现密钥/密码参数
```

## 13. 监控与告警（建议外部监控独立部署）

| 项目 | 方法/命令 | 告警阈值建议 |
| --- | --- | --- |
| 证书到期 | `openssl s_client -connect "$HOST:443" -servername "$HOST" </dev/null 2>/dev/null \| openssl x509 -noout -enddate` | 剩余 < 14 天；Caddy 自动续期，失败会持续告警 |
| 站点可用性 | 外部每 1–5 分钟 `GET https://$HOST/healthz`，要求 200 且 `"status":"ok"` | 连续 3 次失败 |
| 5xx | `$COMPOSE logs --no-log-prefix --since 1h caddy \| grep -o '"status":5[0-9][0-9]' \| wc -l` | 突增或比率 > 1% |
| 预约失败率 | Caddy 日志按 `path=/api/library/book` 统计状态码（4xx 用户/会话问题、5xx 服务问题） | 5xx 突增、`unknown` 结果增多时人工核对 |
| 上游延迟 | 应用日志 `path=/api/*` 行的 `duration_ms`（Caddy JSON `duration` 同步可算） | 持续升高或超 `UPSTREAM_TIMEOUT_SECONDS`(20s) |
| Redis | `$COMPOSE exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning INFO memory'`；`DBSIZE` | 内存接近 `maxmemory`(256MB) 或 ping 失败 |
| PostgreSQL | `$COMPOSE exec -T db psql -U upcpocket -d upcpocket -Atc "SELECT count(*) FROM pg_stat_activity;"`；`pg_database_size('upcpocket')` | 连接数接近上限、体积异常增长 |
| 磁盘 | `df -h /var/lib/docker`；`docker system df` | 使用率 > 80% |
| 备份 | `find deploy/backup -name 'upcpocket-*.sql.age' -mtime +1 \| head`（有输出=超过 1 天无新备份） | 每日检查；连续 2 天缺失 |
| 容器健康 | `$COMPOSE ps` | 任一服务非 healthy 持续 2 分钟 |

本版本没有内置 `/metrics` 指标端点：以上用外部探活 + Caddy/应用日志做基础监控。若需要严格意义上的
"预约失败率"业务指标，需要服务端后续增加固定口径指标（属 `server/` 范围，不在本次部署交付内），
当前不要用 HTTP 200 或容器 healthy 推断业务成功。

## 14. 更新与回滚

- 更新是**人工发布**：本仓库没有自动上传或自动发布脚本；部署机拉取经审查的提交后由运维执行
  构建与重启，不要在生产机直接改代码。
- 后端更新：`git pull`（仅运维授权的部署操作）→ `$COMPOSE build app` → `$COMPOSE up -d app`。
  启动时 `DB_AUTO_MIGRATE=true` 执行幂等迁移；涉及破坏性数据变更时先备份、再维护窗口执行，
  需要时先 `$COMPOSE exec -T app python scripts/migrate.py` 验证。
- PWA 静态更新：替换仓库 `web/` 文件并同步 `web/sw.js` 的 `VERSION`（前端维护），Caddy 只读挂载
  `../web`，无需重启 Caddy；但必须重新做第 6 节的 CSP/PWA 验证。
- 回滚：后端 `git checkout <上一个审查通过的提交>` → `$COMPOSE build app` → `$COMPOSE up -d app`；
  若已执行不可逆数据库迁移，用更新前备份按第 8.2 节恢复。回滚后同样跑健康检查与关键业务验证。

## 15. 已知边界与未验证项

本手册的部署工件在编写环境中**未执行 Docker 构建、未启动容器、未申请真实证书、未做真实备份/
恢复**（该环境没有 Docker daemon；也未配置真实域名/凭据）。实际验证与未验证项：

- 已验证：`docker compose config -q` 静态解析（见下）；Caddyfile 用 Caddy v2.11.9
  `caddy validate` 通过，并实测访问日志过滤器确实去掉 query；`backup.sh`/`restore.sh`
  通过 `bash -n` 与 `shellcheck`；镜像 tag 在 Docker Hub 存在；用服务端 venv 运行
  `generate_keys.py` 生成的布局可被 `Settings.from_env()`/`KeyRing.from_settings()` 加载。
- 未验证：镜像实际构建（`pip install ./server` 的网络与构建轮）、`docker compose up` 运行、
  Caddy 真实 ACME 证书与 HTTP/3、真实 pg_dump/age/恢复演练、真实学校 CAS 登录与真机 PWA 验收、
  ICP/公安备案与线上 DNS/安全组。
- 真实校园账号、预约与真机验收以主 agent 的验收记录为准；本手册不把构建成功或 HTTP 200 当作
  业务成功。
- 单机部署无高可用：db/redis 单实例，Caddy 单实例；Redis 丢失只导致重新登录，PostgreSQL
  需靠备份恢复。跨机房/多副本不在本配置范围。
- 当前版本没有内置指标端点；`deploy/` 也不负责日志外发。若接入外部日志平台，必须保留
  Caddy 的 query 过滤配置，否则会把查询参数带出去。

## 16. 常用命令速查

```sh
cd /srv/upcpocket
COMPOSE="docker compose -f deploy/docker-compose.yml --env-file deploy/.env"

$COMPOSE config -q                 # 静态预检（不需要 daemon）
$COMPOSE build                    # 构建 app 镜像
$COMPOSE up -d                    # 启动/更新全部服务
$COMPOSE ps                       # 健康状态
$COMPOSE logs -f --tail=100 app   # 应用日志（固定字段）
$COMPOSE logs -f --tail=100 caddy # 边缘访问日志（JSON、无 query）
$COMPOSE restart app              # 仅重启应用（不改配置）
$COMPOSE exec -T app python scripts/migrate.py           # 手工迁移
$COMPOSE exec -it app python scripts/delete_user.py --confirm   # 按学号删除一个用户的服务端数据（交互输入学号）
./deploy/backup.sh                                       # 加密备份
AGE_IDENTITY_FILE=~/upcpocket-backup.key ./deploy/restore.sh <file.sql.age> --yes   # 恢复（危险）

# 证书/安全头/健康检查
curl -fsS "https://$(grep -E '^PUBLIC_HOST=' deploy/.env | cut -d= -f2)/healthz"; echo
```

`delete_user.py` 只读取交互输入的学校登录名并删除对应服务端数据（预约记录级联删除，审计记录
保留动作但移除用户引用），不联系学校、不打印学号；执行前先确认这是用户本人的删除请求并记录
操作人/时间。
