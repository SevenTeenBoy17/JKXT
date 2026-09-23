# 三入口与演示沙盒隔离 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 普通账号与超管分别登录，游客一键进入可操作、可重置且不污染正式学校数据的独立演示环境，并以两轮真实业务测试证明隔离边界。

**Architecture:** 共享登录组件但分离入口、用途与会话；服务端在签发 Cookie 前决定并校验入口。演示使用独立实例/数据库/Redis、受限 DEMO 身份、访客工作区、代次提交保护和默认拒绝策略。现有正式业务保持原权限及 private 固定机构保护。

**Tech Stack:** 现有 React UMD/运行时 Babel、原生 JavaScript；Java 21 / Spring Boot 3.3.13 / Spring Security / JPA+JDBC；PostgreSQL 16 / Redis 7 / Flyway；OR-Tools 9.15.6755；JUnit 5/MockMvc、Node 原生断言、浏览器实测。

**Spec:** `D:/VB/JKXT/JKXT V2.0/docs/superpowers/specs/2026-09-20-login-entry-isolation-design.md`；SHA256 `C4F8C38CCED609D8C019E4C4A23E31409A6120A3F1311AECA82436A53AF865C3`。执行者须同时读取规格和本计划；本计划不降低其 AC-01～AC-16。

## Global Constraints

- 正确源目录：`D:/VB/JKXT/JKXT V2.0`；不修改嵌套发布快照。
- 同一浏览器配置文件、同一入口只保持一个当前账号；新登录替换该入口的旧账号，不影响其他入口。
- 生产演示站还必须与正式站使用不同 hostname，并使用 host-only Cookie；只有换端口不满足生产隔离。
- 原 `JKXT_SESSION` 不用于新入口恢复。升级时旧会话失效并要求重新登录；不能按旧快照无感提升为新用途。
- 初始化 120 名完全合成教师，包含班主任、主/副监考资格、学科、不可用时段等；配套考试和考场，展示合理中文名称。不得复制正式学校个人数据。
- 活跃会话空闲 30 分钟失效，工作区创建后绝对 2 小时失效；频繁请求不能无限延长绝对期限。
- 每工作区最多 150 名教师、40 个考场、100 场考试；上传文件上限 10 MiB，并另做压缩展开大小、工作表/行数和类型校验。
- 同工作区至多 1 个求解作业并行，单作业预算 30 秒；超限需明确提示，不假报完成。实例活跃工作区上限 20，超过返回可重试繁忙状态。
- `.env.private` 不修改；不删除、重置、覆盖正式数据库或其他用户改动；不因计划中出现命令而获得生产切换权限。
- 不换框架、不改导航结构和品牌主题；演示不可用模块显示原因，不能导向真实发布或假报操作成功。
- 任何质量门缺失、必测断言未执行或未解决 P0/P1 均为 NO-GO；分数不能抵消。

## Review Focus

1. 同入口另一标签换账号后，旧表单随新 Cookie 发出：Task 3/7/11 必测 `SESSION_CONTEXT_CHANGED`，而非仅取消前端请求。
2. 浏览器原生 EventSource/下载没有用途 header：Task 6/7/11 用实际下载与 SSE 验证；不得只用可任意加 header 的 API 客户端。
3. 请求鉴权后暂停，期间发生重置/过期：Task 4/6/11 用可控屏障强制交错，旧任务成功、失败和进度回调全部不得写回。
4. 两个标签首次进入、响应丢失、他人重放幂等键：Task 4/8/11 验证访客归属和唯一工作区，不能将一个按钮的防抖当服务器幂等。
5. 有错 Excel 导入、调整后导出旧修订、伪装文件/压缩炸弹：Task 5/6/11 反读工作簿和 PDF；错误不半提交、不以 200/下载完成冒充正确结果。

---

## 0. 本计划状态与执行前置条件

2026-09-20，用户已确认书面规格方向并要求继续实现。本文件是本轮新增的**待审阅实施计划**，不是已经执行的代码、测试或上线报告。所有任务框保持未勾选。

当前 Goal `goal-ae1ed4ba71f7b99f` v4，`continue`；round `round-20260920T143811.3190580Z-6447d93c`。沿用目标，不删除旧验收条款或旧门禁。当前 `planning_status`、`plan_task` 仍不可调用；真实 Hook 送达、TaskAnalysis、正式 Evidence 与注册质量门保持 MISSING。发现描述符不代表调用成功。

执行前必须同时满足：

1. 用户审阅本计划并选择执行方式；推荐逐任务子代理实施、独立复核，原因是鉴权与异步任务的接口耦合多，出错代价高。
2. 恢复真实 `planning_status → plan_task`，绑定 ENTRY-01～07、AC-01～16、文件租约、风险与下面任务；不得用 shell 提交替代真实规划门。历史临时例外不等于正式验收放行。
3. 由受支持的核心流程处理 QualityProfile 的旧目标绑定：当前文件 `profile_id=jkxt-school-e2e-20260909-v1` 仍指向另一旧 goal。保留十四个固定门禁，不自行改成功标记或删掉不便执行的项目。
4. 创建含当前必要脏改动的隔离候选，保留完整输入清单与回滚点。只从旧 HEAD 建 worktree 会遗漏用户当前修改，不可作为当前产品候选。

源码扫描已确认：单 Cookie/共享登录尚存在；DEMO 不在 V2 `app_user.role` 约束；最新迁移文件为 V30；Quick 有实际 SSE；测试 profile 有数据库隔离启动保护。上述是代码事实，不是运行验证。

### 本轮写租约与回滚点

本轮仅创建本计划文件。创建前不存在，父目录已核验；Git基线 `cc84611f8631107eaf79a1d53fad91ed66f3b024`。回滚只移除此新增文档，保留原工作区、规格和 Supervisor 审计。本轮不 stage、不 commit、不启动环境、不变更测试。

### 执行顺序与边界

`Task 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12`。

后端会话/工作区协议先固定，前端随后接入；测试辅助方法迁移后才允许整套集成测试解释为有效结果。每任务的 2–5 分钟小步骤按“失败测试→最小实现→通过→独立审阅→目标文件检查点”执行。检查点只包含该任务拥有的文件；不自动提交用户其他工作。

演示首期业务面明确为现有 **Quick 排监考闭环**。ExamPlan/JOINT、真实审批、正式发布、平台管理和外部 AI 在演示实例默认拒绝且页面明确标记不可用；正式账号的这些原有功能必须回归。不能先开放宽泛路由再寄希望于页面不调用。

## 1. 全局类型与接口契约

下列名字是本计划新建接口，不声称当前仓库已有。新 Java 文件均位于 `backend/src/main/java/com/jkxt/`，新测试位于 `backend/src/test/java/com/jkxt/`。

```java
// security/auth/PortalPurpose.java
public enum PortalPurpose { SCHOOL, PLATFORM, DEMO }

// security/auth/SessionEnvelope.java，包内使用，不直接序列化给浏览器
public record SessionEnvelope(
    SessionPrincipal principal, PortalPurpose audience, long authVersion,
    String context, String csrfToken, java.time.Instant expiresAt,
    java.util.UUID workspaceId, Long generation) {}

// demo/DemoLease.java，捕获后不可随后台任务自动替换为新代次
public record DemoLease(java.util.UUID workspaceId, java.util.UUID orgId,
    long generation, java.time.Instant expiresAt) {}

// demo/WorkspaceFence.java，正式请求传 Optional.empty()，不能由客户端选择
public interface WorkspaceFence {
  void assertReadable(DemoLease lease);
  <T> T commit(DemoLease lease, java.util.function.Supplier<T> mutation);
}
```

`WorkspaceFence.commit` 必须拥有或加入同一数据库事务：锁工作区行，验证 READY、捕获的 org/generation、期限，再执行 mutation；事务提交前再次核验期限；不能把检查和 mutation 分在两个独立事务。耗时计算/文件解析在锁外，结果落库在锁内。正式请求不经过“伪 demo lease”绕过权限。

