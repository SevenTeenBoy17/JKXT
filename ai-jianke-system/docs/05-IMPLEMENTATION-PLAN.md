# 05 — 分阶段实施计划（Claude-Ready 任务拆解）

> 每个 Phase = 一次或多次 Claude 会话能完成。Phase 内任务粒度 = 一次 `Skill / Agent` 调用能跑完。**Supervisor v2 在每个 Phase 都会跑 Karpathy + code-review-graph gate**，必须配合。

## 总进度概览

| Phase | 名称 | 周数 | 团队规模 | 验收 |
|---|---|---|---|---|
| P0 | 项目初始化 + 设计 token | 1 周 | 1 全栈 | 仓库可跑 + 设计稿过审 |
| P1 | 认证 + 数据模型 + 基础 CRUD | 2 周 | 1 全栈 | 三张表能增删改查 + RBAC |
| P2 | Excel 三表导入 + 校验 | 1 周 | 1 后端 | 三表能上传 + 失败友好提示 |
| P3 | 一键排监考（CSP 兜底版）| 2 周 | 1 后端 + 1 算法 | OR-Tools 跑通无 AI |
| P4 | AI 策略集成 + 自然语言 | 2 周 | 1 AI + 1 后端 | 6 预设 + NL 能调通 |
| P5 | 修改安排 + 版本管理 | 1 周 | 1 全栈 | 拖拽编辑 + 历史回滚 |
| P6 | 统计图表 + 历史 | 1 周 | 1 前端 | 4 类图表完整 |
| P7 | 商业化（账号 / 计费 / 多租户）| 2 周 | 1 全栈 | SaaS 试运行 |
| P8 | 私有化打包 + 离线 | 1 周 | 1 DevOps | docker-compose up 即用 |
| P9 | 灰度上线 + 监控 + 文档 | 1 周 | 全员 | 首批 3 校试用 |

**总计：14 周 ≈ 3.5 个月**（小团队 2-3 人）。

---

## P0 — 项目初始化（Week 1）

### 任务列表（粒度=Claude 一次能完成）

| # | 任务 | 推荐 Skill | 验收 |
|---|---|---|---|
| P0.1 | 创建 monorepo 骨架（apps/web, apps/api, infra）| `ce-setup` | git clone 后 `pnpm install` + `pip install` 不报错 |
| P0.2 | 配置 ESLint + Prettier + Biome + Ruff + mypy | 直接写 | CI 跑过 |
| P0.3 | 设计设计 tokens（color/spacing/typography）| `ckm-design-system` | tokens 写入 `apps/web/styles/tokens.css` |
| P0.4 | shadcn/ui 初始化 + 基础组件（Button/Input/Table/Card/Dialog）| `ckm-ui-styling` | 6 组件可用 |
| P0.5 | Storybook 配置（可选）| 直接 | npm run storybook 跑通 |
| P0.6 | docker-compose.dev.yml（pg + redis）| `engineering:architecture` | 一行命令本地起栈 |

**Karpathy 重点 K3**：不要顺手装 100 个包，按 02-ARCHITECTURE.md 严格选型。

---

## P1 — 认证 + 数据模型 + CRUD（Week 2-3）

| # | 任务 | 推荐 Skill |
|---|---|---|
| P1.1 | Alembic 初始化 + 7 张表迁移（org/user/term/teacher/room/exam/schedule_*）| 直接 |
| P1.2 | 超管登录 + JWT + 中间件 | `ce-frontend-design` + `engineering:system-design` |
| P1.3 | superadmin 默认账号自动种 + 首登强制改密 | 直接 |
| P1.4 | 教师 CRUD（API + UI 列表 + 编辑 dialog）| `taste-skill` + 直接 |
| P1.5 | 考场 CRUD（同上模式）| 直接 |
| P1.6 | 班级科目管理（CRUD）| 直接 |
| P1.7 | 学年学期自动算法（utils + 单测）| 直接 |
| P1.8 | RBAC 中间件（@require_role）| 直接 |

**Gate**：每个 CRUD 模块完成后 → 跑 code-review-graph `get_impact_radius_tool` 确认无副作用。

---

## P2 — Excel 三表导入（Week 4）

| # | 任务 | 推荐 |
|---|---|---|
| P2.1 | 三张 .xlsx 模板生成脚本 | 直接 |
| P2.2 | `/api/import/teachers` 路由 + openpyxl 解析 | 直接 |
| P2.3 | Pydantic 校验器（每列规则）| 直接 |
| P2.4 | 跨表一致性检查（rooms in exams 等）| 直接 |
| P2.5 | 前端上传卡片 + 进度 + 错误高亮 | `ckm-ui-styling` |
| P2.6 | 端到端测试（pytest + Playwright）| `superpowers:test-driven-development` |

