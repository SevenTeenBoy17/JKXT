// 监考次数统计 — 维度 tab + 柱状图 + 数据表

const SUPERVISE_DATA = {
  teacher: [
    { name: '王伟明', sub: '高三·数学', count: 18, hours: 36, allowance: 2160 },
    { name: '李慧君', sub: '高三·语文', count: 16, hours: 32, allowance: 1920 },
    { name: '陈志远', sub: '高二·物理', count: 14, hours: 28, allowance: 1680 },
    { name: '张丽华', sub: '高二·英语', count: 12, hours: 26, allowance: 1560 },
    { name: '赵思琪', sub: '初三·生物', count: 11, hours: 22, allowance: 1320 },
    { name: '刘文涛', sub: '高一·化学', count: 10, hours: 20, allowance: 1200 },
    { name: '周晓梅', sub: '初二·地理', count: 9,  hours: 18, allowance: 1080 },
    { name: '黄建国', sub: '初三·历史', count: 8,  hours: 16, allowance: 960 },
    { name: '徐安琪', sub: '初一·英语', count: 7,  hours: 14, allowance: 840 },
    { name: '杨海洋', sub: '初二·体育', count: 6,  hours: 12, allowance: 720 },
  ],
  subject: [
    { name: '数学',       sub: '主科', count: 42, hours: 84, allowance: 5040 },
    { name: '语文',       sub: '主科', count: 38, hours: 76, allowance: 4560 },
    { name: '英语',       sub: '主科', count: 35, hours: 70, allowance: 4200 },
    { name: '物理',       sub: '理科', count: 24, hours: 48, allowance: 2880 },
    { name: '化学',       sub: '理科', count: 20, hours: 40, allowance: 2400 },
    { name: '生物',       sub: '理科', count: 16, hours: 32, allowance: 1920 },
    { name: '历史',       sub: '文科', count: 12, hours: 24, allowance: 1440 },
    { name: '地理',       sub: '文科', count: 10, hours: 20, allowance: 1200 },
    { name: '道德与法治', sub: '文科', count: 8,  hours: 16, allowance: 960 },
    { name: '思想政治',   sub: '文科', count: 6,  hours: 12, allowance: 720 },
  ],
  grade: [
    { name: '高三',     sub: '12 个班', count: 38, hours: 76, allowance: 4560 },
    { name: '高二',     sub: '10 个班', count: 28, hours: 56, allowance: 3360 },
    { name: '高一',     sub: '10 个班', count: 22, hours: 44, allowance: 2640 },
    { name: '初三',     sub: '10 个班', count: 20, hours: 40, allowance: 2400 },
    { name: '初二',     sub: '8 个班',  count: 16, hours: 32, allowance: 1920 },
    { name: '初一',     sub: '8 个班',  count: 14, hours: 28, allowance: 1680 },
    { name: '全校联考', sub: '56 班',   count: 48, hours: 96, allowance: 5760 },
  ],
};

