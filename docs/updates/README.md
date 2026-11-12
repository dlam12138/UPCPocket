# 应用内更新

更新器自 **2.0.2 正式版** 提供；当前正式旧版 **2.0.3（versionCode 24）** 同批发布 APK、签名清单与校验文件。2.0 不含更新器，需手动覆盖安装一次；2.0.1（22）复测包已包含更新器，可检查升级。以下 22→23 受控测试记录为开发时证据，当前发布状态以 [2.0.3 发布记录](../releases/2.0.3.md) 为准。

当前开发新版为 `com.upcpocket.next`（UPC Pocket 新版）；与旧包独立安装，更新线路分为新版 `update-next.json` / `update-next.sig` 与旧版 `update.json` / `update.sig`，不得用新包覆盖旧包更新资产。以下信任链和发布步骤以新版为准。

## 用户可见行为

- 入口只在“设置 → 关于 UPC Pocket → 更新”，显示当前版本；不增加首页卡或顶级导航。
- 前台每天最多自动检查一次，只取清单和签名；可在关于页关闭。发现新版只用短 Toast 提示，不弹窗、不后台周期联网、不自动下载，不打断登录/预约。
- 手动检查才打开更新弹窗。弹窗只显示版本、大小、3–5 条纯文本说明、“更新”和“稍后”。同一 versionCode 点“稍后”后不再自动提示；手动检查仍可再次查看。
- 下载显示进度、自动线路、切换线路和取消；先写 `.part`，完整校验后才显示“安装”。
- 安装由 Android 系统确认；不静默安装、不强制更新、不卸载旧包、不清除数据。未知来源设置只在用户点“安装”后打开，返回后复核；拒绝/取消不会自动循环。实现用 `PackageInstaller` 会话提交已校验 APK，系统显示最终更新确认，不使用静默安装权限。

## 信任链

1. 固定仓库 `dlam12138/UPCPocket`，固定候选线路：主加速 `gh-proxy.org`、备用 `ghproxy.net`、GitHub 官方。线路只改变传输，不提升信任。
2. 从同一候选源取 `releases/latest/download/update-next.json` 与 `update-next.sig`；对 `update-next.json` 的**原始 UTF-8 字节**做 `SHA256withECDSA` / ECDSA P-256 验证。
3. 验签通过后才解析固定字段；拒绝未知字段、非整数 `versionCode`、错误包名、非 `v<versionName>` tag、非 `UPC-Pocket-<versionName>.apk`、越界大小、非小写 SHA-256、3–5 条以外的 notes。
4. 用清单内的精确 tag/assetName 下载 APK；所有线路必须对应同一大小和哈希。不使用可变化 `latest` APK 链接。
5. 下载后校验大小、SHA-256、包名、内部 versionCode/versionName、minSdk 与当前安装证书兼容，再交给系统安装器。APK 证书不更换；镜像的 SHA 文本不能替代清单签名。
6. `highestVerifiedVersionCode` 只由已验签清单提升；更旧的合法清单不能覆盖已经观察到的最高版本。`versionCode` 按整数比较，不按字符串或发布时间。
7. APK 只位于应用私有 `cache/updates/`。最终安装通过 `PackageInstaller` 会话把已校验字节交给系统，由系统显示“更新此应用?”确认；不依赖跨进程 FileProvider URI 授权。仍保留独立 `${applicationId}.updates` provider 只暴露该子目录作为窄边界，但安装数据通道不是 content URI；没有广泛存储权限，也不复用诊断 provider。

公钥：[`upcpocket-update-public-key.b64`](upcpocket-update-public-key.b64)（DER SubjectPublicKeyInfo 的 Base64）。
公钥 SHA-256：`cc1b2fbb26e8ae5fba879ac907804edc524a7227ec825daa83e6cffb8ebff774`。

