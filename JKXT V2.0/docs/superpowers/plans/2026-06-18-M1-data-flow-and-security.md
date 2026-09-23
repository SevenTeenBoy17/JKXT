# M1 实施计划 — Epic 1 真实数据流 + Epic 2 安全基线

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` 或 `superpowers:executing-plans` 逐任务实现。步骤用 `- [ ]` 勾选跟踪。
>
> **Goal:** 让用户导入的真实 Excel 数据真正流入 OR-Tools 排考器并产出与之对应的结果；同时消除越权(无 RBAC)与生产弱密码后门。
>
> **Architecture:** 新增 import-commit 落库链路（按 R1 任务级隔离，`source_task_id`）+ `ScheduleFixtureFactory.fromTask()` 从 DB 构建求解输入 + 方法级 RBAC(细分 4 角色) + Flyway dev-only seed 守卫。全部走新 Flyway 迁移 V11+，不改已应用迁移。
>
> **Tech Stack:** Java 21 / Spring Boot 3.3 / PostgreSQL 16 / Flyway / Apache POI / OR-Tools CP-SAT / Spring Security method-security。
>
> **决策依据(用户已确认)：** R1 每任务独立数据 · R2 infeasible 报错让用户改 · R4 细分角色(SUPER_ADMIN/JIAOWU教务/TEACHER教师/VIEWER查看者) · R5 backend 已入 git(Epic 0 ✅) · R6 移除前端无后端 mock 兜底。

---

## 关键设计决策（R1 已最终确认：学校名册共享 + 考试每任务独立）

> **2026-06-18 用户拍板**：采用 **org 名册共享 + 考试每任务独立** 模型（读 V2 schema 后确认这贴合现有结构与 K-12 真实业务）。**无需 V11 迁移**，核心修复纯服务层。原"source_task_id 方案"作废。

**schema 已支持（读 V2 确认）**：
- `exam.exam_task_id`(NOT NULL, FK→exam_task) → 考试天然按任务隔离 ✅
- `exam_room(exam_id, classroom_id)` / `exam_class(exam_id, class_id)` → 考试用哪些考场/班级
- `teacher/classroom/school_class/subject` 为 org 级名册（一校一份）

**真实数据流（修订后，无 schema 改动）**：
```
导入 教师/考场/班级/科目.xlsx ──▶ upsert 到 org 名册(按 org+编号, 已存在则更新)
导入 考试安排.xlsx            ──▶ 落 exam(绑 exam_task_id) + exam_room(关联考场) + exam_class(关联班级)
buildFixtureFromTask(taskId)  ──▶ 读 exam WHERE exam_task_id=taskId + 关联 exam_room 的 classroom + org 活跃 teacher
                               ──▶ 构建 ScheduleFixture(teachers, rooms, examRooms)
