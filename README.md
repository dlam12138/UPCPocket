<div align="center">

# UPC Pocket 2.0

**余额、电量、空教室、课表，把常用校园服务放进口袋。**

面向中国石油大学（华东）的轻量 Android 工具

![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Version](https://img.shields.io/badge/version-2.0-5278C6)
[![License: MIT](https://img.shields.io/badge/License-MIT-8B93A6)](LICENSE)

[**下载 2.0 正式版**](https://github.com/dlam12138/UPCPocket/releases/tag/v2.0) · [隐私与数据](docs/PRIVACY.md) · [更新记录](docs/CHANGELOG.md) · [本地构建](docs/BUILD.md)

</div>

## 功能

| 功能 | 内容 |
| --- | --- |
| 校园卡 | 保存学校账号后查询本人余额；换号立即撤下旧数据 |
| 宿舍电量 | 唐岛湾原生查询；古镇口打开学校页面 |
| 空闲教室 | 两校区、日期、教学楼与节次筛选；只有所选节次均无排课才显示空闲 |
| 图书馆 | 唐岛湾、古镇口东区、求是座位；保存账号自动认证、座位图、时段与预约；验证码回退学校页面 |
| 课表 | 周日到周六、今日课程、节次时间、稳定课程颜色与离线缓存 |
| 专注 | 番茄钟、专注记录与统计 |
| 桌面组件 | 校园卡、电量、今日课表，默认 2×2，课表可扩至 4×2 |

首页、课表、专注三个入口；设置集中在首页右上角。研讨间不包含在当前版本，研究资料继续归档。

## 预览

<div align="center">
<img src="docs/images/widgets-light.png" alt="今日课表、校园卡和电量组件（合成预览）" width="300" />
&nbsp;
<img src="docs/images/classrooms-light.png" alt="空闲教室筛选与网格" width="300" />
</div>

组件预览为合成数据，教室图为公开数据的历史查询结果。

## 安装

1. 下载 [2.0 Release APK](https://github.com/dlam12138/UPCPocket/releases/tag/v2.0)，支持 Android 12 及以上。
2. 已安装本项目预览版时可直接覆盖安装；包名仍为 `com.upcpocket.app`，版本 `2.0`，versionCode `20`。
3. 在设置中保存学校账号，进入所需功能查询；课表组件需要先同步当前周。
4. 组件点击刷新，失败保留上次成功值与时间。2×2 表示桌面网格，不保证几何正方形；系统省电可能延迟课表本地重绘。

安装包使用 R8 与资源压缩，保留全部功能、通用 ABI；不含调试预览与合成组件。为兼容已有安装，2.0 沿用原开发证书，**是正式 Release 发布，签名仍为开发签名**，不是商店生产签名。无需卸载或清除数据。

## 数据与边界

- 非学校官方应用。校园服务直接请求对应学校系统，不经本项目自建服务器转发。
- 学校密码加密保存在本机；保存成功不等于认证成功。账号会话与个人缓存按账号隔离，删号时清理；专注记录保留。
- 空闲教室反映学校排课，临时占用以现场为准；13–14 节缺少确认的官方时间时显示“时间待确认”。
- 超星原生预约已实现，但未执行真实预约写入验收；提交结果未知时应先核对学校记录，不要连续重试。
- 不包含研讨间、课程编辑、导出、提醒、充值或校园卡流水。详细验证与限制见 [2.0 发布记录](docs/releases/2.0.md)。

## 开发与文档

Kotlin · Compose / Material 3 · Room / DataStore · WorkManager · RemoteViews

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest checkModuleBoundaries
```

使用 JDK 17+、Android SDK 36。机器路径与密钥不入库；默认交付精简 Release。参见 [构建指南](docs/BUILD.md)、[项目文档](docs/README.md) 与 [开发规范](AGENTS.md)。

## 致谢

基于 [ZZUPocket](https://github.com/3beuml/ZZUPocket) 演进，保留 [MIT 许可证](LICENSE)。上游学校的接口与业务事实不作为 UPC 接入依据。

Android 技能固定来源与 Apache-2.0 许可证见 [来源说明](docs/android-skills.md)。
