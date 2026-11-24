# ADR 002：本机账号、代次与独立业务会话

- 状态：Accepted（追溯既有实现）
- 记录日期：2026-11-16
- 依据：[M1](../m1/README.md)、[M2](../m2/README.md)、[M3](../m3/README.md)、[M4](../m4/README.md)及当前源码。

## 背景

多个学校系统需要同一用户凭据，但保存成功不是学校认证。改密、换号、删除、进程中断和网络迟到结果不能导致旧会话或旧账号数据重新出现；普通业务不应接触明文密码。

## 决策

统一账号属于共享 core，API 与实现分离。account-api 暴露脱敏状态、内部账号引用/受控本人学号、凭据代次与失效契约；明文仅由 upc-auth 认证适配 opt-in 短期使用并释放。密码用独立 Keystore AES-GCM 本机加密，不进系统备份。

凭据使用 pending/journal/state 和完成 tombstone 支持恢复。先持久化过渡/清理状态，再幂等失效业务，成功后提交新状态并收尾；已完成失效不因重启重放。清理失败保持真实阻断。

账号保存内部仍为 `SAVED_UNVERIFIED`，当前 UI 为“已保存”。CAS 与各业务会话独立建立；各业务按实际协议进行必要校验并在发布/写入前复核账号和凭据/业务代次。课表索引无学校本人字段，本机 reference 绑定不等于学校返回本人身份比对。

同号改密清会话且允许所属业务保留历史缓存/待查标记；换号/删号/不可读凭据清个人会话和无法确认归属的业务数据。本机待办/专注、个人隐藏、主题与寝室设置保留。宿主承担跨模块失效协调，包括插件缺失时的静态清理。

## 备选与理由

本次对照分析：每个 feature 保存账密会复制敏感数据与清理逻辑；任意调用方解密方便但扩大暴露面；一个通用学校 token 无法表示独立业务和本人校验；仅按请求开始时身份判断不能阻止迟到写回。窄 API、代次屏障与独立会话同时覆盖身份、存储和并发边界。

## 影响与代价

事务文件、清理协调与发布复核比简单偏好存储复杂；网络认证不能持有账号事务锁。业务需实现可重复的失效并分别记录会话/缓存状态。真实 CAS 验收必须使用已授权本人真实账密；Mock、离线和账号本机保存不能替代。

## 证据与验证

[SchoolAccount API](../../core/account-api/src/main/kotlin/com/upcpocket/app/account/api/SchoolAccount.kt)、[Repository](../../core/account/src/main/kotlin/com/upcpocket/app/account/SchoolAccountRepository.kt)、[CredentialAccess](../../core/account/src/main/kotlin/com/upcpocket/app/account/SchoolCredentialAccess.kt)、[宿主协调](../../app/src/main/java/com/upcpocket/app/core/AppGraph.kt)、[隐私](../PRIVACY.md)。变更按影响验证账号/认证/相关业务正常路径与必要隔离，不扩大为无关极端注入；当前历史验收范围以各记录为准。

## 2.1.1 成绩查询

用户点「教务同步」时，成绩认证适配使用当前本人凭据建立独立会话，校验学校 CAS 的成绩回调。成绩服务复核账号引用、凭据代次与业务代次后才写入或发布结果。学校返回的索引和明细目前以本次会话作为归属依据。打开页面只读本机缓存，同账号改密时保留缓存，换号和删除账号时由宿主清理。 [成绩认证适配](../../core/upc-auth/src/main/kotlin/com/upcpocket/app/upcauth/UpcGradesAuthenticator.kt) · [成绩服务](../../feature/grades/src/main/kotlin/com/upcpocket/app/grades/GradesService.kt)
