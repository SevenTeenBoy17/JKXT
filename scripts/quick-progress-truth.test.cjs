'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const app = process.env.JKXT_TEST_APP_ROOT || path.resolve(__dirname, '..');
const Babel = require(path.join(app, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const React = require(path.join(app, 'node_modules/react'));
const { renderToStaticMarkup } = require(path.join(app, 'node_modules/react-dom/server'));
const compile = name => Babel.transform(fs.readFileSync(path.join(app, 'src', name), 'utf8'), { presets: ['react'] }).code;
const mainSource = compile('pages/quick.jsx');
const viewContext = vm.createContext({ React, window: {}, setInterval, clearInterval });
for (const name of ['icons.jsx', 'pages/quick-step1.jsx', 'pages/quick-step3.jsx']) vm.runInContext(compile(name), viewContext);
const Step3 = vm.runInContext('QkStep3', viewContext);
const renderProgress = props => renderToStaticMarkup(React.createElement(Step3, {
  running: true, stage: 0, logs: [], strategies: [], backendActive: true, ...props,
})).replace(/<style>[\s\S]*?<\/style>/g, '').replace(/<[^>]*>/g, '');
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const validation = {
  severity: 'pass', overview: { teachers: 19, rooms: 3, exams: 2 },
  cats: {}, items: [], counts: { pass: 1, suggest: 0, warn: 0, error: 0 },
};
const started = { runId: 'run-1', taskId: 'task-1', status: 'pending', stage: 'queued', progress: 0,
  eventsPath: '/api/v1/schedule-jobs/run-1/events' };
const snapshot = (patch = {}) => ({
  runId: 'run-1', taskId: 'task-1', status: 'succeeded', stage: '生成结果预览', progress: 100,
  solver: 'ortools-cpsat', seed: 1, errorCode: null, errorMessage: null,
  stats: { inputSource: 'task-import', teachers: 16, rooms: 2, exams: 4,
    policySource: 'legacy-defaults', policySnapshot: { avoidHomeroomClass: true, sameGrade: 'SOFT', avoidSubjectClass: false } },
  logs: [{ runId: 'run-1', status: 'succeeded', stage: '生成结果预览', progress: 100,
    message: '已写入排考 assignments。', at: '2026-10-21T09:01:03Z' }],
  assignments: [], conflictSet: [],
  fairness: [{ id: 'load', label: '工作量', value: '0', status: 'good', detail: '测试服务快照' },
    { id: 'coverage', label: '覆盖率', value: '100%', status: 'good', detail: '测试服务快照' }],
  ...patch,
});

// Real page handlers/effects with a deterministic hook scheduler and controlled API transport.
// The progress child is rendered by real React; this does not claim browser, DB or live API coverage.
function pageFixture(t, { noSse = false } = {}) {
  const slots = [], effects = [], timers = new Map(), events = new Map(), toasts = [];
  const start = deferred(), final = deferred();
  let cursor = 0, dirty = false, tree, timerId = 0;
  const sameDeps = (a, b) => a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const hooks = {
    Fragment: 'fragment',
    createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
    useState(initial) {
      const i = cursor++;
      if (!slots[i]) slots[i] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[i].value, value => {
        slots[i].value = typeof value === 'function' ? value(slots[i].value) : value; dirty = true;
      }];
    },
    useRef(value) { return slots[cursor++] ||= { current: value }; },
    useEffect(callback, deps) {
      const i = cursor++;
      if (!slots[i] || !sameDeps(slots[i].deps, deps)) {
        const old = slots[i]; slots[i] = { deps, cleanup: old?.cleanup };
        effects.push(() => { slots[i].cleanup?.(); slots[i].cleanup = callback(); });
      }
    },
  };
  const window = {
    addEventListener() {}, removeEventListener() {}, scrollTo() {},
    ui: { toast: (message, options) => toasts.push({ message, options }) },
    api: {
      post(route) {
        if (route === '/exam-tasks') return Promise.resolve({ id: 'task-1' });
        if (route === '/exam-tasks/task-1/imports/validate') return Promise.resolve({ report: validation });
        assert.equal(route, '/exam-tasks/task-1/schedule-jobs'); return start.promise;
      },
      upload(route, file, options) {
        assert.equal(route, '/exam-tasks/task-1/uploads');
        assert.ok(['teacher', 'classroom', 'exam'].includes(options.kind));
        return Promise.resolve({ report: validation, originalName: file.name, sizeBytes: file.size });
      },
      get(route) { assert.equal(route, '/schedule-jobs/run-1'); return final.promise; },
      eventSource(route) {
        assert.equal(route, '/schedule-jobs/run-1/events');
        if (noSse) throw new Error('SSE unavailable');
        return { addEventListener: (name, cb) => events.set(name, cb), close() {} };
      },
    },
  };
  const context = vm.createContext({ React: hooks, window,
    setTimeout: (cb, delay) => { const id = ++timerId; timers.set(id, { cb, delay }); return id; },
    clearTimeout: id => timers.delete(id),
    sessionStorage: { getItem: () => null, removeItem() {} }, localStorage: { setItem() {} },
    orgName: () => '测试学校', PageShell: 'PageShell', Button: 'Button',
    QkStep1: 'QkStep1', QkStep2: 'QkStep2', QkStep3: 'QkStep3', QkStep4: 'QkStep4',
    IconHelp() {}, IconSparkle() {}, IconList() {}, IconBuilding() {}, IconCalendar() {},
  });
  vm.runInContext(mainSource, context);
  const nodes = node => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const component = name => nodes(tree).find(node => node.type === name)?.props;
  function render() {
    cursor = 0; dirty = false; tree = vm.runInContext('QuickSchedulePage()', context);
    while (effects.length) effects.shift()();
  }
  async function flush() {
    for (let i = 0; i < 30; i++) { await Promise.resolve(); if (dirty) render(); }
  }
  t.after(() => { for (const slot of slots) slot?.cleanup?.(); timers.clear(); });
  render();
  return {
    start, final, toasts, component, flush,
    text: () => { assert.ok(component('QkStep3'), 'progress component must be visible'); return renderProgress(component('QkStep3')); },
    async begin() {
      for (const slot of ['t01', 't02', 't03']) {
        await component('QkStep1').onStartUpload(slot, { name: slot + '.xlsx', size: 100 }); await flush();
      }
      await component('QkStep1').onNext(); await flush();
      const pending = component('QkStep2').onNext(); await flush(); return { pending };
    },
    async event(patch) {
      assert.ok(events.get('progress'), 'real page must subscribe to progress events');
      events.get('progress')({ data: JSON.stringify({ runId: 'run-1', at: '2026-10-21T09:01:02Z', ...patch }) });
      await flush();
    },
  };
}

test('a pending real start is never labelled simulation or a fabricated percentage/persisted job', async t => {
  const page = pageFixture(t); await page.begin();
  const text = page.text();
  assert.doesNotMatch(text, /模拟|8%|已入库|数据库持久化|已写入 schedule_run/);
  assert.match(text, /等待.*(?:响应|返回)|正在提交/);
});

test('the server queued response renders exact zero progress and queue phase', async t => {
  const page = pageFixture(t); await page.begin(); page.start.resolve(started); await page.flush();
  const text = page.text();
  assert.match(text, /0%/);
  assert.doesNotMatch(text, /8%|教师 142|考场 24|考试 8|5 条策略|5 项公平性/);
  assert.match(text, /排队|等待求解器/);
});

test('SSE progress uses server percentage and phase, preserves each received log, and waits for final GET', async t => {
  const page = pageFixture(t); const { pending } = await page.begin();
  page.start.resolve(started); await page.flush();
  await page.event({ status: 'running', stage: '读取排班数据', progress: 15, message: '已读取本次教师、考场与考试数据。' });
  await page.event({ status: 'running', stage: '构建约束模型', progress: 35, message: '正在构建 CP-SAT 决策变量与硬约束。' });
  const text = page.text();
  assert.match(text, /35%/); assert.match(text, /构建约束模型/);
  assert.match(text, /已读取本次教师、考场与考试数据。/);
  assert.match(text, /正在构建 CP-SAT 决策变量与硬约束。/);
  assert.doesNotMatch(text, /142|24 间|5 条策略|5 项公平性|未检出违规项/);
  await page.event({ status: 'succeeded', stage: '生成结果预览', progress: 100, message: '已写入排考 assignments。' });
  assert.equal(page.component('QkStep4'), undefined, 'SSE success alone cannot expose a result');
  assert.doesNotMatch(page.text(), /后端方案已生成/);
  page.final.resolve(snapshot()); await pending; await page.flush();
  assert.ok(page.component('QkStep4'), 'a confirmed matching-task snapshot exposes the result');
});

test('completed run displays solver input counts and returned metric/policy data, not upload totals or fixed rules', async t => {
  const page = pageFixture(t); const { pending } = await page.begin();
  page.start.resolve(started); await page.flush();
  await page.event({ status: 'succeeded', stage: '生成结果预览', progress: 100, message: '已写入排考 assignments。' });
  page.final.resolve(snapshot()); await pending; await page.flush();
  const text = page.text();
  assert.match(text, /教师 16 人.*考场 2 间.*考试场次 4 场/);
  assert.doesNotMatch(text, /教师 19|142|24 间|5 条策略|5 项公平性|男女混合监考|科目错峰/);
  assert.match(text, /2 项公平性指标/);
  assert.match(text, /同年级回避尽量满足/);
  assert.match(text, /任课教师避监本班未启用/);
  assert.match(text, /后端方案已生成/);
});

for (const progress of [undefined, null, NaN, -1, 101, '35', '', false]) {
  test(`invalid or missing server progress (${String(progress)}) remains unknown`, () => {
    const text = renderProgress({ backendJob: snapshot({ status: 'running', stage: 'unrecognised', progress }), stage: 3 });
    assert.doesNotMatch(text, /\d+(?:\.\d+)?%|后端方案已生成/);
    assert.match(text, /待.*返回|暂无.*进度|尚未.*进度/);
  });
}

test('unrecognised server stage cannot manufacture successful checks or run a guessed phase', () => {
  const text = renderProgress({ stage: 5, backendJob: snapshot({ status: 'running', stage: '自定义后端阶段', progress: 37.5 }) });
  assert.match(text, /37\.5%/); assert.match(text, /自定义后端阶段/);
  assert.doesNotMatch(text, /未检出违规项|assignments 已写入数据库|运行中·0\.0s/);
});

for (const status of ['failed', 'infeasible', 'cancelled', 'unknown']) {
  test(`100 percent with ${status} does not claim generated or completed result`, () => {
    const text = renderProgress({ running: false, stage: 6, backendJob: snapshot({ status }) });
    assert.doesNotMatch(text, /后端方案已生成|后端完成|查看后端结果|assignments 已写入数据库/);
    assert.doesNotMatch(text, /完成服务端已进入后续阶段/, 'Unconfirmed states must not mark guessed earlier stages complete');
  });
}

test('an absent snapshot never substitutes example statistics or confirmed strategy counts', () => {
  const text = renderProgress({ running: false, stage: 6, backendJob: null,
    strategies: [{ id: 'example', label: '示例策略不应生效', desc: '严格回避' }] });
  assert.doesNotMatch(text, /142|24 间|8 场|5 条策略|5 项公平性|当前生效策略|示例策略不应生效|后端方案已生成/);
  assert.match(text, /尚未.*(?:快照|策略)|等待.*(?:快照|策略)/);
});

test('explicit local preview remains visibly simulated', () => {
  const text = renderProgress({ backendActive: false, stage: 0, running: true });
  assert.match(text, /模拟/); assert.match(text, /8%/);
  assert.doesNotMatch(text, /后端方案已生成|后端 Job v1/);
});

test('polling fallback consumes complete snapshot and verifies the matching task before completion', async t => {
  const page = pageFixture(t, { noSse: true }); const { pending } = await page.begin();
  page.start.resolve(started); await page.flush();
  page.final.resolve(snapshot({ taskId: 'another-task' })); await pending; await page.flush();
  assert.equal(page.component('QkStep4'), undefined);
  assert.ok(page.toasts.some(item => /任务编号与当前请求不一致/.test(item.options.desc)));
});

test('a failed final snapshot cannot expose a result after an SSE terminal event', async t => {
  const page = pageFixture(t); const { pending } = await page.begin();
  page.start.resolve(started); await page.flush();
  await page.event({ status: 'failed', stage: 'WORKER_LOST', progress: 100, message: '执行器租约已失效' });
  page.final.resolve(snapshot({ status: 'failed', stage: 'WORKER_LOST', errorCode: 'WORKER_LOST', errorMessage: '执行器租约已失效' }));
  await pending; await page.flush();
  assert.equal(page.component('QkStep4'), undefined);
  assert.ok(page.toasts.some(item => item.options.desc === '执行器租约已失效'));
});

test('zero input counts and an empty fairness array remain zero, with disabled rules explicitly identified', () => {
  const text = renderProgress({ running: false, stage: 6, backendJob: snapshot({
    stats: { teachers: 0, rooms: 0, exams: 0,
      policySnapshot: { avoidHomeroomClass: false, sameGrade: 'NONE', avoidSubjectClass: true } }, fairness: [],
  }) });
  assert.match(text, /教师 0 人.*考场 0 间.*考试场次 0 场/);
  assert.match(text, /0 项公平性指标/);
  assert.match(text, /班主任避监本班未启用/);
  assert.match(text, /同年级回避未启用/);
  assert.match(text, /任课教师避监本班严格回避/);
});

test('an unknown policy enum is not interpreted as an active rule', () => {
  const text = renderProgress({ running: false, stage: 6, backendJob: snapshot({
    stats: { policySnapshot: { sameGrade: 'toString' } },
  }) });
  assert.doesNotMatch(text, /同年级回避/);
  assert.match(text, /尚未收到本次策略快照/);
});
