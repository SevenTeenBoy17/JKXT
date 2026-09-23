# 08 · 后端开发规格 — 打通前端 UI 的全部后端服务

> **配套**:`01-PRD.md` / `02-ARCHITECTURE.md` / `03-AI-WORKFLOW.md` / `04-EXCEL-TEMPLATES.md` / `07-UI-DEVELOPMENT-SPEC.md`
> **前端基线**:`D:\VB\JKXT\JKXT V2.0\`(纯前端原型,React UMD + Babel,数据全 mock,无后端)
> **本文目标**:把前端每一个页面/操作所隐含的后端能力,落成可直接开发的服务规格——数据模型、API 契约、排考引擎、Excel 管线、多租户、认证安全、异步实时、部署与联调。
> **作者视角**:资深后端架构 + 全栈。本文为实施基线,凡与既有文档冲突处以本文「决策摘要」为准并标注理由。

---

## §0 决策摘要与文档关系

### 0.1 本轮已敲定的 4 个技术分叉(用户决策)

| # | 决策项 | 选定 | 关键影响 |
|---|---|---|---|
| D1 | 后端主技术栈 | **Java 21 · Spring Boot 3.3** | 全文代码示例、依赖、目录结构以 Spring 生态为准 |
| D2 | 部署形态 | **混合交付(云端 SaaS 多租户 + 私有化单校)** | 同一套代码,`deploy.mode=saas\|private` 切换;租户层从第一天抽象 |
| D3 | 数据库 | **PostgreSQL 16** | 行级多租户 + JSONB 灵活字段 + 强约束;预留国产库(达梦/人大金仓)方言适配 |
| D4 | 排考引擎 | **约束求解(OR-Tools CP-SAT)为主 + LLM 辅助** | 求解器是权威分配者;LLM 负责"自然语言→约束 DSL""单元格修复建议""结果总结" |

### 0.2 与 `02-ARCHITECTURE.md` 的差异(有意覆盖)

`02-ARCHITECTURE.md §1` 原定 **FastAPI(Python 3.11)**。本文按 D1 改为 **Java Spring Boot**。**保留不变**的与语言无关资产:PostgreSQL、Redis、JWT(RS256)+bcrypt、org_id 行级多租户、OR-Tools CP-SAT、LiteLLM 式的「多模型抽象」理念、3 张 Excel 模板契约、`03-AI-WORKFLOW.md` 的 LLM 三段式调用与校验管线、`§7` 全部安全/合规要求。**重映射**的运行时组件:

| 关注点 | 原(Python) | 本文(Java) |
|---|---|---|
| Web 框架 | FastAPI | Spring Boot 3.3 (Web MVC + Validation) |
| ORM | SQLAlchemy | Spring Data JPA / Hibernate 6 |
| Excel 读写 | openpyxl + pandas | **Apache POI 5**(+ 大文件用 EasyExcel/SAX 流式) |
| 异步任务 | Celery + Redis | **Spring `@Async` + DB 作业表 + Redis**(或 XXL-JOB;排考 30–60s 异步) |
| 实时进度 | WebSocket | Spring WebSocket + **STOMP** (`/ws/schedule/{runId}`) |
| 约束求解 | OR-Tools(py) | **OR-Tools `com.google.ortools` Java 绑定**(CP-SAT) |
| LLM 抽象 | LiteLLM | 自研 `LlmClient` OpenAI 兼容接口；当前批准配置为 `llm-token / claude-sonnet-4-6`，复用现有主备 LLM API |
| 鉴权 | python-jose | Spring Security 6 + Nimbus JOSE / jjwt |
| 校验 | pydantic | Jakarta Bean Validation (Hibernate Validator) |

### 0.3 逆向工程发现的 6 处契约冲突(必须在后端统一,详见 §13)

1. **角色枚举**:文档 `enum('superadmin','admin')` vs 前端 3 角色(+`viewer` 只读)+PRD 预留「教师查询」。→ 后端定 `SUPER_ADMIN / ADMIN / VIEWER / TEACHER(预留)`。
2. **登录入口**:PRD 要求「单一入口、按账号判角色」,前端却显式选 admin/super tab。→ `/auth/login` **忽略前端声明的角色**,以账号自身 role 为准。
3. **设置分层**:前端「系统设置」是**平台级广播全租户**,文档 `/api/org` 是**单租户级**。→ 明确两层:`/api/admin/platform-settings`(平台)vs `/api/org/settings`(租户)。
4. **租户标识**:文档用 `organization.id(UUID)`,前端用 `slug`(`{slug}.jkxt.cn`)。→ schema 同时含 `id UUID` + `slug` 唯一列。
5. **短信 2FA / 信创栈**:文档未选型,前端 UI 已假设存在。→ 本文给出可插拔短信网关接口 + 国产库方言路径。
6. **审计表**:前端审计含 `userAgent`,文档 schema 未建 `audit_log` 表。→ 本文新建通用 `audit_log`(§3.20)。

### 0.4 原型 vs 规格的「Excel/排考」张力

前端原型用**宽松 3 列模糊匹配 + 贪心 2 人/场**分配,仅为浏览器演示;`04-EXCEL-TEMPLATES.md` + `03-AI-WORKFLOW.md` 定义的**严格 8–10 列定序模板 + LLM/OR-Tools 求解**才是真实契约。**后端一律以严格契约实现**(§6、§7),但所有 API 响应结构必须能填充前端已渲染的字段(§5 DTO 即据前端实际消费推导)。

---

## §1 系统总体架构

### 1.1 分层架构

```
┌─────────────────────────────────────────────────────────────────┐
│  前端 (现有原型 → 接管为真实客户端)                                  │
│  React SPA · 调用 /api · JWT in Authorization header · STOMP 进度  │
└───────────────┬─────────────────────────────────────────────────┘
                │ HTTPS (REST + WebSocket/STOMP)
┌───────────────▼─────────────────────────────────────────────────┐
│  API 层  Spring Boot 3.3 (单体模块化 Modular Monolith)             │
│  ┌─────────┬──────────┬───────────┬──────────┬─────────────────┐ │
│  │ Filter  │ Security │ Tenant    │ Rate     │ Global Exception│ │
│  │ (CORS,  │ (JWT,    │ Resolver  │ Limiter  │ Handler (统一    │ │
│  │  CSRF)  │  RBAC)   │ (org_id)  │ (Bucket4j)│  错误信封)      │ │
│  └─────────┴──────────┴───────────┴──────────┴─────────────────┘ │
│  Controller → Service → Repository(JPA)                          │
│  特性包: auth · org · masterdata · importer · scheduling · ai ·   │
│         tasks · stats · platform(super) · notify · audit         │
└──┬──────────┬──────────┬───────────┬──────────┬──────────────────┘
   │          │          │           │          │
┌──▼──┐   ┌───▼───┐  ┌───▼────┐  ┌───▼────┐ ┌───▼─────────────┐
│ PG  │   │ Redis │  │OR-Tools│  │ LLM API │ │ Async Worker    │
│ 16  │   │  7    │  │CP-SAT  │  │(已有,   │ │ (@Async/队列 +  │
│     │   │(cache,│  │(进程内 │  │ 多模型) │ │  STOMP 进度推送) │
│     │   │ lock, │  │ 求解)  │  │         │ │                 │
│     │   │ queue)│  └────────┘  └─────────┘ └─────────────────┘
└─────┘   └───────┘
```

### 1.2 技术栈清单

| 层 | 选型 | 版本 | 用途 |
|---|---|---|---|
| 语言/运行时 | Java (LTS) | **21** | 虚拟线程(Loom)利于高并发 IO |
| 框架 | Spring Boot | **3.3.x** | Web / Validation / Security / Data JPA / WebSocket / Actuator |
| 数据库 | PostgreSQL | **16** | 主库;JSONB、窗口函数、物化视图、RLS |
| ORM | Spring Data JPA + Hibernate | 6.x | 实体映射、`@Filter` 租户隔离 |
| 迁移 | **Flyway** | 10.x | 版本化 DDL(`V1__init.sql` …) |
| 缓存/锁/队列 | Redis | **7** | 会话黑名单、AI 结果缓存、分布式锁、轻量作业队列 |
| 求解器 | Google OR-Tools | **9.x** (`com.google.ortools`) | CP-SAT 排考硬约束求解与修复 |
| LLM | Spring AI / 自研 `LlmClient` | — | 复用**现成 LLM API**;OpenAI 兼容适配多模型 |
| Excel | Apache POI | **5.x** | 读写 .xlsx;大文件 SAX 流式 |
| 鉴权 | Spring Security + Nimbus JOSE | 6.x | JWT RS256、bcrypt(cost 12) |
| 实时 | Spring WebSocket + STOMP | — | 排考进度、平台通知 |
| 限流 | Bucket4j | 8.x | 登录/AI 接口防爆破 |
| 文档 | springdoc-openapi | 2.x | 自动 OpenAPI 3 + Swagger UI |
| 任务调度 | Spring Scheduling / XXL-JOB(可选) | — | 统计物化刷新、自动备份、到期检查 |
| 可观测 | Actuator + Micrometer + Prometheus + Grafana + Sentry | — | 指标、追踪、错误 |
| 构建 | Maven(或 Gradle) | — | 多模块或单模块 package-by-feature |

### 1.3 模块划分(单体模块化,package-by-feature)

```
com.jkxt
├── common/         # 错误信封、分页、TenantContext、基类实体、工具
├── security/       # JWT、过滤器、RBAC、CSRF、密码、2FA、限流
├── tenant/         # 多租户解析、Hibernate Filter、部署模式
├── org/            # organization(租户)、academic_term(学期)、org_settings
├── masterdata/     # teacher / classroom / school_class / subject (+导入)
├── importer/       # Excel 解析、校验、import_batch、validation_issue
├── scheduling/     # exam_task / schedule_run / assignment / OR-Tools 引擎 / 编辑
├── ai/             # LlmClient、NL→DSL、main-solve、summary、ai_call_log
├── tasks/          # 任务总览/详情/发布/导出/操作记录
├── stats/          # 仪表盘、监考次数、历史、时间线、物化视图
├── platform/       # 超管控制面:tenants/accounts/settings/audit/security/announcements/dashboard
├── notify/         # 站内信、邮件、短信网关(可插拔)、WebSocket 推送
└── audit/          # 通用 audit_log、AOP 切面记录
```

> **为何单体模块化而非微服务**:单校 50 并发、SaaS 中等规模(NFR §11),单体足矣且私有化交付(Docker Compose 单机)要求最小运维面;按特性包隔离,后续可平滑拆服务。

### 1.4 混合交付架构(D2)

| 维度 | SaaS 模式 (`deploy.mode=saas`) | 私有化模式 (`deploy.mode=private`) |
|---|---|---|
| 租户数 | 多租户,`org_id` 从 JWT/子域解析 | 单租户,`org_id` 固定为安装时种子值 |
| 超管控制面 | 完整启用(平台运营) | 降级为「本地运维后台」(单租户运维) |
| 部署 | Docker → K8s(阿里云 ACK) | Docker Compose 单机 + 离线镜像包 |
| LLM | 云端 LLM API | 可配私有/本地模型端点(OpenAI 兼容) |
| 数据 | 阿里云大陆区 RDS | 客户机房,数据不出校 |
| 备份 | 平台统一备份 | 本地定时备份至客户存储 |

**实现要点**:同一构建产物,通过 Spring Profile(`application-saas.yml` / `application-private.yml`)切换;`TenantContext` 在 private 模式下由固定常量提供,业务代码**无感知**(§2)。

---

## §2 多租户设计(混合)

### 2.1 隔离策略:共享库 + 行级 `org_id`

所有租户业务表带 `org_id UUID NOT NULL REFERENCES organization(id)`。**不**采用每租户独立库/Schema(私有化时退化为单 org,零改动)。三重防线:

1. **应用层**:`TenantContext`(基于 `ThreadLocal`/`TransactionScope`)+ Hibernate `@Filter` 自动给每条查询追加 `org_id = :currentOrg`。
2. **数据层(可选,纵深防御)**:PostgreSQL **行级安全 RLS** —— SaaS 高安全场景对核心表启用 `CREATE POLICY ... USING (org_id = current_setting('app.current_org')::uuid)`。
3. **写入校验**:`@PrePersist` 自动注入 `org_id`;跨租户写入抛 `TenantViolationException`。

### 2.2 租户解析链

```
请求 → TenantResolverFilter:
  if deploy.mode == private:   orgId = FIXED_ORG_ID (种子)
  else (saas):
    1. 优先从 JWT claim `org` 取 orgId(登录后)
    2. 未登录接口(如 captcha)从子域 {slug}.jkxt.cn 反查 org.slug → orgId
    3. 超管(SUPER_ADMIN)跨租户:orgId 可为 null,显式传 X-Tenant-Id 覆盖
  → TenantContext.set(orgId);  finally TenantContext.clear()
