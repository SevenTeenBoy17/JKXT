# 排监考系统 v2 — 前端 UI 开发规格文档

**版本**: 1.0  
**日期**: 2026-05-19  
**状态**: 实施基线（D5 eng-plan 终态 → D1 design-greenfield 入口）  
**配套**: `01-PRD.md` / `02-ARCHITECTURE.md` / `03-AI-WORKFLOW.md` / `04-EXCEL-TEMPLATES.md` / `05-IMPLEMENTATION-PLAN.md` / `06-COMMERCIALIZATION-AND-MAINTENANCE.md`

---

## §0 文档使用说明

| 项 | 内容 |
|---|---|
| 受众 | UI 实施者（Claude Design / 前端工程师 / 设计师）|
| 引用素材 | `D:\VB\JKXT\开发材料\` 下编号截图（01-12）|
| 配套文档 | `01-PRD.md` (产品需求) / `02-ARCHITECTURE.md` (架构) / `03-AI-WORKFLOW.md` / `04-EXCEL-TEMPLATES.md` (3 个 Excel 模板) / `05-IMPLEMENTATION-PLAN.md` / `06-COMMERCIALIZATION-AND-MAINTENANCE.md` |
| 视觉权威参考 | `02首页1.png` (Pet Care KPI 结构，**去暗红**) + `06监考人员管理下方界面.png` (Project Tasks timeline) + `12后台可视化界面` (ClickUp / 智联云 dashboard) |
| 视觉非参考 | 老 EXE 截图（03/04/05/07/08/09/10）= **功能蓝图**，**视觉一律重做** |
| 登录页参考 | `01登录页.mp4` + `01登录页截图.png` (World Time 风格) |
| 黄金法则 | airy 专业感 / 反 AI 味 / 数据完整性 / 双气质 / 立体质感 |
| PDF 取舍声明 | PDF 原始规格 100% 实现核心 10 模块；**取舍项**：商业化模块（会员购买/赚钱计划/更多产品/帮助手册 → 移除/降级，per Q3=B + Q13=B）|
| 老 EXE 吸收范围 | **吸收**：功能集 + 字段定义 + 业务流程；**不吸收**：视觉风格 + 侧栏图标 + 排版（per PDF 第 9 条 "可参考结构但视觉重做"）|

---

## §1 产品概述

**排监考系统 v2**——B2B SaaS 多租户、面向高校教务的"一键排监考"工具。

- **角色**: 管理员（本校）+ 超级管理员（注册租户、跨校管理）
- **核心价值**: AI + 算法替代 Excel 手工排监考
- **页面规模**: 10 主页 + 超管后台 + 登录 + 新手引导 = 13 个独立路由
- **技术栈**: Next.js 14 (App Router) + TypeScript + Tailwind + shadcn/ui + Framer Motion + GSAP（登录页）+ TanStack Query + Zustand + Zod + MSW
- **设计基线**: 1440px desktop-first（v1 不做移动端）

---

## §2 设计原则（5 条铁律）

| # | 原则 | 具体含义 |
|---|---|---|
| **R1** | **Airy 专业感** | 浅冷底（#F5F7FA）+ 白卡 + 柔阴影；不暗、不沉、不花哨；舍弃 Pet Care 暗红 |
| **R2** | **反 AI 味** | 禁 Inter / Roboto / Helvetica；禁 Lucide / Heroicons 默认；禁卡套卡套卡；禁通用"暂无数据"；禁默认蓝按钮+灰底+白卡套娃 |
| **R3** | **数据完整性** | 三铁律：禁幻觉数据 / 前后端 schema 单源 / 三态齐备（空-加载-错） |
| **R4** | **双气质** | 登录 = editorial 仪式感（World Time）；主应用 = airy 仪表盘（ClickUp）|
| **R5** | **高级感 > 亲和力** | B2B 教务专业场景，重在效率与可信，不堆插画 emoji 卡通元素 |

---

## §3 设计 Tokens v1.0

### 3.1 颜色（CSS Variables）

```css
:root {
  /* ─── 中性 / 底层 ─── */
  --color-bg-base:        #F5F7FA;   /* 主应用底色 */
  --color-bg-elevated:    #FFFFFF;   /* 卡片 */
  --color-bg-sidebar:     #FFFFFF;   /* 侧栏 */
  --color-bg-topbar:      #FFFFFF;
  --color-bg-overlay:     rgba(15, 23, 42, .48);  /* modal 蒙层 */
  --color-border-soft:    #E4E7EC;
  --color-border-strong:  #CBD5E1;

  /* ─── 文字 ─── */
  --color-text-strong:    #0F172A;   /* 主标题 / 数字 */
  --color-text-medium:    #475569;   /* 正文 */
  --color-text-soft:      #94A3B8;   /* 副信息 / placeholder */
  --color-text-inverse:   #FFFFFF;

  /* ─── 品牌 ─── */
  --color-brand-primary:  #4F46E5;   /* 深邃靛紫，专业感 */
  --color-brand-hover:    #4338CA;
  --color-brand-active:   #3730A3;
  --color-brand-soft:     #EEF2FF;   /* 浅紫底（侧栏 active）*/

  /* ─── 语义 ─── */
  --color-success:        #10B981;
  --color-success-soft:   #DCFCE7;
  --color-warning:        #F59E0B;
  --color-warning-soft:   #FEF3C7;
  --color-danger:         #EF4444;
  --color-danger-soft:    #FEE2E2;
  --color-info:           #0EA5E9;
  --color-info-soft:      #E0F2FE;

  /* ─── KPI 卡 4 色循环（pastel 底 + 深色前景）─── */
  --kpi-1-bg: #EEF2FF;  --kpi-1-fg: #4F46E5;   /* 紫 */
  --kpi-2-bg: #ECFDF5;  --kpi-2-fg: #059669;   /* 绿 */
  --kpi-3-bg: #FFF7ED;  --kpi-3-fg: #D97706;   /* 橙 */
  --kpi-4-bg: #FAF5FF;  --kpi-4-fg: #9333EA;   /* 玫紫 */

  /* ─── Timeline pill 6 色（高饱和，per Project Tasks）─── */
  --pill-1: #6366F1;  /* 紫 */
  --pill-2: #10B981;  /* 绿 */
  --pill-3: #F59E0B;  /* 橙 */
  --pill-4: #EF4444;  /* 红 */
  --pill-5: #06B6D4;  /* 青 */
  --pill-6: #EC4899;  /* 粉 */

  /* ─── Toggle 语义 ─── */
  --toggle-on-general:  #4F46E5;  /* 通用 ON */
  --toggle-on-security: #10B981;  /* 安全相关 ON（如 2FA）*/
  --toggle-off:         #CBD5E1;

  /* ─── 登录页 (sepia editorial) ─── */
  --login-bg-warm:      #E8E5D8;   /* World Time 暖灰底 */
  --login-text-strong:  #1F1F1F;
  --login-text-soft:    #6B6960;
}
```

### 3.2 字体

```css
:root {
  --font-display:  "Geist", "PP Editorial New", "Source Han Sans CN", sans-serif;
  --font-sans:     "Geist", "Source Han Sans CN", system-ui, sans-serif;
  --font-mono:     "Geist Mono", "JetBrains Mono", "Cascadia Code", monospace;
  --font-numeric:  "Geist Mono";    /* KPI 数字独立字体 */
  --font-login-hero: "Boxed Round", "Suisse Int'l Mono", "Geist Mono", monospace;
    /* 登录大数字钟：宽体几何 sans，参考视频 World Time 字体
       推荐自托管：Boxed Round Geometric / Sharp Mono / Pixelpoint
       fallback 链最后回到 Geist Mono */
}

