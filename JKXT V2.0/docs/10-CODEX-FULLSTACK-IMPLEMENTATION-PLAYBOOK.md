# 10 · Codex 全栈开发接力实施手册

日期: 2026-06-05  
目标: 给后续 Codex 全栈开发线程使用,逐步把 JKXT V2.0 从高保真前端原型打通为真正可运行的 AI 赋能高效排监考系统。

## 0. 一句话结论

后续不要让 Codex 一口气“重写全部项目”。最佳打法是 **一阶段一线程 / 一分支 / 一验收门**:先让本地服务跑起来,再接认证和租户,再接基础数据,再前移 OR-Tools 小样本,最后打通 Quick -> Task -> Edit -> Publish -> Stats -> Super 的真实闭环。前端视觉保持,只做 API adapter、状态接管、真实数据、拖拽改派这一处已批准的新交互。

## 1. 后续 Codex 必读上下文

每个后续开发线程开始时都必须先读这些文件:

1. `docs/08-BACKEND-DEV-SPEC.md`  
   主实施规格。以 Java 21 + Spring Boot 3.3 + PostgreSQL 16 + OR-Tools + LLM 辅助为准。
2. `docs/09-SECOND-ROUND-BACKEND-DOC-REVIEW-ANNOTATED.md`  
   二轮/三轮路线收敛、前端小改边界、代码级契约缺口。
3. `docs/BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md`  
   页面到服务的架构总览和第一轮联调参考。若与 08 冲突,以 08 为准。
4. `uploads/07-UI-DEVELOPMENT-SPEC.md`  
   前端生产化目标、DataSlot/四态/桌面优先等规范。
5. `src/pages/quick*.jsx`, `src/pages/quick-engine.jsx`, `src/pages/login.jsx`, `src/app.jsx`, `src/pages/edit.jsx`, `src/pages/super.jsx`, `src/topbar.jsx`  
   当前最关键的前端联调面。

## 2. 总体技术路线

| 层 | 决策 | 说明 |
|---|---|---|
| Backend | Java 21 + Spring Boot 3.3 | Modular Monolith,便于私有化交付和后续拆服务 |
| DB | PostgreSQL 16 + Flyway | `org_id` 多租户、强约束、JSONB、统计视图 |
| Cache/Job | Redis | captcha、短 TTL、job progress、锁、通知队列 |
| Scheduling | OR-Tools CP-SAT Java binding | 求解器主导,保证硬约束和可复现 |
| AI | Spring AI 或自研 `LlmClient` | LLM 只做 NL->DSL、解释、修复建议、总结 |
| Frontend | 现有 UI 最小改造 | 新增 `src/api.js`,保留视觉和页面骨架 |
| Auth | httpOnly session/refresh cookie 或 BFF | `localStorage` 只作开发 hint,生产不可保存长期 token |
| API | 外部统一 `/api/v1` | 内部 `/api` 示例必须由网关/代理改写 |
| Deploy | SaaS + 私有化双模式 | K8s/RDS/Redis 或 Docker Compose 离线包 |

## 3. Codex 执行总协议

后续每个 Codex 线程都按这个协议跑:

1. 先读上下文,不要凭记忆改。
2. 建立或更新 `task_plan.md` / `findings.md` / `progress.md`。
3. 明确本线程只做一个阶段,避免跨阶段大爆炸。
4. 先写测试或验收脚本,再写实现。
5. 每次提交前跑本阶段验证命令。
6. UI 不大改;除 `edit.jsx` 真拖拽外,不引入新视觉结构。
7. 生产安全优先:LLM key、长期 token、隐私数据不进浏览器。
8. 完成前必须给出“证据清单”:文件、命令输出、浏览器截图或 API 返回。

## 4. Master Prompt: 发给后续 Codex 的总提示词

