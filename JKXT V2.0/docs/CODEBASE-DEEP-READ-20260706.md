# JKXT V2.0 代码库深度分析

> 排监考系统 v2.0 —— K-12 中小学教务的考试监考排班系统。全栈、单构建多形态交付（dev / saas / private / public），中文 UI + 中文注释。
> 后端：Java 21 + Spring Boot 3.3 模块化单体（`com.jkxt.*` 按功能分包）+ PostgreSQL 16 + Flyway + Redis + Apache POI + OpenPDF + Google OR-Tools CP-SAT + STOMP/WebSocket + SSE。
> 前端：无打包器的浏览器内 React（Babel Standalone 运行时编译），`Dashboard.html` 单入口，全局 `window.api` / `window.ui`。
> 仓库根：`D:\VB\JKXT\JKXT V2.0`（本文所有仓库相对路径以 `JKXT V2.0/` 为前缀）。

---

## 一、系统总览与整体架构

### 1.1 定位与形态

JKXT 是一套把"一次考试的真实教务数据"通过约束求解自动排出监考安排，并支持人工微调、发布通知、报表统计的闭环系统。它以**同一份 `backend/` 构建**为核心，通过 `SPRING_PROFILES_ACTIVE + JKXT_DEPLOY_MODE + 一组 JKXT_* 环境变量`投影成四种运行形态：

- **dev**：默认口令、captcha-debug=true、无 nginx，直连 8080，多租户逻辑在但演示账号可登录。
- **saas**：多租户，`SUPER_ADMIN` 才能用 `X-Tenant-Id` 跨租户；`disable-demo-accounts=true`、`cookie-secure=true`、`strict-production-checks=true`。
- **private**：单校私有化，固定 `JKXT_FIXED_ORG_ID`（`…0001`）退化为单租户，nginx 同源反代，外发 SMS 关闭，端口偏移到 18180/18081/15432/16379。
- **public**：把 private profile 打成真实公网单主机（Caddy 终结 TLS，pg/redis 不暴露宿主端口）。**注意：public 跑的仍是 private 模式（单固定租户），并非 SaaS。**

### 1.2 文字架构图

```
┌──────────────────────────────────────────────────────────────────────────┐
│  浏览器 (无打包器 React)                                                     │
│  Dashboard.html ──串行加载~35个 text/babel 脚本 (Babel Standalone 编译)      │
│    icons → overlay → common → super-ui → charts → tweaks → api.js           │
│    → pages/* → app.jsx   (加载顺序 = 依赖图，全局 window.* 注册)              │
│  window.api (fetch, credentials:include, 统一错误信封, 401 事件)             │
│  window.ui  (命令式 toast/confirm/modal/drawer)                             │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ HTTPS
      ┌─────────▼─────────┐   public: Caddy(80/443, 自动TLS, 安全头)
      │   nginx (private) │◄──┘
      │  /api/v1 → app:8080│   静态: Dashboard.html + src/ + assets/, 30m 上传上限
      └─────────┬─────────┘   (saas/public 亦可走 Vercel 外部 rewrite)
                │
   ┌────────────▼─────────────────────────────────────────────────────────┐
   │  Spring Boot 模块化单体 (com.jkxt.*)                                    │
   │                                                                        │
   │  过滤链: TraceIdFilter → SessionAuthenticationFilter → authorizeHttp   │
   │                                                                        │
   │  security/  认证·会话·RBAC·租户上下文·启动安全门                          │
   │  common/    TenantContext(ThreadLocal) · RestExceptionHandler · Deploy │
   │  masterdata/ 教师·学科·班级·考场 CRUD (MasterDataService)               │
   │  importer/  Excel 解析→校验→提交 (POI, 先校验零错才落库)                 │
   │  tasks/     考试任务门面 (create→upload→validate→排考→导出)             │
   │  scheduling/ 求解器 + Run状态机 + 编辑/发布  ◄── AI DSL 桥              │
   │  ai/        自然语言策略 → PolicyDsl → SchedulePolicy 开关              │
   │  reporting/ 只读聚合 (JdbcTemplate, active-run 口径)                    │
   │  notify/    发布多渠道扇出 + 站内信收件箱                                │
   │  platform/  超管跨租户运营台 (JdbcTemplate 手写 SQL)                    │
   │  audit/     审计流水实体 (platform + scheduling 复用)                   │
   └───┬─────────────┬──────────────────────────┬────────────┬─────────────┘
       │             │                          │            │
   ┌───▼───┐    ┌────▼────┐            ┌─────────▼──────┐  ┌──▼────────────┐
   │ Redis │    │Postgres │            │ OR-Tools CP-SAT│  │ email/sms 桩   │
   │会话/  │    │16+Flyway│            │ (8-worker      │  │(logging-only) │
   │验证码 │    │V1–V13   │            │  portfolio+重试)│  │真实需另配 bean │
   └───────┘    └─────────┘            └────────────────┘  └───────────────┘
```

### 1.3 分层与横切约定

- **租户隔离**是第一原则，但采用**应用层纪律型隔离**：`SessionAuthenticationFilter` 把 orgId 注入 `TenantContext`（ThreadLocal），每个 service 方法首行 `TenantContext.getRequired()` 取 orgId 并拼进所有查询。**无 Hibernate 全局 filter、无 Postgres RLS** —— 依赖开发者每次都记得加 `org_id` 条件。
- **统一错误信封** `{code,message,status,timestamp,traceId,errors}` 由 `RestExceptionHandler` 收敛，前端 `api.js` 按 `code` 分支。
- **乐观锁标准化**：几乎每张业务表都有 `version bigint`（JPA `@Version`）+ DB 生成的 `created_at/updated_at`。
- **Flyway 强制迁移**：`spring.jpa.ddl-auto=none`，schema 唯一真相在 `V<n>__p<phase>_*.sql` 脚本。

---

## 二、一次典型请求的端到端生命周期（以"一键排考"为例）

下面按 `登录 → 导入 → 排考 → 编辑 → 发布 → 报表` 追踪一次完整业务流，标注每一跳落在哪个文件。

### 2.1 每请求的公共前置（过滤链）

1. `common/web/TraceIdFilter`（最高优先级）：从 `X-Trace-Id` 取或用 `SecureRandom` 生成 16 字节 traceId 放入 MDC，回写响应头，供错误信封与日志关联（`logback-spring.xml` 打 `%X{traceId}`）。
2. `security/auth/SessionAuthenticationFilter`：从 `JKXT_SESSION` Cookie 取 sessionId → `SessionService.find()` 去 Redis（key=`jkxt:session:<uuid>`）反序列化 `SessionPrincipal`（含 userId/orgId/role/permissions）并滑动续期 TTL → `resolveTenantContextOrg` 决定租户（默认 `principal.orgId()`；仅当带 `X-Tenant-Id` 且满足「saas 模式 + SUPER_ADMIN + 目标 org active」才允许跨租户切换）→ `TenantContext.set(orgId)` + 回写 `X-Tenant-Id` 头 + 塞 request attribute + 向 `SecurityContext` 注入 `ROLE_<role>` 与各 permission → **finally 中 `TenantContext.clear()` 防 ThreadLocal 泄漏**。
3. `security/SecurityConfig` 的 `authorizeHttpRequests`：URL+方法粗粒度授权。健康/版本/captcha/login/swagger 放行；`/admin/**` 需 SUPER_ADMIN；**所有写方法（POST/PUT/PATCH/DELETE `/api/v1/**`）需 ADMIN|SUPER_ADMIN**（此为历史 P1-1 修复，此前只读角色也能写）；其余需已认证。

