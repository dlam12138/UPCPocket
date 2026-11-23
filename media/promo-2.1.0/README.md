# UPC Pocket 2.1.0 宣传片 · 《从早八到晚自习》

UPC Pocket 2.1.0 的 Remotion 宣传片工程：一条待办串起石大的一天，覆盖除赞赏二维码外的产品功能。成片为 16:9、1920×1080、30 fps、5580 帧（186.00 秒），中文旁白、烧录字幕，并单独导出 SRT 与封面。

可编辑工程按 [导演分镜](../../docs/releases/2.1.0-promo-storyboard.md) 制作。成片、可独立加载的字幕和封面随 [2.1.0 GitHub Release 草稿](https://github.com/dlam12138/UPCPocket/releases/tag/untagged-e831986d0d47cf9aa9b3) 交付。

## 交付物

| 文件 | 说明 |
| --- | --- |
| `output/UPC-Pocket-2.1.0-Promo.mp4` | 功能预览成片，H.264 CRF 17、AAC 192 kbps、yuv420p |
| `output/UPC-Pocket-2.1.0-Subtitles.srt` | 38 条中文字幕，与旁白共用同一分段时间线 |
| `output/UPC-Pocket-2.1.0-Cover.png` | 1920×1080 封面，来自独立 `Cover` composition |
| `output/stills/` | 关键帧检查图，已加入 `.gitignore` |
| `docs/storyboard.md` | 13 场分镜、时间码、逐段旁白、画面与数据标注 |
| `docs/assets.md` | 截图、配音、音乐、字体、纹理的来源、许可与体积 |
| `docs/screens.md` | 截图命名、别名、扫描与回退规则 |
| `docs/quality-check.md` | 类型检查、时间线/字幕核对、关键帧检查与遗留问题 |

## 环境与依赖

已在本机验证：Node.js 24.15.0、npm 11.12.1、Python 3.13.9、ffmpeg 2026-02-09 构建、Remotion 4.0.541 / React 19.1.0 / TypeScript 5.8.3。

- 仅渲染：Node.js 18+、npm、Chromium 或 Edge。
- 重新生成截图清单：Python 3 + Pillow。`npm run dev` / `npm run render` 会先跑扫描脚本，所以也需要 Pillow；不想装 Pillow 时可直接用 `npx remotion studio` / `npx remotion render` 指向已生成的清单。
- 重新生成旁白：Python 3 + `edge-tts`，且可访问 edge-tts 服务；需要网络，但不需要学校账号。
- 重新生成音乐/音效：Python 3 + `numpy`。
- 音频规范化、探测与混流：`ffmpeg`、`ffprobe` 在 PATH 中，ffmpeg 需带 `libmp3lame`。

```powershell
npm ci
python -m pip install edge-tts pillow numpy   # 仅在重建素材时需要
```

`remotion.config.ts` 会在本机存在 Edge 时使用 Edge；其他机器可用 `REMOTION_BROWSER` 指定 Chromium/Chrome 路径，未设置时由 Remotion 自行准备浏览器。

## 快速复现

```powershell
npm ci
npm run typecheck
npm run dev        # Remotion Studio 预览
npm run render     # 扫描截图并渲染全片到 output/UPC-Pocket-2.1.0-Promo.mp4
npm run cover      # 渲染封面到 output/UPC-Pocket-2.1.0-Cover.png
```

`npm run render` 先执行 `python scripts/scan_screens.py`。如果只想使用现有 `src/screens.generated.json`，可直接运行：

```powershell
npx remotion render src/index.ts Promo output/UPC-Pocket-2.1.0-Promo.mp4
```

常用单帧检查：

```powershell
npx remotion still src/index.ts Promo output/stills/f0060.png --frame=60
```

### 从旁白重建全链路

只有修改 `public/audio/vo-script.json` 或更换声音时才需要：

```powershell
# 1. 重新合成 38 段旁白，写 vo-durations.json，并重建 timeline.generated.json
npm run vo -- --force

# 2. 用同一时间线重写字幕
npm run srt

# 3. 按新总时长重生成原创 BGM 与音效
npm run audio

# 4. 刷新截图清单（可选；换截图后必须执行）
npm run scan

# 5. 类型检查并渲染
npm run typecheck
npm run render
npm run cover
```

`npm run vo` 不带 `--force` 时会复用已存在的 MP3，只重新测量时长并构建时间线。旁白文本、字幕、烧录字幕、SRT 和配音位置都由同一份 segment 列表派生；不要手改 SRT 或 `src/timeline.generated.json`。

## 时间线是怎么工作的

1. `public/audio/vo-script.json` 定义 13 场、38 段旁白，以及每场的 `pre` / `post` / `gap`（帧）。
2. `scripts/make_vo.py` 合成并响度规范化为 `-16 LUFS`、48 kHz 单声道、96 kbps MP3，实测时长写入 `vo-durations.json`。
3. `scripts/build_timeline.py` 按实测时长生成 `src/timeline.generated.json`：每段的绝对帧、场内帧、显示文本与字幕换行。
4. `src/Promo.tsx` 按生成时间线摆放 13 个 `Sequence`；`Captions.tsx` 和 `AudioTrack.tsx` 读取同一份 segment；`make_srt.py` 也读取同一份数据。

因此改旁白长度会自动移动画面、字幕和配音；但场景组件内写死的局部动画帧（如 `at={80}`）不会自动重排，需要按新的段长检查关键帧。

## 可编辑工程说明

```text
src/
  index.ts                 入口，registerRoot
  Root.tsx                 Promo / Cover 两个 composition
  Promo.tsx                13 场 Sequence 装配 + 全局字幕/音轨
  Cover.tsx                封面版式
  timeline.ts              时间线的类型化读取
  timeline.generated.json  自动生成，勿手改
  screens.ts               截图清单读取
  screens.generated.json   自动生成，勿手改
  theme.ts                 颜色、字体、画布、手机内画布尺寸
  scenes/Scene01–13.tsx     逐场画面与局部动画
  components/              手机框、截图裁切、字幕、音频、图形、卡片
  ui/MockScreen.tsx        缺失截图时的代码绘制示意界面
  lib/anim.ts              插值、缓动、弹簧等动画工具
public/
  screens/                 模拟器截图与来源说明
  audio/                   旁白、BGM、音效与 vo-script
  fonts/                   Noto Sans SC 与许可说明
  textures/                颗粒、暗角纹理
scripts/                   素材扫描、旁白、时间线、SRT、音频生成
```

常见修改：

| 想改什么 | 改哪里 | 注意 |
| --- | --- | --- |
| 某场文案、版式、动画 | `src/scenes/SceneXX.tsx` | 局部 `at` 以帧计；改完检查该场关键帧 |
| 旁白文本、声音、语速 | `public/audio/vo-script.json` | 改后 `npm run vo -- --force`，再 `npm run srt`、`npm run audio` |
| 字幕换行/烧录样式 | `vo-script.json` 的 `lines`；`Captions.tsx` | 字幕内容与 SRT 同源，别直接改 SRT |
| 替换/新增截图 | `public/screens/` + `scripts/scan_screens.py` | 命名规则见 `docs/screens.md`；替换后 `npm run scan` |
| 颜色、字号、画布 | `src/theme.ts`、`src/Root.tsx` | 改画布要同步检查所有绝对定位 |
| 开场片名、版本标识 | `Scene01.tsx`、`Brand.tsx`、`Cover.tsx` | 当前统一为「2.1.0 功能预览」 |
| 结尾卡片 | `Scene13.tsx` 末段 | `VersionChip` 与封面共用 |
| 封面 | `src/Cover.tsx` | `npm run cover` 输出单帧 PNG |
| 增删场景 | `vo-script.json`、`Promo.tsx`、`scenes/` | `Promo.tsx` 的组件数组必须与 SCENES 数量、顺序一致 |

视觉规范：纸白 `#F1F3EC`、品牌绿 `#1B6B4A`、产品蓝 `#495D92`；中文字体通过 `usePromoFont()` 在首帧前加载，避免字体未就绪导致换行跳动。场与场之间由 `SceneShell` 做约 12 帧的淡入淡出，背景纸色保持连续，不是硬切。

## 素材与事实边界

- 截图来自 `f9e7a37` 的 `2.1.0-promo` 调试包，在独立 Android 16 模拟器、飞行模式、无学校账号条件下拍摄；详细来源见 `public/screens/README.md`。
- 课程、金额、座位、待办、组件数值等合成内容在画面内有「演示数据」「示意」「合成演示」等标注；`MockScreen` 自身也带「示意界面 · 合成数据」标记。不要为了画面干净删除这些标注。
- 没有真实 CAS 登录、没有真实充值/支付、没有真实预约或报名。Scene10 的公众号交接只到“复制名称、打开微信”，不表示预约成功。
- 不把真实账号、Cookie、Token、学号、姓名、宿舍房号或未脱敏业务数据加入截图、旁白、字幕或工程文件。
- 更新、诊断、QQ 群只作为关于页入口出现；不伪造更新成功或认证通过。
- 赞赏二维码与赞赏演示按交付要求省略。

## 版本管理

工程、渲染所需素材、封面与字幕纳入版本管理。

MP4 作为 Release 附件交付。成片、临时渲染帧和本机依赖已在 `.gitignore` 中排除。

详细清单与哈希见 `docs/assets.md`，验证状态与遗留问题见 `docs/quality-check.md`。
