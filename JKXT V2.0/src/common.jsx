// Common primitives shared across all pages
// Exports: PageShell, SectionTitle, FilterBar, DataTable, MiniKPI, Pagination,
//          StatusDot, EmptyState, Toolbar, Button, Tag, AvatarChip

// ── Button ─────────────────────────────────────────────────────────
function Button({
  variant = 'secondary', size = 'md', icon: Icn, children,
  loading, danger, ...rest
}) {
  const sizes = {
    sm: { h: 28, px: 10, fs: 12 },
    md: { h: 36, px: 14, fs: 13 },
    lg: { h: 40, px: 18, fs: 14 },
  }[size];

  // 3D gradient styles per variant
  const styles = {
    primary: {
      bg: 'linear-gradient(180deg, var(--brand) 0%, var(--brand-hover) 100%)',
      hoverBg: 'linear-gradient(180deg, var(--brand-hover) 0%, var(--brand) 100%)',
      color: '#fff',
      border: '1px solid var(--brand-hover)',
      shadow: '0 1px 0 rgba(255,255,255,.25) inset, 0 -1px 0 rgba(0,0,0,.12) inset, 0 1px 2px rgba(15,23,42,.10), 0 4px 10px -2px var(--brand-soft)',
      shadowHover: '0 1px 0 rgba(255,255,255,.25) inset, 0 -1px 0 rgba(0,0,0,.12) inset, 0 2px 4px rgba(15,23,42,.14), 0 8px 18px -4px var(--brand-soft)',
      shadowActive: '0 1px 3px rgba(0,0,0,.18) inset, 0 1px 1px rgba(15,23,42,.10)',
    },
    secondary: {
      bg: 'linear-gradient(180deg, #FFFFFF 0%, #F8FAFC 100%)',
      hoverBg: 'linear-gradient(180deg, #FFFFFF 0%, #F1F5F9 100%)',
      color: 'var(--text-medium)',
      border: '1px solid var(--border-soft)',
      shadow: '0 1px 0 rgba(255,255,255,.85) inset, 0 1px 2px rgba(15,23,42,.05), 0 1px 3px rgba(15,23,42,.04)',
      shadowHover: '0 1px 0 rgba(255,255,255,.85) inset, 0 2px 4px rgba(15,23,42,.08), 0 4px 10px -2px rgba(15,23,42,.06)',
      shadowActive: '0 1px 2px rgba(15,23,42,.10) inset',
    },
    ghost: {
      bg: 'transparent',
      hoverBg: 'linear-gradient(180deg, #F8FAFC 0%, #F1F5F9 100%)',
      color: 'var(--text-medium)',
      border: '1px solid transparent',
      shadow: 'none',
      shadowHover: '0 1px 2px rgba(15,23,42,.04)',
      shadowActive: '0 1px 2px rgba(15,23,42,.08) inset',
    },
    danger: {
      bg: 'linear-gradient(180deg, #EF4444 0%, #DC2626 100%)',
      hoverBg: 'linear-gradient(180deg, #DC2626 0%, #B91C1C 100%)',
      color: '#fff',
      border: '1px solid #B91C1C',
      shadow: '0 1px 0 rgba(255,255,255,.22) inset, 0 -1px 0 rgba(0,0,0,.12) inset, 0 1px 2px rgba(220,38,38,.18), 0 4px 10px -2px rgba(220,38,38,.32)',
      shadowHover: '0 1px 0 rgba(255,255,255,.22) inset, 0 -1px 0 rgba(0,0,0,.12) inset, 0 2px 4px rgba(220,38,38,.22), 0 8px 18px -4px rgba(220,38,38,.40)',
      shadowActive: '0 1px 3px rgba(0,0,0,.18) inset',
    },
    soft: {
      bg: 'linear-gradient(180deg, var(--brand-soft) 0%, #E0E7FF 100%)',
      hoverBg: 'linear-gradient(180deg, #E0E7FF 0%, var(--brand-soft) 100%)',
      color: 'var(--brand)',
      border: '1px solid rgba(99,102,241,.18)',
      shadow: '0 1px 0 rgba(255,255,255,.6) inset, 0 1px 2px rgba(79,70,229,.08)',
      shadowHover: '0 1px 0 rgba(255,255,255,.6) inset, 0 4px 10px -2px rgba(79,70,229,.16)',
      shadowActive: '0 1px 2px rgba(79,70,229,.16) inset',
    },
  }[danger ? 'danger' : variant];

  return (
    <button
      {...rest}
      style={{
        height: sizes.h,
        padding: `0 ${sizes.px}px`,
        fontSize: sizes.fs,
        background: styles.bg,
        color: styles.color,
        border: styles.border,
        borderRadius: 8,
        fontWeight: 500,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        whiteSpace: 'nowrap',
        boxShadow: styles.shadow,
        transition: 'background 160ms var(--ease), box-shadow 200ms var(--ease), transform 100ms var(--ease)',
        cursor: 'pointer',
        ...rest.style,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.background = styles.hoverBg;
        e.currentTarget.style.boxShadow = styles.shadowHover;
        e.currentTarget.style.transform = 'translateY(-1px)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = styles.bg;
        e.currentTarget.style.boxShadow = styles.shadow;
        e.currentTarget.style.transform = 'translateY(0)';
      }}
      onMouseDown={(e) => { e.currentTarget.style.transform = 'translateY(0) scale(0.97)'; e.currentTarget.style.boxShadow = styles.shadowActive; }}
      onMouseUp={(e)   => { e.currentTarget.style.transform = 'translateY(-1px) scale(1)'; e.currentTarget.style.boxShadow = styles.shadowHover; }}
    >
      {Icn && <Icn size={size === 'sm' ? 13 : 14} strokeWidth={2} />}
      {children}
    </button>
  );
}

