# 10 · 实施任务总计划(单文档 · 可勾选开发清单)

> **用途**:把全部计划任务收敛到一份,供后期开发直接领取执行。本文是**任务编排层**;详细契约见 `08-BACKEND-DEV-SPEC.md`(主规格),路线复核见 `09-SECOND-ROUND-BACKEND-DOC-REVIEW-ANNOTATED.md`。
> **范围**:打通现有前端 UI(`D:\VB\JKXT\JKXT V2.0\src`)的全部后端服务 + 前端最小化接管改造。
> **原则**:前端 UI 不大改(只做 A 数据源接管 / B 小逻辑接线);**唯一例外**=`edit.jsx` 真拖拽(C 类,已批准)。

---

## §0 使用说明

- **任务 ID**:`S{阶段}-{BE后端|FE前端|DL交付}-{序号}`,如 `S1-BE-03`。引用契约写 `→08 §x` / `→09 §x`。
- **状态**:`☐ 待办` · `◐ 进行中` · `☑ 完成` · `⊘ 阻塞`(开发时直接改本文勾选框 `- [ ]`)。
- **优先级**:`P0` 阻塞/核心路径 · `P1` 重要 · `P2` 可延后。
- **颜色图例(同 09)**:<span style="color:#DC2626;font-weight:700">红=必须/阻塞/安全</span> · <span style="color:#D97706;font-weight:700">橙=建议/中风险</span> · <span style="color:#2563EB;font-weight:700">蓝=主路线保留</span> · <span style="color:#059669;font-weight:700">绿=低风险/前端小改</span>。
- **DoD**=完成定义(验收口径),量化指标见 §7。

---

## §1 已锁定决策基线(开发前必读,勿再翻案)

| 维度 | 决策 | 出处 |
|---|---|---|
| 后端栈 | **Java 21 + Spring Boot 3.3**(覆盖原 FastAPI) | 08 §0.1 D1 |
| 数据库 | **PostgreSQL 16**(预留达梦/人大金仓信创方言) | 08 §0.1 D3 / §3.21 |
| 部署 | **混合交付**:云端 SaaS 多租户 + 私有化单校(`deploy.mode` 切换) | 08 §0.1 D2 / §1.4 |
| 排考引擎 | **OR-Tools CP-SAT 主导 + LLM 辅助**(NL→DSL / 总结 / 修复建议) | 08 §0.1 D4 / §7 |
| 多租户 | 共享库 + 行级 `org_id` + Hibernate `@Filter`(+可选 RLS) | 08 §2 |
| <span style="color:#DC2626;font-weight:700">生产认证</span> | **不可用 localStorage token**;走 httpOnly session/refresh cookie 或 BFF + `/auth/me` | 09 C3 |
| <span style="color:#D97706;font-weight:700">API 前缀</span> | 外部统一 **`/api/v1`**(内部若用 `/api` 由网关改写) | 09 C2 |
| 主数据分页 | **不做服务端分页**,整页返回,`DataTable` 零改 | 08 §5.1 / 09 §9.6(R3) |
| edit 编辑 | **本期实现真拖拽改派**(前端唯一 C 类) | 08 §7.7 / 09 §9.6(R3) |
| AI 策略进度 | **复用 quick-step3/4**(navigateTo,不内嵌) | 08 §7.8(R3) |
| 第一轮 Node/NestJS 指南 | **降级为参考**,冲突以 08 为准 | 09 C1 |

---

## §2 里程碑总览(S0–S6)

| 阶段 | 主题 | 出口标准(Exit) | 优先级 |
|---|---|---|---|
| **S0** | 收敛 + 脚手架 | 工程可启动;Flyway/OpenAPI/CI/compose 就绪;前缀统一 | P0 |
| **S1** | 认证 / 租户 / RBAC / 安全 | 前端不再仅靠 localStorage 判角色;httpOnly 登录闭环 + 租户隔离 + 审计 | P0 |
| **S2** | 主数据 + Excel 导入 + 求解器**预研** | 四实体 CRUD/导入可用;OR-Tools 小样本基准跑通 | P0 |
| **S3** | 一键排监考闭环 | Quick→Task→Detail 全链路真实 API + 进度 + 导出 | P0 |
| **S4** | AI 策略 + 人工编辑(真拖拽) | LLM 辅助闭环;edit 真拖拽 + 冲突即时反馈 + 发布 | P0/P1 |
| **S5** | 统计报表 + 通知 | KPI/统计/历史/时间线接真实聚合;通知可追踪 | P1 |
| **S6** | 超管平台面 + 交付硬化 | 多租户控制面 + SaaS/私有化交付 + 安全/恢复演练 | P1/P2 |

