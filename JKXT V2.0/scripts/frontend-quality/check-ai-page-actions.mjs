import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/ai-policy.jsx'), 'utf8');
const compiled = Babel.transform(source, { presets: ['react'] }).code;
const checks = [];
const copy = value => JSON.parse(JSON.stringify(value));
const settle = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function check(name, run) {
  try { await run(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 500) }); }
}
function nodes(tree) {
  if (!tree || typeof tree !== 'object') return [];
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return [tree, ...nodes(tree.props?.children), ...nodes(tree.props?.actions)];
}
function textOf(tree) {
  if (typeof tree === 'string' || typeof tree === 'number') return String(tree);
  if (Array.isArray(tree)) return tree.map(textOf).join('');
  return tree && typeof tree === 'object' ? textOf(tree.props?.children) : '';
}

// The actual JSX functions and event closures execute here. Workflow/network dependencies are
// controlled test doubles; this is a UI regression suite, not a provider or server integration test.
function harness(options = {}) {
  const providers = { providers: [{ id: 'aliyun', name: '测试模型服务', active: true, configured: true, models: ['policy-model'] }], catalog: [{ id: 'policy-model', policyCompatible: true, capability: 'text', status: 'not_tested' }], usage: { monthCallCount: 0, successCount: 0, failureCount: 0 }, recentCalls: [] };
  const task = { id: 'task-review', name: '合成考试', status: 'ready', uploads: [] };
  const prepared = { taskId: task.id, taskName: task.name, fingerprint: '[]', parse: { policyId: 'policy-review', model: 'policy-model', provider: 'aliyun', status: 'succeeded', fallback: false, dsl: { hard: [], soft: [] } } };
  const handoff = { taskId: task.id, jobId: 'job-review', source: 'ai_policy' };
  const calls = [], writes = [], navigations = [];
  const slots = []; let cursor = 0, effects = [], mounted = true, staleStateWrites = 0;
  const same = (a, b) => Boolean(a && b && a.length === b.length && a.every((value, index) => Object.is(value, b[index])));
  const React = {
    Fragment: 'Fragment',
    createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }),
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, next => { if (!mounted) staleStateWrites += 1; slots[index].value = typeof next === 'function' ? next(slots[index].value) : next; }];
    },
    useRef(initial) { const index = cursor++; if (!(index in slots)) slots[index] = { value: { current: initial } }; return slots[index].value; },
    useEffect(fn, deps) {
      const index = cursor++;
      if (!same(slots[index]?.deps, deps)) effects.push(() => { slots[index]?.cleanup?.(); slots[index] = { deps, cleanup: fn() }; });
    },
  };
  const api = {
    get: async route => { calls.push(['GET', route]); return options.get ? options.get(route, providers, task) : route === '/ai/providers' ? copy(providers) : [copy(task)]; },
    post: async (route, body) => { calls.push(['POST', route, copy(body)]); return options.refine ? options.refine(body) : { status: 'succeeded', fallback: false, refinedText: '真实优化文本', model: 'policy-model' }; },
  };
  const workflow = {
    mutable: item => item && !item.publishedAt && ['draft', 'importing', 'ready'].includes(item.status),
    realResponse: result => { if (!result || result.fallback || result.status !== 'succeeded') throw new Error('AI 未返回真实成功结果'); return result; },
    prepare: async (input, passedApi) => { calls.push(['prepare', copy(input)]); assert.equal(passedApi, api); return options.prepare ? options.prepare(input) : copy(prepared); },
    submit: async (input, passedApi) => { calls.push(['submit', copy(input)]); assert.equal(passedApi, api); return options.submit ? options.submit(input) : copy(handoff); },
  };
  const context = vm.createContext({ React, console, Date, window: { api, JKXTAiWorkflow: workflow, navigateTo: route => navigations.push(route) }, orgName: () => '合成学校',
    PageShell: 'PageShell', Button: 'Button', SectionTitle: 'SectionTitle', Tag: 'Tag', IconSparkle: 'IconSparkle',
    sessionStorage: { setItem: (key, value) => writes.push(['session', key, value]) }, localStorage: { setItem: (key, value) => writes.push(['local', key, value]) },
  });
  vm.runInContext(compiled, context);
  const render = () => { cursor = 0; const tree = vm.runInContext('AIPolicyPage()', context); const pending = effects; effects = []; pending.forEach(effect => effect()); return tree; };
  const unmount = () => { slots.forEach(slot => slot.cleanup?.()); mounted = false; };
  const find = (tree, predicate) => { const item = nodes(tree).find(predicate); assert.ok(item, 'expected JSX node exists'); return item; };
  const button = (tree, label) => find(tree, node => node.type === 'Button' && textOf(node) === label);
  const natural = tree => find(tree, node => typeof node.type === 'function' && node.type.name === 'NaturalLanguageInput');
  const selectTask = () => { const tree = render(); find(tree, node => node.props.id === 'ai-task').props.onChange({ target: { value: task.id } }); return render(); };
  return { render, unmount, find, button, natural, selectTask, calls, writes, navigations, providers, task, prepared, handoff, context, staleWrites: () => staleStateWrites };
}

