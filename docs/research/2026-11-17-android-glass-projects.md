# Android 玻璃效果项目调研 · 2.0.8 外观取舍

核验日期：2026-11-17。**12 个独立可看效果项目，另有 1 个同系列技术备选及 1 个已回滚实验案例。** 来源为项目原仓库、源码、许可证、作者媒体和 Android 官方文档；未运行第三方项目、安装示例 APK 或验证在 UPC Pocket 的兼容性。2.0.8 的具体工程方案见 [选座与组件外观计划](../design/2.0.8-library-layout-and-widget-appearance.md)。

## 结论与选型补充

后续决策（同日）：用户已将 [PR #8](https://github.com/dlam12138/UPCPocket/pull/8) → [#9](https://github.com/dlam12138/UPCPocket/pull/9) 的合并纳入2.0.8，并要求注重页面稳定性。#9已提供01的Backdrop 1.0.6 / Shapes 1.2.0与公共控件候选，优先审查复用；其Kotlin/Compose/KSP/Room升级、Lint兼容和老用户默认玻璃需先评估，main尚未合入或验收。下文项目比较与旧版移植候选保留为调研证据，不代表重新开发另一套引擎。具体优先级与条件见 [总计划](../design/2.0.8-update-plan.md)。

用户在查看画廊后认可了**01 Backdrop、03 QWEA0、05 PrismalAGSL**，并提出应用内也可调整，随后明确要求**优先移植对应项目相关代码，避免重复造轮子**。2.0.8规划优先核对01作为Compose主引擎的直接依赖或固定提交源码移植；05作备选、03按实际效果差异评估必要补充，项目侧只做主题、导航、交互及兼容的薄适配。视觉以01整体材质为主、结合03轻折射和05高光层次，优先导航胶囊、首页服务卡与主要按钮。选型认可不等于已引入三库或已证明工具链兼容；密集内容维持清晰文字，应用内效果与桌面静态外观分别实现和控制。详见 [应用内方案](../design/2.0.8-library-layout-and-widget-appearance.md)。

应用内追加核对：现有compile36已能引用API33的RuntimeShader，这只说明平台API可引用，不能据此推导整套上游库已兼容，也不作为默认重写渲染引擎的方案。优先沿用选定上游的背景捕获、shader、缓存与版本降级，再补当前页面连接；31–32保留上游可用的基础材质/受控背景模糊，33+评估少量浮层的轻折射。没有背景源或效果不可用时保持清晰近实底。仅给整个卡片加blur会模糊卡片自身，不能自动获得后面的页面。具体移植版本、编译兼容与帧率仍未验证。[RuntimeShader/AGSL](https://developer.android.com/develop/ui/views/graphics/agsl)、[RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、[Compose绘制与记录](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers#write-contents)

复用时记录仓库、tag/commit、原文件路径、传递依赖及本地修改，保留原版权、LICENSE和上游存在的NOTICE。移植范围包括实现所必需的辅助代码，避免只取shader后重新搭建上游已有管线；最新版不兼容时先核对可用旧版/分支和最小兼容改动，不自动转向自研或升级工具链。当前尚未依赖解析、编译或导入第三方代码。

### 01的有限版本核对与移植候选

依用户复用要求，补查了01早期Android线路，得到下列源码兼容调查候选；**这些版本均未在本项目编译，不能作为已兼容的依赖或已确定的正式基线**。

| 版本与固定提交 | 已核对配置 | 后续用途 |
| --- | --- | --- |
| [1.0.0](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.0)，`eada9a8600142c1bd6a5c5fc143d06f599cdfaef` | 纯Android的`backdrop`模块，min21/compile36；Kotlin2.2.21、Compose1.9.4，尚无Shapes依赖。[版本目录](https://github.com/Kyant0/AndroidLiquidGlass/blob/1.0.0/gradle/libs.versions.toml)、[模块配置](https://github.com/Kyant0/AndroidLiquidGlass/blob/1.0.0/backdrop/build.gradle.kts) | 可先核对源码移植所需的Compose API兼容改动；其Kotlin/Compose仍新于当前项目，不能因是旧版就直接认定AAR可用 |
| [1.0.6](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.6)，`896a94a3ade1cc1a940b92365f942a34971fecda` | Android线路，但已是Kotlin2.3.10、Compose1.10.3、AGP9，并增加Shapes。[版本目录](https://github.com/Kyant0/AndroidLiquidGlass/blob/1.0.6/gradle/libs.versions.toml) | 用于核对后续实现与修复，不因版本号仍为1.x而认定更适合当前工具链 |

[1.0.0固定源码](https://github.com/Kyant0/AndroidLiquidGlass/tree/eada9a8600142c1bd6a5c5fc143d06f599cdfaef)已核对可复用的实现包括：背景记录`backdrops/LayerBackdrop.kt`、`LayerBackdropModifier.kt`、`LayerRecorder.kt`，合成入口`DrawBackdropModifier.kt`，光学效果`effects/Blur.kt`、`effects/Lens.kt`、`Shaders.kt`，以及`highlight/`、`shadow/`。catalog中的`LiquidBottomTabs.kt`、`LiquidButton.kt`可作控件代码候选，按需选取，不移入整套demo。具体必要依赖及最小兼容修改仍待后续编译确认。

选择旧源时必须核对 [1.0.1的LayerBackdrop绘制修复](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.1)和 [1.0.4的绘制不更新修复](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.4)，按实际采用代码确认是否需要回移，不能直接冻结1.0.0后忽略已知问题。[Shaders.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/1.0.0/backdrop/src/main/java/com/kyant/backdrop/Shaders.kt)已有Kyant版权标头，需保留；此次检查的1.0.0 tree有LICENSE、未见NOTICE，不能将通用的“上游存在时保留NOTICE”写成已取得该文件。

当前最适合全部桌面组件的是**保留默认近实底，增加可选通透底色、清晰字色与轻量静态玻璃高光**。动态折射项目适合作风格参考：Android 桌面 XML RemoteViews 不接受任意应用内 Compose/View/Shader 渲染器。不能因为作者把卡片画在“桌面式背景”上，就认定它是真 AppWidget。[RemoteViews 官方文档](https://developer.android.com/reference/android/widget/RemoteViews)

| 优先查看 | 比较内容 | 对本项目的意义 |
| --- | --- | --- |
| 08 Glass Widget | 透明底、白字阴影、信息留白 | 真实 AppWidget，最接近可直接落地的通透方向；没有模糊或折射 |
| 01 Kyant / Backdrop | 胶囊、按钮、卡片的统一玻璃风格 | 用来选择圆角、亮边和层次强度；当前演示属于 App 内 |
| 04 compose-glass | Liquid / Lens / Clear / Frosted / Milk 材质谱 | 比较透明度与可读性；真实磨砂与折射依赖其应用内渲染，桌面只能先借鉴静态表面特征 |
| 03 QWEA0 动图 | 拖动时背景压缩、色散和边缘折射 | 直观看强透镜感；演示由 Activity 画出桌面与图标，不能作为桌面支持证据 |
| 05 PrismalAGSL、02 Vitro | 丰富高光控件与较克制浮动栏 | 对比醒目质感与信息可读性；05仅在01不适合时继续核对复用可行性，02本轮为视觉补充 |
| 09 Smartspacer、10 Kvaesitso | 信息密度、分组与半透明衬底 | 借鉴排版；前者是真 AppWidget，后者是 Launcher 自有界面，权限边界不同 |

Issue #6 的荣耀手机 WakeUp 截图只能说明用户喜欢其通透观感。本轮未取得 WakeUp 对应实现，不能据截图区分应用透明背景、Launcher 额外渲染或两者叠加，也不能承诺所有品牌复现相同效果。

## 应用内液态玻璃候选（01–07）

下列六种独立视觉库及一个同家族备选均属于应用内控件。`minSdk`、默认分支工具版本和 README 对下游的明确要求分别记录；**源码使用某版 AGP 不等于下游消费 AAR 必须同版 AGP**。高版本 Kotlin 元数据、Compose API、原生 ABI 与低版本降级仍需接入时验证。

| 编号与源码 | 真实效果与演示 | 平台、兼容性及许可 |
| --- | --- | --- |
| 01 [Kyant0/AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | [总体效果](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/kmp/artworks/banner.jpg)、[控件集合](https://raw.githubusercontent.com/Kyant0/AndroidLiquidGlass/kmp/artworks/catalog_app.jpg)。圆角、折射、色散、内阴影，按钮/滑块/胶囊风格完整 | min21，RenderEffect 31+，RuntimeShader 折射 33+；[Apache-2.0](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/LICENSE)。当前默认分支为 `kmp`；[核验提交](https://github.com/Kyant0/AndroidLiquidGlass/commit/eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5)日期 11-12，源码 Kotlin2.4.10、AGP9.3.2、CMP1.12、compile37；旧版本适配未核验 |
| 02 [itshedimisawi/vitro](https://github.com/itshedimisawi/vitro) | [应用场景](https://raw.githubusercontent.com/itshedimisawi/vitro/main/docs/vitro-showcase.webp)、[滚动动画](https://raw.githubusercontent.com/itshedimisawi/vitro/main/docs/vitro-scroll.webp)。电影页、浮动栏、chips，明暗材质和滚动边缘过渡较克制 | min23，33+完整AGSL，31–32平台模糊与近似透镜，更旧版本模糊快照；[Apache-2.0](https://github.com/itshedimisawi/vitro/blob/main/LICENSE)。README 要求 Compose1.12+；源码 Kotlin2.4.21、compile37；[0.1.0 发布提交](https://github.com/itshedimisawi/vitro/commit/3384d0432dc2a7fe69804873f607af941c2d1088)日期 10-24，项目较新 |
| 03 [QWEA0/Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android) | **[拖动折射 GIF](https://raw.githubusercontent.com/QWEA0/Liquid-Glass-Android/main/assets/hero-refraction.gif)**、[透镜细节](https://raw.githubusercontent.com/QWEA0/Liquid-Glass-Android/main/assets/hero-lens-detail.jpg)。View/XML 路线，边缘压缩环、RGB色散与传感器高光明显 | min24，完整透镜33+、部分增强36+；[MIT](https://github.com/QWEA0/Liquid-Glass-Android/blob/main/LICENSE)。[核验提交](https://github.com/QWEA0/Liquid-Glass-Android/commit/ea7908990247c56d6a9635e89ed36cb022434508)日期 11-12；源码 Kotlin2.0.21、compile36，含 C++/NEON、NDK27、CMake3.22.1，AAR 声明 ARM64/ARMv7 原生库。Compose 项目额外引 AndroidView 与原生依赖的收益尚未评估 |
| 04 [angelomarzocchi/compose-glass](https://github.com/angelomarzocchi/compose-glass) | [材质谱](https://raw.githubusercontent.com/angelomarzocchi/compose-glass/main/docs/media/glass-spectrum-light.jpg)、[应用场景](https://raw.githubusercontent.com/angelomarzocchi/compose-glass/main/docs/media/glass-in-context-light.jpg)。厚玻璃/亚克力倒角、内发光，以及较薄 liquid 材质 | min24，33+完整AGSL，31–32模糊，更旧版本 tint；[Apache-2.0](https://github.com/angelomarzocchi/compose-glass/blob/main/LICENSE)。README 要求 compile37、Compose BOM2026.09.00+；源码 Kotlin2.4.21；[核验提交](https://github.com/angelomarzocchi/compose-glass/commit/09b97ec6fe061a68f484410b3af15d97d9eb12f3)日期 11-07，社区使用规模尚无充分证据 |
| 05 [styropyr0/PrismalAGSL](https://github.com/styropyr0/PrismalAGSL) | [控件](https://raw.githubusercontent.com/styropyr0/PrismalAGSL/master/docs/screenshots/browse-glass-controls.jpg)、[折射设置](https://raw.githubusercontent.com/styropyr0/PrismalAGSL/master/docs/screenshots/refraction-blur-settings.jpg)、[App 内卡片](https://raw.githubusercontent.com/styropyr0/PrismalAGSL/master/docs/screenshots/widget-preview-refraction.jpg)。按钮、开关、滑块与渐变面板丰富，高光和折射较强 | min25，25–30 frost、31–32 RenderEffect、33+AGSL；README 要求 Kotlin2.2+。源码 Kotlin2.2.21、compile37、BOM2026.06.01；[核验提交](https://github.com/styropyr0/PrismalAGSL/commit/0da7d9c0676bd9a9eb999d2b0905de880378da9d)日期 11-15。根 [LICENSE 为 MIT](https://github.com/styropyr0/PrismalAGSL/blob/master/LICENSE)，但 [发布 POM](https://github.com/styropyr0/PrismalAGSL/blob/master/Prismal/build.gradle.kts)写 Apache-2.0；引库前需确认元数据冲突 |
| 06 [Abdullajon1881/LiquidGlass](https://github.com/Abdullajon1881/LiquidGlass) | [多控件](https://raw.githubusercontent.com/Abdullajon1881/LiquidGlass/main/docs/media/hero.png)、[液滴融合](https://raw.githubusercontent.com/Abdullajon1881/LiquidGlass/main/docs/media/merge.png)。Compose/View 双路线，按压鼓起、多个形状融合，信息卡片若采用需降低效果强度 | min21，33+shader，31–32blur，更旧版本scrim；[Apache-2.0](https://github.com/Abdullajon1881/LiquidGlass/blob/main/LICENSE)。[核验提交](https://github.com/Abdullajon1881/LiquidGlass/commit/72ad05c49628ea2270116b2943629cd81c0cc496)日期 06-13；源码 Kotlin2.1.0、AGP8.7.3、compile35。README 明确尚未发布 Maven Central，需本地发布，不能视为即加即用 |
| 07 [styropyr0/Prismal](https://github.com/styropyr0/Prismal) · 同家族技术备选 | [原作者示例](https://raw.githubusercontent.com/styropyr0/Prismal/master/screenshots/Screenshot_20260517_013547.png)。与05同作者、相近视觉，区别在 View + OpenGL ES2 路线；不另计独立风格 | min25、compile36、Kotlin2.0.21；[MIT](https://github.com/styropyr0/Prismal/blob/master/LICENSE)，[核验提交](https://github.com/styropyr0/Prismal/commit/93544d3b55cd2ca703e83c2c1a886049b8bc1ae9)日期 11-07。每面板 GLSurfaceView，捕获背景 Bitmap 后上传 GPU，可共享捕获；作者推荐新 Compose 项目使用 AGSL 继任库 |

“Liquid”“Frosted”等为作者的材质命名，不表示 UPC Pocket 已拥有该效果。PrismalAGSL 的 “Widget preview”在 App 内 Activity，核对示例 Manifest/tree 未找到 AppWidget Provider/Receiver；QWEA0 桌面式演示由 `HeroShowcaseActivity` 生成，同样不能据图认定 Launcher 支持。

## 真实桌面组件、Launcher 与模糊参考（08–14）

此表“最近推送”来自仓库 `pushed_at`，只表明活动时间，不等于稳定版本发布时间、效果验收或兼容承诺。旧库保留作视觉对比，不因此建议新引入。

| 编号与源码 | 作者效果与适用范围 | 许可、版本及维护证据 |
| --- | --- | --- |
| 08 [fyildirim-debug/glass-widget](https://github.com/fyildirim-debug/glass-widget) | [真实组件截图](https://raw.githubusercontent.com/fyildirim-debug/glass-widget/main/screenshots/widget.png)。透明底+白字阴影，是真 AppWidget，**不含实时模糊/折射**；只借鉴透明、排版和点击，不引入背景定位、AlarmManager 刷新方案 | [MIT](https://github.com/fyildirim-debug/glass-widget/blob/main/LICENSE)，min24、target/compile36；最近推送 2026-07-19 |
| 09 [KieronQuinn/Smartspacer](https://github.com/KieronQuinn/Smartspacer) | [作者 README 演示](https://i.imgur.com/g026zJ5.jpeg)。真实 AppWidget，README 说明支持任意 Launcher；存在约50%深浅背景 wrapper。Pixel 的 At-a-Glance 增强所需 Shizuku 与此通用组件能力分开；App 内 BlurView 不代表桌面实时模糊 | [App GPL-3.0](https://github.com/KieronQuinn/Smartspacer/blob/main/LICENSE)，SDK Apache-2.0；min29，最近推送 2026-11-06。本轮仅参考视觉，不复制 GPL App 代码或引入 Shizuku |
| 10 [MM2-0/Kvaesitso](https://github.com/MM2-0/Kvaesitso) | [卡片页](https://raw.githubusercontent.com/MM2-0/Kvaesitso/main/fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot02.jpg)、[主页](https://raw.githubusercontent.com/MM2-0/Kvaesitso/main/fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot01.jpg)。Launcher 自有卡片透明度与窗口壁纸模糊，具有第三方 widget provider 不拥有的 Window 控制权；这些截图仅展示作者对应界面，不作为当前版本模糊开启的证据 | [GPL-3.0-or-later](https://github.com/MM2-0/Kvaesitso/blob/main/LICENSE.txt)，min26、窗口模糊31+；最近推送 2026-11-13。相关边界见 [讨论1431](https://github.com/MM2-0/Kvaesitso/discussions/1431)、[LauncherCard](https://github.com/MM2-0/Kvaesitso/blob/main/app/ui/src/main/java/de/mm20/launcher2/ui/component/LauncherCard.kt)、[WallpaperBlur](https://github.com/MM2-0/Kvaesitso/blob/main/app/ui/src/main/java/de/mm20/launcher2/ui/launcher/helper/WallpaperBlur.kt)；本轮不复制代码 |
| 11 [Dimezis/BlurView](https://github.com/Dimezis/BlurView) | [截图](https://raw.githubusercontent.com/Dimezis/BlurView/master/BlurScreenshot.png)、[当前动态演示](https://github.com/user-attachments/assets/5abb1034-021b-4dfb-ad1b-3136a2a00a02)。成熟的应用内自定义模糊 View，31+采用 RenderNode/RenderEffect；不是折射，也不能直接放入现有 RemoteViews | [Apache-2.0](https://github.com/Dimezis/BlurView/blob/master/LICENSE.md)，当前库4.0.0/min18/compile&target36；最近推送 2026-10-25。截图与当前视频是作者不同媒体，不作为同版本对照 |
| 12 [wasabeef/Blurry](https://github.com/wasabeef/Blurry) | [GIF](https://raw.githubusercontent.com/wasabeef/Blurry/main/art/blurry.gif)。应用内 View/Bitmap 模糊、色层和下采样；可比较模糊强度，不解决桌面壁纸获取或折射 | [Apache-2.0](https://github.com/wasabeef/Blurry/blob/main/LICENSE)，README API21+、示例4.0.1；最近推送 2023-04-27，作为较早实现参考 |
| 13 [mmin18/RealtimeBlurView](https://github.com/mmin18/RealtimeBlurView) | [GIF](https://raw.githubusercontent.com/mmin18/RealtimeBlurView/master/imgs/1.gif)。历史应用内 RenderScript 毛玻璃；多视图每次绘制开销需注意，不支持 SurfaceView/TextureView；不推荐新引入 | [LICENSE Apache-2.0](https://github.com/mmin18/RealtimeBlurView/blob/master/LICENSE)（GitHub 自动元数据为 NOASSERTION，不能因此判为无许可证）；min15，README1.2.1（2019-05-24），最近推送 2023-12-05 |
| 14 [Lawnchair #7393](https://github.com/LawnchairLauncher/lawnchair/pull/7393) · 回滚实验，非候选 | Launcher 尝试通过 SurfaceFlinger/background blur regions 为桌面组件加模糊；2026-10-21合并后被 [#7419](https://github.com/LawnchairLauncher/lawnchair/pull/7419)同日回滚。记录含构建失败、残影、对比下降、过渡闪灭与叠层故障；某超过10区域的尝试还需 root。未找到可核实的稳定独立演示，画廊明确无媒体 | 仓库 [Apache-2.0](https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/LICENSE.txt)、min26。依赖 Launcher/系统侧能力；不能作为普通 AppWidget 已成熟的公开实现 |

## RemoteViews 与壁纸边界

1. **渲染位置不同。** 现有 XML RemoteViews 由 Launcher 进程按支持控件创建，不支持任意自定义 View 及其子类。Compose、GLSurfaceView、BlurView 与任意 RuntimeShader 控件不能原样嵌入。提供一张背景位图只能显示这张位图，不能自行折射背后实时桌面。[Android RemoteViews](https://developer.android.com/reference/android/widget/RemoteViews)
2. **背景来源受限。** `WallpaperManager.getDrawable()` 在 Android 13 起不再向普通应用可靠提供真实壁纸，14起对普通调用抛 `SecurityException`；具有广泛存储/内部权限的例外不适合为外观新增。即使用户手动选择同一图片，动态壁纸、桌面滚动、裁剪与组件位置也不能保证对齐。[WallpaperManager 官方说明](https://developer.android.com/reference/android/app/WallpaperManager#getDrawable())
3. **窗口模糊不是通用组件能力。** Android 12 的公开 blur API 用于自己拥有的 Window，取决于 GPU 和运行时状态，系统可以关闭；普通 widget provider 不能设置任意 Launcher 的窗口模糊。Launcher 项目能做不代表 UPC Pocket 能在所有桌面做到。[AOSP Window blurs](https://source.android.com/docs/core/display/window-blurs)
4. **兼容与成本分层。** 本项目 min31 覆盖平台模糊 API，但很多真折射需33+；API存在也不意味着能对 Launcher 背景采样。桌面首发优先资源背景、透明度和字色，不增加壁纸权限、截图授权、服务或大位图。App 内若以后单独引玻璃库，再核对许可、工具链、ARM/x86回退、帧率与耗电；本轮不升级依赖。

## 去重、媒体与验证边界

- 已排除 Kyant 系列的 `promicx/KMPLiquidGlass → Kashif-E → Kyant0` 同源分支，以及 QWEA0 的 JosephMontfort/Ibrohim-18 forks；07号明确与05号同家族，14号是回滚反例，不能宣称找到了14种独立风格或14个可直接接入的组件库。
- 本地效果画廊在 `.lody/outputs/208-glass-research-20261117/index.html`，数据为 `projects.json`，来源与图片尺寸/帧数记录在 `media-sources.json`。画廊收录21份原作者公开图片/动画，保留原像素内容与比例；未生成、调色或合成 UPC Pocket“实现截图”。只有14号无媒体，不补造动画。媒体和预览辅助文件位于被忽略目录，不作为仓库正式资产提交。
- 画廊区分应用内玻璃、真实桌面组件、Launcher、传统模糊和实验，可放大、切换多图并在本机记下喜欢的编号。选择仅保存在当前浏览器本地，不回传；用户可直接用编号说明材质与排版偏好。
- 已完成上游仓库/README/默认分支源码、公开媒体、许可证与系统文档调查；只读核对本项目工具链、renderer、偏好与发布入口。图片可读取或源码声明 `minSdk` 不代表示例跑过、帧率已测、AAR已兼容或跨 Launcher 已验收。
- 前期调研不修改业务代码、版本号或APK，不运行第三方项目/安装APK，不登录CAS或执行学校业务；本次更新关联计划并按用户授权提交推送文档，不实际合并PR或发布Release。正式更新范围以 [2.0.8计划](../design/2.0.8-update-plan.md) 为准；01/03/05风格和#9候选均需实际验证兼容、降级与页面稳定性，不将生图或待合代码写成已验收。

本轮查阅的项目内关键依据为 [ARCHITECTURE](../../ARCHITECTURE.md)、[API](../../API.md)、[BUILD](../BUILD.md)、[M5组件设计](../m5/2026-11-05-课表与组件设计.md)、[组件视觉修订](../m5/2026-11-05-组件视觉修订.md)及 [Agent 手册](../agent-native/README.md)。具体源码复用和未来最小验收见 [工程方案](../design/2.0.8-library-layout-and-widget-appearance.md)。
