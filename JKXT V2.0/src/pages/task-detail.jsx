// 任务详情子页 — 4 tab: 概览 / 监考人员 / 排班表 / 操作记录

const __TD_MOCK = {
  id: 'T-2025-016',
  name: '高三 11 月 第二次月考',
  type: '月考', typeColor: 'indigo', grade: '高三',
  days: 2, exams: 10, supervisors: 20,
  status: 'running', progress: 72,
  owner: '李慧君', createdAt: '2025-11-12 10:00', updatedAt: '20 分钟前',
  startDate: '2025-11-22', endDate: '2025-11-23',
  classrooms: 10, students: 480,
  allowance: 4800,
};

const __TD_SUPERVISORS = [
  { id: 'T240101', name: '王伟明', grade: '高三', subject: '数学', sessions: 4, status: 'confirmed' },
  { id: 'T240102', name: '李慧君', grade: '高三', subject: '语文', sessions: 4, status: 'confirmed' },
  { id: 'T240201', name: '张丽华', grade: '高二', subject: '英语', sessions: 3, status: 'confirmed' },
  { id: 'T240202', name: '陈志远', grade: '高二', subject: '物理', sessions: 3, status: 'pending' },
  { id: 'T240301', name: '刘文涛', grade: '高一', subject: '化学', sessions: 2, status: 'pending' },
  { id: 'J240901', name: '赵思琪', grade: '初三', subject: '生物', sessions: 2, status: 'confirmed' },
  { id: 'J240801', name: '周晓梅', grade: '初二', subject: '地理', sessions: 2, status: 'declined' },
];

const __TD_SCHEDULE = [
  { date: '11-22 周六', period: '08:00-09:30', subject: '语文', rooms: 'A-301~310', supervisors: '王伟明 + 李慧君 等 10 人', students: 240 },
  { date: '11-22 周六', period: '10:00-11:30', subject: '数学', rooms: 'A-301~310', supervisors: '张丽华 + 陈志远 等 10 人', students: 240 },
  { date: '11-22 周六', period: '14:30-16:30', subject: '英语 (含听力)', rooms: 'A-301~310', supervisors: '刘文涛 + 赵思琪 等 10 人', students: 240 },
  { date: '11-23 周日', period: '08:00-09:30', subject: '物理', rooms: 'A-301~310', supervisors: '王伟明 + 陈志远 等 10 人', students: 240 },
  { date: '11-23 周日', period: '10:00-11:30', subject: '化学', rooms: 'A-301~310', supervisors: '刘文涛 + 周晓梅 等 10 人', students: 240 },
  { date: '11-23 周日', period: '14:30-16:00', subject: '生物', rooms: 'A-301~310', supervisors: '赵思琪 + 李慧君 等 10 人', students: 240 },
];

const __TD_LOG = [
  { at: '20 分钟前', actor: '李慧君', action: '修改 A-303 14:30 监考人员', detail: '原:刘文涛 → 新:赵思琪',         icon: IconEdit,    color: '#0EA5E9' },
  { at: '1 小时前',  actor: 'AI 系统', action: '自动修复 1 处冲突',         detail: 'A-303 14:30 黄建国 → 周晓梅',  icon: IconSparkle, color: '#9333EA' },
  { at: '今天 09:30', actor: '王明远', action: '发布排班 · 邮件通知 20 人', detail: '已成功发送 20 封通知邮件',     icon: IconCheck,   color: '#10B981' },
  { at: '昨天 14:20', actor: '王明远', action: 'AI 策略生成完成',           detail: '评分 96/100 · 0 冲突',         icon: IconSparkle, color: '#9333EA' },
  { at: '昨天 14:15', actor: '王明远', action: '上传 3 份 Excel 数据',      detail: '48 行 + 142 行 + 36 行',      icon: IconUpload,  color: '#4F46E5' },
  { at: '11-12 10:00','actor': '王明远',action: '创建任务',                  detail: '初始化 · 目标:高三 11 月月考', icon: IconPlus,    color: '#94A3B8' },
];

