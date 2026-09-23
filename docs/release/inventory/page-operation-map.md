# 页面 → 后端的垂直切片

逐接口机器清单位于 `traceability.csv`（包括具体文件和行号、前端处理函数、权限与测试候选）。本表补充用户操作语义与必要产物断言；均为静态追踪，后续真实点击/数据库断言未完成时保持未验证。

| 页面/操作 | 前端 → API | 服务/持久化核心 | 必须验收的实际效果 |
| --- | --- | --- | --- |
| 登录/退出/密码修改 | login.jsx、api.auth → `/auth/captcha`、`/auth/login`、`/auth/logout`、`/auth/me/change-password` | AuthService / SessionService / 用户与Redis会话 | 验证码单次使用、角色/租户绑定、旧Cookie撤销、锁定/节流、跨站写限制；不能仅跳到首页 |
| 工作台/新建任务 | dashboard.jsx → POST `/exam-tasks` | ExamTaskService / exam_task | 成功创建新taskId且刷新后存在；错误状态不toast成功 |
| 考务任务列表/详情 | tasks-page.jsx、task-detail.jsx → `/exam-tasks`、`/{taskId}`、`/assignments` | ExamTaskService / exam_task、schedule_run、schedule_assignment | 同学校真实记录、分页筛选语义、任务ID/结果归属、空态/失败态区别 |
| 排班工作台/Excel导入 | quick.jsx、quick-step1.jsx → `/{taskId}/uploads`、`/imports/validate`、`/import/templates/{kind}` | ExamTaskService / ImportValidationService / 文件、import_batch、主数据 | 100–150名合成但有明确标识的教师；字段、格式、重复、跨表身份、模板内容；不得导入真实人员数据进行测试 |
| 排班工作台/启动求解 | quick.jsx → `/{taskId}/schedule-jobs`、`/schedule-jobs/{jobId}`、SSE | ScheduleJobService / CpSatScheduleSolver / 真实结果表 | 全岗位覆盖、每日上限、窗口、资格、冲突与失败原因；DB、UI、导出一致 |
| 策略与约束/解析后启动 | ai-policy.jsx、ai-workflow.js → `/ai/providers`、`/nl-policy/refine|parse`、schedule-jobs | AiPolicyService、DSL验证、排班服务、AI审计 | 无真实配置明确失败；人工复核后绑定policyId/上传SHA；修改输入后阻止旧策略提交 |
| 人工调度/替换/撤回重做 | edit.jsx → `/tasks/{taskId}/schedule/check-conflicts`、PATCH schedule、undo/redo | ScheduleEditService / schedule_revision、assignment | 冲突预检再写；expectedRevision冲突；撤回/重做后DB与UI一致；已发布不可静默改写 |
| 旧任务发布/下载 | edit.jsx、task-detail.jsx、quick-step4.jsx → `/tasks/{id}/publish`、`/exam-tasks/{id}/export` | ScheduleEditService、ExamTaskService | 发布状态、审计、实际文件内容；与新版本发布链差异需专门核对 |
| 教师CRUD/导入 | people.jsx → `/teachers`、`/teachers/{id}`、`/import/teachers/commit` | MasterDataService、ImportCommitService / teacher | 删除使用中的教师保护、资格/窗口/每日上限保存读取一致、租户隔离 |
| 考场CRUD/导入 | classrooms.jsx → `/classrooms`、`/classrooms/{id}`、`/import/classrooms/commit` | 同上 / classroom | 容量、占用、停用和关联资源的真实校验，不用fixture覆盖学校资源 |
| 班级/科目维护 | classes-subjects.jsx → `/classes`、`/subjects`及各commit | 同上 / school_class、subject | 主数据ID唯一、考试引用一致、删除约束、Excel错误回执 |
| 考试计划/新建版本 | exam-plans.jsx → `/exam-plans`、`/{id}/revisions` | ExamPlanService / exam_plan、exam_plan_revision | 不变版本、继任版本、幂等键、If-Match并发、发布快照不能漂移 |
| 联合规划输入/计算 | exam-plans.jsx → `/plan-revisions/{id}/inputs/{resource}`、planning-jobs、cancel | JointInputRead/Write、ExamPlanPlanningService、联合规划器 | 13类输入资源完整性、提交幂等、取消/超时、日期/时间精度与结果哈希 |
| 风险与待办 | operations-workbench.jsx → `/operations/readiness` | OperationsQueryService | 数量与真实阻断记录一致；“无问题”不能来自加载失败或null默认0 |
| 审批与发布 | operations-v6-approvals.jsx → approval-requests、revision、approve/reject/publish、notification-deliveries | ExamPlanService、OperationsQueryService、NotificationDeliveryService | 审批≠发布；发布≠投递≠已读；核对当前revision游标、审计与结果 |
| 应急与事件 | operations-v6-emergency.jsx、exam-plans.jsx → emergency-changes（列表/继任版本创建） | ExamPlanEmergencyService / emergency_change、版本资源 | 请假/停用导致继任版本，旧已发布数据不可被直接改写；处理不等于仅改变本地卡片 |
| 工作量/历史统计 | stats-supervise.jsx、history.jsx、timeline.jsx → stats/history系列、stats/supervise、timeline/schedule | ReportingService / 持久排班与任务聚合 | 统计分母、状态口径、时间范围、津贴单价/空值，与独立SQL结果核对 |
| 个人/学校设置 | settings.jsx → auth/me/preferences、org/settings、appearance、terms | Preferences、Appearance、OrgSettings、AcademicTerm服务 | 写入后重新读取并刷新应用，不能仅localStorage；非管理员学校设置写被拒绝 |
| 超管租户/账号 | super.jsx → `/admin/tenants`、`/admin/accounts`及动作 | AdminPlatformService / organization、app_user、角色 | tenant ADMIN不得访问；账号停用/强退对会话立即或明确期限生效；无跨租户泄漏 |
| 超管安全/审计/公告 | super.jsx → `/admin/security/*`、audit-logs、announcements | AdminPlatformService / audit_log、ip_rule、announcement | “登记IP规则”与“规则生效”分别验；公告推送和已读源数据需追踪 |
| 超管备份/系统设置 | super.jsx → `/admin/backups/run`、platform-settings、system-health | AdminPlatformService / backup_record、设置及进程指标 | 备份只扫描不创建，缺文件unavailable；配置写后读回；没有恢复演练不得标可恢复 |

## 没有找到页面直连的后端面仍需保留

静态矩阵中部分接口仅有封装或未找到前端匹配（如教师自助签到/接受/拒绝、请假决定、事件/清单、执行/结束/归档、诊断、一些新资源写入端点）。这并不证明接口不可达；接下来应通过实际 Spring 注册表、权限矩阵及独立API测试确认它们的状态，并明确哪些属于本轮承诺交付的UI入口。不能为了绿色删除这些接口。

## 不承诺已完成的覆盖

本次 AST 的路径匹配包含动态通配候选，会产生多对多关联；有15处动态请求/别名未解析（重跑后以JSON数量为准）。这些点必须人工/运行时补齐；封装存在不是用户已能点击。所有结果未关联本轮真实测试产物前均不能计业务PASS。
