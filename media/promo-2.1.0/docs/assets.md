# 素材、来源与许可

本页记录 `media/promo-2.1.0` 中进入成片的素材、体积、来源和可分发边界。所有路径相对于本目录。

## 总览

| 目录/文件 | 数量 | 体积 | 用途 |
| --- | ---: | ---: | --- |
| `public/screens/` | 72 张 PNG + `README.md` | 17.46 MiB | 手机内截图；71 个命名槽位可用 |
| `public/audio/vo/` | 38 个 MP3 | 约 1.66 MiB | 中文旁白 |
| `public/audio/bgm.mp3` | 1 | 3.59 MiB | 原创背景音乐，188.00 秒 |
| `public/audio/sfx/` | 5 个 MP3 | 53.7 KiB | 原创 UI 音效 |
| `public/audio/vo-script.json`、`vo-durations.json` | 2 | 约 12 KiB | 旁白文本/分句与实测时长 |
| `public/fonts/` | 1 TTF + 许可证 + 说明 | 16.95 MiB | Noto Sans SC，渲染时加载 |
| `public/textures/` | 2 张 PNG | 0.15 MiB | 纸张颗粒与暗角 |
| `output/UPC-Pocket-2.1.0-Cover.png` | 1 | 1.92 MiB | 封面 |
| `output/UPC-Pocket-2.1.0-Subtitles.srt` | 1 | 3.4 KiB | 38 条字幕 |

不含成片 MP4。`output/stills/` 是本地关键帧检查图，已忽略。

## 截图

- 来源：从 `f9e7a37` 构建的 `2.1.0-promo` 调试包，在独立 Android 16 模拟器上拍摄；飞行模式、关闭 Wi-Fi，全程未填写学校账号。
- 真实界面 + 合成内容：首页、服务、课表、空闲教室、图书馆、校园卡、电量、待办、专注、组件等入口和版式来自真实应用；课程、金额、座位、待办、组件数值等使用仓库调试预览或本机合成夹具。逐项说明见 [public/screens/README.md](../public/screens/README.md)。
- 地图：保留 OpenStreetMap 署名；`map-*` 是离线地图预览。
- 通信录：只使用学校公开联络信息；办事窗口截图展示公开号码。
- 个人数据：没有真实学号、姓名、宿舍房号、账号、Cookie 或 Token；如后续补拍，必须先脱敏再放入 `public/screens/`。
- 命名、别名、扫描与回退规则见 [screens.md](screens.md)。

### 逐场截图槽位

| 场 | 槽位 |
| --- | --- |
| 01 清晨 | `home-demo` |
| 02 看课 | `home-demo`、`schedule-demo`（回退 Mock）、`course-detail` |
| 03 认路 | `map-origin-wide`、`map-origin`、`map-walk`、`map-bike`、`map-point`、`map-guzhenkou` |
| 04 找人办事 | `directory`、`directory-detail`、`directory-window` |
| 05 午饭与宿舍 | `card`（回退 Mock）、`card-history`、`recharge-demo`、`power`、`power-guzhenkou` |
| 06 找自习室 | `classroom`、`classroom-afternoon`、`classroom-filter`、`classroom-guzhenkou` |
| 07 找座位 | `library`、`library-hub-guzhenkou`、`library-area`、`library-time`、`library-seat`、`library-hidden` |
| 08 活动与事项 | `shiguang`、`shiguang-detail`、`shiguang-mine`、`estation` |
| 09 找学习资料 | `services`、`learn`、`home-pinned`、`official-web`、`official-pinned` |
| 10 下课运动 | `gym`、`gym-swim` |
| 11 晚自习 | `todo`、`todo-edit`、`todo-position`、`focus-todo`、`focus-active`、`focus-stats` |
| 12 桌面组件 | `widgets`、`widget-week-live`、`widget-today`、`widget-card`、`widget-power`、`widget-todo` |
| 13 收尾 | `settings`、`campus-settings`、`theme`、`theme-glass`、`about`、`diagnostics` |

