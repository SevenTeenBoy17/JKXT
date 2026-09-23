// Premium UI primitives for SUPER ADMIN mode
// Exports: FeatureKPI, RadialGauge, ActivityRow, HealthBar, RangePill,
//          SuperHero, GlassPanel, MetricChip, TenantBadge

const resolveFeatureGlyph = (glyph, Icn) => {
  if (glyph) return glyph;
  if (Icn === IconBuilding) return 'availableRooms';
  if (Icn === IconUsers) return 'activeStaff';
  if (Icn === IconShield) return 'security';
  if (Icn === IconClock) return 'supervisionHours';
  if (Icn === IconList) return 'examTasks';
  if (Icn === IconAlert) return 'taskConflict';
  if (Icn === IconCheck) return 'taskCompleted';
  if (Icn === IconChart || Icn === IconArrowUp) return 'allowanceTotal';
  if (Icn === IconCalendar) return 'termSupervisions';
  if (Icn === IconArchive) return 'archive';
  if (Icn === IconSettings) return 'settings';
  if (Icn === IconBell) return 'announcement';
  return 'dashboard';
};

// ── FeatureKPI — calm operational KPI with a semantic filled pictogram ──
function FeatureKPI({
  label, sublabel, value, unit, footnote, icon: Icn,
  gradient, accent = '#4F46E5', orbColor = 'rgba(255,255,255,.4)', glyph,
  onClick, large = false,
}) {
  const semanticGlyph = resolveFeatureGlyph(glyph, Icn);
  return (
    <div
      onClick={onClick}
      onKeyDown={onClick ? (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onClick(event);
        }
      } : undefined}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      className={`feature-kpi relative overflow-hidden ${large ? 'min-h-[128px] lg:min-h-[180px]' : 'min-h-[112px] lg:min-h-[156px]'}${onClick ? ' cursor-pointer' : ''}`}
      style={{
        padding: large ? 22 : 18,
        '--feature-accent': accent,
      }}
    >
      <div className="relative flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[14px] font-semibold leading-5" style={{ color: 'var(--text-strong)' }}>
            {label}
          </div>
          {sublabel && (
            <div className="text-[12px] leading-[18px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
              {sublabel}
            </div>
          )}
        </div>
        <span
          className="feature-kpi__glyph flex items-center justify-center shrink-0"
          data-semantic-glyph={semanticGlyph}
        >
          <ProductGlyph name={semanticGlyph} size={38} />
        </span>
      </div>

      <div className="relative mt-5 flex items-baseline gap-1.5">
        <span
          className="num font-bold tracking-tight"
          style={{
            fontSize: large ? 46 : 40,
            color: 'var(--text-strong)',
            letterSpacing: '-0.03em',
            lineHeight: 1,
          }}
        >
          {value}
        </span>
        {unit && (
          <span className="text-[14px] font-medium ml-0.5" style={{ color: 'var(--text-medium)' }}>
            {unit}
          </span>
        )}
      </div>
      {footnote && (
        <div className="relative mt-2 text-[12px] leading-[18px] font-medium" style={{ color: 'var(--text-soft)' }}>
          {footnote}
        </div>
      )}
    </div>
  );
}

// ── RadialGauge — security score, etc. ─────────────────────────────
function RadialGauge({ value, max = 100, label, sublabel, color, size = 150, thickness = 12, valueColor }) {
  const R = (size - thickness) / 2;
  const C = 2 * Math.PI * R;
  const pct = Math.max(0, Math.min(1, value / max));
  const dash = `${pct * C} ${C}`;
  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <defs>
          <linearGradient id={`gauge-${label}`} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%"  stopColor={color || '#10B981'} stopOpacity="1" />
            <stop offset="100%" stopColor={color || '#34D399'} stopOpacity=".85" />
          </linearGradient>
          <filter id={`glow-${label}`} x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="2" />
          </filter>
        </defs>
        {/* Track */}
        <circle cx={size/2} cy={size/2} r={R} fill="none" stroke="#F1F5F9" strokeWidth={thickness} />
        {/* Progress */}
        <circle cx={size/2} cy={size/2} r={R} fill="none"
          stroke={`url(#gauge-${label})`} strokeWidth={thickness}
          strokeDasharray={dash} strokeLinecap="round"
          transform={`rotate(-90 ${size/2} ${size/2})`}
          style={{ transition: 'stroke-dasharray 800ms cubic-bezier(.2,.8,.2,1)' }} />
        {/* End cap dot */}
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center text-center">
        <span className="num font-bold tracking-tight" style={{
          fontSize: size * 0.30,
          color: valueColor || color || '#10B981',
          letterSpacing: '-0.03em',
          lineHeight: 1,
        }}>{value}</span>
        {sublabel && (
          <span className="font-mono uppercase tracking-[.18em] mt-1"
            style={{ fontSize: size * 0.072, color: 'var(--text-soft)' }}>{sublabel}</span>
        )}
        {label && (
          <span className="font-medium mt-0.5" style={{ fontSize: size * 0.085, color: 'var(--text-medium)' }}>
            {label}
          </span>
        )}
      </div>
    </div>
  );
}

