// KPI cards — 4 pastel-bg cards with big mono numerals + mini chart on right
// Variants: 1=indigo, 2=emerald, 3=amber, 4=violet

function Sparkline({ data, color, fill }) {
  const w = 120, h = 44, pad = 4;
  const max = Math.max(...data), min = Math.min(...data);
  const range = max - min || 1;
  const step = (w - pad * 2) / (data.length - 1);
  const pts = data.map((v, i) => [pad + i * step, pad + (h - pad * 2) * (1 - (v - min) / range)]);
  const path = pts.map((p, i) => (i === 0 ? `M${p[0]},${p[1]}` : `L${p[0]},${p[1]}`)).join(' ');
  const area = `${path} L${pts[pts.length-1][0]},${h} L${pts[0][0]},${h} Z`;
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="overflow-visible">
      <defs>
        <linearGradient id={`sg-${color.replace('#','')}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.22" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#sg-${color.replace('#','')})`} />
      <path d={path} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      {pts.slice(-1).map((p, i) => (
        <circle key={i} cx={p[0]} cy={p[1]} r="3" fill="#fff" stroke={color} strokeWidth="2" />
      ))}
    </svg>
  );
}

function MiniBars({ data, color }) {
  const w = 120, h = 44;
  const max = Math.max(...data);
  const barW = (w - (data.length - 1) * 4) / data.length;
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`}>
      {data.map((v, i) => {
        const bh = (v / max) * (h - 6);
        return (
          <rect
            key={i}
            x={i * (barW + 4)}
            y={h - bh}
            width={barW}
            height={bh}
            rx="2"
            fill={color}
            opacity={i === data.length - 1 ? 1 : 0.55}
          />
        );
      })}
    </svg>
  );
}

function MiniDonut({ pct, color, track }) {
  const r = 18, c = 2 * Math.PI * r;
  const dash = (pct / 100) * c;
  return (
    <svg width="44" height="44" viewBox="0 0 44 44">
      <circle cx="22" cy="22" r={r} fill="none" stroke={track} strokeWidth="5" />
      <circle
        cx="22" cy="22" r={r}
        fill="none" stroke={color} strokeWidth="5"
        strokeDasharray={`${dash} ${c}`}
        strokeDashoffset={c / 4}
        strokeLinecap="round"
        transform="rotate(-90 22 22)"
      />
      <text x="22" y="25" textAnchor="middle" fontFamily="Geist Mono" fontSize="11" fontWeight="700" fill={color}>
        {pct}%
      </text>
    </svg>
  );
}

function KPICard({ variant, label, sub, sub2, value, unit, deltaPct, deltaUp, chart, icon: I }) {
  const cfg = {
    1: { bg: 'var(--kpi-1-bg)', fg: 'var(--kpi-1-fg)', iconBg: '#FFFFFF', iconFg: '#4F46E5' },
    2: { bg: 'var(--kpi-2-bg)', fg: 'var(--kpi-2-fg)', iconBg: '#FFFFFF', iconFg: '#059669' },
    3: { bg: 'var(--kpi-3-bg)', fg: 'var(--kpi-3-fg)', iconBg: '#FFFFFF', iconFg: '#D97706' },
    4: { bg: 'var(--kpi-4-bg)', fg: 'var(--kpi-4-fg)', iconBg: '#FFFFFF', iconFg: '#9333EA' },
  }[variant];

  return (
    <div
      className="relative rounded-2xl p-5 hover-lift overflow-hidden"
      style={{
        background: cfg.bg,
        boxShadow: 'var(--elevation-2)',
      }}
    >
      {/* decorative arc */}
      <svg
        className="absolute -right-8 -bottom-8 opacity-[0.35] pointer-events-none"
        width="160" height="160" viewBox="0 0 160 160"
      >
        <circle cx="80" cy="80" r="60" fill="none" stroke={cfg.fg} strokeWidth="1.2" opacity="0.35" />
        <circle cx="80" cy="80" r="80" fill="none" stroke={cfg.fg} strokeWidth="1" opacity="0.18" />
      </svg>

      {/* Header */}
      <div className="relative flex items-center gap-2.5">
        <span
          className="w-8 h-8 rounded-lg flex items-center justify-center"
          style={{ background: cfg.iconBg, color: cfg.iconFg, boxShadow: 'var(--elevation-1)' }}
        >
          <I size={16} strokeWidth={2} />
        </span>
        <div className="flex-1 min-w-0">
          <div className="text-[12.5px] font-medium leading-tight" style={{ color: 'var(--text-medium)' }}>
            {label}
          </div>
          <div className="text-[10.5px] mt-0.5 font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>
            {sub}
          </div>
        </div>
      </div>

      {/* Value + chart */}
      <div className="relative mt-4 flex items-end justify-between gap-2">
        <div>
          <div className="flex items-baseline gap-1.5">
            <span className="num text-[40px] font-bold leading-none" style={{ color: 'var(--text-strong)' }}>
              {value}
            </span>
            {unit && (
              <span className="text-[13px] font-medium" style={{ color: 'var(--text-medium)' }}>
                {unit}
              </span>
            )}
          </div>
          {deltaPct != null && (
            <div className="mt-2.5 inline-flex items-center gap-1">
              <span
                className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[11px] font-mono font-semibold"
                style={{
                  background: deltaUp ? '#DCFCE7' : '#FEE2E2',
                  color: deltaUp ? '#059669' : '#DC2626',
                }}
              >
                {deltaUp ? <IconArrowUp size={11} strokeWidth={2.5} /> : <IconArrowDown size={11} strokeWidth={2.5} />}
                {deltaPct}%
              </span>
              <span className="text-[11px]" style={{ color: 'var(--text-soft)' }}>
                较上周
              </span>
            </div>
          )}
          {sub2 && (
            <div className="mt-2.5 text-[11px]" style={{ color: 'var(--text-soft)' }}>{sub2}</div>
          )}
        </div>
        {chart && <div className="shrink-0">{chart}</div>}
      </div>
    </div>
  );
}

// Real KPI counts pulled from the backend on mount. No fabricated values / weekly deltas / sparklines
// (P1-4: these four cards used to render hardcoded 142/36/186/8 with fake 较上周 deltas).
function KPIRow() {
  const [stats, setStats] = React.useState(null);

  React.useEffect(() => {
    let alive = true;
    if (!window.api) return undefined;
    const asArray = (x) => Array.isArray(x) ? x
      : Array.isArray(x?.rows) ? x.rows
      : Array.isArray(x?.content) ? x.content
      : Array.isArray(x?.items) ? x.items
      : [];
    Promise.all([
      window.api.get('/teachers').catch(() => []),
      window.api.get('/classrooms').catch(() => []),
      window.api.get('/exam-tasks').catch(() => []),
      window.api.get('/stats/history').catch(() => ({})),
    ]).then(([teachers, classrooms, tasks, history]) => {
      if (!alive) return;
      const taskList = asArray(tasks);
      const activeTasks = taskList.filter((it) => {
        const s = String(it?.status || '').toLowerCase();
        return s && !['draft', 'archived', 'cancelled', 'canceled', 'completed', 'closed'].includes(s);
      }).length;
      setStats({
        teachers: asArray(teachers).length,
        classrooms: asArray(classrooms).length,
        tasks: taskList.length,
        activeTasks,
        sessions: Number(history?.totals?.count) || 0,
      });
    });
    return () => { alive = false; };
  }, []);

  const show = (n) => (stats == null ? '—' : String(n));

  return (
    <div className="grid grid-cols-4 gap-5">
      <KPICard
        variant={1}
        icon={IconList}
        label="考务任务"
        sub="EXAM TASKS"
        value={show(stats?.tasks)}
        unit="个"
        sub2={stats == null ? null : `进行中 ${stats.activeTasks}`}
      />
      <KPICard
        variant={2}
        icon={IconCalendar}
        label="本学期累计监考"
        sub="TOTAL SUPERVISIONS"
        value={show(stats?.sessions)}
        unit="人次"
      />
      <KPICard
        variant={3}
        icon={IconUsers}
        label="在职教职工"
        sub="FACULTY"
        value={show(stats?.teachers)}
        unit="人"
      />
      <KPICard
        variant={4}
        icon={IconBuilding}
        label="可用教室"
        sub="CLASSROOMS"
        value={show(stats?.classrooms)}
        unit="间"
      />
    </div>
  );
}

Object.assign(window, { KPICard, KPIRow, Sparkline, MiniBars, MiniDonut });
