import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/tasks-page.jsx'), 'utf8');
const memory = new Map();
let destination;
let stateIndex = 0;
let states = [];
const updates = [];
const context = vm.createContext({
  window: { navigateTo: value => { destination = value; }, ui: { toast() {} } },
  localStorage: { setItem: (key, value) => memory.set(key, value) },
  React: {
    Fragment: 'Fragment',
    createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
    useState: initial => { const index = stateIndex++; return [index < states.length ? states[index] : initial, value => updates.push({ index, value })]; },
    useEffect() {},
  },
  orgName: () => '测试学校', console, Date, Intl, Map, Set, Blob, URL, setTimeout,
});
for (const name of new Set(source.match(/\bIcon\w+\b/g))) context[name] = name;
for (const name of ['PageShell', 'Button', 'FilterBar', 'EmptyState', 'Tag', 'DataTable', 'MiniKPI', 'AvatarChip']) context[name] = name;
vm.runInContext(babel.transform(source, { presets: ['react'], filename: 'tasks-page.jsx' }).code, context);
const run = code => vm.runInContext(code, context);
const row = value => run(`mapBackendTaskRow(${JSON.stringify(value)})`);
const call = (name, ...values) => run(`${name}(${values.map(value => JSON.stringify(value)).join(',')})`);
const checks = [];
function check(name, test) {
  try { test(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message }); }
}
function render(rows = [], status = 'ready', filters = { status: '全部', type: '全部', time: '全部' }) {
  stateIndex = 0;
  states = [rows, { status, error: status === 'permission' ? '403' : '服务暂时不可用' }, 0, '', filters];
  return run('TasksPage()');
}
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children), ...nodes(tree.props?.actions), ...nodes(tree.props?.action), ...nodes(tree.props?.right)];
}
function textOf(tree) {
  if (Array.isArray(tree)) return tree.map(textOf).join(' ');
  if (tree == null || typeof tree === 'boolean') return '';
  if (typeof tree !== 'object') return String(tree);
  return textOf(tree.props?.children);
}

