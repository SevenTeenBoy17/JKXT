# 84项验收矩阵：独立静态覆盖复核

日期：2026-09-24。候选仓库：`D:/VB/JKXT-release-g0-g1`。复核时HEAD：`cfab40ec62979ea64ead58506d516399ce52378a`。

## 范围、方法与证据边界

- 已完整阅读 `D:/download/02_后端与全栈验收矩阵.md` 和 `D:/download/03_架构与优化决策.md`；前者共14组、每组6项，合计84个原始ID，全部初始状态为 `NOT_RUN`。
- 本复核只读源文件、现有测试代码和 `docs/release/` 已有证据索引，不运行数据库、Docker、真实登录、外部AI或邮件短信。不修改产品代码、测试断言或原附件。
- 下表的“PARTIAL候选”只表示找到了部分相关测试/实现源码，**不是矩阵状态PASS**。未见专测不等于证明功能不存在。所有84项的完整场景状态在此均保留 `NOT_RUN`；既有局部证据单独引用，不能批量继承。
- 上轮全量数据库测试受Docker启动故障阻断；本子任务未修复或重新启动Docker，没有创建隔离库/Redis。运行时Spring注册接口、真实迁移、SQL副作用、导出内容、外部提供方均没有在此获得新增证明。
- 已有 `frozen-unit-regression` 的122项通过、发布工具57项通过及前端47文件语法通过，是各自限定范围的历史证据，不等于84项通过。新候选变动后按影响范围重跑，不能使用旧SHA报告放行。
- Explore Codebase图能力的首次调用因不支持参数被拒；改正参数后返回其他工程社区（`server-image`/`dashboard-image`/`lib-ppt`），未采信为本仓库证据；以下使用真实路径和源码核对。

## 路径说明

以下所有 `backend/...`、`scripts/...` 路径均相对产品目录 `D:/VB/JKXT-release-g0-g1/JKXT V2.0/`；明确标为“仓库根”的路径相对 `D:/VB/JKXT-release-g0-g1/`。真实文件名用于定位，不以名称中的Integration、Acceptance、Production推定行为已经通过。

## ENV：环境与安全配置

入口/配置候选：`backend/src/main/java/com/jkxt/security/SecurityConfig.java`；`backend/src/main/java/com/jkxt/security/api/AuthController.java`；仓库根 `compose.release-test.yml`、`scripts/release/run-isolated.ps1`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| ENV-01 | `backend/src/test/java/com/jkxt/security/ProductionSecurityGateTest.java`；`DemoAccountSecurityGuardTest.java`（同目录） | 隔离生产候选真实启动、脱敏有效配置、正式/演示账号登录结果，不能只查配置文本。 |
| ENV-02 | `backend/src/test/java/com/jkxt/security/ProductionSecurityGateTest.java` | 验证每个不安全生产组合实际拒绝启动；类单测不替代启动行为。 |
| ENV-03 | `backend/src/test/java/com/jkxt/security/AuthIpThrottleAcceptanceTest.java` | Caddy→Nginx→后端双代理、两真实来源、伪造转发头；现有直连IP测试不覆盖此拓扑。 |
| ENV-04 | `backend/src/test/java/com/jkxt/platform/P12DeployModeIntegrationTest.java`；`P12SaasTenantHeaderIntegrationTest.java`（同目录） | private/saas两套实际有效配置、组织切换与拒绝副作用。 |
| ENV-05 | `backend/src/test/java/com/jkxt/security/TestDatabaseIsolationGuardTest.java`；`TestDatabaseIsolationGuard.java`（同目录） | 保留保护器；错误DB/Redis和正确隔离实例的完整启动路径。历史8项保护单测仅证明其局部校验。 |
| ENV-06 | 仓库根 `scripts/release/run-isolated.ps1`；`backend/mvnw` | 干净检出后无个人环境/Cookie的可复建运行；Docker包装Maven依赖不能省略。 |

## AUTH：登录、密码与会话