> **关键路径**:S1(认证+租户)是一切前提;**S3 排考引擎为最高技术风险 → 其 CP-SAT 预研前移至 S2 后期**(09 C6)。

---

## §3 任务清单(逐 Sprint)

### S0 · 收敛与脚手架(P0)

| ☐ | ID | 任务 | 关键产出 | 依赖 | DoD |
|---|---|---|---|---|---|
| - [ ] | S0-01 | 锁定 08 为主规格、外部前缀统一 `/api/v1` | 决策记录(已落 09) | — | 团队按单一前缀;第一轮指南标注为参考 |
| - [ ] | S0-02 | Spring Boot 3.3 / Java 21 脚手架 | Maven、package-by-feature、Profiles `saas/private` | S0-01 | `mvn verify` 通过;两 Profile 可启动 |
| - [ ] | S0-03 | Flyway + `V1__init.sql` 骨架 | 迁移目录(主 + `migration-dm/` 变体) | S0-02 | 空库可迁移到基线 |
| - [ ] | S0-04 | 公共层 | 错误信封、分页约定、`TenantContext`、`TenantEntity` 基类、全局异常处理 | S0-02 | 统一错误 JSON;乐观锁 `@Version` 基类 |
| - [ ] | S0-05 | API 文档 + CI | springdoc-openapi + Swagger UI;CI(build/test/lint) | S0-02 | `/swagger-ui` 可访问;CI 绿 |
| - [ ] | S0-06 | 本地编排 | `docker-compose`(app+pg+redis+nginx) | S0-02 | 一键起本地栈 |

### S1 · 认证 / 租户 / RBAC / 安全(P0 · 前置一切)

**后端**

| ☐ | ID | 任务 | 端点/产出 | DoD |
|---|---|---|---|---|
| - [ ] | S1-BE-01 | 租户与账号实体 | `organization`/`app_user`/`academic_term`/`org_setting`(→08 §3.2–3.4,§3.15) | 迁移落库;唯一约束按 org |
| - [ ] | S1-BE-02 | <span style="color:#DC2626;font-weight:700">JWT + httpOnly</span> | RS256(access1h+refresh7d)+ bcrypt12;**httpOnly cookie/BFF**(非 localStorage) | token 可吊销;密钥可旋转 |
| - [ ] | S1-BE-03 | 登录 + 验证码 + 锁定 | `POST /auth/login`(忽略前端声明 role)、`/auth/captcha`(后端校验)、5 次失败锁 30min | 暴力破解被限流/锁定 |
| - [ ] | S1-BE-04 | 会话端点 | `/auth/me`(返回 user/org/term/permissions + <span style="color:#DC2626;font-weight:700">`onboarded` G3</span>)、`/logout`、`/refresh`、`/change-password`(首登强制) | `/auth/me` 驱动前端恢复态 |
| - [ ] | S1-BE-05 | 2FA | `/auth/2fa/verify`;email/totp + 可插拔 `SmsGateway` | 超管可强制 2FA |
| - [ ] | S1-BE-06 | 多租户隔离 | `TenantContext` + Hibernate `@Filter`;`saas/private` profile;`X-Tenant-Id` 超管覆盖 | 跨租户读写被拦截(测试用例) |
| - [ ] | S1-BE-07 | RBAC | `SUPER_ADMIN/ADMIN/VIEWER/TEACHER` + `@PreAuthorize` 矩阵(→08 §4.2) | 权限点越权返回 403 |
| - [ ] | S1-BE-08 | 审计 + CSRF + 限流 | 通用 `audit_log`(含 `user_agent`) + AOP 切面;CSRF 双提交;Bucket4j | 变更操作均留痕 |

**前端(接线 A/B)**