function TaskDetailPage() {
  const [tab, setTab] = React.useState('overview');
  const t = __TD_MOCK;

  const TABS = [
    { id: 'overview',    label: '概览',     icon: IconDashboard, count: null },
    { id: 'supervisors', label: '监考人员', icon: IconUsers,     count: t.supervisors },
    { id: 'schedule',    label: '排班表',   icon: IconCalendar,  count: t.exams },
    { id: 'log',         label: '操作记录', icon: IconArchive,   count: __TD_LOG.length },
  ];

  return (
    <PageShell
      crumbs={['天津市实验中学', '排班作业', '任务总览', t.name]}
      title={t.name}
      badge={<span className="inline-flex items-center gap-1.5"><Tag color={t.typeColor}>{t.type}</Tag><Tag color="sky" dot>进行中</Tag></span>}
      subtitle={
        <span>编号 <span className="font-mono">{t.id}</span> · 负责人 <span className="font-semibold" style={{ color: 'var(--text-strong)' }}>{t.owner}</span> · 最近更新 {t.updatedAt}</span>
      }
      actions={
        <>
          <Button icon={IconUpload} onClick={() => window.ui.toast('正在导出任务文件…', { kind: 'info', desc: '4 份 Excel + AI 摘要 PDF' })}>导出</Button>
          <Button icon={IconEdit} onClick={() => window.navigateTo('edit')}>修改排班</Button>
          <Button variant="primary" icon={IconCheck} onClick={() => window.ui.toast('已发送邮件通知', { kind: 'success' })}>通知监考人员</Button>
        </>
      }
    >
      {/* Top KPI row */}
      <div className="grid grid-cols-6 gap-3">
        <MiniKPI label="考期"     value={t.days} unit="天" accent="#4F46E5" icon={IconCalendar} />
        <MiniKPI label="考试场次" value={t.exams} unit="场" accent="#0EA5E9" icon={IconList} />
        <MiniKPI label="监考人数" value={t.supervisors} unit="人" accent="#059669" icon={IconUsers} />
        <MiniKPI label="使用教室" value={t.classrooms} unit="间" accent="#D97706" icon={IconBuilding} />
        <MiniKPI label="参考学生" value={t.students.toLocaleString()} unit="人" accent="#9333EA" icon={IconUsers} />
        <MiniKPI label="预计津贴" value={`¥${t.allowance.toLocaleString()}`} accent="#EF4444" icon={IconChart} />
      </div>

      {/* Progress + status */}
      <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        <div className="flex items-center gap-6">
          <div className="flex-1">
            <div className="flex items-baseline justify-between mb-2">
              <span className="text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>整体进度</span>
              <span className="num text-[16px] font-bold" style={{ color: 'var(--brand)' }}>{t.progress}%</span>
            </div>
            <div className="h-2 rounded-full overflow-hidden" style={{ background: '#F1F5F9' }}>
              <div className="h-full rounded-full transition-all" style={{ width: `${t.progress}%`, background: 'linear-gradient(90deg, #4F46E5, #9333EA)' }} />
            </div>
            <div className="mt-3 flex items-center gap-4 text-[11.5px]">
              {[
                { l: '上传数据', done: true },
                { l: 'AI 排班',  done: true },
                { l: '通知监考', done: true },
                { l: '考试进行', done: false, current: true },
                { l: '归档统计', done: false },
              ].map((s, i) => (
                <span key={i} className="inline-flex items-center gap-1.5"
                  style={{ color: s.done ? '#059669' : s.current ? 'var(--brand)' : 'var(--text-soft)' }}>
                  {s.done ? <IconCheck size={11} strokeWidth={2.5} /> : <span className="w-2 h-2 rounded-full" style={{ background: s.current ? 'var(--brand)' : '#CBD5E1', boxShadow: s.current ? '0 0 0 3px rgba(79,70,229,.16)' : 'none' }} />}
                  {s.l}
                </span>
              ))}
            </div>
          </div>
          <div className="w-px h-16" style={{ background: 'var(--border-soft)' }} />
          <div className="text-right">
            <div className="font-mono text-[10.5px] uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>距离开考</div>
            <div className="num text-[28px] font-bold" style={{ color: 'var(--brand)' }}>1 天 18 时</div>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="bg-white rounded-xl p-1 inline-flex items-center gap-1"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        {TABS.map((tb) => {
          const active = tab === tb.id;
          return (
            <button key={tb.id} onClick={() => setTab(tb.id)}
              className="h-9 px-4 rounded-lg inline-flex items-center gap-2 transition-all"
              style={{
                background: active ? 'var(--brand-soft)' : 'transparent',
                color:      active ? 'var(--brand)' : 'var(--text-medium)',
                fontWeight: active ? 600 : 500,
                fontSize: 13,
              }}>
              <tb.icon size={14} strokeWidth={active ? 2.25 : 1.75} />
              {tb.label}
              {tb.count != null && (
                <span className="font-mono text-[10.5px] px-1.5 py-0.5 rounded"
                  style={{ background: active ? '#fff' : '#F1F5F9', color: active ? 'var(--brand)' : 'var(--text-soft)' }}>
                  {tb.count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {tab === 'overview' && (
        <div className="grid grid-cols-[1fr_360px] gap-5">
          <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
            <SectionTitle title="任务摘要" sub="AI 自动生成 · 可编辑" right={<Tag color="violet" dot>AI</Tag>} />
            <div className="rounded-lg p-4 text-[13px] leading-relaxed" style={{ background: '#F8FAFC', color: 'var(--text-medium)' }}>
              本次任务为<strong style={{ color: 'var(--text-strong)' }}>高三年级 11 月第二次月考</strong>,共安排 <strong>10 场考试</strong>(语文/数学/英语/物理/化学/生物)、为期 2 天、覆盖 480 名高三学生与 10 间标准考场(A 栋 301-310)。
              <br /><br />
              系统已基于 K-12 标准策略自动分配 <strong>20 名监考教师</strong>,严格遵守班主任避监本班、同年级回避、每场至少 2 人监考等约束。AI 评分 <strong style={{ color: '#10B981' }}>96 / 100</strong>,无冲突。
            </div>
            <SectionTitle title="时间轴" sub="关键里程碑" />
            <ul className="space-y-3">
              {[
                { at: '11-12 10:00', l: '任务创建', s: 'done' },
                { at: '昨天 14:20',  l: 'AI 智能排班完成', s: 'done' },
                { at: '今天 09:30',  l: '排班已发布 · 邮件通知 20 名监考人员', s: 'done' },
                { at: '11-22 08:00', l: '首场考试开始', s: 'future' },
                { at: '11-23 16:00', l: '考试结束,自动归档', s: 'future' },
              ].map((it, i) => (
                <li key={i} className="flex items-start gap-3">
                  <StatusDot color={it.s === 'done' ? '#10B981' : '#CBD5E1'} pulse={it.s === 'current'} />
                  <div className="flex-1 flex items-baseline justify-between">
                    <span className="text-[12.5px]" style={{ color: it.s === 'done' ? 'var(--text-strong)' : 'var(--text-medium)' }}>{it.l}</span>
                    <span className="font-mono text-[11px]" style={{ color: 'var(--text-soft)' }}>{it.at}</span>
                  </div>
                </li>
              ))}
            </ul>
          </div>

          <div className="space-y-4">
            <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
              <SectionTitle title="文件下载" />
              {[
                { l: '完整监考排班表', s: 'XLSX · 24 KB', c: '#4F46E5' },
                { l: '按教师视图',     s: 'XLSX · 18 KB', c: '#059669' },
                { l: '按考场视图',     s: 'XLSX · 16 KB', c: '#D97706' },
                { l: 'AI 摘要报告',    s: 'PDF · 142 KB', c: '#9333EA' },
              ].map((f) => (
                <button key={f.l} onClick={() => window.ui.toast('开始下载…', { kind: 'info', desc: f.l })}
                  className="w-full mt-2 rounded-lg p-3 flex items-center gap-3 transition-colors hover:bg-slate-50"
                  style={{ border: '1px solid var(--border-soft)' }}>
                  <div className="w-9 h-11 rounded flex items-end justify-center pb-1" style={{ background: '#fff', border: `1px solid ${f.c}55` }}>
                    <span className="font-mono text-[8px] font-bold" style={{ color: f.c }}>{f.s.split(' ')[0]}</span>
                  </div>
                  <div className="flex-1 text-left">
                    <div className="text-[12.5px] font-medium" style={{ color: 'var(--text-strong)' }}>{f.l}</div>
                    <div className="text-[10.5px] mt-0.5 font-mono" style={{ color: 'var(--text-soft)' }}>{f.s}</div>
                  </div>
                  <IconArrowDown size={14} className="text-slate-400" />
                </button>
              ))}
            </div>

            <div className="rounded-2xl p-5 text-white relative overflow-hidden"
              style={{ background: 'linear-gradient(135deg, #9333EA 0%, #4F46E5 100%)', boxShadow: '0 12px 32px rgba(147,51,234,.32)' }}>
              <div className="font-mono text-[10px] uppercase tracking-wider opacity-80">AI SCORE</div>
              <div className="num text-[44px] font-bold leading-none mt-1">96<span className="text-[16px] opacity-70 ml-1">/100</span></div>
              <div className="text-[11.5px] opacity-80 mt-2">排班质量评估</div>
              <div className="mt-3 space-y-1 text-[11px]">
                {[
                  { l: '工作量均衡', v: '+18' },
                  { l: '约束满足',   v: '+38' },
                  { l: '考场分布',   v: '+22' },
                  { l: '冲突惩罚',   v: '-0'  },
                ].map((r) => (
                  <div key={r.l} className="flex items-center justify-between">
                    <span className="opacity-70">{r.l}</span>
                    <span className="font-mono font-semibold">{r.v}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {tab === 'supervisors' && (
        <DataTable
          columns={[
            { key: 'id',     label: '工号', width: 100, mono: true, render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.id}</span> },
            { key: 'name',   label: '姓名', width: 150,
              render: (r) => (
                <div className="inline-flex items-center gap-2"><AvatarChip name={r.name} size={26} />
                  <span className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</span>
                </div>
              ),
            },
            { key: 'grade',   label: '任教年级', width: 100, render: (r) => <Tag color="indigo">{r.grade}</Tag> },
            { key: 'subject', label: '任教科目', width: 100, render: (r) => <Tag color="slate">{r.subject}</Tag> },
            { key: 'sessions', label: '监考场次', width: 100, align: 'center', mono: true, render: (r) => <span className="font-semibold" style={{ color: 'var(--text-strong)' }}>{r.sessions}</span> },
            { key: 'status', label: '响应状态', width: 110,
              render: (r) => r.status === 'confirmed' ? <Tag color="emerald" dot>已确认</Tag>
                            : r.status === 'pending'  ? <Tag color="amber" dot>待响应</Tag>
                                                       : <Tag color="rose" dot>已拒绝</Tag>,
            },
            { key: 'actions', label: '操作', width: 160, align: 'right',
              render: (r) => (
                <div className="inline-flex items-center gap-1">
                  <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--brand)' }}
                    onClick={() => window.ui.toast(`已发送提醒给 ${r.name}`, { kind: 'success' })}>提醒响应</button>
                  <span className="text-slate-300">·</span>
                  <button className="text-[12px] font-medium px-1.5 hover:underline" style={{ color: 'var(--text-medium)' }}
                    onClick={() => window.ui.toast(`替换 ${r.name}`, { kind: 'info' })}>替换</button>
                </div>
              ),
            },
          ]}
          rows={__TD_SUPERVISORS}
          selectable
        />
      )}

      {tab === 'schedule' && (
        <DataTable
          columns={[
            { key: 'date',     label: '日期',     width: 130, mono: true, render: (r) => <span className="font-mono" style={{ color: 'var(--text-strong)' }}>{r.date}</span> },
            { key: 'period',   label: '时段',     width: 130, mono: true, render: (r) => <span className="font-mono font-semibold" style={{ color: 'var(--brand)' }}>{r.period}</span> },
            { key: 'subject',  label: '科目',     width: 160, render: (r) => <Tag color="indigo">{r.subject}</Tag> },
            { key: 'rooms',    label: '考场',     width: 130, mono: true, render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-medium)' }}>{r.rooms}</span> },
            { key: 'supervisors', label: '监考人员', wrap: true,
              render: (r) => <span className="text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{r.supervisors}</span> },
            { key: 'students', label: '考生数', width: 90, align: 'center', mono: true, render: (r) => <span>{r.students}</span> },
          ]}
          rows={__TD_SCHEDULE}
        />
      )}

      {tab === 'log' && (
        <div className="bg-white rounded-2xl p-5" style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
          <SectionTitle title="操作记录" sub={`共 ${__TD_LOG.length} 条 · 全部记录在审计日志`} right={<Button size="sm" variant="ghost" icon={IconUpload} onClick={() => window.ui.toast('导出日志', { kind: 'info' })}>导出</Button>} />
          <ul className="relative space-y-5 pl-7">
            <span className="absolute left-[14px] top-1 bottom-1 w-px" style={{ background: 'var(--border-soft)' }} />
            {__TD_LOG.map((l, i) => (
              <li key={i} className="relative">
                <span className="absolute -left-7 w-7 h-7 rounded-full flex items-center justify-center"
                  style={{ background: l.color + '20', color: l.color, top: 0 }}>
                  <l.icon size={13} strokeWidth={2} />
                </span>
                <div className="flex items-baseline justify-between gap-3">
                  <div>
                    <div className="text-[13px] font-semibold" style={{ color: 'var(--text-strong)' }}>{l.action}</div>
                    <div className="text-[12px] mt-0.5" style={{ color: 'var(--text-medium)' }}>{l.detail}</div>
                  </div>
                  <span className="font-mono text-[11px] shrink-0" style={{ color: 'var(--text-soft)' }}>{l.at}</span>
                </div>
                <div className="text-[10.5px] mt-1 inline-flex items-center gap-1.5" style={{ color: 'var(--text-soft)' }}>
                  <AvatarChip name={l.actor} size={14} />
                  {l.actor}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </PageShell>
  );
}

window.TaskDetailPage = TaskDetailPage;
