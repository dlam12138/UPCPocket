# 郑州大学参考资料归档

这里全部是 **郑州大学 / ZZUPocket 的历史参考**，不是 UPC Pocket 的当前接口契约、入口配置或验收结果。保留资料用于理解上游实现，学校专用结论不可直接迁移。

## 按系统汇总

| 领域 | 历史资料内容 | UPC 使用边界 |
| --- | --- | --- |
| 统一认证 | 郑大 CAS、MFA、userToken、可信设备与在线检测 | UPC 登录链必须独立验证，不能复制请求头、有效期和设备策略 |
| 本科教务 | EAS 学期、课表、节次、实验课及空教室 | 只参考从页面核对数据契约的方法 |
| 研究生教务 | gmsstu、网页登录、独立 CookieJar、整学期数据与周次对齐 | 不假设 UPC 使用同一平台或相同会话 |
| 一卡通与电费 | 郑大 token 交换、余额/流水、寝室与空调双表计、房号算法 | 不迁移郑大域名、房间 JSON、校区编号与计费规则 |
| 图书馆 | order.lib 域名、CAS 票据、Cookie、H5 和 WebView 灰屏排查 | 不推断 UPC 的 HTTPS、校外访问和会话条件 |
| 产品与 Android | 课表、番茄钟、统计、Room、RemoteViews 与界面方案 | 本地能力和设计思路可参考；UPC 功能是否完成仍须独立验证 |

可迁移的探索流程已抽到 [校园系统机制探索方法](../../research/校园系统探索方法.md)。当前 UPC 入口见 [UPC 官方入口清单](../../upc/官方入口.md)。

## 原始资料目录

下面保留上游文档的结构和历史正文。文档中的“已实测”“官方”“当前”等表述均属于郑大及其历史记录；有前后勘误时优先看后续证据。

- [原版项目 README](UPSTREAM-README.md)
- [研究生课表与会话](gms-schedule-api.md)
- [上游开发流程与阶段计划](开发流程与阶段计划.md)
- [接口现状核查](interfaces/接口现状核查.md)
- [本科课表接口](interfaces/eas-schedule-api.md)
- [鉴权与登录契约](interfaces/鉴权与登录契约.md)
- [接口实测坑](interfaces/接口实测坑.md)
- [图书馆 WebView 灰屏](interfaces/图书馆WebView灰屏.md)
- [可行性与开发方案](design/可行性与开发方案.md)
- [功能与工具清单](design/功能与工具清单.md)
- [UI 与登录设计](design/UI与登录设计.md)
- [Android 接入设计](design/Android接入设计.md)

原版 README 中根目录、代码和构建路径以原上游项目布局为准。现有 Android 源码及 `tools/gms_probe.py` 仍包含郑大逻辑；本次仅整理文档，没有完成运行时代码迁移。