---

## P3 — 排监考 CSP 兜底（Week 5-6）

| # | 任务 | 推荐 |
|---|---|---|
| P3.1 | OR-Tools CP-SAT 模型定义（变量/硬约束）| `engineering:architecture` |
| P3.2 | 软约束实现（6 条）+ 权重 | 直接 |
| P3.3 | Celery 任务 + Redis 队列 | 直接 |
| P3.4 | WebSocket 进度推送 | 直接 |
| P3.5 | 结果落 schedule_run + assignments | 直接 |
| P3.6 | 4 张下载 .xlsx 生成（总/教师/考场/时段）| 直接 |
| P3.7 | UI 一键排监考页（拖三个文件 + 跑 + 看结果）| `taste-skill` + `interface-design` |

**关键**：先纯 CSP 跑通，**不要**先做 AI。AI 是 P4。

---

## P4 — AI 策略集成（Week 7-8）

| # | 任务 | 推荐 |
|---|---|---|
| P4.1 | LiteLLM 配置 + 4 模型注册（Claude/GPT/Qwen/DeepSeek）| `claude-api` |
| P4.2 | NL → DSL prompt 实现（含 schema 校验）| 直接 |
| P4.3 | 排考主 prompt 实现（带 prompt caching）| `claude-api` |
| P4.4 | LLM 输出 → OR-Tools 校验 pipeline | 直接 |
| P4.5 | 3 次重试 + CSP fallback | 直接 |
| P4.6 | AI 总结 + 建议生成 | 直接 |
| P4.7 | 个性化策略 AI 页 UI（6 复选 + NL 输入）| `ckm-ui-styling` |
| P4.8 | A/B 测试框架（v1 vs v2 prompt 公平度比较）| `ce-optimize` |

**Karpathy K4 强调**：每条策略 1 个端到端测试用例，公平度指标固化。

---

## P5 — 修改安排 + 版本（Week 9）

- 拖拽编辑（react-dnd）
- 实时约束校验（前端镜像 OR-Tools 子集）
- 版本快照 + Diff 视图
- 一键回滚

---

## P6 — 统计图表（Week 10）

- 任务总览（Recharts BarChart）
- 监考次数统计（BarChart + 标准差线）
- 历史统计（PieChart 公平度分布 + LineChart 学期趋势）
- 导出 PNG / PDF（html2canvas + jspdf）

---

## P7 — 商业化（Week 11-12）

| # | 任务 | 推荐 |
|---|---|---|
| P7.1 | 多租户 row-level security（PG RLS）| `engineering:architecture` |
| P7.2 | 套餐模型（Starter / Pro / Enterprise）+ 计费 | 直接 |
| P7.3 | 微信支付 / 支付宝集成 | `engineering:system-design` |
| P7.4 | 超管控制台（学校列表 / 套餐 / 用量）| `interface-design` |
| P7.5 | 邮件通知（试用到期 / 排考完成）| 直接 |

---

## P8 — 私有化打包（Week 13）

- 全栈 Docker Compose 单机版
- 离线镜像 tar（含 PG / Redis / 后端 / 前端 / 模型代理）
- 一键安装脚本 `install.sh` / `install.bat`
- 离线 LLM 选项（Ollama + Qwen2.5 本地）

---

## P9 — 灰度 + 监控（Week 14）

- Sentry / Posthog / Grafana 接入
- 灰度白名单（3 试点学校）
- 用户手册 + FAQ 站点
- 培训视频 5-10 个 < 3min

---

## 测试矩阵（贯穿全周期）

| 类型 | 工具 | 覆盖率目标 |
|---|---|---|
| 单元 | pytest + jest | ≥ 80% 核心模块 |
| 集成 | pytest-asyncio | 所有 API |
| 端到端 | Playwright | 6 关键路径（登录/三表上传/排考/修改/下载/统计）|
| 性能 | locust | 50 并发管理员 + 5 并发排考 < 60s |
| AI 公平度 | 自定义脚本 | 6 策略各 5 个用例，stdev 阈值 |
| 安全 | OWASP ZAP + Bandit | 0 高危 |
| 可访问 | axe-core | WCAG 2.1 AA |

---

## 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| AI 排考不稳定 | 高 | CSP 强兜底；多模型可切；公平度自动评分 |
| Excel 格式万千变化 | 中 | 模板版本号 + 强校验 + 友好错误 |
| 学校 IT 弱 | 中 | 一键 docker-compose；OEM 经销商上门 |
| 教师隐私（年龄/性别）| 高 | 字段可隐藏；本地化数据；删除合规 |
| LLM 成本失控 | 中 | prompt cache；haiku 默认；用量限额 |
| 学期/学年表达地区差异 | 低 | 信息设置允许覆盖；预留 i18n |