任务内 Java 路径缩写统一相对 `backend/src/main/java/com/jkxt/`；测试路径相对 `backend/src/test/java/com/jkxt/`。同一行未写目录的后续文件与该行前一个文件同包，跨包必须写全。前端和脚本路径相对产品根目录。下列辅助类型随拥有它们的任务创建，不引用未来任务的测试辅助代码：

```java
// Task 4: DemoDtos 内部记录；WorkspaceGrant 不直接作为 HTTP 响应，不能泄露 envelope。
record VisitorGrant(java.util.UUID visitorId, String startupContext,
    java.time.Instant expiresAt) {}
record WorkspaceGrant(SessionEnvelope envelope, DemoLease lease, String state) {}
record CleanupSummary(int expired, int cleaned, int retryableFailures) {}
// Task 5: DemoQuotaService 内部接口，关闭未 commit 的预留必须释放配额。
interface Reservation extends AutoCloseable {
  void commit();
  @Override void close();
}
```

新增会话响应保留原 `user/org/term/permissions/onboarded`，追加：

```json
{
  "audience": "DEMO",
  "sessionContext": "opaque-non-bearer-value",
  "csrfToken": "session-bound-value",
  "expiresAt": "ISO-8601-server-time",
  "demo": { "enabled": true, "generation": 1, "canReset": true }
}
```

该 JSON 是字段示意，不是真实令牌或测试成功响应。正式用途 `demo.enabled=false`。三个用途 token/context 分开缓存，`/auth/me` 是首次发现例外；业务请求必须带 `X-JKXT-Portal`（正式）、`X-JKXT-Session-Context`，写请求再带 `X-JKXT-CSRF`。不允许调用者覆盖安全 header 或通过其声明提升权限。

前端 `SessionResponse` 指已验证的上述响应：原字段类型严格沿用 `AuthDtos.LoginResponse/MeResponse`（user/org/term/permissions/onboarded），追加 `audience:SCHOOL|PLATFORM|DEMO`、`sessionContext:string`、`csrfToken:string`、`expiresAt:string`、`demo:{enabled:boolean,generation?:number,canReset?:boolean}`。缺字段、未知用途或与当前入口不匹配必须拒绝挂载，不能用空对象默认成功。

错误代码冻结：`AUTH_PORTAL_MISMATCH`(403)、`AUTH_CSRF_INVALID`(403)、`SESSION_CONTEXT_CHANGED`(409)、`DEMO_DISABLED`(403)、`DEMO_EXPIRED`(401)、`DEMO_RESETTING`(409)、`DEMO_STALE_GENERATION`(409)、`DEMO_QUOTA_EXCEEDED`(429)、`DEMO_OPERATION_FORBIDDEN`(403)。均沿用 `ApiErrorResponse` 与 traceId，不包含内部连接串、口令或 Cookie。

## Task 0: 冻结候选、输入清单与隔离护栏

**Files:** 新建 `scripts/login-isolation/candidate-manifest.ps1`、`test-candidate-manifest.ps1`、`compose.unit.yml`、`README.md`；输出仅 `artifacts/login-isolation/<run-id>/`。不改正式 Compose 或 `.env.private`。

**Interfaces:** consumes 当前 Git HEAD、已批准规格/计划哈希；produces `candidate-manifest.json`，字段 `sourceRoot,head,files[{path,sha256}],excluded,runId,createdAt`。实现任务只使用已核对的候选输入。

- [ ] 先写清单护栏测试：不存在目录、指向源根、越界相对路径、遗漏 dirty tracked 文件均失败；fixture 在唯一临时目录，不调用删除正式路径。
- [ ] 运行 `pwsh -NoProfile -File scripts/login-isolation/test-candidate-manifest.ps1`，确认未实现脚本时失败；不得以一份空 JSON 通过。
- [ ] 使用 `superpowers:using-git-worktrees` 的执行期流程准备候选；再按明确清单复制必要未提交内容。把配置秘密留在候选 web root 之外，不复制整个 `artifacts`/备份/`.git` 进静态站。

```powershell
# 在候选脚本内采用此安全边界，最终复制清单逐文件落盘并记录哈希。
$sourceRoot = [IO.Path]::GetFullPath($Source)
$candidateRoot = [IO.Path]::GetFullPath($Destination)
if ($sourceRoot -eq $candidateRoot) { throw 'Candidate must differ from source' }
foreach ($relativePath in $SelectedPaths) {
  $absolutePath = [IO.Path]::GetFullPath((Join-Path $sourceRoot $relativePath))
  if (-not $absolutePath.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw 'Path outside source manifest'
  }
  if ($relativePath -eq '.env.private') { throw 'Private configuration is excluded' }
}
```

- [ ] 验证源/候选文件哈希对应、dirty 文件未遗漏；保留受控配置与候选版本回退说明。只创建隔离资源；任何正式切换另行审批。
- [ ] 为 Tasks 1–9 的数据库测试先创建 `compose.unit.yml`：沿用现有隔离测试护栏所需的 `JKXT_TEST_*` 环境契约，PG/Redis/测试运行器为本候选独占资源，DB名 `jkxt_test_entry_<run_id>`，服务地址 `jkxt-test-entry-db` / `jkxt-test-entry-redis`，凭据在测试运行时注入且不输出。不绑定正式端口、卷、连接或 `.env.private`。先用错误连接参数证明 `TestDatabaseIsolationGuard` 拒绝，再在隔离连接运行测试；Task 10 是浏览器双站运行环境，不是首次建立测试数据库。后续文中 `mvn` 命令均在该候选测试运行器的 backend 目录执行，不向宿主默认数据库运行。
- [ ] 独立复核清单与范围，建立检查点后进入 Task 1。失败状态保留，不用新清单掩盖遗漏。

## Task 1: 数据约束、用途与部署模式基础

**Files:** 新建 `backend/src/main/resources/db/migration/V31__login_entry_and_demo_workspace.sql`、`security/auth/PortalPurpose.java`、`demo/DemoProperties.java`；修改 `security/domain/UserAccount.java`、`common/config/DeployProperties.java`、`security/ProductionSecurityGate.java`；新建测试 `demo/DemoSchemaIntegrationTest.java`、`demo/DemoDeploymentGuardTest.java`。若执行时 V31 已被占用，停止重编号审阅，绝不覆盖迁移。

**Interfaces:** produces `PortalPurpose`、`UserAccount.getAuthVersion():long`、`DeployProperties.isDemo():boolean`、工作区/代次/幂等表。consumes Task 0 独立数据库清单。

- [ ] 写迁移测试：V30→V31 保留原学校/用户/权限；角色原四值仍合法；DEMO 只允许由 demo 服务创建；匿名正式注册不因此开放；同一访客不能有两个活跃工作区。
- [ ] 在测试库先跑 `mvn -B -Dtest=DemoSchemaIntegrationTest,DemoDeploymentGuardTest test`，记录真实失败原因与测试数量；未配置隔离库应由 `TestDatabaseIsolationGuard` 拒绝，不能算测试通过。
- [ ] 迁移增量添加 `app_user.auth_version bigint NOT NULL DEFAULT 0`，替换角色 CHECK 增加 DEMO；权限仍由业务控制，不改历史 V2。针对 password_hash/role/org_id/status/deleted_at 的变化递增 auth_version，登录计数/最后登录时间变化不递增。

