// Recent Tasks list — right column (1/3), 5 rows with status pill + actions

const TASKS = [
  {
    name: '2025 秋·初二年级期中考',
    grade: '初二',
    progress: 100,
    exams: 8, supervisors: 16,
    status: 'done',
    updated: '2 小时前',
  },
  {
    name: '高三 11 月 第二次月考',
    grade: '高三',
    progress: 72,
    exams: 10, supervisors: 20,
    status: 'running',
    updated: '20 分钟前',
  },
  {
    name: '初三年级月考',
    grade: '初三',
    progress: 38,
    exams: 8, supervisors: 16,
    status: 'running',
    updated: '刚刚',
  },
  {
    name: '七年级 语数英单元测试',
    grade: '七年级',
    progress: 0,
    exams: 6, supervisors: 12,
    status: 'draft',
    updated: '昨天 16:42',
  },
  {
    name: '高三·一模联考 (市统考)',
    grade: '高三',
    progress: 12,
    exams: 12, supervisors: 24,
    status: 'conflict',
    updated: '昨天 09:15',
  },
];

const STATUS = {
  done:     { label: '已完成',   bg: '#DCFCE7', fg: '#059669', dot: '#10B981' },
  running:  { label: '进行中',   bg: '#E0F2FE', fg: '#0369A1', dot: '#0EA5E9' },
  draft:    { label: '草稿',     bg: '#F1F5F9', fg: '#64748B', dot: '#94A3B8' },
  conflict: { label: '有冲突',   bg: '#FEE2E2', fg: '#DC2626', dot: '#EF4444' },
};

function StatusPill({ status }) {
  const s = STATUS[status];
  return (
    <span
      className="inline-flex items-center gap-1 pl-1.5 pr-2 py-0.5 rounded-full text-[11px] font-medium"
      style={{ background: s.bg, color: s.fg }}
    >
      <span
        className="w-1.5 h-1.5 rounded-full"
        style={{ background: s.dot, boxShadow: status === 'running' ? `0 0 0 2px ${s.dot}33` : 'none' }}
      />
      {s.label}
    </span>
  );
}

function TaskRow({ t, index }) {
  const tones = ['#EEF2FF', '#ECFDF5', '#FFF7ED', '#FAF5FF', '#ECFEFF'];
  const iconColors = ['#4F46E5', '#059669', '#D97706', '#9333EA', '#0891B2'];
  const tone = tones[index % tones.length];
  const ic = iconColors[index % iconColors.length];

  return (
    <div
      className="group rounded-xl p-3 transition-colors cursor-pointer"
      onClick={() => window.navigateTo?.('tasks')}
      onMouseEnter={(e) => (e.currentTarget.style.background = '#F8FAFC')}
      onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
    >
      <div className="flex items-start gap-3">
        <div
          className="shrink-0 w-9 h-9 rounded-lg flex items-center justify-center"
          style={{ background: tone, color: ic }}
        >
          <IconList size={16} strokeWidth={2} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="text-[13px] font-semibold truncate" style={{ color: 'var(--text-strong)' }}>
              {t.name}
            </div>
            <StatusPill status={t.status} />
          </div>
          <div className="mt-0.5 text-[11.5px] inline-flex items-center gap-1.5" style={{ color: 'var(--text-soft)' }}>
            <span>{t.grade}</span>
            <span className="text-slate-300">·</span>
            <span className="font-mono">{t.exams} 场</span>
            <span className="text-slate-300">·</span>
            <span className="font-mono">{t.supervisors} 人</span>
          </div>

          {/* Progress */}
          <div className="mt-2 flex items-center gap-2">
            <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: '#F1F5F9' }}>
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
            <span className="font-mono text-[11px] font-semibold w-9 text-right" style={{ color: 'var(--text-medium)' }}>
              {t.progress}%
            </span>
          </div>

          <div className="mt-1.5 flex items-center justify-between">
            <span className="text-[10.5px] font-mono whitespace-nowrap" style={{ color: 'var(--text-soft)' }}>
              更新 · {t.updated}
            </span>
            <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
              <button
                className="h-6 px-2 rounded text-[11px] font-medium hover:bg-white transition-colors"
                style={{ color: 'var(--brand)' }}
              >
                查看
              </button>
              <span className="w-px h-3" style={{ background: 'var(--border-soft)' }} />
              <button
                className="h-6 w-6 rounded inline-flex items-center justify-center hover:bg-white transition-colors"
                style={{ color: 'var(--text-soft)' }}
              >
                <IconMore size={14} />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function RecentTasksCard() {
  return (
    <div className="bg-white rounded-2xl shadow-card flex flex-col" style={{ border: '1px solid var(--border-soft)' }}>
      <div className="px-5 pt-5 pb-3 flex items-center justify-between">
        <div>
          <h3 className="text-[16px] font-semibold" style={{ color: 'var(--text-strong)' }}>
            最近任务
          </h3>
          <div className="text-[12px] mt-1" style={{ color: 'var(--text-soft)' }}>
            按最近更新排序
          </div>
        </div>
        <button
          className="inline-flex items-center gap-1 text-[12px] font-medium hover:underline"
          style={{ color: 'var(--brand)' }}
          onClick={() => window.navigateTo?.('tasks')}
        >
          查看全部
          <IconChevron dir="right" size={12} />
        </button>
      </div>

      <div className="flex-1 px-2 pb-2 space-y-0.5 overflow-y-auto scrollbar-thin">
        {TASKS.map((t, i) => (
          <TaskRow key={i} t={t} index={i} />
        ))}
      </div>

      <div
        className="px-5 py-3 flex items-center justify-between"
        style={{ borderTop: '1px solid var(--border-soft)', background: '#FAFBFC', borderBottomLeftRadius: 16, borderBottomRightRadius: 16 }}
      >
        <div className="inline-flex items-center gap-3 text-[11.5px]">
          <span className="inline-flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#10B981' }} />
            <span style={{ color: 'var(--text-medium)' }}>已完成 <span className="font-mono font-semibold">21</span></span>
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#0EA5E9' }} />
            <span style={{ color: 'var(--text-medium)' }}>进行中 <span className="font-mono font-semibold">8</span></span>
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#EF4444' }} />
            <span style={{ color: 'var(--text-medium)' }}>冲突 <span className="font-mono font-semibold">1</span></span>
          </span>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { RecentTasksCard, StatusPill });
