// Step 3 · 智能排班（服务端事实 / 明确标注的本地预览）
//   - 顶部标题 + 服务端进度（缺失值不估算）
//   - 6 阶段焦点膨胀时间线 (waiting 32 / running 96 / done 48)
//   - 折叠式引擎日志 (默认收起)
//   - 本次服务端策略快照 chips

// ──────────────────────────────────────────────────────────────
// 阶段元数据 — 6 阶段
// ──────────────────────────────────────────────────────────────
const QK_STAGES = [
  {
    id: 0,
    title: '读取三张表',
    sub:   '解析教师 / 考场 / 考试安排数据',
    stat:  '教师 142 行 · 考场 24 间 · 考试 8 场',
  },
  {
    id: 1,
    title: '应用默认排监考规则',
    sub:   '加载本校预置规则集',
    stat:  '5 条策略已就绪',
  },
  {
    id: 2,
    title: '平衡教师监考次数',
    sub:   '工作量均衡优化 · 模拟',
    stat:  '标准差由 1.2 降至 0.7',
  },
  {
    id: 3,
    title: '检查班主任 / 科目 / 年级规则',
    sub:   '硬约束验证',
    stat:  '未检出违规项',
  },
  {
    id: 4,
    title: '生成排班结果预览',
    sub:   '合并方案 + 渲染表格预览',
    stat:  '48 人次已分配',
  },
  {
    id: 5,
    title: '准备下载文件占位',
    sub:   '模拟生成 4 个 xlsx 占位 · 待服务端接入',
    stat:  '已生成 4 个占位文件 · 待服务端接入导出',
  },
];

// 对应 ScheduleJobService 发布的阶段；不从百分比推断已完成的步骤。
const QK_BACKEND_STAGES = [
  { id: 0, key: 'queued', title: '等待求解器', sub: '服务端排队中' },
  { id: 1, key: 'CP-SAT 初始化', title: '初始化求解器', sub: '启动本次排班任务' },
  { id: 2, key: '读取排班数据', title: '读取排班数据', sub: '读取本次求解使用的教师、考场与考试场次' },
  { id: 3, key: '构建约束模型', title: '构建约束模型', sub: '构建 CP-SAT 决策变量与约束并求解' },
  { id: 4, key: '求解完成，写入结果', title: '写入求解结果', sub: '保存求解器返回的结果，等待终态确认' },
  { id: 5, key: '生成结果预览', title: '核对排班结果', sub: '读取完整任务快照，确认本次结果' },
];

function qkBackendStrategies(job) {
  const policy = job?.stats?.policySnapshot;
  if (!policy || typeof policy !== 'object') return [];
  const strategies = [];
  if (typeof policy.avoidHomeroomClass === 'boolean') strategies.push({
    id: 'homeroom', label: '班主任避监本班', desc: policy.avoidHomeroomClass ? '严格回避' : '未启用',
  });
  const sameGrade = policy.sameGrade === 'HARD' ? '严格回避'
    : policy.sameGrade === 'SOFT' ? '尽量满足' : policy.sameGrade === 'NONE' ? '未启用' : null;
  if (sameGrade) strategies.push({ id: 'grade', label: '同年级回避', desc: sameGrade });
  if (typeof policy.avoidSubjectClass === 'boolean') strategies.push({
    id: 'subject', label: '任课教师避监本班', desc: policy.avoidSubjectClass ? '严格回避' : '未启用',
  });
  return strategies;
}

// ──────────────────────────────────────────────────────────────
// 实时秒表 (仅 active stage 用)
// ──────────────────────────────────────────────────────────────
function useQkActiveTimer(active) {
  const [elapsed, setElapsed] = React.useState(0);
  const startRef = React.useRef(null);
  React.useEffect(() => {
    if (!active) {
      startRef.current = null;
      setElapsed(0);
      return;
    }
    startRef.current = Date.now();
    setElapsed(0);
    const id = setInterval(() => {
      setElapsed((Date.now() - startRef.current) / 1000);
    }, 100);
    return () => clearInterval(id);
  }, [active]);
  return elapsed;
}