```sql
ALTER TABLE app_user ADD COLUMN auth_version bigint NOT NULL DEFAULT 0;
ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check
  CHECK (role IN ('SUPER_ADMIN','ADMIN','VIEWER','TEACHER_QUERY','DEMO'));
CREATE FUNCTION increment_app_user_auth_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.password_hash, NEW.role, NEW.org_id, NEW.status, NEW.deleted_at)
     IS DISTINCT FROM
     ROW(OLD.password_hash, OLD.role, OLD.org_id, OLD.status, OLD.deleted_at) THEN
    NEW.auth_version := OLD.auth_version + 1;
  ELSE
    NEW.auth_version := OLD.auth_version;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_auth_version_changed BEFORE UPDATE ON app_user
FOR EACH ROW EXECUTE FUNCTION increment_app_user_auth_version();
CREATE TABLE demo_workspace (
  id uuid PRIMARY KEY, visitor_id uuid NOT NULL, org_id uuid NOT NULL,
  generation bigint NOT NULL CHECK (generation > 0),
  state varchar(16) NOT NULL CHECK (state IN ('READY','RESETTING','FAILED','EXPIRED')),
  created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
  last_activity_at timestamptz NOT NULL, cleanup_after timestamptz,
  UNIQUE (org_id), CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX demo_one_live_workspace_per_visitor ON demo_workspace(visitor_id)
  WHERE state <> 'EXPIRED';
```

- [ ] 同一迁移建立 `demo_generation(workspace_id,generation,org_id,user_id,cleanup_state)`、`demo_operation(workspace_id,expected_generation,operation_id,kind,request_hash,state,result_json)`；分别以 `(workspace_id,generation)`、`(workspace_id,operation_id)` 为主键，org 归属一经记录不可重指向另一工作区；操作键相同而请求摘要不同返回409。建表顺序、外键和清理策略须在迁移测试实际验证。
- [ ] `demo` 模式启动检查独立 DB/Redis 标识、仅 DEMO 业务会话、host-only Cookie、禁用外部配置；不能把所有非 SaaS 部署都误当安全。旧 seed 机构不得成为可登录 demo 工作区，`DemoAccountSecurityGuard` 继续运行。
- [ ] 测试通过并检查 V1～V30 哈希未改；审阅 SQL/约束/触发器及回滚演练方案。此任务不在正式库执行迁移。

## Task 2: 独立会话、上下文与 CSRF

**Files:** 新建 `security/auth/SessionEnvelope.java`、`security/auth/PortalSessionPolicy.java`、`security/auth/SessionGuard.java`；修改 `security/auth/SessionService.java`、`security/auth/SessionAuthenticationFilter.java`、`security/auth/AuthRequestAttributes.java`、`security/SecurityConfig.java`；测试新建 `security/PortalSessionPolicyTest.java`、`security/SessionPurposeIntegrationTest.java`、`security/PortalCsrfIntegrationTest.java`。

**Interfaces:** `PortalSessionPolicy.allows(PortalPurpose,String):boolean`；`SessionService.create(SessionPrincipal,PortalPurpose,long):CreatedSession`，CreatedSession 附 envelope；`find(String,PortalPurpose):Optional<SessionEnvelope>` 是不续期的读取；`SessionGuard.validateCurrent(SessionEnvelope):SessionEnvelope` 从当前账户/机构/权限读回有效身份。

- [ ] 首先创建入口策略单元测试，包含学校三角色、SUPER_ADMIN、DEMO、空值、未知角色。

```java
@Test void superAdminCannotUseSchoolPortal() {
  assertFalse(PortalSessionPolicy.allows(PortalPurpose.SCHOOL, "SUPER_ADMIN"));
  assertTrue(PortalSessionPolicy.allows(PortalPurpose.PLATFORM, "SUPER_ADMIN"));
  assertFalse(PortalSessionPolicy.allows(PortalPurpose.PLATFORM, "ADMIN"));
  assertFalse(PortalSessionPolicy.allows(PortalPurpose.SCHOOL, "DEMO"));
}
```

- [ ] 运行 `mvn -B -Dtest=PortalSessionPolicyTest test`，先见未实现失败。实现如下决策，不使用请求体 role 判断：

```java
public static boolean allows(PortalPurpose purpose, String role) {
  if (purpose == null || role == null) return false;
  return switch (purpose) {
    case PLATFORM -> "SUPER_ADMIN".equals(role);
    case SCHOOL -> java.util.Set.of("ADMIN", "VIEWER", "TEACHER_QUERY").contains(role);
    case DEMO -> "DEMO".equals(role);
  };
}
```

- [ ] Cookie 按用途分名，Redis key 追加用途并保持用户→会话撤销索引。旧 Cookie/旧序列化会话一律不恢复。正式环境 `__Host-`、Secure、HttpOnly、Path=/、无Domain；重复同名 Cookie、未知用途不得按遍历顺序猜测。
- [ ] 给 SessionGuard 测试禁用用户/机构、软删除、改密、角色变化、权限表变更：每次请求读当前值，角色/权限快照不能继续授权。读取失败返回可识别失败，不回退旧缓存授权；demo session 按 workspace 当前代次解析，见 Task 4。
- [ ] 鉴权后检查 sessionContext；除 `/auth/me` 外，缺失/不匹配返回409。对所有写操作验证同会话 CSRF；登录/游客初始化先检查 Origin 与 JSON。普通 multipart 上传不强迫改为 JSON，仍验证其 CSRF header。
- [ ] 过滤器顺序固定：来源/公开入口规则→目的 Cookie 选择→当前身份/租户→上下文→CSRF→demo 路由策略→既有 RBAC→业务。`finally` 清理 TenantContext 和请求身份；异步业务显式传递 immutable lease。
- [ ] 跑用途/CSRF 集成测试：互换 token、Cookie、header、缺失 Origin、过期、Redis停止、private fixedOrg、SaaS代管权限；成功与拒绝都核对业务副作用。检查点仅包含上述路径。

## Task 3: 登录控制器、失败无会话与安全退出

**Files:** 修改 `security/api/AuthController.java`、`AuthDtos.java`、`security/auth/AuthService.java`；必要的权限提取新建 `security/auth/CurrentAccountAccess.java`；测试新建 `security/PortalHttpFixture.java`、`PortalHttpFixtureTest.java`、`PortalLoginIntegrationTest.java`，补充 `security/AuthIntegrationTest.java`、`AuthLockoutAcceptanceTest.java`、`AuthIpThrottleAcceptanceTest.java`。

**Interfaces:** `AuthService.login(LoginRequest,String,PortalPurpose):LoginResult`；`CurrentAccountAccess.load(UUID userId):SessionPrincipal` 读取真实角色/机构/权限；DTO 追加全局协议字段，不删原字段。

本任务创建 `PortalHttpFixture`，通过真实 MockMvc 登录而非设置 SecurityContext；嵌套 `TestAccount(UUID id,String username,String password)`、`AuthenticatedSession(Cookie cookie,String context,String csrfToken,PortalPurpose purpose)`。方法：`login(PortalPurpose,TestAccount):AuthenticatedSession`、`loginResult(PortalPurpose,TestAccount):MvcResult`、`apply(MockHttpServletRequestBuilder,AuthenticatedSession):MockHttpServletRequestBuilder`、`expectMe(AuthenticatedSession,int):void`、`sessionCount(UUID):long`、`superAdmin():TestAccount`。每次真实验证码独立获取；测试账号仅在隔离 profile 创建，不写配置或日志。Task 9 复用此 helper，不另造不同契约。

- [ ] 写真实登录拒绝测试，使用测试 fixture 新建账号与独立验证码；先登录正确入口保存会话，再用同一账号走错误入口。

```java
// fixture 是本任务定义的 PortalHttpFixture，测试先创建其隔离账号。
var existing = fixture.login(PortalPurpose.PLATFORM, fixture.superAdmin());
long sessionsBefore = fixture.sessionCount(fixture.superAdmin().id());
var rejected = fixture.loginResult(PortalPurpose.SCHOOL, fixture.superAdmin());
assertEquals(403, rejected.getResponse().getStatus());
assertNull(rejected.getResponse().getHeader("Set-Cookie"));
assertEquals(sessionsBefore, fixture.sessionCount(fixture.superAdmin().id()));
fixture.expectMe(existing, 200);
```