```

### 2.3 关键代码骨架

```java
// common/TenantContext.java
public final class TenantContext {
  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
  public static void set(UUID orgId){ CURRENT.set(orgId); }
  public static UUID get(){ return CURRENT.get(); }
  public static void clear(){ CURRENT.remove(); }
}

// 基类实体:所有租户表继承
@MappedSuperclass
@FilterDef(name="tenantFilter", parameters=@ParamDef(name="orgId", type=UUID.class))
@Filter(name="tenantFilter", condition="org_id = :orgId")
public abstract class TenantEntity {
  @Id @GeneratedValue(strategy=GenerationType.UUID) UUID id;
  @Column(name="org_id", nullable=false, updatable=false) UUID orgId;
  @PrePersist void assignTenant(){ if(orgId==null) orgId = TenantContext.get(); }
  // createdAt / updatedAt / createdBy / version(@Version 乐观锁) ...
}

// 每个事务开启 filter
session.enableFilter("tenantFilter").setParameter("orgId", TenantContext.get());
```

### 2.4 设置的两层模型(解决冲突 #3)

- **平台级** `platform_setting`(全局单例,SUPER_ADMIN 改,广播全租户):系统名、域名、时区、语言、默认学段、全局开关(强制 2FA、维护模式、自动备份…)。
- **租户级** `org_setting`(每 org 一行,ADMIN 改):单位名称、地区地址、联系电话、学期覆盖、AI 模型偏好(若平台允许)。

---

## §3 数据模型(PostgreSQL 16)

> 约定:表名 `snake_case`;主键 `id UUID`;租户表含 `org_id`;时间 `timestamptz`;枚举用 `varchar + CHECK`(便于国产库迁移,见 §3.21);灵活字段用 `jsonb`;唯一性按租户作用域 `UNIQUE(org_id, …)`。审计列 `created_at/updated_at/created_by/version` 省略未列。

### 3.1 ER 概览

```
organization 1──* app_user
organization 1──* academic_term      organization 1──1 org_setting
organization 1──* teacher *──* subject (teacher_subject)
organization 1──* school_class *──1 teacher(班主任)   school_class *──* subject
organization 1──* classroom
organization 1──* exam *──* classroom (exam_room) ; exam *──* school_class (exam_class)
organization 1──* exam_task 1──* exam ; exam_task 1──* schedule_run
schedule_run 1──* schedule_assignment (teacher × exam × classroom × role)
exam_task 1──* import_batch 1──* validation_issue
exam_task 1──* ai_call_log ; exam_task 1──1 ai_policy
全局(平台): platform_setting · announcement · announcement_read · audit_log
            · security_event · ip_rule · backup_record · notification
