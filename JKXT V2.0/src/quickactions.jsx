// Quick Actions — 4 CTA cards at the bottom of the dashboard

const ACTIONS = [
  {
    title: '新建排监考任务',
    sub: '上传 3 份 Excel · 一键生成',
    icon: IconPlus,
    primary: true,
    accent: '#4F46E5',
    bg: '#4F46E5',
    fg: '#FFFFFF',
    sBg: 'rgba(255,255,255,.18)',
    sFg: '#FFFFFF',
    nav: 'quick',
  },
  {
    title: 'AI 智能排班',
    sub: '6 条策略 + 自然语言定制',
    icon: IconSparkle,
    badge: 'AI',
    accent: '#9333EA',
    bg: '#FFFFFF',
    fg: '#0F172A',
    sBg: '#FAF5FF',
    sFg: '#9333EA',
    nav: 'ai',
  },
  {
    title: '导入历史数据',
    sub: '支持 .xlsx / .xls / .csv',
    icon: IconUpload,
    accent: '#059669',
    bg: '#FFFFFF',
    fg: '#0F172A',
    sBg: '#ECFDF5',
    sFg: '#059669',
    nav: 'people',
  },
  {
    title: '查看统计报表',
    sub: '监考次数 / 历史趋势',
    icon: IconChart,
    accent: '#D97706',
    bg: '#FFFFFF',
    fg: '#0F172A',
    sBg: '#FFF7ED',
    sFg: '#D97706',
    nav: 'stats-supervise',
  },
];

function ActionCard({ a }) {
  const isPrimary = a.primary;
  return (
    <button
      onClick={() => a.nav && window.navigateTo(a.nav)}
      className="relative rounded-2xl text-left p-5 hover-lift overflow-hidden group"
      style={{
        background: a.bg,
        border: isPrimary ? 'none' : '1px solid var(--border-soft)',
        boxShadow: isPrimary ? '0 8px 24px rgba(79,70,229,.25)' : 'var(--shadow-card)',
        color: a.fg,
      }}
    >
      {/* decorative grid */}
      {isPrimary && (
        <svg
          className="absolute -right-4 -top-6 opacity-25 pointer-events-none"
          width="180" height="180" viewBox="0 0 180 180"
        >
          <g stroke="#fff" strokeWidth="1" fill="none">
            <circle cx="120" cy="60" r="40" />
            <circle cx="120" cy="60" r="60" />
            <circle cx="120" cy="60" r="80" />
          </g>
        </svg>
      )}

      <div className="relative flex items-start justify-between">
        <span
          className="w-10 h-10 rounded-xl flex items-center justify-center"
          style={{ background: a.sBg, color: a.sFg }}
        >
          <a.icon size={20} strokeWidth={2} />
        </span>
        {a.badge && (
          <span
            className="px-1.5 py-0.5 rounded text-[10px] font-mono font-semibold"
            style={{ background: '#FAF5FF', color: '#9333EA' }}
          >
            {a.badge}
          </span>
        )}
        {isPrimary && (
          <span
            className="font-mono text-[10px] uppercase tracking-wider px-1.5 py-0.5 rounded"
            style={{ background: 'rgba(255,255,255,.18)', color: '#FFFFFF' }}
          >
            主操作
          </span>
        )}
      </div>

      <div className="relative mt-5">
        <div className="text-[15px] font-semibold leading-tight">{a.title}</div>
        <div
          className="text-[11.5px] mt-1.5"
          style={{ color: isPrimary ? 'rgba(255,255,255,.78)' : 'var(--text-soft)' }}
        >
          {a.sub}
        </div>
      </div>

      <div
        className="relative mt-5 inline-flex items-center gap-1 text-[12px] font-medium transition-transform group-hover:translate-x-0.5"
        style={{ color: isPrimary ? '#FFFFFF' : a.accent }}
      >
        立即开始
        <IconChevron dir="right" size={12} strokeWidth={2.5} />
      </div>
    </button>
  );
}

function QuickActionsRow() {
  return (
    <div>
      <div className="mb-4 flex items-baseline justify-between">
        <div>
          <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            快速操作
          </h3>
          <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            常用入口，一键直达
          </div>
        </div>
        <div className="inline-flex items-center gap-1.5 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
          <span className="kbd">⌘</span>
          <span className="kbd">K</span>
          <span className="ml-1">打开命令面板</span>
        </div>
      </div>
      <div className="grid grid-cols-4 gap-5">
        {ACTIONS.map((a, i) => (
          <ActionCard key={i} a={a} />
        ))}
      </div>
    </div>
  );
}

window.QuickActionsRow = QuickActionsRow;