// ── PageShell ──────────────────────────────────────────────────────
function PageShell({ crumbs = [], title, badge, subtitle, actions, children }) {
  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between gap-4">
        <div className="min-w-0">
          <nav className="flex items-center gap-1.5 text-[12.5px]">
            {crumbs.map((c, i) => (
              <React.Fragment key={i}>
                <span
                  className={i === crumbs.length - 1 ? '' : 'hover:underline cursor-pointer'}
                  style={{
                    color: i === crumbs.length - 1 ? 'var(--text-strong)' : 'var(--text-soft)',
                    fontWeight: i === crumbs.length - 1 ? 500 : 400,
                  }}
                >
                  {c}
                </span>
                {i < crumbs.length - 1 && (
                  <IconChevron dir="right" size={10} className="text-slate-300" />
                )}
              </React.Fragment>
            ))}
          </nav>
          <div className="mt-3 flex items-baseline gap-3 flex-wrap">
            <h1 className="text-[26px] font-bold leading-none tracking-tight" style={{ color: 'var(--text-strong)' }}>
              {title}
            </h1>
            {badge && (
              <span
                className="font-mono text-[11px] px-2 py-0.5 rounded-md"
                style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
              >
                {badge}
              </span>
            )}
          </div>
          {subtitle && (
            <div className="mt-2 text-[13px]" style={{ color: 'var(--text-medium)' }}>
              {subtitle}
            </div>
          )}
        </div>
        {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
      </div>
      {children}
    </div>
  );
}

// ── SectionTitle ───────────────────────────────────────────────────
function SectionTitle({ title, sub, right }) {
  return (
    <div className="flex items-end justify-between mb-4">
      <div>
        <h3 className="text-[15px] font-semibold" style={{ color: 'var(--text-strong)' }}>
          {title}
        </h3>
        {sub && (
          <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            {sub}
          </div>
        )}
      </div>
      {right}
    </div>
  );
}

