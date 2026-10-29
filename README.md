# UPC Pocket（石大校园助手）· 轻量版

中国石油大学（华东）校园助手的 **Android 本地原型**。

> **本项目是非官方、非商业的个人项目**，与学校不构成隶属或合作关系。
> 本原型**不代理**校园账号密码，也不把受保护数据转发到自建服务器；空闲教室读取学校匿名公开数据，
> 图书馆座位在用户主动登录官方 CAS 后使用原生界面；校园卡余额按本人学号直接查询，不需要登录 ——
> 详见下方「隐私与边界」。

> 当前阶段：**轻量预览版 0.2.2-lightly-preview**（versionCode 14，2026-10-29）。
> 本版在 0.1.10 的 UPC 业务基线上先后删除了课表功能、「图书馆主页」「数字石大」入口和
> 校园卡流水/平台登录；第三步收拢了电费入口、修复唐岛湾楼号选择，并把校园卡余额表单直接
> 放到首页。保留图书馆座位、校园卡余额、空闲教室、番茄钟与通用设置。
> 范围、数据兼容与验证见
> [`docs/0.2.0-轻量版-移除课表-2026-10-29.md`](docs/0.2.0-轻量版-移除课表-2026-10-29.md)、
> [`docs/0.2.1-轻量版-第二步-2026-10-29.md`](docs/0.2.1-轻量版-第二步-2026-10-29.md) 与
> [`docs/0.2.2-轻量版-第三步-2026-10-29.md`](docs/0.2.2-轻量版-第三步-2026-10-29.md)。
> 产品与合规背景见 [`UPC-Pocket-总体计划-v0.1.md`](UPC-Pocket-总体计划-v0.1.md)（原文保留）。

---

## 当前已支持

**首页 · 校园服务**

- 首页只有 3 个服务入口和一个功能表单：电费、空闲教室、图书馆座位、校园卡余额
- 电费：一个入口 → 选校区。唐岛湾在 App 内选楼号＋房间号查询剩余电量（度）；
  古镇口仍打开学校页面（域名证书已过期，不绕过 TLS），不把两校区都说成原生接入
- 唐岛湾楼号选择：楼号来自学校页面列表；加载失败会显示原因和「重新加载楼号」，
  不会留下无法恢复的禁用下拉；已保存的楼号不会被列表回调覆盖
- 输入记忆：唐岛湾的楼号与房间号以同一份配置持久保存，进入页面/进程重启自动回填，
  并提供明确清除；查询失败不丢输入，不随输入自动发起查询
- 校园卡余额：输入框和查询直接嵌在首页，没有二级入口；输入本人学号查询余额与过渡余额，
  不需要登录、不提供充值，也没有流水
- 空闲教室：原生筛选与结果列表，读取学校公开查询；支持日期、校区、楼宇与节次；不提供预约
- 图书馆座位：官方 CAS 网页登录后，原生选馆区、日期、房间、座位与时间，用户确认后提交；支持我的预约与取消，不自动抢座、不轮询

**专注（番茄钟）**

- 计时引擎（墙钟计时，进程被杀后可恢复）、前台服务 + 常驻通知
- 三档预设（25/5、50/10、90/15）与自定义时长，自动开始下一段
- 标签管理、手动补记；标签可自由创建，不再从课程名派生

**学习统计**

- 今日 / 本周 / 本月 / 本年 / 全部，热力图、标签分布、每日明细

**更多**

- 学校登录开关（目前只有图书馆 token 的 Keystore 加密副本）、退出学校登录
- 配色与深色模式（Material You 动态取色 + 8 套内置配色）
- 关于：当前能力、数据来源与边界

---

## 本版删除的课表功能

以下能力在 0.2.0 已**整体删除**（不是隐藏入口）：

