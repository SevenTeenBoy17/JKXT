// 任务总览 — 监考任务的列表 + 筛选 + KPI

const MOCK_TASKS = [
  { id: 'T-2025-018', name: '高三·一模联考 (市统考)', type: '模拟考', typeColor: 'pink', grade: '高三', days: 3, exams: 12, supervisors: 24, status: 'conflict', progress: 12, owner: '王明远', createdAt: '2025-11-18 09:30', updatedAt: '昨天 09:15' },
  { id: 'T-2025-017', name: '初三年级 11 月月考', type: '月考', typeColor: 'indigo', grade: '初三', days: 2, exams: 8, supervisors: 16, status: 'running', progress: 38, owner: '王明远', createdAt: '2025-11-15 14:20', updatedAt: '刚刚' },
  { id: 'T-2025-016', name: '高三 11 月 第二次月考', type: '月考', typeColor: 'indigo', grade: '高三', days: 2, exams: 10, supervisors: 20, status: 'running', progress: 72, owner: '李慧君', createdAt: '2025-11-12 10:00', updatedAt: '20 分钟前' },
  { id: 'T-2025-015', name: '七年级 语数英单元测试', type: '单元测试', typeColor: 'amber', grade: '七年级', days: 1, exams: 6, supervisors: 12, status: 'draft', progress: 0, owner: '张丽华', createdAt: '2025-11-10 16:42', updatedAt: '昨天 16:42' },
  { id: 'T-2025-014', name: '2025 秋·初二年级期中考', type: '期中考', typeColor: 'rose', grade: '初二', days: 3, exams: 8, supervisors: 16, status: 'done', progress: 100, owner: '王明远', createdAt: '2025-11-05 08:00', updatedAt: '2 小时前' },
  { id: 'T-2025-013', name: '高一·物理 单元测试', type: '单元测试', typeColor: 'amber', grade: '高一', days: 1, exams: 5, supervisors: 10, status: 'done', progress: 100, owner: '陈志远', createdAt: '2025-11-03 09:00', updatedAt: '3 天前' },
  { id: 'T-2025-012', name: '初一·英语 月考', type: '月考', typeColor: 'indigo', grade: '初一', days: 1, exams: 6, supervisors: 12, status: 'done', progress: 100, owner: '李慧君', createdAt: '2025-10-28 10:00', updatedAt: '11-04 16:30' },
  { id: 'T-2025-011', name: '全校·道德与法治 联考', type: '联考统考', typeColor: 'cyan', grade: '全校', days: 1, exams: 56, supervisors: 120, status: 'done', progress: 100, owner: '王明远', createdAt: '2025-10-25 14:00', updatedAt: '11-02 18:15' },
];

const STATUS_MAP = {
  done:     { label: '已完成', color: 'emerald', dot: '#10B981' },
  running:  { label: '进行中', color: 'sky', dot: '#0EA5E9' },
  draft:    { label: '草稿',   color: 'slate', dot: '#94A3B8' },
  conflict: { label: '有冲突', color: 'rose', dot: '#EF4444' },
};

function TaskStatusPill({ status }) {
  const s = STATUS_MAP[status];
  return <Tag color={s.color} dot>{s.label}</Tag>;
}

function ProgressBar({ value, status }) {
  const c =
    status === 'done' ? '#10B981' :
    status === 'conflict' ? '#EF4444' :
    status === 'draft' ? '#CBD5E1' :
    'var(--brand)';
  return (
    <div className="flex items-center gap-2 min-w-[120px]">
      <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: '#F1F5F9' }}>
        <div className="h-full rounded-full transition-all" style={{ width: `${value}%`, background: c }} />
      </div>
      <span className="num text-[11.5px] font-semibold w-9 text-right" style={{ color: 'var(--text-medium)' }}>
        {value}%
      </span>
    </div>
  );
}

