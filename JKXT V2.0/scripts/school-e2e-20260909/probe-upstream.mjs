import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

// Authorized provider probe: only the user-supplied Beijing workspace, synthetic content,
// no credential output, redirects prohibited, bounded calls and response capture.
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const attachment='C:/Users/nuoya/.codex/attachments/2eb0200f-2fc0-4898-8921-3a664b84b571/pasted-text.txt';
const text=fs.readFileSync(attachment,'utf8');
const key=text.match(/API秘钥[：:]\s*(sk-[A-Za-z0-9._-]+)/)?.[1];
const endpoint='https://ws-dgs4ei9jjypkwezu.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions';
if(!key) throw new Error('Supplied attachment credential not found; no request sent');
const models=process.argv.slice(2);
if(models.length===0 || models.length>80 || models.some(m=>!/^[A-Za-z0-9._-]+$/.test(m))) throw new Error('Supply 1-80 explicit model IDs');
const stamp=new Date().toISOString().replace(/[:.]/g,'-');
const out=path.join(root,'artifacts/school-e2e-20260909/ai',stamp);
fs.mkdirSync(out,{recursive:true});
const results=[];
const catalogue=JSON.parse(fs.readFileSync(path.join(root,'backend/src/main/resources/ai-models.json'),'utf8')).models;
for(const model of models){
  const def=catalogue.find(m=>m.id===model);
  if(!def?.policyCompatible){results.push({model,status:'not-tested',reason:'Not eligible for policy parsing; use its modality-specific protocol'});continue;}
  const nonce=crypto.randomBytes(5).toString('hex');
  const body={model,stream:false,max_tokens:768,messages:[{role:'system',content:'Return only a JSON object with keys nonce, posts, conclusion. This is synthetic software acceptance data, not actual teacher data.'},{role:'user',content:`nonce=${nonce}. 有30间考场，每间需要2名监考。请计算总岗位数posts，并用中文conclusion说明需多少人。不要附加任何个人信息。`} ]};
  if(def.jsonMode)body.response_format={type:'json_object'};
  if(def.thinking==='disable')body.enable_thinking=false;
  const start=Date.now(); const entry={requestedModel:model,startedAt:new Date(start).toISOString(),nonce};
  try{
    const response=await fetch(endpoint,{method:'POST',redirect:'error',signal:AbortSignal.timeout(90000),headers:{'Content-Type':'application/json','Authorization':`Bearer ${key}`},body:JSON.stringify(body)});
    entry.httpStatus=response.status;
    const raw=await response.text();
    if(raw.length>1048576)throw new Error('Response exceeded capture limit');
    let data;try{data=JSON.parse(raw);}catch{data={};}
    entry.responseModel=data.model??null;
    entry.finishReason=data.choices?.[0]?.finish_reason??null;
    entry.usage=data.usage??null;
    entry.providerRequestId=data.id??data.request_id??null;
    const content=data.choices?.[0]?.message?.content;
    entry.content=typeof content==='string'?content.replaceAll(key,'[REDACTED]').slice(0,5000):null;
    entry.errorCode=data.error?.code??data.code??null;
    let answer;try{answer=JSON.parse(content);}catch{}
    entry.contentCorrect=answer?.nonce===nonce && answer?.posts===60 && typeof answer?.conclusion==='string';
    entry.status=response.ok && entry.contentCorrect && entry.finishReason==='stop'?'passed':'failed';
  }catch(error){entry.status='failed';entry.error=error?.name==='TimeoutError'?'timeout':String(error.message).replaceAll(key,'[REDACTED]').slice(0,400);}
  entry.latencyMs=Date.now()-start; results.push(entry);
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({schema:'RealProviderProbe/v1',endpoint,source:'user supplied attachment, secret read in memory only',data:'synthetic',fallback:false,results},null,2));
  console.log(JSON.stringify({model,status:entry.status,http:entry.httpStatus,code:entry.errorCode,error:entry.error,latencyMs:entry.latencyMs,responseModel:entry.responseModel}));
  // Invalid credential/access rights are not repaired by spending repeated calls.
  if([401,403].includes(entry.httpStatus))break;
}
console.log(JSON.stringify({output:out,passed:results.filter(r=>r.status==='passed').length,total:results.length}));
process.exitCode=results.every(r=>r.status==='passed')?0:1;
