# JKXT 真实学校监考验收执行计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 用大规模可复现合成数据、分工明确的 Agent 团队和独立 Oracle，验证 JKXT 的 AI 排监考在真实学校常态、冲突、突发和策略差异下是否达到发布质量。

**Architecture:** 测试采用“基线学校 + 场景覆盖层 + 策略层 + 扰动层 + 独立 Oracle + 证据包”的六层结构。富数据使用 JSONL 保存真实业务属性，现有 JKXT Excel 表头作为兼容投影；执行环境与当前 18180 演示栈隔离，生成者、执行者、判定者和 Reviewer 分属不同责任组。

**当前状态边界：** 本轮交付的是可执行计划与合成验收工件。29,376 是**计划运行数**，不是已执行数；静态报告当前最多只能产生 `ARTIFACT_PASS/ARTIFACT_FAIL`，在线验收状态仍为 `NOT_RUN`。在隔离栈完成真实 API/UI/求解/AI 调用并形成独立 Oracle 证据前，当前唯一允许的正向结论是 `ARTIFACT_PASS`，不得表述为“系统已通过”或把 `pending` 计作完成。

**Tech Stack:** Java 21 / Spring Boot / PostgreSQL 16 / Redis 7 / OR-Tools CP-SAT / Docker Compose / Node.js / @oai/artifact-tool / JSONL / XLSX。

**Spec:** docs/superpowers/specs/2026-08-30-real-school-proctoring-acceptance-design.md

## Global Constraints

- 本计划所有身份、联系方式、请假和健康信息均为合成数据。
- 年龄不直接作为不利排班依据；只使用显式、最小化的工作能力/照顾代码。
- 当前运行中的 jkxt_private 演示栈只保持可用，不做批量灌数或破坏性清理。
- 任何硬约束不得因公平、速度、领导偏好或 AI 建议而被静默弱化。
- 预期无解必须如实返回 infeasible/冲突类别，不能用减少监考人数等方式伪造成功。
- 每个结果必须绑定输入哈希、随机种子、策略版本、系统版本和独立 Oracle 结论。
- `config/oracle-policy.json` 只能由独立 A7 在执行前审定并冻结；生成器、执行 Agent 和系统输出均不得派生、改写或反向影响它。
- 每个场景的 D1-D4 都从同一只读基线做 copy-on-write 克隆，每次只施加该 D 档定义的一项扰动；四次运行相互独立，禁止把 D1 的结果或状态累计到 D2-D4。
- 教师真实 PII 字段中的电话必须留空；`contact_token` 只是测试关联令牌，不是电话、邮箱或可投递地址。验收环境关闭外部邮件/短信，只允许站内通知。
- `capability_gaps` 与 `blocking_capability_gaps` 是每个运行信封的必填数组（无缺口也必须为 `[]`）；前者记录非阻断结构缺口，后者记录会阻断门的缺口。所有非通过终态同样必须保存应用日志、终态捕获和 A7 Oracle 结果，不得因失败、超时或阻塞而省略证据。
- 本轮只新增计划、生成器和数据包，不修改应用源代码。

---

## 1. 执行摘要与当前基线

现有资产已经覆盖小学、初中、高中各 12 套数据、基础导入、规模、班主任避本班、同学科软回避、可解/无解和脏数据路径，但它们不能直接证明“真实学校复杂场景已通过”：

| 证据 | 已有结果 | 本计划如何处理 |
|---|---|---|
| K12 场景 | 36 套；最终审计实际覆盖 35 套 | 保留为回归层，不作为新体系的全部验收证据 |
| 最终审计 | 33 PASS / 1 FAIL / 1 SKIP | 作为带已知缺陷的基线，不改写历史 |
| 边界/负向预期 | P11/J11/S11 预期无解却成功；P12 出现硬违规；S12 无分配 | 由独立 Oracle 预先冻结期望，系统结果不能反向改期望 |
| UAT | docs/UAT-CHECKLIST.md 中项目仍为 Pending | 在线真实验收后逐项用证据关闭 |
| 请假/健康/年龄 | 当前 fixture 与 36 个 manifest 基本未覆盖 | 新增时间窗不可用、照顾代码和动态替换 |
| 职务/多校区 | 当前模型缺失或未进入求解 fixture | 分成“当前可验证”“产品能力缺口”两类，禁止假通过 |
| AI 策略 | 仅少量 mock DSL 集成测试 | 新增 96 条真实语言、改写、歧义、矛盾和注入提示 |
| 可重复性 | 已有同种子运行教师数/哈希不一致 | 使用输入快照、配对种子、重复运行和置信区间 |

结论：本轮的合格对象不是“生成一个看起来合理的课表”，而是“在可追溯输入下，稳定满足硬约束，并能解释策略差异与异常恢复”。

