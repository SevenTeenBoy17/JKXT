// Recent Tasks list — right column (1/3), real /exam-tasks rows with status pill
//
// P18 阶段B实测整改：此前这里是 5 条写死任务（「高三 11 月 第二次月考」「初三年级月考」…）
// 和写死汇总（已完成 21 · 进行中 8 · 冲突 1）——正是审查 3.2 指控的那块（当时误查
// pages/tasks-page.jsx 判了"过期"，实测在仪表盘上现形）。现改为真实 GET /exam-tasks，
// 与任务总览页同一数据源；空库显示诚实空态。

const STATUS = {
  done:     { label: '已完成',   bg: '#DCFCE7', fg: '#047857', dot: '#10B981' },
  running:  { label: '进行中',   bg: '#E0F2FE', fg: '#0369A1', dot: '#0EA5E9' },
  draft:    { label: '草稿',     bg: '#F1F5F9', fg: '#475569', dot: '#94A3B8' },
  conflict: { label: '有冲突',   bg: '#FEE2E2', fg: '#B91C1C', dot: '#EF4444' },
};

function StatusPill({ status }) {
  const s = STATUS[status] || STATUS.draft;
  return (
    <span
      className="recent-task-status"
      style={{ background: s.bg, color: s.fg }}
    >
      <span
        className="recent-task-status__dot"
        aria-hidden="true"
        style={{ background: s.dot, boxShadow: status === 'running' ? `0 0 0 2px ${s.dot}33` : 'none' }}
      />
      {s.label}
    </span>
  );
}

// 与 tasks-page.jsx 的 mapBackendTaskRow 同一套状态归一（text/babel 各脚本不共享顶层作用域，
// 此处自带一份轻量映射；改动状态语义时两处需同步）。
function rtMapTask(task) {
  const job = task.latestJob || {};
  const hasNumericProgress = typeof job.progress === 'number' ||
    (typeof job.progress === 'string' && job.progress.trim() !== '');
  const status = String(job.status || task.status || 'draft');
  const uiStatus =
    ['scheduled', 'published', 'succeeded'].includes(status) ? 'done' :
    ['failed', 'infeasible'].includes(status) ? 'conflict' :
    ['scheduling', 'running', 'pending'].includes(status) ? 'running' :
    'draft';
  return {
    id: task.id,
    name: task.name || task.taskNo || '排监考任务',
    exams: job.stats?.exams ?? task.stats?.exams ?? null,
    supervisors: job.stats?.assignments ?? task.stats?.assignments ?? null,
    status: uiStatus,
    progress: hasNumericProgress && Number.isFinite(Number(job.progress))
      ? Math.max(0, Math.min(100, Number(job.progress))) : null,
    updated: task.updatedAt ? String(task.updatedAt).slice(5, 16).replace('T', ' ') : '—',
  };
}

function TaskRow({ t, index }) {
  const tones = ['#EEF2FF', '#ECFDF5', '#FFF7ED', '#FAF5FF', '#ECFEFF'];
  const iconColors = ['#4F46E5', '#059669', '#D97706', '#9333EA', '#0891B2'];
  const tone = tones[index % tones.length];
  const ic = iconColors[index % iconColors.length];

  const openDetail = () => {
    try { localStorage.setItem('jkxt:taskDetailId', t.id); } catch {}
    window.navigateTo?.('task-detail');
  };

  return (
    <button
      type="button"
      className="recent-task-row"
      aria-label={`查看任务：${t.name}`}
      onClick={openDetail}
    >
      <div className="flex items-start gap-3">
        <div
          className="shrink-0 w-9 h-9 rounded-lg flex items-center justify-center"
          style={{ background: tone, color: ic }}
        >
          <IconList size={16} strokeWidth={2} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="recent-task-heading">
            <div className="recent-task-title" title={t.name}>
              {t.name}
            </div>
            <StatusPill status={t.status} />
          </div>
          <div className="recent-task-meta">
            <span>{t.exams == null ? '场次未统计' : `${t.exams} 场`}</span>
            <span className="text-slate-300">·</span>
            <span>{t.supervisors == null ? '人次未统计' : `${t.supervisors} 人次`}</span>
          </div>

          {/* Progress */}
          {t.progress != null && <div className="recent-task-progress" aria-label={`进度 ${t.progress}%`}>
            <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--surface-sunken)' }}>
              <div
                className="h-full rounded-full transition-all"
                style={{
                  width: `${t.progress}%`,
                  background:
                    t.status === 'done' ? '#10B981' :
                    t.status === 'conflict' ? '#EF4444' :
                    'var(--brand)',
                }}
              />
            </div>
            <span className="recent-task-progress__value">
              {t.progress}%
            </span>
          </div>}

          <div className="recent-task-bottom">
            <span>
              更新 · {t.updated}
            </span>
            <div>
              <span
                className="recent-task-view"
                style={{ color: 'var(--brand)' }}
              >
                查看
              </span>
            </div>
          </div>
        </div>
      </div>
    </button>
  );
}

