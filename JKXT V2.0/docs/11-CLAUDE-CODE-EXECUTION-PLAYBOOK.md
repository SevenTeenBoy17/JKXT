# 11 · Claude Code 全栈开发执行手册(阶段方法 + 可粘贴提示词库)

> **用途**:交给后续 Claude Code 会话,照此把后端从零写到**全部服务真跑起来**,前端接管为真实客户端,落地真正 AI 赋能的排监考系统。
> **配套**:`08-BACKEND-DEV-SPEC.md`(主契约)· `10-IMPLEMENTATION-TASK-PLAN.md`(任务 ID 与 DoD)· `09-...REVIEW.md`(路线复核)。
> **铁律**:所有提示词都**引用文档路径 + 任务 ID**,让 Claude Code 拿到契约;**先计划后写码**;**先测试后实现(TDD)**;**每阶段必须真跑起来验收**;独立模块**子代理并行**。

---

## §0 这份文档怎么用

1. 本文 = **执行节奏 + 提示词模板**。你(或 Claude Code 主控)按 Sprint 顺序,把 §4/§5 的提示词逐段粘进 Claude Code。
2. **一个 Sprint 一个会话 / 一条分支**;Sprint 内独立任务用**子代理并行**;Sprint 之间 `/clear` 重置上下文,靠 `CLAUDE.md` + 文档找回状态。
3. 每段任务走 §3 的**五步闭环**:计划 → TDD 实现 → 真跑验证 → 代码审查 → 提交 PR。
4. 充分调用本机已装能力:`superpowers`(TDD/调试/计划/验证/子代理/worktree)、`ce-*`(plan/work/code-review/commit-push-pr)、`code-review-graph` MCP(大代码库最小上下文)、`/run` `/verify` `/code-review` `/init`。

---

## §1 一次性 Bootstrap(Session 0)

### 1.1 仓库布局决策(先定)
- **建议**:在 `D:\VB\JKXT\` 下打开 Claude Code(父目录),使其同时看见 **前端 `JKXT V2.0/`(含 docs 契约)** 与 **后端 `ai-jianke-system/backend/`(新建,Spring Boot)**。
- 后端独立 git 仓或与现有共用均可;**先 `git init` 后端目录**,Sprint 用分支。

### 1.2 后端 `CLAUDE.md` 起步模板(让未来会话秒进入状态)
在后端根目录创建 `CLAUDE.md`:
```md
# CLAUDE.md — JKXT 后端(排监考系统)
本仓库是 K-12 排监考系统后端。**契约权威**:../JKXT V2.0/docs/08-BACKEND-DEV-SPEC.md;任务清单:../JKXT V2.0/docs/10-IMPLEMENTATION-TASK-PLAN.md;执行手册:../JKXT V2.0/docs/11-CLAUDE-CODE-EXECUTION-PLAYBOOK.md。

## 技术栈(不可翻案,见 10 §1)
Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Redis 7 · Flyway · OR-Tools CP-SAT · Apache POI · Spring Security(JWT RS256,生产 httpOnly,非 localStorage)· OpenAPI(springdoc)· 外部前缀统一 /api/v1。
混合交付:deploy.mode=saas|private(Profile 切换)。多租户:行级 org_id + Hibernate @Filter。

## 命令
- 构建测试:mvn verify
- 起本地栈:docker compose up -d(app+postgres+redis+nginx)
- API 文档:/swagger-ui
## 纪律
- 先计划后写码;先写失败测试再实现(TDD)。
- 每个租户表继承 TenantEntity(@Version 乐观锁);禁止跨租户查询绕过 @Filter。
- 所有变更走统一错误信封;LLM 入出参写 ai_call_log;敏感数据不进浏览器。
```

### 1.3 Bootstrap 提示词(粘贴执行)
```
请阅读 ../JKXT V2.0/docs/08-BACKEND-DEV-SPEC.md(§0–§3、§11)与 ../JKXT V2.0/docs/10-IMPLEMENTATION-TASK-PLAN.md(§1 决策基线、§3 的 S0)。