| ☐ | ID | 任务 | 文件 | DoD |
|---|---|---|---|---|
| - [ ] | S1-FE-01 | 新增 API adapter | `src/api.js`(JWT/CSRF/租户头/错误信封/401→login) | 全站统一调用层 |
| - [ ] | S1-FE-02 | 登录接管 | `login.jsx`:接 `/auth/login`,**按 `res.role` 路由**(保留 admin/super tab UI)、验证码后端化 | 角色由后端决定 |
| - [ ] | S1-FE-03 | 启动态恢复 | `app.jsx`:启动调 `/auth/me` 恢复 role/org/permissions;`handleLogout` 接 `/auth/logout` | 刷新后角色/权限正确 |
| - [ ] | S1-FE-04 | 导航权限 | `sidebar.jsx` 由 `permissions` 驱动可见项(保留 mode 兜底);删硬编码徽标数 | 菜单随权限/真实计数 |

### S2 · 主数据 + Excel 导入 + 求解器预研(P0/P1)

**后端**

| ☐ | ID | 任务 | 端点/产出 | DoD |
|---|---|---|---|---|
| - [ ] | S2-BE-01 | 主数据实体 | `teacher`/`classroom`/`school_class`/`subject` + 关联表(→08 §3.5–3.8) | 迁移落库;关系/唯一约束 |
| - [ ] | S2-BE-02 | 四实体 CRUD | tenant-scoped + 筛选/搜索;<span style="color:#2563EB;font-weight:700">**整页返回不分页**(R3)</span> | 列表/详情/增删改可用 |
| - [ ] | S2-BE-03 | Excel 导入 | POI 沙盒(魔数+10MB+30s)严格 3 模板(→08 §6,04 文档)+ 6 类/4 级校验报告 + `import_batch`/`validation_issue` | 行级错误定位回 `行号+列名` |
| - [ ] | S2-BE-04 | 跨表一致性 + 模板下载 | `/import/validate` + `/import/templates/{t01,t02,t03}` | 容量可行性等跨表校验 |
| - [ ] | S2-BE-05 | <span style="color:#D97706;font-weight:700">OR-Tools 预研(前移)</span> | CP-SAT 小样本建模 + 无解最小冲突集 + 性能预算(300 师×8 场<60s) | 基准报告;可行/无解/性能结论 |

**前端(接线 A/B + 删占位)**

| ☐ | ID | 任务 | 文件 | DoD |
|---|---|---|---|---|
| - [ ] | S2-FE-01 | 主数据接管 | `people`/`classrooms`/`classes-subjects`/`settings` mock→API(整页) | 四页真实数据 + 四态 |
| - [ ] | S2-FE-02 | <span style="color:#DC2626;font-weight:700">删占位 P3</span> | `classes-subjects.jsx` L97 `Math.random()` 场次→`/subjects/{id}/exams` | 不再随机 |
| - [ ] | S2-FE-03 | <span style="color:#DC2626;font-weight:700">删占位 P2</span> | `people.jsx` L156 津贴 `×120`→后端 `allowance` | 口径=后端单价 |
| - [ ] | S2-FE-04 | 设置受控化 | `settings.jsx` `defaultValue`→受控 + `/org/settings` 回填保存 | 保存真正落库 |
| - [ ] | S2-FE-05 | 上传接管 | `quick-step1` 上传接 `/import/*`;"下载模板"接端点 | 真实上传/校验/模板 |

### S3 · 一键排监考闭环(P0)

**后端**

| ☐ | ID | 任务 | 端点/产出 | DoD |
|---|---|---|---|---|
| - [ ] | S3-BE-01 | 排考实体 | `exam`/`exam_task`/`schedule_run`/`schedule_assignment`(→08 §3.9–3.12) | 状态机就绪 |
| - [ ] | S3-BE-02 | CP-SAT 引擎实装 | H1–H7 硬约束 + S1–S6/Sg/Sj 软约束 + 阈值 0.65(→08 §7.2–7.3) | 硬约束 100% 保证;可复现 |
| - [ ] | S3-BE-03 | 一键排考 | `POST /scheduling/quick`(异步 job)+ `schedule_run` 状态机 | runId 返回;异步不超时 |
| - [ ] | S3-BE-04 | 实时进度 | STOMP `/ws/schedule/{runId}` 6 阶段 | 前端可订阅 progress/stage/log |
| - [ ] | S3-BE-05 | <span style="color:#DC2626;font-weight:700">结果契约 §7.6</span> | **fairness 5 项(G1)+ targets 枚举 teacher/room/slot(G2)** | step4 字段齐全 |
| - [ ] | S3-BE-06 | 导出 | POI 4 sheet + 说明页;单视图/全部/`ai-summary.pdf` | 列头匹配前端 |
| - [ ] | S3-BE-07 | 任务域 | tasks CRUD/list(KPI total/running/done/conflict)/detail/supervisors/log/publish | 列表/详情真实 |