- [ ] `mvn -B -Dtest=PortalLoginIntegrationTest test` 先失败；新增两个映射，旧 login 只映射 SCHOOL。

```java
// 三个入口共享内部校验流程；目的常量来自端点，不来自请求体。
@PostMapping("/school/login")
public LoginResponse schoolLogin(@Valid @RequestBody LoginRequest body,
    HttpServletRequest req, HttpServletResponse res) {
  return loginFor(PortalPurpose.SCHOOL, body, req, res);
}
@PostMapping("/platform/login")
public LoginResponse platformLogin(@Valid @RequestBody LoginRequest body,
    HttpServletRequest req, HttpServletResponse res) {
  return loginFor(PortalPurpose.PLATFORM, body, req, res);
}
```

`loginFor` 私有签名为 `(PortalPurpose,LoginRequest,HttpServletRequest,HttpServletResponse):LoginResponse`；保持现有 IP 限流/验证码/锁定逻辑，调用新 AuthService；仅 LoginResult 成功返回才写 Cookie。AuthService 在登录计数和成功审计之前检查入口、机构、角色；错误入口保留原有效 session。

- [ ] 同入口成功切账号撤销该浏览器提交的旧用途会话，其他入口 Cookie 不变；并发独立浏览器登录仍保持合法，不错误全用户踢下线。`/me` no-store，返回 context/CSRF；退出失败不返回 ok=true。
- [ ] 改密递增授权版本、撤销其他会话并为当前会话更新合法版本/context/CSRF；若跨 DB/Redis 更新失败则要求重新登录，不能留下可用旧权限。测平台强制退出/禁用、两用途索引撤销，以及修改口令后原口令失效。
- [ ] 运行新增测试和原三个 Auth 测试，保留原并发登录成功、锁定计数、错误包断言；测试辅助方法迁移须走 Task 9 完整审查。

## Task 4: 访客工作区、幂等初始化和原子重置

**Files:** 新建 `demo/api/DemoController.java`、`DemoDtos.java`、`demo/DemoLease.java`、`WorkspaceFence.java`、`JdbcWorkspaceFence.java`、`DemoVisitorService.java`、`DemoWorkspaceService.java`、`DemoWorkspaceRepository.java`、`DemoSeedService.java`、`DemoCleanupService.java`；测试新建 `demo/DemoTestFixture.java`、`DemoWorkspaceIntegrationTest.java`、`DemoResetConcurrencyTest.java`、`DemoExpiryTest.java`。

**Interfaces:** `DemoVisitorService.ensureVisitor(HttpServletRequest,HttpServletResponse):VisitorGrant`；`DemoWorkspaceService.start(UUID visitorId,String operationId,String requestHash):WorkspaceGrant`；`reset(DemoLease,String operationId):WorkspaceGrant`；`DemoSeedService.initialize(UUID orgId,int teacherCount):UUID` 返回仅演示用户ID；`DemoCleanupService.sweep(Instant now):CleanupSummary`。VisitorGrant 包含 visitorId/启动context/到期，WorkspaceGrant 包含 SessionEnvelope 与 workspace 状态。参数来源必须是服务端解析，不允许 JSON 自报 visitorId。

测试辅助 `DemoTestFixture.reset(UUID workspaceId,long generation):WorkspaceGrant` 使用该 fixture 已建立的合法当前访客调用服务；`fingerprint(UUID visitorId):String` 独立读取该访客全部机构业务行与受控文件哈希后排序生成摘要，不使用被测服务自报计数。Task 6 直接复用该辅助；HTTP 负例必须走真实鉴权，不用它越过入口。

- [ ] 创建 fake Clock + 可控 Executor 的测试：双启动并发、同键不同摘要、另一访客重放、初始化失败、资源配额、重置失败、绝对期限；运行 `mvn -B -Dtest=DemoWorkspaceIntegrationTest,DemoResetConcurrencyTest,DemoExpiryTest test`，先见失败再实现。
- [ ] 初始化先记录 RESETTING+CREATE 操作，事务分配正式注册的演示 org 和 DEMO 用户、合成资料；完成才原子改 READY。半完成资源保留明确清理任务，不签发可用业务身份。
- [ ] 为每代创建不同 org/user，并在 demo_generation 中保存旧代归属；工作区ID保持稳定。重置先在短事务中撤销旧代并标 RESETTING，再建新代数据，最后 CAS 发布新代。旧 org 延迟清理，不能先删除历史记录后假装重置可回滚。原固定机构完全不参与。

```sql
-- 持行锁并验证身份与到期后，只允许当前代开启一次重置；同操作重试读取结果。
UPDATE demo_workspace SET state = 'RESETTING'
WHERE id = :workspace_id AND generation = :expected_generation
  AND state = 'READY' AND expires_at > clock_timestamp();
-- 最终 READY 切换还须核验 operation_id、期限、前态及新代资源完整。
```

- [ ] Demo Cookie 只指向受控会话/工作区；数据库当前代为权威，普通恢复和重置不重写 Cookie。`/me` 可取得新代 context；旧业务请求携带旧 context/lease 则409。context/CSRF 绑定当前 generation，跨 DB/Redis 未同步时失败关闭，不读旧org。
- [ ] WorkspaceFence 对事务写锁行、比较 capture generation/org、检查服务端期限；reset与结果提交按锁顺序线性化。成功/失败/进度回调都使用捕获代次，不根据当前 workspace 重新制造 lease。
- [ ] 过期终态不能被 reset 重试恢复；后台轮询不续期。每分钟扫过期工作区，15分钟清理目标并重试；先比对 demo_generation 登记归属、DB专用标识、文件根，再删除该代org与受控文件。不能只按客户端orgId清理，也不能扫描并清空全部组织。
- [ ] 用两个访客的数据库/文件指纹断言互不变化；清理中断重启后恢复。记录错误场景而非跳过，建立检查点。

## Task 5: DEMO 权限白名单与无外发保证

**Files:** 新建 `demo/DemoRoutePolicy.java`、`DemoRequestGuard.java`、`DemoQuotaService.java`、`DemoSideEffectPolicy.java`、`demo/api/DemoSimulationController.java`、`demo/api/DemoOverviewController.java`；修改 Task 4 的 `demo/api/DemoDtos.java`、`security/SecurityConfig.java`、`notify/service/NotificationService.java`、`notify/service/NotificationDispatcher.java`；测试新建 `demo/DemoRoutePolicyTest.java`、`DemoAuthorizationIntegrationTest.java`、`DemoSideEffectTest.java`。

**Interfaces:** `DemoRoutePolicy.allows(String method,String normalizedPath):boolean`；`DemoQuotaService.reserve(DemoLease,String operation,long amount):Reservation`；Reservation 为 AutoCloseable，成功提交调用 `commit()`，失败释放；`DemoSideEffectPolicy.requireAllowed(String effect):void`；模拟发布只写 `demo_operation` 的模拟结果，不调用正式 publish。

`DemoOverviewController.overview():DemoOverview` 从服务端请求 lease 取数；`DemoDtos.DemoOverview(long teacherCount,long roomCount,long examCount,List<DemoTaskSummary> recentTasks)`，`DemoTaskSummary(UUID taskId,String title,String status,Instant updatedAt)`。最多五项当前工作区任务；无数据返回真实0与空数组。`DemoSimulationController.simulatePublish(UUID taskId,String operationId):SimulationResult` 仅写沙盒模拟记录；`SimulationResult(UUID taskId,String operationId,String label,Instant createdAt)` 的 label 固定“模拟发布，未通知真实教师”。

- [ ] 先为下表每条允许、错误HTTP方法、编码路径、伪造ID、未注册路由写参数化测试；`RequestMappingHandlerMapping` 枚举所有控制器，未列出的全部拒绝，不自动导入新路由。
- [ ] 完成最小能力集 `tenant:read/masterdata:manage/exam:manage/schedule:manage`，仅与 route/method 白名单联合使用；DEMO 权限从固定受控定义加载，不使用跨机构同名系统角色回退。