/* 字号 / 行高 */
.text-display { font: 700 56px/72px var(--font-display); letter-spacing: -.02em; }
.text-h1      { font: 700 32px/40px var(--font-sans); }
.text-h2      { font: 600 24px/32px var(--font-sans); }
.text-h3      { font: 600 20px/28px var(--font-sans); }
.text-body    { font: 400 14px/22px var(--font-sans); }
.text-small   { font: 400 12px/18px var(--font-sans); }
.text-numeric { font: 700 28px/36px var(--font-numeric); letter-spacing: -.01em; }
.text-kpi     { font: 700 36px/44px var(--font-numeric); letter-spacing: -.02em; }
.text-hero    { font: 700 120px/120px var(--font-login-hero); }  /* 登录 */
```

**禁用字体**: Inter / Roboto / Arial / Open Sans / Helvetica。Web font 通过 `next/font` 自托管 Geist。

### 3.3 间距（4px base） + 栅格系统

```css
--space-1: 4px;   --space-2: 8px;   --space-3: 12px;  --space-4: 16px;
--space-5: 20px;  --space-6: 24px;  --space-8: 32px;  --space-10: 40px;
--space-12: 48px; --space-16: 64px; --space-20: 80px; --space-24: 96px;

/* 栅格 (per ClickUp / 信息设置图，主内容区) */
--grid-cols:       12;        /* 主内容区 12 列 */
--grid-gutter:     24px;      /* 列间距 */
--grid-margin:     32px;      /* 内容区两侧 padding */
--grid-container:  1376px;    /* 1440 - 32×2 margin */

/* 常用栅格分配 */
/* KPI 行: 4 卡均分 3+3+3+3                                  */
/* Dashboard 主内容: 左 8 列 (timeline) + 右 4 列 (列表)        */
/* 信息设置: 左 7 列表单 + 右 5 列开关组 (信息设置图采用此分配)  */
/* 表格全宽: 12 列                                              */
```

**对齐规则**：
- 同行元素**基线对齐**（baseline），数字与文字 mix 时用 `align-items: baseline`
- 表单 label 右对齐 + input 左对齐时，label 列宽固定 120px
- 卡片间隔 = 1 个 `--space-6` (24px)
- Section 标题与下方内容间距 = `--space-4` (16px)
- Section 间距 = `--space-8` to `--space-12`
- 卡片内边距 = `--space-6` (24px) 标准 / `--space-8` (32px) 大卡

**留白原则（per "airy"）**：
- 数据卡之间留白 ≥ 20px
- 主内容区上下 padding ≥ 24px
- KPI 数字 与上方 label 间距 = `--space-2` (8px) 紧
- 表格行高 ≥ 56px（per ClickUp 行宽松感）

### 3.4 圆角

```css
--radius-sm: 8px;   /* 小按钮、tag */
--radius-md: 12px;  /* 输入框、卡片内组件 */
--radius-lg: 16px;  /* 卡片、modal */
--radius-xl: 20px;  /* 大 KPI 卡 */
--radius-pill: 999px;  /* status pill, timeline pill */
```

### 3.5 阴影 + Elevation 立体系统

> ⚠️ "airy" ≠ "扁平无层次"。本节定义 5 层 elevation，配合表面色 + 阴影 + 边线三要素营造**立体质感**（满足 R30）。

```css
/* ── 基础阴影（per ClickUp 柔和调） ── */
--shadow-xs:    0 1px 2px rgba(15,23,42,.04);
--shadow-card:  0 4px 12px rgba(15,23,42,.04);
--shadow-hover: 0 8px 24px rgba(15,23,42,.08);
--shadow-modal: 0 24px 60px rgba(15,23,42,.16);
--shadow-focus: 0 0 0 3px rgba(79,70,229,.12);  /* 品牌色 focus ring */

/* ── Elevation 5 层（数字越大越凸出） ── */
--elevation-0: none;                                                     /* 平贴底色 */
--elevation-1: 0 1px 3px rgba(15,23,42,.04), 0 1px 2px rgba(15,23,42,.02); /* 表格行 / 输入框 */
--elevation-2: 0 4px 12px rgba(15,23,42,.06), 0 2px 4px rgba(15,23,42,.03); /* 卡片静态 */
--elevation-3: 0 12px 24px rgba(15,23,42,.10), 0 4px 8px rgba(15,23,42,.05); /* 卡片 hover / KPI 卡 */
--elevation-4: 0 24px 48px rgba(15,23,42,.16), 0 8px 16px rgba(15,23,42,.08); /* 浮动卡 / Drawer */
--elevation-5: 0 32px 80px rgba(15,23,42,.24), 0 12px 24px rgba(15,23,42,.12); /* Modal / 命令面板 */

/* ── 立体配方（三要素组合） ── */
/* KPI 卡静态 = elevation-2 + pastel 表面 + 无边线，hover → elevation-3 + translateY(-2px) */
/* 数据表格 = elevation-1 + 白底 + border-soft 1px */
/* Timeline pill = elevation-1 + 高饱和填充 + 内嵌 1px highlight (顶部 inset shadow) */
/* 浮动 FAB = elevation-4 + 品牌色实底 + 落下 24px blur */

/* ── 高光 inset (营造"按钮凸起感") ── */
--inset-highlight-top:    inset 0 1px 0 rgba(255,255,255,.12);
--inset-highlight-bottom: inset 0 -1px 0 rgba(0,0,0,.06);
```

**应用规则**:
- 静态卡片 → elevation-2
- Hover 提升 1 层 → elevation-3 + `transform: translateY(-2px)` (120ms)
- 浮层（Drawer/Popover）→ elevation-4
- 全屏遮罩层 → elevation-5
- **禁止**：4 层以上叠加（卡套卡套卡套卡）；任何无层次差的纯白叠白

### 3.10 登录页视频规格（per `01登录页.mp4` 实测）

> 已用 OpenCV 解析视频元数据 + 抽 8 关键帧验证。详见 `开发材料/_video_frames/`。

| 参数 | 视频参考值 | 我们的实施目标 |
|---|---|---|
| **时长** | 8.87 秒 | 8-12 秒循环 |
| **帧率** | 30 fps | 24-30 fps |
| **分辨率** | 1600×1200 | ≥1920×1080（适配 1440 设计 + 4K 屏）|
| **码率** | ~15 Mbps | ≤8 Mbps (优化加载) |
| **文件大小** | 16 MB | 目标 ≤4 MB（WebM/MP4 双格式）|
| **影像内容** | Venice Beach 棕榈（demo 用）| **校园**（图书馆/教学楼/实验楼/操场，4-6 张轮播）|
| **色调** | Sepia 暖褐 | 同左：sepia 滤镜（saturation -20%, hue +10°）|
| **音轨** | 无 | **必须无音轨**（节省体积 + 避免自动播放被浏览器拦）|
| **循环** | 内容是循环秒数 | 视频 `loop muted autoplay playsinline` |
| **降级** | — | 网速差时降级到静态影像 + 实时 JS 时钟 |

**实施清单**:
- [ ] 录 4-6 段校园短视频（每段 8-12s）
- [ ] 用 ffmpeg 编码 `libvpx-vp9` (WebM) + `libx264` (MP4) 双格式
- [ ] 添加 `<source type="video/webm">` 优先
- [ ] poster 属性指向首帧 sepia 处理后的 JPG（首屏不黑）
- [ ] `prefers-reduced-motion: reduce` 时直接显示 poster 静态图，不播视频

### 3.6 动效

```css
--ease-standard:  cubic-bezier(.4, 0, .2, 1);
--ease-decel:     cubic-bezier(0, 0, .2, 1);
--ease-accel:     cubic-bezier(.4, 0, 1, 1);

