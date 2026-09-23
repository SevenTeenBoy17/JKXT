import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const scripts=['scripts/check-jsx.js',...['check-ai-workflow','check-ai-page-actions','check-real-task-actions','check-resource-actions','check-settings-actions','check-operational-labels','check-audit-followups'].map(s=>'scripts/frontend-quality/'+s+'.mjs')];
const results=[];
for(const script of scripts){
  const startedAt=new Date().toISOString();
  const r=spawnSync(process.execPath,[script],{cwd:root,encoding:'utf8',timeout:120000,maxBuffer:4*1024*1024});
  const output=(r.stdout||'')+(r.stderr||'');
  const item={script,cwd:root,startedAt,finishedAt:new Date().toISOString(),exitCode:r.status,signal:r.signal,error:r.error?.message,sourceSha256:createHash('sha256').update(fs.readFileSync(path.join(root,script))).digest('hex'),outputSha256:createHash('sha256').update(output).digest('hex'),output};
  results.push(item);console.log((r.status===0?'PASS ':'FAIL ')+script);
}
const report={schema:'JKXT.FrontendVerification.v1',mode:'local-tests-not-browser-or-formal-supervisor-gates',passed:results.filter(r=>r.exitCode===0).length,total:results.length,results};
const dir=path.join(root,'artifacts/ui-ai-audit-20260905');fs.mkdirSync(dir,{recursive:true});fs.writeFileSync(path.join(dir,'frontend-tests.json'),JSON.stringify(report,null,2));
console.log(`${report.passed}/${report.total} command suites passed`);process.exitCode=report.passed===report.total?0:1;
