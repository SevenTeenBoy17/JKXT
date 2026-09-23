// Isolated actual TeacherFormModal handler tests. No HTTP, browser, DB or user data.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const args = process.argv.slice(2);
const label = args.includes('--red') ? 'red' : args.includes('--baseline') ? 'baseline' : 'green';
const sourcePath = args.includes('--baseline')
  ? 'artifacts/school-e2e-20260909/profile-fix/people.before.jsx' : 'src/pages/people.jsx';
const startedAt = new Date().toISOString();
const source = fs.readFileSync(path.join(root, sourcePath), 'utf8');
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const compiled = Babel.transform(source, { presets: ['react'] }).code;
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const clone = value => JSON.parse(JSON.stringify(value));
const teacherId = 'a0000000-0000-4000-8000-000000000001';
const classId = 'b0000000-0000-4000-8000-000000000001';
const subjectId = 'c0000000-0000-4000-8000-000000000001';
const profile = {
  uuid: teacherId, id: 'SYN-T001', teacherNo: 'SYN-T001', name: '合成教师甲',
  gender: 'female', grade: '高一', phone: null, email: null, status: 'active',
  age: 46, position: '年级组长（合成）',
  leaveWindows: [{start:'2026-11-16T08:00:00',end:'2026-11-16T12:00:00',reason:'合成请假'}],
  functionalAccommodations: ['GROUND_FLOOR'],
  dutyWindows: [{start:'2026-11-17T08:00:00',end:'2026-11-17T18:00:00',reason:'合成可用'}],
  qualifications: ['CHIEF','INVIGILATOR'], qualificationExpiry: '2026-12-31',
  maxDailySessions: 1, preferredCampus: '合成南校区', isHomeroom: true,
  homeroomClassId: classId, homeroomOf: '高一（1）班', subjects: ['语文'],
  supCount: 6, allowance: 240, gradeColor: 'indigo',
  unexpectedReadOnly: 'must not leak into PUT', createdAt: '2026-01-01T00:00:00Z',
};
const preserved = ['age','position','leaveWindows','functionalAccommodations','dutyWindows',
  'qualifications','qualificationExpiry','maxDailySessions','preferredCampus','homeroomClassId'];

function harness({response=clone(profile), reject=false, pending=false, teacher={uuid:teacherId}}={}) {
  const values=[], dependencies=[], effects=[], writes=[], calls=[];
  let cursor=0, effectsCursor=0, tree, release, closed=false;
  const React = {
    createElement(type, props, ...children) {return {type,props:{...(props||{}),children:children.flat(Infinity)}};},
    useState(initial) {const index=cursor++; if(!(index in values)) values[index]=typeof initial==='function'?initial():initial;
      return [values[index], value=>{values[index]=typeof value==='function'?value(values[index]):value;}];},
    useRef(initial) {const index=cursor++; if(!(index in values)) values[index]={current:initial}; return values[index];},
    useEffect(callback,deps) {const index=effectsCursor++; const before=dependencies[index];
      if(!before||deps.some((v,i)=>v!==before[i])) {dependencies[index]=deps;effects.push(callback);}},
  };
  const context = {React,window:{api:{
    async get(url) {calls.push(url);if(pending) await new Promise(resolve=>{release=resolve;});if(reject)throw Error('synthetic GET unavailable');return response;},
    async put(url,body) {writes.push({method:'PUT',url,body:clone(body)});return {};},
    async post(url,body) {writes.push({method:'POST',url,body:clone(body)});return {};},
  },ui:{closeModal(){closed=true;},toast(){}}},Button:'Button',IconCheck:'IconCheck',console};
  vm.createContext(context); vm.runInContext(compiled,context,{filename:sourcePath});
  function render() {cursor=0;effectsCursor=0;tree=context.TeacherFormModal({teacher,onSaved(){}});return tree;}
  function all(node=tree) {if(!node||typeof node!=='object')return [];return [node,...(node.props?.children||[]).flatMap(all)];}
  function field(label) {const node=all().find(n=>n.props?.label===label);assert.ok(node,`field ${label}`);
    const input=all(node).find(n=>['input','select'].includes(n.type));assert.ok(input,`input ${label}`);return input;}
  function change(label,value) {field(label).props.onChange({target:{value}});render();}
  function submitButton() {const buttons=all().filter(n=>n.type==='Button');return buttons.find(n=>n.props.variant==='primary');}
  async function settle() {for(const effect of effects.splice(0)) effect();await new Promise(resolve=>setImmediate(resolve));render();}
  render();
  return {render,settle,all,change,submitButton,writes,calls,release:()=>release?.(),closed:()=>closed};
}
const results=[];
async function check(name,run) {try {await run();results.push({name,pass:true});console.log(`PASS ${name}`);}
  catch(error){results.push({name,pass:false,error:error.message});console.log(`FAIL ${name}: ${error.message}`);}}
