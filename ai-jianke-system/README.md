# AI 监考排考系统 — 全流程开发文档

> 给 Claude / 团队驱动后续开发用的"单一真源"文档集。每个文档独立，但顺序阅读能完整覆盖从需求到上线的全周期。

---

## 📋 文档索引

| # | 文档 | 用途 | 谁该读 |
|---|---|---|---|
| 00 | **README.md** | 总览 + 技术栈 + 商业化摘要（本文件）| 所有人 |
| 01 | [01-PRD.md](docs/01-PRD.md) | 产品需求 + UI 详尽规格 | PM / 前端 / QA |
| 02 | [02-ARCHITECTURE.md](docs/02-ARCHITECTURE.md) | 技术栈 + 数据模型 + API 设计 | 全栈 / DevOps |
| 03 | [03-AI-WORKFLOW.md](docs/03-AI-WORKFLOW.md) | 6 条 AI 策略 + Prompt 工程 + 算法 | AI / 算法工程师 |
| 04 | [04-EXCEL-TEMPLATES.md](docs/04-EXCEL-TEMPLATES.md) | 3 张导入模板的列定义与校验规则 | 后端 / 数据 |
| 05 | [05-IMPLEMENTATION-PLAN.md](docs/05-IMPLEMENTATION-PLAN.md) | 分阶段 Claude-ready 任务拆解 | 全员（驱动 AI 开发）|
| 06 | [06-COMMERCIALIZATION-AND-MAINTENANCE.md](docs/06-COMMERCIALIZATION-AND-MAINTENANCE.md) | 商业模式 + 长期维护策略 | 业务 / 运维 |

## 📂 可下载模板

- `templates/01-教师安排示例表格.xlsx` — 教师信息（含原始样本）
- `templates/02-考场信息示例表格.xlsx` — 考场清单（新增）
- `templates/03-考试安排示例表格.xlsx` — 考试日程（新增）

---

## 🎯 项目一句话定位

> **面向中国 K-12 学校教务的 AI 排监考 SaaS** — 三表 Excel 一键导入 → AI 大模型按 6 条可勾选策略 + 自然语言补充策略 → 输出公平合理的监考安排（总表 + 3 分表，含 AI 总结建议），全 Web，免安装，支持私有化部署。

## 🧭 与对标产品的差异

| 维度 | 我们 | 国内（轻速云/优考试等）| 国际（Optime Exam）| 反作弊系（Proctorio）|
|---|---|---|---|---|
| 排监考调度 | ✅ **核心** | ⚠️ 弱 / 无 | ✅ 有 | ❌ 无 |
| AI 大模型策略 | ✅ 6 预设 + 自然语言 | ❌ 多为规则 | ⚠️ 偏算法 | ❌ |
| 中国 K-12 场景 | ✅ 班主任 / 楼道 / 学年学期自动 | ⚠️ 偏在线考试 | ❌ 西式 | ❌ |
| Excel 一键导入 | ✅ 三表标准化 | 部分 | ⚠️ | ❌ |
| 私有化 + SaaS 双模 | ✅ 计划支持 | 部分 | ⚠️ | ❌ |
| 在线考试 / 反作弊 | ❌ 不做（边界明确）| ✅ | ⚠️ | ✅ |

**蓝海定位**：国际只有 Optime Exam 直接对标，国内几乎空白 → **AI 排监考 SaaS** 是细分蓝海。

## 🛠️ 技术栈一句话总结

详见 [02-ARCHITECTURE.md](docs/02-ARCHITECTURE.md)。

- **前端**：Next.js 14（App Router） + TypeScript + shadcn/ui + Tailwind + Recharts（图表）
- **后端**：FastAPI（Python） + PostgreSQL + Redis + Celery（异步排考任务）
- **AI**：Anthropic Claude API（或国内通义/智谱兼容层）+ 自研约束满足算法（CSP / OR-Tools fallback）
- **部署**：Docker + Docker Compose（私有化）/ Vercel + Railway（SaaS 试运行）/ K8s（生产 SaaS）
- **认证**：JWT + bcrypt；超管/普管 RBAC
- **Excel**：openpyxl 解析；pandas 校验；前端 xlsx-populate 预览

## 🚀 商业化摘要（详见 06）

| 模式 | 价格指引 | 目标客户 |
|---|---|---|
| **SaaS Starter** | 免费（≤ 50 教师 / 6 次/学期）| 小校试用 |
| **SaaS Pro** | ¥980/学期/校 | 中型学校（51-300 教师）|
| **SaaS Enterprise** | ¥2,980/学期/校 + AI 强化包 | 大型学校 / 教育集团 |
| **私有化部署** | ¥30,000 一次性 + ¥6,000/年维护 | 教育局 / 集团多校 |
| **白标 OEM** | 定制报价 | 教育软件经销商 |

## 🛡️ 维护与升级策略摘要（详见 06）

- **半年大版本**：跟随中国学制变化（学年/学期、新课标）
- **月度小版本**：AI 策略迭代、Bug 修复
- **AI 模型独立可换**：Claude / GPT / Qwen / DeepSeek 通过 LiteLLM 抽象
- **数据库迁移**：Alembic 版本化；零停机滚动升级
- **可观测**：Sentry（错误）+ Posthog（行为）+ Grafana（指标）

## 🤖 给后续 Claude 开发会话的指引

每次让 Claude 实现 / 修改时：

1. **先让 Claude 读这个 README + 对应需求的具体 docs/**
2. **任务粒度参考 [05-IMPLEMENTATION-PLAN.md](docs/05-IMPLEMENTATION-PLAN.md)**——每个阶段一条命令
3. **Supervisor v2 已激活**，每次任务自动跑 Karpathy + code-review-graph gate
4. **3 张 Excel 模板**作为约定的输入契约，不要随意改列
5. **AI Prompt 模板**集中在 `03-AI-WORKFLOW.md`，需要改 prompt 改这里

---

**版本**：v1.0  
**生成日期**：2026-05-19  
**生成方**：Claude (Supervisor v2 监督) + 用户需求（AI监考系统开发.docx）
