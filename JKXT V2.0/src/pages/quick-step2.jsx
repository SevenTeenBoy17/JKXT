// Step 2 · 上传校验 (模拟校验报告)
// 4 段架构: A 数据概览 / B 6 类校验快照 / C 问题列表 / D 底部 (互斥单 CTA)
// 叙事重心: 教务可读 · 不像技术日志 · 红色克制

// ──────────────────────────────────────────────────────────────
// 严重度元数据 (新四档: pass / suggest / warn / error)
// ──────────────────────────────────────────────────────────────
const QK_SEV = {
  pass:    { color: '#059669', bg: '#ECFDF5', border: '#A7F3D0', symbol: '✓', label: '通过' },
  suggest: { color: '#0369A1', bg: '#F0F9FF', border: '#BAE6FD', symbol: '✦', label: '建议' },
  warn:    { color: '#B45309', bg: '#FFFBEB', border: '#FDE68A', symbol: '!', label: '警告' },
  error:   { color: '#B91C1C', bg: '#FEF2F2', border: '#FECACA', symbol: '✕', label: '错误' },
};

const QK_STATUS = {
  '可继续':   { fg: '#047857', bg: '#ECFDF5', border: '#A7F3D0' },
  '建议复核': { fg: '#B45309', bg: '#FFFBEB', border: '#FDE68A' },
  '需修改':   { fg: '#B91C1C', bg: '#FEF2F2', border: '#FECACA' },
};

const QK_SOURCE_TONE = {
  '教师表':            { bg: '#EEF2FF', fg: '#4F46E5' },
  '考场表':            { bg: '#ECFDF5', fg: '#047857' },
  '考试表':            { bg: '#FFF7ED', fg: '#B45309' },
  '考场表 / 考试表':   { bg: '#F1F5F9', fg: '#475569' },
  '教师表 / 考试表':   { bg: '#F1F5F9', fg: '#475569' },
};

// 按图标名称字符串解析为组件
const QK_ICON_MAP = {
  IconCheck, IconUsers, IconBuilding, IconClock, IconBook, IconBell,
};

// ──────────────────────────────────────────────────────────────
// A · 数据概览 (5 KPI 含 48px 完整度圆环)
// ──────────────────────────────────────────────────────────────
function QkCompletenessRing({ value }) {
  const radius = 18;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference * (1 - value / 100);
  const ringColor = value >= 95 ? '#10B981' : value >= 85 ? '#0EA5E9' : value >= 70 ? '#F59E0B' : '#EF4444';

  return (
    <div className="relative shrink-0" style={{ width: 48, height: 48 }}>
      <svg width="48" height="48" viewBox="0 0 48 48">
        <circle cx="24" cy="24" r={radius} fill="none" stroke="#E2E8F0" strokeWidth="4" />
        <circle
          cx="24" cy="24" r={radius}
          fill="none" stroke={ringColor} strokeWidth="4"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          strokeLinecap="round"
          transform="rotate(-90 24 24)"
          style={{ transition: 'stroke-dashoffset 600ms cubic-bezier(.22,.61,.36,1)' }}
        />
      </svg>
      <div
        className="absolute inset-0 flex items-center justify-center font-mono text-[12px] font-bold tracking-tight"
        style={{ color: ringColor, letterSpacing: '-0.02em' }}
      >
        {value}
      </div>
    </div>
  );
}