// ──────────────────────────────────────────────────────────────
// 阶段行 (3 态高度差异 = 焦点膨胀)
// ──────────────────────────────────────────────────────────────
function QkStageRow({ stage, idx, currentStage, complete, last, backendActive = false, running = true }) {
  const isDone    = complete || idx < currentStage;
  const isActive  = !complete && running && idx === currentStage;
  const isPending = !isDone && !isActive;

  const elapsed = useQkActiveTimer(isActive);

  // 高度
  const minH = isActive ? 96 : isDone ? 56 : 36;

  return (
    <div
      className="relative flex gap-4 transition-all duration-300"
      style={{ minHeight: minH, paddingBottom: last ? 0 : 8 }}
    >
      {/* 垂直连接线 (穿过 dot 中心, 已完成段绿色) */}
      {!last && (
        <span
          className="absolute w-0.5 transition-colors duration-500"
          style={{
            left: 15,
            top: 32,
            bottom: -24,
            background: isDone ? 'linear-gradient(180deg, #10B981, #34D399)' : '#E2E8F0',
          }}
        />
      )}

      {/* Dot */}
      <div className="relative shrink-0" style={{ width: 32, height: 32 }}>
        <span
          className="absolute inset-0 rounded-full flex items-center justify-center font-mono text-[11.5px] font-bold transition-all duration-300"
          style={{
            background: isDone
              ? 'linear-gradient(180deg, #10B981, #059669)'
              : isActive
                ? 'linear-gradient(180deg, var(--brand), var(--brand-hover))'
                : '#fff',
            color: (isDone || isActive) ? '#fff' : 'var(--text-soft)',
            border: isPending ? '1.5px solid #CBD5E1' : 'none',
            boxShadow: isActive
              ? '0 0 0 4px rgba(79,70,229,.14), 0 2px 4px rgba(79,70,229,.20)'
              : isDone
                ? '0 1px 0 rgba(255,255,255,.3) inset, 0 2px 4px -1px rgba(16,185,129,.30)'
                : 'inset 0 -1px 0 #F1F5F9',
            animation: isActive ? 'qkPulseLarge 1.4s ease-in-out infinite' : 'none',
          }}
        >
          {isDone ? <IconCheck size={14} strokeWidth={3} /> : idx + 1}
        </span>
      </div>

      {/* 内容 */}
      <div className="flex-1 min-w-0">
        {/* 头行: STAGE NN · 标题 · 右侧状态 Tag */}
        <div className="flex items-baseline gap-2 flex-wrap">
          <span
            className="text-[12px] font-mono uppercase tracking-[0.14em] shrink-0"
            style={{ color: 'var(--text-soft)' }}
          >
            阶段 {String(idx + 1).padStart(2, '0')}
          </span>
          <span
            className="text-[13.5px] font-semibold whitespace-nowrap transition-colors duration-300"
            style={{ color: (isDone || isActive) ? 'var(--text-strong)' : 'var(--text-soft)' }}
          >
            {stage.title}
          </span>
          <div className="flex-1" />
          {isActive && (
            <span
              className="font-mono text-[12px] px-1.5 py-0.5 rounded whitespace-nowrap inline-flex items-center gap-1"
              style={{ background: 'var(--brand-soft)', color: 'var(--brand)', fontWeight: 700 }}
            >
              <span className="uppercase tracking-wider">运行中</span>
              <span style={{ opacity: 0.6 }}>·</span>
              <span>{elapsed.toFixed(1)}s</span>
            </span>
          )}
          {isDone && (
            <span
              className="font-mono text-[12px] px-1.5 py-0.5 rounded uppercase tracking-wider whitespace-nowrap"
              style={{ background: '#DCFCE7', color: '#047857', fontWeight: 700 }}
            >
              {backendActive ? '完成' : '模拟完成'}
            </span>
          )}
          {isPending && (
            <span
              className="font-mono text-[12px] px-1.5 py-0.5 rounded uppercase tracking-wider whitespace-nowrap"
              style={{ background: '#F1F5F9', color: 'var(--text-soft)', fontWeight: 600 }}
            >
              等待中
            </span>
          )}
        </div>

        {/* Active: 副标题 + mini 进度条 + 计时 */}
        {isActive && (
          <div className="mt-1.5 space-y-2">
            <div className="text-[12px] leading-snug" style={{ color: 'var(--text-medium)' }}>
              {stage.sub}
            </div>
            <div
              className="h-1.5 rounded-full overflow-hidden relative"
              style={{ background: '#F1F5F9' }}
            >
              <div
                className="h-full rounded-full"
                style={{
                  width: '60%',
                  background: 'linear-gradient(90deg, var(--brand), #818CF8, var(--brand))',
                  backgroundSize: '200% 100%',
                  animation: 'qkProgress 1.4s ease-in-out infinite, qkSheen 2s linear infinite',
                }}
              />
            </div>
            <div className="text-[12px] font-mono flex items-center gap-1.5" style={{ color: 'var(--text-soft)' }}>
              <span
                className="w-1.5 h-1.5 rounded-full"
                style={{ background: 'var(--brand)', animation: 'qkBlink 1s ease-in-out infinite' }}
              />
              {backendActive ? (stage.stat || '等待服务端返回本阶段详情…') : stage.stat ? `本步骤将输出: ${stage.stat}` : '模拟运行中…'}
            </div>
          </div>
        )}

        {/* Done: 完成状态 + mini-stat */}
        {isDone && (
          <div className="mt-1 flex items-center gap-1.5 text-[12px] font-mono" style={{ color: 'var(--text-soft)' }}>
            <IconCheck size={10} strokeWidth={2.5} style={{ color: '#10B981' }} />
            <span>{stage.stat || (backendActive ? '服务端已进入后续阶段' : '')}</span>
          </div>
        )}

        {/* Pending: 副标题灰色 (单行) */}
        {isPending && (
          <div className="mt-0.5 text-[12px] leading-snug" style={{ color: '#CBD5E1' }}>
            {stage.sub}
          </div>
        )}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 折叠式引擎日志 (默认收起)
// ──────────────────────────────────────────────────────────────
function QkCollapsibleLog({ logs, running, backendActive = false }) {
  const [open, setOpen] = React.useState(false);
  const scrollerRef = React.useRef(null);

  React.useEffect(() => {
    if (!open) return;
    const el = scrollerRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [logs, open]);

  const colorFor = (line) => {
    if (line.startsWith('✓')) return '#34D399';
    if (line.startsWith('✕')) return '#F87171';
    if (line.startsWith('▶') || line.startsWith('▼')) return '#A5B4FC';
    if (line.startsWith('◆')) return '#FCD34D';
    return '#CBD5E1';
  };

  return (
    <div
      className="rounded-2xl overflow-hidden transition-all"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
      }}
    >
      <button
        onClick={() => setOpen((o) => !o)}
        className="w-full flex items-center gap-3 px-5 py-3 transition-colors"
        onMouseEnter={(e) => (e.currentTarget.style.background = '#F8FAFC')}
        onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
      >
        <span
          className="w-7 h-7 rounded-lg flex items-center justify-center shrink-0"
          style={{
            background: '#0F172A',
            color: '#34D399',
            boxShadow: '0 1px 0 rgba(255,255,255,.05) inset, 0 1px 2px rgba(15,23,42,.2)',
          }}
        >
          <span className="font-mono text-[10px] font-bold">{'>_'}</span>
        </span>
        <div className="text-left min-w-0 flex-1">
          <div className="text-[13px] font-semibold flex items-center gap-2 flex-wrap" style={{ color: 'var(--text-strong)' }}>
            引擎执行日志
            <span
              className="font-mono text-[12px] uppercase tracking-wider px-1 rounded whitespace-nowrap"
              style={{ background: '#0F172A', color: '#34D399', fontWeight: 700 }}
            >
              {backendActive ? '后端输出' : '模拟输出'}
            </span>
            {running && (
              <span className="font-mono text-[12px] inline-flex items-center gap-1 px-1.5 py-0.5 rounded whitespace-nowrap"
                style={{ background: '#DCFCE7', color: '#047857', fontWeight: 600 }}>
                <span className="w-1 h-1 rounded-full" style={{ background: '#10B981', animation: 'qkBlink 1s ease-in-out infinite' }} />
                实时 · {logs.length} 行
              </span>
            )}
            {!running && logs.length > 0 && (
              <span className="font-mono text-[12px] whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
                共 {logs.length} 行 · 已停止
              </span>
            )}
          </div>
          <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            {open
              ? '点击折叠 · 时间线已含主要进度,日志仅供细节查阅'
              : backendActive ? '展开查看当前页面收到的服务端进度日志' : '展开查看模拟引擎的逐行输出 · 仅本地模拟,不连服务端'}
          </div>
        </div>
        <IconChevron dir={open ? 'up' : 'down'} size={16} className="text-slate-400" />
      </button>

      <div
        style={{
          maxHeight: open ? 360 : 0,
          overflow: 'hidden',
          transition: 'max-height 400ms cubic-bezier(.22,.61,.36,1)',
        }}
      >
        <div
          className="mx-4 mb-4 rounded-xl overflow-hidden flex flex-col"
          style={{
            background: 'linear-gradient(180deg, #0F172A 0%, #1E293B 100%)',
            border: '1px solid #1E293B',
          }}
        >
          {/* 终端标题栏 */}
          <div
            className="flex items-center gap-2 px-3 py-2"
            style={{ background: '#1E293B', borderBottom: '1px solid #334155' }}
          >
            <div className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full" style={{ background: '#EF4444' }} />
              <span className="w-2 h-2 rounded-full" style={{ background: '#F59E0B' }} />
              <span className="w-2 h-2 rounded-full" style={{ background: '#10B981' }} />
            </div>
            <span className="font-mono text-[12px] tracking-wider ml-2" style={{ color: '#94A3B8' }}>
              {backendActive ? '后端 Job · 日志输出' : '模拟引擎 · 日志输出'}
            </span>
            <div className="flex-1" />
            <span className="font-mono text-[12px] tracking-wider" style={{ color: '#94A3B8' }}>
              {backendActive ? '当前页面日志快照' : '本地前端模拟 · 不调用服务端'}
            </span>
          </div>

          {/* 日志主体 */}
          <div
            ref={scrollerRef}
            className="px-3.5 py-2.5 font-mono text-[12px] leading-[1.65] overflow-y-auto scrollbar-thin"
            style={{ color: '#CBD5E1', maxHeight: 240 }}
          >
            {logs.length === 0 && (
              <div style={{ color: '#94A3B8' }}>等待引擎启动…</div>
            )}
            {logs.map((l, i) => (
              <div key={i} className="flex gap-3">
                <span style={{ color: '#94A3B8', minWidth: 76 }}>{l.t}</span>
                <span style={{ color: colorFor(l.text) }}>{l.text}</span>
              </div>
            ))}
            {running && (
              <div className="inline-flex items-center gap-1" style={{ color: '#34D399' }}>
                <span className="font-mono">{'>'}</span>
                <span
                  className="inline-block w-1.5 h-3 ml-1"
                  style={{ background: '#34D399', animation: 'qkCaret 1s steps(2) infinite' }}
                />
              </div>
            )}
          </div>

          {/* 底部操作 */}
          <div
            className="px-3.5 py-1.5 flex items-center gap-3 text-[12px] font-mono"
            style={{ background: '#0B1220', borderTop: '1px solid #1E293B', color: '#94A3B8' }}
          >
            <span>{backendActive ? '● 服务端进度 · 以任务快照为准' : '● 仅前端模拟 · 不入库'}</span>
            <div className="flex-1" />
            <button
              disabled={logs.length === 0}
              onClick={async () => {
                if (!logs.length) return;
                if (!navigator.clipboard?.writeText) {
                  window.ui?.toast?.('复制失败：当前浏览器无法访问剪贴板', { kind: 'error', desc: '可以下载当前日志后查看。' });
                  return;
                }
                try {
                  const text = logs.map((l) => `[${l.t}] ${l.text}`).join('\n');
                  await navigator.clipboard.writeText(text);
                  window.ui?.toast?.('已复制当前日志', { kind: 'success', desc: backendActive ? '仅复制当前页面显示的后端进度日志。' : '仅复制当前页面显示的模拟日志。' });
                } catch {
                  window.ui?.toast?.('复制失败：未能写入剪贴板', { kind: 'error', desc: '请检查浏览器权限，或下载当前日志。' });
                }
              }}
              className="hover:text-slate-300 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              style={{ color: '#94A3B8' }}
            >
              复制
            </button>
            <span>·</span>
            <button
              disabled={logs.length === 0}
              onClick={() => {
                if (!logs.length) return;
                let url;
                let link;
                try {
                  const text = [
                    'Quick 排监考 · 当前页面日志快照',
                    `来源：${backendActive ? '后端进度日志' : '本地模拟日志'}`,
                    '范围：仅当前页面已显示的日志，非后端完整日志。',
                    '',
                    ...logs.map((l) => `[${l.t}] ${l.text}`),
                  ].join('\r\n');
                  const blob = new Blob(['\uFEFF', text], { type: 'text/plain;charset=utf-8' });
                  url = URL.createObjectURL(blob);
                  link = document.createElement('a');
                  link.href = url;
                  link.download = `quick-${backendActive ? 'backend' : 'demo'}-current-logs-${new Date().toISOString().replace(/[:.]/g, '-')}.log`;
                  link.style.display = 'none';
                  document.body.appendChild(link);
                  link.click();
                  window.ui?.toast?.('已发起当前日志下载', { kind: 'info', desc: '仅包含当前页面日志快照，请查看浏览器下载记录。' });
                } catch {
                  window.ui?.toast?.('下载失败：未能生成当前日志文件', { kind: 'error', desc: '请重试，或使用复制按钮。' });
                } finally {
                  link?.remove();
                  if (url) setTimeout(() => URL.revokeObjectURL(url), 1000);
                }
              }}
              className="hover:text-slate-300 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              style={{ color: '#94A3B8' }}
            >
              下载当前日志
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// 当前生效策略 (chips)
// ──────────────────────────────────────────────────────────────
function QkStrategyChips({ strategies, backendActive = false }) {
  const severityColor = {
    '严格回避': { bg: '#FEF2F2', fg: '#B91C1C', dot: '#EF4444' },
    '尽量满足': { bg: '#FFFBEB', fg: '#B45309', dot: '#F59E0B' },
    '加权优化': { bg: '#EEF2FF', fg: '#4F46E5', dot: '#6366F1' },
    '未启用': { bg: '#F1F5F9', fg: '#64748B', dot: '#94A3B8' },
  };
  return (
    <div
      className="rounded-2xl p-4"
      style={{
        background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
        border: '1px solid var(--border-soft)',
        boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
      }}
    >
      <div className="flex items-center gap-3 mb-3">
        <span
          className="w-7 h-7 rounded-lg flex items-center justify-center shrink-0"
          style={{
            background: 'linear-gradient(180deg, #EEF2FF, #E0E7FF)',
            color: '#4F46E5',
            border: '1px solid rgba(79,70,229,.18)',
          }}
        >
          <IconSparkle size={14} strokeWidth={2} />
        </span>
        <div className="flex-1 min-w-0">
          <div className="text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            {backendActive ? '本次策略快照' : '预览示例策略'}{strategies.length > 0 ? ` · ${strategies.length} 项` : ''}
          </div>
          <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-soft)' }}>
            {backendActive ? '仅展示本次服务返回的规则开关；未返回的规则不作生效声明' : '本地预览示例 · 不代表本次真实任务的生效策略'}
          </div>
        </div>
        <button
          onClick={() => window.navigateTo?.('ai')}
          className="text-[12px] font-medium inline-flex items-center gap-1 px-2.5 py-1.5 rounded-md transition-colors whitespace-nowrap"
          style={{ color: 'var(--brand)' }}
          onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--brand-soft)')}
          onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
        >
          调整策略
          <IconChevron dir="right" size={11} />
        </button>
      </div>
      <div className="flex flex-wrap gap-2">
        {backendActive && strategies.length === 0 && <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>尚未收到本次策略快照</span>}
        {strategies.map((s) => {
          const c = severityColor[s.desc] || severityColor['加权优化'];
          return (
            <span
              key={s.id}
              className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[12px] whitespace-nowrap"
              style={{
                background: '#fff',
                border: `1px solid ${c.fg}22`,
                boxShadow: '0 1px 0 rgba(255,255,255,.9) inset, 0 1px 2px rgba(15,23,42,.04)',
              }}
            >
              <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: c.dot }} />
              <span className="font-medium" style={{ color: 'var(--text-strong)' }}>
                {s.label}
              </span>
              <span
                className="font-mono text-[12px] uppercase tracking-wider px-1 rounded shrink-0"
                style={{ background: c.bg, color: c.fg, fontWeight: 700 }}
              >
                {s.desc}
              </span>
            </span>
          );
        })}
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// Step 3 主体
// ──────────────────────────────────────────────────────────────
function QkStep3({ running, stage, logs, strategies = [], onScrollToResult, backendActive = false, backendJob = null }) {
  const complete = stage >= QK_STAGES.length && (!backendActive || (!running && backendJob?.status === 'succeeded'));
  const backendStage = ['pending', 'running', 'succeeded'].includes(backendJob?.status)
    ? QK_BACKEND_STAGES.findIndex(item => item.key === backendJob?.stage) : -1;
  const activeStage = backendActive ? (complete ? QK_BACKEND_STAGES.length : backendStage) : stage;
  const backendRunning = running && ['pending', 'running', 'succeeded'].includes(backendJob?.status);
  const counts = backendJob?.stats;
  const hasCounts = ['teachers', 'rooms', 'exams'].every(key => Number.isInteger(counts?.[key]) && counts[key] >= 0);
  const stages = backendActive ? QK_BACKEND_STAGES.map(item => ({ ...item,
    stat: item.id === 2 && hasCounts
      ? `教师 ${counts.teachers} 人 · 考场 ${counts.rooms} 间 · 考试场次 ${counts.exams} 场`
      : item.id === 5 && complete && Array.isArray(backendJob?.fairness)
        ? `${backendJob.fairness.length} 项公平性指标已返回`
        : '',
  })) : QK_STAGES;
  const ModeChip = backendActive ? window.QkBackendChip : QkMockChip;
  // 真实任务仅显示服务返回的比例；阶段动画不产生求解进度。
  const progress = backendActive
    ? (Number.isFinite(backendJob?.progress) && backendJob.progress >= 0 && backendJob.progress <= 100 ? backendJob.progress : null)
    : complete ? 100 : Math.min(100, Math.max(0, ((stage + (running ? 0.5 : 0)) / stages.length) * 100));
  const backendStatus = !backendJob ? '正在提交，等待服务端响应…'
    : ({ pending: '等待求解器', running: '后端运行中', succeeded: '等待完整结果确认', failed: '求解失败', infeasible: '无可行方案', cancelled: '任务已取消' }[backendJob.status]
      || '任务状态尚未确认');

  return (
    <section className="space-y-5">
      {/* 标题 + 总进度 */}
      <div
        className="rounded-2xl p-5 flex items-end justify-between flex-wrap gap-4"
        style={{
          background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
          border: '1px solid var(--border-soft)',
          boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
        }}
      >
        <div>
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="text-[16px] font-semibold whitespace-nowrap" style={{ color: 'var(--text-strong)' }}>
              智能排监考引擎
            </h3>
            <span
              className="font-mono text-[12px] uppercase tracking-[0.14em] px-1.5 py-0.5 rounded whitespace-nowrap"
              style={{ background: '#F1F5F9', color: 'var(--text-medium)', fontWeight: 600 }}
            >
              {backendActive ? '后端 Job v1' : '前端预览版 v0.1'}
            </span>
            {ModeChip && <ModeChip />}
          </div>
          <div className="text-[12px] mt-1.5 flex items-center gap-2 flex-wrap sm:whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
            {complete
              ? <>
                  <span style={{ color: '#047857' }}>✓ {backendActive ? '后端方案已生成' : '模拟方案已生成'} · 请查看下一步</span>
                  {onScrollToResult && (
                    <button
                      onClick={onScrollToResult}
                      className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-[12px] font-semibold transition-all whitespace-nowrap"
                      style={{
                        background: 'linear-gradient(180deg, var(--brand), var(--brand-hover))',
                        color: '#fff',
                        boxShadow: '0 1px 0 rgba(255,255,255,.20) inset, 0 1px 2px rgba(79,70,229,.25), 0 4px 10px -2px rgba(79,70,229,.30)',
                        marginLeft: 2,
                      }}
                      onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-1px)'; }}
                      onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; }}
                    >
                      查看{backendActive ? '后端' : '模拟'}结果 <span style={{ opacity: 0.85 }}>↓</span>
                    </button>
                  )}
                </>
              : backendActive
                ? <span>{backendStatus}{backendJob?.stage ? ` · ${backendJob.stage}` : ''}</span>
              : running
                ? <span>模拟运行中 · 第 <span className="font-mono font-semibold" style={{ color: 'var(--brand)' }}>{stage + 1} / {stages.length}</span> 步：<span style={{ color: 'var(--text-medium)' }}>{stages[stage]?.title}</span></span>
                : <span>准备启动 …</span>}
          </div>
        </div>
        <div className="w-full min-w-0 flex-1 max-w-md sm:min-w-[320px]">
          <div className="flex items-center justify-between text-[12px] mb-1.5">
            <span className="font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>
              {complete ? (backendActive ? '后端完成' : '模拟完成') : backendActive ? '服务端上报进度' : '阶段进度 · 模拟'}
            </span>
            <span className="font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>
              {progress == null ? '待服务端返回进度' : `${backendActive ? progress : progress.toFixed(0)}%`}
            </span>
          </div>
          <div className="h-2 rounded-full overflow-hidden" style={{ background: '#E2E8F0' }}>
            <div
              className="h-full rounded-full transition-all duration-500"
              style={{
                width: `${progress ?? 0}%`,
                background: complete
                  ? 'linear-gradient(90deg, #10B981, #34D399)'
                  : 'linear-gradient(90deg, var(--brand), #818CF8)',
                boxShadow: complete ? '0 0 8px rgba(16,185,129,.4)' : '0 0 8px rgba(79,70,229,.3)',
              }}
            />
          </div>
        </div>
      </div>

      {/* 6 阶段时间线 (单列, 焦点膨胀) */}
      <div
        className="rounded-2xl p-5"
        style={{
          background: 'linear-gradient(180deg, #FFFFFF 0%, #FCFCFD 100%)',
          border: '1px solid var(--border-soft)',
          boxShadow: '0 1px 0 rgba(255,255,255,.95) inset, 0 1px 3px rgba(15,23,42,.04)',
        }}
      >
        <div className="text-[12px] font-mono uppercase tracking-[0.14em] mb-4" style={{ color: 'var(--text-soft)' }}>
          执行阶段 · 共 {stages.length} 步
        </div>
        <div>
          {stages.map((s, i) => (
            <QkStageRow
              key={s.id}
              stage={s}
              idx={i}
              currentStage={activeStage}
              complete={complete}
              last={i === stages.length - 1}
              backendActive={backendActive}
              running={backendActive ? backendRunning : running}
            />
          ))}
        </div>
      </div>

      {/* 折叠式日志 */}
      <QkCollapsibleLog logs={logs} running={running} backendActive={backendActive} />

      {/* 策略 chips */}
      <QkStrategyChips strategies={backendActive ? qkBackendStrategies(backendJob) : strategies} backendActive={backendActive} />

      <style>{`
        @keyframes qkPulseLarge {
          0%, 100% { box-shadow: 0 0 0 4px rgba(79,70,229,.14), 0 2px 4px rgba(79,70,229,.20); }
          50%      { box-shadow: 0 0 0 9px rgba(79,70,229,.05), 0 2px 4px rgba(79,70,229,.20); }
        }
        @keyframes qkCaret { 0%, 50% { opacity: 1; } 50.01%, 100% { opacity: 0; } }
      `}</style>
    </section>
  );
}

window.QkStep3 = QkStep3;
window.QK_STAGES_V2 = QK_STAGES;
