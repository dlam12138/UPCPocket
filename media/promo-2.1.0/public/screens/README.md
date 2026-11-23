# 2.1.0 界面素材

截图来自从 `f9e7a37` 构建的 `2.1.0-promo` 调试包，在独立 Android 16 模拟器上拍摄。模拟器启用飞行模式、关闭 Wi-Fi，全程未填写学校账号。出现个人课程、金额、座位或任务时，素材使用仓库的调试预览或本机合成数据。部分截图裁去系统栏或截取界面局部以便阅读。

| 素材 | 画面来源 |
| --- | --- |
| `home*`、`services*`、`official*`、`learning*` | 真实首页、服务与学习平台界面。`home-demo` 通过预览入口加入了合成课表与待办 |
| `timetable*`、`today`、`classroom*` | 课表和空闲教室的真实 Compose 预览，课程与教室数据由调试预览生成。`timetable-app` 为宿主的未配置账号状态 |
| `campus-map*`、`map-*`、`directory*` | 真实校园地图、离线步行与骑行预览、公开通讯录。地图保留 OpenStreetMap 署名 |
| `card-history`、`card-recharge`、`recharge-demo`、`power*` | 真实流水、充值和电费入口。`recharge-demo` 使用充值调试夹具，点击合成金额按钮后停在微信充值页 |
| `library-*`、`shiguang*`、`ezton` | 真实馆区入口、选座和石光预览、e站通离线入口。座位与石光个人数据来自调试夹具 |
| `gym`、`pool` | 两个真实的微信交接说明，保留公众号和菜单文字 |
| `todo*`、`focus*` | 真实待办、位置设置、计时和统计。在独立模拟器上加入本机演示待办、运行计时并补录一条演示记录 |
| `widgets*`、`widget-*` | 真实组件设置、桌面本周课表及待办组件。今日课表、校园卡与电量来自真实 RemoteViews 渲染的合成内容。本周课表实拍为未配置账号状态 |
| `settings`、`campus-preference`、`appearance*`、`about`、`diagnostics` | 真实设置与关于页。关于页裁到 QQ 交流群，诊断仅取入口卡片 |
