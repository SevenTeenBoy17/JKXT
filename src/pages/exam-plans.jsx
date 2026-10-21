// Versioned exam-plan work surface. Every visible value comes from a live API response.

const PLAN_STATUS = {
  DRAFT: ['草稿', 'slate'], VALIDATING: ['校验中', 'blue'], READY: ['可求解', 'green'],
  SOLVING: ['求解中', 'blue'], REVIEW_REQUIRED: ['待审查', 'amber'], PENDING_APPROVAL: ['待审批', 'amber'],
  APPROVED: ['已批准', 'green'], PUBLISHED: ['已发布', 'green'], EXECUTING: ['执行中', 'blue'],
  CLOSED: ['已关闭', 'slate'], ARCHIVED: ['已归档', 'slate'], INVALID: ['校验失败', 'red'],
  INFEASIBLE: ['不可行', 'red'], FAILED: ['失败', 'red'], CANCELLED: ['已取消', 'slate'],
  DEADLINE_EXCEEDED: ['已超时', 'red'],
  UNRESOLVED: ['搜索未完成', 'amber'],
  WORKER_LOST: ['任务已中断', 'amber'],
};

const PLAN_TABS = [
  { id: 'sessions', label: '场次与时间表' },
  { id: 'rooms-seats', label: '考场座位' },
  { id: 'invigilation', label: '监考安排' },
  { id: 'conflicts', label: '冲突审查' },
  { id: 'publish', label: '发布记录' },
];

const JOINT_INPUT_RESOURCES = [
  { id: 'demands', label: '考试需求', group: '考试', description: '课程、科目、人数及考试属性' },
  { id: 'demand-classes', label: '需求班级', group: '考试', description: '考试需求与教学班关系' },
  { id: 'demand-windows', label: '考试时间窗', group: '考试', description: '允许或锁定的日期与时段' },
  { id: 'candidates', label: '考生', group: '考生', description: '考生主数据与组织归属' },
  { id: 'enrollments', label: '考试报名', group: '考生', description: '考生与考试需求报名关系' },
  { id: 'candidate-windows', label: '考生可用时间', group: '考生', description: '个体不可用与时间限制' },
  { id: 'special-requirements', label: '特殊安排', group: '考生', description: '无障碍、设备与独立考场需求' },
  { id: 'room-pool', label: '考场资源池', group: '空间', description: '容量、校区与房间特征' },
  { id: 'room-windows', label: '考场停用时间', group: '空间', description: '维护、占用与不可用区间' },
  { id: 'seat-topology', label: '座位拓扑', group: '空间', description: '座位编号、区域与可用状态' },
  { id: 'teacher-pool', label: '监考人员池', group: '人员', description: '资格、负荷、校区与可用时间' },
  { id: 'avoidances', label: '回避与限制', group: '人员', description: '任课、班主任及人工回避关系' },
  { id: 'campus-travel', label: '校区转场', group: '规则', description: '跨校区最短通行时间' },
];

function parseCsvCollection(source) {
  const rows = []; let row = []; let cell = ''; let quoted = false;
  const text = String(source || '').replace(/^\uFEFF/, '');
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted && char === '"' && text[index + 1] === '"') { cell += '"'; index += 1; }
    else if (char === '"') quoted = !quoted;
    else if (!quoted && char === ',') { row.push(cell); cell = ''; }
    else if (!quoted && (char === '\n' || char === '\r')) {
      if (char === '\r' && text[index + 1] === '\n') index += 1;
      row.push(cell); if (row.some((value) => value.trim())) rows.push(row); row = []; cell = '';
    } else cell += char;
  }
  if (quoted) throw new Error('CSV 存在未闭合的引号');
  row.push(cell); if (row.some((value) => value.trim())) rows.push(row);
  if (rows.length < 2) throw new Error('CSV 必须包含表头和至少一行数据');
  const headers = rows[0].map((value) => value.trim());
  if (headers.some((value) => !value)) throw new Error('CSV 表头不能为空');
  if (new Set(headers).size !== headers.length) throw new Error('CSV 表头不能重复');
  const convert = (value) => {
    const trimmed = value.trim();
    if (trimmed === '') return null;
    if (/^(true|false)$/i.test(trimmed)) return trimmed.toLowerCase() === 'true';
    if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(trimmed)) return Number(trimmed);
    if (/^[\[{]/.test(trimmed)) { try { return JSON.parse(trimmed); } catch (_) { return value; } }
    return value;
  };
  return rows.slice(1).map((values, index) => {
    if (values.length !== headers.length) throw new Error(`CSV 第 ${index + 2} 行列数与表头不一致`);
    return Object.fromEntries(headers.map((header, column) => [header, convert(values[column])]));
  });
}

function previewCollection(text, format) {
  if (!String(text || '').trim()) return { items: [], fields: [], errors: [] };
  try {
    const parsed = format === 'csv' ? parseCsvCollection(text) : JSON.parse(text);
    const items = format === 'csv' ? parsed : (Array.isArray(parsed) ? parsed : parsed?.items);
    if (!Array.isArray(items)) throw new Error('JSON 必须是数组或包含 items 数组的对象');
    if (items.some((item) => !item || Array.isArray(item) || typeof item !== 'object')) throw new Error('集合中的每一项都必须是对象');
    return { items, fields: [...new Set(items.flatMap((item) => Object.keys(item)))], errors: [] };
  } catch (error) { return { items: [], fields: [], errors: [error?.message || '无法解析导入内容'] }; }
}

const PLAN_FLOW_ASSETS = [
  { key: 'exam-plan-calendar', label: '考试计划' },
  { key: 'exam-room-building', label: '考场座位' },
  { key: 'invigilator-badge', label: '监考安排' },
  { key: 'emergency-substitution', label: '应急替补' },
];

function listOf(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.content)) return payload.content;
  if (Array.isArray(payload?.items)) return payload.items;
  if (Array.isArray(payload?.data)) return payload.data;
  return [];
}

function valueOf(object, keys, fallback = '—') {
  for (const key of keys) if (object?.[key] !== undefined && object?.[key] !== null && object?.[key] !== '') return object[key];
  return fallback;
}

function displayDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', { timeZone:'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle:'h23' }).format(date);
}

function StatusBadge({ status }) {
  const normalized = String(status || 'DRAFT').toUpperCase();
  const [label, tone] = PLAN_STATUS[normalized] || [normalized, 'slate'];
  const colors = {
    slate: ['var(--surface-muted)', 'var(--text-medium)'], blue: ['var(--info-soft)', 'var(--info)'], green: ['var(--success-soft)', 'var(--success)'],
    amber: ['var(--warning-soft)', 'var(--warning)'], red: ['var(--danger-soft)', 'var(--danger)'],
  };
  return <span className="plan-status-badge" data-tone={tone} style={{ background: `var(--plan-status-${tone}-bg, ${colors[tone][0]})`, color: `var(--plan-status-${tone}-fg, ${colors[tone][1]})` }}>{label}</span>;
}

function ResourceState({ state, label, onRetry }) {
  if (state.status === 'loading') {
    return (
      <div role="status" aria-label={`${label}加载中`} className="space-y-3 py-5" data-testid="resource-loading">
        {[1, 2, 3].map((item) => <div key={item} className="h-14 rounded-xl animate-pulse" style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)' }} />)}
      </div>
    );
  }
  if (state.status === 'permission') {
    return <div role="alert" className="ceramic-tile p-6" data-testid="resource-permission"><div className="font-semibold">无权查看{label}</div><div className="mt-1 text-[12.5px]" style={{ color: 'var(--text-soft)' }}>当前账号缺少所需权限。系统没有展示或缓存该资源内容。</div></div>;
  }
  if (state.status === 'error') {
    return (
      <div role="alert" className="ceramic-tile p-6" data-testid="resource-error">
        <div className="font-semibold" style={{ color: 'var(--danger)' }}>{label}加载失败</div>
        <div className="mt-1 text-[12.5px]" style={{ color: 'var(--text-medium)' }}>{state.error?.message || '服务暂时不可用'}</div>
        {state.error?.traceId && <div className="mt-2 font-mono text-[10.5px]" style={{ color: 'var(--text-soft)' }}>追踪号 {state.error.traceId}</div>}
        {onRetry && <Button className="mt-4" onClick={onRetry}>重新加载</Button>}
      </div>
    );
  }
  return null;
}

function useLiveResource(loader, deps = []) {
  const [state, setState] = React.useState({ status: 'loading', data: null, error: null, refreshedAt: null });
  const requestId = React.useRef(0);
  const load = React.useCallback(async () => {
    const id = ++requestId.current;
    setState((current) => ({ ...current, status: 'loading', error: null }));
    try {
      const data = await loader();
      if (id !== requestId.current) return;
      setState({ status: 'ready', data, error: null, refreshedAt: new Date() });
    } catch (error) {
      if (id !== requestId.current) return;
      setState({ status: error?.status === 403 ? 'permission' : 'error', data: null, error, refreshedAt: null });
    }
  }, deps);
  React.useEffect(() => { load(); return () => { requestId.current += 1; }; }, [load]);
  return [state, load];
}

function PlanMetric({ label, value, note }) {
  return (
    <div className="min-w-0 px-5 py-4 first:pl-0 last:pr-0">
      <div className="text-[11.5px] font-medium" style={{ color: 'var(--text-soft)' }}>{label}</div>
      <div className="mt-1 font-mono text-[24px] font-semibold tracking-tight" style={{ color: 'var(--text-strong)' }}>{value}</div>
      {note && <div className="mt-1 truncate text-[11px]" style={{ color: 'var(--text-soft)' }}>{note}</div>}
    </div>
  );
}

function PlanFlowStrip() {
  const steps = [{ name: 'calendar', label: '数据准备' }, { name: 'room', label: '场次与考场' }, { name: 'teacher', label: '监考安排' }, { name: 'notice', label: '审查与发布' }];
  return (
    <ol className="plans-v5-flow" aria-label="考试计划办理顺序，非当前进度" data-testid="plan-flow-strip">
      {steps.map((item, index) => <li key={item.name}><span className="plans-v5-flow-number">{index + 1}</span><BlueprintAsset name={item.name} size={32} /><span>{item.label}</span></li>)}
    </ol>
  );
}

function planNextStep(status) {
  const key = String(status || '').toUpperCase();
  if (['DRAFT', 'READY'].includes(key)) return { label: '完善数据', tab: 'inputs' };
  if (key === 'PENDING_APPROVAL') return { label: '查看审批', tab: 'conflicts' };
  if (['INVALID', 'INFEASIBLE', 'FAILED', 'REVIEW_REQUIRED'].includes(key)) return { label: '查看问题', tab: 'conflicts' };
  if (['PUBLISHED', 'EXECUTING', 'CLOSED', 'ARCHIVED'].includes(key)) return { label: '查看发布版本', tab: 'publish' };
  return { label: key === 'APPROVED' ? '审查并发布' : '打开计划', tab: 'conflicts' };
}

function planListFacts(plan) {
  // PlanView.version is an optimistic lock, never a business revision number.
  const revisionNo = valueOf(plan, ['activeRevisionNo', 'currentRevisionNo', 'revisionNo'], null);
  const dateValue = valueOf(plan, ['examDate', 'examStartDate', 'startDate'], null);
  const endValue = valueOf(plan, ['examEndDate', 'endDate'], null);
  const format = (value) => {
    if (!value) return '';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '日期格式待核对' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'numeric', day: 'numeric' }).format(date);
  };
  return {
    date: dateValue ? `${format(dateValue)}${endValue && endValue !== dateValue ? ` 至 ${format(endValue)}` : ''}` : '进入场次查看',
    revision: revisionNo !== null ? `第 ${revisionNo} 版` : '进入计划查看',
    next: planNextStep(valueOf(plan, ['status'], '')),
  };
}

