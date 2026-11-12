# 校园卡原生流水接入

本改动把已在独立 Android 客户端由本人账号验证成功的 UPC 新校园卡协议移植至当前插件架构。登录、查询和显示均使用原生界面及 HTTPS 请求；没有嵌入网页、微信 Cookie 导入或流量代理。

## 结构

- `core:upc-auth`：受控读取一次学校凭据，独立 CAS CookieJar，动态 `execution` 表单与单次密码提交。沿固定学校域逐跳检查重定向；校园卡 HTTP 回调直接升级为 HTTPS，保留原始 query。
- 必须区分前端 `synjones-auth`（已完成的令牌）与 `ticket`（需要兑换的票据）。前者直接使用，后者才向固定 OAuth 地址兑换；不能把所有成功跳转都当作 ticket。
- `feature:card`：只接收宿主认证适配返回的 token；先查本人身份，再分页读取流水、按月过滤、显式加载全部、搜索已加载记录与显示学校收支汇总。金额保持整数分。
- 会话绑定内部 accountId、本人学号、凭据代次及本次业务 epoch。换号/删号/改密触发失效，响应发布前再次核对；不新增持久化。
- 页面路由 `card.history` 由校园卡插件注册，首页余额卡按钮进入。认证适配缺失时保留原有余额插件行为。

## 官方地址与字段

源自 UPC 新校园卡官方前端及本人授权查询过程。地址不包含真实票据、学号或业务记录。

| 用途 | 固定 HTTPS 主机与路径 |
| --- | --- |
| 入口 | `mb-xyk.upc.edu.cn/berserker-auth/cas/redirect/wislearn` |
| 认证 | `cas.upc.edu.cn/cas/login` |
| 可选票据兑换 | `mb-xyk.upc.edu.cn/berserker-auth/oauth/token` |
| 本人核验 | `mb-xyk.upc.edu.cn/berserker-base/user` |
| 流水 | `mb-xyk.upc.edu.cn/berserker-search/search/personal/turnover` |
| 收支汇总 | `mb-xyk.upc.edu.cn/berserker-search/statistics/turnover/count` |

业务使用 `synjones-auth` 请求头与 `synAccessSource=h5`。分页为 `size/current`；月范围为 `timeFrom/timeTo`。解析 `records/total/pages`，交易字段为 `orderId/resume/tranamt/typeFrom/jndatetimeStr`（或 `effectdateStr`）。不提交其他账号标识来查询他人流水。

同一 Synjones `berserker-search` 平台的多份公开客户端与官方新校园卡前端均以 `typeFrom = "1"` 表示收入、`"2"` 表示支出。本项目只接受这两个已知值；缺失或其他值直接报 `N-TYPE`，不把未知方向默认当作支出。`tranamt` 与汇总 `income/expenses` 以整数分保存，可无损接受 `820` 或 `820.0` 这类整数写法，小数分或其他单位拒绝而不是四舍五入。

## 验证与边界

- 独立客户端的正常无验证码登录及本人流水已由用户真机验证；这不能替代移植后的本项目真机验收。
- 本项目验证：`:core:upc-auth:testDebugUnitTest` 54 项通过；`:feature:card:testDebugUnitTest` 78 项通过；`:app:testDebugUnitTest` 99 项中 98 项通过、1 项既有真实更新联网测试跳过。`checkModuleBoundaries` 与 R8/resource shrink Release 构建通过；合成数据覆盖已知/未知收支方向、整数与小数金额、单次密码提交、验证码提前停止和迟到结果隔离。所有新增回放样本为合成数据，不依赖真实账号或校园网络。
- 2026-11-12 在无既有账号的隔离模拟器上，经正常账号设置录入用户授权的本机学校账号，使用 R8 Release 完成本人只读验收：CAS 登录与 `/berserker-base/user` 本人核验通过；全部日期首屏 50/850 条，手动“加载更多”后 100/850 条；2026-10 按月查询 50/103 条，汇总可用；该月有界“加载全部”完成 103/103 条且不显示“加载更多”；搜索零匹配为 0 行、清空后恢复；1.5× 字体下深浅色均显示真实数据行、汇总与遮挡后的账号，文本未越界；删除账号后页面回到“尚未设置可用学校账号”，查询/加载全部均禁用。验收后已删除账号，未卸载应用、未清除既有数据。
- 真实验收中，空闲后首个只读请求偶尔返回 NETWORK（“学校服务暂时无法连接”），手动再查即成功；客户端保持 `retryOnConnectionFailure(false)`，不自动重试。本次账号未加载全部 850 条，只做了上述单月 3 页的有界“加载全部”。验证码/二次验证未在真实登录中出现，只由合成测试覆盖“停止且不提交/不重试密码”；改密、换号和迟到结果隔离由合成测试覆盖，未用真实账号制造这些状态。
- 会话和列表仅在内存中保留，没有离线流水、后台轮询或写操作。本改动只增加流水能力，不代表新系统的全部功能均已接入。