入口：`backend/src/main/java/com/jkxt/security/api/AuthController.java`；服务：`backend/src/main/java/com/jkxt/security/auth/SessionService.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| AUTH-01 | `backend/src/test/java/com/jkxt/security/AuthIntegrationTest.java`（改密与mustChangePassword相关方法） | 绕过UI直接写业务的服务端约束；清除标记测试不等于改密前所有写接口禁止。 |
| AUTH-02 | `backend/src/test/java/com/jkxt/security/AuthIntegrationTest.java`（`changePasswordRevokesOtherSessionsButKeepsCurrent`） | 可控时钟跨用户索引寿命、会话续期、所有旧会话撤销、绝对有效期。 |
| AUTH-03 | 同一 `AuthIntegrationTest.java` 的login/me/logout路径 | 学校/平台入口交错、多标签、刷新、下载和SSE统一协议；不合并WIP以冒充已完成。 |
| AUTH-04 | `backend/src/test/java/com/jkxt/security/AuthIntegrationTest.java`；权限相关测试仅作入口参考 | 所有写操作的缺失/错误/正确CSRF证据及无副作用断言；当前SecurityConfig仍禁用CSRF。 |
| AUTH-05 | `backend/src/test/java/com/jkxt/security/AuthLockoutAcceptanceTest.java`；`AuthIpThrottleAcceptanceTest.java`；`AuthIntegrationTest.java`（同目录） | 并发失败计数、Redis断开/重启、稳定错误及告警，不仅并发正确登录。 |
| AUTH-06 | `backend/src/test/java/com/jkxt/platform/TeacherAccountRoleBindingIntegrationTest.java`；账号管理测试 | 临时凭据不入日志、交付流程、真实2FA能力/状态；角色和改密标记不是第二因素证明。 |

## PERM：权限与学校隔离

入口涉及全部控制器；重点 `backend/src/main/java/com/jkxt/examops/api/MyDutyAssignmentController.java`、`DutyAssignmentSelfServiceController.java`，以及主数据、导出、批处理入口。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| PERM-01 | `backend/src/test/java/com/jkxt/masterdata/MasterDataApiIntegrationTest.java`；`backend/src/test/java/com/jkxt/platform/P12SaasTenantHeaderIntegrationTest.java` | 跨校读/写/下载/导出资源逐项对抗，目标学校DB不变，不泄露存在性。 |
| PERM-02 | `backend/src/test/java/com/jkxt/examops/DutyAssignmentSelfServiceIntegrationTest.java`；`backend/src/test/java/com/jkxt/platform/TeacherAccountRoleBindingIntegrationTest.java` | 本人及他人确认、拒绝、签到、已读四类操作及不必要信息裁剪。 |
| PERM-03 | `backend/src/test/java/com/jkxt/security/RbacPermissionMatrixAcceptanceTest.java`；`BusinessRbacAcceptanceTest.java`（同目录） | 基于真实注册接口枚举全业务方法；现有矩阵是选定端点，部分“非403”只证明未被鉴权拦截。 |
| PERM-04 | `backend/src/test/java/com/jkxt/examops/JointInputApiIntegrationTest.java`；`backend/src/test/java/com/jkxt/platform/TeacherAccountRoleBindingIntegrationTest.java` | 合法/跨校ID混批的导入、删除、通知、导出各事务策略及零越权副作用。 |
| PERM-05 | `backend/src/test/java/com/jkxt/platform/P10ReportAdminApiIntegrationTest.java`（`disablingAccountRevokesItsActiveSession`） | 学校停用、降权、活跃SSE失效、恢复不复活旧凭据；单会话停用不足。 |
| PERM-06 | `backend/src/test/java/com/jkxt/security/RbacPermissionMatrixAcceptanceTest.java`；`AuthIntegrationTest.java`（同目录） | 真登录cookie贯穿URL规则、方法权限、服务/仓储；不能mock安全过滤器替代。 |

## DATA：主数据、学期与关联完整性

入口：`backend/src/main/java/com/jkxt/masterdata/api/TeacherController.java`、`ClassroomController.java`、`SchoolClassController.java`、`SubjectController.java`；`backend/src/main/java/com/jkxt/org/api/AcademicTermController.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| DATA-01 | `backend/src/test/java/com/jkxt/masterdata/MasterDataApiIntegrationTest.java` | 真事务、org_id、版本、审计及应用重启后读取；不得仅HTTP成功。 |
| DATA-02 | 上述主数据测试；`backend/src/test/java/com/jkxt/importer/ImportSubjectIdentityValidationIntegrationTest.java` | 两学校同编号允许、同校并发重复拒绝与稳定业务错误。 |
| DATA-03 | 主数据测试与 `backend/src/test/java/com/jkxt/tasks/ExamTaskAssignmentsRunSelectionIntegrationTest.java` | 已发布引用删除/停用、历史快照、姓名变更不改历史。 |
| DATA-04 | `backend/src/test/java/com/jkxt/examops/JointPlanningMigrationIntegrationTest.java`仅作历史迁移起点 | V30 `backend/src/main/resources/db/migration/V30__legacy_task_plan_adapter.sql` 的复合FK删除与成对游标CHECK真实PostgreSQL行为。V28测试不能替代。 |
| DATA-05 | `backend/src/test/java/com/jkxt/org/AcademicTermApiIntegrationTest.java`；`backend/src/test/java/com/jkxt/platform/TenantInitialTermSeedTest.java` | 业务时区跨年/假期/学期交界，工作台/报表/导出共同口径。 |
| DATA-06 | 主数据列表相关测试 | 稳定tie-breaker、多页并发增删、空页回退、边界资源预算的专门测试。 |

