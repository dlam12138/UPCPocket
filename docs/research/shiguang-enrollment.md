# 石光活动筛选与报名接口研究

调查日期：2026-11-13（上海时间）。范围仅为活动筛选与报名；本次没有登录、使用受保护会话或向任何写端点发送请求。下文“参考实现”是第三方源码的行为，不等于学校当前协议保证，也不等于本轮完成真实业务验证。

## 决策摘要

- 当前 Android 活动列表通过服务器分页和关键词查询；尚未实现状态筛选。参考实现还有类型参数，但没有发现“进行中 / 可报名 / 历史”的服务器查询参数。不能把已加载一页的本地筛选结果称为全站结果，也不能根据空页断言全站没有活动。
- 如果暂时需要筛选，可明确标注“仅筛选当前页”，只对实际取得且可靠解析的时间字段判断，时间缺失或格式不明归为未知。列表只有报名截止或报名时间和活动时间等展示文字，不能证明可报名资格；进行中、可报名、已结束不是互斥的同一概念，不能仅以截止时间断言可报名。完整全站筛选须先找到并验证官方服务端参数，或者明确分页扫描的范围、上限、进度、失败和结果不完整；避免无限抓取与为每项请求详情造成负载。
- 不建议仅据这份研究启用原生报名写入。具体请求和返回码来自第三方固定源码；当前网页、JS、CSRF、服务端处理、邀请码社会实践理由及真实业务结果均未在本轮验证。学校实际授权是前提，本地研究不构成授权。

## 来源与本次只读验证