function QkOverviewBar({ overview, counts, backendActive = false }) {
  const ringValue = overview.completeness;
  const completenessLabel = ringValue >= 95 ? '良好' : ringValue >= 85 ? '可接受' : ringValue >= 70 ? '待修正' : '需修复';
  const completenessColor = ringValue >= 95 ? '#047857' : ringValue >= 85 ? '#0369A1' : ringValue >= 70 ? '#B45309' : '#B91C1C';

  const fields = [
    { label: '可排监考',   value: overview.teachers,     unit: '人' },
    { label: '考场数量',   value: overview.rooms,        unit: '间' },
    { label: '考试场次',   value: overview.exams,        unit: '场' },
    { label: '需要监考',   value: overview.supervisions, unit: '人次' },
  ];

  return (
    <div
      className="rounded-2xl p-5"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 6px 16px -8px rgba(15,23,42,.06)',
      }}
    >
      <div className="flex items-center gap-5 flex-wrap">
        {/* 完整度环 + tooltip */}
        <div className="flex items-center gap-3 shrink-0">
          <window.QkTooltip
            position="bottom"
            text={
              <>
                <div className="font-semibold" style={{ color: '#F8FAFC' }}>数据完整度公式</div>
                <div className="mt-1 font-mono" style={{ color: '#CBD5E1' }}>100 − 错误×5 − 警告×1</div>
                <div className="mt-1.5" style={{ color: '#94A3B8' }}>
                  {backendActive
                    ? '错误扣重 · 警告扣轻 · 建议不扣分。当前结果来自后端导入校验。'
                    : '错误扣重 · 警告扣轻 · 建议不扣分。占位公式 · 服务端接入后以真实计算为准。'}
                </div>
              </>
            }
            maxWidth={260}
          >
            <QkCompletenessRing value={ringValue} />
          </window.QkTooltip>
          <div>
            <div className="text-[10.5px] font-mono uppercase tracking-wider whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
              数据完整度
            </div>
            <div className="mt-0.5 flex items-baseline gap-1.5">
              <span
                className="num text-[20px] font-bold tracking-tight"
                style={{ color: 'var(--text-strong)', letterSpacing: '-0.02em' }}
              >
                {ringValue}
              </span>
              <span className="text-[11.5px]" style={{ color: 'var(--text-medium)' }}>%</span>
              <span
                className="text-[10.5px] font-mono ml-1 px-1 py-0.5 rounded whitespace-nowrap"
                style={{ color: completenessColor, background: '#fff', border: `1px solid ${completenessColor}33` }}
              >
                {completenessLabel}
              </span>
            </div>
          </div>
        </div>

        <div className="h-10 w-px" style={{ background: 'var(--border-soft)' }} />

        {/* 4 普通字段 */}
        <div className="flex items-end gap-6 flex-1 flex-wrap">
          {fields.map((f) => (
            <div key={f.label}>
              <div className="text-[10.5px] font-mono uppercase tracking-wider whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
                {f.label}
              </div>
              <div className="mt-1 flex items-baseline gap-1">
                <span className="num text-[20px] font-bold tracking-tight" style={{ color: 'var(--text-strong)', letterSpacing: '-0.02em' }}>
                  {f.value}
                </span>
                <span className="text-[11.5px]" style={{ color: 'var(--text-medium)' }}>{f.unit}</span>
              </div>
            </div>
          ))}
        </div>

        {/* 计数 chips */}
        <div className="flex items-center gap-1.5 flex-wrap">
          {counts.pass    > 0 && <QkCountChip kind="pass"    count={counts.pass} />}
          {counts.suggest > 0 && <QkCountChip kind="suggest" count={counts.suggest} />}
          {counts.warn    > 0 && <QkCountChip kind="warn"    count={counts.warn} />}
          {counts.error   > 0 && <QkCountChip kind="error"   count={counts.error} />}
        </div>
      </div>
    </div>
  );
}

function QkCountChip({ kind, count }) {
  const m = QK_SEV[kind];
  const isPass = kind === 'pass';
  return (
    <window.QkTooltip
      position="bottom"
      text={
        isPass
          ? <><div className="font-semibold" style={{ color: '#F8FAFC' }}>通过项 · 不列入待复核</div><div className="mt-1" style={{ color: '#CBD5E1' }}>该类均已满足检查规则 · 无需复核 · 计入总检查项但不出现在列表中</div></>
          : kind === 'suggest'
            ? '软约束建议 · 可继续排监考 · 仅供参考'
            : kind === 'warn'
              ? '数据瑰疆 · 不阐塞排监考 · 建议复核后推进'
              : '必填字段缺失 · 退回源表修正后重新上传'
      }
      maxWidth={isPass ? 220 : 200}
    >
      <span
        className="inline-flex items-center gap-1.5 px-2 py-1 rounded-lg whitespace-nowrap cursor-help"
        style={{ background: m.bg, border: `1px solid ${m.border}` }}
      >
        <span
          className="w-4 h-4 rounded inline-flex items-center justify-center font-mono text-[10px] font-bold shrink-0"
          style={{ background: m.color, color: '#fff' }}
        >
          {m.symbol}
        </span>
        <span className="text-[11.5px] font-medium" style={{ color: m.color }}>{m.label}</span>
        <span className="font-mono text-[12.5px] font-bold" style={{ color: m.color }}>{count}</span>
      </span>
    </window.QkTooltip>
  );
}