- 教务 / CAS 课表导入、登录后同步学校课表（jwxt 页面传输、协议、读保护、同步计划）
- 旧学校课表同步与学校课表协议
- CSV / 剪贴板 / 浏览器导出脚本导入，课程与日程的编辑、撞课提醒
- 课表网格、课程总览、节次作息、学期与教学周设置、自定义日程
- 桌面小组件「下一节课」（2×2）与「今明课表」（4×2）及其刷新闹钟与注册
- 课表专属设置：格子样式、背景壁纸（含裁剪/模糊）、演示课表、显示设置
- 首页今天页的时间轴与课程区块、校园服务里的教务课表入口

保留的登录与网络能力（图书馆所需的 CAS 登录跳转、Cookie 会话与网络链路）**没有删**。

## 0.2.1 删除的入口与流水

- 校园服务里的「图书馆主页」「数字石大」外部入口及其目录项。
- 校园卡流水：流水 UI/日期分页、校园卡平台登录页、平台余额客户端/服务、流水请求与解析、
  平台令牌持久化与设置里的平台会话说明。
- 校园卡保留**唯一入口**：输入本人学号，直接查询校园卡余额与过渡余额（`getCardInfo`）。
  不登录、不提供充值、不提供流水。
- 图书馆座位原生查询/预约/取消、登录、会话持久化与共享 CAS 修复完整保留；
  CAS 与 libspace 的 Cookie 清理仍然服务于图书馆。

## 0.2.2 电费收拢与输入记忆

- 首页的两个电费卡片合并为一个「电费」入口，进入后选择唐岛湾 / 古镇口。
- 唐岛湾只保留 App 内查询：去掉浏览器、微信、分享等备用入口与外跳图标，
  页面只保留楼号、房间号、查询与结果。
- 楼号选择改为滚动列表对话框：可滚动、可点选、失败可重新加载；不再依赖
  一个会在加载失败后永久禁用的下拉按钮。
- 楼号与房间号保存到 DataStore（`PowerInputRepository`），校园卡学号独立保存
  （`CardInputRepository`）；两者都为将来的 RemoteViews 组件暴露 Flow 读取 API。
- 校园卡余额从「目录卡片 → 第二页」改为首页内嵌 `DirectCardBalanceSection`，
  不再有中间跳转、平台登录、流水或充值。

---

## 数据兼容（重要）

- 应用标识仍是 `com.upcpocket.app`，覆盖安装即可升级；**不要卸载旧版**（卸载会清掉本地数据）。
- Room 数据库仍是 `version = 1`。课表的五张旧表（`semesters`、`courses`、`course_sessions`、
  `periods`、`display_overrides`）**只保留表结构声明**，没有 DAO、Repository 或界面读写它们。
  这样已安装用户的数据库校验值不变，升级后能直接打开，专注记录与标签不受影响。
- 没有启用 `destructiveMigration`，也没有在升级时删库或清 DataStore。
- 电费输入（`power_building` / `power_room`）与校园卡学号（`card_student_id`）
  新增在原有的 `ui_prefs` DataStore 中，`power_building` 沿用旧键；升级不会清掉已有楼号。
- 旧 DataStore 里的课表样式键（壁纸路径、格子密度、演示模式、教学周参数）不再读取；
  文件本身保留，不影响其它偏好。
- 用户设备上已有的课表历史数据仍在数据库文件中，只是 App 内没有入口可访问；
  本版不主动删除它们。

---

## 下载与安装

本仓库**不发布 APK**。自行构建（见下）；构建产物在：

```
app/build/outputs/apk/debug/app-debug.apk
```

- 系统要求：**Android 12（API 31）及以上**
- 应用标识：`com.upcpocket.app`，**与上游 ZZUPocket（`com.zzupocket.app`）是两个不同的应用**，
  不能互相覆盖安装，也不会互相读到对方的数据
- 从任意旧预览版（含 0.1.10 / 0.2.0 / 0.2.1）升级时直接用新 APK 覆盖安装即可；本版 versionCode 为 14

---

## 构建

### 环境

