# 09 · 第二轮后端开发文档复核与彩色路线标注

日期: 2026-06-05  
范围: `D:\VB\JKXT\JKXT V2.0` 现有前端 UI、`docs/BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md`、`docs/08-BACKEND-DEV-SPEC.md`、`uploads/07-UI-DEVELOPMENT-SPEC.md`

## 1. 总结论

本轮不建议推倒重写前端,也不建议重写全部后端文档。更稳妥的路线是:

- <span style="color:#DC2626;font-weight:700">必须统一后端实施基线:</span> `docs/08-BACKEND-DEV-SPEC.md` 作为当前主实施规格,采用 Java 21 + Spring Boot 3.3 + PostgreSQL 16 + OR-Tools + LLM 辅助。`docs/BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md` 保留为第一轮架构总览和联调参考,其中与 08 文档冲突的 Node/NestJS 路线不再作为主实施路线。
- <span style="color:#059669;font-weight:700">前端视觉整体保持:</span> 登录、仪表盘、一键排监考、任务详情等现有界面已具备较高视觉完成度,后端接入阶段应优先加 API adapter、状态治理和真实数据桥接,不做大规模 UI 改版。
- <span style="color:#D97706;font-weight:700">认证与 API 前缀需要二轮修正:</span> `localStorage(jkxt:token)` 只能作为静态原型/开发过渡方案;生产环境必须走服务端会话、httpOnly cookie、短期 access token 或 BFF 模式。接口前缀也需要统一为 `/api/v1` 或由网关明确改写,避免 `/api` 与 `/api/v1` 并行。
- <span style="color:#2563EB;font-weight:700">排考引擎风险应前移验证:</span> OR-Tools 不应等到 S3 才首次验证,建议在 S2 后期并行完成真实样本建模与基准测试,降低后续排考闭环返工风险。

## 2. 颜色图例

| 颜色 | 含义 | 使用场景 |
|---|---|---|
| <span style="color:#DC2626;font-weight:700">红色</span> | 必须修正 / 联调阻塞 / 安全高风险 | 技术路线冲突、生产认证安全、敏感数据泄露风险 |
| <span style="color:#D97706;font-weight:700">橙色</span> | 建议调整 / 路线顺序优化 / 中风险 | 里程碑顺序、API 前缀、求解器预研、状态迁移节奏 |
| <span style="color:#2563EB;font-weight:700">蓝色</span> | 保留并明确 / 作为主路线强化 | 08 文档主规格、混合交付、求解器主导、平台服务 |
| <span style="color:#059669;font-weight:700">绿色</span> | 可低风险保留 / 前端小改即可 | UI 视觉、动画资产、页面结构、`window.JKXT` 兼容层 |

## 3. 前后两版路线冲突与二轮判断