// ── ActivityRow — meeting-list-style entry for activity/log feeds ───
function ActivityRow({ when, whenSub, title, meta, accent, icon: Icn, severity, onClick }) {
  const sevColors = {
    info: { bg: '#EEF2FF', fg: '#4F46E5' },
    warn: { bg: '#FFF7ED', fg: '#D97706' },
    high: { bg: '#FEE2E2', fg: '#DC2626' },
    success: { bg: '#ECFDF5', fg: '#059669' },
  };
  const c = sevColors[severity] || sevColors.info;
  return (
    <div
      onClick={onClick}
      className="group flex items-center gap-4 py-3.5 cursor-pointer transition-colors"
      style={{ borderBottom: '1px solid var(--border-soft)' }}
      onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(248,250,252,.6)')}
      onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
    >
      <div className="w-[88px] shrink-0">
        <div className="font-mono text-[11.5px] font-medium" style={{ color: 'var(--text-medium)' }}>{when}</div>
        {whenSub && (
          <div className="font-mono text-[12.5px] font-semibold mt-0.5" style={{ color: 'var(--text-strong)' }}>{whenSub}</div>
        )}
      </div>
      {Icn && (
        <span className="w-9 h-9 rounded-xl shrink-0 inline-flex items-center justify-center"
          style={{ background: c.bg, color: c.fg }}>
          <Icn size={15} strokeWidth={2} />
        </span>
      )}
      <div className="flex-1 min-w-0">
        <div className="text-[13.5px] font-semibold leading-tight truncate"
          style={{ color: 'var(--text-strong)' }}>{title}</div>
        {meta && (
          <div className="text-[11.5px] mt-1 truncate" style={{ color: 'var(--text-soft)' }}>{meta}</div>
        )}
      </div>
      <span className="w-7 h-7 rounded-full inline-flex items-center justify-center shrink-0 transition-all"
        style={{ background: 'transparent', color: 'var(--text-soft)' }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M7 17 L17 7" />
          <path d="M9 7 H17 V15" />
        </svg>
      </span>
    </div>
  );
}

// ── HealthBar — skill-bar-style indicator (reference's progress + %) ──
function HealthBar({ label, value, unit = '%', color = '#4F46E5', trend, sub, icon: Icn }) {
  const pct = Math.max(0, Math.min(100, value));
  const trendUp = trend === 'up';
  const trendColor = trend === 'up' ? '#10B981' : trend === 'down' ? '#EF4444' : '#94A3B8';
  return (
    <div className="flex items-center gap-3 py-2.5">
      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between mb-1.5">
          <div className="flex items-center gap-2 min-w-0">
            <span className="text-[13px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>{label}</span>
            {sub && (
              <span className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>{sub}</span>
            )}
          </div>
          <span className="num font-mono text-[12.5px] font-semibold shrink-0 ml-2" style={{ color: 'var(--text-strong)' }}>
            {value}{unit}
          </span>
        </div>
        <div className="h-[6px] rounded-full overflow-hidden" style={{ background: '#F1F5F9' }}>
          <div className="h-full rounded-full transition-all"
            style={{
              width: `${pct}%`,
              background: `linear-gradient(90deg, ${color}, ${color}DD)`,
              boxShadow: `0 0 0 1px ${color}33 inset, 0 1px 2px ${color}55`,
            }} />
        </div>
      </div>
      {trend && (
        <span className="w-7 h-7 rounded-full inline-flex items-center justify-center shrink-0"
          style={{ background: trendColor + '15', color: trendColor }}>
          {trendUp
            ? <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M7 14 L12 9 L17 14" /></svg>
            : <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M7 10 L12 15 L17 10" /></svg>}
        </span>
      )}
    </div>
  );
}

// ── RangePill — refined dropdown-style filter (reference) ─────────
function RangePill({ label, value, onClick }) {
  return (
    <button
      onClick={onClick}
      className="inline-flex items-center gap-1.5 h-8 px-3 rounded-full transition-all"
      style={{
        background: '#fff',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 2px rgba(15,23,42,.04), 0 2px 6px rgba(15,23,42,.04)',
      }}
      onMouseEnter={(e) => { e.currentTarget.style.borderColor = '#CBD5E1'; e.currentTarget.style.transform = 'translateY(-1px)'; }}
      onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--border-soft)'; e.currentTarget.style.transform = 'translateY(0)'; }}
    >
      {label && <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>{label}</span>}
      <span className="text-[12.5px] font-semibold" style={{ color: 'var(--text-strong)' }}>{value}</span>
      <IconChevron dir="down" size={12} className="text-slate-400" />
    </button>
  );
}

// ── GlassPanel — refined white panel w/ subtle top highlight ────────
function GlassPanel({ children, className = '', style = {} }) {
  return (
    <div
      className={`relative rounded-2xl ${className}`}
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow:
          '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 4px 12px -4px rgba(15,23,42,.08), 0 16px 36px -16px rgba(15,23,42,.10)',
        ...style,
      }}
    >
      {/* Top edge specular highlight */}
      <span className="absolute inset-x-0 top-0 h-px rounded-t-2xl pointer-events-none"
        style={{ background: 'linear-gradient(90deg, transparent, rgba(255,255,255,.92), transparent)' }} />
      {children}
    </div>
  );
}

