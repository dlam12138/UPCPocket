# UPC Pocket（石大校园助手）

中国石油大学（华东）校园助手的 **Android 本地原型**。

> **本项目是非官方、非商业的个人项目**，与学校不构成隶属或合作关系。
> 本原型**不连接**任何校园系统、**不实现**校园认证、**不读取**任何受保护的业务数据 ——
> 详见下方「隐私与边界」。

> 当前阶段：**M1 · 本地原型**（2026-10-27）。
> 产品与合规背景见 [`UPC-Pocket-总体计划-v0.1.md`](UPC-Pocket-总体计划-v0.1.md)（原文保留）；
> 本次改造的范围、验证与已知问题见 [`docs/M1-本地原型进展.md`](docs/M1-本地原型进展.md)。

---

## 当前已支持

**课表（本地）**

- 手动添加课程 / 日程：名称、星期、节次、周次（每周 / 单周 / 双周）、地点、教师、颜色
- 日程与"手动补录的课程"分开：日程按颜色块显示，课程进入「课程总览」
- 撞课提醒（只与自己添加的内容比较，不拿演示数据制造冲突）
- 教学周：开学日 / 当前周次 / 总周数 / 每周起始日，全部可在设置里改
- 节次作息：10 节课的上下课时间可编辑，课表 / 今天 / 桌面组件共用同一份
- 显示：周六周日开关、格子密度、描边样式、多彩/单色、当前课高亮、自定义课表背景（含模糊与浓淡）

**今天**

- 一条时间轴：今天的课 + 自己添加的日程 + 已完成的专注
- 今日专注摘要（时长 / 番茄数 / 连续天数）与学期概览

**专注（番茄钟）**

- 计时引擎（墙钟计时，进程被杀后可恢复）、前台服务 + 常驻通知
- 三档预设（25/5、50/10、90/15）与自定义时长，自动开始下一段
- 标签管理、手动补记、按课程 / 日程关联专注

**学习统计**

- 今日 / 本周 / 本月 / 本年 / 全部，热力图、标签分布、每日明细

**桌面小组件（RemoteViews + XML）**

- **下一节课**（2×2）：课名、完整时间段、地点、"还有几节"圆气泡、下一节预告
- **今明课表**（4×2）：左列今天、右列明天
- 只读本地数据，不联网；演示模式下表头标「演示」

**校园服务导航**

- 唐岛湾 / 古镇口两校区的官方入口（课表、空闲教室、宿舍电费、图书馆、校园卡、数字石大…）
- **一律用系统浏览器打开**；每张卡写清适用范围与适用条件
- 唐岛湾与古镇口电费是**两套互不相通的系统**，页面置顶分开说明

**演示数据模式**

- 虚构示例课程（课名以「（演示）」结尾），用来在无账号时体验完整界面
- **只在内存生成、永不写数据库**；关掉即消失，用户数据不受影响
- 在今天页、课表页、课程详情、课程总览、桌面组件上都有明确标识

**外观**

- Material You 动态取色 + 8 套内置配色、深浅色（跟随系统 / 强制浅 / 强制深）

---

## 明确未实现

| 未实现 | 原因 |
| --- | --- |
| 教务课表导入 / 自动同步 | 未取得接口授权；本轮范围明确不接入受保护接口 |
| 校园统一身份认证（CAS / 数字石大） | 同上；且**不采集任何账号密码、Cookie、Token** |
| 空教室实时占用、宿舍电费查询、校园卡余额与流水 | 均为受保护业务数据，只提供官方入口跳转 |
| 图书馆座位状态、自动抢座 | 只跳官方页面；**不做抢座** |
| 成绩、考试安排、缴费、转账 | 不在原型范围 |
| 后台定时联网同步 | **应用没有网络权限，也没有任何周期性后台任务** |
| 自动更新 / 应用内下载 | 上游的 GitHub release 检查已整体删除 |
| 多学期并存的新建 / 切换 UI | 目前首次启动按今天自动生成一个学期，可在设置里改参数 |
| `.ics` / Excel 批量导入 | 课表内容暂时全靠手工添加 |

---

## 下载与安装

本仓库**不发布 APK**。自行构建（见下）；构建产物在：

```
app/build/outputs/apk/debug/app-debug.apk
```

- 系统要求：**Android 12（API 31）及以上**
- 应用标识：`com.upcpocket.app`，**与上游 ZZUPocket（`com.zzupocket.app`）是两个不同的应用**，
  不能互相覆盖安装，也不会互相读到对方的数据
- 首次启动会按"今天所在那一周的周一"生成一个学期，并按常见排法生成 10 节课的作息；
  两者都可以在「更多 → 课程表设置」里改

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

### 命令