## IMPORT：Excel导入与文件边界

入口：`backend/src/main/java/com/jkxt/importer/api/ImportController.java`；排班上传还需覆盖 `backend/src/main/java/com/jkxt/tasks/api/ExamTaskController.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| IMPORT-01 | `backend/src/test/java/com/jkxt/importer/ImportCommitApiTest.java`；`ImportCommitAcceptanceTest.java`；`ImportValidationAcceptanceTest.java`（同目录） | 混合合法/错误/重复工作簿、预检零正式写、固定全量/部分策略、无半套关联。 |
| IMPORT-02 | `backend/src/test/java/com/jkxt/importer/ImportValidationApiTest.java`及验证单测仅有普通文件起点 | 伪装类型、损坏ZIP、重复条目、超限和解压比，覆盖所有上传入口及残留检查。 |
| IMPORT-03 | `backend/src/test/java/com/jkxt/importer/ImportCommitAcceptanceTest.java`（`examReimportGuardRejectsAllInvalidAndPreservesExistingData`）；`ImportSubjectIdentityValidationIntegrationTest.java`（同目录） | 考试对不存在/跨校教师、考场、班级等引用逐种拒绝；教师任教学科部分导入政策需单独明确。 |
| IMPORT-04 | `backend/src/test/java/com/jkxt/examops/JointInputApiIntegrationTest.java`的payload/游标测试仅可作新链起点 | 同文件重放、同幂等键异内容、超时/并发导入及结果查询的专门证据。 |
| IMPORT-05 | `backend/src/test/java/com/jkxt/scheduling/service/SchedulePolicyBindingIntegrationTest.java`；`ScheduleJobPolicyResolutionTest.java`（同目录） | 换上传后旧policyId/reviewedUploads拒绝，任务/学校/内容版本全绑定。 |
| IMPORT-06 | `backend/src/test/java/com/jkxt/tasks/service/UploadTruthfulnessTest.java`仅作上传元数据参考 | 匿名/跨校/过期下载，非公开静态暴露，保留和清理审计实测。 |

## SOLVE：排班与算法正确性

入口：`backend/src/main/java/com/jkxt/scheduling/api/SchedulingController.java`、`ScheduleEditController.java`；`backend/src/main/java/com/jkxt/examops/api/PlanningJobController.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| SOLVE-01 | `backend/src/test/java/com/jkxt/examops/service/DeterministicInvigilatorAllocatorTest.java`；`ExamOperationsHardConstraintsTest.java`（同目录）；`backend/src/test/java/com/jkxt/scheduling/ScheduleEditApiIntegrationTest.java` | 旧任务/新计划/手工三入口同一资格覆盖矩阵；单引擎资格断言不等于全入口通过。 |
| SOLVE-02 | `backend/src/test/java/com/jkxt/examops/joint/DeterministicJointPlannerTest.java`（daily limit/campus）；手调集成测试 | 同校跨任务有效占用、并发修改、移动时间、旧无效版本不重复计算。 |
| SOLVE-03 | `backend/src/test/java/com/jkxt/examops/service/DeterministicInvigilatorAllocatorTest.java:28` | 存在上一轮有效red/green及冻结局部回归证据；当前返回诚实UNKNOWN而非已经求得B早/A晚。本文不据此对整份新执行包自动盖PASS。 |
| SOLVE-04 | `backend/src/test/java/com/jkxt/examops/joint/DeterministicJointPlannerTest.java`属性用例仅作参考 | 独立穷举/匹配对照、缺岗/重复/失资格/重叠/超容量坏结果注入及发布拒绝；不能复用同一eligibility helper自证。 |
| SOLVE-05 | `backend/src/test/java/com/jkxt/scheduling/AiPolicySolverTest.java`；`backend/src/test/java/com/jkxt/scheduling/service/ScheduleJobPolicyResolutionTest.java` | 硬软组合、不可满足、时长/次数/主监负担独立计量与公平性，不宣称唯一最优。 |
| SOLVE-06 | `backend/src/test/java/com/jkxt/scheduling/SolverDiagnosticsAcceptanceTest.java`；`backend/src/test/java/com/jkxt/examops/joint/DeterministicJointPlannerTest.java` | MODEL_INVALID/INFEASIBLE/UNKNOWN/FEASIBLE逐类反例、整体deadline含重试、完整来源元数据。 |