// ── MetricChip — inline metric with icon & value (for hero strip) ──
function MetricChip({ icon: Icn, label, value, color = 'rgba(255,255,255,.95)' }) {
  return (
    <div
      className="inline-flex items-center gap-2.5 px-3.5 py-2 rounded-xl"
      style={{
        background: 'rgba(255,255,255,.12)',
        border: '1px solid rgba(255,255,255,.18)',
        backdropFilter: 'blur(8px)',
      }}
    >
      {Icn && (
        <span className="w-7 h-7 rounded-lg inline-flex items-center justify-center"
          style={{ background: 'rgba(255,255,255,.18)', color: '#fff' }}>
          <Icn size={13} strokeWidth={2} />
        </span>
      )}
      <div>
        <div className="text-[10px] uppercase tracking-[.16em] font-mono" style={{ color: 'rgba(255,255,255,.72)' }}>
          {label}
        </div>
        <div className="num text-[15px] font-bold mt-0.5" style={{ color }}>
          {value}
        </div>
      </div>
    </div>
  );
}

// ── TenantBadge — slug-based square avatar for tenants ──────────────
function TenantBadge({ slug, name, size = 36, gradient }) {
  // derive a deterministic gradient from slug
  const tones = [
    ['#A78BFA', '#7C3AED'],
    ['#60A5FA', '#2563EB'],
    ['#34D399', '#059669'],
    ['#F472B6', '#DB2777'],
    ['#FBBF24', '#D97706'],
    ['#22D3EE', '#0891B2'],
    ['#F87171', '#DC2626'],
  ];
  const seed = (slug || name || 'x').split('').reduce((a, c) => a + c.charCodeAt(0), 0);
  const [c1, c2] = gradient || tones[seed % tones.length];
  return (
    <span
      className="inline-flex items-center justify-center rounded-xl font-mono font-bold shrink-0 text-white"
      style={{
        width: size, height: size,
        background: `linear-gradient(135deg, ${c1}, ${c2})`,
        fontSize: size * 0.32,
        boxShadow: `0 6px 16px -4px ${c2}66, inset 0 1px 0 rgba(255,255,255,.4)`,
        letterSpacing: '0.04em',
      }}
    >
      {(slug || name).toUpperCase().slice(0, 3)}
    </span>
  );
}

Object.assign(window, {
  FeatureKPI, RadialGauge, ActivityRow, HealthBar, RangePill,
  GlassPanel, MetricChip, TenantBadge,
});