**前端(接线 A/B + 删占位)**

| ☐ | ID | 任务 | 文件 | DoD |
|---|---|---|---|---|
| - [ ] | S3-FE-01 | Quick 接后端 | `quick.jsx` `startUpload/startScheduling/revalidate`→API + STOMP 进度 | 真实排考链路 |
| - [ ] | S3-FE-02 | 引擎下沉 | `quick-engine.jsx` 掏空(演示 fallback 保留) | 解析/排考/导出走后端 |
| - [ ] | S3-FE-03 | 步骤接管 | `quick-step3` 订阅 STOMP;`quick-step4` 接 §7.6;下载接导出端点 | step3/4 真实 |
| - [ ] | S3-FE-04 | 任务页接管 | `tasks-page`/`task-detail` mock→API;**allowance/AIScore 用后端** | 全真实数据 |
| - [ ] | S3-FE-05 | <span style="color:#DC2626;font-weight:700">删占位 P4</span> | `timeline.jsx` 写死"今天=21/11月"→真实日期 + `/stats/exams/schedule` | "今天"动态正确 |

### S4 · AI 策略 + 人工编辑(含真拖拽)(P0/P1)

**后端**

| ☐ | ID | 任务 | 端点/产出 | DoD |
|---|---|---|---|---|
| - [ ] | S4-BE-01 | LLM 抽象 | `LlmClient`(多 provider OpenAI 兼容,复用现成 API)+ `ai_call_log`(脱敏/审计) | key 不进浏览器;入出参留痕 |
| - [ ] | S4-BE-02 | NL→DSL | `/nl-policy/parse` + `/nl-policy/refine` | 自然语言转结构约束 |
| - [ ] | S4-BE-03 | AI 策略排考 | `/scheduling/ai-policy`(复用 run 链路)+ `ai_policy` 实体 | 复用 quick 进度/结果 |
| - [ ] | S4-BE-04 | Provider | `/ai/providers`(+ <span style="color:#DC2626;font-weight:700">配额用量 G4</span>)+ `/active` 切换 | 用量/额度可见 |
| - [ ] | S4-BE-05 | <span style="color:#D97706;font-weight:700">真拖拽端点</span> | **`PATCH /tasks/{id}/schedule`**(move/swap/assign/unassign + `@Version`)(→08 §7.7C) | 增量改派 + 乐观锁 |
| - [ ] | S4-BE-06 | 冲突/修复/发布 | `check-conflicts` + `runs/{id}/ai-fix` + `tasks/{id}/publish`(邮件通知) | 冲突动态;发布触发邮件 |

**前端**

| ☐ | ID | 任务 | 文件 | DoD |
|---|---|---|---|---|
| - [ ] | S4-FE-01 | AI 策略接管 | `ai-policy`:6 策略/3 数值/NL/provider 接端点;**"优化我的表达"死按钮→`/nl-policy/refine`** | 策略真实提交 |
| - [ ] | S4-FE-02 | <span style="color:#2563EB;font-weight:700">进度复用(§7.8)</span> | `startAI`→runId→`navigateTo('quick')` 复用 step3/4 | 零新增进度组件 |
| - [ ] | S4-FE-03 | <span style="color:#DC2626;font-weight:700">edit 真拖拽(§7.7 · 唯一 C 类)</span> | `ScheduleCell` 拖放 + 候选侧栏 + 乐观更新/回滚 + 撤销栈接 PATCH + **无障碍/触屏等价操作** | 拖拽改派可用 + 键盘可达 |
| - [ ] | S4-FE-04 | 冲突/操作接端点 | `edit` ConflictBanner 动态化(**P5**);发布/AI 修复接端点 | 0/N 冲突动态渲染 |

### S5 · 统计报表 + 通知(P1)