```text
你是 Codex,作为资深全栈工程师接力开发 JKXT V2.0。请先读取并遵守:
- docs/08-BACKEND-DEV-SPEC.md
- docs/09-SECOND-ROUND-BACKEND-DOC-REVIEW-ANNOTATED.md
- docs/BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md
- uploads/07-UI-DEVELOPMENT-SPEC.md
- AGENTS.md / CLAUDE.md 中的监督、计划、review gate 要求

当前总目标:将现有静态高保真前端原型逐步打通为真实可运行的 AI 赋能排监考系统。后端以 Java 21 + Spring Boot 3.3 + PostgreSQL 16 + Redis + OR-Tools CP-SAT + LLM 辅助为主路线。外部 API 统一 /api/v1。前端视觉整体不大改,只新增 API adapter、真实状态、权限/租户恢复、job 进度桥接;edit.jsx 真拖拽改派是唯一已批准 C 类新交互。

请先做:
1. 恢复上下文和当前工作树状态。
2. 将本阶段目标写入/更新 task_plan.md、findings.md、progress.md。
3. 只实现我指定的阶段,不要顺手重构无关 UI 或全项目。
4. 先定义验收证据,再实施。
5. 完成后运行测试/验证命令,刷新 code-review-graph 或做同等审查。
6. 最终报告必须列出:改动文件、运行命令、通过/失败证据、剩余风险、下一阶段建议。

现在执行阶段:【把这里替换为下面某个阶段提示词】。
```

## 5. 阶段路线与可复制提示词

### P0 · 基线与工程启动

目标:让后续开发线程明确当前状态,不误走旧路线。

交付:
- 当前可运行前端方式记录。
- 后端目录规划。
- `.env.example` 草案。
- 本地开发端口表。
- 第一批 smoke test 清单。

提示词:

```text
阶段 P0:请只做基线与工程启动准备。读取 08/09/集成指南/前端入口,输出并落地 docs/DEV-RUNBOOK.md 或更新现有运行文档。明确:
1. 前端当前如何启动和验证。
2. 后端将放在 backend/ 还是 services/api/。
3. Docker Compose 需要哪些服务:postgres、redis、app、nginx 可选。
4. 外部 API base 固定为 /api/v1。
5. 哪些前端文件第一批接 API: src/api.js、login.jsx、app.jsx。
不要写大量业务代码。只做工程启动文档、目录建议、最小 env/runbook,并给出下一阶段 P1 的命令清单。
验证:PowerShell 下列出文件结构,确认 docs/DEV-RUNBOOK.md 存在且包含端口、环境变量、启动命令和验收步骤。
```

### P1 · Spring Boot + Docker Compose 骨架

目标:后端服务、PostgreSQL、Redis 能在本地跑起来。

交付:
- `backend/` Spring Boot 3.3 工程。
- `docker-compose.yml`。
- `backend/.env.example` 或根目录 `.env.example`。
- `/api/v1/health`, `/api/v1/version`, actuator health。
- OpenAPI UI。

提示词:

```text
阶段 P1:请创建或补齐 Spring Boot 3.3 后端骨架,只实现工程可运行与健康检查。技术路线必须匹配 docs/08-BACKEND-DEV-SPEC.md:Java 21,PostgreSQL 16,Redis,Flyway,Spring Security 基础占位,OpenAPI。

具体要求:
1. 创建 backend/ Maven 或 Gradle 工程,优先选择 repo 当前最易维护的方式。
2. 加 docker-compose.yml:postgres,redis,backend;端口写清楚。
3. Flyway 创建最小 schema history 可运行。
4. 实现 GET /api/v1/health 和 GET /api/v1/version。
5. OpenAPI 可访问。
6. 不做业务表大建模,不接前端。

验证命令:
- docker compose up -d postgres redis
- 在 backend 下运行 `.\mvnw.cmd test` / `.\gradlew.bat test`(PowerShell),或 `./mvnw test` / `./gradlew test`(bash)
- 启动后 curl http://localhost:8080/api/v1/health
- curl http://localhost:8080/v3/api-docs 或打开 swagger

完成后报告命令输出和端口。
```

### P2 · 数据库模型与 Flyway 种子

目标:把 08 文档核心表落成迁移,并能创建 demo 租户/账号。

交付:
- organization、user、role、teacher、classroom、class、subject、exam_task、uploaded_file、import_batch、schedule_run、assignment、audit_log、ai_call_log。
- demo tenant + admin seed。
- Testcontainers 或集成测试。

提示词:

```text
阶段 P2:请实现数据库基础模型与 Flyway migration。以 docs/08-BACKEND-DEV-SPEC.md §3 为准,优先做能支撑 S1-S3 的表,不要一次把所有超管功能塞满。

必须包含:
1. org_id 多租户字段和基础索引。
2. demo organization、admin user、term seed。
3. teacher/classroom/class/subject/exam_task/import_batch/schedule_run/assignment 基础表。
4. audit_log、ai_call_log 基础表。
5. repository/entity 层和最小集成测试。

验证:
- 后端测试通过。
- Flyway clean/migrate 可在空库成功。
- psql 或测试能查到 demo tenant/admin。
```

