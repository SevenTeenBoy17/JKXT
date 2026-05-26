// AI 策略监考 — 策略 toggles + 自然语言输入 + provider 切换

const K12_POLICIES = [
  { id: 'homeroom_avoid', label: '班主任避监本班', desc: '班主任老师不安排监考自己任教的班级,避免影响公平', critical: true, default: true },
  { id: 'same_grade_avoid', label: '同年级回避', desc: '高三老师不监考高三、初三老师不监考初三,避免泄题嫌疑', critical: true, default: true },
  { id: 'gender_mix', label: '男女混合监考', desc: '每个考场至少配置 1 男 1 女老师,符合标准化考务规定', critical: false, default: true },
  { id: 'subject_off', label: '副科监主科', desc: '本科目教师不监考本科目考试,如数学老师不监考数学', critical: false, default: true },
  { id: 'workload_balance', label: '工作量均衡', desc: '监考次数 ±2 场范围内均衡分配,避免单人过载', critical: false, default: true },
  { id: 'cross_grade', label: '跨年级优先', desc: '优先安排跨年级监考,初中老师监考高中、反之亦然', critical: false, default: false },
];

const PROVIDERS = [
  { id: 'qwen',     name: 'Qwen3 Max',    org: '阿里通义',   active: true,  color: '#9333EA' },
  { id: 'deepseek', name: 'DeepSeek-V3',  org: 'DeepSeek',   active: false, color: '#4F46E5' },
  { id: 'doubao',   name: 'Doubao Pro',   org: '字节豆包',   active: false, color: '#EF4444' },
  { id: 'openai',   name: 'GPT-4o',       org: 'OpenAI',     active: false, color: '#10B981' },
];

function PolicyToggle({ p, checked, onChange }) {
  return (
    <div
      className="rounded-xl p-4 transition-all"
      style={{
        border: `1px solid ${checked ? (p.critical ? '#10B981' : 'var(--brand)') : 'var(--border-soft)'}`,
        background: checked ? (p.critical ? '#F0FDF4' : 'var(--brand-soft)') : '#fff',
        boxShadow: checked ? 'var(--elevation-1)' : 'none',
      }}
    >
      <div className="flex items-start gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-[13.5px] font-semibold" style={{ color: 'var(--text-strong)' }}>
              {p.label}
            </span>
            {p.critical && <Tag color="emerald" dot>核心</Tag>}
          </div>
          <div className="text-[11.5px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>
            {p.desc}
          </div>
        </div>
        <button
          onClick={() => onChange(!checked)}
          className="relative w-10 h-6 rounded-full transition-colors shrink-0 mt-0.5"
          style={{ background: checked ? (p.critical ? '#10B981' : 'var(--brand)') : '#CBD5E1' }}
        >
          <span
            className="absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all"
            style={{
              left: checked ? 18 : 2,
              boxShadow: 'var(--elevation-1)',
            }}
          />
        </button>
      </div>
    </div>
  );
}

