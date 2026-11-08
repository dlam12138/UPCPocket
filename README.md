<div align="center">

<img src="docs/images/app-icon.svg" width="80" height="80" alt="UPC Pocket 图标" />

# UPC Pocket

**把石大的日常，装进口袋。**

面向中国石油大学（华东）的轻量 Android 校园工具

[![Release](https://img.shields.io/github/v/release/dlam12138/UPCPocket?style=flat-square&label=Release&color=1B6B4A)](https://github.com/dlam12138/UPCPocket/releases/latest)
![Android 12+](https://img.shields.io/badge/Android-12%2B-5278C6?style=flat-square)
![APK 4.85 MiB](https://img.shields.io/badge/APK-4.85_MiB-1B6B4A?style=flat-square)
[![MIT](https://img.shields.io/badge/License-MIT-70757A?style=flat-square)](LICENSE)

[**下载 2.0.3 正式版**](https://github.com/dlam12138/UPCPocket/releases/download/v2.0.3/UPC-Pocket-2.0.3.apk) · [**国内加速下载**](https://gh-proxy.org/https://github.com/dlam12138/UPCPocket/releases/download/v2.0.3/UPC-Pocket-2.0.3.apk)

[版本与校验文件](https://github.com/dlam12138/UPCPocket/releases/latest) · [隐私说明](docs/PRIVACY.md) · [反馈问题](https://github.com/dlam12138/UPCPocket/issues)

<br />

<img src="docs/images/widgets-light.png" width="760" alt="今日课表、校园卡余额、唐岛湾电量三个桌面组件，内容为合成演示数据" />

<sub>今日课表、余额、电量，抬眼就能看见。图中内容为合成演示数据。</sub>

</div>

## 常用的，都放在一起

首页、课表、待办、专注四个入口，左右滑动切页，底部胶囊随手势流畅切换。

| 功能 | 日常怎么用 |
| --- | --- |
| **校园卡余额** | 保存学校账号，查询本人余额；换号后撤下旧数据 |
| **宿舍电量** | 唐岛湾直接查询，古镇口打开学校页面 |
| **空闲教室** | 记住上次校区；切换日期、校区和预设时段自动查询，自选节次手动查询 |
| **图书馆座位** | 三个馆区座位入口；唐岛湾新增按时段找座位与个人隐藏，保留按座位选 |
| **课表** | 周日开头的整周视图、今日课程、节次时间；查过的周可离线看 |
| **待办与提醒** | 快速添加、完成、编辑与删除；可设一次性提醒，首页同步展示近期任务 |
| **专注** | 番茄钟、专注记录与统计，陪你完成一段学习 |
| **桌面组件** | 今日课表、校园卡、电量；默认 2×2，课表可拉宽至 4×2 |

支持浅色、深色与主题配色。学校认证遇到验证码时可转到网页登录；更新和诊断报告集中在关于页。

<div align="center">
<img src="docs/images/home-2.0.3.png" width="300" alt="2.0.3 首页待办摘要与四项滑动导航，合成演示数据" />
<br /><sub>2.0.3 首页待办摘要与滑动导航，合成演示数据。</sub>
</div>

## 三步开始

1. **安装**：下载 APK，支持 Android 12 及以上。旧版可直接覆盖安装，无需卸载。
2. **设置账号**：首页右上角 → 设置 → 学校账号。学校密码加密保存在本机，用于正常学校认证。
3. **按需使用**：保存账号后回课表点右上角刷新；添加待办、按需设置提醒，再把常用组件放到桌面。

2.0 用户需手动安装一次；2.0.2 及含更新器的 2.0.1 复测版用户，可以在 **设置 → 关于 UPC Pocket → 检查更新** 升级。国内加速由第三方提供，下载困难时可切换官方链接；校验文件随 Release 提供。

## 更新方便，反馈也方便

- **应用内更新**：检查新版本、选择下载线路、查看进度，校验通过后由系统确认安装；不自动下载、不强制更新。
- **诊断报告**：关于页可导出或清除本机诊断记录，不记录账密、会话或课程正文；只在你主动分享时发出。
- **问题反馈**：在 [Issues](https://github.com/dlam12138/UPCPocket/issues) 描述机型、版本、操作过程与实际现象。不要公开账号密码、验证码或带授权参数的链接。

## 使用前了解一下

这是**非学校官方应用**，校园查询直接访问对应学校服务，不经本项目自建账号服务器转发。

空闲教室反映学校排课，临时占用以现场为准；图书馆候选与最终可约以学校返回和预约记录为准。本轮按时段找座位与个人隐藏**仅适配唐岛湾**，古镇口东区、求是仍沿用原流程。原生预约未进行真实提交验收。待办提醒需通知权限；精确闹钟未授权或系统限制后台时可能延后，系统强行停止后需重新打开应用。课表第 13–14 节缺少已确认的官方时刻时，会显示“时间待确认”。

安装包约 **4.85 MiB**，保留全部现有功能。为兼容已安装版本，沿用原开发证书，可覆盖安装保留数据。详细验证、已知限制与数据处理见 [2.0.3 发布记录](docs/releases/2.0.3.md) 和 [隐私说明](docs/PRIVACY.md)。

<details>
<summary><strong>开发与文档</strong></summary>

<br />

Kotlin · Jetpack Compose / Material 3 · Room / DataStore · WorkManager · RemoteViews

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest checkModuleBoundaries
```

使用 JDK 17+、Android SDK 36，默认构建 R8 + 资源压缩的通用 Release。机器路径、私钥和个人业务数据不入库。

[构建指南](docs/BUILD.md) · [项目文档](docs/README.md) · [开发规范](AGENTS.md) · [更新记录](docs/CHANGELOG.md) · [应用内更新机制](docs/updates/README.md)

研讨间研究资料保留归档，不属于当前产品功能；当前版本不含课程编辑、导出、课程提醒、充值或校园卡流水；待办提醒独立提供。

</details>

## 致谢

基于 [ZZUPocket](https://github.com/3beuml/ZZUPocket) 演进，保留上游版权与 [MIT 许可证](LICENSE)；悬浮导航的来源与改动见 [移植说明](docs/third-party/ZZUPocket-dock.md)。Android 开发技能的固定来源与 Apache-2.0 许可证见 [来源说明](docs/android-skills.md)。

感谢 [@kujochisaki3378](https://github.com/kujochisaki3378) 在 [Issue #1](https://github.com/dlam12138/UPCPocket/issues/1) 提出时间优先筛选和个人隐藏座位的建议。2.0.3 先在唐岛湾实现，其他馆区仍保留原流程。

### 本地开发候选

2.0.4 / code 25 正在本地验收超星两馆区布局、按时段找和个人隐藏；尚未正式发布。上方下载、截图、体积和功能范围仍对应 2.0.3。详见 [候选验收](docs/releases/2.0.4-chaoxing-library-parity.md)。
