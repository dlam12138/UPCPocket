# M2 校园卡余额强关联执行记录

本目录记录 M2 的实际实现、迁移协议、验证结果与未完成项。方案依据见
[统一账号与课表工程方案 v0.1](../统一账号与课表工程方案-v0.1.md) 第 6 节；原始总体计划不改写。
M2 只收束余额绑定交互与数据归属，页面整体布局和视觉仍留 M5；课表、图书馆统一认证和
石光 e 站通后置。

## 状态

| 项目 | 状态 |
| --- | --- |
| 当前统一账号的受控本人学号读取面 | 完成；只给 accountId / 学号 / 代次，不带密码或通用解密 |
| 余额页面取消独立可编辑学号 | 完成；展示脱敏绑定、账号设置入口、查询/刷新、仅清余额缓存 |
| 页面、2×2 RemoteViews 组件、WorkManager Worker 共用绑定服务 | 完成；目标不接受 Intent/路由/任务参数 |
| 无账号 / LOADING / 清理中 / 密文不可用 / 学号无效时不联网、不显示旧私人值 | 完成；JVM 与 Compose 测试 |
| 已保存未验证按学号查询，余额成功不等于 CAS/密码正确 | 完成；页面与文档明确标注 |
| 余额与过渡余额分别展示；失败保留同账号历史值 + 绝对时间 | 完成；页面失败时明确标注历史缓存 |
| 组件无历史快照也可点击刷新 | 完成；绑定可用即允许，刷新按钮不再等旧快照 |
| 组件最终发布使用版本校验与串行门闩 | 完成；绑定/账号代次/业务代次/快照版本，读后复核，旧读取不能越过新空态 |
| 换号/删号立即撤下旧余额；同号改密保留历史但拒绝旧请求 | 完成；JVM 并发测试 |
| 查询捕获绑定 + 账号代次 + 业务代次，返回/落盘/刷新前校验 | 完成；取消协程不作为唯一保护 |
| 启动 LOADING 时 Receiver/Worker 不用旧快照回填目标 | 完成；缓存读取也要求当前绑定 |
| 旧 Worker 执行时读取当前绑定，不复活旧号 | 完成代码与 JVM 测试；设备端旧队列注入未单独执行 |
| v1 快照迁移、FIRST_SAVE 隔离、marker 幂等/中断恢复 | 完成；JVM 测试 |
| cardStudentId 草稿仅提示、不自动建号，绑定后删除 | 完成；账号页提示与宿主/插件清理 |
| 清理失败在重启后仍隐藏，并可重试 | 完成；持久清理门闩与 JVM/Compose 测试 |
| 仅清余额缓存不等于删除账号 | 完成；JVM 测试 |
| 模块边界、Debug APK、lint、模拟器升级 | 见 [验收记录](验收记录.md) |
| 真实校园余额接口 | 未执行；本轮全部使用虚构学号与替身网络 |

## 模块与唯一目标

```text
:core:account-api
  SchoolAccountId / StudentIdFormat / SchoolStudentIdentity
  SchoolAccountStudentIdentityReader（只读 state + studentIdentity）
        ↓
:feature:card
  CardBalanceBinding（accountId + studentId + credentialGeneration）
  CardBalanceService（页面/组件/Worker 唯一业务入口）
  DirectCardBalanceRepository（唯一查询目标 + 并发门闩）
  DirectCardBalanceStore（v2 / v1 / migration marker / clear marker）
  DirectCardBalanceSection（Compose）
  CardWidgetRenderer（RemoteViews）
        ↓
:app
  AppGraph 装配、账号失效器、WidgetRefreshWorker、WidgetUpdates
```

余额插件只新增对 `:core:account-api` 的依赖，不依赖 `:core:account` 实现，也不读取密码。
`checkModuleBoundaries` 仍要求 core 不依赖 feature/app、feature 实现之间不互依赖。

受控读取面只有在以下条件同时成立时才给出 `SchoolStudentIdentity`：

1. 账号状态为 `SAVED_UNVERIFIED`，且代次大于 0；
2. 登录名规范化后**整串**为 8–16 位 ASCII 数字；
3. 身份代次与当前账号代次一致。

