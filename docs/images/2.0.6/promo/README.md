# 2.0.6 推广截图

本组五张图片已由本地 Debug 预览生成并逐张检查，用于介绍 2.0.6。预览复用实际生产 Compose 页面，所有课程、教师、地点、待办、教室可用状态、充值账号/额度和金额都是合成演示，不是个人校园记录或实时查询。截图不证明真实付款、到账或学校服务可用性。

| 文件 | 内容与来源 |
| --- | --- |
| `01-home.png` | 真实 `HomeScreen`，合成 `TodayPanel` 与内存待办；服务入口只展示、不导航；不读取余额或正式账号 |
| `02-timetable.png` | `TimetablePreviewActivity` 的真实学期/周标题、周网格；合成课程 |
| `03-classroom.png` | `ClassroomPreviewActivity` 的真实筛选和结果组件；内存合成教室，不联网 |
| `04-recharge.png` | `CardRechargeFixtureActivity` 的真实充值页面；账号、规则和请求均为内存夹具，仅选择合成金额 50 元，不创建真实订单或打开微信 |
| `05-todo.png` | 真实 `TodoScreen` 与内存 `TodoStore`；不读写数据库、不调度提醒或通知 |

首页为内容预览，不接入正式 `AppGraph`，未复制私有悬浮导航或余额 UI。其余入口的 `promo=true` 只切换预览显示/合成数据，默认验收夹具行为保留。推广预览及新增入口仅在 `src/debug`，已验收 Release APK 不重编、不改字节。

仅供本机截图的固定启动入口（需先由主协调安装对应 Debug 包）：

```text
adb -s emulator-5556 shell am start -S -n com.upcpocket.next/com.upcpocket.app.debug.PromotionPreviewActivity --es screen home
adb -s emulator-5556 shell am start -S -n com.upcpocket.next/com.upcpocket.app.timetable.TimetablePreviewActivity --ez promo true
adb -s emulator-5556 shell am start -S -n com.upcpocket.next/com.upcpocket.app.ui.classroom.ClassroomPreviewActivity --ez promo true
adb -s emulator-5556 shell am start -S -n com.upcpocket.next/com.upcpocket.app.ui.ecard.CardRechargeFixtureActivity --ez promo true
adb -s emulator-5556 shell am start -S -n com.upcpocket.next/com.upcpocket.app.debug.PromotionPreviewActivity --es screen todo
```
