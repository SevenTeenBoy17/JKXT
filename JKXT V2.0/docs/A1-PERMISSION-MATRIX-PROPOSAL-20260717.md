# A1 · 细粒度 permissions → 端点矩阵（草案，待 review）

> **这是什么**：上生产前加固 backlog 的 **A1**（「permissions 算而不用」——登录时读出 `role.permissions` 注入
> SecurityContext 并下发前端，但服务端实际只做**角色粗粒度**鉴权，没有一处 `hasAuthority(permission)` 生效）
> 需要一个「哪个端点该由哪个 permission 门」的矩阵才能实现。这份草案把矩阵**提出来供你拍板**——因为映射
> 错会**误挡合法操作**，这一步必须你确认，我不替你决定。
>
> **来源**：全部基于真实代码核实（`SecurityConfig`、`SessionAuthenticationFilter`、13 个 controller 的端点、
> 运行库 `role` 表的真实 permissions）。日期 2026-07-17。

---

## 0. 一句话结论（先看这个再决定要不要细读）

**当前的粗粒度模型（角色 × HTTP 方法）对单校/单教务场景已经是正确且足够的**，A1 属「上多角色 SaaS 才需要」的
预留。**并且**：现库里 **VIEWER / TEACHER_QUERY 两个只读角色的 permissions 是空的**（`role` 表根本没有它们的
行），所以**贸然把读接口改成 `hasAuthority(...)` 会把这两个角色挡在所有页面外**——这是 A1 最大的坑。
因此本草案的核心是先请你在**两个方案**之间选一个（见 §4），再谈落地。

---

## 1. 现状（代码核实）

