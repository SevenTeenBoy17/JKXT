// 一键排监考 — 单页 4 步向导
// 子模块: quick-step1 / quick-step2 / quick-step3 / quick-step4
// 状态机集中在此文件, 各 Step 仅消费 props.

// ─────────────────────────────────────────────────────────────────
// Mock 数据 (前端写死, 仅用于预览)
// 新结构: overview(5 KPI) + counts(4 档) + categories(6 类) + items
// ─────────────────────────────────────────────────────────────────
const QK_VALIDATION_CATEGORIES = [
  { id: 'fields', name: '字段完整性', icon: 'IconCheck',    desc: '字段是否齐全' },
  { id: 'avail',  name: '教师可用性', icon: 'IconUsers',    desc: '免监与冲突标记' },
  { id: 'cap',    name: '考场容量',   icon: 'IconBuilding', desc: '座位需求与可用' },
  { id: 'time',   name: '时间冲突',   icon: 'IconClock',    desc: '同教师同考场重叠' },
  { id: 'rules',  name: '班主任 / 科目规则', icon: 'IconBook', desc: '回避 错峰 混合' },
  { id: 'notes',  name: '特殊备注',   icon: 'IconBell',     desc: '人员与教室备注' },
];

const QK_VALIDATION_DATASETS = {
  pass: {
    overview: { completeness: 100, teachers: 142, rooms: 24, exams: 8, supervisions: 48 },
    counts:   { pass: 30, suggest: 2, warn: 0, error: 0 },
    cats: {
      fields: { pass: 6,  suggest: 0, warn: 0, error: 0 },
      avail:  { pass: 6,  suggest: 0, warn: 0, error: 0 },
      cap:    { pass: 12, suggest: 0, warn: 0, error: 0 },
      time:   { pass: 4,  suggest: 0, warn: 0, error: 0 },
      rules:  { pass: 2,  suggest: 0, warn: 0, error: 0 },
      notes:  { pass: 0,  suggest: 2, warn: 0, error: 0 },
    },
    items: [
      { severity: 'suggest', category: 'notes', source: '教师表', ref: '行 24',
        problem: '教师『张红』标注『孕产期免监』· 已自动排除',
        suggest: '无需操作 · 备注已生效',
        status: '可继续' },
      { severity: 'suggest', category: 'notes', source: '考场表', ref: '行 11',
        problem: '教室 C-305 标注『多媒体设备维护中』· 已自动避让',
        suggest: '无需操作 · 备注已生效',
        status: '可继续' },
    ],
  },
  warn: {
    overview: { completeness: 97, teachers: 142, rooms: 24, exams: 8, supervisions: 48 },
    counts:   { pass: 25, suggest: 5, warn: 3, error: 0 },
    cats: {
      fields: { pass: 5,  suggest: 2, warn: 0, error: 0 },
      avail:  { pass: 5,  suggest: 0, warn: 1, error: 0 },
      cap:    { pass: 11, suggest: 0, warn: 1, error: 0 },
      time:   { pass: 4,  suggest: 0, warn: 0, error: 0 },
      rules:  { pass: 0,  suggest: 1, warn: 1, error: 0 },
      notes:  { pass: 0,  suggest: 2, warn: 0, error: 0 },
    },
    items: [
      { severity: 'suggest', category: 'fields', source: '教师表', ref: '行 24',
        problem: '教师『张红』性别字段为空',
        suggest: '补全后可参与男女混合策略,空缺时该策略对此教师不生效',
        status: '可继续' },
      { severity: 'suggest', category: 'fields', source: '考场表', ref: '4 行',
        problem: '4 个考场未填『楼栋』字段',
        suggest: '系统已使用默认值,如需精确楼道分配请回原表补全',
        status: '可继续' },
      { severity: 'warn', category: 'avail', source: '教师表', ref: '行 87',
        problem: '李志强老师同一时段已有学校会议 (示例数据)',
        suggest: '改派给王慧老师 或 调整考试时段',
        status: '建议复核' },
      { severity: 'warn', category: 'cap', source: '考场表 / 考试表', ref: '时段 3',
        problem: 'C-305 第 3 时段需 480 座位 · 当前 460 · 缺口 20',
        suggest: '启用备用考场 D-410 分流,或拆分该时段考试规模',
        status: '建议复核' },
      { severity: 'warn', category: 'rules', source: '教师表', ref: '11-26',
        problem: '同年级回避存在 2 处临近时段',
        suggest: '复核 11-26 上午 1/2 时段教师分配是否需调整',
        status: '建议复核' },
      { severity: 'suggest', category: 'rules', source: '教师表 / 考试表', ref: '—',
        problem: '男女混合监考策略生效率 78% · 低于建议值 85%',
        suggest: '部分时段男女教师比例失衡,无需强制调整',
        status: '可继续' },
      { severity: 'suggest', category: 'notes', source: '教师表', ref: '行 24',
        problem: '教师『张红』标注『孕产期免监』· 已自动排除',
        suggest: '无需操作 · 备注已生效',
        status: '可继续' },
      { severity: 'suggest', category: 'notes', source: '考场表', ref: '行 11',
        problem: '教室 C-305 标注『多媒体设备维护中』· 已自动避让',
        suggest: '无需操作 · 备注已生效',
        status: '可继续' },
    ],
  },
  block: {
    overview: { completeness: 92, teachers: 142, rooms: 24, exams: 8, supervisions: 48 },
    counts:   { pass: 25, suggest: 3, warn: 3, error: 1 },
    cats: {
      fields: { pass: 4,  suggest: 1, warn: 0, error: 1 },
      avail:  { pass: 5,  suggest: 0, warn: 1, error: 0 },
      cap:    { pass: 11, suggest: 0, warn: 1, error: 0 },
      time:   { pass: 4,  suggest: 0, warn: 0, error: 0 },
      rules:  { pass: 1,  suggest: 0, warn: 1, error: 0 },
      notes:  { pass: 0,  suggest: 2, warn: 0, error: 0 },
    },
    items: [
      { severity: 'error', category: 'fields', source: '考试表', ref: '行 5',
        problem: '考试表第 5 场次『考试日期』字段为空',
        suggest: '必须在源表中补全考试日期后重新上传,否则无法排入时间网格',
        status: '需修改' },
      { severity: 'suggest', category: 'fields', source: '教师表', ref: '行 24',
        problem: '教师『张红』性别字段为空',
        suggest: '空缺时该教师将不参与男女混合策略',
        status: '可继续' },
      { severity: 'warn', category: 'avail', source: '教师表', ref: '行 87',
        problem: '李志强老师同一时段已有学校会议 (示例数据)',
        suggest: '改派给王慧老师 或 调整考试时段',
        status: '建议复核' },
      { severity: 'warn', category: 'cap', source: '考场表', ref: '时段 3',
        problem: 'C-305 第 3 时段座位缺口 20',
        suggest: '启用备用考场 D-410 分流',
        status: '建议复核' },
      { severity: 'warn', category: 'rules', source: '教师表', ref: '11-26',
        problem: '同年级回避存在 2 处临近时段',
        suggest: '复核 11-26 上午 1/2 时段教师分配',
        status: '建议复核' },
      { severity: 'suggest', category: 'notes', source: '教师表', ref: '行 24',
        problem: '教师『张红』标注『孕产期免监』· 已自动排除',
        suggest: '无需操作 · 备注已生效',
        status: '可继续' },
      { severity: 'suggest', category: 'notes', source: '考场表', ref: '行 11',
        problem: '教室 C-305 标注『多媒体设备维护中』· 已自动避让',
        suggest: '无需操作 · 备注已生效',
        status: '可继续' },
    ],
  },
};

