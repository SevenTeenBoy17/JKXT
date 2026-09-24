# JKXT 架构决策与当前候选差异核查（2026-09-24）

## 1. 核查范围与证据边界

- 候选工作区：`D:/VB/JKXT-release-g0-g1`。
- 本次核查读取的候选提交：`cfab40ec62979ea64ead58506d516399ce52378a`。
- 历史审计基线：`f206bcf6dff291d346cf5c3cf5a68bfce938935e`，分支 `codex/source-current-20260922`。
- 已完整阅读：`D:/download/02_后端与全栈验收矩阵.md`（84项初始场景）、`D:/download/03_架构与优化决策.md`（12节）。附件是核查材料，不扩大生产操作授权。
- 本文作者仅核查源码和已有报告；本轮没有启动数据库、修改业务代码、运行场景或执行生产操作。新增本文是唯一文件修改。
- 相对历史基线，当前提交内业务主代码的变化集中于 `DeterministicInvigilatorAllocator.java`；其余新增主要是发布证据工具、测试和文档。不能把架构建议直接写成已修复。
- 下文的 `backend/`、`src/`、`Dashboard.html`、`docker-compose.private.yml` 等源码引用，以候选工作区下的 `JKXT V2.0/` 为根；行号对应上述候选提交。

### 证据分类

| 标签 | 含义 |
|---|---|
| `STATIC_OBSERVED` | 本轮读取当前源码观察到的路径或风险，不等于已经复现运行失败 |
| `PRIOR_EXECUTED_PROOF` | 上一轮已记录真实命令、退出码和断言的证据；本轮没有重复运行 |
| `PARTIAL_IMPLEMENTATION` | 有真实实现，但不足以支持全部验收要求 |
| `NOT_RUN / BLOCKED` | 未执行，或此前被环境/授权阻断；不能补写PASS |

Supervisor 已按用户指示全局暂停。本轮不激活、不修复、不运行其入口，也不要求或附加其轮次报告。普通项目检查与发布边界继续有效。

## 2. 附件12节逐项对照

### 第一节：优先补齐真实闭环，不再另造系统

**结论：现有工程确有完整后端技术基础，保持模块化单体合理；这不是全站已验收的证据。**

- `backend/pom.xml:33–70` 已声明 Spring Web、Security、JPA、Redis、Actuator、PostgreSQL 与 Flyway。
- 不需要为了本轮整改重建演示系统、引入微服务或全量迁移 TypeScript。
- 后续以真实入口与数据不变量补齐闭环；缺配置服务要如实显示，不静默删功能。

状态：`STATIC_OBSERVED`。主要关联：FRONT-01、FRONT-06、RELEASE-06。

### 第二节：以功能证据链组织代码

**结论：静态追溯已部分建立，实际 Spring 注册与数据副作用仍需隔离运行证据。**

- `docs/release/inventory/README.md` 与 `summary.json` 明确现有188条为静态业务接口映射，而不是 Spring 实际注册清单。
- `backend/src/test/java/com/jkxt/release/RegisteredApiInventoryTest.java` 负责真实 `RequestMappingHandlerMapping` 采集，并检查隔离数据库、迁移和 Redis。
- 上一轮该探针被 `TestDatabaseIsolationGuard` 拒绝：缺少显式隔离数据库变量。不能以探针文件存在或静态数量代替实际运行。
- 需要继续补全后台执行、下载、进度流、最终文件内容和事务副作用；界面截图、HTTP 200 或函数名称都不够。

状态：`PARTIAL_IMPLEMENTATION`；运行时清单仍 `BLOCKED / NOT_RUN`。关联：ENV-05/06、PERM-06、FRONT-01/06、RELEASE-02。

### 第三节：新旧排班统一硬约束与独立验证

**结论：A/B反例的“误报无解”已修复；旧文“尚未在Java执行”已过时。此修复不等于已经实现完整回溯，也不证明新旧入口约束一致。**