### 2.2 登录

`pages/login.jsx` `handleLogin`：前端校验 → 若 `captchaId` 以 `local-` 前缀（后端未接通）则拦截 → `POST /auth/login`。

后端 `security/auth/AuthService.login`：
- `CaptchaService.consume`：Redis `GETDEL` 原子取删验证码，堵并发重放。
- 全局（跨租户）`findFirstByUsernameIgnoreCase` 找账号 → 非 active/锁定判断 → BCrypt 校验。
- 失败走**原生 JDBC 原子 UPDATE**：`failed_attempts+1，达 5 次置 locked_until=now+30min returning failed_attempts`（规避 `@Version` 并发丢计数）；`@Transactional(noRollbackFor=AuthException)` 保证失败计数/审计仍提交。
- 私有部署硬校验 org 必须等于固定 org、租户必须 active。
- `SessionService.create`：随机 UUID 作 sessionId，序列化 SessionPrincipal 入 Redis（TTL 默认 7 天），签 HttpOnly Cookie，并维护 `用户→会话集`索引（供改密后 `revokeOtherSessions`）。

前端拿到 profile 后按 `role` 校验 super/admin 门户互斥，`applySession` 写 `localStorage`（`jkxt:auth/mode/page/onboarded`），首登触发 `onboarding.jsx` 7 步引导。

### 2.3 导入（真实数据落库，替代旧写死 fixture）

一键排考编排器 `pages/quick.jsx` 状态机：`ensureBackendTask()` 先 `POST /exam-tasks` 建 draft 任务拿 taskId（`tasks/service/ExamTaskService.create`，生成 `QK-日期-短UUID` 任务号），再 `api.upload('/exam-tasks/:id/uploads',{kind})`。

后端 `tasks/service/ExamTaskService.upload`（728 行门面）：
- 对每种 kind（teacher/classroom/exam）先经 `importer/service/ImportValidationService.validate` 出 `ValidationReport`。
- 登记 `UploadedFile`（sha256/storageKey，非真实对象存储）与 `ImportBatch`（rowCount/validCount/errorCount）两张追踪表。
- **`error==0` 才调 `importer/service/ImportCommitService.commit` 真正落库**。

`ImportCommitService`（669 行两阶段提交核心）：
- **提交侧强制复校**：`commit()` 内再次 `validate()` 而非信任前端结果，`error()>0` 直接 400。
- `parseRows()` → 按 canonical kind switch 到 `commitTeachers/Classrooms/Classes/Subjects/Exams`。
- 自然键 upsert（教师 `teacherNo`、考场/班级 `code`、考试 `exam_task_id` 全删重插）；文件内自然键重复 **skip+warning**（非 last-write-wins）。
- email 冲突走 `resolveUniqueEmail()`：清空邮箱仍按工号导入（NULL 豁免唯一约束），避免一封坏邮箱整批 500。
- **归属校验**：所有入站外键 UUID 经 `findByOrgIdAndId(...).orElseThrow(404)`，把跨租户引用注入挡在写入前。
- 考试导入是最复杂路径——**"守卫先于破坏"**：先只读盘点 `plannedValid`，有效占比 <50% 直接拒绝且不碰旧数据；确认后才删旧 exam（`exam_room/exam_class` 级联）并清本任务 `schedule_run`。
- **班主任导入顺序无关回填**：教师先于班级导入时 `homeroom_class_id` 暂空但持久化 `homeroom_class_code`（V12），`commitClasses` 到达该班级时用 `findByOrgIdAndHomeroomClassCodeAndHomeroomClassIdIsNull` 回填 —— 这是"班主任不监本班"约束的数据支点。

`ExamTaskService.validate` 用 `aggregateReport` 判就绪度，关键是 `effectiveKindCount` **同时看「任务级 ImportBatch」与「主数据表 count」两条路径**，任一有数据即不算缺失，消除"主数据已备却误报缺数据"的假阴性。

> **注意**：CSV/公式注入防护（CWE-1236）**不在导入包内**——导入原样存储用户文本，防护前移到导出侧 `ExamTaskService.csvSafe()`（对以 `= + - @ \t \r` 开头者加前置单引号）。

### 2.4 排考（异步 CP-SAT + SSE 进度）

`quick.jsx` `startScheduling` 是**硬闸门**：无 `window.api` 或无 taskId 直接 toast「无法排监考」拒绝伪造（R6 去 mock），否则 `POST /schedule-jobs`。

后端 `scheduling/service/ScheduleJobService.startTaskRun`（Run 生命周期状态机核心），一个事务里：
1. `buildFixtureFromTask`：从真实表拼装 `ScheduleFixture`——查 exam、exam_room、exam_class、`school_class.homeroom_teacher_id ∪ teacher.homeroom_class_id`（班主任本班集合）、teacher_subject、subject。缺考试/考场/教师则抛 400 `SCHEDULE_INPUT_INCOMPLETE` 而非造假数据。
2. `resolvePolicy`：读该 task 最新 `nl2dsl` 的 `ai_call_log.masked_output.dsl`（AI 桥），`policyFromDsl` 解析成 `SchedulePolicy`（三开关：班主任避本班 hard、同年级回避 NONE/SOFT/HARD、任课避本班 opt-in）；缺 dsl 键（schema_invalid）回退 `defaults()`（SOFT）。
3. `prepareRealRun`：落一行 `schedule_run(status=pending)`，并清空任务 `publishedAt`（重排废止旧发布）。

随后 `CompletableFuture.runAsync` 交给 `scheduleTaskExecutor` 线程池（`ScheduleAsyncConfig`：core2/max4/queue32，CallerRunsPolicy 背压），`executePreparedRun` 分阶段 publish 进度（15/35/80/100）。

`scheduling/solver/CpSatScheduleSolver`（纯 CP-SAT、无状态）：
- `precheck`：考场容量（H7）、可用教师数 < 监考需求 → 直接 infeasible。
- 建模：每个 examRoom 一组 `assign/main` BoolVar；约束 `sum(assign)=监考人数`、`sum(main)=1`、`main≤assign`、同教师同时段互斥、同 exact slot 互斥。
- 目标：**字典序 minimax**——公平权（最忙教师负载，1e6）≫ 同年级/任课/班主任超 2 场软惩罚（1e3）≫ 主监角色均衡（1），间隔 1000× 保证高层绝不为低层被牺牲。
- **可靠性两条血泪教训固化**：8-worker portfolio（单 worker 对可行模型约 40% 种子会早退 UNKNOWN）+ 变种子重试 3 次（`baseSeed+attempt*7919`）；**严禁 `setStopAfterFirstSolution`**。

进度经 `scheduling/service/ScheduleProgressSink` 双写：内存 LRU(512) buffer（供 SSE 断线回放）+ 落 `schedule_run.logs`；`SchedulingController` 的 `/runs/{id}/events` 返回 `SseEmitter`。

前端 `quick.jsx` `waitBackendJob` 以 **80×500ms（≈40s 上限）轮询** `/schedule-jobs/:id`（尽管 api.js 提供 eventSource/webSocket，进度仍用轮询）；`infeasible/failed` 如实回退 Step2 提示冲突；成功则 `qkScheduleFromJob` 聚合 assignments 交给 `quick-step4.jsx`。

### 2.5 编辑（拖拽改派 + undo/redo）

