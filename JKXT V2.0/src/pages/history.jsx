// 历史统计 — 教师监考历史汇总 + 聚合卡片 + 子页入口

// Donut for distribution by grade
function GradeDistribution({ data }) {
  const rows = Array.isArray(data) ? data : [];
  const total = rows.reduce((a, b) => a + b.count, 0);
  if (rows.length === 0) {
    return (
      <div className="bg-white rounded-2xl p-5"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        <SectionTitle title="按年级分布" sub="暂无数据" />
        <div className="py-10 text-center text-[13px]" style={{ color: 'var(--text-soft)' }}>
          最近一次成功排班中没有监考记录，年级分布暂无数据。
        </div>
      </div>
    );
  }
  return (
    <div className="bg-white rounded-2xl p-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle title="按年级分布" sub={`共 ${total} 监考人次`} />
      <div className="flex items-center gap-5">
        <div className="shrink-0" style={{ width: 140 }}>
          <DoughnutChartJS
            labels={rows.map((d) => d.grade)}
            data={rows.map((d) => d.count)}
            colors={rows.map((d) => d.color)}
            height={140} cutout="68%"
            centerLabel="累计" centerValue={total}
          />
        </div>
        <ul className="space-y-1.5 flex-1">
          {rows.map((d) => (
            <li key={d.grade} className="flex items-center gap-2 text-[12px]">
              <span className="w-2 h-2 rounded-full" style={{ background: d.color }} />
              <span style={{ color: 'var(--text-medium)' }}>{d.grade}</span>
              <span className="ml-auto font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{d.count}</span>
              <span className="text-[10.5px] font-mono w-10 text-right" style={{ color: 'var(--text-soft)' }}>
                {total ? Math.round((d.count / total) * 100) : 0}%
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

// Trend line
function historyTrendPresentation(trend) {
  const labels = Array.isArray(trend?.labels) ? trend.labels : [];
  const data = Array.isArray(trend?.data) ? trend.data : [];
  const month = (value) => {
    const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(String(value));
    return match ? `${match[1]} 年 ${Number(match[2])} 月` : String(value);
  };
  const first = labels.length ? month(labels[0]) : '';
  const last = labels.length ? month(labels[labels.length - 1]) : '';
  const comparable = labels.length >= 2 && labels.length === data.length && data[0] > 0
    && typeof trend?.trendPct === 'number' && Number.isFinite(trend.trendPct);
  return {
    subtitle: labels.length ? `${first}${labels.length > 1 ? ` — ${last}` : ''} · 监考人次` : '最近一次成功排班 · 监考人次',
    trendPct: comparable ? trend.trendPct : null,
    comparison: comparable ? `${last}较${first}` : '月份不足或首月为零，不作增长比较',
  };
}

function MonthlyTrend({ trend }) {
  if (!trend || !Array.isArray(trend.data) || trend.data.length === 0) {
    return (
      <div className="bg-white rounded-2xl p-5"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        <SectionTitle title="月度趋势" sub="暂无数据" />
        <div className="py-10 text-center text-[13px]" style={{ color: 'var(--text-soft)' }}>
          最近一次成功排班中没有监考记录，月度趋势暂无数据。
        </div>
      </div>
    );
  }
  const labels = Array.isArray(trend.labels) ? trend.labels : [];
  const data = trend.data;
  const presentation = historyTrendPresentation(trend);
  const trendPct = presentation.trendPct;
  return (
    <div className="bg-white rounded-2xl p-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle
        title="月度趋势"
        sub={presentation.subtitle}
        right={trendPct === null ? null : (
          <div className="inline-flex flex-wrap items-center gap-1.5">
            <span className="num font-semibold text-[15px]" style={{ color: 'var(--text-strong)' }}>{trendPct >= 0 ? '+' : ''}{trendPct}%</span>
            <Tag color="slate">{presentation.comparison}</Tag>
          </div>
        )}
      />
      <LineChartJS
        labels={labels}
        datasets={[{ label: '监考人次', data }]}
        height={180} accent="#4F46E5" unit=" 人次"
      />
    </div>
  );
}

function HistoryPage() {
  const [historyPayload, setHistoryPayload] = React.useState(null);
  const [historyStatus, setHistoryStatus] = React.useState('loading');
  const [historyError, setHistoryError] = React.useState(null);
  const [reloadKey, setReloadKey] = React.useState(0);
  React.useEffect(() => {
    let cancelled = false;
    async function loadHistory() {
      setHistoryStatus('loading');
      setHistoryError(null);
      if (!window.api?.get) {
        setHistoryStatus('error');
        setHistoryError(new Error('历史统计接口未就绪'));
        return;
      }
      try {
        const payload = await window.api.get('/stats/history');
        if (!cancelled) {
          setHistoryPayload(payload);
          setHistoryStatus('ready');
        }
      } catch (error) {
        if (!cancelled) {
          setHistoryPayload(null);
          setHistoryError(error);
          setHistoryStatus(error?.status === 403 ? 'permission' : 'error');
        }
      }
    }
    loadHistory();
    return () => { cancelled = true; };
  }, [reloadKey]);

  // 迭代11b P1: 后端已加载(含空 rows)时用真实值——空则显示真实空表,不回退写死 MOCK_HISTORY 当真实历史。
  const backendLoaded = historyPayload !== null && Array.isArray(historyPayload.rows);
  const historyRows = backendLoaded ? historyPayload.rows : [];
  const total = historyRows.reduce((a, b) => a + Number(b.count || 0), 0);
  const totalDur = historyRows.reduce((a, b) => a + Number(b.duration || b.hours || 0), 0);
  const totalAllowance = historyRows.reduce((a, b) => a + Number(b.total || b.allowance || 0), 0);

  const maxCount = Math.max(1, ...historyRows.map((h) => Number(h.count || 0)));

  // ── 真实的搜索/筛选/排序（此前 FilterBar 无任何处理器：输入框静默无效、
  //    筛选胶囊只弹「示例筛选 · 列表数据为预览」toast，表格纹丝不动）──
  const [q, setQ] = React.useState('');
  const [gradeF, setGradeF] = React.useState('全部');
  const [subjectF, setSubjectF] = React.useState('全部');
  const [sortF, setSortF] = React.useState('次数倒序');
  // 年级/科目选项从真实数据推导——写死的「初一..高三」在小学部署下全是无效选项
  const gradeOptions = ['全部', ...Array.from(new Set(historyRows.map((r) => r.grade).filter((g) => g && g !== '-')))];
  const subjectOptions = ['全部', ...Array.from(new Set(historyRows.flatMap((r) => String(r.subjects || '').split('/')).filter((s) => s && s !== '-')))];
  const filteredRows = historyRows
    .filter((r) => !q || String(r.code || '').toLowerCase().includes(q.toLowerCase()) || String(r.name || '').includes(q))
    .filter((r) => gradeF === '全部' || r.grade === gradeF)
    .filter((r) => subjectF === '全部' || String(r.subjects || '').split('/').includes(subjectF))
    .slice()
    .sort((a, b) => {
      if (sortF === '次数正序') return (Number(a.count) || 0) - (Number(b.count) || 0);
      if (sortF === '姓名 A→Z') return String(a.name || '').localeCompare(String(b.name || ''), 'zh');
      if (sortF === '工号升序') return String(a.code || '').localeCompare(String(b.code || ''));
      return (Number(b.count) || 0) - (Number(a.count) || 0);   // 次数倒序（默认）
    });

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
    const recent = Array.isArray(r.recent) ? r.recent : [];
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
              { l: '本校排名', v: '#' + r.ranking },
              { l: '本次监考人次占比', v: Number(r.contributionPct ?? 0).toFixed(1) + '%' },
            ].map((it) => (
              <div key={it.l} className="rounded-lg p-3" style={{ background: 'var(--surface-muted)' }}>
                <div className="text-[10.5px] font-mono uppercase tracking-wider" style={{ color: 'var(--text-soft)' }}>{it.l}</div>
                <div className="mt-1 num text-[16px] font-bold" style={{ color: 'var(--text-strong)' }}>{it.v} <span className="text-[11px] font-normal" style={{ color: 'var(--text-medium)' }}>{it.u || ''}</span></div>
              </div>
            ))}
          </div>
          <div>
            <div className="text-[12.5px] font-medium mb-2" style={{ color: 'var(--text-medium)' }}>近期监考</div>
            {recent.length ? <ul className="space-y-2">
              {recent.map((it, i) => (
                <li key={i} className="rounded-lg p-3 flex items-center justify-between" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)' }}>
                  <div className="flex items-center gap-3">
                    <span className="font-mono text-[11px] px-1.5 py-0.5 rounded" style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)' }}>{it.d}</span>
                    <span className="text-[13px]" style={{ color: 'var(--text-strong)' }}>{it.l}</span>
                  </div>
                  <span className="num font-semibold text-[13px]" style={{ color: '#D97706' }}>+¥{it.amt}</span>
                </li>
              ))}
            </ul> : <div className="rounded-lg px-4 py-6 text-center text-[12px]" style={{ background: 'var(--surface-muted)', color: 'var(--text-soft)', border: '1px dashed var(--border-strong)' }}>暂无近期监考记录</div>}
          </div>
        </div>
      ),
      footer: (
        <Button onClick={() => window.ui.closeDrawer()}>关闭</Button>
      ),
    });
  };

  return (
    <PageShell
      crumbs={[orgName(), '数据与资源', '历史统计']}
      title="历史统计"
      badge="最近一次成功排班"
      subtitle="按教师查看最近一次成功排班的监考、时长与津贴；当前报表不是全学期累计，也不代表已发布结果。"
      actions={
        <>
          <Button variant="primary" icon={IconUpload} onClick={() => {
            // 迭代10 P2：此前只 toast「预计 5 秒后下载 Excel」却无任何请求/生成(下载永不到)。改为用已加载的
            // SheetJS(window.XLSX)真·客户端从当前表格数据生成 xlsx。
            const XLSX = window.XLSX;
            if (!XLSX) { window.ui.toast('导出组件未就绪', { kind: 'error', desc: '请刷新页面后重试' }); return; }
            const aoa = [
              ['工号', '姓名', '任教年级', '任教科目', '监考次数(场)', '总时长(小时)', '场均时长(小时)', '累计津贴(元)'],
              ...historyRows.map((r) => [r.code, r.name, r.grade, r.subjects,
                Number(r.count) || 0, Number(r.duration) || 0, Number(r.avgDur) || 0, Number(r.total) || 0]),
            ];
            const ws = XLSX.utils.aoa_to_sheet(aoa);
            const wb = XLSX.utils.book_new();
            XLSX.utils.book_append_sheet(wb, ws, '历史统计');
            XLSX.writeFile(wb, '历史监考统计.xlsx');
            window.ui.toast('已导出 Excel', { kind: 'success', desc: `${historyRows.length} 名教师` });
          }}>导出报表</Button>
        </>
      }
    >
      {historyStatus === 'loading' && <div role="status" className="ceramic-tile p-5 text-[13px]" style={{ color: 'var(--text-medium)' }}>正在加载真实历史统计…</div>}
      {historyStatus === 'permission' && <div role="alert" className="ceramic-tile p-5"><div className="font-semibold">无权查看历史统计</div><div className="mt-1 text-[12.5px]" style={{ color: 'var(--text-soft)' }}>当前账号没有读取该报表的权限，系统未展示任何模拟数据。</div></div>}
      {historyStatus === 'error' && <div role="alert" className="ceramic-tile p-5"><div className="font-semibold" style={{ color: 'var(--danger)' }}>历史统计加载失败</div><div className="mt-1 text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{historyError?.message || '服务暂时不可用'}</div><Button className="mt-3" onClick={() => setReloadKey((value) => value + 1)}>重新加载</Button></div>}
      <div className="grid grid-cols-4 gap-4">
        <MiniKPI label="本次监考人次" value={total} unit="人次" accent="#4F46E5" icon={IconCalendar} />
        <MiniKPI label="本次监考时长" value={totalDur} unit="小时" accent="#059669" icon={IconClock} />
        <MiniKPI label="本次津贴" value={`¥${totalAllowance.toLocaleString()}`} accent="#D97706" icon={IconChart} sub="最近一次成功排班" />
        <MiniKPI label="参与教师" value={historyRows.filter((r) => (Number(r.count) || 0) > 0).length} unit="人" accent="#9333EA" icon={IconUsers} sub={`人均 ${(total/Math.max(1, historyRows.filter((r) => (Number(r.count) || 0) > 0).length)).toFixed(1)} 场`} />
      </div>

      <div className="grid grid-cols-2 gap-5">
        <GradeDistribution data={historyPayload?.byGrade} />
        <MonthlyTrend trend={historyPayload?.monthlyTrend} />
      </div>

      {/* 学期胶囊已移除：后端不按学期过滤（exam_task.term_id 从不写入，批次2 已确立的语义边界），
          留着一个「2025 秋/2024 秋」选项就是假承诺。恢复的前提是后端先支持按学期切分。 */}
      <FilterBar
        search="搜索工号、姓名..."
        searchValue={q}
        onSearch={setQ}
        filters={[
          { label: '年级:', value: gradeF, options: gradeOptions, onChange: setGradeF },
          { label: '科目:', value: subjectF, options: subjectOptions, onChange: setSubjectF },
          { label: '排序:', value: sortF, options: ['次数倒序', '次数正序', '姓名 A→Z', '工号升序'], onChange: setSortF },
        ]}
        total={filteredRows.length}
        onReset={() => { setQ(''); setGradeF('全部'); setSubjectF('全部'); setSortF('次数倒序'); }}
      />

      <DataTable columns={columns} rows={filteredRows} onRowClick={openDetail} />
    </PageShell>
  );
}

window.HistoryPage = HistoryPage;