// ── MiniKPI (small KPI card for page tops) ─────────────────────────
function MiniKPI({ label, value, unit, trend, trendUp, accent = '#4F46E5', icon: Icn, sub }) {
  return (
    <div
      className="relative rounded-xl p-4 flex items-center gap-3.5 transition-all overflow-hidden"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 2px rgba(15,23,42,.04), 0 6px 16px -8px rgba(15,23,42,.08)',
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.transform = 'translateY(-2px)';
        e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.9) inset, 0 4px 8px rgba(15,23,42,.06), 0 16px 32px -8px rgba(15,23,42,.14)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = 'translateY(0)';
        e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 2px rgba(15,23,42,.04), 0 6px 16px -8px rgba(15,23,42,.08)';
      }}
    >
      {/* Top highlight line */}
      <span className="absolute inset-x-0 top-0 h-px pointer-events-none"
        style={{ background: 'linear-gradient(90deg, transparent, rgba(255,255,255,.85), transparent)' }} />
      {Icn && (
        <div
          className="relative w-10 h-10 rounded-lg flex items-center justify-center shrink-0"
          style={{
            background: `linear-gradient(180deg, ${accent}1F, ${accent}10)`,
            color: accent,
            boxShadow: `0 1px 0 rgba(255,255,255,.6) inset, 0 -1px 0 ${accent}1A inset, 0 2px 4px -1px ${accent}26`,
            border: `1px solid ${accent}26`,
          }}
        >
          <Icn size={18} strokeWidth={2} />
        </div>
      )}
      <div className="min-w-0 flex-1 relative">
        <div className="text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
          {label}
        </div>
        <div className="mt-0.5 flex items-baseline gap-1.5">
          <span className="num text-[22px] font-bold tracking-tight" style={{ color: 'var(--text-strong)', letterSpacing: '-0.02em' }}>
            {value}
          </span>
          {unit && (
            <span className="text-[12px]" style={{ color: 'var(--text-medium)' }}>
              {unit}
            </span>
          )}
          {trend !== undefined && (
            <span
              className="ml-auto inline-flex items-center gap-0.5 font-mono text-[11px] font-semibold px-1.5 py-0.5 rounded"
              style={{
                color: trendUp ? '#059669' : '#DC2626',
                background: trendUp ? '#ECFDF5' : '#FEE2E2',
                border: `1px solid ${trendUp ? '#A7F3D0' : '#FECACA'}`,
              }}
            >
              {trendUp ? <IconArrowUp size={11} strokeWidth={2.5} /> : <IconArrowDown size={11} strokeWidth={2.5} />}
              {trend}%
            </span>
          )}
        </div>
        {sub && (
          <div className="text-[10.5px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>
            {sub}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Tag (small label) ──────────────────────────────────────────────
function Tag({ children, color = 'slate', dot, soft = true }) {
  const colors = {
    slate:   { bg: '#F1F5F9', fg: '#475569', dotC: '#94A3B8' },
    indigo:  { bg: '#EEF2FF', fg: '#4F46E5', dotC: '#6366F1' },
    emerald: { bg: '#ECFDF5', fg: '#047857', dotC: '#10B981' },
    amber:   { bg: '#FFF7ED', fg: '#B45309', dotC: '#F59E0B' },
    rose:    { bg: '#FEE2E2', fg: '#B91C1C', dotC: '#EF4444' },
    sky:     { bg: '#E0F2FE', fg: '#0369A1', dotC: '#0EA5E9' },
    violet:  { bg: '#FAF5FF', fg: '#7C3AED', dotC: '#9333EA' },
    pink:    { bg: '#FCE7F3', fg: '#BE185D', dotC: '#EC4899' },
    cyan:    { bg: '#ECFEFF', fg: '#0E7490', dotC: '#06B6D4' },
  }[color] || { bg: '#F1F5F9', fg: '#475569', dotC: '#94A3B8' };
  return (
    <span
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-[11px] font-medium whitespace-nowrap"
      style={{ background: soft ? colors.bg : colors.dotC, color: soft ? colors.fg : '#fff' }}
    >
      {dot && <span className="w-1.5 h-1.5 rounded-full" style={{ background: colors.dotC }} />}
      {children}
    </span>
  );
}

// ── FilterBar ──────────────────────────────────────────────────────
function FilterBar({ search, filters = [], total, onReset, right }) {
  return (
    <div
      className="relative rounded-xl p-3 flex items-center gap-2 flex-wrap"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 2px rgba(15,23,42,.04)',
      }}
    >
      {search !== undefined && (
        <div className="relative flex items-center" style={{ width: 240 }}>
          <IconSearch size={14} className="absolute left-3 text-slate-400" />
          <input
            type="text"
            placeholder={search}
            className="w-full h-9 pl-9 pr-3 rounded-lg text-[13px] outline-none transition-colors"
            style={{
              background: '#F8FAFC',
              border: '1px solid transparent',
              color: 'var(--text-strong)',
            }}
            onFocus={(e) => { e.target.style.background = '#fff'; e.target.style.border = '1px solid var(--brand)'; e.target.style.boxShadow = 'var(--shadow-focus)'; }}
            onBlur={(e)  => { e.target.style.background = '#F8FAFC'; e.target.style.border = '1px solid transparent'; e.target.style.boxShadow = 'none'; }}
          />
        </div>
      )}
      {filters.map((f, i) => (
        <button
          key={i}
          className="h-9 px-3 rounded-lg inline-flex items-center gap-1.5 text-[13px] transition-all"
          style={{
            border: '1px solid var(--border-soft)',
            color: 'var(--text-medium)',
            background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
            boxShadow: '0 1px 0 rgba(255,255,255,.85) inset, 0 1px 2px rgba(15,23,42,.04)',
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.background = 'linear-gradient(180deg, #FFFFFF 0%, #F1F5F9 100%)';
            e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.85) inset, 0 2px 4px rgba(15,23,42,.06), 0 4px 10px -2px rgba(15,23,42,.04)';
            e.currentTarget.style.transform = 'translateY(-1px)';
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.background = 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)';
            e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.85) inset, 0 1px 2px rgba(15,23,42,.04)';
            e.currentTarget.style.transform = 'translateY(0)';
          }}
        >
          <span style={{ color: 'var(--text-soft)' }}>{f.label}</span>
          <span className="font-medium" style={{ color: 'var(--text-strong)' }}>{f.value}</span>
          <IconChevron size={12} className="text-slate-400" />
        </button>
      ))}
      {onReset && (
        <button
          onClick={onReset}
          className="h-9 px-2.5 rounded-lg text-[12px] font-medium hover:bg-slate-50 transition-colors"
          style={{ color: 'var(--text-soft)' }}
        >
          清空筛选
        </button>
      )}
      <div className="flex-1" />
      {total !== undefined && (
        <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>
          共 <span className="font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{total}</span> 条
        </span>
      )}
      {right}
    </div>
  );
}