`pages/edit.jsx` 真实拖拽：drop 时先 `POST /tasks/:id/schedule/check-conflicts`，无冲突再 `PATCH /tasks/:id/schedule`（带 `expectedRevision` 乐观锁）。

后端 `scheduling/service/ScheduleEditService`：
- `patch`：`expectedRevision` 乐观锁 + `conflicts()` 复核（H1 时段重叠 / H2 不可用 / H3 班主任本班，**与 solver `eligible()` 同口径**，杜绝"一键流强制、拖拽却能绕过"的路径漂移）→ 写 `ScheduleRevision`（before/after）。
- **undo/redo 双指针**：`revision`（最高水位）+ `revisionCursor`（当前位置），新改派若 cursor<revision 先删 cursor 之后的分支再截断，落库而非内存栈，支持跨会话。
- **发布不变量**：`published_at != null IFF status='published'`；任何 patch/undo/redo 命中已发布任务会 `reopenIfPublished` 重置为 `scheduled` 并清 `publishedAt`，强制显式再发布。

### 2.6 发布（悲观锁串行 + 多渠道扇出）

`ScheduleEditService.publish`（真正发布入口，非 tasks 包）：
- 事务内对任务行取悲观写锁 `findByOrgIdAndIdForUpdate`**串行化并发发布**（防两个并发发布双双给监考重复下发通知，迭代 12 修复）。
- 幂等：`published && publishedAt!=null` 则不重复通知。
- `notify/service/NotificationDispatcher.dispatch` 多渠道扇出，写一条 `schedule_published` 通知。
- `NotificationDispatcher` **诚实口径**：email/sms 为 logging-only 桩（`LoggingEmailSender/LoggingSmsSender`）记 `logged_only` 而非 `delivered`；站内如实区分 `deliveredNotifications`（实投广播条数）与 `addressedTeachers`（被涉及教师数），绝不把"被涉及教师"伪装成"已送达收件箱"。
- 收件箱由 `notify/service/NotificationService` 提供 list/unreadCount/markRead，已读态存 `notification_read`（V11，按用户维度，PK=`(notification_id,user_id)`）。

### 2.7 报表

`reporting/api/ReportingController`（`/api/v1/stats/*`、`/history/*`、`/timeline/schedule`）→ `reporting/service/ReportingService`，全 `@Transactional(readOnly)` + JdbcTemplate 原生 SQL。
- **统一 active-run 口径**：`activeRunId = latestSucceededRun`（该 org 最新 `succeeded` run，无则 zero-UUID 匹配空），所有聚合 `where schedule_run_id=?`，避免把历史/被取代 run 汇总。
- `teacherHistory`：left join 保证零负载教师也列出、subjects 走相关子查询（避免多学科教师 fan-out 重复计数）、`recent` 近 4 场用 `ROW_NUMBER` 窗口一次批量取（替代 per-teacher N+1，见近期 commit `e07d6cd`）。
- 津贴 = `duration × allowanceUnit`；`allowanceUnit` 从 `organization.settings->>'allowanceUnit'` 取，**正则校验是数字才 cast、否则回退 120**（防单条脏设置打挂全报表）。
- `timelineSchedule` 取最新成功 run 最小考试日为窗口起点，只查 `[start, start+6]` 7 天窗口。

前端 `pages/history.jsx`、`pages/stats-supervise.jsx` 真实拉取，仅在后端 null（加载中/失败）时回退示例数据并打"示例数据"标；导出用 SheetJS 客户端真生成 xlsx。

---

## 三、后端逐模块深度剖析

### 3.1 `com.jkxt.security` + `com.jkxt.common` —— 安全边界与横切

**职责**：认证/会话/RBAC/租户上下文/统一异常——整个后端的安全边界。

**关键类**：
- `security/SecurityConfig`：过滤链与授权规则（CSRF disable、STATELESS、CORS allowCredentials+origin 白名单）。
- `security/auth/SessionAuthenticationFilter`：每请求鉴权入口 + 租户解析。
- `security/auth/AuthService`：登录/登出/me/改密/onboarded 业务核心。
- `security/auth/SessionService`：Redis 会话存储 + 用户→会话集索引 + 滑动 TTL。
- `security/auth/CaptchaService`：SVG 验证码，`GETDEL` 原子消费。
- `common/tenant/TenantContext`：`ThreadLocal<UUID>`，`getRequired()` 是 8+ service 的隔离注入点。
- `common/web/RestExceptionHandler`：`@RestControllerAdvice` 全站异常映射（业务异常按自带 status；校验/类型/多部件/约束→400；乐观锁/悲观锁→409；媒体类型→415；兜底→500 只 log 不外泄堆栈）。
- `common/config/DeployProperties`：`isSaas()/isPrivate()/fixedOrgUuid()`。
- `security/ProductionSecurityGate`：strict 模式下对不安全默认（演示账号未禁用/captcha-debug/cookie 非 Secure）**拒绝启动**（fail-loud）。
- `security/DemoAccountSecurityGuard`：`ApplicationReadyEvent` 时按 `disable-demo-accounts` 幂等禁用 5 个种子演示账号。
- `platform/api/AdminPlatformController`：全局唯一方法级 `@PreAuthorize(hasRole SUPER_ADMIN)`，与 URL 规则形成双重门。

**数据流**：见 §2.1 / §2.2。

**设计取舍**：
- 无状态 Spring Security + 自维护 Redis 会话，换取水平扩展与按用户批量吊销。
- 登录账号簿记走原生 JDBC 原子 UPDATE 规避 `@Version` 竞态。
- 租户隔离用"过滤器注入 ThreadLocal + 每 service 手动拼 orgId"的**纪律型**约定。
- 跨租户切换收敛到单一入口 + 三重前置。
- 两道安全默认防线：`ProductionSecurityGate`（fail-loud）+ `DemoAccountSecurityGuard`（saas 禁用演示账号）。

### 3.2 `com.jkxt.masterdata` —— 主数据 CRUD（排监考数据底座）

**职责**：管理 Teacher/Subject/SchoolClass/Classroom 4 类核心主数据。

**关键类**：
- `masterdata/service/MasterDataService`（**唯一业务核心，约 800 行**）：4 类主数据的增删改查、唯一性/归属校验、`requireNotReferenced` 删除守卫、`replaceSubjects` 关联维护、`toXxxDto` 派生统计（监考次数/课酬）、audit、`normalizeXxx` 输入规范化。
- `masterdata/api/{Teacher,Subject,SchoolClass,Classroom}Controller`：**同构薄壳**，仅 `@Valid` 后转调 service。
- `masterdata/api/MasterDataDtos`：8 个 record（Request 带 bean 校验 + Dto 含派生字段与前端兼容冗余字段）。
- `masterdata/domain/Teacher`：含 `homeroom_class_id` 与 `homeroom_class_code` 双字段；`Classroom` 用 `@JdbcTypeCode(JSON)` 存 features jsonb。
- `masterdata/repo/TeacherRepository`：派生查询全带 OrgId 前缀。

**数据流**：Controller `@Valid` → Service 首行 `TenantContext.getRequired()` → 写路径先唯一性校验（`requireUniqueXxx`）→ `applyXxx` 规范化 + **归属校验（`ownedXxxId`：所有外键 UUID `findByOrgIdAndId().orElseThrow(404)`）** → `saveAndFlush` → 多对多用 `replaceSubjects`（先 delete 再 `insert...on conflict do nothing`）→ audit。删除先 `requireNotReferenced`（count 排程/考试引用，>0 抛贴切 409）再手工清反向引用。读路径 `list` 全量拉本 org 后**内存 stream+filter**；`toXxxDto` 逐条 jdbcTemplate 子查询补派生字段。课酬用 `activeRunFilter`（`distinct on` 每 task 最近 succeeded run）避免重排叠加。