function CreatePlanPanel({ onCancel, onCreated }) {
  const [name, setName] = React.useState('');
  const [batchCode, setBatchCode] = React.useState('');
  const [saving, setSaving] = React.useState(false);
  const [error, setError] = React.useState('');
  const submit = async (event) => {
    event.preventDefault();
    if (!name.trim()) { setError('请填写计划名称'); return; }
    setSaving(true); setError('');
    try {
      const key = window.crypto?.randomUUID?.() || `plan-${Date.now()}`;
      const result = await window.api.examPlans.create({ name: name.trim(), planNo: batchCode.trim() || undefined }, { headers: { 'Idempotency-Key': key } });
      onCreated(result);
    } catch (requestError) {
      setError(requestError?.message || '创建失败');
    } finally { setSaving(false); }
  };
  return (
    <form onSubmit={submit} className="ceramic-tile ceramic-tile--soft p-5" data-testid="create-plan-panel">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-end">
        <label className="flex-1 min-w-0">
          <span className="mb-1.5 block text-[12px] font-semibold">计划名称 *</span>
          <input value={name} onChange={(event) => setName(event.target.value)} className="h-11 w-full rounded-xl px-3 outline-none" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-strong)' }} placeholder="例如：2026 秋季期末考试" autoFocus />
        </label>
        <label className="flex-1 min-w-0">
          <span className="mb-1.5 block text-[12px] font-semibold">批次编码</span>
          <input value={batchCode} onChange={(event) => setBatchCode(event.target.value)} className="h-11 w-full rounded-xl px-3 outline-none font-mono" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-strong)' }} placeholder="可选，由学校自行定义" />
        </label>
        <div className="flex gap-2">
          <Button type="button" disabled={saving} onClick={onCancel}>取消</Button>
          <Button type="submit" variant="primary" loading={saving}>创建草稿</Button>
        </div>
      </div>
      {error && <div role="alert" className="mt-3 text-[12px]" style={{ color: 'var(--danger)' }}>{error}</div>}
    </form>
  );
}

function PlanList({ state, onRetry, onOpen, onRefresh }) {
  const [query, setQuery] = React.useState('');
  const [status, setStatus] = React.useState('');
  const [page, setPage] = React.useState(1);
  const rows = listOf(state.data);
  const filtered = [...rows].sort((a,b)=>(Date.parse(b.updatedAt || b.createdAt)||0)-(Date.parse(a.updatedAt || a.createdAt)||0)).filter((item) => (!status || item.status===status) && `${valueOf(item, ['name', 'title'], '')} ${valueOf(item, ['planNo', 'plan_no', 'batchCode', 'batch_code', 'code'], '')}`.toLowerCase().includes(query.trim().toLowerCase()));
  const pageSize = 12;
  const lastPage = Math.max(1, Math.ceil(filtered.length / pageSize));
  const currentPage = Math.min(page, lastPage);
  const visibleRows = filtered.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const clearFilters = () => { setQuery(''); setStatus(''); setPage(1); };
  if (state.status !== 'ready') return <ResourceState state={state} label="考试计划" onRetry={onRetry} />;
  return (
    <div className="plans-v5-list">
      <div className="plans-v5-toolbar">
        <label className="plans-v5-search">
          <span className="sr-only">搜索考试计划</span><IconSearch size={18} />
          <input value={query} onChange={(event) => {setQuery(event.target.value);setPage(1);}} placeholder="搜索计划名称或批次编码" data-testid="plan-search" />
        </label>
        <label className="plans-v5-filter"><span>状态</span><select value={status} onChange={event=>{setStatus(event.target.value);setPage(1);}} aria-label="筛选计划阶段"><option value="">全部</option>{[...new Set(rows.map(item=>item.status).filter(Boolean))].map(key=><option value={key} key={key}>{PLAN_STATUS[key]?.[0] || key}</option>)}</select></label>
        {(query || status) && <Button onClick={clearFilters}>清空筛选</Button>}
        <Button onClick={onRefresh}>刷新</Button>
      </div>
      {rows.length === 0 ? (
        <div className="ceramic-tile px-6 py-12 text-center" data-testid="plans-empty">
          <div className="font-semibold">尚未创建考试计划</div>
          <div className="mt-1 text-[12.5px]" style={{ color: 'var(--text-soft)' }}>创建草稿后，可导入场次、考场、考生与监考数据。</div>
        </div>
      ) : filtered.length === 0 ? (
        <div className="ceramic-tile px-6 py-10 text-center" data-testid="plans-filter-empty">
          <div className="font-semibold">没有符合筛选条件的计划</div>
          <button type="button" className="mt-2 min-h-11 text-[13px] underline underline-offset-2" style={{ color: 'var(--brand)' }} onClick={clearFilters}>清空筛选</button>
        </div>
      ) : (
        <div className="plans-v5-table-wrap">
          <table className="plans-v5-table"><caption className="sr-only">考试计划、日期、当前版本及下一步操作</caption><thead><tr><th scope="col">计划名称</th><th scope="col">考试日期</th><th scope="col">当前版本</th><th scope="col">状态</th><th scope="col">下一步</th></tr></thead><tbody>
          {visibleRows.map((plan) => {
            const id = valueOf(plan, ['id', 'planId', 'plan_id'], '');
            const facts = planListFacts(plan);
            return (
              <tr key={id} data-testid={`plan-row-${id}`}>
                <th scope="row"><button className="plans-v5-name" onClick={() => onOpen(plan, facts.next.tab)}>{valueOf(plan, ['name', 'title'], '未命名计划')}</button><span className="plans-v5-meta">{valueOf(plan, ['planNo', 'plan_no', 'batchCode', 'batch_code', 'code'], '未设置批次编码')}</span></th>
                <td data-label="考试日期">{facts.date}</td>
                <td data-label="当前版本">{facts.revision}</td>
                <td data-label="状态"><StatusBadge status={valueOf(plan, ['status', 'lifecycleStatus', 'lifecycle_status'], 'UNKNOWN')} /></td>
                <td data-label="下一步"><Button className="plans-v5-next" onClick={() => onOpen(plan, facts.next.tab)}>{facts.next.label}</Button></td>
              </tr>
            );
          })}</tbody></table>
          <div className="plans-v5-table-footer"><span role="status">共 {filtered.length} 个计划{filtered.length !== rows.length ? `（全部 ${rows.length} 个）` : ''}</span><span>已发布版本只读，调整需创建后继版本。</span>{lastPage > 1 && <nav aria-label="计划分页"><Button disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)}>上一页</Button><span>第 {currentPage} / {lastPage} 页</span><Button disabled={currentPage === lastPage} onClick={() => setPage(currentPage + 1)}>下一页</Button></nav>}</div>
        </div>
      )}
      {rows.length > 0 && <p className="plans-v5-meta">清单接口未提供的考试日期和版次，需进入计划核对；不会将系统锁版本显示为业务版次。</p>}
    </div>
  );
}