### P3 · 认证、租户、RBAC

目标:前端登录不再靠本地假状态,服务端返回真实用户、租户、权限。

交付:
- captcha、login、logout、me。
- httpOnly cookie/session 或 BFF 认证方案。
- `TenantContext`。
- `/api/v1/auth/me` 返回 `user/org/term/permissions/onboarded`。

提示词:

```text
阶段 P3:请实现认证、租户上下文和 RBAC 最小闭环。必须遵守 09 文档:生产不使用 localStorage token 作为长期凭证。前端可保留 jkxt:auth 作为开发首屏 hint,真实身份以 /api/v1/auth/me 为准。

实现:
1. POST /api/v1/auth/captcha
2. POST /api/v1/auth/login
3. POST /api/v1/auth/logout
4. GET /api/v1/auth/me,返回 user、org、term、permissions、onboarded
5. TenantContext + 审计基础
6. 登录失败、锁定、未认证、无权限错误码

验证:
- 后端单元/集成测试覆盖 login/me/logout。
- curl 登录后能带 cookie 访问 /auth/me。
- 未登录访问业务接口返回 401。
- 不把长期 token 写入浏览器 localStorage。
```

### P4 · 前端 API Adapter 与登录接管

目标:现有前端视觉不变,登录和启动态接真实 API。

交付:
- `src/api.js`。
- `Dashboard.html` 引入顺序正确。
- `login.jsx` 接 `/auth/login`。
- `app.jsx` 启动接 `/auth/me`。
- loading/error/data 状态。

提示词:

```text
阶段 P4:请只做前端最小 API adapter 和登录接管,不要重做登录页视觉。读取 src/pages/login.jsx、src/app.jsx、Dashboard.html、docs/09... 第 4/9 节。

实现:
1. 新增 src/api.js,外部 base=/api/v1,支持 cookie/session、错误信封、上传、SSE/WS 占位。
2. Dashboard.html 在页面脚本前加载 src/api.js。
3. login.jsx handleLogin 改为调用 window.api.post('/auth/login',...)。
4. app.jsx 启动时调用 /auth/me 恢复 user/org/term/permissions/onboarded。
5. jkxt:auth 只作开发 hint,不可作为真实权限。
6. 登录失败展示现有视觉体系内的错误状态。

验证:
- 浏览器打开登录页视觉无明显变化。
- 正确账号可登录进入 dashboard。
- 刷新页面后 /auth/me 恢复状态。
- 未登录时回到 login。
- 控制台无新增致命错误。
```

### P5 · 主数据 CRUD 与导入校验

目标:教师、考场、班级、科目从 mock 切到真实 API,并支撑 Excel 导入校验。

交付:
- `/teachers`, `/classrooms`, `/classes`, `/subjects` CRUD。
- `/import/*`, `/import/validate`, templates。
- 前端 People/Classrooms/Classes-Subjects 接真实数据。
- 默认整页返回,不改 DataTable。

提示词:

```text
阶段 P5:请实现主数据真实 API 和前端接管。遵守 09 §9.6:主数据不做服务端分页,默认整页返回,保留现有 DataTable 结构。

后端:
1. teachers/classrooms/classes/subjects CRUD。
2. Excel 模板下载和导入校验,返回前端 quick-step2 可消费的 severity/category/overview/counts/cats/items 结构。
3. 行级错误、列级错误、warn/info 分级。

前端:
1. people.jsx/classrooms.jsx/classes-subjects.jsx 从 mock 切换到 API。
2. 保留表格、筛选和视觉。
3. 补 loading/empty/error/data 四态。

验证:
- 后端测试覆盖 CRUD 和导入校验。
- 前端页面能展示 demo seed。
- 错误导入返回可读行级错误。
```

### P6 · OR-Tools 小样本预研与异步 Job

目标:先证明排考模型可行,不要等到完整 Quick 才发现无解/性能问题。

交付:
- OR-Tools Java binding 可运行。
- 小样本 fixture。
- 无解最小冲突集。
- schedule_run job 状态机。
- STOMP/SSE 进度。

提示词:

