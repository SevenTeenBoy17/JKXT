# 03 — AI 工作流 + 6 策略 + Prompt 工程

## 1. AI 角色定位

> AI 在系统中做**两件事**：① 把自然语言策略翻译成可执行约束 ② 在 6 条预设 + 自然语言混合下生成排考方案。**硬约束（时间冲突、容量）由 OR-Tools 兜底**，AI 不"承担正确性"。

## 2. 系统使用的 AI 模型

| 用途 | 默认模型 | 替代（LiteLLM 抽象）|
|---|---|---|
| 自然语言策略 → 约束 DSL | claude-sonnet-4 | qwen-max / glm-4 |
| 排考主推理 | claude-opus-4.5 | qwen3-max / deepseek-r1 |
| 排考总结/建议 | claude-sonnet-4 | 同上 |
| 兜底（成本敏感）| claude-haiku-4 | qwen-turbo |

**配置**：超管可在"信息设置 → AI 配置"切换。

## 3. 6 条预设策略详细 DSL

每条策略翻译成统一约束 DSL（供 OR-Tools 二次校验）：

| # | 名 | 类型 | 表达 |
|---|---|---|---|
| S1 | 每教师监考时长一致 | soft | minimize stdev(hours[t] for t in teachers) |
| S2 | 班主任 ≤ 2 科 / 5 科考试 | hard | for t in teachers if t.is_homeroom: count(assign[t]) ≤ 2 |
| S3 | 班主任 < 学科教师 | soft | mean(count_homeroom) < mean(count_subject) |
| S4 | 延迟课老师早场 | soft | if t.has_late_class_today: prefer assign[t].start < 15:00 |
| S5 | 年龄 > N → 楼道 | soft | if t.age > N: prefer assign[t].room.is_corridor |
| S6 | 跨年级老师高年级 | soft | if t.grades_taught contains [7,8]: prefer assign[t].exam.grade == 8 |

**权重**（默认值，UI 可调）：
- S1: 0.20，S2: 1.00（hard），S3: 0.15，S4: 0.20，S5: 0.15，S6: 0.30
- 软约束总分 = Σ(weight × satisfied_ratio)，目标最大化

## 4. 自然语言策略 → DSL Prompt

```text
你是一名教务排考约束分析师。

输入：
- 用户自然语言策略：{nl_text}
- 已知约束 DSL 模板（见下方）

任务：
把自然语言切分成原子约束，每条标注类型(hard/soft)、权重(0-1)、
作用域(teacher/room/exam)、表达式(JSON DSL)。

约束 DSL 词汇：
- COND: t.age > N, t.gender == 'F', t.subject == X, t.is_homeroom
- TARGET: assign[t].room.is_corridor, assign[t].exam.grade, assign[t].start
- AGG: count, mean, stdev, min, max
- OP: =, !=, <, >, in, not in
- COMBINE: AND, OR, NOT

输出 JSON：
{
  "constraints": [
    {"name": "...", "type": "soft", "weight": 0.3, "expression": {...}, "explanation": "..."}
  ],
  "unable_to_parse": ["..."]  // 无法解析的部分原文返回
}

特别注意：
- 拿不准的约束都标 soft + 0.1 权重，不要乱猜 hard
- 不要发明 DSL 词汇外的字段；找不到字段返回 unable_to_parse
- 中文输入 → 中文 explanation
```

## 5. 排考主 Prompt（核心）

```text
你是一名公平资深的中学教务主任，正在给本学期的考试安排监考。

【上下文】
- 学校：{org.name}
- 学期：{term.year_start}-{term.year_end} 第{term.term}学期
- 教师总数：{n_teachers}（其中班主任 {n_homeroom}）
- 考场总数：{n_rooms}（其中楼道 {n_corridor}）
- 考试场次：{n_exams}，跨日期 {date_range}

【教师数据】（JSON，含编号/姓名/年级/科目/班主任/性别/年龄/extras）
{teachers_json}

【考场数据】
{rooms_json}

【考试场次数据】
{exams_json}

【硬约束】（必须满足，违反则方案废弃）
1. 一个教师同一时段不能监考两个考场
2. 一个考场同一时段只有一个主监考
3. 班主任在 5 科考试中最多监考 2 科

【软约束】（最大化满足度）
{soft_constraints_with_weights}

【自然语言补充】
{nl_constraints_parsed_or_raw}

【输出】
按以下 JSON schema 输出排考方案：
{
  "assignments": [
    {"exam_id": "...", "room_id": "...", "teacher_id": "...", "role": "main"},
    ...
  ],
  "reasoning": "本方案如何满足主要约束的解释（200 字内）",
  "tradeoffs": ["妥协 1: 教师 X 的早场偏好未满足，因为 ...", "..."],
  "recommendations": ["建议 1: ...", "建议 2: ..."]
}

【约束】
- 不要输出 JSON 以外的内容
- assignments 列表必须覆盖所有 (exam_id, room_id) 二元组
- teacher_id 必须存在于教师数据中
- 拿不准时优先满足硬约束 + 高权重软约束
```