| ☐ | ID | 任务 | 端点/文件 | DoD |
|---|---|---|---|---|
| - [ ] | S5-BE-01 | 聚合端点 | kpis/supervise/history/by-grade/monthly-trend/exams-schedule(→08 §8.1) | 维度/指标可查 |
| - [ ] | S5-BE-02 | 统计缓存 | `stats_cache`/物化视图 + 事件刷新(`ScheduleChangedEvent`) | 重聚合命中缓存 |
| - [ ] | S5-BE-03 | 派生量(支撑 G1) | 班主任监考占比 / 楼道分配均衡度 / 特殊备注处理数 + 均衡 std/gini | fairness 5 项有源 |
| - [ ] | S5-BE-04 | 通知 | 站内信/邮件 + 可插拔 `SmsGateway` + `/notifications` | 通知状态可追踪 |
| - [ ] | S5-FE-01 | 统计接管 | dashboard/kpis/stats-supervise/history/timeline mock→API | 看板真实 |
| - [ ] | S5-FE-02 | <span style="color:#DC2626;font-weight:700">删占位 P1</span> | `history.jsx` L167 贡献率 `count/200`→后端真实分母 | 贡献率正确 |
| - [ ] | S5-FE-03 | 导出 | 统计/历史"导出报表"接 `/stats/*/export` | Excel/PDF 真实 |

### S6 · 超管平台面 + 交付硬化(P1/P2)

| ☐ | ID | 任务 | 端点/文件 | DoD |
|---|---|---|---|---|
| - [ ] | S6-BE-01 | 租户域 | tenants(注册向导事务/暂停 90 天/恢复/CSV 导入)(→08 §10) | 生命周期可控 |
| - [ ] | S6-BE-02 | 账号域 | accounts(凭证/重置/强制下线/2FA/禁用 30 天/删除) | 控制面齐全 |
| - [ ] | S6-BE-03 | 平台运维 | platform-settings(广播)/audit-logs(导出)/security(IP/threats/backups)/announcements(发布/推送/撤回/read-stats)/dashboard/metrics-health/notifications | 各模块真实 |
| - [ ] | S6-FE-01 | 超管接管 | `super.jsx` 各模块→`/admin/*`;注册向导真实多步;假 toast 接端点 | 控制面真实 |
| - [ ] | S6-FE-02 | <span style="color:#DC2626;font-weight:700">租户源统一</span> | `topbar.jsx` `TENANTS` 与 `super.jsx` `SUPER_TENANTS` 合并为 `/admin/tenants` | 顶栏与租户页一致 |
| - [ ] | S6-FE-03 | 引导后端化 | `onboarding.jsx` `jkxt:onboarded`→`/auth/me` 标志 + 持久化 | 换设备记忆引导 |
| - [ ] | S6-DL-01 | 交付 | SaaS(K8s ACK)+ 私有化(Compose 离线镜像)+ Profiles | 两形态可交付 |
| - [ ] | S6-DL-02 | 可观测/备份 | Sentry/Prometheus/Grafana + 自动备份 + 恢复演练 | 监控/恢复演练通过 |
| - [ ] | S6-DL-03 | 合规/选型 | 安全扫描 + UAT runbook;**信创栈/短信网关选型确认** | 验收清单通过 |

---

## §4 后端契约待补清单(随 OpenAPI 落定)

| ☐ | 编号 | 缺口 | 处理 | 归属 |
|---|---|---|---|---|
| - [ ] | G1 | `fairness` 须 5 项(平均/最大差/班主任占比/楼道分配/特殊备注 + dots/rating) | 08 §7.6 已改;派生量见 S5-BE-03 | S3-BE-05 |
| - [ ] | G2 | `ai.targets[].kind` 枚举含 `teacher\|room\|slot` | 08 §7.6 已改 | S3-BE-05 |
| - [ ] | G3 | `/auth/me` 增 `onboarded` 布尔 + 持久化端点 | 待落 OpenAPI | S1-BE-04 |
| - [ ] | G4 | `/ai/providers` 增配额/用量(本月调用/剩余额度/平均响应) | 待落 OpenAPI | S4-BE-04 |
| - [ ] | G5 | `PATCH /tasks/{id}/schedule` 增量改派完整 schema | 08 §7.7C;随真拖拽 | S4-BE-05 |

---

## §5 前端改造总览(前端不大改 · 分类汇总)

| 类别 | 含义 | 任务 | 说明 |
|---|---|---|---|
| **A 数据源接管** | mock→`api.*`,JSX 不变 | S1-FE-01/03/04、S2-FE-01/05、S3-FE-01/02/03/04、S5-FE-01、S6-FE-01 | UI 视觉零改 |
| **B 小逻辑接线** | 改用后端值/订阅 STOMP/删占位 | S1-FE-02、S2-FE-02/03/04、S3-FE-05、S4-FE-01/02/04、S5-FE-02/03、S6-FE-02/03 | 布局不变 |
| <span style="color:#DC2626;font-weight:700">**C 结构/新交互**</span> | **唯一例外** | **S4-FE-03**(edit 真拖拽) | 经批准;骨架/视觉保留,叠加拖放层 |