**授权在两处生效**：
- `SecurityConfig`（URL + 方法级粗粒度）：
  - `permitAll`：health / version / auth captcha & login / actuator health / swagger
  - `/api/v1/admin/**` → `hasRole('SUPER_ADMIN')`
  - `POST` logout / me/onboarded / me/change-password / notifications/** → `authenticated`（任何登录用户）
  - `POST|PUT|PATCH|DELETE /api/v1/**` → `hasAnyRole('ADMIN','SUPER_ADMIN')`
  - 其余 `/api/v1/**` → `authenticated`
- `AdminPlatformController` 类级 `@PreAuthorize("hasRole('SUPER_ADMIN')")`（与上面的 `/admin/**` 双重门）。

**permissions 现状**：`SessionAuthenticationFilter.authentication()` 给每个会话授予
`ROLE_<role>` **外加每个 permission 字符串各一个 authority**。但**全库无一处 `hasAuthority(permission)`**，故
permission 目前只驱动前端显隐，不参与服务端鉴权。这正是 A1 要收口的「算而不用」。

**权限词表（10 个，来自 seed）**：
`platform:read` `platform:manage` `tenant:read` `tenant:manage` `audit:read` `security:manage`
`user:manage` `masterdata:manage` `exam:manage` `schedule:manage`

**角色 → permissions（运行库真实值）**：

| 角色 | ROLE authority | permission authorities |
|---|---|---|
| SUPER_ADMIN | `ROLE_SUPER_ADMIN` | platform:read, platform:manage, tenant:read, tenant:manage, audit:read, security:manage |
| ADMIN（教务） | `ROLE_ADMIN` | tenant:read, user:manage, masterdata:manage, exam:manage, schedule:manage |
| **VIEWER** | `ROLE_VIEWER` | **（空——`role` 表无此行）** |
| **TEACHER_QUERY** | `ROLE_TEACHER_QUERY` | **（空——`role` 表无此行）** |

> ⚠️ **A1 头号风险就在最后两行**：VIEWER/TEACHER_QUERY 没有任何 permission authority。任何 `hasAuthority(...)`
> 门（哪怕是读）都会 403 挡死它们。要么给它们补读权限（方案 B），要么读接口不上 permission 门（方案 A）。

---

## 2. 全端点清单（13 controller）

| 分组 | 端点 | 方法 | 现在的门 |
|---|---|---|---|
| **auth** | /auth/captcha, /auth/login | POST | permitAll |
| | /auth/me | GET | authenticated |
| | /auth/me/onboarded, /me/change-password, /logout | POST | authenticated（自助） |
| **notifications** | /notifications, /unread-count | GET | authenticated（自助） |
| | /notifications/{id}/read, /read-all | POST | authenticated（自助） |
| **masterdata** | /teachers /classes /classrooms /subjects | GET（列表+by-id） | authenticated |
| | 同上 | POST/PUT/DELETE | ADMIN\|SUPER_ADMIN |
| **import** | /import/templates/{kind}, /{kind}/template | GET | authenticated |
| | /import/validate, /{kind}, /{kind}/validate, /{kind}/commit | POST | ADMIN\|SUPER_ADMIN |
| **exam-tasks** | /exam-tasks（列表）, /{id}, /{id}/assignments, /{id}/export, /schedule-jobs/{jobId}(+/events) | GET | authenticated |
| | /exam-tasks, /{id}/uploads, /{id}/imports/validate, /{id}/schedule-jobs | POST | ADMIN\|SUPER_ADMIN |
| **schedule edit** | /tasks/{id}/schedule/check-conflicts, /undo, /redo, /publish | POST | ADMIN\|SUPER_ADMIN |
| | /tasks/{id}/schedule | PATCH | ADMIN\|SUPER_ADMIN |
| **scheduling（demo）** | /scheduling/fixtures, /runs/{id}(+/events) | GET | authenticated |
| | /scheduling/fixtures/{id}/solve, /runs | POST | ADMIN\|SUPER_ADMIN |
| **ai policy** | /ai/providers | GET | authenticated |
| | /nl-policy/parse, /nl-policy/refine | POST | ADMIN\|SUPER_ADMIN |
| **reporting** | /stats/history*, /stats/supervise, /timeline/schedule | GET | authenticated |
| **platform（超管台）** | /admin/**（租户/账号/安全/备份/公告/设置 共 ~30 端点） | ALL | SUPER_ADMIN |
| **health/version** | /health /version | GET | permitAll |

> 注：`/scheduling/fixtures/**` 是 demo 端点，真实一键流程走 `/exam-tasks/**`（见 [[jkxt-true-state-and-core-gap]]），
> 但仍需鉴权覆盖以防被直接调用。

---

## 3. 提议的 permission → 端点 映射

按语义把端点归到已有的 10 个 permission 上（**不新增词汇**，最大化复用现有 seed）：

| permission | 该门控哪些端点（**写**操作） | 持有角色（现状） |
|---|---|---|
| `masterdata:manage` | teachers/classes/classrooms/subjects 的 POST/PUT/DELETE；import 的 validate/{kind}/commit | ADMIN, （SUPER_ADMIN 需补） |
| `exam:manage` | exam-tasks 的 create/uploads/imports/validate；import commit(exam) | ADMIN |
| `schedule:manage` | schedule-jobs 启动；/tasks/{id}/schedule PATCH、check-conflicts/undo/redo/publish；scheduling solve/runs；nl-policy parse/refine | ADMIN |
| `user:manage` | （org 内账号管理——**当前后端无此端点**，预留给未来 org 级用户管理） | ADMIN |
| `platform:manage` | /admin/tenants(增改/suspend/restore)、/admin/accounts(增/改/禁/删/重置/强登出)、/admin/platform-settings PUT、/admin/announcements 增改发撤推、/admin/backups/run | SUPER_ADMIN |
| `platform:read` | /admin/tenants(GET)、/admin/accounts(GET)、/admin/announcements(GET/read-stats)、/admin/backups(GET)、/admin/platform-settings(GET) | SUPER_ADMIN |
| `security:manage` | /admin/security/ip-ban、/ip-allowlist | SUPER_ADMIN |
| `audit:read` | /admin/audit-logs、/admin/security/overview、/admin/security/threats | SUPER_ADMIN |
| `tenant:read` | （**读**类业务端点，见 §4 决策）——masterdata GET、exam-tasks GET、reporting、import 模板下载、ai/providers | ADMIN, SUPER_ADMIN |
| `tenant:manage` | （SaaS 跨租户切换 X-Tenant-Id，已由 filter 校验 SUPER_ADMIN；无独立端点） | SUPER_ADMIN |

**自助类**（/auth/me*、/notifications/*）：保持 `authenticated`，不上 permission 门——任何登录用户对自己的
资源可读写，语义正确。

---

## 4. ⚠️ 必须你拍板的核心决策：读接口怎么办

因为 VIEWER/TEACHER_QUERY 的 permissions 是空的，「读接口」有两条互斥路线：

### 方案 A（推荐·低风险·零前端改动）：读维持 `authenticated`，只给写上 permission 门
- **只改写操作**：把 `POST|PUT|PATCH|DELETE` 从粗粒度 `hasAnyRole('ADMIN','SUPER_ADMIN')` 收紧为对应的
  `@PreAuthorize("hasAuthority('masterdata:manage')")` 等。ADMIN/SUPER_ADMIN 已持有这些权限 → **零误挡**。
- **读操作全部维持 `authenticated`**：VIEWER/TEACHER_QUERY 继续能读（它们本就该只读），**零回归、零前端联动**。
- **收益**：写路径从「按角色」升级为「按能力」，未来加「只能管主数据、不能碰排考」这类细分角色时，直接给它
  `masterdata:manage` 而不给 `schedule:manage` 即可，无需改 SecurityConfig。
- **代价**：读仍是「登录即可读」，没有「连读都分权限」的粒度。对单校/单教务足够。
- **工作量**：后端 only，约 1 天（给 ~10 个写端点加方法级注解 + 全套件 + 每角色活栈复验）。**不动前端。**

### 方案 B（完整·需先补数据·会动前端）：读也上 permission 门
- **先补 seed**：给 VIEWER/TEACHER_QUERY 建 `role` 行并赋 `tenant:read`（VIEWER 全读；TEACHER_QUERY 可能只给
  «查询本人监考» 的更窄权限——**这又是一处产品决策**）。
- 读接口改 `@PreAuthorize("hasAuthority('tenant:read')")`。
- **前端必须同步**：按同一套 permission 显隐菜单/按钮，否则用户看得到点不动（403）。**这一步动前端**——而前端
  当前有你未提交的改动，我不会碰；需你我协调或你先合并前端改动。
- **收益**：真正的「读也分权限」，未来可做「只能看报表、不能看名册」这类超细分。
- **代价**：改数据 + 改前端 + 误挡风险最高（TEACHER_QUERY 的读范围一旦定窄，配错就把老师挡在查询外）。
- **工作量**：后端 + 数据迁移 + 前端，约 2–3 天，且需产品先定 VIEWER/TEACHER_QUERY 的读范围。

> **我的建议**：**先做方案 A**（纯后端、零误挡、立刻提升写路径的鉴权粒度），把 A1 的「服务端不设防的写」这个
> 实质缺口补掉；方案 B 等你确实要上多角色 SaaS、且前端改动告一段落再做。

---

## 5. 方案 A 的落地清单（若你选 A，我照此实现）

1. **`SecurityConfig`**：移除 `POST|PUT|PATCH|DELETE /api/v1/**` 的粗粒度 `hasAnyRole`（或保留为兜底），改由各
   controller 写方法上的 `@PreAuthorize("hasAuthority('xxx')")` 精确门控。`/admin/**` 的 `hasRole('SUPER_ADMIN')`
   保留为超管台兜底不动。
2. **给 SUPER_ADMIN 补 `masterdata:manage`/`exam:manage`/`schedule:manage`**（若产品认为超管也应能代管业务写）——
   **或** 明确「超管只管平台、不碰业务写」，则这些业务写端点 SUPER_ADMIN 反而不该有 → 需你确认超管的业务写边界。
   （这是方案 A 里唯一的小决策：**超管能不能替租户做主数据/排考写操作**？）
3. 逐 controller 加注解：masterdata 四个 → `masterdata:manage`；import 写 → `masterdata:manage`（+exam commit 归
   `exam:manage`？）；exam-tasks 写 → `exam:manage`；schedule 编辑/发布/solve/nl-policy → `schedule:manage`。
4. **测试**：每个 permission 一条「有权 200 / 无权 403」验收（RED→GREEN），并回归「VIEWER/TEACHER_QUERY 读仍 200、
   写仍 403」「ADMIN 全业务写 200」。
5. Docker maven 全套件 + 活栈每角色复验 + 提交。

---

## 6. 请你回复其一

- **「做 A」**（推荐）→ 我按 §5 实现纯后端细粒度写鉴权（顺带请你回答 §5.2 的「超管业务写边界」小问）。
- **「做 B」** → 需你先给出 VIEWER / TEACHER_QUERY 的**读范围定义**，且我们要协调前端改动时机。
- **「暂不做」** → A1 归档为「上多角色 SaaS 再做」，系统维持当前粗粒度（对单校已足够）。

---
*本草案不改任何代码，仅供决策。基于 2026-07-17 真实代码 + 运行库核实。相关：`docs/PRE-GA-HARDENING-BACKLOG-20260706.md`。*