```

### 3.2 organization(租户)

```sql
CREATE TABLE organization (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug        varchar(40)  NOT NULL UNIQUE,          -- {slug}.jkxt.cn, 创建后不可改
  name        varchar(100) NOT NULL,
  school_type varchar(20)  NOT NULL CHECK (school_type IN
              ('小学','初中','高中','完全中学','九年一贯制')),
  plan        varchar(20)  NOT NULL DEFAULT '试用版' CHECK (plan IN
              ('试用版','标准版','专业版','企业版')),
  status      varchar(20)  NOT NULL DEFAULT 'trial' CHECK (status IN
              ('active','trial','suspended')),
  city        varchar(60),
  address     varchar(200),
  contact_phone varchar(30),
  admin_cap   int  NOT NULL DEFAULT 4,               -- 管理员上限
  student_cap int  NOT NULL DEFAULT 2400,            -- 学生上限
  trial_expire_at timestamptz,                       -- 试用到期
  suspended_at    timestamptz,                       -- 暂停时间(+90天数据保留)
  created_at  timestamptz NOT NULL DEFAULT now()
);
```

### 3.3 app_user(账号)

```sql
CREATE TABLE app_user (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id        uuid REFERENCES organization(id),    -- 超管可为 NULL(跨租户)
  username      varchar(60)  NOT NULL,               -- 工号或登录名
  email         varchar(120) NOT NULL,
  phone         varchar(30),
  display_name  varchar(60)  NOT NULL,
  department    varchar(40),                         -- 教务处/教导处/平台(展示属性,非RBAC)
  role          varchar(20)  NOT NULL CHECK (role IN
                ('SUPER_ADMIN','ADMIN','VIEWER','TEACHER')),
  password_hash varchar(100) NOT NULL,               -- bcrypt cost 12
  must_change_pwd boolean    NOT NULL DEFAULT true,  -- 首登强制改密
  pwd_changed_at  timestamptz,
  mfa_enabled   boolean      NOT NULL DEFAULT false,
  mfa_method    varchar(10)  CHECK (mfa_method IN ('email','sms','totp')),
  recovery_email varchar(120),
  status        varchar(10)  NOT NULL DEFAULT 'offline' CHECK (status IN ('online','offline','disabled')),
  disabled_at   timestamptz,                         -- 禁用(30天可恢复)
  last_login_at timestamptz,
  last_login_ip varchar(45),
  login_count   int NOT NULL DEFAULT 0,
  failed_attempts int NOT NULL DEFAULT 0,            -- 连续失败,≥5 锁定
  locked_until  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (org_id, username),
  UNIQUE (org_id, email)
);
CREATE INDEX idx_user_org ON app_user(org_id);
```

### 3.4 academic_term(学年学期)

```sql
CREATE TABLE academic_term (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id     uuid NOT NULL REFERENCES organization(id),
  year_label varchar(20) NOT NULL,                   -- '2025-2026'
  term       varchar(4)  NOT NULL CHECK (term IN ('S1','S2')),  -- 上/下学期
  start_date date, end_date date,
  is_current boolean NOT NULL DEFAULT false,
  is_override boolean NOT NULL DEFAULT false,         -- PRD §10 学年覆盖
  UNIQUE (org_id, year_label, term)
);
```

### 3.5 teacher(监考人员)

```sql
CREATE TABLE teacher (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id     uuid NOT NULL REFERENCES organization(id),
  teacher_no varchar(20) NOT NULL,                   -- 工号 T240101/J240901
  name       varchar(40) NOT NULL,
  gender     varchar(2)  CHECK (gender IN ('男','女')),  -- 男女混合监考策略需要
  grade      varchar(20),                            -- 任教年级(枚举见 §3.22)
  is_homeroom boolean NOT NULL DEFAULT false,        -- 是否班主任
  homeroom_class_id uuid REFERENCES school_class(id),-- 班主任所带班(避监本班)
  age        int  CHECK (age BETWEEN 18 AND 75),     -- S5 楼道策略需要;空→S5对其失效
  seniority  int,                                    -- 教龄
  edu        varchar(10) CHECK (edu IN ('专科','本科','硕士','博士')),
  cross_grades varchar(40),                          -- 跨年级授课 "7,8" (S6)
  is_main_supervisor boolean NOT NULL DEFAULT false, -- 可任主监考
  phone      varchar(30),
  email      varchar(120),
  status     varchar(10) NOT NULL DEFAULT 'active' CHECK (status IN ('active','leave')),
  notes      varchar(500),                           -- 备注 → extras
  UNIQUE (org_id, teacher_no)
);
CREATE INDEX idx_teacher_org_grade ON teacher(org_id, grade);
```

### 3.6 subject(科目) / teacher_subject

```sql
CREATE TABLE subject (
  id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id    uuid NOT NULL REFERENCES organization(id),
  name      varchar(30) NOT NULL,                    -- 语文/数学/英语...
  category  varchar(10) CHECK (category IN ('主科','理科','文科','副科','术科')),
  grades    jsonb,                                   -- 适用年级数组 ["初一",...]
  weekly_hours int CHECK (weekly_hours BETWEEN 0 AND 40),
  exam_type varchar(20),                             -- 笔试/笔试+听力/机考/体测...
  color     varchar(12),                             -- UI 标签色(可派生)
  UNIQUE (org_id, name)
);
CREATE TABLE teacher_subject (                        -- 教师 *—* 科目(副科监主科策略)
  teacher_id uuid NOT NULL REFERENCES teacher(id) ON DELETE CASCADE,
  subject_id uuid NOT NULL REFERENCES subject(id) ON DELETE CASCADE,
  PRIMARY KEY (teacher_id, subject_id)
);
```

### 3.7 school_class(班级) / school_class_subject

```sql
CREATE TABLE school_class (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  code        varchar(20) NOT NULL,                  -- 高三(7)班
  grade       varchar(20) NOT NULL,                  -- 年级
  homeroom_teacher_id uuid REFERENCES teacher(id),   -- 班主任(1:1)
  student_count int NOT NULL CHECK (student_count >= 0),
  default_classroom_id uuid REFERENCES classroom(id),
  UNIQUE (org_id, code)
);
CREATE TABLE school_class_subject (
  class_id   uuid NOT NULL REFERENCES school_class(id) ON DELETE CASCADE,
  subject_id uuid NOT NULL REFERENCES subject(id) ON DELETE CASCADE,
  PRIMARY KEY (class_id, subject_id)
);
```

### 3.8 classroom(考场)

```sql
CREATE TABLE classroom (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  code        varchar(20) NOT NULL,                  -- A-301
  name        varchar(60) NOT NULL,
  building    varchar(40) NOT NULL,                  -- 楼栋
  floor       int NOT NULL,
  capacity    int NOT NULL CHECK (capacity > 0),     -- 日常容量
  exam_capacity int NOT NULL CHECK (exam_capacity > 0), -- 考试容量(间隔座),≤capacity(机房例外)
  type        varchar(12) NOT NULL DEFAULT 'normal' CHECK (type IN
              ('normal','lab','computer','lecture','language')),
  is_corridor boolean NOT NULL DEFAULT false,        -- 是否楼道(S5 年龄策略)
  features    jsonb,                                 -- ["多媒体","空调",...]
  exam_types  jsonb,                                 -- 适用考试类型 ["笔试","听力"]
  status      varchar(12) NOT NULL DEFAULT 'available' CHECK (status IN
              ('available','maintenance','occupied')),
  UNIQUE (org_id, code)
);
```

### 3.9 exam(考试场次)

```sql
CREATE TABLE exam (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  exam_task_id uuid NOT NULL REFERENCES exam_task(id) ON DELETE CASCADE,
  exam_no     varchar(20),                           -- E001
  subject_id  uuid REFERENCES subject(id),
  subject_name varchar(30) NOT NULL,                 -- 冗余(导入容错)
  grade       varchar(20) NOT NULL,
  exam_date   date NOT NULL,                         -- 缺失=阻塞错误
  start_time  time NOT NULL,
  end_time    time NOT NULL CHECK (end_time > start_time),
  supervisors_per_room int NOT NULL DEFAULT 2,       -- 每考场监考人数(1主+1副)
  exam_kind   varchar(12) CHECK (exam_kind IN
              ('月考','期中考','期末考','单元测试','模拟考','联考统考')),
  notes       varchar(500)
);
CREATE TABLE exam_class (                              -- 考试涉及班级
  exam_id  uuid NOT NULL REFERENCES exam(id) ON DELETE CASCADE,
  class_id uuid NOT NULL REFERENCES school_class(id),
  PRIMARY KEY (exam_id, class_id)
);
CREATE TABLE exam_room (                               -- 考试占用考场(导入"考场编号列表")
  exam_id     uuid NOT NULL REFERENCES exam(id) ON DELETE CASCADE,
  classroom_id uuid NOT NULL REFERENCES classroom(id),
  seat_demand int,                                    -- 该室考生数(≤exam_capacity)
  PRIMARY KEY (exam_id, classroom_id)
);
CREATE INDEX idx_exam_task ON exam(exam_task_id);
CREATE INDEX idx_exam_date ON exam(org_id, exam_date);
```

### 3.10 exam_task(排考任务)

```sql
CREATE TABLE exam_task (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  task_no     varchar(20) NOT NULL,                  -- JK-2025-001
  name        varchar(100) NOT NULL,                 -- 2025-2026下学期期中考试
  exam_kind   varchar(12),                           -- 月考/期中考...
  grade       varchar(20),                           -- 适用年级/全校
  term_id     uuid REFERENCES academic_term(id),
  start_date  date, day_count int,
  source      varchar(10) CHECK (source IN ('upload','sample','manual')),
  status      varchar(12) NOT NULL DEFAULT 'draft' CHECK (status IN
              ('draft','running','done','conflict','published','archived')),
  owner_id    uuid REFERENCES app_user(id),
  custom_fields jsonb,                               -- PRD 自定义字段
  UNIQUE (org_id, task_no)
);
```

> **状态机**:`draft`(草稿) → `running`(排班中) → `done`(已完成) / `conflict`(有冲突) → `published`(已发布,触发邮件) → `archived`(归档统计)。前端列表 `STATUS_MAP` 仅展示 draft/running/done/conflict;publish/archive 为后端动作态。

### 3.11 schedule_run(一次排考执行)

```sql
CREATE TABLE schedule_run (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  exam_task_id uuid NOT NULL REFERENCES exam_task(id) ON DELETE CASCADE,
  engine      varchar(12) NOT NULL CHECK (engine IN ('quick','ai-policy','manual')),
  status      varchar(12) NOT NULL DEFAULT 'queued' CHECK (status IN
              ('queued','running','succeeded','failed','fallback')),
  stage       varchar(20),                           -- 6 阶段当前
  progress    int NOT NULL DEFAULT 0,                -- 0-100
  ai_policy_id uuid REFERENCES ai_policy(id),
  soft_score  numeric(4,3),                          -- 软约束得分(阈值0.65)
  score_breakdown jsonb,                             -- 工作量/约束/分布/冲突
  stats       jsonb,                                 -- exams/supervisions/teachersUsed/avg/std...
  log         jsonb,                                 -- 终端日志行
  error       text,
  started_at  timestamptz, finished_at timestamptz
);
```

### 3.12 schedule_assignment(排班结果项)

```sql
CREATE TABLE schedule_assignment (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  schedule_run_id uuid NOT NULL REFERENCES schedule_run(id) ON DELETE CASCADE,
  exam_id     uuid NOT NULL REFERENCES exam(id),
  classroom_id uuid NOT NULL REFERENCES classroom(id),
  teacher_id  uuid NOT NULL REFERENCES teacher(id),
  role        varchar(8) NOT NULL CHECK (role IN ('main','assistant')), -- 主/副监考
  exam_date   date NOT NULL, start_time time NOT NULL, end_time time NOT NULL,
  duration_hours numeric(4,2),                        -- 统计/津贴
  allowance   numeric(8,2),                           -- 津贴 = 时长×单价
  confirm_status varchar(10) NOT NULL DEFAULT 'pending' CHECK (confirm_status IN
              ('pending','confirmed','declined')),    -- 监考人员响应
  is_draft    boolean NOT NULL DEFAULT false,         -- 草稿单元格
  conflict    varchar(200),                           -- 冲突说明(如"黄建国14:30已被占用")
  UNIQUE (schedule_run_id, exam_id, classroom_id, teacher_id)
);
CREATE INDEX idx_assign_run ON schedule_assignment(schedule_run_id);
CREATE INDEX idx_assign_teacher ON schedule_assignment(org_id, teacher_id, exam_date);
```

### 3.13 import_batch / validation_issue

```sql
CREATE TABLE import_batch (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  exam_task_id uuid REFERENCES exam_task(id),
  slot        varchar(4) NOT NULL CHECK (slot IN ('t01','t02','t03')), -- 教师/考场/考试
  file_name   varchar(200), file_size int, row_count int,
  state       varchar(12) NOT NULL CHECK (state IN ('validating','uploaded','warning','error')),
  completeness int,                                   -- 0-100
  counts      jsonb,                                  -- {pass,suggest,warn,error}
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE validation_issue (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  import_batch_id uuid NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
  severity    varchar(8) NOT NULL CHECK (severity IN ('suggest','warn','error')),
  category    varchar(8) NOT NULL CHECK (category IN ('fields','avail','cap','time','rules','notes')),
  source      varchar(20),                            -- 教师表/考场表/考试表
  row_ref     varchar(20),                            -- 行号/时段
  col_name    varchar(30),
  problem     varchar(300), suggestion varchar(300),
  status      varchar(12)                             -- 可继续/建议复核/需修改
);
```

### 3.14 ai_policy / ai_call_log

```sql
CREATE TABLE ai_policy (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  exam_task_id uuid REFERENCES exam_task(id),
  toggles     jsonb NOT NULL,                         -- 6策略开关 {homeroom_avoid:true,...}
  numerics    jsonb,                                  -- {homeroomMaxPerDay:2,maxPerDay:3,minGapMin:15}
  nl_addendum varchar(500),                           -- 自然语言补充 ≤500字
  parsed_dsl  jsonb,                                  -- NL→DSL 结果(constraints[])
  provider    varchar(20)                             -- 当前部署值: gpt-agent
);
CREATE TABLE ai_call_log (                             -- 合规:所有 LLM 入参+出参留痕
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid NOT NULL REFERENCES organization(id),
  schedule_run_id uuid REFERENCES schedule_run(id),
  call_type   varchar(12) CHECK (call_type IN ('nl2dsl','solve','summary','refine','fix')),
  provider    varchar(20), model varchar(40),
  prompt      text, response text,
  tokens_in   int, tokens_out int, latency_ms int,
  status      varchar(10), error text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
```

### 3.15–3.20 平台面与公共表(节选 DDL)

```sql
-- 3.15 platform_setting (全局单例) / org_setting (每租户单行)
CREATE TABLE platform_setting (k varchar(60) PRIMARY KEY, v jsonb NOT NULL, updated_at timestamptz);
CREATE TABLE org_setting (org_id uuid PRIMARY KEY REFERENCES organization(id), data jsonb NOT NULL, updated_at timestamptz);

-- 3.16 announcement / announcement_read
CREATE TABLE announcement (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title varchar(140) NOT NULL, body_md text,
  severity varchar(10) CHECK (severity IN ('info','reminder','critical')),
  target varchar(20) CHECK (target IN ('all','admins','selected')),
  target_org_ids jsonb,                               -- target=selected 时
  status varchar(10) CHECK (status IN ('draft','published')),
  publish_at timestamptz, expire_at timestamptz, read_by int DEFAULT 0,
  created_at timestamptz DEFAULT now());
CREATE TABLE announcement_read (
  announcement_id uuid REFERENCES announcement(id) ON DELETE CASCADE,
  org_id uuid, user_id uuid, read_at timestamptz,
  PRIMARY KEY (announcement_id, user_id));

-- 3.17 security_event(失败登录/可疑IP) / 3.18 ip_rule(黑白名单) / 3.19 backup_record
CREATE TABLE ip_rule (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  ip varchar(45) NOT NULL, rule varchar(8) CHECK (rule IN ('ban','allow')),
  reason varchar(200), created_by uuid, created_at timestamptz DEFAULT now());
CREATE TABLE backup_record (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  scope varchar(20), size_bytes bigint, checksum varchar(64),
  location varchar(200), created_at timestamptz DEFAULT now());

-- 3.20 audit_log(通用,解决冲突#6) —— 所有变更与超管操作写此表
CREATE TABLE audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid,                                        -- 超管跨租户可空
  actor_id uuid, actor_name varchar(60),
  action varchar(120) NOT NULL,                       -- "发布排班"/"删除教师档案"...
  target varchar(120),                                -- 目标对象
  severity varchar(8) CHECK (severity IN ('info','warn','high')),
  ip varchar(45), user_agent varchar(300),
  detail jsonb,
  created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX idx_audit_org_time ON audit_log(org_id, created_at DESC);
CREATE INDEX idx_audit_severity ON audit_log(severity, created_at DESC);

-- 3.X notification(站内信/平台事件流)
CREATE TABLE notification (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid, user_id uuid, kind varchar(16), title varchar(140), body varchar(400),
  link_page varchar(40), unread boolean DEFAULT true, created_at timestamptz DEFAULT now());
```

### 3.21 国产数据库(信创)适配说明

PostgreSQL 16 为默认。若客户强制信创(达梦 DM8 / 人大金仓 KingbaseES,二者均高度兼容 PG):

- Hibernate 方言切换:`org.hibernate.dialect`(KingbaseES 提供 PG 兼容方言;DM 用 `DmDialect`)。
- 避免 PG 专有:`gen_random_uuid()` → 应用层 `GenerationType.UUID` 生成;`jsonb` → DM/Kingbase 支持但建议封装 `JsonType`(hypersistence-utils),必要时降级 `text + 应用序列化`。
- RLS:DM/Kingbase 支持度不一,信创部署可关闭数据层 RLS,仅依赖应用层 `@Filter`(隔离仍完整)。
- Flyway:占位用 `${}`,DDL 走「最大公约数」SQL;为信创单独维护 `db/migration-dm/` 变体目录。

### 3.22 共享枚举(年级体系,随 school_type 变)

```
小学: 一年级..六年级
初中/完全中学初中部: 初一(七年级)/初二(八年级)/初三(九年级)
高中/完全中学高中部: 高一/高二/高三
考试类型: 月考/期中考/期末考/单元测试/模拟考/联考统考
```
建议落 `code_dictionary(org_id, type, code, label, sort)` 字典表,前端下拉与校验共用,避免硬编码。

---

## §4 认证 / 授权 / 安全

### 4.1 登录流程(单一入口,解决冲突 #2)

<span style="color:#2563EB;font-weight:700">【二轮·保留并明确】</span> **前端不大改**:保留 `login.jsx` 现有 admin/super 角色 tab(UI 不变),仅作 UX 提示;后端**仍不信任**前端声明角色,以账号自身 `role` 为准。登录成功后前端按返回的 `res.role` 决定落地页(`SUPER_ADMIN→super-dashboard`,否则 `dashboard`)。详见 09 §9。

```
POST /api/auth/login { username, password, captchaId, captcha }   // 不接受前端声明 role
  1. 限流(Bucket4j: 同IP 10次/分;同账号 5次/5分)
  2. 校验图形验证码(captchaId 对应 Redis 中答案,大小写不敏感,一次性)
  3. 查 app_user;不存在/禁用 → 401(信息模糊,防枚举)
  4. locked_until > now → 423 Locked
  5. bcrypt 校验;失败 → failed_attempts++,≥5 设 locked_until=now+30min,写 security_event + audit_log
  6. 成功:failed_attempts=0;若 mfa_enabled → 返回 mfaRequired,走 /auth/2fa/verify
  7. 签发 JWT:access(1h, claims: sub,role,org,name) + refresh(7d, Redis 白名单)
  8. 更新 last_login_at/ip、login_count;写 audit_log
  → { token, refreshToken, role, org:{id,slug,name}, user:{...}, mustChangePwd }
前端据 role 落地:SUPER_ADMIN→super-dashboard;ADMIN→dashboard;VIEWER→只读首页
```

- **验证码后端化**(原型为前端生成,正式必须后端):`POST /api/auth/captcha` → `{captchaId, imageBase64}`,答案存 Redis TTL 3 分钟。
- **首登强制改密**:`mustChangePwd=true` 时前端跳改密页,调 `/api/auth/change-password`。
- **记住 7 天**:refresh token 有效期,Redis 维护白名单(可吊销)。

### 4.2 RBAC 矩阵(权限点 = 页面/导航项)

| 权限点(page id) | 模块 | SUPER_ADMIN | ADMIN | VIEWER | TEACHER(预留) |
|---|---|:--:|:--:|:--:|:--:|
| dashboard 仪表盘 | 总览 | | ✅ | 👁 | |
| quick 一键排监考 | 排班 | | ✅ | | |
| ai AI 策略排监考 | 排班 | | ✅ | | |
| edit 修改监考安排 | 排班 | | ✅ | | |
| tasks 任务总览 | 排班 | | ✅ | 👁 | 👁(仅本人) |
| stats-supervise 监考次数统计 | 数据 | | ✅ | 👁 | |
| people 监考人员管理 | 数据 | | ✅ | 👁 | |
| classrooms 考场管理 | 数据 | | ✅ | 👁 | |
| classes/subjects 班级科目 | 数据 | | ✅ | 👁 | |
| history 历史统计 | 数据 | | ✅ | 👁 | 👁(仅本人) |
| settings 信息设置 | 设置 | | ✅ | | |
| super-dashboard 超管仪表盘 | 平台 | ✅ | | | |
| super-tenants 租户管理 | 平台 | ✅ | | | |
| super-accounts 管理员账号 | 平台 | ✅ | | | |
| super-system-settings 系统设置 | 平台 | ✅ | | | |
| super-audit 审计日志 | 平台 | ✅ | | | |
| super-security 安全中心 | 平台 | ✅ | | | |
| super-announcements 公告发布 | 平台 | ✅ | | | |

✅=读写 👁=只读。实现:方法级 `@PreAuthorize("hasRole('ADMIN')")` + 数据级 TenantContext;VIEWER 通过 `@PreAuthorize("hasAnyRole('ADMIN','VIEWER')")` 且写操作仅 ADMIN。

### 4.3 Spring Security 配置要点

```java
http
 .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())  // 变更端点需 X-CSRF-Token
            .ignoringRequestMatchers("/api/auth/login","/api/auth/captcha"))
 .cors(Customizer.withDefaults())                          // 联调期放行前端源
 .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
 .authorizeHttpRequests(a -> a
    .requestMatchers("/api/auth/**","/api/public/**").permitAll()
    .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
    .anyRequest().authenticated())
 .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
 .addFilterAfter(tenantResolverFilter, JwtAuthFilter.class)
 .exceptionHandling(e -> e.authenticationEntryPoint(restAuthEntryPoint));  // 统一401
```

### 4.4 安全清单(逐项映射前端已暴露的能力)

| 控制项 | 要求 | 实现 |
|---|---|---|
| 密码 | bcrypt cost 12;强度前端实时反馈;90 天过期 | `BCryptPasswordEncoder(12)`;`pwd_changed_at` 校验 |
| JWT | RS256 公私钥可旋转;access 1h + refresh 7d | Nimbus;私钥 KMS/密钥文件;JWKS 端点轮换 |
| 2FA | 邮箱/短信/TOTP;超管可强制 | `mfa_method`;短信走可插拔 `SmsGateway`(§9) |
| 登录锁定 | 连续 5 次失败锁 30 分钟 + 审计 | `failed_attempts`/`locked_until` |
| IP 黑白名单 | 封禁/可信 IP | `ip_rule` + 网关/过滤器拦截 |
| CSRF | 所有变更端点校验 token | Cookie+Header 双提交 |
| 文件上传 | 仅 .xlsx;magic byte;10MB;独立进程 30s 超时沙盒 | POI 前置 ZIP/OOXML 魔数校验 + 超时执行器 |
| 静态加密 | 敏感字段 AES-256 at-rest | PG `pgcrypto` 或应用层 `@Convert` |
| 传输 | HTTPS only;HSTS | 网关/Nginx 终止 TLS |
| 越权 | 行级 org 隔离 + 方法级 RBAC | §2 + `@PreAuthorize` |
| AI 审计 | 所有 LLM 入参/出参留痕 | `ai_call_log`(§3.14) |
| 软删除 | 30 天软删 → 硬删;租户暂停 90 天保留 | `disabled_at`/`suspended_at` + 定时清理 |

---

## §5 API 规范(REST)

### 5.1 通用约定

- **Base**:<span style="color:#D97706;font-weight:700">二轮统一外部路径为 `/api/v1`</span>;若 Spring 服务内部或下方端点目录仍以 `/api/...` 表示,需由网关/前端代理明确改写到 `/api/v1/...`,不要同时暴露两套外部口径。
- **认证**:`Authorization: Bearer <access>`;变更请求另带 `X-CSRF-Token`;超管跨租户带 `X-Tenant-Id`。
- **分页**:`?page=1&size=20&sort=field,desc` → `{ content:[...], page, size, total, totalPages }`(仅用于大列表如审计日志 / 任务历史)。<span style="color:#2563EB;font-weight:700">【三轮·已定·保留前端】</span> 主数据(教师/考场/班级/科目/监考统计/历史)**不做服务端分页**,接口整页返回全集,前端 `DataTable`(无分页控件)零改动;个别超大数据量改用「精简列 / 前端虚拟滚动」,仍不改 `DataTable` 结构。详见 09 §9.6。
- **统一错误信封**:
  ```json
  { "code":"VALIDATION_FAILED", "message":"考试日期不能为空",
    "traceId":"...", "errors":[{"field":"examDate","row":5,"reason":"无法排入时间网格"}] }
  ```
  HTTP 语义:400 校验 / 401 未认证 / 403 越权 / 404 / 409 冲突 / 422 业务规则 / 423 锁定 / 429 限流 / 500。
- **幂等**:批量导入/创建支持 `Idempotency-Key` 头。

### 5.2 端点目录(按模块;★=文档已确认,其余据前端推导)

**认证 `/api/auth`**
| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/captcha` | 生成图形验证码 |
| POST ★ | `/login` | 登录 → token/role/org |
| POST | `/2fa/verify` | 二次验证 |
| POST ★ | `/logout` | 登出(吊销 refresh) |
| POST | `/refresh` | 刷新 access |
| GET ★ | `/me` | 当前用户 `{user,org,term,permissions}` |
| POST ★ | `/change-password` | 改密(首登强制) |

**租户级设置 `/api/org`**(ADMIN)
| GET/PUT ★ | `/settings` | 单位信息(名称/类型/地区/地址/电话) |
| GET/PUT | `/account` | 当前账户档案/改邮箱/2FA |
| GET/PUT ★ | `/term` | 学年学期(含覆盖) |

**主数据 `/api/{teachers|classrooms|classes|subjects}`**(标准 CRUD,以 teachers 为模板)
| GET | `/teachers?grade=&subject=&isHomeroom=&status=&q=&page=` | 列表 |
| GET | `/teachers/{id}` | 详情(含 supCount) |
| POST | `/teachers` | 新增 |
| PUT | `/teachers/{id}` | 编辑 |
| DELETE | `/teachers/{id}` | 删除 |
| POST | `/teachers/import` | 批量导入 xlsx(工号幂等) |
| GET | `/teachers/import/template` | 下载模板 |
| GET | `/teachers/{id}/schedule?year=&month=` | 个人排班 timeline |

> classrooms/classes/subjects 同构;classes 另有 `/{id}/students`、`/{id}/subjects`;subjects 另有 `/{id}/exams`。详见 §附录 A 完整索引。

**Excel 导入与校验 `/api/import`**(详见 §6)
| POST ★ | `/teachers` `/rooms` `/exams` | 上传 3 表 → 解析+结构校验 |
| POST | `/validate` | 跨表一致性校验 → 完整报告(6 类/4 级) |
| GET | `/templates/{t01\|t02\|t03}` | 空模板下载 |

**排考 `/api/scheduling`**(详见 §7)
| POST | `/quick` | 一键排监考(同步小数据/异步大数据)→ runId |
| POST | `/ai-policy` | AI 策略排考(异步)→ runId |
| GET | `/runs/{runId}` | 轮询状态/阶段/日志/结果 |
| WS | `/ws/schedule/{runId}` | STOMP 实时进度(6 阶段) |
| POST | `/runs/{runId}/reschedule` | 整体重排 |
| POST | `/runs/{runId}/ai-fix` | 单冲突 AI 修复(可撤销) |
| POST | `/nl-policy/parse` | 自然语言→约束 DSL |
| POST | `/nl-policy/refine` | "优化我的表达" |
| GET | `/ai/providers` · PUT `/ai/providers/active` | 模型列表/切换(超管控) |

**任务 `/api/tasks`**
| GET | `/?status=&grade=&kind=&q=&page=` | 列表 + KPI(total/running/done/conflict) |
| POST | `/` · GET `/{id}` · PATCH `/{id}` · DELETE `/{id}` · POST `/{id}/duplicate` | CRUD |
| POST | `/{id}/publish` | 发布 + 邮件通知全体监考 |
| GET | `/{id}/supervisors` · POST `/{id}/supervisors/{tid}/remind` · `.../replace` | 监考人员 tab |
| GET | `/{id}/schedule` · PUT `/{id}/schedule` · POST `/{id}/schedule/check-conflicts` | 排班编辑(拖拽/批量/草稿) |
| GET | `/{id}/log` | 操作记录(审计) |
| GET | `/{id}/export?view={overall\|teacher\|room\|exam}` · `/export/all` · `/export/ai-summary.pdf` | 导出 |

**统计 `/api/stats`**(详见 §8;多为重聚合 [HEAVY])
| GET | `/kpis` `/kpis/trends` `/dashboard/today` | 仪表盘 |
| GET | `/supervise?dim={teacher\|subject\|grade}&metric={count\|hours\|allowance}&semester=` | 监考次数统计 |
| GET | `/history/teachers` `/history/by-grade` `/history/monthly-trend` | 历史 |
| GET | `/exams/schedule?start=&end=&grade=&kind=` | 时间线(周/日/月) |
| GET | `/teachers/{id}/assignments` | 个人明细 Drawer |
| GET | `/*/export?format={excel\|pdf}` | 导出文件流 |

**超管平台面 `/api/admin`**(SUPER_ADMIN;详见 §10)
租户 `tenants` · 账号 `accounts` · 平台设置 `platform-settings` · 审计 `audit-logs` · 安全 `security/*`+`backups` · 公告 `announcements` · 仪表盘 `dashboard`+`metrics/health` · 通知 `notifications`。完整清单见 §10.2 与附录 A。

---

## §6 Excel 导入 / 导出管线

### 6.1 三张模板(严格契约,源自 `04-EXCEL-TEMPLATES.md`)

行 1 为表头(定序,不可增删改列);保留首行版本注释 `# Template v1.0 — DO NOT MODIFY`。

**① 教师安排 `01-教师安排示例表格.xlsx`**

| 列 | 列名 | 类型 | 必填 | 校验 / 触发等级 |
|---|---|---|---|---|
| A 教师编号 | text | ✅ | 租户内唯一 `[A-Z0-9]{2,10}` · 缺失=**ERROR** |
| B 教师姓名 | text | ✅ | 1–20 · 空=**ERROR** |
| C 任教年级 | enum | ✅ | §3.22 年级 · 非枚举=**ERROR** |
| D 任教科目 | enum | ✅ | 科目枚举 · 非枚举=**ERROR** |
| E 班主任 | bool | ⚪ | 是/Y/空 |
| F 主监考 | bool | ⚪ | 是/空 |
| G 性别 | enum | ⚪ | 男/女/空 · 空→男女混合策略对其失效=**WARN** |
| H 年龄 | int | ⚪ | 22–70 · 空→S5 对其失效=**WARN** |
| I 跨年级授课 | text | ⚪ | "7,8" · S6 需要 |
| J 备注 | text | ⚪ | → extras |

**② 考场信息 `02-考场信息示例表格.xlsx`**:A 考场编号✅ / B 考场名称✅ / C 所在楼栋✅(空=WARN 用默认) / D 所在楼层✅ / E 容量✅(空=**ERROR** 无法算座位余量) / F 是否楼道⚪(S5) / G 适用考试类型⚪ / H 备注⚪。

**③ 考试安排 `03-考试安排示例表格.xlsx`**:A 考试编号✅ / B 考试科目✅ / C 考试年级✅ / D 涉及班级✅("1,2,3") / E 考试日期✅(空=**ERROR** "无法排入时间网格") / F 开始时间✅ / G 结束时间✅(必须 > 开始) / H 考场编号列表✅(每个必须存在于②表) / I 每考场监考人数⚪(默认 2) / J 备注⚪。

### 6.2 校验分级与跨表一致性

- **4 级严重度**:`error`(阻塞,必须修正)/ `warn`(可继续,需确认)/ `suggest`(建议)/ `pass`。
- **6 类**:`fields 字段完整性` / `avail 教师可用性` / `cap 考场容量` / `time 时间冲突` / `rules 班主任·科目·年级规则` / `notes 特殊备注`。
- **完整度**(占位公式,服务端以真实计算覆盖):`100 − error×5 − warn×1`。
- **跨表一致性**(导入后):② 考场编号∈③ 考场列表;③ 年级在① 至少 1 名可监考教师;**③ 总场次×每场人数 ≤ ① 教师总数×学期可监考上限**(容量可行性);每行失败回 `行号+列名+expected/actual`。

### 6.3 处理流水(后端)

```
upload(multipart) → 1.魔数校验(OOXML ZIP 头) + 大小≤10MB + 仅.xlsx
  → 2.沙盒解析(独立线程池/进程,超时30s) POI 读 → headers + rows
  → 3.结构校验(列名定序、类型、必填、枚举、唯一) → validation_issue[]
  → 4.落 import_batch(state/counts/completeness)
  → /import/validate: 5.跨表一致性 → 合并报告
  → 6.确认导入:row→实体 upsert(工号/编号/考试编号为幂等键),事务写库
```

```java
// 解析骨架(POI;大文件改用 EasyExcel 流式回调)
try (XSSFWorkbook wb = new XSSFWorkbook(sandboxedStream)) {
  XSSFSheet sheet = wb.getSheetAt(0);
  List<String> headers = readHeaderRow(sheet, schema.expectedColumns()); // 定序校验
  for (Row row : sheet) { /* 类型/必填/枚举/唯一 → issues */ }
}
```

### 6.4 导出(4 表 + 说明页)

`GET /tasks/{id}/export/all` → 单工作簿 5 sheet(POI 写):
- **监考安排总表**:日期·时段·科目·考场·楼栋·班级·考生数·监考教师
- **教师监考分表**:教师·任教年级·监考场次
- **考场安排分表**:考场·占用场次·明细
- **考试安排分表**:日期·时段·科目·班级·考生数·考场·监考人数
- **说明(meta)**:任务编号·学校·学期·生成时间·考试场次·监考人次·涉及教师·工作量标准差

单视图 `?view=overall|teacher|room|exam` 导出单 sheet;`ai-summary.pdf` 用模板引擎(Thymeleaf)+ openhtmltopdf/Flying Saucer 渲染。

---

## §7 智能排监考引擎(核心)

### 7.1 混合架构(D4:约束求解为主 + LLM 辅助)

```
                ┌──────────────── 一键排监考(quick)──────────────┐
                │ 跳过 NL/AI,直接进求解器(默认 5 策略)             │
输入(3表已导入) ─┤                                                 ├─→ 结果
                │ AI 策略排监考(ai-policy):                        │
                └─ ① LLM: NL→DSL ─ ② 求解 ─ ③ LLM: 总结 ──────────┘
```

**职责切分(关键设计)**:

| 环节 | 谁来做 | 说明 |
|---|---|---|
| 自然语言 → 约束 DSL | **LLM**(call `nl2dsl`) | 把"王老师周一上午外出""初三和高三错开半小时"解析成结构化软/硬约束 + 权重 |
| 核心分配(谁监考哪个考场) | **OR-Tools CP-SAT**(权威) | 确定性、可复现、保证硬约束;LLM 可选提供 warm-start 提示但**不决定最终解** |
| 软约束打分 | 求解器目标函数 | 加权;阈值 0.65 |
| 失败回退 | CP-SAT 放松 + 报告 | 不可行时定位最小冲突集,回报用户 |
| 单元格冲突修复 | **LLM 建议 + 求解器校验** | edit 页"AI 自动修复":LLM 提候选,求解器验证不引入新冲突,可撤销 |
| 结果总结 | **LLM**(call `summary`) | 自然语言整体评估 + 复核建议 + 下一步 |

> 与 `03-AI-WORKFLOW.md` 的差异:原文档 call 2「主排考」由 LLM 产出 assignments、OR-Tools 仅做硬校验。**按 D4 改为求解器主导**——更稳、可复现、硬约束 100% 保证;LLM 退居"翻译 + 总结 + 修复建议"。原文档的三段式调用、温度、缓存、重试/回退策略保留。

### 7.2 约束清单(求解器实现目标)

**硬约束(违反即无效,CP-SAT 强制)**:

| 编号 | 名称 | 形式化 |
|---|---|---|
| H1 | 同一时段一名监考不在两个考场 | ∀teacher t, ∀重叠时段 s: Σ assign[t,*,*] over s ≤ 1 |
| H2 | 每考场每场恰好 1 名主监考 | ∀(exam e, room r): Σ_t main[e,r,t] = 1 |
| H3 | 每考场监考人数 = 需求 | ∀(e,r): Σ_t assign[e,r,t] = supervisors_per_room |
| H4 | 班主任避监本班(`homeroom_avoid`) | assign[e,r,t]=0 若 t 是 e 涉及班级的班主任 |
| H5 | 同年级回避(`same_grade_avoid`) | assign[e,r,t]=0 若 t.grade == e.grade |
| H6 | 班主任监考 ≤ 2 场(S2) | ∀ homeroom t: Σ assign[t] ≤ 2 |
| H7 | 容量可行 | seat_demand(e,r) ≤ room.exam_capacity(预过滤,非变量) |

**软约束(目标函数加权,权重来自 `03-AI-WORKFLOW.md §3` + 前端策略)**:

| 编号 | 名称 | 默认权重 | 方向 |
|---|---|---|---|
| S1 | 工作量均衡(`workload_balance`,±2 场) | 0.20 | minimize 监考次数离差 |
| S3 | 班主任监考 < 学科教师均值 | 0.15 | soft |
| S4 | 当天有课老师排早场(<15:00) | 0.20 | prefer |
| S5 | 年龄 > N → 楼道考场(`is_corridor`) | 0.15 | prefer |
| S6 | 跨年级老师优先高年级 | 0.30 | prefer |
| Sg | 男女混合监考(`gender_mix`,每室≥1男1女) | 0.20 | penalty if 同性 |
| Sj | 副科监主科(`subject_off`,不监本科目) | 0.15 | penalty if 同科目 |

权重可被 LLM 解析的 NL 约束动态追加/调整;最终 `softScore = Σ wᵢ·satisfiedRatioᵢ`,阈值 **0.65**。

### 7.3 CP-SAT 模型(OR-Tools Java)

```java
// scheduling/solver/CpSatScheduler.java  (com.google.ortools.sat)
Loader.loadNativeLibraries();
CpModel model = new CpModel();

// 决策变量:assign[e][r][t] ∈ {0,1}  仅对"可行教师集"建变量(预过滤 H4/H5/H7 剪枝域)
Map<Key, BoolVar> assign = new HashMap<>();
Map<Key, BoolVar> main   = new HashMap<>();   // 主监考标志
for (Exam e : exams) for (Room r : e.rooms()) for (Teacher t : feasible(e, r)) {
    assign.put(key(e,r,t), model.newBoolVar("a_"+e+"_"+r+"_"+t));
    main.put(  key(e,r,t), model.newBoolVar("m_"+e+"_"+r+"_"+t));
    model.addLessOrEqual(main.get(key(e,r,t)), assign.get(key(e,r,t))); // main ⇒ assigned
}

// H3 人数 / H2 一主监考
for (Exam e : exams) for (Room r : e.rooms()) {
    model.addEquality(sum(assign over t), e.supervisorsPerRoom());      // H3
    model.addEquality(sum(main   over t), 1);                          // H2
}
// H1 时段不冲突
for (Teacher t : teachers)
  for (TimeSlot s : overlappingSlots())
    model.addLessOrEqual(sum(assign[*,*,t] where exam in s), 1);
// H6 班主任 ≤2
for (Teacher t : homeroomTeachers())
    model.addLessOrEqual(sum(assign[*,*,t]), 2);

// ── 软约束 → 目标 ──
List<IntVar> penalties = new ArrayList<>();
// S1 工作量均衡:load[t]=Σassign;最小化 (maxLoad - minLoad)
IntVar[] load = perTeacherLoad(model, assign);
IntVar maxL = model.newIntVar(0, U, "maxL"); IntVar minL = model.newIntVar(0, U, "minL");
model.addMaxEquality(maxL, load); model.addMinEquality(minL, load);
IntVar spread = model.newIntVar(0, U, "spread");
model.addEquality(spread, LinearExpr.sum(new IntVar[]{maxL}, ...).subtract(minL));
// Sg 男女混合 / Sj 副科 / S4 / S5 / S6 → 各自 reified 惩罚变量,见 builder
// 目标:最小化 Σ wᵢ·penaltyᵢ(整数化权重×100)
model.minimize(LinearExpr.weightedSum(penalties.toArray(IntVar[]::new), intWeights));

CpSolver solver = new CpSolver();
solver.getParameters().setMaxTimeInSeconds(45);   // NFR <60s
solver.getParameters().setNumSearchWorkers(8);    // 并行
CpSolverStatus st = solver.solve(model);
// st∈{OPTIMAL,FEASIBLE}→提取解;INFEASIBLE→最小冲突集定位(放松H4/H5报告)
```

> **规模**:300 教师 × 8 场考试(NFR)。预过滤把变量域压到可行集后,CP-SAT 45s 内可得 OPTIMAL/FEASIBLE;不可行时用 `addAssumptions` + `sufficientAssumptionsForInfeasibility` 输出最小冲突,回报"约束过严"。

### 7.4 LLM 集成(复用现成 API)

```java
// ai/LlmClient.java —— 多模型抽象(OpenAI 兼容);复用现有 LLM API 端点
public interface LlmClient {
  DslResult nl2dsl(String nlText, SchoolContext ctx);     // call 1, 温度 0.2
  AiSummary summarize(ScheduleResult r);                  // call 3, 温度 0.4
  FixSuggestion suggestFix(Conflict c, Context ctx);      // edit AI 修复
}
// 实现:OpenAI-compatible client → {primaryBaseUrl, fallbackBaseUrl, apiKey, model};
// 当前批准配置为 gpt-agent + claude-sonnet-4-6，密钥仅由服务端环境注入。
// 或直接用 Spring AI 的 OpenAiChatModel(可指向任意 OpenAI 兼容网关)
```

- **NL→DSL 输出**:`{ constraints:[{name,type:'hard'|'soft',weight,scope,expression(JSON DSL),explanation}], unable_to_parse:[] }`;DSL 词汇:COND(`t.age>N`,`t.gender=='女'`,`t.subject==X`,`t.is_homeroom`)、TARGET(`assign[t].room.is_corridor`,`.exam.grade`,`.start`)、AGG(count/mean/stdev)、OP、COMBINE。
- **缓存**:system prompt 走 provider 的 prompt cache;teacher/room/exam 上下文 5 分钟临时缓存(Redis)。
- **合规**:每次调用入参+出参写 `ai_call_log`(§3.14)。
- **回退链**(`03 §10`):超时 60s → 切模型 → CP-SAT;非 JSON → 重试+schema 约束;硬违反 → 重试后 CP-SAT;全软<0.4 → 报"约束过严"+CP-SAT 可行解;LLM 网关宕 → 标 run `failed` + 邮件超管。

### 7.5 异步执行 + 6 阶段进度

排考异步(`schedule_run.status=queued→running→succeeded/failed/fallback`),STOMP 推 6 阶段:`读取三张表 → 应用默认规则 → 平衡监考次数 → 检查班主任/科目/年级规则 → 生成结果预览 → 准备下载文件`。前端 `quick-step3` 消费 `progress`/`stage`/`log`。

### 7.6 结果数据契约(填充前端)

<span style="color:#DC2626;font-weight:700">【二轮·必须补全】</span> 据第二轮前端审计补全(详见 09 §9.2):`fairness` 必须返回**5 项**(前端 `quick-step4` 已渲染 5 张公平性卡:平均/最大差/班主任占比/楼道分配/特殊备注);`ai.targets[].kind` 枚举含 `teacher|room|slot`(前端 `QkAITargetChip` 已为三者各配图标)。下方契约已据此更新。

```jsonc
// GET /scheduling/runs/{runId} (succeeded)
{ "runId":"...", "taskId":"JK-2025-001", "status":"succeeded",
  "stats": { "exams":8,"supervisions":48,"teachersUsed":22,"rooms":12,
             "avg":"2.2","spread":2,"std":"0.70" },
  "fairness": [                                       // R2: 必须齐 5 项(前端 step4 渲染 5 张卡)
    {"key":"avg",   "label":"平均监考次数",   "value":"2.2 次/人","rating":"良好","dots":4},
    {"key":"spread","label":"最大监考次数差", "value":"2 次",     "rating":"合格","dots":3},
    {"key":"head",  "label":"班主任监考占比", "value":"18%",      "rating":"良好","dots":4},
    {"key":"bldg",  "label":"楼道监考分配",   "value":"均衡",     "rating":"良好","dots":4},
    {"key":"notes", "label":"特殊备注处理",   "value":"5 条",     "rating":"已处理","dots":5} ],
  "scoreBreakdown": {"workload":18,"constraint":38,"distribution":22,"conflictPenalty":0,"total":96},
  "ai": { "verdict":"整体均衡,2 项需复核",
          "reviews":[{"sev":"warn","who":"黄建国","msg":"14:30 时段接近上限"}],
          "targets":[{"kind":"teacher|room|slot","name":"...","meta":"..."}],  // R2: kind 含 room/slot
          "nextSteps":["..."] },
  "downloads": [ {"view":"overall","label":"监考安排总表","rows":48,"size":"24KB"}, ... ],
  "assignments": [ {"examId","roomId","teacherId","role":"main","date","start","end","conflict":null} ] }
```

### 7.7 排班手动编辑 · 真拖拽改派(交互契约)

<span style="color:#D97706;font-weight:700">【三轮·已定·本期纳入】</span> 经用户拍板,`edit.jsx` 本期**实现真·拖拽改派**——这是「前端不大改」原则下**唯一被批准的 C 类(新交互)前端改动**;其余页面仍只做 A/B 接线。本节给出交互契约、后端端点与前端改动范围(本轮仅文档,不写代码)。

**A. 交互模型**
- 网格 = 考场(行)× 时段/场次(列);单元格 `ScheduleCell` 内是该(考场×场次)的监考教师(主/副)。
- 拖拽源:① 单元格内的教师 chip;② 新增「待分配 / 候选监考」侧栏(可折叠)作为拖拽源。
- 拖拽目标:另一(考场×场次)单元格。落子语义:**移动**(源空出)/ **交换**(目标已满→与被占教师对调)/ **指派**(候选栏拖入)/ **撤下**(拖回候选栏)。
- 技术:HTML5 Drag-and-Drop(`draggable`+`onDragStart/onDragOver/onDrop`)或指针事件 DnD;须有 drag ghost、drop-target 高亮、非法目标禁投反馈。

**B. 乐观更新 + 后端校验闭环**
1. 落子 → 前端**乐观**就地更新 + push 本地撤销栈;
2. `PATCH /tasks/{id}/schedule`(单步增量,见 C)→ 后端校验硬约束并落库(乐观锁 `@Version`,§3.12);
3. `POST /tasks/{id}/schedule/check-conflicts` → 返回受影响冲突数组 → 动态渲染 `ConflictBanner`(0 条隐藏 / N 条循环,替代写死的「1 处」);
4. 任一步失败 → 前端**回滚**乐观更新 + toast 原因(如「黄建国 14:30 已被高三(2)班占用」,硬约束 H1)。

**C. 端点契约(在 §5.2「排班编辑」基础上细化)**
- <span style="color:#059669;font-weight:700">新增·明确</span> `PATCH /api/v1/tasks/{id}/schedule` — 单步增量改派,body `{ op:'move'|'swap'|'assign'|'unassign', examId, fromRoomId?, toRoomId, teacherId, withTeacherId?(swap), role:'main'|'assistant', version }` → resp `{ ok, version, affected:[assignmentId…], conflicts:[…] }`。**增量 PATCH 与 §5.2 整表 `PUT` 并存**:拖拽逐步用 PATCH(求响应),显式「保存草稿」用 PUT(批量)。
- 复用 `POST /tasks/{id}/schedule/check-conflicts`、`POST /scheduling/runs/{runId}/ai-fix`(拖出冲突后一键 AI 建议替换)、`POST /tasks/{id}/publish`。
- 撤销/重做:前端编辑栈为主(⌘Z/⌘⇧Z 已有 UI);每步 PATCH 持久化,撤销=反向 PATCH;后端 `@Version` 防并发覆盖。

**D. 前端改动范围(C 类 · 明确登记)**
`edit.jsx`:① `ScheduleCell` 增 `draggable`+拖放事件与视觉反馈;② 新增「候选监考」拖拽源侧栏(可折叠);③ `ConflictBanner` 由写死改为消费 `check-conflicts` 动态列表;④ 本地撤销/重做栈接 PATCH。**布局骨架与视觉风格保持不变,仅叠加拖放交互层**。其余页面不受影响。

**E. 风险与兜底**
- <span style="color:#DC2626;font-weight:700">可访问性/触屏</span>:DnD 必须配**键盘可达**等价操作(选中单元格→「改派」抽屉)与触屏长按拖拽——无障碍是 K-12 采购验收项。
- 并发:多管理员同改一任务,`@Version` 冲突时提示「已被他人修改,请刷新」。
- 性能:大网格(数百单元格)拖拽用 CSS transform + 局部重渲染,避免整表 re-render。
- 硬约束即时性:落子即 `check-conflicts`,冲突单元格红框高亮(H1 同时段双占等)。

### 7.8 AI 策略页进度去向(交互路线)

<span style="color:#2563EB;font-weight:700">【三轮·已定·复用】</span> `ai-policy.jsx` 提交策略后**不在本页内嵌进度区**:改为 `POST /api/v1/scheduling/ai-policy` 拿 `runId` → `window.navigateTo('quick')` 跳转并**复用 `quick-step3`(6 阶段 STOMP 进度)+ `quick-step4`(结果契约 §7.6)**。即 quick 向导与 AI 策略**共用同一 run/进度/结果链路**,零新增进度组件。前端改动:`ai-policy` 的 `startAI` 由本地假 `setTimeout` 改为「提交→拿 runId→navigateTo(携带 runId 上下文)」;`quick` 接收外部 `runId` 时直接进入 step3。属 B 级接线,两页布局不变。

---

## §8 统计报表

### 8.1 聚合端点 → SQL(节选)

```sql
-- 监考次数统计 /stats/supervise?dim=teacher&metric=count
SELECT t.id, t.name, t.grade, COUNT(a.id) cnt,
       SUM(a.duration_hours) hours, SUM(a.allowance) allowance
FROM schedule_assignment a JOIN teacher t ON a.teacher_id=t.id
JOIN schedule_run r ON a.schedule_run_id=r.id JOIN exam_task k ON r.exam_task_id=k.id
WHERE a.org_id=:org AND k.term_id=:term AND k.status='published'
GROUP BY t.id ORDER BY cnt DESC;   -- dim=subject/grade 仅改 GROUP BY

-- 历史按年级分布 donut / 月度趋势:DATE_TRUNC('month', a.exam_date)
```

### 8.2 性能:物化视图 + 事件刷新

重聚合 [HEAVY] 接口(`/supervise`、`/history/teachers`、`/kpis`、`/history/monthly-trend`)采用 `stats_cache` 表或 PG **物化视图**:

- 排班 **发布/修改** 时发领域事件 `ScheduleChangedEvent` → 异步 job 刷新该 `(org, term, dim)` 聚合写 `stats_cache`。
- 接口先读缓存,未命中实时算并回写。
- KPI 看板可加 Redis 短 TTL(60s)。

### 8.3 关键派生量

- **津贴** `allowance = duration_hours × 单价`(单价存 `org_setting`,前端示例 ¥100/场口径)。
- **均衡度**:除 `spread` 外补 `stddev`、`max/avg`、可选基尼系数,支撑公平性告警。
- **本学期贡献%**:前端 `count/200` 硬编码分母 → 后端用真实「全校本学期总场次」。

---

## §9 异步任务 / 实时通信

| 场景 | 方案 |
|---|---|
| 排考(30–60s) | `schedule_run` 作业表 + `@Async`(虚拟线程池)或 Redis 队列消费;幂等;超时熔断 |
| 进度推送 | Spring WebSocket + STOMP `/ws/schedule/{runId}`;订阅鉴权(JWT 握手) |
| 导出大文件 | 异步生成 → 对象存储(OSS/MinIO)→ 回调下载链接 |
| 邮件/短信 | `notify` 模块异步发送;失败重试 + 死信 |
| 定时 | 统计物化刷新、自动备份(每日 03:00)、试用到期检查、软删清理:`@Scheduled` 或 XXL-JOB(集群去重用 Redis 锁) |

**短信网关可插拔**(填补文档缺口):
```java
public interface SmsGateway { void send(String phone, String tplCode, Map<String,String> params); }
// 实现:AliyunSmsGateway / TencentSmsGateway / NoopSmsGateway(私有化无短信时)
```

---

## §10 超管控制面(平台 Plane)

### 10.1 模块要点

- **超管仪表盘** `/admin/dashboard` + `/admin/metrics/health`:平台安全分、租户活跃率、2FA 覆盖率、安全告警;CPU/内存/存储/DB 连接/API 调用实时;近期跨租户审计流;租户概览卡;`POST /admin/reports/weekly`(运营周报邮件)。
- **租户管理** `/admin/tenants`:列表/详情/编辑(slug 锁)/注册向导(基础→套餐→主管理员→域名→确认,事务)/暂停(保留 90 天)/恢复/CSV 批量导入。
- **管理员账号** `/admin/accounts`:跨租户列表;新增(自动临时密码+强制首登 2FA);凭证抽屉(改验证邮箱/手机、强制 2FA、密码 90 天过期、跨地区登录开关、会话 8h);重置密码/强制下线/禁用(30 天)/永久删除(留 30 天)。
- **平台设置** `/admin/platform-settings`:系统名/域名/邮箱/时区/语言/默认学段/Logo + 全局开关(邮件、强制 2FA、访客只读、自动备份、维护模式);保存广播全租户 + 审计。
- **审计日志** `/admin/audit-logs`:筛选(时间/级别 info·warn·high/租户/类型);详情含 userAgent;导出(CSV/xlsx/JSON/PDF,导出本身记审计)。
- **安全中心** `/admin/security/*`:安全分维度;可疑 IP 表(`threats`);封禁/白名单 IP;立即备份 `/admin/backups/run`;备份列表。
- **公告发布** `/admin/announcements`:Markdown 正文;目标(全部/选定租户/仅管理员);发布/推送(站内信+邮件)/撤回;各租户阅读率 `/read-stats`。
- **平台通知** `/admin/notifications`:可疑 IP、新租户待审、账号锁定、试用到期、备份完成、版本部署、高危审计。

### 10.2 端点见 §5.2 + 附录 A;全部 `hasRole('SUPER_ADMIN')`,跨租户操作强制写 `audit_log(org_id=目标租户, severity)`。

---

## §11 非功能 / 部署 / 运维

### 11.1 NFR(源自 `01-PRD §6`)

| 类别 | 指标 |
|---|---|
| 并发 | 单校 50 在线管理员;AI 排考队列 5 并发 |
| 响应 | 普通页 <1s;AI 排考 <60s(300 师 8 场) |
| 可用 | SaaS 99.5%/月;私有化依客户基础设施 |
| 安全 | bcrypt12、JWT7d、HTTPS、CSRF、SQL 参数化、AES-256 at-rest |
| 合规 | 教育数据本地化(大陆服务器)、出口加密、删除 30 天软删 |
| i18n | 框架预留,v1 中文 |

### 11.2 部署拓扑

**SaaS**:`Nginx/SLB → Spring Boot(多副本,K8s ACK) → PG RDS(主从)+ Redis 集群`;OSS+CDN 静态;Prometheus+Grafana+Sentry;CI/CD(GitHub Actions/云效)→ 镜像 → ACK 滚动。

**私有化**:`docker-compose.yml`(app + postgres + redis + nginx)单机离线镜像包;`.env` 配 `DEPLOY_MODE=private`、固定 `FIXED_ORG_ID`、LLM 端点(可指私有模型)、本地备份卷;无外网时 LLM 可降级"仅启发式 + CP-SAT"。

```yaml
# docker-compose.private.yml(节选)
services:
  app:    { image: jkxt/backend:2.0, env_file: .env, depends_on: [postgres, redis] }
  postgres:{ image: postgres:16, volumes: [pgdata:/var/lib/postgresql/data] }
  redis:  { image: redis:7 }
  nginx:  { image: nginx, ports: ["443:443"], volumes: [./certs:/certs] }
```

### 11.3 配置矩阵(profile 驱动)

| 变量 | saas | private |
|---|---|---|
| `DEPLOY_MODE` | saas | private |
| `FIXED_ORG_ID` | — | 安装种子 UUID |
| `DB_URL` | 云 RDS | 本地 PG/DM/Kingbase |
| `JKXT_LLM_PROVIDER` | `gpt-agent` | `gpt-agent` |
| `JKXT_LLM_BASE_URL` | `https://api.llm-token.cn/v1` | 同左，或批准的私有兼容端点 |
| `JKXT_LLM_FALLBACK_BASE_URL` | `https://gpt-agent.cc/v1` | 同左，或批准的备用兼容端点 |
| `JKXT_LLM_API_KEY` | Secret Manager 注入 | 未跟踪 `.env.private` / 本地密钥管理器注入 |
| `JKXT_LLM_MODEL` | `claude-sonnet-4-6` | `claude-sonnet-4-6` |
| `JKXT_LLM_MAX_RETRIES` | `2` | `2` |
| `SMS_PROVIDER` | aliyun | noop/本地 |
| `BACKUP_TARGET` | OSS | 本地卷 |

---

## §12 前后端联调(把现有原型接管为真实客户端)

> 现状:前端组件挂在 `window.*`,数据全为 mock,导航用 `localStorage(jkxt:auth/mode/page)` + `jkxt:nav` 事件,无任何网络请求。联调即"用真实 API 替换 mock 数据源",**UI 几乎不改**。
>
> <span style="color:#DC2626;font-weight:700">二轮安全修正:</span> 本节中 `localStorage(jkxt:token)` 仅作为本地静态原型/开发联调过渡示例。生产环境必须使用 httpOnly session/refresh cookie、短期 access token in memory 或 BFF 模式,并以 `/auth/me` 作为真实登录态与权限来源。

### 12.1 引入 API 客户端层(新增 `src/api.js`)

```js
// 统一封装:错误信封、CSRF、租户头
// 注意:localStorage token 只用于开发联调;生产改用 httpOnly cookie / BFF。
window.api = {
  base: (location.hostname.endsWith('jkxt.cn') ? '' : 'http://localhost:8080') + '/api/v1',
  token: () => localStorage.getItem('jkxt:token'), // dev-only transitional token
  async req(method, path, body, opts={}) {
    const r = await fetch(this.base + path, {
      method, headers: {
        'Content-Type':'application/json',
        ...(this.token() ? {Authorization:`Bearer ${this.token()}`} : {}),
        ...(method!=='GET' ? {'X-CSRF-Token': getCookie('XSRF-TOKEN')} : {}),
      },
      body: body && JSON.stringify(body),
    });
    if (r.status===401) { window.navigateTo('login'); throw new Error('unauth'); }
    if (!r.ok) { const e = await r.json().catch(()=>({})); window.ui.toast(e.message||'请求失败',{kind:'error'}); throw e; }
    return r.status===204 ? null : r.json();
  },
  get(p){return this.req('GET',p)}, post(p,b){return this.req('POST',p,b)},
  put(p,b){return this.req('PUT',p,b)}, del(p){return this.req('DELETE',p)},
  upload(p, file){ const fd=new FormData(); fd.append('file',file);
    return fetch(this.base+p,{method:'POST',headers:{Authorization:`Bearer ${this.token()}`},body:fd}).then(r=>r.json()); }
};
```

加载顺序:在 `Dashboard.html` 中 `src/overlay.jsx` 之后、各页面之前加 `<script src="src/api.js"></script>`。

### 12.2 会话改造(`app.jsx` / `login.jsx`)

- `login.jsx handleLogin`:改为 `const res = await window.api.post('/auth/login', {username,password,captchaId,captcha})`;开发联调阶段可临时写 `jkxt:auth='1'`、`jkxt:mode = res.role==='SUPER_ADMIN'?'super':'admin'`;生产阶段不持久保存长期 token,**角色由后端 res.role / `/auth/me` 决定**(不再由前端 tab)。
- `app.jsx`:启动时 `GET /auth/me` 校验会话有效并拿 `{user,org,term,permissions}`;`handleLogout` 调 `/auth/logout` 清服务端会话与本地 hint;`permissions` 驱动 sidebar 可见项(替代硬编码 mode 分支)。

### 12.3 各页 mock → fetch 映射(渐进式)

| 页面 | 现 mock | 接管为 |
|---|---|---|
| people/classrooms/classes/subjects | `MOCK_*` 数组 | `useEffect(()=>api.get('/teachers?...'))` |
| quick-step1 上传 | 前端 parseWorkbook | `api.upload('/import/teachers', file)` → 校验报告来自 `/import/validate` |
| quick-step3 排班 | 本地 generateSchedule | `api.post('/scheduling/quick',{...})` → runId → STOMP 订阅进度 |
| quick-step4 结果 | mock 结果 | `api.get('/scheduling/runs/{runId}')`(§7.6 契约) |
| ai-policy | 本地 | `/scheduling/ai-policy` + `/nl-policy/parse` + `/ai/providers` |
| edit | mock 网格 | `/tasks/{id}/schedule` + `/runs/{id}/ai-fix` |
| tasks/task-detail | mock | `/tasks*` |
| stats/history/timeline | mock | `/stats/*` |
| super.jsx 各模块 | `SUPER_*` | `/admin/*` |

### 12.4 联调注意

- **CORS**:开发期后端放行前端源(`http://localhost:8000`);生产同域或网关代理 `/api`。
- **保留 mock 回退**:每页加 `USE_API` 开关,后端未就绪时回退 mock,支持分模块灰度上线。
- **localStorage 语义升级**:`jkxt:auth` 仅作开发态快速首屏 hint,**真实鉴权以 `/auth/me` 为准**;`jkxt:token` 不进入生产方案。
- **`window.__jkxtDemo` 退役**:Tweaks 演示开关在接真后端后改为仅开发态可见。

---

## §13 实施路线图 与 风险/缺口

### 13.1 分期(建议 6 个 Sprint × 2 周)

| Sprint | 主题 | 交付 |
|---|---|---|
| **S1 地基** | 工程脚手架 + 多租户 + 认证 | Spring Boot 骨架、Flyway 初始 schema、TenantContext/@Filter、JWT+RBAC+captcha、`/auth/*`、`/auth/me`;前端 `api.js` + 登录接管 |
| **S2 主数据** | teacher/classroom/class/subject CRUD + 导入 | 4 实体 CRUD + 筛选分页;Excel 导入(POI 沙盒)+ 3 模板 + 校验报告;前端 4 管理页接管 |
| **S3 排考引擎** | OR-Tools + 异步 + 实时 | CP-SAT 模型(H1–H7+S1…)、`schedule_run`、`/scheduling/quick`、STOMP 进度、结果契约;quick 向导接管 |
| **S4 AI + 编辑** | LLM 集成 + 编辑/发布 | `LlmClient`(NL→DSL/总结/修复)+ ai_call_log + provider 切换;edit 拖拽/冲突/AI 修复;task publish+邮件 |
| **S5 统计** | 看板/统计/历史/时间线 + 物化 | 聚合端点 + stats_cache + 事件刷新;导出(xlsx/pdf);4 统计页接管 |
| **S6 超管 + 交付** | 平台面 + 部署 | tenants/accounts/settings/audit/security/announcements;SaaS(K8s)+ 私有化(Compose 离线包);可观测/备份;安全审计回归 |

> 关键路径:S1 多租户与认证是一切前提;S3 排考引擎是最高技术风险,建议 S2 后期即并行预研 OR-Tools 建模与小样本基准。

### 13.2 风险与缺口(逆向工程暴露,需决策)

| # | 风险/缺口 | 影响 | 建议 |
|---|---|---|---|
| R1 | 角色冲突(viewer/teacher 未在文档) | RBAC 边界 | 采本文 4 角色枚举;teacher-query 列入 v1.1 |
| R2 | 排考可行性:容量/教师不足时无解 | 用户挫败 | 导入期即做容量可行性校验(§6.2);无解时输出最小冲突集 |
| R3 | LLM 不可控/超时/成本 | AI 排考可用性 | 求解器主导(D4)已规避;LLM 仅辅助 + 全回退链 + ai_call_log 成本监控 |
| R4 | 信创栈未定(DB/OS/CPU) | 私有化交付 | 默认 PG;预留 DM/Kingbase 方言(§3.21);客户确认后补 migration 变体 |
| R5 | 短信网关未选型 | 短信 2FA | 可插拔 `SmsGateway`;私有化用 noop |
| R6 | 大文件/大校(>1000 教师)Excel | 内存/超时 | EasyExcel SAX 流式 + 沙盒超时;分片导入 |
| R7 | 物化统计与实时一致性 | 看板滞后 | 事件驱动刷新 + 短 TTL;明确"统计 T+秒级"口径 |
| R8 | 数据迁移(老 EXE 历史数据) | 上线 | 视客户存量;预留导入适配器(非本期) |

---

## §14 附录

### A. 完整接口索引(约 110 个端点,按前缀)

```
/api/auth        captcha login 2fa/verify logout refresh me change-password
/api/org         settings(GET/PUT) account(GET/PUT) account/password account/email
                 account/email/verify account/mfa term(GET/PUT)
/api/teachers    GET POST GET/{id} PUT/{id} DELETE/{id} import import/template {id}/schedule
/api/classrooms  …同构… {id}/schedule
/api/classes     …同构… {id}/students {id}/subjects
/api/subjects    …同构… {id}/exams
/api/import      teachers rooms exams validate templates/{t01|t02|t03}
/api/scheduling  quick ai-policy runs/{id} runs/{id}/reschedule runs/{id}/ai-fix
                 nl-policy/parse nl-policy/refine ai/providers ai/providers/active
                 ws:/ws/schedule/{runId}
/api/tasks       GET POST {id}(GET/PATCH/DELETE) {id}/duplicate {id}/publish
                 {id}/supervisors {id}/supervisors/{tid}/remind .../replace
                 {id}/schedule(GET/PUT) {id}/schedule/check-conflicts {id}/log
                 {id}/export {id}/export/all {id}/export/ai-summary.pdf
/api/stats       kpis kpis/trends dashboard/today supervise supervise/export
                 history/teachers history/by-grade history/monthly-trend history/export
                 exams/schedule exams/schedule/day teachers/{id}/assignments
                 teachers/{id}/report/export
/api/admin       dashboard metrics/health reports/weekly notifications
                 tenants(GET/POST) tenants/{id}(GET/PUT) tenants/{id}/suspend|restore tenants/import
                 accounts(GET/POST) accounts/{id}(PUT) accounts/{id}/reset-password|force-logout|disable
                 accounts/{id}/2fa accounts/{id}(DELETE)
                 platform-settings(GET/PUT)
                 audit-logs audit-logs/{id} audit-logs/export
                 security/overview security/threats security/ip-ban security/ip-allowlist
                 backups backups/run
                 announcements(GET/POST) announcements/{id}(PUT)
                 announcements/{id}/publish|push|retract announcements/{id}/read-stats
```

### B. 错误码(节选)

`AUTH_INVALID_CREDENTIALS` 401 · `AUTH_LOCKED` 423 · `AUTH_CAPTCHA_FAILED` 400 · `AUTH_MFA_REQUIRED` 200(带 flag) · `TENANT_SUSPENDED` 403 · `IMPORT_SCHEMA_MISMATCH` 400 · `IMPORT_BLOCKING_ERRORS` 422 · `SCHEDULE_INFEASIBLE` 422(带最小冲突集) · `LLM_TIMEOUT` 504(已回退) · `RATE_LIMITED` 429 · `TENANT_VIOLATION` 403。

### C. 关键派生公式

- 津贴:`allowance = duration_hours × unit_price(org_setting)`
- 完整度(占位):`100 − error×5 − warn×1`
- 软约束总分:`Σ wᵢ · satisfiedRatioᵢ`(阈值 0.65)
- 工作量均衡:`spread = max(load) − min(load)`;`std = stddev(load)`

### D. 与既有文档的引用映射

| 本文 | 权威来源 |
|---|---|
| §3 数据模型 | `02-ARCHITECTURE.md §3`(org_id 行级) + 前端 4 域逆向 |
| §6 Excel | `04-EXCEL-TEMPLATES.md`(严格 3 模板) |
| §7 排考/LLM | `03-AI-WORKFLOW.md`(DSL/权重/回退,主导方改为求解器) |
| §4 安全 | `01-PRD §6` + `02-ARCHITECTURE §7` |
| §10 超管 | 前端 `super.jsx` 控制面逆向(文档仅种子端点) |

---

## §15 第二轮复核彩色标注(2026-06-05)

> 本节是针对现有前端 UI、第一轮 `BACKEND-INTEGRATION-DEVELOPMENT-GUIDE.md` 与本文实施规格的二轮收敛结论。详细量表见 `docs/09-SECOND-ROUND-BACKEND-DOC-REVIEW-ANNOTATED.md`。

### 15.1 颜色图例

- <span style="color:#DC2626;font-weight:700">红色:</span> 必须修正 / 联调阻塞 / 安全高风险。
- <span style="color:#D97706;font-weight:700">橙色:</span> 建议调整 / 路线顺序优化 / 中风险。
- <span style="color:#2563EB;font-weight:700">蓝色:</span> 保留并明确 / 作为主路线强化。
- <span style="color:#059669;font-weight:700">绿色:</span> 可低风险保留 / 前端小改即可。

### 15.2 二轮路线结论

| 项 | 标注 | 二轮判断 | 处理 |
|---|---|---|---|
| 主实施规格 | <span style="color:#2563EB;font-weight:700">保留本文为主</span> | 本文覆盖 Java 21、Spring Boot 3.3、PostgreSQL、OR-Tools、LLM 辅助、混合交付,更接近可实施基线。 | 后续后端开发、OpenAPI、数据库、测试和部署以本文为准。 |
| 第一轮 Node/NestJS 路线 | <span style="color:#DC2626;font-weight:700">不再并行</span> | 第一轮集成指南的 Node/NestJS/Fastify 方案与本文 Java/Spring Boot 路线互斥。 | 第一轮文档降级为架构总览、页面映射和联调参考;冲突处以本文为准。 |
| 外部 API 前缀 | <span style="color:#D97706;font-weight:700">需统一</span> | 当前文档存在 `/api` 与 `/api/v1` 两种口径。 | 推荐外部统一 `/api/v1`;若服务内部保留 `/api`,必须由网关/代理明确改写。 |
| 认证存储 | <span style="color:#DC2626;font-weight:700">生产不可用 localStorage token</span> | `localStorage(jkxt:token)` 只能作为静态原型或开发联调过渡。 | 生产采用 httpOnly session/refresh cookie、短期 access token 或 BFF;前端启动必须调用 `/auth/me`。 |
| 前端 UI | <span style="color:#059669;font-weight:700">不大改</span> | 现有登录、仪表盘、一键排监考、任务页视觉和流程成熟。 | 只新增 API adapter、真实四态、权限/租户恢复、job 进度桥接;不重做视觉体系。 |
| 求解器风险 | <span style="color:#D97706;font-weight:700">预研前移</span> | 排考可行性、性能和无解解释是最高技术风险。 | 在 S2 后期即跑 OR-Tools 小样本基准,不要等 S3 首次验证。 |
| LLM 安全 | <span style="color:#DC2626;font-weight:700">服务端硬规则</span> | Provider key、prompt 原文、教师/学生隐私数据不能进入浏览器。 | 服务端脱敏、schema 校验、prompt 注入防护、审计和成本控制为必做项。 |

### 15.3 前端最小改造边界

二轮复核后,前端只需要以下小范围联调改造:

1. 新增统一 `apiClient` / `src/api.js`,并用兼容层代理现有 `window.JKXT` 快速排监考能力。
2. 登录页接真实 `/api/v1/auth/login` 和 `/api/v1/auth/me`;`jkxt:auth` 仅保留为开发环境首屏 hint。
3. Quick 页面把上传、校验、排考、导出改接后端 job;浏览器本地引擎保留为 demo/fallback。
4. Tasks、Detail、Edit、People、Classrooms、Classes/Subjects、Super 逐页切换为真实 API,补 loading/empty/error/data 四态。
5. 登录纸飞机、熊猫、仪表盘视觉资产和主要布局保持,只修正遮挡、可访问性和 API 状态反馈。

---

*文档结束 · v1.0 · 后端实施基线(Java Spring Boot + PostgreSQL + OR-Tools + LLM 辅助 · 混合交付)*