- `backend/src/main/java/com/jkxt/examops/service/DeterministicInvigilatorAllocator.java:110–140` 从完整 `qualified` 集合判断固有不可用，避免将贪心已消耗的每日额度误作业务无解证明。
- `backend/src/test/java/com/jkxt/examops/service/DeterministicInvigilatorAllocatorTest.java:28–72` 包含 A/B 每日一场、B仅早场可用的独立见证；允许有效 B早/A晚方案，或诚实的 `DETERMINISTIC_UNKNOWN`，不允许误报无解。
- 已有上一轮证据：`evidence/counterexample-valid-red.command.json` 和 `evidence/counterexample-valid-green.command.json`；冻结版本回归见 `evidence/frozen-unit-regression.command.json`。这些属于 `PRIOR_EXECUTED_PROOF`，不是本文新增测试结果。
- 旧 `backend/src/main/java/com/jkxt/scheduling/solver/ScheduleFixture.java:20–30` 不承载主监资格、资格有效期或每日上限。
- 旧 `backend/src/main/java/com/jkxt/scheduling/service/ScheduleJobService.java:381附近` 按 active 状态筛教师，再构建旧输入。此路径未见新资格模型桥接；须真实验证旧、新、手工入口，不能只凭模型差异宣布所有旧结果错误。
- 新 `ExamPlanPlanningService.java:406–444` 已加载资格与岗位要求，不能将其描述为没有资格控制。
- 独立结果验证应与求解器选择逻辑分离，对缺岗、重复、超容量、资格、跨任务冲突分别注入坏结果。

状态：A/B误报修复为 `PRIOR_EXECUTED_PROOF`；跨入口一致性为 `STATIC_OBSERVED / NOT_RUN`。关联：SOLVE-01/02/03/04/05/06。

### 第四节：唯一生效版本和统一发布

**结论：新发布已有严格机制，旧发布的验证和历史语义仍不同。**

- `backend/src/main/java/com/jkxt/examops/service/ExamPlanService.java:268–296` 要求 APPROVED、期望游标与严格重校验，并写入 outbox；`:322` 拒绝提交人自审。
- 旧 `backend/src/main/java/com/jkxt/scheduling/service/ScheduleEditService.java:242–328` 检查结果非空、任务行锁和重复发布幂等，未在该发布方法中调用新链路的严格发布检查。
- 旧方法在事务内调用 `notificationDispatcher.dispatch`；未来接真实外部提供方时，事务与网络调用的耦合需要处理。
- `ScheduleEditService.java:329–342` 对已发布修改采用重开任务、清除发布时间，不是不可变后继版本。
- 不能直接删除旧API；应先复现停用教师后发布、自审/审批绕过、并发发布和已发布变更，再通过共享领域边界适配。

状态：`PARTIAL_IMPLEMENTATION / STATIC_OBSERVED`。关联：PUBLISH-01/02/03/04/05/06、REPORT-01。

### 第五节：数据库与可升级性

**结论：V30复合外键删除语义风险仍在源码中，尚未获得本轮数据库运行结果。**

- `backend/src/main/resources/db/migration/V30__legacy_task_plan_adapter.sql:24` 要求 `projected_run_id` 与游标同时为空或同时非空。
- 同文件 `:29` 定义 `(org_id, projected_run_id) ... ON DELETE SET NULL`，而 `org_id` 非空。
- 删除合法生命周期尚需确认；必须在独立 PostgreSQL 中复现 DATA-04，观察 org 非空约束及游标配对，不凭静态推导写数据库 FAIL。
- 修复应采用追加迁移，保留原 V30、外键、历史和测试数据库保护；不使用 clean/repair 掩盖问题。

状态：`STATIC_OBSERVED / NOT_RUN`。关联：DATA-03/04、ENV-05、RELEASE-01。

### 第六节：会话寿命与安全协议

**结论：Cookie/Redis与权限过滤已有实现，但附件指出的若干寿命和协议缺口仍存在。**

- `backend/src/main/java/com/jkxt/security/SecurityConfig.java:40` 全局关闭 CSRF。
- `backend/src/main/java/com/jkxt/security/auth/SessionService.java:66–67` 创建用户索引 TTL；`:79` 读取时只续会话本体；`:101–116` 撤销其他会话依赖用户索引。需要真实短TTL测试验证索引失联风险。
- `SessionPrincipal.java` 无创建时刻、绝对到期或权限版本字段。
- `SessionAuthenticationFilter.java:51–63` 使用 Redis 内权限快照；组织 active 检查位于显式切换组织分支，不是全部现有会话的重校验。
- 主代码中 `mustChangePassword` 的使用集中于实体和登录响应；未发现限制业务写请求的服务端过滤路径。应以真实请求验证 AUTH-01，而非仅凭搜索宣称全路径失败。
- 不直接合并登录隔离WIP。若引入入口协议，登录、me、退出、上传、下载、SSE及多标签页要作为同一协议验证。

状态：`STATIC_OBSERVED`；相关业务场景仍待隔离执行。关联：AUTH-01/02/03/04/05/06、PERM-05/06。

### 第七节：异步恢复与资源控制

