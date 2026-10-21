// Two existing routes, eight blueprint states. Never substitute fixture data here.
const OPS6_STATUS={PENDING:'待审批',PENDING_APPROVAL:'待审批',APPROVED:'已批准',REJECTED:'已退回',PUBLISHED:'已发布',EXECUTING:'执行中',CLOSED:'已结束',ARCHIVED:'已归档',REVIEW_REQUIRED:'待重新提交',READY:'待提交',INVALID:'校验未通过',INFEASIBLE:'无可行安排'};
const ops6List=value=>{if(!Array.isArray(value))throw new Error('返回格式不完整，请刷新核对。');return value;};
const ops6UserId=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)?value.toLowerCase():'';
const ops6Revision=(value,item)=>{if(!value || value.id!==item.revisionId || value.planId!==item.planId)throw new Error('版本关联不一致，已停止操作。');return value;};
function ops6WriteResult(value,item,kind){
  const revision=ops6Revision(value,item);
  const expected={approve:'APPROVED',reject:'REVIEW_REQUIRED',publish:'PUBLISHED'}[kind];
  if(!expected || revision.status!==expected || (kind==='publish'&&(!revision.publishedAt||Number.isNaN(Date.parse(revision.publishedAt)))))throw Error('返回版本未确认目标状态，请重新核对。');
  return revision;
}
const ops6Version=value=>Number.isInteger(value)?`第 ${value} 版`:'版次未提供';
function ops6DutyRoleLabel(value) {
  const raw=value==null?'':String(value).trim();
  if(!raw)return '角色未提供';
  const labels={CHIEF:'主监考',INVIGILATOR:'监考',ASSISTANT:'副监考',ROAMING:'巡考',RESERVE:'备用监考',MAIN:'主监考'};
  const code=raw.toUpperCase();
  return Object.prototype.hasOwnProperty.call(labels,code)?labels[code]:`未识别岗位（${raw}）`;
}
const ops6Date=value=>window.opsDate?window.opsDate(value):(!value?'未提供':Number.isNaN(Date.parse(value))?'时间格式异常':new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(new Date(value)));
const ops6Day=value=>!value || Number.isNaN(Date.parse(value))?'':new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(value));
function ops6Stage(item) {
  if(item.revision?.status==='APPROVED' && item.status==='APPROVED')return 'publish';
  if(item.status==='PENDING')return 'pending';
  return 'history';
}
function ops6Checked(revision){const s=revision?.validationSummary;return Boolean(s?.valid===true && Array.isArray(s.issues) && s.issues.length===0 && s.hardViolationCount===0);}
function Ops6Badge({status,children}){return <span className={`ops6-badge ${['APPROVED','PUBLISHED','DELIVERED'].includes(status)?'is-success':['REJECTED','FAILED'].includes(status)?'is-danger':'is-neutral'}`}>{children || OPS6_STATUS[status] || status || '状态未提供'}</span>;}
function Ops6State({state,onRetry,label='记录'}) {
  if(state.status==='loading')return <div className="ops6-loading" role="status" aria-live="polite"><p>正在读取{label}…</p><div/><div/><div/></div>;
  return <div className="ops6-empty" role="alert"><window.Ops6Asset name="empty" size={64}/><h2>{state.status==='permission'?'当前账号没有查看权限':`${label}加载失败`}</h2><p>{state.error?.message || '请检查网络连接后重试。'}</p><Button onClick={onRetry}>重新读取</Button></div>;
}
function Ops6Guide(){return <><ol className="ops6-flow" aria-label="审批与发布办理顺序"><li><window.Ops6Asset name="approve"/><div><strong>审批通过 → 版本待发布</strong><p>核对无误后进入发布准备</p></div></li><li><window.Ops6Asset name="publish"/><div><strong>发布成功 → 安排生效</strong><p>以成功返回的版本为准</p></div></li><li><window.Ops6Asset name="document"/><div><strong>站内投递 → 单独核对</strong><p>投递不等于已读或岗位确认</p></div></li></ol><p className="ops6-note">审批通过不等于已经发布；本机构队列不是个人派单。</p></>;}
async function ops6Queue() {
  const rows=ops6List(await window.api.operations.approvals());
  // Bounded parallel joins; the approval record cursor is never a revision cursor.
  let next=0;const result=new Array(rows.length);const cache=new Map();
  await Promise.all(Array.from({length:Math.min(6,rows.length)},async()=>{while(next<rows.length){const index=next++;const item=rows[index];try{
    if(!item.planId || !item.revisionId)throw new Error('缺少计划或版本关联');
    const key=`${item.planId}/${item.revisionId}`;
    if(!cache.has(key))cache.set(key,window.api.examPlans.revision(item.planId,item.revisionId));
    result[index]={...item,revision:ops6Revision(await cache.get(key),item)};
  }catch(error){result[index]={...item,joinError:error.message};}}}));
  return result.sort((a,b)=>(Date.parse(b.createdAt)||0)-(Date.parse(a.createdAt)||0));
}
function Ops6ApprovalPage(){
  const loader=React.useCallback(ops6Queue,[]);const [state,reload]=useLiveResource(loader,[loader]);
  const [stage,setStage]=React.useState('pending');const [query,setQuery]=React.useState('');const [plan,setPlan]=React.useState('');const [date,setDate]=React.useState('');const [page,setPage]=React.useState(1);const [selection,setSelection]=React.useState(null);
  const opener=React.useRef(null);const returnFocus=React.useRef(false);
  React.useEffect(()=>{if(returnFocus.current&&!selection&&state.status==='ready'){returnFocus.current=false;(document.getElementById(opener.current)||document.querySelector('.ops6-stages button[aria-pressed="true"]'))?.focus();}},[selection,state.status]);
  const rows=state.data || [];const filtered=rows.filter(x=>(!plan||x.planId===plan)&&(!date||ops6Day(x.createdAt)===date)&&`${x.planName||''} ${x.requestedByName||''}`.toLowerCase().includes(query.trim().toLowerCase()));
  const current=filtered.filter(x=>ops6Stage(x)===stage);const pages=Math.max(1,Math.ceil(current.length/8));const safePage=Math.min(page,pages);
  const choose=(item,event)=>{opener.current=event.currentTarget.id;setSelection(item);};
  const back=()=>{returnFocus.current=true;setSelection(null);reload();};
  if(selection)return <Ops6ApprovalReview key={selection.id} item={selection} onBack={back}/>;
  return <div className="ops6"><PageShell routeId="approvals" crumbs={[orgName(),'结果与档案','审批与发布']} title="审批与发布" subtitle="先核查版本，再审批和发布" actions={<Button disabled={state.status==='loading'} onClick={reload}>刷新</Button>}><div className="ops6-approvals">
    <p className="ops6-note">本机构版本队列 · 提交时间均为北京时间，不随顶部学期切换</p>
    {state.status!=='ready'?<Ops6State state={state} onRetry={reload} label="审批队列"/>:<>
      <div className="ops6-stages" aria-label="按办理阶段筛选">{[['pending','待审批','document'],['publish','待发布','publish'],['history','处理记录','clock']].map(([key,label,asset])=><button type="button" key={key} aria-pressed={stage===key} onClick={()=>{setStage(key);setPage(1);}} className={stage===key?'is-selected':''}><window.Ops6Asset name={asset}/><span>{label}</span><strong>{filtered.filter(x=>ops6Stage(x)===key).length}</strong></button>)}</div>
      <div className="ops6-filters"><label className="ops6-search"><span>搜索计划或提交人</span><input value={query} onChange={e=>{setQuery(e.target.value);setPage(1);}} placeholder="输入计划名称或提交人"/></label><label><span>考试计划</span><select value={plan} onChange={e=>{setPlan(e.target.value);setPage(1);}}><option value="">全部计划</option>{[...new Map(rows.map(x=>[x.planId,x.planName||'名称未提供'])).entries()].map(([id,name])=><option key={id} value={id}>{name}</option>)}</select></label><label><span>提交日期</span><input type="date" value={date} onChange={e=>{setDate(e.target.value);setPage(1);}}/></label>{(query||plan||date)&&<Button onClick={()=>{setQuery('');setPlan('');setDate('');setPage(1);}}>清除筛选</Button>}</div>
      {rows.some(x=>x.joinError)&&<p className="ops6-warning" role="status">部分版本暂未核实，待发布数量不包含未核实版本。请刷新后重试。</p>}
      <section className="ops6-panel ops6-queue" aria-label="当前阶段版本列表">{!current.length?<div className="ops6-empty"><window.Ops6Asset name="empty" size={76}/><h2>{query||plan||date?'没有符合筛选条件的版本':stage==='pending'?'暂无待审批版本':stage==='publish'?'暂无待发布版本':'暂无处理记录'}</h2><p>{rows.length?'可切换办理阶段或调整筛选。':'提交审批后，对应版本会显示在这里。'}</p>{!rows.length&&<Button onClick={()=>window.navigateTo('exam-plans')}>查看考试计划</Button>}</div>:<table className="ops6-table"><thead><tr><th>计划与版本</th><th>提交人</th><th>提交时间</th><th>审批 / 版本状态</th><th>下一步</th></tr></thead><tbody>{current.slice((safePage-1)*8,safePage*8).map(item=><tr key={item.id}><td data-label="计划与版本"><div className="ops6-plan-name"><window.Ops6Asset name="document"/><div><strong>{item.planName||'计划名称未提供'}</strong><small>{ops6Version(item.revision?.revisionNo ?? item.revisionNo)}{item.revision?.changeReason?` · ${item.revision.changeReason}`:''}</small></div></div></td><td data-label="提交人">{item.requestedByName||'姓名未提供'}</td><td data-label="提交时间"><time>{ops6Date(item.createdAt)}</time></td><td data-label="审批 / 版本状态"><Ops6Badge status={item.status}/>{(item.joinError||OPS6_STATUS[item.status]!==OPS6_STATUS[item.revision?.status])&&<small>{item.joinError?'版本待核查':`版本：${OPS6_STATUS[item.revision?.status]||'状态未提供'}`}</small>}</td><td data-label="下一步"><Button id={`ops6-request-${item.id}`} className="ops6-outline" disabled={!item.planId||!item.revisionId} onClick={event=>choose(item,event)}>{stage==='pending'?'审阅此版本':stage==='publish'?'准备发布':'查看此版本'}</Button></td></tr>)}</tbody></table>}
      <footer className="ops6-pagination"><span>当前阶段共 {current.length} 个版本</span>{pages>1&&<div><Button aria-label="上一页" disabled={safePage===1} onClick={()=>setPage(safePage-1)}>上一页</Button><span>{safePage} / {pages}</span><Button aria-label="下一页" disabled={safePage===pages} onClick={()=>setPage(safePage+1)}>下一页</Button></div>}</footer></section><Ops6Guide/>
    </>}
  </div></PageShell></div>;
}
function Ops6Validation({revision}) {
  const s=revision.validationSummary;
  const complete=typeof s?.valid==='boolean'&&Array.isArray(s.issues);
  return <section className="ops6-section">
    <h2><window.Ops6Asset name="approve" size={30}/>校验结果</h2>
    <p className="ops6-note">校验对象：{ops6Version(revision.revisionNo)}。记录未提供校验时间，不以提交时间替代。</p>
    {!complete?<p className="ops6-warning">尚未返回完整校验结果，请到来源版本核查。</p>:<>
      <p className={ops6Checked(revision)?'ops6-success':'ops6-warning'}>{ops6Checked(revision)?'此版本最近一次校验通过':'此版本校验结果需要核查'}</p>
      <dl className="ops6-facts"><dt>硬约束问题</dt><dd>{Number.isInteger(s.hardViolationCount)?s.hardViolationCount:'未提供'}</dd><dt>返回问题记录</dt><dd>{s.issues.length} 条</dd></dl>
      {s.issues.length>0&&<ul className="ops6-issues">{s.issues.map((x,i)=><li key={i}>{x.message||x.code||'问题描述未提供'}{x.suggestion&&<p>{x.suggestion}</p>}</li>)}</ul>}
    </>}
    <details><summary>查看完整校验记录</summary><pre>{JSON.stringify(s||{},null,2)}</pre></details>
  </section>;
}
function ops6SessionLabel(row) {
  return [row.session_name,row.sessionName,row.subject_name,row.subjectName,row.session_external_code,row.sessionCode].find(x=>typeof x==='string'&&x.trim())?.trim()||'场次未提供';
}
function ops6RoomLabel(row) {
  const name=String(row.room_name??row.roomName??'').trim(),code=String(row.room_code??row.roomCode??'').trim();
  return name&&code&&name!==code?`${name}（${code}）`:name||code;
}
function ops6DutyDiff(before,after) {
  const groups=new Map();let uncomparable=0;
  const field=(x,a,b)=>x[a]??x[b];
  for(const [side,rows] of [['before',before],['after',after]]) for(const x of rows) {
    const session=x.session_external_code||x.sessionCode;
    const role=x.duty_role||x.dutyRole||x.role;
    const starts=field(x,'starts_at','startsAt'),ends=field(x,'ends_at','endsAt');
    const teacher=field(x,'teacher_no','teacherNo');
    if(!session||!role||!starts||!ends||!teacher){uncomparable++;continue;}
    const room=x.room_code||x.roomCode||'';
    const key=JSON.stringify([session,room,role,starts,ends]);
    if(!groups.has(key))groups.set(key,{key,session,room,role,starts,ends,sessionLabel:ops6SessionLabel(x),roomLabel:ops6RoomLabel(x),before:[],after:[]});
    if(side==='after'){groups.get(key).sessionLabel=ops6SessionLabel(x);groups.get(key).roomLabel=ops6RoomLabel(x);}
    const name=field(x,'teacher_name','teacherName');
    groups.get(key)[side].push({teacher,label:name?`${name}（${teacher}）`:teacher});
  }
  const changed=[...groups.values()].filter(g=>JSON.stringify(g.before.map(x=>x.teacher).sort())!==JSON.stringify(g.after.map(x=>x.teacher).sort()));
  return {changed,uncomparable};
}
function Ops6DutyComparison({data,revision}) {
  const {changed,uncomparable}=ops6DutyDiff(data.before,data.after);
  return <>
    {uncomparable>0&&<p className="ops6-warning">{uncomparable} 条安排缺少场次、时段、角色或教师工号，未纳入自动对比，请核对来源。</p>}
    {changed.length?<table className="ops6-table ops6-diff-table"><thead><tr><th>变更范围</th><th>来源{ops6Version(data.source.revisionNo)}</th><th>当前{ops6Version(revision.revisionNo)}</th></tr></thead><tbody>{changed.map(g=><tr key={g.key}><td data-label="变更范围"><strong>{g.sessionLabel} · {ops6DutyRoleLabel(g.role)}</strong><small>{ops6Date(g.starts)} 至 {ops6Date(g.ends)}</small><small>{g.roomLabel?`考场：${g.roomLabel}`:'考场信息未提供'}</small></td><td data-label="来源版本">{g.before.length?g.before.map(x=>x.label).join('、'):'该组无安排'}</td><td data-label="当前版本"><strong>{g.after.length?g.after.map(x=>x.label).join('、'):'该组无安排'}</strong></td></tr>)}</tbody></table>:<p>已返回的可比字段未发现人员变化；不代表完整安排完全相同。</p>}
    <p className="ops6-note">按场次、时段、角色及已提供的考场分组比较人员名单，不把同组人员逐一推断为替换关系。</p>
    <details><summary>展开两版原始安排</summary><div className="ops6-raw-compare"><section><h3>来源版本</h3><pre>{JSON.stringify(data.before,null,2)}</pre></section><section><h3>当前版本</h3><pre>{JSON.stringify(data.after,null,2)}</pre></section></div></details>
  </>;
}
function Ops6Changes({revision,item}){
  const load=React.useCallback(async()=>{
    if(!revision.predecessorId)return {initial:true};
    const api=window.api.examPlans;const [source,before,after]=await Promise.all([api.revision(item.planId,revision.predecessorId),api.assignments(revision.predecessorId),api.assignments(item.revisionId)]);
    if(source.id!==revision.predecessorId||source.planId!==item.planId)throw Error('来源版本关联不一致');
    return {source,before:ops6List(before),after:ops6List(after)};
  },[revision.predecessorId,item.planId,item.revisionId]);
  const [state,reload]=useLiveResource(load,[load]);
  return <section className="ops6-section"><h2><window.Ops6Asset name="document" size={30}/>本次调整</h2>{state.status!=='ready'?<div><p className="ops6-note">{state.status==='loading'?'正在读取关联版本…':'版本对比暂不可用，可查看准确来源安排。'}</p>{state.status!=='loading'&&<Button onClick={reload}>重试对比</Button>}</div>:state.data.initial?<p>此版本未提供前一版本关联，无法进行前后对比。</p>:<Ops6DutyComparison data={state.data} revision={revision}/>}</section>;
}
function Ops6Deliveries({revision,item,onView}) {
  const load=React.useCallback(async()=>ops6List(await window.api.get('/operations/notification-deliveries',{revisionId:item.revisionId})).filter(x=>x.revisionId===item.revisionId),[item.revisionId]);
  const [state,reload]=useLiveResource(load,[load]);const [filter,setFilter]=React.useState('');const [query,setQuery]=React.useState('');
  const rows=state.data||[];const visible=rows.filter(x=>(!filter||x.status===filter)&&String(x.recipientId||'').toLowerCase().includes(query.trim().toLowerCase()));
  return <>
    <section className="ops6-publication-banner">
      <window.Ops6Asset name="publish" size={60}/><div><h2>{ops6Version(revision.revisionNo)}已有发布记录</h2><p>发布时间：{ops6Date(revision.publishedAt)}（北京时间）</p><p className="ops6-note">发布时此版安排生效，历史版本保留只读。不代表此版始终是当前生效版本。</p></div>
      <Button className="ops6-outline" onClick={onView}>查看已发布安排</Button>
    </section>
    <section className="ops6-panel ops6-deliveries">
      <header className="ops6-inline-heading"><div><h2>通知投递记录</h2><p>投递、已读与接受监考岗位分别核对</p></div><Button onClick={reload} disabled={state.status==='loading'}>刷新投递</Button></header>
      {state.status!=='ready'?<Ops6State state={state} onRetry={reload} label="通知投递"/>:<>
        {rows.some(x=>x.status==='FAILED')&&<p className="ops6-warning" role="status">仍有 {rows.filter(x=>x.status==='FAILED').length} 条投递失败；不改变版本已有的发布结果。</p>}
        <div className="ops6-delivery-toolbar"><div className="ops6-delivery-stats" aria-label="按通知投递状态筛选">{[['','全部'],['PENDING','待投递'],['DELIVERED','已投递'],['FAILED','投递失败']].map(([key,label])=><button type="button" key={key} aria-pressed={filter===key} onClick={()=>setFilter(key)}>{label}<strong>{rows.filter(x=>!key||x.status===key).length}</strong></button>)}</div><label><span>搜索收件人标识</span><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="输入收件人标识"/></label></div>
        {!visible.length?<div className="ops6-empty"><h3>{query?'没有匹配的收件人':'当前范围没有投递记录'}</h3><p>没有记录不代表收件人已收到通知。</p>{(query||filter)&&<Button onClick={()=>{setQuery('');setFilter('');}}>清除投递筛选</Button>}</div>:<table className="ops6-table"><thead><tr><th>收件人标识</th><th>渠道</th><th>投递状态</th><th>已读状态</th><th>最近尝试</th><th>说明</th></tr></thead><tbody>{visible.map(x=><tr key={x.id}>
          <td data-label="收件人标识">{x.recipientId||'未提供'}</td>
          <td data-label="渠道">{x.channel==='IN_APP'?'站内通知':x.channel||'未提供'}</td>
          <td data-label="投递状态"><Ops6Badge status={x.status}>{({PENDING:'待投递',DELIVERED:'已投递',FAILED:'投递失败'})[x.status]||x.status}</Ops6Badge>{x.deliveredAt&&<small>投递于 {ops6Date(x.deliveredAt)}</small>}</td>
          <td data-label="已读状态">{x.readAt?`已读 ${ops6Date(x.readAt)}`:x.status==='PENDING'?'尚未投递':'暂无已读回执'}</td>
          <td data-label="最近尝试">{x.lastAttemptAt?ops6Date(x.lastAttemptAt):'未提供尝试时间'}</td>
          <td data-label="说明">{x.failureReason==='CHANNEL_NOT_CONFIGURED'?'通知渠道未配置':x.failureReason||'未返回失败说明'}</td>
        </tr>)}</tbody></table>}
        <p className="ops6-note">接口当前仅提供收件人标识，不推测教师姓名。本页不触发机构批量投递，也不提供单人重试。</p>
      </>}
    </section>
  </>;
}
function Ops6VersionArrangement({item,revision,onClose}) {
  const load=React.useCallback(async()=>{
    ops6Revision(await window.api.examPlans.revision(item.planId,item.revisionId),item);
    return ops6List(await window.api.examPlans.assignments(item.revisionId));
  },[item.planId,item.revisionId]);
  const [state,reload]=useLiveResource(load,[load]);
  return <window.BlueprintDrawer className="ops6 ops6-version-drawer" title={`${ops6Version(revision.revisionNo)} · 监考安排`} description="只读核查此版本，不跳转或自动切换最新版。" onClose={onClose} footer={<Button onClick={onClose}>返回版本审阅</Button>}>
    <div className="ops6-object"><strong>{item.planName}</strong><p>{ops6Version(revision.revisionNo)} · {OPS6_STATUS[revision.status]||revision.status}</p></div>
    {state.status!=='ready'?<Ops6State state={state} onRetry={reload} label="版本安排"/>:!state.data.length?<p className="ops6-empty">此版本未返回监考安排。</p>:<table className="ops6-table"><thead><tr><th>场次与时段</th><th>监考教师</th><th>岗位</th></tr></thead><tbody>{state.data.map((x,i)=><tr key={x.id||i}><td data-label="场次与时段"><strong>{ops6SessionLabel(x)}</strong><small>{ops6Date(x.starts_at||x.startsAt)} 至 {ops6Date(x.ends_at||x.endsAt)}</small><small>{ops6RoomLabel(x)?`考场：${ops6RoomLabel(x)}`:'考场信息未提供'}</small></td><td data-label="监考教师">{x.teacher_name||x.teacherName||'姓名未提供'}<small>{x.teacher_no||x.teacherNo||'工号未提供'}</small></td><td data-label="岗位">{ops6DutyRoleLabel(x.duty_role||x.dutyRole||x.role)}</td></tr>)}</tbody></table>}
    <p className="ops6-note">考场名称与编码来自当前基础资料，不代表发布时的名称快照。未提供的关联不作推断。安排仅为当前查询到的版本记录，不表示教师已接受岗位。</p>
  </window.BlueprintDrawer>;
}
function ops6AuditPage(value,item,cursor) {
  if(!value||value.planId!==item.planId||value.revisionId!==item.revisionId||value.source!=='EXAM_PLAN_AUDIT_EVENT'||!Array.isArray(value.items)||value.items.length>20||typeof value.hasMore!=='boolean')throw Error('审计响应范围或格式不完整');
  if(value.hasMore?(typeof value.nextCursor!=='string'||!value.nextCursor||value.nextCursor.length>1024||value.nextCursor===cursor):value.nextCursor!==null)throw Error('审计分页信息不完整');
  const ids=new Set();
  for(const row of value.items){
    if(!row||!ops6UserId(row.id)||ids.has(row.id)||typeof row.action!=='string'||!row.action.trim()||typeof row.occurredAt!=='string'||Number.isNaN(Date.parse(row.occurredAt))||(row.actorDisplayName!==null&&typeof row.actorDisplayName!=='string'))throw Error('审计记录格式不完整');
    ids.add(row.id);
  }
  return value;
}
function ops6AuditAction(action) {
  const labels={PLAN_CREATED:'创建计划',REVISION_CREATED:'创建版本',REVISION_VALIDATED:'校验版本',APPROVAL_SUBMITTED:'提交审批',APPROVAL_APPROVED:'批准版本',APPROVAL_REJECTED:'退回审批',REVISION_PUBLISHED:'发布版本',REVISION_EXECUTING:'开始执行',REVISION_CLOSED:'结束执行',REVISION_ARCHIVED:'归档版本',DUTY_ACCEPTED:'接受监考岗位',DUTY_REJECTED:'拒绝监考岗位',DUTY_CHECKED_IN:'监考签到',SUBSTITUTION_CASE_OPENED:'建立替补事项',SUBSTITUTION_RESOLVED:'处理替补事项',INCIDENT_RECORDED:'记录考务事件',INCIDENT_RESOLVED:'处理考务事件',CHECKLIST_ITEM_COMPLETED:'完成检查项',CHECKLIST_ITEM_WAIVED:'豁免检查项',EMERGENCY_SUCCESSOR_CREATED:'创建应急后继版本'};
  return Object.prototype.hasOwnProperty.call(labels,action)?labels[action]:`未识别操作（${action}）`;
}
function Ops6AuditHistory({item}) {
  const me=window.__jkxtMe;
  const canRead=Boolean(ops6UserId(me?.user?.id)&&ops6UserId(me?.org?.id)&&ops6UserId(item.planId)&&ops6UserId(item.revisionId)&&['ADMIN','SUPER_ADMIN'].includes(me?.user?.role)&&Array.isArray(me?.permissions)&&me.permissions.includes('tenant:read'));
  const scope=JSON.stringify([me?.org?.id,me?.user?.id,me?.user?.role,canRead,item.planId,item.revisionId]);
  const empty={scope,open:false,phase:'idle',items:[],hasMore:false,nextCursor:null,error:'',failedMore:false};
  const [state,setState]=React.useState(empty);
  const request=React.useRef({scope,sequence:0,locked:false,mounted:true});
  // Hide the previous identity's records in the very first render, before effects run.
  if(request.current.scope!==scope){request.current.scope=scope;request.current.sequence++;request.current.locked=false;}
  const view=state.scope===scope?state:empty;
  React.useEffect(()=>{request.current.mounted=true;return()=>{request.current.mounted=false;request.current.sequence++;request.current.locked=false;};},[]);
  const load=async(more=false)=>{
    if(!canRead||request.current.locked||request.current.scope!==scope||(more&&!view.hasMore))return;
    request.current.locked=true;const sequence=++request.current.sequence;const cursor=more?view.nextCursor:null;
    const current=()=>request.current.mounted&&request.current.scope===scope&&request.current.sequence===sequence;
    setState({...view,scope,open:true,phase:more?'more':'loading',items:more?view.items:[],error:'',failedMore:false});
    try{
      const result=ops6AuditPage(await window.api.examPlans.auditEvents(item.planId,item.revisionId,{limit:20,...(cursor?{cursor}:{})}),item,cursor);
      if(!current())return;
      const rows=new Map((more?view.items:[]).map(x=>[x.id,x]));for(const row of result.items)rows.set(row.id,row);
      setState(latest=>({scope,open:latest.scope===scope&&latest.open,phase:'ready',items:[...rows.values()],hasMore:result.hasMore,nextCursor:result.nextCursor,error:'',failedMore:false}));
    }catch(error){
      if(!current())return;
      const denied=error?.status===401||error?.status===403;
      const unavailable=denied||error?.status===404;
      const message=denied?'当前会话或权限不能读取操作记录，请核对登录身份。':error?.status===404?'此计划版本不可用，无法读取操作记录。':'操作记录读取失败或返回格式不完整，请重试。';
      setState(latest=>({...view,scope,open:latest.scope===scope&&latest.open,phase:'error',items:unavailable?[]:more?view.items:[],hasMore:unavailable?false:view.hasMore,nextCursor:unavailable?null:view.nextCursor,error:message,failedMore:more&&!unavailable}));
    }finally{if(current())request.current.locked=false;}
  };
  const toggle=event=>{const open=event.currentTarget.open;if(open&&view.phase==='idle')load();else setState({...view,scope,open});};
  const busy=view.phase==='loading'||view.phase==='more';
  return <details className="ops6-panel ops6-history" open={view.open} onToggle={toggle}><summary>本版本已记录操作</summary>
    <p className="ops6-note">仅显示本版本已写入考务审计表的操作，不含全部数据变更或完整前后状态。无记录不代表未发生操作。较新的记录请刷新查看。</p>
    {!canRead?<p className="ops6-warning">当前账号身份或读取权限未能核实，不能读取操作记录。</p>:view.open&&<>
      <Button onClick={()=>load()} disabled={busy}>刷新操作记录</Button>
      {busy&&<p role="status">{view.phase==='more'?'正在读取更多操作记录…':'正在读取操作记录…'}</p>}
      {view.error&&<div role="alert"><p className="ops6-error">{view.error}</p><Button onClick={()=>load(view.failedMore)} disabled={busy}>{view.failedMore?'重试加载更多':'重试读取'}</Button></div>}
      {view.items.length>0&&<table className="ops6-table"><thead><tr><th>记录时间（北京时间）</th><th>操作</th><th>操作人</th></tr></thead><tbody>{view.items.map(row=><tr key={row.id} data-event-id={row.id}><td data-label="记录时间">{ops6Date(row.occurredAt)}</td><td data-label="操作">{ops6AuditAction(row.action)}</td><td data-label="操作人">{row.actorDisplayName?.trim()||'操作人未提供'}</td></tr>)}</tbody></table>}
      {view.phase==='ready'&&!view.items.length&&<p className="ops6-empty">当前来源未返回本版本的操作记录。</p>}
      {view.hasMore&&!view.error&&<Button onClick={()=>load(true)} disabled={busy}>加载更多</Button>}
      {view.phase==='ready'&&!view.hasMore&&view.items.length>0&&<p>已到当前返回记录末尾；不代表完整历史。</p>}
      <p className="ops6-note">操作人名称为当前账户名称，未提供时不推断身份。此区域只读，不支持修改或撤销历史。</p>
    </>}
  </details>;
}
function Ops6ApprovalReview({item,onBack}) {
  const [busy,setBusy]=React.useState('');const lock=React.useRef(false);const mounted=React.useRef(true);const [reason,setReason]=React.useState('');const [error,setError]=React.useState('');const [message,setMessage]=React.useState('');const [uncertain,setUncertain]=React.useState(false);const [publishOpen,setPublishOpen]=React.useState(false);const [checked,setChecked]=React.useState(false);const [decision,setDecision]=React.useState('');
  const headingRef=React.useRef(null);const [sourceOpen,setSourceOpen]=React.useState(false);
  React.useEffect(()=>{mounted.current=true;headingRef.current?.focus({preventScroll:true});headingRef.current?.closest('.page-shell')?.scrollIntoView({block:'start',behavior:'instant'});return()=>{mounted.current=false;};},[]);
  const load=React.useCallback(async()=>{
    const [revision,history]=await Promise.all([window.api.examPlans.revision(item.planId,item.revisionId),window.api.operations.approvals()]);
    return {revision:ops6Revision(revision,item),history:ops6List(history).filter(x=>x.planId===item.planId&&x.revisionId===item.revisionId)};
  },[item.planId,item.revisionId]);
  const [state,reload]=useLiveResource(load,[load]);const revision=state.data?.revision;const history=state.data?.history||[];
  React.useEffect(()=>{if(message&&state.status==='ready'){headingRef.current?.focus({preventScroll:true});headingRef.current?.closest('.page-shell')?.scrollIntoView({block:'start',behavior:'instant'});}},[message,state.status]);
  const request=history.find(x=>x.id===item.id);const user=window.__jkxtMe?.user;
  const actorId=ops6UserId(user?.id),requesterId=ops6UserId(request?.requestedBy);const self=Boolean(actorId&&requesterId===actorId);
  const canManageSchedule=Array.isArray(window.__jkxtMe?.permissions)&&window.__jkxtMe.permissions.includes('schedule:manage');
  const pending=request?.status==='PENDING'&&revision?.status==='PENDING_APPROVAL';const approved=request?.status==='APPROVED'&&revision?.status==='APPROVED';
  const cursorReady=Number.isInteger(revision?.revisionCursor)&&revision.revisionCursor>=0;
  const canDecide=pending&&!self&&Boolean(actorId&&requesterId)&&canManageSchedule&&cursorReady&&reason.trim().length>0&&!busy&&!uncertain&&state.status==='ready';
  const canPublish=approved&&Boolean(actorId)&&canManageSchedule&&cursorReady&&ops6Checked(revision)&&!busy&&!uncertain&&state.status==='ready';
  const refresh=async()=>{setDecision('');setPublishOpen(false);setError('');await reload();if(mounted.current)setUncertain(false);};
  const execute=async(kind)=>{
    if(lock.current || (kind==='publish'?(!canPublish||!checked):!canDecide))return;
    lock.current=true;setBusy(kind);setError('');setMessage('');
    try{
      // Re-read exact object immediately before deciding, never silently switch versions.
      const latest=ops6Revision(await window.api.examPlans.revision(item.planId,item.revisionId),item);
      if(latest.revisionCursor!==revision.revisionCursor||latest.status!==revision.status)throw Object.assign(Error('版本已变化'),{status:409});
      const headers={'If-Match':String(revision.revisionCursor)};
      const result=kind==='publish'?await window.api.examPlans.publish(item.planId,item.revisionId,{headers}):await window.api.examPlans[kind](item.planId,item.revisionId,{reason:reason.trim()},{headers});
      ops6WriteResult(result,item,kind);
      if(!mounted.current)return;
      setMessage(kind==='publish'?'发布请求已成功返回，请核对下方发布记录与投递情况。':kind==='approve'?'此版本已批准，仍需单独确认发布。':'审批已退回，请在来源版本修改后重新提交。');setDecision('');setPublishOpen(false);setReason('');await reload();
    }catch(e){if(mounted.current){const u=!e.status||e.status>=500||[408,409,412].includes(e.status);setUncertain(u);setError(u?'请求结果或版本状态需要核对，请刷新后确认，勿重复提交。':e.message||'操作未完成，请核对权限与版本状态。');}}
    finally{lock.current=false;if(mounted.current)setBusy('');}
  };
  const openSource=()=>setSourceOpen(true);
  return <div className="ops6"><PageShell routeId="approvals" crumbs={[orgName(),'审批与发布',revision?.publishedAt?'发布记录':'审阅版本']} title={item.planName||'审阅版本'} subtitle={revision?.publishedAt?'发布事实与通知投递分开核对':'仅操作当前版本，审批不会直接发布'} actions={<Button disabled={Boolean(busy)} onClick={onBack}>返回版本队列</Button>}><div className="ops6-review" ref={headingRef} tabIndex={-1}>
    {state.status!=='ready'?<Ops6State state={state} onRetry={refresh} label="版本详情"/>:<>
      <header className="ops6-review-meta"><div><Ops6Badge status={revision.status}/><strong>{ops6Version(revision.revisionNo)}</strong></div><dl><dt>提交人</dt><dd>{request?.requestedByName||'未提供'}</dd><dt>提交时间</dt><dd>{ops6Date(request?.createdAt)}</dd><dt>当前操作人</dt><dd>{user?.displayName||'身份未提供'}</dd></dl></header>
      {message&&<p className="ops6-success" role="status">{message}</p>}{error&&<p className="ops6-error" role="alert">{error}</p>}{uncertain&&<Button onClick={refresh} disabled={Boolean(busy)}>刷新并核对状态</Button>}
      {revision.publishedAt?<Ops6Deliveries revision={revision} item={item} onView={openSource}/>:<div className="ops6-review-grid"><div className="ops6-panel"><Ops6Changes revision={revision} item={item}/><Ops6Validation revision={revision}/><section className="ops6-section"><h2>提交说明</h2><p>{revision.changeReason||'当前版本未提供变更说明。'}</p><Button onClick={openSource}>查看此版本安排</Button></section></div>
      <aside className="ops6-panel ops6-decision"><h2><window.Ops6Asset name="approve" size={30}/>{approved?'发布准备':'审批决定'}</h2><div className="ops6-object"><small>当前操作对象</small><strong>{item.planName||'名称未提供'} / {ops6Version(revision.revisionNo)}</strong></div>
      {!canManageSchedule&&<p className="ops6-warning">当前账号没有审批和发布权限，请由具备权限的人员处理。</p>}
      {((pending&&!requesterId)||((pending||approved)&&!actorId))&&<p className="ops6-warning">当前操作人或提交人身份未能核实，不能提交审批决定或继续发布。</p>}
      {pending?<><label className="ops6-opinion"><span>审批意见（必填）</span><textarea value={reason} maxLength={2000} rows={6} disabled={Boolean(busy)||uncertain} onChange={e=>{setReason(e.target.value);setDecision('');}} placeholder="请填写核对依据或退回原因" required/></label><p className="ops6-note">提交人不能审批自己的申请，批准和退回均受此限制。</p>{self&&<p className="ops6-warning">这是您提交的申请，请由其他具备审批权限的人员处理。</p>}{!user?.id&&<p className="ops6-warning">当前操作人身份未提供，不能提交审批决定。</p>}
      {decision?<div className="ops6-confirm-inline" role="group" aria-label="确认审批决定"><strong>{decision==='approve'?'确认批准此版本？':'确认退回修改？'}</strong><p>对象：{ops6Version(revision.revisionNo)}。意见：{reason}</p><div className="ops6-actions"><Button disabled={Boolean(busy)} onClick={()=>setDecision('')}>取消</Button><Button variant="primary" disabled={!canDecide} loading={Boolean(busy)} onClick={()=>execute(decision)}>{decision==='approve'?'确认批准':'确认退回'}</Button></div></div>:<div className="ops6-actions"><Button disabled={!canDecide} onClick={()=>setDecision('reject')}>退回修改</Button><Button variant="primary" disabled={!canDecide} onClick={()=>setDecision('approve')}>批准此版本</Button></div>}<p className="ops6-note">批准后进入待发布，不会自动发布或发送通知。</p></>:approved?<><p>此版本已批准。请再次确认版次、校验结果和发布影响。</p><dl className="ops6-facts"><dt>版本审批</dt><dd>已批准</dd><dt>最近校验</dt><dd>{ops6Checked(revision)?'通过':'待核查'}</dd></dl>{!ops6Checked(revision)&&<p className="ops6-warning">完整校验结果尚未通过，不能确认发布。</p>}{!user?.id&&<p className="ops6-warning">当前操作人身份未提供，不能确认发布。</p>}<Button variant="primary" disabled={!canPublish} onClick={()=>{setChecked(false);setPublishOpen(true);}}>发布此版本</Button><p className="ops6-note">发布后为只读版本，后续调整须创建后继版本。</p></>:<><p>{request?.status==='PENDING'?'审批记录与版本状态不一致，请刷新核对。':'此记录当前不可提交审批或发布决定。'}</p><Button onClick={refresh}>刷新版本状态</Button><p className="ops6-note">所有操作固定到当前版次，不自动切换最新版。</p></>}
      </aside></div>}
      {revision.publishedAt&&<details className="ops6-panel ops6-history"><summary>查看版本信息与校验</summary><Ops6Changes revision={revision} item={item}/><Ops6Validation revision={revision}/></details>}
      <details className="ops6-panel ops6-history"><summary>审批记录与版本编号</summary>{history.map(x=><article key={x.id}><strong>{OPS6_STATUS[x.status]||x.status}</strong><p>提交：{x.requestedByName||'未提供'} · {ops6Date(x.createdAt)}</p>{x.decidedAt&&<p>决定：{x.decidedByName||'未提供'} · {ops6Date(x.decidedAt)}</p>}<p>{x.decisionReason||'未提供审批意见'}</p></article>)}<dl className="ops6-facts"><dt>计划标识</dt><dd>{item.planId}</dd><dt>版本标识</dt><dd>{item.revisionId}</dd><dt>版本游标</dt><dd>{revision.revisionCursor}</dd></dl></details>
      <Ops6AuditHistory item={item}/>
    </>}
    {sourceOpen&&revision&&<Ops6VersionArrangement item={item} revision={revision} onClose={()=>setSourceOpen(false)}/>}
    {publishOpen&&revision&&<window.BlueprintDialog className="ops6 ops6-publish-dialog" title={`确认发布${ops6Version(revision.revisionNo)}`} description="发布仅影响此计划的此版本，请核对后再确认。" busy={Boolean(busy)} onClose={()=>setPublishOpen(false)} footer={<><Button disabled={Boolean(busy)} onClick={()=>setPublishOpen(false)}>取消</Button><Button variant="primary" disabled={!canPublish||!checked} loading={busy==='publish'} onClick={()=>execute('publish')}>确认发布</Button></>}><div className="ops6-object"><strong>{item.planName} / {ops6Version(revision.revisionNo)}</strong><p>本次操作日期：{ops6Day(new Date().toISOString())}（北京时间）</p></div><h3>发布前核对</h3><dl className="ops6-facts"><dt>审批状态</dt><dd>已批准</dd><dt>最近校验</dt><dd>{ops6Checked(revision)?'通过':'需核查'}</dd><dt>硬约束问题</dt><dd>{revision.validationSummary?.hardViolationCount ?? '未提供'}</dd></dl><h3>发布后的变化</h3><ul><li>此版监考安排在授权范围内生效。</li><li>版本只读，修改需要创建后继版本。</li><li>通知投递另行核对，不代表已读或接受岗位。</li></ul><label className="ops6-consent"><input type="checkbox" checked={checked} disabled={Boolean(busy)} onChange={e=>setChecked(e.target.checked)}/><span>我已核对计划、版次与上述影响，确认发布此版本。</span></label>{error&&<p role="alert" className="ops6-error">{error}</p>}{uncertain&&<Button disabled={Boolean(busy)} onClick={refresh}>刷新并核对状态</Button>}</window.BlueprintDialog>}
  </div></PageShell></div>;
}
window.Ops6ApprovalModel=Object.freeze({stage:ops6Stage,checked:ops6Checked,day:ops6Day,diff:ops6DutyDiff,writeResult:ops6WriteResult});
window.ExamApprovalsPage=Ops6ApprovalPage;