await check('actual JSX forwards selected task, text and pinned model without starting before confirmation', async () => {
  const h = harness(); h.render(); await settle();
  let tree = h.selectTask();
  h.natural(tree).props.onTextChange('均衡工作量');
  tree = h.render();
  h.find(tree, node => node.props.id === 'ai-model').props.onChange({ target: { value: 'policy-model' } });
  tree = h.render();
  await h.button(tree, '解析策略').props.onClick();
  assert.deepEqual(h.calls.find(call => call[0] === 'prepare')[1], { taskId: h.task.id, text: '均衡工作量', policyIds: ['homeroom_avoid', 'same_grade_avoid', 'subject_off', 'workload_balance'], provider: 'aliyun', model: 'policy-model' });
  assert.equal(h.calls.some(call => call[0] === 'submit'), false);
  await h.button(h.render(), '已复核，启动真实求解').props.onClick();
  assert.equal(h.calls.find(call => call[0] === 'submit')[1].parse.policyId, 'policy-review');
  assert.equal(h.writes.length, 2); assert.deepEqual(h.navigations, ['quick']);
  h.unmount();
});

await check('delayed refine after unmount neither updates state nor writes shared handoff', async () => {
  const waiting = deferred(), h = harness({ refine: () => waiting.promise }); h.render(); await settle();
  h.natural(h.render()).props.onTextChange('保留原文');
  const pending = h.natural(h.render()).props.onRefine(); h.unmount();
  waiting.resolve({ status: 'succeeded', fallback: false, refinedText: '迟到的优化' }); await pending;
  assert.equal(h.staleWrites(), 0); assert.equal(h.writes.length, 0); assert.equal(h.navigations.length, 0);
});

await check('delayed prepare after unmount cannot revive a stale prepared policy', async () => {
  const waiting = deferred(), h = harness({ prepare: () => waiting.promise }); h.render(); await settle();
  const pending = h.button(h.selectTask(), '解析策略').props.onClick(); h.unmount();
  waiting.resolve(h.prepared); await pending;
  assert.equal(h.staleWrites(), 0); assert.equal(h.writes.length, 0); assert.equal(h.navigations.length, 0);
});

await check('delayed submitted job after unmount does not navigate away from the new page', async () => {
  const waiting = deferred(), h = harness({ submit: () => waiting.promise }); h.render(); await settle();
  await h.button(h.selectTask(), '解析策略').props.onClick();
  const pending = h.button(h.render(), '已复核，启动真实求解').props.onClick(); h.unmount();
  waiting.resolve(h.handoff); await pending;
  assert.equal(h.calls.filter(call => call[0] === 'submit').length, 1);
  assert.equal(h.staleWrites(), 0); assert.equal(h.writes.length, 0); assert.equal(h.navigations.length, 0);
});

await check('late rejection after unmount stays handled without stale error state', async () => {
  const waiting = deferred(), h = harness({ prepare: () => waiting.promise }); h.render(); await settle();
  const pending = h.button(h.selectTask(), '解析策略').props.onClick(); h.unmount();
  waiting.reject(new Error('synthetic-late-failure')); await pending;
  assert.equal(h.staleWrites(), 0); assert.equal(h.navigations.length, 0);
});