await check('basic edit preserves full professional profile and real UUID',async()=>{
  const h=harness();await h.settle();h.change('姓名','合成教师甲·已更名');h.change('联系电话','');
  await h.submitButton().props.onClick();assert.equal(h.writes.length,1);const w=h.writes[0];
  assert.equal(w.url,`/teachers/${teacherId}`);assert.equal(w.body.name,'合成教师甲·已更名');assert.equal(w.body.phone,null);
  for(const key of preserved)assert.deepEqual(w.body[key],profile[key],`preserve ${key}`);
  assert.equal(w.body.isHomeroom,true);assert.equal(w.body.teacherNo,'SYN-T001');
  assert.ok(!Object.hasOwn(w.body,'subjectIds'),'actual DTO lacks IDs: preserve relation through omission, never translate names');
  for(const key of ['uuid','id','homeroomOf','subjects','supCount','allowance','gradeColor','createdAt','unexpectedReadOnly'])
    assert.ok(!Object.hasOwn(w.body,key),`read-only ${key} must not be submitted`);
});
await check('explicit subject UUIDs preserved without using display names',async()=>{
  const h=harness({response:{...clone(profile),subjectIds:[subjectId]}});await h.settle();
  await h.submitButton().props.onClick();assert.deepEqual(h.writes[0]?.body.subjectIds,[subjectId]);
});
await check('null scalar and empty arrays retained without inventing defaults',async()=>{
  const p={...clone(profile),age:null,position:null,qualificationExpiry:null,preferredCampus:null,
    homeroomClassId:null,isHomeroom:false,qualifications:[],leaveWindows:[],dutyWindows:[],functionalAccommodations:[],maxDailySessions:2};
  const h=harness({response:p});await h.settle();await h.submitButton().props.onClick();
  for(const key of preserved)assert.deepEqual(h.writes[0]?.body[key],p[key],`preserve null/empty ${key}`);
});
await check('failed GET cannot save normalized defaults or bypass disabled button',async()=>{
  const h=harness({reject:true});await h.settle();
  assert.equal(h.submitButton()?.props.disabled,true);await h.submitButton().props.onClick();assert.equal(h.writes.length,0);
});
await check('mismatched GET UUID blocks edit',async()=>{
  const h=harness({response:{...clone(profile),uuid:'a0000000-0000-4000-8000-000000000002'}});await h.settle();
  assert.equal(h.submitButton()?.props.disabled,true);await h.submitButton().props.onClick();assert.equal(h.writes.length,0);
});
await check('incomplete GET professional profile is not accepted',async()=>{
  const p=clone(profile);delete p.qualifications;const h=harness({response:p});await h.settle();
  assert.equal(h.submitButton()?.props.disabled,true);await h.submitButton().props.onClick();assert.equal(h.writes.length,0);
});
await check('missing original basic fields do not become editable default values',async()=>{
  const p=clone(profile);delete p.grade;const h=harness({response:p});await h.settle();
  assert.equal(h.submitButton()?.props.disabled,true);await h.submitButton().props.onClick();assert.equal(h.writes.length,0);
});
await check('missing source UUID leaves save disabled without a write',async()=>{
  const h=harness({teacher:{id:'SYN-T001'}});await h.settle();
  assert.equal(h.submitButton()?.props.disabled,true);await h.submitButton().props.onClick();assert.equal(h.writes.length,0);
});
await check('invalid subject identifiers cannot be reconstructed from subject names',async()=>{
  const h=harness({response:{...clone(profile),subjectIds:['语文']}});await h.settle();
  assert.equal(h.submitButton()?.props.disabled,true);await h.submitButton().props.onClick();assert.equal(h.writes.length,0);
});
await check('pending GET exposes loading and does not offer save',async()=>{
  const h=harness({pending:true});await h.settle();assert.equal(h.submitButton(),undefined);
  h.release();await h.settle();assert.ok(h.submitButton());
});
await check('new teacher retains existing minimal create contract',async()=>{
  const h=harness({teacher:null});await h.settle();assert.equal(h.calls.length,0);
  h.change('工号','SYN-NEW');h.change('姓名','合成新增教师');await h.submitButton().props.onClick();
  assert.equal(h.writes.length,1);assert.equal(h.writes[0].method,'POST');assert.equal(h.writes[0].url,'/teachers');
  assert.equal(h.writes[0].body.name,'合成新增教师');assert.equal(h.writes[0].body.teacherNo,'SYN-NEW');
});
await check('subject omission server contract remains null-preserving',async()=>{
  const service=fs.readFileSync(path.join(root,'backend/src/main/java/com/jkxt/masterdata/service/MasterDataService.java'),'utf8');
  const update=service.slice(service.indexOf('public TeacherDto updateTeacher('),service.indexOf('public void deleteTeacher('));
  assert.match(update,/if\s*\(request\.subjectIds\(\) != null\)\s*\{\s*replaceSubjects/s);
});
const output=path.join(root,'artifacts/school-e2e-20260909/profile-fix',`${label}-results.json`);
const report={schema:'TeacherProfilePreservation/v1',startedAt,finishedAt:new Date().toISOString(),label,
  scope:'Actual compiled React form handlers with isolated synthetic API doubles; no browser/live backend proof',
  sourcePath,sourceSha256:hash(source),testSha256:hash(fs.readFileSync(fileURLToPath(import.meta.url))),
  passed:results.filter(x=>x.pass).length,failed:results.filter(x=>!x.pass).length,results};
fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({report:output,passed:report.passed,failed:report.failed,sha256:hash(fs.readFileSync(output))}));
process.exitCode=report.failed?1:0;