**设计取舍**：单 Service 聚合 4 实体（横切逻辑复用 vs 单文件庞大）；JPA + 原生 JdbcTemplate 混用（关联表/派生统计无实体，裸 SQL 更直接）；内存过滤（K-12 单校规模可接受）；宽松规范化（中英文别名统一，非法值静默回退）。

### 3.3 `com.jkxt.importer` —— Excel 导入管道

**职责**：把 xlsx 主数据/考试名册转成落库实体，"先校验、零错误才落库"两阶段闭环。

**关键类**：
- `importer/service/ImportValidationService`：POI 解析 + `TemplateSpec/ColumnSpec` record + 表头别名映射（`normalizeHeader` 去空格/下划线/全角括号转半角/小写后 `fieldByAlias` 反查）+ 五类分级校验（error/warn/suggest）+ 打分。
- `importer/service/ImportCommitService`（669 行）：见 §2.3。
- `importer/api/ImportController`：`/api/v1/import` 模板下载/validate 干跑/`{kind}/commit`。
- `importer/api/ImportDtos`：`ValidationReport/ValidationItem/CommitResult`。
- `importer/domain/UploadedFile` + `ImportBatch`：追踪表，**仅由 tasks 包 `ExamTaskService` 任务上传流写入**，importer service 自身不触碰（这是 `aggregateReport` 要跨两路径判就绪的根因）。

**设计取舍**：校验与提交严格分离且提交侧强制复校（不可绕过的落库门）；自然键 upsert + 文件内去重告警（非 last-write-wins）；email 唯一约束降级而非崩溃；"守卫先于破坏"的两阶段考试重导入；`linkSubjects` 先解析后替换（保护 subject_avoid 数据链）；`BigDecimal.intValueExact()` 拒绝 `10.05→105` 静默放大。

### 3.4 `com.jkxt.scheduling` —— 排班求解器 + Run 生命周期 + 编辑/发布（核心引擎）

**职责**：把考试任务的真实数据转成 CP-SAT 模型求解出监考排班，异步执行 + SSE 进度，支持人工改派/undo-redo，最终发布通知。**全仓被打磨最狠的模块**，几乎每个非平凡决策都有"迭代 N Pk"注释。

