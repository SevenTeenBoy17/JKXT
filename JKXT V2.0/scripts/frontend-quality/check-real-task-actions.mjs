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
const memory = new Map();
let destination;
const sandbox = vm.createContext({
  window: { navigateTo: (route) => { destination = route; }, ui: { toast() {} } },
  localStorage: { setItem: (key, value) => memory.set(key, value), getItem: (key) => memory.get(key) },
  console, Date, Intl, Map, Set, Blob, URL, setTimeout, clearTimeout,
});
for (const name of ['tasks-page.jsx', 'task-detail.jsx', 'quick-step4.jsx']) {
  sources[name] = fs.readFileSync(path.join(root, 'src/pages', name), 'utf8');
  const code = babel.transform(sources[name], { presets: ['react'], filename: name }).code;
  vm.runInContext(code, sandbox, { filename: name });
}
const checks = [];
function check(name, run) {
  try { run(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message }); }
}
const run = (code) => vm.runInContext(code, sandbox);
check('edit selects clicked task before navigating', () => {
  memory.set('jkxt:taskDetailId', 'task-A');
  run("openSelectedTask({ id: 'task-B', __backend: true }, 'edit')");
  assert.equal(memory.get('jkxt:taskDetailId'), 'task-B');
  assert.equal(destination, 'edit');
});
check('recent filters use supplied now, reject stale/future/invalid dates', () => {
  const now = Date.parse('2026-09-05T12:00:00Z');
  assert.equal(run(`taskWithinDays('2026-09-04T12:00:00Z', 7, ${now})`), true);
  for (const date of ['2025-01-01T00:00:00Z', '2026-09-06T00:00:00Z', '', 'broken']) {
    assert.equal(run(`taskWithinDays(${JSON.stringify(date)}, 30, ${now})`), false);
  }
});
check('CSV neutralizes formulas and quotes cells', () => {
  for (const value of ['=1+1', '+SUM(A1)', '-2+3', '@SUM(A1)', ' \t=1+1', '\n=1+1']) {
    assert.ok(run(`taskCsvCell(${JSON.stringify(value)})`).startsWith('"\''));
  }
  assert.equal(run(`taskCsvCell('语文,"A"')`), '"语文,""A"""');
  assert.equal(run('taskCsvCell(0)'), '"0"');
});
check('CSV contains the actual filtered row and no fixed mock totals', () => {
  const csv = run("taskRowsToCsv([{id:'real-7',name:'期末考试',status:'done',exams:7,supervisors:14}])");
  assert.ok(csv.includes('real-7') && csv.includes('期末考试') && csv.includes('"14"'));
});
check('task API failures have distinct error/retry and loading states', () => {
  assert.match(sources['tasks-page.jsx'], /tasks-load-error/);
  assert.match(sources['tasks-page.jsx'], /tasks-loading/);
  assert.match(sources['tasks-page.jsx'], /setReloadKey/);
  assert.doesNotMatch(sources['tasks-page.jsx'], /预计 5 秒后下载|累计 21|refNow = tasks\.reduce/);
});
check('no implicit sample fairness or AI for missing server fields', () => {
  assert.equal(run('qkBackendFairness(undefined).length'), 0);
  assert.equal(run('qkBackendAi(undefined)'), null);
  assert.equal(run("qkBackendFairness([{id:'avgLoad',label:'平均',value:0,status:'good'}])[0].value"), 0);
  assert.equal(run("qkBackendFairness([{id:'avgLoad'}])[0].rating"), '未评级');
  assert.doesNotMatch(sources['quick-step4.jsx'], /qkBackendFairness\(result\?\.fairness, fallbackData/);
  assert.match(sources['quick-step4.jsx'], /qk-fairness-unavailable/);
  assert.match(sources['quick-step4.jsx'], /qk-ai-unavailable/);
});
check('unavailable and legacy placeholder metrics never receive successful ratings', () => {
  assert.equal(run("qkBackendFairness([{id:'notesApplied',status:'unavailable',detail:'服务端明确说明：未采集备注结果'}])[0].detail"), '服务端明确说明：未采集备注结果');
  for (const item of [
    {id:'corridorShare',value:'—',status:'unavailable'},
    {id:'notesApplied',value:'0',status:'good',detail:'P6 fixture 暂无特殊备注'},
    {id:'corridorShare',value:'0%',status:'good',detail:'P6 fixture 暂未启用楼道监考'},
  ]) {
    const result = run(`qkBackendFairness([${JSON.stringify(item)}])[0]`);
    assert.equal(result.value, '—');
    assert.equal(result.dots, null);
    assert.equal(result.rating, '未统计');
    assert.match(result.detail, /不计入公平性评估/);
  }
});
check('allowance requires complete actual durations and server unit rate', () => {
  const rows = [{ startTime: '08:00:00', endTime: '09:30:00' }, { startTime: '08:00', endTime: '10:00' }];
  assert.equal(run(`taskAllowance(${JSON.stringify(rows)}, 120)`), 420);
  assert.equal(run(`taskAllowance(${JSON.stringify(rows)}, null)`), null);
  assert.equal(run(`taskAllowance(${JSON.stringify(rows)}, 0)`), 0);
  assert.equal(run("taskAllowance([{startTime:'08:00',endTime:'07:00'}],120)"), null);
  assert.equal(run("taskAllowance([{startTime:'08:00'}],120)"), null);
  assert.doesNotMatch(sources['task-detail.jsx'], /supervisions \|\| 0\) \* 100|全部记录在审计日志/);
});
check('backend detail preserves missing student count and real dates', () => {
  const data = run("mapBackendDetail({id:'t',status:'published',createdAt:'2026-09-01',updatedAt:'2026-09-02'}, {rows:[{examId:'e',roomId:'r',teacherId:'p',teacherName:'甲',startTime:'08:00',endTime:'09:00'}]}, 120)");
  assert.equal(data.stats.allowance, 120);
  assert.equal(data.stats.students, null);
  assert.equal(data.status, 'published');
  assert.equal(data.updatedAt, '2026-09-02');
  assert.equal(run('mapBackendDetail({}, {rows:[]}).status'), '未提供');
  assert.equal(run('mapBackendTaskRow({stats:{},latestJob:{}}).exams'), null);
});
check('audit absence is explicit, execution logs are not invented', () => {
  assert.match(sources['task-detail.jsx'], /尚未提供.*审计/);
  assert.match(sources['task-detail.jsx'], /latestJob\?\.logs/);
  assert.doesNotMatch(sources['task-detail.jsx'], /action: '生成排监考预览'|action: '创建任务'/);
});
check('publication does not claim per-person resend from an idempotent task publish API', () => {
  assert.doesNotMatch(sources['task-detail.jsx'], /重发通知|已重新发布排班通知/);
  assert.match(sources['task-detail.jsx'], /r\.alreadyPublished/);
  assert.match(sources['task-detail.jsx'], /r\.notificationStatus/);
});
check('unfinished, failed, and unknown backend results cannot masquerade as success', () => {
  for (const status of ['pending', 'running', 'failed', 'infeasible', 'cancelled', '', undefined]) {
    assert.equal(run(`qkBackendReady(${JSON.stringify({ status })})`), false);
  }
  assert.equal(run("qkBackendReady({status:'succeeded'})"), true);
});

