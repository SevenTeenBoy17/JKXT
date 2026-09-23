# 前端承诺 vs 后端实现 · 断裂点全量整改计划（用户选择「D. 全部」）

> 来源：6-agent 审查 + 对抗验证（workflow `wf_4c2be172-aa0`），**43 项发现 / 38 项经证伪后确认成立**。
> 起因：用户实测发现「顶栏提示切换学期需在信息设置中操作 → 信息设置页根本没有学期区块」的死循环。
> 全量明细见 `test-data/ui-gap-findings.txt`。本文档是**分批实施与验收**的追踪表。

## 严重性分布

| 级别 | 数量 | 含义 |
|---|---|---|
| **P0** | 5 | 前后端合谋**伪造成功**，会让人据此做出错误决策（如误信有备份） |
| **P1** | 16 | 用户被卡住 / 看到编造数据 / 关键入口断头 |
| **P2** | 16 | 功能缺失但有替代路径，或影响面较窄 |
| **P3** | 6 | 文案不符、死代码、冗余别名 |

按类型：双方都缺 24 · 后端有API前端未接 11 · 前端有UI后端无API 3 · 仅文案误导 3 · 非缺陷 2（剔除）

---

## 批次划分（每批独立可验收，做完即提交）

### 批次 1 · P0 伪造成功（最高危：影响真实决策）
| # | 项 | 位置 | 修法 |
|---|---|---|---|
| 1.1 | **备份是假的** | `AdminPlatformService.java:478-494` | `runBackup()` 只插 `status='succeeded'`/4200MB/假 checksum。**真备份成本高**→ 采用「诚实化」：改为真实调用 `pg_dump` 或**明确标注为占位**并把 UI 文案改为「备份功能未接入，请使用 scripts/deploy/backup-private」；绝不能继续弹「备份完成」 |
| 1.2 | **系统设置保存是假的** | `super.jsx:1371-1412` + `AdminPlatformService.java:585-601` | 全库**无 platform_setting 表**（对抗验证补证）。→ 新建表 + 迁移，GET 读库、PUT 落库；前端 6 个文本字段改受控 + 挂载时 GET 初始化 |
| 1.3 | 公告 push 只加已读数 | `/admin/announcements/{id}/push` | 不投递通知也不改 status → 要么真投递（写 notification），要么改文案 |
| 1.4 | 命令面板「立即备份」 | `topbar.jsx:116` | 连请求都不发只 toast → 接真实端点或移除 |
| 1.5 | 审计导出「邮件发送链接」 | `super.jsx:1553` `:1296` | 全后端无 SMTP（只有 `LoggingEmailSender`），同文件 `:1255` 自认「本系统无邮件通道」→ 改为直接下载或如实标注 |

### 批次 2 · 学期功能（用户亲报的死循环，MVP 切片）
| # | 项 | 修法 |
|---|---|---|
| 2.1 | 后端无 term API | 新建 `org/api/AcademicTermController` + `org/service/AcademicTermService` + DTO：`GET /terms`、`GET /terms/current`、`POST /terms`、`POST /terms/{id}/activate`。**权限零改动**：GET 落 `tenant:read`、写落兜底 `hasAnyRole(ADMIN,SUPER_ADMIN)` |
| 2.2 | 当前学期无唯一性保证 | 迁移 **`V17`**（计划写的 `V16` 已被批次 1 的 `platform_setting` 占用）：先清脏，再建部分唯一索引 `uq_academic_term_org_current ON academic_term(org_id) WHERE is_current` |
| 2.3 | 前端无入口 | `api.js` 加 `api.terms`；`settings.jsx` 在 01/02 之间插「学年学期」区块（列表+设为当前+新建），原 02 顺延 03。注：`ui.modal` 只吃 `content:<ReactNode>`、无 `fields` 能力，故新建表单用受控子组件 `TermForm` 承载 |
| 2.4 | 顶栏徽章写死 | `topbar.jsx` 改读 `window.__jkxtMe.term`（后端 `MeResponse.term` 本就下发，只是前端没用），无 term 时显示「未设置学期」；点击直接跳 settings。连带清掉 3 处假 toast：`topbar.jsx:85` 命令面板谎报「已切换至 2025–2026 第二学期」、`history.jsx:260`、`stats-supervise.jsx:143` |
| 2.5 | 新建租户不 seed 学期 | `createTenant` 追加 `seedInitialTerm()`。幂等手法**不是**计划写的 `on conflict do nothing`——该表唯一键是 `(org_id, year_label, term)`，挡不住 2.2 新建的 `is_current` 部分唯一索引；且 `createTenant` 用 `on conflict (slug) do update` 会命中**已有**租户。故改为 `insert … select … where not exists (select 1 from academic_term where org_id = ?)`，仅在该租户零学期时播种 |
| 2.6 | **切换学期是语义空转** | `exam_task.term_id` 运行时永不写入（DB 实证 18/18 NULL）、报表不按学期过滤。**MVP 必须配文案**「学期用于报表口径与档案标识；切换不会改动已生成的排班结果」——否则修掉一个死循环又造一个新谎 |