## PUBLISH：修改、审批、发布与变更

入口：`backend/src/main/java/com/jkxt/scheduling/api/ScheduleEditController.java`；`backend/src/main/java/com/jkxt/examops/api/ExamPlanController.java`、`ClosedLoopController.java`、`EmergencyChangeController.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| PUBLISH-01 | `backend/src/test/java/com/jkxt/scheduling/ScheduleEditApiIntegrationTest.java`；`backend/src/test/java/com/jkxt/examops/ExamPlanApiIntegrationTest.java` | 每个新旧发布入口在停用/新增请假后重校验，失败不发通知。 |
| PUBLISH-02 | `backend/src/test/java/com/jkxt/examops/ExamOperationsClosedLoopIntegrationTest.java`；`ExamPlanApiIntegrationTest.java`（同目录） | 自审/未审批发布拒绝、另人合法审批；简化规则需显式产品授权。 |
| PUBLISH-03 | `backend/src/test/java/com/jkxt/examops/ExamPlanApiIntegrationTest.java`（optimistic lifecycle）；手调测试 | 两并发管理员同游标真实竞争，冲突输入保留，无半版本结果。 |
| PUBLISH-04 | `backend/src/test/java/com/jkxt/scheduling/ScheduleEditApiIntegrationTest.java`（`republishIsIdempotentDoesNotDuplicateNotificationOrChangePublishedAt`） | 同键/异键并发及事务提交故障注入；通知事件和有效版本各唯一。 |
| PUBLISH-05 | `backend/src/test/java/com/jkxt/examops/ExamOperationsClosedLoopIntegrationTest.java`；`backend/src/test/java/com/jkxt/tasks/service/ExamTaskMutationGuardTest.java` | 后继应急版本、差异历史及受影响教师通知；旧新页面同指向有效版本。 |
| PUBLISH-06 | `backend/src/test/java/com/jkxt/examops/ExamOperationsClosedLoopIntegrationTest.java`；`ExamOperationsExecutionIntegrationTest.java`（同目录） | 新旧从规划至归档全部状态和真实副作用、不得旧API旁路审批/验证。 |

## ASYNC：异步、恢复与资源控制

实现：`backend/src/main/java/com/jkxt/scheduling/service/ScheduleJobService.java`、`ScheduleAsyncConfig.java`、`ScheduleProgressSink.java`；`backend/src/main/java/com/jkxt/examops/service/ExamPlanPlanningService.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| ASYNC-01 | `backend/src/test/java/com/jkxt/scheduling/ScheduleJobApiIntegrationTest.java`仅正常轮询 | 提交后未领取时实际终止/恢复进程，不永久QUEUED，副作用幂等。 |
| ASYNC-02 | `backend/src/test/java/com/jkxt/examops/ExamPlanJointPlanningIntegrationTest.java`仅deadline/cancel起点 | RUNNING中断与租约回收，旧新任务真实重启恢复。 |
| ASYNC-03 | `scripts/p11/k6-smoke.js`及异步配置仅为起点 | 混合计算/登录/查询/取消负载、队列上限、拒绝策略及资源指标。 |
| ASYNC-04 | `backend/src/test/java/com/jkxt/examops/ExamPlanJointPlanningIntegrationTest.java`；`joint/DeterministicJointPlannerTest.java`（相对同examops目录） | 等待和执行中真实取消、重复请求、晚到结果不能覆盖有效版本。 |
| ASYNC-05 | `backend/src/test/java/com/jkxt/scheduling/ScheduleJobApiIntegrationTest.java:73` | 现有只验证200和asyncStarted；需要真实浏览器断网重连、终态、跨校及过期会话持续订阅。 |
| ASYNC-06 | `backend/src/test/java/com/jkxt/p11/P11TestcontainersInfrastructureTest.java`仅依赖连接起点 | DB/Redis短时失效、双worker竞争、提交不确定幂等恢复。 |

