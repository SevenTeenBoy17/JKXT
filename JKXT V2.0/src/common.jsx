// Common primitives shared across all pages
// Exports: PageShell, SectionTitle, FilterBar, DataTable, MiniKPI, Pagination,
//          StatusDot, EmptyState, Toolbar, Button, Tag, AvatarChip

// ── Button ─────────────────────────────────────────────────────────
function Button({
  variant = 'secondary', size = 'md', icon: Icn, children,
  loading = false, danger = false, className = '', style,
  disabled = false, type = 'button', ...rest
}) {
  const resolvedVariant = danger ? 'danger' : variant;
  const isDisabled = disabled || loading;

  return (
    <button
      {...rest}
      type={type}
      className={`ui-button ${className}`.trim()}
      data-variant={resolvedVariant}
      data-size={size}
      data-motion-button="true"
      disabled={isDisabled}
      aria-busy={loading ? 'true' : undefined}
      style={style}
    >
      <span className="ui-button__shine" aria-hidden="true" />
      {loading
        ? <span className="ui-button__spinner" aria-hidden="true" />
        : Icn && <Icn size={size === 'sm' ? 13 : size === 'lg' ? 16 : 14} strokeWidth={2} />}
      <span className="ui-button__label">{children}</span>
    </button>
  );
}

// ── PageShell ──────────────────────────────────────────────────────
function PageShell({ crumbs = [], title, badge, subtitle, actions, children, routeId }) {
  const resolvedRoute = routeId || window.__jkxtActivePage;
  return (
    <div className="page-shell page-shell--workspace space-y-6">
      <div className="page-header">
        <div className="page-header__intro" data-tour-id="page-heading">
          <nav className="page-breadcrumbs" aria-label="面包屑导航">
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
          <div className="page-title-row">
            <PageTitleMark title={title} crumbs={crumbs} routeId={resolvedRoute} />
            <h1 className="page-title" style={{ color: 'var(--text-strong)' }}>
              {title}
            </h1>
            {badge && (
              <span
                className="page-meta-badge"
                style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
              >
                {badge}
              </span>
            )}
          </div>
          {subtitle && (
            <div className="page-subtitle" data-motion-text="true" style={{ color: 'var(--text-medium)' }}>
              {subtitle}
            </div>
          )}
        </div>
        {actions && <div className="page-actions" data-tour-id="page-actions">{actions}</div>}
      </div>
      {children}
    </div>
  );
}

// ── SectionTitle ───────────────────────────────────────────────────
function SectionTitle({ title, sub, right }) {
  return (
    <div className="section-title">
      <div>
        <h3 className="section-title__heading" style={{ color: 'var(--text-strong)' }}>
          {title}
        </h3>
        {sub && (
          <div className="section-title__subtitle" style={{ color: 'var(--text-soft)' }}>
            {sub}
          </div>
        )}
      </div>
      {right}
    </div>
  );
}

// ── MiniKPI (small KPI card for page tops) ─────────────────────────
const resolveMiniKPIGlyph = (glyph, Icn) => {
  if (glyph) return glyph;
  if (Icn === IconCalendar) return 'termSupervisions';
  if (Icn === IconClock) return 'supervisionHours';
  if (Icn === IconChart || Icn === IconArrowUp) return 'allowanceTotal';
  if (Icn === IconUsers) return 'activeStaff';
  if (Icn === IconBuilding) return 'availableRooms';
  if (Icn === IconArchive) return 'archive';
  if (Icn === IconList) return 'examTasks';
  if (Icn === IconCheck) return 'taskCompleted';
  if (Icn === IconAlert) return 'taskConflict';
  if (Icn === IconShield) return 'security';
  return null;
};