| 编号 | 标注 | 前一版/现状 | 二轮判断 | 落地处理 |
|---|---|---|---|---|
| C1 | <span style="color:#DC2626;font-weight:700">主实施栈冲突</span> | 第一轮集成指南建议 Next.js + NestJS/Fastify;08 文档明确 Java 21 + Spring Boot 3.3。 | 不能让团队按两条后端路线并行推进。 | 以 `08-BACKEND-DEV-SPEC.md` 为主实施基线;第一轮文档改为架构总览/前端映射参考。 |
| C2 | <span style="color:#D97706;font-weight:700">API 前缀不统一</span> | 第一轮偏 `/api/v1`;08 文档接口索引展示 `/api/auth`、`/api/tasks` 等。 | 前端 adapter、网关和 OpenAPI 必须看到同一前缀。 | 推荐统一 `/api/v1`;若 Spring 服务内部保留 `/api`,则由网关或前端代理显式改写,文档中只出现一种外部口径。 |
| C3 | <span style="color:#DC2626;font-weight:700">浏览器持久 token 风险</span> | 08 §12.1 提到 `localStorage(jkxt:token)`;当前前端也用 localStorage 管理登录态。 | 静态原型可用,生产不可作为最终认证方案。 | P1 允许过渡,但生产必须改为 httpOnly session/refresh cookie + `/auth/me`;`jkxt:auth` 仅作为 UI 首屏 hint。 |
| C4 | <span style="color:#059669;font-weight:700">前端大改不必要</span> | 当前前端是静态 React UMD/Babel + mock/localStorage,但视觉和主要工作流成熟。 | 后端接入期不应重做页面视觉。 | 新增 `src/api.js` 或等价 adapter,保持页面组件、布局、动画和素材不大改。 |
| C5 | <span style="color:#2563EB;font-weight:700">08 文档保留为主规格</span> | 08 已覆盖租户、RBAC、数据表、接口、Excel、排考、LLM、超管、部署。 | 内容完整度高,更接近可交付实施说明。 | 后续新增需求优先补 08,不要再扩散到多份互斥规格。 |
| C6 | <span style="color:#D97706;font-weight:700">求解器风险需前移</span> | 08 将排考引擎置于 S3,并建议 S2 后期预研。 | 这是全项目最高技术风险之一。 | 在 S2 后期强制加入 OR-Tools 小样本基准、无解最小冲突集、性能预算。 |
| C7 | <span style="color:#DC2626;font-weight:700">LLM 只能服务端调用</span> | UI 暗示多 provider 策略输入;文档已有服务端 LLM 方向。 | 密钥、原始隐私数据、prompt 注入防护必须写成硬性规则。 | LLM key 不进浏览器;请求做脱敏/审计/schema 校验;solver 和人工确认仍是落库前置条件。 |

## 4. 前端是否需要修改

结论: <span style="color:#059669;font-weight:700">需要小改数据层,不需要大改视觉层。</span>

| 前端位置 | 必要改造 | 级别 | 说明 |
|---|---|---|---|
| `Dashboard.html` | 引入统一 API adapter,并准备从 CDN/Babel 迁移到构建工程 | <span style="color:#D97706;font-weight:700">中</span> | 当前可继续用于演示;生产应由 Vite/Next 等构建产物接管。 |
| `src/pages/login.jsx` | 登录改为 `POST /api/v1/auth/login` + `/auth/me` 引导;`jkxt:auth` 只保留开发 hint | <span style="color:#DC2626;font-weight:700">高</span> | 认证安全是上线阻塞点。 |
| `src/app.jsx` | 启动时从 `/auth/me`、租户、权限、偏好恢复状态 | <span style="color:#DC2626;font-weight:700">高</span> | 避免只靠 localStorage 决定角色和页面权限。 |
| `src/pages/quick.jsx`、`quick-step*.jsx`、`quick-engine.jsx` | 将浏览器 Excel 解析/排考保留为本地体验 fallback,主路径接上传、导入校验、排考 job、进度 WS/SSE、导出 API | <span style="color:#D97706;font-weight:700">中</span> | UI 结构可保留,真实任务由后端持久化。 |
| `src/pages/tasks-page.jsx`、`task-detail.jsx`、`edit.jsx` | 接任务列表/详情/冲突/替换/发布/导出真实 API | <span style="color:#D97706;font-weight:700">中</span> | 重点是 loading/empty/error/data 四态,不是重做布局。 |
| `src/pages/people.jsx`、`classrooms.jsx`、`classes-subjects.jsx` | 将 mock 数据改为 tenant-scoped CRUD/import API | <span style="color:#D97706;font-weight:700">中</span> | 现有表格和筛选可保留。 |
| `src/pages/super.jsx` | 接超管租户、账号、安全、审计、备份、公告 API | <span style="color:#2563EB;font-weight:700">主路线</span> | 08 文档已覆盖平台服务,前端按其拆分联调即可。 |
| 登录纸飞机/熊猫等视觉资产 | 保留现有高级感素材,仅避免遮挡和过大动效 | <span style="color:#059669;font-weight:700">低</span> | 后端接入阶段无需再围绕资产重做。 |

## 5. 二轮推荐实施路线

