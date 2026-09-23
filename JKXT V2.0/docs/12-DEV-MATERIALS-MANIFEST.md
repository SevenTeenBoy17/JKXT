# 12 · Claude Code 开发所需材料清单(精确路径 + 使用方法)

> **用途**:在 Claude Code 中开发本系统时,需要哪些材料、各在哪、怎么用。配套 `11-CLAUDE-CODE-EXECUTION-PLAYBOOK.md`(执行手册)。
> **路径约定**:**在 `D:\VB\JKXT\` 打开 Claude Code**(父目录),即可同时看到前端 `JKXT V2.0\` 与后端 `ai-jianke-system\`。下表给**绝对路径**(权威);提示词里可用相对 `JKXT V2.0\docs\08-...` 引用。
> **材料分 5 类**:① 核心契约(必读)② 配套规格(背景)③ 实现夹具(Excel 模板)④ 前端源码与 UI 参考 ⑤ 外部凭据(你提供,不在仓库)。

---

## §1 ① 核心契约材料(每个 Sprint 必引用 · 按路径让 CC 读对应章节)

| 材料 | 绝对路径 | 用途 | 使用方法 |
|---|---|---|---|
| **后端主规格** | `D:\VB\JKXT\JKXT V2.0\docs\08-BACKEND-DEV-SPEC.md` | 数据表 DDL / API 契约 / 排考引擎 / Excel / 安全 / 部署的**权威来源** | 提示词必带「读 08 §x」;按 Sprint 只读相关章节,勿整篇塞上下文 |
| **任务清单** | `D:\VB\JKXT\JKXT V2.0\docs\10-IMPLEMENTATION-TASK-PLAN.md` | 79 个可勾选任务 + DoD + 决策基线(§1) | 每 Sprint 读对应 S 段;完成即勾 `- [ ]`(单一进度源) |
| **执行手册(提示词库)** | `D:\VB\JKXT\JKXT V2.0\docs\11-CLAUDE-CODE-EXECUTION-PLAYBOOK.md` | Bootstrap、五步闭环、S1–S6 与难点专用**可粘贴提示词** | 你照此**粘提示词**驱动 CC;本文是"操作说明书" |
| **路线复核** | `D:\VB\JKXT\JKXT V2.0\docs\09-SECOND-ROUND-BACKEND-DOC-REVIEW-ANNOTATED.md` | 着色决策(红/橙/蓝/绿)、代码级前端审计 §9、冲突裁决 | 遇决策分歧/前端改造边界时查;勿翻案已定项 |
| **前端架构说明** | `D:\VB\JKXT\JKXT V2.0\CLAUDE.md` | 前端无构建(CDN+Babel+window.* 全局)的架构、约定 | 做前端接管(api.js/各页)前必读;CC 进前端会话自动加载 |

> 第一轮参考(已降级):`...\docs\BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md`(Node/NestJS 路线,**仅作架构/页面映射参考,冲突以 08 为准**)。Word 版 `JKXT-V2.0-*.docx` 仅供分享,开发不读。

---

## §2 ② 配套权威规格(深挖具体模块时引用)

路径前缀:`D:\VB\JKXT\ai-jianke-system\docs\`

| 材料 | 文件 | 何时用 |
|---|---|---|
| 产品需求 | `01-PRD.md` | 理解 10 模块/角色/非功能需求 |
| 原架构 | `02-ARCHITECTURE.md` | 数据模型/多租户来源(**注:原定 Python,已被 08 覆盖为 Java**) |
| **AI 工作流** | `03-AI-WORKFLOW.md` | **S4 必读**:LLM 三段式调用、NL→DSL、权重、回退链 |
| **Excel 模板规格** | `04-EXCEL-TEMPLATES.md` | **S2 必读**:3 张表的列定义/校验分级 |
| 实施计划/商业化 | `05-IMPLEMENTATION-PLAN.md` / `06-COMMERCIALIZATION-AND-MAINTENANCE.md` | 排期/交付/商业化背景 |
| UI 开发规格 | `07-UI-DEVELOPMENT-SPEC.md` | 前端视觉/组件规格(1266 行,P 级数据契约) |
| **原始 PDF(最高权威)** | `D:\VB\JKXT\AI监考系统开发.pdf` | 仅 2 页,**开工读一次**作 ground truth |

---

## §3 ③ 实现夹具:Excel 模板(真实文件,直接用)

| 模板 | 绝对路径 | 用途 |
|---|---|---|
| ① 教师安排 | `D:\VB\JKXT\ai-jianke-system\templates\01-教师安排示例表格.xlsx` | S2 导入实现的**输入夹具** + POI 解析单测 + `/import/templates/t01` 下载源 |
| ② 考场信息 | `D:\VB\JKXT\ai-jianke-system\templates\02-考场信息示例表格.xlsx` | 同上(t02) |
| ③ 考试安排 | `D:\VB\JKXT\ai-jianke-system\templates\03-考试安排示例表格.xlsx` | 同上(t03) |

**使用方法**:S2 实现 Excel 导入时——(a) 把这 3 个 `.xlsx` 复制到后端 `src/test/resources/templates/` 作 POI 解析与校验的**表驱动测试夹具**;(b) 复制到后端 `templates/` 目录作为 `/import/templates/{t01,t02,t03}` 下载端点的源文件;(c) 对照 `04-EXCEL-TEMPLATES.md` 校验列定义一致。
> 根目录另有一份 `D:\VB\JKXT\教师安排示例表格.xlsx`(散落副本),以 `ai-jianke-system\templates\` 下的为准。

---

## §4 ④ 前端源码 + UI 参考(前端接管阶段用)

| 材料 | 路径 | 用途 |
|---|---|---|
| 前端入口 | `D:\VB\JKXT\JKXT V2.0\Dashboard.html` | 脚本加载顺序;加 `src/api.js` 的位置 |
| 前端源码 | `D:\VB\JKXT\JKXT V2.0\src\`(`*.jsx` + `pages\*.jsx`) | 接管改造目标;**用 code-review-graph `get_minimal_context` 按需读,勿整目录塞上下文** |
| 登录页参考 | `D:\VB\JKXT\JKXT V2.0\uploads\01登录页截图.png` / `01登录页.mp4` | 登录联调视觉比对 |
| 首页参考 | `D:\VB\JKXT\JKXT V2.0\uploads\02首页1.png` | 仪表盘联调比对 |
| 监考人员参考 | `D:\VB\JKXT\JKXT V2.0\uploads\06监考人员管理下方界面.png` | 人员/timeline 联调比对 |
| 后台/超管参考 | `D:\VB\JKXT\JKXT V2.0\uploads\12后台可视化界面.png` / `超管UI界面参考.png` | 统计/超管联调比对 |

**使用方法**:前端只接线不改视觉;联调每页后**截图对照** uploads 参考图,确认数据来自真实后端且视觉未变。

---

## §5 ⑤ 外部凭据 / 配置(⚠️ 你必须提供,**不在仓库、永不提交**)

| 材料 | 来源 | 用途 | 怎么注入 |
|---|---|---|---|
| **LLM API**(已有) | 你持有的 provider | S4 AI 真赋能的**前提** | `JKXT_LLM_PROVIDER`、`JKXT_LLM_BASE_URL`、`JKXT_LLM_FALLBACK_BASE_URL`、`JKXT_LLM_API_KEY`、`JKXT_LLM_MODEL`、`JKXT_LLM_MAX_RETRIES`；只通过 Secret Manager 或未跟踪的 `.env.private` 注入，**不写受版本控制的 YAML、不入 git** |
| JWT 签名密钥 | 开发时生成(RS256 公私钥) | 认证 | `openssl` 生成,放密钥文件/KMS;`.gitignore` |
| DB / Redis 凭据 | docker-compose 提供(本地)/ 云 RDS(生产) | 连接 | `application-*.yml` 用环境变量占位 |
| 邮件 / 短信网关(可选) | 选型后提供 | 通知/2FA | 可插拔配置;私有化可 noop |

> **关键**:你说"LLM API 已有"——开发到 S4 时，把 **provider / 主备 base URL / key / model / retry budget** 作为服务端环境变量注入。真实密钥只能进入 Secret Manager 或已由 `.gitignore` 排除的 `.env.private`；不要把它写进 `application-private.yml`、示例文件、前端代码、日志或测试产物。

---

## §6 运行时 / 工具前置(开工前装好)

| 工具 | 用途 | 校验 |
|---|---|---|
| **JDK 21** | 编译运行 Spring Boot | `java -version` = 21 |
| **Maven 3.9+** | 构建 | `mvn -v` |
| **Docker Desktop** | 起 Postgres16 / Redis7 / app(compose) | `docker ps` |
| **OR-Tools(Maven 依赖)** | CP-SAT 排考(含本地原生库,Maven 自动拉) | S2 spike 验证可加载 |
| Node 18+(后期) | 前端从 CDN 迁 Vite 构建(生产,非必需于联调) | 可延后 |
| **Claude Code + code-review-graph MCP** | 开发主体 + 大代码库省 token | MCP 已装(见记忆);进会话 `list_graph_stats` |
| 已装 skills | `superpowers`(TDD/调试/计划/验证/子代理/worktree)、`ce-*`、`/run` `/verify` `/code-review` | 提示词里直接调用 |

---

## §7 开发中将产出的材料(CC 创建,非现有)

| 产出 | 位置 | 何时 |
|---|---|---|
| 后端工程 | `D:\VB\JKXT\ai-jianke-system\backend\`(greenfield 新建) | Session 0 Bootstrap |
| 后端 `CLAUDE.md` | `...\backend\CLAUDE.md`(模板见 11 §1.2) | Session 0 |
| Flyway 迁移 | `...\backend\src\main\resources\db\migration\` | 每 Sprint 增量 |
| OpenAPI | `/swagger-ui` + `/v3/api-docs`(运行时) | 持续 |
| 前端 `src/api.js` | `D:\VB\JKXT\JKXT V2.0\src\api.js` | S1-FE-01 |

---

## §8 每个 Sprint 该加载哪些材料(速查)

| Sprint | 必引用文档(by path) | 夹具/凭据 |
|---|---|---|
| **S0 脚手架** | 08 §0–§3/§11、10 §3-S0、11 §1 | — |
| **S1 认证/租户** | 08 §2/§3.2–3.4/§4、10 §3-S1 | JWT 密钥(生成) |
| **S2 主数据+导入** | 08 §3.5–3.8/§6、**04-EXCEL-TEMPLATES.md**、10 §3-S2、11 §5.1 | **3 个 .xlsx 模板** |
| **S3 排考闭环** | 08 §7/§3.9–3.12、11 §5.2 | — |
| **S4 AI+真拖拽** | 08 §7.4/§7.7/§7.8、**03-AI-WORKFLOW.md**、11 §5.3/§5.4 | **⚠️ LLM API 凭据** |
| **S5 统计/通知** | 08 §8、10 §3-S5 | 短信网关(可选) |
| **S6 超管/交付** | 08 §10/§11、10 §3-S6 | 云/信创/短信选型 |
| **前端接管(横切)** | 09 §9、08 §12、前端 `CLAUDE.md`、11 §5.5、uploads 参考图 | — |

---

## §9 使用方法 7 条要点

1. **在 `D:\VB\JKXT\` 开 Claude Code**,一处看全前后端 + docs + templates。
2. **大文档/源码按路径让 CC 自取**,只读相关章节;别把 08/07/src 整篇塞进上下文(用 code-review-graph 最小上下文)。
3. **PDF 与 04/03 开工各读一次**作 ground truth,之后以 08 为准。
4. **3 个 .xlsx 是真实夹具**——直接进后端测试资源与下载端点,不要重新造。
5. **LLM API 凭据 S4 前备好**,以环境变量注入,**永不提交**;它是"真 AI 赋能"的唯一外部依赖。
6. **前端只接线不改视觉**,每页联调后对照 uploads 参考图截图验收。
7. **进度唯一源是 10 的勾选框**;决策唯一源是 10 §1 / 09,勿翻案。

---

*v1.0 · 开发材料清单 · 所有路径已核验存在 · 配套 11 执行手册*
