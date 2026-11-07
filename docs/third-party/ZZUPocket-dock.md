# ZZUPocket 悬浮 dock 来源与许可

本项目的底部悬浮胶囊 dock 视觉/覆盖层结构改编自：

- 项目：[3beuml/ZZUPocket](https://github.com/3beuml/ZZUPocket)
- 文件：`app/src/main/java/com/zzupocket/app/ui/nav/AppNav.kt`
- 固定来源提交：`dda318e9b37d8b65991a965e72fd4dd40b630a20`
- 原始链接：<https://github.com/3beuml/ZZUPocket/blob/dda318e9b37d8b65991a965e72fd4dd40b630a20/app/src/main/java/com/zzupocket/app/ui/nav/AppNav.kt>

本项目的修改：

- 只保留首页、课表、待办、专注四个 UPC 入口；
- 接入本项目 Material 3 主题与 App 深浅色设置；
- 用 `HorizontalPager` 的连续位置驱动选中泡、图标色态与点击切换；
- 使用 `selectable` 补足 selected/无障碍语义；
- 把胶囊改为不占 Scaffold.bottomBar 的覆盖层，并通过 `LocalDockClearance` 给页面/FAB/Snackbar 留出安全余量。

上游没有整页 Pager；Pager、页面活动门控和 UPC 页面适配是本项目新增，不声称来自上游。

## MIT License

Copyright (c) 2026 3beuml

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