| 项 | 版本 |
| --- | --- |
| JDK | 17 或以上（本轮在 **JDK 21** 上验证） |
| Gradle | 8.9（wrapper 自带，国内镜像） |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 36 |
| minSdk | 31 |

`local.properties`（**不入库**）里写 SDK 路径：

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

> Windows 路径请用正斜杠：`sdk.dir=E:/AndroidSdk`。写成 `E:\AndroidSdk` 会被
> Properties 解析成 `E:AndroidSdk`，Gradle 报 `Invalid file path`。

### 命令

```bash
# 调试包
./gradlew :app:assembleDebug          # Windows: .\gradlew.bat

# 单元测试
./gradlew :app:testDebugUnitTest

# 一起跑（本项目常规验证口径）
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

> 如果 `JAVA_HOME` 指向 JDK 8（某些机器默认如此），需要先切到 17+，否则 Gradle 起不来。

### 签名

`keystore.properties`（**不入库**）可选：

```properties
storeFile=/absolute/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

没有该文件时 release 会用 debug 签名。debug 与 release 刻意使用**同一套签名**，
避免切换时因签名不同而必须卸载（本地数据会全丢）。

### 微信直达配置（可选）

正式移动应用 AppID **不写入仓库**。可在 `keystore.properties`（已 gitignore）或本机
`~/.gradle/gradle.properties` 配置：

```properties
wechatAppId=wx1234567890abcdef
```

构建时写入 `BuildConfig.WECHAT_APP_ID`。为空时 App 只显示复制/分享兜底，不发送 SDK 请求。

---

## 技术栈

| 方面 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0 + Jetpack Compose + Material 3 |
| 依赖注入 | 手写容器 `core/AppGraph.kt`（依赖很少，不引入 Hilt） |
| 本地存储 | Room（专注记录与标签；课表旧表仅保留结构）、DataStore（偏好）、kotlinx.serialization（计时快照） |
| 网络 | 平台 HTTPS 客户端，用于空闲教室、图书馆座位、校园卡与唐岛湾电费；图书馆登录使用官方 WebView，校园卡余额按学号直连查询 |
| 后台 | 前台服务（番茄钟）。**没有 WorkManager，没有桌面组件，没有周期任务** |
| 微信 | 腾讯开放平台 Android SDK 6.8.40（OpenWebview / 小程序请求），AppID 未配置时禁用直达 |
| 后端 | **没有**，也不会有 |

### 代码结构

```
app/src/main/java/com/upcpocket/app/
├── core/         手动依赖容器 AppGraph、学校业务 Cookie 清理
├── data/
│   ├── db/       Room 实体与 DAO（专注 2 张表；课表旧表仅保结构）
│   ├── local/    官方入口目录
│   ├── classroom/ 空闲教室公开查询协议（无认证、无凭据）
│   ├── library/  图书馆座位会话、签名、数据层（token 仅内存）
│   ├── ecard/    校园卡余额（按学号直连查询与本地缓存）
│   ├── power/    唐岛湾剩余电量查询
│   ├── school/   官方登录跳转（图书馆座位使用）
│   ├── wechat/   微信 SDK 适配（AppID 为空时禁用）
│   ├── prefs/    DataStore：主题、记住登录、电费楼号；番茄钟偏好
│   └── repo/     专注仓库
├── domain/       纯数据模型与统计口径（专注）
├── focus/        番茄钟引擎、通知、前台服务
└── ui/           Compose：home / focus / campus / classroom / library / ecard / power / settings / about / theme / nav
```

---

## 隐私与边界

- **核心功能不需要账号**；只有图书馆座位需要用户在学校官方 CAS 页面登录。
  校园卡余额按学号直接查询，不需要登录。
- **不保存密码**。图书馆 seat token 默认只在进程内存；开启“记住登录”时，只有图书馆 token
  用 Android Keystore 加密保存（不随系统备份）。退出登录或关闭开关会清理。
  校园卡余额只在本机保存学号、两项金额与读取时间；唐岛湾保存楼号与房间号。
  两者都可以在对应功能区清除，不随输入自动联网。