```text
阶段 P6:请实现 OR-Tools CP-SAT 小样本预研和异步排考 job 基础。求解器必须是权威分配者,LLM 不决定最终 assignments。

实现:
1. 加 OR-Tools Java binding。
2. 建 fixtures:可行样本、教师不足无解样本、教室容量冲突样本。
3. 实现硬约束 H1-H7 的最小模型。
4. 输出 schedule_run 状态:pending/running/succeeded/failed/infeasible。
5. 输出最小冲突集和 fairness 5 项。
6. 实现 SSE 或 STOMP 进度占位。

验证:
- 单元测试:可行样本生成 assignments。
- 单元测试:无解样本返回 SCHEDULE_INFEASIBLE + conflictSet。
- 200 场以内性能基准记录。
```

### P7 · Quick 一键排监考真实闭环

目标:Quick 四步从浏览器本地引擎切到后端 job,形成真实任务。

交付:
- 创建 task。
- 上传三张 Excel。
- 校验。
- 创建 schedule job。
- 进度页。
- 结果页。
- 导出。

提示词:

```text
阶段 P7:请打通 Quick -> Task -> Detail 的真实闭环。不要重做 quick-step1~4 视觉,只替换数据源和状态。

后端:
1. POST /api/v1/exam-tasks
2. POST /api/v1/exam-tasks/{taskId}/uploads
3. POST /api/v1/exam-tasks/{taskId}/imports/validate
4. POST /api/v1/exam-tasks/{taskId}/schedule-jobs
5. GET /api/v1/schedule-jobs/{jobId}
6. GET /api/v1/exam-tasks/{taskId}/assignments
7. 导出 xlsx/pdf 基础版

前端:
1. quick.jsx 调用 API,本地 quick-engine 仅保留 fallback。
2. quick-step3 接真实进度。
3. quick-step4 接真实 result,必须有 fairness 5 项和 ai.targets teacher/room/slot。
4. tasks-page/task-detail 显示真实任务。

验证:
- 浏览器完整跑:登录 -> Quick 上传/示例 -> 校验 -> 排考 -> 结果 -> 任务详情。
- Playwright 截图保留。
- 后端日志和 DB 中能查到 task/run/assignments。
```

### P8 · LLM AI 赋能策略、解释、修复建议

目标:真正接入 LLM,但保持 solver 主导和安全可控。

交付:
- `LlmClient`。
- provider 配置和用量。
- `/nl-policy/parse`, `/nl-policy/refine`。
- `/ai/providers` 返回配额/用量/平均响应。
- `ai_call_log`。
- PII masking、schema validation、fallback。

提示词:

```text
阶段 P8:请实现 LLM 辅助能力。必须遵守:LLM key 不进浏览器,教师/学生隐私要脱敏,LLM 只做策略解析/解释/修复建议,最终排考由 OR-Tools 和人工确认。

实现:
1. 后端 `LlmClient` 接 OpenAI-compatible endpoint，当前批准配置为 `llm-token / claude-sonnet-4-6`，主备端点由 `JKXT_LLM_BASE_URL` 与 `JKXT_LLM_FALLBACK_BASE_URL` 注入。
2. /api/v1/nl-policy/parse:自然语言 -> 约束 DSL。
3. /api/v1/nl-policy/refine:优化用户策略表达。
4. /api/v1/ai/providers:返回 provider 状态、配额、用量、平均响应。
5. ai_call_log 记录 model、latency、tokens、cost、status、masked input/output。
6. JSON schema 校验、重试、超时、降级到 CP-SAT。

前端:
1. ai-policy.jsx 死按钮接 /nl-policy/refine。
2. Provider 用量显示接真实 /ai/providers。
3. AI 策略提交后复用 quick-step3/4 进度和结果。

验证:
- fake/mock provider 测试通过。
- 真实 provider 可选,没有 key 时不阻塞本地测试。
- schema 不合格不会落库。
```

### P9 · Edit 真拖拽改派、冲突检查、发布通知

目标:实现唯一批准的 C 类前端新交互,并保证后端冲突校验。

交付:
- `PATCH /tasks/{id}/schedule`。
- `POST /tasks/{id}/schedule/check-conflicts`。
- revision/undo。
- edit.jsx 拖拽候选栏。
- publish + notification。

提示词:

```text
阶段 P9:请实现 edit.jsx 真拖拽改派和冲突检查。这是唯一已批准的 C 类前端改动,但仍要保留现有页面骨架和视觉风格。

后端:
1. PATCH /api/v1/tasks/{id}/schedule 增量改派。
2. POST /api/v1/tasks/{id}/schedule/check-conflicts。
3. revision/undo/redo 基础。
4. POST /api/v1/tasks/{id}/publish。
5. notification/audit_log 写入。

前端:
1. edit.jsx 增加拖拽交互层和候选教师栏。
2. 放置前/后显示冲突反馈。
3. 0 冲突隐藏 ConflictBanner,N 条循环展示。
4. 发布按钮接真实端点。

验证:
- 拖拽一个监考员后 DB assignment 改变。
- 冲突改派被拒绝或提示。
- 撤销可恢复。
- 发布后任务状态和通知状态更新。
```

