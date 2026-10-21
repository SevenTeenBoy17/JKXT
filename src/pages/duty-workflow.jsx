// Real permission-scoped operations: no samples, optimistic writes or automatic POST retry.
function dutyPermission(permission, profile = window.__jkxtMe) {
  return Array.isArray(profile?.permissions) && profile.permissions.includes(permission);
}
function dutySessionKey() {
  const me = window.__jkxtMe;
  return `${me?.org?.id || ''}:${me?.user?.id || ''}:${(me?.permissions || []).join(',')}`;
}
function dutyDate(value) {
  if (!value || Number.isNaN(new Date(value).getTime())) return '时间未提供';
  return new Intl.DateTimeFormat('zh-CN', {timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hourCycle:'h23'}).format(new Date(value));
}
function dutyRole(role) {
  return ({CHIEF:'主监考',INVIGILATOR:'监考',ASSISTANT:'副监考',RESERVE:'备用监考',ROAMING:'巡考'})[role] || role || '岗位未提供';
}
function dutyRows(value) {
  if (!Array.isArray(value) || value.some(row=>!row || typeof row.id!=='string' || !row.id || typeof row.status!=='string'
      || !Number.isSafeInteger(row.engagementNo) || row.engagementNo<0) || new Set(value.map(row=>row.id)).size!==value.length) {
    throw new Error('接岗接口返回格式不完整，请刷新核对或联系管理员。');
  }
  return value;
}
function dutyError(error) {
  const known = {
    DUTY_ENGAGEMENT_STALE:'该岗位的接岗批次已改变，请刷新后核对当前安排。',
    DUTY_STATE_INVALID:'岗位状态已改变，当前操作不能继续。请刷新核对。',
    DUTY_CHECK_IN_WINDOW_CLOSED:'当前不在签到时间内：考前 60 分钟起至考试结束可签到。',
    SUBSTITUTION_STATE_INVALID:'该事项已被处理或已失效，请刷新核对。',
    DUTY_TEACHER_INACTIVE:'当前教师不在岗，请联系教务核对。',
    DUTY_TEACHER_UNQUALIFIED:'当前资格不符合该岗位要求，请联系教务核对。',
    DUTY_TEACHER_UNAVAILABLE:'当前时间不可用或已有批准请假，请联系教务核对。',
    SUBSTITUTION_REPLACEMENT_INVALID:'请选择另一位在岗教师。',
    SUBSTITUTION_TEACHER_UNQUALIFIED:'该教师不符合岗位资格要求，请重新选择。',
    SUBSTITUTION_TEACHER_CONFLICT:'该教师此时段已有安排、不可用或已请假，请重新选择。',
    SUBSTITUTION_DAILY_LIMIT:'该教师当天监考场次已达上限，请重新选择。',
  };
  const message = error?.status===403 ? '当前账号没有此操作权限，请联系管理员。'
    : error?.status===401 ? '登录已失效，请重新登录。'
    : error?.status===404 ? '该岗位或事项已不属于当前可操作范围，请刷新核对。'
    : known[error?.code] || error?.message || '请求失败，请刷新核对。';
  return message + (error?.traceId ? `（追踪号：${error.traceId}）` : '');
}
function useDutyRows(kind, permission) {
  const allowed=dutyPermission(permission), identity=dutySessionKey();
  const [identityEpoch,setIdentityEpoch]=React.useState(0);
  const identitySignal=React.useRef({identity,epoch:0});
  const scope=JSON.stringify([identity,identityEpoch]);
  const [state,setState]=React.useState({status:'loading',rows:[],error:'',identity:scope});
  const [busy,setBusy]=React.useState(false),[message,setMessage]=React.useState('');
  const generation=React.useRef(0),lifecycle=React.useRef(0),alive=React.useRef(false),locked=React.useRef(false);
  const valid=()=>alive.current && identity===dutySessionKey()
    && identityEpoch===identitySignal.current.epoch && dutyPermission(permission);
  React.useEffect(()=>{
    const changed=()=>{
      const next=dutySessionKey();
      if(next===identitySignal.current.identity)return;
      identitySignal.current={identity:next,epoch:identitySignal.current.epoch+1};
      // Invalidate synchronously: React can batch A -> B -> A without rendering B.
      lifecycle.current++;generation.current++;locked.current=false;
      setBusy(false);setMessage('');setIdentityEpoch(identitySignal.current.epoch);
    };
    window.addEventListener('jkxt:me-changed',changed);
    return()=>window.removeEventListener('jkxt:me-changed',changed);
  },[]);
  const load=React.useCallback(async()=>{
    if(!allowed || identity!==dutySessionKey() || identityEpoch!==identitySignal.current.epoch)return null;
    const id=++generation.current;
    setState({status:'loading',rows:[],error:'',identity:scope});
    try {
      const rows=dutyRows(await window.api[kind].list());
      if(!valid() || id!==generation.current)return null;
      setState({status:'ready',rows,error:'',identity:scope});return rows;
    }catch(error){
      if(valid() && id===generation.current)setState({status:'error',rows:[],error:dutyError(error),identity:scope});
      return null;
    }
  },[kind,allowed,identity,identityEpoch]);
  React.useEffect(()=>{
    identitySignal.current.identity=identity;
    lifecycle.current++;alive.current=true;locked.current=false;setBusy(false);setMessage('');
    if(allowed)load();
    return()=>{alive.current=false;lifecycle.current++;generation.current++;};
  },[load]);
  const refresh=async()=>{if(locked.current)return;setMessage('');return load();};
  const run=async(write,verify,success)=>{
    if(locked.current || !valid() || state.identity!==scope || state.status!=='ready')return null;
    const epoch=lifecycle.current;
    const current=()=>valid() && epoch===lifecycle.current;
    locked.current=true;setBusy(true);setMessage('');
    try {
      await write();if(!current())return null;
      const rows=await load();if(!current())return null;
      if(!rows || !verify(rows)){
        setState({status:'error',rows:[],error:'提交已返回，但最新结果未能核对。请刷新核对，勿重复提交。',identity:scope});return false;
      }
      setMessage(success);return true;
    }catch(error){
      if(current())setState({status:'error',rows:[],error:`${dutyError(error)} 请先刷新核对结果，不要重复提交。`,identity:scope});
      return current()?false:null;
    }finally{if(current()){locked.current=false;setBusy(false);}}
  };
  return {allowed,identity,scope,isCurrent:valid,state:state.identity===scope?state:{status:'loading',rows:[],error:''},busy,message,refresh,run};
}
function DutySelfPanel() {
  const model=useDutyRows('duties','duty:self');
  const regionRef=React.useRef(null),triggerRef=React.useRef(null);
  const returnFocusRef={get current(){return triggerRef.current?.isConnected?triggerRef.current:regionRef.current;}};
  const [dialog,setDialog]=React.useState(null),[reason,setReason]=React.useState(''),[formError,setFormError]=React.useState('');
  React.useEffect(()=>{setDialog(null);setReason('');setFormError('');},[model.scope]);
  const actionRow=dialog?.scope===model.scope && model.state.status==='ready'
    ? model.state.rows.find(row=>row.id===dialog.row.id && row.engagementNo===dialog.row.engagementNo) : null;
  if(!model.allowed)return null;
  const open=(row,action,trigger)=>{if(!model.isCurrent())return;triggerRef.current=trigger;setDialog({row,action,scope:model.scope});setReason('');setFormError('');};
  const submit=async()=>{
    if(!actionRow || model.busy)return;
    if(dialog.action==='reject' && (!reason.trim() || reason.trim().length>500)){setFormError('请填写 1–500 字的原因。');return;}
    const next={accept:'ACCEPTED',reject:'REJECTED','check-in':'CHECKED_IN'}[dialog.action];
    const result=await model.run(()=>window.api.duties.act(actionRow,dialog.action,reason.trim()),
      rows=>rows.some(row=>row.id===actionRow.id && row.engagementNo===actionRow.engagementNo && row.status===next),
      next==='REJECTED'?'已记录无法参加，替补事项已交由教务处理。':next==='CHECKED_IN'?'签到已记录，不代表考试已结束。':'已确认接岗，请按安排到场并签到。');
    if(result!==null && model.isCurrent())setDialog(null);
  };
  const labels={PENDING_CONFIRMATION:'待确认',ACCEPTED:'已确认',REJECTED:'已拒绝',CHECKED_IN:'已签到',CANCELLED:'已取消'};
  return <section ref={regionRef} tabIndex={-1} className="duty-workflow" aria-label="我的监考" aria-busy={model.busy || model.state.status==='loading'}>
    <header className="duty-heading"><div><h2>我的监考</h2><p>当前有效发布版本 · 北京时间 · 仅显示与本账号绑定的岗位</p></div><Button disabled={model.busy || model.state.status==='loading'} onClick={model.refresh}>刷新</Button></header>
    {model.message&&<p className="duty-success" role="status">{model.message}</p>}
    {model.state.status==='loading'&&<p role="status">正在核对当前监考安排…</p>}
    {model.state.status==='error'&&<p className="duty-error" role="alert">{model.state.error}</p>}
    {model.state.status==='ready'&&(!model.state.rows.length?<div className="duty-empty"><h3>暂无当前监考</h3><p>若您应有监考安排，请联系教务核对账号绑定和计划发布状态。此处不展示草稿或历史失效版本。</p></div>:<div className="duty-list">{model.state.rows.map(row=><article key={`${row.id}:${row.engagementNo}`} className="duty-card">
      <div className="duty-card-top"><h3>{row.plan_name||'计划名称未提供'}</h3><span className="duty-state" data-state={row.status}>{labels[row.status]||'状态待核对'}</span></div>
      <p className="duty-time">{dutyDate(row.starts_at)} — {dutyDate(row.ends_at)}</p>
      <dl className="duty-facts"><dt>场次 / 科目</dt><dd>{row.session_external_code||'场次未提供'} · {row.subject_name||'科目未提供'}</dd><dt>考场 / 岗位</dt><dd>{row.room_name||row.room_code||'考场未提供'} · {dutyRole(row.duty_role)}</dd><dt>接岗人</dt><dd>{row.teacher_name||'姓名未提供'} {row.teacher_no||''} · {row.engagementNo===0?'首次安排':`第 ${row.engagementNo} 次替补`}</dd></dl>
      {row.reason&&<p className="duty-note">原因：{row.reason}</p>}{row.checked_in_at&&<p className="duty-note">签到记录：{dutyDate(row.checked_in_at)}</p>}
      <div className="duty-actions">{row.status==='PENDING_CONFIRMATION'&&<><Button variant="primary" disabled={model.busy} onClick={event=>open(row,'accept',event?.currentTarget)}>确认接岗</Button><Button disabled={model.busy} onClick={event=>open(row,'reject',event?.currentTarget)}>无法参加</Button></>}{row.status==='ACCEPTED'&&<Button variant="primary" disabled={model.busy} onClick={event=>open(row,'check-in',event?.currentTarget)}>签到</Button>}</div>
    </article>)}</div>)}
    {actionRow&&<window.BlueprintDialog returnFocusRef={returnFocusRef} title={dialog.action==='reject'?'说明无法参加的原因':dialog.action==='check-in'?'确认到场签到':'确认接岗'} description="仅提交本账号当前接岗记录，不修改原发布版本。" busy={model.busy} onClose={()=>setDialog(null)} footer={<div className="duty-actions"><Button disabled={model.busy} onClick={()=>setDialog(null)}>取消</Button><Button variant="primary" loading={model.busy} onClick={submit}>确认提交</Button></div>}>
      <p>{actionRow.plan_name||'当前考试计划'} · {actionRow.room_name||actionRow.room_code||'考场未提供'}</p><p>{dutyDate(actionRow.starts_at)} · {dutyRole(actionRow.duty_role)}</p>
      {dialog.action==='reject'?<label className="duty-field">无法参加的原因<textarea value={reason} disabled={model.busy} maxLength={500} aria-required="true" aria-invalid={Boolean(formError)} onChange={event=>setReason(event.target.value)}/><small>{reason.length}/500</small></label>:<p className="duty-note">{dialog.action==='check-in'?'请确认您已到场。签到时间由服务器记录。':'确认后如需请假或调整，请联系教务办理，不要代他人确认。'}</p>}
      {formError&&<p className="duty-error" role="alert">{formError}</p>}
    </window.BlueprintDialog>}
  </section>;
}
function DutySubstitutionPanel() {
  const model=useDutyRows('substitutions','schedule:manage');
  const regionRef=React.useRef(null),triggerRef=React.useRef(null);
  const returnFocusRef={get current(){return triggerRef.current?.isConnected?triggerRef.current:regionRef.current;}};
  const [filter,setFilter]=React.useState('open'),[dialog,setDialog]=React.useState(null),[teachers,setTeachers]=React.useState({status:'idle',rows:[],error:''}),[teacherId,setTeacherId]=React.useState(''),[reason,setReason]=React.useState(''),[formError,setFormError]=React.useState('');
  const candidateGeneration=React.useRef(0);
  React.useEffect(()=>{
    candidateGeneration.current++;setDialog(null);setTeachers({status:'idle',rows:[],error:''});
    setTeacherId('');setReason('');setFormError('');
    return()=>{candidateGeneration.current++;};
  },[model.scope]);
  const active=dialog?.scope===model.scope && model.state.status==='ready'
    ? model.state.rows.find(row=>row.id===dialog.row.id && row.engagementNo===dialog.row.engagementNo && row.actionable===true && ['OPEN','ASSIGNED'].includes(row.status)) : null;
  if(!model.allowed)return null;
  const loadTeachers=async()=>{
    if(!model.isCurrent())return;
    const generation=++candidateGeneration.current;
    setTeachers({status:'loading',rows:[],error:''});
    try {
      const values=await window.api.get('/teachers',{status:'active'});
      if(!Array.isArray(values) || values.some(value=>!value || typeof value.uuid!=='string' || !value.uuid || typeof value.name!=='string'))throw new Error('教师名册返回格式不完整。');
      if(generation===candidateGeneration.current && model.isCurrent())setTeachers({status:'ready',rows:values,error:''});
    }catch(error){if(generation===candidateGeneration.current && model.isCurrent())setTeachers({status:'error',rows:[],error:dutyError(error)});}
  };
  const open=(row,trigger)=>{if(!model.isCurrent())return;triggerRef.current=trigger;setDialog({row,scope:model.scope});setTeacherId('');setReason('');setFormError('');loadTeachers();};
  const close=()=>{if(model.busy)return;candidateGeneration.current++;setDialog(null);setTeachers({status:'idle',rows:[],error:''});};
  const submit=async()=>{
    if(!active || model.busy)return;
    if(teachers.status!=='ready' || !teachers.rows.some(row=>row.uuid===teacherId && row.uuid!==active.teacherId) || !reason.trim() || reason.trim().length>500){setFormError('请选择另一位教师，并填写 1–500 字的办理原因。');return;}
    const result=await model.run(()=>window.api.substitutions.resolve(active,teacherId,reason.trim()),
      rows=>rows.some(row=>row.id===active.id && row.status==='RESOLVED' && row.replacementTeacherId===teacherId && row.engagementNo===active.engagementNo),
      '替补已转交。转交不代表教师已确认、已签到或通知已送达，请继续核对当前监考安排。');
    if(result!==null && model.isCurrent()){candidateGeneration.current++;setDialog(null);}
  };
  const rows=model.state.rows.filter(row=>filter==='all'||['OPEN','ASSIGNED'].includes(row.status));
  const pending=model.state.rows.filter(row=>row.actionable===true&&['OPEN','ASSIGNED'].includes(row.status)).length;
  const labels={OPEN:'待办理',ASSIGNED:'待转交',RESOLVED:'已转交',CANCELLED:'已取消'};
  return <section ref={regionRef} tabIndex={-1} className="duty-workflow" aria-label="监考替补待办" aria-busy={model.busy || model.state.status==='loading'}>
    <header className="duty-heading"><div><h2>监考替补待办</h2><p>教师无法参加或批准请假后产生的实际事项，与下方“变更登记”分开核对。</p></div><Button disabled={model.busy||model.state.status==='loading'} onClick={()=>{close();return model.refresh();}}>刷新</Button></header>
    {model.message&&<p role="status" className="duty-success">{model.message}</p>}
    <div className="duty-toolbar"><label>查看范围 <select value={filter} disabled={model.busy} onChange={event=>setFilter(event.target.value)}><option value="open">未结事项</option><option value="all">全部事项</option></select></label>{model.state.status==='ready'&&<span>当前可办理 {pending} 项</span>}</div>
    {model.state.status==='loading'&&<p role="status">正在读取替补事项…</p>}{model.state.status==='error'&&<p role="alert" className="duty-error">{model.state.error}</p>}
    {model.state.status==='ready'&&(!rows.length?<p className="duty-empty">当前范围没有替补事项。已转交的事项可切换“全部事项”查看；这不代表所有教师已确认。</p>:<div className="duty-list">{rows.map(row=><article className="duty-card" key={row.id}>
      <div className="duty-card-top"><h3>{row.planName||'计划名称未提供'}</h3><span className="duty-state" data-state={row.status}>{labels[row.status]||'状态待核对'}</span></div>
      <p className="duty-time">{dutyDate(row.startsAt)} — {dutyDate(row.endsAt)}</p><dl className="duty-facts"><dt>场次 / 考场</dt><dd>{row.sessionCode||'场次未提供'} · {row.roomName||row.roomCode||'考场未提供'} · {dutyRole(row.dutyRole)}</dd><dt>待替换教师</dt><dd>{row.teacherName||row.teacherNo||'教师未提供'} {row.teacherNo||''}</dd>{row.replacementTeacherId&&<><dt>转交给</dt><dd>{row.replacementTeacherName||row.replacementTeacherNo||'姓名未提供'}（请核对当前确认状态）</dd></>}</dl>
      <p className="duty-note">原因：{row.reason||'未提供'}</p>{row.resolutionReason&&<p className="duty-note">办理说明：{row.resolutionReason}</p>}
      {['OPEN','ASSIGNED'].includes(row.status)&&(row.actionable===true?<Button variant="primary" disabled={model.busy} onClick={event=>open(row,event?.currentTarget)}>办理替补</Button>:<p className="duty-note">历史或已停用安排，仅供核对；不再显示办理入口。</p>)}
    </article>)}</div>)}
    {active&&<window.BlueprintDialog returnFocusRef={returnFocusRef} title="办理监考替补" description="转交后由新教师确认接岗；原发布版本及历史回应保留。" busy={model.busy} onClose={close} footer={<div className="duty-actions"><Button disabled={model.busy} onClick={close}>取消</Button><Button variant="primary" loading={model.busy} disabled={teachers.status!=='ready'} onClick={submit}>确认转交</Button></div>}>
      <p>{active.planName||'当前计划'} · {dutyRole(active.dutyRole)} · {dutyDate(active.startsAt)}</p><p>待替换：{active.teacherName||active.teacherNo||'教师未提供'}</p>
      {teachers.status==='loading'&&<p role="status">正在读取本校教师名册…</p>}{teachers.status==='error'&&<div><p role="alert" className="duty-error">{teachers.error}</p><Button onClick={loadTeachers}>重试读取教师</Button></div>}
      {teachers.status==='ready'&&<label className="duty-field">替补教师<select value={teacherId} disabled={model.busy} aria-required="true" onChange={event=>setTeacherId(event.target.value)}><option value="">请选择另一位教师</option>{teachers.rows.filter(row=>row.uuid!==active.teacherId).map(row=><option key={row.uuid} value={row.uuid}>{row.name} · {row.teacherNo||'工号未提供'}</option>)}</select></label>}
      <p className="duty-note">此列表是本校教师名册，不代表均可替补。提交时后台重新校验资格、请假、时间冲突与每日上限；不符合时不会转交。</p>
      <label className="duty-field">办理原因<textarea maxLength={500} value={reason} disabled={model.busy} aria-required="true" aria-invalid={Boolean(formError)} onChange={event=>setReason(event.target.value)}/><small>{reason.length}/500</small></label>
      {formError&&<p role="alert" className="duty-error">{formError}</p>}
    </window.BlueprintDialog>}
  </section>;
}
window.JKXTDutyWorkflows=Object.freeze({can:dutyPermission});
window.DutySelfPanel=DutySelfPanel;
window.DutySubstitutionPanel=DutySubstitutionPanel;
