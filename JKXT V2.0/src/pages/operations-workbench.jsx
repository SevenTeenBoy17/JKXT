// Administrative work queues. This projection never mutates business records.
const OPS_RISK_DESTINATIONS = {
  NO_EXAM_PLANS: {route:'exam-plans', label:'查看考试计划'},
  PENDING_APPROVALS: {route:'approvals', label:'查看审批请求'},
  ACTIVE_EMERGENCIES: {route:'emergency', label:'查看应急记录'},
  OPEN_SUBSTITUTIONS: {route:'exam-plans', label:'选择计划核查替补'},
  FAILED_DELIVERIES: {route:'exam-plans', label:'选择计划核查通知'},
  BLOCKING_INCIDENTS: {route:'exam-plans', label:'选择计划核查事件'},
  CHECKLIST_INCOMPLETE: {route:'exam-plans', label:'选择计划核查清单'},
  INVALID_REVISIONS: {route:'exam-plans', label:'查看计划版本'},
  SESSIONS_WITHOUT_ROOMS: {route:'exam-plans', label:'选择计划核查考场'},
  UNCOVERED_DUTY_POSTS: {route:'exam-plans', label:'选择计划核查岗位'},
};
const OPS_EMERGENCY_TYPES = {
  TEACHER_SICK_LEAVE:'教师临时病假', TEACHER_MASS_ABSENCE:'多名教师缺勤',
  ROOM_UNAVAILABLE:'考场临时停用', CANDIDATE_COUNT_INCREASE:'考生人数增加',
  NOTIFICATION_CHANNEL_FAILURE:'通知渠道故障', NETWORK_INTERRUPTION_RETRY:'网络中断重试',
};
function opsCount(value) { return Number.isInteger(value) && value >= 0 ? value : '未提供'; }
function opsDate(value) {
  if (!value) return '时间未提供';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', {timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(date);
}
function opsRecentRows(rows) {
  return [...rows].sort((a,b) => (Date.parse(b.updatedAt || b.createdAt) || 0) - (Date.parse(a.updatedAt || a.createdAt) || 0));
}
function opsSourceTarget(item) {
  if (typeof item?.planId !== 'string' || !item.planId.trim() || typeof item?.revisionId !== 'string' || !item.revisionId.trim()) return null;
  return {planId:item.planId, revisionId:item.revisionId};
}
function opsEmergencyView(item) {
  return {
    name: item.planName || '计划名称未提供',
    type: OPS_EMERGENCY_TYPES[item.emergencyType] || item.emergencyType || '变更类型未提供',
    status: item.status === 'ACTIVE' ? '记录有效' : item.status || '状态未提供',
    sourceVersion: Number.isInteger(item.revisionNo) ? `第 ${item.revisionNo} 版` : '来源版次未提供',
  };
}
function opsReadinessView(payload) {
  return {
    readyLabel: payload?.ready === true ? '接口检查已就绪' : payload?.ready === false ? '需要核查' : '状态未提供',
    categoryCount: Array.isArray(payload?.issues) ? payload.issues.length : '未提供',
    blockingCount: opsCount(payload?.blockingIssueCount),
  };
}
function OpsRawDetails({value,label='技术记录'}) {
  return <details className="ops5-raw"><summary>{label}<IconChevron size={16}/></summary><pre>{JSON.stringify(value,null,2)}</pre></details>;
}
const OPS_RISK_PRESENTATION = {
  ACTIVE_EMERGENCIES: {title:'应急变更记录需复核',description:'存在有效登记，请核对来源版本与登记资源。',asset:'risk',tone:'warning',scope:'本机构应急登记',review:'来源计划与版本、影响时间、登记资源'},
  UNCOVERED_DUTY_POSTS: {title:'待补齐监考岗位',description:'接口返回尚未覆盖的监考岗位，请进入计划核查。',asset:'staff',tone:'warning',scope:'本机构计划中的监考岗位',review:'计划版本、岗位需求与现有监考安排'},
  FAILED_DELIVERIES: {title:'通知投递失败',description:'接口返回投递失败记录，请进入计划核查通知。',asset:'notice',tone:'danger',scope:'本机构通知投递记录',review:'通知渠道、失败原因与实际投递状态'},
};
function opsRiskView(issue) {
  const code = issue?.code;
  const own = (object,key) => Object.prototype.hasOwnProperty.call(object,key) ? object[key] : null;
  const copy = own(OPS_RISK_PRESENTATION,code);
  const labels = own(OPERATIONAL_ISSUE_LABELS,code);
  return {
    title: copy?.title || labels?.[0] || '其他待核查问题',
    description: copy?.description || labels?.[1] || issue?.message || '尚未提供说明',
    target: own(OPS_RISK_DESTINATIONS,code),
    count: opsCount(issue?.count),
    asset: copy?.asset || 'risk', tone: copy?.tone || 'neutral',
    scope: copy?.scope || '本机构计划与运行记录',
    review: copy?.review || '具体计划、版本与接口返回的问题说明',
  };
}
function opsPrioritizeIssues(issues) {
  const order = ['ACTIVE_EMERGENCIES','UNCOVERED_DUTY_POSTS','FAILED_DELIVERIES'];
  const rank = code => { const index=order.indexOf(code); return index < 0 ? order.length : index; };
  return [...issues].sort((a,b)=>rank(a?.code)-rank(b?.code));
}
function OpsRefreshIcon() {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M19.4 12a7.5 7.5 0 1 0-1.6 5M20 7l-2.4-2.1"/></svg>;
}
function OpsInfoIcon() { return <IconHelp size={20}/>; }
function OpsSearchIcon() { return <IconSearch size={20}/>; }
function OpsWarningIcon() { return <IconAlert size={20}/>; }
function OpsRiskRow({issue,index,expanded,onToggle}) {
  const view = opsRiskView(issue);
  const panelId = `ops5-risk-${index}`;
  return <article className={`ops5-risk-row ops5-tone-${view.tone}`}>
    <div className="ops5-risk-heading">
      <window.BlueprintAsset name={view.asset} size={48}/>
      <div className="ops5-risk-copy"><h3>{view.title}</h3>{!expanded && <p>{view.description}</p>}</div>
      <span className="ops5-risk-count" aria-label={`该类数量 ${view.count}`}>{view.count}<small>{issue?.code==='UNCOVERED_DUTY_POSTS' ? '个' : '条'}</small></span>
      {!expanded && view.target && <Button className="ops5-link" onClick={()=>window.navigateTo(view.target.route)}>{view.target.label}</Button>}
      <button type="button" className="ops5-expand" aria-expanded={expanded} aria-controls={panelId} aria-label={`${expanded ? '收起' : '展开'}${view.title}`} onClick={onToggle}><IconChevron dir={expanded ? 'up' : 'down'} size={20}/><span className="ops5-expand-label">{expanded ? '收起' : '展开'}</span></button>
    </div>
    {expanded && <div id={panelId} className="ops5-risk-detail">
      <dl className="ops5-review-facts"><dt>影响范围</dt><dd>{view.scope}</dd><dt>需要核查</dt><dd>{view.review}</dd><dt>提醒</dt><dd>汇总接口不提供逐条风险编号；展开或收起不会改变业务状态。</dd></dl>
      <div className="ops5-risk-destination">{view.target ? <Button variant="primary" onClick={()=>window.navigateTo(view.target.route)}>{view.target.label}</Button> : <p>尚未提供此类问题的核查入口，请根据接口说明联系管理员。</p>}{view.target?.route==='exam-plans' && <p>接口未提供具体计划定位，请进入后选择计划。</p>}</div>
      <OpsRawDetails value={issue} label="查看此类检查信息"/>
    </div>}
  </article>;
}
function OpsCheckGuide() {
  const steps = [
    ['核实来源','确认问题来源与影响范围。'],
    ['处理变更','进入所属计划核对变更或补齐安排。'],
    ['重新校验','修改后按对应版本流程重新校验。'],
    ['核对发布','核对审批、发布与通知的实际状态。'],
  ];
  return <aside className="ops5-panel ops5-guide" aria-label="核查顺序"><h2>核查顺序</h2><ol>{steps.map(([title,description],index)=><li key={title}><span aria-hidden="true">{index+1}</span><div><h3>{title}</h3><p>{description}</p></div></li>)}</ol></aside>;
}
function OpsCoverage({generatedAt}) {
  return <aside className="ops5-coverage" aria-label="核查覆盖范围">
    <section className="ops5-panel"><h2>核查覆盖范围</h2><dl className="ops5-coverage-facts"><dt>已获取</dt><dd>运行汇总</dd><dt>未覆盖</dt><dd>具体资源与冲突原因，需要进入来源核查。</dd></dl></section>
    <section className="ops5-freshness"><h2><IconClock size={22}/> 数据新鲜度</h2><p>更新于 <time>{opsDate(generatedAt)}</time>（北京时间）</p><p>处理后请刷新核对。</p></section>
  </aside>;
}
function OpsRiskPage() {
  const loader = React.useCallback(async () => {
    const payload = await window.api.operations.readiness();
    if (!payload || Array.isArray(payload) || !Array.isArray(payload.issues) || !payload.totals || typeof payload.totals !== 'object') throw new Error('运行准备度返回格式不完整，请重试或联系管理员。');
    return payload;
  }, []);
  const [state,reload] = useLiveResource(loader,[loader]);
  const [expandedId,setExpandedId] = React.useState(null);
  const payload = state.data || {};
  const view = opsReadinessView(payload);
  const issues = opsPrioritizeIssues(Array.isArray(payload.issues) ? payload.issues : []);
  const expanded = issues.some((issue,index)=>`${issue?.code}:${index}`===expandedId);
  return <PageShell routeId="risks" crumbs={[orgName(),'考务概览','风险与待办']} title="风险与待办" subtitle="先处理阻塞事项，再核查运行准备。" actions={<Button className="ops5-refresh" disabled={state.status==='loading'} onClick={reload} icon={OpsRefreshIcon}>刷新</Button>}>
    <div className="ops-v5 blueprint-v5">
      {state.status !== 'ready' ? <ResourceState state={state} label="运行准备度" onRetry={reload}/> : <>
        <div className="ops5-scope"><span><IconBuilding size={20}/>本机构汇总 · 不随顶部学期筛选</span><span><IconClock size={20}/>数据更新 {opsDate(payload.generatedAt)}（北京时间）</span><span><IconHelp size={20}/>数量可能重叠，请勿相加</span></div>
        <div className="ops5-risk-layout">
          <section className="ops5-panel ops5-priority" aria-label="需要核查的问题"><h2>优先处理</h2>
            {issues.length === 0 ? <div className="ops5-empty"><h3>本次检查没有返回问题</h3><p>仅代表当前检查范围，不替代考前人工核对。</p><Button onClick={()=>window.navigateTo('exam-plans')}>查看考试计划</Button></div> : issues.map((issue,index)=>{
              const id=`${issue?.code}:${index}`;
              return <OpsRiskRow key={id} issue={issue} index={index} expanded={expandedId===id} onToggle={()=>setExpandedId(current=>current===id ? null : id)}/>;
            })}
          </section>
          {expanded ? <OpsCoverage generatedAt={payload.generatedAt}/> : <OpsCheckGuide/>}
        </div>
        <details className="ops5-accordion"><summary><h2>其他运行指标与检查范围</h2><span>展开其他指标</span><IconChevron size={20}/></summary><div className="ops5-accordion-body"><p>运行准备：{view.readyLabel}。不同类别可能指向同一个计划；这些计数不是独立待办人数或考试数量。</p><dl className="ops5-total-facts">{Object.entries(payload.totals || {}).map(([key,value])=><React.Fragment key={key}><dt>{key==='activeEmergencies' ? '有效应急登记' : OPERATIONAL_TOTAL_LABELS[key] || key}</dt><dd>{typeof value==='number' ? opsCount(value) : String(value ?? '未提供')}</dd></React.Fragment>)}</dl><p className="ops5-muted">接口阻塞计数 {view.blockingCount}，按问题类别相加，可能重叠。接口检查不替代具体资源及冲突原因核查。</p></div></details>
        <p className="ops5-notice"><IconHelp size={20}/><span>本页是机构汇总，不是个人待办清单。</span></p>
      </>}
    </div>
  </PageShell>;
}
const OPS_RESOURCE_LABELS = {
  teacherIds:'教师工号 / 标识', roomCodes:'考场编号', sessionCode:'场次编号', delta:'增加人数',
  effectiveAt:'生效时间', endsAt:'结束时间', channel:'通知渠道', recipientIds:'收件人标识',
  changedAssignments:'变更安排数', affectedAssignments:'受影响安排数', retentionRate:'原安排保留率',
  replacements:'替换记录', substitutions:'替补数', changes:'变更记录', type:'处理类型', strategy:'处理方式',
};
function OpsResourceFacts({value,emptyLabel='未提供登记字段',omitKeys=[]}) {
  const entries = value && typeof value==='object' && !Array.isArray(value) ? Object.entries(value).filter(([key])=>!omitKeys.includes(key)) : [];
  if (!entries.length) return <p className="ops5-muted">{value == null || typeof value==='object' && !Array.isArray(value) ? emptyLabel : typeof value==='object' ? JSON.stringify(value) : String(value)}</p>;
  return <dl className="ops5-resource-facts">{entries.map(([key,item])=><React.Fragment key={key}><dt>{OPS_RESOURCE_LABELS[key] || key}</dt><dd>{key==='strategy' && item==='MINIMAL_DUTY_SUBSTITUTION' ? '尽量保留原安排的监考替补' : ['effectiveAt','endsAt'].includes(key) ? opsDate(item) : Array.isArray(item) && item.every(v=>typeof v!=='object') ? item.join('、') || '未提供' : item && typeof item==='object' ? <pre>{JSON.stringify(item,null,2)}</pre> : String(item ?? '未提供')}</dd></React.Fragment>)}</dl>;
}
function opsEmergencyTime(item) {
  const resource = item?.affectedResources;
  const start = resource?.effectiveAt ? opsDate(resource.effectiveAt) : '生效时间未提供';
  return resource?.endsAt ? `${start} — ${opsDate(resource.endsAt)}` : start;
}
function opsEmergencyResources(item) {
  const resource = item?.affectedResources;
  if (!resource || typeof resource!=='object' || Array.isArray(resource) || !Object.keys(resource).length) return '登记资源未提供';
  const labels = {teacherIds:'教师',roomCodes:'考场',sessionCode:'场次',channel:'通知渠道',delta:'增加人数',recipientIds:'收件人标识'};
  const values = Object.entries(labels).flatMap(([key,label])=>{
    const value = resource[key];
    const shown = Array.isArray(value) && value.every(v=>typeof v==='string' || typeof v==='number') ? value.join('、') : typeof value==='string' || typeof value==='number' ? String(value) : '';
    return shown ? [`${label} ${shown}`] : [];
  });
  return values.length ? values.join('；') : '其他登记字段，请查看详情';
}
function OpsEmergencyPage() {
  const loader = React.useCallback(async()=>{
    const payload = await window.api.operations.emergencyChanges();
    if(!Array.isArray(payload)) throw new Error('应急记录返回格式不正确，请重试。');
    return payload;
  },[]);
  const [state,reload] = useLiveResource(loader,[loader]);
  const [query,setQuery] = React.useState('');
  const [type,setType] = React.useState('');
  const [selectedId,setSelectedId] = React.useState(()=>window.__jkxtEmergencyReturnId || '');
  React.useEffect(()=>{window.__jkxtEmergencyReturnId='';},[]);
  const rows = opsRecentRows(Array.isArray(state.data) ? state.data : []);
  const filtered = rows.filter(item=>{
    const view=opsEmergencyView(item);
    return (!type || item.emergencyType===type) && `${view.name} ${view.type} ${item.id || ''}`.toLowerCase().includes(query.trim().toLowerCase());
  });
  const selected = filtered.find(item=>item.id===selectedId);
  const select = item=>setSelectedId(item.id);
  const close = ()=>setSelectedId('');
  const openSource = () => {
    const target=opsSourceTarget(selected);
    if(!target) return;
    window.__jkxtEmergencyReturnId=selected.id;
    window.JKXTPlanNavigation.open({...target,returnRoute:'emergency',returnLabel:'返回应急记录'});
  };
  return <PageShell routeId="emergency" crumbs={[orgName(),'结果与档案','应急与事件']} title="应急与事件" subtitle="查看来源、影响范围和登记内容。" actions={<><Button variant="primary" disabled={state.status!=='ready'} onClick={()=>window.navigateTo('exam-plans')}>选择计划办理变更</Button><Button disabled={state.status==='loading'} onClick={reload} icon={OpsRefreshIcon}>刷新</Button></>}>
    <div className="ops-v5 blueprint-v5">
      {state.status !== 'ready' ? <ResourceState state={state} label="应急变更记录" onRetry={reload}/> : <>
        <p className="ops5-notice"><OpsInfoIcon/><span>记录有效不代表问题已解决。本页展示本机构全部应急变更登记，不随顶部学期筛选。</span></p>
        <section className="ops5-panel ops5-records" aria-label="应急记录列表">
          <div className="ops5-toolbar"><label className="ops5-type"><span>事件类型</span><select value={type} onChange={e=>setType(e.target.value)}><option value="">全部类型</option>{[...new Set(rows.map(item=>item.emergencyType).filter(Boolean))].map(key=><option key={key} value={key}>{OPS_EMERGENCY_TYPES[key] || key}</option>)}</select></label><label className="ops5-search"><span className="ops5-visually-hidden">搜索记录</span><OpsSearchIcon/><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="搜索计划名称、变更类型或编号"/></label>{(query || type) && <Button onClick={()=>{setQuery('');setType('');}}>清空筛选</Button>}</div>
          <div className="ops5-section-heading"><h2>当前展示：应急变更记录</h2><small>显示 {filtered.length} / {rows.length} 条 · 最近更新在前</small></div>
          {rows.length===0 ? <div className="ops5-empty"><h3>尚无应急变更记录</h3><p>发生临时变更时，从考试计划的对应版本办理。本页不会自动生成示例记录。</p><Button onClick={()=>window.navigateTo('exam-plans')}>查看考试计划</Button></div> : filtered.length===0 ? <div className="ops5-empty"><h3>没有符合条件的记录</h3><Button onClick={()=>{setQuery('');setType('');}}>清空筛选</Button></div> : <table className="ops5-record-table"><thead><tr><th scope="col">事件</th><th scope="col">生效时间</th><th scope="col">来源版本</th><th scope="col">登记资源</th><th scope="col">操作</th></tr></thead><tbody>{filtered.map(item=>{
            const view=opsEmergencyView(item);
            return <tr key={item.id} className={item.id===selectedId ? 'is-selected' : ''}>
              <td data-label="事件"><strong>{view.type}</strong><span className="ops5-badge">{view.status}</span></td>
              <td data-label="生效时间"><span>{opsEmergencyTime(item)}</span><small>登记于 {opsDate(item.createdAt)}（北京时间）</small></td>
              <td data-label="来源版本"><strong>{view.name}</strong><span>{view.sourceVersion}</span></td>
              <td data-label="登记资源">{opsEmergencyResources(item)}</td>
              <td data-label="操作"><Button className="ops5-link" aria-pressed={item.id===selectedId} aria-haspopup="dialog" onClick={()=>select(item)}>查看详情</Button></td>
            </tr>;
          })}</tbody></table>}
        </section>
        <details className="ops5-accordion"><summary><h2>查看登记字段说明</h2><IconChevron size={20}/></summary><div className="ops5-accordion-body"><p>“记录有效”是登记状态，不代表变更已经审批、发布或解决。当前尚无关闭记录接口；请到所属计划核查。现场事件与替补事项属于不同业务记录。</p><p>生效时间来自登记资源，不以记录创建时间代替。教师、考场编号及其他名称需按来源版本核对；未提供字段保持未提供。</p></div></details>
        {selected && <window.BlueprintDrawer open={true} onClose={close} title={opsEmergencyView(selected).type} description="查看应急登记的来源与影响资源；关闭详情不会改变业务状态。" className="ops-v5 ops5-drawer">
          <p className="ops5-notice"><OpsInfoIcon/><span>这是登记记录，不代表替补已完成。</span></p>
          <section className="ops5-drawer-section"><h3>来源信息 <span className="ops5-badge">{opsEmergencyView(selected).status}</span></h3><dl className="ops5-resource-facts"><dt>计划名称</dt><dd>{opsEmergencyView(selected).name}</dd><dt>来源版本</dt><dd>{opsEmergencyView(selected).sourceVersion}</dd><dt>登记时间</dt><dd>{opsDate(selected.createdAt)}（北京时间）</dd></dl></section>
          <section className="ops5-drawer-section"><h3>影响时间</h3><p>{opsEmergencyTime(selected)}{selected.affectedResources?.effectiveAt || selected.affectedResources?.endsAt ? '（北京时间）' : ''}</p></section>
          <section className="ops5-drawer-section"><h3>登记的影响资源</h3><OpsResourceFacts value={selected.affectedResources} omitKeys={['effectiveAt','endsAt']} emptyLabel="未提供影响资源"/></section>
          <section className="ops5-drawer-section"><h3>接口记录的处理动作</h3><OpsResourceFacts value={selected.actionTaken} emptyLabel="未提供处理动作"/></section>
          <section className="ops5-drawer-section"><h3>核对建议</h3><ul><li>核对受影响岗位与登记资源</li><li>在计划版本中核对后续安排</li><li>核对重新发布和通知的实际状态</li></ul><p className="ops5-muted">来源版本是发生变更时的原版本，不是后继版本。</p></section>
          {!opsSourceTarget(selected) && <p className="ops5-notice ops5-warning" role="status"><OpsWarningIcon/><span>缺少计划或来源版本关联，无法安全跳转。</span></p>}
          <OpsRawDetails value={selected} label="编号与原始记录"/>
          <div className="ops5-drawer-actions"><Button variant="primary" disabled={!opsSourceTarget(selected)} onClick={openSource}>查看来源版本</Button><Button aria-label="关闭记录详情" onClick={close}>关闭</Button></div>
        </window.BlueprintDrawer>}
      </>}
    </div>
  </PageShell>;
}
window.ExamRisksPage = OpsRiskPage;
window.ExamEmergencyPage = OpsEmergencyPage;