- **有 `INTERNET` 权限**，用于用户主动打开的公开查询与官方登录；专注与统计离线可用。
- **数据只在本机**：专注记录与标签在本机数据库，没有自建服务器，App 不会上传。
  卸载 App 会一并清掉。
- **系统备份也关掉了**：`android:allowBackup="false"`，并且
  `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml` 对
  database / sharedpref / file / external 逐域排除。
- **空闲教室只读公开数据**，请求仅携带查询条件，不带认证 Cookie。
- **图书馆登录页只作为官方登录步骤**，登录成功后立即关闭并清理 WebView；
  功能界面是原生查询/选座，不抓取网页、不自动提交。
- **不采集**：没有统计 SDK、没有崩溃上报、没有广告。
- 仓库本身**不接收**任何把账号密码、令牌、Cookie、学生业务数据写进代码或文档的提交。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [`UPC-Pocket-总体计划-v0.1.md`](UPC-Pocket-总体计划-v0.1.md) | 产品定位、合规边界、校园服务台账、里程碑（原文保留） |
| [`docs/0.2.0-轻量版-移除课表-2026-10-29.md`](docs/0.2.0-轻量版-移除课表-2026-10-29.md) | 第一步范围：删除清单、保留清单、数据兼容取舍、构建与点测结果 |
| [`docs/0.2.1-轻量版-第二步-2026-10-29.md`](docs/0.2.1-轻量版-第二步-2026-10-29.md) | 第二步范围：删除两个入口与校园卡流水/平台登录，保留余额与图书馆，构建与点测结果 |
| [`docs/0.2.2-轻量版-第三步-2026-10-29.md`](docs/0.2.2-轻量版-第三步-2026-10-29.md) | 第三步：唐岛湾楼号选择修复、电费入口收拢、输入持久化、校园卡表单上首页 |
| [`docs/0.1.10-教务认证后连接与白屏修复-2026-10-28.md`](docs/0.1.10-教务认证后连接与白屏修复-2026-10-28.md) | 0.1.5–0.1.10 的登录与余额进展（历史记录，原文保留） |
| [`docs/android-skills.md`](docs/android-skills.md) | 引入的 android/skills 版本、索引与更新流程 |
| [`docs/upc/官方入口.md`](docs/upc/官方入口.md) | UPC 官方入口台账与匿名核查状态（当前有效） |
| [`docs/README.md`](docs/README.md) | 文档总索引：UPC 入口、研究方法与历史归档 |
| [`AGENTS.md`](AGENTS.md) | 项目开发规范（对 Agent 与本人都适用） |

> 郑州大学的接口契约与设计资料已移入 [`docs/archive/zzu/`](docs/archive/zzu/README.md)，
> 仅作为上游历史参考；**不要**据此接入任何校园系统。

---

## 致谢

本项目以 [ZZUPocket](https://github.com/3beuml/ZZUPocket)（MIT）为 Android 参考底座，
保留了它的番茄钟、统计与原生查询页面的设计与部分实现。
**上游的郑州大学认证、教务 / 一卡通接口、课表、宿舍房间规则没有复用。**

上游曾参考的社区项目（均为 MIT，本原型已不包含其代码）：
[Illustar0/ZZU.Py](https://github.com/Illustar0/ZZU.Py)、
[Elykia093/ZZU-Electricity-Monitor](https://github.com/Elykia093/ZZU-Electricity-Monitor)、
[rimrose/ZZU-Dorm-Electricity-Balance-Base](https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base)。

Android 技术指导：[android/skills](https://github.com/android/skills)（Apache-2.0），见 `docs/android-skills.md`。

---

## 许可证

[MIT](LICENSE) © 2026 3beuml（上游作者）

本项目作为 ZZUPocket 的衍生作品，沿用同一 MIT 许可证；上游版权声明保留在 `LICENSE` 中。