封面另用 `home-demo`。完整槽位与别名见 [screens.md](screens.md)。

## 旁白

- 引擎：`edge-tts`，声音 `zh-CN-XiaoxiaoNeural`，语速 `+15%`，音高 `+0Hz`。
- 处理：`ffmpeg loudnorm=I=-16:TP=-1.5:LRA=11,highpass=f=90`，输出 48 kHz 单声道、96 kbps MP3。
- 数量：38 段在用（`v101`–`v138`），总语音 142.728 秒；每段文本与字幕同源，见 `public/audio/vo-script.json`。
- 可复现：`npm run vo -- --force` 需要网络访问 edge-tts；不涉及学校账号、CAS 或真实凭据。

## 音乐与音效

- `public/audio/bgm.mp3`：`scripts/build_audio.py` 用正弦波、噪声和简单包络合成的原创环境铺底；188.00 秒，3,761,324 字节；SHA-256 `5375ec232da4ad24f33f83971561488d4acf7bee5bfac42faa3df157ee8c444c`。
- 音效：`chime`、`impact-low`、`pop`、`tick-ui`、`whoosh-soft`，同样由脚本合成，共 5 个。
- 没有第三方音乐、采样包或版权素材；不需要额外授权。
- BGM 原素材为 188.00 秒。混音按成片时长播放，并在最后 3.5 秒淡出。

## 字体

- `public/fonts/NotoSansSC.ttf`：Noto Sans SC 2.04，17,773,244 字节；`usePromoFont()` 在首帧前通过 `FontFace` 加载为 `PromoSC`，避免中文换行跳动。
- 字体 name 表版权声明：© 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'.
- 许可：SIL Open Font License 1.1；完整文本见 `public/fonts/OFL.txt`，版权补记见 `public/fonts/README.md`。
- 工程未对字体做子集化或字形修改。若替换字体，需同步检查 `src/theme.ts` 的 `FONT.cjk` 与 `Cover.tsx`/各场景的字重。

## 纹理与图形

- `public/textures/grain.png`（133,756 字节）与 `vignette.png`（25,746 字节）用于纸张颗粒和暗角，由 `Backdrop.tsx` 叠加；无第三方图库来源。
- 当前目录未附纹理生成脚本；如需重生成，保持 1920×1080、低对比、可平铺/覆盖即可。
- 图标、手机框、组件示意、地图路线示意均为工程内代码绘制（`Glyph.tsx`、`Phone.tsx`、`WidgetArt.tsx`、`MockScreen.tsx` 等）。

## 依赖与许可

- Remotion 4.0.541、React 19.1.0、React DOM 19.1.0，按各自 npm 包许可证使用。
- 渲染浏览器为系统 Edge/Chromium，不随工程分发。
- 截图中的 Android 界面属于 UPC Pocket 项目；OpenStreetMap 数据保留署名。
- 不包含赞赏二维码、赞赏页截图或赞赏演示。

## 当前校验值

| 文件 | SHA-256 |
| --- | --- |
| `public/audio/bgm.mp3` | `5375ec232da4ad24f33f83971561488d4acf7bee5bfac42faa3df157ee8c444c` |
| `src/timeline.generated.json` | `c3bf382a9c4c7c67d86d673182244496012bfa61ca1205c21179df036adef2fd` |
| `src/screens.generated.json` | `b1b4b77eb260372289b851e54e177bb73f01232ae945aec8251f4e35f06336c6` |
| `output/UPC-Pocket-2.1.0-Subtitles.srt` | `069be0089dbd4403f830a43e08e6f4ce50ea7e1dba703ee23e952ec38916da0d` |
| `output/UPC-Pocket-2.1.0-Cover.png` | `0dc0008bde51497946d82b8f3190262323ed81dd3cdda7f8692944afcf23106d` |
