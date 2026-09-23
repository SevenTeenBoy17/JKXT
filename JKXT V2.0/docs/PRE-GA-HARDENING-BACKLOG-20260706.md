# JKXT V2.0 · 上生产前加固 Backlog（Pre-GA Hardening）

> 背景：系统经 13 轮迭代已高度收敛——全套件 90/90 绿、核心正确性/安全/租户隔离扎实、深度分析（`docs/CODEBASE-DEEP-READ-20260706.md`）完成。
> 剩余的**不是会崩的 bug**，而是上生产/规模化前的加固项。本文把它们整理成可决策的任务：每项含 **位置 / 问题 / 修复 / 影响面(含是否动前端) / 工作量 / 建议**。
> **用法**：你点选要做的项（或说「做 B 组」），我逐项实现 + Docker maven 全套件验证 + 提交。带 🟢 的是**低风险、无需产品决策、可立即做**；带 🟡 的**需你先拍板**（改 auth 模型 / 可能动前端 / 属产品取舍）。

---

## A. 安全 / 授权加固（🟡 需你决策）

### A1. 细粒度 permissions「算而不用」—— 🟩 **后端已完成**（方案 B，`f5c5a59`）；前端显隐待做
> 用户拍板走方案 B（读写都上权限门）+ 超管可代管业务写。**后端已落地**：`V15` 为每个 org 建
> VIEWER/TEACHER_QUERY(tenant:read) + 给 SUPER_ADMIN 补业务写权限；`SecurityConfig` 改为按 `hasAuthority`
> 细粒度鉴权（主数据→masterdata:manage、排考→schedule:manage、考试任务→exam:manage、读→tenant:read）；
> 全套件 93 绿、RED→GREEN 锁定。**剩余前端**：按 permission 显隐写控件，规格见
> `docs/A1-FRONTEND-HANDOFF-20260717.md`（因前端有未提交改动未实施，非安全阻塞——后端已是权威防线）。
> 下方为原始描述留存。

- **位置**：`security/SecurityConfig.java`（授权规则）、登录时 `AuthService` 注入 `permissions` 到 SecurityContext、`AdminPlatformController` 唯一方法级注解。
- **问题**：登录时读出 `role.permissions` 注入 SecurityContext 并下发前端，但全库只有 2 处授权表达式且都是 `hasRole('SUPER_ADMIN')`，无一处 `hasAuthority(permission)` 生效。服务端实质只做**角色粗粒度**鉴权，权限点仅驱动前端展示。
- **我的评估**：**当前不是活跃越权漏洞**——现役角色模型（SUPER_ADMIN / ADMIN / VIEWER / TEACHER_QUERY）+「写方法需 ADMIN、/admin 需 SUPER_ADMIN、读需已认证」对单校/单教务场景是**足够**的；VIEWER/TEACHER_QUERY 只读、ADMIN=教务能改本 org 全部，符合产品语义。permissions 是**面向未来更细分角色的预留基建**。
- **修复方案（若要收口）**：确定"哪些端点该由哪个 permission 门"（这是**产品决策**）→ 在对应 controller 方法加 `@PreAuthorize("hasAuthority('xxx')")`，并让前端按同一套 permission 显隐。
- **影响面**：**动后端授权 + 前端显隐**；映射错会误挡合法操作。**需产品先定义 permission→端点矩阵**。
- **建议**：**单校交付可暂不做**（角色粗粒度已够）；若走多角色 SaaS 再做。**优先级：中（取决于是否上多角色）**。

### A2. CSRF 禁用 + Cookie 认证——仅靠 SameSite
- **位置**：`SecurityConfig`（`csrf().disable()`、cookie SameSite 由 `jkxt.auth.cookie-same-site` 配，默认 Lax）。
- **问题**：状态改变型请求（POST/PUT/DELETE）无 CSRF token 防护，安全性完全依赖 SameSite=Lax。若某部署为"跨站前端"把 same-site 配成 `None`，CSRF 防护即失效。
- **修复方案**：为 cookie 认证引入 **SPA CSRF token 流**（Spring Security `CookieCsrfTokenRepository` + 前端每次 mutation 带 `X-XSRF-TOKEN` 头）。
- **影响面**：**改认证协议 + 前端每个写请求都要带 token**（`src/api.js` 统一注入）。**明确会动前端**，需前后端一起改。
- **建议**：**保持 SameSite=Lax 且绝不配 None 的部署可暂不做**；若确有跨站前端需求再上完整 CSRF token 流。**优先级：中（取决于部署形态）**。