function MiniKPI({ label, value, unit, trend, trendUp, accent = '#4F46E5', icon: Icn, glyph, sub }) {
  const semanticGlyph = resolveMiniKPIGlyph(glyph, Icn);
  return (
    <div
      className="mini-kpi relative rounded-xl p-4 flex items-center gap-3.5 transition-all overflow-hidden"
      style={{
        '--kpi-accent': accent,
      }}
    >
      {(semanticGlyph || Icn) && (
        <div
          className={`mini-kpi__icon relative flex items-center justify-center shrink-0${semanticGlyph ? ' mini-kpi__icon--semantic' : ''}`}
          data-semantic-glyph={semanticGlyph || undefined}
          style={!semanticGlyph ? { background: `${accent}12`, color: accent, border: `1px solid ${accent}22` } : undefined}
        >
          {semanticGlyph
            ? <ProductGlyph name={semanticGlyph} size={34} />
            : <Icn size={18} strokeWidth={2} />}
        </div>
      )}
      <div className="min-w-0 flex-1 relative">
        <div className="mini-kpi__label" style={{ color: 'var(--text-soft)' }}>
          {label}
        </div>
        <div className="mt-0.5 flex items-baseline gap-1.5">
          <span className="mini-kpi__value num font-bold tracking-tight" style={{ color: 'var(--text-strong)', letterSpacing: '-0.02em' }}>
            {value}
          </span>
          {unit && (
            <span className="mini-kpi__unit" style={{ color: 'var(--text-medium)' }}>
              {unit}
            </span>
          )}
          {trend !== undefined && (
            <span
              className="ml-auto inline-flex items-center gap-0.5 font-mono text-[12px] font-semibold px-1.5 py-0.5 rounded"
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
          <div className="mini-kpi__meta mt-0.5" style={{ color: 'var(--text-soft)' }}>
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
      className="ui-tag inline-flex shrink-0 items-center gap-1 px-1.5 py-0.5 rounded-md text-[12px] font-medium whitespace-nowrap"
      style={{ background: soft ? colors.bg : colors.dotC, color: soft ? colors.fg : '#fff' }}
    >
      {dot && <span className="w-1.5 h-1.5 shrink-0 rounded-full" style={{ background: colors.dotC }} />}
      {children}
    </span>
  );
}

// ── FilterPill — interactive dropdown filter (used inside FilterBar) ──
function FilterPill({ filter }) {
  const fallback = ['全部', '不限', '当前', '全年级'].includes(filter.value)
    ? [filter.value]
    : [filter.value];
  const options = (filter.options && filter.options.length) ? filter.options : fallback;
  const [value, setValue] = React.useState(filter.value);
  const [open, setOpen] = React.useState(false);
  const ref = React.useRef(null);

  // Stay in sync when the parent drives the value (controlled usage —
  // e.g. 「清空筛选」 resetting every pill back to its default).
  React.useEffect(() => { setValue(filter.value); }, [filter.value]);

  React.useEffect(() => {
    if (!open) return;
    const onDown = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const pick = (v) => {
    setValue(v);
    setOpen(false);
    if (filter.onChange) filter.onChange(v);
    // 未接 onChange 的胶囊此前会弹「已按 X 筛选 · 示例筛选」——谎称筛选生效而列表纹丝不动。
    // 现在如实说明；已接线的页面（history/stats-supervise/edit/people/classrooms/...）不会走到这里。
    else window.ui?.toast?.('该筛选项尚未接通', { kind: 'info', desc: '此处仅展示信息，暂不影响列表' });
  };

  return (
    <div className="filter-pill relative" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)}
        className="filter-pill__trigger h-9 px-3 rounded-lg inline-flex items-center gap-1.5 text-[13px] transition-all whitespace-nowrap"
        style={{
          border: open ? '1px solid var(--brand)' : '1px solid var(--border-soft)',
          color: 'var(--text-medium)',
          background: open ? '#fff' : 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
          boxShadow: open
            ? 'var(--shadow-focus)'
            : '0 1px 0 rgba(255,255,255,.85) inset, 0 1px 2px rgba(15,23,42,.04)',
        }}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span style={{ color: 'var(--text-soft)' }}>{filter.label}</span>
        <span className="font-medium" style={{ color: ['全部', '全年级', '不限', '当前'].includes(value) ? 'var(--text-strong)' : 'var(--brand)' }}>{value}</span>
        <IconChevron dir={open ? 'up' : 'down'} size={12} className="text-slate-400" />
      </button>
      {open && (
        <div
          className="filter-menu absolute left-0 top-full mt-1 py-1 rounded-lg z-40 overflow-hidden"
          style={{ minWidth: 150, maxHeight: 280, overflowY: 'auto', background: '#fff', border: '1px solid var(--border-soft)', boxShadow: '0 12px 28px rgba(15,23,42,.12)' }}
          role="listbox"
        >
          {options.map((opt, i) => {
            const active = opt === value;
            return (
              <button
                key={i}
                onClick={() => pick(opt)}
                className="w-full flex items-center justify-between px-3 py-1.5 text-[12.5px] transition-colors hover:bg-slate-50 text-left whitespace-nowrap"
                style={{ color: active ? 'var(--brand)' : 'var(--text-medium)', fontWeight: active ? 600 : 400 }}
                role="option"
                aria-selected={active}
              >
                {opt}
                {active && <IconCheck size={13} strokeWidth={3} className="shrink-0 ml-3" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ── FilterBar ──────────────────────────────────────────────────────
function FilterBar({ search, searchValue, onSearch, filters = [], total, onReset, right }) {
  const controlled = typeof onSearch === 'function';
  return (
    <div
      className="filter-bar relative rounded-xl p-3 flex items-center gap-2 flex-wrap"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 2px rgba(15,23,42,.04)',
      }}
    >
      {search !== undefined && (
        <div className="filter-search relative flex items-center" style={{ width: 240 }}>
          <IconSearch size={14} className="absolute left-3 text-slate-400 pointer-events-none" />
          <input
            type="text"
            placeholder={search}
            aria-label={search}
            value={controlled ? (searchValue || '') : undefined}
            onChange={controlled ? (e) => onSearch(e.target.value) : undefined}
            onKeyDown={controlled ? (e) => { if (e.key === 'Escape' && searchValue) { e.stopPropagation(); onSearch(''); } } : undefined}
            className="filter-search__input w-full h-9 pl-9 pr-8 rounded-lg text-[13px] outline-none transition-colors"
            style={{
              background: '#F8FAFC',
              border: '1px solid transparent',
              color: 'var(--text-strong)',
            }}
            onFocus={(e) => { e.target.style.background = '#fff'; e.target.style.border = '1px solid var(--brand)'; e.target.style.boxShadow = 'var(--shadow-focus)'; }}
            onBlur={(e)  => { e.target.style.background = '#F8FAFC'; e.target.style.border = '1px solid transparent'; e.target.style.boxShadow = 'none'; }}
          />
          {controlled && searchValue && (
            <button
              onClick={() => onSearch('')}
              aria-label="清除搜索"
              className="absolute right-2 w-5 h-5 rounded-full inline-flex items-center justify-center hover:bg-slate-200 transition-colors"
              style={{ color: 'var(--text-soft)' }}
            >
              <svg width="11" height="11" viewBox="0 0 12 12" fill="none"><path d="M2.5 2.5 L9.5 9.5 M9.5 2.5 L2.5 9.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"/></svg>
            </button>
          )}
        </div>
      )}
      {filters.map((f, i) => (
        <FilterPill key={i} filter={f} />
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
function DataTable({ columns, rows, selectable, onRowClick, footer, density = 'comfortable', pageSize = 10 }) {
  const [selected, setSelected] = React.useState(new Set());
  const [sort, setSort] = React.useState({ key: null, direction: null });
  const rowH = density === 'compact' ? 44 : 56;
  const normalizedPageSize = Math.max(1, Number(pageSize) || 10);
  const usesBuiltInPagination = footer === undefined;
  const sortedEntries = React.useMemo(() => {
    const entries = rows.map((row, index) => ({ row, index }));
    if (!sort.key || !sort.direction) return entries;
    const direction = sort.direction === 'ascending' ? 1 : -1;
    return entries.sort((a, b) => {
      const left = a.row?.[sort.key];
      const right = b.row?.[sort.key];
      if (left == null && right == null) return a.index - b.index;
      if (left == null) return 1;
      if (right == null) return -1;
      const compared = typeof left === 'number' && typeof right === 'number'
        ? left - right
        : String(left).localeCompare(String(right), 'zh-CN', { numeric: true, sensitivity: 'base' });
      return compared === 0 ? a.index - b.index : compared * direction;
    });
  }, [rows, sort.key, sort.direction]);
  const pageCount = Math.max(1, Math.ceil(sortedEntries.length / normalizedPageSize));
  const [page, setPage] = React.useState(1);
  const safePage = Math.max(1, Math.min(page, pageCount));
  const pageStart = usesBuiltInPagination ? (safePage - 1) * normalizedPageSize : 0;
  const visibleEntries = usesBuiltInPagination
    ? sortedEntries.slice(pageStart, pageStart + normalizedPageSize)
    : sortedEntries;
  const visibleIndexes = visibleEntries.map((entry) => entry.index);
  const selectedOnPage = visibleIndexes.filter((index) => selected.has(index)).length;

  React.useEffect(() => {
    setPage((current) => Math.max(1, Math.min(current, pageCount)));
  }, [pageCount]);

  React.useEffect(() => {
    setSelected(new Set());
  }, [rows]);

  const toggleAll = () => {
    const next = new Set(selected);
    if (visibleEntries.length > 0 && selectedOnPage === visibleEntries.length) {
      visibleIndexes.forEach((index) => next.delete(index));
    } else {
      visibleIndexes.forEach((index) => next.add(index));
    }
    setSelected(next);
  };
  const toggleOne = (i) => {
    const s = new Set(selected);
    s.has(i) ? s.delete(i) : s.add(i);
    setSelected(s);
  };
  const toggleSort = (key) => {
    setSort((current) => ({
      key,
      direction: current.key === key && current.direction === 'ascending' ? 'descending' : 'ascending',
    }));
    setPage(1);
  };
  const describeRow = (row, rowIndex) => {
    const primary = columns.find((col) => col.key !== 'actions' && ['string', 'number'].includes(typeof row?.[col.key]));
    return primary ? `${primary.label} ${row[primary.key]}` : `第 ${rowIndex + 1} 行`;
  };
  const allChecked = visibleEntries.length > 0 && selectedOnPage === visibleEntries.length;
  return (
    <div
      className="data-table relative rounded-xl overflow-hidden"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 8px 24px -8px rgba(15,23,42,.08)',
      }}
    >
      <div className="data-table__scroller overflow-x-auto">
        <table className="w-full" style={{ fontSize: 13 }}>
          <thead>
            <tr style={{ background: '#FAFBFC', borderBottom: '1px solid var(--border-soft)' }}>
              {selectable && (
                <th className="px-3 py-3 text-left" style={{ width: 44 }}>
                  <Checkbox
                    checked={allChecked}
                    indeterminate={selectedOnPage > 0 && !allChecked}
                    onChange={toggleAll}
                    label={allChecked ? '取消选择当前页全部行' : '选择当前页全部行'}
                  />
                </th>
              )}
              {columns.map((col, i) => (
                <th
                  key={i}
                  aria-sort={col.sortable ? (sort.key === col.key ? sort.direction : 'none') : undefined}
                  className="px-4 py-3 text-left font-medium whitespace-nowrap"
                  style={{
                    color: 'var(--text-soft)',
                    fontSize: 12,
                    textAlign: col.align || 'left',
                    width: col.width,
                  }}
                >
                  {col.sortable ? (
                    <button
                      type="button"
                      onClick={() => toggleSort(col.key)}
                      className="inline-flex items-center gap-1 rounded-sm"
                      aria-label={`按${col.label}${sort.key === col.key && sort.direction === 'ascending' ? '降序' : '升序'}排列`}
                    >
                      {col.label}
                      <span className="inline-flex flex-col">
                        <svg aria-hidden="true" focusable="false" width="8" height="5" viewBox="0 0 8 5" fill="none" style={{ transform: sort.key === col.key && sort.direction === 'descending' ? 'rotate(180deg)' : 'none' }}><path d="M1 4 L4 1 L7 4" stroke={sort.key === col.key ? 'var(--brand)' : '#CBD5E1'} strokeWidth="1.5" strokeLinecap="round" /></svg>
                      </span>
                    </button>
                  ) : <span>{col.label}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visibleEntries.map(({ row, index: rowIndex }, visibleIndex) => {
              return (
                <tr
                  key={rowIndex}
                  className="data-table__row transition-colors group"
                  data-selected={selected.has(rowIndex) ? 'true' : 'false'}
                  style={{
                    borderBottom: visibleIndex < visibleEntries.length - 1 ? '1px solid var(--border-soft)' : 'none',
                    background: selected.has(rowIndex) ? 'var(--brand-soft)' : 'transparent',
                    cursor: onRowClick ? 'pointer' : 'default',
                    height: rowH,
                  }}
                  onMouseEnter={(e) => { if (!selected.has(rowIndex)) e.currentTarget.style.background = '#F8FAFC'; }}
                  onMouseLeave={(e) => { if (!selected.has(rowIndex)) e.currentTarget.style.background = 'transparent'; }}
                  tabIndex={onRowClick ? 0 : undefined}
                  aria-label={onRowClick ? `打开${describeRow(row, rowIndex)}` : undefined}
                  onClick={onRowClick ? () => onRowClick(row, rowIndex) : undefined}
                  onKeyDown={onRowClick ? (event) => {
                    if (event.target !== event.currentTarget) return;
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onRowClick(row, rowIndex);
                    }
                  } : undefined}
                >
                  {selectable && (
                    <td className="px-3" onClick={(e) => e.stopPropagation()}>
                      <Checkbox
                        checked={selected.has(rowIndex)}
                        onChange={() => toggleOne(rowIndex)}
                        label={`${selected.has(rowIndex) ? '取消选择' : '选择'}${describeRow(row, rowIndex)}`}
                      />
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
                      {col.render ? col.render(row, rowIndex) : row[col.key]}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {footer !== undefined
        ? footer
        : <Pagination total={rows.length} page={safePage} pageSize={normalizedPageSize} onChange={setPage} />}
    </div>
  );
}

// ── Checkbox ───────────────────────────────────────────────────────
function Checkbox({ id, checked, indeterminate, onChange, label }) {
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (ref.current) ref.current.indeterminate = !!indeterminate;
  }, [indeterminate]);
  return (
    <label
      className="inline-flex items-center justify-center cursor-pointer relative"
      style={{ width: 44, height: 44, margin: -14 }}
    >
      <input
        id={id}
        ref={ref}
        type="checkbox"
        checked={!!checked}
        onChange={onChange || (() => {})}
        readOnly={!onChange}
        aria-label={label || '选择'}
        className="absolute inset-0 opacity-0 cursor-pointer peer"
        style={{ width: 44, height: 44, margin: 0 }}
      />
      <span
        aria-hidden="true"
        className="inline-flex items-center justify-center w-4 h-4 rounded transition-all peer-focus-visible:ring-2"
        style={{
          background: (checked || indeterminate) ? 'var(--brand)' : '#fff',
          border: `1.5px solid ${(checked || indeterminate) ? 'var(--brand)' : '#CBD5E1'}`,
          boxShadow: 'var(--cb-ring, none)',
        }}
      >
        {checked && !indeterminate && <IconCheck size={11} strokeWidth={3} className="text-white" />}
        {indeterminate && <span className="w-2 h-0.5 bg-white rounded-sm" />}
      </span>
    </label>
  );
}

// ── Pagination ─────────────────────────────────────────────────────
function Pagination({ total, page: initialPage = 1, pageSize = 10, onChange }) {
  const normalizedPageSize = Math.max(1, Number(pageSize) || 10);
  const pages = Math.max(1, Math.ceil(total / normalizedPageSize));
  const clampPage = (value) => Math.max(1, Math.min(pages, Number(value) || 1));
  const [page, setPage] = React.useState(clampPage(initialPage));
  const currentPage = clampPage(page);

  React.useEffect(() => {
    setPage((current) => clampPage(current));
  }, [pages]);

  React.useEffect(() => {
    setPage(clampPage(initialPage));
  }, [initialPage]);

  const go = (p) => {
    const next = clampPage(p);
    setPage(next);
    onChange?.(next);
  };
  const from = (currentPage - 1) * normalizedPageSize + 1;
  const to = Math.min(currentPage * normalizedPageSize, total);
  // window of up to 5 page numbers around current
  const winStart = Math.max(1, Math.min(currentPage - 2, pages - 4));
  const winCount = Math.min(5, pages);
  return (
    <div
      className="data-table__pagination px-4 py-3 flex items-center justify-between"
      style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC' }}
    >
      <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>
        显示 <span className="font-mono">{total === 0 ? 0 : from}–{to}</span> 共 <span className="font-mono">{total}</span> 条
      </span>
      <div className="flex items-center gap-1">
        <button
          onClick={() => go(currentPage - 1)}
          disabled={currentPage <= 1}
          className="w-7 h-7 rounded-md inline-flex items-center justify-center hover:bg-slate-100 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
          style={{ color: 'var(--text-soft)' }}
          aria-label="上一页"
        >
          <IconChevron dir="left" size={14} />
        </button>
        {Array.from({ length: winCount }).map((_, i) => {
          const n = winStart + i;
          const active = n === currentPage;
          return (
            <button
              key={i}
              onClick={() => go(n)}
              className="w-7 h-7 rounded-md font-mono text-[12px] font-medium transition-colors hover:bg-slate-100"
              style={{
                background: active ? 'var(--brand)' : 'transparent',
                color: active ? '#fff' : 'var(--text-medium)',
              }}
              aria-current={active ? 'page' : undefined}
            >
              {n}
            </button>
          );
        })}
        {pages > winStart + winCount - 1 && <span className="px-1 text-slate-400">…</span>}
        <button
          onClick={() => go(currentPage + 1)}
          disabled={currentPage >= pages}
          className="w-7 h-7 rounded-md inline-flex items-center justify-center hover:bg-slate-100 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
          style={{ color: 'var(--text-soft)' }}
          aria-label="下一页"
        >
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
function EmptyState({ title, sub, action, glyph = 'archive' }) {
  return (
    <div className="empty-state bg-white rounded-xl py-16 flex flex-col items-center text-center"
      data-motion-card="true"
      style={{ border: '1px dashed var(--border-strong)' }}>
      <span className="empty-state__icon mb-4" aria-hidden="true">
        <ProductGlyph name={glyph} size={44} />
      </span>
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

// ── orgName ────────────────────────────────────────────────────────
// 当前登录机构的真实名称。此前全站面包屑/页脚/导出文件都写死「天津市实验中学」——
// 换任何一所学校部署，界面都会显示别人的校名。/auth/me 的 org.name 一直有真值，只是没人用。
function orgName() {
  return window.__jkxtMe?.org?.name || '本校';
}

Object.assign(window, {
  Button, PageShell, SectionTitle, MiniKPI, Tag, FilterBar, DataTable,
  Checkbox, Pagination, StatusDot, EmptyState, AvatarChip, orgName,
});