| 阶段 | 二轮修正 | 目标 | 验收 |
|---|---|---|---|
| S0 文档收敛 | <span style="color:#DC2626;font-weight:700">新增</span> | 明确 08 为主实施规格,第一轮文档为参考;统一 API 外部前缀 | OpenAPI、网关、前端 adapter 均使用同一 base URL |
| S1 认证与租户 | <span style="color:#DC2626;font-weight:700">强化</span> | `/auth/login`、`/auth/me`、tenant context、RBAC、审计基础 | 前端不再只靠 localStorage 判断登录与角色 |
| S2 基础数据 + 求解器预研 | <span style="color:#D97706;font-weight:700">前移</span> | 教师/考场/班级/科目 CRUD/import,同时跑 OR-Tools 小样本 | 真实样本可验证可行/无解/性能预算 |
| S3 一键排监考闭环 | <span style="color:#2563EB;font-weight:700">保留</span> | 上传、校验、排考 job、进度、结果、导出、任务详情 | Quick -> Task -> Detail 全链路真实 API |
| S4 AI 策略与人工修订 | <span style="color:#2563EB;font-weight:700">保留</span> | LLM 只做策略解析/解释/修复建议,solver 和人工确认落库 | schema 校验、成本审计、回退链完整 |
| S5 报表与通知 | <span style="color:#2563EB;font-weight:700">保留</span> | 统计、历史、导出、站内信/邮件/短信可插拔 | KPI、导出、通知状态可追踪 |
| S6 超管与交付硬化 | <span style="color:#2563EB;font-weight:700">保留</span> | 多租户平台、安全、备份、公告、SaaS/私有化交付 | 安全扫描、恢复演练、UAT runbook 通过 |

## 6. 可视化量表: 前一版 vs 二轮收敛后

| 维度 | 前一版状态 | 二轮收敛后目标 | 分数变化 | 说明 |
|---|---:|---:|---:|---|
| 后端技术路线一致性 | 58 | 92 | +34 | 从 Node/NestJS 与 Java/Spring 冲突,收敛为 08 主实施规格。 |
| 前端不大改可行性 | 82 | 90 | +8 | 明确只改 adapter、真实状态、API 三态/四态,视觉结构保留。 |
| 认证与租户安全 | 48 | 88 | +40 | localStorage token 从生产方案降级为开发过渡;生产走服务端会话与 `/auth/me`。 |
| API 契约清晰度 | 68 | 90 | +22 | 统一 `/api/v1` 或网关改写,避免双前缀。 |
| 排考核心风险控制 | 64 | 86 | +22 | OR-Tools 小样本基准前移到 S2 后期,减少 S3 集中爆雷。 |
| LLM 可控性 | 76 | 88 | +12 | 强化服务端调用、schema 校验、脱敏、审计和 solver 主导。 |
| 产品内测可用性 | 74 | 86 | +12 | 内测可先测真实认证/数据/一键排考闭环,不被视觉重构打断。 |
| 商业交付成熟度 | 70 | 87 | +17 | 混合交付路线适合学校采购和私有化,但安全/恢复演练必须前置验收。 |

> 评分说明: 100 为可直接进入生产级实施;80+ 为路线清晰且风险可控;60-79 为可开发但返工风险明显;60 以下为需先收敛关键决策。

## 7. Product / Data / Creative / Commercial Lens 结论

- Product Design: 现有 UI 已适合内测信息架构和核心流程验证;最大缺口是 mock 行为、权限边界和真实错误处理,不是页面审美。
- Data Analytics: 后续应把每个阶段的 DoD 量化成验收指标,例如登录成功率、导入行级错误定位率、排考 job 完成率、无解冲突解释率、导出成功率。
- Creative Production: 登录和品牌资产可保留;动态素材应服务识别和愉悦感,不要在联调阶段引入新的大动效变量。
- Public Equity / Commercial Maturity Lens: K-12/学校系统更看重稳定、安全、私有化、可审计和恢复演练;混合交付路线是正确方向,但认证安全和租户隔离必须先达标。

## 8. 本轮验收清单

- <span style="color:#DC2626;font-weight:700">已标注:</span> 主实施栈冲突和认证安全风险。
- <span style="color:#D97706;font-weight:700">已标注:</span> API 前缀、求解器预研、里程碑顺序调整。
- <span style="color:#2563EB;font-weight:700">已标注:</span> 08 文档作为主实施规格,混合交付和 solver 主导保留。
- <span style="color:#059669;font-weight:700">已标注:</span> 前端视觉不大改,只做 adapter 和真实数据接入。
- 已给出前后量表,便于后续产品/研发/测试共同评审。