// ──────────────────────────────────────────────────────────────
// B · 6 类校验快照 (3×2 网格)
// ──────────────────────────────────────────────────────────────
function QkCategoryCard({ cat, stat, selected, onSelect }) {
  const total = (stat.pass || 0) + (stat.suggest || 0) + (stat.warn || 0) + (stat.error || 0);
  const worst = stat.error ? 'error' : stat.warn ? 'warn' : stat.suggest ? 'suggest' : 'pass';
  const m = QK_SEV[worst];
  const Icon = QK_ICON_MAP[cat.icon] || IconCheck;

  // 显示非 0 的微计数
  const microStats = [];
  if (stat.error)   microStats.push({ k: 'error',   v: stat.error,   m: QK_SEV.error });
  if (stat.warn)    microStats.push({ k: 'warn',    v: stat.warn,    m: QK_SEV.warn });
  if (stat.suggest) microStats.push({ k: 'suggest', v: stat.suggest, m: QK_SEV.suggest });
  if (stat.pass)    microStats.push({ k: 'pass',    v: stat.pass,    m: QK_SEV.pass });

  return (
    <button
      onClick={() => onSelect(cat.id)}
      className="relative rounded-2xl p-4 text-left transition-all overflow-hidden"
      style={{
        background: selected
          ? 'linear-gradient(180deg, #FFFFFF 0%, var(--brand-soft) 100%)'
          : 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: selected ? '1px solid rgba(79,70,229,.30)' : '1px solid var(--border-soft)',
        boxShadow: selected
          ? '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 2px rgba(79,70,229,.10), 0 8px 18px -6px rgba(79,70,229,.20)'
          : '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 2px rgba(15,23,42,.03)',
      }}
      onMouseEnter={(e) => {
        if (selected) return;
        e.currentTarget.style.transform = 'translateY(-2px)';
        e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.95) inset, 0 4px 10px rgba(15,23,42,.06), 0 12px 24px -8px rgba(15,23,42,.10)';
      }}
      onMouseLeave={(e) => {
        if (selected) return;
        e.currentTarget.style.transform = 'translateY(0)';
        e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 2px rgba(15,23,42,.03)';
      }}
    >
      {selected && (
        <span
          className="absolute left-0 top-3 bottom-3 w-[3px] rounded-r-full"
          style={{ background: 'var(--brand)' }}
        />
      )}

      <div className="flex items-start justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <span
            className="w-7 h-7 rounded-lg flex items-center justify-center shrink-0"
            style={{
              background: selected ? '#fff' : '#F1F5F9',
              color: selected ? 'var(--brand)' : 'var(--text-medium)',
              border: selected ? '1px solid rgba(79,70,229,.18)' : '1px solid var(--border-soft)',
            }}
          >
            <Icon size={14} strokeWidth={2} />
          </span>
          <div className="min-w-0">
            <div className="text-[13px] font-semibold leading-snug sm:whitespace-nowrap" style={{ color: selected ? 'var(--brand)' : 'var(--text-strong)' }}>
              {cat.name}
            </div>
            <div className="text-[10.5px] mt-0.5 leading-snug sm:whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
              {cat.desc}
            </div>
          </div>
        </div>
        <span
          className="w-5 h-5 rounded inline-flex items-center justify-center font-mono text-[10.5px] font-bold shrink-0"
          style={{
            background: m.color,
            color: '#fff',
            boxShadow: `0 1px 2px ${m.color}33`,
          }}
        >
          {m.symbol}
        </span>
      </div>

      <div className="mt-3 flex items-center justify-between gap-2 flex-wrap">
        <div className="flex items-center gap-2 flex-wrap">
          {microStats.length === 0 && (
            <span className="text-[11px] font-mono" style={{ color: 'var(--text-soft)' }}>
              暂无检查项
            </span>
          )}
          {microStats.map((s) => (
            <span key={s.k} className="inline-flex items-center gap-1 text-[11px] font-mono whitespace-nowrap">
              <span className="w-1.5 h-1.5 rounded-full" style={{ background: s.m.color }} />
              <span style={{ color: s.m.color }}>{s.m.label}</span>
              <span className="font-semibold" style={{ color: s.m.color }}>{s.v}</span>
            </span>
          ))}
        </div>
        <span className="text-[10.5px] font-mono whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
          共 {total} 项
        </span>
      </div>
    </button>
  );
}