私钥**不在仓库**。发布脚本只从环境变量 `UPCPOCKET_UPDATE_PRIVATE_KEY` 读取本机仓库外路径，不打印私钥内容。该密钥只签更新清单，不是 APK 签名证书；新版 APK 使用独立长期 Release 证书
`c8e461698efe19ee6ad5fb635dd28eaf860596caea2b2756efa5ce6b2051a23c`，不与旧包互相覆盖。

## 清单字段

```json
{
  "schemaVersion": 1,
  "packageName": "com.upcpocket.next",
  "versionName": "2.0.4",
  "versionCode": 25,
  "minSdk": 31,
  "tag": "v2.0.4",
  "assetName": "UPC-Pocket-2.0.4.apk",
  "sizeBytes": 0,
  "sha256": "<64 lowercase hex>",
  "publishedAt": "2026-11-07T00:00:00Z",
  "notes": ["...", "...", "..."]
}
```

清单上限 64 KiB，签名上限 1 KiB，APK 上限 50 MiB。实际 `sizeBytes`/`sha256` 由脚本从 APK 读取，不由人工填写。

## 发布流程（准备，不自动上传）

1. 构建并验收 R8 Release APK，确认版本、签名和 R8 行为。
2. 准备 3–5 行纯文本更新说明（无 HTML、无控制字符）。
3. 在仓库外设置私钥路径并运行：

```powershell
$env:UPCPOCKET_UPDATE_PRIVATE_KEY = '<仓库外私钥路径>'
.\tools\release_update.ps1 `
  -Apk '<构建产物路径>\UPC-Pocket-2.0.4.apk' `
  -VersionName '2.0.4' -VersionCode 25 `
  -NotesPath '<更新说明路径>' `
  -OutputDirectory '<空输出目录>'
```

脚本调用 build-tools 的 `aapt2`/`apksigner` 核对包名、versionCode/versionName、minSdk、大小、签名证书，生成：
`UPC-Pocket-2.0.4.apk`、`update-next.json`、`update-next.sig`、`SHA256SUMS.txt`。脚本用内置公钥自校验，存在 `openssl` 时再用独立工具交叉验签。

4. 只有人工确认后才把四个文件上传到同一个 GitHub Release；**不得先公开再原地替换资产**。修复必须提升 versionCode 后重发。不上传私钥、GitHub token 或 R8 mapping。

5. 同仓库新版 Release 若设为 latest，除新版四件套，还必须原字节附带已发布 2.0.3 的 `update.json` / `update.sig`，继续指向 `v2.0.3` 的原 APK 资产；不重写、不重签、不提高旧包清单版本。旧客户端固定请求 latest 下这两个旧文件名，缺失会返回 404。首次新版也可先不设为 latest，此时新版 latest 检查尚不可用，不宣称已上线。本轮仅本地准备，未发布。

## 受控 E2E（仅 debug）

受控测试源只在 `BuildConfig.DEBUG` 且构建时显式传入以下参数时存在：

```powershell
.\gradlew.bat :app:assembleDebug `
  -PupdateTestBase=http://10.0.2.2:8765 `
  -PupdateTestPublicKey=<测试公钥 Base64>
```

测试公钥只用于本地测试夹具，不进入 release；`src/debug/res/xml/network_security_config.xml` 只对模拟器宿主机 `10.0.2.2` 放开明文，release 的网络策略不包含该域。E2E 高 versionCode 包必须用同一源码的独立 debug 构建与临时用户/独立模拟器验证，不能把测试包当作正式 22 包发布，也不能降低 release TLS 策略。

## 当前发布状态

- 2.0.3（24）正式发布，与 APK 同批提供 `update.json`、`update.sig` 和 `SHA256SUMS.txt`；更新签名密钥与 2.0.1 复测包一致。
- 公共加速源可能存在同步/缓存延迟，失败会尝试其他固定线路；无法获取可信清单时显示检查失败，不伪造最新版本。
- 2.0.1 本地候选记录保留在 `docs/releases/2.0.1.md`，其中未发布描述属于当时现场。