## NOTIFY：通知、阅读、确认和签到

入口：`backend/src/main/java/com/jkxt/notify/api/NotificationController.java`；`backend/src/main/java/com/jkxt/examops/api/NotificationDeliveryController.java`、`DutyAssignmentSelfServiceController.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| NOTIFY-01 | `backend/src/test/java/com/jkxt/notify/NotificationDispatcherTest.java`；`backend/src/test/java/com/jkxt/examops/NotificationDeliveryTruthfulnessTest.java` | 两套通道及UI统一显示未配置/LOGGED，不升格真实外部成功。 |
| NOTIFY-02 | 客户端/dispatch测试不是外部提供方验收 | 必须另获外发批准、测试收件人/限额、提供方ID及受理/送达/已读区分；未批准不记PASS。 |
| NOTIFY-03 | `backend/src/test/java/com/jkxt/notify/NotificationApiIntegrationTest.java`；`backend/src/test/java/com/jkxt/examops/ExamOperationsClosedLoopIntegrationTest.java` | 各投递状态、本人与跨人、重复、客户端receipt不能覆盖证明字段。 |
| NOTIFY-04 | `backend/src/test/java/com/jkxt/examops/ExamOperationsClosedLoopIntegrationTest.java`的故障注入用例仅作测试参考 | 正式请求拒绝/隔离failDeliveryIds；服务源码仍读取该字段，需复现后整改。 |
| NOTIFY-05 | `backend/src/test/java/com/jkxt/notify/NotificationProducersAcceptanceTest.java`；闭环重试测试 | 慢/失败提供方不占发布事务，真实消费者、重试/死信/人工处理。 |
| NOTIFY-06 | `backend/src/test/java/com/jkxt/examops/DutyAssignmentSelfServiceIntegrationTest.java` | 提前/过迟窗口、重复、应急换人后旧岗位拒绝，审计/通知/工作台一致。 |

## REPORT：统计、导出和审计

入口：`backend/src/main/java/com/jkxt/reporting/api/ReportingController.java`、`backend/src/main/java/com/jkxt/platform/api/AdminPlatformController.java`、`backend/src/main/java/com/jkxt/tasks/api/ExamTaskController.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| REPORT-01 | `backend/src/test/java/com/jkxt/platform/P10ReportAdminApiIntegrationTest.java`；`backend/src/test/java/com/jkxt/tasks/ExamTaskAssignmentsRunSelectionIntegrationTest.java` | 有timeline有效run SQL对账候选，仍需所有仪表盘/列表/统计/导出同版本口径。 |
| REPORT-02 | `backend/src/test/java/com/jkxt/platform/P10ReportAdminApiIntegrationTest.java`；`backend/src/test/java/com/jkxt/org/OrgSettingsApiIntegrationTest.java` | 90/150分钟、跨日、主副不同费率的确定金额/舍入/明细求和；字段存在不等于算术正确。 |
| REPORT-03 | `scripts/p11/global-fullflow-e2e.cjs`导出仅bytes检查 | 重新解析XLSX/PDF，中文长字段、零条、工作表和实际版本；不能以>200字节或下载事件通过。 |
| REPORT-04 | 权限测试和导出入口仅作候选 | 字符串不被执行为公式、敏感字段角色裁剪、跨校/过期授权、大导出资源控制。 |
| REPORT-05 | `backend/src/test/java/com/jkxt/audit/AuditPersistenceProbeTest.java`；主数据审计测试；`backend/src/test/java/com/jkxt/platform/SystemHealthAndAuditWindowTest.java` | 全关键写的成功/失败事务语义、actor/目标/版本/trace及无敏感日志。 |
| REPORT-06 | 公告入口与旧全链脚本仅作候选 | 推送不增已读、实际唯一接收者回执、不以租户数估算。 |