--duration-fast:   120ms;   /* hover */
--duration-base:   200ms;   /* 进场、tab 切换 */
--duration-slow:   300ms;   /* modal、drawer */
--duration-page:   400ms;   /* 路由过渡 */
```

**禁用**: bouncy easing（`spring`, `back-out`, `elastic`）—— 一律平滑曲线。

### 3.7 KPI 卡盘

每个 KPI 卡固定结构：
- 背景：4 色循环之一（pastel）
- 头部：图标盒（24px，深色）+ 标题（text-body, medium）
- 主数：text-kpi 36px Geist Mono
- 趋势：text-small + 上/下箭头（语义色）
- 副图：sparkline 或 mini donut 或 mini bar（右侧 30% 区域）

### 3.8 状态 Pill

```
Pill 结构：[圆点] + 文字 + 圆角 pill 容器
- 在线 / 成功 / 已结束(好)：success-soft 底 + success 字 + success 点
- 进行中 / 草稿：info-soft 底 + info 字
- 已暂停 / 离线：bg #F1F5F9 + text-soft 字
- 失败 / 已禁用：danger-soft 底 + danger 字
- 角色（Super Admin / Editor / Viewer）：brand-soft 底 + brand 字
```

---

## §4 全局布局 Shell

### 4.1 登录页布局（独立 layout）

```
┌─────────────────────────────────────────────────┐
│  ◯ Logo (左上)                       学期角(右上) │
│                                                  │
│       09 : 00 : 00                              │
│       (大数字钟 / GSAP 滚动)                      │
│                                                  │
│       天津 09:00  北京 09:00  ...                │
│   ─────────────────────────────────────────     │
│                                       ┌────────┐│
│   背景视频循环 (sepia tint)             │ 登录卡  ││
│   排监考系统 · TIANJIN UNIVERSITY      │ Tab    ││
│                                       │ 表单   ││
│                                       └────────┘│
└─────────────────────────────────────────────────┘
```

- 全屏 video bg (~10s loop, sepia 滤镜, 50% opacity 罩)
- 左上 Logo 简标（不带文字）
- 中上"超大数字时间"，秒数 GSAP `IncrementTextPlugin` 滚动
- 下方副信息条：3-5 个城市时间 + 当前学期 + 日期
- 右下浮卡：登录表单（Tab 切换"管理员 / 超级管理员"）

### 4.2 主应用布局

```
┌───────────────────────────────────────────────────────────────┐
│  ◯ Logo / 系统名     [搜索…⌘K]    🔔 ❓ [Avatar 名字 ▾] [租户▾]│  ← topbar 64px
├──────────────┬────────────────────────────────────────────────┤
│                │  [面包屑]                                       │
│  侧栏 (240px)  │  ┌─────────────────────────────────────────┐   │
│  ───────────  │  │ Page Title           [操作1] [操作2 主]  │   │
│  · 仪表盘 ★    │  └─────────────────────────────────────────┘   │
│  ─ PDF 10 ─    │                                                 │
│  · 一键排监考   │  内容区（卡片 + 表格 + 图表）                    │
│  · 个性化策略AI │                                                 │
│  · 修改监考安排 │                                                 │
│  · 任务总览     │                                                 │
│  · 监考次数统计 │                                                 │
│  · 监考人员管理 │                                                 │
│  · 考场管理     │                                                 │
│  · 班级科目管理▾│                                                 │
│     ├ 班级管理 │                                                 │
│     └ 科目管理 │                                                 │
│  · 历史统计     │                                                 │
│  · 信息设置     │                                                 │
│  ─────────    │                                                 │
│  · 退出登录    │                                                 │
└────────────────┴────────────────────────────────────────────────┘