```bash
# 调试包
./gradlew :app:assembleDebug          # Windows: .\gradlew.bat

# 单元测试
./gradlew :app:testDebugUnitTest

# 一起跑（本项目常规验证口径）
./gradlew :app:assembleDebug :app:testDebugUnitTest
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

---

## 技术栈

| 方面 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0 + Jetpack Compose + Material 3 |
| 依赖注入 | 手写容器 `core/AppGraph.kt`（依赖很少，不引入 Hilt） |
| 本地存储 | Room（课表 / 日程 / 番茄钟）、DataStore（偏好）、kotlinx.serialization（计时快照） |
| 桌面小组件 | AppWidgetProvider + RemoteViews + XML（不用 Glance：需要 `previewLayout` 与可变行数） |
| 网络 | **无**。Manifest 没有 `INTERNET` 权限，依赖里没有 OkHttp |
| 后台 | 前台服务（番茄钟）+ AlarmManager（组件在上下课时刻刷新）。**没有 WorkManager** |
| 后端 | **没有**，也不会有 |

### 代码结构

```
app/src/main/java/com/upcpocket/app/
├── core/         手动依赖容器 AppGraph、教学周换算、课表文本工具
├── data/
│   ├── db/       Room 实体与 DAO（7 张表，version = 1）
│   ├── local/    本地默认值、演示课表、官方入口目录
│   ├── prefs/    DataStore：界面偏好（含演示模式）、番茄钟偏好
│   └── repo/     课表仓库（纯本地）、专注仓库、今日课表口径
├── domain/       纯数据模型与统计口径（课表、周次、专注、日程）
├── focus/        番茄钟引擎、通知、前台服务
└── ui/           Compose：today / schedule / focus / campus / about / theme / nav / widget
```

---

## 隐私与边界

- **不需要账号**，也不保存任何密码、Cookie 或令牌。
- **没有网络权限**（`AndroidManifest.xml` 未声明 `INTERNET`）。所有本地能力离线可用。
- **数据只在本机**：课表、日程、专注记录都在本机数据库，没有自建服务器，App 不会上传。
  卸载 App 会一并清掉。
- **系统备份也关掉了**：`android:allowBackup="false"`，并且
  `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml` 对
  database / sharedpref / file / external 逐域排除。
  > 这一条必须说清：**没有 `INTERNET` 权限并不等于"数据不会被传走"**。
  > 系统云备份与换机迁移是**操作系统**代劳的，走设备的备份服务（Google Drive / 厂商云），
  > 不经过本应用的网络栈，应用有没有网络权限都拦不住。所以这里选择关闭备份，
  > 而不是在文案里补一句免责说明。代价是换机不会自动带数据（本原型本来也没有导出功能）。
- **校园服务的登录与查询发生在系统浏览器里**，由学校自己的网页完成；
  App 不内嵌页面、不代填账号、不读取页面内容。
- **不采集**：没有统计 SDK、没有崩溃上报、没有广告。
- 仓库本身**不接收**任何把账号密码、令牌、Cookie、学生业务数据写进代码或文档的提交。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [`UPC-Pocket-总体计划-v0.1.md`](UPC-Pocket-总体计划-v0.1.md) | 产品定位、合规边界、校园服务台账、里程碑（原文保留） |
| [`docs/M1-本地原型进展.md`](docs/M1-本地原型进展.md) | 本轮改造：ZZU 逻辑清单、删除/保留决策、演示数据约束、验证记录、已知限制 |
| [`docs/android-skills.md`](docs/android-skills.md) | 引入的 android/skills 版本、索引与更新流程 |
| [`AGENTS.md`](AGENTS.md) | 项目开发规范（对 Agent 与本人都适用） |

> 上游留下的 `docs/interfaces/`、`docs/design/` 等文档记录的是**郑州大学**系统的接口契约与设计，
> 与本项目当前能力无关，仅作为上游历史资料保留；**不要**据此接入任何校园系统。

---

## 致谢

本项目以 [ZZUPocket](https://github.com/3beuml/ZZUPocket)（MIT）为 Android 参考底座，
保留了它的课表网格、番茄钟、统计与 RemoteViews 小组件的设计与实现。
**上游的郑州大学认证、教务 / 一卡通接口、宿舍房间规则没有复用。**

桌面小组件的版式参考了 [拾光课程表](https://github.com/ShiGuangSchedule/shiguangschedule)
（Apache-2.0；只参考设计，没有拷贝代码）。

上游曾参考的社区项目（均为 MIT，本原型已不包含其代码）：
[Illustar0/ZZU.Py](https://github.com/Illustar0/ZZU.Py)、
[Elykia093/ZZU-Electricity-Monitor](https://github.com/Elykia093/ZZU-Electricity-Monitor)、
[rimrose/ZZU-Dorm-Electricity-Balance-Base](https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base)。

Android 技术指导：[android/skills](https://github.com/android/skills)（Apache-2.0），见 `docs/android-skills.md`。

---

## 许可证

[MIT](LICENSE) © 2026 3beuml（上游作者）

本项目作为 ZZUPocket 的衍生作品，沿用同一 MIT 许可证；上游版权声明保留在 `LICENSE` 中。