const QK_AI_SUMMARIES = [
  '李志强老师本次仅 1 场监考 · 与上学期均值 2.3 场偏差较大 · 建议下次轮换提高优先级',
  'C 栋 305 教室 3 天内被连续使用 · 建议在「修改监考安排」中替换为 C-306 平衡负载',
  '周二上午 2 场考试集中安排 · 当时段需要 24 位监考教师 · 压力高于其他时段',
  '本次工作量标准差 0.7 · 优于上学期 0.9 · 班主任避监本班策略生效良好',
  '识别到 3 位教师跨年级监考 · 已严格遵循「同年级回避」策略',
];

const QK_STRATEGIES = [
  { id: 's1', label: '班主任避监本班', desc: '严格回避' },
  { id: 's2', label: '同年级回避',     desc: '严格回避' },
  { id: 's3', label: '男女混合监考',   desc: '尽量满足' },
  { id: 's4', label: '科目错峰',       desc: '尽量满足' },
  { id: 's5', label: '工作量均衡',     desc: '加权优化' },
];

// 暴露给子模块
window.QK_VALIDATION_DATASETS  = QK_VALIDATION_DATASETS;
window.QK_VALIDATION_CATEGORIES = QK_VALIDATION_CATEGORIES;
window.QK_AI_SUMMARIES         = QK_AI_SUMMARIES;
window.QK_STRATEGIES           = QK_STRATEGIES;

// ─────────────────────────────────────────────────────────────────
// QkTooltip — CSS hover tooltip · 用于完整度公式 / 公平性指标解释
// ─────────────────────────────────────────────────────────────────
function QkTooltip({ text, position = 'top', children, className = '', maxWidth = 220 }) {
  const posClass =
    position === 'top'
      ? 'bottom-full left-1/2 -translate-x-1/2 mb-2'
      : position === 'bottom'
        ? 'top-full left-1/2 -translate-x-1/2 mt-2'
        : position === 'right'
          ? 'left-full top-1/2 -translate-y-1/2 ml-2'
          : 'right-full top-1/2 -translate-y-1/2 mr-2';
  return (
    <span className={`relative inline-flex group ${className}`}>
      {children}
      <span
        className={`absolute ${posClass} z-50 px-2.5 py-1.5 rounded-lg pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity duration-150`}
        style={{
          background: 'linear-gradient(180deg, #1E293B, #0F172A)',
          color: '#F1F5F9',
          fontSize: 11,
          fontWeight: 500,
          maxWidth,
          width: 'max-content',
          boxShadow: '0 8px 24px -8px rgba(15,23,42,.30), 0 1px 2px rgba(15,23,42,.10)',
          lineHeight: 1.6,
          textAlign: 'left',
          letterSpacing: '0.01em',
        }}
      >
        {text}
        <span
          className="absolute"
          style={{
            ...(position === 'top' && { top: '100%', left: '50%', marginLeft: -4 }),
            ...(position === 'bottom' && { bottom: '100%', left: '50%', marginLeft: -4 }),
            ...(position === 'left' && { left: '100%', top: '50%', marginTop: -4 }),
            ...(position === 'right' && { right: '100%', top: '50%', marginTop: -4 }),
            width: 0, height: 0,
            borderLeft: '4px solid transparent',
            borderRight: '4px solid transparent',
            ...(position === 'top' && { borderTop: '4px solid #0F172A', borderBottom: 'none' }),
            ...(position === 'bottom' && { borderBottom: '4px solid #1E293B', borderTop: 'none' }),
          }}
        />
      </span>
    </span>
  );
}