// ── DataTable ──────────────────────────────────────────────────────
function DataTable({ columns, rows, selectable, onRowClick, footer, density = 'comfortable' }) {
  const [selected, setSelected] = React.useState(new Set());
  const rowH = density === 'compact' ? 44 : 56;
  const toggleAll = () => {
    if (selected.size === rows.length) setSelected(new Set());
    else setSelected(new Set(rows.map((_, i) => i)));
  };
  const toggleOne = (i) => {
    const s = new Set(selected);
    s.has(i) ? s.delete(i) : s.add(i);
    setSelected(s);
  };
  const allChecked = selected.size === rows.length && rows.length > 0;
  return (
    <div
      className="relative rounded-xl overflow-hidden"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 8px 24px -8px rgba(15,23,42,.08)',
      }}
    >
      <div className="overflow-x-auto">
        <table className="w-full" style={{ fontSize: 13 }}>
          <thead>
            <tr style={{ background: '#FAFBFC', borderBottom: '1px solid var(--border-soft)' }}>
              {selectable && (
                <th className="px-3 py-3 text-left" style={{ width: 44 }}>
                  <Checkbox checked={allChecked} indeterminate={selected.size > 0 && !allChecked} onChange={toggleAll} />
                </th>
              )}
              {columns.map((col, i) => (
                <th
                  key={i}
                  className="px-4 py-3 text-left font-medium whitespace-nowrap"
                  style={{
                    color: 'var(--text-soft)',
                    fontSize: 12,
                    textAlign: col.align || 'left',
                    width: col.width,
                  }}
                >
                  <span className="inline-flex items-center gap-1">
                    {col.label}
                    {col.sortable && (
                      <span className="inline-flex flex-col">
                        <svg width="8" height="5" viewBox="0 0 8 5" fill="none"><path d="M1 4 L4 1 L7 4" stroke="#CBD5E1" strokeWidth="1.5" strokeLinecap="round" /></svg>
                      </span>
                    )}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, ri) => (
              <tr
                key={ri}
                className="transition-colors group"
                style={{
                  borderBottom: ri < rows.length - 1 ? '1px solid var(--border-soft)' : 'none',
                  background: selected.has(ri) ? 'var(--brand-soft)' : 'transparent',
                  cursor: onRowClick ? 'pointer' : 'default',
                  height: rowH,
                }}
                onMouseEnter={(e) => { if (!selected.has(ri)) e.currentTarget.style.background = '#F8FAFC'; }}
                onMouseLeave={(e) => { if (!selected.has(ri)) e.currentTarget.style.background = 'transparent'; }}
                onClick={() => onRowClick?.(row, ri)}
              >
                {selectable && (
                  <td className="px-3" onClick={(e) => { e.stopPropagation(); toggleOne(ri); }}>
                    <Checkbox checked={selected.has(ri)} onChange={() => {}} />
                  </td>
                )}
                {columns.map((col, ci) => (
                  <td
                    key={ci}
                    className="px-4"
                    style={{
                      color: 'var(--text-medium)',
                      textAlign: col.align || 'left',
                      fontFamily: col.mono ? 'Geist Mono, monospace' : undefined,
                      fontVariantNumeric: col.mono ? 'tabular-nums' : undefined,
                      whiteSpace: col.wrap ? 'normal' : 'nowrap',
                    }}
                  >
                    {col.render ? col.render(row, ri) : row[col.key]}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {footer || <Pagination total={rows.length} />}
    </div>
  );
}

// ── Checkbox ───────────────────────────────────────────────────────
function Checkbox({ checked, indeterminate, onChange }) {
  return (
    <span
      onClick={onChange}
      className="inline-flex items-center justify-center w-4 h-4 rounded transition-colors cursor-pointer"
      style={{
        background: checked ? 'var(--brand)' : '#fff',
        border: `1.5px solid ${checked ? 'var(--brand)' : '#CBD5E1'}`,
      }}
    >
      {checked && !indeterminate && <IconCheck size={11} strokeWidth={3} className="text-white" />}
      {indeterminate && <span className="w-2 h-0.5 bg-white rounded-sm" />}
    </span>
  );
}

// ── Pagination ─────────────────────────────────────────────────────
function Pagination({ total, page = 1, pageSize = 10 }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <div
      className="px-4 py-3 flex items-center justify-between"
      style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}
    >
      <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>
        显示 <span className="font-mono">1–{Math.min(pageSize, total)}</span> 共 <span className="font-mono">{total}</span> 条
      </span>
      <div className="flex items-center gap-1">
        <button className="w-7 h-7 rounded-md inline-flex items-center justify-center hover:bg-slate-100" style={{ color: 'var(--text-soft)' }}>
          <IconChevron dir="left" size={14} />
        </button>
        {Array.from({ length: Math.min(pages, 5) }).map((_, i) => {
          const n = i + 1;
          const active = n === page;
          return (
            <button
              key={i}
              className="w-7 h-7 rounded-md font-mono text-[12px] font-medium transition-colors"
              style={{
                background: active ? 'var(--brand)' : 'transparent',
                color: active ? '#fff' : 'var(--text-medium)',
              }}
            >
              {n}
            </button>
          );
        })}
        {pages > 5 && <span className="px-1 text-slate-400">…</span>}
        <button className="w-7 h-7 rounded-md inline-flex items-center justify-center hover:bg-slate-100" style={{ color: 'var(--text-soft)' }}>
          <IconChevron dir="right" size={14} />
        </button>
      </div>
    </div>
  );
}

// ── StatusDot ──────────────────────────────────────────────────────
function StatusDot({ color = '#10B981', pulse }) {
  return (
    <span
      className="inline-block w-2 h-2 rounded-full"
      style={{
        background: color,
        boxShadow: pulse ? `0 0 0 3px ${color}22` : 'none',
      }}
    />
  );
}

// ── EmptyState ─────────────────────────────────────────────────────
function EmptyState({ title, sub, action }) {
  return (
    <div className="bg-white rounded-xl py-16 flex flex-col items-center text-center"
      style={{ border: '1px dashed var(--border-strong)' }}>
      <div className="w-16 h-16 rounded-full flex items-center justify-center mb-4" style={{ background: '#F1F5F9' }}>
        <IconArchive size={28} className="text-slate-400" />
      </div>
      <div className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>{title}</div>
      {sub && <div className="text-[12.5px] mt-1.5 max-w-sm" style={{ color: 'var(--text-soft)' }}>{sub}</div>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

// ── AvatarChip ─────────────────────────────────────────────────────
const AVATAR_TONES = [
  { bg: '#EEF2FF', fg: '#4F46E5' },
  { bg: '#ECFDF5', fg: '#059669' },
  { bg: '#FFF7ED', fg: '#D97706' },
  { bg: '#FAF5FF', fg: '#9333EA' },
  { bg: '#FEE2E2', fg: '#DC2626' },
  { bg: '#ECFEFF', fg: '#0891B2' },
  { bg: '#FCE7F3', fg: '#DB2777' },
];
function AvatarChip({ name, size = 28, seed }) {
  const tone = AVATAR_TONES[(seed ?? name.charCodeAt(0)) % AVATAR_TONES.length];
  const initial = name.slice(0, 1);
  return (
    <span
      className="inline-flex items-center justify-center rounded-full font-semibold shrink-0"
      style={{
        width: size, height: size,
        background: tone.bg, color: tone.fg,
        fontSize: size * 0.42,
      }}
    >
      {initial}
    </span>
  );
}

Object.assign(window, {
  Button, PageShell, SectionTitle, MiniKPI, Tag, FilterBar, DataTable,
  Checkbox, Pagination, StatusDot, EmptyState, AvatarChip,
});
