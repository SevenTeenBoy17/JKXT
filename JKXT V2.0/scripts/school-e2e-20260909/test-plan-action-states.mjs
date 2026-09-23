import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(scriptPath), '../..');
const sourcePath = path.resolve(root, process.argv[2] || 'src/pages/exam-plans.jsx');
const source = fs.readFileSync(sourcePath, 'utf8');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const compiled = Babel.transform(source, { presets: ['react'] }).code;
const checks = [];
const startedAt = new Date().toISOString();
const sha = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const clone = (value) => JSON.parse(JSON.stringify(value));
const settle = () => new Promise((resolve) => setImmediate(resolve));
function nodes(tree) {
  if (!tree || typeof tree !== 'object') return [];
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return [tree, ...nodes(tree.props?.children), ...nodes(tree.props?.footer)];
}
function text(tree) {
  if (tree == null || typeof tree === 'boolean') return '';
  if (typeof tree !== 'object') return String(tree);
  if (Array.isArray(tree)) return tree.map(text).join('');
  return text(tree.props?.children);
}
const buttons = (tree, label) => nodes(tree).filter((node) => ['Button', 'button'].includes(node.type) && text(node) === label);
const button = (tree, label) => buttons(tree, label)[0];
function harness(component, initialStatus, overrides = {}) {
  const slots = [], calls = [], notices = [], changed = [];
  let cursor = 0, effects = [];
  const props = { planId: 'plan-42', revision: { id: 'revision-7', status: initialStatus, revisionNo: 2, version: 17, configuration: { mode: 'JOINT' } }, onChanged: async (value) => changed.push(value) };
  const api = {};
  for (const name of ['startPlanning', 'planningJob', 'submitApproval', 'validate', 'createRevision', 'replaceJointInput', 'jointInputSnapshot', 'publish']) {
    api[name] = async (...args) => {
      calls.push({ name, args: clone(args) });
      if (overrides[name]) return overrides[name](...args);
      if (name === 'jointInputSnapshot') return { items: [], revisionCursor: 17 };
      if (name === 'startPlanning' || name === 'planningJob') return { id: 'job-1', status: 'SUCCEEDED', diagnostics: [] };
      if (name === 'submitApproval') return { id: 'revision-7', status: 'PENDING_APPROVAL', version: 18 };
      if (name === 'createRevision') return { id: 'revision-8', status: 'DRAFT', version: 1 };
      return { revisionCursor: 18, acceptedCount: 1 };
    };
  }
  const context = vm.createContext({ console, document: { addEventListener() {}, removeEventListener() {} }, window: {
    api: { examPlans: api }, crypto: { randomUUID: () => 'action-test-key' },
    ui: { toast: (...args) => notices.push(clone(args)), confirm: async () => true },
    setTimeout: (resolve) => { setImmediate(resolve); },
  }});
  const same = (a, b) => a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  context.React = {
    Fragment: 'Fragment',
    createElement: (type, attrs, ...children) => ({ type, props: { ...attrs, children: children.flat(Infinity) } }),
    useState(initial) { const i = cursor++; if (!(i in slots)) slots[i] = typeof initial === 'function' ? initial() : initial; return [slots[i], (next) => { slots[i] = typeof next === 'function' ? next(slots[i]) : next; }]; },
    useRef(initial) { const i = cursor++; if (!(i in slots)) slots[i] = { current: initial }; return slots[i]; },
    useMemo(fn, deps) { const i = cursor++; if (!same(slots[i]?.deps, deps)) slots[i] = { deps, value: fn() }; return slots[i].value; },
    useCallback(fn, deps) { return this.useMemo(() => fn, deps); },
    useEffect(fn, deps) { const i = cursor++; if (!same(slots[i]?.deps, deps)) effects.push(() => { slots[i]?.cleanup?.(); slots[i] = { deps, cleanup: fn() }; }); },
  };
  for (const name of new Set([...source.matchAll(/<([A-Z]\w*)/g)].map((m) => m[1]))) context[name] = name;
  vm.runInContext(compiled, context);
  return { calls, notices, changed, props,
    render() { cursor = 0; const tree = context[component](props); const pending = effects; effects = []; pending.forEach((fn) => fn()); return tree; },
  };
}
async function check(id, fn) {
  try { await fn(); checks.push({ id, pass: true }); }
  catch (error) { checks.push({ id, pass: false, error: error.message.slice(0, 500) }); }
}

