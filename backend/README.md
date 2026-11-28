# UPC Pocket Backend

这是独立部署的 Spring Boot 服务端工程，不属于 Android Gradle 工程，也不会打入 APK。当前阶段提供账号密文存储、AES-GCM 信封加密与 CAS SPI；默认 CAS 适配器不发起网络请求。接入校方 CAS 前，必须先完成校方对服务标识、测试环境、出口白名单和安全流程的确认。

## 本地构建与 H2 验证

使用 JDK 21。Windows 在仓库根目录执行：

```powershell
.\backend\gradlew.bat -p backend --no-daemon --max-workers=1 test bootJar
```

Linux/macOS 在仓库根目录执行：

```sh
sh ./backend/gradlew -p backend --no-daemon --max-workers=1 test bootJar
```

存储单测通过独立的 H2 PostgreSQL 兼容内存库运行 Flyway V1 并调用 `Flyway.validate()`；加密与 CAS 单测不需要网络或 Docker。测试配置中的密钥只是一次性合成值，只能用于本机测试，不得复制到任何运行环境。

`bootJar` 只构建服务端 JAR。应用启动时会校验 `pocket.vault.initial-write-key-version` 非空，且该版本必须出现在已加载的主密钥版本中；DB 密码、主密钥和两把盲索引密钥都要通过受保护的外部来源提供。管理端 JWT 还需要配置 `pocket.admin.jwt.issuer`、`audience` 和 `jwk-set-uri`。示例中的 `.invalid` 地址仅允许本地上下文启动，不会验证真实管理令牌；部署时必须换成校方批准的 HTTPS 身份提供方配置。`POCKET_CAS_ENABLED` 默认 `false`，当前 CAS 占位适配器即使配置了端点也不会发送请求或报告已就绪。

## PostgreSQL 容器运行

1. 将 `.env.example` 复制为本机 `.env`，把四个 `*_SOURCE_FILE` 改为工作区之外的受保护文件路径。设置 `POCKET_VAULT_INITIAL_WRITE_KEY_VERSION` 为主密钥列表中实际存在的版本，并把三个 `POCKET_ADMIN_JWT_*` 值换成校方批准的签发方、受众和 JWK Set HTTPS 地址。`.invalid` 示例仅供占位，文件内容和 `.env` 都不能提交。
2. 准备数据库密码文件、版本化主密钥文件，以及两个独立的 32 字节 Base64 HMAC 密钥文件。主密钥文件格式为 `v1:<base64-32B>,v2:<base64-32B>`；主密钥、索引密钥和数据库密码分别保管。确保 Docker Compose 启动的容器用户可以读取这些文件，并按校方密钥保管要求限制宿主机 ACL。入口脚本会将只读 secret 挂载复制到容器 `/tmp` tmpfs 的私有 `0400` 文件后再启动 JVM。
3. 启动：

   ```sh
   docker compose up -d --build
   ```

Compose 使用 PostgreSQL 16.16，数据库只接入内部网络且不发布宿主机端口；应用另接出站网络，供未来经批准的上游适配器使用。应用端口只在容器网络内暴露，生产 HTTPS 应由校方网关终止。数据库文件保存在命名卷中。不要用 `docker compose down -v` 作为常规停机步骤。

应用读取密钥文件时拒绝符号链接；在支持 POSIX 权限的文件系统上，文件不能对 group 或 other 可读。Windows 等不提供 POSIX 权限视图的平台会跳过该位检查，部署前必须由运维核验宿主机 ACL，确认只有授权的 Docker 主机/容器能读取 secrets。

Flyway 在应用启动时执行迁移。正式环境应使用校方提供的 PostgreSQL 16 实例、TLS 与备份策略，不要把生产密钥或数据库备份用于开发和测试。

## 密钥注入与轮换