### 批次 3 · 生产安全 + 编造数据
| # | 项 | 修法 |
|---|---|---|
| 3.1 | ✅ **SchedulingController 生产暴露** | 已修。3 个 fixture 端点落 `POST /api/v1/scheduling/**` → `schedule:manage`，任何教务管理员可调；`upsertFixtureMasterData` 按 teacherNo/roomCode 查到真实档案后**覆盖写**（教师 gender→unknown、教室 building→"P6 Fixture"、features→["solver-fixture"]、容量按 fixture 覆盖）。修法：`DeployProperties.isDev()` + 控制器守卫，非 dev 返回 404。**边界已核实**：真实业务路径 `startTaskRun` 走 `prepareRealRun`，不碰主数据；前端全仓零引用 fixture 端点，收紧不影响任何功能 |
| 3.2 | ⬜ **该发现已过期** | `tasks-page.jsx` 现已是「仅展示真实数据，不注入演示任务」并真调 `GET /exam-tasks`，写死数组 0 处。审查快照与当前代码不符，无需整改 |
| 3.3 | ✅ 任务详情页 mock | 已修。`__TD_MOCK/__TD_SUPERVISORS/__TD_SCHEDULE/__TD_LOG` 四块编造数据（假编号 T-2025-016、假负责人、4800 元津贴、假 AI 记录「评分 96/100 · 0 冲突」、假回执「已在站内通知 20 位监考教师」）。**比原描述更危险**：fetch 的 `.catch` 也降级到这条分支，接口一出错用户看到的不是错误而是一份像真的一样的排班方案。修法：`!isReal` 提前返回诚实空态，四块常量整体删除 |
| 3.4 | ✅ 顶栏租户切换器纯装饰 | 已修。核实：`jkxt:tenant-change` 全仓 **0 个监听者**、`api.js` 0 处租户头、`/admin/tenants` 需 SUPER_ADMIN 故普通管理员必 403 被静默 catch → 界面长期留着写死的 4 所**编造学校**。修法：非超管改为显示自己学校真实名称（不可点），且不再发那个注定 403 的请求 |
| 3.5 | ✅ 面包屑/侧边栏计数写死 | 已修。写死校名「天津市实验中学」共 **11 处**（10 个面包屑/页脚 + 导出文件字段）→ 新增全局 `orgName()`（common.jsx）读 `/auth/me` 的 org.name。侧边栏 `count: 8/7/7` 三处写死（空库也显示 8 个任务）→ 按身份取真实值，取不到则不渲染徽章、绝不显示假数字。**未纳入**：`super.jsx` 的编造租户库与超管命令面板/通知，属已知的示意性超管界面（代码注释已明示），作独立议题 |

### 批次 4 · 列表页搜索筛选（根因在共享组件）
| # | 项 | 修法 |
|---|---|---|
| 4.1 | ✅ 列表页搜索框静默失效 | **根因修正**：共享组件本身支持受控模式（`onSearch`/`filter.onChange` 传了就真筛），真根因是页面没传处理器。复查 8 个用 FilterBar 的页面：4 页已接好（classes-subjects/classrooms/people/tasks-page），实际待修 3 页——`history.jsx`（搜索+年级/科目/排序，选项从真实数据推导，**学期胶囊移除**：后端不按学期过滤，留着就是假承诺）、`stats-supervise.jsx`（搜索+排序；年级/科目胶囊仅「按教师」维度渲染）、`edit.jsx`（搜索考场/科目/教师 + **真实的「仅显示冲突」**：按后端 ScheduleConflict.assignmentId/conflictingAssignmentId 与 AssignmentRow.assignmentId 精确命中，非时段模糊匹配）。`super.jsx` 3 处不修：示意性超管界面，独立议题 |
| 4.2 | ⬜ 后端端点补查询参数 | **判定为暂不需要**：三页数据量为全校教师/科目级（数百行内），全量返回 + 客户端过滤诚实且响应即时；服务端分页/查询参数留待数据规模需要时再加，避免为不存在的规模预建复杂度 |
| 4.3 | ✅ 「示例筛选」占位 toast | `common.jsx` FilterPill 兜底不再谎称「已按 X 筛选」（列表纹丝不动却报成功），改为如实的「该筛选项尚未接通 · 此处仅展示信息」；已接线页面不会走到该分支 |

