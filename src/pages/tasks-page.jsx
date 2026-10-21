// 三表任务工作单：业务阶段与最近求解作业分开；不推断考试完成或通知送达。

const STATUS_MAP = {
  done:     { label: '已完成', color: 'emerald', dot: '#10B981' },
  running:  { label: '进行中', color: 'sky', dot: '#0EA5E9' },
  draft:    { label: '草稿',   color: 'slate', dot: '#94A3B8' },
  conflict: { label: '有冲突', color: 'rose', dot: '#EF4444' },
  failed:   { label: '失败', color: 'rose', dot: '#EF4444' },
  cancelled:{ label: '已取消', color: 'slate', dot: '#94A3B8' },
  unknown:  { label: '状态未提供', color: 'slate', dot: '#94A3B8' },
};

const TASK_STAGE_MAP = {
  draft: { label: '草稿', tone: 'neutral' },
  validated: { label: '已校验', tone: 'info' },
  preview: { label: '待复核', tone: 'warning' },
  scheduling: { label: '排班中', tone: 'info' },
  scheduled: { label: '已排班', tone: 'warning' },
  published: { label: '已发布', tone: 'success' },
  failed: { label: '排班失败', tone: 'danger' },
  infeasible: { label: '无可行解', tone: 'danger' },
  cancelled: { label: '已取消', tone: 'neutral' },
  local_preview: { label: '本地预览', tone: 'neutral' },
};

function taskStageInfo(row) {
  return TASK_STAGE_MAP[row.stage] || { label: row.stage ? `未知阶段（${row.stage}）` : '阶段未提供', tone: 'neutral' };
}

function taskJobInfo(row) {
  const labels = { pending: '等待求解', running: '正在求解', succeeded: '求解成功', failed: '求解失败', infeasible: '无可行解', cancelled: '求解已取消' };
  return {
    label: labels[row.jobStatus] || (row.jobStatus ? `未知求解状态（${row.jobStatus}）` : row.hasJob ? '求解状态未提供' : '尚无求解作业'),
    tone: ['failed', 'infeasible'].includes(row.jobStatus) ? 'danger' : 'neutral',
  };
}

function taskNextAction(row) {
  if (!row.__backend) return { label: '查看本地预览', route: 'task-detail' };
  if (['failed', 'infeasible'].includes(row.jobStatus) || ['failed', 'infeasible'].includes(row.stage)) return { label: '查看失败详情', route: 'task-detail' };
  if (['running', 'pending'].includes(row.jobStatus) || row.stage === 'scheduling') return { label: '查看排班进度', route: 'task-detail' };
  if (row.stage === 'published') return { label: '查看发布结果', route: 'task-detail' };
  if (row.stage === 'scheduled' && row.supervisors > 0) return { label: '复核安排', route: 'edit' };
  if (['draft', 'validated'].includes(row.stage)) return { label: '查看准备情况', route: 'task-detail' };
  return { label: '查看任务详情', route: 'task-detail' };
}

