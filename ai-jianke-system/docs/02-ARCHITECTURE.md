# 02 — 系统架构 + 数据模型 + API 设计

## 1. 技术栈选型（含决策理由）

| 层 | 选型 | 理由（Karpathy K1：明确假设）|
|---|---|---|
| **前端框架** | Next.js 14 (App Router) + TS | SSR/CSR 双模，路由清晰，shadcn 生态成熟；中国 K-12 客户对 SEO 弱所以 CSR 也行 |
| **UI 库** | shadcn/ui + Tailwind + Recharts | 高质量组件 + 响应式 + 中国一手数据可视化 |
| **后端** | FastAPI (Python 3.11+) | 类型安全 + OpenAPI 自动 + AI 生态最强 + 与 openpyxl/pandas 无缝 |
| **DB** | PostgreSQL 16 | 关系型 + 强约束 + JSON 字段灵活 + 中国合规云（阿里云 RDS）有 |
| **Cache + Queue** | Redis 7 | 会话、AI 调用结果缓存、Celery broker |
| **异步任务** | Celery + Redis | 排考可能 30-60s，必须异步避免请求超时 |
| **AI 抽象** | LiteLLM | 一份代码切 Claude / GPT / Qwen / DeepSeek / 智谱 |
| **算法 fallback** | Google OR-Tools (CP-SAT) | AI 失败 / 校验不通过时的兜底约束求解 |
| **认证** | JWT (RS256) + bcrypt 12r | 业界标准，公私钥可旋转 |
| **Excel** | openpyxl（读写） + pandas（校验）| 久经考验 |
| **部署 - SaaS** | Docker Compose（试运营）→ K8s (Aliyun ACK) | 渐进式 |
| **部署 - 私有化** | Docker Compose 单机版 + 离线镜像包 | 学校 IT 简单可装 |
| **CDN / 静态** | 阿里云 OSS + CDN | 国内访问快 |
| **监控** | Sentry（错误） + Posthog（行为） + Prometheus + Grafana（指标）| 全栈可见性 |
| **CI/CD** | GitHub Actions → 镜像仓库 → 自动部署 | 标准 |

## 2. 系统拓扑

```
┌──────────────────────────────────────────────────────────┐
│  浏览器 (PC + 平板友好；不做 mobile 重点)                  │
└────────────┬─────────────────────────────────────────────┘
             │ HTTPS
┌────────────▼─────────────────────────────────────────────┐
│  CDN + Next.js (Vercel / 阿里云 EAS)                       │
│   - SSR for SEO 页（落地页）                                │
│   - CSR for 控制台                                          │
└────────────┬─────────────────────────────────────────────┘
             │ /api/* (REST + WebSocket for 进度推送)
┌────────────▼─────────────────────────────────────────────┐
│  FastAPI (Gunicorn + UvicornWorker, 4 worker)              │
│   - 路由层 / RBAC 中间件 / 请求校验 (Pydantic)               │
│   - 业务层（teachers, rooms, exams, schedules, ai）         │
│   - 数据层（SQLAlchemy + Alembic）                           │
└──┬─────────────┬───────────────┬─────────────────┬────────┘
   │             │               │                 │
   ▼             ▼               ▼                 ▼
┌─────┐    ┌──────────┐    ┌─────────┐    ┌──────────────┐
│ PG  │    │ Redis    │    │ Celery  │    │ LiteLLM      │
│ 16  │    │ Cache+Q  │    │ Worker  │    │ → Claude/... │
└─────┘    └──────────┘    └────┬────┘    └──────────────┘
                                │
                                ▼
                          ┌──────────┐
                          │ OR-Tools │
                          │ CSP 兜底 │
                          └──────────┘
```

## 3. 数据模型（关键表）

### 3.1 organization（学校 / 单位）
| 列 | 类型 | 备注 |
|---|---|---|
| id | UUID PK | |
| name | text | 学校名 |
| address | text | |
| logo_url | text | |
| created_at, updated_at | timestamptz | |

### 3.2 user（管理员账号）
| id | UUID PK |
| org_id | FK organization |
| username | text unique |
| password_hash | text bcrypt |
| role | enum('superadmin','admin') |
| email | text |
| created_at, last_login_at | timestamptz |

### 3.3 academic_term（学年学期）
| id | UUID PK |
| org_id | FK |
| year_start, year_end | int（例 2025, 2026）|
| term | enum('S1','S2') |
| is_override | bool（true 表手动覆盖）|
| active | bool |

### 3.4 teacher（教师）
| id | UUID PK |
| org_id | FK |
| code | text（教师编号 X1）|
| name | text |
| grade | text（任教年级）|
| subject | text |
| is_homeroom | bool |
| is_main_proctor | bool |
| gender | enum('M','F','U') |
| age | int |
| extras | jsonb（备注、跨年级、延迟课等动态属性）|

### 3.5 exam_room（考场）
| id | UUID PK |
| org_id | FK |
| code | text（考场号 R101）|
| building | text |
| floor | int |
| capacity | int |
| is_corridor | bool |
| extras | jsonb |

### 3.6 exam（考试场次）
| id | UUID PK |
| org_id | FK |
| term_id | FK academic_term |
| subject | text |
| grade | text |
| exam_date | date |
| start_time | time |
| end_time | time |
| room_ids | uuid[] |
| extras | jsonb |

