# 验证记录与遗留问题

2026-11-23 验证 UPC Pocket 2.1.0 功能预览的工程、素材、字幕和导出成片。

## 环境

| 项 | 值 |
| --- | --- |
| Node.js / npm | 24.15.0 / 11.12.1 |
| Python | 3.13.9 |
| ffmpeg | 2026-02-09 git 构建（gyan.dev full build） |
| Remotion / React / TypeScript | 4.0.541 / 19.1.0 / 5.8.3 |
| 画布 | 1920×1080，30 fps，5580 帧，186.00 秒 |
| 截图基线 | `f9e7a37` 的 `2.1.0-promo` 调试包 |

## 已执行检查

### 1. TypeScript

```powershell
npm run typecheck
```

结果为退出码 0，覆盖最终场景、字幕和混音代码。

### 2. 时间线可重建

用 `public/audio/vo-script.json` + `vo-durations.json` 按 `build_timeline.py` 的同一算法在内存中重算，与 `src/timeline.generated.json` **完全相等**：

- `fps=30`、`totalFrames=5580`、`totalSeconds=186.0`；
- 13 场、38 段；
- 每段 `from` / `localFrom` / `duration` / `seconds` / `text` / `lines` 一致。

说明：`timeline.generated.json` 没有被手改，旁白文本、字幕和画面位置仍同源。

### 3. SRT 对齐

用同一时间线在内存中重写 SRT，与 `output/UPC-Pocket-2.1.0-Subtitles.srt` 按行比较：

- 38 条 cue，文本、换行、起止时间完全一致；
- 首条 00:00.133–00:02.967；
- 末条 03:00.567–03:03.033「UPC Pocket，明天见。」；
- 03:03.033–03:06.000 为无旁白结尾卡。

### 4. 截图清单与引用

`src/screens.generated.json`（`generatedAt 2026-11-23T01:38:29+00:00`）：

- 74 个槽位，71 个可用；
- 缺失：`card`、`library-records`、`map-route`；
- 未映射文件：无；
- 72 张 PNG 对 71 个槽位：`timetable.png` 与 `timetable-app.png` 都映射 `schedule`，清单选较新的 `timetable.png`；
- 场景引用 57 个槽位，55 个有实拍，2 个明确回退为带标记的 MockScreen：`card`（Scene05）、`schedule-demo`（Scene02）。

### 5. 音频

- 38 段在用旁白，语音总长 142.728 秒；
- `bgm.mp3` 188.000 秒，3,761,324 字节；
- 5 个原创音效；
- 旁白规范化参数 `-16 LUFS`、48 kHz 单声道、96 kbps。

混音使用总帧数控制 BGM 的片尾 3.5 秒淡出。

### 6. 关键帧检查

`output/stills/` 共 24 张。最终行高调整（`FeatureRow` 上/下内边距 13px → 10px）和最后几处文案修改后的复核帧：

| 帧 | 场 | 检查点 | 结果 |
| ---: | --- | --- | --- |
| 60 / 300 | 01 / 02 | 开场、`DemoBadge`、NoteRail、首页/课表 | 角标与侧栏不压手机；无文字重叠 |
| 900 | 03 | 地图、方式卡、导航示意卡、右侧小字 | 版式无重叠；地图署名可读 |
| 1850 | 05 | 三条特性末行、标签、来源注 | 行高压缩后标签与来源注有间距 |
| 2250 | 06 | 三步、标签、来源注 | Step 布局不受行高影响；无重叠 |
| 2700 | 07 | 本机隐藏行、标签、记录面板 | 早前「本机隐藏座位」与标签重叠已消除 |
| 3150 | 08 | 石光/e站通行、标签、来源注 | 无重叠 |
| 3600 | 09 | 学习平台行、标签、来源注 | 无重叠 |
| 4000 | 10 | 交接边界行、标签、来源注 | 无重叠 |
| 4520 | 11 | 待办/计时行、标签、来源注 | 无重叠 |
| 4950 | 12 | 四类小组件、数值说明、玻璃面板 | 删除底部重复标签后不再压字幕卡 |
| 5300 | 13 | 三条特性、标签、来源注 | 无重叠；结尾卡未受影响 |

较早生成的 `f1260、f1600、f2000、f2380、f2900、f3300、f3800、f4150、f4400、f4750、f5250、f5460` 只用于内容审查。它们早于最后一次行高调整或 Scene05/07/12 的文案修改，**不作为最终版式证据**；其中 `f5460` 的结尾卡与行高无关，内容仍可参考。

### 7. 封面

`output/UPC-Pocket-2.1.0-Cover.png` 存在，1920×1080，2,015,648 字节，SHA-256 `0dc0008bde51497946d82b8f3190262323ed81dd3cdda7f8692944afcf23106d`。封面由 `Cover` composition 生成，含「封面演示画面 · 课程与待办为合成数据」说明。

## 成片验收

- 最终 MP4 为 33,474,553 字节，SHA-256 为 `531cd95e8c7319d977a482bf73dc8e78de685160e737b7a36cea46b719932d15`。
- `ffprobe` 确认 1920×1080、30 fps、5580 帧、H.264 与 AAC 音轨。视频轨时长 186.000 秒，容器时长 186.005 秒。
- 将 38 段旁白和成片混音解码为 8 kHz 单声道，用交叉相关核对每段的播放起点。SRT 的文本和起止时间均与时间线一致。38 段最大绝对播放偏差约 0.1 ms，最小归一化相关度 0.90。
- 从最终 MP4 抽查 17 帧，覆盖 13 场、地图署名、周课表示意和结尾卡。周课表示意与真实组件均对应 11 月 22 日至 28 日。片尾最后 0.25 秒混音 RMS 约为 −61.2 dBFS。
- 独立测试用相同哈希的 MP4 复核配音起点，执行全片解码与黑帧检测，未发现解码错误或黑帧。关键帧和封面未检出可解码二维码。独立审查复核了校园功能描述、合成数据标记、地图署名和草稿中的发布边界。
- 成片为 H.264 High、AAC-LC 立体声。实测视频像素格式为全范围 `yuvj420p`，整体响度 −16.9 LUFS、真峰值 −2.6 dBFS。

## 素材与验收范围

- Android 16 独立模拟器实拍来自 `f9e7a37` 的 `2.1.0-promo` 调试包。实际画面使用离线模式及合成样本，数据和组件示意有显式标记。
- 校园卡总览、完整周课表布局和 e站通数据示例使用带标记的示意。外部交接演示到系统浏览器或微信入口。
- 真实 CAS、校园数据、支付和预约、正式候选包与真实设备验收待正式发布阶段进行。此处验证的是宣传成片和实际使用的离线画面。
- 字体许可与版权见 [OFL.txt](../public/fonts/OFL.txt) 和 [字体说明](../public/fonts/README.md)。

## 证据索引

| 证据 | 路径 |
| --- | --- |
| 源码与场景 | `src/`、`src/scenes/Scene01–13.tsx` |
| 时间线 | `src/timeline.generated.json`、`src/timeline.ts` |
| 截图清单 | `src/screens.generated.json`、`scripts/scan_screens.py` |
| 字幕 | `output/UPC-Pocket-2.1.0-Subtitles.srt` |
| 旁白与音频 | `public/audio/vo-script.json`、`vo-durations.json`、`vo/`、`bgm.mp3`、`sfx/` |
| 截图来源 | `public/screens/README.md` |
| 关键帧 | `output/stills/`（本地，已忽略） |
| 分镜 | `docs/storyboard.md` |
| 素材 | `docs/assets.md` |
| 截图规则 | `docs/screens.md` |