| 方法 | 白名单路径（均以 `/api/v1` 为前缀） | 额外限制 |
|---|---|---|
| GET/POST | `/teachers` `/classes` `/classrooms` `/subjects` | 各集合显式四条，非任意collection |
| GET/PUT/DELETE | 上述四集合的 `/{id}` | 服务端归属检查；删除关联约束仍执行 |
| GET | `/import/templates/{kind}` `/import/{kind}/template` | kind仅teachers/classes/classrooms/subjects/exams |
| POST | `/import/validate` `/import/{kind}` `/import/{kind}/validate` `/import/{kind}/commit` | 相同kind集合、文件/行/解压限额；commit才落库 |
| GET/POST | `/exam-tasks` | 新建限额，分页不越租户 |
| GET/DELETE | `/exam-tasks/{taskId}` | 仅当前工作区任务，发布态限制不变 |
| POST | `/exam-tasks/{taskId}/uploads` `/imports/validate` `/schedule-jobs` | 后两项也完整拼接在 `/{taskId}` 后；不允许 sample=true 绕过 |
| GET | `/schedule-jobs/{jobId}` `/schedule-jobs/{jobId}/events` | job归属与捕获代次，逐事件检查 |
| GET | `/exam-tasks/{taskId}/assignments` `/exam-tasks/{taskId}/export` | run/revision明确，xlsx/pdf格式白名单 |
| POST | `/tasks/{taskId}/schedule/check-conflicts` `/tasks/{taskId}/schedule/undo` `/tasks/{taskId}/schedule/redo` | 基线业务约束和租户校验不变 |
| PATCH | `/tasks/{taskId}/schedule` | revision并发校验、提交栅栏 |
| POST | `/demo/workspace/reset` `/demo/tasks/{taskId}/simulate-publish` | 当前DEMO、context、CSRF、操作幂等；模拟结果显式标识 |
| GET | `/demo/overview` | 当前工作区统计，禁止全站缓存 |
| GET/POST | `/auth/me`(仅GET) `/auth/logout`(仅POST) | 按具体方法，不因本行组合放开反向方法 |

游客引导 `/demo/visitor`、`/demo/sessions` 是独立来源/限流规则，不纳入“所有认证路由放开”。必要工作台只读聚合使用新建 `/demo/overview`，由当前工作区真实数据聚合；不为填满仪表盘而开放整个 org/admin/settings API。

```java
@Test void unknownAndRealPublishRoutesAreDenied() {
  assertFalse(policy.allows("POST", "/api/v1/tasks/123/publish"));
  assertFalse(policy.allows("POST", "/api/v1/nl-policy/parse"));
  assertFalse(policy.allows("GET", "/api/v1/admin/accounts"));
  assertFalse(policy.allows("POST", "/api/v1/future-route"));
}
```

- [ ] 新增 `/demo/overview` 与模拟发布的 Controller/DTO 方法，白名单精确登记。仅使用服务端工作区org取数；不得使用全站统计缓存。
- [ ] 在已确认外发服务边界再次阻止 demo SMS/mail/webhook/收费AI；容器网络不连接公网出口。测试使用独立接收器，错误路由返回403且接收数0；本地沙盒教学通知标明“模拟”，不能调用 NotificationDispatcher。
- [ ] 启动5次/10分钟/IP、reset2次/分钟/访客、上传10次/分钟/工作区、solve3次/分钟/工作区；所有配额均服务端原子计数，新实例总20工作区并行，求解每工作区1个。新增限额属于本计划具体值，随计划审阅确认。
- [ ] 运行 `mvn -B -Dtest=DemoRoutePolicyTest,DemoAuthorizationIntegrationTest,DemoSideEffectTest test` 并审查默认拒绝覆盖，不能改DEMO为ADMIN解决403。计划链JOINT/真实审批仍拒绝。

## Task 6: 导入、排班异步、SSE 与修订导出接入

**Files:** 修改 `importer/service/ImportCommitService.java`、`ImportValidationService.java`、`tasks/api/ExamTaskController.java`、`tasks/service/ExamTaskService.java`、`masterdata/service/MasterDataService.java`、`scheduling/service/ScheduleJobService.java`、`ScheduleProgressSink.java`、`ScheduleEditService.java`；新建 `demo/DemoImportLimits.java`；测试新建 `demo/DemoBusinessFlowIntegrationTest.java`、`DemoJobFenceTest.java`、`DemoExportRevisionTest.java`。

**Interfaces:** 保留正式原方法，增加内部 `Optional<DemoLease>` 上下文捕获，不接受HTTP body传入lease。`ExamTaskService.export(UUID taskId,String format,UUID runId,long revision):byte[]`；原导出入口兼容明确最新修订，demo请求须指定已展示的run/revision。

- [ ] 写Excel错误不落库测试与受控竞态测试；用已有 POI helper 构造工作簿，不复制含生产信息文件。捕获任务在入口通过后阻塞，再 reset，随后释放。

```java
// 测试控制器使用两个 CountDownLatch；等待均设超时，不用任意 sleep 证明竞态。
assertTrue(afterAuthorization.await(5, java.util.concurrent.TimeUnit.SECONDS));
fixture.reset(lease.workspaceId(), lease.generation());
continueCommit.countDown();
assertEquals("DEMO_STALE_GENERATION", result.get(5, java.util.concurrent.TimeUnit.SECONDS).code());
assertEquals(beforeOtherVisitor, fixture.fingerprint(otherVisitor));
```

`DemoTestFixture`（已由 Task 4 创建）提供 reset/fingerprint；`lease` 为 DemoLease，`otherVisitor` 为 UUID。`result` 类型为 `Future<OperationOutcome>`；测试内定义 `record OperationOutcome(String code)`，捕获业务返回的稳定错误码而非任意异常都通过。

- [ ] 文件解析先验证完整批次，quota按最终去重后增量计算，提交用 WorkspaceFence；禁止通过多批导入绕过150教师/40考场/100考试。限制xlsx展开累计64MiB、每文件最多10工作表、最多10000行、单单元格32767字符；不执行公式，不跟随外部链接。恶意/损坏文件返回明确错误且无部分持久化。
- [ ] Quick的 `runAsync → persistResult` 捕获lease；`publish`独立进度事务及catch失败写入也用同一lease。30秒预算传入实际求解器并加调度截止，非只让UI超时。取消/过期后不写结果、通知或进度。
- [ ] `ScheduleProgressSink` 每个订阅保存自己的用途、会话、workspace/gen；缓冲回放与发送前验证。不凭同一个runId共享跨访客订阅；关闭时移除 emitter，自动重连重新鉴权，不保留撤销连接。
- [ ] 人工patch/undo/redo保留原硬约束与乐观修订校验，写入栅栏在同事务。导出选择指定run/revision，文本与工作簿标明“演示·模拟数据”、生成时间、代次和修订；revision不属于任务时拒绝，不能悄悄导出最新其他任务。
- [ ] 对不开放的 ExamPlanPlanning/JOINT 分支测试直接API仍403，正式账号原测试继续通过；不要添加一组未经验证的新demo求解路径。
- [ ] 运行三类新增测试及 RealDataScheduleFlowIntegrationTest、ScheduleEditApiIntegrationTest、ScheduleJobApiIntegrationTest；反读文件内容及类型而非只看下载大小。

## Task 7: 前端入口上下文与统一传输层

