import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/tasks.jsx'), 'utf8');
const storage = new Map();
let route;
const context = vm.createContext({window:{navigateTo:r=>route=r},localStorage:{setItem:(k,v)=>storage.set(k,v)},React:{createElement:(type,props,...children)=>({type,props:{...props,children}})},IconList:'icon',IconChevron:'chevron',console,Date,Number,Math});
vm.runInContext(babel.transform(source,{presets:['react']}).code, context);
const run = text => vm.runInContext(text,context);
const results = [];
function check(name,fn){try{fn();results.push({name,pass:true});}catch(e){results.push({name,pass:false,error:e.message});}}
check('missing progress is not fabricated',()=>{
  assert.equal(run("rtMapTask({status:'draft'}).progress"),null);
  assert.equal(run("rtMapTask({latestJob:{status:'running'}}).progress"),null);
  assert.equal(run("rtMapTask({latestJob:{status:'succeeded',progress:100}}).progress"),100);
  assert.equal(run("rtMapTask({stats:{exams:0,assignments:0}}).exams"),0);
  assert.equal(run("rtMapTask({}).exams"),null);
});
check('invalid progress cannot escape 0 to 100 range',()=>{
  assert.equal(run("rtMapTask({latestJob:{progress:999}}).progress"),100);
  assert.equal(run("rtMapTask({latestJob:{progress:-1}}).progress"),0);
  assert.equal(run("rtMapTask({latestJob:{progress:'bad'}}).progress"),null);
  for(const value of ["''", "'  '", 'false', 'true', '[]', '{}', 'null', 'undefined']) {
    assert.equal(run(`rtMapTask({latestJob:{progress:${value}}}).progress`),null,value);
  }
  assert.equal(run("rtMapTask({latestJob:{progress:'0'}}).progress"),0);
  assert.equal(run("rtMapTask({latestJob:{progress:0}}).progress"),0);
});
check('native recent task button selects actual task',()=>{
  const row=run("TaskRow({t:{id:'real-99',name:'一个很长的中文任务名称 Long examination title',status:'done',progress:100,exams:2,supervisors:4,updated:'09-05 15:50'},index:0})");
  assert.equal(row.type,'button');assert.equal(row.props.type,'button');
  assert.match(row.props['aria-label'],/一个很长的中文任务名称/);
  row.props.onClick();assert.equal(storage.get('jkxt:taskDetailId'),'real-99');assert.equal(route,'task-detail');
});
check('status has explicit unbreakable semantic class',()=>{
  const pill=run("StatusPill({status:'done'})");
  assert.match(pill.props.className,/recent-task-status/);
  const css=fs.readFileSync(path.join(root,'src/styles/usability-20260905.css'),'utf8');
  assert.match(css,/\.recent-task-status\s*\{[^}]*white-space:\s*nowrap/s);
  assert.match(css,/\.recent-task-status\s*\{[^}]*flex:\s*0 0 auto/s);
});
check('request error cannot become empty task success',()=>{
  assert.match(source,/recent-tasks-error/);assert.match(source,/setError/);
  assert.doesNotMatch(source,/catch\(\(\) => \{ if \(alive\) setTasks\(\[\]\)/);
});
check('all small status labels meet AA contrast',()=>{
  const luminance=hex=>hex.slice(1).match(/../g).map(h=>parseInt(h,16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((n,v,i)=>n+v*[.2126,.7152,.0722][i],0);
  for(const status of Object.values(run('STATUS'))){
    const a=luminance(status.bg),b=luminance(status.fg);
    assert.ok((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5,status.label);
  }
});
for(const [name,api] of [['missing method',{}],['synchronous failure',{get(){throw Error('offline');}}],['rejected request',{get(){return Promise.reject(Error('offline'));}}],['malformed payload',{get(){return Promise.resolve({});}}]]){
  const states=[];let effect;
  context.window.api=api;
  context.React.useState=value=>{const i=states.length;states.push(value);return[value,next=>{states[i]=next;}];};
  context.React.useEffect=fn=>{effect=fn;};
  run('RecentTasksCard()');
  let thrown;
  try{effect();await new Promise(resolve=>setImmediate(resolve));}catch(e){thrown=e;}
  check(`actual component handles ${name}`,()=>{assert.equal(thrown,undefined);assert.equal(states[0],null);assert.match(states[1],/服务尚未连接|任务加载失败/);});
}
check('shared tags retain readable type and unshrinkable geometry',()=>{
  vm.runInContext(babel.transform(fs.readFileSync(path.join(root,'src/common.jsx'),'utf8'),{presets:['react']}).code,context);
  const tag=run("Tag({children:'运行中',color:'emerald',dot:true})");
  assert.match(tag.props.className,/shrink-0/);assert.match(tag.props.className,/text-\[12px\]/);assert.match(tag.props.className,/whitespace-nowrap/);
});
check('term row no longer clips name or date to a fixed height',()=>{
  const settings=fs.readFileSync(path.join(root,'src/pages/settings.jsx'),'utf8');
  const start=settings.indexOf('<div key={t.id}');
  const row=settings.slice(start,settings.indexOf('设为当前',start));
  assert.match(row,/settings-term-row/);assert.doesNotMatch(row,/justify-between h-11|font-semibold truncate/);
  assert.match(row,/overflowWrap: 'anywhere'/);assert.match(row,/min-h-11/);
});
check('calendar controls and weekend surfaces follow both theme schemes',()=>{
  const timeline=fs.readFileSync(path.join(root,'src/timeline.jsx'),'utf8');
  assert.match(timeline,/d\.dim \? 'var\(--surface-muted\)'/);
  assert.doesNotMatch(timeline,/background: active \? '#FFFFFF'/);
  assert.match(timeline,/background: active \? 'var\(--surface-raised\)'/);
  const css=fs.readFileSync(path.join(root,'src/styles/usability-20260905.css'),'utf8');
  assert.match(css,/\.sidebar-nav-item\[data-active="true"\][^{]*\{[^}]*color: var\(--brand\) !important/s);
});
console.log(JSON.stringify({suite:'RecentTaskIntegrity',results,passed:results.filter(r=>r.pass).length,failed:results.filter(r=>!r.pass).length},null,2));
if(results.some(r=>!r.pass))process.exitCode=1;