## AI：AI、约束转换与成本边界

入口：`backend/src/main/java/com/jkxt/ai/api/AiPolicyController.java`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| AI-01 | `backend/src/test/java/com/jkxt/ai/client/OpenAiCompatibleLlmClientTest.java`；`service/AiPolicyOperationalFailureTest.java`（相对同ai目录） | 真实API关闭/缺配置路径与非AI排班仍可用；不把固定摘要当成功。 |
| AI-02 | `backend/src/test/java/com/jkxt/ai/client/OpenAiCompatibleLlmClientTest.java`的模拟HTTP回退只是局部证据 | 经批准真实提供方/模型/预算，真实主备成功和全失败；模拟服务器不证明外部模型。 |
| AI-03 | `backend/src/test/java/com/jkxt/ai/service/PolicyDslValidatorTest.java`；`backend/src/test/java/com/jkxt/ai/AiPolicyAcceptanceAuditIntegrationTest.java` | 未知人物/字段/参数、注入、白名单及硬约束可执行性完整端点路径。 |
| AI-04 | `backend/src/test/java/com/jkxt/scheduling/service/SchedulePolicyBindingIntegrationTest.java` | 学校、任务、版本和输入指纹绑定并用于求解/发布。 |
| AI-05 | `backend/src/test/java/com/jkxt/ai/client/OpenAiCompatibleLlmClientTest.java`的有限重试/响应大小用例 | 并发额度原子性、总预算、429/慢响应/取消、实际费用边界；不只单请求次数。 |
| AI-06 | `backend/src/test/java/com/jkxt/ai/service/PiiMaskerTest.java`；`backend/src/test/java/com/jkxt/ai/AiPolicyAcceptanceAuditIntegrationTest.java` | 实际提供方请求最小化、标识映射、应用及错误日志检查；无授权不发送真实教师数据。 |

## OPS：备份、运维与监控

入口：`backend/src/main/java/com/jkxt/platform/api/AdminPlatformController.java`、`backend/src/main/java/com/jkxt/common/api/HealthController.java`；`scripts/deploy/backup-private.ps1`、`backup-public.ps1`、`restore-drill-private.ps1`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| OPS-01 | `backend/src/test/java/com/jkxt/platform/PlatformHonestyAcceptanceTest.java`（缺产物不假成功） | 坏密码/不可写/磁盘满，实际备份命令非零、临时/空/不匹配产物不能登记成功。 |
| OPS-02 | `scripts/deploy/restore-drill-private.ps1` | 独立恢复库、登录/读取/导出/数据对账及实测RPO/RTO，不只hash存在。 |
| OPS-03 | 部署脚本/配置静态候选 | 异机副本、故障下仍可恢复、凭据分离和挂载最小权限；无实际存储拓扑证明。 |
| OPS-04 | `backend/src/test/java/com/jkxt/common/api/HealthAndVersionControllerTest.java`；`P11ObservabilityIntegrationTest.java`（同目录）；`backend/src/test/java/com/jkxt/platform/SystemHealthAndAuditWindowTest.java` | liveness/readiness对DB/Redis/可选AI失效的语义及核心请求结果。 |
| OPS-05 | 观测trace测试仅作起点 | 实际告警接收端送达、故障与trace关联；日志不算通知送达。 |
| OPS-06 | `backend/src/test/java/com/jkxt/platform/PlatformHonestyAcceptanceTest.java` | IP封禁/维护/自动备份实际被执行方消费和可撤销；配置落库不是功能完成。 |

