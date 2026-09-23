// Explicit local acceptance run. Creates one clearly labelled synthetic task; never publishes it.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const XLSX=createRequire(import.meta.url)('../assets/vendor/xlsx.full.0.18.5.min.js');
if (!process.argv.includes('--run')) { console.log('Run with --run to test the configured local backend and create a synthetic task.'); process.exit(0); }
const origin='http://127.0.0.1:18180/api/v1';
let cookie=''; const checks=[];
const report={startedAt:new Date().toISOString(),syntheticOnly:true,published:false,checks};
async function request(path,method='GET',body) {
  const form=body instanceof FormData;
  const response=await fetch(origin+path,{method,headers:{...(cookie?{Cookie:cookie}:{}),...(!form&&body!==undefined?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:form?body:JSON.stringify(body),signal:AbortSignal.timeout(130000),redirect:'error'});
  const set=response.headers.getSetCookie?.()||[];if(set.length)cookie=set.map(s=>s.split(';')[0]).join('; ');
  const text=await response.text();let data;try{data=JSON.parse(text);}catch{data={message:'Non-JSON response'};}
  if(!response.ok){const e=new Error(`${method} ${path}: HTTP ${response.status} ${data.code||data.message||''}`);e.status=response.status;throw e;}
  return data;
}
const api={get:p=>request(p),post:(p,b)=>request(p,'POST',b)};
async function check(name,fn){const start=Date.now();try{const data=await fn();checks.push({name,pass:true,elapsedMs:Date.now()-start,...data});}catch(e){checks.push({name,pass:false,elapsedMs:Date.now()-start,error:e.message});throw e;}}
try {
  await check('local backend authentication using explicit development test account',async()=>{
    const source=fs.readFileSync('src/pages/login.jsx','utf8');
    const password=source.match(/username:\s*'tenant\.test',\s*password:\s*'([^']+)'/)?.[1];
    assert.ok(password,'Development test account not configured; do not try other accounts');
    const captcha=await request('/auth/captcha','POST');
    assert.ok(captcha.debugAnswer,'Local debug challenge unavailable; manual authentication required');
    await request('/auth/login','POST',{username:'tenant.test',password,captchaId:captcha.captchaId,captcha:captcha.debugAnswer});
    assert.ok(cookie,'Session cookie missing');
  });
  await check('catalog registers 80 unique models and configured Alibaba gateway',async()=>{
    const r=await api.get('/ai/providers');assert.equal(r.catalog.length,80);assert.equal(new Set(r.catalog.map(m=>m.id)).size,80);assert.ok(r.providers.some(p=>p.id==='aliyun'&&p.configured));
    return {catalogCount:r.catalog.length,policyModels:r.catalog.filter(m=>m.policyCompatible).map(m=>m.id)};
  });
  const task=await api.post('/exam-tasks',{name:'AI 接口验收 · synthetic · '+new Date().toISOString(),source:'ai_policy'});report.taskId=task.id;
  const prefix='QA'+task.id.replaceAll('-','').slice(0,10);
  const fixtures={
    teacher:[['teacherNo','name','gender','grade'],[prefix+'A','审计测试甲','female','G11'],[prefix+'B','审计测试乙','male','G12']],
    classroom:[['code','name','capacity','examCapacity'],[prefix+'R','独立验收考场',30,20]],
    exam:[['subjectName','grade','examDate','startTime','endTime','roomCode','seatDemand','supervisorsPerRoom'],['审计合成考试','G10','2027-01-12','08:30','10:00',prefix+'R',20,2]]
  };
  report.fixtureResourcePrefix=prefix;
  for(const [kind,data] of Object.entries(fixtures))await check('unique synthetic '+kind+' XLSX upload',async()=>{
    const workbook=XLSX.utils.book_new();XLSX.utils.book_append_sheet(workbook,XLSX.utils.aoa_to_sheet(data),'Data');
    const bytes=XLSX.write(workbook,{type:'buffer',bookType:'xlsx'});
    const form=new FormData();form.set('kind',kind);form.set('file',new Blob([bytes],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}),prefix+'-'+kind+'.xlsx');
    await api.post(`/exam-tasks/${task.id}/uploads`,form);
  });
  const context={window:{}};vm.runInNewContext(fs.readFileSync('src/ai-workflow.js','utf8'),context);const w=context.window.JKXTAiWorkflow;
  let reviewed;
  for(const model of ['qwen-plus','qwen-flash']){
    await check(model+' real refine JSON through application backend',async()=>{const r=w.realResponse(await api.post('/nl-policy/refine',{text:'请将监考工作量均衡设为软约束，不设置固定次数差。',policyIds:['workload_balance'],provider:'aliyun',model}));assert.equal(r.model,model);assert.ok(r.refinedText?.trim());return{model:r.model,status:r.status,fallback:r.fallback};});
    await check(model+' real parse respects the selected policy meaning',async()=>{reviewed=await w.prepare({taskId:task.id,text:'请仅启用工作量均衡软约束；不启用班主任、年级或科目回避，不增加其他约束。',policyIds:['workload_balance'],provider:'aliyun',model},api);assert.equal(reviewed.parse.model,model);assert.ok(reviewed.parse.policyId);const enabled=[...reviewed.parse.dsl.hard,...reviewed.parse.dsl.soft].filter(r=>r.enabled!==false);assert.equal(enabled.length,1,'Exactly one selected enabled rule must be present');assert.equal(enabled[0].type,'workload_balance','Unrequested enabled policy rule');return{model,policyId:reviewed.parse.policyId,status:reviewed.parse.status,fallback:reviewed.parse.fallback,dsl:reviewed.parse.dsl};});
  }
  await check('stale reviewed upload set rejected by real backend before solve',async()=>{const bad=JSON.parse(JSON.stringify(reviewed.reviewedUploads));bad[0].sha256='stale-hash';await assert.rejects(api.post(`/exam-tasks/${task.id}/schedule-jobs`,{policyId:reviewed.parse.policyId,reviewedUploads:bad}),/409/);});
  await check('explicit confirmation starts actual bound solver job',async()=>{const h=await w.submit(reviewed,api);report.jobId=h.jobId;return{jobId:h.jobId};});
  await check('actual OR-Tools result matches the synthetic fixture and confirmed optional policies',async()=>{
    let job;
    for(let i=0;i<60;i++){job=await api.get('/schedule-jobs/'+report.jobId);if(!['pending','queued','running'].includes(job.status))break;await new Promise(r=>setTimeout(r,1000));}
    assert.ok(['succeeded','completed','success'].includes(job.status),'Solver status '+job.status);
    const rows=await api.get(`/exam-tasks/${task.id}/assignments`);assert.equal(rows.rows?.length,2,'Synthetic template has 1 examination requiring 2 duty posts');
    assert.equal(new Set(rows.rows.map(r=>r.teacherId)).size,2,'Both simultaneous duty posts must have different teachers');
    assert.ok(rows.rows.every(r=>r.roomCode===prefix+'R'&&r.subjectName==='审计合成考试'),'Result must use the imported examination and room');
    assert.equal(job.stats.solver,'ortools-cpsat');assert.equal(job.conflictSet.length,0);
    assert.equal(job.stats.policyId,reviewed.parse.policyId);
    assert.deepEqual(job.stats.policySnapshot,{sameGrade:'NONE',avoidSubjectClass:false,avoidHomeroomClass:false});
    for (const id of ['corridorShare','notesApplied']) {
      const metric=job.stats.fairness.find(item=>item.id===id);
      assert.ok(metric,'Explicit unavailable metric missing: '+id);
      assert.equal(metric.value,'—');assert.equal(metric.status,'unavailable');
    }
    report.solverResult={status:job.status,rowCount:rows.rows.length,stats:job.stats};
    return {status:job.status,rowCount:rows.rows.length,stats:job.stats};
  });
} catch(e) { report.error=e.message;process.exitCode=1; }
finally {cookie='';report.finishedAt=new Date().toISOString();report.passed=checks.filter(c=>c.pass).length;report.total=checks.length;fs.mkdirSync('artifacts/ui-ai-audit-20260905',{recursive:true});fs.writeFileSync('artifacts/ui-ai-audit-20260905/live-policy-smoke-v3.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));}
