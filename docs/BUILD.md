# 构建指南

## 环境

| 项 | 版本 |
| --- | --- |
| Android | minSdk 31 / compileSdk 与 targetSdk 36 |
| JDK | 17 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
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

## 签名与发布

可在被忽略的 `keystore.properties` 中配置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；私钥存放在仓库外。未配置时，当前 Gradle 配置使用本机调试签名，包括 `assembleRelease`，不能仅凭产物名称判断签名性质。

```powershell
.\gradlew.bat :app:assembleRelease
```

v0.2.7 下载包沿用已经验证的调试构建与原测试证书，只规范发布文件名，包内版本仍为 `0.2.7-lightly-preview`（versionCode 19）。没有重新构建为混淆生产包。自行构建的调试证书通常与下载版不同，不能保证直接覆盖安装。

发布包 SHA-256：

```text
87d4e1a87a65c6bf1668d1c95408963f03e23d641b9a1d3aaab195cf94acea50
```

签名证书 SHA-256（公开证书指纹，不是私钥）：

```text
07aaae87a235e3e1e99c364164a0a0774e27d8cbfd4bd68f32c03b31f1d06281
```

维护时保留既有 Compose / Material 3、手动 AppGraph、Room / DataStore 与 RemoteViews 架构；原始总体计划不改写，后续阶段另行记录。