function QkCategoryGrid({ categories, cats, selected, onSelect }) {
  return (
    <div
      className="rounded-2xl p-5"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
      }}
    >
      <div className="flex items-end justify-between mb-4 flex-wrap gap-2">
        <div>
          <h3 className="text-[14px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            6 类校验快照
          </h3>
          <div className="text-[11.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            点击任一类别 · 筛选下方复核项
          </div>
        </div>
        {selected !== 'all' && (
          <button
            onClick={() => onSelect('all')}
            className="text-[11.5px] font-medium px-2.5 py-1.5 rounded-md inline-flex items-center gap-1 transition-colors whitespace-nowrap"
            style={{ color: 'var(--text-medium)', border: '1px solid var(--border-soft)', background: '#fff' }}
            onMouseEnter={(e) => (e.currentTarget.style.background = '#F8FAFC')}
            onMouseLeave={(e) => (e.currentTarget.style.background = '#fff')}
          >
            清空筛选
            <IconChevron dir="right" size={11} />
          </button>
        )}
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
        {categories.map((c) => (
          <QkCategoryCard
            key={c.id}
            cat={c}
            stat={cats[c.id] || { pass: 0, suggest: 0, warn: 0, error: 0 }}
            selected={selected === c.id}
            onSelect={onSelect}
          />
        ))}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// C · 问题列表
// ──────────────────────────────────────────────────────────────
function QkProblemItem({ item, categories }) {
  const m = QK_SEV[item.severity];
  const st = QK_STATUS[item.status] || QK_STATUS['可继续'];
  const src = QK_SOURCE_TONE[item.source] || { bg: '#F1F5F9', fg: '#475569' };
  const cat = categories.find((c) => c.id === item.category);

  return (
    <div
      className="rounded-xl relative overflow-hidden transition-all"
      style={{
        background: '#fff',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 2px rgba(15,23,42,.03)',
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.9) inset, 0 2px 4px rgba(15,23,42,.05), 0 8px 18px -8px rgba(15,23,42,.10)';
        e.currentTarget.style.borderColor = m.border;
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.boxShadow = '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 2px rgba(15,23,42,.03)';
        e.currentTarget.style.borderColor = 'var(--border-soft)';
      }}
    >
      {/* 严重度 stripe (左侧 4px 全高) */}
      <span
        className="absolute left-0 top-0 bottom-0"
        style={{ width: 4, background: m.color }}
      />

      <div className="pl-5 pr-4 py-3.5">
        {/* 顶行: severity icon + 严重度文字 + 所属表 + ref + 右侧状态 Tag + 定位 */}
        <div className="flex items-center gap-2 flex-wrap mb-2">
          <span
            className="w-5 h-5 rounded inline-flex items-center justify-center font-mono text-[11px] font-bold shrink-0"
            style={{ background: m.color, color: '#fff', boxShadow: `0 1px 2px ${m.color}33` }}
          >
            {m.symbol}
          </span>
          <span className="text-[11.5px] font-semibold whitespace-nowrap" style={{ color: m.color }}>
            {m.label}
          </span>
          <span style={{ color: 'var(--border-strong)' }}>·</span>
          <span
            className="font-mono text-[10.5px] px-1.5 py-0.5 rounded uppercase tracking-wider font-semibold whitespace-nowrap"
            style={{ background: src.bg, color: src.fg }}
          >
            {item.source}
          </span>
          {item.ref && item.ref !== '—' && (
            <span className="font-mono text-[10.5px] whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
              {item.ref}
            </span>
          )}
          {cat && (
            <span className="font-mono text-[10.5px] whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
              · {cat.name}
            </span>
          )}

          <div className="flex-1" />

          <span
            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-medium whitespace-nowrap"
            style={{ background: st.bg, color: st.fg, border: `1px solid ${st.border}` }}
          >
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: st.fg }} />
            {item.status}
          </span>
          {item.ref && item.ref !== '—' && (
            <button
              onClick={() => window.ui?.toast?.('已记录定位需求 · 待服务端接入后可跳转源表', { kind: 'info', desc: `${item.source} · ${item.ref}` })}
              className="text-[11.5px] font-medium px-2 py-1 rounded inline-flex items-center gap-1 transition-colors whitespace-nowrap"
              style={{ color: 'var(--brand)' }}
              onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--brand-soft)')}
              onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
            >
              定位行
              <IconChevron dir="right" size={11} />
            </button>
          )}
        </div>

        {/* 问题说明 */}
        <div
          className="text-[12.5px] font-medium leading-relaxed"
          style={{ color: 'var(--text-strong)' }}
        >
          {item.problem}
        </div>

        {/* 建议处理方式 */}
        {item.suggest && (
          <div className="mt-1.5 flex items-start gap-1.5 text-[12px] leading-relaxed">
            <span
              className="shrink-0 mt-0.5 font-mono text-[12px]"
              style={{ color: '#0EA5E9' }}
            >
              ✦
            </span>
            <span className="font-medium whitespace-nowrap" style={{ color: '#0369A1' }}>
              处理建议:
            </span>
            <span style={{ color: 'var(--text-medium)' }}>{item.suggest}</span>
          </div>
        )}
      </div>
    </div>
  );
}