不截取登录名中的数字，不把别名、邮箱或脱敏文本当学号。8–16 位限制沿用原余额协议，
本轮没有来源依据支持放宽。`studentIdentity` 与 `state` 都不含密码、`CredentialPayload`
或任意解密入口；`SchoolAccountState` 只保留脱敏账号/学号、状态、代次与固定错误码。

## 查询与并发

`DirectCardBalanceRepository.refreshCurrent()` 不接受学号参数，流程为：

1. 从当前受控身份面捕获 `(accountId, studentId, credentialGeneration)`；
2. 先恢复未完成的缓存清理并做 v1 迁移；清理未完成时拒绝联网；
3. 在串行刷新锁内重新核对绑定，捕获业务代次；
4. 只把绑定中的学号交给余额协议（表单仍为 `sno=...&xxbh=synjones&idtype=sno&id=...`，
   不带密码、Cookie 或账号原文）；
5. 响应解析后再次核对当前绑定与业务代次；
6. 在文件锁内第三次核对，才写 v2 并发布内存快照；
7. 页面/组件刷新只使用当前绑定重新计算，迟到的旧结果无法复活。

业务代次在“仅清余额缓存”和账号失效清理时递增。同账号更新密码不删除历史快照，但凭据代次
变化会使在途请求失效；历史值以旧代次保存，页面和组件都明确显示为历史值。

账号失效器在账号锁内被调用。`clearForAccountChange()` 只清余额缓存、迁移文件与旧明文草稿，
不反向读取账号 `state`/`studentIdentity`；测试用计数读取面验证调用期间为 0 次账号读取，
避免与账号锁形成环形等待。

## 存储与迁移

目录：`noBackupFilesDir/`（应用私有，不参与系统备份/迁移）。

| 文件 | 内容 | 展示/迁移规则 |
| --- | --- | --- |
| `card-balance-v2.json` | 格式/版本 + accountId + 学号 + 凭据代次 + 余额/过渡余额（分）+ 绝对时间 | 只有 accountId 与学号都属于当前绑定才展示；同号旧代次可作为历史值 |
| `card-balance-v1.json` | 旧版无归属快照：学号、两项金额、时间 | 未绑定不展示；仅当前绑定学号与旧快照整串一致时迁移，保留原时间；异号/损坏删除 |
| `card-balance-migration-v2.json` | accountId + 学号 + 结果（MIGRATED/ALREADY_OWNED/NO_LEGACY/MISMATCH/CORRUPT） | 保证幂等；其他账号的 marker 不继承旧 v1；marker 已提交但 v2 丢失时，同号 v1 仍可重建 |
| `card-balance-clear-v2.json` | 用户清缓存的进行中门闩 | 先写门闩再删数据；未全部删成功时保留，重启继续隐藏并重试；成功后删除 |

迁移顺序：先恢复 pending clear，再隔离不属于当前绑定的孤儿 v2，然后读取当前 v2；没有
当前 v2 时才判断 v1 同号/异号/损坏。所有迁移只读写本机文件，不联网；失败不会自动查询。

`cardStudentId` 旧草稿只作为“更多 → 学校账号”的脱敏提示，不自动创建账号、不推断密码或
本人身份。账号绑定出有效学号后，宿主与插件都会删除该明文草稿；若插件未激活，宿主仍会清理。

FIRST_SAVE 没有“上一账号”可清理，但仍会按上述规则核对旧 v1/v2：异号或未知归属的 v2 先删除，
同号 v1 才迁移，因此不会把首存前的旧私人余额带进新账号。

## 页面、组件与 Worker

- 余额页读取 `CardBalanceService.bindingStatus` 与 `snapshot`。所有非 READY 状态都不显示
  快照、不提供查询按钮；LOADING 显示正在恢复，清理中显示已隐藏旧值，密文不可用/未绑定/
  学号无效分别给出明确说明。失败时保留同账号历史值并显示绝对时间，标注“历史缓存”。
- 2×2 组件展示脱敏学号/已绑定状态、主余额与绝对时间；过渡余额在页面完整展示，2×2 只
  突出主余额（沿用现有组件信息密度，不在 M2 重排视觉）。已绑定但无快照时刷新按钮仍可用；
  无绑定/清理中点击只打开应用。
