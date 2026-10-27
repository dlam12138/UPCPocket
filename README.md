# UPC Pocket（石大校园助手）

中国石油大学（华东）校园助手的 **Android 本地原型**。

> **本项目是非官方、非商业的个人项目**，与学校不构成隶属或合作关系。
> 本原型**不代理**校园账号密码，也不把受保护数据转发到自建服务器；空闲教室读取学校匿名公开数据，
> 图书馆座位在用户主动登录官方 CAS 后使用原生界面 —— 详见下方「隐私与边界」。

> 当前阶段：**手机反馈预览版 0.1.2-preview**（2026-10-27）。
> 产品与合规背景见 [`UPC-Pocket-总体计划-v0.1.md`](UPC-Pocket-总体计划-v0.1.md)（原文保留）；
> 本次改造的范围、验证与已知问题见 [`docs/0.1.2-功能实现与验收-2026-10-27.md`](docs/0.1.2-功能实现与验收-2026-10-27.md)。

---

## 当前已支持

**课表（本地）**

- 手动添加课程 / 日程：名称、星期、节次、周次（每周 / 单周 / 双周）、地点、教师、颜色
- CSV 批量导入：选择本地文件或从剪贴板导入、预览校验、确认后整批写入，重复导入去重；不上传文件。支持 1–13 节、多时段单元格、单双周分片；登录后可用 `tools/upc-schedule-export.user.js` 主动导出整学期 CSV。具体教务导出列名仍需核对，见 `docs/CSV-课表导入.md`
- 日程与"手动补录的课程"分开：日程按颜色块显示，课程进入「课程总览」
- 撞课提醒（只与自己添加的内容比较，不拿演示数据制造冲突）
- 教学周：开学日 / 当前周次 / 总周数 / 每周起始日，全部可在设置里改
- 节次作息：1–13 节的上下课时间可编辑，课表 / 今天 / 桌面组件共用同一份；老安装自动补种 11–13 节但不覆盖已改时间
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

**校园服务**

