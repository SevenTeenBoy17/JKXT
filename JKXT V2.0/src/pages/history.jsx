// 历史统计 — 教师监考历史汇总 + 聚合卡片 + 子页入口

const MOCK_HISTORY = [
  { code: 'T240101', name: '王伟明', grade: '高三', subjects: '数学', count: 18, duration: 36, avgDur: 2.0, total: 2160, ranking: 1 },
  { code: 'T240102', name: '李慧君', grade: '高三', subjects: '语文', count: 16, duration: 32, avgDur: 2.0, total: 1920, ranking: 2 },
  { code: 'T240202', name: '陈志远', grade: '高二', subjects: '物理', count: 14, duration: 28, avgDur: 2.0, total: 1680, ranking: 3 },
  { code: 'T240201', name: '张丽华', grade: '高二', subjects: '英语', count: 12, duration: 26, avgDur: 2.17, total: 1560, ranking: 4 },
  { code: 'J240901', name: '赵思琪', grade: '初三', subjects: '生物', count: 11, duration: 22, avgDur: 2.0, total: 1320, ranking: 5 },
  { code: 'T240301', name: '刘文涛', grade: '高一', subjects: '化学', count: 10, duration: 20, avgDur: 2.0, total: 1200, ranking: 6 },
  { code: 'J240801', name: '周晓梅', grade: '初二', subjects: '地理', count: 9, duration: 18, avgDur: 2.0, total: 1080, ranking: 7 },
  { code: 'J240902', name: '黄建国', grade: '初三', subjects: '历史', count: 8, duration: 16, avgDur: 2.0, total: 960, ranking: 8 },
  { code: 'J240701', name: '徐安琪', grade: '七年级', subjects: '英语', count: 7, duration: 14, avgDur: 2.0, total: 840, ranking: 9 },
  { code: 'J240802', name: '杨海洋', grade: '初二', subjects: '体育',  count: 6, duration: 12, avgDur: 2.0, total: 720, ranking: 10 },
];