### A3. 登录限流仅按账号锁定，无按 IP / 全局节流 —— ✅ **已完成**（`d712063` B2 做了① + `d764f28` 做了②）
> ①clientIp 仅信任可信代理的 XFF：B2 的 `ClientIpResolver` 已落地。②按 IP 滑窗节流：`LoginIpThrottle`
> 已落地——以真实 IP 为 key、叠加账号锁定、只计失败、Redis fail-open、**默认关闭**（saas 默认开）、
> RED→GREEN 测试锁定（`AuthIpThrottleAcceptanceTest`，全套件 92 绿）。**A3 收口。** 下方为原始描述留存。

- **位置**：`AuthService.login`（5 次/30 分钟按账号锁定 + 验证码）；`AuthController.clientIp()`（取值）。
- **问题**：攻击者可对**大量不同用户名**撞库（每账号 5 次门槛），验证码是唯一横向门槛，无按 IP 节流。**且 `clientIp` 取 X-Forwarded-For 首段可伪造**（`AuthController.java:92-96`），使任何 IP 维度限流都可被轮换 XFF 绕过——两者耦合。
- **修复方案**：①`clientIp` 改为**仅信任可信代理的 XFF**（配 `jkxt.auth.trusted-proxy`，否则用 `getRemoteAddr()`）②加 Redis 按 IP 滑窗节流（如 20 次/5 分钟）叠加在账号锁定之上。
- **影响面**：**后端 only、不动前端**；但 ①需配可信代理（nginx/Caddy 的真实 client IP 传递链），配错会把真实 IP 记成代理 IP。
- **建议**：**上公网前建议做**（撞库是真实威胁面）；内网私有化可延后。**优先级：中高（若公网 SaaS/public）**。

### A4. 超管授权单点——service 层无二次校验
- **位置**：`platform/api/AdminPlatformController`（类级 `@PreAuthorize(hasRole SUPER_ADMIN)`）+ `SecurityConfig` `/admin/**` SUPER_ADMIN（已是双重门）；`AdminPlatformService` 本身不校验。
- **问题**：全平台运营台仅靠 controller/URL 两道门；若日后有人给 `AdminPlatformService` 接一条非 `/admin` 的调用路径且忘加注解，service 无兜底。
- **修复方案**：在 `AdminPlatformService` 关键写方法加 `SecurityContext` 角色断言（防御纵深），或抽一个 `requireSuperAdmin()` 首行调用。
- **影响面**：**后端 only、纯增量**（对现役正确调用零行为变化）、不动前端、无产品决策。
- **建议**：**可做**（纯防御纵深，低成本），但当前双重门已在，属**锦上添花**。**优先级：中低**。🟢（此项其实无需决策，若你要我直接做也可）

---

## B. 后端加固（🟢 低风险 / 无需决策 / 我可直接做+验证）