- `WidgetRefreshWorker` 输入只有组件类型和可选绑定指纹 `accountId:credentialGeneration`，
  不含学号、密码、Cookie 或 URL。执行时读取当前绑定并再次核对；指纹不匹配、绑定消失或
  缓存清理未完成时结束，不发布结果。旧版本已排队任务没有指纹时仍按当前绑定执行。
- 同类型唯一任务名 `widget-refresh-card`、Provider/Worker FQCN、刷新 action 和组件实例保持
  不变；任务策略使用 `REPLACE`，避免换号后旧任务仍在取消/收尾时新绑定刷新被 `KEEP` 丢弃。
  广播层的 `tryBegin` 仍合并连续点击。

### 组件发布时序

仅“读缓存后检查一次”不足以阻止旧读取覆盖新空态：旧 `updateKind` 可以在换号/删除/清缓存
之后才完成 `cached()`，再用旧 `CardBindingStatus(A)` 渲染并写回桌面。M2 收尾增加
`CardWidgetPublisher` 作为唯一发布门闩：

- 每次发布先取得递增 ticket，**在锁外**读取状态并允许挂起/读文件；旧读取可以慢慢完成。
- 真正 `updateAppWidget` 前在短 `synchronized` 临界区内同时比对 ticket 与完整
  `CardWidgetRevision`：可用状态 + 绑定 token（内部 accountId:凭据代次）+ 业务代次 +
  内存快照版本。任一项变化即丢弃，不渲染。
- 新状态变化会取得更大的 ticket；若旧发布已经先写入，新发布在锁之后覆盖，最终桌面只保留
  最新一次。旧 ticket 在锁外返回时不会越过已经发布的新空态。
- `CardBalanceService.readWidgetState` / `readWidgetStateBlocking` 读取前后复核
  status/businessEpoch/snapshotRevision；不稳定时重读，仍不稳定则退回“当前绑定 + 空快照”，
  不显示旧值。`widgetStateNow()` 也复用该稳定读取。
- `DirectCardBalanceRepository.cached()` / `cachedBlocking()` 在读取结束后复核绑定、业务代次
  和快照版本；迟到读取返回 null，不把旧快照回填进内存。`refreshCurrent()` 落盘后也再次复核，
  旧请求不能把结果作为新值发布。
- Provider 的 `onUpdate`/resize（`updateKindNow`）、`requestCard` 的 suspend 刷新
  （`updateKind`）、Worker 的 finally 重绘（`updateKind`）全部走同一发布器；校园卡分支不存在
  直接 `updateAppWidget` 的旁路。电量组件保持原路径。
- 读缓存与渲染不在锁内；Provider 同步路径只做有界的 8 KB 缓存读取，不等待协程 Mutex 或
  网络。锁定区只做版本比较与一次 sink 写入。

## 隐私与网络边界

- 余额接口是现有 HTTP 接口，学号与金额在传输中不受 HTTPS 保护；迁移/绑定不改变这一事实。
- 余额接口不接收密码、Cookie 或账号原文；保存账号成功不等于 CAS 认证成功，余额成功也不
  反证密码正确。
- 余额快照未单独加密，依赖 Android 应用沙箱且不进入备份；不保存服务器原始响应、姓名或
  卡号。错误信息不回显服务器正文、学号或真实账号。
- 删除学校账号会清余额缓存、旧草稿与组件内容；“仅清除余额缓存”不删除账号、图书馆会话、
  专注、电量或主题数据。

## 未完成与限制

- 未向真实校园余额接口发起验证；本轮使用虚构学号、`FakeAccountIdentity` 和内存 HTTP
  fixture。真实接口的可用性、HTTP 行为与学校策略仍需在授权范围内单独确认。
- 未在设备上注入“WorkManager 旧任务在换号后执行”或“清理中杀进程”的故障；对应语义由
  JVM 并发/失败测试覆盖。组件发布竞态由可注入 publisher/read 的确定性测试覆盖，没有在设备
  上强行制造线程交错。
- Provider 的同步 onUpdate/resize 路径只读取有界的 8 KB 缓存文件；未迁移旧文件、未联网、
  未等待协程 Mutex，但仍是主线程上的小文件读取，沿用原有实现边界。
- 未执行 release/R8、真机或其他桌面。M2 不改变 2×2 布局与视觉，M5 再统一处理。
- M3 课表、M4 图书馆统一认证和石光 e 站通不在本阶段。