**Temperature 建议**：0.3（稳定为主，少量探索）  
**Top-p**：0.95  
**Max tokens**：4096（300 教师 × 8 场考试 ≈ 2400 tokens 输出 + 思考预算）  
**Thinking budget**（Claude Extended Thinking）：8K（启用以提升排考公平度）

## 6. 验证 Pipeline

```
LLM 输出 JSON
   ↓
JSON Schema 校验（assignments 完整性）
   ↓
OR-Tools 硬约束校验：
   - 教师时段冲突？
   - 考场时段冲突？
   - 容量？
   - 班主任 ≤ 2 科？
   ↓
软约束打分：
   for each strategy in strategies:
       satisfied_ratio = compute(strategy, schedule)
       score += weight * satisfied_ratio
   ↓
score < threshold(0.65) OR 硬约束失败
   ↓ yes
   反馈 LLM 重试（注入失败原因 + 重新生成请求）
   重试次数 < 3
   ↓ no
   降级到 OR-Tools 纯 CSP 求解（不带 AI 推理，保证可行解）
```

## 7. AI 总结 + 建议 Prompt

排考成功后，再调一次 LLM 生成"教务主任友好"的总结：

```text
作为教务主任助手，请基于下面的排考结果，生成给校长的简短报告。

【数据】
- 总场次：{n_assignments}
- 教师参与：{n_teachers_used}/{n_teachers_total}
- 教师次数标准差：{stdev_count}（越小越公平）
- 各软约束满足率：{strategy_scores}

【输出要求】
1. 用 ≤ 150 字总结本次排考的核心特点
2. 用 ≤ 100 字列出 3 条对下次排考的具体建议
3. 用中文，避免 jargon

【输出 JSON】
{
  "summary": "...",
  "recommendations": ["...", "...", "..."]
}
```

## 8. Prompt 版本管理

- 每个 prompt 模板放在 `apps/api/app/prompts/v{N}.j2`（Jinja2）
- 版本号写入 schedule_run.extras，便于 A/B 与回溯
- 每月评估"老 prompt vs 新 prompt"在 staging 数据上的公平度差异

## 9. 成本控制

| 项 | 估算 |
|---|---|
| 300 教师 8 场考试 1 次排考 | 输入 ~6K tokens / 输出 ~3K tokens / thinking 6K |
| Claude Opus 4.5 单次 | ≈ $0.45 USD（约 ¥3.3）|
| 每月 50 次排考 / 校 | ≈ ¥165 / 校 |
| Qwen-Max 等效 | ≈ ¥30 / 校 / 月 |

**优化**：
- Prompt cache 启用（系统消息固定不变 → 90% 折扣）
- 教师/考场/考试上下文用 ephemeral cache（5 分钟内重排免费）
- 小校用 Haiku-4 即可（< ¥10 / 月）

## 10. 失败回退

| 失败类型 | 处理 |
|---|---|
| LLM 调用超时 (60s) | 切换备用 model；再失败 → CSP 兜底 |
| LLM 输出非 JSON | 解析失败 → 重试 + 强化 schema 提示 |
| LLM 输出违反硬约束 | 反馈具体冲突重试；3 次后 CSP 兜底 |
| 所有软约束分数 < 0.4 | 提示用户"约束过严，建议放宽" + 提供 CSP 可行解 |
| LiteLLM 中端服务不通 | 队列任务标记 failed，邮件通知超管 |