**Files:** 新建 `src/auth/entry-context.js`、`src/auth/session-context.js`、`src/auth/session-storage.js`；修改 `src/api.js`、`src/app.jsx`、`src/theme.js`、`src/onboarding.jsx`、`src/topbar.jsx`、`src/tasks.jsx`、`src/pages/quick-engine.jsx`、`src/pages/quick-step1.jsx`、`src/pages/quick-step4.jsx`、`src/pages/tasks-page.jsx`、`src/pages/task-detail.jsx`、`src/pages/people.jsx`、`src/pages/edit.jsx`、`src/pages/ai-policy.jsx`、`src/pages/quick.jsx`、`Dashboard.html`；新增 `scripts/login-isolation/test-entry-context.mjs`、`test-session-context.mjs`、`test-session-storage.mjs`。本任务只拥有这些调用点的传输/缓存隔离改动，Task 8 接手共享文件后才改界面。

**Interfaces:** UMD导出 `window.JKXTEntry.resolve(URL):{purpose,loginPath}`、`window.JKXTSession.accept(response):void`、`snapshot():{context,epoch,purpose}`、`invalidate(reason):void`；`api.download(path,filename,options):Promise<void>`；`api.subscribe(path,onEvent,options):{close()}`。单元测试可通过module.exports或vm沙盒加载同一源文件，不写另一套测试版逻辑。

- [ ] Node测试先验证旧query、未知入口、学校/平台直接路径与跨源redirect拒绝。

```javascript
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const entry = require('../../src/auth/entry-context.js');
assert.equal(entry.resolve(new URL('http://localhost/platform/login')).purpose, 'PLATFORM');
assert.equal(entry.resolve(new URL('http://localhost/Dashboard.html?portal=super')).loginPath, '/platform/login');
assert.equal(entry.resolve(new URL('http://localhost/?portal=unknown')).purpose, 'SCHOOL');
```

- [ ] 运行 `node scripts/login-isolation/test-entry-context.mjs` 先失败；实现固定路径和旧别名规范化，只能返回白名单目的地。Dashboard在首个相对资源前加入 `<base href="/">`，并验证原hash导航不退化；脚本依赖顺序 entry→session/storage→theme→现有vendor/UI依赖→api→页面→app。现有 theme 位于 head，不能只在底部补入口脚本而留下提前读旧缓存；所有深链接逐个刷新验证资源无404。
- [ ] `api.defaultBase/resolveUrl` 不再信任持久化的任意 apiBase 或跨源绝对URL；发送 Cookie/context/CSRF 前核验同源且 `/api/v1/` 路径，协议/用户名/密码/重定向跨源均拒绝。demoOrigin仅用于整页导航，不用于正式站带凭据跨源请求。固定部署代理路径由受控配置提供，拒绝 localStorage 注入。保留 `api.get(path,query,options)` 三参数、multipart 边界和原错误结构。
- [ ] `api.request` 每次捕获session epoch，按当前入口覆盖保留header，credentials固定；响应前再次比较epoch，不一致抛 `SESSION_CONTEXT_CHANGED`；仅当前epoch的401或身份相关409清敏感视图，不自动换另一个Cookie，普通修订冲突409保留业务冲突提示。迟到401不得触发当前新账号的onUnauthorized。公开引导与 `/me` 例外必须显式标记，不能靠路径含“auth”全豁免。

```javascript
const captured = JKXTSession.snapshot();
const target = new URL(url, location.origin);
if (target.origin !== location.origin || !target.pathname.startsWith('/api/v1/') ||
    target.username || target.password) {
  throw Object.assign(new Error('不允许向此地址发送登录信息'), { code: 'UNTRUSTED_API_ORIGIN' });
}
const response = await fetch(target, { ...options, redirect: 'error', credentials: 'same-origin', headers });
if (captured.epoch !== JKXTSession.snapshot().epoch) {
  throw Object.assign(new Error('账号已变化，请重新确认'), { code: 'SESSION_CONTEXT_CHANGED' });
}
```

- [ ] multipart、下载、SSE使用同一request安全头构造器；download通过blob创建临时对象URL并及时revoke，先解析非成功JSON错误再决定是否创建文件。逐项替换 `quick-step1.downloadQkTemplate`、`quick-step4.fetchDownload`、`tasks-page.TaskQueueOnboarding.downloadTemplate`、`task-detail.downloadReal` 的直接fetch，不能用JSON get代替二进制下载。
- [ ] SSE用fetch流解析器保留event/id/data、分块UTF-8和多行边界，close后abort；接入 `quick.jsx:waitBackendJob`，保留断线轮询与最后GET确认，但身份失效/重置必须同时终止订阅与轮询，不将401无限当网络错误重试。当前WebSocket仅为无业务消费者的api包装，禁用该未用入口并给出明确错误，不新增票据子系统。
- [ ] 缓存键统一包含 origin/purpose/org/user/generation；不在localStorage存Cookie/token。逐项处理 `theme.identityScope`、onboarding引导/学习键、topbar tenantId、quick-engine `jkxt:tasks`、跨页面taskDetailId、ai-policy→quick的quickHandoff、pending run、记住账号，以及全局 `__jkxtMe/__jkxtDemo`。旧键只删除或失效，不复制到新身份；草稿/弹窗/内存和请求一并清理。跨标签广播只含当前用途的失效通知、不发token、不踢下线另一用途。退出网络失败锁定当前页面并提示重试，不能用本地成功掩盖服务端未撤销。
- [ ] 运行 `node scripts/login-isolation/test-entry-context.mjs`、`test-session-context.mjs`、`test-session-storage.mjs`：两用途同浏览器、同用途切账号、迟到Promise/401、旧缓存、恶意apiBase、下载SSE身份头、multipart、403保留错误原因。沿用现有node:assert/vm与Babel测试习惯，不引入未安装的Jest。浏览器真实Cookie行为由Task11证明。

## Task 8: 登录双页面与演示入口体验

**Files:** 修改 `src/pages/login.jsx`、`src/app.jsx`、`src/pages/quick.jsx`、`Dashboard.html`；新建 `src/components/demo-banner.jsx`、`src/auth/demo-start.js`；下载/缓存调用点由Task7交接，不扩展未知写租约；新增 `scripts/login-isolation/test-demo-start.mjs`、`test-login-portals.mjs`。

**Interfaces:** `DemoStart.enter({demoOrigin}):Promise<void>` 只导航白名单演示origin；演示页面 `DemoStart.bootstrap():Promise<SessionResponse>` 先visitor再sessions；`DemoBanner({expiresAt,onReset,pending,error})`；登录成功只能交给 JKXTSession.accept。

- [ ] 测试普通页显示演示按钮、平台页没有；未认证不闪现业务数据；移除getLocalDemoCredentials调用和所有旧凭据预填，不删除验证码/锁定提示。
- [ ] 先运行 `node scripts/login-isolation/test-demo-start.mjs` 和 `node scripts/login-isolation/test-login-portals.mjs` 见失败；保留现有表单样式与资产，只增加独立标题/路径、提交状态和演示入口。按钮 disabled 与 aria-busy 同步，错误可读、可键盘重试。Dashboard在app前加载demo-start和demo-banner，遵守现有Babel作用域检查。
- [ ] demo-start在同origin内使用跨标签启动锁（检测能力，不假设所有环境存在），bootstrap成功并确认cookie/context才挂载工作台；缺协调能力时明确单标签引导，异常不构造本地假会话。

```javascript
async function withDemoStartLock(bootstrap) {
  if (!navigator.locks) throw Object.assign(new Error('请在单一标签页中开始演示'), { code: 'DEMO_SINGLE_TAB_REQUIRED' });
  return navigator.locks.request('jkxt-demo-bootstrap', { mode: 'exclusive' }, bootstrap);
}
```

降级单标签入口仍须服务端访客Cookie/幂等，不能在捕获上述错误后直接多次POST；用户确认单标签后只提交一个明确操作。

