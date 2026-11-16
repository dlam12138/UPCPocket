# 校园卡：余额、本人流水与微信充值

继承[根](../../AGENTS.md)与 [feature](../AGENTS.md)指令。先读[架构](../../ARCHITECTURE.md)的账号/校园卡流程、[隐私](../../docs/PRIVACY.md)对应章节；流水读[研究与验收](../../docs/research/native-card-history.md)，充值读[集成记录](../../docs/releases/2.0.6-card-recharge.md)，连接恢复读[2.0.7](../../docs/releases/2.0.7-development.md)。

## 入口与所有权

| 文件 | 作用 |
| --- | --- |
| [CardPlugin.kt](src/main/kotlin/com/upcpocket/app/card/CardPlugin.kt) | 能力、页面、组件依赖与认证注入 |
| [CardBalanceService.kt](src/main/kotlin/com/upcpocket/app/card/CardBalanceService.kt) | 页面/组件/Worker 统一绑定余额入口 |
| [CardHistoryClient.kt](src/main/kotlin/com/upcpocket/app/data/cardhistory/CardHistoryClient.kt) | 本人核验、流水与汇总只读调用 |
| [CardRechargeService.kt](src/main/kotlin/com/upcpocket/app/data/cardrecharge/CardRechargeService.kt) | 充值准备、单次订单、微信准备与查单状态机 |
| [RechargePendingStore.kt](src/main/kotlin/com/upcpocket/app/data/cardrecharge/RechargePendingStore.kt) | 加密待查标记与可靠提交 |
| [CardWidgetRenderer.kt](src/main/kotlin/com/upcpocket/app/widget/CardWidgetRenderer.kt) | 余额 RemoteViews；系统 Provider 在 app |

## 独有约束

- 余额目标只来自当前受控本人学号，页面/组件/Worker 不接受路由、Intent、旧草稿或脱敏字符串作为目标。余额读取不发送密码，不代表 CAS 认证成功。
- 旧余额快照仅整串学号匹配时迁移；保留原查询时间。换号立即隐藏旧值；同号改密允许历史余额，拒绝旧请求写回。清缓存门闩先落盘，清理失败不能恢复旧值。
- 组件读取返回状态与 revision 同一稳定版本；发布前复核账号/业务/快照版本，不仅检查查询开始时的目标。
- 流水只读本人，服务端身份与当前账号一致才读取；分页完整性真实表达，“加载全部”需用户主动触发。流水/token 不新增持久化或后台轮询。
- 密码适配由 app 经 `core:upc-auth` 注入，card 不依赖 `core:account`。网页登录完成后仍核验学校结果、本人与账号代次；不能将任意回调链接当 token。
- 充值是校园写操作，与余额/流水的只读验证分开。进入页只认证和读取准备；正常路径“输入金额 → 点击充值 → 拉起微信”，不再叠加金额确认。额度依学校动态配置及已核对 UI 缺省处理，不能猜额度或写死经验上限。
- 下单前确认微信可用及当前身份；请求前可靠保存完整待查标记，失败即停止。密码、token、签名材料、支付链接不得落入标记。未知/超时/取消不自动重试、不自动解除防重；回到 App 只做一次只读查单，分发/回跳不代表付款或到账。
- 同号改密保留待查标记供重新认证后核对；换号/删号清个人数据且旧操作失效。清本机标记不等于取消订单或退款。
- 固定只读 GET 连接恢复不得复用到订单、支付或密码/票据。传输诊断只写固定阶段与有界数字，取消快照不能冒充底层完整轨迹。

## 验证与联动

按改动选 `:feature:card:testDebugUnitTest --tests <相关测试类>`；插件/组件/微信分发影响同时核对 app 的对应消费者，必要时 `:app:lintDebug` 与 `checkModuleBoundaries`。只读准备、下单、付款、到账分别给证据；未授权不做写操作，CAS 验收按根指令使用本人真实账密。

修改账号绑定联查 account-api、宿主 invalidator 与 WidgetRefreshWorker；微信分发联查宿主 SDK 配置；存储变更联查升级兼容。同步 API、PRIVACY、相关发布记录，涉及职责/事务决策再更新 ARCHITECTURE/ADR。