### 批次 5 · 只读缺口与其它 P2
| # | 项 | 修法 |
|---|---|---|
| 5.1 | ✅ **津贴单价 `allowanceUnit` 永久不可改** | 已修。新增 `GET/PUT /api/v1/org/settings`（OrgSettingsController/Service，权限走既有兜底零改动）：jsonb_set 只动 allowanceUnit 键、其余 settings 键原样保留；读侧数字模式守卫与 MasterDataService 同款（脏值回退 120 不得 500）。前端 settings.jsx 新增「03 津贴标准」区块（账户安全顺延 04），确认弹窗如实说明「金额为现算口径，修改后全部统计与报表即时生效」。测试 OrgSettingsApiIntegrationTest 3 条（含 jsonb 其它键保留、脏值回退） |
| 5.2 | ✅ 监考教师「响应状态」写死 pending | 已修。**比原描述更深**：不止状态列写死——「工号」列也是 `'T'+(2025000+i)` 顺序编造的假工号（后端 AssignmentRow.teacherNo 有真值没人用）。删除假状态列（后端零响应追踪数据，该状态机除「待响应」外分支不可达）、工号改真值、「提醒响应」按真实语义改名「重发通知」并在 toast 里说明是整任务广播非单人催办 |
| 5.3 | ✅ Notification 无生产者 | 已修（部分）。NotificationService 增 `publishSystemNotice` 统一入口；接入两个高价值生产者：**导入完成**（ImportCommitService，与导入同事务——回滚则不发）、**排考失败/无解**（ScheduleJobService 的 catch 与 infeasible 路径——此前没盯进度条的人完全不知任务已失败）。成功路径不发（正式下发走既有「排班发布」通知，避免一任务双通知）。公告推送属超管示意界面，独立议题。测试 NotificationProducersAcceptanceTest |
| 5.4 | ⬜ 批量导入租户/账号 | **归入 super.jsx 示意界面独立议题**：两处假表单都在 super.jsx（:888 租户、:1296 账号，后者 toast 还编造「模板已发送至 admin@jkxt.cn」），与超管界面整体属同一议题 |
| 5.5 | ✅ SSE 进度流未接 | 已修。根源有二：①前端 500ms×80 轮询、一到 80 次就谎报「排监考任务超时」（后端任务仍在跑且会正常完成）；②nginx 默认 proxy_buffering 会把 text/event-stream 扣到响应结束，SSE 根本流不动。修法：nginx 给 `*/events` 路径加免缓冲 location（proxy_buffering off + 1h read timeout）；前端改 SSE 优先（event: progress）+ 轮询兜底（2s 间隔、10 分钟上限、到点如实说明「任务仍在后台运行」而非谎称超时），终态以 GET 快照为准 |

