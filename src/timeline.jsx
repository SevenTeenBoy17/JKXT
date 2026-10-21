// Timeline — real exam schedule with 4 interactive views (日 / 周 / 月 / 年)

const DAYS = [
  { label: '周一', date: '19', dow: '一', dim: false },
  { label: '周二', date: '20', dow: '二', dim: false },
  { label: '周三', date: '21', dow: '三', dim: false, today: true },
  { label: '周四', date: '22', dow: '四', dim: false },
  { label: '周五', date: '23', dow: '五', dim: false },
  { label: '周六', date: '24', dow: '六', dim: true },
  { label: '周日', date: '25', dow: '日', dim: true },
];

// Pills positioned by day index (1-based) and span. time/room drive the 日 (day) view.
const EXAMS = [
  { row: 0, start: 1, span: 2, label: '初二·数学 期中考', meta: '全年级 8 个班 · A 栋三楼', color: 4, supN: 8,  time: '08:00', room: 'A 栋三楼' },
  { row: 0, start: 4, span: 1, label: '高三·英语月考',     meta: '高三 10 班 · 实验楼',   color: 1, supN: 12, time: '09:30', room: '实验楼二层' },
  { row: 1, start: 1, span: 1, label: '初一·语文单元测',   meta: '初一 6 班 · 本班教室', color: 3, supN: 6,  time: '10:00', room: '本班教室' },
  { row: 1, start: 3, span: 2, label: '高二·物理 期中考', meta: '全年级 10 班 · B 栋四楼', color: 4, supN: 10, time: '14:30', room: 'B 栋四楼' },
  { row: 1, start: 6, span: 1, label: '初三·化学月考',     meta: '初三 8 班 · C 栋二层',  color: 1, supN: 8,  time: '09:00', room: 'C 栋二层' },
  { row: 2, start: 2, span: 2, label: '全校·道德与法治',   meta: '全校 56 班 · 各班教室', color: 5, supN: 30, time: '14:00', room: '各班教室' },
  { row: 2, start: 5, span: 1, label: '高一·生物单元测',   meta: '高一 12 班 · 本班教室', color: 3, supN: 12, time: '10:30', room: '本班教室' },
  { row: 3, start: 1, span: 1, label: '七年级·英语月考',   meta: '七年级 9 班 · 本班教室', color: 1, supN: 9, time: '08:30', room: '本班教室' },
  { row: 3, start: 4, span: 2, label: '高三·一模联考',     meta: '全市统考 · 标准考场 12 间', color: 5, supN: 24, time: '09:00', room: '标准考场 ×12' },
  { row: 4, start: 3, span: 1, label: '初二·历史单元测',   meta: '初二 8 班 · 本班教室',  color: 3, supN: 8,  time: '15:00', room: '本班教室' },
  { row: 4, start: 6, span: 1, label: '高三·二模冲刺',     meta: '高三 10 班 · 实验楼',   color: 6, supN: 20, time: '08:00', room: '实验楼三层' },
];

const COLORS = {
  1: { bg: '#6366F1', soft: '#EEF2FF', name: '月考' },
  2: { bg: '#10B981', soft: '#ECFDF5', name: '期末考' },
  3: { bg: '#F59E0B', soft: '#FFF7ED', name: '单元测试' },
  4: { bg: '#EF4444', soft: '#FEE2E2', name: '期中考' },
  5: { bg: '#06B6D4', soft: '#ECFEFF', name: '联考统考' },
  6: { bg: '#EC4899', soft: '#FCE7F3', name: '模拟考' },
};

function examColor(value) {
  const index = Number(value);
  return Number.isInteger(index) && COLORS[index] ? COLORS[index] : COLORS[1];
}

const GAP = 8;
const COLW = `((100% - ${GAP * 6}px) / 7)`;

// exams covering a given 0-based day column
const examsOnDay = (d, exams = EXAMS) => exams.filter((e) => (e.start - 1) <= d && d < (e.start - 1) + e.span);

// ──────────────────────────────────────────────────────────────
// 周视图 (week) — original horizontal pill grid
// ──────────────────────────────────────────────────────────────
function ExamPill({ exam }) {
  const c = examColor(exam.color);
  const leftCss  = `calc((${COLW} + ${GAP}px) * ${exam.start - 1})`;
  const widthCss = `calc(${COLW} * ${exam.span} + ${GAP * (exam.span - 1)}px)`;
  const compact = exam.span === 1;
  return (
    <div
      className="absolute rounded-full flex items-center gap-2 cursor-pointer transition-all hover:scale-[1.02] overflow-hidden"
      style={{
        left: leftCss, top: exam.row * 44, width: widthCss, height: 30,
        paddingLeft: compact ? 10 : 4, paddingRight: 12,
        background: c.bg, color: '#fff',
        boxShadow: 'var(--elevation-2), inset 0 1px 0 rgba(255,255,255,.2)',
      }}
      title={`${exam.label} · ${exam.time} · ${exam.room}`}
    >
      {!compact && (
        <div className="flex -space-x-1.5 shrink-0">
          <div className="w-[22px] h-[22px] rounded-full border-2 border-white flex items-center justify-center text-[9px] font-semibold"
            style={{ background: c.soft, color: c.bg }}>
            {exam.label.slice(0, 1)}
          </div>
          <div className="w-[22px] h-[22px] rounded-full border-2 border-white flex items-center justify-center text-[9px] font-semibold text-white"
            style={{ background: 'rgba(0,0,0,.18)' }}>
            +{exam.supN || 5}
          </div>
        </div>
      )}
      {compact && <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: '#fff' }} />}
      <div className="min-w-0 flex-1">
        <div className="text-[11.5px] font-semibold leading-tight truncate">{exam.label}</div>
        {!compact && <div className="text-[10px] leading-tight opacity-90 truncate font-mono">{exam.meta}</div>}
      </div>
    </div>
  );
}