| 编号 | 项 | 位置 | 修复 | 价值 | 工作量 |
|---|---|---|---|---|---|
| B0 ✅ | 主数据审计缺口 | MasterDataService | update 路径 + subject 全 CRUD 补 audit | 合规追溯 | **已完成**（`f8ebdd3`+`2c9bcb8` 回归测试，全套件绿） |
| B1 ✅ | 关联表反向索引缺失 | teacher_subject / school_class_subject | Flyway V14 加 `(org_id, subject_id)` 索引 | 规模化删科目/科目用量统计提速 | **已完成**（`bb10a04`，全套件绿） |
| B2 ✅ | clientIp XFF 可伪造 | AuthController + 新增 ClientIpResolver | 仅信任可信代理 XFF、从右取最右非可信段、只解析 IP 字面量不触发 DNS | 审计 IP 真实性 | **已完成**（`d712063`）。实测证实旧代码经 nginx 主路径也可伪造。**残留**：18081 旁路端口已改绑回环（见 `application-private.yml` 注释）；若对外发布须把 trusted-proxies 收窄为 nginx 容器 IP/32 |
| B3 ✅ | buildFixtureFromTask 查询放大 | ScheduleJobService | exam_room/exam_class 半连接下推到 SQL（exam_room 显式 `ORDER BY ctid` 保序）；teacher_subject/school_class/subject 刻意不收窄（收窄会改 fixture 内容） | 大 org 排考内存/延迟 | **已完成**（`d712063`，全套件绿，双视角对抗审查 PASS） |
| B4 ✅ | platform by-id 全表扫 | AdminPlatformService | tenantById/accountById/announcementById 抽 `*_SELECT` 骨架+`*ScopeClause/Args` 共用、直接按 id 查、`query()+findFirst()+orElseThrow` 保 404 语义 | 规模化超管台延迟 | **已完成**（`d712063`）。连带修复既有 bug：租户列表 admins 计数漏过滤软删（`400d38d`，含 RED-first 回归测试） |
| B5 ✅ | 主数据列表 N+1 余项 | MasterDataService.toSubjectDto/toSchoolClassDto | 逐条子查询批量化（listSubjects 1+2N→3；listClasses 1+≤3N→≤4），单条路径共用同一 SQL 口径 | 大 org 列表延迟 | **已完成**（`d712063`，全套件绿） |

> B 组均**后端 only、不动前端、无产品决策**。**B0–B5 全部已完成并验证**（`d712063`+`400d38d`，全套件 91/0/0/1 绿）。

## C. 生产配置 / 部署 checklist（🟡 非代码，部署时执行）
- 关 `captcha-debug`、开 `disable-demo-accounts`、开 `strict-production-checks`、cookie `Secure=true`（`ProductionSecurityGate` 会 fail-loud 拦不安全默认）。
- **改掉 Flyway seed 里的明文演示口令**（`Demo@2025 / Super001!!! / Tenant001!!!`）——生产禁用或改密。
- **⚠️【部署 A1(V15+SecurityConfig)时必做】清空会话存储（Redis）强制全员重新登录。**
  A1 把鉴权改为按 permission，而会话里的 permission 是**登录时快照冻结在 Redis** 的（`SessionAuthenticationFilter`
  读 `principal.permissions()`，不按请求重解析）。升级窗口内，V15 之前登录的会话带着旧快照：**升级前登录的
  SUPER_ADMIN 缺新增的 `*:manage` → 写操作 403；升级前登录的 VIEWER/TEACHER_QUERY 权限为空 → 读 403**，直到各自
  重新登录才恢复。对抗审查 P1（deploy-window）。**这是本项目既有「会话内 authz 变更需 re-login 生效」行为在 A1
  升级点的具体表现。** 做法：部署 V15 镜像后 `docker exec jkxt-private-redis redis-cli FLUSHDB`（或重建 redis 卷）
  一次即可——之后新登录经修复后的 `AuthService.permissions` 回落一切正常。（新登录已由 `306d7f0` 修复，此步只针对
  升级瞬间的存量会话。）
- 上公网则开 `JKXT_AUTH_IP_THROTTLE_ENABLED` 并配 `trusted-proxies`；`private` 栈 18081 保持仅绑回环。
- **【B2 收尾·nginx 覆盖 XFF】`deploy/nginx/private.conf` 已把 `X-Forwarded-For` 从追加(`$proxy_add_x_forwarded_for`)
  改为【覆盖】(`$remote_addr`)。** private 是单边拓扑(nginx 即唯一可信边缘)，覆盖后客户端自带的 XFF 被 nginx 直接丢弃，
  后端拿到的 XFF 恒为可信来源——伪造审计 IP 变为**不可能**，且不再依赖「客户端真实 IP 恰好不落在 trusted-proxies
  网段」这一脆弱前提。**实盘已验证**(2026-07-17，V15 镜像部署到 jkxt-private)：改前带 `XFF: 9.9.9.9` → last_login_ip=9.9.9.9(经 docker 网关落在 172.16/12 被绕过)；nginx 覆盖 + reload 后同样请求 → last_login_ip=nginx 真实对端，伪造被丢弃。
  ⚠️ `deploy/` 在本仓未跟踪，此改动只在运行栈生效——维护 `deploy/` 者须保留此覆盖配置。（若 private 前面再加一层可信代理，改回追加并把该层网段加入 `trusted-proxies`。）
