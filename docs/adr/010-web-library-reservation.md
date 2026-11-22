# ADR 010 · Web 图书馆预约与校方会话边界

- 记录于 2026-11-21。前端契约仍适用，本机接入决策由 [ADR 011](011-local-library-companion.md)更新。
- 延续 [ADR 007](007-ios-pwa-local-data-and-school-links.md) 的官方网页交接，以及 [ADR 008](008-web-public-campus-data.md) 的公开与需授权数据边界。

## 背景

上游 Android 的唐岛湾 libseat 登录先由 UPC CAS 签发 service ticket，libspace 回调以此取得 App Token，随后兑换 Seat Token 并核验本人。座位、起止时间与占用情况来自学校。确认预约时预检验证码和会话，上游调用 `POST /rest/v2/freeBook` 后，通过 `GET /rest/v2/user/reservations` 核对新记录。古镇口东区和求是分别使用超星 seat 和 seatengine。 [M4 认证记录](../m4/README.md) · [超星两馆区记录](../releases/2.0.4-chaoxing-library-parity.md) · [上游唐岛湾 Repository](https://github.com/dlam12138/UPCPocket/blob/bf28477354eaec182e0b507699f82ea4f22fa771/feature/library/src/main/kotlin/com/upcpocket/app/data/library/LibrarySeatRepository.kt#L265-L370)

Android 使用本机认证适配器和受限 WebView。PWA 的独立来源受浏览器跨域和 Cookie 隔离约束。学校 Web 授权需要为 Pocket 部署来源登记回调，确认预约权限和会话规则。当前仓库只具备官方入口和公开查询服务。 [Fetch 跨域协议](https://fetch.spec.whatwg.org/#cors-protocol) · [WebKit Cookie 说明](https://webkit.org/blog/10218/full-third-party-cookie-blocking-and-more/)

## 决策

- 为三个馆区保留官方页面入口，登录、预约和记录核对可由官方页面完成。唐岛湾站内以查询房间、座位和时段、明确确认、一次提交、读取记录为首期范围。
- 站内前端使用[同源预约契约](../../API.md#web-图书馆预约)。部署方只在接入校方授权回调和本人会话后开启该能力。当前的本机服务提供未接入状态。
- 站内的学校认证在校方页面完成。授权服务需要校验回调绑定、管理会话及退出，使用受限同源会话 Cookie，并隔离账号切换和过期产生的迟到结果。学校凭据与业务令牌由相应授权组件管理。浏览器的业务数据只用于当前页面，个人响应使用 `no-store`。
- 校方授权服务负责权威预检和持久防重。已发送的未知结果保持待核对，重复打开页面仍须查询待核对状态。出现验证码且确认尚未发起预约时，引导用户在官方页面处理。只以与已提交座位及完整时段一致的新学校预约记录确认成功。

## 取舍与影响

继续只提供官方页面可即时完成预约。增加同源前端契约让交互及结果状态可在本地验证，真实站内预约仍取决于校方授权的连接实现。将 Android 的登录表单处理直接搬到 PWA 会改变密码、浏览器来源、票据和会话的处理边界，当前 PWA 采用官方登录与授权回调。

本轮范围限于 Web。古镇口的两套站内协议、取消、签到、研讨间和座位图待独立决定。验证记录区分合成会话、设备入口以及真实校园预约。 [iOS 网页版](../web.md)
