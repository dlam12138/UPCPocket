# 截图命名、扫描与回退

`public/screens/` 是宣传片的手机画面来源。`scripts/scan_screens.py` 把目录里的图片扫描成 `src/screens.generated.json`；`ScreenDeck`、`Shot`、`PlainShot` 按槽位 id 取图。场景代码只认槽位 id，不直接写文件名。

## 命名约定

- 文件名小写，使用 `-` 分隔；扩展名支持 `.png`、`.jpg`、`.jpeg`、`.webp`。
- 名字先归一化：转小写、`_` 和空格转 `-`；然后优先匹配 74 个槽位名，其次查别名表。
- 同一槽位有多个文件时，**修改时间最新**的胜出；扫描结果会记录实际 `file`、`width`、`height`。
- 空白或不可用截图不要靠重命名混入：`directory-dial`、`widget-week-preview` 在 `IGNORED` 中，扫描时直接跳过。
- 推荐截图尺寸 720×1518（与现有素材一致）；扫描器接受其他尺寸并记录宽高，手机内画布按 1080×2280 取景。

## 当前状态

`src/screens.generated.json` 生成于 `2026-11-23T01:38:29+00:00`：

| 项 | 数量/值 |
| --- | --- |
| 命名槽位 | 74 |
| 可用槽位 | 71 |
| 目录中的 PNG | 72（另有 README） |
| 缺失槽位 | `card`、`library-records`、`map-route` |
| 未映射文件 | 无 |
| 场景引用槽位 | 57 |
| 有实拍的引用 | 55 |
| 明确回退 Mock 的引用 | 2（`card`、`schedule-demo`） |

72 张 PNG 对应 71 个槽位，是因为 `timetable.png` 与 `timetable-app.png` 都映射到 `schedule`；当前清单选择较新的 `timetable.png`。`timetable-app.png` 是宿主未配置账号状态，仍在目录中但未被引用。

### 常见别名

| 实际文件/别名 | 槽位 |
| --- | --- |
| `library-hub`、`library-venues`、`library-venue` | `library` |
| `library-gz`、`library-guzhenkou` | `library-hub-guzhenkou` |
| `campus-preference`、`campus` | `campus-settings` |
| `timetable`、`timetable-app`、`timetable-week`、`week` | `schedule` |
| `timetable-detail`、`course` | `course-detail` |
| `pool`、`swimming`、`swim` | `gym-swim` |
| `ezton`、`service-portal` | `estation` |
| `stone-light` | `shiguang` |
| `contacts` | `directory` |
| `appearance`、`appearance-light`、`theme-settings` | `theme` |
| `appearance-glass` | `theme-glass` |
| `tasks`、`todo-list`、`tasks-day` | `todo` |
| `widget-balance` | `widget-card` |
| `widgets-live`、`desktop-widget` | `widget-week-live` |

完整槽位列表、别名和忽略规则以 `scripts/scan_screens.py` 为准；不要在场景里硬编码文件名。

## 扫描算法

```text
for each image in public/screens:
  stem → norm(stem)
  if stem in IGNORED: skip
  slot = stem if stem in SLOTS else ALIASES[stem]
  if no slot: report unmapped
  if slot already found and this file is older: keep existing
  record file, pixel size, mtime
write screens.generated.json:
  generatedAt / sourceDir / screens / missing / unmapped
```

更新流程：

```powershell
# 放入或替换 public/screens/<name>.png
npm run scan
# 查看 src/screens.generated.json 的 screens/missing/unmapped
npm run typecheck
npm run dev
```

`npm run render` 会先跑一次扫描。若只改场景、不换截图，可以不重跑；若替换了同名截图，必须重跑，否则清单可能仍指向旧文件或旧尺寸。

## 回退与标注

`src/components/Capture.tsx` 的 `Shot`/`PlainShot` 在槽位无实拍时渲染 `src/ui/MockScreen.tsx`。`MOCK_ALIAS` 把一些“演示槽位”指向可绘制的 id：

- `schedule-demo` → `schedule`（代码绘制的整周课表示意）
- `home-demo`、`home-synced` → `home`
- `library-records` → `library`
- `map-origin`、`map-point`、`map-guzhenkou` → `map`
- `power-guzhenkou` → `power`
- `gym-swim` → `gym`

`MockScreen` 的每个页面都带有「示意界面 · 合成数据」标记。额外的画面级标注：

- Scene01、Scene02 在手机下方使用 `DemoBadge`「演示数据」；Scene02 的整周课表明确是示意。
- 各场 `SourceNote` 说明真实界面与合成数据的边界。
- Scene12 的七日组件布局由 `WeekWidget` 代码绘制，标题为「七日布局示意 · 合成课程」；四类小组件裁图另有「课程、余额、电量与待办数值均为演示数据」说明。

### 当前两个回退

| 槽位 | 使用场 | 原因 | 可见标注 |
| --- | --- | --- | --- |
| `card` | 05 午饭与宿舍 | 没有校园卡总览实拍；`card-history`、`card-recharge` 有实拍，但总览用 Mock 更完整 | `CardMock` 自带「示意界面 · 合成数据」；Scene05 注明余额为合成演示、流水未登录 |
| `schedule-demo` | 02 看课 | 场景需要一个完整、可读的整周课表示意；`schedule` 实拍存在，但当前未接入本场 | `ScheduleMock` 自带标记 + 手机下方「演示数据」角标；字幕注课表只读 |

`schedule` 实拍已存在但当前未被任何场景引用。若要用实拍替换 Scene02 的示意，需要把该场 `screens` 中的 `{id: 'schedule-demo'}` 改为 `{id: 'schedule'}`，并重新检查角标、侧栏对齐和课程数据是否仍与旁白一致。

`library-records`、`map-route` 缺失但当前没有被场景引用；预约记录用代码面板「预约记录 · 示意」，地图路线用 `map-walk`/`map-bike` 实拍，因此不影响渲染。

### 未被引用的实拍

以下槽位有图但当前场景未直接引用，保留作替换或后续剪辑素材：

```text
card-recharge  focus  focus-add-record  focus-records  home  home-todo
learn-webvpn   library-map  map  schedule  services-admin  services-life
today          widget-todo-done  widget-week  widget-week-crop
```

## 截图来源与隐私

- 来源、模拟器条件、真实/合成边界见 [public/screens/README.md](../public/screens/README.md)。
- 截图来自 `f9e7a37` 的 `2.1.0-promo` 调试包；飞行模式、无学校账号；课程、金额、座位、待办等为调试夹具或本机合成数据。
- 不要加入真实账号、密码、Cookie、Token、学号、姓名、完整宿舍房号、预约记录或未脱敏业务内容。
- 地图截图保留 OpenStreetMap 署名；通讯录只使用公开联络信息。
- 新增截图前先确认对应槽位和画面标注；不能用“真实截图”名义把合成内容说成真实业务结果。
