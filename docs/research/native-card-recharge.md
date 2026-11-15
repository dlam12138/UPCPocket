# 原生校园卡微信充值

用户已在独立实验 App 0.9.0 的手机上验证严格原生充值链成功。本改动将同一协议移植到当前校园卡插件，取消实验版单次 1–20 元限制及手动准备/单独再次唤起步骤。朋友项目移植后的真机付款、返回与到账仍需复验，不将原型验收扩大为本包验收。

## 页面与金额

首页校园卡区 → 充值。打开页面只读加载学校账号、本人卡与规则；正常流程是选择/输入金额 → 微信充值 → 一次金额确认 → 自动单次下单并唤起微信。返回自动查单一次，保留刷新状态。失败/超时/未知不自动重下单；异常恢复入口要求先核对学校与微信记录。

额度来源是学校 `singleFeeitem.feeitem`：`retain_money` 是前端使用的最小金额、`maxmoney` 是最大金额，单位均为元；`layout` 是逗号分隔预设；`flag` 第八字符 `1` 仅预设、`2` 仅整数、`0` 普通手输。使用十进制金额，不将元错误乘 100 后提交，不使用 20/200/9999 等备用上限。提交前再只读获取规则，若上限变化立即更新展示并重新校验，超限不会创建订单。其他服务端限制仍由学校最终校验。

## 协议及账号边界

- `core:upc-auth` 继续负责凭据；充值只复用 `CardHistoryClient` 模块内部的受控会话租约，页面得不到密码或 token。租约绑定账号/凭据代次/会话及业务 epoch，过期响应不能发布或清理较新的会话。
- 配置：`/berserker-app/frontInfo` → 本人卡 `/berserker-app/ykt/tsm/queryCard?scene=recharge` → `/charge/feeitem/singleFeeitem`；卡片学号须匹配、挂失或冻结停止。
- 用户确认后单次 POST `/charge/order/thirdOrder`，只接受学校同源 HTTPS（已观察 HTTP 回调升级 HTTPS）的明确跳转；再读 `/charge/pay/getpayinfo`，选择学校动态微信方式，单次 POST `/blade-pay/pay`。
- 已观察返回为 `paysubmit` 单表单，提交到 `https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb`，原样保留 query，学校来源头，禁止跨域发送校园认证头或未知 hidden 字段。只读取明确的 `weixin://wap/pay` 字面量并显式限定微信包，不执行脚本、无浏览器回退。
- 学校前端签名模板来自固定公开充值 bundle，仅单次运行期严格提取已知字面量，不将材料写入源码/测试/APK/磁盘/日志。前端变化或页面必须执行脚本时停止。
- 加密 pending 在发起 POST 前可靠保存，进程重建恢复后禁止再次下单；网络超时保留未知状态。支付事件只消费一次，账号失效、清理/完成订单后不能再消费旧事件。
- 查 `/charge/order/personal_data`，要求业务 code 200 且订单号匹配。状态 1 为支付成功；0/2 显示失败/超时但保留核对标记，其他为未知。同账号改密只在新凭据核验后只读核对原订单，不恢复旧 token 或旧付款 URI。

## 验证

本次实际检查：`:feature:card:testDebugUnitTest` 123 项通过（含动态额度、单次下单、异常恢复、凭据轮换和两项 Compose 交互回归）；`:core:upc-auth:testDebugUnitTest` 161 项通过；`:app:testDebugUnitTest` 103 项中 102 项通过、1 项既有真实更新联网测试跳过。`:app:assembleDebug` 与 `checkModuleBoundaries` 通过。`:feature:card:lintDebug` 0 errors、6 warnings，均位于既有登录 WebView/组件资源（SetJavaScriptEnabled、Overdraw、UselessParent、SmallSp、RtlSymmetry、RtlEnabled）。

所有回放为合成数据，不调用真实订单/支付接口；没有为本次改动发起真实付款。公开学校配置/模板的先前只读调研不等于新的真实订单验收。

本地尚无 Android 设备，未做移植版真机/UI 外观验收。Release 需要维护者的长期签名，本分支不改变版本、签名或发布配置，不发布 APK/Release。
