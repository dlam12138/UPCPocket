# UPC Pocket iOS 静态网页版（PWA）

源代码在 [`web/`](../web/)，面向 Safari 和 iPhone 主屏幕。这是一个独立的静态站点：不需要本项目自建的账号服务、数据库或 `/api/*` 接口，学校业务统一交给学校官方页面。

基线由 PR [#16](https://github.com/dlam12138/UPCPocket/pull/16) 的网页版实现整合而来，保留 Safari 书签、原来的浏览器存储和学习记录。站内个人数据由浏览器本机处理。账号登录、预约、支付及实时业务查询由相应官方页面处理。

## 能力范围

| 能力 | 当前实现 |
| --- | --- |
| 课表与日程 | 粘贴或文件导入学期课表，或用 `.ics` 临时预览。可按日新增、编辑和删除本机个人日程，首页显示当天课程和日程。 |
| 待办 | 新增、编辑、截止、优先级、完成、删除与撤销，支持按日期、重要程度和新增时间排序。 |
| 专注 | 可选 15、25、45 或 60 分钟，支持暂停、继续、重开恢复、历史与今日／本周统计。 |
| 校园服务 | 搜索服务、设置常用校区并固定入口到首页。学习平台、学校业务和场馆入口交给原页面或公众号办理。 |
| 通讯录、地图与平面图 | 按校区查公开电话和地址，关联地点可打开地图。随站点位与路网可离线规划步行或骑行。卓越楼可换楼区、楼层和查找房号。 |
| 学校校历 | 随站索引展示最近核对的 2026–2027 学年两学期官方原图，并保留教务处栏目和原文。 |
| 离线 | 首次联网缓存完成后，可离线重开应用并使用本机数据、通讯录、地图和卓越楼图层。官方网页与校历原图需要可访问的网络。 |

在首页右上角设置常用校区。在「服务」搜索条目，点「固定」添加到首页。地图可手动选择起终点。定位与前台导航需用户同意，离开地图或页面进入后台后停止。古镇口西区主要道路尚未录入，实际可通行区域以现场为准。待办截止和专注结束目前没有关页后的系统提醒。

## 本地预览与静态 HTTPS

不要直接双击打开 `index.html`；ES 模块和 Service Worker 需要 HTTP(S) 来源。在仓库根启动任意静态文件服务器，只把 [`web/`](../web/) 作为站点根：

```sh
python3 -m http.server 8000 --bind 127.0.0.1 --directory web
```

Windows 可使用 `py -m http.server 8000 --bind 127.0.0.1 --directory web`。打开 [http://127.0.0.1:8000/](http://127.0.0.1:8000/)；本机回环地址属于安全上下文，可以注册 Service Worker。保持同一地址和端口以保留当前浏览器的 IndexedDB 与离线缓存。

在 iPhone 上预览时，同一局域网的普通 HTTP 地址不是安全上下文，Service Worker 不会注册。请使用已经配置 HTTPS 的静态托管地址，或在本机配置受信任的 HTTPS 后从 Safari 访问。部署只需把 [`web/`](../web/) 的静态文件放到任意静态 HTTPS 主机，不需要反向代理或后端进程。

## 安装与离线

1. 在 Safari 打开稳定的 HTTPS 地址。
2. 点「分享 → 添加到主屏幕」，保持「作为网页 App 打开」。
3. 首次启动或更新后先保持联网，待 Service Worker 下载完静态资源，再离线重开。更新缓存前断网可能暂时打开旧页面，重新联网刷新可恢复读取。

Service Worker 按部署范围和版本缓存 [`web/sw.js`](../web/sw.js) 允许列表中的静态资源，当前版本为 `v24`。`.ics` 解析器和 Safari 书签脚本按需加载，首次使用时才缓存；离线且从未加载过时会提示先联网。修改静态文件后需同步提升 Service Worker 版本与相关查询参数，否则已安装的旧缓存不会更新。清除网站或网页应用数据会移除导入的课表、待办、专注记录和离线缓存。

Safari 标签页与主屏幕应用使用各自的本机存储；在 Safari 导入的课表不会自动出现在主屏幕应用，需要在两者各自导入。 [Apple 安装指南](https://support.apple.com/guide/iphone/iphea86e5236/ios) · [WebKit 存储说明](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/)

## 导入学期课表

1. 进入「课表 → 课表选项 → 设置 Safari 书签」，点「复制书签脚本」。在 Safari 书签中编辑一个书签，把网址换成复制的内容。以后可重复使用同一书签。 [Apple 书签指南](https://support.apple.com/guide/iphone/iph42ab2f3a7/ios)
2. 在 Safari 登录学校课表页，从书签列表运行该书签。核对中文预览并确认导出整学期，成功后点「复制课表 JSON」。
3. 回到平时使用的 Pocket 页面，进入「课表」点「粘贴导入」。如果 iOS 显示「粘贴」，点它确认。

也可以在学校页下载 JSON 到「文件」，回到 Pocket 点「导入 JSON」选择文件。两种入口使用同一套 V3 校验：严格 UTF-8、学期信息、连续完整周集合、每周七天日期与课程字段；全部通过后才按学年和学期一次提交。校验或写入失败保留上一次课表，不会把缺失周当成空周。已有课表时从标题旁的「课表选项」更新或删除本机学期。清除站点数据后可用原始 JSON 重新导入。 [WebKit 剪贴板规则](https://webkit.org/blog/10855/async-clipboard-api/)

`.ics` 仍作为临时预览，只保留在当前页面内存；关闭页面后不保留，需要长期查看时请导入完整学期 JSON。

## 学校官方入口

「服务 → 校园官方网页」可查看完整入口。以下是常用地址。

| 服务 | 官方入口 |
| --- | --- |
| 唐岛湾图书馆 | [图书馆座位预约系统](https://libspace.upc.edu.cn/libseat/) |
| 古镇口东区图书馆 | [超星座位入口](https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453) |
| 求是图书馆 | [超星 seatengine 入口](https://office.chaoxing.com/front/third/apps/seatengine/index?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258) |
| 空闲教室 | [学校空闲教室查询](https://app.upc.edu.cn/freeclass/wap/default/all) |
| 学校校历 | [教务处校历栏目](https://jwc.upc.edu.cn/603/list.htm) |
| e站通 | [学校服务大厅](https://service.upc.edu.cn/) |
| 学校课表 | [学校课表页](https://app.upc.edu.cn/site/weekschedule/index) |
| 教务与成绩 | [教务系统](https://jwxt.upc.edu.cn/jsxsd/framework/xsMainV.htmlx) · [成绩与学分](https://app.upc.edu.cn/site/score/index) |
| 学校门户 | [学校官网](https://www.upc.edu.cn/) · [数字石大](https://i.upc.edu.cn/) · [教务处](https://jwc.upc.edu.cn/) · [学院目录](https://www.upc.edu.cn/jxkydw.htm) |
| 校园生活 | [校园卡官方登录](https://mb-xyk.upc.edu.cn/berserker-auth/cas/redirect/wislearn?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit) · [石光](https://dekt.upc.edu.cn/) · [唐岛湾电量（HTTP）](http://dfcz.upc.edu.cn/ShiYou/Default.aspx) · [古镇口电费](https://gzkdf.upc.edu.cn/acmswx/index.aspx) |
| 学习平台 | [知到](https://passport.zhihuishu.com/login) · [雨课堂](https://www.yuketang.cn/web) · [课堂派](https://www.ketangpai.com/) · [石大云课堂](https://learn.upc.edu.cn/meol/index.do) · [惟真学堂](https://upcwzedu.zhihuishu.com/) |
| 体育馆、游泳馆 | [体育场馆预约规则](https://sports.upc.edu.cn/2023/0606/c7678a405213/page.htm) · [学校游泳馆介绍](https://news.upc.edu.cn/info/1432/114958.htm)，在「中国石油大学体育场馆」公众号办理。 |

图书馆和空闲教室通常需要校园网或学校允许的网络。本站不接触学校账号、Cookie 或预约数据；学校页面和外部浏览器有各自的数据处理规则。

## 本机数据

- 个人日程、待办、专注状态与历史和已导入课表保存在当前浏览环境的 IndexedDB（数据库 `upc-pocket`）。常用校区、固定入口、待办排序、专注时长和外观偏好保存在 `localStorage`。首页和本机日程按上海日期归组，待办截止仍按设备时区输入和显示。
- 课表导入只读取用户主动粘贴或选择的文件；不接收学校账密或会话，不上传课表或记录课程日志。
- `.ics` 预览只读入当前页面内存。Pocket 只在用户点击粘贴时访问系统剪贴板；启用 Apple 通用剪贴板时，系统可将复制内容提供给其他符合条件的附近设备。 [Apple 通用剪贴板](https://support.apple.com/102430)
- 离线缓存只保存网页本身和同源静态资源，校历原图由教务处提供。定位只在主动使用地图时留在运行内存。完整说明见 [隐私与数据](PRIVACY.md#ios-网页版静态-pwa)。

## 来源、许可证与验证边界

- 网页版来源于 PR [#16](https://github.com/dlam12138/UPCPocket/pull/16)，作者 Lycoris；整合时保留原页面结构、官方地址、浏览器存储约定和 Safari 书签。
- `.ics` 预览使用 [ical.js 2.2.1](https://registry.npmjs.org/ical.js/2.2.1)，随站点提供完整的[上游 MPL-2.0 许可证](../web/vendor/ical-LICENSE)。
- 通讯录、校园地图及卓越楼图层复用同仓 Android 的公开或已重绘数据。校园地图和派生路网采用 [OpenStreetMap ODbL 1.0](https://www.openstreetmap.org/copyright)，地图界面显示署名、许可和数据入口。卓越楼图层保留[教务处原文](https://jwc.upc.edu.cn/2026/0830/c23036a498711/page.htm)和原图页码。
- 2026-11-26 的 WebKit 定向验证覆盖服务搜索、首页固定、通讯录拨号链接、步骑行算路、合成定位、楼层查房、日程增改删、专注恢复和首次离线打开校园资料。用旧版静态站点升级到 v24，确认 v2 数据库中的待办、暂停计时和导入课表保留。iPhone 17e 模拟器中的 Safari 打开了新版页面。
- 解析回归可在仓库根运行 `node --test tools/web/test_*.mjs`，覆盖课表、`.ics` 和校园地点匹配。校区道路与楼层门牌需现场核对。真机 GPS、正式部署后的主屏幕安装及真实学校登录未在本轮验收。
- 决策见 [ADR 008](adr/008-ios-pwa-local-data-and-school-links.md) 、[ADR 009](adr/009-web-semester-timetable-import.md) 和 [ADR 010](adr/010-safari-static-campus.md)。