function QkProblemList({ items, categories, sevFilter, catFilter, onSevChange, onCatClear }) {
  // 二级筛选
  const filtered = items.filter((it) => {
    const sevOk = sevFilter === 'all' || it.severity === sevFilter;
    const catOk = catFilter === 'all' || it.category === catFilter;
    return sevOk && catOk;
  });

  // 按严重度排序: error → warn → suggest
  const sevOrder = { error: 0, warn: 1, suggest: 2, pass: 3 };
  const sorted = [...filtered].sort((a, b) => (sevOrder[a.severity] ?? 9) - (sevOrder[b.severity] ?? 9));

  // 严重度筛选 chips 计数
  const sevCounts = items.reduce((acc, it) => {
    acc[it.severity] = (acc[it.severity] || 0) + 1;
    return acc;
  }, {});
  const sevTotal = items.length;

  const SEV_FILTERS = [
    { id: 'all',     label: '全部',  count: sevTotal },
    { id: 'error',   label: '错误',  count: sevCounts.error   || 0 },
    { id: 'warn',    label: '警告',  count: sevCounts.warn    || 0 },
    { id: 'suggest', label: '建议',  count: sevCounts.suggest || 0 },
  ];

  return (
    <div
      className="rounded-2xl p-5"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
      }}
    >
      {/* 头 + 严重度筛选 */}
      <div className="flex items-end justify-between mb-4 flex-wrap gap-3">
        <div>
          <h3 className="text-[14px] font-semibold whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
            待复核项
          </h3>
          <div className="text-[11.5px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            共 <span className="font-mono font-semibold" style={{ color: 'var(--text-medium)' }}>{sevTotal}</span> 项 · 已按严重度排序
            {catFilter !== 'all' && (
              <>
                {' · '}
                <button onClick={onCatClear} className="font-medium hover:underline" style={{ color: 'var(--brand)' }}>
                  清空类别筛选
                </button>
              </>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1.5 flex-wrap">
          {SEV_FILTERS.map((s) => {
            const active = sevFilter === s.id;
            const sevColor = s.id === 'all' ? '#475569' : QK_SEV[s.id]?.color;
            return (
              <button
                key={s.id}
                onClick={() => onSevChange(s.id)}
                className="h-7 px-2.5 rounded-md text-[12px] inline-flex items-center gap-1.5 font-medium transition-all whitespace-nowrap"
                style={{
                  background: active ? sevColor : '#fff',
                  color: active ? '#fff' : 'var(--text-medium)',
                  border: active ? `1px solid ${sevColor}` : '1px solid var(--border-soft)',
                  boxShadow: active
                    ? `0 1px 0 rgba(255,255,255,.25) inset, 0 1px 3px ${sevColor}33`
                    : '0 1px 0 rgba(255,255,255,.85) inset, 0 1px 2px rgba(15,23,42,.03)',
                }}
              >
                {!active && s.id !== 'all' && (
                  <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: sevColor }} />
                )}
                {s.label}
                <span
                  className="font-mono text-[10.5px] font-bold px-1 rounded shrink-0"
                  style={{
                    background: active ? 'rgba(255,255,255,.22)' : '#F1F5F9',
                    color: active ? '#fff' : 'var(--text-medium)',
                  }}
                >
                  {s.count}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* 列表 */}
      {sorted.length === 0 ? (
        <div
          className="rounded-xl p-8 flex flex-col items-center text-center"
          style={{ background: '#F8FAFC', border: '1px dashed var(--border-strong)' }}
        >
          <div
            className="w-12 h-12 rounded-full flex items-center justify-center mb-2"
            style={{ background: '#fff', border: '1px solid var(--border-soft)' }}
          >
            <IconCheck size={20} strokeWidth={2.5} style={{ color: '#10B981' }} />
          </div>
          <div className="text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            当前筛选下无待复核项
          </div>
          <div className="text-[11.5px] mt-1" style={{ color: 'var(--text-soft)' }}>
            {sevFilter !== 'all' || catFilter !== 'all'
              ? '可清空筛选条件查看全部'
              : '本次数据全部检查通过 · 可进入排监考'}
          </div>
        </div>
      ) : (
        <div className="space-y-2.5">
          {sorted.map((it, i) => (
            <QkProblemItem key={i} item={it} categories={categories} />
          ))}
        </div>
      )}
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// D · 底部操作 (互斥单 CTA)
// ──────────────────────────────────────────────────────────────
function QkStep2Footer({ counts, validating, onBack, onNext, backendActive = false }) {
  const hasError = counts.error > 0;
  const hasWarn  = counts.warn > 0;

  let primaryLabel, primaryDisabled, primaryStatus;
  if (hasError) {
    primaryLabel = `请先修正 ${counts.error} 项错误`;
    primaryDisabled = true;
    primaryStatus = 'blocked';
  } else if (hasWarn) {
    primaryLabel = `确认警告 · 开始${backendActive ? '后端' : '模拟'}排监考`;
    primaryDisabled = false;
    primaryStatus = 'warn-confirm';
  } else {
    primaryLabel = backendActive ? '开始后端智能排监考' : '开始智能排监考（模拟）';
    primaryDisabled = false;
    primaryStatus = 'clean';
  }

  const statusText = hasError
    ? <span className="whitespace-nowrap">检测到 <span className="font-mono font-semibold" style={{ color: '#B91C1C' }}>{counts.error}</span> 项错误 · <span style={{ color: 'var(--text-soft)' }}>需先在源表修正后重新上传</span></span>
    : hasWarn
      ? <span className="whitespace-nowrap">存在 <span className="font-mono font-semibold" style={{ color: '#B45309' }}>{counts.warn}</span> 项警告 · <span style={{ color: 'var(--text-soft)' }}>不影响排监考 · 建议关注</span></span>
      : <span className="whitespace-nowrap" style={{ color: '#047857' }}>✓ 全部检查通过 · 可进入排监考</span>;

  return (
    <div
      className="rounded-2xl px-5 py-3.5 flex items-center gap-4 flex-wrap"
      style={{
        background: hasError
          ? 'linear-gradient(180deg, #FFFFFF 0%, #FEF7F7 100%)'
          : 'linear-gradient(180deg, #FFFFFF 0%, #FAFBFC 100%)',
        border: hasError ? '1px solid #FECACA' : '1px solid var(--border-soft)',
        boxShadow: hasError
          ? '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(239,68,68,.06), 0 6px 18px -8px rgba(239,68,68,.18)'
          : '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
      }}
    >
      <div className="text-[13px]" style={{ color: 'var(--text-medium)' }}>
        {statusText}
      </div>
      <div className="flex-1" />
      <Button
        onClick={onBack}
        icon={IconUpload}
        style={hasError ? { color: '#B91C1C', borderColor: '#FECACA' } : undefined}
      >
        <span style={{ opacity: 0.7, marginRight: 4 }}>←</span>
        返回上传
      </Button>
      <Button
        variant="primary"
        icon={primaryDisabled ? IconAlert : primaryStatus === 'warn-confirm' ? IconCheck : IconBolt}
        onClick={primaryDisabled ? undefined : onNext}
        loading={validating}
        style={{
          opacity: primaryDisabled ? 0.55 : 1,
          pointerEvents: primaryDisabled ? 'none' : 'auto',
        }}
      >
        {primaryLabel}
        {!primaryDisabled && <span style={{ opacity: 0.7, marginLeft: 4 }}>→</span>}
      </Button>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// Step 2 主体
// ──────────────────────────────────────────────────────────────
function QkStep2({ data, validating, validatedAt, onRevalidate, onBack, onNext, backendActive = false }) {
  const [sevFilter, setSevFilter] = React.useState('all');
  const [catFilter, setCatFilter] = React.useState('all');
  const listRef = React.useRef(null);

  const categories = window.QK_VALIDATION_CATEGORIES || [];

  const handleSelectCat = (id) => {
    setCatFilter((prev) => (prev === id ? 'all' : id));
    setTimeout(() => {
      const el = listRef.current;
      if (el) {
        const top = el.getBoundingClientRect().top + window.scrollY - 100;
        window.scrollTo({ top, behavior: 'smooth' });
      }
    }, 50);
  };

  // 校验时间
  const fmtTime = (d) => {
    if (!d) return '刚刚';
    const diff = Math.floor((Date.now() - d.getTime()) / 1000);
    if (diff < 5) return '刚刚';
    if (diff < 60) return `${diff} 秒前`;
    if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  };

  return (
    <section className="space-y-5">
      {/* 标题 + 重新校验 */}
      <div className="flex items-end justify-between flex-wrap gap-3">
        <div>
          <h3 className="text-[16px] font-semibold flex items-center gap-2" style={{ color: 'var(--text-strong)' }}>
            {backendActive ? '后端校验报告' : '模拟校验报告'}
            {backendActive ? <window.QkBackendChip /> : <QkMockChip />}
          </h3>
          <div className="text-[12px] mt-1" style={{ color: 'var(--text-soft)' }}>
            {validating
              ? '正在重新校验…'
              : <>更新于 <span className="font-mono">{fmtTime(validatedAt)}</span> · {backendActive ? '后端导入校验结果' : '仅前端模拟,未接入服务端'}</>}
          </div>
        </div>
        <Button icon={IconSparkle} onClick={onRevalidate} loading={validating} disabled={validating}>
          {validating ? '校验中…' : backendActive ? '重新后端校验' : '重新模拟一次'}
        </Button>
      </div>

      {/* A · 数据概览 */}
      <QkOverviewBar overview={data.overview} counts={data.counts} backendActive={backendActive} />

      {/* B · 6 类校验快照 */}
      <QkCategoryGrid
        categories={categories}
        cats={data.cats}
        selected={catFilter}
        onSelect={handleSelectCat}
      />

      {/* C · 问题列表 */}
      <div ref={listRef}>
        <QkProblemList
          items={data.items}
          categories={categories}
          sevFilter={sevFilter}
          catFilter={catFilter}
          onSevChange={setSevFilter}
          onCatClear={() => setCatFilter('all')}
        />
      </div>

      {/* D · 底部操作 */}
      <QkStep2Footer counts={data.counts} validating={validating} onBack={onBack} onNext={onNext} backendActive={backendActive} />
    </section>
  );
}

window.QkStep2 = QkStep2;
