---
name: "JKXT Quick 排监考"
description: "面向学校考务人员的克制、可信、可复核排监考工作台"
colors:
  action-indigo: "#4F46D7"
  action-indigo-deep: "#4037B8"
  canvas: "#F4F6FA"
  surface: "#FFFFFF"
  surface-subtle: "#F7F9FC"
  ink-strong: "#101828"
  ink-medium: "#445268"
  ink-soft: "#65748A"
  stroke: "#DDE4ED"
typography:
  headline:
    fontFamily: "Geist, Source Han Sans CN, system-ui, sans-serif"
    fontSize: "clamp(1.45rem, 2vw, 1.75rem)"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  body:
    fontFamily: "Geist, Source Han Sans CN, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "Geist, Source Han Sans CN, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1.4
rounded:
  sm: "8px"
  md: "10px"
  lg: "12px"
spacing:
  xs: "8px"
  sm: "12px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.action-indigo}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "10px 16px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-strong}"
    rounded: "{rounded.md}"
    padding: "10px 16px"
---

# Design System: JKXT Quick 排监考

## Overview

**Creative North Star: "The Registrar's Desk"**

JKXT 应像一张整理有序的教务处工作台：结构稳定、信息可扫读、控件位置可预期。视觉层级来自排版、留白、细边框和少量色彩，而不是渐变、玻璃、漂浮装饰或重复阴影。

强调色只用于主操作、选中状态和键盘焦点。数据密集页面使用连续的白色工作平面；摘要帮助定向，但不能抢走任务本身的注意力。

**Key Characteristics:**

- 任务标题清楚，说明短，操作靠近标题。
- 8 / 12 / 16 / 24 / 32px 的稳定节奏。
- 表格、筛选与分页形成连续工作区。
- 状态动效短促且可被 reduced-motion 完全关闭。

## Colors

主色是克制的校务靛蓝；大部分界面由冷白、浅灰与深墨色完成。

### Primary

- **Action Indigo:** 仅用于页面主操作、当前导航、链接与焦点。

### Neutral

- **Canvas:** 登录后应用的安静背景。
- **Surface:** 表格、表单、面板的实体工作面。
- **Ink:** 三档文本保证标题、正文和辅助信息可扫读。
- **Stroke:** 负责分组，不使用重阴影代替结构。

**The One Voice Rule.** 单屏强调色面积不得超过约 10%；多个同权紫蓝按钮是禁止的。

## Typography

**Display Font:** Geist（回退 Source Han Sans CN 与 system-ui）  
**Body Font:** Geist（回退 Source Han Sans CN 与 system-ui）  
**Label/Mono Font:** Geist Mono

**Character:** 现代但不炫技；中文与数字在密集数据场景中保持稳定的字面和节奏。

### Hierarchy

- **Headline**（700，响应式 23–28px，1.2）：页面唯一 H1，保持一行，窄屏最多两行。
- **Title**（600，15–16px，1.35）：工作区与面板标题。
- **Body**（400，14px，1.55）：说明、表单与任务内容，正文建议不超过 72ch。
- **Label**（600，12px，1.4）：表头、字段与状态；禁止无意义全大写。

**The One H1 Rule.** 每页只保留一个主要标题；日期与状态使用元信息样式，不与标题争夺权重。

## Elevation

默认采用实体表面与 1px 结构边框。阴影只用于浮层、菜单和明确的悬浮状态；常驻卡片保持平坦。

### Shadow Vocabulary

- **Control:** 极浅 0 1px 2px 阴影，仅用于可交互控件。
- **Overlay:** 柔和 0 12px 32px 阴影，仅用于菜单、抽屉和模态框。

**The Flat-by-Default Rule.** 静止表面不抬升；若每个区域都带阴影，说明层级已经失效。

## Components

### Buttons

- **Shape:** 稳定圆角（10px），不使用胶囊形。
- **Primary:** 靛蓝底、白字、10×16px 内边距；单个工作区只保留一个主按钮。
- **Hover / Focus:** 颜色加深或 3px 焦点环；不漂浮、不发光。
- **Secondary:** 白底、深字、1px 边框。

### Chips

- **Style:** 仅用于真实状态与筛选值；浅底、深字、8px 圆角。
- **State:** 选中态使用浅靛蓝背景和明确边框，不添加渐变。

### Cards / Containers

- **Corner Style:** 工作区 12px；控件 8–10px。
- **Background:** 实体白色或极浅灰。
- **Shadow Strategy:** 默认无阴影，悬浮控件和浮层例外。
- **Border:** 1px 冷灰结构线。
- **Internal Padding:** 16px，主要区域可用 20–24px。

### Inputs / Fields

- **Style:** 白底、1px 边框、10px 圆角。
- **Focus:** 靛蓝边框与可见焦点环。
- **Error / Disabled:** 文案与颜色同时表达，禁止仅靠红色。

### Navigation

- **Style, typography, default/hover/active states, mobile treatment.** 侧栏保持实体浅色；当前项使用浅靛蓝面和深靛蓝文字。移动端抽屉保留 44px 触控目标与清晰关闭路径。

## Do's and Don'ts

### Do:

- **Do** 用 1px 边框、标题层级和 8/12/16/24/32px 节奏组织页面。
- **Do** 让筛选、表格与分页成为一块连续工作面。
- **Do** 保留键盘焦点、44px 触控目标和 reduced-motion。

### Don't:

- **Don't** 使用“玻璃拟态与大面积半透明模糊”。
- **Don't** 使用“紫蓝渐变、网格光晕、漂浮圆环等泛 AI 装饰”。
- **Don't** 使用“每个数字一张彩色卡片、重复图标底座与卡片套卡片”。
- **Don't** 使用“装饰性跑马灯、滚动编舞和与任务无关的动效”。
- **Don't** 把登录后的任务页面做成“营销落地页式 Hero、口号、徽章和巨幅留白”。