## FRONT：前端与真实联调

候选工具：`scripts/p11/global-fullflow-e2e.cjs`、`playwright-e2e.cjs`；`scripts/school-e2e-20260909/run-workflows.mjs`、`verify-quality.mjs`。静态前端/API追踪见仓库根 `docs/release/inventory/frontend-requests.csv`、`traceability.csv`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| FRONT-01 | 上述请求清单/旧浏览器脚本 | 全页面可见/隐藏角色入口逐控件真实网络、服务与DB副作用，不以toast判成功。 |
| FRONT-02 | 后端 `backend/src/test/java/com/jkxt/common/web/RestExceptionHandlerAcceptanceTest.java`、`ExceptionContractAcceptanceTest.java`仅错误契约起点 | 401/403/409/422/429/503/断网的真实中文交互、trace与未提交输入保留。 |
| FRONT-03 | 旧全链脚本/后端幂等测试仅局部候选 | 高延迟双击、刷新、后退、超时重试，前后端共同幂等并恢复终态。 |
| FRONT-04 | 前端Babel语法检查仅开发语法 | 干净生产构建、无浏览器JSX/React开发包、资源指纹与旧缓存切换。 |
| FRONT-05 | `scripts/p11/playwright-e2e.cjs`等仅选择性UI起点 | 管理员/教师/只读键盘焦点、对话框、中文长字、缩放/宽度真实视觉回归。 |
| FRONT-06 | `scripts/p11/global-fullflow-e2e.cjs`；`scripts/school-e2e-20260909/run-workflows.mjs` | 新旧真实服务从导入至归档，HTTP/DB/UI/文件同时证实，核心API不全部mock。 |

## RELEASE：发布与交付证据

候选工具：仓库根 `scripts/release/check-release.mjs`、`record-command.ps1`、`run-isolated.ps1`；产品目录 `scripts/p11/k6-smoke.js`。

| 场景ID | PARTIAL测试源码候选 | 未完成的完整验收证据（当前NOT_RUN） |
|---|---|---|
| RELEASE-01 | `backend/src/test/java/com/jkxt/p11/P11TestcontainersInfrastructureTest.java`；`backend/src/test/java/com/jkxt/examops/JointPlanningMigrationIntegrationTest.java` | 空库全迁移与代表旧版升级，真实PG约束/数据，禁止clean/repair掩盖差异。 |
| RELEASE-02 | 全部已发现测试和仓库根发布检查器 | 发现/执行对应、必需无跳过，真实单元/集成/E2E报告，不复用旧产物。 |
| RELEASE-03 | Maven/前端依赖和镜像配置仅清单候选 | 实际构建产物SBOM、漏洞扫描与可达风险策略、支持期限/例外负责人。 |
| RELEASE-04 | `scripts/p11/k6-smoke.js`；算法规模单测仅局部候选 | 明确资源/数据量/并发的混合负载和恢复，P95/P99/错误率/队列实际记录。 |
| RELEASE-05 | 仓库根源码快照/命令记录/发布工具 | 同候选SHA、构建ID、镜像摘要及回滚演练；变更后重新取得受影响证据。 |
| RELEASE-06 | 发布状态/部署/管理员文档 | 全必需门禁、学校UAT签认及人工放行；生产迁移/真实通知/域名公网另授权。 |

## 已核实源码风险与旧结论修订

以下均为源码观察，尚未在本子任务运行复现，不冒充运行 `FAIL`：