> 📌 **侧栏构成说明**：
> - **★ 仪表盘**：现代 SaaS 仪表盘范式的添加项（PDF 未列），作为登录后的默认落地页，承担 KPI 总览 + 快速入口角色。
> - **PDF 10 项**：严格按 `01-PRD.md` / PDF 规定的 10 个业务模块（一键排监考 → 信息设置）。
> - 仪表盘 + 业务 10 项 + 退出登录 = 共 12 行可见项；侧栏可折叠为图标。
```

- **侧栏**: 白底 + 项 active 时 brand-soft 底 + 左 3px 紫竖条 + 文字加粗（per 12后台可视化）
- **侧栏图标**: 线性 2px stroke + 圆头（自定义集，禁 Lucide 默认）
- **侧栏可折叠**: 收起后宽 72px，仅图标
- **顶栏**: 白底 + 底分隔线 + 搜索 `⌘K` 唤醒命令面板
- **租户切换器**: 顶栏右侧下拉，显示当前学校 + 切换列表（仅多租户用户）
- **当前学期 badge**: 顶栏左侧（搜索旁），点击进入设置→学年覆盖

### 4.3 超管布局（vs 主应用 differences）

- 同主应用 Shell
- Logo 换 "超级管理控制台" 副标
- 侧栏内容完全不同：仪表盘 / 单位管理 / 注册单位 / 管理员账号 / 系统统计 / 公告发布 / 操作审计 / 安全中心
- 主色调略加深（如 `--color-brand-active`），物理上区分权限层级
- 顶栏多个"返回管理员视图"按钮（如超管也有自己学校）

### 4.4 响应式（desktop-first）

| 断点 | 行为 |
|---|---|
| ≥1920 | KPI 5 列，timeline 完全展开 |
| 1440-1919（**设计基线**）| KPI 4 列 |
| 1280-1439 | KPI 4 列，侧栏可手动折叠 |
| 1024-1279 | KPI 2 列，侧栏默认折叠 |
| <1024 | 显示"建议使用桌面浏览器"，移动版 v2 |

---

## §5 组件库清单（System Primitives）

> 所有组件用 shadcn/ui 作基底，覆盖 tokens，导出到 `components/ui/`。业务组件在 `components/features/`。

### 5.1 通用
- `Button` (variants: primary/secondary/ghost/danger/link; sizes: sm/md/lg; loading state)
- `Input` (text/number/password; with prefix/suffix icon; error state with red border + helper text)
- `Select` / `Combobox` (single/multi; searchable)
- `Toggle` (general blue / security green per 信息设置 图)
- `Checkbox` / `RadioGroup`
- `Tabs` (蓝色下划线 + 200ms slide indicator)
- `Modal` / `Drawer` / `Popover` / `Tooltip`
- `DropdownMenu`

### 5.2 数据
- `DataSlot<T>` — 强制三态包装（loading / empty / error / data）
- `Table` (sortable, selectable, sticky header, row hover)
- `Pagination` (`10条/页` + `< 1 2 3 >`)
- `FilterBar` (横向筛选条 + 总数显示 + 清空)
- `SearchInput` (debounced + clear button + 快捷键提示)

### 5.3 反馈
- `EmptyState` (大插画 + 标题 + 副文 + 主 CTA + 副 CTA)
- `Skeleton` (静态，不旋转；matches 数据结构形状)
- `ErrorState` (图标 + 原因 + 重试 + 反馈)
- `Toast` (success/info/warning/error；右上角；自动消失 4s)
- `Banner` (顶部条；如 demo data 提示)
- `ConfirmDialog` (危险操作二次确认；红色主按钮)

### 5.4 业务
- `KPICard` (props: variant 1/2/3/4 + icon + label + value + delta + miniChart)
- `StatusPill` (语义色 + 圆点)
- `Avatar` (尺寸 24/32/40/56; 含 fallback 字母)
- `AvatarGroup` (堆叠头像 + "+N")
- `Timeline` (横向 calendar 网格 + pill 节点；可拖拽编辑模式)
- `ScheduleGrid` (双轴：日期 × 考场，cell = 一场监考 pill)
- `SemesterBadge` (当前学期自动判断 + 覆盖标识)
- `TenantSwitcher` (顶栏下拉)
- `ActionGroup` (`查看 编辑 禁用` 风格三按钮，small ghost)

### 5.5 表单
- `FormField` (label + required marker + input + helper + error)
- `FormSection` (子标题 + 灰底分隔)
- `ExcelUploader` (拖拽 + 点击 + 文件名预览 + 模板下载链接；3 模板见 `04-EXCEL-TEMPLATES.md`)
- `ExcelPreview` (parse 后表格预览 + 行级校验高亮)
- `LogoUploader` (per 信息设置 图：虚线框 + 云图标 + 格式说明)
- `AddressPicker` (国/省/市/区 4 级级联 + 详细地址 textarea；级联用中国行政区划数据 `china-division`)
- `PasswordField` (含强度提示 + 显示/隐藏 toggle + zxcvbn 评分)
- `EmailVerifyField` (邮箱输入 + "发送验证码" 按钮带 60s 倒计时 + 6 位验证码输入 + 成功 ✓ 标记)
- `CaptchaField` (图形验证码 + 刷新；登录页用；可切换为滑块验证)

### 5.6 导航
- `Sidebar` (collapsible 240px ↔ 72px; with nested groups)
- `SidebarItem` (icon + label + active state per §4.2)
- `Topbar` (logo + search + 学期 badge + 通知 + avatar + tenant switcher)
- `Breadcrumb` (含 home icon + 分隔符 `/`)
- `CommandPalette` (`⌘K` / `Ctrl+K` 唤起；模糊搜索 3 类内容：① 路由跳转 ② 快捷动作 ③ 最近访问；含分组 + 高亮匹配 + 键盘导航 ↑↓⏎ + Esc 关闭)

### 5.7 AI / 业务专属
- `AISummaryCard` (Markdown render + 复制按钮 + provider 标签)
- `ProviderBadge` (当前 AI provider 名 + icon)
- `PolicyToggles` (6 个策略开关 + 1 个数值输入 + 1 个自由文本)
- `NaturalLangTextarea` (大输入框 + 字数 + 示例提示)
- `AIInvokeButton` (按钮态：idle → calling → done；带 spinner + 进度文案)

### 5.8 图表（按 PDF 规定 + 新图增强）
- `BarChartCard` (任务总览 / 监考次数 — recharts 柱状)
- `PieChartCard` (历史统计 — recharts 饼)
- `LineChartCard` (历史统计趋势 — recharts 折线)
- `DonutKPI` (超管首页"服务器状态"小环 + 中心数字)
- `Sparkline` (KPI 卡底部迷你趋势线)

---

## §6 关键交互模式

| 场景 | 模式 |
|---|---|
| 表格行 hover | 整行 bg → #F8FAFC + transform translateY(-1px) + shadow-hover，duration-fast |
| 表格行 select | checkbox 选中 + 行 brand-soft 底 + 顶部出现"已选 N 项 [操作]" |
| Toggle 切换 | 圆点滑动 200ms ease-standard + 颜色渐变 + 触觉反馈（移动）|
| Tab 切换 | 蓝下划线 slide 200ms + 内容 fade 150ms |
| Modal 打开 | overlay fade 200ms + dialog scale(.96 → 1) + fade 250ms |
| Drawer 打开 | 右侧 slide-in 250ms ease-decel |
| 拖拽（修改安排）| pill `cursor:grab` → `:grabbing` ghost 0.5 opacity + 落区高亮 + 冲突 pill 红边震动 |
| Excel 上传 | drop zone hover bg 变 brand-soft → 拖入文件 brand-primary 虚线 → 上传中进度条 → 解析后展开预览 |
| AI 调用 | 按钮态 idle → spinner + "AI 正在分析..." → 进度文案分阶段（读取数据/应用策略/生成结果）→ 平滑过渡到结果 |
| 表单 dirty | 任何编辑后顶栏出现"未保存"chip + 离开页面前 confirm dialog |
| 删除二次确认 | 红色主按钮 modal + 输入"DELETE"或对象名确认（per 危险等级）|
| 命令面板 ⌘K | 顶部居中 modal + 即时搜索（路由 + 操作 + 最近）|

---

## §7 页面详细规格（13 页）

> 每页规格：路由 / 角色 / Schema / 操作 / 状态 / 关键组件 / 视觉映射 / 拓展点

### 📄 P0 登录页（基于视频实际拆解）

> **视频实测**: 8.87s 循环 / 30fps / 1600×1200。布局为**上半屏数字钟 + 下半屏影像**的二段式结构（**非全屏视频背景**），秒数**实时滚动**而非一次性入场。

| 项 | 内容 |
|---|---|
| 路由 | `/login` |
| 角色 | 公开 |
| Schema | `{ username: string, password: string, captcha?: string, role: 'admin' \| 'super' }` |
| 操作 | 登录 / 忘记密码 / 切换角色 Tab |
| 状态 | 输入 / 验证中 / 错误 / 成功跳转 |

**布局（二段式，非全屏 video bg）**:

```
┌──────────────────────────────────────────────────────────────┐
│  ▲ 排监考系统                            [⊞ 角色切换 / 帮助]   │  ← 顶部细条 (h~64px)
│                                                                │
│   hours        minutes        seconds                          │  ← 小灰字标签
│  ┌──────┐    ┌──────┐    ┌──┐                               │
│  │ 0 9  │  : │ 0 0  │  : │00│                                │  ← 巨型时钟 (上半屏 ~55%)
│  └──────┘    └──────┘    └──┘    暖灰底 #E8E5D8              │
│                                                                │
│     2025-2026 第二学期 ·  当前  09:00  ·  早 08:00  ·         │
│                            晚 21:00  ·  教学楼一 / 实验楼      │  ← 5 段教务相关 token
│                                                                │
├──────────────────────────────────────────────────────────────┤
│                                                                │
│   (背景影像：校园/图书馆/教学楼，sepia tint 处理)              │  ← 下半屏 ~45% 影像
│                                                                │
│   天津大学                              ┌──────────────────┐  │
│   TIANJIN UNIVERSITY                    │   登录            │  │
│                                          │  [管理员 / 超管] │  │  ← 浮卡 (覆盖右下角影像)
│                                          │   👤 账号        │  │     elevation-4
│                          Sunday,        │   🔒 密码        │  │
│                          April 27       │   [   登 录   ]  │  │
│                                          │   忘记密码 ↗     │  │
│                                          └──────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

| 区域 | 详细 |
|---|---|
| **顶部条** (64px) | 左：`▲ 排监考系统` logo（三角符 + 系统名）；右：角色切换 chip + 帮助 ❓ |
| **数字钟** (上半屏 55%) | 三段 `HH : MM : SS`；hours/minutes 极大尺寸（display 144px）；seconds 略小（96px）；分隔符 `:` 是装饰方点；上方小灰字标签 `hours/minutes/seconds`；**秒数实时滚动**，分/时按真实时间更新 |
| **副信息条** | 5 段教务相关 token：当前学期 / 当前时刻 / 早场监考开始时间 / 晚场结束时间 / 当前可用考场提示 |
| **下半屏影像** (~45%) | 背景全宽影像 + 统一 sepia 滤镜（饱和度 -20%、暖色偏 +10°）；**禁威尼斯海滩**——v1 用**校园/图书馆/教学楼/实验室**学术氛围影像；可循环切换 4-6 张 |
| **影像左下** | `天津大学` (display 大字) + `TIANJIN UNIVERSITY` (Geist Mono 小字)，描边 / 半透明白背 |
| **影像右下日期** | `Sunday, April 27` 风格的卡片（半透明白底 + 暗字）|
| **登录卡** (浮于影像右下) | 宽 380px / elevation-4 / 圆角 16px / 内 padding 32px；包含 Tab 切角色（管理员/超管）+ 账号 + 密码 + `<CaptchaField>` + 登录按钮（品牌色实心）+ 忘记密码链接 |

| 关键组件 | `<LoginHero>` (二段式 layout) + `<RealtimeClock>` (秒数滚动) + `<LoginCard>` (右下浮卡) + `<RoleTabs>` + `<EduInfoStrip>` (5 token 副信息条) + `<HeroImageRotator>` (sepia 滤镜的校园影像轮播) |
|---|---|
| **视觉映射** | 严格匹配视频 World Time 风格但替换为教务语义（学校名/学期/教务 token）|
| **拓展点** | 第三方 SSO（v2）；影像可后台配置上传 |

### 🏠 P1 仪表盘 / Dashboard

| 项 | 内容 |
|---|---|
| 路由 | `/[tenant]/dashboard` |
| 角色 | 管理员 |
| Schema | `DashboardSummary { activeTasks, totalSupervisors, upcomingExams[], recentTasks[], chartData }` |
| 操作 | 进入子模块 + 快速新建任务 |
| 关键组件 | 4× `<KPICard>` (任务进行中 / 累计排班 / 教师数 / 考场数) + `<UpcomingExamsTimeline>` (近 7 天 Project Tasks 风格) + `<RecentTasksTable>` + `<QuickActionsCard>` |
| 视觉映射 | ClickUp 主框架 + KPI 用 4 色循环 + Timeline 用 06下方 风格 |
| 空状态 | 大插画"还没有任何监考任务" + "新建第一个任务" 主 CTA + "导入历史数据" 副 CTA |
| 拓展点 | KPI 可配置；timeline 可切换月/周视图 |