**必删错误/随机占位(接真数据会显示错误)**:`history` count/200(P1·S5-FE-02)、`people` ×120(P2·S2-FE-03)、`classes-subjects` Math.random()(P3·S2-FE-02)、`timeline` 写死今天(P4·S3-FE-05)、`edit` ConflictBanner 写死 1 条(P5·S4-FE-04)。
**视觉资产**:登录纸飞机/熊猫/仪表盘——<span style="color:#059669;font-weight:700">完全保留,零改动</span>。

---

## §6 风险登记册

| ☐ | ID | 风险 | 等级 | 缓解 | 关联 |
|---|---|---|---|---|---|
| - [ ] | R1 | 排考无解(容量/人数不足) | <span style="color:#DC2626;font-weight:700">高</span> | 导入期容量可行性校验 + 无解最小冲突集回报 | S2-BE-04/05、S3-BE-02 |
| - [ ] | R2 | CP-SAT 性能/正确性(最高技术风险) | <span style="color:#DC2626;font-weight:700">高</span> | 预研前移 S2 后期 + 基准 + 域剪枝 | S2-BE-05 |
| - [ ] | R3 | 生产认证 localStorage token 不安全 | <span style="color:#DC2626;font-weight:700">高</span> | httpOnly/BFF + `/auth/me` | S1-BE-02 |
| - [ ] | R4 | LLM 不可控/超时/成本/隐私 | <span style="color:#D97706;font-weight:700">中</span> | 求解器主导 + 回退链 + 脱敏 + ai_call_log 成本监控 | S4-BE-01 |
| - [ ] | R5 | 真拖拽无障碍/触屏/并发 | <span style="color:#D97706;font-weight:700">中</span> | 键盘等价操作 + 长按拖拽 + `@Version` | S4-FE-03 |
| - [ ] | R6 | 信创栈未定(DB/OS/CPU) | <span style="color:#D97706;font-weight:700">中</span> | 默认 PG + 方言预留;客户确认后补 migration 变体 | S0-03、S6-DL-03 |
| - [ ] | R7 | 短信网关未选型 | <span style="color:#D97706;font-weight:700">中</span> | 可插拔 `SmsGateway`;私有化 noop | S1-BE-05、S6-DL-03 |
| - [ ] | R8 | 大校 Excel(>1000 师)内存/超时 | <span style="color:#D97706;font-weight:700">中</span> | EasyExcel SAX 流式 + 沙盒超时 + 分片 | S2-BE-03 |

---

## §7 验收度量(量化 DoD)

| 指标 | 口径 | 目标 |
|---|---|---|
| 登录成功率 / token 刷新 | 正常凭证登录成功、refresh 续期 | ≥99% |
| 导入行级错误定位率 | 错误回 `行号+列名` 命中 | 100% |
| 排考 job 完成率 | 提交→succeeded/fallback | ≥99% |
| 无解冲突解释率 | 无解时输出最小冲突集 | 100% |
| 排考时延 | 300 师×8 场 | <60s |
| 普通页响应 | 列表/详情 | <1s |
| 导出成功率 | Excel/PDF 文件流 | ≥99% |
| 越权拦截 | 跨租户/越权请求 | 100% 403 |
| 真拖拽闭环 | 落子→PATCH→冲突回填→可撤销 | 全通过 |

---

## §8 关键路径与并行建议

```
S0 ──► S1(认证+租户,阻塞一切)──► S2(主数据+导入) ──► S3(排考闭环) ──► S4(AI+真拖拽) ──► S5(统计) ──► S6(超管+交付)
                                         └──► S2-BE-05 OR-Tools 预研(与 S2 并行,降 S3 风险)
前端接线(S*-FE-*)随对应后端端点就绪即可并行接管;每页保留 mock 回退,支持分模块灰度。
```

**首启建议**:`S0-02/03/04` + `S1-BE-02/03/04` + `S1-FE-01/02/03` —— 先让认证闭环"通电",再按 Sprint 推进。

---

*v1.0 · 实施任务总计划 · 配套 08 主规格 / 09 路线复核 · 状态请在本文勾选框就地更新*
