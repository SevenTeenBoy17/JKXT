// AI 策略监考 — 策略 toggles + 自然语言输入 + provider 切换

const K12_POLICIES = [
  { id: 'homeroom_avoid', label: '班主任避监本班', desc: '班主任老师不安排监考自己任教的班级,避免影响公平', critical: true, default: true },
  { id: 'same_grade_avoid', label: '同年级回避偏好', desc: '默认是软偏好：尽量避免监考同年级，不能承诺全部回避；如明确要求硬约束，须复核解析结果。', critical: true, default: true },
  // 迭代9 P0-E：gender_mix / cross_grade 当前仅生成 DSL 但求解器不强制执行(见 AiPolicyService.enforcementWarnings)。
  // 如实标注 advisory + 默认不勾，避免让教务误以为已强制；勾选后后端也会回传 enforcementWarnings 提示。
  { id: 'gender_mix', label: '男女混合监考', desc: '每个考场至少配置 1 男 1 女老师(暂未纳入自动排考约束·仅供参考)', critical: false, default: false, advisory: true },
  { id: 'subject_off', label: '科目回避偏好', desc: '软约束：尽量不安排教师监考本科目；不能承诺全部满足。', critical: false, default: true },
  { id: 'workload_balance', label: '工作量均衡', desc: '求解器始终优化工作量均衡，不承诺固定次数差上限。', critical: false, default: true, fixed: true },
  { id: 'cross_grade', label: '跨年级优先', desc: '优先安排跨年级监考(暂未纳入自动排考约束·仅供参考)', critical: false, default: false, advisory: true },
];

function PolicyToggle({ p, checked, onChange }) {
  return (
    <div
      className="rounded-xl p-4 transition-all"
      style={{
        border: `1px solid ${checked ? (p.critical ? '#10B981' : 'var(--brand)') : 'var(--border-soft)'}`,
        background: checked ? (p.critical ? 'var(--surface-muted)' : 'var(--brand-soft)') : 'var(--surface-raised)',
        boxShadow: checked ? 'var(--elevation-1)' : 'none',
      }}
    >
      <div className="flex items-start gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-[13.5px] font-semibold" style={{ color: 'var(--text-strong)' }}>
              {p.label}
            </span>
            {p.critical && <Tag color="emerald" dot>核心</Tag>}
          </div>
          <div className="text-[11.5px] leading-relaxed" style={{ color: 'var(--text-medium)' }}>
            {p.desc}
          </div>
        </div>
        <button
          role="switch"
          aria-checked={checked}
          aria-label={p.label}
          disabled={p.fixed}
          onClick={() => onChange(!checked)}
          className="relative w-10 h-6 rounded-full transition-colors shrink-0 mt-0.5"
          style={{ background: checked ? (p.critical ? '#10B981' : 'var(--brand)') : '#CBD5E1' }}
        >
          <span
            className="absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all"
            style={{
              left: checked ? 18 : 2,
              boxShadow: 'var(--elevation-1)',
            }}
          />
        </button>
      </div>
    </div>
  );
}