### ⚡ P2 一键排监考

| 项 | 内容 |
|---|---|
| 路由 | `/[tenant]/schedule/quick` |
| 角色 | 管理员 |
| Schema | `QuickInput { examTable: File, teacherTable: File, classroomTable: File, semester }` → `Result { mainTable: Url, subTables: Url[3], aiSummary: string }` |
| Excel 模板 | 3 个模板详见 [`04-EXCEL-TEMPLATES.md`](./04-EXCEL-TEMPLATES.md)：① 教师安排示例表 ② 考场信息示例表 ③ 考试安排示例表 |
| 操作 | 4 步：上传 → 校验 → 执行 → 结果 |
| 关键组件 | `<StepIndicator>` (4 圆点 + 当前态) + `<ExcelUploader>`×3 (并列 + 各带模板下载链接) + `<ValidationReport>` (错误行高亮 + 下载校验报告) + `<AIInvokeButton>` + `<ResultPanel>` (4 文件下载 + `<AISummaryCard>` + `<ScheduleTimeline>` 可视化) |
| 视觉映射 | 步骤指示器走 ClickUp + 结果 timeline 走 06下方 |
| 拓展点 | 第 4 个可选 Excel（如校历）|

### 🤖 P3 个性化策略 AI 监考

| 项 | 内容 |
|---|---|
| 路由 | `/[tenant]/schedule/ai-policy` |
| Schema | `AIPolicyInput { teacherTable, policies: { equalDuration: bool, classMasterMax: number=2, classMasterLessThan: bool, lateClassEarly: bool, ageThreshold: number\|null, crossGradeHigher: bool }, naturalLangAddendum: string }` |
| 操作 | 上传 + 勾选 + 输入 → AI 调用 → 导出 |
| 关键组件 | `<ExcelUploader>` + `<PolicyToggles>` (6 项，1 项含数值输入框，含 tooltip 解释) + `<NaturalLangTextarea>` (带示例 chip 快速插入) + `<ProviderBadge>` (per Q10=C, 显示当前 mock provider) + `<AIInvokeButton>` + `<ResultDownload>` + `<AISummaryCard>` |
| 视觉映射 | ClickUp 表单 + AI 摘要 Markdown |
| 拓展点 | 策略后台可配置；provider 可挂 OpenAI/DeepSeek/Qwen/Mock |

### ✏️ P4 修改监考安排

| 项 | 内容 |
|---|---|
| 路由 | `/[tenant]/schedule/edit/[taskId]` |
| Schema | `Schedule { taskId, days[], rooms[], assignments[{examId, roomId, supervisors[], time}] }` |
| 操作 | 拖拽换人 / 单点编辑 / 批量替换 / 撤销 / 保存草稿 / 发布 |
| 关键组件 | `<ScheduleGrid editable>` (双轴可拖拽) + `<ConflictBanner>` + `<SupervisorPicker>` (modal) + `<UndoRedoBar>` (顶部) + `<SaveDraftFab>` (右下) |
| 视觉映射 | Project Tasks timeline 编辑态 + 拖拽 ghost + 冲突红边 |
| 拓展点 | 协同编辑 v2 |

### 📋 P5 任务总览

| 项 | 内容 |
|---|---|
| 路由 | `/[tenant]/tasks` |
| Schema | `ExamTask { id, name, grade, days, subjectCount, roomCount, supervisorCount, status, createdAt, customFields }` |
| 操作 | 新建 / 查看 / 编辑 / 导出 / 复制 / 删除 |
| 关键组件 | `<FilterBar>` (状态/年级/时间区间) + `<TaskTable>` + `<StatusPill>` + `<BarChartCard>` (PDF 规定) + `<NewTaskFAB>` |
| 视觉映射 | ClickUp 列表 + 柱图 KPI 顶部 |
| 空状态 | "暂无任务" + "新建任务" 主 CTA |

### 📊 P6 监考次数统计

| 路由 | `/[tenant]/stats/supervise` |
|---|---|
| Schema | `SuperviseStats { byTeacher[], bySubject[], byGrade[], aggregate, period }` |
| 操作 | 切换维度 / 时段 / 导出 |
| 关键组件 | `<DimensionTabs>` (教师/学科/年级) + `<BarChartCard>` + `<DataTable>` + `<ExportButton>` |
| 视觉映射 | ClickUp 顶部 tab + 柱状图 |

### 👥 P7 监考人员管理（双层）

| 路由 | `/[tenant]/people` |
|---|---|
| Schema | `Teacher { id, code, name, grade, subjects[], isHomeroom, gender?, age?, customFields }` |
| 操作 | 导入 / 新增 / 编辑 / 年级管理 / 修改年级 (批量) / 删除 (批量) |
| 关键组件 | **上半屏**：`<FilterBar>` (年级/科目/总人数) + `<TeacherTable>` (可多选 + 内联编辑); **下半屏**：`<TeacherScheduleTimeline>` (每教师近 30 天监考排班) |
| 视觉映射 | 上 ClickUp 列表 + 下 06下方 timeline |
| 拓展点 | 年龄/学历/电话/教龄等 customFields 后台启用 |

### 🏫 P8 考场管理

| 路由 | `/[tenant]/classrooms` |
|---|---|
| Schema | `Classroom { id, code, name, building, floor, capacity, type: 'normal'\|'special', customFields }` |
| 操作 | 导入 / 新增 / 编辑 / 删除 |
| 关键组件 | `<FilterBar>` + `<ClassroomTable>` + `<RoomDetailModal>` (含占用日历) |

### 🏷️ P9 班级科目管理（含 2 子页）

| 路由 | `/[tenant]/classes-subjects/{classes\|subjects}` |
|---|---|
| Schema | `Class { id, name, studentCount, subjects[], customFields }` + `Subject { id, name, customFields }` |
| 关键组件 | `<SubTabs>` (班级/科目) + `<ClassTable>` / `<SubjectTable>` + `<ImportModal>` |

### 📈 P10 历史统计

| 路由 | `/[tenant]/stats/history` (主表) + `/[tenant]/stats/history/aggregate` (子页 Q14=B) |
|---|---|
| Schema 主表 | `TeacherHistory { code, name, subjects[], grade, count, duration, totalAllowance, details[] }` |
| Schema 子页 | `Aggregate { byMonth: pie[], byTrend: line[] }` |
| 关键组件 主表 | `<FilterBar>` + `<HistoryTable>` (金额列右对齐) + `<DetailModal>` (per 教师明细) + `<ExportButton>` + 子页入口卡片 |
| 关键组件 子页 | `<PieChartCard>` + `<LineChartCard>` + 学期切换器 |

### ⚙️ P11 信息设置（**普通管理员版** — 5 个逻辑模块）

> ⚠️ 本轮新约束：普通管理员**只见**这 5 个模块，不见超管的完整系统设置（超管完整版在 P12 ④）。

| 路由 | `/[tenant]/settings` |
|---|---|
| Schema | `{ tenant: { name }, region: { country, province, city, district, detailAddress }, security: { newPassword, confirmPassword }, contact: { verifiedEmail } }` |
| 5 模块 | ① 单位（单位名称）② 地区（国/省/市/区+详细地址）③ 修改密码（新密码）④ 确认密码 ⑤ 验证邮箱 |
| 表单字段数 | 9 个 input（单位 1 + 级联 4 + 详细 1 + 新密码 1 + 确认 1 + 邮箱 1）|
| 操作 | 保存修改 / 取消 |
| 关键组件 | `<FormSection>` ×5 包裹模块 + 内部：`<FormField>` (单位名称) + `<AddressPicker>` (4 级级联 + 详细) + `<PasswordField>` × 2 + `<EmailVerifyField>` |
| 视觉映射 | 信息设置图 form 范式（左表单单栏；**不要**右侧全局开关组——那是超管页才有）|
| 拓展点 | v2 加通知偏好；customFields 由超管在 P12 ④ 系统设置中定义后此处自动渲染 |