- 按 `docs/UAT-CHECKLIST.md` / `CONNECTED-PRODUCTION-DEPLOYMENT.md` 走割接门禁链。

> **🚀 2026-07-17 全套加固已实盘部署到 jkxt-private 并验证**：Flyway V12→V15 顺利应用；FLUSHDB 后重登录正常；
> A1 细粒度 RBAC 实测（ADMIN 读写 200、VIEWER 读 200/写 403 经 permission 门）；B2 nginx 覆盖 XFF 后伪造被丢弃；
> 一键排考全流程回归 28 分配（正常 upload→validate→schedule 序列）。
>
> **关于「succeeded 但 0 分配」的观察——已代码核实、非缺陷（更正前一版措辞）**：`ScheduleJobService.buildFixtureFromTask`
> 在 POST /schedule-jobs 的同一事务里**同步且全面**校验输入并抛 400 `SCHEDULE_INPUT_INCOMPLETE`：exams 为空(253 行)、
> **任一 exam 未绑定考场**(322 行)、考场不存在(330 行)、**教师名册为空**(383 行)。故「输入不完整的 fixture 永远不会
> 产生 succeeded run」——「0-exam 宜返回 400」其实**早已实现**，无需新增。首个冒烟里那一次 succeeded-0 是**不可复现的
> 读时序瞬态**（状态翻 succeeded 与 /assignments 端点可见性之间的窄窗）——careful 序列(28)+ 首轮 5/5 压测(28)+ 代码
> 三重 400 门禁均证实排考流程正确。真实前端用 job 快照内嵌的 assignments（来自求解结果），不受该读竞态影响。**无需修复。**

## D. 仓库卫生（🟢 低风险清理）
- ✅ 空残留目录 `backend;D`（0 字节）——已不存在（前轮清理）。
- ✅ 根目录构建产物 zip——`.gitignore` 已加 `*.zip`（`bb10a04`），untracked 不会误入库。
- ~~冗余文档~~：**经实地核查此项不成立**——两份 `10-*` 是 untracked 草稿（非正式文档）；两份 `12-*` 名字看即不同用途（开发物料清单 vs 落地整改方案）；并无"已被 08 覆盖的独立 NestJS 路线文档"（NestJS 只是被 08 等文档作为已否决备选提及）。**不删任何文档。**
- `会话内 stale authz`（管理员在线改角色需 re-login 生效）——记为已知行为，文档标注即可。

---

## 剩余待办（截至 `400d38d`）

**B 组全部完成（B0–B5）、A1(后端)+A3+A4 已完成、D 组已收口。** 剩下：

- **A1 前端显隐**（🟩 后端已完成 `f5c5a59`）——按 permission 隐藏写控件，规格见 `A1-FRONTEND-HANDOFF-20260717.md`；
  需在前端未提交改动合并/授权后做，非安全阻塞。
- **A2 CSRF token 流**（🟡 会动前端每个写请求，前端有你的未提交改动，我不碰）——保持 SameSite=Lax 且不配 None 的部署可暂不做。
- **C 组 生产配置 checklist**（部署时执行，非代码）：关 captcha-debug、开 disable-demo-accounts、开 strict-production-checks、cookie Secure=true、改掉明文演示口令、**private 栈 18081 保持仅绑回环**、**上公网则开 `JKXT_AUTH_IP_THROTTLE_ENABLED` 并配 `trusted-proxies`**。

**当前状态**：所有**后端 only、无需动前端**的加固项已全部完成（A3/A4/B0–B5）。剩余 A1（产品决策）、A2（动前端）严格受决策/边界阻挡，非我可单方推进。系统已达"单校私有化可交付"，且已具备上公网所需的 IP 节流 + XFF 防伪造基建。

---
*本 backlog 基于深度分析 + 真实代码核实。A3+A4+B0–B5 已完成并验证（`d712063`/`400d38d`/`d764f28`，全套件 92/0/0/1 绿，关键项 RED→GREEN 锁定）。过程中附带修复两处真实缺陷：B2 揭示"经 nginx 主路径也可伪造审计 IP"、B4 揭示"租户列表 admins 漏过滤软删"。*