// ─────────────────────────────────────────────────────────────────
// 顶部 Mock 横条 (sessionStorage 记忆关闭态; 监听 jkxt:showMockBanner 重新打开)
// ─────────────────────────────────────────────────────────────────
function MockBanner({ backendMode = false }) {
  const [show, setShow] = React.useState(() => {
    try { return sessionStorage.getItem('jkxt:mockBanner') !== '0'; } catch { return true; }
  });
  React.useEffect(() => {
    const onShow = () => {
      try { sessionStorage.removeItem('jkxt:mockBanner'); } catch {}
      setShow(true);
    };
    window.addEventListener('jkxt:showMockBanner', onShow);
    return () => window.removeEventListener('jkxt:showMockBanner', onShow);
  }, []);
  if (!show) return null;
  return (
    <div
      className="rounded-xl px-4 py-2.5 flex items-center gap-3 text-[12.5px]"
      style={{
        background: 'linear-gradient(180deg, #FFFBEB 0%, #FEF3C7 100%)',
        border: '1px solid #FCD34D',
        boxShadow: '0 1px 0 rgba(255,255,255,.7) inset, 0 1px 2px rgba(180,83,9,.06)',
        color: '#92400E',
      }}
    >
      <span
        className="font-mono text-[10px] tracking-[0.16em] uppercase px-1.5 py-0.5 rounded shrink-0"
        style={{ background: '#FBBF24', color: '#78350F', fontWeight: 700 }}
      >
        Preview
      </span>
      <span className="leading-snug">
        {backendMode
          ? <>本页已接入<span className="font-semibold">后端任务闭环</span>：创建 task · 上传/校验 · schedule_run · assignments · xlsx/pdf 导出。</>
          : <>本页在<span className="font-semibold">浏览器内真实完成</span>：解析·校验·排班·导出真实 xlsx。<span className="font-semibold">未</span>连接学校服务端 / 数据库。</>}
      </span>
      <div className="flex-1" />
      <button
        onClick={() => { try { sessionStorage.setItem('jkxt:mockBanner', '0'); } catch {}; setShow(false); }}
        className="text-[11.5px] font-medium px-2 py-0.5 rounded hover:bg-amber-200/60 transition-colors"
        style={{ color: '#92400E' }}
      >
        我知道了 ✕
      </button>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// 步骤指示器 (sticky · 4 步)
// ─────────────────────────────────────────────────────────────────
function QkStepIndicator({ step, running, onJump }) {
  const steps = [
    { id: 'prep',   label: '准备数据', sub: '模板 · 上传 3 表' },
    { id: 'verify', label: '上传校验', sub: '字段 · 容量 · 冲突' },
    { id: 'run',    label: '智能排班', sub: '规则 · 求解 · 输出' },
    { id: 'result', label: '生成结果', sub: '查看 · 下载 · 修改' },
  ];

  return (
    <div
      className="rounded-2xl px-6 py-4 sticky z-30"
      style={{
        top: 72,
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04), 0 8px 24px -12px rgba(15,23,42,.10)',
        backdropFilter: 'saturate(180%) blur(8px)',
      }}
    >
      <div className="flex items-center">
        {steps.map((s, i) => {
          const done = i < step;
          const active = i === step;
          const isRunning = running && active;
          const canJump = i <= step;
          return (
            <React.Fragment key={s.id}>
              <button
                onClick={() => canJump && onJump?.(i)}
                disabled={!canJump}
                className="flex items-center gap-3 shrink-0 group"
                style={{ cursor: canJump ? 'pointer' : 'not-allowed' }}
              >
                <span
                  className="relative w-10 h-10 rounded-full inline-flex items-center justify-center font-mono font-bold text-[13px] transition-all"
                  style={{
                    background: done
                      ? 'linear-gradient(180deg, #10B981 0%, #059669 100%)'
                      : active
                        ? 'linear-gradient(180deg, var(--brand) 0%, var(--brand-hover) 100%)'
                        : '#F1F5F9',
                    color: (done || active) ? '#fff' : 'var(--text-soft)',
                    boxShadow: active
                      ? '0 0 0 4px rgba(79,70,229,.14), 0 1px 0 rgba(255,255,255,.3) inset, 0 4px 10px -2px rgba(79,70,229,.30)'
                      : done
                        ? '0 1px 0 rgba(255,255,255,.3) inset, 0 2px 6px -2px rgba(16,185,129,.30)'
                        : 'inset 0 -1px 0 #E2E8F0',
                    animation: isRunning ? 'qkPulse 1.4s ease-in-out infinite' : 'none',
                  }}
                >
                  {done ? <IconCheck size={16} strokeWidth={3} /> : i + 1}
                </span>
                <div className="text-left">
                  <div
                    className="text-[10px] uppercase font-mono tracking-[0.14em] leading-none"
                    style={{ color: (done || active) ? 'var(--text-soft)' : '#CBD5E1' }}
                  >
                    STEP {String(i + 1).padStart(2, '0')}
                  </div>
                  <div
                    className="text-[13.5px] font-semibold mt-1.5 leading-none transition-colors group-hover:underline-offset-2"
                    style={{
                      color: (done || active) ? 'var(--text-strong)' : 'var(--text-soft)',
                      textDecoration: canJump && done ? 'none' : 'none',
                    }}
                  >
                    {s.label}
                  </div>
                  <div
                    className="text-[11px] mt-1 leading-none whitespace-nowrap"
                    style={{ color: 'var(--text-soft)' }}
                  >
                    {s.sub}
                  </div>
                </div>
              </button>
              {i < steps.length - 1 && (
                <div className="flex-1 mx-4 relative h-px">
                  <div className="absolute inset-0" style={{ background: '#E2E8F0' }} />
                  <div
                    className="absolute inset-y-0 left-0 transition-all duration-500"
                    style={{
                      width: done ? '100%' : '0%',
                      background: 'linear-gradient(90deg, #10B981, #34D399)',
                    }}
                  />
                </div>
              )}
            </React.Fragment>
          );
        })}
      </div>
      <style>{`
        @keyframes qkPulse {
          0%, 100% { box-shadow: 0 0 0 4px rgba(79,70,229,.14), 0 1px 0 rgba(255,255,255,.3) inset, 0 4px 10px -2px rgba(79,70,229,.30); }
          50%      { box-shadow: 0 0 0 8px rgba(79,70,229,.06), 0 1px 0 rgba(255,255,255,.3) inset, 0 4px 10px -2px rgba(79,70,229,.30); }
        }
      `}</style>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// 主页面 — 状态机 + 编排
// ─────────────────────────────────────────────────────────────────
function qkBackendKind(slot) {
  return ({ t01: 'teacher', t02: 'classroom', t03: 'exam' })[slot] || slot;
}

function qkRowsFromReport(slot, report) {
  const overview = report?.overview || {};
  if (slot === 't01') return overview.teachers ?? null;
  if (slot === 't02') return overview.rooms ?? null;
  if (slot === 't03') return overview.exams ?? null;
  return null;
}

function qkUploadStateFromReport(report) {
  const severity = report?.severity;
  if (severity === 'error') return 'error';
  if (severity === 'warn' || severity === 'suggest') return 'warning';
  return 'uploaded';
}

function qkIssueFromReport(report) {
  if (!report || report.severity === 'pass') return null;
  const first = (report.items || [])[0];
  return {
    summary: first?.problem || report.severity,
    detail: first?.suggest || first?.code || '',
  };
}

function qkRunStageFromProgress(progress) {
  const value = Math.max(0, Math.min(100, Number(progress || 0)));
  if (value >= 100) return 6;
  return Math.max(0, Math.min(5, Math.floor(value / 20)));
}

function qkTerminalJob(status) {
  return ['succeeded', 'failed', 'infeasible', 'cancelled'].includes(String(status || '').toLowerCase());
}

function qkLogsFromJob(job) {
  const logs = Array.isArray(job?.logs) ? job.logs : [];
  return logs.map((entry) => ({
    t: String(entry.at || entry.time || '').slice(11, 23) || new Date().toLocaleTimeString(),
    text: entry.message || entry.stage || JSON.stringify(entry),
  }));
}

function qkScheduleFromJob(job) {
  const rows = Array.isArray(job?.assignments) ? job.assignments : [];
  const teachers = new Map();
  rows.forEach((row) => {
    const key = row.teacherId || row.teacherName || 'unknown';
    const prev = teachers.get(key) || { id: row.teacherId, name: row.teacherName || 'Unknown teacher', grade: row.grade || '', count: 0 };
    prev.count += 1;
    teachers.set(key, prev);
  });
  return {
    rows,
    teachers: Array.from(teachers.values()),
    stats: {
      exams: new Set(rows.map((row) => row.examId)).size,
      supervisions: rows.length,
      teachersUsed: teachers.size,
      rooms: new Set(rows.map((row) => row.roomId || row.roomCode)).size,
      source: 'backend',
    },
  };
}

function QuickSchedulePage() {
  // ── 状态 ──────────────────────────────────────────────────────
  const [step, setStep] = React.useState(0);            // 0/1/2/3 → Step 1..4
  const [uploads, setUploads] = React.useState({
    t01: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
    t02: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
    t03: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
  });

  // ── 演示开关 (从 Tweaks 同步) ─────────────────────────────────
  const [demo, setDemo] = React.useState(() => {
    const w = window.__jkxtDemo || {};
    return {
      validation:    w.validation    || 'warn', // pass / warn / block
      volume:        w.volume        || '48',   // 48 / 240
      aiCount:       w.aiCount       || 3,
      speed:         w.speed         || '1x',   // 1x / 2x / instant
      uploadResult:  w.uploadResult  || 'clean',// clean / warn-t01 / warn-t02 / err-t03
    };
  });
  React.useEffect(() => {
    const onChange = (e) => {
      const d = e.detail || {};
      const keys = ['validation', 'volume', 'aiCount', 'speed', 'uploadResult'];
      const next = {};
      let hit = false;
      keys.forEach((k) => {
        const fk = 'demo' + k.charAt(0).toUpperCase() + k.slice(1);
        if (fk in d) { next[k] = d[fk]; hit = true; }
      });
      if (hit) setDemo((prev) => ({ ...prev, ...next }));
    };
    window.addEventListener('tweakchange', onChange);
    return () => window.removeEventListener('tweakchange', onChange);
  }, []);

  // ── 校验状态 ──────────────────────────────────────────────────
  const [validationReport, setValidationReport] = React.useState(null);
  const validationData = validationReport || QK_VALIDATION_DATASETS[demo.validation] || QK_VALIDATION_DATASETS.warn;
  const [validatedAt, setValidatedAt] = React.useState(null);     // Date | null
  const [validating, setValidating]   = React.useState(false);

  // ── 排班进度 ──────────────────────────────────────────────────
  const [running, setRunning]         = React.useState(false);
  const [runStage, setRunStage]       = React.useState(-1);        // -1 idle → 0..3 → 4 done
  const [runLogs, setRunLogs]         = React.useState([]);
  const [resultAt, setResultAt]       = React.useState(null);

  // ── 真实闭环：解析缓存 / 排班结果 / 任务编号 ─────────────────
  const parsedRef = React.useRef({ t01: null, t02: null, t03: null });
  const taskIdRef = React.useRef(null);
  const backendJobRef = React.useRef(null);
  const [schedule, setSchedule] = React.useState(null);   // { rows, teachers, stats }
  const [taskId, setTaskId]     = React.useState(null);
  const [dataSource, setDataSource] = React.useState('sample'); // 'upload' | 'sample'
  const [backendResult, setBackendResult] = React.useState(null);
  const [backendDegraded, setBackendDegraded] = React.useState(false);
  const backendMode = !!window.api && !backendDegraded;
  const backendActive = backendMode && !!(taskId || validationReport || backendResult);

  // ── Refs (用于步骤间平滑滚动) ────────────────────────────────
  const refs = {
    step1: React.useRef(null),
    step2: React.useRef(null),
    step3: React.useRef(null),
    step4: React.useRef(null),
    templates: React.useRef(null),
  };
  const scrollTo = (refKey, off = -84) => {
    const el = refs[refKey]?.current;
    if (!el) return;
    const top = el.getBoundingClientRect().top + window.scrollY + off;
    window.scrollTo({ top, behavior: 'smooth' });
  };

  const allUploaded = Object.values(uploads).every((u) => u.state === 'uploaded' || u.state === 'warning');
  const hasError      = Object.values(uploads).some((u) => u.state === 'error');
  const anyValidating = Object.values(uploads).some((u) => u.state === 'validating');
  const allReady      = allUploaded && !hasError;

  // ── Mock 数据 (按 slot key 索引) ─────────────────────────────
  const SLOT_META = {
    t01: { rows: 142, size: 56, niceName: '实验中学_教师档案_2025秋.xlsx' },
    t02: { rows: 36,  size: 18, niceName: '实验中学_考场信息_2025秋.xlsx' },
    t03: { rows: 48,  size: 32, niceName: '2025秋·初二期中考_排考.xlsx' },
  };
  const SLOT_ISSUE = {
    t01: window.QK_SLOTS?.find((s) => s.key === 't01')?.issues || {},
    t02: window.QK_SLOTS?.find((s) => s.key === 't02')?.issues || {},
    t03: window.QK_SLOTS?.find((s) => s.key === 't03')?.issues || {},
  };

  // ── Actions ───────────────────────────────────────────────────
  const setSlot = (slot, patch) =>
    setUploads((prev) => ({ ...prev, [slot]: { ...prev[slot], ...patch } }));

  const ensureBackendTask = async () => {
    if (!window.api) throw new Error('API adapter is not available');
    if (taskIdRef.current) return { id: taskIdRef.current };
    const created = await window.api.post('/exam-tasks', {
      name: `Quick ${new Date().toLocaleString()}`,
      source: 'quick',
    });
    taskIdRef.current = created.id;
    setTaskId(created.id);
    try { localStorage.setItem('jkxt:taskDetailId', created.id); } catch {}
    return created;
  };

  const applyBackendUpload = (slot, file, response, source) => {
    const report = response?.report;
    const state = qkUploadStateFromReport(report);
    setValidationReport(null);
    setSlot(slot, {
      state,
      name: response?.originalName || file?.name || `sample-${slot}.xlsx`,
      rows: qkRowsFromReport(slot, report),
      size: response?.sizeBytes ? Math.max(1, Math.round(response.sizeBytes / 1024)) : (file?.size ? Math.round(file.size / 1024) : null),
      source,
      updatedAt: new Date(),
      issue: qkIssueFromReport(report),
    });
    setDataSource(source);
    return state;
  };

  const waitBackendJob = async (jobId) => {
    let latest = null;
    for (let i = 0; i < 80; i++) {
      latest = await window.api.get(`/schedule-jobs/${jobId}`);
      setBackendResult(latest);
      setRunStage(qkRunStageFromProgress(latest.progress));
      setRunLogs(qkLogsFromJob(latest));
      if (qkTerminalJob(latest.status)) return latest;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error('Schedule job timeout');
  };

  React.useEffect(() => {
    if (!window.api) return;
    let handoff = null;
    try {
      const raw = sessionStorage.getItem('jkxt:quickHandoff');
      if (raw) {
        handoff = JSON.parse(raw);
        sessionStorage.removeItem('jkxt:quickHandoff');
      }
    } catch {
      handoff = null;
    }
    if (!handoff?.taskId || !handoff?.jobId) return;
    const now = new Date();
    taskIdRef.current = handoff.taskId;
    backendJobRef.current = handoff.jobId;
    setTaskId(handoff.taskId);
    setDataSource('sample');
    setBackendDegraded(false);
    setValidationReport(handoff.validationReport || QK_VALIDATION_DATASETS.pass);
    setValidatedAt(now);
    setUploads({
      t01: { state: 'uploaded', name: 'AI-policy-teachers-sample.xlsx', rows: 48, size: 24, source: 'ai_policy', updatedAt: now, issue: null },
      t02: { state: 'uploaded', name: 'AI-policy-classrooms-sample.xlsx', rows: 10, size: 16, source: 'ai_policy', updatedAt: now, issue: null },
      t03: { state: 'uploaded', name: 'AI-policy-exams-sample.xlsx', rows: 6, size: 18, source: 'ai_policy', updatedAt: now, issue: null },
    });
    setStep(2);
    setRunning(true);
    setRunStage(0);
    setRunLogs([
      { t: now.toLocaleTimeString('zh-CN'), text: 'AI policy parsed and handed off to Quick progress view.' },
      { t: now.toLocaleTimeString('zh-CN'), text: 'Solver authority remains OR-Tools; LLM output is policy DSL only.' },
    ]);
    setTimeout(() => scrollTo('step3'), 120);
    waitBackendJob(handoff.jobId)
      .then((done) => {
        const sched = qkScheduleFromJob(done);
        setSchedule(sched);
        setTaskId(done.taskId);
        taskIdRef.current = done.taskId;
        setBackendResult(done);
        setRunStage(6);
        setRunning(false);
        setStep(3);
        setResultAt(new Date());
        try { localStorage.setItem('jkxt:taskDetailId', done.taskId); } catch {}
        setTimeout(() => scrollTo('step4'), 200);
      })
      .catch((err) => {
        setBackendDegraded(true);
        setRunning(false);
        window.ui?.toast?.('AI policy job handoff failed', { kind: 'warning', desc: String(err?.message || err) });
      });
  }, []);

  // 根据 demo.uploadResult 决定该 slot 落到什么终态
  const finalStateFor = (slot, source) => {
    const r = demo.uploadResult;
    if (r === 'warn-t01' && slot === 't01') return { state: 'warning', issue: SLOT_ISSUE.t01.warn };
    if (r === 'warn-t02' && slot === 't02') return { state: 'warning', issue: SLOT_ISSUE.t02.warn };
    if (r === 'err-t03'  && slot === 't03') return { state: 'error',   issue: SLOT_ISSUE.t03.err  };
    return { state: 'uploaded', issue: null };
  };

  const startUpload = async (slot, file) => {
    const isSample = !file;
    setSlot(slot, {
      state: 'validating',
      name: file?.name || `示例-${slot}.xlsx`,
      rows: null,
      size: file?.size ? Math.round(file.size / 1024) : null,
      source: isSample ? 'sample' : 'upload',
      updatedAt: null,
      issue: null,
    });

    if (window.api) {
      try {
        const task = await ensureBackendTask();
        const response = await window.api.upload(`/exam-tasks/${task.id}/uploads`, isSample ? null : file, {
          kind: qkBackendKind(slot),
          sample: String(isSample),
        });
        setBackendDegraded(false);
        const state = applyBackendUpload(slot, file, response, isSample ? 'sample' : 'upload');
        window.ui?.toast?.(`${slot.toUpperCase()} uploaded to backend`, {
          kind: state === 'error' ? 'warning' : state === 'warning' ? 'info' : 'success',
          desc: state === 'error' ? 'Backend validation found blocking items' : 'Backend upload and validation completed',
        });
        return;
      } catch (err) {
        setBackendDegraded(true);
        window.ui?.toast?.('Backend upload unavailable; using local fallback', {
          kind: 'warning',
          desc: String(err?.message || err),
        });
      }
    }

    // ── 真实文件：浏览器内解析 + 结构校验（非模拟）──────────
    if (!isSample && window.JKXT?.hasXLSX?.()) {
      window.JKXT.parseWorkbook(file).then((parsed) => {
        const { state, issue } = window.JKXT.validateSlot(slot, parsed);
        parsedRef.current[slot] = state === 'error' ? null : parsed;
        if (state !== 'error') setDataSource('upload');
        setSlot(slot, {
          state,
          name: file.name,
          rows: parsed.rowCount,
          size: Math.round(file.size / 1024),
          source: 'upload',
          updatedAt: new Date(),
          issue,
        });
        const toastConf = state === 'error'
          ? { kind: 'warning', desc: issue?.summary || '结构校验未通过' }
          : state === 'warning'
            ? { kind: 'info', desc: issue?.summary || '含提示项' }
            : { kind: 'success', desc: `${parsed.rowCount} 行 · 表头与必填字段校验通过` };
        window.ui?.toast?.(`${slot.toUpperCase()} ${state === 'uploaded' ? '已解析' : state === 'warning' ? '已解析 · 含提示' : '解析未通过'}`, toastConf);
      }).catch((err) => {
        parsedRef.current[slot] = null;
        setSlot(slot, {
          state: 'error', name: file.name, rows: null,
          size: Math.round(file.size / 1024), source: 'upload', updatedAt: new Date(),
          issue: { summary: '无法解析该文件', detail: `${String(err?.message || err)}\n请上传有效的 .xlsx / .xls 表格（首行为表头）。` },
        });
        window.ui?.toast?.(`${slot.toUpperCase()} 解析失败`, { kind: 'warning', desc: '请确认是有效的 Excel 文件' });
      });
      return;
    }

    // ── 示例数据 / 无解析组件兜底：沿用既有演示场景 ──────────
    const dur = demo.speed === 'instant' ? 200 : demo.speed === '2x' ? 600 : 1200;
    setTimeout(() => {
      const meta = SLOT_META[slot];
      const { state, issue } = finalStateFor(slot, isSample ? 'sample' : 'upload');
      parsedRef.current[slot] = null;
      setSlot(slot, {
        state,
        name: file?.name || meta.niceName,
        rows: meta.rows,
        size: meta.size,
        source: isSample ? 'sample' : 'upload',
        updatedAt: new Date(),
        issue,
      });
      if (isSample) setDataSource('sample');
      const toastConf = state === 'error'
        ? { kind: 'warning', desc: issue?.summary || '存在错误' }
        : state === 'warning'
          ? { kind: 'info', desc: issue?.summary || '存在警告' }
          : { kind: 'success', desc: `${meta.rows} 行 · 结构校验通过` };
      window.ui?.toast?.(`${slot.toUpperCase()} ${state === 'uploaded' ? '已就绪' : state === 'warning' ? '已就绪 · 含警告' : '存在错误'}（示例）`, toastConf);
    }, dur);
  };

  const removeSlot = (slot) => {
    parsedRef.current[slot] = null;
    setSlot(slot, { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null });
    window.ui?.toast?.(`${slot.toUpperCase()} 已移除`, { kind: 'info', desc: '可重新上传或载入示例' });
  };

  const useExampleData = async () => {
    const now = new Date();
    if (window.api) {
      try {
        const task = await ensureBackendTask();
        for (const slot of ['t01', 't02', 't03']) {
          const meta = SLOT_META[slot];
          setSlot(slot, {
            state: 'validating',
            name: meta.niceName,
            rows: null,
            size: null,
            source: 'sample',
            updatedAt: null,
            issue: null,
          });
          const response = await window.api.upload(`/exam-tasks/${task.id}/uploads`, null, {
            kind: qkBackendKind(slot),
            sample: 'true',
          });
          applyBackendUpload(slot, null, response, 'sample');
        }
        const validation = await window.api.post(`/exam-tasks/${task.id}/imports/validate`, {});
        setBackendDegraded(false);
        setValidationReport(validation.report);
        setValidatedAt(new Date());
        setStep(1);
        setTimeout(() => scrollTo('step2'), 100);
        window.ui?.toast?.('Backend sample data loaded', { kind: 'success', desc: 'Three sample workbooks are attached and validated' });
        return;
      } catch (err) {
        setBackendDegraded(true);
        window.ui?.toast?.('Backend sample unavailable; using local fallback', {
          kind: 'warning',
          desc: String(err?.message || err),
        });
      }
    }
    const apply = (slot) => {
      const meta = SLOT_META[slot];
      const { state, issue } = finalStateFor(slot, 'sample');
      setSlot(slot, {
        state,
        name: meta.niceName.replace(/^[^_]+_/, '示例-'),
        rows: meta.rows,
        size: meta.size,
        source: 'sample',
        updatedAt: now,
        issue,
      });
    };
    apply('t01'); apply('t02'); apply('t03');
    const r = demo.uploadResult;
    const note = r === 'clean' ? '3 张表已就绪 · 全部通过'
      : r === 'warn-t01' ? '3 张表已就绪 · 表 01 含警告'
      : r === 'warn-t02' ? '3 张表已就绪 · 表 02 含警告'
      : r === 'err-t03'  ? '表 03 有错误 · 请先修正'
      : '3 张表已就绪';
    window.ui?.toast?.('已载入示例数据 (模拟)', { kind: r === 'err-t03' ? 'warning' : 'info', desc: note });
  };

  const startValidation = async () => {
    if (!allReady) return;
    setValidating(true);
    setStep(1);
    if (window.api && taskIdRef.current) {
      try {
        const response = await window.api.post(`/exam-tasks/${taskIdRef.current}/imports/validate`, {});
        setBackendDegraded(false);
        setValidationReport(response.report);
        setValidatedAt(new Date());
        setValidating(false);
        scrollTo('step2');
        window.ui?.toast?.('Backend validation completed', {
          kind: response.report?.severity === 'error' ? 'warning' : 'success',
          desc: response.report?.severity === 'error' ? 'Fix blocking items before scheduling' : 'Task is ready for scheduling',
        });
        return;
      } catch (err) {
        setBackendDegraded(true);
        window.ui?.toast?.('Backend validation unavailable; using local fallback', {
          kind: 'warning',
          desc: String(err?.message || err),
        });
      }
    }
    const dur = demo.speed === 'instant' ? 200 : demo.speed === '2x' ? 500 : 900;
    setTimeout(() => {
      setValidatedAt(new Date());
      setValidating(false);
      scrollTo('step2');
    }, dur);
  };

  const revalidate = async () => {
    setValidating(true);
    if (window.api && taskIdRef.current) {
      try {
        const response = await window.api.post(`/exam-tasks/${taskIdRef.current}/imports/validate`, {});
        setBackendDegraded(false);
        setValidationReport(response.report);
        setValidatedAt(new Date());
        setValidating(false);
        window.ui?.toast?.('Backend validation refreshed', {
          kind: response.report?.severity === 'error' ? 'warning' : 'success',
          desc: response.report?.severity === 'error' ? 'Fix blocking items before scheduling' : 'Task is ready for scheduling',
        });
        return;
      } catch (err) {
        setBackendDegraded(true);
        window.ui?.toast?.('Backend revalidation unavailable; using local fallback', {
          kind: 'warning',
          desc: String(err?.message || err),
        });
      }
    }
    const dur = demo.speed === 'instant' ? 200 : demo.speed === '2x' ? 500 : 900;
    setTimeout(() => {
      setValidatedAt(new Date());
      setValidating(false);
      window.ui?.toast?.('已重新校验 (模拟)', { kind: 'info', desc: '基于当前数据集刷新结果' });
    }, dur);
  };

  const startScheduling = async () => {
    if (validationData.counts.error > 0) {
      window.ui?.toast?.('请先修正错误项', { kind: 'warning', desc: `当前存在 ${validationData.counts.error} 项阻断排监考的错误` });
      return;
    }
    // R6 去 mock：排监考结果一律来自真实后端 OR-Tools 求解，不再有"无后端降级"的伪造排班。
    if (!window.api || !taskIdRef.current) {
      window.ui?.toast?.('无法排监考', {
        kind: 'warning',
        desc: '请先成功导入并校验三张表（教师 / 考场 / 考试），系统将基于真实数据由 OR-Tools 求解。',
      });
      return;
    }
    setStep(2);
    setRunning(true);
    setRunStage(0);
    setRunLogs([]);
    setBackendResult(null);
    setTimeout(() => scrollTo('step3'), 100);
    try {
      if (!validationReport) {
        const validation = await window.api.post(`/exam-tasks/${taskIdRef.current}/imports/validate`, {});
        setValidationReport(validation.report);
        setValidatedAt(new Date());
        if ((validation.report?.counts?.error || 0) > 0) {
          setRunning(false);
          setStep(1);
          setTimeout(() => scrollTo('step2'), 100);
          window.ui?.toast?.('校验未通过', { kind: 'warning', desc: '请先修正阻断排监考的错误项后再试' });
          return;
        }
      }
      const started = await window.api.post(`/exam-tasks/${taskIdRef.current}/schedule-jobs`, {});
      backendJobRef.current = started.jobId || started.runId;
      setBackendResult(started);
      setRunLogs(qkLogsFromJob(started));
      setRunStage(qkRunStageFromProgress(started.progress));
      const done = await waitBackendJob(backendJobRef.current);
      if (done.status && done.status !== 'succeeded') {
        // 求解返回 infeasible / failed：如实呈现冲突，绝不伪造成功。
        setRunning(false);
        setStep(1);
        setTimeout(() => scrollTo('step2'), 100);
        window.ui?.toast?.(done.status === 'infeasible' ? '当前数据无可行排监考方案' : '排监考求解失败', {
          kind: 'warning',
          desc: done.errorMessage || '请根据冲突项调整教师 / 考场容量 / 约束后重试。',
        });
        return;
      }
      const sched = qkScheduleFromJob(done);
      setSchedule(sched);
      setTaskId(done.taskId);
      taskIdRef.current = done.taskId;
      setBackendResult(done);
      setRunStage(6);
      setRunning(false);
      setStep(3);
      setResultAt(new Date());
      try { localStorage.setItem('jkxt:taskDetailId', done.taskId); } catch {}
      setTimeout(() => scrollTo('step4'), 200);
    } catch (err) {
      setRunning(false);
      setStep(1);
      if (err?.code === 'SCHEDULE_INPUT_INCOMPLETE') {
        window.ui?.toast?.('数据不完整，无法排监考', {
          kind: 'warning',
          desc: err.message || '请先完整导入教师、考场与考试安排（含每场考试的考场绑定）后再排考。',
        });
      } else {
        window.ui?.toast?.('排监考失败', { kind: 'error', desc: String(err?.message || err) });
      }
      setTimeout(() => scrollTo('step2'), 100);
    }
  };

  const restartAll = async () => {
    const ok = await window.ui?.confirm?.({
      title: '重新开始?',
      desc: backendMode
        ? '将清空当前页面中的 3 张表与排班结果。已创建的后端任务会保留在任务总览中。'
        : '将清空已上传的 3 张表与本次排班结果。此动作仅前端模拟,不会影响服务端。',
      confirmText: '确认重置',
      cancelText: '取消',
      danger: true,
    });
    if (!ok) return;
    setStep(0);
    parsedRef.current = { t01: null, t02: null, t03: null };
    taskIdRef.current = null;
    backendJobRef.current = null;
    setSchedule(null);
    setTaskId(null);
    setValidationReport(null);
    setBackendResult(null);
    setDataSource('sample');
    setUploads({
      t01: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
      t02: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
      t03: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
    });
    setValidatedAt(null);
    setRunStage(-1);
    setRunLogs([]);
    setResultAt(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const reschedule = () => {
    setStep(1);
    setRunStage(-1);
    setRunLogs([]);
    setResultAt(null);
    setBackendResult(null);
    setTimeout(() => scrollTo('step2'), 100);
  };

  const goEdit = () => window.navigateTo?.('edit');

  // 跳转 + 滚动
  const onJump = (i) => {
    setStep(i);
    setTimeout(() => scrollTo(['step1','step2','step3','step4'][i]), 60);
  };

  return (
    <PageShell
      crumbs={['天津市实验中学', '排监考作业', '一键排监考']}
      title="一键排监考"
      subtitle={
        <span className="whitespace-nowrap">
          上传<span className="font-medium" style={{ color: 'var(--text-strong)' }}> 教师 / 考场 / 考试安排 </span>三张表 · {backendMode ? '创建后端排监考任务' : '生成排监考方案预览（模拟）'}
        </span>
      }
      actions={
        <>
          <Button icon={IconHelp} onClick={() => window.ui?.toast?.('已在新窗口打开示例任务（模拟）', { kind: 'info' })}>查看示例任务</Button>
          <Button icon={IconSparkle} onClick={useExampleData}>载入示例数据</Button>
          <Button icon={IconList} onClick={() => window.navigateTo?.('tasks')}>历史任务</Button>
        </>
      }
    >
      {/* 校园 / 学期 胶囊 - 紧贴标题区底部 */}
      <div className="-mt-4 flex items-center gap-2 flex-wrap">
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11.5px] font-medium whitespace-nowrap"
          style={{ background: '#EEF2FF', color: '#4338CA', border: '1px solid rgba(79,70,229,.14)' }}
        >
          <IconBuilding size={12} strokeWidth={2} />
          天津市实验中学
        </span>
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11.5px] font-medium whitespace-nowrap"
          style={{ background: '#ECFDF5', color: '#047857', border: '1px solid rgba(16,185,129,.18)' }}
        >
          <IconCalendar size={12} strokeWidth={2} />
          2025 秋季学期
        </span>
        <span className="text-[11px] whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
          在「信息设置」中切换
        </span>
        <span className="flex-1" />
        <button
          onClick={() => window.dispatchEvent(new CustomEvent('jkxt:showMockBanner'))}
          className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md text-[10.5px] font-mono uppercase tracking-wider whitespace-nowrap transition-colors"
          style={{ background: '#F1F5F9', color: '#64748B', border: '1px solid #E2E8F0' }}
          onMouseEnter={(e) => { e.currentTarget.style.background = '#FEF3C7'; e.currentTarget.style.borderColor = '#FCD34D'; e.currentTarget.style.color = '#92400E'; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = '#F1F5F9'; e.currentTarget.style.borderColor = '#E2E8F0'; e.currentTarget.style.color = '#64748B'; }}
          title="点击重新显示顶部模式提示"
        >
          <span className="w-1.5 h-1.5 rounded-full" style={{ background: backendMode ? '#10B981' : '#F59E0B' }} />
          {backendMode ? backendActive ? '后端任务 · 已接入' : '后端模式 · 待创建任务' : '预览模式 · 未接入服务端'}
        </button>
      </div>

      <MockBanner backendMode={backendMode} />

      <QkStepIndicator step={step} running={running} onJump={onJump} />

      {/* Step 1 */}
      <div ref={refs.step1}>
        <QkStep1
          uploads={uploads}
          allReady={allReady}
          hasError={hasError}
          anyValidating={anyValidating}
          onStartUpload={startUpload}
          onRemoveSlot={removeSlot}
          onUseExample={useExampleData}
          onNext={startValidation}
          validating={validating}
          templatesRef={refs.templates}
          backendMode={backendMode}
        />
      </div>

      {/* Step 2 — 仅在三张表全部就绪 (uploaded/warning) 后出现, 错误态不进入 */}
      {allReady && (
        <div ref={refs.step2}>
          <QkStep2
            data={validationData}
            validating={validating}
            validatedAt={validatedAt}
            onRevalidate={revalidate}
            onBack={() => { setStep(0); setTimeout(() => scrollTo('step1'), 60); }}
            onNext={startScheduling}
            locateRow={() => { setStep(0); setTimeout(() => scrollTo('step1'), 60); }}
            backendActive={!!validationReport}
          />
        </div>
      )}

      {/* Step 3 — 仅在点击开始排班后出现 */}
      {(running || runStage >= 0) && (
        <div ref={refs.step3}>
          <QkStep3
            running={running}
            stage={runStage}
            logs={runLogs}
            strategies={QK_STRATEGIES}
            onScrollToResult={() => scrollTo('step4')}
            backendActive={!!backendResult}
          />
        </div>
      )}

      {/* Step 4 — 仅在排班完成后出现 */}
      {resultAt && !running && (
        <div ref={refs.step4}>
          <QkStep4
            resultAt={resultAt}
            volume={demo.volume}
            aiCount={demo.aiCount}
            taskId={taskId}
            schedule={schedule}
            result={backendResult}
            dataSource={dataSource}
            onGoEdit={goEdit}
            onReschedule={reschedule}
            onRestart={restartAll}
          />
        </div>
      )}
    </PageShell>
  );
}

window.QuickSchedulePage = QuickSchedulePage;
window.MockBanner = MockBanner;
window.QkStepIndicator = QkStepIndicator;
window.QkTooltip = QkTooltip;