### 👑 P12 超管后台（**完整版** — 含原信息设置全部功能）

> 本页吸收"超管控制台 + 信息设置完整版"两张图的所有功能。

| 路由 | `/admin/super` (root) + 7 子路由（见下）|
|---|---|
| 角色 | 超级管理员（默认凭证 `admin` / `Admin123!!!`，首次登录强制改密）|
| 视觉映射 | 超管 dashboard 图（KPI 4 卡 + 权限列表 + 操作日志）+ 信息设置图（基础参数 + 全局开关）|
| 拓展点 | 多级超管 / 操作回放 / 跨租户数据看板 |

**7 个子页 Schema 与关键组件**：

| # | 路由 | 子页名 | Schema 核心字段 | 关键组件 |
|---|---|---|---|---|
| ① | `/admin/super` | 超管仪表盘 | `{ serverStatus, adminCount, roleGroupCount, securityAlerts, recentLogs[] }` | 4× `<KPICard>` + `<Sparkline>` + `<DonutKPI>` + `<OperationLogTimeline>` |
| ② | `/admin/super/tenants` | 租户(单位)管理 | `Tenant { id, slug, name, address, contactName, contactPhone, adminCount, plan, createdAt, status: 'active'\|'suspended', customFields }` | `<TenantTable>` + `<NewTenantWizard>` + `<TenantDetailDrawer>` |
| ③ | `/admin/super/accounts` | 管理员账号 | `Admin { id, name, email, department, role: 'super'\|'admin'\|'viewer', tenantId, status: 'online'\|'offline', lastLoginAt, twoFactorEnabled }` | `<AdminTable>` + `<RoleChip>` + `<OnlineDot>` + `<RoleAssignModal>` |
| ④ | `/admin/super/system-settings` | 系统设置 | `SystemSettings { basics: { systemName, domain, contactEmail, timezone, description, logoUrl }, switches: { emailNotif, twoFactor, guestAccess, autoBackup, maintenanceReminder } }` | `<FormField>` × 6 + `<LogoUploader>` + 5× `<Toggle>` 含图标盒（per 信息设置图）|
| ⑤ | `/admin/super/audit` | 审计日志 | `AuditEntry { id, actor, action, target, severity: 'info'\|'warn'\|'high', timestamp, ip, payload }` | `<FilterBar>`(时间/操作类型/严重度) + `<AuditTable>` + `<EntryDetailModal>` |
| ⑥ | `/admin/super/security` | 安全中心 | `SecurityOverview { failedLogins[], suspiciousIps[], lastBackupAt, securityScore: 0-100 }` | `<KPICard>` × 2 + `<SecurityScoreRing>` + `<ThreatTable>` |
| ⑦ | `/admin/super/announcements` | 公告发布 | `Announcement { id, title, content, severity, targetTenants: 'all'\|string[], publishAt, expireAt, status }` | `<AnnouncementEditor>` (Markdown) + `<PublishScheduler>` + `<AnnouncementList>` |

### 🎓 P13 新手引导（首次登录触发，per Q13=B）

| 项 | 内容 |
|---|---|
| 触发 | 首次登录 / `user.onboardingCompleted === false` / 顶栏 ❓ 主动触发 |
| 形态 | 全屏 modal + 步骤指示 + 多步带视频/图片 + 可跳过 |
| 内容 | 吸收原帮助手册 11 篇教程的核心：① 系统总览 ② 准备 3 个 Excel ③ 一键排监考流程 ④ AI 策略用法 ⑤ 修改监考安排 ⑥ 性别均衡 / 教师连排 / 科目禁排 ⑦ 完成（送到 Dashboard）|
| 关键组件 | `<OnboardingModal>` + `<StepDots>` + `<MediaSlot>` (img/video) + `<SkipButton>` + `<FinishButton>` |
| 数据 | localStorage + 后端 user.onboardingCompleted |

---

## §8 动效规范

### 8.1 登录页 GSAP timeline（基于视频实测：二段式 + 实时秒数滚动）

视频拆解：8.87s/30fps/1600×1200，秒数**持续滚动**（即真实时间驱动），不是一次性入场动画。

```js
// === 入场 timeline（~3 秒，仅播一次） ===
const tl = gsap.timeline({ defaults: { ease: 'power3.out' } });

tl.from('.hero-image', { 
    opacity: 0, scale: 1.04, duration: 1.0 
  })
  .from('.top-bar', { 
    y: -20, opacity: 0, duration: 0.4 
  }, '-=0.7')
  .from('.clock-label', {           // hours/minutes/seconds 小灰字标签
    opacity: 0, y: 8, stagger: 0.08, duration: 0.3 
  }, '-=0.4')
  .from('.clock-digit', {            // 大数字本体
    y: 60, opacity: 0, stagger: 0.12, duration: 0.5, ease: 'power4.out' 
  }, '-=0.2')
  .from('.edu-info-strip > *', {     // 5 段教务 token
    y: 12, opacity: 0, stagger: 0.06, duration: 0.35 
  }, '-=0.3')
  .from('.image-caption-tianjin', {  // 影像左下校名
    x: -30, opacity: 0, duration: 0.5 
  }, '-=0.4')
  .from('.image-caption-date', {     // 影像右下日期卡
    x: 30, opacity: 0, duration: 0.5 
  }, '-=0.5')
  .from('.login-card', {             // 登录浮卡最后入场
    y: 40, opacity: 0, scale: 0.96, duration: 0.55, ease: 'back.out(1.15)' 
  }, '-=0.2');
// ⚠️ 登录页是唯一允许 back-out easing 的场景（仪式感）

// === 持续效果：秒数实时滚动（核心动效） ===
// 使用 setInterval(1000) 更新 .clock-seconds 内容，配合 CSS 数字滚轮:
//   .clock-seconds 内 2 个 <span> 表示个位/十位
//   每秒新值时：从 (-100%) translate 到 (0)，120ms cubic-bezier(.4,0,.2,1)
//   旧值同时 (0) → (100%) 滑出
// 分钟 / 小时 同理但仅每分/每小时触发一次
//
// 推荐用 odometer.js 或自实现 odometer

// === 持续效果：影像 sepia 微缩放 (Ken Burns) ===
gsap.to('.hero-image', {
  scale: 1.02, duration: 12, repeat: -1, yoyo: true, ease: 'sine.inOut'
});

// === 持续效果：影像 6s 轮播切换 ===
//   4-6 张校园影像循环；切换时 800ms crossfade
```

**性能注意**:
- 入场 timeline 仅播 1 次 (~3s)，后续仅秒数滚动消耗 (~1Hz)
- Ken Burns 缩放 transform GPU 加速，不掉帧
- 影像总尺寸 ≤ 800KB 压缩后；用 `next/image` 自动 WebP

### 8.2 主应用通用

| 事件 | 动效 | duration | ease |
|---|---|---|---|
| 页面进场 | fade + translateY(8px → 0) | 200ms | standard |
| 路由切换 | content fade-out 100ms + new content fade-in 200ms | 300ms total | standard |
| Hover 卡片 | translateY(-1px) + shadow-hover | 120ms | standard |
| Hover 按钮 | bg slightly darker + scale(1.02) | 120ms | standard |
| Click 按钮 | scale(.97) → 1 | 80ms | accel |
| Modal 进场 | overlay fade + dialog scale(.96 → 1) + fade | 250ms | decel |
| Drawer | translateX(100% → 0) | 250ms | decel |
| Toast | slide-in from top + fade | 200ms 进, 4s 持, 200ms 出 | decel |
| Tab indicator | translateX | 200ms | standard |
| Skeleton | bg gradient sweep | 1.5s loop | linear |

### 8.3 禁用动效

- 任何 `bounce/elastic/back-out`（登录除外）
- 任何 >400ms 的元素级动效（导致卡顿感）
- 任何 < 80ms 的 hover（用户感受不到，浪费 CPU）

---

## §9 三态模式库

### 9.1 真无数据（初次进入）

