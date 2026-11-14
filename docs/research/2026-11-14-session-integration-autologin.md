# 两会话整合与自动登录排查（2026-11-14）

两个会话的成果已整合到当前项目工作树 `merge-autologin`。自动登录的排查结论仍是：**已确认何种客户端规则会退回普通网页登录，但尚未确定用户这次在哪一步中止，未验证恢复原生自动登录。**

用户补充 2.0.4 与 2.0.5 均有故障，因此不能将问题归因于本次 e站通接入或合并。正式发布仍为 2.0.4；本次源码为未发布的 2.0.5 / versionCode 26，`com.upcpocket.next`。原公开 APK、更新清单及签名未修改。

## 整合范围

来源为两个用户指定的会话：

| 会话 | 原工作位置 | 主要成果 |
| --- | --- | --- |
| [登录提示与验证码识别](https://lody.ai/dlam12138/sessions/ddadf2b4-936e-4181-b62b-2171e5fdaa93) | `fix-login-verification` 工作树 | 共享验证码识别、课表/流水的通用网页登录提示与回归 |
| [e站通只读接入](https://lody.ai/dlam12138/sessions/8cacd019-161a-47bc-9f93-1e6771a6d0fe) | 本地 `E:/UPCpocket` | e站通、绑定 SSO/内存会话、相关账号隔离与只读页面 |

两源共同基点为 `a38d3772ca9fe3b46cf6ea70e1e5fdf84eb3bcb1`，成果当时均未提交，普通分支 merge 不会带入这些文件。本次按源码路径联合导入：登录工作树 76 文件、本地开发目录 85 文件，联合 **96 文件**；交集 65 文件，其中 **24 文件**内容不同，逐一核对后保留双方有效改动。共同前置的石光、本人流水与受限网页登录接续一并保留。

关键决策：

- 宿主装配、Gradle、首页 e站通入口、学校 Cookie 清理与开发/来源文档保留 e站通完整实现。
- 通用 CAS 保留绑定 SSO、密码提交标记、内存 Cookie 来源与失效清理、主动二次验证识别，同时叠加登录分支的惰性 HTML 排除和共享验证码识别。
- 校园卡与课表保留通用“网页登录/请在学校页面继续登录”提示；脚本字段选择器、注释、明确隐藏的占位结构不单独构成验证码要求。活动或无法解释的验证码继续停止密码提交。
- 石光回放保留主动二次验证覆盖并加入隐藏占位、脚本选择器与注释。e站通普通密码表单回放中的备选短信表单明确隐藏，保留一次密码提交与活动挑战用例。

原始两处源码未改写；导入前后逐文件 SHA-256 一致。没有导入 `promo-video/`、构建目录、旧候选 APK、账号原始材料或其他被忽略的本机资料。当前目录本机构建配置仍为 Git ignored 文件；没有复制私钥或变更长期签名。逐路径清单、源哈希和 24 组差异保存在本机 `.local/integration-20261114/source-manifest.json`、`merged-manifest.json` 与 `pairs/`。

## 已确认的回退规则

通用 CAS 的 GET 或密码提交结果为 401/403，且正文可识别为普通密码表单、验证码或二次验证结构时，`challengeOrDenied` 返回 `INTERACTION_REQUIRED`。校园卡独立流程也会将精确学校 CAS 的这类可识别拒绝响应交给网页继续。**普通密码表单本身就能触发这条规则；返回交互要求不等于学校一定要求验证码。**

这解释“打开网页后只有账号密码”的一种代码路径，但不能证明用户此次一定命中该路径。此前用户主动提供的脱敏诊断只记录课表 `INTERACTION`，没有 CAS 请求阶段、HTTP 状态或停止子原因；没有对应流水事件。网页接续后曾成功读取课表，也不能倒推出原生失败的具体原因。

通用映射由历史提交 `0dee494ce15a6308a321756394fd69b982587a32` 引入，早于本次合并。本轮没有改 401/403 策略，没有通过重试密码或扩大主机、路径、TLS 权限来强行继续。

对应代码：

- `core/upc-auth/.../UpcCasLogin.kt`：GET/POST 分支、`challengeOrDenied`、`parseCasLoginForm`。
- `core/upc-auth/.../UpcCardLoginFlow.kt`：`recognizedDeniedLogin`、`follow` 与 `CardCasProtocol.body`。
- 先前诊断和提示修正范围见 [课表与流水的网页登录提示](2026-11-14-login-verification.md)。

## 本轮公开 GET 核对

researcher 只访问学校公开入口，未读取凭据、提交密码、执行本人业务查询或降低 TLS 校验。共 10 次本机 HTTP GET，另用网页工具核对公开 CAS 页面。原始 HTML、Cookie、动态 Location、execution、脚本正文只在内存处理，没有写入研究材料。

固定学校来源为 [CAS](https://cas.upc.edu.cn/cas/login)、[课表](https://app.upc.edu.cn/site/weekschedule/index)、[校园卡认证入口](https://mb-xyk.upc.edu.cn/berserker-auth/cas/redirect/wislearn?targetUrl=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fplat%2F%3Fname%3DloginTransit)和登录页引用的 [init.js](https://cas.upc.edu.cn/cas/cas/js/init.js)。课表实际请求使用源码中固定的 CAS service。

| 核对项 | 当次观察 | 可作出的判断 |
| --- | --- | --- |
| 公开状态 | 课表 CAS 为 200；校园卡为入口 302 → CAS 200 | 公开 GET 未复现 401/403，未测本人密码提交 |
| 普通表单 | 一个 POST 表单，未指定 action；字段为 username/password/submit/type/execution/_eventId | 结构仍符合现有普通密码解析；不能代表所有风控状态 |
| 验证码 | 无 captcha 输入；captchaParent 为 display:none；无 config.captcha 引用或赋值 | 当次没有活动验证码证据；脚本选择器不是活动挑战 |
| Cookie | CAS 设置 host-only、Path=/ 的会话 Cookie；未见父域 Domain | 当前精确 CAS 域过滤能接收所见 Cookie，没有建立误丢弃证据 |
| form action | 属性缺省 | 通用解析器回退到当前带 service 的页面 URL，未建立 service 丢失证据 |
| 脚本 | 静态扫描未发现额外隐藏 token、密码加密关键词或 submit 值写入 | 未发现新增步骤证据；没有执行完整浏览器脚本，不能排除动态差异 |

最后一组使用与应用相同的 Accept、Accept-Language 和 User-Agent，但 HTTP/TLS 实现是本机 urllib，不能等同手机上的 OkHttp、网络或风控状态。网页工具显示的 Validation Code 文本也不反映 CSS 可见性，不能单凭渲染文本判定要求验证码。

所见按钮字段存在一个**仅限校园卡、低置信度的待核对点**：英文页面 submit 为 `LOGIN`，中文请求所见值不同；校园卡 `CardCasProtocol.body` 会覆盖为 `LOGIN`，通用课表 CAS 则读取实际字段值。这项差异在基点已存在，没有本人 POST 证明服务器据此拒绝，且不能解释课表同时失败。本轮未据此修改请求。

完整脱敏观察、请求计数、方法限制及代码推论保存在本机 `.local/integration-20261114/researcher-public-check.json` 与同名 `.md`。没有把失败的外部参考页面当作 CAS 状态码官方语义依据。

## 合并后验证

以下只记本轮实际结果。来源文档中的历史通过数量和旧 APK 校验值不作为合并后验证。

- 联合 96 个源文件存在，官方入口 JSON 可解析；`git diff --check` 通过，未发现冲突标记。
- tester 首轮 Debug 构建/单测命令退出 1：校园卡 91 项中 `DirectCardBalanceSectionTest.failedCacheClearHidesOldValueAndLeavesRetryAvailable` 在第 194 行等待初始快照时触发 5 秒 `ComposeTimeoutException`，尚未执行缓存清理动作。
- 保存首轮 XML 后，tester 未改源码，单例复核 1/1 通过；整组复跑又出现同名等待超时。第二轮七模块合计 779 项：776 通过、2 项真实联网测试跳过、1 项失败、0 错误，其余六模块通过。Debug APK、AndroidTest APK 编译/打包和模块边界通过，但整条命令因校园卡测试退出 1。原始失败保留，不写成全部测试通过。
- 该测试、余额生产 UI、余额服务/仓库与合成账号在两源及共同基点内容相同（仅规范换行后比较）；历史开发记录也有同名首次快照超时。当前例点击查询前缺少邻近查询用例已有的“按钮可用”等待，而页面初始本机缓存恢复期间会禁用查询并拒绝过早调用。这里存在明确测试同步缺口；失败当刻的按钮状态未记录，暂不把静态推论写成动态根因已完全证实。

当前 Gradle 执行结束后，仅在该测试首次点击查询前加回已有的 `waitEnabled("card_query")`，使操作等待页面真正可操作；未改生产 guard、原断言或 5 秒上限。tester 随后重新执行校园卡整组，91/91 通过；最终 Debug/AndroidTest/模块边界命令退出 0。其余六模块未变，Gradle 为 UP-TO-DATE，沿用本轮已经执行通过的结果。主控独立复算 93 份最终 JUnit XML，共 **779 项：777 通过、2 跳过、0 失败、0 错误**。跳过项为 `ChaoxingLiveReadTest` 与 `RealUpdateNetworkTest`，没有算作通过。没有通过重复累计单例复核或历史执行扩大总数。

阶段性证据为 `.local/integration-20261114/tester-20261114-133633/` 的 `debug-build-tests.log`、`debug-result.json`、`card-initial-xml/`、`card-recheck-xml/`、`debug-final.log`、`pre-fix-unit-summary.json`，源码核对为 `card-timeout-source-check.json`。这项测试同步修正与本研究文档属于 96 个来源文件整合之外的附加改动；`docs/README.md` 另补本报告入口。

修正后的实际执行见同目录 `post-fix.log` 与 `post-fix-result.json`，主控复算见 `.local/integration-20261114/primary-final-unit-check.json`。独立 reviewer 对 24 个分歧文件、跨模块会话/失效装配及测试一行同步修正的复核无剩余 P1/P2；审查重点和未覆盖范围见本机 `.local/integration-20261114/reviewer-static.md`。本阶段没有修改生产登录行为或新增诊断框架。

## 最终签名候选与设备验证

本轮新交付为 [UPC-Pocket-2.0.5-merged-candidate.apk](../../artifacts/UPC-Pocket-2.0.5-merged-candidate.apk)，未公开发布，未覆盖旧候选或正式更新资产。

| 项目 | 实际值 |
| --- | --- |
| 应用与版本 | `com.upcpocket.next`，2.0.5 / versionCode 26 |
| 大小 | 5,448,760 字节 |
| APK SHA-256 | `b5ca354c38c1bf5b130687a7f28db28f4ee1abb3c3ad0b7eca387825216d949e` |
| 签名证书 SHA-256 | `c8e461698efe19ee6ad5fb635dd28eaf860596caea2b2756efa5ce6b2051a23c` |
| 签名 | 单签名，APK Signature Scheme v2 校验通过 |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` |

`:app:assembleRelease` 退出 0，用时 1 分 30 秒，实际执行 R8 与 resource shrink。实际 APK、三个 Release merged/packaged manifest、DEX 与 mapping 检查未发现本轮专用调试/预览/夹具入口；debuggable、testOnly 均为 false，两份独立 MIT 许可资源存在。实际打包网络资源默认禁止明文，只保留 `jhzf.upc.edu.cn`、`dfcz.upc.edu.cn` 两个既有精确主机例外；没有扩展子域、debug trust override 或自定义信任锚。

安装前核对现有包名、versionCode 与证书匹配后，以保留数据方式覆盖安装；没有卸载、清除数据或降级。回拉已安装 APK 为 5,448,760 字节，SHA-256 与交付文件一致。最终 Release 在隔离模拟器完成四个主 tab、e站通公开目录（当次 194 项）、无账号待处理/在办空态走查；主控实际查看首页和目录截图。

现有今日课表 RemoteViews 的普通进程死亡验证保留了第一次未成功结束进程的记录：立即 HOME 后 kill，PID 仍为 10090，此次没有计作冷启动成功。确认桌面稳定后再次结束后台进程，PID 10090 → 空；点击组件刷新，空 → 10443。再次后台结束，10443 → 空；点击整卡，空 → 10511 并进入 `MainActivity` 的课表连接页。主控实际查看最终刷新与导航截图；未使用 force-stop 来替代普通进程死亡。

构建、包审计与安装证据集中在 `.local/integration-20261114/tester-20261114-133633/`，完整入口为 `VERIFICATION.md`、机器可读 APK 信息为 `apk-metadata.json`、设备结果汇总为 `smoke-summary.json`。原始记录包括 `release-build.log`、`release-result.json`、`release-audit.json`、`release-signature.txt`、`release-network-audit.json`、`release-manifests-audit.json`、`install-preflight.json`、`install-result.json`、`installed-bytes.json`，以及 `widget-cold-process.json`、`widget-cold-process-recheck.json`、`widget-refresh-process.json`、`widget-navigation-cold-process.json`、`widget-navigation-process.json`。这些本机证据不作为校园接口成功结论。

本次没有本人账号真实登录、验证码、二次验证或受保护业务读取，也未验证自动登录已经恢复。R8 后带账号恢复、完整真实 WebView 接续、全部 Worker/三个组件与真机矩阵未在本轮覆盖；已验证的组件路径仅为无账号状态、普通进程死亡后的刷新及整卡导航。构建与合成测试通过不能替代这些实测。

## 下一次诊断应补什么

若继续本人账号验证，应在授权范围内仅采集固定枚举、数字和布尔值，以区分请求阶段与停止条件。建议字段如下，不在本轮新增诊断框架：

| 字段 | 固定范围示例 |
| --- | --- |
| 功能 | TIMETABLE、CARD |
| 阶段 | ENTRY_GET、CAS_GET、CAS_POST、CAS_RETURN、BUSINESS_CALLBACK |
| HTTP 状态 | 数字；无响应时用固定缺省值 |
| 已提交密码 | 0 或 1；一次操作仍最多提交一次 |
| 停止原因 | NORMAL_FORM_ON_DENIED、CAPTCHA_ACTIVE、SECOND_FACTOR_ACTIVE、FORM_UNRECOGNIZED、REDIRECT_REJECTED、HTTP_UPSTREAM、NETWORK、TLS、UNKNOWN |

诊断不记录账号、密码、Cookie、Token、URL/query、请求响应、HTML、业务内容、异常消息或自由文本；现有主动导出入口与固定包名前缀栈帧约束继续适用。需要保留 UNKNOWN，不能把未知拒绝重命名为验证码或密码错误，也不能自动反复提交密码。当前证据不足以指定一个已证实的真实故障根因，更不足以宣称已恢复自动登录。