function ObjectRows({ rows, emptyLabel, revisionStatus = 'UNKNOWN', isActiveRevision = false }) {
  const published = ['PUBLISHED', 'EXECUTING', 'CLOSED'].includes(revisionStatus);
  const live = isActiveRevision && ['PUBLISHED', 'EXECUTING'].includes(revisionStatus);
  const currentLabel = live ? '当前执行：' : published || revisionStatus === 'ARCHIVED' ? '版本接岗记录：' : '未发布 · 版本候选：';
  if (!rows.length) return <div className="ceramic-tile py-10 text-center text-[12.5px]" style={{ color: 'var(--text-soft)' }}>{emptyLabel}</div>;
  return (
    <div className="divide-y overflow-hidden rounded-[18px]" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-soft)' }}>
      {rows.map((row, index) => row.teacher_id && row.duty_role ? (
        <div key={row.id || index} className="grid grid-cols-1 gap-3 px-5 py-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1.2fr)_minmax(0,1fr)] lg:items-center" data-testid="duty-current-projection">
          <div className="min-w-0">
            <div className="text-[13.5px] font-semibold break-words">{published ? '原发布：' : '版本安排：'}{row.teacher_name || '姓名未提供'}</div>
            <div className="mt-1 text-[12px]" style={{ color: 'var(--text-medium)' }}>{row.teacher_no || '工号未提供'} · {({CHIEF:'主监考',INVIGILATOR:'监考教师',ASSISTANT:'助理监考',ROAMING:'巡考',RESERVE:'备用教师'})[row.duty_role] || '岗位未识别'}</div>
          </div>
          <div className="min-w-0" data-testid="duty-current-owner">
            <div className="text-[13.5px] font-semibold break-words">{currentLabel}{row.current_teacher_id ? (row.current_teacher_name || '姓名未提供') : '未提供'}</div>
            <div className="mt-1 flex flex-wrap items-center gap-2 text-[12px]" style={{ color: 'var(--text-medium)' }}>
              <span>{row.current_teacher_id && row.current_status ? ({PENDING_CONFIRMATION:'待确认',ACCEPTED:'已确认',CHECKED_IN:'已签到',REJECTED:'已拒绝',DECLINED:'已拒绝',CANCELLED:'已取消',COMPLETED:'已完成',DRAFT:'草稿'})[row.current_status] || '执行状态未识别' : '执行状态未提供'}</span>
              {Number.isInteger(row.current_engagement_no) && row.current_engagement_no > 0 && <span>第 {row.current_engagement_no} 次替补</span>}
            </div>
          </div>
          <div className="text-[12px] leading-relaxed break-words" style={{ color: 'var(--text-medium)' }}>
            <div>{row.starts_at ? displayDate(row.starts_at) : '开始时间未提供'}</div>
            <div>{row.ends_at ? displayDate(row.ends_at) : '结束时间未提供'}</div>
          </div>
        </div>
      ) : (
        <div key={valueOf(row, ['id', 'code', 'external_id', 'seat_label'], index)} className="grid grid-cols-1 gap-2 px-5 py-4 lg:grid-cols-[minmax(0,1.6fr)_repeat(3,minmax(0,.8fr))] lg:items-center">
          <div className="min-w-0"><div className="truncate text-[13.5px] font-semibold">{valueOf(row, ['name', 'title', 'subjectName', 'subject_name', 'examName', 'exam_name', 'courseName', 'course_name', 'roomName', 'room_name', 'teacherName', 'teacher_name', 'seatLabel', 'seat_label', 'dutyRole', 'duty_role', 'code'], `记录 ${index + 1}`)}</div><div className="mt-1 truncate font-mono text-[10.5px]" style={{ color: 'var(--text-soft)' }}>{valueOf(row, ['code', 'id', 'externalId', 'external_id', 'sessionNo', 'session_no', 'roomCode', 'room_code', 'teacherNo', 'teacher_no', 'seatLabel', 'seat_label'], '—')}</div></div>
          <div className="text-[12px]" style={{ color: 'var(--text-medium)' }}>{valueOf(row, ['startsAt', 'starts_at', 'startTime', 'start_time', 'date', 'examDate', 'exam_date', 'buildingName', 'building_name', 'roomName', 'room_name', 'dutyRole', 'duty_role', 'role'], '—')}</div>
          <div className="text-[12px]" style={{ color: 'var(--text-medium)' }}>{valueOf(row, ['endsAt', 'ends_at', 'endTime', 'end_time', 'capacity', 'seatNumber', 'seat_number', 'seatLabel', 'seat_label', 'teacherName', 'teacher_name', 'assigneeName', 'assignee_name', 'roomName', 'room_name'], '—')}</div>
          <div className="text-[12px] lg:text-right" style={{ color: 'var(--text-soft)' }}>{valueOf(row, ['status', 'type', 'severity', 'constraintType', 'constraint_type', 'reason', 'message'], '—')}</div>
        </div>
      ))}
    </div>
  );
}

function JointInputWorkspace({ revision, onChanged }) {
  const revisionId = valueOf(revision, ['id', 'revisionId', 'revision_id'], '');
  const initialCursor = Number(valueOf(revision, ['revisionCursor', 'revision_cursor', 'version'], NaN));
  const revisionStatus = String(valueOf(revision, ['status'], 'DRAFT')).toUpperCase();
  const editable = ['DRAFT', 'INVALID'].includes(revisionStatus);
  const [resources, setResources] = React.useState({});
  const [cursor, setCursor] = React.useState(Number.isFinite(initialCursor) ? initialCursor : null);
  const [dialog, setDialog] = React.useState(null);
  const [format, setFormat] = React.useState('json');
  const [source, setSource] = React.useState('');
  const [fileName, setFileName] = React.useState('');
  const [saving, setSaving] = React.useState(false);
  const [commitError, setCommitError] = React.useState('');
  const [commitMessage, setCommitMessage] = React.useState('');
  const generationRef = React.useRef(0);
  const preview = React.useMemo(() => previewCollection(source, format), [source, format]);

  const loadResource = React.useCallback(async (resource, generation = generationRef.current) => {
    setResources((current) => ({ ...current, [resource.id]: { ...current[resource.id], status: 'loading', error: null } }));
    try {
      const snapshot = await window.api.examPlans.jointInputSnapshot(revisionId, resource.id);
      if (generation !== generationRef.current) return;
      const items = listOf(snapshot);
      const nextCursor = Number(valueOf(snapshot, ['revisionCursor', 'revision_cursor'], NaN));
      if (Number.isFinite(nextCursor)) setCursor((current) => current === null ? nextCursor : Math.max(current, nextCursor));
      setResources((current) => ({ ...current, [resource.id]: { status: 'ready', items, snapshot, error: null } }));
    } catch (error) {
      if (generation !== generationRef.current) return;
      setResources((current) => ({ ...current, [resource.id]: { status: error?.status === 403 ? 'permission' : 'error', items: [], error } }));
    }
  }, [revisionId]);

  const loadAll = React.useCallback(async () => {
    const generation = ++generationRef.current;
    await Promise.all(JOINT_INPUT_RESOURCES.map((resource) => loadResource(resource, generation)));
  }, [loadResource]);

  React.useEffect(() => { loadAll(); return () => { generationRef.current += 1; }; }, [loadAll]);
  React.useEffect(() => {
    if (!dialog) return undefined;
    const closeOnEscape = (event) => { if (event.key === 'Escape' && !saving) setDialog(null); };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [dialog, saving]);

  const openImport = (resource) => {
    if (!editable) return;
    setDialog(resource); setFormat('json'); setSource(''); setFileName(''); setCommitError(''); setCommitMessage('');
  };

  const readFile = async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setFileName(file.name);
    setFormat(file.name.toLowerCase().endsWith('.csv') ? 'csv' : 'json');
    try { setSource(await file.text()); setCommitError(''); }
    catch (error) { setCommitError(error?.message || '文件读取失败'); }
  };

  const commit = async () => {
    if (!editable || saving || !dialog || preview.errors.length || preview.items.length === 0 || !Number.isFinite(cursor)) return;
    setSaving(true); setCommitError(''); setCommitMessage('');
    try {
      const idempotencyKey = window.crypto?.randomUUID?.() || `joint-input-${dialog.id}-${Date.now()}`;
      const result = await window.api.examPlans.replaceJointInput(revisionId, dialog.id, preview.items, {
        headers: { 'If-Match': String(cursor), 'Idempotency-Key': idempotencyKey },
      });
      const nextCursor = Number(valueOf(result, ['revisionCursor', 'revision_cursor'], NaN));
      if (Number.isFinite(nextCursor)) setCursor(nextCursor);
      setCommitMessage(`已提交 ${Number(valueOf(result, ['acceptedCount', 'accepted_count'], preview.items.length))} 条记录；正在回读全部资源`);
      await loadAll();
      await onChanged?.(result);
      setDialog(null);
    } catch (error) {
      const code = String(valueOf(error?.response || error?.details || {}, ['code', 'errorCode', 'error_code'], '')).toUpperCase();
      if (error?.status === 409 || code.includes('STALE') || code.includes('REVISION_CURSOR')) {
        setCommitError('版本游标已过期：其他操作者已修改该版本。已重新读取服务器数据，请检查预览后再次提交。');
        await loadResource(dialog);
      } else setCommitError(error?.message || '导入失败，服务器未接受本次集合替换');
    } finally { setSaving(false); }
  };

  return (
    <section className="space-y-4" aria-labelledby="joint-input-title" data-testid="joint-input-workspace" data-responsive-contract="320 390 768 1024 1440">
      <style>{`
        .joint-input-grid{display:grid;grid-template-columns:minmax(0,1fr);gap:12px}.joint-control{min-height:44px}
        @media (min-width:390px){.joint-input-grid{gap:14px}.joint-import-dialog{margin:16px}}
        @media (min-width:768px){.joint-input-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
        @media (min-width:1024px){.joint-input-grid{grid-template-columns:repeat(3,minmax(0,1fr))}}
        @media (min-width:1440px){.joint-input-grid{grid-template-columns:repeat(4,minmax(0,1fr))}}
      `}</style>
      <div className="ceramic-tile ceramic-tile--soft flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
        <div><h3 id="joint-input-title" className="text-[15px] font-semibold">联合排程数据与约束</h3><p className="mt-1 text-[12px] leading-relaxed" style={{ color: 'var(--text-soft)' }}>13 类输入均从当前不可变版本接口回读；导入会以整集合替换，并使用版本游标防止覆盖他人修改。</p></div>
        <div className="flex shrink-0 items-center gap-2"><span className="rounded-lg px-2.5 py-1.5 font-mono text-[11px]" style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)' }}>cursor {Number.isFinite(cursor) ? cursor : '读取中'}</span><Button className="joint-control" onClick={loadAll}>刷新全部</Button></div>
      </div>
      {!editable && <div role="status" className="rounded-xl px-4 py-3 text-[12px]" style={{ background: 'var(--warning-soft)', color: 'var(--warning)' }}>当前 {PLAN_STATUS[revisionStatus]?.[0] || revisionStatus} 版本的输入集合只读。需修改输入时，请创建后继草稿并重新导入所需资源。</div>}
      <div className="joint-input-grid">
        {JOINT_INPUT_RESOURCES.map((resource) => {
          const state = resources[resource.id] || { status: 'loading', items: [] };
          return <article key={resource.id} className="ceramic-tile flex min-h-48 flex-col p-4" data-resource={resource.id}>
            <div className="flex items-start justify-between gap-3"><div><div className="text-[10.5px] font-semibold uppercase tracking-[.08em]" style={{ color: 'var(--brand)' }}>{resource.group}</div><h4 className="mt-1 text-[13.5px] font-semibold">{resource.label}</h4></div><code className="max-w-[55%] break-all text-right text-[9.5px]" style={{ color: 'var(--text-soft)' }}>{resource.id}</code></div>
            <p className="mt-2 min-h-9 text-[11.5px] leading-relaxed" style={{ color: 'var(--text-soft)' }}>{resource.description}</p>
            <div className="mt-auto pt-4">
              {state.status === 'loading' && <div role="status" data-testid={`joint-input-loading-${resource.id}`} className="h-11 animate-pulse rounded-xl" style={{ background: 'var(--surface-muted)' }}><span className="sr-only">{resource.label}加载中</span></div>}
              {state.status === 'permission' && <div role="alert" className="text-[11.5px]" style={{ color: 'var(--warning)' }}>当前账号无权读取此资源</div>}
              {state.status === 'error' && <div role="alert" data-testid={`joint-input-error-${resource.id}`}><div className="text-[11.5px]" style={{ color: 'var(--danger)' }}>{state.error?.message || '读取失败'}</div><button type="button" data-testid={`joint-input-retry-${resource.id}`} className="joint-control mt-2 rounded-lg px-3 text-[11.5px] font-semibold" style={{ color: 'var(--brand)', border: '1px solid var(--border-soft)' }} onClick={() => loadResource(resource)}>重试读取</button></div>}
              {state.status === 'ready' && <div><div className="flex items-end justify-between"><div><div className="font-mono text-[24px] font-semibold">{state.items.length}</div><div className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>服务器回读记录</div></div><Button className="joint-control" disabled={!editable || !Number.isFinite(cursor)} onClick={() => openImport(resource)}>导入替换</Button></div>{state.items.length === 0 && <div data-testid={`joint-input-empty-${resource.id}`} className="mt-3 rounded-lg px-3 py-2 text-[11px]" style={{ background: 'var(--surface-muted)', color: 'var(--text-soft)' }}>空集合：尚未导入该版本资源</div>}</div>}
            </div>
          </article>;
        })}
      </div>
      {dialog && <div className="fixed inset-0 z-[120] flex items-center justify-center overflow-y-auto p-2" style={{ background: 'color-mix(in srgb, var(--surface-sunken) 74%, transparent)', backdropFilter: 'blur(8px)' }}>
        <div role="dialog" aria-modal="true" aria-labelledby="joint-import-title" aria-describedby="joint-import-description" className="joint-import-dialog ceramic-tile my-auto w-full max-w-3xl overflow-hidden" style={{ maxHeight: 'calc(100dvh - 24px)' }}>
          <div className="flex items-start justify-between gap-4 border-b p-4 sm:p-5" style={{ borderColor: 'var(--border-soft)' }}><div><h3 id="joint-import-title" className="text-[16px] font-semibold">导入替换 · {dialog.label}</h3><p id="joint-import-description" className="mt-1 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>先在本地解析预览；确认后以完整集合提交。服务器成功响应前不会显示成功状态。</p></div><button type="button" aria-label="关闭导入窗口" className="joint-control min-w-11 rounded-xl text-[18px]" style={{ border: '1px solid var(--border-soft)' }} disabled={saving} onClick={() => setDialog(null)}>×</button></div>
          <div className="max-h-[calc(100dvh-190px)] space-y-4 overflow-y-auto p-4 sm:p-5">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-[160px_minmax(0,1fr)]"><label><span className="mb-1.5 block text-[11.5px] font-semibold">数据格式</span><select aria-label="导入数据格式" value={format} onChange={(event) => setFormat(event.target.value)} className="joint-control w-full rounded-xl px-3" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-strong)' }}><option value="json">JSON</option><option value="csv">CSV</option></select></label><label><span className="mb-1.5 block text-[11.5px] font-semibold">选择 JSON 或 CSV 文件</span><input aria-label="选择导入文件" type="file" accept=".json,.csv,application/json,text/csv" onChange={readFile} className="joint-control block w-full rounded-xl px-3 py-2 text-[11.5px]" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-strong)' }} /></label></div>
            {fileName && <div className="font-mono text-[10.5px]" style={{ color: 'var(--text-soft)' }}>已读取 {fileName}</div>}
            <label><span className="mb-1.5 block text-[11.5px] font-semibold">粘贴或检查原始内容</span><textarea autoFocus aria-label="导入原始内容" value={source} onChange={(event) => setSource(event.target.value)} rows="8" spellCheck="false" className="w-full rounded-xl p-3 font-mono text-[11px] outline-none" style={{ background: 'var(--surface-sunken)', border: '1px solid var(--border-strong)' }} placeholder={format === 'json' ? '[{"id":"..."}]' : 'id,name\n1,示例'} /></label>
            <section aria-label="导入预览" className="rounded-xl p-4" style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)' }}><div className="flex flex-wrap gap-5"><div><div className="font-mono text-[20px] font-semibold">{preview.items.length}</div><div className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>记录</div></div><div><div className="font-mono text-[20px] font-semibold">{preview.fields.length}</div><div className="text-[10.5px]" style={{ color: 'var(--text-soft)' }}>字段</div></div></div>{preview.errors.length > 0 && <ul role="alert" className="mt-3 space-y-1 text-[11.5px]" style={{ color: 'var(--danger)' }}>{preview.errors.map((error) => <li key={error}>{error}</li>)}</ul>}{preview.items.length > 0 && <pre className="mt-3 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-lg p-3 text-[10px]" style={{ background: 'var(--surface-raised)' }}>{JSON.stringify(preview.items.slice(0, 3), null, 2)}</pre>}{source.trim() && preview.items.length === 0 && preview.errors.length === 0 && <div className="mt-3 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>解析结果为空</div>}</section>
            {commitError && <div role="alert" data-testid="joint-input-stale-or-error" className="rounded-xl px-4 py-3 text-[11.5px]" style={{ background: 'var(--danger-soft)', color: 'var(--danger)' }}>{commitError}</div>}{commitMessage && <div role="status" className="rounded-xl px-4 py-3 text-[11.5px]" style={{ background: 'var(--success-soft)', color: 'var(--success)' }}>{commitMessage}</div>}
          </div>
          <div className="flex flex-col-reverse gap-2 border-t p-4 sm:flex-row sm:items-center sm:justify-end" style={{ borderColor: 'var(--border-soft)' }}><Button className="joint-control" disabled={saving} onClick={() => setDialog(null)}>取消</Button><Button className="joint-control" variant="primary" loading={saving} disabled={!editable || saving || preview.errors.length > 0 || preview.items.length === 0 || !Number.isFinite(cursor)} onClick={commit}>确认替换 {preview.items.length} 条记录</Button></div>
        </div>
      </div>}
    </section>
  );
}