目标:完成 S0-02~S0-06 的工程脚手架。先进入计划模式给出目录结构与依赖清单,经我确认后再写代码。要求:
- Maven 工程,package-by-feature:com.jkxt.{common,security,tenant,org,masterdata,importer,scheduling,ai,tasks,stats,platform,notify,audit}
- application.yml + application-saas.yml + application-private.yml(deploy.mode 切换;private 用固定 FIXED_ORG_ID)
- Flyway:db/migration(主)+ db/migration-dm(信创变体目录占位);V1__init.sql 先只建 organization、app_user 两表
- common 层:统一错误信封(code/message/traceId/errors)、分页约定、TenantContext(ThreadLocal)、TenantEntity 基类(id UUID/org_id/created_at/@Version)、GlobalExceptionHandler、Bean Validation 接入
- springdoc-openapi + Swagger UI
- docker-compose.yml:app + postgres:16 + redis:7 + nginx(本地 TLS 可选)
- 冒烟测试:Spring 上下文加载 + GET /actuator/health 200

完成定义(DoD):`mvn verify` 全绿;`docker compose up -d` 起栈成功;访问 /swagger-ui 正常。把验证命令、输出/截图给我。最后用 ce-commit 提交「chore: project scaffold (S0)」。
```

### 1.4 建立代码图谱(大代码库省 token)
```
前端 ../JKXT V2.0/src 较大、后端将持续增长。请用 code-review-graph MCP 为当前工作区构建/更新结构图谱(build_or_update_graph),之后所有跨文件理解优先用 get_minimal_context / get_impact_radius,避免整文件读入。先报告 list_graph_stats。
```

---

## §2 贯穿全程的 Claude Code 协作总原则

| 能力 | 怎么用 | 何时 |
|---|---|---|
| **计划模式** | 任何 Sprint/复杂任务**先出计划等确认**,不直接写码 | 每个 Sprint 开头 |
| **TDD**(`superpowers:test-driven-development`) | 端点/服务**先写失败测试再实现**;红→绿→重构 | 所有后端逻辑 |
| **子代理并行**(`superpowers:subagent-driven-development`) | 多个**相互独立**模块(如 4 个主数据 CRUD)分派子代理同时实现 | 任务无依赖时 |
| **git worktree**(`superpowers:using-git-worktrees`) | 并行推进多 Sprint 或隔离实验,互不污染 | 并行/试验 |
| **系统化调试**(`superpowers:systematic-debugging`) | 卡壳/测试失败时先定位根因,不乱改 | 出 bug 时 |
| **验证后完成**(`superpowers:verification-before-completion` / `/verify` / `/run`) | "写完"≠完成;**必须把服务跑起来发真请求**确认 | 每段收尾 |
| **代码审查**(`/code-review` / `ce-code-review`) | 提交前审:越权/事务/N+1/契约/安全 | 每 Sprint 提交前 |
| **提交/PR**(`ce-commit-push-pr`) | 分支 `sprint/Sx`,conventional commits,PR 对照任务 ID | 每段交付 |
| **code-review-graph MCP** | `get_minimal_context` 只读相关代码;`get_impact_radius` 估改动爆炸半径 | 大改/跨模块 |
| **上下文卫生** | 一 Sprint 一会话;跨 Sprint `/clear`,靠 CLAUDE.md + docs 找回 | 全程 |

> **黄金句式**(每个提示词都带):①「先读 `docs/08` §x 与 `docs/10` 的 {任务ID}」②「先计划等我确认」③「先写失败测试」④「相互独立的 {模块} 用子代理并行」⑤「把服务跑起来发真请求验证,对照 10 §7 DoD」。

---

## §3 每个 Sprint 的标准五步闭环(可复用模板)

把下面 5 段按顺序用于**任一 Sprint**,把 `{Sx}`/`{任务ID}`/`{端点}`/`{页面}` 替换即可。

**① 规划**
```
读 ../JKXT V2.0/docs/10-IMPLEMENTATION-TASK-PLAN.md 的 {Sx} 全部任务,以及 docs/08-BACKEND-DEV-SPEC.md 对应章节。进入计划模式:按任务 ID 拆解本 Sprint 实现计划,标注依赖、可并行项、数据迁移与测试策略。不要写代码,等我确认。
```
**② TDD 实现**
```
按计划用 superpowers 的 test-driven-development 实现 {任务ID 列表}。每个端点/服务先写失败测试(覆盖正常+租户隔离+错误码)再实现,严格对齐 08 的 DTO 字段名/错误信封/约束。相互独立的模块({列举})派子代理并行实现,最后汇总。每完成一个任务勾选 10 文档对应 - [ ]。
```
**③ 真跑验证**
```
用 docker compose up -d 起栈(或 /run),对 {端点} 用 curl/HTTP 发真实请求验证:正常流、租户越权拦截(403)、错误码。{页面} 改指向真实后端做端到端联调并截图。对照 10 §7 的量化 DoD 自检并报告结果。
```
**④ 代码审查**
```
用 /code-review(或 ce-code-review)审查本 Sprint 改动,重点:租户越权/泄漏、事务边界、N+1、契约一致性、安全(JWT/CSRF/上传沙盒/SQL 参数化)、空值与并发。逐条修复后重跑测试与验证。
```
**⑤ 提交 PR**
```
用 ce-commit-push-pr 在 sprint/{Sx} 分支提交并开 PR;PR 描述列出完成的任务 ID、DoD 达成情况、验证截图、遗留项。
```

---

## §4 逐 Sprint 启动提示词(S0–S6 · 含并行编排)

> 每个 Sprint 先粘下面的「启动/计划」提示词,再按 §3 走②③④⑤。**关键路径:S1 先行**;S2 后期并行启动 OR-Tools 预研。

### S1 · 认证 / 租户 / RBAC / 安全(P0,阻塞一切)
```
读 docs/10 的 S1 全部任务 + docs/08 §2(多租户)§3.2–3.4 §4(认证安全)。进入计划模式给实现计划。本 Sprint 是全项目地基,务必:
- JWT RS256 access1h+refresh7d,**生产用 httpOnly cookie/BFF,禁止 localStorage 存 token**(09 C3);bcrypt cost 12。
- /auth/login 单一入口,**忽略前端声明 role,以账号 role 为准**;验证码后端化;5 次失败锁 30min。
- /auth/me 返回 user/org/term/permissions + onboarded(G3);change-password 首登强制。
- TenantContext + Hibernate @Filter,saas/private Profile;超管 X-Tenant-Id。
- RBAC(SUPER_ADMIN/ADMIN/VIEWER/TEACHER)@PreAuthorize;通用 audit_log + AOP;CSRF;Bucket4j 限流。
TDD 必含**跨租户越权用例**(A 租户 token 读 B 租户数据→403)。计划批准后按 §3 推进。
```
**并行编排**:`S1-BE-*` 后端先行;`S1-FE-01/02/03`(api.js + login + app /auth/me)可在 BE-03/04 就绪后并行接。

### S2 · 主数据 + Excel 导入 + 求解器预研(P0/P1)
```
读 docs/10 的 S2 + docs/08 §3.5–3.8、§6(Excel)、§7.2–7.3(约束)。计划模式出方案。要点:
- teacher/classroom/school_class/subject 四实体 + 关联表;CRUD **整页返回不分页**(R3)。
- Excel 导入:Apache POI **沙盒(独立线程池+30s 超时)+ 魔数校验 + 10MB**;严格 3 模板(见 04 文档);校验报告 6 类/4 级 + 行号+列名;import_batch/validation_issue。导入校验用**表驱动测试**覆盖每种 warn/error。
- 跨表一致性(②考场∈③列表、容量可行性)。
**S2-BE-05 OR-Tools 预研单开子代理**(见 §5.1),与 CRUD/导入并行。
前端 S2-FE 同步:people/classrooms/classes/settings mock→API,**删除占位 P2(津贴×120)、P3(Math.random 场次)**。
```
**子代理并行**:4 个实体 CRUD 相互独立 → 4 个子代理;导入与 CRUD 独立 → 再 1 个;OR-Tools 预研 → 再 1 个。

### S3 · 一键排监考闭环(P0,核心)
```
读 docs/10 的 S3 + docs/08 §7(排考引擎)§3.9–3.12。计划模式。核心交付:
- exam/exam_task/schedule_run/schedule_assignment 实体 + 状态机。
- **CP-SAT 引擎实装**(见 §5.2 专用提示词):H1–H7 硬约束 + S1–S6/Sg/Sj 软约束 + 阈值 0.65;变量域预剪枝;solver 45s 上限。
- /scheduling/quick 异步 job;STOMP /ws/schedule/{runId} 6 阶段进度。
- **结果契约严格按 08 §7.6:fairness 5 项(G1)+ targets 枚举 teacher/room/slot(G2)**。
- POI 导出 4 sheet+说明页;tasks CRUD/list(KPI)/detail/log。
引擎必须有**可复现**单测(同输入同解)与**无解→最小冲突集**用例。前端 S3-FE:quick 接 API+STOMP、quick-engine 掏空(留 fallback)、step4 接契约、**删占位 P4(timeline 写死今天)**。
DoD:Quick→Task→Detail 全链路真实 API,job 完成率/无解解释率达标(10 §7)。
```

### S4 · AI 策略 + 人工编辑(真拖拽)(P0/P1)
```
读 docs/10 的 S4 + docs/08 §7.4(LLM)、§7.7(真拖拽)、§7.8(AI 进度复用)。计划模式。交付:
- LlmClient 多 provider(OpenAI 兼容,复用现成 LLM API);NL→DSL(/nl-policy/parse)+ /refine;ai_call_log 脱敏+审计;**prompt 注入防护 + 输出 JSON schema 校验 + 回退链**(超时切模型→CP-SAT)。
- /scheduling/ai-policy 复用 run 链路;/ai/providers + 配额用量(G4)+ /active。
- **PATCH /tasks/{id}/schedule 增量改派(move/swap/assign/unassign + @Version)** + check-conflicts + runs/{id}/ai-fix + publish(邮件)。
- 前端 S4-FE-03 **edit 真拖拽**(本期唯一 C 类,见 §5.4 专用提示词):拖放+候选侧栏+乐观更新/回滚+撤销栈+**无障碍/触屏等价操作**;ai-policy「优化我的表达」死按钮接 /refine;startAI→runId→navigateTo('quick') 复用 step3/4。
AI 链路必须**真实可跑**(非 stub):给一段中文约束能解析成 DSL 并影响排考结果。
```

### S5 · 统计报表 + 通知(P1)
```
读 docs/10 的 S5 + docs/08 §8。计划模式。交付:聚合端点(kpis/supervise/history/by-grade/monthly-trend/exams-schedule);stats_cache/物化视图 + ScheduleChangedEvent 事件刷新;**班主任占比/楼道分配/特殊备注派生量(支撑 G1 fairness)**;通知(站内信/邮件 + 可插拔 SmsGateway)。前端 S5-FE 接管 + **删占位 P1(history count/200)**;导出接端点。聚合 SQL 写集成测试核对数值。
```

### S6 · 超管平台面 + 交付硬化(P1/P2)
```
读 docs/10 的 S6 + docs/08 §10、§11。计划模式。交付:tenants(注册向导事务/暂停90天/恢复/CSV)、accounts(凭证/重置/强制下线/2FA/禁用/删除)、platform-settings(广播)、audit-logs(导出)、security(IP/threats/backups)、announcements(发布/推送/撤回/read-stats)、dashboard/metrics-health。前端 super.jsx 各模块接 /admin/*;**统一 topbar 与 super 的租户数据源**;onboarding 后端化。交付:SaaS(K8s ACK)+ 私有化(Compose 离线镜像);可观测(Sentry/Prometheus/Grafana)+ 自动备份 + 恢复演练;安全扫描 + UAT runbook;**确认信创栈/短信选型**。
```

---

## §5 关键难点的专用提示词(最大化发挥编程能力)

### 5.1 OR-Tools CP-SAT 预研 spike(S2-BE-05,务必前移)
```
独立 spike:用 OR-Tools Java(com.google.ortools)CP-SAT 为排考做可行性与性能基准。读 docs/08 §7.2–7.3 的硬/软约束。
- 造合成数据集:小(8 场/22 师)、中(24/96)、大(8 场/300 师,NFR 规模)。
- 建模 H1–H7 硬约束 + S1 工作量均衡(先只做这个软目标),变量域按 H4/H5/H7 预剪枝。
- 测:可行集求解时间、是否 OPTIMAL/FEASIBLE、无解时用 sufficientAssumptionsForInfeasibility 输出最小冲突集。
- 产出基准报告(各规模时延)+ 结论:45s 内能否满足 NFR。**这是全项目最高技术风险,先证明可行再进 S3。**
不接入 Spring,纯独立 main + JUnit 基准即可。
```

### 5.2 CP-SAT 引擎实装(S3-BE-02)
```
用 TDD 实装排考引擎(在 5.1 spike 基础上)。读 docs/08 §7.2–7.3、§7.6。
- 先写测试:给定固定 teachers/rooms/exams fixture,断言①无硬约束违反(H1–H7)②同输入可复现③软约束打分≥0.65接受、<0.65走重试/回退④无解→最小冲突集。
- 再实现 CpSatScheduler:决策变量 assign[e,r,t]/main[e,r,t](仅可行集建变量),软约束 S1–S6/Sg/Sj 加权目标,solver 45s/8 worker。
- 输出映射为 08 §7.6 的 assignments + stats + scoreBreakdown。
关注:大网格内存、超时兜底、与 LLM 解析出的额外约束如何合并进目标。
```

### 5.3 LLM NL→DSL 与安全(S4-BE-01/02)
```
TDD 实现 LlmClient 与自然语言约束解析。读 docs/08 §7.4。
- LlmClient 接口(nl2dsl/summarize/suggestFix),WebClient 适配多 provider(OpenAI 兼容,baseUrl/key/model 配置化),复用现成 LLM API。
- /nl-policy/parse:中文约束→{constraints:[{name,type,weight,scope,expression,explanation}],unable_to_parse}。**金标准测试**:准备 8–10 条中文约束→期望 DSL。
- 安全(硬性):prompt 注入防护、输出 JSON schema 校验后才采纳、教师/学生隐私脱敏后入 prompt、key 不出服务端、每次调用写 ai_call_log(入参+出参+token+延迟)。
- 回退链:超时 60s→切模型→CP-SAT;非 JSON→重试+schema;全软<0.4→报「约束过严」。
验收:一段中文约束能真实改变排考结果,且日志可审计。
```

### 5.4 前端 edit 真拖拽(S4-FE-03,唯一 C 类)
```
读 docs/08 §7.7 实现 edit.jsx 真拖拽改派(前端唯一结构性改动,其余页面只接线)。
- ScheduleCell 加 draggable + onDragStart/Over/Drop;新增「候选监考」可折叠侧栏作拖拽源;落子语义 move/swap/assign/unassign。
- 乐观更新→PATCH /api/v1/tasks/{id}/schedule(带 version)→check-conflicts→失败回滚+toast 原因;ConflictBanner 改为消费冲突数组(0 隐藏/N 循环)。
- 撤销/重做接 PATCH;**必须提供键盘可达等价操作(选中→改派抽屉)与触屏长按拖拽**(无障碍验收项)。
- 保持现有网格布局/视觉,仅叠加拖放交互层。先做交互原型截图给我确认手感,再接后端。
```

### 5.5 前端整体接管(横切,api.js + 各页)
```
读 docs/08 §12、docs/09 §9。实现前端「mock→真实 API」接管,**不改 UI 视觉/布局**:
- 新增 src/api.js(JWT/CSRF/租户头/统一错误/401→login);在 Dashboard.html 的 overlay.jsx 后、各页前加载。
- 每页加 USE_API 开关,后端未就绪回退 mock,支持分模块灰度。
- 按页接管:people/classrooms/classes/subjects/settings → CRUD;quick* → 导入/排考/进度/导出;tasks/detail/edit;stats/history/timeline/dashboard;super.jsx → /admin/*。
- **删除会显示错误的占位**:history count/200、people ×120、classes Math.random()、timeline 写死今天、edit ConflictBanner 写死 1 条。
- 统一 topbar 与 super 的租户数据源。
每页接管后用真实后端联调并截图。
```

---

## §6 验证与质量门(怎么确认"真的跑起来了")

每个 Sprint 结束,Claude Code 必须给出**可复现的"真跑"证据**,不接受"应该能跑":

1. **起栈**:`docker compose up -d` → `docker compose ps` 全 healthy;`/actuator/health` 200。
2. **迁移**:Flyway 迁移成功;`/swagger-ui` 列出本 Sprint 端点。
3. **冒烟**:对关键端点 `curl` 真实请求,贴请求/响应。
4. **集成测试**:Testcontainers 起真实 Postgres/Redis 跑集成测试(非 H2 假库)。
5. **端到端**:前端对应页指向真实后端,截图证明数据来自后端(非 mock)。
6. **量化 DoD**(对照 `10 §7`):登录成功率、导入行级错误定位、排考 job 完成率、无解解释率、时延<60s、越权 100% 403、导出成功率。
7. **AI 真赋能验收(S4 后)**:输入一段中文监考要求 → 系统解析为约束 → 影响排考结果 → 给出自然语言总结;全程 ai_call_log 可审计。

> 任何一项不通过 → 用 `superpowers:systematic-debugging` 定位根因再修,不得跳过。

---

## §7 上下文与会话管理(让 Claude Code 持续高效)

- **一 Sprint 一会话 / 一分支**;Sprint 间 `/clear`,靠后端 `CLAUDE.md` + `docs/08/10` 找回状态。
- **大代码库省 token**:跨文件理解优先 `code-review-graph` 的 `get_minimal_context`/`get_impact_radius`,避免整文件读入;改动前用 `get_impact_radius` 估爆炸半径。
- **并行**:相互独立任务派子代理;并行多 Sprint 用 `git worktree`(`superpowers:using-git-worktrees`)隔离。
- **CLAUDE.md 维护**:每 Sprint 收尾把新命令/约定/坑写回后端 `CLAUDE.md`(用 `/init` 增量或手动)。
- **任务追踪**:每完成任务,勾选 `10-IMPLEMENTATION-TASK-PLAN.md` 对应 `- [ ]`,保持单一进度源。
- **决策不翻案**:遇到栈/分页/拖拽/认证等已定项,直接引用 `10 §1`,不重新讨论。

---

## §8 写好 Claude Code 提示词的 8 条要点(本项目专用)

1. **给契约**:永远先「读 docs/08 §x 与 docs/10 的 {任务ID}」,别让它凭空猜字段。
2. **先计划**:复杂任务要求「进入计划模式,等我确认再写码」。
3. **先测试**:要求 TDD,且**点名要租户越权/错误码/边界用例**。
4. **要并行**:明确「相互独立的 {模块} 用子代理并行」。
5. **要真跑**:收尾必须「起栈发真请求 + 截图 + 对照 DoD」,拒绝"应该能跑"。
6. **要审查**:提交前 `/code-review`,点名越权/事务/N+1/安全。
7. **小步提交**:一段一 PR,描述对照任务 ID;失败先调试根因不乱改。
8. **守边界**:强调「前端不改 UI,只接线;唯一例外 edit 真拖拽」,防止它顺手重构。

---

## 附:推荐执行顺序(首月)

```
Session 0  Bootstrap(§1)              → 脚手架跑起来 + 代码图谱
Session 1  S1 认证/租户(§4 S1 + §3)    → httpOnly 登录闭环 + 越权拦截(前端通电)
Session 2  S2 主数据+导入(§4 S2)        ┐ 并行
Session 2' S2-BE-05 OR-Tools spike(§5.1)┘ → 排考可行性证明
Session 3  S3 排考闭环(§4 S3 + §5.2)    → Quick→Task→Detail 真链路
Session 4  S4 AI+真拖拽(§4 S4 + §5.3/5.4)→ 真正 AI 赋能 + 人工编辑
Session 5  S5 统计/通知 → Session 6 超管/交付硬化
```
**第一可见里程碑**:Session 1 结束即可演示「真实登录 + 真实角色/权限 + 跨租户隔离」——系统正式"通电"。

---

*v1.0 · Claude Code 执行手册 · 配套 08 契约 / 10 任务 / 09 复核 · 按 Sprint 粘贴提示词即可驱动全栈开发*