- [ ] DEMO挂载现有学校业务外壳但能力受限，不映射成ADMIN；workbench读 `/demo/overview`；Quick样例来自新工作区真实seed/文件，不打开固定SAMPLE_ORG_ID后门。首期不开放页面显示说明和回Quick按钮。
- [ ] DemoBanner始终可见，倒计时依据服务端期限；重置确认列清当前访客影响，同浏览器标签被通知失效，重置失败不显示新120教师假数据；退出后不立即自动再次创建工作区。
- [ ] 验证390/768/1440、200%缩放、键盘提交/焦点、loading/error/expiry/reduced-motion；不改变左侧导航视觉结构。截图与真实动作后端结果一起保留。

## Task 9: 认证测试辅助方法迁移与完整性审查准备

**Files:** 复用Task3的 `backend/src/test/java/com/jkxt/security/PortalHttpFixture.java`、`PortalHttpFixtureTest.java`；修改下列原测试中的认证辅助方法，不批量改变业务断言。Task3已迁移的三个Auth测试在此复核，不重复实现。

扫描命中清单（相对 `backend/src/test/java/com/jkxt/`）：

```text
ai/AiPolicyApiIntegrationTest.java
ai/AiPolicyAcceptanceAuditIntegrationTest.java
common/web/ExceptionContractAcceptanceTest.java
common/web/RestExceptionHandlerAcceptanceTest.java
examops/DutyAssignmentSelfServiceIntegrationTest.java
examops/ExamOperationsClosedLoopIntegrationTest.java
examops/ExamOperationsExecutionIntegrationTest.java
examops/ExamPlanApiIntegrationTest.java
examops/JointInputApiIntegrationTest.java
importer/ImportCommitAcceptanceTest.java
importer/ImportCommitApiTest.java
importer/ImportValidationApiTest.java
masterdata/MasterDataApiIntegrationTest.java
notify/NotificationApiIntegrationTest.java
notify/NotificationProducersAcceptanceTest.java
org/AcademicTermApiIntegrationTest.java
org/OrgSettingsApiIntegrationTest.java
platform/P10ReportAdminApiIntegrationTest.java
platform/P12DeployModeIntegrationTest.java
platform/P12SaasTenantHeaderIntegrationTest.java
platform/PlatformHonestyAcceptanceTest.java
platform/SystemHealthAndAuditWindowTest.java
platform/TeacherAccountRoleBindingIntegrationTest.java
platform/TenantInitialTermSeedTest.java
scheduling/FixtureScaffoldingProductionGuardTest.java
scheduling/RealDataScheduleFlowIntegrationTest.java
scheduling/ScheduleEditApiIntegrationTest.java
scheduling/ScheduleJobApiIntegrationTest.java
security/AuthIntegrationTest.java
security/AuthIpThrottleAcceptanceTest.java
security/AuthLockoutAcceptanceTest.java
security/BusinessRbacAcceptanceTest.java
security/RbacPermissionMatrixAcceptanceTest.java
security/UserPreferencesAndAppearanceIntegrationTest.java
tasks/ExamTaskAssignmentsRunSelectionIntegrationTest.java
tasks/ExamTaskQuickFlowIntegrationTest.java
```

**Interfaces:** 消费Task3已定义的 `PortalHttpFixture` 与其类型/签名，使用真实MockMvc登录，不直接设置SecurityContext。测试账号只在隔离test profile生成，不写入生产配置或日志；不得为旧测试另加关闭用途/context/CSRF校验的开关。

- [ ] 新helper测试使用一次性验证码真实登录，解析对应Cookie及token；错误用途必须失败。验证码debug仅隔离测试profile可用，生产保持false。
- [ ] helper向业务请求加用途/context/CSRF/Origin；`loginResult`本身不可偷偷修正调用者的错误用途。校验 `apply` 不能覆盖专用于负例的缺失/错误header测试。

```java
public MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request,
    AuthenticatedSession session) {
  return request.cookie(session.cookie())
      .header("Origin", "http://localhost")
      .header("X-JKXT-Portal", session.purpose().name().toLowerCase(java.util.Locale.ROOT))
      .header("X-JKXT-Session-Context", session.context())
      .header("X-JKXT-CSRF", session.csrfToken());
}
```

- [ ] 仅迁移上述文件的login/cookie/header辅助代码，保留各原场景、通过数、拒绝码、并发/硬约束断言。Auth旧Cookie契约测试明确改为“旧Cookie拒绝”的新回归，不直接删掉。
- [ ] `rg`复扫原Cookie/旧登录路径：仅旧接口兼容/拒绝测试允许保留；新增失败、skip、断言变化逐项登记原因。配置禁止临时关闭CSRF/context安全特性。
- [ ] 跑原backend全套测试，比较基线与新结果的测试数量/失败/skip。后续单独执行 `review.coderabbit.test-integrity`，普通review不替代。

## Task 10: 隔离运行配置、深链接与故障恢复

**Files:** 新建 `scripts/login-isolation/compose.acceptance.yml`、`nginx.school.conf`、`nginx.demo.conf`、`start-isolated.ps1`、`test-environment-guard.ps1`；不改原根级私有部署文件；演示正式部署另用同目录经过审阅的部署模板。

**Interfaces:** 运行清单 `environment.json` 包含runId、候选哈希、两站origin、DB/Redis/卷标识、健康结果，不包含秘密；`start-isolated.ps1 -RunId <id>` 仅在护栏验证后启动本任务资源，不kill占用端口的他人进程。

- [ ] 先写配置检查：任何业务数据库地址、原卷名、公开暴露DB、外部AI密钥、`.env.private`挂载、Docker socket暴露在应用容器均拒绝。
- [ ] school测试站用 `http://127.0.0.1:18280`、demo测试站用 `http://localhost:18281`；端口占用则停止并报告，不能杀进程。两独立DB/Redis、后端、文件卷，容器命名 `jkxt-test-entry-<run-id>-*`，DB名 `jkxt_test_entry_<run-id>`（runId只允许小写字母数字下划线）。
- [ ] Nginx仅发布挑选后的静态资产，不挂载整个仓库根作公开目录。深链接显式映射，API代理优先，不把未知API404回退成HTML200。

```nginx
location = /school/login { try_files /Dashboard.html =404; }
location = /platform/login { try_files /Dashboard.html =404; }
location ^~ /api/ { proxy_pass http://backend:8080; proxy_buffering off; }
location ~ /(?:\.git|\.env|backend|artifacts|docs)(?:/|$) { deny all; }
```

demo站只映射 `/demo/start`；生产模板必须两个不同hostname+TLS，并把Secure Cookie实际响应纳入验收。本机双端口只证明局部测试，不等于生产TLS验收。

- [ ] 执行环境护栏失败测试、镜像构建、健康检查、直接打开/刷新所有入口。Docker不可用或依赖失败保留失败状态；不返回“服务已启动”。
- [ ] 在独立副本执行 V30备份→V31升级→数据核对→恢复V30副本，验证真实恢复而非仅生成文件；恢复不启用旧公开口令。Redis/DB停止路径只操作本任务容器，随后恢复并验证错误提示和会话行为。

## Task 11: 两轮业务、浏览器与结果质量验收

**Files:** 新建 `scripts/login-isolation/dataset.mjs`、`oracle.mjs`、`run-business-round.mjs`、`test-oracle.mjs`、`browser-checklist.md`、`acceptance-cases.json`、`score.mjs`；结果写独立 `artifacts/login-isolation/<run-id>/round-1|round-2`。本任务Node业务驱动走真实HTTP，不复用Java测试辅助来绕过登录。

**Interfaces:** `run-business-round.mjs --manifest <environment.json> --teachers 120|150 --round 1|2`；必须核验环境身份与allowlisted loopback origin，禁止默认指向18180。`oracle.validate({input,assignments,revision,exports}):{checks,failures}`；score只读取真实断言结果，不能写pass覆盖测试结果。