function SolverDiagnostics({ diagnostics }) {
  const rows = listOf(diagnostics);
  if (!rows.length) return null;
  const groups = rows.reduce((result, diagnostic) => {
    const stage = String(valueOf(diagnostic, ['stage', 'planningStage', 'planning_stage'], 'GENERAL')).toUpperCase();
    (result[stage] ||= []).push(diagnostic); return result;
  }, {});
  return <div role="alert" className="mt-4 space-y-3" data-testid="solver-diagnostics"><div className="text-[12px] font-semibold" style={{ color: 'var(--danger)' }}>联合求解诊断（未返回部分成功）</div>{Object.entries(groups).map(([stage, issues]) => <section key={stage} className="rounded-xl px-4 py-3" style={{ background: 'var(--danger-soft)', border: '1px solid color-mix(in srgb, var(--danger) 25%, transparent)' }}><div className="font-mono text-[10.5px] font-semibold" style={{ color: 'var(--danger)' }}>{stage}</div><ul className="mt-2 space-y-3">{issues.map((issue, index) => { const affected = listOf(issue?.affectedResourceIds || issue?.affectedIds || issue?.affected_resources); const suggestions = listOf(issue?.suggestions || issue?.repairSuggestions || issue?.repair_suggestions); return <li key={valueOf(issue, ['code', 'id'], index)}><div className="text-[12px] font-semibold">{valueOf(issue, ['message', 'code'], '未命名冲突')}</div>{affected.length > 0 && <div className="mt-1 break-all font-mono text-[10px]" style={{ color: 'var(--text-medium)' }}>受影响：{affected.join('、')}</div>}{suggestions.length > 0 && <ul className="mt-1 list-disc pl-4 text-[11px]" style={{ color: 'var(--text-medium)' }}>{suggestions.map((suggestion) => <li key={String(suggestion)}>{typeof suggestion === 'string' ? suggestion : valueOf(suggestion, ['message', 'action'], JSON.stringify(suggestion))}</li>)}</ul>}</li>; })}</ul></section>)}</div>;
}