1. `backend/src/main/java/com/jkxt/security/SecurityConfig.java:40` 仍使用 `.csrf(AbstractHttpConfigurer::disable)`；AUTH-04不能由旧绿色权限测试覆盖。
2. `backend/src/main/java/com/jkxt/security/auth/SessionService.java:67` 创建用户索引TTL；`:79` 读取活跃会话只续本体，不同步续用户索引。AUTH-02需跨TTL实测和最小修复。
3. `backend/src/main/java/com/jkxt/scheduling/solver/CpSatScheduleSolver.java:240` 将 `INFEASIBLE` 与 `MODEL_INVALID` 同分支处理；SOLVE-06应建立独立分类反例。
4. `backend/src/main/java/com/jkxt/examops/service/NotificationDeliveryService.java:41` 仍从正式DTO读取 `failDeliveryIds`；NOTIFY-04应覆盖正式环境拒绝路径。
5. `scripts/p11/global-fullflow-e2e.cjs:139-140` 仅断言XLSX/PDF字节数大于200，`:146` 提供方可回退到mock；该脚本绿色不能证明REPORT-03/AI-02。
6. `backend/src/test/java/com/jkxt/importer/ImportCommitAcceptanceTest.java:88` 明确保留“未知任教学科警告后插入教师”，`:143` 为“非法考场容量警告后提交”。不能机械删除既有断言。前者是教师字段部分导入政策，附件IMPORT-03主要针对考试外键；应明确字段级全量/部分策略并新增考试引用反例。
7. 附件03中A/B反例“仅静态推导、未在Java执行”的文字反映审计基线，而非当前候选最新状态。候选已有 `counterexample-valid-red`、`counterexample-valid-green` 和冻结局部回归证据，修复的是误报无解；当前诚实返回搜索未完成，不能宣称完整搜索已找到B早/A晚。保留附件原文/hash，通过本对照更新状态，不改写原附件。

## 12项架构决策的完整纳入边界

1. 保持模块化单体、Java/Spring Boot、PostgreSQL、Redis和已实现业务；没有证据不增微服务/K8s/全量TS迁移。
2. 每功能保留UI→请求→实际注册API→权限→服务/事务→数据库→异步→产物→测试证据链，补OpenAPI之外的后台/下载/订阅。
3. 新旧排班统一输入快照及硬约束定义，不强求不同引擎同排列；独立验证/小问题对照，禁止困难即降软。
4. 唯一生效版本/发布校验边界，旧API适配兼容，审批/并发/幂等/后继版本可追溯。
5. PostgreSQL真语义、追加迁移、组织与历史保护；V30删除由合法生命周期决定，不机械删约束或改旧迁移。
6. 保留会话正确机制，补索引TTL、绝对寿命、改密/权限变化；入口隔离是完整协议迁移，CSRF不能全路径豁免。
7. 持久领取、租约/心跳、恢复/取消及资源预算；数据库里QUEUED不等于任务可靠保存。
8. 保留已有LOGGED/CHANNEL_NOT_CONFIGURED真实性保护；补实际消费、本人阅读/签到与真实平台功能，外部渠道独立批准。
9. 备份失败即终止、临时产物与内容验证、独立恢复演练及最小权限；健康/就绪/可选AI区分，日志不是告警送达。
10. 先业务真状态，兼容式生产构建；统一请求层和幂等/版本/trace；原生EventSource不能硬塞自定义header或把长期密钥入URL。
11. 独立实际分工、共享契约、迁移编号、小提交/小回归与STATUS续接；角色名称不能冒充独立审阅。
12. 84项是最低基线，新增入口继续扩展；可选外部能力未批准不能静默排除为PASS，最终学校UAT/人工授权方可生产放行。

## 复核结论

84个ID全部保留并关联部分已有实现/测试与缺口；**本次没有任何新增业务PASS**。优先G0/G1完成原文hash/规范化执行包、运行时注册表与隔离全套测试，然后按既定阶段复现→最小修复→专项及影响面回归。缺少Docker运行证据仍须保留环境阻断；原始执行包已由本次附件补齐，不应继续标“未收到84项文件”。不恢复Supervisor，不生成其报告，不把普通项目测试包装成Supervisor验收。