```
┌─────────────────────────────────────┐
│                                      │
│         [大插画 240px]                │
│      （每页专属，非通用）              │
│                                      │
│        还没有任何考试任务              │
│   开始你的第一次智能监考排班              │
│                                      │
│   [新建任务（主）]  [导入数据（副）]    │
│                                      │
└─────────────────────────────────────┘
```

### 9.2 筛选后空

```
┌─────────────────────────────────────┐
│       🔍  没有匹配的结果              │
│   试试调整筛选条件或清空筛选            │
│           [清空筛选]                  │
└─────────────────────────────────────┘
```

### 9.3 加载（骨架）

- 表格：行 × 5 灰条 + 列对齐
- KPI 卡：标题灰条 + 数字灰条 + 趋势灰条
- 图表：图标 + "正在加载..." 文案 + 进度条（不旋转）

### 9.4 错误重试

```
┌─────────────────────────────────────┐
│       ⚠️  无法加载数据                │
│   原因：网络超时（503 Gateway）        │
│      [重试]  [反馈问题]                │
└─────────────────────────────────────┘
```

### 9.5 Demo Data 横幅

```
🟡 当前显示演示数据。真实数据请联系管理员或导入。     [了解更多]
```

---

## §10 系统逻辑（per PDF 关键规则）

> 这些是必须正确实现的系统级业务逻辑，PDF 已明文规定。

### 10.1 学期自动判断

```ts
// utils/semester.ts
function detectCurrentSemester(now: Date = new Date()): SemesterId {
  const year = now.getFullYear();
  const month = now.getMonth() + 1;   // 1-12
  const day = now.getDate();
  const isInSpring = (month > 3 || (month === 3 && day >= 1)) 
                  && (month < 7 || (month === 7 && day <= 15));
  // 3.1 ~ 7.15 视为上一学年第二学期
  if (isInSpring) {
    return { 
      academicYear: `${year - 1}-${year}`, 
      term: 2, 
      label: `${year - 1}-${year} 学年第二学期` 
    };
  }
  // 其他时段视为本学年第一学期
  return { 
    academicYear: `${year}-${year + 1}`, 
    term: 1, 
    label: `${year}-${year + 1} 学年第一学期` 
  };
}
```

- 应用启动时调用 → 写入 `<SemesterContext>`
- 顶栏 `<SemesterBadge>` 显示 `label`
- 用户可在 **P11 信息设置 → 学年覆盖** 手动覆盖（落到 `user.semesterOverride`）
- 覆盖时 badge 显示 `🔒 已覆盖` 角标

### 10.2 角色与凭证

| 角色 | 默认凭证（首次部署）| 权限 |
|---|---|---|
| 超级管理员 | `admin` / `Admin123!!!` | 注册租户、管理所有管理员、跨校查看 |
| 管理员 | 由超管创建 / 单位邀请 | 本租户内全权 |

> ⚠️ 首次部署后 **必须强制超管改密码**（首次登录拦截 → 修改密码 modal）

### 10.3 路由守卫

```ts
// middleware.ts
- /login              → 公开
- /[tenant]/*         → 需登录 + role ∈ {admin, super}
- /admin/super/*      → 需登录 + role === 'super'
- 403 → /login
- 401 → /login?next=...
```

### 10.4 多租户路由

- URL 含 `[tenant]` slug → 来自 user.tenantId 或顶栏切换器
- 每个 API 请求自动加 `X-Tenant-Id` header
- 服务端二次校验（不信任前端 header）
- 超管可访问任何 tenant，路由前缀仍带 slug 便于审计

---

## §11 响应式断点（desktop-first）

详 §4.4。v1 主做 1440px 基线，1280-1919 全适配，<1024 引导桌面。

---

## §12 可访问性（WCAG 2.1 AA 基线）

- 所有交互元素 keyboard tab 可达 + visible focus ring（focus-ring shadow）
- 表单 label 关联 input via `htmlFor`
- 图标按钮含 `aria-label`
- 表格行可键盘选中（space）
- Modal 触发 focus trap + Esc 关闭 + 返回焦点到触发元素
- 颜色对比 ≥ 4.5:1（已在 tokens 设计时考虑）
- Screen reader 友好的 status 公告（aria-live）

---

## §13 数据契约 + Mock 规范

### 13.1 Zod Schema 单源

```ts
// packages/schemas/teacher.ts
export const teacherSchema = z.object({
  id: z.string().uuid(),
  code: z.string().min(1),
  name: z.string().min(1),
  grade: z.string(),
  subjects: z.array(z.string()),
  isHomeroom: z.boolean(),
  gender: z.enum(['male', 'female']).optional(),
  age: z.number().int().min(20).max(80).optional(),
  customFields: z.record(z.unknown()).default({}),
});
export type Teacher = z.infer<typeof teacherSchema>;
```

前端 mock + 后端实现 + 客户端校验全部用这一份。

### 13.2 MSW Mock 层

```ts
// mocks/handlers/teachers.ts
rest.get('/api/v1/:tenant/teachers', (req, res, ctx) => {
  if (DEMO_MODE === 'empty') return res(ctx.json({ data: [], total: 0, meta: { isDemoData: false }}));
  return res(ctx.json({
    data: [{ id: 'demo-1', code: 'T001', name: '张老师', _demo: true, ... }],
    total: 1,
    meta: { isDemoData: true, message: '演示数据' }
  }));
});
```

### 13.3 envelope 标准

```ts
type ApiResponse<T> = {
  data: T;
  total?: number;
  meta?: { isDemoData?: boolean; message?: string };
  error?: { code: string; message: string; details?: unknown };
};
```

### 13.4 三态包装强制

```tsx
<DataSlot query={useTeachers()}
  loading={<TableSkeleton rows={5} cols={7} />}
  empty={<EmptyTeachers onImport={...} onCreate={...} />}
  error={(err, retry) => <ErrorState error={err} onRetry={retry} />}
>
  {(data, meta) => (
    <>
      {meta?.isDemoData && <DemoBanner />}
      <TeacherTable rows={data} />
    </>
  )}
</DataSlot>
```

**禁止裸渲染数据** —— ESLint 规则强制 `<Table>` 不能直接接收来自 `useQuery` 的 data。

---

## §14 开发执行清单（按 Sprint）

| Sprint | 内容 | 验收 |
|---|---|---|
| **S1 (1周)** | 项目脚手架 + tokens + 全局布局 shell + Login 页 + 路由守卫 | 登录能跳到空 Dashboard |
| **S2 (1周)** | Dashboard + 任务总览 + 通用组件库（DataSlot/Table/KPI/EmptyState/Skeleton/Error）| 全空数据态可演示 |
| **S3 (1周)** | 监考人员 + 考场 + 班级科目 + ExcelUploader / FilterBar | 3 个管理页 CRUD 通 |
| **S4 (1.5周)** | 一键排监考（4 步向导）+ 修改监考安排（拖拽 timeline）| 上传到结果全流程 |
| **S5 (1周)** | AI 策略页 + AISummaryCard + ProviderBadge + 抽象 provider | mock provider 可调通 |
| **S6 (1周)** | 监考次数统计 + 历史统计（含 aggregate 子页）+ 所有 chart 组件 | 图表三态齐 |
| **S7 (1周)** | 信息设置（普通版）+ 超管后台（4-5 子页）+ RoleGuard | 双角色路由隔离 |
| **S8 (1周)** | 新手引导 + 命令面板 ⌘K + 国际化抽离 + a11y 全审 | 首次登录引导完整 |
| **S9 (1周)** | 联调真后端 + 性能优化 + 视觉走查 + bug fix | 上线候选 |

---

## §15 Claude Design 启动 Prompt（可直接复制）

