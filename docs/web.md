# UPC Pocket iOS 静态网页版（PWA）

源代码在 [`web/`](../web/)，面向 Safari 和 iPhone 主屏幕。这是一个独立的静态站点：不需要本项目自建的账号服务、数据库或 `/api/*` 接口，学校业务统一交给学校官方页面。

本目录来源于 PR [#16](https://github.com/dlam12138/UPCPocket/pull/16)（作者 Lycoris）的网页版实现，本次只整合静态外壳、本机课表导入、待办、专注与官方入口。原设计与地址、浏览器存储约定、Safari 书签、第三方 iCalendar 解析器及其许可证保留；站内登录、站内预约、服务端代登录与相关脚本不进入本版。当前不提供站内预约、站内账号会话或安全历史。

## 能力范围

| 能力 | 当前实现 |
| --- | --- |
| 课表 | 粘贴或文件导入完整学期，或打开 `.ics` 临时预览；导入结果保存在本机 |
| 待办 | 本机新增、编辑、截止、勾选、删除与撤销 |
| 专注 | 本机计时、暂停、恢复、结束、历史与今日/本周统计 |
| 离线 | Service Worker 缓存应用外壳；已导入课表、待办和专注可离线使用 |
| 图书馆 | 唐岛湾、古镇口东区、求是三个馆区的学校官方入口 |
| 空闲教室 | 学校官方查询入口 |
| 学校校历 | 教务处校历栏目入口 |
| e站通 | 学校官方服务大厅入口 |

站内不收集学校账号密码，不保存 Cookie、Ticket 或预约记录。登录、验证码、选座、预约、取消与记录都在学校官方页面完成，实际结果以学校页面为准。校园卡、石光、宿舍电量在网页版显示为暂未接入。Android 应用的图书馆、空闲教室和 e站通仍为手机原生直连，不受本网页版影响。

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
3. 首次从主屏幕启动时保持联网，让静态资源完成初始化；之后可从图标离线重开。

Service Worker 按部署范围和版本缓存 [`web/sw.js`](../web/sw.js) 允许列表中的静态资源，当前版本为 `v23`。`.ics` 解析器和 Safari 书签脚本按需加载，首次使用时才缓存；离线且从未加载过时会提示先联网。修改静态文件后需同步提升 Service Worker 版本与相关查询参数，否则已安装的旧缓存不会更新。清除网站或网页应用数据会移除导入的课表、待办、专注记录和离线缓存。

Safari 标签页与主屏幕应用使用各自的本机存储；在 Safari 导入的课表不会自动出现在主屏幕应用，需要在两者各自导入。 [Apple 安装指南](https://support.apple.com/guide/iphone/iphea86e5236/ios) · [WebKit 存储说明](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/)

## 导入学期课表

1. 进入「课表 → 课表选项 → 设置 Safari 书签」，点「复制书签脚本」。在 Safari 书签中编辑一个书签，把网址换成复制的内容。以后可重复使用同一书签。 [Apple 书签指南](https://support.apple.com/guide/iphone/iph42ab2f3a7/ios)
2. 在 Safari 登录学校课表页，从书签列表运行该书签。核对中文预览并确认导出整学期，成功后点「复制课表 JSON」。
3. 回到平时使用的 Pocket 页面，进入「课表」点「粘贴导入」。如果 iOS 显示「粘贴」，点它确认。

也可以在学校页下载 JSON 到「文件」，回到 Pocket 点「导入 JSON」选择文件。两种入口使用同一套 V3 校验：严格 UTF-8、学期信息、连续完整周集合、每周七天日期与课程字段；全部通过后才按学年和学期一次提交。校验或写入失败保留上一次课表，不会把缺失周当成空周。已有课表时从标题旁的「课表选项」更新或删除本机学期。清除站点数据后可用原始 JSON 重新导入。 [WebKit 剪贴板规则](https://webkit.org/blog/10855/async-clipboard-api/)

`.ics` 仍作为临时预览，只保留在当前页面内存；关闭页面后不保留，需要长期查看时请导入完整学期 JSON。

## 学校官方入口

以下地址是本站使用的固定官方入口，站内不代理其接口：

| 服务 | 官方入口 |
| --- | --- |
| 唐岛湾图书馆 | [图书馆座位预约系统](https://libspace.upc.edu.cn/libseat/) |
| 古镇口东区图书馆 | [超星座位入口](https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453) |
| 求是图书馆 | [超星 seatengine 入口](https://office.chaoxing.com/front/third/apps/seatengine/index?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258) |
| 空闲教室 | [学校空闲教室查询](https://app.upc.edu.cn/freeclass/wap/default/all) |
| 学校校历 | [教务处校历栏目](https://jwc.upc.edu.cn/603/list.htm) |
| e站通 | [学校服务大厅](https://service.upc.edu.cn/) |
| 学校课表 | [学校课表页](https://app.upc.edu.cn/site/weekschedule/index) |

图书馆和空闲教室通常需要校园网或学校允许的网络。本站不接触学校账号、Cookie 或预约数据；学校页面和外部浏览器有各自的数据处理规则。

## 本机数据

- 待办、专注状态与历史、导入的学期课表保存在当前浏览环境的 IndexedDB（数据库 `upc-pocket`）。降低透明度等外观偏好保存在 `localStorage`。
- 课表导入只读取用户主动粘贴或选择的文件；不接收学校账密或会话，不上传课表或记录课程日志。
- `.ics` 预览只读入当前页面内存。Pocket 只在用户点击粘贴时访问系统剪贴板；启用 Apple 通用剪贴板时，系统可将复制内容提供给其他符合条件的附近设备。 [Apple 通用剪贴板](https://support.apple.com/102430)
- 离线缓存只保存网页本身和静态资源，不缓存学校业务页面或个人预约数据。完整说明见 [隐私与数据](PRIVACY.md#ios-网页版静态-pwa)。

## 来源、许可证与验证边界

- 网页版来源于 PR [#16](https://github.com/dlam12138/UPCPocket/pull/16)，作者 Lycoris；整合时保留原页面结构、官方地址、浏览器存储约定和 Safari 书签。
- `.ics` 预览使用 [ical.js 2.2.1](https://registry.npmjs.org/ical.js/2.2.1)，随站点提供完整的[上游 MPL-2.0 许可证](../web/vendor/ical-LICENSE)。
- 保留了原课表与 `.ics` 的既有解析测试 [`tools/web/test_timetable_import.mjs`](../tools/web/test_timetable_import.mjs) 和 [`tools/web/test_calendar_import.mjs`](../tools/web/test_calendar_import.mjs)。本轮整合未新增或重跑测试，也未构建、运行浏览器或访问学校服务；仅完成源码、资源引用与差异核查。真实学校页面、真机安装和离线行为仍需按实际发布环境验证。
- 决策见 [ADR 008](adr/008-ios-pwa-local-data-and-school-links.md) 与 [ADR 009](adr/009-web-semester-timetable-import.md)。