function PlanLifecycleActions({ planId, revision, planName, planNo, onChanged }) {
  const revisionId = valueOf(revision, ['id', 'revisionId', 'revision_id'], '');
  const status = String(valueOf(revision, ['status'], 'DRAFT')).toUpperCase();
  const cursor = Number(valueOf(revision, ['revisionCursor', 'revision_cursor', 'version'], 0));
  const [busy, setBusy] = React.useState('');
  const [error, setError] = React.useState('');
  const [message, setMessage] = React.useState('');
  const [job, setJob] = React.useState(null);
  const [validationIssues, setValidationIssues] = React.useState([]);
  const [solverDiagnostics, setSolverDiagnostics] = React.useState([]);
  const [cancelling, setCancelling] = React.useState(false);
  const [cancelRequested, setCancelRequested] = React.useState(false);
  const [approvalReason, setApprovalReason] = React.useState('');
  const [emergencyTeacher, setEmergencyTeacher] = React.useState('');
  const [publishOpen, setPublishOpen] = React.useState(false);
  const [publishNeedsRefresh, setPublishNeedsRefresh] = React.useState(false);
  const publishLock = React.useRef(false);
  const planningRunRef = React.useRef(0);
  const headers = { 'If-Match': String(cursor) };
  React.useEffect(() => () => { planningRunRef.current += 1; }, []);

  const execute = async (key, label, operation) => {
    if (busy) return;
    setBusy(key); setError(''); setMessage('');
    if (key === 'validate') { setValidationIssues([]); setSolverDiagnostics([]); }
    try {
      const result = await operation();
      setMessage(label);
      window.ui?.toast?.(label, { kind: 'success' });
      await onChanged?.(result);
      return result;
    } catch (requestError) {
      if (key === 'validate' && requestError?.status === 422) {
        const payload = requestError.response || requestError.details || {};
        const issues = Array.isArray(payload?.issues) ? payload.issues
          : Array.isArray(payload?.details?.issues) ? payload.details.issues : [];
        const hardCount = Number(payload?.hardViolationCount ?? payload?.details?.hardViolationCount ?? issues.length);
        const text = `校验未通过：发现 ${hardCount} 项硬约束冲突`;
        setValidationIssues(issues);
        setSolverDiagnostics(issues);
        setError(text);
        window.ui?.toast?.('版本校验未通过', { kind: 'error', desc: text });
        await onChanged?.(payload);
        return null;
      }
      const text = requestError?.message || '操作失败，请稍后重试';
      setError(text);
      window.ui?.toast?.('操作未完成', { kind: 'error', desc: text });
      return null;
    } finally { setBusy(''); }
  };

  const monitorPlanningJob = async (initialJob, runId) => {
    let current = initialJob;
    const terminal = new Set(['SUCCEEDED', 'INFEASIBLE', 'FAILED', 'CANCELLED', 'DEADLINE_EXCEEDED', 'INVALID']);
    for (let attempt = 0; attempt < 160 && !terminal.has(String(current?.status).toUpperCase()); attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 1000));
      if (planningRunRef.current !== runId) return;
      current = await window.api.examPlans.planningJob(valueOf(current, ['jobId', 'job_id', 'id'], ''));
      setJob(current);
      setSolverDiagnostics(listOf(current?.diagnostics));
    }
    if (planningRunRef.current !== runId) return;
    const finalStatus = String(current?.status || '').toUpperCase();
    if (finalStatus === 'CANCELLED') {
      setCancelRequested(false);
      setMessage('后端已确认取消求解，版本数据未被当作成功结果');
      await onChanged?.();
      return;
    }
    if (!terminal.has(finalStatus)) {
      setMessage('自动查询已暂停，任务仍在后台运行；请继续查询进度，确认最终状态后再操作版本');
      window.ui?.toast?.('自动查询已暂停', { kind: 'info', desc: '任务仍在后台运行' });
      return;
    }
    setCancelRequested(false);
    if (finalStatus !== 'SUCCEEDED') {
      const diagnostics = listOf(current?.diagnostics);
      setSolverDiagnostics(diagnostics);
      const diagnostic = diagnostics[0];
      const finalStage = String(current?.stage || '').toUpperCase();
      const unresolved = finalStage === 'UNRESOLVED' || current?.stats?.solverStatus === 'UNKNOWN';
      const interrupted = finalStage === 'WORKER_LOST';
      const terminalCopy = interrupted
        ? '后台任务已失去有效租约并安全终止；未自动重放，可创建新的求解任务重试'
        : unresolved
        ? '本次搜索未找到完整安排，尚未证实无解；可重试或审查其他安排'
        : finalStatus === 'DEADLINE_EXCEEDED' || finalStage === 'DEADLINE_EXCEEDED'
        ? '联合求解已达到截止时间，未把局部结果标记为成功'
        : `联合求解未成功：${finalStatus}`;
      setError(unresolved || interrupted ? terminalCopy : diagnostic?.message || terminalCopy);
      window.ui?.toast?.('联合求解未完成', { kind: 'error', desc: terminalCopy });
      await onChanged?.();
      return;
    }
    setMessage('考试时段、考场座位与监考岗位已完整生成');
    window.ui?.toast?.('联合排程已生成', { kind: 'success' });
    await onChanged?.();
  };

  const startPlanning = async () => {
    if (busy || !['DRAFT', 'INVALID', 'READY', 'FAILED', 'INFEASIBLE'].includes(status)) return;
    setBusy('planning'); setError(''); setMessage('');
    setValidationIssues([]); setSolverDiagnostics([]); setCancelRequested(false);
    const runId = ++planningRunRef.current;
    try {
      const key = window.crypto?.randomUUID?.() || `planning-${Date.now()}`;
      const current = await window.api.examPlans.startPlanning(planId, revisionId, {
        mode: 'JOINT', seed: 20260831, failOnPartialCoverage: true, timeoutSeconds: 120,
      }, { headers: { 'Idempotency-Key': key } });
      setJob(current);
      await monitorPlanningJob(current, runId);
    } catch (requestError) {
      const text = requestError?.message || '求解任务失败';
      setError(text);
      window.ui?.toast?.('求解未完成', { kind: 'error', desc: text });
    } finally { setBusy(''); }
  };

  const resumePlanning = async () => {
    if (busy || !job) return;
    setBusy('planning'); setError(''); setMessage('');
    const runId = ++planningRunRef.current;
    try {
      await monitorPlanningJob(job, runId);
    } catch (requestError) {
      const text = requestError?.message || '查询求解进度失败';
      setError(text);
      window.ui?.toast?.('进度查询未完成', { kind: 'error', desc: text });
    } finally { setBusy(''); }
  };

  const cancelPlanning = async () => {
    const jobId = valueOf(job, ['jobId', 'job_id', 'id'], '');
    if (!jobId) return;
    setCancelling(true);
    try {
      const cancelled = await window.api.examPlans.cancelPlanning(jobId);
      setJob(cancelled);
      setError('');
      const cancelledStatus = String(valueOf(cancelled, ['status'], '')).toUpperCase();
      if (cancelledStatus === 'CANCELLED') {
        planningRunRef.current += 1;
        setCancelRequested(false);
        setMessage('后端已确认取消求解，未将局部结果标记为成功');
        await onChanged?.();
      } else {
        setCancelRequested(true);
        setMessage('已提交取消请求，正在等待后端确认；在状态变为“已取消”前任务仍视为运行中');
      }
    } catch (requestError) {
      setCancelRequested(false);
      setError(requestError?.message || '取消失败');
    }
    finally { setCancelling(false); }
  };

  const createSuccessor = () => {
    const key = window.crypto?.randomUUID?.() || `revision-${Date.now()}`;
    return execute('successor', '已创建可编辑的后继版本，请重新导入所需资源', () => window.api.examPlans.createRevision(planId, {
      predecessorId: revisionId,
      title: `v${Number(valueOf(revision, ['revisionNo', 'revision_no'], 0)) + 1} 人工调整`,
      changeReason: ['FAILED', 'INFEASIBLE'].includes(status) ? '修复失败或不可行的排程' : '发布后调整',
      configuration: revision?.configuration || {},
    }, { headers: { 'Idempotency-Key': key } }));
  };

  const createEmergency = async () => {
    const teacherNo = emergencyTeacher.trim();
    if (!teacherNo) { setError('请填写需要替补的教师工号'); return; }
    const key = window.crypto?.randomUUID?.() || `emergency-${Date.now()}`;
    const result = await execute('emergency', '已创建病假替补后继版本', () => window.api.examPlans.emergencyChange(planId, revisionId, {
      type: 'TEACHER_SICK_LEAVE',
      payload: { teacherIds: [teacherNo], effectiveAt: new Date().toISOString() },
    }, { headers: { 'Idempotency-Key': key } }));
    if (result) setEmergencyTeacher('');
  };

  const decideApproval = async (decision) => {
    const reason = approvalReason.trim();
    if (!reason) { setError('请先填写审批意见'); return; }
    const approved = decision === 'approve';
    const confirmed = await window.ui?.confirm?.({
      title: approved ? '确认批准该版本？' : '确认驳回该版本？',
      desc: reason,
      confirmText: approved ? '确认批准' : '确认驳回',
      cancelText: '取消',
      danger: !approved,
    });
    if (!confirmed) return;
    const result = await execute(decision, approved ? '版本已批准' : '版本已驳回', () => (
      approved
        ? window.api.examPlans.approve(planId, revisionId, { reason }, { headers })
        : window.api.examPlans.reject(planId, revisionId, { reason }, { headers })
    ));
    if (result) setApprovalReason('');
  };

  const publishRevision = async () => {
    if (status !== 'APPROVED' || busy || publishLock.current || publishNeedsRefresh) return;
    publishLock.current = true;
    setBusy('publish'); setError(''); setMessage('');
    try {
      const result = await window.api.examPlans.publish(planId, revisionId, { headers });
      setPublishOpen(false);
      setMessage('版本已发布，请另行核对通知投递与教师回执。');
      await onChanged?.(result);
    } catch (requestError) {
      const uncertain = !requestError?.status || requestError.status >= 500 || [408, 409, 412].includes(requestError.status);
      setPublishNeedsRefresh(uncertain);
      setError(uncertain ? '发布结果或版本状态需要核对，请刷新后确认，勿重复提交。' : requestError?.message || '发布未完成，请核对权限与版本状态。');
    } finally { publishLock.current = false; setBusy(''); }
  };

  const jobStatus = String(valueOf(job, ['status'], '')).toUpperCase();
  const jobDisplayStatus = String(job?.stage || '').toUpperCase() === 'WORKER_LOST' ? 'WORKER_LOST'
    : String(job?.stage || '').toUpperCase() === 'UNRESOLVED' || job?.stats?.solverStatus === 'UNKNOWN'
    ? 'UNRESOLVED' : jobStatus;
  const activeJob = Boolean(job) && !['SUCCEEDED', 'INFEASIBLE', 'FAILED', 'CANCELLED', 'DEADLINE_EXCEEDED', 'INVALID'].includes(jobStatus);

  return (
    <section className="plans-v5-lifecycle ceramic-tile p-4 lg:p-5" aria-labelledby="plan-lifecycle-title" data-testid="plan-lifecycle-actions">
      <div className="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
        <div>
          <h3 id="plan-lifecycle-title" className="text-[13px] font-semibold">版本操作</h3>
          <p className="mt-1 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>当前阶段：{PLAN_STATUS[status]?.[0] || status}。发布后不可直接修改。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {['DRAFT', 'INVALID', 'FAILED', 'INFEASIBLE'].includes(status) && <Button loading={busy === 'validate'} disabled={Boolean(busy)} onClick={() => execute('validate', '版本校验已完成', () => window.api.examPlans.validate(planId, revisionId, { headers }))}>校验版本</Button>}
          {['DRAFT', 'INVALID', 'READY', 'FAILED', 'INFEASIBLE'].includes(status) && <Button variant={status === 'READY' ? 'secondary' : 'primary'} loading={busy === 'planning'} disabled={Boolean(busy)} onClick={startPlanning}>生成联合排程</Button>}
          {['READY', 'REVIEW_REQUIRED'].includes(status) && <Button variant="primary" loading={busy === 'submit'} disabled={Boolean(busy)} onClick={() => execute('submit', '版本已提交审批', () => window.api.examPlans.submitApproval(planId, revisionId, { headers }))}>提交审批</Button>}
          {status === 'PENDING_APPROVAL' && <><Button variant="primary" loading={busy === 'approve'} disabled={Boolean(busy)} onClick={() => decideApproval('approve')}>批准</Button><Button variant="danger" loading={busy === 'reject'} disabled={Boolean(busy)} onClick={() => decideApproval('reject')}>驳回</Button></>}
          {status === 'APPROVED' && <Button variant="primary" loading={busy === 'publish'} disabled={Boolean(busy) || publishNeedsRefresh} onClick={() => {setError('');setPublishOpen(true);}}>发布此版本</Button>}
          {publishNeedsRefresh && <Button disabled={Boolean(busy)} onClick={async () => { await onChanged?.();setPublishNeedsRefresh(false);setError(''); }}>刷新并核对状态</Button>}
          {['FAILED', 'INFEASIBLE', 'PUBLISHED', 'EXECUTING', 'CLOSED'].includes(status) && <Button loading={busy === 'successor'} disabled={Boolean(busy)} onClick={createSuccessor}>创建后继版本</Button>}
          {activeJob && busy !== 'planning' && <Button disabled={Boolean(busy)} onClick={resumePlanning}>继续查询进度</Button>}
          {activeJob && <Button variant="danger" loading={cancelling} disabled={cancelling || cancelRequested} onClick={cancelPlanning}>{cancelRequested ? '等待取消确认' : '取消求解'}</Button>}
        </div>
      </div>
      {['DRAFT', 'INVALID'].includes(status) && <p className="mt-3 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>完成输入后可直接生成联合排程；“校验版本”检查的是已生成的场次、座位与监考安排。</p>}
      {['FAILED', 'INFEASIBLE'].includes(status) && <p className="mt-3 text-[11.5px]" style={{ color: 'var(--text-soft)' }}>可重试当前输入；如需修复输入，请创建后继版本并重新导入所需资源，原版本保留供核查。</p>}
      {status === 'PENDING_APPROVAL' && <label className="mt-4 block"><span className="mb-1.5 block text-[11.5px] font-semibold">审批意见</span><textarea value={approvalReason} onChange={(event) => setApprovalReason(event.target.value)} rows="2" className="w-full rounded-xl px-3 py-2 text-[12.5px] outline-none" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-strong)' }} placeholder="填写批准依据或驳回原因" /></label>}
      {job && activeJob && <div role="status" className="mt-4 rounded-xl px-4 py-3 text-[12px]" style={{ background: 'var(--surface-muted)' }}>{cancelRequested ? '等待后端确认取消' : `求解阶段 ${valueOf(job, ['stage'], 'QUEUED')} · ${Number(valueOf(job, ['progress'], 0))}%`}</div>}
      {job && !activeJob && <div role="status" data-testid={`solver-terminal-${jobStatus.toLowerCase()}`} className="mt-4 flex items-center justify-between gap-3 rounded-xl px-4 py-3 text-[12px]" style={{ background: jobStatus === 'SUCCEEDED' ? 'var(--success-soft)' : ['CANCELLED', 'UNRESOLVED'].includes(jobDisplayStatus) ? 'var(--surface-muted)' : 'var(--danger-soft)' }}><span>联合求解终态</span><StatusBadge status={jobDisplayStatus} /></div>}
      <SolverDiagnostics diagnostics={solverDiagnostics} />
      {validationIssues.length > 0 && <div role="alert" className="mt-4 rounded-xl px-4 py-3 text-[12px]" style={{ background: 'var(--danger-soft)', color: 'var(--danger)', border: '1px solid color-mix(in srgb, var(--danger) 28%, transparent)' }}><div className="font-semibold">硬约束冲突</div><ul className="mt-2 space-y-1">{validationIssues.slice(0, 8).map((issue, index) => <li key={valueOf(issue, ['id', 'resourceId', 'resource_id'], index)}>• {valueOf(issue, ['message', 'code'], '未命名冲突')}</li>)}</ul>{validationIssues.length > 8 && <div className="mt-2">另有 {validationIssues.length - 8} 项，请在“冲突审查”中查看全部内容。</div>}</div>}
      {['PUBLISHED', 'EXECUTING'].includes(status) && <div className="mt-4 flex flex-col gap-2 border-t pt-4 sm:flex-row sm:items-end" style={{ borderColor: 'var(--border-soft)' }}><label className="min-w-0 flex-1"><span className="mb-1.5 block text-[11.5px] font-semibold">临时病假教师工号</span><input value={emergencyTeacher} onChange={(event) => setEmergencyTeacher(event.target.value)} className="h-11 w-full rounded-xl px-3 font-mono outline-none" style={{ background: 'var(--surface-raised)', border: '1px solid var(--border-strong)' }} placeholder="例如 T0062" /></label><Button loading={busy === 'emergency'} disabled={Boolean(busy)} onClick={createEmergency}>创建替补变更</Button></div>}
      <div aria-live="polite" className="mt-3 min-h-4 text-[11.5px]" style={{ color: error ? 'var(--danger)' : 'var(--success)' }}>{error || message}</div>
      {publishOpen && <BlueprintDialog title="确认发布此版本" busy={busy === 'publish'} onClose={() => {if (!publishLock.current) setPublishOpen(false);}} className="plans-v5-publish" description="发布后为只读版本，后续调整需要创建后继版本。" footer={<><Button disabled={busy === 'publish'} onClick={() => setPublishOpen(false)}>取消</Button><Button variant="primary" loading={busy === 'publish'} disabled={publishNeedsRefresh || status !== 'APPROVED'} onClick={publishRevision}>确认发布</Button></>}>
        <div className="plans-v5-publish-identity"><BlueprintAsset name="calendar" size={44} /><div><strong>{planName || '当前考试计划'}</strong><p>{planNo || '未设置批次编码'} · 第 {valueOf(revision, ['revisionNo', 'revision_no'], '未提供')} 版 · 已批准</p></div></div>
        <dl className="plans-v5-confirm-facts"><div><dt>发布对象</dt><dd>当前选定版本</dd></div><div><dt>发布后状态</dt><dd>已发布，只读</dd></div><div><dt>后续调整</dt><dd>创建后继版本并重新审查</dd></div></dl>
        <p className="plans-v5-notice plans-v5-notice--warning"><IconAlert size={22} />发布不代表教师已确认，请另行核对通知和回执。</p>
        <section className="plans-v5-notification-scope"><h3>通知范围</h3><p>以发布接口和学校配置为准，不预先承诺所有收件人均已收到。</p></section>
        {error && <p role="alert" className="plans-v5-publish-error">{error}</p>}
        {publishNeedsRefresh && <Button disabled={Boolean(busy)} onClick={async () => {setPublishOpen(false);await onChanged?.();setPublishNeedsRefresh(false);setError('');}}>刷新并核对状态</Button>}
      </BlueprintDialog>}
    </section>
  );
}