ScheduleJobService 求解        ──▶ 真实数据进 CP-SAT；数据不足→ SCHEDULE_INPUT_INCOMPLETE(R2)
```

**已读代码确认的接线点**：
- `ScheduleJobService` 已注入 `examRepository/teacherRepository/classroomRepository`（line 62-64）→ 直接可用
- `ScheduleJobService.startTaskRun` line **128** `fixtureFactory.feasible()` ← 唯一致命改点
- `prepareRunForTask`(221-279) 当前从 fixture **创建** exam → 改为从已落库 exam **读取**（新增 `prepareRealRun`，不再 upsertFixtureMasterData 覆盖）
- `ImportValidationService` 的 `TemplateSpec`(列定义齐全: teachers/classrooms/classes/subjects/exams) + `readHeaderIndexes`/`value` 解析为 **private** → **需提取为可复用 `parseRows(kind,file)`** 供 commit 复用（不改 validate 行为，既有测试保持绿）

### 修订后 Epic 1 任务（替代原 1.1-1.6）
- **1.1 解析器复用重构**：`ImportValidationService` 加 public `List<RowMap> parseRows(kind, MultipartFile)`（复用 readHeaderIndexes+value，返回 header→值 映射的有效数据行）。验收：既有 validate 测试不变 + parseRows 单测。
- **1.2 ImportCommitService**：注入 masterdata/tasks 仓库；先 validate(无 error 才提交)；teachers/classrooms/classes/subjects → upsert org 名册(按 org+编号)；exams → 落 exam+exam_room+exam_class(绑 taskId)；返回 {kind,inserted,updated}。验收：导入 142 教师→DB 142 行；导入考试→exam+exam_room 落库。
- **1.3 commit 端点**：`POST /api/v1/import/{kind}/commit?taskId=` + ExamTask upload 流程接 commit。
- **1.4 buildFixtureFromTask**：ScheduleJobService 新增私有 `buildFixtureFromTask(orgId, task)`：读 exam(by task)+exam_room→classroom+org teacher → ScheduleFixture；空数据抛 `SCHEDULE_INPUT_INCOMPLETE`(R2)。
- **1.5 接线 startTaskRun:128**：`feasible()` → `buildFixtureFromTask(...)`；新增 `prepareRealRun`(不重新建 exam，用已落库的)；保留 feasible 仅 fixture 测试端点。验收：RealDataScheduleFlowIntegrationTest 导入真实样本→排考→assignments 含导入教师/考试；换数据→结果不同。
- **1.6 校验真实化**：cap/time/rules 分类真实计算(座位>容量/同教师同考场时间重叠/班主任回避)。
- **1.7 前端接线+去 mock(R6)**：quick.jsx 接 commit；删 QK_VALIDATION_DATASETS/假日志/qkResultData 兜底；断后端→错误态。

---

## 文件结构（M1 将创建/修改）

### Epic 1 — 真实数据流
| 动作 | 文件 | 责任 |
|---|---|---|
| 新建迁移 | `backend/.../db/migration/V11__p14_source_task_scope.sql` | 给 5 表加 `source_task_id` 列 + 索引 |
| 新建 | `backend/.../importer/service/ImportCommitService.java` | 解析→落库 teachers/classrooms/classes/subjects/exams(打 source_task_id) |
| 修改 | `backend/.../importer/api/ImportController.java` | 新增 `POST /import/{kind}/commit?taskId=` |
| 修改 | `backend/.../tasks/service/ExamTaskService.java:133-191` | upload 后可触发 commit；validate 真实化 |
| 修改 | `backend/.../scheduling/solver/ScheduleFixtureFactory.java` | 新增 `fromTask(UUID taskId, UUID orgId)` |
| 修改 | `backend/.../scheduling/service/ScheduleJobService.java:121-141` | startTaskRun 用 fromTask 替换 feasible()；数据不足→抛 `SCHEDULE_INPUT_INCOMPLETE` |
| 修改 | repos: `TeacherRepository/ClassroomRepository/ExamRepository/...` | 增 `findBySourceTaskId(...)` 查询 |
| 新建测试 | `backend/.../tasks/RealDataScheduleFlowIntegrationTest.java` | 导入真实样本→排考→断言结果含该数据 |

### Epic 2 — 安全基线
| 动作 | 文件 | 责任 |
|---|---|---|
| 修改 | `backend/.../security/SecurityConfig.java` | `@EnableMethodSecurity` |
| 修改 | `backend/.../platform/api/AdminPlatformController.java` | 类级 `@PreAuthorize("hasRole('SUPER_ADMIN')")` |
| 修改 | `backend/.../scheduling/api/ScheduleEditController.java` + `tasks/ExamTaskController.java` + `masterdata/*Controller.java` | 写操作加 `@PreAuthorize("hasAnyRole('SUPER_ADMIN','JIAOWU')")` |
| 修改 | `backend/.../security/auth/SessionAuthenticationFilter.java` | 装配 `ROLE_*` authorities 供 hasRole 消费 |
| 新建迁移 | `backend/.../db/migration/V12__p14_roles_fine_grained.sql` | 角色枚举补 JIAOWU/TEACHER/VIEWER + 演示账号改 must_change_password=true |
| 修改 | `backend/.../resources/application-private.yml` + `application-saas.yml` | `flyway.locations` 排除 demo-seed；`captcha-debug=false` |
| 迁移分目录 | 把 V4/V9/V10 demo seed 拆到 `db/migration-dev/`，仅 dev profile 加载 | 生产不 seed 弱密码 |
| 新建测试 | `backend/.../security/RbacAuthorizationTest.java` | 普通角色调超管端点→403 |

> **说明**：本计划给出文件级任务分解与验收；逐步骤的精确 Java 代码在执行时按 TDD 产出（先写失败测试→最小实现→验证→提交），每个 Task 独立提交。

---

## Epic 1 任务序列（TDD，按依赖排序）

### Task 1.1 — V11 迁移：source_task_id 列
- [ ] 写迁移 `V11__p14_source_task_scope.sql`：`ALTER TABLE teacher/classroom/school_class/subject/exam ADD COLUMN source_task_id uuid NULL REFERENCES exam_task(id) ON DELETE CASCADE;` + 各表 `CREATE INDEX ... (source_task_id)`。
- [ ] 跑 `backend\mvnw.cmd test -Dtest=P2DatabaseIntegrationTest` 确认迁移应用、既有行 source_task_id 为 NULL。
- [ ] **验收**：Flyway history 出现 V11；5 表新列存在；既有 seed 行不受影响。
- [ ] Commit: `feat(db): V11 add source_task_id task-scoping (Epic1.1)`

### Task 1.2 — Repository 任务级查询
- [ ] 给 `TeacherRepository/ClassroomRepository/SchoolClassRepository/SubjectRepository/ExamRepository` 加 `findBySourceTaskId(UUID)` / `deleteBySourceTaskId(UUID)`；实体加 `sourceTaskId` 字段。
- [ ] 单测：插入带 source_task_id 的行 → findBySourceTaskId 命中、findBySourceTaskIdIsNull 不含。
- [ ] **验收**：测试通过。
- [ ] Commit: `feat(repo): source-task-scoped queries (Epic1.2)`

### Task 1.3 — ImportCommitService（解析→落库，最核心）
- [ ] 先写失败测试 `ImportCommitServiceTest`：给 teachers.xlsx 字节 + taskId → commit → TeacherRepository.findBySourceTaskId(taskId) 返回解析出的全部行（字段正确）。同理 classrooms/classes/subjects/exams(+exam_room)。
- [ ] 实现 `ImportCommitService`：复用 `ImportValidationService` 的 POI 解析，校验通过后把每行 upsert 进对应表并打 `source_task_id`；exams 额外写 exam_room/exam_class 关联；返回 `{kind, inserted, updated, skipped}`。
- [ ] **R2 数据完整性**：commit 时记录每类已就绪行数到 ImportBatch。
- [ ] 跑测试至通过。
- [ ] **验收**：导入 142 教师 → DB 真有 142 行(source_task_id=task)；导入 8 考试 → exam+exam_room 行落库。
- [ ] Commit: `feat(import): real import-commit persists rows per task (Epic1.3)`

### Task 1.4 — ImportController commit 端点
- [ ] 写 MVC 测试：`POST /api/v1/import/{kind}/commit?taskId=` 上传文件 → 200 + 落库计数。
- [ ] 实现端点调用 ImportCommitService。
- [ ] **验收**：端点测试通过。
- [ ] Commit: `feat(import): POST /import/{kind}/commit endpoint (Epic1.4)`

### Task 1.5 — ScheduleFixtureFactory.fromTask（DB→求解输入）
- [ ] 写失败测试 `ScheduleFixtureFactoryFromTaskTest`：预置 source_task 教师/考场/考试 → `fromTask(taskId, orgId)` 返回的 ScheduleFixture 的 teachers/rooms/examRooms 与 DB 行一一对应。
- [ ] 实现 `fromTask`：注入 4 repos，按 source_task_id 读 teacher→TeacherInput、classroom→RoomInput、exam+exam_room→ExamRoomInput（映射 subjectName/grade/date/start/end/seatDemand/supervisorsPerRoom）。
- [ ] 数据不足(无教师/无考场/无考试)→抛 `SCHEDULE_INPUT_INCOMPLETE`(R2)。
- [ ] **验收**：测试通过；fromTask 不含任何硬编码姓名/日期。
- [ ] Commit: `feat(scheduling): ScheduleFixtureFactory.fromTask builds fixture from DB (Epic1.5)`

### Task 1.6 — 接线 startTaskRun（致命缺口修复点）
- [ ] 改 `ScheduleJobService.java:128`：`prepareRunForTask(orgId, task, fixtureFactory.fromTask(task.getId(), orgId))` 替换 `feasible()`。
- [ ] 移除/隔离 `upsertFixtureMasterData` 的反向写库（fromTask 数据已在库，不应被 fixture 覆盖）。
- [ ] 数据不完整时 job 置 failed + 结构化错误码 `SCHEDULE_INPUT_INCOMPLETE`，前端展示"请先导入完整数据"。
- [ ] 保留 `feasible()` 仅 `/scheduling/fixtures/{id}/runs` 测试路径可达。
- [ ] **验收**：用 RealDataScheduleFlowIntegrationTest：建任务→commit 真实样本→起 job→assignments 含导入的教师/考试；换一组数据→结果不同。
- [ ] Commit: `fix(scheduling): solve real imported task data, not hardcoded fixture (Epic1.6)`

### Task 1.7 — 校验报告真实化（容量/时间冲突/班主任规则）
- [ ] `ExamTaskService.aggregateReport` / `ImportValidationService`：真实计算 cap(座位>容量)、time(同教师/同考场时间重叠)、rules(班主任回避本班)分类计数，填充 6 大分类(非全 0)。
- [ ] 测试：构造容量超限/时间冲突样本 → 报告对应分类 count>0。
- [ ] **验收**：Step2 报告反映真实问题。
- [ ] Commit: `feat(import): real validation categories (Epic1.7)`

### Task 1.8 — 前端接线 + 移除 mock 兜底(R6)
- [ ] `quick.jsx`：上传后调 `/import/{kind}/commit`；移除 `QK_VALIDATION_DATASETS`/`SLOT_META`/假日志 setTimeout 兜底；后端不可用→显式错误态(不再回退假数据)。
- [ ] `quick-step1-4.jsx`：移除写死 `qkResultData`/`QK_AI_SUMMARIES`；空态/错误态用真实四态。
- [ ] Dashboard.html 进 cache-bust。
- [ ] **验收**：导入自己的 Excel → Step4 结果是你的数据；断网后端→明确报错而非假装成功。
- [ ] Commit: `feat(quick): wire real import-commit, remove mock fallback (Epic1.8)`

---

## Epic 2 任务序列（可与 Epic 1 并行）

### Task 2.1 — 角色枚举细分 + 迁移 V12
- [ ] V12 迁移：role 表补 `JIAOWU`(教务)/`TEACHER`(教师)/`VIEWER`(查看者)；映射现有 ADMIN→JIAOWU(或保留 ADMIN 作教务超集)；演示账号 `must_change_password=true`。
- [ ] **验收**：role 表含 4 角色；测试。
- [ ] Commit: `feat(security): fine-grained roles JIAOWU/TEACHER/VIEWER (Epic2.1)`

### Task 2.2 — 方法级 RBAC
- [ ] 写失败测试 `RbacAuthorizationTest`：TEACHER/VIEWER 调 `/admin/**`→403；JIAOWU 调改派/发布→200；TEACHER 调改派→403。
- [ ] `SecurityConfig` 加 `@EnableMethodSecurity`；`SessionAuthenticationFilter` 装配 `ROLE_<role>` authority。
- [ ] AdminPlatformController 类级 `@PreAuthorize("hasRole('SUPER_ADMIN')")`；写操作控制器加 `hasAnyRole('SUPER_ADMIN','JIAOWU')`；只读统计加 `hasAnyRole(...,'VIEWER')`。
- [ ] 跑测试至通过；回归既有 35 测试套件(避免误伤合法调用)。
- [ ] **验收**：越权 403；合法 200；既有测试全绿。
- [ ] Commit: `feat(security): method-level RBAC on all endpoints (Epic2.2)`

### Task 2.3 — 演示密码生产守卫
- [ ] 把 `V4/V9/V10` 中的 demo 账号 seed 拆到独立 `db/migration-dev/`；`application.yml` 默认 locations 仅 `db/migration`；`application-dev.yml` 追加 `db/migration-dev`；private/saas **不含** dev seed。
- [ ] `application-private.yml`/`saas.yml`：`jkxt.auth.captcha-debug=false`。
- [ ] 登录页移除硬编码预填凭据(login.jsx:628-632)。
- [ ] **验收**：private 部署全新库 → 无 admin/Super001!!! 弱密码；captcha 不返回 debugAnswer。
- [ ] Commit: `fix(security): demo seeds dev-only, no prod backdoor (Epic2.3)`

---

## M1 验收（里程碑级，必须用真实数据）
1. 全新 private 库部署 → 用真实学校 Excel(教师/考场/考试)导入一个任务 → 排考结果**包含导入的教师与考试**；换另一组数据 → 结果不同。
2. 数据不完整 → 明确报错(SCHEDULE_INPUT_INCOMPLETE)，不再默默解 fixture。
3. TEACHER/VIEWER 角色 curl 超管端点 → 403；JIAOWU 可排考/发布。
4. 全新 private 库无演示弱密码；captcha-debug 关闭。
5. `backend\mvnw.cmd test` 全绿（既有 35 + 新增）；`code-review-graph` gate 通过。
6. 前端无 mock 兜底：断后端→报错态而非假成功。

## 风险
- R-A：真实数据规模(数百教师/数十考试)下 CP-SAT 可行性/性能——需准备真实规模压测，infeasible 按 R2 报错并给冲突解释。
- R-B：source_task_id 方案与既有 org 级主数据查询的交互——所有 list 查询需显式 `source_task_id IS NULL` 过滤主数据页，避免任务导入数据泄漏到名册页。
- R-C：RBAC 加严可能误伤既有 E2E harness 的调用——回归 `scripts/p11/global-fullflow-e2e.cjs` 并按角色调整测试账号。
- R-D：Docker 重建慢——每个 Task 完成跑聚焦测试，里程碑末跑全量 + 重建 grep 编译错误(#9)。

## 执行方式
M1 共 11 个 Task（Epic1 ×8 + Epic2 ×3）。建议 `subagent-driven-development`：每 Task 一个 fresh subagent，Task 间 review；Epic1/Epic2 可交错并行(独立)。每 Task 独立提交，里程碑末跑全量测试 + gate + 真实数据验收。