### P10 · 报表、历史、Topbar 租户一致性、Super 管理

目标:把 mock/toast-first 平台功能接成真实服务。

交付:
- stats/history/timeline。
- topbar tenants 与 super tenants 同源。
- accounts、audit、security、backup、announcements。

提示词:

```text
阶段 P10:请实现报表、历史、Topbar 租户一致性和 Super 管理真实端点。重点修复 09 §9.3/9.4 的错误值和双租户源。

后端:
1. /api/v1/stats/*, /api/v1/history/*, /api/v1/timeline/*。
2. /api/v1/admin/tenants 作为 topbar 和 super 的唯一租户来源。
3. admin accounts/security/audit/backups/announcements 基础端点。
4. P1-P5 中列出的写死/随机值改由后端返回。

前端:
1. history.jsx 去掉分母 200 写死。
2. people.jsx 津贴由后端 allowance 返回。
3. classes-subjects.jsx 去掉随机场次。
4. timeline.jsx 接真实日期和排考日程。
5. super.jsx 假 toast 操作接真实端点。

验证:
- topbar 切换租户与 super 租户管理列表一致。
- 报表数据刷新不出现随机跳变。
- 审计日志记录高危 mutation。
```

### P11 · 生产安全、测试、性能和可观测

目标:把系统从“能跑”推到“可内测/可试点”。

交付:
- 单元/集成/E2E 覆盖。
- security scan。
- k6 性能。
- structured logs + traceId。
- backup/restore drill。

提示词:

```text
阶段 P11:请做生产硬化和验证门,不新增大功能。根据 08 §10/§11 补测试、性能、安全和可观测。

实现:
1. 后端 unit + integration + Testcontainers。
2. 前端 Playwright:登录、导入、排考、编辑、发布、报表、超管。
3. k6:列表、导入、排考 job、统计。
4. OWASP/ZAP 或等价安全检查:Auth、RBAC、上传、租户越权。
5. traceId、结构化日志、错误信封。
6. backup run + restore drill 文档。

验收:
- 所有测试命令给出输出。
- 关键 E2E 截图存 screenshots/。
- 安全高风险项必须修复或列阻塞。
```

### P12 · 私有化与 SaaS 交付包

目标:服务可在私有化单校和 SaaS 多租户两种模式交付。

交付:
- `docker-compose.private.yml`。
- `deploy.mode=private|saas`。
- env 模板。
- 初始化脚本。
- 运维 runbook。
- UAT checklist。

提示词:

```text
阶段 P12:请完成交付打包。系统必须支持 SaaS 多租户和私有化单校两种部署。不要引入未验证的复杂云组件。

实现:
1. docker-compose.private.yml:app/postgres/redis/nginx/backup volume。
2. deploy.mode=private 时固定 org,禁用外部短信/可配置私有 LLM endpoint。
3. deploy.mode=saas 时启用多租户和 X-Tenant-Id / tenant context。
4. 初始化 demo 校区、管理员和样例数据。
5. docs/DEPLOYMENT.md, docs/UAT-CHECKLIST.md。

验证:
- 一条命令启动私有化环境。
- 登录 demo admin。
- 跑通 Quick 排考闭环。
- 备份和恢复演练记录。
```

## 6. 插件/Agent 能力如何用

### Superpowers / TDD 执行提示词

```text
请用 Superpowers/TDD 风格执行本阶段:先写最小验收测试或 smoke 脚本,让它失败,再实现最小代码让它通过,再重构。每次只推进一个可验证切片。不要直接大面积生成未验证代码。完成后给出 red -> green -> refactor 证据。
```

### Build Web Apps 前端接线提示词

```text
请作为 Build Web Apps 前端工程师接线当前阶段。要求:遵守现有视觉设计,不做营销页或重设计;只在必要位置新增 api adapter、loading/empty/error/data 四态、权限状态和交互接线。用 Browser/Playwright 验证真实页面,截图证明没有视觉破坏和核心流程可用。
```

### Product Design 内测流程提示词