function PlanConflictReview({revision, rules}) {
  const summary = revision?.validationSummary || {};
  const issues = Array.isArray(summary.issues) ? summary.issues : null;
  const checked = typeof summary.valid === 'boolean' && issues !== null;
  const categories = [
    {name:'calendar',label:'考试场次相关问题',match:/SESSION|DEMAND|EXAM|WINDOW/},
    {name:'room',label:'考场与座位相关问题',match:/ROOM|SEAT|CAPACITY/},
    {name:'teacher',label:'监考岗位相关问题',match:/DUTY|DUTIES|TEACHER|INVIGILAT/},
  ];
  const status = String(revision?.status || '').toUpperCase();
  return <div className="plans-v5-review-grid">
    <section className="plans-v5-review-main plans-v5-panel"><h2>校验结果</h2>
      {!checked ? <div role="status" className="plans-v5-validation-state"><BlueprintAsset name="calendar" size={44} /><div><strong>尚未返回完整校验结果</strong><p>请先在版本操作中执行校验。配置规则不代表校验已通过。</p></div></div> : <>
        <div role="status" className={`plans-v5-validation-state ${summary.valid ? 'is-valid' : 'is-invalid'}`}><span className="plans-v5-validation-symbol" aria-hidden="true">{summary.valid ? <IconCheck size={28} /> : <IconAlert size={28} />}</span><div><strong>{summary.valid ? '最近一次校验通过' : '该次校验未通过'}</strong><p>本次返回 {issues.length} 条校验问题，修改输入后需重新校验。</p></div></div>
        <ul className="plans-v5-check-metrics">{categories.map(category => <li key={category.name}><BlueprintAsset name={category.name} size={36} /><span>{category.label}</span><strong>{issues.filter(issue => category.match.test(String(issue.code || ''))).length}</strong></li>)}</ul>
        <p className="plans-v5-meta">以上为本次返回的问题条数，不是未排考试或资源缺口数量；一条问题可能涉及多个资源。</p>
        {issues.length > 0 && <ul className="plan-conflict-list plans-v5-issue-list">{issues.map((issue,index)=><li key={index}><strong>{valueOf(issue,['message','code'],'问题说明未提供')}</strong>{issue.suggestion && <p>{issue.suggestion}</p>}{issue.resourceId && <details><summary>查看关联资源</summary><small>资源标识：{issue.resourceId}</small></details>}</li>)}</ul>}
        <details className="ops-raw"><summary>查看完整校验记录</summary><pre>{JSON.stringify(summary,null,2)}</pre></details>
      </>}
      <details className="plans-v5-rules"><summary><IconSettings size={20} /><span>{Array.isArray(rules) && rules.length ? `查看 ${rules.length} 条约束配置` : '已配置规则 · 展开查看'}</span><IconChevron size={16} /></summary><div><p>下列内容是排程规则，不代表已发生冲突；空列表也不代表没有冲突。</p>{Array.isArray(rules) ? rules.length ? <pre>{JSON.stringify(rules,null,2)}</pre> : <p>当前未返回约束配置。</p> : <p role="alert">约束配置返回格式不完整，请刷新重试。</p>}</div></details>
    </section>
    <aside className="plans-v5-review-aside"><section className="plans-v5-panel"><h2>发布前核对</h2><ol className="plans-v5-preflight"><li><BlueprintAsset name="calendar" size={40} /><div><h3>确认计划与版本</h3><p>确认考试计划信息及当前查看版本正确。</p></div></li><li><BlueprintAsset name="staff" size={40} /><div><h3>核对审批记录</h3><p>确认版本已完成审批且处于“已批准”状态。</p></div></li><li><BlueprintAsset name="notice" size={40} /><div><h3>检查通知范围</h3><p>发布与通知投递、教师回执是不同状态。</p></div></li></ol><p className="plans-v5-notice"><IconHelp size={18} />规则配置不等于校验已通过。</p></section>
      <div className={`plans-v5-version-notice ${status === 'APPROVED' ? 'is-approved' : ''}`}><IconShield size={28} /><div><strong>{status === 'APPROVED' ? '此版本已批准，发布后不可直接修改。' : ['PUBLISHED','EXECUTING','CLOSED','ARCHIVED'].includes(status) ? '当前版本只读，调整需创建后继版本。' : '尚未达到发布阶段。'}</strong><p>{status === 'APPROVED' ? '如需修改，必须新建版本并重新审查。' : '请按版本操作和真实校验结果继续办理。'}</p></div></div>
    </aside>
  </div>;
}
function PlanTabContent({ tab, revision, onChanged, isActiveRevision = false }) {
  const revisionId = valueOf(revision, ['id', 'revisionId', 'revision_id'], '');
  const loader = React.useCallback(async () => {
    if (!revisionId) return [];
    if (tab === 'sessions') return window.api.examPlans.sessions(revisionId);
    if (tab === 'rooms-seats') {
      const [rooms, seats] = await Promise.all([window.api.examPlans.rooms(revisionId), window.api.examPlans.seats(revisionId)]);
      return { rooms, seats };
    }
    if (tab === 'invigilation') return window.api.examPlans.assignments(revisionId);
    if (tab === 'conflicts') return window.api.examPlans.constraints(revisionId);
    return [];
  }, [tab, revisionId]);
  const [state, reload] = useLiveResource(loader, [loader]);
  if (tab === 'inputs') return <JointInputWorkspace revision={revision} onChanged={onChanged} />;
  if (tab === 'publish') {
    return (
      <div className="ceramic-tile p-5">
        <div className="grid grid-cols-1 divide-y sm:grid-cols-3 sm:divide-x sm:divide-y-0">
          <PlanMetric label="版本" value={valueOf(revision, ['revisionNo', 'revision_no'], '未提供')} note={valueOf(revision, ['id', 'revisionId', 'revision_id'], '')} />
          <PlanMetric label="状态" value={PLAN_STATUS[String(valueOf(revision, ['status'], 'DRAFT')).toUpperCase()]?.[0] || valueOf(revision, ['status'], '—')} note={`更新 ${displayDate(valueOf(revision, ['updatedAt', 'updated_at', 'createdAt', 'created_at'], null))}`} />
          <PlanMetric label="发布者" value={valueOf(revision, ['publishedByName', 'published_by_name'], '未提供')} note={displayDate(valueOf(revision, ['publishedAt', 'published_at'], null))} />
        </div>
        {!valueOf(revision, ['publishedAt', 'published_at'], null) && <div className="mt-4 rounded-xl px-4 py-3 text-[12.5px]" style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)' }}>当前版本尚无发布记录。发布前必须完成校验、冲突审查与审批。</div>}
      </div>
    );
  }
  if (state.status !== 'ready') return <ResourceState state={state} label={PLAN_TABS.find((item) => item.id === tab)?.label || '版本数据'} onRetry={reload} />;
  if (tab === 'rooms-seats') {
    return <div className="grid grid-cols-1 gap-5 xl:grid-cols-2"><div><SectionTitle title="考场分配" sub="容量、楼宇与考试特征" /><ObjectRows rows={listOf(state.data?.rooms)} emptyLabel="本版本尚未分配考场" /></div><div><SectionTitle title="座位分配" sub="考生座位与特殊安排" /><ObjectRows rows={listOf(state.data?.seats)} emptyLabel="本版本尚未生成座位" /></div></div>;
  }
  if (tab === 'conflicts') return <PlanConflictReview revision={revision} rules={state.data} />;
  const labels = { sessions: '本版本尚未安排考试场次', invigilation: '本版本尚未分配监考岗位' };
  return <ObjectRows rows={listOf(state.data)} emptyLabel={labels[tab]} revisionStatus={valueOf(revision, ['status'], 'UNKNOWN')} isActiveRevision={isActiveRevision} />;
}