function StatsSupervisePage() {
  const [dim, setDim]       = React.useState('teacher');
  const [metric, setMetric] = React.useState('count');

  const data = SUPERVISE_DATA[dim];

  const dims = [
    { id: 'teacher', label: '按教师', icon: IconUsers },
    { id: 'subject', label: '按学科', icon: IconBook },
    { id: 'grade',   label: '按年级', icon: IconArchive },
  ];
  const metrics = [
    { id: 'count',     label: '监考次数', unit: '场',    color: '#4F46E5' },
    { id: 'hours',     label: '监考时长', unit: '小时',  color: '#059669' },
    { id: 'allowance', label: '津贴金额', unit: '元',    color: '#D97706' },
  ];

  const total = data.reduce((a, b) => a + b[metric], 0);
  const max = data.reduce((a, b) => Math.max(a, b[metric]), 0);
  const avg = Math.round(total / data.length);

  const columns = [
    { key: 'rank', label: '排名', width: 60, align: 'center',
      render: (_, i) => (
        <span
          className="inline-flex items-center justify-center w-6 h-6 rounded-md font-mono font-bold text-[11.5px]"
          style={{
            background: i < 3 ? ['#FEF3C7', '#F1F5F9', '#FFEDD5'][i] : '#F1F5F9',
            color:      i < 3 ? ['#D97706', '#475569', '#9A3412'][i] : '#94A3B8',
          }}
        >
          {i + 1}
        </span>
      ),
    },
    { key: 'name', label: dim === 'teacher' ? '姓名' : dim === 'subject' ? '学科' : '年级', width: 160,
      render: (r) => (
        <div className="inline-flex items-center gap-2">
          {dim === 'teacher' && <AvatarChip name={r.name} size={26} />}
          <div>
            <div className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</div>
            <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{r.sub}</div>
          </div>
        </div>
      ),
    },
    { key: 'count', label: '监考次数', width: 180,
      render: (r) => (
        <div className="flex items-center gap-2">
          <div className="flex-1 h-1.5 rounded-full" style={{ background: '#F1F5F9' }}>
            <div className="h-full rounded-full" style={{ width: `${(r.count / max) * 100}%`, background: '#4F46E5' }} />
          </div>
          <span className="num font-semibold w-8 text-right" style={{ color: 'var(--text-strong)' }}>{r.count}</span>
        </div>
      ),
    },
    { key: 'hours', label: '总时长 (h)', width: 110, align: 'right', mono: true,
      render: (r) => <span>{r.hours}</span> },
    { key: 'avg', label: '场均时长', width: 100, align: 'right', mono: true,
      render: (r) => <span>{(r.hours / r.count).toFixed(1)} h</span> },
    { key: 'allowance', label: '津贴', width: 110, align: 'right',
      render: (r) => <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>¥{r.allowance.toLocaleString()}</span> },
    { key: 'ratio', label: '占比', width: 80, align: 'right',
      render: (r) => <span className="num text-[12px]" style={{ color: 'var(--text-soft)' }}>{((r.count / total) * 100).toFixed(1)}%</span> },
  ];

  const m = metrics.find((x) => x.id === metric);

  return (
    <PageShell
      crumbs={['天津市实验中学', '数据与资源', '监考次数统计']}
      title="监考次数统计"
      badge="2025-2026 学年下学期"
      subtitle="多维度分析监考工作量、时长与津贴,支持导出"
      actions={
        <>
          <Button icon={IconCalendar} onClick={() => window.ui.toast('切换学期', { kind: 'info', desc: '可对比历史学期数据' })}>切换学期</Button>
          <Button variant="primary" icon={IconUpload} onClick={() => window.ui.toast('正在生成统计报表…', { kind: 'info', desc: `按 ${m.label} ${dim === 'teacher' ? '教师' : dim === 'subject' ? '学科' : '年级'} 维度 · Excel + PDF` })}>导出报表</Button>
        </>
      }
    >
      {/* Dimension tabs */}
      <div className="flex items-center justify-between">
        <div className="inline-flex p-1 rounded-xl bg-white"
          style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
          {dims.map((d) => {
            const active = dim === d.id;
            return (
              <button
                key={d.id}
                onClick={() => setDim(d.id)}
                className="h-9 px-4 rounded-lg inline-flex items-center gap-2 transition-all"
                style={{
                  background: active ? 'var(--brand)' : 'transparent',
                  color: active ? '#fff' : 'var(--text-medium)',
                  fontWeight: active ? 600 : 500,
                  fontSize: 13,
                  boxShadow: active ? '0 1px 2px rgba(79,70,229,.25)' : 'none',
                }}
              >
                <d.icon size={15} strokeWidth={active ? 2.25 : 1.75} />
                {d.label}
              </button>
            );
          })}
        </div>
        <div className="inline-flex p-1 rounded-xl bg-white"
          style={{ border: '1px solid var(--border-soft)' }}>
          {metrics.map((m) => {
            const active = metric === m.id;
            return (
              <button
                key={m.id}
                onClick={() => setMetric(m.id)}
                className="h-8 px-3 rounded-md text-[12.5px] font-medium transition-all"
                style={{
                  background: active ? '#F1F5F9' : 'transparent',
                  color: active ? 'var(--text-strong)' : 'var(--text-soft)',
                }}
              >
                <span style={{ color: active ? m.color : undefined, fontWeight: active ? 600 : 500 }}>
                  ● {m.label}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <MiniKPI label={`总${m.label}`} value={metric === 'allowance' ? '¥' + total.toLocaleString() : total} unit={metric !== 'allowance' ? m.unit : ''} accent={m.color} icon={IconChart} />
        <MiniKPI label="最高值" value={metric === 'allowance' ? '¥' + max.toLocaleString() : max} unit={metric !== 'allowance' ? m.unit : ''} accent="#9333EA" icon={IconArrowUp} sub={data[0].name} />
        <MiniKPI label="平均值" value={metric === 'allowance' ? '¥' + avg.toLocaleString() : avg} unit={metric !== 'allowance' ? m.unit : ''} accent="#0EA5E9" icon={IconChart} />
        <MiniKPI label="样本数" value={data.length} unit={dim === 'teacher' ? '人' : dim === 'subject' ? '科' : '组'} accent="#10B981" icon={IconUsers} />
      </div>

      <div className="bg-white rounded-2xl p-5"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        <SectionTitle
          title={`${dims.find(d => d.id === dim).label} · ${m.label}`}
          sub={`共 ${data.length} 项 · 已按 ${m.label} 倒序`}
          right={
            <div className="inline-flex items-center gap-2 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
              <span className="inline-flex items-center gap-1.5">
                <span className="w-2 h-2 rounded" style={{ background: m.color }} />
                {m.label}
              </span>
            </div>
          }
        />
        <BarChartJS
          labels={data.map((d) => d.name)}
          datasets={[{ label: m.label, data: data.map((d) => d[metric]) }]}
          accent={m.color} height={280} unit={metric === 'allowance' ? ' 元' : metric === 'hours' ? ' h' : ' 场'}
        />
      </div>

      <FilterBar
        search="搜索..."
        filters={[
          { label: '年级:', value: '全部' },
          { label: '科目:', value: '全部' },
          { label: '排序:', value: m.label + ' 倒序' },
        ]}
        total={data.length}
      />

      <DataTable columns={columns} rows={data} />
    </PageShell>
  );
}

window.StatsSupervisePage = StatsSupervisePage;
