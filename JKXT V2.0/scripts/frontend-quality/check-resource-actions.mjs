import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const sources = {};
const sandbox = vm.createContext({ window: {}, console, Date, Map, Set, Number, String, localStorage: {getItem:()=>null} });
for (const name of ['people.jsx', 'edit.jsx']) {
  sources[name] = fs.readFileSync(path.join(root, 'src/pages', name), 'utf8');
  vm.runInContext(babel.transform(sources[name], { presets: ['react'], filename: name }).code, sandbox, {filename:name});
}
const run = (code) => vm.runInContext(code, sandbox);
const checks = [];
async function check(name, action) {
  try { await action(); checks.push({name, pass:true}); }
  catch (error) { checks.push({name, pass:false, error:error.message.slice(0,600)}); }
}
await check('teacher schedule filters by stable UUID, never name or employee number', () => {
  const result = run("resourceTeacherRows({rows:[{teacherId:'uuid-A',teacherName:'同名',assignmentId:'a'},{teacherId:'uuid-B',teacherName:'同名',assignmentId:'b'}]}, {uuid:'uuid-B',id:'T1',name:'同名'})");
  assert.equal(result.length, 1);
  assert.equal(result[0].assignmentId, 'b');
  assert.throws(() => run("resourceTeacherRows({rows:[]},{id:'T1'})"));
  assert.throws(() => run("resourceTeacherRows({}, {uuid:'uuid-A'})"));
});
await check('teacher detailed schedule opens a real component, not a toast', () => {
  assert.match(sources['people.jsx'], /content: <TeacherScheduleTimeline teacher=\{t\}/);
  assert.match(sources['people.jsx'], /\/exam-tasks\/\$\{encodeURIComponent\(selectedTaskId\)\}\/assignments/);
  assert.doesNotMatch(sources['people.jsx'], /toast\(`查看 \$\{t\.name\} 详细排班|const schedule = \[\]|i \+ 1 === 21/);
});
await check('schedule loading and failures are truthful with retry', () => {
  assert.match(sources['people.jsx'], /teacher-schedule-error/);
  assert.match(sources['people.jsx'], /teacher-schedule-loading/);
  assert.match(sources['people.jsx'], /setReloadKey/);
});
await check('AI optimization is honestly unavailable without a real endpoint', () => {
  assert.doesNotMatch(sources['edit.jsx'], /AI 修复建议将在 P10 接入/);
  assert.match(sources['edit.jsx'], /disabled[^\n]*AI 自动优化/);
});
await check('replacement prechecks before patch and propagates expected revision', async () => {
  const calls = [];
  sandbox.mockApi = {
    post:async (url, body) => {calls.push({method:'post',url,body}); return {allowed:true,conflicts:[]};},
    patch:async (url, body) => {calls.push({method:'patch',url,body}); return {assignmentId:'assignment-A',teacherId:'teacher-B',teacherName:'乙'};},
  };
  await run("requestScheduleReplacement(mockApi,'task-A',{assignmentId:'assignment-A'},'teacher-B',7,'keyboard-select')");
  assert.equal(calls.length,2);
  assert.equal(calls[0].url,'/tasks/task-A/schedule/check-conflicts');
  assert.equal(calls[1].body.expectedRevision,7);
  assert.equal(calls[1].body.reason,'keyboard-select');
});
await check('blocked or missing precheck approval never writes a replacement', async () => {
  let writes=0;
  for (const checkResult of [{allowed:false,conflicts:[{message:'时间冲突'}]},{}]) {
    sandbox.mockApi={post:async()=>checkResult,patch:async()=>{writes++;}};
    const result = await run("requestScheduleReplacement(mockApi,'task-A',{assignmentId:'assignment-A'},'teacher-B',0,'keyboard-select')");
    assert.equal(result.patched,null);
  }
  assert.equal(writes,0);
});
await check('reassignment supports keyboard selection and labelled native controls', () => {
  assert.match(sources['edit.jsx'], /onSelectTeacher/);
  assert.match(sources['edit.jsx'], /onAssignSelected/);
  assert.match(sources['edit.jsx'], /aria-pressed/);
  assert.match(sources['edit.jsx'], /aria-label="撤销上一笔改派"/);
});

let stateIndex=0;
let stateValues=[];
const updates=[];
sandbox.React={
  Fragment:Symbol.for('react.fragment'),
  createElement:(type,props,...children)=>({type,props:{...props,children}}),
  useState:(initial)=>{const index=stateIndex++;return [index<stateValues.length?stateValues[index]:initial,(value)=>updates.push({index,value})];},
  useRef:(value)=>({current:value}), useEffect(){}, useMemo:(action)=>action(), useCallback:(action)=>action,
};
for(const identifier of new Set(Object.values(sources).join('\n').match(/\bIcon\w+\b/g))) sandbox[identifier]=identifier;
for(const identifier of ['PageShell','SectionTitle','Button','Tag','AvatarChip','FilterBar','EmptyState'])sandbox[identifier]=identifier;
sandbox.orgName=()=> '测试学校';
function render(code,values=[]){stateIndex=0;stateValues=values;updates.length=0;return run(code);}
function nodes(tree,match){
  if(!tree||typeof tree!=='object')return [];
  if(Array.isArray(tree))return tree.flatMap((item)=>nodes(item,match));
  return [...(match(tree)?[tree]:[]),...nodes(tree.props?.children,match)];
}
await check('teacher schedule ready render contains selected teacher rows and task actions',()=>{
  const tree=render("TeacherScheduleTimeline({teacher:{uuid:'teacher-B',name:'乙'}})",[
    [{id:'task-A',name:'期末'}],'task-A',{status:'ready',error:''},
    {status:'ready',error:'',teacherId:'teacher-B',taskId:'task-A',rows:[{assignmentId:'slot-A',teacherId:'teacher-B',examDate:'2026-09-05',startTime:'08:00',endTime:'10:00',subjectName:'语文',roomCode:'R1'}]},0,
  ]);
  assert.equal(nodes(tree,(item)=>item.type==='select')[0].props['aria-label'],'选择考务任务');
  assert.ok(nodes(tree,(item)=>item.type==='td').some((item)=>item.props.children.includes('语文')));
  let selected;
  sandbox.window.JKXTTaskTools={open:(task,route)=>{selected={task,route};return true;}};
  sandbox.window.ui={closeDrawer(){}};
  nodes(tree,(item)=>item.type==='Button'&&item.props.children.includes('调整此任务'))[0].props.onClick();
  assert.equal(selected.task.id,'task-A');
  assert.equal(selected.route,'edit');
});
await check('native teacher and assignment buttons expose real selection handlers',()=>{
  let chosen;
  sandbox.choose=(teacher)=>{chosen=teacher.id;};
  const rail=render("TeacherCandidateRail({teachers:[{id:'teacher-B',name:'乙'}],draggedTeacherId:'teacher-B',onSelectTeacher:choose})");
  const button=nodes(rail,(item)=>item.type==='button')[0];
  assert.equal(button.props['aria-pressed'],true);
  button.props.onClick();
  assert.equal(chosen,'teacher-B');
  let slot;
  sandbox.assign=(row)=>{slot=row.assignmentId;};
  const assignment=render("AssignmentDropSlot({row:{assignmentId:'slot-A',teacherName:'甲'},onAssignSelected:assign,selectedTeacher:{name:'乙'}})");
  assert.equal(assignment.type,'button');
  assert.ok(assignment.props['aria-label'].includes('改派给 乙'));
  assignment.props.onClick();
  assert.equal(slot,'slot-A');
});
await check('mismatched server patch result is never accepted as successful reassignment',async()=>{
  sandbox.mockApi={post:async()=>({allowed:true}),patch:async()=>({assignmentId:'other',teacherId:'teacher-B'})};
  await assert.rejects(run("requestScheduleReplacement(mockApi,'task-A',{assignmentId:'slot-A'},'teacher-B',1,'keyboard-select')"),/未返回匹配/);
});
await check('active edit page selection previews before explicit checked revision-bound confirmation',async()=>{
  let refreshes=0;
  const calls=[];
  const toasts=[];
  // Exercise public UI handlers, not private useState positions from the old
  // immediate-write interaction. Persist refs so confirmation uses live context.
  const hooks=[]; let cursor=0;
  sandbox.React.useState=initial=>{const i=cursor++;if(!(i in hooks))hooks[i]=typeof initial==='function'?initial():initial;return [hooks[i],next=>{hooks[i]=typeof next==='function'?next(hooks[i]):next;}];};
  sandbox.React.useRef=initial=>{const i=cursor++;if(!(i in hooks))hooks[i]={current:initial};return hooks[i];};
  // Expand only these stateless production children. Keeping hooks owned by the
  // page avoids the old shallow-tree blind spot without inventing a ScheduleCell.
  const componentTypes=new Set(run('[ManualScheduleGrid,ScheduleCell,AssignmentDropSlot,TeacherCandidateRail,ManualReplacementDialog]'));
  const expand=tree=>{
    if(!tree||typeof tree!=='object')return tree;
    if(Array.isArray(tree))return tree.map(expand);
    if(componentTypes.has(tree.type))return expand(tree.type(tree.props));
    return {...tree,props:{...tree.props,children:expand(tree.props?.children)}};
  };
  const renderEdit=()=>{cursor=0;return expand(run('EditSchedulePage()'));};
  sandbox.useScheduleEditData=()=>({loading:false,error:'',taskId:'task-A',task:{id:'task-A',name:'期末考试',status:'scheduled'},assignments:{taskId:'task-A',runId:'run-A',status:'scheduled',stats:{revisionCursor:3},rows:[{assignmentId:'slot-A',teacherId:'teacher-A',teacherName:'甲',roomId:'room-A',roomCode:'R1',examId:'exam-A',examDate:'2026-09-05',startTime:'08:00',endTime:'10:00'}]},teachers:[{id:'teacher-B',name:'乙'}],reload:async()=>{refreshes++;return true;}});
  sandbox.window.api={post:async(url,body)=>{calls.push({method:'post',url,body});return {allowed:true,conflicts:[]};},patch:async(url,body)=>{calls.push({method:'patch',url,body});return {...body,teacherName:'乙'};}};
  sandbox.window.ui={toast:(message)=>toasts.push(message)};
  const initialTree=renderEdit();
  assert.equal(nodes(initialTree,item=>item.props?.['data-testid']==='md-grid-time').length,1,'Actual ManualScheduleGrid renders once');
  const cell=nodes(initialTree,item=>item.type==='button'&&item.props?.['data-assignment-id']==='slot-A')[0];
  assert.ok(cell,'Actual assignment control is reachable through ManualScheduleGrid and ScheduleCell');
  assert.equal(cell.props.disabled,false);
  await cell.props.onClick({currentTarget:{focus(){}}});
  const candidate=nodes(renderEdit(),item=>item.type==='button'&&item.props?.['data-teacher-id']==='teacher-B')[0];
  assert.ok(candidate,'Actual candidate selection control is rendered after selecting a slot');
  candidate.props.onClick();
  assert.equal(calls.length,0,'Selecting a slot and teacher must not write');
  const findControl=id=>nodes(renderEdit(),item=>item.props?.['data-testid']===id)[0];
  assert.equal(findControl('md-confirm').props.disabled,true,'Reason is required');
  await findControl('md-confirm').props.onClick();
  assert.equal(calls.length,0);
  findControl('md-reason').props.onChange({target:{value:'原教师请假，已核实替补安排'}});
  await findControl('md-confirm').props.onClick();
  assert.equal(calls.length,2);
  assert.equal(calls[0].url,'/tasks/task-A/schedule/check-conflicts');
  assert.equal(calls[1].url,'/tasks/task-A/schedule');
  assert.equal(calls[1].body.teacherId,'teacher-B');
  assert.equal(calls[1].body.assignmentId,'slot-A');
  assert.equal(calls[1].body.expectedRevision,3);
  assert.equal(calls[1].body.reason,'原教师请假，已核实替补安排');
  assert.equal(refreshes,1);
  assert.ok(toasts.includes('监考员已改派'));
});
console.log(JSON.stringify({contract:'ResourceActions/v1', checks,passed:checks.filter((item)=>item.pass).length,failed:checks.filter((item)=>!item.pass).length},null,2));
if(checks.some((item)=>!item.pass)) process.exitCode=1;