- `EnvMasterKeyProvider` 接受受保护文件或环境变量中的 `版本:Base64` 主密钥列表，每个 KEK 为 32 字节。开发实现使用 AES-256-GCM 包裹随机 DEK；生产环境应通过 `MasterKeyProvider` 替换成校方 KMS/专用密钥服务适配器。
- 登录盲索引与审计主体引用使用两把不同的 32 字节 HMAC-SHA-256 密钥。轮换时需保留旧键版本，直到对应数据完成转换。
- 加入新 KEK 后，由受控流程切换 `vault_policy.write_key_version`，再以有界批次读取待重包账号。每行更新都同时核对 `account_id`、`credential_generation`、旧 `key_version` 与旧 `wrapped_dek`，并只重包 DEK；载荷密文不变。以剩余旧版本记录数归零和对应审计结果作为完成依据，然后才能按校方流程退役旧键。
- 不在命令行参数、日志、仓库 `.env`、故障报告或聊天中粘贴密钥。密钥生成、备份和恢复使用校方批准的秘密管理流程。
- 应用自有的密码字符数组、明文 UTF-8 暂存和 DEK 缓冲区会在 `finally` 路径清零；无法逐份证明 JVM、密码学提供方、TLS 和 Jackson 内部临时缓冲区均已擦除。部署侧应继续关闭堆转储与 core dump，并确保网关、应用日志、错误监控和代理不落盘请求正文或认证头。

## 单活启停、回滚与恢复

当前会话端口是进程内存实现，运行实例必须保持单活。更新时先备份数据库并保留旧镜像，然后构建新镜像；停下旧 `pocket-backend` 后再启动新版本，不能滚动并行或临时启动第二实例。旧版本不能与新版本同时接收请求。

常规升级步骤：

```sh
docker image tag upcpocket-backend:local upcpocket-backend:previous
docker compose build pocket-backend
docker compose stop pocket-backend
docker compose up -d --no-deps pocket-backend
```

回滚应用镜像时，先停当前实例，再将 `upcpocket-backend:previous` 重新标记为 `upcpocket-backend:local` 并强制重建服务容器。不要尝试反向执行已应用的数据库迁移；迁移兼容性与回滚策略需逐版本审查。

恢复演练应使用隔离网络和独立数据库恢复备份，挂载相匹配的密钥版本，执行 Flyway 校验并确认服务状态。演练不得连接生产 CAS 或校园业务，也不得把生产账户密文复制到一般开发环境。恢复结果与审计记录需按校方规则脱敏保存。

## CAS 接入边界

`ConfiguredUpcCasVerifier` 默认关闭且不含 HTTP 客户端。`pocket.cas.login-url`、`service`、连接超时和读取超时只是后续扩展配置；配置完整不代表 CAS 已可用，当前 `verify()` 始终返回 `UpstreamUnavailable`。出现验证码或二次验证时必须停止原生自动提交并交给学校正常网页流程，不得绕过或重试密码。

**防锁号与匿名响应权衡**：匿名登录和无 Token 改密共用按 `loginTag` 的有界并发在途锁，两路匿名并发请求无论账号是否托管或停用均统一返回 `409 VERIFICATION_BUSY`。若账号因错密熔断审计失败遗留持久化 `pending_attempt_id` 或正有后台自动代登在途，为严守“单账号不同时/不重复向 CAS 提密防锁号”，匿名登录不会再次调用 CAS，直接返回 `401 SIGN_IN_FAILED`；在学校 CAS 恰好同时返回 `503/409` 的故障窗口下，该本地拒绝与未知/停用账号的上游返回存在状态码及耗时差异，属明确接受的安全权衡（正常情况下均统一返回 `401`）。

## Spring Boot 维护

Gradle 当前固定 Spring Boot 3.5.16。Spring 官方说明 3.5.16 是 3.5.x 的最后一个 OSS 版本；继续获得 OSS 支持需规划升级到 4.0.x/4.1.x，或按校方采购和维护要求采用商业支持。升级前应单独评估迁移兼容性，并持续跟进[官方发布记录](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)与[安全公告](https://spring.io/security)。