**结论：已有持久状态和真实取消检查，但不等于已具备重启恢复与持久领取。**

- 旧 `ScheduleJobService.java:136,171`、新 `ExamPlanPlanningService.java:177` 使用进程内 `CompletableFuture.runAsync`。
- 新 `ExamPlanPlanningService.java:197,257,300,500–506` 存在取消标志及提交前检查，不能把取消笼统描述成纯UI文案。
- `backend/src/main/java/com/jkxt/scheduling/service/ScheduleAsyncConfig.java:14–22` 设置线程池2/4、队列32、`CallerRunsPolicy`；满载时可能由请求线程执行计算。
- 本次读取的计算路径未见租约、心跳和重启回收调度的闭环证据。先执行隔离进程中断/恢复和队列饱和场景，再设计持久任务领取机制。

状态：`PARTIAL_IMPLEMENTATION / STATIC_OBSERVED`。关联：ASYNC-01至ASYNC-06。

### 第八节：通知与平台功能真实性

**结论：已有诚实的外部通道关闭保护；客户端证据字段、测试故障参数和2FA标记仍需整改验证。**

- `backend/src/main/java/com/jkxt/notify/channel/LoggingEmailSender.java:42` 返回 `LOGGED`；`NotificationDispatcher.java:59–67` 区分 `logged_only`。
- `backend/src/main/java/com/jkxt/examops/service/NotificationDeliveryService.java:63–69` 对非 IN_APP 通道记录 `CHANNEL_NOT_CONFIGURED`。不应继续把全部通知实现称为假成功。
- 同服务 `:41–42,70–78` 接受 `failDeliveryIds` 并触发模拟失败；`NotificationDeliveryDtos.java:18` 暴露该字段，控制器 dispatch 路径未见环境隔离。需真实验证 NOTIFY-04。
- `NotificationDeliveryService.java:135–149` 已做本人资源关联和合法状态检查，必须保留；`:151–156` 将客户端 receipt 右合并到服务端 receipt JSON，存在覆盖证明字段的风险，需 NOTIFY-03反例。
- `backend/src/main/java/com/jkxt/platform/service/AdminPlatformService.java:292` 将 `twoFactor` 映射为 `!must_change_password`，不能作为真实第二因素已启用证据。
- 外部邮件/SMS未获批准时仅验收关闭路径；不得把外部真实投递场景标为PASS。

状态：`PARTIAL_IMPLEMENTATION / STATIC_OBSERVED`。关联：NOTIFY-01至NOTIFY-06、AUTH-06、OPS-06。

### 第九节：备份与运行观测

**结论：“写死大小伪造备份”旧问题已部分修正，但真实文件扫描仍不足以证明备份成功或可恢复。**

- `AdminPlatformService.java:623–637` 诚实说明本服务只扫描已有产物、不自行执行 pg_dump。
- 同文件 `:645–677` 扫描任意 `.sql` 后即可登记 `succeeded`；`:682–694` 读取相邻 `.sha256`，缺失返回 `unverified`，不重算内容校验和，也未见非空/完成清单检查。
- `docker-compose.private.yml:85` 应用备份卷挂载未标记 `:ro`；`:124–127` 备份 shell 没有 `set -e`，末尾 `ls` 可能掩盖前面 pg_dump 失败。
- `scripts/deploy/backup-private.ps1:16–20` 已使用 `set -eu` 并检查退出码，但直接写最终文件名，仍缺临时文件、非空与完成清单约束。
- `backend/src/main/java/com/jkxt/common/api/HealthController.java:23–25` 返回固定 UP；`application.yml` 开启 Actuator probes 不等于已经验证 DB/Redis/可选AI的就绪语义。
- 需要隔离恢复演练、真实数据对账与RPO/RTO，不能仅看哈希字符串或文件扩展名。

状态：`PARTIAL_IMPLEMENTATION / STATIC_OBSERVED`。关联：OPS-01/02/03/04/05/06。

### 第十节：前端与后端同步整改

**结论：统一请求层和真实错误归一化已有基础，生产构建与协议仍需补齐。**

- `src/api.js:49–57` 保留 status/code/details/trace；`:83–103` 使用真实 fetch、支持传入 signal，不能称为全mock请求层。
- 该请求方法未提供默认超时、CSRF载体处理或统一业务幂等键生命周期；应结合具体操作与服务端契约补齐。
- `src/api.js:150` 使用标准 `EventSource(..., { withCredentials: true })`。当前 Cookie 协议与此并不天然冲突；若未来强制入口上下文头，需要同步更换流协议，不能假定原生EventSource能自定义请求头。
- `Dashboard.html:409,415–459` 仍加载浏览器 Babel、`text/babel` 和 window 兼容脚本；`:9` 使用运行时 Tailwind CDN脚本。需分步引入生产构建并验证脚本顺序、缓存、动态类和全部关键页面。
- 视觉截图不能替代HTTP、落库、导出重新解析与角色权限验证。

