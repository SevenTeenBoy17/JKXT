import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = fs.readFileSync(path.join(root,'src/pages/edit.jsx'),'utf8');
const css = fs.readFileSync(path.join(root,'src/styles/manual-v5.css'),'utf8');
const babel = createRequire(import.meta.url)(path.join(root,'assets/vendor/babel-standalone.7.29.0.min.js'));
const rows = [
  { assignmentId:'a1',roomId:'r1',roomCode:'101',examId:'e1',examDate:'2026-11-05',startTime:'08:30',endTime:'10:00',teacherId:'t1',teacherName:'原教师',subjectName:'语文',role:'main' },
  { assignmentId:'a2',roomId:'r1',roomCode:'101',examId:'e2',examDate:'2026-11-05',startTime:'08:30',endTime:'10:00',teacherId:'t1',teacherName:'原教师',subjectName:'数学',role:'assistant' },
  { assignmentId:'a3',roomId:'r2',roomCode:'102',examId:'e3',examDate:'2026-11-06',startTime:'10:30',endTime:'12:00',teacherId:'t1',teacherName:'原教师',subjectName:'英语',role:'main' },
];
const teachers = [{id:'t1',name:'原教师',teacherNo:'T01'},{id:'t2',name:'候选教师',teacherNo:'T02',subject:'语文'}];
const task = {id:'task-a',name:'本校期中考试',status:'scheduled'};
const hooks=[],effects=[],mutations=[];
let hookIndex=0;
let denial=false;
const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
const ctx=vm.createContext({console,Date,Intl,Map,Set,Number,String,
  localStorage:{getItem:()=>task.id,setItem(){}},orgName:()=> '本校',
  window:{BlueprintDialog:'BlueprintDialog',BlueprintDrawer:'BlueprintDrawer',ui:{toast(){}},addEventListener(){},removeEventListener(){},api:{
    get:async url=>url==='/teachers'?teachers:url.endsWith('/assignments')?{taskId:task.id,runId:'run-a',rows,stats:{revisionCursor:2}}:task,
    post:async(url,body)=>{mutations.push({method:'post',url,body});return {allowed:!denial,conflicts:denial?[{message:'本时段已安排监考'}]:[]};},
    patch:async(url,body)=>{mutations.push({method:'patch',url,body});return {...body,teacherName:'候选教师'};},
  }},
  React:{Fragment:'Fragment',createElement:(type,props,...children)=>({type,props:{...props,children}}),
    useState(initial){const i=hookIndex++;if(!(i in hooks))hooks[i]=typeof initial==='function'?initial():initial;return[hooks[i],v=>{hooks[i]=typeof v==='function'?v(hooks[i]):v;}];},
    useRef(initial){const i=hookIndex++;return hooks[i]||=( {current:initial});},
    useMemo(fn,deps){const i=hookIndex++;if(!hooks[i]||!same(hooks[i].deps,deps))hooks[i]={deps,value:fn()};return hooks[i].value;},
    useCallback(fn,deps){return this.useMemo(()=>fn,deps);},
    useEffect(fn,deps){const i=hookIndex++;if(!hooks[i]||!same(hooks[i].deps,deps)){const previous=hooks[i];hooks[i]={deps};effects.push(()=>{previous?.cleanup?.();hooks[i].cleanup=fn();});}},
  },
});
for(const name of new Set(source.match(/\bIcon\w+\b/g)))ctx[name]=name;
for(const name of ['PageShell','Button','Tag','AvatarChip','FilterBar','EmptyState'])ctx[name]=name;
vm.runInContext(babel.transform(source,{presets:['react']}).code,ctx);
const render=()=>{hookIndex=0;return ctx.EditSchedulePage();};
const flatten=value=>!value||typeof value!=='object'?[]:Array.isArray(value)?value.flatMap(flatten):[value,...flatten(typeof value.type==='function'?value.type(value.props):value.props?.children),...flatten(value.props?.footer),...flatten(value.props?.actions)];
const all=()=>flatten(render());
const find=id=>all().find(n=>n.props?.['data-testid']===id);
const flush=async()=>{for(let i=0;i<5;i++){render();effects.splice(0).forEach(fn=>fn());await new Promise(r=>setImmediate(r));}};
await flush();
assert.equal(ctx.manualVisibleRows(rows,'语文','2026-11-05').length,1);
assert.equal(ctx.manualVisibleRows(rows,'','2026-11-06').length,1);
assert.equal(ctx.manualVisibleRows(rows,'missing','').length,0);
assert.equal(ctx.groupSchedule([{...rows[0],roomId:null,roomCode:'X'},{...rows[0],roomId:null,roomCode:'Y'}]).cells.size,2,'distinct room codes are not merged when roomId is missing');
assert.equal(all().filter(n=>n.props?.['data-testid']==='p9-assignment-slot').length,3,'all exams sharing room/time are visible, not just first cell');
find('md-date-filter').props.onChange({target:{value:'2026-11-06'}});
assert.equal(all().filter(n=>n.props?.['data-testid']==='p9-assignment-slot').length,1);
find('md-view-room').props.onClick();
assert.ok(find('md-grid-room'));
assert.equal(find('md-view-room').props['aria-pressed'],true);
find('md-date-filter').props.onChange({target:{value:''}});
assert.equal(all().filter(n=>n.props?.['data-testid']==='p9-assignment-slot').length,3);
find('md-view-time').props.onClick();
assert.ok(find('md-grid-time'));
assert.equal(mutations.length,0,'filters and orientation do not write');
assert.equal(all().some(n=>n.type==='BlueprintDialog'),false);
find('p9-assignment-slot').props.onClick();
assert.equal(all().some(n=>n.type==='BlueprintDialog'),false,'slot selection only reveals candidates');
const details=all().find(n=>n.props?.['aria-label']==='查看候选教师的资料');
details.props.onClick();
assert.equal(all().some(n=>n.type==='BlueprintDrawer'),true,'profile opens the shared drawer');
all().find(n=>n.type==='BlueprintDrawer').props.onClose();
assert.equal(all().some(n=>n.type==='BlueprintDrawer'),false);
all().find(n=>n.props?.['data-teacher-id']==='t2').props.onClick();
assert.equal(all().some(n=>n.type==='BlueprintDialog'),true,'candidate selection opens the shared modal');
assert.equal(mutations.length,0,'opening a preview does not save');
assert.equal(find('md-confirm').props.disabled,true,'reason is required');
find('md-reason').props.onChange({target:{value:'原教师临时请假，已核对候选'}});
denial=true;
await find('md-confirm').props.onClick();await flush();
assert.equal(mutations.some(m=>m.method==='patch'),false,'denied precheck never writes');
assert.ok(find('md-reselect'));
assert.ok(find('md-error'));
assert.equal(find('md-reason').props.value,'原教师临时请假，已核对候选');
find('md-reselect').props.onClick();
assert.equal(all().some(n=>n.type==='BlueprintDialog'),false);
assert.equal(all().find(n=>n.props?.['data-assignment-id']==='a1').props['aria-pressed'],true,'reselection retains original selected post');
all().find(n=>n.props?.['data-teacher-id']==='t2').props.onClick();
assert.equal(find('md-reason').props.value,'原教师临时请假，已核对候选','reselection preserves reason');
denial=false;
await find('md-confirm').props.onClick();await flush();
assert.equal(mutations.filter(m=>m.method==='patch').length,1);
assert.equal(mutations.find(m=>m.method==='patch').body.expectedRevision,2);
assert.equal(all().some(n=>n.type==='BlueprintDialog'),false);
assert.match(css, /@media\(max-width:640px\)/);
assert.match(css, /prefers-reduced-motion:reduce/);
assert.match(css, /forced-colors:active/);
assert.match(css, /min-height:44px/);
assert.doesNotMatch(css, /(?:^|\n)(?:body|html|\.sidebar|\.topbar)[\s{]/);
console.log('PASS Manual V5: JSX, real date/search filters, transposed grid, all same-slot exams visible, missing-ID room separation, shared modal/drawer, preview-only selection, blocked context/reason preservation, real precheck→patch and scoped responsive styles.');