## 2. 政策与工程依据的使用边界

以下来源用于设计控制，不把特定考试规则机械套用到所有校内考试：

- 教育部普通高校招生考试考务工作规定提供资格、培训、回避、主副监考和流动监考等控制基线：<http://www.moe.gov.cn/srcsite/A15/moe_776/s3258/201604/t20160411_237531.html>
- 《个人信息保护法》要求处理个人信息遵循明确目的、最小必要，并对自动化决策的透明、公平和影响保持控制：<https://www.gov.cn/xinwen/2021-08/20/content_5632486.htm>
- 残疾人保障相关规则支持以实际工作条件/便利为依据设置照顾，而不是用年龄标签替代个体能力：<https://www.gov.cn/fuwu/cjr/2013-07/16/content_2630795.htm>
- NIST AI RMF 的 Govern/Measure/Manage 控制支持人工监督、问责、TEVV、偏差监测和持续风险管理：<https://airc.nist.gov/airmf-resources/airmf/5-sec-core/>
- OR-Tools 官方排班示例明确区分覆盖/不重叠硬约束与偏好/均衡目标，并保留可行/不可行状态：<https://developers.google.com/optimization/scheduling/employee_scheduling>
- OR-Tools Workforce Scheduling 文档支持把优先级、覆盖和偏好分层：<https://developers.google.com/optimization/service/scheduling/workforce_scheduling>
- 鲁棒排课研究用于设计“扰动后最小改动”指标，不作为法律依据：<https://arxiv.org/html/2311.17766>

学校在正式执行前必须冻结一份 `exam-policy-matrix.json`，明确考试类型、硬/软规则、规则版本，以及 `approval_state`（审批状态）、`jurisdiction`/`locality_status`（适用地区与本地映射状态）、`approved_by`（批准人）、`effective_from`/`effective_until`（生效期）。任一审批字段缺失、仍为占位值、未批准或已过期，都不能通过 G0-05。未获得地方细则的数值阈值一律标为“项目验收阈值”，不能标成法规。

独立判定策略另存为 `config/oracle-policy.json`。它由 A7 根据已批准的政策矩阵在任何在线执行前单独冻结并记录哈希，不属于生成器产物；A7 不得从 `generation-config.json`、场景生成结果、被测系统 DSL、自检结果或运行输出自动推导该文件。

## 3. Agent 团队与责任隔离

实际测试最多同时运行 4 个 Agent，按波次调度。生成者、执行者、Oracle 和 Reviewer 不复用责任组。