状态：`PARTIAL_IMPLEMENTATION / STATIC_OBSERVED`。关联：FRONT-01至FRONT-06、AUTH-04、ASYNC-05。

### 第十一节：团队与协同证据

**结论：本次有实际只读独立核查，但不把角色、工具成功响应或本报告当作客户验收。**

- 主任务负责集成与证据工具；本代理只核查附件与源码，随后按限定路径授权新增本文。
- 本轮使用 Review Changes 技能。图工具的 `get_minimal_context` 虽成功响应，但返回 `0 nodes / 0 edges / 0 files`；这是空图，不能解释为低风险。已采用人工逐路径静态核查回退，未宣称图已覆盖工程。
- 不运行或恢复已全局暂停的Supervisor。普通Git、源码审阅和项目测试规则继续适用。
- 本报告没有运行新业务测试，也不是 CodeRabbit 对最终候选的正式批准记录。

状态：核查已完成；正式发布验收未完成。

### 第十二节：明确交付范围与发布放行

**结论：84项原始矩阵现已提供，应解除“执行包缺失”这一材料阻断，但不能把场景状态自动改为PASS。**

- 原矩阵明确84项初始为 NOT_RUN，要求每项绑定测试ID、命令、环境、SHA、退出码、断言和报告。
- 既有122项单元/切片测试不是84项场景的替代品。只有对应真实执行证据可绑定场景；不能把候选SHA变化后的旧报告直接盖章。
- 本文生成时既有库存说明仍保留“执行包MISSING”的旧描述；应由集成任务更新材料来源hash、场景ID和覆盖表，保留原文版本不篡改。
- 新发现入口继续追加，不以84为覆盖上限；外发、计费AI、生产迁移和公网部署仍需单独授权。

状态：材料可用；其余未运行/阻断项如实保留。关联：RELEASE-01至RELEASE-06。

## 3. 不应遗漏的 SOLVE-06 专项

`backend/src/main/java/com/jkxt/scheduling/solver/CpSatScheduleSolver.java` 当前源码存在以下观察：

1. `:212` 设置单次求解60秒；`:232–239` 最多执行三次求解，此路径未见涵盖全部重试的整体deadline。
2. `:240–241` 将 `MODEL_INVALID` 与 `INFEASIBLE` 一并送入 `infeasible(...)`。需用实际可触达夹具验证外部错误分类，不能仅改响应文字掩盖模型错误。
3. `:46–49,126–135` 班主任次数采用软上限。附件要求由业务定义硬/软，不宜擅自把它认定为全校必须硬限制，也不能继续以旧注释代替学校需求确认。

以上为 `STATIC_OBSERVED`，不是本轮新增Java运行失败记录。A/B修复并未覆盖这些问题。

## 4. 建议的依赖顺序与下一步

1. **G0/G1收口**：保存两份原始附件及hash，注册84场景；恢复独立测试依赖后枚举实际Spring路由，运行全套适用测试，解析发现/执行/失败/错误/跳过数量。
2. **安全与迁移红例**：先锁定会话索引TTL、绝对寿命、强制改密、CSRF，以及DATA-04真实数据库反例；保留TestDatabaseIsolationGuard。
3. **输入/算法/发布契约**：建立新旧求解、手工调整、发布入口矩阵；补独立结果验证、MODEL_INVALID分类和整体预算测试，再最小改造共同业务边界。
4. **异步与通知**：完成统一发布后，处理持久领取/恢复、取消提交竞态、客户端receipt与测试故障注入隔离；未批准的外部通道只测试关闭路径。
5. **运维与前端闭环**：备份失败及恢复演练、就绪探针、生产构建、缓存与真实浏览器业务链路；文件导出必须重新解析内容。
6. **冻结候选复验**：固定新的Git SHA/构建身份，重跑受影响门禁和完整必需场景，再独立终审；明确授权后才考虑真实部署。

## 5. 本次判定

- 附件已完整吸收并与当前源码逐节比对。
- 已修复的旧问题不重复机械修改；仍有静态风险的项进入复现队列。
- 本文没有给数据库、集成、通知、恢复或生产发布补写PASS。
- 当前判定：**整改继续，未达到交付放行条件**。
