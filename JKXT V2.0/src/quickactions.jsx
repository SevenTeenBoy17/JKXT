// Quick Actions — 4 CTA cards at the bottom of the dashboard

const ACTIONS = [
  {
    title: '新建排监考任务',
    sub: '上传 3 份 Excel · 一键生成',
    glyph: 'quick',
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
    glyph: 'strategy',
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
    sub: '支持 .xlsx / .xls',   // 不再谎称支持 .csv——前后端(POI WorkbookFactory)都只认 Excel 工作簿
    glyph: 'upload',
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
    glyph: 'stats',
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
      className="quick-action-item relative rounded-2xl text-left p-5 overflow-hidden group"
      data-primary={isPrimary ? 'true' : 'false'}
      data-tone={isPrimary ? 'indigo' : a.nav === 'ai' ? 'violet' : a.nav === 'people' ? 'emerald' : 'amber'}
      style={{
        '--action-accent': a.accent,
      }}
    >
      <div className="relative flex items-start justify-between">
        <span className="quick-action-icon quick-action-item__icon" aria-hidden="true">
          <ProductGlyph name={a.glyph} size={38} active />
        </span>
        {a.badge && (
          <span
            className="px-1.5 py-0.5 rounded text-[10px] font-mono font-semibold"
            style={{ background: '#FAF5FF', color: '#9333EA' }}
          >
            {a.badge}
          </span>
        )}
      </div>

      <div className="quick-action-item__copy relative mt-5">
        <div className="text-[15px] font-semibold leading-tight">{a.title}</div>
        <div
          className="text-[11.5px] mt-1.5"
          style={{ color: 'var(--text-soft)' }}
        >
          {a.sub}
        </div>
      </div>

      <div
        className="relative mt-5 inline-flex items-center gap-1 text-[12px] font-medium transition-transform group-hover:translate-x-0.5"
        style={{ color: a.accent }}
      >
        立即开始
        <IconChevron dir="right" size={12} strokeWidth={2.5} />
      </div>
    </button>
  );
}

function QuickActionsRow() {
  return (
    <div className="quick-actions">
      <div className="mb-4 flex items-baseline justify-between">
        <div>
          <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            快速操作
          </h3>
          <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            按任务场景直达常用操作
          </div>
        </div>
        <div className="inline-flex items-center gap-1.5 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
          <span className="kbd">⌘</span>
          <span className="kbd">K</span>
          <span className="ml-1">打开命令面板</span>
        </div>
      </div>
      <div className="quick-actions__grid grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-5">
        {ACTIONS.map((a, i) => (
          <ActionCard key={i} a={a} />
        ))}
      </div>
    </div>
  );
}

window.QuickActionsRow = QuickActionsRow;
