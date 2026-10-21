// 一键排监考 — 单页 4 步向导
// 子模块: quick-step1 / quick-step2 / quick-step3 / quick-step4
// 状态机集中在此文件, 各 Step 仅消费 props.

// ─────────────────────────────────────────────────────────────────
// 校验分类；计数与结论仅来自服务返回的实际报告。
// ─────────────────────────────────────────────────────────────────
const QK_VALIDATION_CATEGORIES = [
  { id: 'fields', name: '字段完整性', icon: 'IconCheck',    desc: '字段是否齐全' },
  { id: 'avail',  name: '教师可用性', icon: 'IconUsers',    desc: '免监与冲突标记' },
  { id: 'cap',    name: '考场容量',   icon: 'IconBuilding', desc: '座位需求与可用' },
  { id: 'time',   name: '时间冲突',   icon: 'IconClock',    desc: '同教师同考场重叠' },
  { id: 'rules',  name: '班主任 / 科目规则', icon: 'IconBook', desc: '回避 错峰 混合' },
  { id: 'notes',  name: '特殊备注',   icon: 'IconBell',     desc: '人员与教室备注' },
];


// 迭代9 P0-E 策略诚实化：求解器实际强制的仅 班主任避本班/同年级回避/科目回避/工作量均衡。
// 男女混合监考(gender_mix)暂未纳入自动排考约束——如实标注为"仅参考"，避免让教务误以为已强制执行。
const QK_STRATEGIES = [
  { id: 's1', label: '班主任避监本班', desc: '严格回避' },
  { id: 's2', label: '同年级回避',     desc: '严格回避' },
  { id: 's3', label: '男女混合监考',   desc: '仅参考·暂未纳入约束' },
  { id: 's4', label: '科目错峰',       desc: '尽量满足' },
  { id: 's5', label: '工作量均衡',     desc: '加权优化' },
];

// 暴露给子模块
window.QK_VALIDATION_CATEGORIES = QK_VALIDATION_CATEGORIES;
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
          fontSize: 12,
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
      className="rounded-xl px-4 py-2.5 flex items-start sm:items-center gap-3 text-[12.5px]"
      style={{
        background: 'linear-gradient(180deg, #FFFBEB 0%, #FEF3C7 100%)',
        border: '1px solid #FCD34D',
        boxShadow: '0 1px 0 rgba(255,255,255,.7) inset, 0 1px 2px rgba(180,83,9,.06)',
        color: '#92400E',
      }}
    >
      <span
        className="text-[12px] font-semibold px-2 py-0.5 rounded shrink-0"
        style={{ background: '#FBBF24', color: '#78350F', fontWeight: 700 }}
      >
        服务状态
      </span>
      <span className="min-w-0 flex-1 leading-snug">
        {backendMode
          ? <>请上传本校实际文件，完成校验后生成排监考安排。导入结果与任务会保存在当前学校。</>
          : <>学校服务暂不可用，请恢复连接后重试导入或校验。当前没有新的成功结果。</>}
      </span>
      <button
        onClick={() => { try { sessionStorage.setItem('jkxt:mockBanner', '0'); } catch {}; setShow(false); }}
        className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap text-[12px] font-medium px-2 py-1 rounded hover:bg-amber-200/60 transition-colors"
        style={{ color: '#92400E' }}
      >
        我知道了
        <IconPlus size={13} strokeWidth={2.25} className="rotate-45" />
      </button>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// 步骤指示器 (sticky · 4 步)