### 批次 7 · 超管界面真实化（2026-08-18 追加，用户指令「超管示意界面继续优化升级」）
| # | 项 | 修法 |
|---|---|---|
| 7.1 | ✅ **静默假数据兜底**（核心缺陷） | 复查修正：super.jsx 主体**早已接真实 /admin/* 端点**，真正的问题是 useSuperResource/useSuperObject 在接口失败时**静默换上 5 块编造兜底**（假租户/账号/审计/威胁 IP/公告，含批次1 定性为伪造的「自动备份完成 4.2GB」）——后端故障时超管看到一屏可信的假运营数据。修法：兜底改诚实空值 + hooks 暴露 error + 6 个页面顶部 `SuperApiErrorBanner`（明示「空/零值并非真实状态」） |
| 7.2 | ✅ 3 处 FilterBar 未接线 | 租户/账号/审计三页搜索筛选全接线；选项从真实数据推导（原「基础版/旗舰版」「北京/上海」与后端取值不对应，选了永远筛不出结果；「北京一中/上海中学」是编造租户）。审计页「时间窗」胶囊移除——后端不接时间参数，装模作样筛时间即假承诺 |
| 7.3 | ✅ 假批量导入 ×2 | 租户(:888 假 CSV 拖拽区+假模板下载)与账号(:1296 编造「模板已发送至 admin@jkxt.cn」)→ 如实说明「后端暂无批量导入接口，请逐个创建」 |
| 7.4 | ✅ 系统设置初值写死 | 批次1 已让 GET/PUT 真读写 platform_setting 表，但前端从不 GET——保存后一刷新界面回到写死默认，像"设置丢了"。改挂载时读真值 merge（键名与后端 PLATFORM_SETTING_DEFAULTS 逐一核对一致）；「影响所有 7 个租户」不再编数字 |
| 7.5 | ✅ topbar 超管假通知/假面板项 | SUPER_NOTIFICATIONS 7 条编造通知→铃铛改读真实 /admin/announcements（公告无按人已读追踪，unread 恒 false 不伪造小红点；markRead 超管态不再向不存在的 /notifications/{公告id}/read 发请求）；命令面板 3 条写死编造租户→运行时从真实 /admin/tenants 生成；「注册新租户/导出审计日志」两个假 toast 动作→真实跳转 |
| 7.6 | ⬜ 装饰性健康仪表 | dashboard 的「数据库连接 14/50」「今日 API 调用 7.1万」等系统健康指标后端无数据源，属已知限制——需要 actuator 指标透出后再真实化，本轮不编造替代品 |

### 批次 6 · P3 清理
| # | 项 | 修法 |
|---|---|---|
| 6.1 | ✅ `RoleRepository` 零消费者死代码 | 已删 `Role` 实体 + `RoleRepository`（全库零引用，`role` **表**保留——AuthService 的裸 SQL 是 P17 修复刻意为之，迁移它风险大于收益，不做） |
| 6.2 | ✅ 冗余别名端点 | 已删 5 条别名（`/history/summary|teachers|by-grade|monthly-trend` + `/stats/timeline/schedule`），保留规范路径。删前逐一核实：前端 src/、e2e 脚本、源码快照对别名**零引用**；唯一使用者是 P10 测试的 2 处，已同步改规范路径 |
| 6.3 | ✅ 文案：声称支持 .csv | `quickactions.jsx` 改「支持 .xlsx / .xls」——前后端(POI WorkbookFactory)都只认 Excel 工作簿 |

---

## 通用验收标准（每批都适用）

1. **不得用假实现替换假实现**——若某能力确实不做，UI 必须如实说明，不得弹成功提示
2. 每批做完跑 **Docker maven 全套件**（当前基线 103 绿），零回归
3. 涉及行为变更的加**回归测试**（RED-first：先证明测试能抓到缺陷）
4. 前端改动需在**真实 Chrome** 里验证（用户登录一次后由 agent 驱动）
5. 每批**独立提交**，commit message 说清「原来骗了什么、现在真做了什么」

## 状态追踪

| 批次 | 状态 | 提交 |
|---|---|---|
| 1 · P0 伪造成功 | ✅ 完成并部署 | `d244b61`（V16 实盘随批次2 部署补上）|
| 2 · 学期功能 | ✅ 完成并部署 | `feaf145`（后端）；前端改动随用户 WIP 待提交 |
| 3 · 生产安全+编造数据 | ✅ 完成并部署 | `7552c3b`（3.1 后端）；3.3/3.4/3.5 前端待提交；3.2 判定过期 |
| 4 · 列表页搜索筛选 | ✅ 完成 | `8ac945d`（docs）；3 页接线前端待提交；4.2 判定暂不需要 |
| 5 · 只读缺口+P2 | ⏳ 进行中 | — |
| 6 · P3 清理 | ⏳ 待开始 | — |

> ⚠️ **前端改动边界**：`src/*.jsx` 与 `Dashboard.html` 有用户未提交的 WIP 改动。动前端前需确认——本计划中批次 2/3/4 含大量前端改动，需用户授权或先合并其 WIP。
