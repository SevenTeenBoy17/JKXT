import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
const models = [
'qwen3.7-plus','qwen-math-turbo','qwen3-vl-235b-a22b-thinking','qwen3-vl-32b-thinking','qwen-plus-2025-07-28','deepseek-r1-distill-qwen-7b','qwen-max','glm-5','qwen-mt-flash','qwen3-vl-30b-a3b-thinking',
'qwen3.6-plus','qwen-vl-ocr-latest','qwen3-32b','deepseek-r1-distill-qwen-32b','qwen3.6-flash','qwen-vl-plus','qwen3.7-flash-2026-07-15','qwen-long','qwen3.5-35b-a3b','glm-4.5-air',
'qwen3-coder-480b-a35b-instruct','qwen3-coder-plus','qwen3-vl-8b-thinking','deepseek-v4-flash-0731','qwen-vl-ocr-1028','qwen3.5-flash-2026-02-23','qwen3-max-preview','qwen3-vl-flash-2025-10-15','qwen3-8b','qwen-plus-0112',
'gui-plus','qwen-plus','qwen-math-plus','qwen-turbo','qvq-max','qwen3-coder-flash','qwen3-next-80b-a3b-thinking','qwen3.5-27b','tongyi-xiaomi-analysis-flash','deepseek-r1',
'qwen3-vl-flash','qwen3-14b','qwen-math-plus-0919','MiniMax-M2.5','qwen-plus-2025-12-01','qwen3-max-2025-09-23','deepseek-v4-pro','qwen-plus-character','qwen-flash-character','MiniMax-M2.1',
'deepseek-r1-distill-qwen-14b','qwen3-30b-a3b-instruct-2507','qwen-flash','qwen-flash-2025-07-28','qwen3-235b-a22b-instruct-2507','qwen3-coder-plus-2025-07-22','kimi-k2.5','qwen3.5-plus-2026-04-20','qwen3.7-max','qwen-vl-ocr',
'kimi-k2.6','qwen-long-latest','qwen-vl-ocr-2025-04-13','qwen-plus-1220','qwen-vl-ocr-2025-11-20','qwen3.5-122b-a10b','tongyi-intent-detect-v3','qwen3-max','qwen3.5-plus-2026-02-15','qwen3-235b-a22b-thinking-2507',
'qwen3.7-max-2026-06-08','glm-5.1','qwen3.7-max-preview','kimi-k2-thinking','qwen3.6-max-preview','glm-5.2','kimi-k2.7-code','deepseek-v4-pro-0813','deepseek-v3.1','qwen3.5-397b-a17b'];
export { models };
function crc32(buf) { let c=0xffffffff; for(const b of buf){ c^=b; for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0); } return (c^0xffffffff)>>>0; }
function chunk(type,data){const t=Buffer.from(type),n=Buffer.alloc(4),c=Buffer.alloc(4);n.writeUInt32BE(data.length);c.writeUInt32BE(crc32(Buffer.concat([t,data])));return Buffer.concat([n,t,data,c]);}
function fixture(){
 const w=400,h=200,raw=Buffer.alloc(h*(1+w*3),255);
 for(let y=0;y<h;y++){raw[y*(1+w*3)]=0;for(let x=100;x<300;x++)if(y>=60&&y<160){const i=y*(1+w*3)+1+x*3;raw[i]=49;raw[i+1]=88;raw[i+2]=196;}}
 const glyphs=['010110010010111','111001111100111','111001111001111'];
 glyphs.forEach((g,k)=>{for(let y=0;y<5;y++)for(let x=0;x<3;x++)if(g[y*3+x]==='1')for(let yy=0;yy<10;yy++)for(let xx=0;xx<10;xx++){const i=(85+y*10+yy)*(1+w*3)+1+(140+k*45+x*10+xx)*3;raw[i]=raw[i+1]=raw[i+2]=255;}});
 const hdr=Buffer.alloc(13);hdr.writeUInt32BE(w);hdr.writeUInt32BE(h,4);hdr[8]=8;hdr[9]=2;
 return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',hdr),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
}
const image=fixture();
const kind=id=>id==='gui-plus'?'gui':id==='qwen-mt-flash'?'translation':id.includes('ocr')?'ocr':id.includes('-vl-')||id==='qvq-max'?'vision':id==='tongyi-intent-detect-v3'?'intent':'text';
function payload(id){
 const capability=kind(id),vision=['gui','ocr','vision'].includes(capability);
 let prompt=capability==='translation'?'考试安排':capability==='intent'?'识别以下文本的意图，仅返回简短意图类别：请查看明天的考试安排。':vision?'只输出图中三个白色数字，不要解释。':'仅回答数字：1+1等于几？';
 if(capability==='gui')prompt='这是合成测试界面。给出蓝色矩形中央的点击坐标，格式 [x,y]，不要执行操作。';
 const content=vision?[{type:'image_url',image_url:{url:'data:image/png;base64,'+image.toString('base64')}},{type:'text',text:prompt}]:prompt;
 const body={model:id,messages:[{role:'user',content}],stream:true,max_tokens:1024};
 if(capability==='translation')body.translation_options={source_lang:'Chinese',target_lang:'English'};
 if(/^qwen3/.test(id)&&!id.includes('thinking')&&!id.includes('ocr')&&id!=='qwen3.7-max-preview')body.enable_thinking=false;
 if(id==='qwen3.7-max-preview')body.enable_thinking=true;
 if(id==='qvq-max')body.max_tokens=4096;
 return body;
}
const redact=s=>String(s||'').replace(/sk-[\w.-]+/g,'[REDACTED]').slice(0,350);
async function probe(id,key,endpoint){
 const startedAt=new Date().toISOString(),start=Date.now(),body=payload(id);
 try{
 const response=await fetch(endpoint,{method:'POST',redirect:'error',headers:{'content-type':'application/json',Authorization:'Bearer '+key},body:JSON.stringify(body),signal:AbortSignal.timeout(id==='qvq-max'?120000:45000)});
 const text=await response.text(); if(text.length>2_000_000)throw Error('Response too large');
 if(!response.ok){let e;try{e=JSON.parse(text);}catch{} return {id,capability:kind(id),startedAt,http:response.status,status:'failed',code:redact(e?.error?.code||e?.code||'UPSTREAM_HTTP'),message:redact(e?.error?.message||e?.message),durationMs:Date.now()-start};}
 let content='',finish='',usage=null,servedModel='',requestId='',streamError='';
 if(text.trim().startsWith('{')){
 const e=JSON.parse(text);content=e.choices?.[0]?.message?.content||'';finish=e.choices?.[0]?.finish_reason||'';usage=e.usage;servedModel=e.model;requestId=e.id;streamError=e.error?.code||'';
 }else for(const line of text.split(/\r?\n/)){if(!line.startsWith('data:'))continue;const d=line.slice(5).trim();if(d==='[DONE]')continue;const e=JSON.parse(d);content+=e.choices?.[0]?.delta?.content||'';finish=e.choices?.[0]?.finish_reason||finish;usage=e.usage||usage;servedModel=e.model||servedModel;requestId=e.id||requestId;if(e.error)streamError=e.error.code||'STREAM_ERROR';}
 const cap=kind(id),correct=cap==='text'?/^\s*(?:2|二)[。.!！]?\s*$/.test(content):['vision','ocr'].includes(cap)?content.replace(/\s/g,'')==='123':cap==='translation'?/exam|examination/i.test(content):cap==='intent'?/考试|查看|查询/.test(content):/\b200\b/.test(content)&&/\b110\b/.test(content);
 const complete=!!content.trim()&&['stop','tool_calls'].includes(finish)&&!streamError;
 return {id,capability:cap,startedAt,http:response.status,status:complete&&correct?'passed':complete?'output_mismatch':'incomplete',finish,code:streamError||null,answer:redact(content),servedModel,requestId,durationMs:Date.now()-start,usage,requestSha256:crypto.createHash('sha256').update(JSON.stringify(body)).digest('hex')};
 }catch(e){return {id,capability:kind(id),startedAt,http:null,status:'failed',code:e.name==='TimeoutError'?'TIMEOUT':'TRANSPORT_ERROR',durationMs:Date.now()-start};}
}
if(process.argv.includes('--run')){
 const key=process.env.JKXT_LLM_API_KEY,base=process.env.JKXT_LLM_BASE_URL;
 if(!key||!base)throw Error('Set server-side JKXT_LLM_API_KEY and JKXT_LLM_BASE_URL');
 const u=new URL(base);if(u.protocol!=='https:'||!u.hostname.endsWith('.cn-beijing.maas.aliyuncs.com')||u.username||u.password||u.search)throw Error('Only the supplied Beijing workspace HTTPS endpoint is allowed');
 const filter=process.env.JKXT_MODEL_FILTER?.split(',').filter(Boolean),selected=filter?models.filter(m=>filter.includes(m)):models;
 const reportDir=path.resolve('artifacts/ui-ai-audit-20260905');fs.mkdirSync(reportDir,{recursive:true});
 const results=[],pending=[...selected],runStartedAt=new Date().toISOString();
 const reportFile=path.join(reportDir,filter?'model-retest.json':'model-smoke.json');
 const persist=()=>fs.writeFileSync(reportFile,JSON.stringify({schema:'JKXT.ModelSmoke.v1',runStartedAt,syntheticOnly:true,endpointOrigin:u.origin,total:selected.length,completed:results.length,results},null,2));
 await Promise.all(Array.from({length:3},async()=>{while(pending.length){const id=pending.shift(),r=await probe(id,key,base.replace(/\/+$/,'')+'/chat/completions');results.push(r);persist();console.log(JSON.stringify({id:r.id,status:r.status,http:r.http,code:r.code,durationMs:r.durationMs}));}}));
 console.log(JSON.stringify({reportFile,passed:results.filter(r=>r.status==='passed').length,total:results.length}));
 process.exitCode=results.every(r=>r.status==='passed')?0:2;
}