---

## 9. 代码级前端改造影响审计(二轮补充 · 逐文件 / 契约缺口)

> 方法:对 26 个前端文件派双 agent 逐行审计,按 **A**(纯数据源接管,JSX 不变)/ **B**(小逻辑接线)/ **C**(结构·UI 改动)分级。**结论:强制性 C ≈ 0**,从代码层面坐实第 4 节「不大改视觉层」。本节是第 4 节(页面粒度)的**行级下钻**,沿用第 2 节图例。

### 9.1 为何"前端不大改"在代码层成立(两条决定性证据)

- <span style="color:#059669;font-weight:700">校验报告渲染器与 Excel 列数解耦:</span> `quick-step2.jsx` 的 `QkStep2({data})` 只消费 `severity`(4 级)/`category`(6 类)/`overview`/`counts`/`cats`/`items`,**不读任何列名或列数**。∴ 后端无论用严格 8–10 列模板还是其它,只要响应归一成这套结构,前端校验页一像素不改。「严格模板」不构成前端 UI 改动。
- <span style="color:#059669;font-weight:700">客户端引擎下沉 ≠ 改 UI:</span> `quick-engine.jsx`(parse/validate/generate/export)是演示桩;`quick-step1~4` 是受控展示组件,引擎仅在父级 `quick.jsx` 调用,从不进入它们的 JSX。掏空引擎改 `api.*` 属**丢弃演示逻辑**,不牵动四步组件结构。

### 9.2 <span style="color:#DC2626;font-weight:700">必须补全的后端响应契约</span>(否则前端已渲染的区块缺数据 = 联调阻塞)

| # | 前端已渲染 | 代码位置 | 08 契约现状 | 二轮处理 |
|---|---|---|---|---|
| G1 | <span style="color:#DC2626;font-weight:700">公平性 5 张卡</span>(平均/最大差/班主任占比/楼道分配/特殊备注,各含 dots+rating) | `quick-step4.jsx` `qkResultData` | §7.6 示例**仅给 avg 1 项** | 已在 08 §7.6 就地补全为 5 项(本轮红标);后端须派生 `head/bldg/notes` |
| G2 | <span style="color:#DC2626;font-weight:700">AI 复核目标 chip</span>(`kind∈teacher/room/slot`,各配图标) | `quick-step4.jsx` `QkAITargetChip` | §7.6 `targets` 仅示 `kind:"teacher"` | 已在 08 §7.6 把枚举改为 `teacher\|room\|slot` |
| G3 | <span style="color:#D97706;font-weight:700">首次引导完成标志</span> `jkxt:onboarded` | `onboarding.jsx` L244/252 | §5 `/auth/me` 无该字段 | `/auth/me` 增 `onboarded` 布尔 + 持久化端点 |
| G4 | <span style="color:#D97706;font-weight:700">Provider 配额用量</span>(本月调用/剩余额度/平均响应) | `ai-policy.jsx` L164–174 | §3.14 仅有 `ai_call_log`,无配额概念 | `/ai/providers` 响应增配额/用量聚合 |

### 9.3 <span style="color:#DC2626;font-weight:700">必须删除的前端错误/随机占位</span>(接真数据会显示**错误值**,非美观问题)

| # | 文件:行 | 现状(真实代码) | 必须改为 |
|---|---|---|---|
| P1 | `history.jsx` L167 | `((r.count / 200) * 100)` 本学期贡献,**分母 200 写死** | 后端"全校本学期总场次"或直接返回 `contributionPct` |
| P2 | `people.jsx` L156 | `(supCount) * 120` 累计津贴,**单价 ¥120 写死**(且与 08 §8.3 ¥100 口径矛盾) | 后端 `allowance`(单价存 `org_setting`) |
| P3 | `classes-subjects.jsx` L97 | `Math.floor(Math.random()*8)+2` 本学期场次,**每次渲染随机** | `/subjects/{id}/exams` 真实场次 |
| P4 | `timeline.jsx` L6/L127/L353 | `today:true` 写死周三/21 日、当前线 `14:30`、`month===11` 才有数据 | 真实当前日期 + `/stats/exams/schedule` |
| P5 | `edit.jsx` `ConflictBanner` | 写死"检测到 **1 处**冲突 黄建国" | 消费 `check-conflicts` 数组,0 条隐藏 / N 条循环 |