**三层结构与关键类**：
- **solver/（纯求解，无状态）**：`CpSatScheduleSolver`（见 §2.4）、`SchedulePolicy`（求解开关 record：`avoidHomeroomClass` 硬 / `SameGrade` NONE·SOFT·HARD / `avoidSubjectClass`；`defaults()`=SOFT、`allOn()`=HARD）、`ScheduleFixtureFactory`（P6 演示/基准 fixture）。
- **service/（状态机 + 事务）**：`ScheduleJobService`（Run 生命周期）、`ScheduleEditService`（编辑/发布）、`ScheduleProgressSink`（SSE 枢纽，单协调锁让 publish/stream 互斥、LRU512 封内存泄漏、emitter I/O 在锁外）、`ScheduleAsyncConfig`（线程池）。
- **api/（薄）**：REST + SSE。
- **repo/**：`ScheduleRunRepository.findActiveRun`（"当前生效 run"唯一口径=最新 succeeded 优先，避免失败重排锁死已成功排班）。

**设计取舍**：字典序 minimax（数学保证高层不被低层牺牲）；软约束优先于硬过滤（K-12 班主任占比高，硬约束会误判 INFEASIBLE，硬过滤只保留班主任避本班 H3 与同年级 HARD 档）；8-worker portfolio + 变种子重试绕 UNKNOWN；编辑与求解同口径；undo/redo 双指针落库；发布生命周期不变量；异步 worker 全程 `ifPresent` no-op 防线程崩溃。

### 3.5 `com.jkxt.ai` —— AI 策略 DSL / LLM

**职责**：把教务自然语言排考策略脱敏后送 LLM，解析成受约束的 `PolicyDsl`，落库 `ai_call_log`，如实标注哪些规则求解器真会执行。**求解权威始终是 OR-Tools**（`solverAuthority=OR_TOOLS`），LLM 只做意图解析。

**关键类**：
- `ai/service/AiPolicyService`：脱敏→LLM→校验→四种结局（succeeded / schema_invalid / fallback / JSON 异常）→落库；`fallbackDsl` 确定性草案、`enforcementWarnings` 如实标注、`providers` 用量汇总。
- `ai/service/PolicyDslValidator`：10 种 type 白名单、severity 白名单、`solverAuthority==OR_TOOLS`、`confidence∈[0,1]`、规则数 ≤50。
- `ai/service/PiiMasker`：正则脱敏（邮箱/手机/身份证/中文"X老师·主任·同学"）。
- `ai/client/OpenAiCompatibleLlmClient`：mock 桩（fallback=true）与真实 OpenAI 兼容 `/chat/completions`（`response_format=json_object`，温度 0.1，重试/超时/usage 解析）。
- `ai/config/LlmProperties`：`jkxt.llm.*`（enabled/provider/baseUrl/apiKey/model/timeout/maxRetries/monthlyQuota）。
- `ai/domain/AiCallLog`：`ai_call_log` 实体，**DSL 持久化于 `maskedOutput.dsl`**。
- `ai/AiPolicyApiIntegrationTest`：锁定四大契约（mock 返回 fallback 而非伪 succeeded、脱敏落库、usage 统计、schema_invalid 不持久化 dsl）。

**设计取舍**（反 AI 幻觉三原则）：无真实 LLM 时无条件 `fallback=true`；`enforcementWarnings` 主动告知哪些规则求解器根本不执行；schema_invalid 故意不持久化 dsl 防误导下游。`fallbackDsl` 的 `same_grade_avoid` 定为 SOFT 而非 HARD（HARD 会把可行单年级排考翻成 INFEASIBLE）。

### 3.6 `com.jkxt.reporting` —— 报表统计

**职责**：纯 JdbcTemplate 只读聚合层，见 §2.7。

**关键类**：`reporting/service/ReportingService`（报表聚合核心）、`reporting/api/ReportingController`。

**设计取舍**：报表统一 active-run 口径；防脏数据打挂报表（`allowanceUnit` 正则守卫）；子查询防 fan-out + 窗口查询消 N+1；`monthlyTrend` 不足两月返回 `trendPct=null` 而非误导性 0%。**无独立测试文件**，契约靠注释与前端联调保障。

### 3.7 `com.jkxt.tasks` —— 考试任务门面（编排层）

**职责**：面向教务的考试任务全生命周期（create→upload→validate→排考→查看分配→导出 xlsx/pdf→delete），把 importer/scheduling/masterdata 串成流水线。

**关键类**：
- `tasks/service/ExamTaskService`（728 行门面）：双路径就绪判定、`findActiveRun` 选活跃排班、`csvSafe` 注入防护、POI(xlsx)/OpenPDF(pdf，内嵌 CJK 字体、全行分页、跨页重复表头) 导出。
- `tasks/api/ExamTaskController`：`/api/v1/exam-tasks` REST + SSE 进度转发 + **export 白名单前置校验**（因下游 `pdf?'pdf':'xlsx'` 三元会把非法值静默改写成 xlsx 使 service 校验成死代码，故 controller 层先 400）。
- `tasks/repo/ExamTaskRepository`：`findByOrgIdAndIdForUpdate` 悲观写锁（发布串行化）。

**设计取舍**：就绪度双路径判定消除假阴性；活跃排班唯一真源（失败重排不掩盖已生成好排班）；CSV/公式注入防护在导出侧；发布并发安全（悲观锁+幂等）。

### 3.8 `com.jkxt.platform` —— 超管运营台

**职责**：`SUPER_ADMIN` 独占的跨租户运营台（租户/账号/安全总览/审计日志/公告/备份/平台设置）。

**关键类**：
- `platform/service/AdminPlatformService`（764 行）：**几乎全用 JdbcTemplate 手写 SQL** 直查 organization/app_user/audit_log/security_event/ip_rule/backup_record/announcement，把 DB 行映射成前端 UI 语义字段（中文状态/角色/部门）；账号 reset/disable/delete **真副作用** + 撤销会话；`rejectCrossTenantMutationInPrivateMode` 把跨租户写锁到 fixedOrgUuid。
- `platform/api/AdminPlatformController`：类级 `hasRole('SUPER_ADMIN')` **唯一 RBAC 闸门**。

**设计取舍**：账号安全操作真副作用化（reset/disable/delete 从纯审计空动作改为真改密/停用/软删并即时撤销全部会话；删除用墓碑软删 `deleted_at + username/email 加 id 后缀`释放唯一名额）；private 部署跨租户写入硬隔离；全层 JdbcTemplate + Map 出参（牺牲类型安全换直接对接前端 UI 语义）。

### 3.9 `com.jkxt.notify` + `com.jkxt.audit` —— 通知与审计

**通知**（见 §2.6）：
- `notify/service/NotificationDispatcher`：发布多渠道扇出，诚实口径（delivered/logged_only/skipped）。
- `notify/service/NotificationService`：站内信收件箱，已读态按用户，SQL 同租户守卫。
- `notify/channel/NotificationSender`：渠道 SPI + `DeliveryOutcome` 枚举；email/sms 均 logging-only 桩，**可换真 SMTP/SMS bean 不动 dispatcher**。

**审计**：`audit/domain/AuditLog`（org/actor/action/target/severity/JSON payload），极薄流水实体，被 platform 与 scheduling 复用。

---

## 四、数据模型（核心表、关系、Flyway 演进）

### 4.1 核心表与关系

PostgreSQL 16，`V2__p2_core_schema.sql`（16KB）是整个 schema 的"大爆炸"phase，一次建齐 ~23 张表：

```
organization ──┬── role ── user_role ── app_user            (RBAC: app_user.role 枚举列 + user_role 关联表两套)
               │
               ├── subject ─┬─ teacher_subject ─── teacher   (M2M 任教)
               │            └─ school_class_subject ─ school_class  (M2M 开课)
               │                                       │
               │   classroom                           │ (循环 FK: school_class.homeroom_teacher_id
               │      ↑ default_classroom_id           │  ↔ teacher.homeroom_class_id，V2 延迟 ALTER 解)
               │
               ├── exam_task ── exam ─┬─ exam_class    (哪些班坐这场)
               │       │              └─ exam_room     (考场绑定)
               │       │
               │       ├── uploaded_file ── import_batch ── validation_issue  (导入追踪)
               │       │
               │       └── schedule_run ── schedule_assignment   (teacher×classroom×exam,
               │              │                UNIQUE(run,exam,classroom,teacher) 防重)
               │              └── schedule_revision (P9 编辑 before/after + revision_cursor undo/redo)
               │
               ├── notification ── notification_read (V11, PK=(notification_id,user_id) 每用户已读)
               ├── audit_log / ai_call_log            (审计与 AI 留痕)
               └── ip_rule / security_event / backup_record / announcement (V9 运营表)
```

**关键关系细节**：
- 一次排考 = `exam_task` 下挂多个 `exam`；一次 `schedule_run` 产出多条 `schedule_assignment`。
- **denormalization 快照**：`exam.subject_name`、`schedule_assignment.exam_date/start_time/end_time` 冗余存一份，即使 subject 改名/exam 变更，历史排考记录仍可独立读出。
- **FK 的 ON DELETE 语义不统一（最值得注意）**：`org_id` 外键基本 `ON DELETE CASCADE`（删租户级联清库）；指向主数据的引用（`exam.subject_id`、`schedule_assignment.classroom_id/teacher_id`、`school_class.default_classroom_id`、`teacher.homeroom_class_id`）故意留默认 `NO ACTION`（被排考引用的教师/教室无法直接物理删除，需先解引用）；`audit_log/ai_call_log` 对 actor/task 用 `ON DELETE SET NULL` 保留历史。这是有意的"历史不可级联删"设计，但分散无集中文档。

### 4.2 Flyway V1–V13 演进

| 版本 | Phase | 内容 |
|---|---|---|
| V1 | p1 | baseline marker 表，验证 Flyway 链路 |
| **V2** | p2 | **核心 schema 大爆炸**：~23 表 + 全部索引/CHECK/FK，启用 pgcrypto |
| V3 | p2 | 演示租户 Demo School（固定 UUID …0001）+ ADMIN + admin 用户，ON CONFLICT 幂等 |
| V4 | p3 | SUPER_ADMIN 角色 + jwc.demo/ops.demo 演示账号，口令 `Demo@2025` |
| V5 | p5 | 主数据 seed：6 科目/4 教室/4 班级/6 教师 + 班主任双向绑定 + M2M |
| V6 | p6 | `schedule_run.status` queued→pending，新增 infeasible 态（DROP/ADD CHECK） |
| V7 | p8 | `ai_call_log` 加 masked_input/output，purpose 加 refine、status 加 schema_invalid/timeout |
| V8 | p9 | `schedule_run` 加 revision/revision_cursor + 新表 schedule_revision + notification |
| V9 | p10 | (18KB) 运营表 ip_rule/security_event/backup_record/announcement + 组织改名天津示范校 + 6 兄弟租户 + 报表演示完整排考链路 seed |
| V10 | p13 | UAT 账号 admin/`Super001!!!`(SUPER_ADMIN)、tenant.test/`Tenant001!!!`(ADMIN) |
| V11 | p14 | 新表 notification_read（每用户已读态）+ 未读计数索引 |
| V12 | p14 | teacher 加 homeroom_class_code（解决导入顺序致班主任-班级绑定丢失、班主任被排进本班） |
| V13 | p15 | app_user 加 deleted_at 软删标记 + 索引（注释明确解释为何不用部分唯一索引：会破坏多处 ON CONFLICT (org_id,username) 约束推断） |

**演进模式**：V2 大爆炸后，V6-V13 均为窄迁移——要么 ALTER 加列/换 CHECK 枚举，要么加运营/通知新表，要么纯 seed。所有 seed 用固定 UUID + `ON CONFLICT DO UPDATE/NOTHING` 做到可重复幂等。状态机全靠 CHECK 约束在库层兜底，枚举演进只能 DROP+ADD CHECK。

### 4.3 配置层

- `application.yml`（profile 默认 dev）：datasource 多层 env 回退、flyway、jpa（`ddl-auto=none`、`open-in-view=false`）、redis + `jkxt.*` 自定义全部 env 占位。
- `application-saas.yml`：`disable-demo-accounts=true`、`cookie-secure=true`、`strict-production-checks=true`、`external-sms=true`。
- `application-private.yml`：`mode=private`、SMS 强制关、LLM/通知默认 mock/inbox 安全回退、`cookie-secure=false`（内网 http）。
- `logback-spring.xml`：结构化 kv 日志（含 `%X{traceId}`），单 CONSOLE appender，INFO 级。
- **缺 `application-dev.yml`**：dev 直接复用基线 `application.yml`。

---

## 五、前端架构

### 5.1 无打包器组织

`Dashboard.html` 单入口，`<head>` 内 `:root` 定义全部设计 token（CSS 变量）+ 响应式外壳 CSS；`<body>` 底部 5 个 vendor script（Chart/xlsx/React18.3.1 dev/ReactDOM/Babel）后，按**严格依赖顺序**串行加载 ~35 个 `text/babel` 脚本：`icons → overlay → common → super-ui → charts → tweaks → … → api → pages → app`，每个 src 带 `?v=<phase>` cache-bust。

**核心机制**：全部文件共享一个全局作用域，用 `Object.assign(window,{...})` / `window.XxxPage` 注册、bare-global 引用取代 ES 模块。**加载顺序即依赖图**（基元必须先于用它的页面）。启动：`app.jsx` 末尾 `ReactDOM.createRoot(#root).render(<App/>)`。

### 5.2 核心五块

- **`src/app.jsx`**（路由中枢）：`PAGE_MAP(pageId→{Comp,title,mode})`；`window.navigateTo` 写 localStorage 并派发 `jkxt:nav`；App 做 authChecked/loggedIn 门禁、admin↔super 双模式、`PageTransition`（PPT 式滑动叠层）、挂 OverlayHost/TweaksPanel/OnboardingModal、把 tweaks 同步成 CSS 变量与 `window.__jkxtDemo`。
- **`src/api.js`**（`window.api`）：`defaultBase()` 按 location 端口自动推导 API 源（:8000 静态→127.0.0.1:8080；代理端口→同源），可被 `localStorage['jkxt:apiBase']`/`window.JKXT_API_BASE` 覆盖；`normalizeError` 把后端 `{code,message,details,traceId}` 转带字段 Error；**401 派发 `jkxt:api-unauthorized`**；含 get/post/put/patch/del/upload/eventSource/webSocket。
- **`src/overlay.jsx`**（`window.ui`）：模块级 `_ui` 单例 + 订阅者模式，`toast`（自动 3.5s）、`confirm/modal/drawer` 返回 Promise（可 await）；`useDialogA11y` 提供 Esc 关闭/焦点陷阱。
- **`src/common.jsx`**：共享 UI 基元库（Button 5 变体 3D、PageShell、DataTable 含全选/分页、FilterBar、EmptyState 等），末尾 `Object.assign(window,{...})` 导出。
- **`src/tweaks-panel.jsx`**：实时改 CSS 变量的调参面板 + 演示开关，通过 `postMessage(__edit_mode_set_keys)` 让外部 edit-mode host 回写磁盘 `EDITMODE` 块。

其他：`icons.jsx`（自绘 SVG）、`charts.jsx`（Chart.js 封装）、`topbar.jsx`（⌘K 命令面板 + 通知抽屉 + 租户切换器）、`sidebar.jsx`（240px 侧栏，ADMIN/SUPER 两套分组）。

### 5.3 路由与状态

**状态路由而非 URL 路由**：`PAGE_MAP` + localStorage（`jkxt:page/mode`）+ CustomEvent（`jkxt:nav/jkxt:mode`）事件总线。代价：无深链接、无浏览器前进后退、刷新靠 localStorage 复原。会话流：App 首帧调 `GET /auth/me`，成功 `applySession`，401/404 视为未登录静默进 LoginPage；401 全局事件由 App 单点监听清会话回登录页。

### 5.4 页面清单及接后端状态

| 页面 | 职责 | 接后端状态 |
|---|---|---|
| `pages/quick.jsx`（1105 行编排器） | 一键排监考 4 步状态机 | **真接**（backend-first + 降级；排班结果强制走后端，前端不再伪造） |
| `pages/quick-step1~4.jsx` | 各步纯 props 消费 | step1 真下载模板 blob；step4 双模式（后端 href / 浏览器 xlsx） |
| `pages/quick-engine.jsx`（`window.JKXT`） | SheetJS 解析/校验/贪心排班/导出/本地任务库 | **贪心排班已成死代码**（强制后端后），仅解析/校验/导出仍用 |
| `pages/people.jsx` | 监考人员管理 | **完全真接**（list/CRUD/import）；逐场明细无接口如实置空 |
| `pages/classes-subjects.jsx` | 班级+科目双资源 | **真接**，与 people 同构 |
| `pages/classrooms.jsx` | 考场 | **真接** |
| `pages/edit.jsx` | 拖拽改派 | **真接**（check-conflicts 预检 + 乐观锁 PATCH + undo/redo/publish）；"AI 优化"仍是 stub |
| `pages/task-detail.jsx` | 任务详情 4 tab | 优先真接 → 本地 → mock；AI 评分卡/进度% 写死展示位 |
| `pages/tasks-page.jsx` | 任务总览 | **真接**，不再注入写死演示任务 |
| `pages/ai-policy.jsx` | AI 策略 | **真接**（providers/refine/parse），如实透传 enforcementWarnings |
| `pages/stats-supervise.jsx` | 监考次数统计 | **真接**，仅 null 时回退示例并打标 |
| `pages/history.jsx` | 历史统计 | 部分真接；表格行 payload=null 时仍回退 MOCK_HISTORY（口径不完全一致） |
| `pages/super.jsx`（2189 行） | 超管 7 子页 | 读**真接**（失败回退 mock），写操作无 api 时报错而非伪成功 |
| `pages/settings.jsx` | 信息设置 | `/auth/me` + 改密真接，多数表单只读 |
| `pages/dashboard.jsx` | 仪表盘外壳 | 新建任务 modal 真接，主体组件仍多为示例 |
| `pages/login.jsx`（1676 行） | 登录 | 真接（认证逻辑仅 ~100 行，其余为装饰视觉） |

**诚实化（去 mock）贯穿全线**：注释密集标注迭代 9/10/11 修复——刻意区分"后端真实 / 浏览器本地真实 / 示例模拟"三态并在 UI 上如实标注。

---

## 六、三种部署模式与运维

### 6.1 四形态对比

| | dev | saas | private | public |
|---|---|---|---|---|
| compose | `docker-compose.yml`(canonical) | 同 + `.env.saas` | `docker-compose.private.yml` | `docker-compose.public.yml` |
| profile | dev | saas | private | **private**（+Caddy） |
| 前门 | 无，直连 8080 | nginx/Vercel | nginx 同源反代 | Caddy(80/443 TLS)→nginx |
| 端口 | 8080/5432/6379 | 同 dev | 偏移 18180/18081/15432/16379 | pg/redis 不暴露宿主 |
| 租户 | 多租户 | 多租户（X-Tenant-Id） | 固定 org 单租户 | 固定 org 单租户 |
| captcha-debug | true | false | false | 硬拒 |
| SMS | — | external-sms=true | 关闭 | 关闭 |

`docker-compose.dev.yml` 仅 4 行，用 `include` 引 `docker-compose.yml` 避免定义漂移。`deploy/nginx/private.conf`（private/public 共用）：`/api/v1→app:8080`、静态托管、30m 上传上限。`deploy/caddy/Caddyfile`：自动 TLS + nosniff/Referrer-Policy 安全头。

### 6.2 运维流："文档→脚本→证据"

- `scripts/deploy`（27 个 PowerShell）：private（init/backup/restore-drill/smoke）+ public 重型门禁族（build-release/preflight/cutover-pack/T3-approval/evidence/verify-remote/vercel-rewrite）。
- `scripts/p11`：k6 压测、Playwright e2e、Testcontainers、security-scan、global-fullflow-e2e。
- **public 割接是一条长门禁链**：`build-public-release`（带 sha256 manifest）→ test → preflight → report-cutover-readiness（机密只报 present/placeholder）→ prepare-cutover-pack → request-t3-approval → invoke-launch-gates(-DryRun)→ 只有带确认短语 `START JKXT PUBLIC PRODUCTION` 才真启。`init-public` 硬拒 example 域名/占位机密/captcha-debug/非安全 cookie。
- `.circleci/config.yml`：`backend-test`（jdk21+pg+redis 跑 mvnw test）、`public-release-readiness`（在假目标 `jkxt-public-validation.invalid` 上**反向断言** T3 审批与 Vercel rewrite 始终 blocked，防放行门被绕过）。
- 备份：一次性 postgres 容器 `pg_dump` + sha256 + restore-drill 演练，目标 **RPO≤1h / RTO≤4h**，不引入独立备份组件。

### 6.3 机密卫生

`.gitignore`/`.vercelignore` 只放行 `*.example`；release manifest 带 sha256；public preflight 强制前端 vendor 本地化 + 拒绝跨源脚本；`.vercel/project.json` 只含 id 不含 token。

### 6.4 文档分层

`08-BACKEND-DEV-SPEC`（后端权威规格）→ `09`（路线复核着色决策）→ `10/11`（实施计划 + 可粘贴提示词手册）→ `DEPLOYMENT.md`（运维 runbook）/ `CONNECTED-PRODUCTION-DEPLOYMENT.md`（"连接后端才算完成"契约）/ UAT-CHECKLIST → `12-真实落地整改路线方案`。用 `12-DEV-MATERIALS-MANIFEST` 做"该读哪份"索引。

---

## 七、技术亮点

1. **求解可靠性经实战淬炼**：8-worker portfolio + 变种子重试绕 CP-SAT 对可行模型偶发 UNKNOWN、严禁 `stopAfterFirstSolution`——是 memory 记载的核心修复（30/30 稳定）。字典序 minimax 目标数学保证公平层不被牺牲。
2. **软约束优先哲学**：同年级回避/班主任场次上限/任课避本班从硬约束改成目标惩罚，杜绝 K-12 高班主任占比下把可行排班误判 INFEASIBLE。
3. **编辑与求解同口径**：`ScheduleEditService.conflicts()` 的 H1/H2/H3 与 solver `eligible()` 一致，杜绝路径漂移。
4. **反 AI 幻觉的诚实工程**：AI 只解析意图（`solverAuthority=OR_TOOLS` 强校验），`enforcementWarnings` 主动告知哪些规则求解器不执行，schema_invalid 不落 dsl，有集成测试锁死契约。
5. **通知诚实口径**：logging-only 桩记 `logged_only` 而非 `delivered`，站内区分实投广播条数与被涉及教师数，绝不虚假送达。
6. **导入"守卫先于破坏"**：考试重导入先只读盘点有效占比过半才销毁旧排考，杜绝一次列错位静默抹掉已发布排班；班主任导入顺序无关回填（V12）。
7. **两道安全默认防线**：`ProductionSecurityGate` fail-loud 拒绝启动 + `DemoAccountSecurityGuard` saas 禁用公开密码种子账号。
8. **迭代注释可追溯性极强**：scheduling 子系统几乎每个非平凡决策都有"迭代 N Pk"注释记录踩坑动机，solver 无状态纯函数、service 管状态、api 薄，分层干净。
9. **报表消 N+1**：`teacherHistory` 用 `ROW_NUMBER` 窗口批量取近场，`activeRunFilter`（`distinct on` 最近 succeeded run）避免课酬 3× 叠加。
10. **单构建多形态 + 重型割接护栏**：同一镜像 env+profile 投影四态；生产割接证据门禁 + 确认短语 + T3 审批 + CI 反向证明。

---

## 八、现存技术债 / 风险 / 待办（汇总去重排序）

### P0 / 高风险（安全与授权）

1. **细粒度 permissions "算而不用"（前后端授权粒度不一致）**：登录时读出 permissions 注入 SecurityContext 并下发前端，但全代码库只有 2 处授权表达式（`SecurityConfig` 与 `AdminPlatformController`）且都是 `hasRole('SUPER_ADMIN')`，无任何 `hasAuthority(permission)` 生效。服务端实质只做角色粗粒度鉴权，权限点仅驱动前端展示——**潜在越权面**。
2. **超管授权单点**：全平台运营台仅靠 `AdminPlatformController` 类级 `@PreAuthorize` 一道闸，service 层无二次校验，租户/账号级操作依赖 method 参数正确传入。
3. **明文演示口令随代码入库**：V3/V4/V5/V9/V10/V13 seed 含 `Demo@2025 / Super001!!! / Tenant001!!!` bcrypt hash；私有化默认 `disable-demo=false`，漏设即复活。生产前必须按 UAT-CHECKLIST 关 captcha-debug、改口令。
4. **CSRF 禁用 + Cookie 认证**：安全性完全依赖 SameSite（默认 Lax）。若某部署为跨站前端把 same-site 配成 None，状态改变型请求将失去 CSRF 防护；`cookie-secure` 默认 false 仅 strict 生产门会拦。
5. **登录限流仅按账号锁定**（5 次/30min）+ 验证码，无按 IP/全局节流，攻击者可对大量不同用户名撞库；`clientIp` 无条件取 `X-Forwarded-For` 首段可伪造（当前仅用于审计，影响面低）。
6. **用户名全局唯一依赖**：`findFirstByUsernameIgnoreCase` 跨租户，saas 下同名（如 admin）只命中第一个，与 app_user 租户模型存在张力，需确认 DB 唯一约束兜底。

### P1 / 中风险（正确性与性能）

7. **会话内 stale authz**：role/permissions 登录时快照进 Redis，管理员在线期间改其角色需 re-login 才生效。
8. **求解耗时未压测**：`CpSatScheduleSolver` `setMaxTimeInSeconds(60)` × 8 worker × 重试 3 次 = 单次最坏 180s，跑在 core2/max4/queue32 线程池；benchmark 仅 200 场 1 考场 2 教师量级偏小，大 case 下线程池可能被占满，CallerRunsPolicy 会让求解在 Web 调用线程就地跑。
9. **主数据列表 N+1**：`toTeacherDto/toSubjectDto/toSchoolClassDto` 每条记录跑 2-4 个 jdbcTemplate 子查询（教师已 hoist allowanceUnit，但 subjects/supCount/allowance 仍逐条查询），大 org 下延迟线性增长。
10. **buildFixtureFromTask 查询放大**：多次 `jdbcTemplate.queryForList` 按 `org_id` 拉全表 exam_room/exam_class/teacher_subject/subject 到内存 join，未按 taskId/examId 收窄。
11. **platform by-id 读取全量 list 后内存 filter**：`tenantById/accountById/announcementById` 规模变大是隐性 O(n) 全表扫。
12. **前端轮询 40s 硬超时**：schedule-jobs 进度用 80×500ms 轮询（api.js 已提供 eventSource/webSocket 未用），大规模求解可能不足。
13. **导出/通知在发布主事务内同步扇出**：真实 SMTP/SMS 接入后大批监考会拉长发布事务耗时。
14. **审计覆盖不完整**：masterdata 的 `*_UPDATED` 与 `SUBJECT_CREATED/UPDATED` 缺失审计；platform 审计 ip 恒为 `-`、securityOverview score 是启发式、backups size/checksum 写死占位。
15. **校验层多为 warn 不阻断**：examDate/startTime 非法仅告警，真正阻断落在 commit 的 parse 返回 null→skip，"校验通过"≠"该行会落库"；exam 50% 有效占比阈值是启发式守卫，会误伤合法脏数据重导。
16. **口径不一致**：`subject_avoid` 在 `enforcementWarnings`/`fallbackDsl`/`resolvePolicy` 三处口径需交叉核对（warning 与实际求解开关轻微错位风险）。

### P2 / 技术债与卫生

17. **MasterDataService 800 行**承担 controller-service 双职责（字段校验+业务+SQL+审计+DTO），拆分成本渐增。
18. **前端写死展示位易误读为真实**：task-detail "AI 评分 96/100"、整体进度%、"距离开考 1天18时"、people "本年级均值 14.2 场"、津贴=supervisions×100 均硬编码；`history.jsx` 表格行 payload=null 时回退 MOCK_HISTORY 与同页空态策略不一致。
19. **前端死代码**：`quick-engine.generateSchedule` 贪心排班在强制后端后已死；`edit.jsx` "AI 优化"仍是 P10 stub。
20. **加载顺序即隐式契约（脆弱）**：新增文件须手动插正确位置，`?v=<phase>` cache-bust 需手动推进；混用两套 React 解构别名（useState/useStateTb/useStateSb）规避全局重名。
21. **成本核算占位**：real LLM 分支 `costCents` 恒 ZERO；`OpenAiCompatibleLlmClient` 每次 chat new HttpClient 无连接池，重试对 4xx 也无谓重试；`extractJsonObject` 首'{'到末'}'裸截取有截错风险；MODELS 列表硬编码。
22. **DTO 冗余双字段**（uuid+id、teacherNo 两次、code+id）是前端 mock 期历史包袱。
23. **关联表缺反向索引**：teacher_subject/school_class_subject 按 subject_id 反查无索引，大数据量走 seq scan；notification_read 的 user_id/org_id 裸 uuid 无 FK。
24. **仓库卫生债**：`JKXT-V2.0-源码/` 是漂移前端快照（勿改）；`backend;D` 空残留目录；根目录搁 21MB 构建产物 zip；两份 `10-*`、两份 `12-*` 文档冗余，NestJS 路线文档已被 08 覆盖但仍在库。
25. **"public"命名易误导**：面向公网的 `docker-compose.public.yml` 跑 private 模式（单固定租户），非 SaaS。
26. **部分文档自认 stale**：`12-整改路线`纠正 active-context 过时；文档-代码存在时间差，需以最新 commit 为准。
27. **fairness 占位**：`corridorShare/notesApplied` 恒 0（楼道监考/特殊备注尚是占位）；timeline 颜色/行号纯展示派生。
28. **运维脚本深度绑定 Windows PowerShell**（27 个 .ps1 + 本机 Docker Desktop），Linux 主机运维需另行适配。

### 已修复（历史，供参考）

- P1-1：写方法 role 门（此前只读角色也能写）。
- Epic 1.3-1.5：写死 fixture + 导入不落库（头号根因）。
- 一键排考 UNKNOWN 随机失败（portfolio+重试）。
- 迭代 11 P1：schema_invalid 缺 dsl 键误当空 DSL 解析成 HARD。
- 迭代 12：异步 worker 崩溃、并发发布重复通知、teacherHistory N+1。

---

## 九、面向新工程师的"如何上手"建议

### 9.1 先读什么（1-2 天建立心智模型）

1. **`CLAUDE.md`**（仓库指南）+ **`docs/DEPLOYMENT.md`**（运维 runbook + X-Tenant-Id 头契约表）：理解三/四种部署形态与仓库布局（哪些是 agent 工作文件/漂移快照）。
2. **`docs/08-BACKEND-DEV-SPEC.md`**：后端权威规格；**`docs/12-真实落地整改路线方案.md`**：了解已知硬伤与已修状态（读时对照最新 commit，文档有时间差）。
3. **`V2__p2_core_schema.sql`**：一次看懂全库表关系——这是理解一切数据流的地基。

### 9.2 跑起来（本地 dev）

- 用 `docker-compose.yml`（canonical dev 基座：postgres16+redis7+backend，8080/5432/6379，captcha-debug=true）。`scripts/deploy/init-*.ps1` 缺 `.env` 时会从 `.example` 复制后 `docker compose up --build`。
- **踩坑提醒（来自 memory）**：验证 Docker 重建必须显式 grep 编译错误——失败的 `up --build` 会让旧镜像顶替致测到陈旧代码；从 agent shell 启动 Docker Desktop 前需 `$env:ProgramData='C:\ProgramData'`。
- 前端**必须走 HTTP**（Babel XHR 拉 .jsx，`file://` 会因 CORS 失败）。nginx/静态服务托管 `Dashboard.html`。
- 登录用 dev 演示账号（口令在 Flyway seed 注释里）；首登有 7 步引导。

### 9.3 跑通一遍主流程（建立端到端直觉）

按 §2 的顺序在 UI 里手动走一遍：登录 → 一键排考页导入示例三表 → 校验 → 排考（看 SSE 进度）→ 编辑页拖拽改派 → 发布 → 报表页看统计。对照后端日志（结构化 kv 含 traceId）观察每一跳。

### 9.4 关键约定（避免踩雷）

- **改后端**：任何 service 新方法首行必须 `TenantContext.getRequired()` 并把 orgId 拼进所有查询（无 RLS 兜底，忘了就跨租户泄漏）；所有入站外键 UUID 走 `findByOrgIdAndId().orElseThrow` 归属校验。
- **改 schema**：只加 `V<n>__p<phase>_*.sql`，绝不改已应用脚本；`ddl-auto=none`，Hibernate 不会帮你建表；枚举演进要 DROP+ADD CHECK；seed 用固定 UUID + ON CONFLICT 保持幂等。
- **改前端**：新文件必须手动插到 `Dashboard.html` 正确加载位置（依赖之后、app.jsx 之前），并推进 `?v=<phase>` cache-bust；用 `Object.assign(window,{...})` / `window.XxxPage` 注册；注意全局作用域重名（已有 useStateTb/Sb 等别名惯例）。
- **改求解器**：编辑侧 `conflicts()` 与 solver `eligible()` 必须同口径；绝不加 `setStopAfterFirstSolution`；软约束优先于硬过滤。

### 9.5 最值得先深入的两个文件

- **`scheduling/service/ScheduleJobService.java`** + **`solver/CpSatScheduleSolver.java`**：系统的心脏，注释密度最高、可追溯性最强，读懂它就读懂了整个产品的价值主张。
- **`tasks/service/ExamTaskService.java`**（728 行门面）：它把 importer/scheduling/masterdata/notify 串成一条流水线，是理解模块协作的最佳切入点。

### 9.6 上手第一个任务的安全区

优先从**主数据三页（people/classes-subjects/classrooms）**或 masterdata 后端入手——它们已完全接线、结构同构、影响面可控，是熟悉"Controller 薄壳 + Service + 租户隔离 + DTO 派生"范式的最佳练手区。避免一上来就碰 scheduling 求解器或 platform 授权单点这类高风险区。

---

*本文基于 10 路子系统精读结果合成，忠实于精读结论。文档与代码可能存在时间差，涉及具体行号/状态时请以最新 commit 为准。*