function WeekView({ onPickDay, days = DAYS, exams = EXAMS, currentTime = '14:30' }) {
  const rowH = 44;
  const rows = Math.max(5, ...exams.map((exam) => (Number(exam.row) || 0) + 1));
  const todayIndex = days.findIndex((d) => d.today);
  const showCurrentTime = todayIndex >= 0 && typeof currentTime === 'string' && currentTime.trim() !== '';
  return (
    <div>
      {/* Day header */}
      <div className="flex gap-2 mb-3">
        {days.map((d, i) => (
          <button
            key={i}
            onClick={() => onPickDay(i)}
            className="flex-1 min-w-0 flex flex-col items-center justify-center rounded-lg pt-2 pb-1.5 transition-colors hover:bg-slate-50"
            style={{ background: d.today ? 'var(--brand-soft)' : 'transparent' }}
            title={`查看 ${d.label} 当日明细`}
          >
            <span className="font-mono text-[10px] uppercase tracking-wider"
              style={{ color: d.today ? 'var(--brand)' : d.dim ? '#CBD5E1' : 'var(--text-soft)' }}>
              {d.dow}
            </span>
            <span className="num text-[18px] font-bold mt-0.5"
              style={{ color: d.today ? 'var(--brand)' : d.dim ? '#CBD5E1' : 'var(--text-strong)' }}>
              {d.date}
            </span>
          </button>
        ))}
      </div>
      {/* Grid + pills */}
      <div className="relative overflow-hidden" style={{ height: rows * rowH + 12 }}>
        <div className="absolute inset-0 flex gap-2 pointer-events-none">
          {days.map((d, i) => (
            <div key={i} className="flex-1 min-w-0 rounded-lg"
              style={{
                height: '100%',
                background: d.today
                  ? 'linear-gradient(to bottom, var(--brand-soft), transparent)'
                  : d.dim ? 'var(--surface-muted)' : 'transparent',
                borderLeft: i === 0 ? 'none' : '1px dashed var(--border-soft)',
              }} />
          ))}
        </div>
        {showCurrentTime && <div className="absolute top-0 bottom-2 w-px"
          style={{ left: `calc((${COLW} + ${GAP}px) * ${todayIndex} + ${COLW} / 2)`, background: 'var(--brand)' }}>
          <div className="absolute -top-1 -translate-x-1/2 px-1.5 py-0.5 rounded font-mono text-[10px] font-semibold text-white"
            style={{ background: 'var(--brand)' }}>
            {currentTime}
          </div>
        </div>}
        {exams.map((e, i) => <ExamPill key={e.id || i} exam={e} />)}
        {exams.length === 0 && (
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
            <div className="px-3 py-2 rounded-lg text-[12px]" style={{ background: 'var(--surface-overlay)', color: 'var(--text-soft)', border: '1px solid var(--border-soft)' }}>
              该日期范围内暂无已排定考试
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 日视图 (day) — detailed agenda for one day
// ──────────────────────────────────────────────────────────────
function DayView({ dayIdx, setDayIdx, days = DAYS, exams = EXAMS }) {
  if (!Array.isArray(days) || days.length === 0) {
    return (
      <div className="timeline-day-view timeline-day-empty flex flex-col items-center justify-center rounded-xl" style={{ background: 'var(--surface-muted)', border: '1px dashed var(--border-soft)' }}>
        <IconCalendar size={26} className="text-slate-300" />
        <div className="text-[12.5px] mt-2" style={{ color: 'var(--text-soft)' }}>当前没有可用的考试日期</div>
      </div>
    );
  }
  const d = days[dayIdx] || days[0];
  const list = examsOnDay(dayIdx, exams).slice().sort((a, b) => a.time.localeCompare(b.time));
  const dateLabel = d.isoDate
    ? `${Number(String(d.isoDate).slice(5, 7))} 月 ${Number(String(d.isoDate).slice(8, 10))} 日`
    : `${Number(d.date) || '—'} 日`;
  return (
    <div className="timeline-day-view">
      {/* day switcher */}
      <div className="flex items-center justify-between mb-3">
        <button
          onClick={() => setDayIdx(Math.max(0, dayIdx - 1))}
          disabled={dayIdx === 0}
          className="w-8 h-8 rounded-lg inline-flex items-center justify-center transition-colors disabled:opacity-30 hover:bg-slate-100"
          style={{ border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}
          aria-label="前一天"
        >
          <IconChevron dir="left" size={14} />
        </button>
        <div className="text-center">
          <div className="text-[13px] font-semibold" style={{ color: d.today ? 'var(--brand)' : 'var(--text-strong)' }}>
            {dateLabel} · {d.label}
            {d.today && <span className="ml-2 font-mono text-[10px] px-1.5 py-0.5 rounded" style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}>今天</span>}
          </div>
          <div className="text-[11px] mt-0.5 whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
            {list.length > 0 ? `共 ${list.length} 场考试` : '本日无考试安排'}
          </div>
        </div>
        <button
          onClick={() => setDayIdx(Math.min(days.length - 1, dayIdx + 1))}
          disabled={dayIdx >= days.length - 1}
          className="w-8 h-8 rounded-lg inline-flex items-center justify-center transition-colors disabled:opacity-30 hover:bg-slate-100"
          style={{ border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}
          aria-label="后一天"
        >
          <IconChevron dir="right" size={14} />
        </button>
      </div>

      {/* agenda list */}
      {list.length === 0 ? (
        <div className="timeline-day-empty flex flex-col items-center justify-center rounded-xl" style={{ background: 'var(--surface-muted)', border: '1px dashed var(--border-soft)' }}>
          <IconCalendar size={26} className="text-slate-300" />
          <div className="text-[12.5px] mt-2" style={{ color: 'var(--text-soft)' }}>本日暂无考试安排</div>
        </div>
      ) : (
        <div className="timeline-day-list space-y-2 overflow-y-auto pr-1">
          {list.map((e, i) => {
            const c = examColor(e.color);
            return (
              <div key={i} className="flex items-stretch gap-3 rounded-xl p-3 transition-colors hover:bg-slate-50"
                style={{ border: '1px solid var(--border-soft)' }}>
                <div className="font-mono text-[13px] font-semibold pt-0.5 shrink-0 tabular-nums" style={{ color: 'var(--text-strong)', width: 46 }}>
                  {e.time}
                </div>
                <div className="w-1 rounded-full shrink-0" style={{ background: c.bg }} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-[13px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>{e.label}</span>
                    <span className="text-[10px] font-mono px-1.5 py-0.5 rounded shrink-0 whitespace-nowrap" style={{ background: c.soft, color: c.bg }}>{e.subjectName || '考试'}</span>
                  </div>
                  <div className="text-[11.5px] mt-1 flex items-center gap-2 whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
                    <span className="inline-flex items-center gap-1 min-w-0"><IconBuilding size={11} /><span className="truncate">{e.room}</span></span>
                    <span style={{ opacity: .5 }}>·</span>
                    <span className="inline-flex items-center gap-1 shrink-0"><IconUsers size={11} />监考 {e.supN} 人</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 月 / 年分析视图 — 真实每日聚合、热力图与趋势图共用同一口径
// ──────────────────────────────────────────────────────────────
const HEAT_COLORS = ['#F1F5F9', '#DDE8FF', '#B9CEFA', '#7CA3F3', '#315FD5'];
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const pad2 = (value) => String(value).padStart(2, '0');
const toIsoDate = (year, month, day) => `${year}-${pad2(month)}-${pad2(day)}`;
const parseIsoParts = (isoDate) => {
  const parts = String(isoDate || '').split('-').map(Number);
  return { year: parts[0] || 0, month: parts[1] || 0, day: parts[2] || 0 };
};
const daysInMonth = (year, month) => new Date(Date.UTC(year, month, 0)).getUTCDate();
const mondayIndex = (year, month, day) => (new Date(Date.UTC(year, month - 1, day)).getUTCDay() + 6) % 7;
const formatIsoDateZh = (isoDate) => {
  const p = parseIsoParts(isoDate);
  return p.year ? `${p.year} 年 ${p.month} 月 ${p.day} 日` : '未选择日期';
};

function normalizeCalendarDays(payload) {
  const merged = new Map();
  const add = (isoDate, examCount, supervisionCount, roomCount) => {
    if (!ISO_DATE.test(String(isoDate || ''))) return;
    const current = merged.get(isoDate) || { isoDate, examCount: 0, supervisionCount: 0, roomCount: 0 };
    current.examCount += Math.max(0, Number(examCount) || 0);
    current.supervisionCount += Math.max(0, Number(supervisionCount) || 0);
    current.roomCount += Math.max(0, Number(roomCount) || 0);
    merged.set(isoDate, current);
  };

  const calendarRows = Array.isArray(payload?.calendarDays) ? payload.calendarDays : [];
  calendarRows.forEach((row) => add(row?.isoDate, row?.examCount, row?.supervisionCount, row?.roomCount));

  return [...merged.values()].sort((a, b) => a.isoDate.localeCompare(b.isoDate));
}

function heatColor(count, maxCount) {
  if (count <= 0 || maxCount <= 0) return HEAT_COLORS[0];
  return HEAT_COLORS[Math.min(4, Math.max(1, Math.ceil((count / maxCount) * 4)))];
}

function HeatLegend({ maxCount }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 mt-3 text-[11px]" style={{ color: 'var(--text-soft)' }}>
      <div className="inline-flex items-center gap-1.5" aria-label={`颜色越深表示考试越多，单日最高 ${maxCount} 场`}>
        <span>少</span>
        {HEAT_COLORS.map((color) => (
          <span key={color} className="w-3.5 h-3.5 rounded-[4px]" style={{ background: color, border: '1px solid rgba(100,116,139,.12)' }} />
        ))}
        <span>多</span>
      </div>
      <span className="font-mono">单日最高 {maxCount} 场</span>
    </div>
  );
}

function SelectedDateSummary({ isoDate, row }) {
  const value = row || { examCount: 0, supervisionCount: 0, roomCount: 0 };
  return (
    <div id="exam-date-summary" className="mt-3 px-3 py-2.5 rounded-xl flex flex-wrap items-center justify-between gap-2" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }} aria-live="polite">
      <div>
        <div className="text-[12px] font-semibold" style={{ color: 'var(--text-strong)' }}>{formatIsoDateZh(isoDate)}</div>
        <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{value.examCount > 0 ? '已排定考试数据' : '当日没有已排定考试'}</div>
      </div>
      <div className="flex items-center gap-4 text-[11.5px] whitespace-nowrap" style={{ color: 'var(--text-medium)' }}>
        <span><b className="font-mono text-[13px]" style={{ color: 'var(--text-strong)' }}>{value.examCount}</b> 场考试</span>
        <span><b className="font-mono text-[13px]" style={{ color: 'var(--text-strong)' }}>{value.supervisionCount}</b> 监考人次</span>
        <span><b className="font-mono text-[13px]" style={{ color: 'var(--text-strong)' }}>{value.roomCount}</b> 间考场</span>
      </div>
    </div>
  );
}

function MonthHeatmap({ year, month, rows, selectedDate, onSelect }) {
  const index = new Map(rows.map((row) => [row.isoDate, row]));
  const maxCount = Math.max(0, ...rows.map((row) => row.examCount));
  const totalDays = daysInMonth(year, month);
  const leading = mondayIndex(year, month, 1);
  const cells = [...Array(leading).fill(null), ...Array.from({ length: totalDays }, (_, i) => i + 1)];
  while (cells.length % 7 !== 0) cells.push(null);
  const weekdays = ['一', '二', '三', '四', '五', '六', '日'];

  React.useEffect(() => {
    const prefix = `${year}-${pad2(month)}-`;
    if (!String(selectedDate || '').startsWith(prefix)) {
      onSelect(rows[0]?.isoDate || toIsoDate(year, month, 1));
    }
  }, [year, month]);

  const selectedRow = index.get(selectedDate);
  return (
    <div>
      <div className="grid grid-cols-7 gap-1.5 mb-1.5" aria-hidden="true">
        {weekdays.map((weekday, i) => (
          <div key={weekday} className="text-center font-mono text-[10px] py-1" style={{ color: i >= 5 ? '#94A3B8' : 'var(--text-soft)' }}>
            周{weekday}
          </div>
        ))}
      </div>
      <div className="grid grid-cols-7 gap-1.5" role="group" aria-label={`${year} 年 ${month} 月考试热力图`}>
        {cells.map((day, cellIndex) => {
          if (day === null) return <span key={`blank-${cellIndex}`} aria-hidden="true" />;
          const isoDate = toIsoDate(year, month, day);
          const row = index.get(isoDate) || { isoDate, examCount: 0, supervisionCount: 0, roomCount: 0 };
          const active = selectedDate === isoDate;
          const weekend = (cellIndex % 7) >= 5;
          return (
            <button
              key={isoDate}
              type="button"
              onClick={() => onSelect(isoDate)}
              className="min-h-[46px] md:min-h-[52px] rounded-lg p-1.5 text-left transition-[box-shadow,transform] hover:-translate-y-px focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-1"
              style={{
                background: heatColor(row.examCount, maxCount),
                border: active ? '1px solid #315FD5' : '1px solid rgba(148,163,184,.18)',
                boxShadow: active ? '0 0 0 2px rgba(49,95,213,.14)' : 'none',
              }}
              aria-pressed={active}
              aria-label={`${formatIsoDateZh(isoDate)}，${row.examCount} 场考试，${row.supervisionCount} 监考人次，${row.roomCount} 间考场`}
              aria-describedby={active ? 'exam-date-summary' : undefined}
            >
              <span className="block text-[11px] font-semibold" style={{ color: weekend && row.examCount === 0 ? '#94A3B8' : 'var(--text-strong)' }}>{day}</span>
              <span className="block mt-1 text-[10px] font-mono" style={{ color: row.examCount > 0 ? '#173A8A' : '#94A3B8' }}>{row.examCount > 0 ? `${row.examCount} 场` : '—'}</span>
            </button>
          );
        })}
      </div>
      <HeatLegend maxCount={maxCount} />
      <SelectedDateSummary isoDate={selectedDate || toIsoDate(year, month, 1)} row={selectedRow} />
    </div>
  );
}

function YearHeatmap({ year, rows, selectedDate, onSelect }) {
  const containerRef = React.useRef(null);
  const index = new Map(rows.map((row) => [row.isoDate, row]));
  const maxCount = Math.max(0, ...rows.map((row) => row.examCount));
  const start = Date.UTC(year, 0, 1);
  const end = Date.UTC(year, 11, 31);
  const leading = mondayIndex(year, 1, 1);
  const days = [];
  for (let cursor = start, ordinal = 0; cursor <= end; cursor += 86400000, ordinal += 1) {
    const date = new Date(cursor);
    const isoDate = toIsoDate(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate());
    days.push({
      isoDate,
      dayIndex: ordinal,
      week: Math.floor((leading + ordinal) / 7),
      weekday: (date.getUTCDay() + 6) % 7,
    });
  }
  const weekCount = Math.max(...days.map((day) => day.week)) + 1;
  const monthLabels = Array.from({ length: 12 }, (_, monthIndex) => {
    const monthStart = Date.UTC(year, monthIndex, 1);
    const ordinal = Math.round((monthStart - start) / 86400000);
    return { label: `${monthIndex + 1} 月`, week: Math.floor((leading + ordinal) / 7) };
  });

  React.useEffect(() => {
    if (!String(selectedDate || '').startsWith(`${year}-`)) {
      onSelect(rows[0]?.isoDate || toIsoDate(year, 1, 1));
    }
  }, [year]);

  const focusDay = (targetIndex) => {
    const clamped = Math.max(0, Math.min(days.length - 1, targetIndex));
    const target = days[clamped];
    onSelect(target.isoDate);
    window.requestAnimationFrame(() => containerRef.current?.querySelector(`[data-heat-index="${clamped}"]`)?.focus());
  };
  const selectedRow = index.get(selectedDate);
  const coarsePointer = typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse)').matches;
  const columnStep = coarsePointer ? 24 : 12;
  const rowStep = coarsePointer ? 24 : 14;
  const targetSize = coarsePointer ? 22 : 10;
  const labelGutter = 38;
  const canvasWidth = labelGutter + weekCount * columnStep;

  return (
    <div>
      <div className="overflow-x-auto pb-1" style={{ overflowY: 'hidden', overscrollBehaviorInline: 'contain' }}>
        <div ref={containerRef} className="relative" style={{ minWidth: canvasWidth, height: coarsePointer ? 198 : 126 }} role="group" aria-label={`${year} 年考试日历热力图，方向键可逐日或逐周移动`}>
          {monthLabels.map((item) => (
            <span key={item.label} className="absolute top-0 text-[10px] font-mono" style={{ left: labelGutter + item.week * columnStep, color: 'var(--text-soft)' }}>{item.label}</span>
          ))}
          {['周一', '周三', '周五', '周日'].map((label, i) => (
            <span key={label} className="absolute left-0 text-[9.5px] font-mono" style={{ top: 25 + i * rowStep * 2, color: 'var(--text-soft)' }}>{label}</span>
          ))}
          {days.map((day) => {
            const row = index.get(day.isoDate) || { examCount: 0, supervisionCount: 0, roomCount: 0 };
            const active = selectedDate === day.isoDate;
            return (
              <button
                key={day.isoDate}
                type="button"
                data-heat-index={day.dayIndex}
                tabIndex={active || (!selectedDate && day.dayIndex === 0) ? 0 : -1}
                onClick={() => onSelect(day.isoDate)}
                onKeyDown={(event) => {
                  const delta = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[event.key];
                  if (delta == null) return;
                  event.preventDefault();
                  focusDay(day.dayIndex + delta);
                }}
                className="absolute grid place-items-center rounded-[6px] focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-1"
                style={{
                  left: labelGutter + day.week * columnStep,
                  top: 23 + day.weekday * rowStep,
                  width: targetSize,
                  height: targetSize,
                  background: 'transparent',
                }}
                aria-pressed={active}
                aria-label={`${formatIsoDateZh(day.isoDate)}，${row.examCount} 场考试，${row.supervisionCount} 监考人次，${row.roomCount} 间考场`}
                aria-describedby={active ? 'exam-date-summary' : undefined}
                title={`${day.isoDate} · ${row.examCount} 场考试`}
              >
                <span
                  aria-hidden="true"
                  className="block rounded-[4px]"
                  style={{
                    width: 10,
                    height: 10,
                    background: heatColor(row.examCount, maxCount),
                    border: active ? '1px solid #173A8A' : '1px solid rgba(100,116,139,.12)',
                    boxShadow: active ? '0 0 0 2px rgba(49,95,213,.18)' : 'none',
                  }}
                />
              </button>
            );
          })}
        </div>
      </div>
      <HeatLegend maxCount={maxCount} />
      <SelectedDateSummary isoDate={selectedDate || toIsoDate(year, 1, 1)} row={selectedRow} />
    </div>
  );
}

function buildTrendPoints(rows, year, month, period) {
  const index = new Map(rows.map((row) => [row.isoDate, row]));
  if (period === 'month') {
    return Array.from({ length: daysInMonth(year, month) }, (_, dayIndex) => {
      const isoDate = toIsoDate(year, month, dayIndex + 1);
      const row = index.get(isoDate) || { examCount: 0, supervisionCount: 0, roomCount: 0 };
      return { ...row, isoDate, label: `${dayIndex + 1} 日`, shortLabel: String(dayIndex + 1), value: row.examCount };
    });
  }
  return Array.from({ length: 12 }, (_, monthIndex) => {
    const prefix = `${year}-${pad2(monthIndex + 1)}-`;
    const monthRows = rows.filter((row) => row.isoDate.startsWith(prefix));
    return {
      isoDate: `${year}-${pad2(monthIndex + 1)}`,
      label: `${monthIndex + 1} 月`,
      shortLabel: `${monthIndex + 1}月`,
      value: monthRows.reduce((sum, row) => sum + row.examCount, 0),
      supervisionCount: monthRows.reduce((sum, row) => sum + row.supervisionCount, 0),
      roomCount: Math.max(0, ...monthRows.map((row) => row.roomCount)),
    };
  });
}

function TrendChart({ year, month, period, rows }) {
  const points = buildTrendPoints(rows, year, month, period);
  const [selectedIndex, setSelectedIndex] = React.useState(0);
  const chartRef = React.useRef(null);
  const width = 820, height = 252;
  const pad = { left: 44, right: 18, top: 18, bottom: 38 };
  const chartW = width - pad.left - pad.right;
  const chartH = height - pad.top - pad.bottom;
  const maxValue = Math.max(1, ...points.map((point) => point.value));
  const xFor = (index) => pad.left + (points.length === 1 ? chartW / 2 : (index / (points.length - 1)) * chartW);
  const yFor = (value) => pad.top + chartH - (value / maxValue) * chartH;
  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${xFor(index).toFixed(2)} ${yFor(point.value).toFixed(2)}`).join(' ');
  const area = `${path} L ${xFor(points.length - 1).toFixed(2)} ${(pad.top + chartH).toFixed(2)} L ${xFor(0).toFixed(2)} ${(pad.top + chartH).toFixed(2)} Z`;
  const total = points.reduce((sum, point) => sum + point.value, 0);
  const active = points.filter((point) => point.value > 0).length;
  const peak = points.reduce((best, point, index) => point.value > best.point.value ? { point, index } : best, { point: points[0], index: 0 });

  React.useEffect(() => setSelectedIndex(peak.index), [year, month, period, peak.index]);
  const selected = points[selectedIndex] || points[0];
  const labelEvery = period === 'year' ? 1 : Math.max(1, Math.ceil(points.length / 7));
  const focusPoint = (targetIndex) => {
    const nextIndex = Math.max(0, Math.min(points.length - 1, targetIndex));
    setSelectedIndex(nextIndex);
    window.requestAnimationFrame(() => chartRef.current?.querySelector(`[data-trend-index="${nextIndex}"]`)?.focus());
  };

  return (
    <div>
      <div className="w-full overflow-x-auto">
        <svg ref={chartRef} viewBox={`0 0 ${width} ${height}`} className="block w-full min-w-[620px]" style={{ height: 252 }} role="group" aria-label={`${period === 'year' ? `${year} 年逐月` : `${year} 年 ${month} 月逐日`}考试场次趋势，总计 ${total} 场，峰值 ${peak.point.label} ${peak.point.value} 场`}>
          {[0, .25, .5, .75, 1].map((ratio) => {
            const y = pad.top + chartH - ratio * chartH;
            const label = Math.round(maxValue * ratio);
            return (
              <g key={ratio}>
                <line x1={pad.left} x2={width - pad.right} y1={y} y2={y} stroke="#E8EDF4" strokeWidth="1" />
                <text x={pad.left - 9} y={y + 4} textAnchor="end" fontSize="10" fill="#94A3B8" fontFamily="Geist Mono, monospace">{label}</text>
              </g>
            );
          })}
          <path d={area} fill="rgba(49,95,213,.08)" />
          <path d={path} fill="none" stroke="#315FD5" strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" />
          {points.map((point, index) => {
            const selectedPoint = index === selectedIndex;
            return (
              <g
                key={point.isoDate}
                data-trend-index={index}
                tabIndex={selectedPoint ? 0 : -1}
                focusable="true"
                role="button"
                aria-label={`${point.label}，${point.value} 场考试`}
                onClick={() => setSelectedIndex(index)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setSelectedIndex(index);
                    return;
                  }
                  const targetIndex = event.key === 'ArrowLeft' ? index - 1
                    : event.key === 'ArrowRight' ? index + 1
                      : event.key === 'Home' ? 0
                        : event.key === 'End' ? points.length - 1
                          : null;
                  if (targetIndex == null) return;
                  event.preventDefault();
                  focusPoint(targetIndex);
                }}
                style={{ cursor: 'pointer', outline: 'none' }}
              >
                {(index % labelEvery === 0 || index === points.length - 1) && (
                  <text x={xFor(index)} y={height - 12} textAnchor="middle" fontSize="10" fill="#64748B" fontFamily="Geist Mono, monospace">{point.shortLabel}</text>
                )}
                <circle cx={xFor(index)} cy={yFor(point.value)} r="12" fill="transparent" pointerEvents="all" aria-hidden="true" />
                <circle
                  cx={xFor(index)} cy={yFor(point.value)} r={selectedPoint ? 6 : point.value > 0 ? 5 : 4}
                  fill={selectedPoint ? '#173A8A' : '#FFFFFF'} stroke="#315FD5" strokeWidth={selectedPoint ? 2.5 : 2}
                  pointerEvents="none"
                  aria-hidden="true"
                >
                  <title>{point.label} · {point.value} 场考试</title>
                </circle>
              </g>
            );
          })}
        </svg>
      </div>
      <div className="grid grid-cols-3 gap-2 mt-2" aria-live="polite">
        {[
          { label: '本期总量', value: total, unit: '场' },
          { label: period === 'year' ? '活跃月份' : '考试日期', value: active, unit: period === 'year' ? '个月' : '天' },
          { label: `当前 · ${selected.label}`, value: selected.value, unit: '场' },
        ].map((item) => (
          <div key={item.label} className="rounded-xl px-3 py-2.5" style={{ background: '#F8FAFC', border: '1px solid var(--border-soft)' }}>
            <div className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>{item.label}</div>
            <div className="mt-1 text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}><span className="font-mono text-[17px]">{item.value}</span> {item.unit}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function AnalyticsEmpty({ year, month, period }) {
  return (
    <div className="min-h-[280px] rounded-xl flex flex-col items-center justify-center text-center px-6" style={{ background: '#FAFBFC', border: '1px dashed var(--border-soft)' }}>
      <IconChart size={28} className="text-slate-300" />
      <div className="mt-3 text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>当前范围暂无已排定考试</div>
      <div className="mt-1 text-[11.5px] max-w-[360px]" style={{ color: 'var(--text-soft)' }}>{period === 'year' ? `${year} 年` : `${year} 年 ${month} 月`}没有成功排班数据；不会使用模拟值填充图表。</div>
    </div>
  );
}

function AnalyticsUnavailable() {
  return (
    <div className="min-h-[280px] rounded-xl flex flex-col items-center justify-center text-center px-6" style={{ background: '#FFFBEB', border: '1px dashed #F1D89B' }} role="status">
      <IconAlert size={28} style={{ color: '#A16207' }} />
      <div className="mt-3 text-[13px] font-semibold" style={{ color: '#713F12' }}>完整统计数据尚未启用</div>
      <div className="mt-1 text-[11.5px] max-w-[430px]" style={{ color: '#8A5A18' }}>当前后端仅提供所示日期范围的排班明细，无法据此推算整月或全年。为避免静默低估，月度与年度图表已停止展示，请升级后端服务后重试。</div>
    </div>
  );
}

function AnalyticsPanel({ period, chartView, year, month, rows, selectedDate, onSelectDate }) {
  const prefix = period === 'year' ? `${year}-` : `${year}-${pad2(month)}-`;
  const scopedRows = rows.filter((row) => row.isoDate.startsWith(prefix));
  if (scopedRows.length === 0) return <AnalyticsEmpty year={year} month={month} period={period} />;
  if (chartView === 'line') return <TrendChart year={year} month={month} period={period} rows={scopedRows} />;
  return period === 'year'
    ? <YearHeatmap year={year} rows={scopedRows} selectedDate={selectedDate} onSelect={onSelectDate} />
    : <MonthHeatmap year={year} month={month} rows={scopedRows} selectedDate={selectedDate} onSelect={onSelectDate} />;
}

function PeriodDropdown({ period, year, month, years, onYear, onMonth }) {
  const [open, setOpen] = React.useState(false);
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (!open) return;
    const close = (event) => { if (ref.current && !ref.current.contains(event.target)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className="h-9 md:h-7 px-2.5 rounded-lg inline-flex items-center gap-1.5 text-[12px] font-medium focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-1"
        style={{ border: '1px solid var(--border-soft)', color: 'var(--text-medium)', background: open ? '#F8FAFC' : '#fff' }}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <IconCalendar size={12} />
        {period === 'year' ? `${year} 年` : `${year} 年 ${month} 月`}
        <IconChevron dir={open ? 'up' : 'down'} size={12} className="text-slate-400" />
      </button>
      {open && (
        <div className="absolute left-0 sm:left-auto sm:right-0 top-full mt-1.5 p-3 rounded-xl z-30" style={{ width: 240, background: '#fff', border: '1px solid var(--border-soft)', boxShadow: '0 16px 36px rgba(15,23,42,.14)' }} role="dialog" aria-label="选择统计时间">
          <div className="text-[10.5px] font-medium mb-2" style={{ color: 'var(--text-soft)' }}>可用年份</div>
          <div className="flex flex-wrap gap-1.5">
            {years.map((option) => {
              const active = option === year;
              return (
                <button key={option} type="button" onClick={() => { onYear(option); if (period === 'year') setOpen(false); }} className="h-8 px-2.5 rounded-lg text-[11.5px] font-medium focus:outline-none focus:ring-2 focus:ring-indigo-500" style={{ background: active ? '#E9EFFF' : '#F8FAFC', color: active ? '#2449A7' : 'var(--text-medium)', border: active ? '1px solid #B9CEFA' : '1px solid var(--border-soft)' }} aria-pressed={active}>
                  {option}
                </button>
              );
            })}
          </div>
          {period === 'month' && (
            <>
              <div className="text-[10.5px] font-medium mt-3 mb-2" style={{ color: 'var(--text-soft)' }}>月份</div>
              <div className="grid grid-cols-4 gap-1.5">
                {Array.from({ length: 12 }, (_, index) => index + 1).map((option) => {
                  const active = option === month;
                  return (
                    <button key={option} type="button" onClick={() => { onMonth(option); setOpen(false); }} className="h-8 rounded-lg text-[11.5px] font-medium focus:outline-none focus:ring-2 focus:ring-indigo-500" style={{ background: active ? '#315FD5' : '#F8FAFC', color: active ? '#fff' : 'var(--text-medium)', border: active ? '1px solid #315FD5' : '1px solid var(--border-soft)' }} aria-pressed={active}>
                      {option} 月
                    </button>
                  );
                })}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function TimelineLoading() {
  return (
    <div className="min-h-[280px] rounded-xl p-4 animate-pulse" style={{ background: '#FAFBFC', border: '1px solid var(--border-soft)' }} aria-live="polite" aria-label="正在加载考试安排">
      <div className="h-3 w-32 rounded" style={{ background: '#E2E8F0' }} />
      <div className="grid grid-cols-7 gap-2 mt-5">
        {Array.from({ length: 28 }, (_, index) => <span key={index} className="h-10 rounded-lg" style={{ background: index % 5 === 0 ? '#DDE8FF' : '#EEF2F7' }} />)}
      </div>
    </div>
  );
}

function TimelineError({ message, onRetry }) {
  return (
    <div className="min-h-[280px] rounded-xl flex flex-col items-center justify-center text-center px-6" style={{ background: '#FFF9F8', border: '1px solid #F5D0C9' }} role="alert">
      <IconAlert size={28} style={{ color: '#C2413B' }} />
      <div className="mt-3 text-[13px] font-semibold" style={{ color: '#7F1D1D' }}>考试安排暂时无法加载</div>
      <div className="mt-1 text-[11.5px] max-w-[420px]" style={{ color: '#9F3A38' }}>{message || '请检查后端服务后重试；页面不会使用模拟数据替代。'}</div>
      <Button size="sm" className="mt-4" onClick={onRetry}>重新加载</Button>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// Orchestrator
// ──────────────────────────────────────────────────────────────
function TimelineCard() {
  const [view, setView] = React.useState('week');
  const [chartView, setChartView] = React.useState('heatmap');
  const [dayIdx, setDayIdx] = React.useState(0);
  const [year, setYear] = React.useState(new Date().getFullYear());
  const [month, setMonth] = React.useState(new Date().getMonth() + 1);
  const [selectedDate, setSelectedDate] = React.useState('');
  const [timeline, setTimeline] = React.useState(null);
  const [loadState, setLoadState] = React.useState('loading');
  const [loadError, setLoadError] = React.useState('');
  const requestRef = React.useRef(0);

  const loadTimeline = React.useCallback(async () => {
    const requestId = requestRef.current + 1;
    requestRef.current = requestId;
    setLoadState('loading');
    setLoadError('');
    if (!window.api?.get) {
      setLoadState('error');
      setLoadError('当前页面没有可用的数据服务。');
      return;
    }
    try {
      const payload = await window.api.get('/timeline/schedule');
      if (requestRef.current !== requestId) return;
      const rows = Array.isArray(payload?.calendarDays) ? normalizeCalendarDays(payload) : [];
      const dataYears = [...new Set(rows.map((row) => Number(row.isoDate.slice(0, 4))))].filter(Boolean).sort((a, b) => a - b);
      const declaredYears = (Array.isArray(payload?.availableYears) ? payload.availableYears : []).map(Number).filter(Boolean);
      const years = [...new Set([...declaredYears, ...dataYears])].sort((a, b) => a - b);
      const preferred = rows[rows.length - 1]?.isoDate || payload?.currentDate;
      const parts = parseIsoParts(preferred);
      const preferredYear = years.includes(parts.year) ? parts.year : years[years.length - 1] || parts.year || new Date().getFullYear();
      setTimeline(payload || {});
      setYear(preferredYear);
      setMonth(parts.month || Number(payload?.currentMonth) || 1);
      setSelectedDate(ISO_DATE.test(String(preferred || '')) ? preferred : toIsoDate(preferredYear, 1, 1));
      const todayIndex = (Array.isArray(payload?.days) ? payload.days : []).findIndex((day) => day.today);
      setDayIdx(todayIndex >= 0 ? todayIndex : 0);
      setLoadState('ready');
    } catch (error) {
      if (requestRef.current !== requestId) return;
      console.warn('[timeline] request failed', error);
      setLoadError(error?.message || '请求考试安排失败');
      setLoadState('error');
    }
  }, []);

  React.useEffect(() => {
    loadTimeline();
    return () => { requestRef.current += 1; };
  }, [loadTimeline]);

  const days = Array.isArray(timeline?.days) ? timeline.days : [];
  const exams = Array.isArray(timeline?.exams) ? timeline.exams : [];
  const analyticsSupported = Array.isArray(timeline?.calendarDays);
  const calendarRows = normalizeCalendarDays(timeline || {});
  const dataYears = calendarRows.map((row) => Number(row.isoDate.slice(0, 4))).filter(Boolean);
  const years = [...new Set([...(Array.isArray(timeline?.availableYears) ? timeline.availableYears.map(Number) : []), ...dataYears])].filter(Boolean).sort((a, b) => a - b);
  if (years.length === 0) years.push(year);

  const VIEWS = [
    { id: 'day', label: '日' },
    { id: 'week', label: '周' },
    { id: 'month', label: '月' },
    { id: 'year', label: '年' },
  ];
  const isAnalytics = view === 'month' || view === 'year';
  const scopedPrefix = view === 'year' ? `${year}-` : `${year}-${pad2(month)}-`;
  const scopedRows = calendarRows.filter((row) => row.isoDate.startsWith(scopedPrefix));
  const scopedExamCount = scopedRows.reduce((sum, row) => sum + row.examCount, 0);
  const firstDate = String(days[0]?.isoDate || '');
  const lastDate = String(days[days.length - 1]?.isoDate || '');
  const dateRange = ISO_DATE.test(firstDate) && ISO_DATE.test(lastDate) && firstDate <= lastDate
    ? `${firstDate} — ${lastDate}` : '日期范围未提供';
  const selectedDayDate = String((days[dayIdx] || days[0])?.isoDate || '');
  const dayDateLabel = ISO_DATE.test(selectedDayDate) ? selectedDayDate : '日期未提供';
  const title = ({ day: '所选日期考试安排', week: '排班周览', month: '月度考试分布', year: '年度考试分布' })[view];
  const subtitle = isAnalytics && !analyticsSupported
    ? '当前数据服务不支持完整月度与年度统计'
    : view === 'day'
    ? `${dayDateLabel} · 最近一次成功排班 · 时间、考场与监考人数`
    : view === 'week'
      ? `${dateRange} · 最近一次成功排班 · ${exams.length} 场考试`
      : `${year} 年${view === 'month' ? ` ${month} 月` : ''} · ${scopedExamCount} 场考试 · ${scopedRows.length} 个考试日 · 各任务最近成功排班汇总`;

  return (
    <section className="bg-white rounded-2xl shadow-card p-4 md:p-5 pb-3" style={{ border: '1px solid var(--border-soft)' }} aria-labelledby="dashboard-exam-overview-title">
      <div className="flex flex-col xl:flex-row xl:items-end justify-between mb-4 gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h3 id="dashboard-exam-overview-title" className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>{title}</h3>
            {loadState === 'ready' && (!isAnalytics || analyticsSupported) && <span className="inline-flex items-center gap-1 text-[12px] font-medium" style={{ color: 'var(--text-medium)' }}><span className="w-1.5 h-1.5 rounded-full" style={{ background: '#36A475' }} />真实排班数据</span>}
            {loadState === 'ready' && isAnalytics && !analyticsSupported && <span className="inline-flex items-center gap-1 text-[12px] font-medium" style={{ color: 'var(--text-medium)' }}><span className="w-1.5 h-1.5 rounded-full" style={{ background: '#D69E2E' }} />完整统计未启用</span>}
          </div>
          <div className="text-[12px] mt-1" style={{ color: 'var(--text-soft)' }} aria-live="polite">{loadState === 'ready' ? subtitle : loadState === 'loading' ? '正在读取考试安排…' : '数据连接异常'}</div>
        </div>
        <div className="flex flex-wrap items-center gap-2 shrink-0">
          <div className="inline-flex p-0.5 rounded-lg" style={{ background: 'var(--surface-muted)' }} role="group" aria-label="日历视图">
            {VIEWS.map((option) => {
              const active = view === option.id;
              return (
                <button key={option.id} type="button" onClick={() => setView(option.id)} className="px-3 h-9 md:h-7 text-[12px] font-medium rounded-md transition-[background-color,box-shadow,color] focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-1" style={{ background: active ? 'var(--surface-raised)' : 'transparent', color: active ? 'var(--text-strong)' : 'var(--text-soft)', boxShadow: active ? 'var(--elevation-1)' : 'none' }} aria-pressed={active}>
                  {option.label}
                </button>
              );
            })}
          </div>
          {isAnalytics && analyticsSupported && (
            <div className="inline-flex p-0.5 rounded-lg" style={{ background: 'var(--surface-muted)' }} role="group" aria-label="图表类型">
              {[{ id: 'heatmap', label: '热力' }, { id: 'line', label: '趋势' }].map((option) => {
                const active = chartView === option.id;
                return <button key={option.id} type="button" onClick={() => setChartView(option.id)} className="px-2.5 h-9 md:h-7 text-[12px] font-medium rounded-md focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-1" style={{ background: active ? 'var(--surface-raised)' : 'transparent', color: active ? 'var(--brand)' : 'var(--text-soft)', boxShadow: active ? 'var(--elevation-1)' : 'none' }} aria-pressed={active}>{option.label}</button>;
              })}
            </div>
          )}
          {isAnalytics && analyticsSupported && <PeriodDropdown period={view} year={year} month={month} years={years} onYear={setYear} onMonth={setMonth} />}
        </div>
      </div>

      {loadState === 'loading' && <TimelineLoading />}
      {loadState === 'error' && <TimelineError message={loadError} onRetry={loadTimeline} />}
      {loadState === 'ready' && view === 'week' && <WeekView days={days} exams={exams} currentTime={timeline?.currentTime || ''} onPickDay={(index) => { setDayIdx(index); setView('day'); }} />}
      {loadState === 'ready' && view === 'day' && <DayView days={days} exams={exams} dayIdx={dayIdx} setDayIdx={setDayIdx} />}
      {loadState === 'ready' && isAnalytics && (analyticsSupported
        ? <AnalyticsPanel period={view} chartView={chartView} year={year} month={month} rows={calendarRows} selectedDate={selectedDate} onSelectDate={setSelectedDate} />
        : <AnalyticsUnavailable />)}

      {loadState === 'ready' && !isAnalytics && (
        <div className="flex flex-wrap items-center justify-between gap-2 pt-3 mt-1 text-[11px]" style={{ borderTop: '1px solid var(--border-soft)', color: 'var(--text-soft)' }}>
          <span>条目颜色仅用于快速区分考试，不代表考试类型</span>
          <span className="font-mono">数据更新 · {timeline?.generatedAt ? String(timeline.generatedAt).slice(11, 16) : '—'}</span>
        </div>
      )}
    </section>
  );
}

window.TimelineCard = TimelineCard;