```
我要为「排监考系统 v2」(B2B SaaS, 高校教务) 做高保真前端 UI。

【设计原则】
1. Airy 专业感：浅冷底 (#F5F7FA) + 白卡 + 柔阴影；不暗、不沉、不花哨
2. 反 AI 味：禁 Inter/Roboto 字体；禁 Lucide/Heroicons 默认；禁卡套卡套卡
3. 数据完整性：禁幻觉数据，三态齐备（loading/empty/error）
4. 双气质：登录 editorial 仪式感；主应用 airy 仪表盘
5. 高级感优先于亲和力（B2B 教务专业场景）

【参考】
- 登录页参考 World Time 极简数字钟 + sepia 影像
- 主应用参考 ClickUp 仪表盘（白侧栏 + 浅紫 active + 柔阴影卡）
- KPI 卡参考 Pet Care 多色 bento（但去暗红，改 airy pastel）
- Timeline 参考 Project Tasks 风格（横向 calendar + 彩色 pill）

【Design Tokens】
- 字体: Geist (display/sans) + Geist Mono (numeric/login)
- 主色: #4F46E5 (深邃靛紫)
- KPI 卡 pastel 底: 紫 #EEF2FF / 绿 #ECFDF5 / 橙 #FFF7ED / 玫紫 #FAF5FF
- 圆角: 8/12/16/20/pill
- 阴影: shadow-card 0 4px 12px rgba(15,23,42,.04)
- 动效: 200ms cubic-bezier(.4,0,.2,1) 平滑

【请实现】
第一页：仪表盘 (Dashboard)
- 顶栏：Logo "排监考系统" + 搜索⌘K + 当前学期 badge "2025-2026 第二学期" + 通知🔔 + 头像下拉 + 租户切换器
- 侧栏（白底，240px）：仪表盘(active 浅紫底+左 3px 紫竖条)、一键排监考、AI策略监考、修改监考安排、任务总览、监考次数统计、监考人员管理、考场管理、班级科目管理(可展开)、历史统计 ── 信息设置、退出登录
- 内容区：4 个 KPI 卡（任务进行中 12 / 累计排班 248 / 教师数 156 / 考场数 24，每卡不同 pastel 底 + 大数字 + 趋势箭头 + 右侧 sparkline 或 mini donut）
- 下方分两栏：左 2/3 = 近 7 天考试 timeline（横向 calendar，彩色 pill）；右 1/3 = 最近任务列表（5 行，每行有状态 pill 和操作按钮）
- 底部：快速操作卡（4 个 CTA：新建任务 / AI 智能排班 / 导入数据 / 查看统计）

【硬约束】
- 不要编造看起来"好看但不存在"的数据；用真实业务字段
- 不要用 emoji 装饰（除登录页学期 badge 外）
- 不要用渐变除非在 KPI 底或登录页
- 给我 1440px desktop 设计基线
- 输出 HTML+Tailwind (CSS variables defined in tokens above)

风格关键词: editorial / professional / airy / data-first / Chinese B2B SaaS
```

---

## §16 业务价值与成功指标（用户使用率/留存率）

> 满足 R33：UI 设计必须服务"提高用户使用率和留存率"——这些指标决定后续商业化推广。

### 16.1 北极星指标 (North Star Metric)

| 指标 | 定义 | v1 目标 |
|---|---|---|
| **TTFW** Time-to-First-Win | 新用户从注册到完成第 1 次成功排监考的时间 | ≤ 30 分钟 |
| **WAU/MAU** | 周活/月活比 | ≥ 50%（学期内）|
| **任务完成率** | 进入"一键排监考"后产出有效结果的比例 | ≥ 85% |

### 16.2 UI 设计杠杆（每个指标对应的 UI 决策）

| 指标 | UI 杠杆 | 实现章节 |
|---|---|---|
| TTFW 缩短 | **新手引导 P13** 7 步带视频 + 首屏 KPI 快速操作卡 + Excel 模板一键下载 | §7 P1 / P13 |
| 留存提升 | **学期 badge** 自动更新让用户感"系统在跟我"+ **任务总览** 历史可追溯 | §10.1 / P5 |
| 完成率提升 | **三态齐备**（空/错/loading）减少卡顿放弃 + **ExcelPreview** 上传前预检 + **AI 摘要** 解释结果 | §9 / P2 |
| 错误率降低 | **禁幻觉数据**让用户信任 + **冲突 banner** 实时提示 + **撤销栈** P4 | §2 R3 / P4 |
| 推荐意愿 | **登录页 GSAP 仪式感**（朋友第一眼"哇"）+ **timeline 可视化**截图传播性强 | P0 / P7 下半 / P4 |

### 16.3 衡量埋点清单（v1 必须实现）

```ts
// analytics events
track('login.success', { role, tenantId })
track('onboarding.step', { step, completed, skipped })
track('schedule.quick.upload', { fileCount, totalRows })
track('schedule.quick.complete', { duration_ms, status })
track('schedule.edit.drag', { fromCell, toCell, conflict })
track('ai.policy.invoke', { policies_enabled: number, provider })
track('export.download', { fileType, page })
track('settings.save', { fields_changed: string[] })
track('error.shown', { page, errorCode })
track('empty.cta.clicked', { page, ctaName })
```

> 埋点用 PostHog / Mixpanel / 或自建。所有事件经 Zod schema 校验后再发送。

---

## §17 商业化卖点矩阵（推广关键词）

> 满足 R34："商业化落地、推广卖点高"。下表是给销售/官网/对外材料用的卖点弹药。

| 卖点 | UI 体现 | 销售话术 |
|---|---|---|
| **AI 智能排班** | P3 个性化策略 6 toggle + 自由文本 + provider 抽象 | "6 条预设策略 + 自然语言定制，1 分钟出考表" |
| **零幻觉、可信** | §2 R3 数据完整性铁律 + 三态齐备 | "教务老师最怕'看起来对'，我们承诺'真实空' = 真实数据" |
| **登录即仪式** | P0 GSAP timeline + 视频背景 + World Time 风格 | "每天打开都像走进太空舱，让繁琐工作有仪式感" |
| **拖拽即调整** | P4 修改监考安排可拖拽 timeline | "改人换班，鼠标拖一下，自动检测冲突" |
| **企业级安全** | P12 双因素认证 + 审计日志 + 路由守卫 | "金融级安全，符合等保 2.0 要求" |
| **多校协同** | Q5 多租户 + 顶栏切换器 + 超管跨校 | "教育集团一站管理 N 所学校" |
| **0 培训上手** | P13 新手引导 7 步 + ⌘K 命令面板 + 空状态 CTA | "新管理员 30 分钟独立产出，无需培训" |
| **专业可信** | 5 维设计原则 + ClickUp 级仪表盘 + Geist 字体 | "对标 Linear / Notion 的现代设计语言" |
| **数据可视化** | KPI 多色 + Sparkline + Timeline + 柱/饼/折线齐 | "一屏看全学校监考全貌" |
| **极简定价** | 商业化模块（会员/赚钱计划）已剔除 → 干净体验 | "纯订阅、无内购、无广告" |

### 17.1 对外材料素材建议

- 📹 **登录页 GSAP 全屏 demo 视频**（15s）→ 官网 hero
- 📸 **Dashboard 4 KPI + timeline 截图**（1440px） → 落地页 above-fold
- 📸 **一键排监考 4 步流程截图组**（GIF） → 解决方案页
- 📸 **修改监考安排拖拽 GIF** → 演示页
- 📸 **超管控制台截图** → 企业客户对接素材
- 📊 **AI 策略矩阵图**（6 toggle + 自由文本）→ 技术白皮书

---

## §18 文档版本日志

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-05-19 | 首版（综合 4 轮对话 + PDF + 12 张图 + Q1-Q15 决策）|
| 1.1 | 2026-05-19 | 审查修订 9 项：sidebar 一致性 / elevation 立体系统 / 系统逻辑 §10 / 组件补全 / P12 schema 详化 / P11 措辞 / §16 业务价值 / §17 商业化卖点 / 交叉引用 |
| 1.2 | 2026-05-19 | 视频解析修订：① §7 P0 改为二段式（上数字钟 / 下影像）+ 5 段教务 token + 浮卡 ② §8.1 GSAP timeline 7 段细化 + 实时秒数滚动 + Ken Burns + 影像轮播 ③ §3.2 字体补登录 Hero Boxed Round ④ §3.10 视频规格表（实测 + 实施清单）|

---

> 📌 **后续维护**：本文档随设计反馈迭代，所有改动同步更新 `01-PRD.md` 中相关字段。