- 唐岛湾 / 古镇口两校区的服务入口（课表、空闲教室、宿舍电费、图书馆、校园卡余额、数字石大）
- 空闲教室为原生筛选与结果列表，读取学校公开查询，支持日期、校区、楼宇与 1–13 节；不提供预约，也不把查询失败表示为空闲
- 图书馆座位为原生数据层与界面：官方 CAS 网页登录后，选馆区、日期、房间、座位与时间，用户确认后提交；支持我的预约与取消，不自动抢座、不轮询
- 唐岛湾电费、校园卡提供“复制链接并打开微信”或“分享链接到微信”，由用户在微信中点击；这不是任意 H5 的一步直达，也不补齐学校认证。已接入微信 SDK 适配层，但没有正式移动应用 AppID，直达按钮不启用
- 校园卡使用用户提供的[学校余额页面](http://jhzf.upc.edu.cn:20081/wechat/query/balance.html)，裸链接匿名访问提示无效 token；如有此提示，应从学校公众号进入。尚未实现原生余额，不做充值
- 唐岛湾与古镇口电费使用不同地址，页面置顶分开说明；已移除信息化建设处、古镇口校区综合办公室导航

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
| 教务登录后自动同步 | 不接入教务受保护接口；提供本地 CSV 与登录后主动运行的浏览器导出脚本。脚本尚未在真实账号上端到端验证 |
| 账号密码代填 / 自建 CAS 代理 | **不采集、不代填任何账号密码**；图书馆只使用官方 CAS 网页完成登录，token 仅在内存 |
| 原生宿舍电费、校园卡余额与流水 | 唐岛湾入口依赖微信 OAuth，校园卡裸链接返回无效 token；尚未取得可用的只读数据链，当前不设假余额入口 |
| 微信 SDK 正式直达 | SDK 适配层已接入，但缺少正式移动应用 AppID、正确包名/签名和目标服务/小程序标识；未配置时不显示直达按钮 |
| 自动抢座 / 后台轮询 | 图书馆座位只按用户主动查询与确认提交；**不做抢座** |
| 成绩、考试安排、缴费、转账 | 不在原型范围 |
| 后台定时联网同步 | 不做周期性后台请求，公开空闲教室与图书馆均由用户主动操作 |
| 自动更新 / 应用内下载 | 上游的 GitHub release 检查已整体删除 |
| 多学期并存的新建 / 切换 UI | 目前首次启动按今天自动生成一个学期，可在设置里改参数 |
| `.ics` / Excel 批量导入 | 当前只支持 CSV；不把通用列名支持宣传为已验证所有教务导出格式 |

---

## 下载与安装

本仓库**不发布 APK**。自行构建（见下）；构建产物在：

```
app/build/outputs/apk/debug/app-debug.apk
```

- 系统要求：**Android 12（API 31）及以上**
- 应用标识：`com.upcpocket.app`，**与上游 ZZUPocket（`com.zzupocket.app`）是两个不同的应用**，
  不能互相覆盖安装，也不会互相读到对方的数据
- 首次启动会按"今天所在那一周的周一"生成一个学期，并按常见排法生成 13 节课的作息；
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
即使填了 AppID，仍需在微信开放平台登记包名 `com.upcpocket.app` 与当前签名证书，并在真机验证
微信接受 `OpenWebview` 请求、目标页真正打开、学校认证/查询成功；`sendReq` 返回 true 不算完成。

---

## 技术栈

| 方面 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0 + Jetpack Compose + Material 3 |
| 依赖注入 | 手写容器 `core/AppGraph.kt`（依赖很少，不引入 Hilt） |
| 本地存储 | Room（课表 / 日程 / 番茄钟）、DataStore（偏好）、kotlinx.serialization（计时快照） |
| 桌面小组件 | AppWidgetProvider + RemoteViews + XML（不用 Glance：需要 `previewLayout` 与可变行数） |
| 网络 | 平台 HTTPS 客户端，用于匿名公开空闲教室查询与图书馆座位接口；图书馆登录使用官方 CAS WebView。无 OkHttp、后台轮询 |
| 后台 | 前台服务（番茄钟）+ AlarmManager（组件在上下课时刻刷新）。**没有 WorkManager** |
| 微信 | 腾讯开放平台 Android SDK 6.8.40（OpenWebview / 小程序请求），AppID 未配置时禁用直达 |
| 后端 | **没有**，也不会有 |

### 代码结构

```
app/src/main/java/com/upcpocket/app/
├── core/         手动依赖容器 AppGraph、教学周换算、课表文本工具
├── data/
│   ├── db/       Room 实体与 DAO（7 张表，version = 1）
│   ├── local/    本地默认值、演示课表、官方入口目录
│   ├── classroom/ 空闲教室公开查询协议（无认证、无凭据）
│   ├── library/  图书馆座位会话、签名、数据层（token 仅内存）
│   ├── wechat/   微信 SDK 适配（AppID 为空时禁用）
│   ├── prefs/    DataStore：界面偏好（含演示模式）、番茄钟偏好
│   └── repo/     课表仓库（纯本地）、专注仓库、今日课表口径
├── domain/       纯数据模型与统计口径（课表、周次、专注、日程）
├── focus/        番茄钟引擎、通知、前台服务
└── ui/           Compose：today / schedule / focus / campus / about / theme / nav / widget
```

---

## 隐私与边界

- **核心功能不需要账号**；图书馆座位是可选功能，必须由用户在学校官方 CAS 页面登录。
- **不保存密码、Cookie 或令牌**。图书馆 seat token 只保存在进程内存，退出登录/进程结束即清除；登录 WebView 在成功或退出时清理 Cookie。电费/校园卡仍在微信或外部浏览器完成认证。
- **有 `INTERNET` 权限**，用于用户主动打开的公开空闲教室查询，以及图书馆座位的官方登录与正常接口。本地课表、CSV 导入和专注离线可用。
- **数据只在本机**：课表、日程、专注记录都在本机数据库，没有自建服务器，App 不会上传。
  卸载 App 会一并清掉。
- **系统备份也关掉了**：`android:allowBackup="false"`，并且
  `res/xml/backup_rules.xml`、`res/xml/data_extraction_rules.xml` 对
  database / sharedpref / file / external 逐域排除。
  > 这一条必须说清：**没有 `INTERNET` 权限并不等于"数据不会被传走"**。
  > 系统云备份与换机迁移是**操作系统**代劳的，走设备的备份服务（Google Drive / 厂商云），
  > 不经过本应用的网络栈，应用有没有网络权限都拦不住。所以这里选择关闭备份，
  > 而不是在文案里补一句免责说明。代价是换机不会自动带数据（本原型本来也没有导出功能）。
- **空闲教室只读公开数据**，请求仅携带查询条件，不带认证 Cookie，不上传课表或专注数据。
- **图书馆登录页只作为官方 CAS 登录步骤**，登录成功后立即关闭并清理 WebView；功能界面是原生查询/选座，不抓取网页、不自动提交。
- **微信**：没有正式 AppID 时不发送 SDK 请求；用户主动选择时才复制/分享链接。复制/分享与打开微信首页都不算直达成功。
- **不采集**：没有统计 SDK、没有崩溃上报、没有广告。
- 仓库本身**不接收**任何把账号密码、令牌、Cookie、学生业务数据写进代码或文档的提交。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [`UPC-Pocket-总体计划-v0.1.md`](UPC-Pocket-总体计划-v0.1.md) | 产品定位、合规边界、校园服务台账、里程碑（原文保留） |
| [`docs/M1-本地原型进展.md`](docs/M1-本地原型进展.md) | 本轮改造：ZZU 逻辑清单、删除/保留决策、演示数据约束、验证记录、已知限制 |
| [`docs/0.1.2-功能实现与验收-2026-10-27.md`](docs/0.1.2-功能实现与验收-2026-10-27.md) | 本轮新增：图书馆原生预约、微信 SDK 适配、CSV 多时段与 1–13 节、测试与缺失材料 |
| [`docs/手机测试说明-0.1.2.md`](docs/手机测试说明-0.1.2.md) | 0.1.2-preview 真机验收步骤与已知边界 |
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