### 3.7 schedule_run（排考一次运行）
| id | UUID PK |
| org_id | FK |
| term_id | FK |
| mode | enum('quick','strategy_ai','manual') |
| strategies | jsonb（勾选的预设 + 自然语言）|
| status | enum('queued','running','done','failed') |
| version | int（同一学期多版本）|
| created_by | FK user |
| ai_summary | text |
| ai_recommendations | text |
| started_at, finished_at | timestamptz |

### 3.8 schedule_assignment（具体安排，每一条）
| id | UUID PK |
| run_id | FK schedule_run |
| exam_id | FK exam |
| room_id | FK exam_room |
| teacher_id | FK teacher |
| role | enum('main','assist') |

> 历史统计 / 监考次数统计基于 schedule_assignment + schedule_run 聚合查询。

## 4. RESTful API（OpenAPI 自动生成）

### 4.1 认证
```
POST   /api/auth/login              { username, password } → { token, role, org }
POST   /api/auth/logout
GET    /api/auth/me                 → { user, org, term }
POST   /api/auth/change-password    { old, new }
```

### 4.2 超管 only
```
POST   /api/admin/users             创建普管
DELETE /api/admin/users/:id
PUT    /api/admin/users/:id/role
GET    /api/admin/orgs              列出所有学校
POST   /api/admin/orgs
```

### 4.3 教师 CRUD
```
GET    /api/teachers?grade=&subject=
POST   /api/teachers
PUT    /api/teachers/:id
DELETE /api/teachers/:id
POST   /api/teachers/import         multipart Excel → 解析+校验
GET    /api/teachers/export.xlsx
```

### 4.4 考场 / 考试同上模式
```
/api/rooms/*
/api/exams/*
```

### 4.5 排考核心
```
POST   /api/schedules/quick-run     { } → schedule_run（异步，返回 task_id）
POST   /api/schedules/strategy-ai   { strategies, nlText, teacherFileId } → task_id
GET    /api/schedules/runs/:id      → status + progress + result
GET    /api/schedules/runs/:id/download/total.xlsx
GET    /api/schedules/runs/:id/download/by-teacher.xlsx
GET    /api/schedules/runs/:id/download/by-room.xlsx
GET    /api/schedules/runs/:id/download/by-time.xlsx
PUT    /api/schedules/runs/:id/assignments    手工修改
GET    /api/schedules/runs/:id/ai-summary
```

### 4.6 统计
```
GET    /api/stats/overview           当前学期总体
GET    /api/stats/proctor-counts     每位教师次数
GET    /api/stats/history?from=&to=  历史趋势
```

### 4.7 信息设置
```
GET/PUT /api/org                    本单位信息
GET/PUT /api/term                   学年学期（含 override）
GET/PUT /api/ai-config              （超管）切换 AI 模型
```

### 4.8 WebSocket
```
WS /ws/schedule/:task_id            实时推送排考进度
```

## 5. 排考算法 Pipeline

```
Excel 三表 → 校验 → 标准化 → 上下文打包
    ↓
LLM Prompt（含 6 策略 + NL + 数据）→ AI 排考草案 (JSON)
    ↓
OR-Tools CP-SAT 校验（硬约束：教师不冲突时间 / 考场不冲突时间 / 容量）
    ↓
软约束评分（6 条策略各权重 → 总分）
    ↓
分数 < 阈值 → 反馈 AI 重试（最多 3 次）
    ↓
最终 schedule + AI 生成 summary + recommendations
```

详细 Prompt 见 [03-AI-WORKFLOW.md](03-AI-WORKFLOW.md)。

## 6. 项目目录结构（monorepo 单仓）

```
ai-jianke/
├─ apps/
│  ├─ web/                Next.js (TypeScript)
│  │  ├─ app/
│  │  ├─ components/      shadcn/ui 复用
│  │  ├─ lib/             API client、auth
│  │  └─ public/
│  └─ api/                FastAPI
│     ├─ app/
│     │  ├─ routers/      auth, teachers, schedules, ...
│     │  ├─ models/       SQLAlchemy
│     │  ├─ schemas/      Pydantic
│     │  ├─ services/     业务（含 LLM client / OR-Tools）
│     │  ├─ tasks/        Celery
│     │  └─ core/         settings, security, db
│     ├─ alembic/         迁移
│     ├─ tests/
│     └─ pyproject.toml
├─ packages/
│  └─ shared-types/       前后端共享类型（OpenAPI → TS 自动生成）
├─ infra/
│  ├─ docker-compose.yml          私有化部署
│  ├─ docker-compose.dev.yml      开发用
│  ├─ k8s/                        SaaS K8s manifests
│  └─ scripts/
├─ docs/                  （本目录）
└─ templates/             三张 .xlsx 模板
```

## 7. 安全与合规要点

- **密码**：bcrypt cost=12；首次登录强制改 superadmin 密码
- **JWT**：RS256 + 公私钥；access 1h + refresh 7d
- **CSRF**：所有变更端点要求 X-CSRF-Token header
- **SQL**：100% 参数化（SQLAlchemy ORM）
- **文件上传**：限制 .xlsx，magic byte 校验，10 MB 上限，沙盒解析（独立进程超时 30s）
- **AI 内容审计**：所有 LLM 调用入参 + 出参写 audit log，便于回溯
- **数据本地化**：私有化客户数据完全在客户机房；SaaS 客户数据在阿里云大陆区
- **删除合规**：教师离职等 30 天软删除后硬删除