// Donut for distribution by grade
function GradeDistribution() {
  const data = [
    { grade: '高三', count: 34, color: '#EF4444' },
    { grade: '高二', count: 26, color: '#F59E0B' },
    { grade: '高一', count: 18, color: '#06B6D4' },
    { grade: '初三', count: 19, color: '#6366F1' },
    { grade: '初二', count: 15, color: '#10B981' },
    { grade: '七年级', count: 9, color: '#9333EA' },
  ];
  const total = data.reduce((a, b) => a + b.count, 0);
  return (
    <div className="bg-white rounded-2xl p-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle title="按年级分布" sub={`累计 ${total} 场监考`} right={<Button size="sm" variant="ghost">详细 →</Button>} />
      <div className="flex items-center gap-5">
        <div className="shrink-0" style={{ width: 140 }}>
          <DoughnutChartJS
            labels={data.map((d) => d.grade)}
            data={data.map((d) => d.count)}
            colors={data.map((d) => d.color)}
            height={140} cutout="68%"
            centerLabel="累计" centerValue={total}
          />
        </div>
        <ul className="space-y-1.5 flex-1">
          {data.map((d) => (
            <li key={d.grade} className="flex items-center gap-2 text-[12px]">
              <span className="w-2 h-2 rounded-full" style={{ background: d.color }} />
              <span style={{ color: 'var(--text-medium)' }}>{d.grade}</span>
              <span className="ml-auto font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{d.count}</span>
              <span className="text-[10.5px] font-mono w-10 text-right" style={{ color: 'var(--text-soft)' }}>
                {Math.round((d.count / total) * 100)}%
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

// Trend line
function MonthlyTrend() {
  const labels = ['8 月', '9 月', '10 月', '11 月'];
  const data = [12, 28, 45, 36];
  return (
    <div className="bg-white rounded-2xl p-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle
        title="月度趋势"
        sub="本学期 8 - 11 月监考量"
        right={
          <div className="inline-flex items-center gap-1.5">
            <span className="num font-semibold text-[15px]" style={{ color: 'var(--text-strong)' }}>+147%</span>
            <Tag color="emerald" dot>较 8 月</Tag>
          </div>
        }
      />
      <LineChartJS
        labels={labels}
        datasets={[{ label: '监考场次', data }]}
        height={180} accent="#4F46E5" unit=" 场"
      />
    </div>
  );
}

function HistoryPage() {
  const total = MOCK_HISTORY.reduce((a, b) => a + b.count, 0);
  const totalDur = MOCK_HISTORY.reduce((a, b) => a + b.duration, 0);
  const totalAllowance = MOCK_HISTORY.reduce((a, b) => a + b.total, 0);

  const maxCount = Math.max(...MOCK_HISTORY.map((h) => h.count));

  const columns = [
    { key: 'ranking', label: '排名', width: 70, align: 'center',
      render: (r) => {
        const c = r.ranking <= 3 ? ['#D97706', '#94A3B8', '#A16207'][r.ranking - 1] : '#94A3B8';
        return (
          <span
            className="inline-flex items-center justify-center w-6 h-6 rounded-md font-mono font-bold text-[11.5px]"
            style={{
              background: r.ranking <= 3 ? c + '20' : '#F1F5F9',
              color: c,
            }}
          >
            {r.ranking}
          </span>
        );
      },
    },
    { key: 'code', label: '工号', width: 100, mono: true,
      render: (r) => <span className="font-mono text-[12px]" style={{ color: 'var(--text-soft)' }}>{r.code}</span> },
    { key: 'name', label: '姓名', width: 130,
      render: (r) => (
        <div className="inline-flex items-center gap-2">
          <AvatarChip name={r.name} size={26} />
          <span className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name}</span>
        </div>
      ),
    },
    { key: 'grade', label: '任教年级', width: 90,
      render: (r) => <Tag color="indigo">{r.grade}</Tag> },
    { key: 'subjects', label: '任教科目', width: 100,
      render: (r) => <Tag color="slate">{r.subjects}</Tag> },
    { key: 'count', label: '监考次数', width: 180, sortable: true,
      render: (r) => (
        <div className="flex items-center gap-2.5">
          <span className="num font-semibold w-6 text-right" style={{ color: 'var(--text-strong)' }}>{r.count}</span>
          <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: '#F1F5F9' }}>
            <div className="h-full rounded-full" style={{ width: `${(r.count / maxCount) * 100}%`, background: 'var(--brand)' }} />
          </div>
        </div>
      ),
    },
    { key: 'duration', label: '总时长', width: 90, align: 'right', mono: true,
      render: (r) => <span>{r.duration} <span className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>h</span></span> },
    { key: 'avgDur', label: '场均时长', width: 100, align: 'right', mono: true,
      render: (r) => <span>{r.avgDur.toFixed(1)} h</span> },
    { key: 'total', label: '累计津贴', width: 120, align: 'right', sortable: true,
      render: (r) => (
        <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>
          ¥<span className="ml-0.5">{r.total.toLocaleString()}</span>
        </span>
      ),
    },
    { key: 'actions', label: '操作', width: 100, align: 'right',
      render: (r) => (
        <button className="text-[12px] font-medium hover:underline" style={{ color: 'var(--brand)' }}
          onClick={(e) => { e.stopPropagation(); openDetail(r); }}>
          查看明细 →
        </button>
      ),
    },
  ];

  const openDetail = (r) => {
    window.ui.drawer({
      title: r.name + ' · 监考明细',
      subtitle: `${r.code} · ${r.grade} · ${r.subjects}`,
      width: 520,
      content: (
        <div className="space-y-5">
          <div className="grid grid-cols-3 gap-3">
            {[
              { l: '监考次数', v: r.count, u: '场' },
              { l: '总时长',   v: r.duration, u: 'h' },
              { l: '场均',     v: r.avgDur.toFixed(1), u: 'h' },
              { l: '总津贴',   v: '¥' + r.total.toLocaleString() },
              { l: '本年级排名', v: '#' + r.ranking },
              { l: '本学期贡献', v: ((r.count / 200) * 100).toFixed(1) + '%' },
            ].map((it) => (
              <div key={it.l} className="rounded-lg p-3" style={{ background: '#F8FAFC' }}>
                <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>{it.l}</div>
                <div className="mt-1 num text-[16px] font-bold" style={{ color: 'var(--text-strong)' }}>{it.v} <span className="text-[11px] font-normal" style={{ color: 'var(--text-medium)' }}>{it.u || ''}</span></div>
              </div>
            ))}
          </div>
          <div>
            <div className="text-[12.5px] font-medium mb-2" style={{ color: 'var(--text-medium)' }}>近期监考</div>
            <ul className="space-y-2">
              {[
                { d: '11-21 14:30', l: '高三·一模联考 (A-302)', amt: 120 },
                { d: '11-15 08:00', l: '高三·期中考 (A-301)', amt: 120 },
                { d: '11-08 10:00', l: '高三·月考 (A-303)', amt: 120 },
                { d: '10-28 14:30', l: '高二·跨年级监考 (A-401)', amt: 120 },
              ].map((it, i) => (
                <li key={i} className="rounded-lg p-3 flex items-center justify-between" style={{ background: '#fff', border: '1px solid var(--border-soft)' }}>
                  <div className="flex items-center gap-3">
                    <span className="font-mono text-[11px] px-1.5 py-0.5 rounded" style={{ background: '#F1F5F9', color: 'var(--text-medium)' }}>{it.d}</span>
                    <span className="text-[13px]" style={{ color: 'var(--text-strong)' }}>{it.l}</span>
                  </div>
                  <span className="num font-semibold text-[13px]" style={{ color: '#D97706' }}>+¥{it.amt}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>
      ),
      footer: (
        <>
          <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
          <Button icon={IconUpload} onClick={() => window.ui.toast('导出明细', { kind: 'info' })}>导出个人报表</Button>
        </>
      ),
    });
  };

  return (
    <PageShell
      crumbs={['天津市实验中学', '数据与资源', '历史统计']}
      title="历史统计"
      badge="2025-2026 学年下学期"
      subtitle="按学期、教师维度查看历史监考、时长与津贴汇总"
      actions={
        <>
          <Button icon={IconCalendar} onClick={() => window.ui.toast('切换学期', { kind: 'info', desc: '当前 2025-2026 下 · 可切换至 2024-2025 上/下' })}>切换学期</Button>
          <Button icon={IconChart} onClick={() => window.ui.toast('跟转至聚合分析', { kind: 'info' })}>聚合分析</Button>
          <Button variant="primary" icon={IconUpload} onClick={() => window.ui.toast('正在导出报表…', { kind: 'info', desc: '预计 5 秒后下载 Excel' })}>导出报表</Button>
        </>
      }
    >
      <div className="grid grid-cols-4 gap-4">
        <MiniKPI label="累计监考次数" value={total} unit="场" accent="#4F46E5" icon={IconCalendar} trend="9.6" trendUp />
        <MiniKPI label="累计监考时长" value={totalDur} unit="小时" accent="#059669" icon={IconClock} trend="8.4" trendUp />
        <MiniKPI label="累计津贴" value={`¥${totalAllowance.toLocaleString()}`} accent="#D97706" icon={IconChart} sub="本学期累计" />
        <MiniKPI label="参与教师" value={MOCK_HISTORY.length} unit="人" accent="#9333EA" icon={IconUsers} sub={`人均 ${(total/MOCK_HISTORY.length).toFixed(1)} 场`} />
      </div>

      <div className="grid grid-cols-2 gap-5">
        <GradeDistribution />
        <MonthlyTrend />
      </div>

      <FilterBar
        search="搜索工号、姓名..."
        filters={[
          { label: '学期:', value: '当前' },
          { label: '年级:', value: '全部' },
          { label: '科目:', value: '全部' },
          { label: '排序:', value: '次数倒序' },
        ]}
        total={MOCK_HISTORY.length}
        onReset={() => {}}
      />

      <DataTable columns={columns} rows={MOCK_HISTORY} onRowClick={openDetail} />
    </PageShell>
  );
}

window.HistoryPage = HistoryPage;