// ─────────────────────────────────────────────────────────────────
function QkStepIndicator({ step, running, onJump }) {
  const steps = [
    { id: 'prep',   label: '准备数据', sub: '模板 · 上传 3 表', glyph: 'upload' },
    { id: 'verify', label: '上传校验', sub: '字段 · 容量 · 冲突', glyph: 'audit' },
    { id: 'run',    label: '智能排班', sub: '规则 · 求解 · 输出', glyph: 'quick' },
    { id: 'result', label: '生成结果', sub: '查看 · 下载 · 修改', glyph: 'stats' },
  ];

  return (
    <div
      className="qk-step-card rounded-2xl px-5 py-3.5 sticky z-30"
      style={{
        top: 72,
        background: '#FFFFFF',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 8px 22px -18px rgba(15,23,42,.30)',
      }}
    >
      <div className="qk-step-scroll-hint hidden items-center justify-end gap-1 px-1 pb-1 text-[12px]" style={{ color: 'var(--text-soft)' }}>
        左右滑动查看全部 4 步
        <IconChevron dir="right" size={12} />
      </div>
      <div className="qk-step-row flex items-center">
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
                className="qk-step-action flex items-center gap-3 shrink-0 rounded-xl px-2.5 py-2 text-left transition-colors"
                style={{
                  cursor: canJump ? 'pointer' : 'not-allowed',
                  background: active ? '#F7F8FF' : 'transparent',
                  opacity: canJump ? 1 : 0.58,
                }}
                aria-current={active ? 'step' : undefined}
              >
                <span
                  className="qk-step-glyph relative w-11 h-11 rounded-xl inline-flex items-center justify-center shrink-0"
                  style={{
                    background: done ? '#F1FAF6' : active ? '#EEF2FF' : '#F8FAFC',
                    border: `1px solid ${done ? '#CFE9DC' : active ? '#CDD5FF' : '#E2E8F0'}`,
                  }}
                >
                  <ProductGlyph name={s.glyph} size={27} active={active} />
                  <span
                    className="absolute -right-1 -bottom-1 min-w-[18px] h-[18px] px-1 rounded-full inline-flex items-center justify-center text-[12px] font-semibold"
                    style={{
                      background: done ? '#238164' : active ? '#4C5FD7' : '#E7ECF2',
                      color: done || active ? '#fff' : '#657287',
                      border: '2px solid #fff',
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {i + 1}
                  </span>
                </span>
                <div className="qk-step-copy text-left">
                  <div
                    className="text-[12px] font-medium leading-none"
                    style={{ color: done ? '#238164' : active ? '#4C5FD7' : 'var(--text-soft)' }}
                  >
                    {done ? '已完成' : isRunning ? '处理中' : `第 ${i + 1} 步`}
                  </div>
                  <div
                    className="text-[14px] font-semibold mt-1.5 leading-none"
                    style={{
                      color: (done || active) ? 'var(--text-strong)' : 'var(--text-soft)',
                    }}
                  >
                    {s.label}
                  </div>
                  <div
                    className="qk-step-sub text-[12px] mt-1.5 leading-none whitespace-nowrap"
                    style={{ color: 'var(--text-soft)' }}
                  >
                    {s.sub}
                  </div>
                </div>
              </button>
              {i < steps.length - 1 && (
                <div className="qk-step-connector flex-1 mx-3 relative h-px min-w-8">
                  <div className="absolute inset-0" style={{ background: '#E2E8F0' }} />
                  <div
                    className="absolute inset-y-0 left-0 transition-[width] duration-300"
                    style={{
                      width: done ? '100%' : '0%',
                      background: '#72B49A',
                    }}
                  />
                </div>
              )}
            </React.Fragment>
          );
        })}
      </div>
      <style>{`
        @media (max-width: 900px) {
          .qk-step-card {
            overflow-x: auto;
            scrollbar-width: thin;
            overscroll-behavior-inline: contain;
          }
          .qk-step-row { min-width: 760px; }
        }
        @media (max-width: 640px) {
          .qk-step-card {
            position: relative !important;
            top: auto !important;
            z-index: 1 !important;
            overflow-x: auto;
            padding: 10px !important;
          }
          .qk-step-scroll-hint {
            display: flex !important;
            position: sticky;
            left: 0;
            width: calc(100vw - 56px);
          }
          .qk-step-row { min-width: 690px; }
          .qk-step-action { padding-inline: 8px; }
          .qk-step-connector { margin-inline: 8px; min-width: 26px; }
        }
        @media (prefers-reduced-motion: reduce) {
          .qk-step-action,
          .qk-step-connector > div { transition: none !important; }
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
  if (!['pass', 'suggest', 'warn', 'error'].includes(severity)) throw new Error('服务未返回有效的文件校验报告，请重试上传');
  if (severity === 'error') return 'error';
  if (severity === 'warn' || severity === 'suggest') return 'warning';
  return 'uploaded';
}

function qkRequireValidationReport(response) {
  const report = response?.report;
  if (!report?.overview || !report?.cats || !Array.isArray(report?.items)
    || !['pass', 'suggest', 'warn', 'error'].includes(report.severity)
    || !['pass', 'suggest', 'warn', 'error'].every(key => Number.isFinite(report.counts?.[key]) && report.counts[key] >= 0)) {
    throw new Error('服务未返回完整的校验报告，请重试校验');
  }
  return report;
}

function qkIsServiceUnavailable(error) {
  if (!window.api?.post || !window.api?.upload) return true;
  const status = Number(error?.status);
  if (Number.isInteger(status) && status >= 100) return [502, 503, 504].includes(status);
  return error?.name === 'TimeoutError'
    || ['NETWORK_ERROR', 'ERR_NETWORK', 'ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT'].includes(error?.code)
    || /failed to fetch|fetch failed|network request failed|networkerror|load failed/i.test(String(error?.message || ''));
}

function qkIssueFromReport(report) {
  if (!report || report.severity === 'pass') return null;
  const first = (report.items || [])[0];
  return {
    summary: first?.problem || report.severity,
    detail: first?.suggest || first?.code || '',
  };
}

function qkTerminalJob(status) {
  return ['succeeded', 'failed', 'infeasible', 'cancelled'].includes(String(status || '').toLowerCase());
}

function qkRequireCompletedJob(job, requestedTaskId) {
  if (job?.status !== 'succeeded') {
    throw new Error(job?.errorMessage || '服务尚未确认排监考成功，请在「任务总览」查看任务状态。');
  }
  if (typeof requestedTaskId !== 'string' || !requestedTaskId.trim()
    || (job.taskId != null && job.taskId !== requestedTaskId)) {
    throw new Error('排监考结果的任务编号与当前请求不一致，请在「任务总览」核对。');
  }
  return { ...job, taskId: requestedTaskId };
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
    const prev = teachers.get(key) || { id: row.teacherId, name: row.teacherName || '未知教师', grade: row.grade || '', count: 0 };
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

function qkCurrentTermLabel(term) {
  if (typeof term?.name === 'string' && term.name.trim()) return term.name.trim();
  const year = typeof term?.yearLabel === 'string' ? term.yearLabel.trim() : '';
  const semester = { S1: '第一学期', S2: '第二学期' }[term?.term];
  return year && semester ? `${year} · ${semester}` : '当前学期未提供';
}

function QuickSchedulePage() {
  // ── 状态 ──────────────────────────────────────────────────────
  const [step, setStep] = React.useState(0);            // 0/1/2/3 → Step 1..4
  const [uploads, setUploads] = React.useState({
    t01: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
    t02: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
    t03: { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null },
  });
  const [currentTerm, setCurrentTerm] = React.useState(() => window.__jkxtMe?.term || null);
  React.useEffect(() => {
    const syncTerm = () => setCurrentTerm(window.__jkxtMe?.term || null);
    syncTerm();
    window.addEventListener('jkxt:term-changed', syncTerm);
    window.addEventListener('jkxt:me-changed', syncTerm);
    return () => {
      window.removeEventListener('jkxt:term-changed', syncTerm);
      window.removeEventListener('jkxt:me-changed', syncTerm);
    };
  }, []);

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
  const validationData = validationReport;
  const [validationError, setValidationError] = React.useState('');
  const [validatedAt, setValidatedAt] = React.useState(null);     // Date | null
  const [validating, setValidating]   = React.useState(false);

  // ── 排班进度 ──────────────────────────────────────────────────
  const [running, setRunning]         = React.useState(false);
  const [runStage, setRunStage]       = React.useState(-1);        // -1 idle → 0 waiting/running → 6 confirmed result
  const [runLogs, setRunLogs]         = React.useState([]);
  const [resultAt, setResultAt]       = React.useState(null);

  // ── 真实闭环：解析缓存 / 排班结果 / 任务编号 ─────────────────
  const parsedRef = React.useRef({ t01: null, t02: null, t03: null });
  const taskIdRef = React.useRef(null);
  const importRevisionRef = React.useRef(0);
  const validationRequestRef = React.useRef(0);
  const uploadRequestRef = React.useRef({ t01: 0, t02: 0, t03: 0 });
  const taskGenerationRef = React.useRef(0);
  const taskCreationRef = React.useRef(null);
  const backendJobRef = React.useRef(null);
  const [schedule, setSchedule] = React.useState(null);   // { rows, teachers, stats }
  const [taskId, setTaskId]     = React.useState(null);
  const [dataSource, setDataSource] = React.useState('upload'); // 'upload' | 'sample'
  const [backendResult, setBackendResult] = React.useState(null);
  const [backendDegraded, setBackendDegraded] = React.useState(false);
  const backendMode = !!window.api && !backendDegraded;
  const backendActive = backendMode && !!(taskId || validationReport || backendResult);
  const canImportDemo = window.__jkxtMe?.org?.id === '00000000-0000-0000-0000-000000000001';

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

  // ── Actions ───────────────────────────────────────────────────
  const setSlot = (slot, patch) =>
    setUploads((prev) => ({ ...prev, [slot]: { ...prev[slot], ...patch } }));

  const invalidateValidation = () => {
    importRevisionRef.current += 1;
    validationRequestRef.current += 1;
    setValidationReport(null);
    setValidatedAt(null);
    setValidationError('');
    setValidating(false);
  };
  const setActiveTask = (nextId) => {
    if (taskIdRef.current !== nextId) {
      invalidateValidation();
      taskGenerationRef.current += 1;
      taskIdRef.current = nextId;
    }
    setTaskId(nextId);
  };
  const beginValidationRequest = () => ({
    revision: importRevisionRef.current,
    request: ++validationRequestRef.current,
    taskId: taskIdRef.current,
  });
  const isCurrentValidation = (request) => request
    && request.revision === importRevisionRef.current
    && request.request === validationRequestRef.current
    && request.taskId === taskIdRef.current;

  const ensureBackendTask = async () => {
    if (!window.api) throw new Error('接口适配器不可用');
    if (taskIdRef.current) return { id: taskIdRef.current };
    if (!taskCreationRef.current) {
      const generation = taskGenerationRef.current;
      const creation = window.api.post('/exam-tasks', {
        name: `一键排监考 ${new Date().toLocaleString()}`,
        source: 'quick',
      }).then(created => {
        if (generation !== taskGenerationRef.current) throw new Error('任务已变更，请重新上传');
        if (!created?.id) throw new Error('服务未返回任务编号，请重试');
        setActiveTask(created.id);
        try { localStorage.setItem('jkxt:taskDetailId', created.id); } catch {}
        return created;
      });
      taskCreationRef.current = creation;
      creation.finally(() => { if (taskCreationRef.current === creation) taskCreationRef.current = null; }).catch(() => {});
    }
    return taskCreationRef.current;
  };

  const applyBackendUpload = (slot, file, response, source) => {
    const report = response?.report;
    const state = qkUploadStateFromReport(report);
    invalidateValidation();
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

  // P18 批次5.5：SSE 优先 + 轮询兜底，废除 40 秒硬超时。
  // 此前是 500ms×80 轮询，一到 80 次就抛「排监考任务超时」——而后端任务仍在跑并会正常完成，
  // 大任务(200+ 考场)几乎必被误报。后端本就有 /schedule-jobs/{id}/events 进度流
  // (event: progress，data=ProgressEvent JSON)，nginx 已对 events 路径关闭缓冲。
  const applyJobSnapshot = (latest, isEvent = false) => {
    setBackendResult(previous => isEvent ? { ...previous, ...latest } : latest);
    // 100% 进度不等于求解成功；只有确认状态和任务编号后的结果才能进入完成阶段。
    setRunStage(0);
    setRunLogs(previous => isEvent
      ? [...previous, ...qkLogsFromJob({ logs: [latest] })]
      : qkLogsFromJob(latest));
  };

  const waitBackendJob = async (jobId) => {
    const finalSnapshot = async () => {
      const latest = await window.api.get(`/schedule-jobs/${jobId}`);
      applyJobSnapshot(latest);
      return latest;
    };

    // ① SSE：事件驱动，无轮询开销。15 秒收不到任何事件则放弃(代理不透传等)回退轮询。
    const viaSse = () => new Promise((resolve, reject) => {
      let es;
      try { es = window.api.eventSource(`/schedule-jobs/${jobId}/events`); }
      catch (e) { reject(e); return; }
      let settled = false;
      const finish = (fn, v) => { if (settled) return; settled = true; try { es.close(); } catch {} fn(v); };
      let quiet = setTimeout(() => finish(reject, new Error('sse-quiet')), 15000);
      es.addEventListener('progress', (ev) => {
        clearTimeout(quiet);
        quiet = setTimeout(() => finish(reject, new Error('sse-quiet')), 120000); // 事件间隔哨兵
        try {
          const p = JSON.parse(ev.data);
          applyJobSnapshot(p, true);
          if (qkTerminalJob(p.status)) finish(resolve, null);
        } catch { /* 单条坏事件不致命，等下一条或哨兵回退 */ }
      });
      es.onerror = () => finish(reject, new Error('sse-error'));
    });

    try {
      await viaSse();
      return await finalSnapshot();   // 终态以 GET 快照为准(含 conflictSet/统计等完整字段)
    } catch (_) { /* SSE 不可用 → 轮询兜底 */ }

    // ② 轮询兜底：2 秒间隔，只以任务终态为准。上限 10 分钟——远超实测最长求解；
    //    到点也不谎称「任务超时」，如实说明任务仍在后台运行。
    for (let i = 0; i < 300; i++) {
      const latest = await finalSnapshot();
      if (qkTerminalJob(latest.status)) return latest;
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
    throw new Error('等待已超过 10 分钟：任务仍在后台运行，可稍后在「任务总览」查看结果');
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
    let handoffReport = null;
    if (handoff.validationReport != null) {
      try { handoffReport = qkRequireValidationReport({ report: handoff.validationReport }); }
      catch { /* 旧移交允许缺少报告；无效报告同样保持未校验，不生成校验时间。 */ }
    }
    setActiveTask(handoff.taskId);
    backendJobRef.current = handoff.jobId;
    setDataSource(handoff.source === 'sample' ? 'sample' : 'upload');
    setBackendDegraded(false);
    setValidationReport(handoffReport);
    setValidatedAt(handoffReport ? now : null);
    setUploads({
      t01: { state: 'uploaded', name: '策略任务教师数据', rows: qkRowsFromReport('t01', handoffReport), size: null, source: 'ai_policy', updatedAt: now, issue: null },
      t02: { state: 'uploaded', name: '策略任务考场数据', rows: qkRowsFromReport('t02', handoffReport), size: null, source: 'ai_policy', updatedAt: now, issue: null },
      t03: { state: 'uploaded', name: '策略任务考试数据', rows: qkRowsFromReport('t03', handoffReport), size: null, source: 'ai_policy', updatedAt: now, issue: null },
    });
    setStep(2);
    setRunning(true);
    setRunStage(0);
    setRunLogs([
      { t: now.toLocaleTimeString('zh-CN'), text: 'AI 策略已解析，并移交至一键排监考进度视图。' },
      { t: now.toLocaleTimeString('zh-CN'), text: '求解仍由 OR-Tools 主导；大模型仅输出策略 DSL。' },
    ]);
    setTimeout(() => scrollTo('step3'), 120);
    waitBackendJob(handoff.jobId)
      .then((snapshot) => {
        const done = qkRequireCompletedJob(snapshot, handoff.taskId);
        const sched = qkScheduleFromJob(done);
        setSchedule(sched);
        setActiveTask(done.taskId);
        setBackendResult(done);
        setRunStage(6);
        setRunning(false);
        setStep(3);
        setResultAt(new Date());
        try { localStorage.setItem('jkxt:taskDetailId', done.taskId); } catch {}
        setTimeout(() => scrollTo('step4'), 200);
      })
      .catch((err) => {
        setBackendDegraded(qkIsServiceUnavailable(err));
        setRunning(false);
        setRunStage(-1);
        window.ui?.toast?.('AI 策略任务移交失败', { kind: 'warning', desc: String(err?.message || err) });
      });
  }, []);

  const startUpload = async (slot, file) => {
    if (!file) {
      window.ui?.toast?.('请选择实际文件', { kind: 'warning', desc: '请上传本校的 .xlsx / .xls 文件。' });
      return;
    }
    invalidateValidation();
    const uploadRequest = ++uploadRequestRef.current[slot];
    let requestTaskId = taskIdRef.current;
    const isCurrentUpload = () => uploadRequest === uploadRequestRef.current[slot]
      && requestTaskId === taskIdRef.current;
    parsedRef.current[slot] = null;
    setSlot(slot, {
      state: 'validating',
      name: file.name,
      rows: null,
      size: file.size ? Math.round(file.size / 1024) : null,
      source: 'upload',
      updatedAt: null,
      issue: null,
    });

    try {
      if (!window.api?.upload) throw new Error('学校服务暂不可用，请恢复连接后重新上传');
      const task = await ensureBackendTask();
      if (uploadRequest !== uploadRequestRef.current[slot] || task.id !== taskIdRef.current) return;
      requestTaskId = task.id;
      const response = await window.api.upload(`/exam-tasks/${task.id}/uploads`, file, {
        kind: qkBackendKind(slot), sample: 'false',
      });
      if (!isCurrentUpload()) return;
      const state = applyBackendUpload(slot, file, response, 'upload');
      setBackendDegraded(false);
      window.ui?.toast?.(`${slot.toUpperCase()} ${state === 'error' ? '校验未通过' : '上传完成'}`, {
        kind: state === 'error' ? 'warning' : state === 'warning' ? 'info' : 'success',
        desc: state === 'error' ? '请按文件中的错误提示修改后重传' : '文件已提交，结果来自实际校验',
      });
    } catch (err) {
      if (!isCurrentUpload()) return;
      setBackendDegraded(qkIsServiceUnavailable(err));
      setSlot(slot, {
        state: 'error', rows: null, updatedAt: null,
        issue: { summary: '上传未完成', detail: `${String(err?.message || err)}\n请使用“重新上传”重试。` },
      });
      window.ui?.toast?.('上传失败', { kind: 'error', desc: String(err?.message || err) });
    }
  };

  const removeSlot = (slot) => {
    uploadRequestRef.current[slot] += 1;
    parsedRef.current[slot] = null;
    invalidateValidation();
    setSlot(slot, { state: 'idle', name: null, rows: null, size: null, source: null, updatedAt: null, issue: null });
    window.ui?.toast?.(`${slot.toUpperCase()} 已移除`, { kind: 'info', desc: '可重新上传本校文件' });
  };

  const useExampleData = async () => {
    if (!canImportDemo) {
      window.ui?.toast?.('请导入本校文件', { kind: 'warning', desc: '演示数据仅可写入固定演示学校。模板可直接下载。' });
      return;
    }
    invalidateValidation();
    const sampleRequests = Object.fromEntries(['t01', 't02', 't03'].map(slot => [slot, ++uploadRequestRef.current[slot]]));
    let sampleTaskId = taskIdRef.current;
    let validationRequest = null;
    const isCurrentSample = () => ['t01', 't02', 't03'].every(slot => sampleRequests[slot] === uploadRequestRef.current[slot])
      && sampleTaskId === taskIdRef.current;
    try {
        if (!window.api?.upload) throw new Error('学校服务暂不可用，请恢复连接后重试');
        const task = await ensureBackendTask();
        if (!['t01', 't02', 't03'].every(slot => sampleRequests[slot] === uploadRequestRef.current[slot]) || task.id !== taskIdRef.current) return;
        sampleTaskId = task.id;
        for (const slot of ['t01', 't02', 't03']) {
          setSlot(slot, {
            state: 'validating',
            name: `演示-${qkBackendKind(slot)}.xlsx`,
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
          if (!isCurrentSample()) return;
          const state = applyBackendUpload(slot, null, response, 'sample');
          if (state === 'error') throw new Error('演示文件校验未通过，请查看文件错误提示');
        }
        validationRequest = beginValidationRequest();
        setValidating(true);
        const validation = await window.api.post(`/exam-tasks/${task.id}/imports/validate`, {});
        if (!isCurrentValidation(validationRequest) || !isCurrentSample()) return;
        setBackendDegraded(false);
        const report = qkRequireValidationReport(validation);
        setValidationReport(report);
        setValidatedAt(new Date());
        setStep(1);
        setTimeout(() => scrollTo('step2'), 100);
        window.ui?.toast?.(report.counts.error > 0 ? '演示数据校验未通过' : '演示数据已导入演示学校', {
          kind: report.counts.error > 0 ? 'warning' : 'success', desc: '三张演示表已写入演示任务，结果来自实际校验',
        });
    } catch (err) {
        if (!isCurrentSample() || (validationRequest && !isCurrentValidation(validationRequest))) return;
        setBackendDegraded(qkIsServiceUnavailable(err));
        setValidationError(`演示导入或校验未完成：${String(err?.message || err)}`);
        setUploads(previous => Object.fromEntries(Object.entries(previous).map(([slot, upload]) => [slot,
          upload.state === 'validating' ? { ...upload, state: 'error', rows: null, updatedAt: null,
            issue: { summary: '演示导入未完成', detail: String(err?.message || err) } } : upload,
        ])));
        window.ui?.toast?.('演示导入未完成', { kind: 'error', desc: String(err?.message || err) });
    } finally {
      if (isCurrentValidation(validationRequest)) setValidating(false);
      if (!isCurrentSample() && sampleTaskId === taskIdRef.current) {
        setUploads(previous => Object.fromEntries(Object.entries(previous).map(([slot, upload]) => [slot,
          sampleRequests[slot] === uploadRequestRef.current[slot] && upload.source === 'sample' && upload.state === 'validating'
            ? { ...upload, state: 'error', rows: null, updatedAt: null,
              issue: { summary: '演示导入已停止', detail: '导入期间文件已变更，请重新选择文件或导入演示数据。' } }
            : upload,
        ])));
      }
    }
  };

  const validateImports = async () => {
    if (!allReady) return;
    const request = beginValidationRequest();
    setValidating(true);
    setValidationReport(null);
    setValidatedAt(null);
    setValidationError('');
    setStep(1);
    try {
        if (!window.api?.post || !request.taskId) throw new Error('请连接学校服务并成功导入三张表后重试');
        const response = await window.api.post(`/exam-tasks/${request.taskId}/imports/validate`, {});
        if (!isCurrentValidation(request)) return;
        const report = qkRequireValidationReport(response);
        setBackendDegraded(false);
        setValidationReport(report);
        setValidatedAt(new Date());
        setValidating(false);
        scrollTo('step2');
        window.ui?.toast?.('校验已完成', {
          kind: report.counts.error > 0 ? 'warning' : 'success',
          desc: report.counts.error > 0 ? '请先修正阻断项再排监考' : '任务已就绪，可开始排监考',
        });
        return;
    } catch (err) {
        if (!isCurrentValidation(request)) return;
        setBackendDegraded(qkIsServiceUnavailable(err));
        setValidationError(`校验未完成：${String(err?.message || err)}`);
        window.ui?.toast?.('校验失败', { kind: 'error', desc: String(err?.message || err) });
    } finally {
      if (isCurrentValidation(request)) setValidating(false);
    }
  };
  const startValidation = validateImports;
  const revalidate = validateImports;

  const startScheduling = async () => {
    if (!validationData || !validatedAt || validating || validationError) {
      window.ui?.toast?.('请先完成校验', { kind: 'warning', desc: '本次数据尚无有效校验结果，请重试校验。' });
      return;
    }
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
    const requestedTaskId = taskIdRef.current;
    setStep(2);
    setRunning(true);
    setRunStage(0);
    setRunLogs([]);
    setBackendResult(null);
    setTimeout(() => scrollTo('step3'), 100);
    try {
      if (!validationReport) {
        const validation = await window.api.post(`/exam-tasks/${requestedTaskId}/imports/validate`, {});
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
      const started = await window.api.post(`/exam-tasks/${requestedTaskId}/schedule-jobs`, {});
      backendJobRef.current = started.jobId || started.runId;
      setBackendResult(started);
      setRunLogs(qkLogsFromJob(started));
      setRunStage(0);
      const snapshot = await waitBackendJob(backendJobRef.current);
      if (snapshot?.status !== 'succeeded') {
        // 求解返回 infeasible / failed：如实呈现冲突，绝不伪造成功。
        setRunning(false);
        setRunStage(-1);
        setStep(1);
        setTimeout(() => scrollTo('step2'), 100);
        window.ui?.toast?.(snapshot?.status === 'infeasible' ? '当前数据无可行排监考方案' : '排监考求解未确认成功', {
          kind: 'warning',
          desc: snapshot?.errorMessage || '请在「任务总览」核对任务状态，再根据冲突项调整教师 / 考场容量 / 约束后重试。',
        });
        return;
      }
      const done = qkRequireCompletedJob(snapshot, requestedTaskId);
      const sched = qkScheduleFromJob(done);
      setSchedule(sched);
      setActiveTask(done.taskId);
      setBackendResult(done);
      setRunStage(6);
      setRunning(false);
      setStep(3);
      setResultAt(new Date());
      try { localStorage.setItem('jkxt:taskDetailId', done.taskId); } catch {}
      setTimeout(() => scrollTo('step4'), 200);
    } catch (err) {
      setRunning(false);
      setRunStage(-1);
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
        : '将清空当前页面中的文件与校验状态。已提交的任务仍保留在任务总览中。',
      confirmText: '确认重置',
      cancelText: '取消',
      danger: true,
    });
    if (!ok) return;
    invalidateValidation();
    Object.keys(uploadRequestRef.current).forEach(slot => { uploadRequestRef.current[slot] += 1; });
    taskGenerationRef.current += 1;
    taskCreationRef.current = null;
    setStep(0);
    parsedRef.current = { t01: null, t02: null, t03: null };
    setActiveTask(null);
    backendJobRef.current = null;
    setSchedule(null);
    setValidationReport(null);
    setValidationError('');
    setBackendResult(null);
    setDataSource('upload');
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
      crumbs={[orgName(), '排监考作业', '一键排监考']}
      title="一键排监考"
      subtitle={
        <span className="sm:whitespace-nowrap">
          上传<span className="font-medium" style={{ color: 'var(--text-strong)' }}> 教师 / 考场 / 考试安排 </span>三张表 · {backendMode ? '根据实际数据校验与排监考' : '请连接学校服务后导入'}
        </span>
      }
      actions={
        <>
          <Button
            icon={IconHelp}
            onClick={() => {
              const guide = refs.templates.current;
              const toggle = guide?.querySelector?.('button');
              if (toggle?.getAttribute('aria-expanded') !== 'true') toggle?.click?.();
              guide?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
            }}
          >
            查看字段说明
          </Button>
          {canImportDemo && <Button icon={IconSparkle} onClick={useExampleData} disabled={anyValidating || validating}>导入演示数据（写入演示学校）</Button>}
          <Button icon={IconList} onClick={() => window.navigateTo?.('tasks')}>历史任务</Button>
        </>
      }
    >
      {/* 校园 / 学期 胶囊 - 紧贴标题区底部 */}
      <div className="-mt-4 flex items-center gap-2 flex-wrap">
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[12px] font-medium whitespace-nowrap"
          style={{ background: '#EEF2FF', color: '#4338CA', border: '1px solid rgba(79,70,229,.14)' }}
        >
          <IconBuilding size={12} strokeWidth={2} />
          {orgName()}
        </span>
        <span
          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[12px] font-medium whitespace-nowrap"
          style={{ background: '#ECFDF5', color: '#047857', border: '1px solid rgba(16,185,129,.18)' }}
        >
          <IconCalendar size={12} strokeWidth={2} />
          {qkCurrentTermLabel(currentTerm)}
        </span>
        <button type="button" onClick={() => window.navigateTo?.('settings')} className="text-[12px] whitespace-nowrap hover:underline" style={{ color: 'var(--text-soft)' }}>
          在「信息设置」中切换
        </button>
        <span className="flex-1" />
        <button
          onClick={() => window.dispatchEvent(new CustomEvent('jkxt:showMockBanner'))}
          className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md text-[12px] font-medium whitespace-nowrap transition-colors"
          style={{ background: '#F1F5F9', color: '#64748B', border: '1px solid #E2E8F0' }}
          onMouseEnter={(e) => { e.currentTarget.style.background = '#FEF3C7'; e.currentTarget.style.borderColor = '#FCD34D'; e.currentTarget.style.color = '#92400E'; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = '#F1F5F9'; e.currentTarget.style.borderColor = '#E2E8F0'; e.currentTarget.style.color = '#64748B'; }}
          title="点击重新显示顶部模式提示"
        >
          <span className="w-1.5 h-1.5 rounded-full" style={{ background: backendMode ? '#10B981' : '#F59E0B' }} />
          {backendMode ? backendActive ? '任务已连接' : '等待导入文件' : '服务连接异常，请重试'}
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
          canImportDemo={canImportDemo}
        />
      </div>

      {/* Step 2 — 仅在三张表全部就绪 (uploaded/warning) 后出现, 错误态不进入 */}
      {validating && <div role="status" className="rounded-xl p-4" style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)' }}>正在校验实际导入数据，请稍候…</div>}
      {validationError && <div role="alert" className="rounded-xl p-4 space-y-3" style={{ background: 'var(--surface-muted)', color: 'var(--text-strong)', border: '1px solid var(--border-strong)' }}>
        <p>{validationError}</p>
        {allReady ? <Button onClick={revalidate} disabled={validating}>重试校验</Button> : <p>请检查文件错误提示后重新上传。</p>}
      </div>}
      {allReady && validationData && (
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
            onScrollToResult={() => scrollTo('step4')}
            backendActive={true}
            backendJob={backendResult}
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
