// 监考次数统计 — 维度 tab + 柱状图 + 数据表

function superviseNumber(value) {
  if (value == null || typeof value === 'boolean' || (typeof value === 'string' && !value.trim())) return null;
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
}

function superviseValue(value, metric) {
  const number = superviseNumber(value);
  if (number == null) return '—';
  return metric === 'allowance' ? '¥' + number.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : number;
}

function StatsSupervisePage() {
  const [dim, setDim]       = React.useState('teacher');
  const [metric, setMetric] = React.useState('count');
  const [reload, setReload] = React.useState(0);
  const [resource, setResource] = React.useState({ key: '', status: 'loading', rows: [], error: '' });
  const requestKey = `${dim}:${metric}:${reload}`;

  React.useEffect(() => {
    let cancelled = false;
    async function loadStats() {
      setResource({ key: requestKey, status: 'loading', rows: [], error: '' });
      try {
        if (typeof window.api?.get !== 'function') throw Object.assign(new Error(), { code: 'STATS_UNAVAILABLE' });
        const payload = await window.api.get(`/stats/supervise?dim=${encodeURIComponent(dim)}&metric=${encodeURIComponent(metric)}`);
        if (!Array.isArray(payload?.rows) || payload.rows.some((row) => !row || typeof row !== 'object' || Array.isArray(row))) {
          throw new Error('Invalid statistics response');
        }
        if (!cancelled) setResource({ key: requestKey, status: 'ready', rows: payload.rows, error: '' });
      } catch (error) {
        if (cancelled) return;
        const message = error?.status === 401 || error?.code === 'AUTH_UNAUTHENTICATED'
          ? '登录已失效，请重新登录后重试。'
          : error?.status === 403 || error?.code === 'FORBIDDEN'
            ? '当前账号无权查看监考统计，请联系学校管理员。'
            : error?.code === 'STATS_UNAVAILABLE'
              ? '统计服务暂不可用，请稍后重试。'
              : '统计数据加载失败，请重试。';
        setResource({ key: requestKey, status: 'error', rows: [], error: message });
      }
    }
    loadStats();
    return () => { cancelled = true; };
  }, [dim, metric, reload]);

  // 请求条件变化的首帧就隐藏旧数据，避免旧维度的记录被显示或导出。
  const status = resource.key === requestKey ? resource.status : 'loading';
  const data = status === 'ready' ? resource.rows : [];

  // ── 真实的搜索/筛选/排序状态（配合下方 FilterBar 的接线）──
  const [q, setQ] = React.useState('');
  const [gradeF, setGradeF] = React.useState('全部');
  const [subjectF, setSubjectF] = React.useState('全部');
  const [sortDir, setSortDir] = React.useState('desc');
  const gradeOptions = ['全部', ...Array.from(new Set(data.map((r) => r.grade).filter((g) => g && g !== '-')))];
  const subjectOptions = ['全部', ...Array.from(new Set(data.flatMap((r) => String(r.subjects || '').split('/')).filter((s) => s && s !== '-')))];
  const filteredData = data
    .filter((r) => !q || String(r.name || '').includes(q) || String(r.code || '').toLowerCase().includes(q.toLowerCase()))
    .filter((r) => dim !== 'teacher' || gradeF === '全部' || r.grade === gradeF)
    .filter((r) => dim !== 'teacher' || subjectF === '全部' || String(r.subjects || '').split('/').includes(subjectF))
    .slice()
    .sort((a, b) => {
      const left = superviseNumber(a[metric]), right = superviseNumber(b[metric]);
      if (left == null) return right == null ? 0 : 1;
      if (right == null) return -1;
      return sortDir === 'asc' ? left - right : right - left;
    });

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

  const availableRows = filteredData.filter((row) => superviseNumber(row[metric]) != null);
  const metricsComplete = availableRows.length === filteredData.length;
  const total = metricsComplete ? filteredData.reduce((sum, row) => sum + superviseNumber(row[metric]), 0) : null;
  const maxRow = availableRows.reduce((best, row) => (
    !best || superviseNumber(row[metric]) > superviseNumber(best[metric]) ? row : best
  ), null);
  const chartMax = maxRow ? superviseNumber(maxRow[metric]) : null;
  const max = metricsComplete ? chartMax : null;
  const avg = metricsComplete && filteredData.length ? Math.round(total / filteredData.length * 100) / 100 : null;
  const canExport = status === 'ready' && filteredData.length > 0;
  const resetFilters = () => { setQ(''); setGradeF('全部'); setSubjectF('全部'); setSortDir('desc'); };

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
            <div className="font-medium" style={{ color: 'var(--text-strong)' }}>{r.name || '名称未提供'}</div>
            <div className="text-[11px] mt-0.5" style={{ color: 'var(--text-soft)' }}>{r.sub}</div>
          </div>
        </div>
      ),
    },
    // 主指标进度条列：随所选维度(监考次数/时长/津贴)联动，与上方图表/汇总卡口径一致
    // (此前恒用 r.count，切到时长/津贴时条形与图表矛盾 — 迭代8 修复)。
    { key: metric, label: (metrics.find((x) => x.id === metric) || {}).label || '监考次数', width: 180,
      render: (r) => {
        const v = superviseNumber(r[metric]);
        if (v == null) return <span title="该指标未提供">—</span>;
        return (
          <div className="flex items-center gap-2">
            <div className="flex-1 h-1.5 rounded-full" style={{ background: '#F1F5F9' }}>
              <div className="h-full rounded-full" style={{ width: `${chartMax ? (v / chartMax) * 100 : 0}%`, background: (metrics.find((x) => x.id === metric) || {}).color || '#4F46E5' }} />
            </div>
            <span className="num font-semibold w-14 text-right" style={{ color: 'var(--text-strong)' }}>
              {superviseValue(v, metric)}
            </span>
          </div>
        );
      },
    },
    { key: 'hours', label: '总时长 (h)', width: 110, align: 'right', mono: true,
      render: (r) => <span>{superviseValue(r.hours)}</span> },
    { key: 'avg', label: '场均时长', width: 100, align: 'right', mono: true,
      render: (r) => {
        const count = superviseNumber(r.count), hours = superviseNumber(r.hours);
        return <span>{count > 0 && hours != null ? `${(hours / count).toFixed(1)} h` : '—'}</span>;
      } },
    { key: 'allowance', label: '津贴', width: 110, align: 'right',
      render: (r) => <span className="num font-semibold" style={{ color: 'var(--text-strong)' }}>{superviseValue(r.allowance, 'allowance')}</span> },
    { key: 'ratio', label: '占比', width: 80, align: 'right',
      render: (r) => <span className="num text-[12px]" style={{ color: 'var(--text-soft)' }}>{total > 0 && superviseNumber(r[metric]) != null ? `${(superviseNumber(r[metric]) / total * 100).toFixed(1)}%` : '—'}</span> },
  ];

  const m = metrics.find((x) => x.id === metric);

  return (
    <PageShell
      crumbs={[orgName(), '数据与资源', '监考次数统计']}
      title="监考次数统计"
      subtitle="按本校最近一次成功排班统计工作量、时长与津贴；不是全学期累计，不随顶部学期筛选，也不代表已发布结果。"
      actions={
        <>
          <Button icon={IconCalendar} onClick={() => window.navigateTo && window.navigateTo('settings')}>学校设置</Button>
          <Button variant="primary" icon={IconUpload} disabled={!canExport} onClick={() => {
            if (!canExport) return;
            // 仅导出当前请求已加载的筛选结果；缺失数值留空，真实零保留为数值。
            const XLSX = window.XLSX;
            if (!XLSX) { window.ui.toast('导出组件未就绪', { kind: 'error', desc: '请刷新页面后重试' }); return; }
            const dimLabel = dim === 'teacher' ? '教师' : dim === 'subject' ? '学科' : '年级';
            const aoa = [
              [dimLabel, '类别', '监考次数(场)', '监考时长(小时)', '津贴金额(元)'],
              ...filteredData.map((r) => [r.name ?? null, r.sub ?? null, superviseNumber(r.count), superviseNumber(r.hours), superviseNumber(r.allowance)]),
            ];
            try {
              const ws = XLSX.utils.aoa_to_sheet(aoa);
              const wb = XLSX.utils.book_new();
              XLSX.utils.book_append_sheet(wb, ws, `监考统计-${dimLabel}`);
              XLSX.writeFile(wb, `监考统计_最近成功排班_${dimLabel}维度.xlsx`);
              window.ui.toast('已导出 Excel', { kind: 'success', desc: `按${dimLabel}维度 · ${filteredData.length} 行` });
            } catch (error) {
              window.ui.toast('导出失败', { kind: 'error', desc: '请稍后重试' });
            }
          }}>导出报表</Button>
        </>
      }
    >
      {/* Dimension tabs */}
      <div className="stats-control-bar flex flex-col lg:flex-row lg:items-center lg:justify-between gap-2">
        <div className="stats-control-group inline-flex p-1 rounded-xl bg-white"
          style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
          {dims.map((d) => {
            const active = dim === d.id;
            return (
              <button
                key={d.id}
                onClick={() => setDim(d.id)}
                className="h-9 px-4 rounded-lg inline-flex items-center gap-2 transition-all whitespace-nowrap shrink-0"
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
        <div className="stats-control-group inline-flex p-1 rounded-xl bg-white"
          style={{ border: '1px solid var(--border-soft)' }}>
          {metrics.map((m) => {
            const active = metric === m.id;
            return (
              <button
                key={m.id}
                onClick={() => setMetric(m.id)}
                className="h-8 min-w-[92px] px-3 rounded-md text-[12.5px] font-medium transition-all whitespace-nowrap shrink-0"
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

      {status === 'loading' && <div role="status" aria-live="polite" className="rounded-2xl p-8" style={{ border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}>正在加载监考统计…</div>}
      {status === 'error' && <div role="alert" className="rounded-2xl p-6 space-y-3" style={{ border: '1px solid var(--border-soft)', color: 'var(--text-strong)' }}>
        <p>{resource.error}</p>
        <Button onClick={() => setReload((value) => value + 1)}>重试</Button>
      </div>}
      {status === 'ready' && data.length === 0 && <div role="status" className="rounded-2xl p-8" style={{ border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}>
        <p className="font-medium" style={{ color: 'var(--text-strong)' }}>暂无监考统计</p>
        <p className="mt-2 text-sm">当前维度没有可统计的记录。保存排考结果后可重新加载。</p>
        <div className="mt-3"><Button onClick={() => setReload((value) => value + 1)}>重新加载</Button></div>
      </div>}

      {status === 'ready' && data.length > 0 && <>
      <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
        <MiniKPI label={`总${m.label}`} value={superviseValue(total, metric)} unit={total != null && metric !== 'allowance' ? m.unit : ''} accent={m.color} icon={IconChart} />
        <MiniKPI label="最高值" value={superviseValue(max, metric)} unit={max != null && metric !== 'allowance' ? m.unit : ''} accent="#9333EA" icon={IconArrowUp} sub={max != null ? maxRow?.name : '暂无完整数据'} />
        <MiniKPI label="平均值" value={superviseValue(avg, metric)} unit={avg != null && metric !== 'allowance' ? m.unit : ''} accent="#0EA5E9" icon={IconChart} />
        <MiniKPI label="样本数" value={filteredData.length} unit={dim === 'teacher' ? '人' : dim === 'subject' ? '科' : '组'} accent="#10B981" icon={IconUsers} />
      </div>
      {!metricsComplete && <p role="status" className="text-sm" style={{ color: 'var(--text-medium)' }}>部分记录未提供{m.label}，汇总与占比暂不可用；缺失值以“—”显示，导出时保留为空。</p>}

      {filteredData.length > 0 && <div className="bg-white rounded-2xl p-5"
        style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
        <SectionTitle
          title={`${dims.find(d => d.id === dim).label} · ${m.label}`}
          sub={`当前筛选 ${filteredData.length} 项 · 已按${m.label}${sortDir === 'asc' ? '正序' : '倒序'}排列`}
          right={
            <div className="inline-flex items-center gap-2 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>
              <span className="inline-flex items-center gap-1.5">
                <span className="w-2 h-2 rounded" style={{ background: m.color }} />
                {m.label}
              </span>
            </div>
          }
        />
        {availableRows.length > 0 ? <BarChartJS
          labels={filteredData.map((d) => d.name || '名称未提供')}
          datasets={[{ label: m.label, data: filteredData.map((d) => superviseNumber(d[metric])) }]}
          accent={m.color} height={280} unit={metric === 'allowance' ? ' 元' : metric === 'hours' ? ' h' : ' 场'}
        /> : <p role="status" className="py-8 text-center" style={{ color: 'var(--text-medium)' }}>当前指标暂无可用数值</p>}
      </div>}

      {/* 真实筛选（此前无任何处理器，搜索静默无效、胶囊只弹示例 toast）。
          年级/科目胶囊仅「按教师」维度渲染——按学科/按年级维度的行本身就是学科/年级，无此语义；
          选项从真实数据推导，不再写死「初一..高三/语数英」。 */}
      <FilterBar
        search="搜索..."
        searchValue={q}
        onSearch={setQ}
        filters={[
          ...(dim === 'teacher' && gradeOptions.length > 1 ? [{ label: '年级:', value: gradeF, options: gradeOptions, onChange: setGradeF }] : []),
          ...(dim === 'teacher' && subjectOptions.length > 1 ? [{ label: '科目:', value: subjectF, options: subjectOptions, onChange: setSubjectF }] : []),
          { label: '排序:', value: sortDir === 'desc' ? m.label + ' 倒序' : m.label + ' 正序',
            options: [m.label + ' 倒序', m.label + ' 正序'],
            onChange: (v) => setSortDir(v.endsWith('正序') ? 'asc' : 'desc') },
        ]}
        total={filteredData.length}
        onReset={resetFilters}
      />

      {filteredData.length > 0 ? <DataTable columns={columns} rows={filteredData} /> : <div role="status" className="rounded-2xl p-8 text-center" style={{ border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}>
        <p>没有符合筛选条件的监考记录</p>
        <div className="mt-3"><Button onClick={resetFilters}>清除筛选</Button></div>
      </div>}
      </>}
    </PageShell>
  );
}

window.StatsSupervisePage = StatsSupervisePage;