// Component-level action smoke tests use controlled hooks and actual handlers.
// They do not contact live services or imply browser/layout coverage.
let stateIndex = 0;
let presetStates = [];
const stateUpdates = [];
sandbox.React = {
  Fragment: Symbol.for('react.fragment'),
  createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
  useState: (initial) => {
    const index = stateIndex++;
    return [index < presetStates.length ? presetStates[index] : initial, (value) => stateUpdates.push({ index, value })];
  },
  useRef: (value) => ({ current: value }),
  useEffect() {},
};
for (const identifier of new Set(Object.values(sources).join('\n').match(/\bIcon\w+\b/g))) sandbox[identifier] = identifier;
for (const identifier of ['PageShell', 'EmptyState', 'Button', 'MiniKPI', 'SectionTitle', 'DataTable', 'Tag', 'AvatarChip']) sandbox[identifier] = identifier;
sandbox.orgName = () => '测试学校';
function renderComponent(code, states = []) {
  stateIndex = 0; presetStates = states; stateUpdates.length = 0;
  return run(code);
}
check('task detail initial state renders a loading view without stale data', () => {
  const tree = renderComponent('TaskDetailPage()');
  assert.equal(tree.type, 'PageShell');
  assert.equal(tree.props.children[0].props.title, '正在读取任务…');
});
check('result actions render only returned export formats and preserve missing AI state', () => {
  const tree = renderComponent("QkStep4({taskId:'task-real',result:{status:'succeeded',stats:{exams:2,assignments:4},downloads:{xlsx:'/api/v1/exam-tasks/task-real/export?format=xlsx'}},onRestart:()=>{}})");
  const grid = tree.props.children.find((item) => item && item.type === sandbox.QkDownloadGrid);
  assert.ok(grid);
  assert.equal(grid.props.downloads.length, 1);
  assert.equal(grid.props.downloads[0].format, 'XLSX');
  assert.ok(tree.props.children.some((item) => item?.props?.['data-testid'] === 'qk-ai-unavailable'));
});
check('unknown backend state renders unavailable instead of a generated-result panel', () => {
  const tree = renderComponent("QkStep4({result:{stats:{}},onRestart:()=>{}})");
  assert.equal(tree.type, 'EmptyState');
});

async function checkAsync(name, action) {
  try { await action(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message }); }
}
await checkAsync('real export handler requests the selected task and blocks failed-file success', async () => {
  let requested = null;
  let saved = 0;
  sandbox.window.api = { url: (pathname, query) => pathname + (query ? '?format=' + query.format : '') };
  sandbox.window.JKXTTaskTools.downloadBlob = () => { saved += 1; };
  sandbox.fetch = async (url) => { requested = url; return { ok: false, status: 503 }; };
  let tree = renderComponent("QkStep4({taskId:'task-real',result:{status:'succeeded',stats:{},downloads:{xlsx:'present'}},onRestart:()=>{}})");
  let grid = tree.props.children.find((item) => item && item.type === sandbox.QkDownloadGrid);
  grid.props.onDownload('overall', '完整排班');
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(requested, '/exam-tasks/task-real/export?format=xlsx');
  assert.equal(saved, 0);
  assert.ok(stateUpdates.some((change) => change.value?.error?.includes('503')));
  sandbox.fetch = async (url) => { requested = url; return { ok: true, blob: async () => new Blob(['XLSX fixture'], {type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}) }; };
  tree = renderComponent("QkStep4({taskId:'task-other',result:{status:'succeeded',stats:{},downloads:{xlsx:'present'}},onRestart:()=>{}})");
  grid = tree.props.children.find((item) => item && item.type === sandbox.QkDownloadGrid);
  grid.props.onDownload('overall', '完整排班');
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(requested, '/exam-tasks/task-other/export?format=xlsx');
  assert.equal(saved, 1);
});
console.log(JSON.stringify({ contract: 'RealTaskActions/v1', checks, passed: checks.filter((c) => c.pass).length, failed: checks.filter((c) => !c.pass).length }, null, 2));
if (checks.some((c) => !c.pass)) process.exitCode = 1;