### 9.4 <span style="color:#DC2626;font-weight:700">数据一致性必修(上线缺陷)</span>

`topbar.jsx` 的 `TENANTS`(4 校:tsy/nky/yjs/tg)与 `super.jsx` 的 `SUPER_TENANTS`(7 校,多 wjx/bsd/wq)是**两套独立硬编码且 slug/abbr 不一致**。接后端后必须统一为同一 `GET /api/v1/admin/tenants`(或当前用户可见租户),否则顶栏租户切换器与租户管理页对不上。(注:任务常被误记在 `super-ui.jsx`,实际在 `topbar.jsx` L95。)

### 9.5 死按钮 / 假动作 → 接真端点(<span style="color:#D97706;font-weight:700">B 级接线,布局不变</span>)

`ai-policy.jsx`「优化我的表达」按钮**无 onClick**(死按钮)→ `POST /nl-policy/refine`;`super.jsx` 注册租户向导、系统设置保存、IP 封禁/白名单、立即备份、公告发布/撤回**全是假 toast** → 各自真端点;`edit.jsx` 撤销/重做/发布/AI 修复同理。均不改组件结构。

### 9.6 三轮灰色点 → 决策落定(2026-06-05)

> 三个灰色点经用户本轮拍板,**已从「建议规避」转为「明确决策」**。本轮仅落文档,不动代码。

| 点 | 三轮决策 | 处理 | 前端影响 |
|---|---|---|---|
| 主数据列表分页 | <span style="color:#2563EB;font-weight:700">不做服务端分页</span> | 接口整页返回全集;超大数据量用精简列/前端虚拟滚动 | <span style="color:#059669;font-weight:700">零改动</span>(`DataTable` 不变) |
| AI 策略进度去向 | <span style="color:#2563EB;font-weight:700">复用 quick-step3/4</span> | 提交→`runId`→`navigateTo('quick')`,共用进度/结果链路(08 §7.8) | <span style="color:#D97706;font-weight:700">B 级接线</span>,布局不变 |
| edit 真·拖拽改派 | <span style="color:#D97706;font-weight:700">本期纳入(实现真拖拽)</span> | 见 08 §7.7 交互契约;新增 `PATCH` 增量改派端点 | <span style="color:#DC2626;font-weight:700">唯一 C 类</span>:叠加拖放交互层 + 候选栏,骨架/视觉保留 |

> 决策净影响:全前端改动仍以 A/B(数据源 + 小逻辑)为主;**仅 `edit.jsx` 经批准引入一处 C 类(真拖拽)**;分页与 AI 进度两项确认**不引入 C**。

### 9.7 本节对 08 文档的回写(已就地着色)

- <span style="color:#DC2626;font-weight:700">已改</span> 08 §7.6:`fairness` 补 5 项、`ai.targets.kind` 含 `room/slot`(G1/G2)。
- <span style="color:#2563EB;font-weight:700">已标注</span> 08 §4.1:保留前端登录角色 tab,后端仍以账号 role 为准。
- <span style="color:#2563EB;font-weight:700">已定</span> 08 §5.1:主数据**不做服务端分页**,整页返回,保 `DataTable` 不变(R3)。
- <span style="color:#D97706;font-weight:700">已增</span> 08 §7.7:`edit.jsx` 真拖拽改派交互契约 + `PATCH /tasks/{id}/schedule` 增量端点(R3,前端唯一 C 类)。
- <span style="color:#2563EB;font-weight:700">已增</span> 08 §7.8:AI 策略页进度复用 quick-step3/4(R3)。
- <span style="color:#DC2626;font-weight:700">待补</span> 08:`/auth/me` 增 `onboarded`、`/ai/providers` 增配额(G3/G4),将于下次随 OpenAPI 落定。