- [ ] 先写oracle的故意错误样本测试：双时段重叠教师、无资格主监考、班主任监本班、人数不足、旧修订导出、混入另一org。全部错误必须被拒绝；零条assignment不能“空集合全通过”。
- [ ] 冻结用例清单与分母，每条绑定AC、入口、数据、动作、期望持久效果、拒绝效果和独立证据。仅合成姓名，不称真实教师个人资料。120/150教师同时覆盖主/副资格、班主任、学科、不可用/请假窗口。

```javascript
// 结果核验核心断言，测试输入中 expectedSlots 由考试×岗位清单独立产生。
assert.equal(assignments.length, expectedSlots.length);
assert.equal(new Set(assignments.map(a => `${a.examId}:${a.roomId}:${a.role}`)).size, assignments.length);
assert.equal(failures.filter(f => f.kind === 'hard-constraint').length, 0);
assert.equal(exportedRevision, selectedRevision);
assert.deepEqual(exportedRows, canonicalRows);
```

该段变量由 `oracle.validate` 的输入构造，不从被测服务“自报正确”字段取得。硬约束必须独立重算；公平性只展示分布，不能替代硬约束。

- [ ] 第一轮120教师：真实xlsx写入→含错文件拒绝→修正重导→页面校验→本地求解→人工调整→刷新回读→xlsx/pdf导出反读→模拟发布。浏览器点击后检查DB/API回读，不以按钮可点击判成功。
- [ ] 同轮完成AC-01～16全部失败/安全用例，包括多入口并存、wrong portal不签发、CSRF、旧Cookie、他人ID、demo外发为0、reset/expiry受控交错、上传超限、无解、离线、DB/Redis停止、退出失败。
- [ ] 第二轮使用新访客和150教师，重新生成文件与新请求；至少两访客并发，复测第一轮每个缺陷、修订导出、多标签、重置、清理重启。不得复用第一轮截图、旧报告或旧数据库结果。
- [ ] 使用当前允许的浏览器技能执行实际页面验收；工具受限则标MISSING，不用绕过策略的浏览器/终端替代。需要人类验证码时请求用户输入，不模拟“已登录”。覆盖390/768/1440、200%缩放、键盘焦点、减少动画、错误/加载/空态。
- [ ] 每个文件记录sha256、生成参数、输入/输出修订、时间和候选版本；报告显示功能25/身份数据隔离30/业务20/可用性10/恢复10/证据5的加权分及实际覆盖率。未执行项0分并列缺失原因；任何阻塞门禁保持NO-GO。

## Task 12: 固定质量门、独立审查与交付判定

**Files:** 只追加本任务脱敏报告与清单；若发现缺陷，回到拥有该文件的任务，新增回归后重跑受影响完整闭环，不就地调低标准。

**Interfaces:** 真实核心绑定的criterion/Evidence/gate结果 → 核心 finalize；本地报告不能生成或冒充正式 Evidence ID。

- [ ] 通过真实规划绑定本任务AC与保留门禁，使用 `supervisor-gate.ps1 -GateId ... -CriterionId ...`；只传核心已返回的真实ID，不用示例ID执行。执行前检查固定命令来源/超时/工作区均为注册项。
- [ ] 以下十四门全部保留；命令来自当前项目配置，仅记录，不在本计划编写阶段运行：

| Gate ID | 项目配置声明的固定命令/执行器（核心注册待核验） |
|---|---|
| data.school-xlsx | `node scripts/school-e2e-20260909/verify-quality.mjs dataset` |
| code.plan-action-states | `node scripts/school-e2e-20260909/test-plan-action-states.mjs` |
| code.exam-plan-lifecycle | `node scripts/frontend-quality/check-exam-plan-lifecycle.mjs` |
| code.joint-planning-workspace | `node scripts/frontend-quality/check-joint-planning-workspace.mjs` |
| ui.frontend-isolated | `node scripts/school-e2e-20260909/verify-quality.mjs frontend` |
| api-db.two-round-workflow | `node scripts/school-e2e-20260909/verify-quality.mjs workflows` |
| api.authorization | `node scripts/school-e2e-20260909/verify-quality.mjs authorization` |
| ai.external-and-application | `node scripts/school-e2e-20260909/verify-quality.mjs ai` |
| ui.real-buttons | `node scripts/school-e2e-20260909/verify-quality.mjs browser` |
| ui.visual-accessibility | `node scripts/school-e2e-20260909/verify-quality.mjs visual` |
| code.backend-tests | `docker compose -f scripts/school-e2e-20260909/compose.verify.yml run --rm verify` |
| config.quality-validator | `node scripts/school-e2e-20260909/verify-quality.mjs selftest` |
| review.coderabbit | trusted core runner `bin/run-coderabbit-review.py --review-category independent` |
| review.coderabbit.test-integrity | trusted core runner `bin/run-coderabbit-review.py --review-category test-integrity` |

- [ ] 不将新隔离测试直接当旧全站门禁输入：旧门禁还要求AI等原产品证据，demo禁用外部AI不表示旧AI验收通过。新增隔离门禁须正式注册，并且是加法，不替换旧命令/降低阈值。
- [ ] CodeRabbit独立责任组审查精确diff；测试/fixture/validator另做test-integrity。没有真实返回产物就是MISSING，子代理口头认可不能替代。
- [ ] 验收报告包含：本次改动、原功能回归、两轮断言总数/通过/失败/未测、六维得分、截图及文件链接、候选镜像/源码/配置哈希、独立ReviewRecord、回滚结果、P0/P1列表。
- [ ] 核心 finalize=complete 且用户部署授权具备时才进行生产切换；否则交付明确为隔离候选或incomplete。不得承诺“上线后绝不出问题”；提供已验证范围、监控、回滚触发条件。

## 2. 验收追踪与路径责任

| 规格条款 | 实施任务 | 验收位置 |
|---|---|---|
| AC-01、AC-02 | 2、3、7、8、10 | PortalLogin + 页面直接打开/刷新 |
| AC-03、AC-04、AC-05 | 1、2、3、5、9 | 入口拒绝/RBAC/CSRF/租户测试 |
| AC-06、AC-07 | 2、3、6、7、8 | 多标签、真实下载SSE、乱序响应 |
| AC-08、AC-09 | 4、5、8 | 访客并发/幂等/跨工作区测试 |
| AC-10、AC-11 | 5、6、8、11 | 真实Excel闭环、修订导出、外发计数 |
| AC-12、AC-13 | 4、5、6、11 | 可控竞态、资源限额、期限和清理 |
| AC-14、AC-15 | 7、8、10、11 | 可访问性、失败状态、运行依赖中断 |
| AC-16 | 0、1、10、12 | 候选/迁移/备份恢复与正式门禁 |

实施时 Backend身份组拥有Tasks1–3；Demo组4–6；Frontend组7–8；独立QA组9–12。共享文件 `SecurityConfig.java`、`AuthService.java`、`src/api.js` 必须串行交接租约，不并行写。同一实现者不能给自己的任务发正式独立审查通过记录。

## 3. 计划自检与当前交付边界

本计划的自检范围：规格覆盖、名字/类型一致性、文件真实存在或明确新建、原测试迁移范围、质量门保留、回滚与权限边界。已修正测试辅助依赖倒置、通知分发路径、四个下载入口、SSE后备轮询、深链接资源基址、恶意apiBase和迟到401处理。代码块是实施合同/关键算法，不是已部署代码。尚无新功能测试通过率或产品质量分。

只读定位中存在错误路径探测（例如 `V2__auth.sql` 不存在，实际为 `V2__p2_core_schema.sql`），已通过文件清单纠正；这些失败保留为诊断失败，不计作通过测试。两位子代理仅贡献后端/前端源码清单，不构成正式独立ReviewRecord或质量门通过证据。

下一阶段只在书面计划审阅、执行方式选择及真实规划门恢复后开始。没有恢复门禁时，可继续不依赖门禁的只读诊断，不进入认证/数据库实施；不把离线检查冒充正式验收。