function NaturalLanguageInput({ text, onTextChange, onRefine, onAcceptRefined, refining, refined }) {
  const examples = [
    '王伟明老师本周一上午有外出会议,请避开',
    '初三(4)班和高三(7)班的考场需要错开半小时',
    '尽量让教龄 5 年以下的老师跟资深老师搭档',
    '英语听力时段需要安排懂听力设备的老师',
  ];

  return (
    <div className="bg-white rounded-2xl p-5"
      style={{ border: '1px solid var(--border-soft)', boxShadow: 'var(--elevation-1)' }}>
      <SectionTitle
        title="自然语言补充要求"
        sub="预置策略不能完全覆盖的情况,可用自然语言告诉 AI"
        right={<Tag color="violet">AI 增强</Tag>}
      />
      <textarea
        value={text}
        aria-label="自然语言补充要求"
        maxLength={500}
        onChange={(e) => onTextChange(e.target.value)}
        placeholder="例如:王伟明老师本周一上午有外出,请避开;高三模考要求两位资深老师配合..."
        className="w-full h-32 rounded-xl p-4 text-[13px] outline-none resize-none transition-colors leading-relaxed"
        style={{
          background: 'var(--surface-muted)',
          border: '1px solid var(--border-soft)',
          color: 'var(--text-strong)',
        }}
      />
      <div className="mt-2 flex items-center justify-between">
        <div className="text-[11px]" style={{ color: 'var(--text-soft)' }}>
          已输入 <span className="font-mono">{text.length}</span> / 500 字
        </div>
        <button
          onClick={onRefine}
          disabled={refining || !text.trim()}
          className="text-[11px] inline-flex items-center gap-1 hover:underline disabled:opacity-50"
          style={{ color: 'var(--brand)' }}
        >
          {refining ? <span className="w-3 h-3 rounded-full border-2 border-current border-t-transparent animate-spin" /> : <IconSparkle size={11} />}
          优化我的表达
        </button>
      </div>
      {refined?.refinedText && (
        <div className="mt-3 rounded-xl p-3 text-[12px] leading-relaxed"
          style={{ background: 'var(--surface-muted)', border: '1px solid var(--border-soft)', color: 'var(--text-medium)' }}>
          <div className="font-semibold mb-1" style={{ color: 'var(--text-strong)' }}>AI 表达草稿 · 尚未采用</div>
          <div>{refined.refinedText}</div>
          <p className="mt-2">原文已保留。模型可能改变软硬约束或遗漏要求，请逐项核对后再采用。</p>
          <button type="button" className="mt-2 px-3 py-2 rounded-lg" style={{ border: '1px solid var(--border-soft)', color: 'var(--brand)' }} onClick={onAcceptRefined} disabled={refining}>已核对，采用此草稿</button>
        </div>
      )}

      <div className="mt-4">
        <div className="text-[11px] mb-2 font-medium" style={{ color: 'var(--text-soft)' }}>
          快速插入示例:
        </div>
        <div className="flex gap-2 flex-wrap">
          {examples.map((e) => (
            <button
              key={e}
              onClick={() => onTextChange(text ? text + '\n' + e : e)}
              className="px-2.5 py-1.5 rounded-full text-[11.5px] transition-colors hover:bg-white"
              style={{ background: 'var(--surface-muted)', color: 'var(--text-medium)', border: '1px solid var(--border-soft)' }}
            >
              + {e}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

function validateAiProviderPayload(data) {
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  const text = value => typeof value === 'string';
  const optionalText = value => value == null || text(value);
  const invalid = () => { throw new Error('模型配置响应格式异常，请刷新重试或联系管理员。'); };
  if (!object(data) || !Array.isArray(data.providers)) invalid();
  for (const provider of data.providers) {
    if (!object(provider) || !text(provider.id) || !provider.id.trim() || !optionalText(provider.name)
      || typeof provider.active !== 'boolean' || typeof provider.configured !== 'boolean'
      || !Array.isArray(provider.models) || !provider.models.every(text)) invalid();
  }
  const catalog = data.catalog ?? [];
  const recentCalls = data.recentCalls ?? [];
  if (!Array.isArray(catalog) || !Array.isArray(recentCalls)) invalid();
  for (const model of catalog) {
    if (!object(model) || !text(model.id) || !model.id.trim() || typeof model.policyCompatible !== 'boolean'
      || !optionalText(model.capability) || !optionalText(model.status)) invalid();
  }
  for (const call of recentCalls) {
    if (!object(call) || !text(call.id) || !optionalText(call.model) || !optionalText(call.purpose)
      || !optionalText(call.status) || !text(call.createdAt) || Number.isNaN(new Date(call.createdAt).getTime())) invalid();
  }
  if (data.usage != null && (!object(data.usage) || ['monthCallCount','successCount','failureCount']
    .some(key => data.usage[key] != null && !Number.isFinite(data.usage[key])))) invalid();
  return { ...data, catalog, recentCalls };
}

function AIPolicyPage() {
  const [policies, setPolicies] = React.useState(K12_POLICIES.reduce((a,p)=>({...a,[p.id]:p.default}),{}));
  const [nlText,setNlText] = React.useState('');
  const [refined,setRefined] = React.useState(null);
  const [providerData,setProviderData] = React.useState(null);
  const [tasks,setTasks] = React.useState([]);
  const [taskId,setTaskId] = React.useState('');
  const [model,setModel] = React.useState('auto');
  const [loading,setLoading] = React.useState(true);
  const [loadError,setLoadError] = React.useState('');
  const [actionError,setActionError] = React.useState('');
  const [busy,setBusy] = React.useState('');
  const [prepared,setPrepared] = React.useState(null);
  const [sessionCalls,setSessionCalls] = React.useState([]);
  const lock = React.useRef(false);
  const seq = React.useRef(0);
  const lifecycle = React.useRef({ mounted: false, action: 0 });
  const errorRef = React.useRef(null);
  React.useEffect(()=>{if(actionError)errorRef.current?.focus();},[actionError]);
  const provider = providerData?.providers?.find(p=>p.active) || providerData?.providers?.[0];
  const catalog = providerData?.catalog || [];
  const selectedTask = tasks.find(t=>t.id===taskId);
  const compatible = catalog.filter(m=>m.policyCompatible);
  const selectedPolicyIds = Object.keys(policies).filter(k=>policies[k]);
  const canCall = !!provider?.configured && !!model && !busy && !loading && !loadError;
  const changed = fn => { setPrepared(null); setRefined(null); setActionError(''); fn(); };
  const load = async () => {
    if (!lifecycle.current.mounted) return;
    const token=++seq.current;setLoading(true);setLoadError('');
    try {
      if(!window.api)throw new Error('后端接口尚未初始化');
      const [pd,rows] = await Promise.all([window.api.get('/ai/providers'),window.api.get('/exam-tasks')]);
      if(token!==seq.current || !lifecycle.current.mounted)return;
      if(!Array.isArray(rows))throw new Error('任务列表格式异常');
      const validated = validateAiProviderPayload(pd);
      setProviderData(validated);setTasks(rows);
      const primary=validated.providers.find(p=>p.active)||validated.providers[0];
      setModel(previous=>previous==='auto'||primary?.models?.includes(previous)?previous:'auto');
    } catch(e) {if(token===seq.current && lifecycle.current.mounted) {setProviderData(null);setLoadError(String(e.message||e));}}
    finally {if(token===seq.current && lifecycle.current.mounted)setLoading(false);}
  };
  React.useEffect(()=>{
    lifecycle.current.mounted=true;load();
    return()=>{lifecycle.current.mounted=false;lifecycle.current.action++;seq.current++;lock.current=false;};
  },[]);
  const record = (operation,result,status='succeeded') => setSessionCalls(prev=>[{id:Date.now()+operation,operation,status,model:result?.model||model,at:new Date().toLocaleTimeString('zh-CN')},...prev].slice(0,10));
  const act = async (operation,fn) => {
    if(lock.current || !lifecycle.current.mounted)return;
    const actionId=++lifecycle.current.action;
    const isCurrent=()=>lifecycle.current.mounted && lifecycle.current.action===actionId;
    lock.current=true;setBusy(operation);setActionError('');
    try { await fn(isCurrent); }
    catch(e){if(isCurrent()){setActionError(String(e.message||e));record(operation,null,'failed');}}
    finally {if(isCurrent()){lock.current=false;setBusy('');}}
  };
  const refine = () => act('优化表达',async(isCurrent)=>{
    const response=await window.api.post('/nl-policy/refine',{text:nlText,policyIds:selectedPolicyIds,provider:provider.id,model:model==='auto'?undefined:model});
    if(!isCurrent())return;
    const r=window.JKXTAiWorkflow.realResponse(response);
    if(!r.refinedText?.trim())throw new Error('服务端未提供优化后的文本。');
    if(r.refinedText.length>500)throw new Error('返回文本超过本页 500 字上限，请缩短输入后重试；原文已保留。');
    setRefined(r);setPrepared(null);record('优化表达',r);
  });
  const prepare = () => act('解析策略',async(isCurrent)=>{
    setPrepared(null);
    const text=nlText.trim()||'请仅将已选策略转换为约束；未选的策略不得擅自启用。';
    const p=await window.JKXTAiWorkflow.prepare({taskId,text,policyIds:selectedPolicyIds,provider:provider.id,model:model==='auto'?undefined:model},window.api);
    if(!isCurrent())return;
    setPrepared(p);record('解析策略',p.parse);
  });
  const submit = () => act('启动求解',async(isCurrent)=>{
    // Confirmed DSL is passed to the real task, never uploads a sample roster.
    const handoff=await window.JKXTAiWorkflow.submit(prepared,window.api);
    // Server work can finish after navigation; do not hijack the new page or overwrite its handoff.
    if(!isCurrent())return;
    try{
      sessionStorage.setItem('jkxt:quickHandoff',JSON.stringify(handoff));
      localStorage.setItem('jkxt:taskDetailId',handoff.taskId);
    }catch{
      throw new Error('排班已提交，但浏览器存储不可用。请在考务任务中查看所选任务，勿重复提交。');
    }
    window.navigateTo?.('quick');
  });
  const statusLabel = value=>({not_tested:'未实测',verified:'已实测',failed:'调用失败',configured:'已配置',unconfigured:'未配置'}[value]||value||'未实测');
  return (
    <PageShell crumbs={[orgName(),'排班作业','AI 策略监考']} title="AI 策略监考"
      subtitle="AI 将自然语言转换为约束草稿；您复核后，由 OR-Tools 使用任务考试与学校在职教师名册求解。"
      actions={<Button onClick={load} disabled={loading||!!busy}>刷新数据</Button>}>
      <div className="ai-policy-form">
        {loading && <div role="status" className="settings-status">正在读取任务与模型配置…</div>}
        {loadError && <div role="alert" className="settings-status settings-status--error"><span>读取失败：{loadError}</span><Button onClick={load}>重试</Button></div>}
        {actionError && <div ref={errorRef} tabIndex={-1} role="alert" className="settings-status settings-status--error">{actionError}</div>}
        <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_320px]">
          <div className="space-y-5">
            <section className="settings-panel">
              <SectionTitle title="1. 选择真实任务" sub="仅可处理草稿；已发布或运行中的任务不会被改写。" />
              <p className="text-[13px] leading-relaxed mb-4" style={{ color: 'var(--text-medium)' }}>教师与考场导入会更新学校资源名册，不会将候选人员限定为本次上传的行。考试来自所选任务；请同时复核学校人员可用性与回避规则。</p>
              <label htmlFor="ai-task" className="block mb-2">考试任务</label>
              <select id="ai-task" value={taskId} disabled={loading||!!busy} onChange={e=>changed(()=>setTaskId(e.target.value))}
                className="w-full border rounded-lg p-3" style={{background:'var(--surface-muted)',color:'var(--text-strong)',borderColor:'var(--border-strong)'}}>
                <option value="">请选择已导入数据的草稿任务</option>
                {tasks.map(t=><option key={t.id} value={t.id} disabled={!window.JKXTAiWorkflow.mutable(t)}>{t.name} · {t.status}</option>)}
              </select>
              {selectedTask && <div className="settings-notice">
                <div>数据来源：任务 {selectedTask.taskNo||selectedTask.id}</div>
                {selectedTask.uploads?.length?<ul className="mt-2">{selectedTask.uploads.map(f=><li key={f.id} style={{overflowWrap:'anywhere'}}>{({teacher:'教师表',classroom:'考场表',exam:'考试表'})[f.kind]||f.kind}：{f.originalName}</li>)}</ul>
                  :<p>没有任务上传记录；提交时将由服务端核验本校资源与该任务的考试数据，不使用示例补齐。</p>}
              </div>}
              <Button variant="ghost" onClick={()=>window.navigateTo('quick')} disabled={!!busy}>去排班工作台导入数据</Button>
            </section>
            <section className="settings-panel">
              <SectionTitle title="2. 设置策略" sub="未支持自动执行的策略已标注“仅供参考”；不会作为硬约束承诺。" />
              <fieldset disabled={!!busy} className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <legend className="sr-only">预置监考策略</legend>
                {K12_POLICIES.map(p=><PolicyToggle key={p.id} p={p} checked={policies[p.id]} onChange={v=>changed(()=>setPolicies(prev=>({...prev,[p.id]:v})))} />)}
              </fieldset>
              <details className="settings-notice"><summary>策略说明与当前边界</summary><p className="mt-2">班主任、年级和科目回避须由求解器校验；工作量均衡是软约束。性别混排、跨年级偏好仅供人工参考。每天最多场次和场间间隔未在此页实现配置，不展示无效输入框。</p></details>
            </section>
            <fieldset disabled={!!busy||!canCall}>
              <NaturalLanguageInput text={nlText} onTextChange={v=>changed(()=>setNlText(v.slice(0,500)))} onRefine={refine} onAcceptRefined={()=>{if(refined?.refinedText&&!busy)changed(()=>setNlText(refined.refinedText));}} refining={busy==='优化表达'} refined={refined} />
            </fieldset>
            <section className="settings-panel">
              <SectionTitle title="3. 解析并复核" sub="解析不会自动启动求解；请检查实际约束和警告。" />
              <Button variant="primary" disabled={!canCall||!taskId} onClick={prepare}>{busy==='解析策略'?'正在解析…':'解析策略'}</Button>
              {prepared && <div className="mt-4 space-y-3">
                <div role="status">已解析：{prepared.taskName} · 实际模型 {prepared.parse.model}</div>
                {!!prepared.parse.warnings?.length && <ul className="settings-notice">{prepared.parse.warnings.map((s,i)=><li key={i}>{s}</li>)}</ul>}
                <details open><summary>约束草稿（请人工复核）</summary><pre className="mt-2 p-3 rounded-lg" style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontSize:12,background:'var(--surface-muted)'}}>{JSON.stringify(prepared.parse.dsl,null,2)}</pre></details>
                <Button variant="primary" disabled={!!busy} onClick={submit}>{busy==='启动求解'?'正在提交…':'已复核，启动真实求解'}</Button>
              </div>}
            </section>
          </div>
          <aside className="space-y-5">
            <section className="settings-panel">
              <SectionTitle title="模型与服务" sub="密钥由服务端保管；不在浏览器中配置。" />
              <p>{provider?.name||'尚未读取配置'} · {provider?.configured?'服务端已配置':'未配置'}</p>
              <label htmlFor="ai-model" className="block mt-4 mb-2">策略解析模型</label>
              <select id="ai-model" value={model} disabled={!provider?.configured||!!busy} onChange={e=>changed(()=>setModel(e.target.value))}
                className="w-full rounded-lg p-3 border" style={{background:'var(--surface-muted)',color:'var(--text-strong)',borderColor:'var(--border-strong)'}}>
                <option value="auto">自动（服务端默认与备用模型）</option>
                {(compatible.length?compatible.map(m=>m.id):provider?.models||[]).map(id=><option key={id} value={id}>{id}</option>)}
              </select>
              <p className="settings-caption">已配置不代表所有模型都可完成策略解析。OCR、翻译、GUI 等专用模型仅列入目录，不混入排班策略。</p>
              <dl className="mt-4 space-y-2">
                <div>本月实际调用：{providerData?.usage?.monthCallCount??'—'}</div>
                <div>成功 / 失败：{providerData?.usage?.successCount??'—'} / {providerData?.usage?.failureCount??'—'}</div>
              </dl>
              <details className="mt-4"><summary>完整模型目录（{catalog.length}）</summary>
                <div className="mt-3 space-y-2" style={{maxHeight:360,overflowY:'auto'}}>
                  {catalog.map(m=><div key={m.id} className="pb-2 border-b" style={{borderColor:'var(--border-soft)',overflowWrap:'anywhere'}}>
                    <div style={{fontSize:13}}>{m.id}</div><div className="settings-caption">{m.capability} · {statusLabel(m.status)}{m.policyCompatible?'':' · 非策略模型'}</div>
                  </div>)}
                  {!catalog.length&&<p>服务端暂未返回模型目录，请确认已更新后端。</p>}
                </div>
              </details>
            </section>
            <section className="settings-panel">
              <SectionTitle title="真实调用记录" sub="本次会话与服务端最近记录，不显示示例评分。" />
              {sessionCalls.map(c=><div key={c.id} className="py-2 border-b" style={{borderColor:'var(--border-soft)',overflowWrap:'anywhere'}}>{c.at} · {c.operation} · {c.status==='succeeded'?'成功':'失败'}<div className="settings-caption">{c.model}</div></div>)}
              {(providerData?.recentCalls||[]).map(c=><div key={c.id} className="py-2 border-b" style={{borderColor:'var(--border-soft)',overflowWrap:'anywhere'}}>{new Date(c.createdAt).toLocaleString('zh-CN')} · {c.purpose} · {c.status}<div className="settings-caption">{c.model}</div></div>)}
              {!sessionCalls.length&&!providerData?.recentCalls?.length&&<p className="settings-caption">暂无可展示的调用记录。发起调用后刷新可读取服务端记录。</p>}
            </section>
          </aside>
        </div>
      </div>
    </PageShell>
  );
}
window.AIPolicyPage=AIPolicyPage;
