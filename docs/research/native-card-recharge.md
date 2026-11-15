# 原生校园卡微信充值

PR #5 作者报告已在独立实验 App 0.9.0 的手机上验证严格原生充值链成功。本改动将同一协议移植到当前校园卡插件，取消实验版单次 1–20 元限制及手动准备/单独再次唤起步骤。朋友项目移植后的真机付款、返回与到账仍需复验，不将原型验收扩大为本包验收。

## 页面与金额

首页校园卡区 → 充值。打开页面只读加载学校账号、本人卡与规则；2.0.6 集成后正常流程是选择/输入金额 → 点击带金额的微信充值按钮 → 自动单次下单并唤起微信；按钮即本次下单授权，不再叠加金额确认。下单前先检查微信可用性，不可用时不创建订单。返回自动查单一次，保留刷新状态。失败/超时/未知不自动重下单；异常恢复入口要求先核对学校与微信记录。

输入范围按学校充值页面的配置顺序读取：`frontInfo.getFrontConfig.rechargeMaxAmount` 是可选初始上限，按已校验十进制向下取整数；`singleFeeitem.feeitem.retain_money` 是最小金额、`maxmoney` 是后续覆盖的最大金额，单位均为元。有效 `maxmoney` 优先于前端初始上限；缺失、`null`、空字符串或数值零不覆盖初始上限。字符串零按学校页面的 truthy 行为先覆盖为零。最终缺省或零值采用学校共享金额组件明确使用的 **0.01 / 9999 元 UI 输入默认值**；这不是学校服务端承诺的充值额度。`layout` 是逗号分隔预设；`flag` 第八字符 `1` 仅预设、`2` 仅整数、`0` 普通手输。

以上缺省顺序来自[学校公开充值组件](https://mb-xyk.upc.edu.cn/campus-card/js/chunk-1186b329.e153ac09.js)（2026-11-15 核对 SHA-256：`5ba099124706161ec8e8962cab4a150b266063220b9201403aef0a6986873f6b`）。本次已授权的真实只读准备复现确认 `feeitem` 为对象，两个上下限均为 `null`，可选前端上限缺省；旧解析器因此报 `P-FEE-CONFIG`。原生解析现在只对上述有来源的缺省形状应用默认，负值、布尔、对象、数组、非有限数或畸形十进制仍拒绝；配置可有不改变分精度的额外尾零，用户输入仍严格最多两位小数。不将元乘 100 后提交，也不沿用实验版 20 元上限。提交前再只读获取规则，若上限变化立即更新展示并重新校验，超限不会创建订单。服务端限制仍由学校最终校验；真实下单、微信付款与到账尚未验证。

## 协议及账号边界

- `core:upc-auth` 继续负责凭据；充值只复用 `CardHistoryClient` 模块内部的受控会话租约，页面得不到密码或 token。租约绑定账号/凭据代次/会话及业务 epoch，过期响应不能发布或清理较新的会话。
- 配置：`/berserker-app/frontInfo` → 本人卡 `/berserker-app/ykt/tsm/queryCard?scene=recharge` → `/charge/feeitem/singleFeeitem`；卡片学号须匹配、挂失或冻结停止。
- 用户点击充值按钮后单次 POST `/charge/order/thirdOrder`，只接受学校同源 HTTPS（已观察 HTTP 回调升级 HTTPS）的明确跳转；再读 `/charge/pay/getpayinfo`，选择学校动态微信方式，单次 POST `/blade-pay/pay`。
- 已观察返回为 `paysubmit` 单表单，提交到 `https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb`，原样保留 query，学校来源头，禁止跨域发送校园认证头或未知 hidden 字段。只读取明确的 `weixin://wap/pay` 字面量并显式限定微信包，不执行脚本、无浏览器回退。
- 学校前端签名模板来自固定公开充值 bundle，仅单次运行期严格提取已知字面量，不将材料写入源码/测试/APK/磁盘/日志。前端变化或页面必须执行脚本时停止。
- 加密 pending 在发起 POST 前可靠保存，进程重建恢复后禁止再次下单；网络超时保留未知状态。支付事件只消费一次，账号失效、清理/完成订单后不能再消费旧事件。
- 集成审查补充：学校携带会话的固定 HTTPS 请求即使返回 HTTP 200，只要 JSON 业务码是 401/403，也结束当前会话租约；不自动重放当次下单/付款请求，后续用户加载或查单才重新认证。无会话的学校静态文件和微信响应不以其业务码清理学校会话。
- pending 写入显式调用文件描述符 `sync()`，再完成 AtomicFile 提交并用同一 AtomicFile 读回、解密、比较完整记录（账号/学号/代次、attemptId、金额、orderId、payId）后才允许下一步 POST。失败保留旧标记，不能因存在旧文件或 attemptId 相同就放行。依据 [AOSP AtomicFile](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/util/AtomicFile.java#172) 的 `finishWrite` 会仅日志处理部分提交错误；显式同步错误与精确读回用于普通应用进程死亡恢复，不承诺断电或硬件故障下的绝对持久化。
- 查 `/charge/order/personal_data`，要求业务 code 200 且订单号匹配。状态 1 为支付成功；0/2 显示失败/超时但保留核对标记，其他为未知。同账号改密只在新凭据核验后只读核对原订单，不恢复旧 token 或旧付款 URI。

## PR 作者原始验证记录（本轮未直接采纳）

以下为 PR 作者随提交提供的检查记录，尚不能代表本轮集成验收：`:feature:card:testDebugUnitTest` 123 项通过（含动态额度、单次下单、异常恢复、凭据轮换和两项 Compose 交互回归）；`:core:upc-auth:testDebugUnitTest` 161 项通过；`:app:testDebugUnitTest` 103 项中 102 项通过、1 项既有真实更新联网测试跳过。`:app:assembleDebug` 与 `checkModuleBoundaries` 通过。`:feature:card:lintDebug` 0 errors、6 warnings，均位于既有登录 WebView/组件资源（SetJavaScriptEnabled、Overdraw、UselessParent、SmallSp、RtlSymmetry、RtlEnabled）。

所有回放为合成数据，不调用真实订单/支付接口；没有为本次改动发起真实付款。公开学校配置/模板的先前只读调研不等于新的真实订单验收。

作者原分支未做移植版真机/UI 外观验收。本轮在维护者工程按 2.0.6 / 27 集成，不变更长期签名或公开发布配置；当前集成、独立验证与本地新候选状态见 [2.0.6 充值集成记录](../releases/2.0.6-card-recharge.md)。