function TasksPage() {
  const tasks = MOCK_TASKS;
  const total = tasks.length;
  const running = tasks.filter(t => t.status === 'running').length;
  const done = tasks.filter(t => t.status === 'done').length;
  const conflict = tasks.filter(t => t.status === 'conflict').length;

  const columns = [
    { key: 'id', label: '任务编号', mono: true, width: 130,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.id}</span> },
    { key: 'name', label: '任务名称', sortable: true,
      render: (r) => (
        <div>
          <div className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</div>
          <div className="mt-0.5 inline-flex items-center gap-1.5">
            <Tag color={r.typeColor}>{r.type}</Tag>
            <span className="text-[11px]" style={{ color: 'var(--text-soft)' }}>{r.grade}</span>
          </div>
        </div>
      ),
    },
    { key: 'days', label: '考期', width: 70, align: 'center', mono: true,
      render: (r) => <span>{r.days} 天</span> },
    { key: 'exams', label: '场次', width: 70, align: 'center', mono: true,
      render: (r) => <span>{r.exams}</span> },
    { key: 'supervisors', label: '监考人数', width: 90, align: 'center', mono: true,
      render: (r) => <span>{r.supervisors}</span> },
    { key: 'progress', label: '进度', width: 160,
      render: (r) => <ProgressBar value={r.progress} status={r.status} /> },
    { key: 'status', label: '状态', width: 100,
      render: (r) => <TaskStatusPill status={r.status} /> },
    { key: 'owner', label: '负责人', width: 110,
      render: (r) => (
        <span className="inline-flex items-center gap-1.5">
          <AvatarChip name={r.owner} size={22} />
          <span className="text-[12.5px]">{r.owner}</span>
        </span>
      ),
    },
    { key: 'updatedAt', label: '最近更新', width: 110, mono: true,
      render: (r) => <span className="text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.updatedAt}</span> },
    { key: 'actions', label: '操作', width: 100, align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
            onClick={() => openTaskDetail(r)}>查看</button>
          <span className="text-slate-300">·</span>
          <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
            onClick={() => window.navigateTo('edit')}>编辑</button>
          <button className="w-6 h-6 rounded inline-flex items-center justify-center hover:bg-slate-100 ml-1" style={{ color: 'var(--text-soft)' }}
            onClick={() => window.ui.toast('更多操作', { kind: 'info', desc: '复制 / 导出 / 删除 · 右键菜单 v2' })}>
            <IconMore size={14} />
          </button>
        </div>
      ),
    },
  ];

  const openTaskDetail = (t) => {
    window.navigateTo('task-detail');
  };

  return (
    <PageShell
      crumbs={['天津市实验中学', '排班作业', '任务总览']}
      title="任务总览"
      badge={`${total} 个任务`}
      subtitle="管理本学期所有监考任务,支持筛选、批量操作与导出"
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.toast('请前往「一键排监考」上传 Excel', { kind: 'info', action: { label: '去一键排监考', onClick: () => window.navigateTo('quick') } })}>导入</Button>
          <Button icon={IconChart} onClick={() => window.ui.toast('正在生成报表…', { kind: 'info', desc: '预计 5 秒后下载' })}>导出报表</Button>
          <Button variant="primary" icon={IconPlus} onClick={() => window.navigateTo('quick')}>新建任务</Button>
        </>
      }
    >
      <div className="grid grid-cols-4 gap-4">
        <MiniKPI label="任务总数" value={total} unit="个" accent="#4F46E5" icon={IconList} sub="本学期" />
        <MiniKPI label="进行中" value={running} unit="个" accent="#0EA5E9" icon={IconClock} trend="14.3" trendUp sub="较上周 +1" />
        <MiniKPI label="已完成" value={done} unit="个" accent="#10B981" icon={IconCheck} sub="累计 21" />
        <MiniKPI label="冲突待处理" value={conflict} unit="个" accent="#EF4444" icon={IconAlert} sub="需立即处理" />
      </div>

      <FilterBar
        search="搜索任务名称、编号、负责人..."
        filters={[
          { label: '状态:', value: '全部' },
          { label: '年级:', value: '全年级' },
          { label: '考试类型:', value: '全部' },
          { label: '时间:', value: '近 30 天' },
        ]}
        total={total}
        onReset={() => {}}
      />

      <DataTable columns={columns} rows={tasks} selectable onRowClick={openTaskDetail} />
    </PageShell>
  );
}

window.TasksPage = TasksPage;