// Expectations derive from the server contract, not frontend constants.
const statuses = ['DRAFT', 'INVALID', 'READY', 'FAILED', 'INFEASIBLE', 'REVIEW_REQUIRED', 'PENDING_APPROVAL', 'APPROVED', 'SOLVING', 'VALIDATING', 'PUBLISHED', 'EXECUTING', 'CLOSED', 'ARCHIVED'];
for (const status of statuses) {
  await check(`lifecycle.${status}.action-matrix`, () => {
    const h = harness('PlanLifecycleActions', status), tree = h.render();
    assert.equal(Boolean(button(tree, '生成联合排程')), ['DRAFT', 'INVALID', 'READY', 'FAILED', 'INFEASIBLE'].includes(status), `${status}: generation availability`);
    assert.equal(Boolean(button(tree, '提交审批')), ['READY', 'REVIEW_REQUIRED'].includes(status), `${status}: submit availability`);
    assert.equal(Boolean(button(tree, '创建后继版本')), ['FAILED', 'INFEASIBLE', 'PUBLISHED', 'EXECUTING', 'CLOSED'].includes(status), `${status}: successor availability`);
    assert.equal(Boolean(button(tree, '发布此版本')), status === 'APPROVED', `${status}: publish availability`);
    assert.equal(h.calls.length, 0, 'render must not mutate the backend');
  });
  await check(`inputs.${status}.server-editability`, async () => {
    const h = harness('JointInputWorkspace', status); h.render(); await settle();
    const tree = h.render(), imports = buttons(tree, '导入替换');
    assert.equal(imports.length, 13);
    assert.ok(imports.every((item) => Boolean(item.props.disabled) === !['DRAFT', 'INVALID'].includes(status)), `${status}: all collection controls must match DRAFT/INVALID contract`);
    assert.ok(h.calls.every((item) => item.name === 'jointInputSnapshot'), 'loading must remain read-only');
  });
}
for (const status of ['DRAFT', 'INVALID', 'FAILED', 'INFEASIBLE']) {
  await check(`generate.${status}.real-handler`, async () => {
    const h = harness('PlanLifecycleActions', status), action = button(h.render(), '生成联合排程');
    assert.ok(action, 'generation control must be reachable');
    await action.props.onClick();
    assert.equal(h.calls.length, 1);
    assert.deepEqual(h.calls[0], { name: 'startPlanning', args: ['plan-42', 'revision-7', { mode: 'JOINT', seed: 20260831, failOnPartialCoverage: true, timeoutSeconds: 120 }, { headers: { 'Idempotency-Key': 'action-test-key' } }] });
    assert.equal(h.changed.length, 1);
    assert.match(text(h.render()), /考试时段、考场座位与监考岗位已完整生成/);
  });
}
for (const status of ['READY', 'REVIEW_REQUIRED']) {
  await check(`submit.${status}.real-handler`, async () => {
    const h = harness('PlanLifecycleActions', status), action = button(h.render(), '提交审批');
    assert.ok(action, 'submit control must be reachable'); await action.props.onClick();
    assert.deepEqual(h.calls, [{ name: 'submitApproval', args: ['plan-42', 'revision-7', { headers: { 'If-Match': '17' } }] }]);
    assert.equal(h.changed[0].status, 'PENDING_APPROVAL');
    assert.match(text(h.render()), /版本已提交审批/);
  });
}
for (const status of ['FAILED', 'INFEASIBLE']) {
  await check(`recover.${status}.new-draft-not-copy-promise`, async () => {
    const h = harness('PlanLifecycleActions', status), action = button(h.render(), '创建后继版本');
    assert.ok(action, 'new draft recovery must be reachable'); await action.props.onClick();
    assert.equal(h.calls[0].name, 'createRevision');
    assert.equal(h.calls[0].args[0], 'plan-42');
    assert.equal(h.calls[0].args[1].predecessorId, 'revision-7');
    assert.match(h.calls[0].args[1].changeReason, /失败|不可行|修复/);
    assert.equal(h.changed[0].status, 'DRAFT');
    assert.match(text(h.render()), /重新导入/);
  });
}
await check('generate.pending-and-error.no-false-success', async () => {
  let reject;
  const pending = new Promise((_, fail) => { reject = fail; });
  const h = harness('PlanLifecycleActions', 'DRAFT', { startPlanning: () => pending });
  const action = button(h.render(), '生成联合排程'); assert.ok(action);
  const operation = action.props.onClick();
  const pendingTree = h.render();
  assert.equal(button(pendingTree, '生成联合排程').props.disabled, true);
  assert.ok(!text(pendingTree).includes('已完整生成')); assert.equal(h.changed.length, 0);
  reject(Object.assign(new Error('权限不足，未执行求解'), { status: 403 })); await operation;
  assert.match(text(h.render()), /权限不足/); assert.equal(h.changed.length, 0);
  assert.ok(!h.notices.some((row) => row[1]?.kind === 'success'));
});
await check('submit.server-rejection.no-false-success', async () => {
  const h = harness('PlanLifecycleActions', 'READY', { submitApproval: async () => { throw Object.assign(new Error('版本游标过期'), { status: 409 }); } });
  const action = button(h.render(), '提交审批'); assert.ok(action); await action.props.onClick();
  assert.match(text(h.render()), /版本游标过期/); assert.equal(h.changed.length, 0);
  assert.ok(!h.notices.some((row) => row[1]?.kind === 'success'));
});
await check('input.dialog-state-change.blocks-stale-write', async () => {
  const h = harness('JointInputWorkspace', 'DRAFT'); h.render(); await settle();
  buttons(h.render(), '导入替换')[0].props.onClick();
  const input = nodes(h.render()).find((node) => node.props['aria-label'] === '导入原始内容');
  input.props.onChange({ target: { value: '[{"id":"test-demand"}]' } });
  h.props.revision.status = 'FAILED';
  const tree = h.render(), action = button(tree, '确认替换 1 条记录');
  assert.ok(action); assert.equal(action.props.disabled, true);
  await action.props.onClick();
  assert.ok(!h.calls.some((row) => row.name === 'replaceJointInput'));
});

const failed = checks.filter((row) => !row.pass);
const report = { contract: 'ExamPlanActionStateComponentRegression/v1', startedAt, finishedAt: new Date().toISOString(), sourcePath: path.relative(root, sourcePath).replaceAll('\\', '/'), sourceSha256: sha(sourcePath), testSha256: sha(scriptPath), boundary: 'VM renders actual JSX and invokes callbacks with isolated API doubles; no browser, network, database or live-instance execution', summary: { total: checks.length, passed: checks.length - failed.length, failed: failed.length }, checks };
const out = path.join(root, 'artifacts/school-e2e-20260909/ui-fixes', startedAt.replaceAll(':', '-'));
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'results.json'), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({ ...report, artifact: path.relative(root, path.join(out, 'results.json')).replaceAll('\\', '/') }, null, 2));
if (failed.length) process.exitCode = 1;