function formatTaskDateTime(value) {
  if (!value) return '未提供';
  const text = String(value);
  if (!Number.isFinite(Date.parse(text))) return '未提供';
  // Instant fields from the API include an offset. Do not guess the timezone of legacy strings.
  if (!/(?:Z|[+-]\d{2}:?\d{2})$/i.test(text)) return '时区未提供';
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(text));
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${values.year}/${values.month}/${values.day} ${values.hour}:${values.minute}`;
}

function filterTaskRows(rows, search, filters, now = Date.now()) {
  const query = String(search || '').trim().toLowerCase();
  return rows.filter(row => {
    if (query && !`${row.name} ${row.id} ${row.displayId || ''}`.toLowerCase().includes(query)) return false;
    if (filters.status === '求解异常') {
      if (!['failed', 'infeasible'].includes(row.jobStatus)) return false;
    } else if (filters.status === '未知阶段') {
      if (!row.stage || TASK_STAGE_MAP[row.stage]) return false;
    } else if (filters.status !== '全部' && taskStageInfo(row).label !== filters.status) return false;
    if (filters.type !== '全部' && row.type !== filters.type) return false;
    if (filters.time === '近 7 天' && !taskWithinDays(row.createdAt, 7, now)) return false;
    if (filters.time === '近 30 天' && !taskWithinDays(row.createdAt, 30, now)) return false;
    return true;
  });
}

function taskWithinDays(value, days, now = Date.now()) {
  const date = Date.parse(String(value || '').replace(' ', 'T'));
  const elapsed = now - date;
  return Number.isFinite(date) && elapsed >= 0 && elapsed <= days * 86400000;
}

function taskCsvCell(value) {
  let text = String(value ?? '');
  // Spreadsheet formula injection also permits whitespace/control prefixes.
  if (/^[\s\u0000-\u001f]*[=+@-]/.test(text) || /^[\t\r\n]/.test(text)) text = "'" + text;
  return '"' + text.replace(/"/g, '""') + '"';
}

function taskRowsToCsv(rows) {
  const records = [
    ['任务编号', '任务名称', '数据来源', '业务阶段', '求解状态', '发布状态', '考试场次', '监考人次', '创建时间（北京时间）', '更新时间（北京时间）'],
    ...rows.map((row) => [row.displayId || row.id, row.name, row.type, taskStageInfo(row).label, taskJobInfo(row).label, taskPublicationInfo(row).label,
      row.exams, row.supervisors, formatTaskDateTime(row.createdAt), formatTaskDateTime(row.updatedAt)]),
  ];
  return '\uFEFF' + records.map((row) => row.map(taskCsvCell).join(',')).join('\r\n');
}

function taskDownloadBlob(blob, filename) {
  const href = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = href;
  link.download = filename.replace(/[\\/:*?"<>|]/g, '_');
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(href), 30000);
}

function openSelectedTask(task, route = 'task-detail') {
  if (!task?.id) return false;
  try { localStorage.setItem('jkxt:taskDetailId', task.id); }
  catch {
    window.ui?.toast?.('无法打开任务', { kind: 'error', desc: '浏览器存储不可用，无法安全传递任务编号。请允许本站存储后重试。' });
    return false;
  }
  window.navigateTo(route);
  return true;
}

window.JKXTTaskTools = { csvCell: taskCsvCell, downloadBlob: taskDownloadBlob, open: openSelectedTask };

function TaskStatusPill({ status }) {
  const s = STATUS_MAP[status] || STATUS_MAP.unknown;
  return <Tag color={s.color} dot>{s.label}</Tag>;
}

function ProgressBar({ value, status }) {
  if (!Number.isFinite(value)) return <span style={{ color: 'var(--text-soft)' }}>未提供</span>;
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

function mapBackendTaskRow(task) {
  const job = task.latestJob || {};
  const stats = task.stats || {};
  const status = String(job.status || task.status || 'unknown');
  const uiStatus =
    ['scheduled', 'published', 'succeeded'].includes(status) ? 'done' :
    status === 'infeasible' ? 'conflict' :
    status === 'failed' ? 'failed' :
    status === 'cancelled' ? 'cancelled' :
    ['scheduling', 'running', 'pending'].includes(status) ? 'running' :
    ['draft', 'validated', 'preview'].includes(status) ? 'draft' : 'unknown';
  const created = task.createdAt || '';
  const days = task.startsOn && task.endsOn ? Math.round((Date.parse(task.endsOn) - Date.parse(task.startsOn)) / 86400000) + 1 : null;
  return {
    id: task.id,
    displayId: task.taskNo || String(task.id).slice(0, 8),
    name: task.name || '任务名称未提供',
    type: '服务器任务',
    typeColor: 'emerald',
    grade: null,
    days: Number.isFinite(days) && days > 0 ? days : null,
    exams: job.stats?.exams ?? stats.exams ?? null,
    supervisors: job.stats?.assignments ?? stats.assignments ?? null,
    status: uiStatus,
    stage: String(task.status || ''),
    jobStatus: String(job.status || ''),
    hasJob: Boolean(task.latestJob),
    jobError: job.errorMessage || job.errorCode || '',
    progress: Number.isFinite(job.progress) ? Math.min(100, Math.max(0, job.progress)) : null,
    owner: task.ownerName || '未提供',
    createdAt: created,
    updatedAt: task.updatedAt || '',
    publishedAt: task.publishedAt || '',
    uploads: task.uploads,
    latestJob: task.latestJob,
    __backend: true,
  };
}

function taskPublicationInfo(row) {
  if (!row.__backend) return { label: '未保存至服务器', tone: 'neutral' };
  if (row.stage === 'published') return { label: '已发布', tone: 'success' };
  if (TASK_STAGE_MAP[row.stage]) return { label: '未发布', tone: 'neutral' };
  return { label: '发布状态未提供', tone: 'neutral' };
}

function taskQueueStage(row) {
  if (['scheduled', 'published'].includes(row.stage)) return { label: '已排班', tone: 'info' };
  return taskStageInfo(row);
}

function taskNeedsAttention(row) {
  return ['draft', 'validated', 'preview', 'scheduled', 'failed', 'infeasible'].includes(row.stage)
    || ['failed', 'infeasible'].includes(row.jobStatus);
}

function taskRequireRecord(value, expectedId) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || typeof value.id !== 'string' || !value.id.trim()
    || (expectedId && value.id !== expectedId)) throw new Error('任务接口返回的标识不完整或不匹配，请重新读取。');
  return value;
}

function taskReadLocalPreviews() {
  try {
    const rows = window.JKXT?.listTasks?.();
    return { rows: Array.isArray(rows) ? rows.filter(row => row && typeof row.id === 'string' && row.id) : [], error: '' };
  } catch { return { rows: [], error: '无法读取浏览器本地预览；不会影响学校服务器任务。' }; }
}

const TASK_TEMPLATE_KINDS = [
  { kind: 'teachers', title: '教师表', note: '含工号与可用性', asset: 'teacher' },
  { kind: 'classrooms', title: '考场表', note: '含编号与容量', asset: 'room' },
  { kind: 'exams', title: '考试表', note: '含科目与时间', asset: 'calendar' },
];

function TaskQueueOnboarding() {
  const [busy, setBusy] = React.useState('');
  const [error, setError] = React.useState('');
  const downloadRef = React.useRef(false);
  const aliveRef = React.useRef(true);
  React.useEffect(() => { aliveRef.current = true; return () => { aliveRef.current = false; }; }, []);
  const downloadTemplate = async (item) => {
    if (downloadRef.current) return;
    downloadRef.current = true; setBusy(item.kind); setError('');
    try {
      if (typeof window.api?.url !== 'function') throw new Error('模板服务尚未就绪，请刷新后重试。');
      const response = await fetch(window.api.url('/import/templates/' + item.kind), { credentials: 'include' });
      if (!response.ok) throw new Error(`模板下载失败（HTTP ${response.status}）`);
      const blob = await response.blob();
      if (!blob.size || /json|text\//i.test(blob.type)) throw new Error('模板服务未返回有效的 Excel 文件。');
      if (!aliveRef.current) return;
      taskDownloadBlob(blob, `${item.title}-模板.xlsx`);
      window.ui?.toast?.('空白模板已生成', { kind: 'success', desc: `${item.title}模板已交给浏览器下载。` });
    } catch (failure) { if (aliveRef.current) setError(failure?.message || '模板下载失败，请重试。'); }
    finally { downloadRef.current = false; if (aliveRef.current) setBusy(''); }
  };
  return <>
    <section className="tasks-v5__empty" data-testid="tasks-v5-empty" aria-labelledby="tasks-v5-empty-title">
      <BlueprintAsset name="empty" size={240} className="tasks-v5__empty-art" />
      <h2 id="tasks-v5-empty-title">还没有考务任务</h2>
      <p>当前学校暂无服务器考务任务。从一次真实考试开始。</p>
      <div className="tasks-v5__input-cards">{TASK_TEMPLATE_KINDS.map(item => <div className="tasks-v5__input-card" key={item.kind}>
        <BlueprintAsset name={item.asset} size={44} /><div><strong>{item.title}</strong><p>{item.note}</p></div>
      </div>)}</div>
      <div className="tasks-v5__empty-actions"><Button variant="primary" onClick={() => window.navigateTo('quick')}>导入并创建任务</Button></div>
      <details className="tasks-v5__templates"><summary>下载三表模板</summary>
        <p className="tasks-v5__meta">分别下载后端提供的空白 Excel 模板，不导入示例数据。</p>
        <div className="tasks-v5__template-list">{TASK_TEMPLATE_KINDS.map(item => <Button key={item.kind} data-testid={`tasks-v5-template-${item.kind}`} disabled={Boolean(busy)} loading={busy === item.kind} onClick={() => downloadTemplate(item)}>下载{item.title}</Button>)}</div>
        {error && <p role="alert" data-testid="tasks-v5-template-error">{error}</p>}
      </details>
    </section>
    <section className="tasks-v5__preparation"><BlueprintAsset name="staff" size={44} /><div><h2>开始前请准备</h2><p>请使用本校真实数据；先查看导入校验结果，再开始排班。</p></div></section>
    <p className="tasks-v5__meta">不会因登录账号是新账号而隐藏学校已有记录，也不会向当前学校注入示例数据。</p>
  </>;
}

function TaskUploadReport({ report }) {
  const items = Array.isArray(report?.items) ? report.items.filter(item => item && typeof item === 'object') : null;
  const counts = report?.counts;
  if (!items && (!counts || typeof counts !== 'object')) return <p className="tasks-v5__meta">暂无校验报告</p>;
  const countText = (key) => Number.isFinite(counts?.[key]) && counts[key] >= 0 ? counts[key] : '未提供';
  return <details><summary>查看校验报告</summary><div className="tasks-v5__report">
    <p>通过 {countText('pass')} · 提示 {countText('suggest')} · 警告 {countText('warn')} · 错误 {countText('error')}</p>
    {items?.length ? <ul>{items.map((item, index) => <li key={item.id || index}>
      <strong>{({ error: '错误', warn: '警告', suggest: '建议', pass: '通过' })[item.severity] || '校验项'}</strong>
      {Number.isInteger(item.row) ? ` · 第 ${item.row} 行` : ''}{item.column ? ` · ${item.column}` : ''}
      <div>{item.problem || item.status || item.code || '说明未提供'}</div>{item.suggest && <div>建议：{item.suggest}</div>}
    </li>)}</ul> : <p>{items ? '报告未列出逐项问题；请结合上面的统计核对。' : '逐项校验信息未提供。'}</p>}
  </div></details>;
}

function TaskQueueDrawer({ taskId, onClose }) {
  const [state, setState] = React.useState({ status: 'loading', task: null, error: '' });
  const [reloadKey, setReloadKey] = React.useState(0);
  React.useEffect(() => {
    let alive = true;
    setState({ status: 'loading', task: null, error: '' });
    Promise.resolve().then(() => {
      if (typeof window.api?.get !== 'function') throw new Error('任务服务尚未就绪，请刷新后重试。');
      return window.api.get('/exam-tasks/' + encodeURIComponent(taskId));
    }).then(value => {
      const task = taskRequireRecord(value, taskId);
      if (task.uploads != null && (!Array.isArray(task.uploads) || task.uploads.some(upload => !upload || typeof upload !== 'object'))) throw new Error('导入文件信息格式不正确，请重新读取。');
      if (alive) setState({ status: 'ready', task, error: '' });
    }).catch(error => { if (alive) setState({ status: error?.status === 403 ? 'permission' : 'error', task: null, error: error?.message || '无法读取此任务。' }); });
    return () => { alive = false; };
  }, [taskId, reloadKey]);
  const task = state.task?.id === taskId ? state.task : null;
  const row = task ? mapBackendTaskRow(task) : null;
  const job = row ? taskJobInfo(row) : null;
  const failed = row && (['failed', 'infeasible'].includes(row.jobStatus) || ['failed', 'infeasible'].includes(row.stage));
  const publication = row ? taskPublicationInfo(row) : null;
  const fullDetail = () => { if (task && openSelectedTask({ id: task.id, __backend: true })) onClose(); };
  return <BlueprintDrawer open onClose={onClose} title="任务详情" description="核对当前任务的导入资料、最近一次求解与发布状态" className="tasks-v5 tasks-v5__drawer"
    footer={<div className="tasks-v5__drawer-actions">{task && <Button variant="primary" data-testid="tasks-v5-full-detail" onClick={fullDetail}>查看完整任务</Button>}<Button data-testid="tasks-v5-close-detail" onClick={onClose}>关闭</Button></div>}>
    <div data-testid="tasks-v5-drawer">
      {state.status === 'loading' && <p role="status">正在读取任务详情…</p>}
      {['error', 'permission'].includes(state.status) && <div role="alert" data-testid="tasks-v5-detail-error" className="tasks-v5__state"><h2>{state.status === 'permission' ? '无权查看此任务' : '任务详情读取失败'}</h2><p>{state.error}</p><Button onClick={() => setReloadKey(key => key + 1)}>重新读取详情</Button></div>}
      {task && row && <>
        <div className="tasks-v5__detail-head"><div><h2>{row.name}</h2><p className="tasks-v5__meta">任务编码 {row.displayId}</p></div><div className="tasks-v5__badges"><span className="tasks-v5__pill" data-tone={taskQueueStage(row).tone}>{taskQueueStage(row).label}</span><span className="tasks-v5__pill" data-tone={publication.tone}>{publication.label}</span></div></div>
        <section className="tasks-v5__detail-section"><h2>导入文件</h2>
          {Array.isArray(task.uploads) ? task.uploads.length ? <ul className="tasks-v5__uploads">{task.uploads.map((upload, index) => {
            const kind = TASK_TEMPLATE_KINDS.find(item => item.kind === upload.kind);
            return <li className="tasks-v5__upload" key={upload.id || index}><div className="tasks-v5__file-head"><BlueprintAsset name={kind?.asset || 'notice'} size={32} /><div><strong>{upload.originalName || '文件名未提供'}</strong><p className="tasks-v5__meta">{kind?.title || upload.kind || '文件类型未提供'}</p></div></div><TaskUploadReport report={upload.report} /></li>;
          })}</ul> : <p className="tasks-v5__meta">此任务尚未上传文件。</p> : <p className="tasks-v5__meta">导入文件信息未提供。</p>}
        </section>
        <section className="tasks-v5__detail-section"><h2>最近一次排班结果</h2><div className="tasks-v5__result" data-tone={failed ? 'danger' : 'neutral'}>
          <strong>{failed ? '最近一次排班未成功' : job.label}</strong>
          {failed && <p>原因：{row.jobError || '接口未提供具体失败原因'}</p>}
          {row.stage === 'published' && failed && <p>任务记录仍为已发布；本次求解失败不代表先前发布结果不存在，请打开完整任务核对。</p>}
          {!failed && row.hasJob && <p>任务阶段：{taskStageInfo(row).label}。求解状态、任务阶段和发布状态分别核对。</p>}
          {Array.isArray(task.latestJob?.conflictSet) && task.latestJob.conflictSet.length > 0 && <ul>{task.latestJob.conflictSet.map((item, index) => <li key={index}>{item?.message || item?.code || '冲突说明未提供'}</li>)}</ul>}
        </div></section>
        <section className="tasks-v5__detail-section"><h2>建议下一步</h2><ol className="tasks-v5__steps"><li>{failed ? '查看实际失败原因与导入校验报告。' : '核对当前任务的资料与最近求解状态。'}</li><li>{failed ? '在完整任务中核对安排；重新导入会创建新任务，不会覆盖本任务。' : '打开完整任务，按当前阶段继续核对安排或发布结果。'}</li></ol></section>
        <details className="tasks-v5__technical"><summary>技术信息</summary><dl><dt>任务 ID</dt><dd>{task.id}</dd><dt>业务阶段</dt><dd>{task.status || '未提供'}</dd><dt>作业 ID</dt><dd>{task.latestJob?.jobId || '未提供'}</dd><dt>运行 ID</dt><dd>{task.latestJob?.runId || '未提供'}</dd><dt>求解状态</dt><dd>{task.latestJob?.status || '未提供'}</dd><dt>最近更新</dt><dd>{formatTaskDateTime(task.updatedAt)}</dd><dt>发布时间</dt><dd>{formatTaskDateTime(task.publishedAt)}</dd></dl></details>
      </>}
    </div>
  </BlueprintDrawer>;
}

function TasksPage() {
  const [backendRows, setBackendRows] = React.useState([]);
  const [loadState, setLoadState] = React.useState({ status: 'loading', error: '' });
  const [reloadKey, setReloadKey] = React.useState(0);
  const [search, setSearch] = React.useState('');
  const [filters, setFilters] = React.useState({ status: '全部', type: '全部', time: '全部' });
  const [queue, setQueue] = React.useState('all');
  const [selectedId, setSelectedId] = React.useState(null);
  React.useEffect(() => {
    let alive = true;
    setLoadState({ status: 'loading', error: '' });
    Promise.resolve().then(() => {
      if (typeof window.api?.get !== 'function') throw new Error('API 未就绪，请刷新页面后重试。');
      return window.api.get('/exam-tasks');
    }).then(rows => {
      if (!Array.isArray(rows)) throw new Error('任务接口返回格式不正确');
      const mapped = rows.map(row => mapBackendTaskRow(taskRequireRecord(row)));
      if (new Set(mapped.map(row => row.id)).size !== mapped.length) throw new Error('任务接口返回了重复标识，请重新读取。');
      if (alive) { setBackendRows(mapped); setLoadState({ status: 'ready', error: '' }); }
    }).catch(error => {
      if (alive) { setBackendRows([]); setLoadState({ status: error?.status === 403 ? 'permission' : 'error', error: error?.message || '无法连接任务服务' }); }
    });
    return () => { alive = false; };
  }, [reloadKey]);
  const isReady = loadState.status === 'ready';
  const local = taskReadLocalPreviews();
  const localRows = local.rows.filter(row => !backendRows.some(task => task.id === row.id));
  const filteredTasks = filterTaskRows(backendRows, search, filters).filter(row => queue !== 'attention' || taskNeedsAttention(row));
  const resetAll = () => { setSearch(''); setFilters({ status: '全部', type: '全部', time: '全部' }); setQueue('all'); };
  const refresh = () => { setSelectedId(null); setReloadKey(key => key + 1); };
  const exportTasks = () => {
    if (!isReady || !filteredTasks.length) return;
    taskDownloadBlob(new Blob([taskRowsToCsv(filteredTasks)], { type: 'text/csv;charset=utf-8' }), '考务任务-当前筛选.csv');
    window.ui?.toast?.('已生成任务清单', { kind: 'success', desc: `已交给浏览器下载，共 ${filteredTasks.length} 条服务器任务。` });
  };
  const nextAction = row => {
    const next = taskNextAction(row);
    if (next.route === 'edit') openSelectedTask(row, 'edit');
    else setSelectedId(row.id);
  };
  return <div className="blueprint-v5 tasks-v5"><PageShell routeId="tasks" crumbs={[orgName(), '三表排班', '考务任务']} title="考务任务"
    subtitle={isReady && !backendRows.length ? '从一次真实考试开始' : '三表任务：导入、排班、核对与发布'}
    badge={isReady ? `${backendRows.length} 个任务` : undefined}
    actions={<><Button data-testid="tasks-v5-refresh" loading={loadState.status === 'loading'} onClick={refresh}>刷新</Button>{isReady && backendRows.length > 0 && <><Button variant="primary" icon={IconPlus} onClick={() => window.navigateTo('quick')}>导入并创建任务</Button><Button data-testid="tasks-v5-export" disabled={!filteredTasks.length} onClick={exportTasks}>导出当前清单</Button></>}</>}>
    {loadState.status === 'loading' && <div role="status" data-testid="tasks-loading" className="tasks-v5__state">正在读取当前学校的任务列表…</div>}
    {['error', 'permission'].includes(loadState.status) && <div role="alert" data-testid="tasks-load-error" className="tasks-v5__state"><h2>{loadState.status === 'permission' ? '无权读取学校任务' : '任务读取失败'}</h2><p>{loadState.error}</p><p className="tasks-v5__meta">目前无法判断学校是否有任务，不会展示本地预览作为服务器结果。</p><Button onClick={refresh}>重新读取</Button></div>}
    {isReady && <>
      {backendRows.length === 0 ? <TaskQueueOnboarding /> : <>
        <section className="tasks-v5__queue" data-testid="tasks-v5-queue" aria-label="当前学校三表任务清单">
          <div className="tasks-v5__tabs" role="group" aria-label="任务范围"><button type="button" className="tasks-v5__tab" aria-pressed={queue === 'all'} onClick={() => setQueue('all')}>全部任务</button><button type="button" className="tasks-v5__tab" data-testid="tasks-v5-attention" aria-pressed={queue === 'attention'} onClick={() => setQueue('attention')}>需要处理</button></div>
          <div className="tasks-v5__filters"><label className="tasks-v5__field"><span>任务名称或编号</span><input data-testid="tasks-v5-search" type="search" value={search} placeholder="搜索任务名称或编号" onChange={event => setSearch(event.target.value)} /></label><label className="tasks-v5__field"><span>业务阶段</span><select data-testid="tasks-v5-status" value={filters.status} onChange={event => setFilters(previous => ({ ...previous, status: event.target.value }))}>{['全部', '草稿', '已校验', '待复核', '排班中', '已排班', '已发布', '排班失败', '无可行解', '已取消', '求解异常', '阶段未提供', '未知阶段'].map(value => <option key={value}>{value}</option>)}</select></label><Button data-testid="tasks-v5-reset" onClick={resetAll}>重置筛选</Button></div>
          {queue === 'attention' && <p className="tasks-v5__notice">需要处理：草稿、待复核、已排班未发布及求解异常；不代表已指派给个人。</p>}
          {filteredTasks.length ? <table className="tasks-v5__table"><caption className="sr-only">服务器任务的业务排班阶段、最近求解作业与发布状态</caption><thead><tr><th scope="col">任务</th><th scope="col">排班状态</th><th scope="col">发布状态</th><th scope="col">最近更新</th><th scope="col">下一步</th></tr></thead><tbody>{filteredTasks.map(row => {
            const stage = taskQueueStage(row), publication = taskPublicationInfo(row), job = taskJobInfo(row), next = taskNextAction(row), updated = formatTaskDateTime(row.updatedAt);
            const actionLabel = ['failed', 'infeasible'].includes(row.jobStatus) || ['failed', 'infeasible'].includes(row.stage) ? '查看原因' : next.route === 'edit' ? '核对安排' : row.stage === 'published' ? '查看安排' : next.label;
            return <tr key={row.id}><td data-label="任务"><button type="button" className="tasks-v5__name" data-testid={`tasks-v5-detail-${row.id}`} onClick={() => setSelectedId(row.id)}>{row.name}</button><div className="tasks-v5__meta">{row.displayId || row.id}</div></td><td data-label="排班状态"><span className="tasks-v5__pill" data-tone={stage.tone}>{stage.label}</span><div className="tasks-v5__job" data-tone={job.tone}>{job.label}</div></td><td data-label="发布状态"><span className="tasks-v5__pill" data-tone={publication.tone}>{publication.label}</span></td><td data-label="最近更新"><time dateTime={updated.includes('/') ? row.updatedAt : undefined}>{updated}</time></td><td data-label="下一步"><Button data-task-action={row.id} onClick={() => nextAction(row)}>{actionLabel}</Button></td></tr>;
          })}</tbody></table> : <div className="tasks-v5__state" data-testid="tasks-v5-filter-empty"><h2>没有符合条件的考务任务</h2><p>请调整任务名称、业务阶段或范围；学校已有记录不会因筛选而被清空。</p><Button onClick={resetAll}>清空筛选</Button></div>}
          <div className="tasks-v5__count"><span>当前筛选 {filteredTasks.length} 条，共 {backendRows.length} 条服务器任务</span><span>时间均为北京时间 · 不随顶部学期筛选</span></div>
        </section>
        <p className="tasks-v5__notice">考试计划与三表任务是不同流程；请核对当前任务来源。已排班不代表已发布，已发布不代表教师已确认或考试结束。</p>
      </>}
      {(localRows.length > 0 || local.error) && <details className="tasks-v5__local" data-testid="tasks-v5-local"><summary>浏览器本地预览（独立于学校任务）{localRows.length ? ` · ${localRows.length} 条` : ''}</summary><p className="tasks-v5__meta">可能来自本机其他会话，未保存为当前学校服务器任务；不计入上方队列、数量或导出清单。</p>{local.error && <p role="status">{local.error}</p>}<ul>{localRows.map(row => <li key={row.id}><div><strong>{row.name || '预览名称未提供'}</strong><p className="tasks-v5__meta">{row.source === 'upload' ? '本地上传预览' : '本地示例预览'}</p></div><Button onClick={() => openSelectedTask({ id: row.id })}>查看本地预览</Button></li>)}</ul></details>}
    </>}
  </PageShell>{selectedId && isReady && <TaskQueueDrawer key={selectedId} taskId={selectedId} onClose={() => setSelectedId(null)} />}</div>;
}

window.TasksPage = TasksPage;
