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

function KPICard({ variant, label, sub, sub2, value, unit, deltaPct, deltaUp, chart, icon: I, glyph }) {
  const cfg = {
    1: { bg: 'var(--kpi-1-bg)', fg: 'var(--kpi-1-fg)', iconBg: '#FFFFFF', iconFg: '#4F46E5' },
    2: { bg: 'var(--kpi-2-bg)', fg: 'var(--kpi-2-fg)', iconBg: '#FFFFFF', iconFg: '#059669' },
    3: { bg: 'var(--kpi-3-bg)', fg: 'var(--kpi-3-fg)', iconBg: '#FFFFFF', iconFg: '#D97706' },
    4: { bg: 'var(--kpi-4-bg)', fg: 'var(--kpi-4-fg)', iconBg: '#FFFFFF', iconFg: '#9333EA' },
  }[variant];

  return (
    <div
      className="dashboard-kpi-card relative p-5 overflow-hidden"
      style={{
        '--kpi-accent': cfg.fg,
      }}
    >
      {/* Header */}
      <div className="relative flex items-center gap-2.5">
        <span
          className="dashboard-kpi-card__icon flex items-center justify-center"
          data-semantic-glyph={glyph || undefined}
          style={!glyph ? { background: `${cfg.iconFg}12`, color: cfg.iconFg } : undefined}
        >
          {glyph ? <ProductGlyph name={glyph} size={32} /> : <I size={16} strokeWidth={2} />}
        </span>
        <div className="flex-1 min-w-0">
          <div className="text-[13px] font-medium leading-5" style={{ color: 'var(--text-medium)' }}>
            {label}
          </div>
          {sub && sub !== label && (
            <div className="text-[12px] leading-[18px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{sub}</div>
          )}
        </div>
      </div>

      {/* Value + chart */}
      <div className="relative mt-4 flex items-end justify-between gap-2">
        <div>
          <div className="flex items-baseline gap-1.5">
            <span className="num text-[36px] font-bold leading-[40px]" style={{ color: 'var(--text-strong)' }}>
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
                className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[12px] font-mono font-semibold"
                style={{
                  background: deltaUp ? '#DCFCE7' : '#FEE2E2',
                  color: deltaUp ? '#059669' : '#DC2626',
                }}
              >
                {deltaUp ? <IconArrowUp size={11} strokeWidth={2.5} /> : <IconArrowDown size={11} strokeWidth={2.5} />}
                {deltaPct}%
              </span>
              <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>
                较上周
              </span>
            </div>
          )}
          {sub2 && (
            <div className="mt-2.5 text-[12px] leading-[18px]" style={{ color: 'var(--text-soft)' }}>{sub2}</div>
          )}
        </div>
        {chart && <div className="shrink-0">{chart}</div>}
      </div>
    </div>
  );
}

// Real KPI counts pulled from the backend on mount. No fabricated values / weekly deltas / sparklines
// (P1-4: these four cards used to render hardcoded 142/36/186/8 with fake 较上周 deltas).
// Match the real latest-job status used by the recent-task list. A scheduled,
// published or failed task is not a currently running solve.
function dashboardMetrics(teachers, classrooms, tasks, history) {
  const rows = (value) => {
    const result = Array.isArray(value) ? value
      : [value?.rows, value?.content, value?.items].find(Array.isArray);
    return result?.every((row) => row && typeof row === 'object' && !Array.isArray(row)) ? result : null;
  };
  const teacherRows = rows(teachers);
  const roomRows = rows(classrooms);
  const taskRows = rows(tasks);
  const count = history?.totals?.count;
  return {
    teachers: teacherRows === null ? null : teacherRows.filter((row) => row.status === 'active').length,
    classrooms: roomRows === null ? null : roomRows.filter((row) => ['active', 'available'].includes(row.status)).length,
    tasks: taskRows?.length ?? null,
    runningTasks: taskRows === null ? null : taskRows.filter((row) =>
      ['scheduling', 'running', 'pending'].includes(String(row.latestJob?.status || row.status || '').toLowerCase())).length,
    sessions: typeof count === 'number' && Number.isFinite(count) && count >= 0 ? count : null,
  };
}

function KPIRow() {
  const [stats, setStats] = React.useState(null);

  React.useEffect(() => {
    let alive = true;
    if (!window.api?.get) { setStats(dashboardMetrics(null, null, null, null)); return undefined; }
    Promise.all([
      window.api.get('/teachers').catch(() => null),
      window.api.get('/classrooms').catch(() => null),
      window.api.get('/exam-tasks').catch(() => null),
      window.api.get('/stats/history').catch(() => null),
    ]).then(([teachers, classrooms, tasks, history]) => {
      if (!alive) return;
      setStats(dashboardMetrics(teachers, classrooms, tasks, history));
    });
    return () => { alive = false; };
  }, []);

  const show = (n) => (n == null ? '—' : String(n));
  const unavailable = (key) => stats !== null && stats[key] === null ? '读取失败，请刷新重试' : null;

  return (
    <div className="dashboard-kpi-strip grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-0">
      <KPICard
        variant={1}
        icon={IconList}
        glyph="examTasks"
        label="考务任务"
        sub="考务任务"
        value={show(stats?.tasks)}
        unit="个"
        sub2={unavailable('tasks') || (stats == null ? null : `进行中 ${stats.runningTasks}`)}
      />
      <KPICard
        variant={2}
        icon={IconCalendar}
        glyph="termSupervisions"
        label="已发布任务监考"
        sub="监考人次"
        value={show(stats?.sessions)}
        unit="人次"
        sub2={unavailable('sessions') || '仅旧任务最近成功排班'}
      />
      <KPICard
        variant={3}
        icon={IconUsers}
        glyph="activeStaff"
        label="在职教职工"
        sub="在职教职工"
        value={show(stats?.teachers)}
        unit="人"
        sub2={unavailable('teachers')}
      />
      <KPICard
        variant={4}
        icon={IconBuilding}
        glyph="availableRooms"
        label="可用教室"
        sub="可用教室"
        value={show(stats?.classrooms)}
        unit="间"
        sub2={unavailable('classrooms')}
      />
    </div>
  );
}

Object.assign(window, { KPICard, KPIRow, Sparkline, MiniBars, MiniDonut });