1. 项目入口 [官方入口](../upc/官方入口.md) 和 [机器可读入口](../upc/official-entrypoints.json) 将 `https://dekt.upc.edu.cn` 列为石光入口。项目记录 2026-11-13 本人只读活动首页、详情等成功，但真实搜索/完整分页尚未验；那是已有记录，不是本轮新验证。
2. 学校学生资助管理中心 [2024-12-26 通知](https://csun.upc.edu.cn/_t1756/2024/1226/c4442a449291/pagem.htm) 给出 `http://dekt.upc.edu.cn/web/index.html`，并描述该项社会实践可按类型筛选、普通加入或邀请码报名、填写申请理由及队长审核。此为特定活动说明，不能推广为全部活动规则或证明参数名称。
3. 学校新闻网 [2021-09-17 报道](https://news.upc.edu.cn/info/1432/106047.htm) 描述石光使用统一身份认证并支持活动报名；历史描述不证明当前接口。
4. 参考 [UPC-MCP 固定提交 shiguang.py](https://github.com/dlam12138/UPC-MCP/blob/948f5f21ad7a12fd94b53403a07ac867209f7700/src/upc_mcp/shiguang.py) 第 22–38、478–638、791–931、958–1016 行及 [shiguang_service.py](https://github.com/dlam12138/UPC-MCP/blob/948f5f21ad7a12fd94b53403a07ac867209f7700/src/upc_mcp/shiguang_service.py) 第 528–648 行；本次从本机 Git 对象直接读取指定提交。项目已有 [许可和移植说明](../third-party/UPC-MCP.md)。
5. 2026-11-13 使用 web 检索学校公开网页，公开通知可读；web 工具打开石光根页和 `/web/index.html` 均不可访问。本机对 `https://dekt.upc.edu.cn/web/index.html` 发出无凭据 HEAD，收到 HTTP 403；因此未取得当前石光页面或 JS，不能推断服务是否整体不可用，也未继续访问受保护部分。未保存网页、个人数据或凭据。
6. 静态检查工作区 `feature/shiguang` 的 `ShiguangHttp.kt`、`ShiguangProtocol.kt`。没有构建或更改生产代码、测试。

## 列表参数和状态证据

参考实现 `activities()` 使用 GET `/actvMng/actvMngFront!jumpActvCampListPageForWeb.action`，参数 `page`、`oper=1`，可选 `searchWord`、`actvModel.activityOnlineType`（本地允许 `001`–`004`）和 `actvModel.activityBaseType`（本地允许 `001`–`005`）。这些取值范围是参考客户端校验，不证明各代码的含义、当前服务端实际筛选效果或组合语义；不可凭名字将 onlineType 编成报名状态。参考实现没有状态参数。Android 当前只发送 `page`、`oper=1` 和可选 `searchWord`，结果以页为单位解析；关键词完整真实验证尚未完成。

参考详情解析读取隐藏项 `activityId`、`enrollStartTime`、`enrollEndTime`、原样拼写的 `activityStratTime`、`activityEndTime` 与 `onlineType` 或 `activityOnlineType`，并检查 `actvJoin` / `actvJoinByCode` 节点。它按本地当前时间依次判断活动已结束、进行中、报名未开始、已截止、开放；时间缺失或无入口则可能为 unavailable。该算法是参考客户端推断，尤其活动已开始时优先判进行中，不能证明学校不允许活动开始后报名。未发现稳定的本人已报名字段，`already_registered` 为未知；开放时间与按钮存在也不证明名额、资格或最终报名成功。应以学校实际时区和字段格式验证后再做状态展示。

## 报名请求：仅参考源码行为

共同基址 `https://dekt.upc.edu.cn`；已认证的客户端发送 POST 表单数据（`httpx` 的 `data=`），接受 JSON，设置 `X-Requested-With: XMLHttpRequest` 和对应详情页 Referer，不跟随重定向。参考代码使用会话 Cookie，但没有在报名表单或显式请求头中加入 CSRF token；这只说明源码未显式添加，不能证明服务端不要求 CSRF、无其他 Cookie 或页面动态值。本轮无法查看当前页面 JS 验证。

| 分支 | 路径（均为 POST） | 表单字段与参考客户端限制 |
| --- | --- | --- |
| 普通活动 | `/actvMngAjax/actvMngFront!goEnrollInForWeb.action` | `actvModel.activityId`，数字 1–20 位 |
| 普通社会实践 | `/actvMngAjax/actvMngFront!goEnrollInSocialForWeb.action` | 上述 ID 与 `actvModel.enrollVerifyDesc`；理由 1–500 字符 |
| 邀请码 | `/actvMngAjax/actvMngFront!goEnrollInByCodeForWeb.action` | 上述 ID 与 `inputInviteCode`；邀请码 1–200 字符；参考实现未发送理由 |

详情 Referer 按活动类型分别使用 `/actvMng/actvMngFront!jumpActvCampViewPageForWeb.action` 或 `/actvMng/actvMngFront!jumpSocialPracticeViewPageForWeb.action`，附活动 ID。上述长度均是第三方本地校验，不是已证实服务端限制。邀请码分支适用社会实践但不发送理由，与前述学校通知对该特定活动的描述存在未解差异；不应假设邀请码报名无需理由或上述表单已经完整。

## 返回语义与防重复

以下是参考源码对 JSON `oper` 的映射，而非本次观测到的响应：`success` 为提交后待审核，`accepted` 为已报名；`SincerityError` 诚信值不足，`moreNumAll` 繁忙，`moreNum` 名额满，`notRequest` 资格不足，`timeRepeat` 时间冲突，`moreLockFail` 并发争用，`beyondLimit` 同时报名限制，`beyondLimit1` 宣讲类限制。源码将非空 `errorInfo` 视为需用户处理；具体含义、阈值和码值适用范围均待核验。HTTP 200 自身不是报名成功。

参考服务层在确认流程中重新读取详情、检查报名窗口及入口、会话绑定和代次；以会话绑定、活动 ID、类型形成持久回执，写前记录 in-flight，成功短期抑制重复，未知或进行中阻止再次发送。它对超时、断连、5xx、未知码、非预期重定向、不可解析或异常响应保守处理，提示结果未知、先核对本人参与记录且不自动重试。参考实现把某些认证/403 响应视为未提交，但本轮没有服务器语义证据；未来实现对发送后无法证明未执行的情况应保守视为未知，避免自动重试或自动恢复后重发。持久回执只能防止本机重复，不证明跨设备幂等，也不能替代官方本人记录。

## 后续实现边界与待验证

在已取得实际授权之后，先只读核对官方当前页面与 JS、分类/状态参数、字段和时区，确认分页边界、搜索组合及缺字段行为；不得用当前页冒充全站。报名设计需先展示活动、报名方式、时间及必要输入，要求本人明确确认；绑定已核验账号与代次，确认前重新读取详情，服务端才是名额和资格的最终裁决。设计一次发送、并发互斥、进程死亡后的持久防重和未知结果查询；未经证实不要猜测已报名、成功或失败。

仍待官方协议或获授权环境验证：所有请求路径和字段的现行性、CSRF 与会话要求、各类活动及邀请码的理由规则、返回码与审核状态、资格和时钟边界、跨设备重复处理、本人参与列表能否可靠核对未知结果。需要真实报名验证时应另行获得针对该次实际报名的明确确认，并分别记录发送和业务结果；本次未做此验证。