function NaturalLanguageInput() {
  const [text, setText] = React.useState('');
  const examples = [
    '王伟明老师本周一上午有外出会议,请避开',
    '初三(4)班和高三(7)班的考场需要错开半小时',
    '尽量让教龄 5 年以下的老师跟资深老师搭档',
    '英语听力时段需要安排懂听力设备的老师',
  ];

  return (
    <div className="bg-white rounded-2xl p-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle
        title="自然语言补充要求"
        sub="预置策略不能完全覆盖的情况,可用自然语言告诉 AI"
        right={<Tag color="violet">AI 增强</Tag>}
      />
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="例如:王伟明老师本周一上午有外出,请避开;高三模考要求两位资深老师配合..."
        className="w-full h-32 rounded-xl p-4 text-[13px] outline-none resize-none transition-colors leading-relaxed"
        style={{
          background: '#F8FAFC',
          border: '1px solid var(--border-soft)',
          color: 'var(--text-strong)',
        }}
      />
      <div className="mt-2 flex items-center justify-between">
        <div className="text-[11px]" style={{ color: 'var(--text-soft)' }}>
          已输入 <span className="font-mono">{text.length}</span> / 500 字
        </div>
        <button className="text-[11px] inline-flex items-center gap-1 hover:underline" style={{ color: 'var(--brand)' }}>
          <IconSparkle size={11} /> 优化我的表达
        </button>
      </div>

      <div className="mt-4">
        <div className="text-[11px] mb-2 font-medium" style={{ color: 'var(--text-soft)' }}>
          快速插入示例:
        </div>
        <div className="flex gap-2 flex-wrap">
          {examples.map((e) => (
            <button
              key={e}
              onClick={() => setText(text ? text + '\n' + e : e)}
              className="px-2.5 py-1.5 rounded-full text-[11.5px] transition-colors hover:bg-white"
              style={{ background: '#F1F5F9', color: 'var(--text-medium)', border: '1px solid var(--border-soft)' }}
            >
              + {e}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

function ProviderSelector() {
  const [providers, setProviders] = React.useState(PROVIDERS);
  const setActive = (id) => {
    setProviders(providers.map((p) => ({ ...p, active: p.id === id })));
    window.ui.toast(`已切换至 ${providers.find((p) => p.id === id).name}`, { kind: 'success' });
  };
  return (
    <div className="bg-white rounded-2xl p-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle
        title="AI 服务提供方"
        sub="不同模型对中文教育场景的理解略有差异"
        right={<Tag color="indigo">企业版可切换</Tag>}
      />
      <div className="grid grid-cols-2 gap-2">
        {providers.map((p) => (
          <button
            key={p.id}
            onClick={() => setActive(p.id)}
            className="rounded-xl p-3 flex items-center gap-2.5 transition-all text-left"
            style={{
              background: p.active ? p.color + '0A' : '#fff',
              border: `1px solid ${p.active ? p.color : 'var(--border-soft)'}`,
            }}
          >
            <div
              className="w-8 h-8 rounded-lg flex items-center justify-center font-mono font-bold text-[11px]"
              style={{ background: p.color + '20', color: p.color }}
            >
              {p.org.slice(0, 2)}
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-[12.5px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>{p.name}</div>
              <div className="text-[10.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{p.org}</div>
            </div>
            {p.active && (
              <span className="w-5 h-5 rounded-full inline-flex items-center justify-center"
                style={{ background: p.color }}>
                <IconCheck size={11} strokeWidth={3} className="text-white" />
              </span>
            )}
          </button>
        ))}
      </div>

      <div className="mt-4 pt-4 space-y-2 text-[11.5px]"
        style={{ borderTop: '1px solid var(--border-soft)' }}>
        {[
          { k: '本月调用', v: '142 次', c: 'var(--text-strong)' },
          { k: '剩余额度', v: '858 次', c: 'var(--brand)' },
          { k: '平均响应', v: '4.2 秒', c: 'var(--text-strong)' },
        ].map((r) => (
          <div key={r.k} className="flex items-center justify-between">
            <span style={{ color: 'var(--text-soft)' }}>{r.k}</span>
            <span className="font-mono font-semibold" style={{ color: r.c }}>{r.v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function AIPolicyPage() {
  const [policies, setPolicies] = React.useState(
    K12_POLICIES.reduce((acc, p) => ({ ...acc, [p.id]: p.default }), {})
  );
  const [running, setRunning] = React.useState(false);
  const activeCount = Object.values(policies).filter(Boolean).length;

  const startAI = () => {
    setRunning(true);
    window.ui.toast('AI 正在读取数据…', { kind: 'info' });
    setTimeout(() => {
      window.ui.toast('应用策略中 (班主任避监·同年级回避…)', { kind: 'info' });
      setTimeout(() => {
        setRunning(false);
        window.ui.toast('AI 生成完成', { kind: 'success', desc: '评分 96/100 · 0 冲突 · 可下载结果', action: { label: '查看任务', onClick: () => window.navigateTo('tasks') } });
      }, 2400);
    }, 1200);
  };

  return (
    <PageShell
      crumbs={['天津市实验中学', '排班作业', 'AI 策略监考']}
      title={
        <span className="inline-flex items-center gap-2.5">
          AI 策略监考
          <Tag color="violet" dot>AI · Qwen3 Max</Tag>
        </span>
      }
      subtitle="勾选策略 + 自然语言补充,AI 将基于本校历史排班数据生成最优监考方案"
      actions={
        <>
          <Button icon={IconHelp} onClick={() => window.ui.toast('策略说明', { kind: 'info', desc: '点击查看 6 条预置策略的详细逻辑' })}>策略说明</Button>
          <Button icon={IconList} onClick={() => window.ui.toast('历史 AI 调用', { kind: 'info', desc: '本月共 142 次调用' })}>历史调用</Button>
        </>
      }
    >
      <div className="grid grid-cols-[1fr_320px] gap-5">
        {/* Left: main inputs */}
        <div className="space-y-5">
          {/* Source data */}
          <div className="bg-white rounded-2xl p-5"
            style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <SectionTitle
              title="基础数据"
              sub="上传或选择本次排班依据的考试与教师数据"
              right={<Tag color="emerald" dot>已就绪</Tag>}
            />
            <div className="grid grid-cols-3 gap-3">
              {[
                { l: '考试安排', f: '2025秋·初二期中考_排考.xlsx', r: '48 行', c: 'indigo' },
                { l: '监考人员', f: '实验中学_教师档案_2025秋.xlsx', r: '142 行', c: 'emerald' },
                { l: '考场信息', f: '从系统读取最新数据', r: '36 间', c: 'amber' },
              ].map((d) => (
                <div key={d.l} className="rounded-lg p-3 flex items-center gap-3"
                  style={{ background: '#F8FAFC' }}>
                  <div className="w-9 h-11 rounded flex items-end justify-center pb-1"
                    style={{ background: '#fff', border: '1px solid var(--border-soft)' }}>
                    <span className="font-mono text-[8px] font-bold" style={{ color: 'var(--text-medium)' }}>XLSX</span>
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>
                      {d.l}
                    </div>
                    <div className="text-[12px] font-medium mt-0.5 truncate" style={{ color: 'var(--text-strong)' }}>
                      {d.f}
                    </div>
                    <div className="text-[10.5px] font-mono" style={{ color: 'var(--text-soft)' }}>{d.r}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Policies */}
          <div className="bg-white rounded-2xl p-5"
            style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <SectionTitle
              title="预置策略"
              sub={`勾选 ${activeCount} / ${K12_POLICIES.length} · 中小学专属策略`}
              right={
                <Button size="sm" variant="ghost" icon={IconSettings}>自定义策略</Button>
              }
            />
            <div className="grid grid-cols-2 gap-3">
              {K12_POLICIES.map((p) => (
                <PolicyToggle
                  key={p.id}
                  p={p}
                  checked={policies[p.id]}
                  onChange={(v) => setPolicies({ ...policies, [p.id]: v })}
                />
              ))}
            </div>

            {/* Numeric inputs row */}
            <div className="grid grid-cols-3 gap-3 mt-4 pt-4"
              style={{ borderTop: '1px solid var(--border-soft)' }}>
              {[
                { l: '班主任最多监考', v: '2', u: '场/天', c: 'indigo' },
                { l: '教师每天最多', v: '3', u: '场', c: 'emerald' },
                { l: '场间最短间隔', v: '15', u: '分钟', c: 'amber' },
              ].map((n) => (
                <div key={n.l} className="rounded-lg p-3" style={{ background: '#F8FAFC' }}>
                  <div className="text-[11px]" style={{ color: 'var(--text-soft)' }}>{n.l}</div>
                  <div className="mt-1 flex items-baseline gap-1.5">
                    <input
                      defaultValue={n.v}
                      className="w-12 bg-transparent outline-none num text-[20px] font-bold"
                      style={{ color: 'var(--text-strong)' }}
                    />
                    <span className="text-[11.5px]" style={{ color: 'var(--text-medium)' }}>{n.u}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>

          <NaturalLanguageInput />
        </div>

        {/* Right rail */}
        <div className="space-y-4">
          <ProviderSelector />

          {/* Generate button (sticky) */}
          <div className="rounded-2xl p-5 text-white relative overflow-hidden"
            style={{
              background: 'linear-gradient(135deg, #4F46E5 0%, #7C3AED 100%)',
              boxShadow: '0 12px 32px rgba(79,70,229,.32)',
            }}>
            <div className="flex items-center gap-2 font-mono text-[10px] uppercase tracking-wider opacity-80 mb-2">
              <IconSparkle size={11} /> AI READY
            </div>
            <div className="text-[18px] font-bold leading-tight">
              生成智能排班
            </div>
            <div className="text-[11.5px] opacity-80 mt-1 leading-relaxed">
              {activeCount} 条策略已激活,AI 将基于历史数据进行优化。预计耗时 30-60 秒。
            </div>
            <button
              className="mt-4 w-full h-10 rounded-lg text-[13px] font-semibold inline-flex items-center justify-center gap-1.5"
              style={{ background: 'rgba(255,255,255,.95)', color: 'var(--brand)', opacity: running ? 0.7 : 1 }}
              onClick={startAI}
              disabled={running}
            >
              {running ? (
                <>
                  <span className="w-3 h-3 rounded-full border-2 border-current border-t-transparent animate-spin" />
                  AI 运行中…
                </>
              ) : (
                <>
                  <IconSparkle size={14} strokeWidth={2.25} />
                  开始 AI 排班
                </>
              )}
            </button>
            <svg className="absolute -right-6 -top-4 opacity-15 pointer-events-none" width="160" height="160" viewBox="0 0 160 160">
              <g stroke="#fff" strokeWidth="1" fill="none">
                <circle cx="120" cy="40" r="35" />
                <circle cx="120" cy="40" r="55" />
                <circle cx="120" cy="40" r="75" />
              </g>
            </svg>
          </div>

          {/* Recent invocations */}
          <div className="bg-white rounded-2xl p-5"
            style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <SectionTitle title="近期 AI 调用" sub="最近 5 次" />
            <ul className="space-y-3">
              {[
                { task: '高三 11 月 第二次月考', t: '20 分钟前', score: 96, c: '#10B981' },
                { task: '初三年级月考',           t: '昨天 14:32', score: 92, c: '#10B981' },
                { task: '七年级 单元测试',       t: '11-18',     score: 88, c: '#F59E0B' },
                { task: '初二期中考',             t: '11-12',     score: 94, c: '#10B981' },
              ].map((it, i) => (
                <li key={i} className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-lg inline-flex items-center justify-center"
                    style={{ background: it.c + '15', color: it.c }}>
                    <IconSparkle size={13} strokeWidth={2} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="text-[12px] font-medium truncate" style={{ color: 'var(--text-strong)' }}>
                      {it.task}
                    </div>
                    <div className="text-[10.5px] font-mono mt-0.5" style={{ color: 'var(--text-soft)' }}>
                      {it.t} · 评分 {it.score}/100
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </PageShell>
  );
}

window.AIPolicyPage = AIPolicyPage;