function resolvePlanRevision(revisions, requestedId, defaultId) {
  const id=requestedId || defaultId;
  return id ? revisions.find(item=>String(valueOf(item,['id','revisionId','revision_id'],''))===String(id)) || null : revisions[0] || null;
}
window.JKXTPlanNavigation = {
  open(target) {
    if(!target?.planId) return false;
    window.__jkxtPlanTarget={...target};
    window.navigateTo('exam-plans');
    return true;
  },
};
function PlanDetail({ plan, onBack, initialRevisionId='', initialTab='conflicts', returnTarget=null }) {
  const planId = valueOf(plan, ['id', 'planId', 'plan_id'], '');
  const loader = React.useCallback(() => Promise.all([window.api.examPlans.get(planId), window.api.examPlans.revisions(planId)]), [planId]);
  const [state, reload] = useLiveResource(loader, [loader]);
  const [tab, setTab] = React.useState(initialTab);
  const [revisionId, setRevisionId] = React.useState(initialRevisionId);
  const tabRefs = React.useRef([]);
  const selectTabByKeyboard = (event, index) => {
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % PLAN_TABS.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + PLAN_TABS.length) % PLAN_TABS.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = PLAN_TABS.length - 1;
    else return;
    event.preventDefault();
    setTab(PLAN_TABS[next].id);
    tabRefs.current[next]?.focus?.();
  };
  if (state.status !== 'ready') return <div className="space-y-4"><Button onClick={onBack}>返回考试计划</Button>{returnTarget?.returnRoute && <Button onClick={()=>window.navigateTo(returnTarget.returnRoute)}>{returnTarget.returnLabel || '返回来源页面'}</Button>}<ResourceState state={state} label="计划详情" onRetry={reload} /></div>;
  const detail = state.data[0] || plan;
  const revisions = listOf(state.data[1]);
  const defaultRevisionId = valueOf(detail, ['activeRevisionId', 'active_revision_id', 'currentRevisionId', 'current_revision_id', 'latestRevisionId', 'latest_revision_id'], valueOf(revisions[0], ['id', 'revisionId', 'revision_id'], ''));
  const selectedRevisionId = revisionId || defaultRevisionId;
  const revision = resolvePlanRevision(revisions, revisionId, defaultRevisionId);
  if(!revision && selectedRevisionId) return <div className="ops-panel"><h2>指定版本不可用</h2><p className="ops-muted">该版本不存在或已无权访问。为避免查看错误安排，系统没有切换到其他版本。</p><Button onClick={onBack}>返回考试计划</Button>{returnTarget?.returnRoute && <Button onClick={()=>window.navigateTo(returnTarget.returnRoute)}>{returnTarget.returnLabel || '返回来源页面'}</Button>}</div>;
  const handleRevisionChanged = async (next) => {
    const nextId = valueOf(next, ['successorRevisionId', 'successor_revision_id', 'revisionId', 'revision_id', 'id'], '');
    if (nextId) setRevisionId(nextId);
    await reload();
  };
  return (
    <div className="plans-v5-detail" data-testid="plan-detail">
      {returnTarget?.returnRoute && <div className="plan-purpose"><div className="grow"><strong>{selectedRevisionId===initialRevisionId ? (returnTarget.returnRoute === 'task-detail' ? '正在查看旧任务的送审版本' : returnTarget.returnRoute === 'emergency' ? '正在查看应急记录的来源版本' : '正在查看来源页面指定的版本') : '已切换到其他计划版本'}</strong><p>{returnTarget.returnRoute === 'task-detail' ? '请核对当前送审排班；其他版本的审批结果不会自动批准该任务。可返回任务页面刷新审批状态。' : returnTarget.returnRoute === 'emergency' ? '来源版本是发生变更时的原版本；其他版本不一定是该次变更的后继安排。' : '请核对版本标识和状态，其他版本不代表来源页面指定的安排。'}</p></div><Button onClick={()=>window.navigateTo(returnTarget.returnRoute)}>{returnTarget.returnLabel || '返回来源页面'}</Button></div>}
      <div className="plans-v5-context">
        <div className="plans-v5-context-row">
          <div>
            <div className="plans-v5-context-name"><strong>{valueOf(detail, ['name', 'title'], '未命名计划')}</strong><span className="plans-v5-plan-no">{valueOf(detail, ['planNo', 'plan_no', 'batchCode', 'batch_code', 'code'], '未设置批次编码')}</span></div>
            <div className="plans-v5-current-version"><span>当前查看</span><strong>第 {valueOf(revision, ['revisionNo', 'revision_no'], '未提供')} 版</strong><StatusBadge status={valueOf(revision, ['status'], 'UNKNOWN')} /></div>
          </div>
          <div className="plans-v5-context-controls"><label><span>切换版本</span><select value={selectedRevisionId} onChange={(event) => setRevisionId(event.target.value)}>{revisions.length === 0 && <option value="">暂无版本</option>}{revisions.map((item) => { const id = valueOf(item, ['id', 'revisionId', 'revision_id'], ''); return <option key={id} value={id}>第 {valueOf(item, ['revisionNo', 'revision_no'], '未提供')} 版 · {PLAN_STATUS[String(item.status).toUpperCase()]?.[0] || item.status}</option>; })}</select></label><Button onClick={onBack}>返回计划</Button></div>
        </div>
        <details className="plans-v5-technical"><summary>查看版本标识与更新时间</summary><p>计划标识：{planId}<br />版本标识：{selectedRevisionId}<br />更新时间：{displayDate(valueOf(revision, ['updatedAt', 'updated_at'], null))}</p></details>
        {Boolean(detail?.stale || revision?.stale) && <div role="alert" className="mt-4 rounded-xl px-4 py-3 text-[12px]" style={{ background: 'var(--warning-soft)', color: 'var(--warning)' }}>该数据已过期，可能有其他用户提交了后继版本。请刷新后再继续修改。</div>}
      </div>
      {selectedRevisionId && <PlanLifecycleActions key={`${planId}:${selectedRevisionId}`} planId={planId} revision={revision || {}} planName={valueOf(detail, ['name', 'title'], '')} planNo={valueOf(detail, ['planNo', 'plan_no'], '')} onChanged={handleRevisionChanged} />}
      <div className="plans-v5-tab-toolbar"><div role="tablist" aria-label="考试计划详情" className="plans-v5-tabs">{PLAN_TABS.map((item, index) => <button key={item.id} ref={(node) => { tabRefs.current[index] = node; }} id={`plan-tab-${planId}-${item.id}`} type="button" role="tab" aria-selected={tab === item.id} aria-controls={`plan-panel-${planId}-${item.id}`} tabIndex={tab === item.id || (tab === 'inputs' && index === 0) ? 0 : -1} onKeyDown={(event) => selectTabByKeyboard(event, index)} onClick={() => setTab(item.id)}>{item.label}</button>)}</div><Button id={`plan-inputs-${planId}`} aria-pressed={tab === 'inputs'} aria-controls={`plan-panel-${planId}-inputs`} onClick={() => setTab('inputs')}>数据与约束</Button></div>
      <div id={`plan-panel-${planId}-${tab}`} role={tab === 'inputs' ? 'region' : 'tabpanel'} aria-labelledby={tab === 'inputs' ? `plan-inputs-${planId}` : `plan-tab-${planId}-${tab}`} tabIndex="0" data-testid={`plan-tab-${tab}`}><PlanTabContent key={`${selectedRevisionId}:${tab}`} tab={tab} revision={revision || {}} isActiveRevision={Boolean(selectedRevisionId && selectedRevisionId === valueOf(detail, ['activeRevisionId', 'active_revision_id'], null))} onChanged={handleRevisionChanged} /></div>
    </div>
  );
}

