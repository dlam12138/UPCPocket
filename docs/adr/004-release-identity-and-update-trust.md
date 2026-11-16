# ADR 004：独立新版身份与更新信任链

- 状态：Accepted（追溯既有实现）
- 记录日期：2026-11-16
- 依据：[新版身份记录](../releases/2.0.4-next-identity.md)、[当前正式发布](../releases/2.0.6.md)、[更新机制](../updates/README.md)及当前源码。

## 背景

新版采用独立长期 Release 签名，旧版使用历史签名。需要保留旧用户安装与数据，避免为换证书卸载/清数据；镜像可以传输 APK，但不能成为真实性依据。同仓库 latest 同时服务新旧更新客户端。

## 决策

新版 applicationId 为 `com.upcpocket.next`，源码/资源 namespace 保持 `com.upcpocket.app`，旧包 `com.upcpocket.app` 可并存；不跨包迁移或删除旧数据。Provider authority 基于 applicationId，系统回调按当前 Context 显式组件处理。

新版 Release 使用仓库外长期 keystore，配置缺失则构建失败，禁止 debug 签名回退。用户默认交付 R8+资源收缩 universal APK；验收后保留原字节，发布不能重编替换同一产物。

更新固定仓库、线路和内置 P-256 公钥；先验原始 UTF-8 清单再解析固定字段，下载后核大小、SHA、包名、内部版本/minSdk与已安装证书，最后由 PackageInstaller 交系统确认。不静默安装、不卸载/清数据，不依赖 GitHub API/PAT，镜像 SHA 文本不代替签名。

更新清单签名密钥证明元数据来源，APK 长期签名证书维持 Android 安装身份；两者职责不同，不能互相替代。

新版发布四件套 APK、`update-next.json`、`update-next.sig`、`SHA256SUMS.txt`；设 latest 时同批原字节带旧 `update.json`/`update.sig`，继续指向旧版 2.0.3，旧 Release 不变。公开资产不原地替换。签更新清单私钥在仓库外，发布脚本仅从环境变量读取路径且只准备文件，不推送或发布。

## 备选与理由

本次对照分析：同包换证书会破坏覆盖升级；卸载重装会丢数据且不符合授权；镜像哈希无法独立证明来源；只发新版清单会使旧客户端 latest 文件缺失。独立身份、两套清单与原始字节验签保留现有安装/更新契约。

## 影响与代价

旧版数据无法自动转入新版，首次使用需重新设置；发布多维护两个旧元数据附件。源码 namespace 与安装包身份不同，必须检查实际包/证书/Manifest，不能仅凭类路径或文件名判断。发布仍需用户授权，工具准备完成不代表已公开。

## 证据与验证

[app Gradle](../../app/build.gradle.kts)、[UpdateSignature](../../app/src/main/java/com/upcpocket/app/update/UpdateSignature.kt)、[UpdateApkVerifier](../../app/src/main/java/com/upcpocket/app/update/UpdateApkVerifier.kt)、[UpdateInstaller](../../app/src/main/java/com/upcpocket/app/update/UpdateInstaller.kt)、[release_update.ps1](../../tools/release_update.ps1)、[BUILD](../BUILD.md)。具体构建/安装/附件验签结果以对应 release 记录为准；当前文档任务不构建、签新附件或发布。