| ID | Agent / 个性 | 责任 | 写入租约 | 禁止事项 |
|---|---|---|---|---|
| A0 | 测试总监 Agent | 冻结版本、编排波次、处理终态 | runs/control/** | 不改 Oracle 期望 |
| A1 | 学校运营 Agent（务实教务主任） | 生成校历、考试、职务和现实事件 | source/school-ops/** | 不判定系统合格 |
| A2 | 教师画像 Agent（关怀型 HR） | 合成任课、班主任、照顾、请假、资格 | source/teacher-profiles/** | 不存诊断文本 |
| A3 | 资源 Agent（严谨考务员） | 生成考场、容量、设施、校区和通勤矩阵 | source/resources/** | 不放宽容量 |
| A4 | 策略 Agent（多人格） | 生成 8 种策略和 96 条提示变体 | source/strategies/** | 不直接调用数据库 |
| A5 | 扰动 Agent（红队） | 注入生病、封场、延误、群体缺勤、冲突 | runs/disruptions/** | 不改基线文件 |
| A6 | 执行 Agent（机械执行） | 调用真实 API/UI，保存原始响应 | runs/raw/** | 不解释或修补结果 |
| A7 | 约束 Oracle（独立裁判） | 独立审定并冻结 config/oracle-policy.json；在执行前计算可解性/硬约束和预期类别 | config/oracle-policy.json、oracle/** | 不让生成器派生 Oracle 政策；不读取系统“自评”作真值 |
| A8 | 统计 Agent（审慎分析师） | 公平、稳定性、性能、策略配对分析 | runs/metrics/** | 不删除离群失败 |
| A9 | 证据 Agent（审计员） | 哈希、版本、证据索引、链路完整性 | evidence/** | 不修改原始响应 |
| A10 | 独立 Reviewer（保守验收） | 复跑关键门并作 APPROVE/REQUEST_CHANGES | review/** | 不与 A1-A9 同组 |

本轮已经实际建立两名只读研究 Agent：制度/政策 Agent 使用 Exa 检查 50 个来源；现有资产 Agent 扫描测试数据、求解 fixture 和 UAT 缺口。它们不写产品文件，其结论已纳入本计划。

### 3.1 Agent 共享状态

每个运行只通过 run-envelope.json 交换状态，必须包含：

| 字段 | 说明 |
|---|---|
| run_id | RS-{tier}-{scenario}-{strategy}-{seed} |
| input_manifest_sha256 | 教师、考场、考试、策略、扰动清单的组合哈希 |
| baseline_parent_id | 非 AI 运行必填；同一 scenario 的 D1-D4 与全部扰动运行必须指向同一冻结基线父层 |
| baseline_assignment_snapshot_sha256 | pending 时为空；非 AI 运行进入任一终态前必须写入实际 baseline assignment snapshot 的 SHA-256，同一 scenario 不得出现多个值 |
| expected_feasibility | feasible / infeasible / boundary |
| policy_version | 学校政策矩阵版本 |
| strategy_id | 8 种策略之一 |
| seed | 固定整数种子 |
| app_version / solver_version | 被测版本 |
| started_at / finished_at | UTC 时间 |
| raw_result_sha256 | 原始响应哈希 |
| oracle_result_sha256 | 独立判定哈希 |
| application_log_sha256 | 本次运行应用日志/日志索引哈希；包括失败、超时、阻塞和 incomplete |
| terminal_capture_sha256 | SSE/轮询最后响应、错误信封或超时捕获的哈希；所有非通过终态必填 |
| capability_gaps | 必填数组；无缺口写 `[]`，有缺口逐项写未进入系统的字段/能力和影响门 |
| blocking_capability_gaps | 必填数组；只记录使门失败或不可判定的能力缺口；pass 必须为空 |
| terminal_state | pending / pass / fail / timeout / unknown / blocked / incomplete；非 pending 时上述版本、时间与结果哈希不得为空 |

除 `pending` 外，每个终态都必须同时具有日志、终态捕获和 Oracle 结果。`pass` 必须对应 `oracle.hard_gate=PASS`，且所有适用的 H01-H12 均为 PASS；`fail` 必须对应 `oracle.hard_gate=FAIL`，且至少一个 H01-H12 为 FAIL；`timeout/unknown/blocked/incomplete` 必须对应 `NOT_EVALUABLE` 和非空原因码，不能用“无结果”跳过 Oracle。AI 提示运行也必须保存轮询/SSE 终态捕获。

## 4. 大规模合成数据设计

### 4.1 四个规模层

| Tier | 原型 | 教师 | 考场 | 考试考场次 | 发布用途 |
|---|---|---:|---:|---:|---|
| S | 乡镇单校区小校 | 64 | 18 | 56 | 功能门，必须 100% 通过 |
| M | 城区标准学校 | 240 | 72 | 480 | 标准发布门，必须 100% 通过 |
| L | 城市三校区学校 | 760 | 260 | 2,100 | 大规模发布门，可解成功率至少 98% |
| XL | 区域级极限画像 | 1,600 | 540 | 6,000 | 容量拐点画像，不单独阻断发布 |

总基线包含 2,664 名合成教师、890 个考场和 8,636 个考试考场次。每位教师拥有工号、合成姓名、学科/年级/任课班级、班主任班级、职务、资格、校区、可用时段、工作量上限、连续场次上限、备用资格和最小化照顾代码。真实 PII 投影中的手机号一律留空；如测试需要跨文件关联，只使用不可路由、不可反解且明确标记为测试用途的 `contact_token`。邮箱仅可使用 RFC 保留的 `.invalid` 无投递域名；外部邮件/短信通道关闭，通知验证只覆盖站内 inbox。

### 4.2 数据文件

| 文件 | 作用 |
|---|---|
| generated/teachers.jsonl | 富教师画像真值 |
| generated/rooms.jsonl | 校区、楼层、容量、设施和无障碍属性 |
| generated/exam_sessions.jsonl | 每个考场次与监考需求 |
| generated/scenario_catalog.jsonl | 384 个场景定义 |
| generated/disruptions.jsonl | 动态事件和生效时间 |
| generated/strategy_prompts.jsonl | 96 条 AI 提示及期望语义 |
| generated/run_matrix.jsonl | 29,376 个分阶段运行定义 |
| config/oracle-policy.json | A7 在执行前独立审定并冻结的 Oracle 政策；禁止由生成器派生或覆盖 |
| generated/travel-matrix.json | 各 Tier 30/45/60/120 分钟四档通勤矩阵与单校区覆盖层路线 |
| generated/hard-constraint-map.json | 场景子码到 G0/H01-H12 的机器映射 |
| generated/acceptance_criteria.json | 硬门、软门、评分、证据要求及明确的 `release_matrix` |
| generated/agent_team.json | Agent 契约和租约 |
| schemas/*.schema.json | 教师、场景、扰动、运行信封和证据索引闭合契约 |
| outputs/{thread}/real-school-acceptance-matrix.xlsx | 人可读验收矩阵 |
| outputs/{thread}/import-ready/{tier}/ | 每个 Tier 的教师/考场/考试三份 JKXT 兼容工作簿 |

### 4.3 24 个场景家庭

| ID | 场景家庭 | 关键变化 | 预期 |
|---|---|---|---|
| F01 | 正常单年级考试 | 无扰动 | 可解 |
| F02 | 多年级并行 | 多学科、多楼栋 | 可解 |
| F03 | 本学科回避 | 科任教师不得监本科目 | 可解或边界 |
| F04 | 本班/班主任回避 | 班主任和任课班级冲突 | 可解 |
| F05 | 校长/主任考务职务 | 管理职责与普通监考互斥 | 可解 |
| F06 | 主副监考资格 | 每场需至少一名主监资格 | 可解 |
| F07 | 提前一周请假 | 已审批全日/半日不可用 | 可解 |
| F08 | T-24 小时生病 | 单人撤回 | 可修复 |
| F09 | T-30 分钟急病 | 候补替代 | 可修复且低改动 |
| F10 | 群体流感/培训 | 5%/10%/20%，第四级压力为 30% 同时不可用 | 可解、边界或无解 |
| F11 | 年龄与站立照顾 | 使用功能限制，不仅看年龄 | 可解 |
| F12 | 行动/无障碍照顾 | 禁止楼道、跨楼和无电梯楼层 | 可解 |
| F13 | 孕期/哺乳照顾 | 仅保存 duty restriction code | 可解 |
| F14 | 稀缺学科教师保护 | 避免影响命题/阅卷/授课 | 可解 |
| F15 | 资格和培训到期 | 未培训教师不得作为主监 | 可解或无解 |
| F16 | 连续场次与休息 | 上限、午间和夜间限制 | 可解 |
| F17 | 多校区通勤 | 30/45/60/120 分钟四档交通矩阵；单校区 Tier 使用只在覆盖层存在的临时外部考点 | 可解或无解 |
| F18 | 临时封闭考场 | 设备故障、停电、消防检查 | 可修复 |
| F19 | 考试延期/时间移动 | 一科延后并与其他场次冲突 | 可修复 |
| F20 | 特殊考场 | 听力、机考、无障碍、隔离考场 | 可解 |
| F21 | 候补池耗尽 | 连续多次替补 | 正常/紧张可解，严重为边界，越界为无解 |
| F22 | 利益/亲属回避 | 显式 interest conflict code | 可解 |
| F23 | 数据矛盾/脏数据 | 时间反转、缺考场、重复工号 | 导入拒绝 |
| F24 | 资源故意不足 | 监考需求超过合格教师 | 必须 infeasible |

每个家庭与 S/M/L/XL、正常/紧张/严重/预期无解四级压力组合，生成 384 个场景。所有 expected_feasibility 在执行前由 A7 冻结。

### 4.4 D1-D4 独立扰动语义

D1-D4 是同一场景的四个**独立注入时点分支**，不是压力等级，也不是按时间依次叠加的事件链。压力等级由场景 ID 中的 `normal/tense/severe/infeasible` 唯一决定；D1=T-7d、D2=T-24h、D3=T-2h、D4=T-30m。执行器必须以冻结的 baseline snapshot 为父层，对每个 D 分支建立独立 copy-on-write 子层并只应用该分支扰动。规划阶段用同一 `baseline_parent_id` 绑定父层；真实执行产生基线安排后，所有非 AI 子运行还必须写入同一 `baseline_assignment_snapshot_sha256`。运行结束即丢弃；后续分支不继承前一分支的缺勤、封场、延期、替补消耗或数据库状态。若一个业务故事需要多扰动组合，必须分配新的组合场景 ID。

## 5. 八种策略与 AI“个性”测试

硬约束集合对所有策略完全相同，策略只能改变软目标权重。

| 策略 ID | 个性/目标排序 | 必须观察的方向 |
|---|---|---|
| STR-BASE | 中性教务主任 | 作为对照组 |
| STR-FAIR | 公平审计型 | 降低加权负载 Gini 和极差 |
| STR-CARE | 关怀型 HR | 降低照顾群体不利时段/跨楼任务 |
| STR-STABLE | 稳健保守型 | 扰动后最小改动、保留原安排 |
| STR-EFFICIENT | 效率型校长 | 缩短求解时间与跨校区移动 |
| STR-HOMEROOM | 班主任保护型 | 减少班主任冲突和集中负担 |
| STR-SUBJECT | 教学连续性型 | 保护稀缺学科、命题和阅卷教师 |
| STR-RESERVE | 韧性型考务员 | 保留候补余量，避免早期耗尽 |

### 5.1 96 条提示矩阵

每种策略生成 12 类提示：标准、简写、口语、学校公文、反向表达、含多个权重、歧义、内部矛盾、与硬约束冲突、要求静默放宽、提示注入、超出当前 DSL 能力。验收不是逐字匹配，而是检查：

- 明确提示的语义映射正确。
- 同义改写保持同一 DSL。
- 歧义提示要求澄清或标记不确定，不擅自猜测。
- 矛盾提示指出冲突并拒绝执行。
- 要求弱化硬规则时明确拒绝。
- 当前模型不能表达的规则返回 unsupported/capability_gap。
- 解释列出实际生效的硬规则、软权重和被忽略项。

## 6. 验收标准与质量门

### 6.1 Gate 0：数据与环境

| ID | 标准 | 合格线 |
|---|---|---|
| G0-01 | 合成身份/隐私 | 真实 PII 命中数为 0 |
| G0-02 | 可复现 | 同版本同种子的规范化 JSONL 与工作簿单元格内容摘要 100% 一致；XLSX 容器文件哈希按运行留证 |
| G0-03 | 引用完整性 | 教师/考场/考试/策略/场景外键错误为 0 |
| G0-04 | 隔离 | 使用独立 Compose 项目、DB、Redis、端口和租户 |
| G0-05 | 版本与政策冻结 | 应用、求解器、策略、数据生成器版本全部存在；政策矩阵的本地审批状态、适用地区、批准人和生效期完整有效，且 A7 独立冻结的 oracle-policy 哈希存在。任一项缺失即 FAIL |

### 6.2 Gate 1：P0 硬约束

任一项失败即整批 REQUEST_CHANGES，不进入加权评分。

| ID | 硬约束 | 合格线 |
|---|---|---|
| H01 | 所有考试考场次覆盖 | 100% |
| H02 | 教师同一时段重复 | 0 |
| H03 | 考场同一时段重复 | 0 |
| H04 | 每场监考人数精确 | 100% |
| H05 | 请假/生病/不可用教师被安排 | 0 |
| H06 | 已配置本学科/本班/班主任/利益冲突 | 0 |
| H07 | 不可达跨校区或跨楼转场 | 0 |
| H08 | 无资格人员承担主监/特殊考场 | 0 |
| H09 | 显式照顾/工作能力限制违规 | 0 |
| H10 | 预期无解识别 | 100%，带冲突类别 |
| H11 | AI 静默删除或弱化硬规则 | 0 |
| H12 | 原始证据、审计或租户串数据 | 0 |

### 6.3 Gate 2：发布质量

`generated/acceptance_criteria.json.release_matrix` 必须逐 Tier 固化下表全部字段。含 `selector.threshold_source` 的门按该单一 JSON Path 逐 Tier 解引用；跨阶段门使用命名对象 `selector.threshold_sources.{phase}` 分别解引用，再按 `aggregate` 聚合。两者都是唯一权威阈值来源；顶层 `threshold` 仅作旧执行器展示/默认值，不得覆盖解析值。数值必须按该门 `operator` 判定；`profile_only` 只采集与报告，并从发布阻断分母排除。无效路径、缺 Tier、类型不符或聚合结果冲突必须 fail closed。S/M/L 必须是数值阈值；XL 不设发布阈值的项目必须明确写 `profile_only`，不能留空或混入 S/M/L 发布阻断分母。

| `release_matrix` 字段 | S | M | L | XL（画像、非发布阻断） |
|---|---:|---:|---:|---:|
| 预期可解成功率 | 100% | 100% | ≥98% | `profile_only` |
| 普通求解 p95 | ≤15 秒 | ≤45 秒 | ≤120 秒 | `profile_only` |
| 扰动恢复 p95 | ≤15 秒 | ≤45 秒 | ≤120 秒 | `profile_only` |
| 正常场景加权负载 Gini | ≤0.12 | ≤0.12 | ≤0.12 | ≤0.18 |
| 紧急场景加权负载 Gini | ≤0.18 | ≤0.18 | ≤0.18 | `profile_only` |
| 单人缺勤未受影响分配保留率 | ≥95% | ≥95% | ≥95% | ≥90% |
| 10% 群体缺勤保留率 | ≥80% | ≥80% | ≥80% | `profile_only` |
| 明确 AI 提示语义准确率 | ≥98% | ≥98% | ≥98% | 同 L |
| 矛盾/不支持提示识别率 | 100% | 100% | 100% | 100% |
| 解释-实际规则一致率 | ≥95% | ≥95% | ≥95% | ≥95% |
| 证据包完整率 | 100% | 100% | 100% | 100% |

“可解成功率”不把 timeout/unknown 算作成功；“保留率”只统计不受扰动且仍合法的原分配。
“可解成功率”的分母只包含 `expected_feasibility=feasible` 且证据完整的运行；`boundary`、`infeasible`、`validation_reject` 分别进入边界、无解识别和导入拒绝门，不得混入成功率分母。XL 仅提供容量画像，不进入 S/M/L 发布分母；Q04-Q07 的 XL 阈值按表中单列规则处理。

### 6.4 Gate 3：加权总分

硬门全部通过后才计算：

| 类别 | 权重 |
|---|---:|
| 正确性与场景覆盖 | 30 |
| 扰动恢复与稳定性 | 20 |
| 公平与照顾 | 15 |
| AI 策略忠实度 | 15 |
| 性能与容量 | 10 |
| 解释、审计与可运维性 | 10 |

最终合格线为 ≥90/100，每个类别得分率 ≥80%，且无未关闭 P0/P1。缺证据不得按零失败处理，只能标记 incomplete。

## 7. 统计协议

DataCamp 的实验设计、功效分析、自助法置信区间、置换检验和蒙特卡洛模拟材料用于方法选择参考；项目实际采用以下固定协议：

- 硬约束使用确定性全量断言，不做抽样推断。
- 策略比较使用相同场景、相同种子的配对设计。
- 标准资格层：144 个 S/M/L 正常/紧张场景 × 8 策略 × 10 种子 = 11,520 次。
- 扰动深测层：64 个高风险场景 × 8 策略 × 30 种子 = 15,360 次。
- AI 提示层：96 条提示 × 10 次 = 960 次。
- 加上 1,536 次全场景×D1-D4 冒烟，总运行矩阵为 29,376 次；每个扰动 ID 至少被绑定一次。
- 对 Gini、p95、改动率和策略差异报告 95% bootstrap 置信区间，重采样 10,000 次。
- 多策略比较使用 Holm 方法控制多重比较；alpha=0.05，目标功效 ≥0.80。
- 预先定义最小实际差异：Gini 下降 0.02、保留率提高 5 个百分点、p95 下降 10%。
- 同时报告中位数、p95、最大值、失败数；不得只报告均值。

DataCamp 方法入口包括：

- <https://app.datacamp.com/learn/courses/foundations-of-inference-in-python>
- <https://app.datacamp.com/learn/courses/ab-testing-in-python>
- <https://app.datacamp.com/learn/courses/statistical-simulation-in-python>
- <https://app.datacamp.com/learn/courses/experimental-design-in-python>

## 8. 执行波次与真实测试流程

### Wave 0：冻结与预检

- 冻结数据生成器、政策矩阵、策略 DSL、应用镜像和 Oracle 版本。
- 校验 29,376 个 run_id 唯一、所有外键有效、1,536 个扰动均被运行绑定，预期无解均有容量或通勤构造证明。
- 建立独立 jkxt_acceptance Compose 项目，使用 19180/19081/19432/19379 端口和独立卷。
- 备份初始空库并记录恢复命令。

### Wave 1：导入与硬约束

- S/M/L 每层先跑一套三表导入和校验。
- 对 384 个场景执行 baseline 策略冒烟。
- A7 在系统结果之外检查 H01-H12。
- 任何硬违规立即停止该场景后续策略，保留失败证据。

### Wave 2：策略与 AI

- 对 144 个发布场景运行 8 策略、10 种子。
- 对 96 条提示运行解析、解释、矛盾和注入测试。
- 使用配对指标验证策略方向；策略差异不能以破坏硬约束换取。

### Wave 3：动态扰动

- 先保存 baseline assignment snapshot，将实际 SHA-256 写入同一 scenario 的全部非 AI run envelope，并核对 D1-D4 父哈希完全一致。
- 注入 T-7d、T-24h、T-2h、T-30m 请假/生病、封场、延期和交通事件。
- 比较前后安排、候补消耗、恢复时间和新增违规。
- 对 64 个高风险场景运行 30 种子统计层。

### Wave 4：前端与人工情景剧

代表性抽样至少包含 S 2 套、M 4 套、L 6 套和 12 个动态事件。由学校运营 Agent 按“教务主任接到电话—登记请假—触发重排—人工审批—通知教师”的真实流程驱动 UI，核对页面/API/DB 三方一致性、错误信息、解释和审计日志。此阶段不能用仅 API 成功代替 UI 验收。

### Wave 5：独立审查

A10 随机抽取所有 P0 失败、所有预期无解、每策略至少 5 个成功、每 Tier 至少 10 个结果，复跑 Oracle 和关键指标。最终 ReviewRecord 必须绑定精确输入清单、结果哈希和独立复跑证据。

## 9. 证据与缺陷规则

每次运行的 evidence/index.json 必须列出（`pass` 与所有非通过终态要求相同）：

- 输入 JSONL/XLSX 的 SHA-256。
- 导入响应、调度任务响应、SSE/轮询终态、assignments、AI DSL/解释。
- 数据库只读真值查询摘要。
- H01-H12 独立断言结果。
- 公平、性能、稳定性和策略差异指标。
- 截图/导出文件哈希（适用于 UI 抽样）。
- 应用日志索引，不在报告中复制凭据或敏感字段。
- `capability_gaps` 与 `blocking_capability_gaps` 均为必填数组；前者记录非阻断结构差异，后者记录使门失败/不可判定的能力缺口，无缺口都写 `[]`。
- SSE/轮询终态或超时/错误终态捕获，以及对应哈希。

`fail/timeout/unknown/blocked/incomplete` 必须保留上述应用日志、终态捕获和 Oracle 输出；缺任一项时终态只能继续标记 `incomplete`，不得排除出统计分母、重命名为跳过或当作零失败。

缺陷严重级别：

| 级别 | 定义 | 发布动作 |
|---|---|---|
| P0 | 漏监考、双派、不可用人员上岗、跨租户、伪造成功、静默放宽硬规则 | 立即停止，阻断 |
| P1 | 预期无解误判、策略语义错误、解释与执行不一致、证据缺失、L 成功率不足 | 阻断 |
| P2 | 公平/性能/稳定性未达标但无硬违规 | 修复或经书面风险接受 |
| P3 | 文案、视觉或低风险易用性问题 | 可进入排期 |

## 10. 分阶段退出标准

| 阶段 | 退出条件 |
|---|---|
| 数据准备 | 静态验证全绿、同种子哈希一致、工作簿可打开且表头兼容 |
| 冒烟 | S/M/L 基础导入和一条可解、一条无解均符合 Oracle |
| 发布资格 | 11,520 次策略层完成，P0=0，P1=0 |
| 扰动资格 | 15,360 次深测完成，保留率达标；恢复 p95 使用 release_matrix：S≤15 秒、M≤45 秒、L≤120 秒，XL=`profile_only` 且必须报告实测值 |
| UI/UAT | 页面/API/DB 三方一致，关键流程证据齐全 |
| 最终验收 | Gate 0-3 通过，独立 Reviewer=APPROVE |

## 11. 实施任务

### Task 1: 冻结规格、Agent 契约和验收口径

**Files:**

- Create: docs/superpowers/specs/2026-08-30-real-school-proctoring-acceptance-design.md
- Create: docs/superpowers/plans/2026-08-30-real-school-proctoring-acceptance.md
- Create: test-data/real-school-acceptance/AGENT-CHARTERS.md
- Create: test-data/real-school-acceptance/schemas/*.schema.json

**Interfaces:**

- Consumes: 现有 K12 计划、final_audit、UAT、求解 fixture 和官方/技术来源。
- Produces: 固定的 scenario、run-envelope、evidence、ReviewRecord 契约。

- [ ] **Step 1:** 核对所有 H01-H12 都有数据字段、Oracle 断言和证据来源。
- [ ] **Step 2:** A7 独立创建、审定并冻结 config/oracle-policy.json；证明生成器只在冻结后按哈希只读消费，且不会派生、创建或覆盖该文件。
- [ ] **Step 3:** 搜索文档中的未定项标记与“适当处理”等不可验收措辞，并改成数值或明确状态。
- [ ] **Step 4:** 由不同责任组复核硬/软分层、政策本地审批字段和隐私最小化规则。

### Task 2: 生成确定性大规模数据包

**Files:**

- Create: test-data/real-school-acceptance/tools/build_real_school_artifacts.mjs
- Create: test-data/real-school-acceptance/tools/verify_real_school_artifacts.mjs
- Create: test-data/real-school-acceptance/generated/**
- Create: test-data/real-school-acceptance/outputs/{thread}/**

**Interfaces:**

- Consumes: 固定种子 20260830、四个 Tier、24 个家庭、8 策略。
- Produces: 2,664 教师、890 考场、8,636 考试考场次、384 场景、96 提示、29,376 运行定义和 JKXT 三表投影。

- [ ] **Step 1:** 运行生成器并写出 manifest 与每个文件哈希。
- [ ] **Step 2:** 运行验证器，断言计数、唯一键、引用、时段、容量、PII 和 run_id。
- [ ] **Step 3:** 用相同种子重生成并比对规范化 JSONL 与工作簿内容摘要；同时记录每次 XLSX 容器文件哈希，不要求带打包元数据的容器字节完全一致。
- [ ] **Step 4:** 检查 Excel 公式错误并逐表渲染首屏，修复裁切/乱码。

### Task 3: 建立独立 Oracle 与指标计算

**Files:**

- Create: test-data/real-school-acceptance/oracle/constraint_oracle.*
- Create: test-data/real-school-acceptance/oracle/metrics.*
- Test: test-data/real-school-acceptance/oracle/tests/**

**Interfaces:**

- Consumes: 富数据 JSONL、assignment snapshot、政策矩阵。
- Produces: H01-H12、Gini、极差、p95、保留率、策略方向和冲突最小集。

- [ ] **Step 1:** 先写一条故意双派的失败 fixture，确认 Oracle 拦截。
- [ ] **Step 2:** 逐项实现 H01-H12，不能调用被测系统的自检结果。
- [ ] **Step 3:** 对现有 P12 和预期无解错配案例做回归。
- [ ] **Step 4:** 实现统计汇总并固定 bootstrap 种子。

### Task 4: 建立隔离真实测试环境

**Files:**

- Create: deploy/acceptance/docker-compose.acceptance.override.yml
- Create: .env.acceptance.example
- Create: scripts/acceptance/start-acceptance.ps1
- Create: scripts/acceptance/stop-acceptance.ps1

**Interfaces:**

- Consumes: docker-compose.private.yml 和独立端口 19180/19081/19432/19379。
- Produces: jkxt_acceptance Compose 项目、独立 DB/Redis/卷、健康证据和回滚快照。

- [ ] **Step 1:** 先验证端口未占用且目标卷名只属于 jkxt_acceptance。
- [ ] **Step 2:** 启动后检查 PostgreSQL、Redis、app、nginx 均 healthy。
- [ ] **Step 3:** 运行一套 S 导入/排班/导出冒烟。
- [ ] **Step 4:** 验证 stop 脚本默认不删除卷；清理卷必须另行显式批准。

### Task 5: 执行 29,376 次矩阵并做浏览器 UAT

**Files:**

- Create: test-data/real-school-acceptance/runs/**
- Create: test-data/real-school-acceptance/evidence/**
- Create: test-data/real-school-acceptance/reports/**

**Interfaces:**

- Consumes: 隔离环境、运行矩阵、Oracle。
- Produces: 原始结果、指标、证据索引、缺陷和最终 ReviewRecord。

- [ ] **Step 1:** 按 Wave 1-3 执行；D1-D4 从相同 baseline 做独立 copy-on-write 单扰动运行，绝不累计。
- [ ] **Step 2:** 每个非通过终态保存应用日志、终态捕获和 Oracle 输出，并始终填写 `capability_gaps` 与 `blocking_capability_gaps`。
- [ ] **Step 3:** 在 Wave 4 完成 12 套 UI 抽样和 12 个动态情景剧。
- [ ] **Step 4:** 统计 Agent 生成配对差异、95% CI 和容量曲线。
- [ ] **Step 5:** A10 复跑抽样并给出 APPROVE 或 REQUEST_CHANGES。

## 12. 时间与资源预算

| 日程 | 工作 | 预计 |
|---|---|---|
| D1 | 冻结政策/数据/Oracle，启动隔离环境 | 1 天 |
| D2 | S/M 冒烟、导入、硬约束 | 1 天 |
| D3-D4 | L/策略矩阵与 AI 提示 | 2 天 |
| D5-D6 | 扰动深测与统计 | 2 天 |
| D7 | UI 情景剧、UAT、独立审查 | 1 天 |
| 缺陷回归 | 取决于 P0/P1 数量 | 单独计时 |

按当前求解时长估算，29,376 次不能全部串行；执行 Agent 应使用 2-4 个隔离租户/队列并限制并发，避免 CallerRunsPolicy 把压力转移到请求线程。并发本身也要作为性能测试变量记录。

## 13. 最终验收签字页

最终报告必须明确填写，不允许留空：

| 项 | 值 |
|---|---|
| 数据版本 / 哈希 | 由 manifest 自动填入 |
| 应用版本 / 镜像摘要 | 由启动证据填入 |
| 运行总数 / 完成 / 失败 / 超时 | 由 run index 汇总 |
| P0 / P1 / P2 / P3 | 由缺陷表汇总 |
| H01-H12 | 每项 PASS/FAIL/MISSING |
| S/M/L/XL 普通与恢复 p95 | 数值、release_matrix 阈值与 95% CI；XL 标明 `profile_only`、画像非阻断并报告实测值 |
| S/M/L/XL 公平/保留率与 AI 指标 | 数值与 release_matrix 阈值 |
| 证据完整率 | 百分比 |
| 总分 | 0-100，仅硬门通过后计算 |
| Reviewer | 责任组、时间、ReviewRecord 哈希 |
| 最终结论 | APPROVE / REQUEST_CHANGES / INCOMPLETE |

只有 29,376 个计划运行完成其规定范围、所有阻断门通过、加权总分至少 90、证据完整且独立 Reviewer=APPROVE，才可声明“满足大部分真实学校监考场景需求”。在此之前线上状态保持 `NOT_RUN`，当前最多只能声明静态工件 `ARTIFACT_PASS`。XL 只用于描述容量边界，不能替代 S/M/L 的发布门。