function ExamPlansPage() {
  const loader = React.useCallback(async () => { const payload=await window.api.examPlans.list(); if(!Array.isArray(payload)) throw new Error('计划列表返回格式不正确'); return payload; }, []);
  const [state, reload] = useLiveResource(loader, [loader]);
  const [target,setTarget] = React.useState(()=>window.__jkxtPlanTarget || null);
  const [selected, setSelected] = React.useState(()=>window.__jkxtPlanTarget?.planId ? {id:window.__jkxtPlanTarget.planId} : null);
  const [creating, setCreating] = React.useState(false);
  const [initialTab, setInitialTab] = React.useState('conflicts');
  React.useEffect(()=>{window.__jkxtPlanTarget=null;},[]);
  return (
    <PageShell crumbs={[orgName(), '考务作业', '考试计划']} title={selected?.name || '考试计划'} subtitle={selected ? '在同一版本内核对场次、座位与监考安排。' : '先选择计划，再进入同一版本的场次、座位与监考安排。'}
      actions={!selected && <Button variant="primary" onClick={() => setCreating(true)} data-testid="create-plan">新建计划</Button>}>
      <div className="plan-workbench plans-v5 blueprint-v5">
        {selected && <PlanDetail key={selected.id} plan={selected} initialRevisionId={target?.revisionId || ''} initialTab={initialTab} returnTarget={target} onBack={() => { setSelected(null);setTarget(null);reload(); }} />}
        <div hidden={Boolean(selected)} className="space-y-4">
          <PlanFlowStrip />
          {creating && <CreatePlanPanel onCancel={() => setCreating(false)} onCreated={(plan) => { setCreating(false);setTarget(null);setInitialTab('inputs');setSelected(plan);reload(); }} />}
          <PlanList state={state} onRetry={reload} onRefresh={reload} onOpen={(plan, tab)=>{setTarget(null);setInitialTab(tab || 'conflicts');setSelected(plan);}} />
          <div className="plans-v5-legacy-note"><p>已有教师、考场、考试三张表？可继续使用排班工作台。两条流程不会自动同步修改。</p><Button onClick={()=>window.navigateTo('quick')}>三表快速排班</Button></div>
        </div>
      </div>
    </PageShell>
  );
}

// Readiness presentation is a projection only: retain the API's counts, status and raw issue data.
const OPERATIONAL_TOTAL_LABELS = {
  plans: '未归档计划', publishedPlans: '已发布及后续阶段计划', pendingApprovals: '待审批请求',
  activeEmergencies: '未处理应急变更', openSubstitutions: '未完成替补事项', failedDeliveries: '通知投递失败',
  blockingIncidents: '未处理严重事件', requiredChecklistPending: '待完成必查项', invalidRevisions: '无效或失败版本',
  sessionsWithoutRooms: '未分配考场场次', uncoveredDutyPosts: '待补齐监考岗位',
  pendingDutyConfirmations: '已分派待教师确认',
  revisions: '版本', sessions: '场次', rooms: '考场', seats: '座位', duties: '岗位', issues: '问题',
  blockers: '阻塞问题总量', blocking: '阻塞问题总量', blockingIssueCount: '阻塞问题总量',
  ready: '运行准备状态', generatedAt: '数据生成时间（北京时间）',
};

const OPERATIONAL_ISSUE_LABELS = {
  NO_EXAM_PLANS: ['尚无有效考试计划', '当前没有未归档的考试计划。'],
  PENDING_APPROVALS: ['待审批请求', '存在等待审批的计划版本，请核查审批记录。'],
  ACTIVE_EMERGENCIES: ['未处理应急变更', '仍有应急变更未解决，请核查处理进度。'],
  OPEN_SUBSTITUTIONS: ['未完成替补事项', '监考替补事项仍未完成。'],
  FAILED_DELIVERIES: ['通知投递失败', '存在投递失败记录，请核查通知渠道与重试结果。'],
  BLOCKING_INCIDENTS: ['未处理严重事件', '高等级或严重事件仍未解决。'],
  CHECKLIST_INCOMPLETE: ['必查项尚未完成', '运行检查清单中的必查项仍有待办。'],
  INVALID_REVISIONS: ['无效或失败版本', '存在校验无效、求解不可行或失败的计划版本。'],
  SESSIONS_WITHOUT_ROOMS: ['未分配考场场次', '存在尚未分配考场的考试场次。'],
  UNCOVERED_DUTY_POSTS: ['待补齐监考岗位', '部分监考岗位尚未达到要求人数。'],
};

function operationalSummaryItem(key, value) {
  const item = { key, label: OPERATIONAL_TOTAL_LABELS[key] || '其他接口指标', display: String(value), numeric: typeof value === 'number' && Number.isFinite(value), dateTime: null };
  if (typeof value === 'boolean') item.display = key === 'ready' ? (value ? '已就绪' : '尚未就绪') : (value ? '是' : '否');
  if (key === 'generatedAt') {
    const date = new Date(value);
    item.numeric = false;
    if (!Number.isNaN(date.getTime())) {
      item.dateTime = date.toISOString();
      item.display = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(date);
    }
  }
  return item;
}

function OperationalIssueRows({ rows }) {
  return <div className="space-y-3">{rows.map((issue, index) => {
    const code = String(issue.code || '');
    const [label, explanation] = OPERATIONAL_ISSUE_LABELS[code] || ['其他待核查问题', issue.message || '后台未提供问题说明。'];
    return <article key={`${code}:${index}`} className="ceramic-tile min-w-0 p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1" style={{ overflowWrap: 'anywhere' }}><h3 className="text-[14px] font-semibold">{label}</h3><p className="mt-1 text-[13px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>{explanation}</p></div>
        <div className="text-[12px]" style={{ color: 'var(--text-medium)' }}>关联数量 <span className="font-mono font-semibold" style={{ color: 'var(--text-strong)' }}>{issue.count == null ? '未提供' : String(issue.count)}</span></div>
      </div>
      <details className="mt-3 text-[12px]" style={{ color: 'var(--text-soft)' }}><summary className="min-h-11 cursor-pointer py-3">查看接口原始记录</summary><pre className="rounded-lg p-3 leading-relaxed" style={{ background: 'var(--surface-muted)', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(issue, null, 2)}</pre></details>
    </article>;
  })}</div>;
}

function OperationalQueuePage({ title, subtitle, endpoint, emptyLabel, routeId }) {
  const loader = React.useCallback(endpoint, [endpoint]);
  const [state, reload] = useLiveResource(loader, [loader]);
  const payload = state.data;
  const directRows = listOf(payload);
  const issueRows = listOf(payload?.issues);
  const checkRows = listOf(payload?.checks);
  const rows = directRows.length ? directRows : checkRows;
  const totals = payload?.totals && typeof payload.totals === 'object' && !Array.isArray(payload.totals) ? payload.totals : {};
  const scalarTotals = Object.entries(totals).filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value));
  const scalarPayload = payload && !Array.isArray(payload)
    ? Object.entries(payload).filter(([key, value]) => key !== 'issues' && key !== 'checks' && key !== 'totals' && ['string', 'number', 'boolean'].includes(typeof value))
    : [];
  return (
    <PageShell routeId={routeId} crumbs={[orgName(), '考务概览', title]} title={title} subtitle={subtitle} actions={<Button onClick={reload}>刷新</Button>}>
      {state.status !== 'ready' ? <ResourceState state={state} label={title} onRetry={reload} /> : <div className="space-y-5">
        {(scalarTotals.length > 0 || scalarPayload.length > 0) && <section aria-label="准备度汇总" className="grid grid-cols-2 gap-px overflow-hidden rounded-[18px] sm:grid-cols-3 xl:grid-cols-4" style={{ background: 'var(--border-soft)', border: '1px solid var(--border-soft)' }}>
          {[...scalarTotals, ...scalarPayload].map(([key, value]) => {
            const item = operationalSummaryItem(key, value);
            return <div key={key} data-readiness-field={key} className={`min-w-0 p-4 ${key === 'generatedAt' ? 'col-span-2' : ''}`} style={{ background: 'var(--surface-raised)' }}>
              <div className="text-[12px] font-medium leading-relaxed" style={{ color: 'var(--text-soft)', overflowWrap: 'anywhere' }} title={key}>{item.label}</div>
              <div className={`mt-2 leading-relaxed ${item.numeric ? 'font-mono text-[20px] font-semibold' : 'text-[14px] font-medium'}`} style={{ color: 'var(--text-strong)', overflowWrap: 'anywhere', minWidth: 0 }}>{item.dateTime ? <time dateTime={item.dateTime}>{item.display}</time> : item.display}</div>
              {!OPERATIONAL_TOTAL_LABELS[key] && <div className="mt-1 text-[12px] font-mono" style={{ color: 'var(--text-soft)', overflowWrap: 'anywhere' }}>{key}</div>}
            </div>;
          })}
        </section>}
        {issueRows.length > 0 && <section aria-label="阻塞与风险问题"><SectionTitle title="阻塞与风险问题" sub={`接口返回 ${issueRows.length} 类问题，请核查关联数量与原始记录`} /><OperationalIssueRows rows={issueRows} /></section>}
        {rows.length > 0 && <section aria-label={issueRows.length ? '检查明细' : title}><SectionTitle title={issueRows.length ? '检查明细' : title} sub={issueRows.length ? '其余检查项与运行记录' : '来自当前实时接口'} /><ObjectRows rows={rows} emptyLabel={emptyLabel} /></section>}
        {issueRows.length === 0 && rows.length === 0 && scalarTotals.length === 0 && scalarPayload.length === 0 && <div className="ceramic-tile py-12 text-center text-[12.5px]" style={{ color: 'var(--text-soft)' }}>{emptyLabel}</div>}
      </div>}
    </PageShell>
  );
}

function ExamRisksPage() { return <OperationalQueuePage routeId="risks" title="风险与待办" subtitle="汇总数据完整性、资源缺口、冲突和执行准备度" endpoint={window.api.operations.readiness} emptyLabel="当前没有返回风险或待办记录" />; }
function ExamApprovalsPage() { return <OperationalQueuePage routeId="approvals" title="审批与发布" subtitle="查看待审批版本、审批结果与发布状态" endpoint={window.api.operations.approvals} emptyLabel="当前没有待处理的审批或发布记录" />; }
function ExamEmergencyPage() { return <OperationalQueuePage routeId="emergency" title="应急与事件" subtitle="处理请假替补、考场停用、临时变更与考场事件" endpoint={window.api.operations.emergencyChanges} emptyLabel="当前没有应急变更或事件记录" />; }

window.ExamPlansPage = ExamPlansPage;
window.ExamRisksPage = ExamRisksPage;
window.ExamApprovalsPage = ExamApprovalsPage;
window.ExamEmergencyPage = ExamEmergencyPage;