function RecentTasksCard() {
  const [tasks, setTasks] = React.useState(null);   // null=加载中，[]=真实为空
  const [error, setError] = React.useState(null);
  const [reloadKey, setReloadKey] = React.useState(0);
  React.useEffect(() => {
    let alive = true;
    setTasks(null);
    setError(null);
    if (typeof window.api?.get !== 'function') { setError('服务尚未连接，请重试。'); return undefined; }
    Promise.resolve().then(() => window.api.get('/exam-tasks'))
      .then((rows) => {
        if (!Array.isArray(rows)) throw new Error('任务数据格式异常');
        if (alive) setTasks(rows.map(rtMapTask));
      })
      .catch(() => { if (alive) setError('任务加载失败，请检查连接后重试。'); });
    return () => { alive = false; };
  }, [reloadKey]);

  const list = tasks || [];
  const recent = list.slice(0, 5);
  const done = list.filter((t) => t.status === 'done').length;
  const running = list.filter((t) => t.status === 'running').length;
  const conflict = list.filter((t) => t.status === 'conflict').length;

  return (
    <div className="recent-tasks-card bg-white rounded-2xl shadow-card flex flex-col" style={{ border: '1px solid var(--border-soft)' }}>
      <div className="recent-tasks-header">
        <div>
          <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            最近任务
          </h3>
          <div className="text-[12px] mt-1" style={{ color: 'var(--text-soft)' }}>
            {error ? '暂时无法读取' : tasks === null ? '加载中…' : `共 ${list.length} 项 · 显示最近 ${recent.length} 项`}
          </div>
        </div>
        <button
          type="button"
          className="recent-tasks-all inline-flex items-center gap-1 font-medium hover:underline"
          style={{ color: 'var(--brand)' }}
          onClick={() => window.navigateTo?.('tasks')}
        >
          查看全部
          <IconChevron dir="right" size={12} />
        </button>
      </div>

      <div className="flex-1 px-2 pb-2 space-y-0.5 overflow-y-auto scrollbar-thin">
        {error && <div className="recent-tasks-feedback" data-testid="recent-tasks-error" role="alert">
          <p>{error}</p>
          <button type="button" className="recent-tasks-all" onClick={() => setReloadKey(k => k + 1)}>重新加载</button>
        </div>}
        {!error && tasks === null && <div className="recent-tasks-feedback" role="status">正在读取任务…</div>}
        {tasks !== null && recent.length === 0 && (
          <div className="px-3 py-8 text-center text-[12.5px]" style={{ color: 'var(--text-soft)' }}>
            暂无排监考任务。请从「排班工作台」导入真实考试数据，创建第一个任务。
          </div>
        )}
        {recent.map((t, i) => (
          <TaskRow key={t.id || i} t={t} index={i} />
        ))}
      </div>

      {tasks !== null && !error && <div
        className="recent-tasks-summary"
      >
        <div className="flex flex-wrap items-center gap-3 text-[12px]">
          <span className="inline-flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#10B981' }} />
            <span style={{ color: 'var(--text-medium)' }}>已完成 <span className="font-mono font-semibold">{done}</span></span>
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#0EA5E9' }} />
            <span style={{ color: 'var(--text-medium)' }}>进行中 <span className="font-mono font-semibold">{running}</span></span>
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#EF4444' }} />
            <span style={{ color: 'var(--text-medium)' }}>冲突 <span className="font-mono font-semibold">{conflict}</span></span>
          </span>
        </div>
      </div>}
    </div>
  );
}

Object.assign(window, { RecentTasksCard, StatusPill });
