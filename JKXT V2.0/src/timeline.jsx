// Timeline — horizontal 7-day calendar with colored exam pills (Project Tasks style)

const DAYS = [
  { label: '周一', date: '19', dow: 'Mon', dim: false },
  { label: '周二', date: '20', dow: 'Tue', dim: false },
  { label: '周三', date: '21', dow: 'Wed', dim: false, today: true },
  { label: '周四', date: '22', dow: 'Thu', dim: false },
  { label: '周五', date: '23', dow: 'Fri', dim: false },
  { label: '周六', date: '24', dow: 'Sat', dim: true },
  { label: '周日', date: '25', dow: 'Sun', dim: true },
];

// Pills positioned by day index (1-based) and span — Project Tasks style
const EXAMS = [
  { row: 0, start: 1, span: 2, label: '初二·数学 期中考', meta: '全年级 8 个班 · A 栋三楼', color: 4, supN: 8 },
  { row: 0, start: 4, span: 1, label: '高三·英语月考', meta: '', color: 1 },
  { row: 1, start: 1, span: 1, label: '初一·语文单元测', meta: '', color: 3 },
  { row: 1, start: 3, span: 2, label: '高二·物理 期中考', meta: '全年级 10 班 · B 栋四楼', color: 4, supN: 10 },
  { row: 1, start: 6, span: 1, label: '初三·化学月考', meta: '', color: 1 },
  { row: 2, start: 2, span: 2, label: '全校·道德与法治', meta: '全校 56 班 · 各班教室', color: 5, supN: 30 },
  { row: 2, start: 5, span: 1, label: '高一·生物单元测', meta: '', color: 3 },
  { row: 3, start: 1, span: 1, label: '七年级·英语月考', meta: '', color: 1 },
  { row: 3, start: 4, span: 2, label: '高三·一模联考', meta: '全市统考 · 标准考场 12 间', color: 5, supN: 24 },
  { row: 4, start: 3, span: 1, label: '初二·历史单元测', meta: '', color: 3 },
  { row: 4, start: 6, span: 1, label: '高三·二模冲刺', meta: '', color: 6 },
];

const COLORS = {
  1: { bg: '#6366F1', soft: '#EEF2FF' },
  2: { bg: '#10B981', soft: '#ECFDF5' },
  3: { bg: '#F59E0B', soft: '#FFF7ED' },
  4: { bg: '#EF4444', soft: '#FEE2E2' },
  5: { bg: '#06B6D4', soft: '#ECFEFF' },
  6: { bg: '#EC4899', soft: '#FCE7F3' },
};

function ExamPill({ exam, colW, gap }) {
  const c = COLORS[exam.color];
  const left = (exam.start - 1) * (colW + gap);
  const width = exam.span * colW + (exam.span - 1) * gap;
  const compact = exam.span === 1;
  return (
    <div
      className="absolute rounded-full flex items-center gap-2 cursor-pointer transition-all hover:scale-[1.02] overflow-hidden"
      style={{
        left,
        top: exam.row * 44,
        width,
        height: 30,
        paddingLeft: compact ? 10 : 4,
        paddingRight: 12,
        background: c.bg,
        color: '#fff',
        boxShadow: 'var(--elevation-2), inset 0 1px 0 rgba(255,255,255,.2)',
      }}
    >
      {!compact && (
        <div className="flex -space-x-1.5 shrink-0">
          <div
            className="w-[22px] h-[22px] rounded-full border-2 border-white flex items-center justify-center text-[9px] font-semibold"
            style={{ background: c.soft, color: c.bg }}
          >
            {exam.label.slice(0, 1)}
          </div>
          <div
            className="w-[22px] h-[22px] rounded-full border-2 border-white flex items-center justify-center text-[9px] font-semibold text-white"
            style={{ background: 'rgba(0,0,0,.18)' }}
          >
            +{exam.supN || 5}
          </div>
        </div>
      )}
      {compact && (
        <span
          className="w-1.5 h-1.5 rounded-full shrink-0"
          style={{ background: '#fff' }}
        />
      )}
      <div className="min-w-0 flex-1">
        <div className="text-[11.5px] font-semibold leading-tight truncate">{exam.label}</div>
        {!compact && (
          <div className="text-[10px] leading-tight opacity-90 truncate font-mono">{exam.meta}</div>
        )}
      </div>
    </div>
  );
}