```text
请作为 Product Design reviewer 对本阶段完成后的流程做内测:从学校教务管理员角度走一遍任务,检查是否少了必要状态、错误提示、空状态、权限边界、确认步骤、撤销路径。输出 P0/P1/P2 问题清单,只要求当前阶段必须修的问题,不要扩展成视觉重设计。
```

### Creative Production 视觉保护提示词

```text
请作为 Creative Production reviewer 检查本阶段是否破坏现有登录、纸飞机/熊猫、仪表盘、Quick 向导的高级感和品牌一致性。只允许提出资产遮挡、动效过强、状态反馈不清晰、按钮/图标风格不一致等具体问题。不要建议重做整套视觉。
```

## 7. 每阶段完成审计清单

每个阶段结束前,让 Codex 自查:

| 项 | 必须证据 |
|---|---|
| 目标是否只覆盖本阶段 | `task_plan.md` 状态和最终报告 |
| 代码是否跑起来 | 启动命令 / curl / 浏览器截图 |
| 数据是否真实落库 | DB 查询 / API 返回 / 测试断言 |
| 前端是否没有大改视觉 | 关键页面截图对比 |
| 安全红线是否遵守 | 无长期 token/LLM key/隐私明文进浏览器 |
| API 是否统一 `/api/v1` | OpenAPI / `rg` 检索 |
| 失败/空/加载状态是否存在 | 浏览器或测试证据 |
| review gate 是否完成 | `code_review_graph` 或人工审查记录 |

## 8. 不要这样做

1. 不要同时让 Codex 做 P1-P12 全部实现。
2. 不要回到 Node/NestJS 路线。
3. 不要把 LLM 当主排考引擎。
4. 不要把长期 token 或 LLM key 写进浏览器。
5. 不要为了接后端重做登录页、仪表盘、Quick 页面视觉。
6. 不要把主数据分页强塞进当前 DataTable。
7. 不要让 `/api` 和 `/api/v1` 两套外部口径并存。
8. 不要只写 mock 端点就声称后端打通。
9. 不要跳过浏览器/E2E 验证。
10. 不要在没有 DB/日志证据时声称任务、排考、发布已经真实生效。

## 9. 推荐发给下一轮 Codex 的第一条消息

```text
请接力开发 JKXT V2.0,目标是最终打通真实 AI 赋能高效排监考系统。先不要全量实现,请从 P0/P1 开始。

必须先读:
- docs/10-CODEX-FULLSTACK-IMPLEMENTATION-PLAYBOOK.md
- docs/08-BACKEND-DEV-SPEC.md
- docs/09-SECOND-ROUND-BACKEND-DOC-REVIEW-ANNOTATED.md
- docs/BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md
- uploads/07-UI-DEVELOPMENT-SPEC.md

然后执行 docs/10 中的「Master Prompt」和「P1 · Spring Boot + Docker Compose 骨架」。请用 Superpowers/TDD 风格推进,先定义验收证据,再创建 backend Spring Boot 骨架、docker-compose postgres/redis/backend、/api/v1/health、OpenAPI 和最小测试。完成后运行测试与 curl 验证,刷新 review gate,输出改动文件和命令结果。
```

## 10. 真正完成全栈目标的最终验收

系统最终必须能证明:

1. 私有化环境一条命令启动 app + postgres + redis。
2. 管理员真实登录,刷新后 `/auth/me` 恢复身份。
3. 教师/考场/班级/科目来自数据库。
4. Excel 导入校验返回行级错误。
5. Quick 一键排监考创建真实 task/run/assignment。
6. OR-Tools 求解器可处理可行与无解样本。
7. 进度页显示真实 job progress。
8. 结果页显示 fairness 5 项、AI targets 三类。
9. LLM 策略解析/解释/修复建议经过 schema 校验并记录 `ai_call_log`。
10. edit.jsx 真拖拽改派能触发后端冲突校验和增量保存。
11. 发布任务写通知和审计日志。
12. 报表/历史/超管不再依赖随机或写死数据。
13. Playwright 覆盖登录 -> 导入 -> 排考 -> 编辑 -> 发布 -> 报表 -> 超管核心流。
14. 安全检查覆盖认证、租户越权、上传、LLM key/隐私数据。
15. SaaS/private 两种部署模式有 runbook 和 UAT checklist。

只有这些证据都成立,才能说“后端彻底打通,所有服务跑起来,真正实现 AI 赋能高效排监考系统”。
