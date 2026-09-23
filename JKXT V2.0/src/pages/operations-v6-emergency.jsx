// V6 is a read-only projection of emergency registrations, never a resolution workflow.
const OPS6_EMERGENCY_TYPES = Object.freeze({
  TEACHER_SICK_LEAVE: ['教师临时病假', 'teacher', 'indigo'],
  TEACHER_MASS_ABSENCE: ['多名教师缺勤', 'teacher', 'indigo'],
  ROOM_UNAVAILABLE: ['考场临时停用', 'room', 'amber'],
  CANDIDATE_COUNT_INCREASE: ['考生人数增加', 'students', 'blue'],
  NOTIFICATION_CHANNEL_FAILURE: ['通知渠道故障', 'publish', 'amber'],
  NETWORK_INTERRUPTION_RETRY: ['网络中断重试', 'clock', 'amber'],
});
const OPS6_EMERGENCY_FIELDS = Object.freeze({
  teacherIds: '教师工号 / 标识', roomCodes: '考场编号', sessionCode: '场次编号', delta: '增加人数',
  effectiveAt: '生效时间', endsAt: '结束时间', channel: '通知渠道', recipientIds: '收件人标识',
  changedAssignments: '变更安排数', affectedAssignments: '受影响安排数', retentionRate: '原安排保留率',
  replacements: '替换记录', substitutions: '替补数', changes: '变更记录', type: '处理类型', strategy: '处理方式',
});
function ops6EmergencyDate(value, compact = false) {
  if (!value || Number.isNaN(new Date(value).getTime())) return '时间未提供';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', ...(compact ? {} : {year: 'numeric'}), month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(value));
}
function ops6EmergencyDay(value) {
  if (!value || Number.isNaN(new Date(value).getTime())) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date(value));
  const part = key => parts.find(item => item.type === key)?.value || '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}