function TimelineCard() {
  const colW = 94;
  const gap = 8;
  const rowH = 44;
  const rows = 5;

  return (
    <div className="bg-white rounded-2xl shadow-card p-5 pb-3" style={{ border: '1px solid var(--border-soft)' }}>
      {/* Header */}
      <div className="flex items-end justify-between mb-4">
        <div>
          <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            近 7 天考试安排
          </h3>
          <div className="text-[12px] mt-1" style={{ color: 'var(--text-soft)' }}>
            2025-11-19 至 2025-11-25 · 共 <span className="font-mono font-semibold" style={{ color: 'var(--text-medium)' }}>11</span> 场考试
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="inline-flex p-0.5 rounded-lg" style={{ background: '#F1F5F9' }}>
            {['日', '周', '月'].map((v, i) => (
              <button
                key={v}
                className="px-3 h-7 text-[12px] font-medium rounded-md transition-colors"
                style={{
                  background: i === 1 ? '#FFFFFF' : 'transparent',
                  color: i === 1 ? 'var(--text-strong)' : 'var(--text-soft)',
                  boxShadow: i === 1 ? 'var(--elevation-1)' : 'none',
                }}
              >
                {v}
              </button>
            ))}
          </div>
          <button className="h-7 px-2.5 rounded-lg inline-flex items-center gap-1 text-[12px] font-medium hover:bg-slate-50 transition-colors"
            style={{ border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}>
            <IconCalendar size={12} />
            11 月
            <IconChevron size={12} className="text-slate-400" />
          </button>
        </div>
      </div>

      {/* Day header */}
      <div className="flex gap-2 mb-3">
        {DAYS.map((d, i) => (
          <div
            key={i}
            className="flex flex-col items-center justify-center rounded-lg pt-2 pb-1.5"
            style={{
              width: colW,
              background: d.today ? 'var(--brand-soft)' : 'transparent',
            }}
          >
            <span
              className="font-mono text-[10px] uppercase tracking-wider"
              style={{ color: d.today ? 'var(--brand)' : d.dim ? '#CBD5E1' : 'var(--text-soft)' }}
            >
              {d.dow}
            </span>
            <span
              className="num text-[18px] font-bold mt-0.5"
              style={{
                color: d.today ? 'var(--brand)' : d.dim ? '#CBD5E1' : 'var(--text-strong)',
              }}
            >
              {d.date}
            </span>
          </div>
        ))}
      </div>

      {/* Grid + pills */}
      <div className="relative overflow-hidden" style={{ height: rows * rowH + 12 }}>
        {/* vertical gridlines */}
        <div className="absolute inset-0 flex gap-2 pointer-events-none">
          {DAYS.map((d, i) => (
            <div
              key={i}
              className="rounded-lg"
              style={{
                width: colW,
                height: '100%',
                background: d.today
                  ? 'linear-gradient(to bottom, rgba(238,242,255,.45), rgba(238,242,255,0))'
                  : d.dim
                  ? '#FAFAFA'
                  : 'transparent',
                borderLeft: i === 0 ? 'none' : '1px dashed #E2E8F0',
                marginLeft: i === 0 ? 0 : -4,
              }}
            />
          ))}
        </div>

        {/* Today vertical line */}
        <div
          className="absolute top-0 bottom-2 w-px"
          style={{
            left: 2 * (colW + 8) + colW / 2,
            background: 'var(--brand)',
          }}
        >
          <div
            className="absolute -top-1 -translate-x-1/2 px-1.5 py-0.5 rounded font-mono text-[10px] font-semibold text-white"
            style={{ background: 'var(--brand)' }}
          >
            14:30
          </div>
        </div>

        {/* Pills */}
        {EXAMS.map((e, i) => (
          <ExamPill key={i} exam={e} colW={colW} gap={8} />
        ))}
      </div>

      {/* Legend */}
      <div className="flex items-center gap-4 pt-3 mt-1" style={{ borderTop: '1px solid var(--border-soft)' }}>
        {[
          { c: '#6366F1', l: '月考' },
          { c: '#EF4444', l: '期中考' },
          { c: '#10B981', l: '期末考' },
          { c: '#F59E0B', l: '单元测试' },
          { c: '#06B6D4', l: '联考统考' },
          { c: '#EC4899', l: '模拟考' },
        ].map((x) => (
          <div key={x.l} className="inline-flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full" style={{ background: x.c }} />
            <span className="text-[11.5px]" style={{ color: 'var(--text-medium)' }}>{x.l}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

window.TimelineCard = TimelineCard;
