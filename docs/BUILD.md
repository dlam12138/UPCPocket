# 构建指南

## 环境

| 项 | 版本 |
| --- | --- |
| Android | minSdk 31 / compileSdk 与 targetSdk 36 |
| JDK | 17+（本机 Android Studio JBR 21） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.31 |
| Compose BOM | 2024.12.01 |
| Room | 2.6.1 |

安装 Android SDK 36 与项目所需 Build Tools。通过环境变量配置本机 JDK，SDK 路径写入根目录 `local.properties`，例如 `sdk.dir=/your/android-sdk`。不提交本机路径或签名文件。

## 检查与构建

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Linux/macOS：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试包输出到 `app/build/outputs/apk/debug/`。测试成功不能替代本人设备上的校园接口验收；不要在测试中放入真实学号、Cookie、Token 或业务记录。

## 精简 Release

对用户交付默认构建 R8 + 资源收缩的 release，而不是 debug 包：

```powershell
.\gradlew.bat :app:assembleRelease
```

当前 `app/build.gradle.kts` 已启用 `isMinifyEnabled=true`、`isShrinkResources=true`，未升级 AGP/R8，也未删除既有 keep 规则。2026-11-05 晚本机同一源码树实测：

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `app-debug.apk` | 67,465,995 | `39F3A5357368940C45B27A700CF7052C597E72CE66C30A96117D2812CD0B90EC` |
| `app-release.apk` | 4,642,177 | `FE61368B88127FE092838F7D3A1601B8E8A60A60A10312DD9703DC57C44D3B3B` |

该 release 为 universal APK（arm64-v8a / armeabi-v7a / x86 / x86_64），不是 ABI 多包。`src/debug` 的合成 provider/预览 Activity 不会进入 release；构建后应检查 merged manifest 与 APK 资源，而不是只看源码目录。细节见 [三组件视觉修订](m5/2026-11-05-组件视觉修订.md)。

## 应用内更新清单与签名

更新清单验签公钥固定在仓库 `docs/updates/upcpocket-update-public-key.b64`，构建时读入 `BuildConfig.UPDATE_PUBLIC_KEY`。私钥只在开发机仓库外，通过 `UPCPOCKET_UPDATE_PRIVATE_KEY` 环境变量交给发布脚本；不要写入 Gradle、源码、日志或 Release 附件。

```powershell
$env:UPCPOCKET_UPDATE_PRIVATE_KEY = '<仓库外私钥路径>'
.\tools\release_update.ps1 `
  -Apk '<空目录>\UPC-Pocket-2.0.3.apk' `
  -VersionName '2.0.3' -VersionCode 24 `
  -NotesPath '<3–5 行纯文本说明>' `
  -OutputDirectory '<空输出目录>'
```

脚本使用 build-tools 的 `aapt2`/`apksigner` 核对包名、版本、minSdk、大小和原开发证书，生成 `update.json`、`update.sig`、`SHA256SUMS.txt`，并在本机自校验；有 `openssl` 时再做一次独立交叉验签。它只准备文件，**不上传、不 push、不修改 Release**。每次发布必须把 APK、清单、签名、SHA256SUMS 作为同一次 Release 的附件，公开后不原地替换资产；修复需提升 versionCode 后重发。

受控 E2E 只允许在 `BuildConfig.DEBUG` 且显式传入 `-PupdateTestBase`/`-PupdateTestPublicKey` 的 debug 包中使用，测试源不能进入 release；具体边界见 [应用内更新说明](updates/README.md)。release 的 `UPDATE_TEST_*` 字段为空，且 `BuildConfig.DEBUG=false`，不能借此降低 release TLS 策略。

## 签名与发布

可在被忽略的 `keystore.properties` 中配置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；私钥存放在仓库外。未配置时，当前 Gradle 配置使用本机调试签名，包括 `assembleRelease`，不能仅凭产物名称判断签名性质。

```powershell
.\gradlew.bat :app:assembleRelease
```

2.0.3 正式发布使用 `versionName=2.0.3`、`versionCode=24`，开启 R8 与资源压缩。为兼容已安装预览版，保留原开发证书；未配置生产密钥时必须如实注明开发签名。安装包和校验值以 [2.0.3 发布记录](releases/2.0.3.md) 及 GitHub Release 附件为准，不把 debug 包重新命名冒充精简 Release。

签名证书 SHA-256（公开证书指纹，不是私钥）：

```text
07aaae87a235e3e1e99c364164a0a0774e27d8cbfd4bd68f32c03b31f1d06281
```

维护时保留既有 Compose / Material 3、手动 AppGraph、Room / DataStore 与 RemoteViews 架构；原始总体计划不改写，后续阶段另行记录。