function ops6EmergencyMeta(item) {
  const descriptor = OPS6_EMERGENCY_TYPES[item?.emergencyType];
  return {
    name: item?.planName || '计划名称未提供',
    type: descriptor?.[0] || item?.emergencyType || '变更类型未提供',
    asset: descriptor?.[1] || 'document', tone: descriptor?.[2] || 'indigo',
    status: item?.status === 'ACTIVE' ? '记录有效' : item?.status === 'INACTIVE' ? '记录已失效' : item?.status || '状态未提供',
    version: Number.isInteger(item?.revisionNo) ? `第 ${item.revisionNo} 版` : '来源版次未提供',
  };
}
function ops6EmergencyTarget(item) {
  if (typeof item?.planId !== 'string' || !item.planId.trim() || typeof item?.revisionId !== 'string' || !item.revisionId.trim()) return null;
  return {planId: item.planId, revisionId: item.revisionId};
}
function ops6EmergencyValue(value) {
  if (Array.isArray(value)) return value.filter(item => ['string', 'number'].includes(typeof item)).join('、') || '未提供';
  return typeof value === 'string' && value.trim() || typeof value === 'number' ? String(value) : '未提供';
}
function ops6EmergencyImpact(item) {
  const resource = item?.affectedResources || {};
  const start = ops6EmergencyDate(resource.effectiveAt, true);
  const end = ops6EmergencyDate(resource.endsAt, true);
  if (start === '时间未提供' && end === '时间未提供') return '影响时间未提供';
  if (start === '时间未提供') return `生效时间未提供，结束于 ${end}`;
  if (end === '时间未提供') return `${start} 起，结束时间未提供`;
  if (ops6EmergencyDay(resource.effectiveAt) === ops6EmergencyDay(resource.endsAt)) return `${start}–${end.slice(-5)}`;
  return `${start} 至 ${end}`;
}
function ops6EmergencyResources(item) {
  const resources = item?.affectedResources || {};
  const fields = [['teacherIds', '教师'], ['roomCodes', '考场'], ['sessionCode', '场次'], ['delta', '增加人数'], ['channel', '通知渠道']];
  return fields.flatMap(([key, label]) => {
    const value = ops6EmergencyValue(resources[key]);
    return value === '未提供' ? [] : [`${label} ${value}${key === 'delta' ? ' 人' : ''}`];
  }).join(' · ') || '影响资源未提供';
}
function ops6EmergencyFilter(rows, {query = '', type = '', start = '', end = ''} = {}) {
  const keyword = query.trim().toLocaleLowerCase();
  return rows.filter(item => {
    const meta = ops6EmergencyMeta(item);
    const date = ops6EmergencyDay(item.createdAt);
    return (!type || item.emergencyType === type)
      && `${meta.name} ${meta.type} ${item.id || ''}`.toLocaleLowerCase().includes(keyword)
      && (!start || Boolean(date) && date >= start) && (!end || Boolean(date) && date <= end);
  });
}
function ops6EmergencyFacts(rows) {
  return {
    registrations: rows.length,
    plans: new Set(rows.map(item => item.planId).filter(value => typeof value === 'string' && value.trim())).size,
    missingTime: rows.filter(item => !ops6EmergencyDay(item?.affectedResources?.effectiveAt) && !ops6EmergencyDay(item?.affectedResources?.endsAt)).length,
    missingPlan: rows.filter(item => typeof item.planId !== 'string' || !item.planId.trim()).length,
  };
}
function ops6EmergencyKeyRows(rows) {
  const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
  const occurrences=new Map();
  return rows.map(item=>{
    const base=item.id?`record:${item.id}`:`client:${JSON.stringify(canonical(item))}`;
    const occurrence=occurrences.get(base)||0;occurrences.set(base,occurrence+1);
    // UI identity only. Never submit this key or present it as a backend record ID.
    return Object.defineProperty({...item},'__ops6Key',{value:`${base}:${occurrence}`});
  });
}
function Ops6EmergencyAsset({name, size = 40}) {
  if (window.Ops6Asset) return <window.Ops6Asset name={name} size={size}/>;
  const fallback = {document: 'calendar', students: 'staff', clock: 'notice', approve: 'calendar', publish: 'notice'};
  return window.BlueprintAsset ? <window.BlueprintAsset name={fallback[name] || name} size={size}/> : null;
}
function Ops6EmergencyFields({value, omit = [], empty = '当前接口未提供'}) {
  const entries = value && typeof value === 'object' && !Array.isArray(value) ? Object.entries(value).filter(([key]) => !omit.includes(key)) : [];
  if (!entries.length) return value && (typeof value !== 'object' || Array.isArray(value) && value.length)
    ? <pre className="ops6-em-raw">{typeof value === 'string' ? value : JSON.stringify(value, null, 2)}</pre>
    : <p className="ops6-em-muted">{empty}</p>;
  return <dl className="ops6-em-facts">{entries.map(([key, item]) => <React.Fragment key={key}>
    <dt>{OPS6_EMERGENCY_FIELDS[key] || key}</dt>
    <dd>{['effectiveAt', 'endsAt'].includes(key) ? ops6EmergencyDate(item) : key === 'strategy' && item === 'MINIMAL_DUTY_SUBSTITUTION' ? '尽量保留原安排的监考替补' : item && typeof item === 'object' && (!Array.isArray(item) || item.some(value => value && typeof value === 'object')) ? <pre>{JSON.stringify(item, null, 2)}</pre> : ops6EmergencyValue(item)}</dd>
  </React.Fragment>)}</dl>;
}
function Ops6EmergencyFold({title, children}) {
  const [open, setOpen] = React.useState(false);
  const id = React.useId();
  return <section className="ops6-em-fold">
    <button type="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(value => !value)}>{title}<IconChevron dir={open ? 'up' : 'down'} size={18}/></button>
    {open && <div id={id} className="ops6-em-fold-content">{children}</div>}
  </section>;
}
function Ops6EmergencyFilters({rows, filters, setFilters, clearFilters}) {
  const hasFilters = Boolean(filters.query || filters.type || filters.start || filters.end);
  const invalidRange = Boolean(filters.start && filters.end && filters.start > filters.end);
  return <div className="ops6-em-filter-wrap">
    <div className="ops6-em-toolbar">
      <label className="ops6-em-search"><span className="ops6-em-sr-only">搜索计划或变更类型</span><IconSearch size={19}/><input type="search" value={filters.query} onChange={event => setFilters(current => ({...current, query: event.target.value}))} placeholder="搜索计划或变更类型"/></label>
      <label className="ops6-em-type"><span className="ops6-em-sr-only">变更类型</span><select value={filters.type} onChange={event => setFilters(current => ({...current, type: event.target.value}))}><option value="">全部类型</option>{[...new Set(rows.map(item => item.emergencyType).filter(Boolean))].map(key => <option key={key} value={key}>{OPS6_EMERGENCY_TYPES[key]?.[0] || key}</option>)}</select></label>
      <details className="ops6-em-date-filter"><summary><IconCalendar size={18}/><span>登记日期{filters.start || filters.end ? ' · 已筛选' : ''}</span><IconChevron size={15}/></summary><div className="ops6-em-date-popover">
        <p>按北京时间筛选登记日期，包含起止日。</p>
        <label>开始日期<input type="date" value={filters.start} max={filters.end || undefined} onChange={event => setFilters(current => ({...current, start: event.target.value}))}/></label>
        <label>结束日期<input type="date" value={filters.end} min={filters.start || undefined} onChange={event => setFilters(current => ({...current, end: event.target.value}))}/></label>
        {(filters.start || filters.end) && <Button onClick={() => setFilters(current => ({...current, start: '', end: ''}))}>清除日期</Button>}
      </div></details>
      {hasFilters && <Button className="ops6-em-clear" onClick={clearFilters}>清空筛选</Button>}
    </div>
    {invalidRange && <p role="alert" className="ops6-em-range-error">开始日期不能晚于结束日期，请调整登记日期。</p>}
  </div>;
}
function Ops6EmergencyDetail({item, onClose, onSource}) {
  const meta = ops6EmergencyMeta(item);
  return <window.BlueprintDrawer title="变更登记详情" description={`${meta.name} · 来源${meta.version}`} onClose={onClose} className="ops6 ops6-emergency-drawer" footer={<>
    <div className="ops6-em-drawer-buttons"><Button onClick={onClose}>关闭</Button><Button variant="primary" disabled={!ops6EmergencyTarget(item)} onClick={() => onSource(item)}>查看来源版本</Button></div>
    <p className="ops6-em-muted ops6-em-center">关闭详情不会改变业务状态</p>
  </>}>
    <p className="ops6-em-validity"><IconHelp size={20}/>{meta.status}<span>登记状态，不代表处置完成</span></p>
    <section className="ops6-em-detail-section"><h3>影响范围</h3><div className="ops6-em-impact-grid"><div><span>登记的影响资源</span><strong>{ops6EmergencyResources(item)}</strong></div><div><span>影响时间（北京时间）</span><strong>{ops6EmergencyImpact(item)}</strong></div></div></section>
    <section className="ops6-em-detail-section"><h3>来源与后续安排</h3><div className="ops6-em-version-flow"><div><span>来源版本</span><strong>{meta.version}</strong><p>登记时关联的版本</p></div><span className="ops6-em-version-link" aria-label="后续关系未知">?</span><div><span>后续安排</span><strong>当前接口未提供</strong><p>请进入计划核对，不能据此判断是否已办理。</p></div></div></section>
    <dl className="ops6-em-facts ops6-em-registration"><dt>登记时间</dt><dd>{ops6EmergencyDate(item.createdAt)}（北京时间）</dd><dt>变更类型</dt><dd>{meta.type}</dd><dt>计划名称</dt><dd>{meta.name}</dd></dl>
    <Ops6EmergencyFold title="登记的影响资源"><Ops6EmergencyFields value={item.affectedResources}/></Ops6EmergencyFold>
    <Ops6EmergencyFold title="登记的处理动作"><Ops6EmergencyFields value={item.actionTaken} empty="未提供处理动作。登记动作不代表后续版本已审批或发布。"/></Ops6EmergencyFold>
    <Ops6EmergencyFold title="编号与原始记录"><pre className="ops6-em-raw">{JSON.stringify(item, null, 2)}</pre></Ops6EmergencyFold>
    {!ops6EmergencyTarget(item) && <p role="status" className="ops6-em-caution">缺少计划或来源版本关联，无法安全跳转。</p>}
    <p className="ops6-em-advice">请在来源版本核对受影响岗位，再确认后续安排是否审批和发布。</p>
  </window.BlueprintDrawer>;
}
function Ops6EmergencyPage() {
  const loader = React.useCallback(async () => {
    const result = await window.api.operations.emergencyChanges();
    if (!Array.isArray(result) || result.some(item => !item || typeof item !== 'object' || Array.isArray(item))) throw new Error('应急登记返回格式不完整，请重新加载或联系管理员。');
    return result;
  }, []);
  const [state, reload] = useLiveResource(loader, [loader]);
  const [filters, setFilters] = React.useState(() => window.__jkxtOps6EmergencyReturn?.filters || {query: '', type: '', start: '', end: ''});
  const [selectedId, setSelectedId] = React.useState(() => window.__jkxtOps6EmergencyReturn?.selectedId || window.__jkxtEmergencyReturnId || '');
  const [detailItem, setDetailItem] = React.useState(null);
  React.useEffect(() => {window.__jkxtOps6EmergencyReturn = null; window.__jkxtEmergencyReturnId = '';}, []);
  const rows = React.useMemo(() => ops6EmergencyKeyRows(Array.isArray(state.data) ? state.data : []).sort((a, b) => (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0)), [state.data]);
  const filtered = React.useMemo(() => ops6EmergencyFilter(rows, filters), [rows, filters]);
  const selected = filtered.find(item => item.__ops6Key === selectedId || item.id && item.id === selectedId) || filtered[0] || null;
  const stats = ops6EmergencyFacts(filtered);
  const clearFilters = () => setFilters({query: '', type: '', start: '', end: ''});
  const openSource = item => {
    const target = ops6EmergencyTarget(item);
    if (!target || !window.JKXTPlanNavigation?.open) return;
    window.__jkxtOps6EmergencyReturn = {filters, selectedId: item.__ops6Key};
    window.__jkxtEmergencyReturnId = item.id || '';
    setDetailItem(null);
    window.JKXTPlanNavigation.open({...target, returnRoute: 'emergency', returnLabel: '返回应急记录'});
  };
  const filterLabel = filters.start || filters.end ? `${filters.start || '不限开始'} 至 ${filters.end || '不限结束'}` : '全部登记日期';
  const empty = state.status === 'ready' && rows.length === 0;
  return <div className="ops6 ops6-emergency"><PageShell routeId="emergency" crumbs={[orgName(), '结果与档案', '应急与事件']} title="应急与事件" subtitle="核对临时变化，找到需要查看的安排" actions={<><Button onClick={reload} disabled={state.status === 'loading'} icon={OpsRefreshIcon}>刷新</Button>{!empty && <Button variant="primary" onClick={() => window.navigateTo('exam-plans')}>选择计划办理变更</Button>}</>}>
    <p className="ops6-em-date-caption"><IconClock size={18}/><span>登记日期：{filterLabel} · 北京时间</span></p>
    {state.status !== 'ready' ? <section className="ops6-em-status-panel"><ResourceState state={state} label="应急变更登记" onRetry={reload}/></section> : <>
      {!empty && <section className="ops6-em-overview" aria-label="当前筛选登记统计">
        <div className="ops6-em-scope"><Ops6EmergencyAsset name="document" size={34}/><h2>本机构登记 · 当前筛选统计</h2><span>不随顶部学期切换</span><span className="ops6-em-scope-note"><IconHelp size={17}/>记录有效 ≠ 处置完成</span></div>
        <div className="ops6-em-metrics">
          <div className="ops6-em-metric ops6-em-tone-indigo"><Ops6EmergencyAsset name="document" size={48}/><div><span>登记记录</span><strong>{stats.registrations}</strong></div></div>
          <div className="ops6-em-metric ops6-em-tone-green"><Ops6EmergencyAsset name="approve" size={48}/><div><span>涉及计划</span><strong>{stats.plans}</strong></div></div>
          <div className="ops6-em-metric ops6-em-tone-amber"><Ops6EmergencyAsset name="clock" size={48}/><div><span>影响时间未提供</span><strong>{stats.missingTime}<small>条</small></strong></div></div>
        </div>
        {stats.missingPlan > 0 && <p className="ops6-em-muted">另有 {stats.missingPlan} 条记录缺少计划关联，未计入“涉及计划”。</p>}
      </section>}
      <div className={`ops6-em-workspace ${filtered.length ? '' : 'ops6-em-workspace--empty'}`}>
        <section className="ops6-em-records" aria-label="应急变更记录">
          {empty && <p className="ops6-em-empty-scope">本机构应急变更登记 · 不随顶部学期切换</p>}
          <Ops6EmergencyFilters rows={rows} filters={filters} setFilters={setFilters} clearFilters={clearFilters}/>
          {filtered.length ? <>
            <div className="ops6-em-record-list">{filtered.map((item, index) => {
              const meta = ops6EmergencyMeta(item);
              const isSelected = selected === item;
              return <article key={item.__ops6Key} className={`ops6-em-record ${isSelected ? 'is-selected' : ''} ops6-em-tone-${meta.tone}`}>
                <span className="ops6-em-record-art"><Ops6EmergencyAsset name={meta.asset} size={48}/></span>
                <button type="button" className="ops6-em-select" aria-pressed={isSelected} onClick={() => setSelectedId(item.__ops6Key)}>
                  <span className="ops6-em-record-top"><strong>{meta.type}</strong><span className="ops6-em-badge">{meta.status}</span></span>
                  <span className="ops6-em-plan">{meta.name} · 来源{meta.version}</span>
                  <span className="ops6-em-record-meta"><span><IconClock size={15}/>{ops6EmergencyImpact(item)}</span><span>{ops6EmergencyResources(item)}</span></span>
                  <span className="ops6-em-registered">登记于 {ops6EmergencyDate(item.createdAt, true)}</span>
                </button>
                <button type="button" className="ops6-em-detail-button" aria-haspopup="dialog" aria-label={`查看${meta.type}详情，${meta.name}，${ops6EmergencyDate(item.createdAt, true)}`} onClick={() => {setSelectedId(item.__ops6Key); setDetailItem(item);}}><IconChevron dir="right" size={20}/><span>详情</span></button>
              </article>;
            })}</div>
            <footer className="ops6-em-list-footer"><span role="status">共 {filtered.length} 条登记{filtered.length !== rows.length ? `，全部 ${rows.length} 条` : ''}</span><span>按登记时间倒序</span></footer>
          </> : <div className="ops6-em-empty"><Ops6EmergencyAsset name="empty" size={112}/><h2>{empty ? '暂无应急变更登记' : '没有符合条件的登记'}</h2><p>{empty ? '当前范围内没有登记记录。需要办理临时变更时，请从考试计划进入。' : '请调整计划名称、变更类型或登记日期后重试。'}</p>{empty ? <Button variant="primary" onClick={() => window.navigateTo('exam-plans')}>查看考试计划</Button> : <Button onClick={clearFilters}>清空筛选</Button>}<p className="ops6-em-muted">无登记记录不代表没有考试安排。</p></div>}
        </section>
        {selected && <aside className="ops6-em-summary" aria-label="所选变更">
          <h2>所选变更</h2><div className="ops6-em-summary-heading"><Ops6EmergencyAsset name={ops6EmergencyMeta(selected).asset} size={52}/><div><h3>{ops6EmergencyMeta(selected).type}</h3><span className="ops6-em-badge">{ops6EmergencyMeta(selected).status}</span></div></div>
          <dl className="ops6-em-facts ops6-em-summary-facts"><dt>来源版本</dt><dd>{ops6EmergencyMeta(selected).version}</dd><dt>影响资源</dt><dd>{ops6EmergencyResources(selected)}</dd><dt>后续安排</dt><dd>当前接口未提供</dd></dl>
          <p className="ops6-em-muted">需进入计划核对，不能据此判断是否已办理。</p>
          <section className="ops6-em-check-guide"><h3>建议核查</h3><ol><li><span>1</span>核对受影响岗位</li><li><span>2</span>查看来源版本</li><li><span>3</span>确认后续安排与发布</li></ol></section>
          {!ops6EmergencyTarget(selected) && <p className="ops6-em-caution">缺少计划或来源版本关联，无法安全跳转。</p>}
          <Button className="ops6-em-source" variant="primary" disabled={!ops6EmergencyTarget(selected)} onClick={() => openSource(selected)}>查看来源版本</Button>
          <p className="ops6-em-muted">打开登记关联的版本，不自动切换到最新版。</p>
          <Ops6EmergencyFold title="字段说明与登记规则"><p>“记录有效”仅表示登记仍然有效，不代表已审批、已发布或已解决。</p><p>影响时间来自登记资源，不能用登记日期代替。当前接口未提供后续版本关联，请在计划中核对。</p></Ops6EmergencyFold>
        </aside>}
      </div>
      {empty && <aside className="ops6-em-empty-guide"><strong><IconHelp size={20}/>办理提示</strong><div><h3>已有考试计划</h3><p>进入对应计划办理临时变更</p></div><div><h3>仅查看登记</h3><p>使用上方类型与关键词筛选</p></div></aside>}
    </>}
    {detailItem && state.status === 'ready' && <Ops6EmergencyDetail item={detailItem} onClose={() => setDetailItem(null)} onSource={openSource}/>}
  </PageShell></div>;
}
window.Ops6EmergencyModel = Object.freeze({registrationDay: ops6EmergencyDay, filterRows: ops6EmergencyFilter, facts: ops6EmergencyFacts, sourceTarget: ops6EmergencyTarget, impact: ops6EmergencyImpact, keyRows: ops6EmergencyKeyRows});
window.ExamEmergencyPage = Ops6EmergencyPage;