await check('malformed provider payload becomes a retryable error instead of a render crash', async () => {
  const malformed = [null, { providers: {} }, { providers: [null] }, { providers: [], catalog: {} }, { providers: [], recentCalls: [null] }, { providers: [], usage: { monthCallCount: {} } }];
  for (const response of malformed) {
    const h = harness({ get: route => route === '/ai/providers' ? response : [] }); h.render(); await settle();
    const tree = h.render();
    assert.ok(textOf(tree).includes('模型配置响应格式异常'));
    assert.ok(h.button(tree, '重试'));
    assert.equal(h.button(tree, '解析策略').props.disabled, true);
    assert.equal(h.calls.some(call => call[0] === 'prepare' || call[0] === 'submit'), false);
    h.unmount();
  }
});

await check('real refinement stays a review candidate until explicit acceptance', async () => {
  const h = harness(); h.render(); await settle();
  h.natural(h.render()).props.onTextChange('原文软约束');
  await h.natural(h.render()).props.onRefine();
  let tree = h.render();
  assert.equal(h.natural(tree).props.text, '原文软约束');
  assert.equal(h.natural(tree).props.refined.refinedText, '真实优化文本');
  h.natural(tree).props.onAcceptRefined(); tree = h.render();
  assert.equal(h.natural(tree).props.text, '真实优化文本');
  assert.equal(h.natural(tree).props.refined, null);
  assert.equal(h.calls.some(call=>call[0]==='submit'), false);
  h.unmount();
});
await check('refine over 500 characters preserves original text and exposes failure', async () => {
  const h = harness({ refine: async () => ({ status: 'succeeded', fallback: false, refinedText: '长'.repeat(501) }) }); h.render(); await settle();
  h.natural(h.render()).props.onTextChange('不可丢失的原文');
  await h.natural(h.render()).props.onRefine();
  const tree = h.render();
  assert.equal(h.natural(tree).props.text, '不可丢失的原文');
  assert.ok(textOf(tree).includes('超过本页 500 字上限'));
  assert.equal(h.natural(tree).props.refined, null);
  h.unmount();
});

await check('fallback refine and rejected submit never report fake success or navigate', async () => {
  const h = harness({ refine: async () => ({ status: 'succeeded', fallback: true, refinedText: '模拟内容' }), submit: async () => { throw new Error('server-policy-rejected'); } }); h.render(); await settle();
  h.natural(h.render()).props.onTextChange('真实要求');
  await h.natural(h.render()).props.onRefine();
  assert.equal(h.natural(h.render()).props.text, '真实要求');
  assert.ok(textOf(h.render()).includes('AI 未返回真实成功结果'));
  await h.button(h.selectTask(), '解析策略').props.onClick();
  await h.button(h.render(), '已复核，启动真实求解').props.onClick();
  assert.ok(textOf(h.render()).includes('server-policy-rejected'));
  assert.equal(h.writes.length, 0); assert.equal(h.navigations.length, 0);
  h.unmount();
});

await check('actual action lock prevents duplicate parse and examples respect the text limit', async () => {
  const waiting = deferred(), h = harness({ prepare: () => waiting.promise }); h.render(); await settle();
  h.natural(h.render()).props.onTextChange('字'.repeat(700));
  assert.equal(h.natural(h.render()).props.text.length, 500);
  const button = h.button(h.selectTask(), '解析策略');
  const first = button.props.onClick(); const second = button.props.onClick();
  assert.equal(h.calls.filter(call => call[0] === 'prepare').length, 1);
  waiting.resolve(h.prepared); await Promise.all([first, second]); h.unmount();
});

await check('same-grade preference and always-on balance accurately describe solver behavior', () => {
  const h = harness();
  const policies = vm.runInContext('K12_POLICIES', h.context);
  assert.match(policies.find(policy => policy.id === 'same_grade_avoid').desc, /软偏好/);
  assert.match(policies.find(policy => policy.id === 'same_grade_avoid').desc, /不能承诺/);
  assert.equal(policies.find(policy => policy.id === 'workload_balance').fixed, true);
  assert.doesNotMatch(source, /本月共 142|sample: 'true'|defaultValue=\{n\.v\}/);
  h.unmount();
});

console.log(JSON.stringify({ contract: 'AiPageActions/v1', checks, passed: checks.filter(item => item.pass).length, total: checks.length }, null, 2));
if (checks.some(item => !item.pass)) process.exitCode = 1;