check('scheduled, published and successful solver are three different facts', () => {
  const scheduled = row({ id: 's', status: 'scheduled', latestJob: { status: 'succeeded' } });
  const published = row({ id: 'p', status: 'published', latestJob: { status: 'succeeded' } });
  assert.equal(scheduled.stage, 'scheduled');
  assert.equal(published.stage, 'published');
  assert.equal(scheduled.status, 'done', 'keep legacy status compatibility');
  assert.equal(call('taskStageInfo', scheduled).label, '已排班');
  assert.equal(call('taskStageInfo', published).label, '已发布');
  assert.equal(call('taskJobInfo', scheduled).label, '求解成功');
  assert.equal(call('taskStageInfo', row({ latestJob: { status: 'succeeded' } })).label, '阶段未提供');
});
check('failed job remains visible even if task stage is scheduled or published', () => {
  const failed = row({ id: 'f', status: 'published', latestJob: { status: 'failed', errorMessage: '教师不足' } });
  assert.equal(failed.stage, 'published');
  assert.equal(failed.jobStatus, 'failed');
  assert.equal(failed.jobError, '教师不足');
  assert.equal(call('taskNextAction', failed).label, '查看失败详情');
  assert.equal(call('taskNextAction', failed).route, 'task-detail');
});
check('absent values never become invented dates, progress, counts or owners', () => {
  const missing = row({ id: 'missing' });
  assert.equal(missing.progress, null);
  assert.equal(missing.exams, null);
  assert.equal(missing.supervisors, null);
  assert.equal(missing.updatedAt, '');
  assert.equal(call('formatTaskDateTime', missing.updatedAt), '未提供');
  assert.equal(call('taskStageInfo', row({ status: 'future_stage' })).label, '未知阶段（future_stage）');
});
check('timestamps are explicitly Beijing time including UTC date crossover', () => {
  assert.equal(call('formatTaskDateTime', '2026-09-05T17:05:00Z'), '2026/09/06 01:05');
  assert.equal(call('formatTaskDateTime', 'invalid'), '未提供');
  assert.equal(call('formatTaskDateTime', '2026-09-05T17:05:00'), '时区未提供');
});
check('stage filters separate scheduled and published and export only filtered rows', () => {
  const rows = [row({ id: 'a', name: '月考 A', status: 'scheduled' }), row({ id: 'b', name: '月考 B', status: 'published' })];
  const filtered = call('filterTaskRows', rows, '月考', { status: '已排班', type: '全部', time: '全部' });
  assert.equal(filtered.length, 1);
  assert.equal(filtered[0].id, 'a');
  const csv = call('taskRowsToCsv', filtered);
  assert.ok(csv.includes('月考 A') && !csv.includes('月考 B'));
  assert.ok(csv.includes('已排班') && !csv.includes('已完成'));
  assert.ok(csv.includes('业务阶段') && csv.includes('求解状态'));
  assert.equal(call('taskCsvCell', ' =1+1'), '"\' =1+1"');
});
check('semantic next actions use existing safe routes and explicit task selection', () => {
  const scheduled = row({ id: 'task-B', status: 'scheduled', stats: { assignments: 3 } });
  assert.equal(call('taskNextAction', scheduled).route, 'edit');
  assert.equal(call('taskNextAction', scheduled).label, '复核安排');
  const draft = row({ id: 'task-D', status: 'draft' });
  assert.equal(call('taskNextAction', draft).route, 'task-detail', 'quick cannot resume an existing task');
  assert.equal(call('taskNextAction', row({ id: 'task-P', status: 'published' })).route, 'task-detail');
  const tree = render([scheduled]);
  const action = nodes(tree).find(node => node.props?.['data-task-action'] === 'task-B');
  assert.ok(action, 'real row action exists');
  action.props.onClick();
  assert.equal(memory.get('jkxt:taskDetailId'), 'task-B');
  assert.equal(destination, 'edit');
});
check('task surface is compact, identifies three-table scope and preserves a long name', () => {
  const name = '高一年级秋季学期跨校区统一考试补考任务（包含所有专业班级和特殊安排）';
  const tree = render([row({ id: 'long', name, status: 'scheduled' })]);
  const all = nodes(tree);
  assert.ok(textOf(tree).includes(name));
  assert.ok(textOf(tree).includes('三表'));
  assert.ok(all.some(node => node.type === 'table'));
  assert.ok(!all.some(node => node.type === 'MiniKPI'), 'no completion KPI merging scheduled and published');
  const shell = all.find(node => node.type === 'PageShell');
  assert.equal(nodes(shell.props.actions).filter(node => textOf(node) === '导入并创建任务').length, 1);
  assert.ok(!nodes(shell.props.actions).some(node => textOf(node) === '导入'));
  assert.ok(!textOf(tree).includes('已完成'));
});
check('loading, permission and failure surfaces do not expose stale rows or export', () => {
  for (const status of ['loading', 'permission', 'error']) {
    const tree = render([row({ id: 'stale', name: '陈旧任务', status: 'published' })], status);
    assert.ok(!textOf(tree).includes('陈旧任务'));
    assert.ok(!nodes(tree).some(node => node.type === 'table'));
    assert.ok(nodes(tree).some(node => node.props?.['data-testid'] === (status === 'loading' ? 'tasks-loading' : 'tasks-load-error')));
  }
});
check('scoped CSS provides readable text, wrapping and narrow-screen layout', () => {
  const css = fs.readFileSync(path.join(root, 'src/styles/task-workflow-v2.css'), 'utf8');
  assert.match(css, /font-size:\s*14px/);
  assert.match(css, /font-size:\s*12px/);
  assert.match(css, /overflow-wrap:\s*anywhere/);
  assert.match(css, /@media\s*\(max-width:\s*\d+px\)/);
  assert.match(css, /focus-visible/);
  assert.doesNotMatch(css, /#[\da-f]{3,8}\b/i, 'use existing theme tokens, not a new palette');
});

try {
  const rows = [row({ id: 'csv-a', name: '=月考 A', status: 'scheduled', stats: { assignments: 0 } }), row({ id: 'csv-b', name: '月考 B', status: 'published' })];
  context.capturedDownload = null;
  run('taskDownloadBlob = (blob, filename) => { capturedDownload = { blob, filename }; }');
  const tree = render(rows, 'ready', { status: '已排班', type: '全部', time: '全部' });
  const exportButton = nodes(tree).find(node => node.type === 'Button' && textOf(node) === '导出当前清单');
  assert.ok(exportButton && !exportButton.props.disabled);
  exportButton.props.onClick();
  const download = context.capturedDownload;
  assert.equal(download.filename, '考务任务-当前筛选.csv');
  const csv = await download.blob.text();
  assert.ok(csv.includes('月考 A') && !csv.includes('月考 B'));
  assert.ok(csv.includes('"\'=月考 A"'));
  assert.ok(csv.includes('"0"'), 'zero assignments remains a real zero');
  checks.push({ name: 'actual export button serializes only the selected stage and neutralizes formulas', pass: true });
} catch (error) {
  checks.push({ name: 'actual export button serializes only the selected stage and neutralizes formulas', pass: false, error: error.message });
}

console.log(JSON.stringify({ contract: 'TaskWorkflow/v2', checks, passed: checks.filter(check => check.pass).length, failed: checks.filter(check => !check.pass).length }, null, 2));
if (checks.some(check => !check.pass)) process.exitCode = 1;
