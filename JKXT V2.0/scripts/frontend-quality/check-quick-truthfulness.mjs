import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const sources = ['quick-step1.jsx', 'quick.jsx'].map(file => fs.readFileSync(path.join(root, 'src/pages', file), 'utf8'));
const compiled = sources.map(source => babel.transform(source, { presets: ['react'] }).code);
const nodes = value => !value || typeof value !== 'object' ? [] : Array.isArray(value)
  ? value.flatMap(nodes) : [value, ...nodes(value.props?.children), ...nodes(value.props?.actions)];
const textOf = value => typeof value === 'string' || typeof value === 'number' ? String(value)
  : Array.isArray(value) ? value.map(textOf).join('') : value && typeof value === 'object' ? textOf(value.props?.children) : '';
const report = { severity: 'pass', overview: { completeness: 100, teachers: 17, rooms: 4, exams: 2, supervisions: 8 }, counts: { pass: 6, suggest: 0, warn: 0, error: 0 }, cats: {}, items: [] };
const demoOrg = '00000000-0000-0000-0000-000000000001';
const realFile = { name: '本校教师.xlsx', size: 3072 };

function harness({ api = {}, orgId = 'real-school', parser, handoff } = {}) {
  const hooks = [], timers = [], toasts = [], calls = [], effects = [], cleanups = [];
  const listeners = new Map();
  const saved = new Map(), session = new Map(handoff ? [['jkxt:quickHandoff', JSON.stringify(handoff)]] : []);
  let cursor = 0;
  const sandbox = vm.createContext({ console, Date, Intl, Map, Set, Blob, URL,
    setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout() {},
    localStorage: { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, String(value)) },
    sessionStorage: { getItem: key => session.get(key) ?? null, setItem: (key, value) => session.set(key, String(value)), removeItem: key => session.delete(key) },
    React: {
      Fragment: Symbol.for('react.fragment'),
      createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
      useState(initial) { const i = cursor++; if (!(i in hooks)) hooks[i] = typeof initial === 'function' ? initial() : initial;
        return [hooks[i], value => { hooks[i] = typeof value === 'function' ? value(hooks[i]) : value; }]; },
      useRef(initial) { const i = cursor++; if (!(i in hooks)) hooks[i] = { current: initial }; return hooks[i]; },
      useEffect(effect) { effects.push(effect); },
    },
    window: { __jkxtMe: { org: { id: orgId, slug: orgId === demoOrg ? 'demo' : 'school' } },
      ui: { toast: (...args) => toasts.push(args) }, JKXT: parser,
      addEventListener(name, listener) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(listener); },
      removeEventListener(name, listener) { listeners.get(name)?.delete(listener); }, scrollTo() {},
    },
    orgName: () => '测试学校',
  });
  if (api !== null) sandbox.window.api = {
    post: async (url, body) => { calls.push({ method: 'post', url, body }); return api.post ? api.post(url, body) : url === '/exam-tasks' ? { id: 'task-real' } : { report }; },
    upload: async (url, file, fields) => { calls.push({ method: 'upload', url, file, fields }); return api.upload ? api.upload(url, file, fields) : { originalName: file?.name || '演示.xlsx', sizeBytes: 3072, report }; },
  };
  for (const name of new Set(sources.join('\n').match(/\bIcon\w+\b/g))) sandbox[name] = name;
  for (const name of ['PageShell', 'Button', 'Tag', 'ProductGlyph', 'QkStep2', 'QkStep3', 'QkStep4']) sandbox[name] = name;
  compiled.forEach(code => vm.runInContext(code, sandbox));
  const run = code => vm.runInContext(code, sandbox);
  const render = () => { cursor = 0; return run('QuickSchedulePage()'); };
  const step1 = () => nodes(render()).find(node => node.type === sandbox.window.QkStep1);
  const tick = async () => { await new Promise(resolve => setImmediate(resolve)); while (timers.length) timers.shift()(); await new Promise(resolve => setImmediate(resolve)); };
  return { sandbox, calls, toasts, saved, render, step1, tick, run,
    mountEffects() { effects.splice(0).forEach(effect => { const cleanup = effect(); if (typeof cleanup === 'function') cleanups.push(cleanup); }); },
    unmountEffects() { cleanups.splice(0).forEach(cleanup => cleanup()); },
    emit(name) { listeners.get(name)?.forEach(listener => listener({})); },
    find(type) { return nodes(render()).find(node => node.type === type); },
    async uploadAll() { for (const key of ['t01', 't02', 't03']) await step1().props.onStartUpload(key, realFile); await tick(); },
  };
}

const checks = [];
async function check(name, fn) {
  try { await fn(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 600) }); }
}
const noSuccess = h => assert.equal(h.toasts.some(([, options]) => options?.kind === 'success'), false);
const deferred = () => { let resolve, reject; const promise = new Promise((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; };
const completedJob = { taskId: 'task-real', status: 'succeeded', progress: 100, assignments: [] };
const completeBackendJob = (h, snapshot) => {
  h.sandbox.window.api.get = async () => snapshot;
  h.sandbox.window.api.eventSource = () => ({
    addEventListener(name, listener) {
      if (name === 'progress') Promise.resolve().then(() => listener({ data: JSON.stringify({ status: 'succeeded', progress: 100 }) }));
    },
    close() {},
  });
};
const runHandoff = async (snapshot, validationReport) => {
  const h = harness({ handoff: { taskId: 'task-real', jobId: 'job-real', validationReport } });
  completeBackendJob(h, snapshot); h.render(); h.mountEffects(); await h.tick(); return h;
};
const runSchedule = async snapshot => {
  const h = harness({ api: { post: async url => url === '/exam-tasks' ? { id: 'task-real' }
    : url.endsWith('/schedule-jobs') ? { jobId: 'job-real' } : { report } } });
  completeBackendJob(h, snapshot); await h.uploadAll(); await h.step1().props.onNext();
  await h.find('QkStep2').props.onNext(); await h.tick(); return h;
};

await check('current semester uses the authenticated school term and never a fixed year', async () => {
  const h = harness(); h.sandbox.window.__jkxtMe.term = { name: '2026–2027 学年第一学期', yearLabel: '2026–2027', term: 'S1' };
  assert.match(textOf(h.render()), /2026–2027 学年第一学期/); assert.doesNotMatch(textOf(h.render()), /2025 秋季学期/);
});
await check('missing or unrecognized semester stays explicitly unknown', async () => {
  const h = harness(); assert.match(textOf(h.render()), /当前学期未提供/);
  const other = harness(); other.sandbox.window.__jkxtMe.term = { yearLabel: '2028–2029', term: 'UNKNOWN' };
  assert.match(textOf(other.render()), /当前学期未提供/); assert.doesNotMatch(textOf(other.render()), /第二学期/);
});
await check('semester follows the existing term and profile change events', async () => {
  const h = harness(); h.render(); h.mountEffects();
  h.sandbox.window.__jkxtMe.term = { yearLabel: '2028–2029', term: 'S2' }; h.emit('jkxt:term-changed');
  assert.match(textOf(h.render()), /2028–2029 · 第二学期/);
  h.sandbox.window.__jkxtMe = { org: { id: 'another-school' }, term: null }; h.emit('jkxt:me-changed');
  assert.match(textOf(h.render()), /当前学期未提供/); h.unmountEffects();
});

await check('API upload rejection remains an error even when a local parser is available', async () => {
  let parses = 0;
  const h = harness({ api: { upload: async () => { throw new Error('synthetic upload offline'); } },
    parser: { hasXLSX: () => true, parseWorkbook: async () => { parses++; return { rowCount: 142 }; }, validateSlot: () => ({ state: 'uploaded' }) } });
  await h.step1().props.onStartUpload('t01', realFile); await h.tick();
  const upload = h.step1().props.uploads.t01;
  assert.equal(upload.state, 'error'); assert.equal(upload.rows, null); assert.equal(parses, 0); noSuccess(h);
});
await check('missing API never creates mock file counts or a success state', async () => {
  const h = harness({ api: null }); await h.step1().props.onStartUpload('t01', realFile); await h.tick();
  assert.equal(h.step1().props.uploads.t01.state, 'error'); assert.equal(h.step1().props.uploads.t01.rows, null); noSuccess(h);
});
await check('missing file never requests a sample import', async () => {
  const h = harness(); await h.step1().props.onStartUpload('t01', null); await h.tick();
  assert.equal(h.calls.length, 0); assert.notEqual(h.step1().props.uploads.t01.state, 'uploaded'); noSuccess(h);
});
await check('real uploads preserve the selected file and backend counts', async () => {
  const h = harness(); await h.step1().props.onStartUpload('t01', realFile);
  const request = h.calls.find(call => call.method === 'upload');
  assert.equal(request.file, realFile); assert.equal(request.fields.sample, 'false');
  const upload = h.step1().props.uploads.t01;
  assert.equal(upload.state, 'uploaded'); assert.equal(upload.rows, 17); assert.equal(upload.source, 'upload');
});
await check('malformed upload response cannot mark a file uploaded', async () => {
  const h = harness({ api: { upload: async () => ({}) } }); await h.step1().props.onStartUpload('t01', realFile);
  assert.equal(h.step1().props.uploads.t01.state, 'error'); noSuccess(h);
});
await check('received file and permission errors do not label the service disconnected', async () => {
  for (const status of [400, 403, 413, 422]) {
    const h = harness({ api: { upload: async () => { throw Object.assign(new Error('实际文件拒绝原因'), { status }); } } });
    await h.step1().props.onStartUpload('t01', realFile);
    assert.equal(h.step1().props.backendMode, true);
    assert.match(h.step1().props.uploads.t01.issue.detail, /实际文件拒绝原因/);
    assert.equal(h.step1().props.uploads.t01.state, 'error');
  }
});
await check('transport failures and gateway unavailable responses expose a connection error', async () => {
  for (const error of [new TypeError('Failed to fetch'), Object.assign(new Error('服务暂不可用'), { status: 503 })]) {
    const h = harness({ api: { upload: async () => { throw error; } } });
    await h.step1().props.onStartUpload('t01', realFile);
    assert.equal(h.step1().props.backendMode, false); assert.equal(h.step1().props.uploads.t01.state, 'error'); noSuccess(h);
  }
});
await check('three uploads alone do not display a synthetic validation report', async () => {
  const h = harness(); await h.uploadAll(); assert.equal(h.find('QkStep2'), undefined);
});
await check('validation rejection leaves no report, no timestamp and an actionable error', async () => {
  const h = harness({ api: { post: async url => { if (url === '/exam-tasks') return { id: 'task-real' }; throw new Error('synthetic validation offline'); } } });
  await h.uploadAll(); h.toasts.length = 0;
  await h.step1().props.onNext(); await h.tick();
  assert.equal(h.find('QkStep2'), undefined); assert.equal(h.step1().props.validating, false);
  assert.ok(nodes(h.render()).some(node => node.props?.role === 'alert' && /校验/.test(textOf(node)))); noSuccess(h);
});
await check('successful validation displays only the actual backend report', async () => {
  const h = harness(); await h.uploadAll(); await h.step1().props.onNext();
  const view = h.find('QkStep2'); assert.ok(view); assert.equal(view.props.data, report); assert.ok(view.props.validatedAt);
});
await check('API absence during validation cannot retain a successful report', async () => {
  const h = harness(); await h.uploadAll(); h.sandbox.window.api = null; h.toasts.length = 0;
  await h.step1().props.onNext(); await h.tick();
  assert.equal(h.find('QkStep2'), undefined); assert.equal(h.step1().props.validating, false); noSuccess(h);
});
await check('malformed validation report produces an error instead of success', async () => {
  const h = harness(); await h.uploadAll(); h.sandbox.window.api.post = async () => ({ report: { severity: 'pass' } }); h.toasts.length = 0;
  await h.step1().props.onNext(); await h.tick();
  assert.equal(h.find('QkStep2'), undefined); assert.ok(nodes(h.render()).some(node => node.props?.role === 'alert')); noSuccess(h);
});
await check('failed revalidation clears the previous successful report', async () => {
  const h = harness(); await h.uploadAll(); await h.step1().props.onNext();
  const revalidate = h.find('QkStep2').props.onRevalidate;
  h.sandbox.window.api.post = async () => { throw new Error('synthetic revalidate offline'); };
  h.toasts.length = 0; await revalidate(); await h.tick();
  assert.equal(h.find('QkStep2'), undefined); assert.equal(h.step1().props.validating, false); noSuccess(h);
});
await check('ordinary school cannot import examples through either visible or retained handlers', async () => {
  const h = harness(); const tree = h.render(); const step = nodes(tree).find(node => node.type === h.sandbox.window.QkStep1);
  assert.equal(nodes(tree).some(node => node.props?.onClick === step.props.onUseExample), false);
  assert.equal(step.props.canImportDemo, false);
  await step.props.onUseExample(); await h.tick(); assert.equal(h.calls.length, 0); noSuccess(h);
});
await check('demo import is explicit and uses only server reports', async () => {
  const h = harness({ orgId: demoOrg }); const tree = h.render(); const step = nodes(tree).find(node => node.type === h.sandbox.window.QkStep1);
  assert.equal(step.props.canImportDemo, true);
  const action = nodes(tree).find(node => node.props?.onClick === step.props.onUseExample);
  assert.match(textOf(action), /导入演示数据/); assert.match(textOf(action), /写入/);
  await step.props.onUseExample(); await h.tick();
  assert.equal(h.calls.filter(call => call.method === 'upload' && call.fields.sample === 'true').length, 3);
  assert.equal(h.step1().props.uploads.t01.rows, 17); assert.equal(h.find('QkStep2').props.data, report);
});
await check('demo import API failure never becomes local sample success', async () => {
  const h = harness({ orgId: demoOrg, api: { upload: async () => { throw new Error('synthetic sample offline'); } } });
  await h.step1().props.onUseExample(); await h.tick();
  assert.equal(Object.values(h.step1().props.uploads).some(upload => upload.state === 'uploaded'), false);
  assert.equal(Object.values(h.step1().props.uploads).some(upload => upload.state === 'validating'), false);
  assert.equal(h.find('QkStep2'), undefined); noSuccess(h);
});
await check('text-only drag and drop never forwards null to an import handler', async () => {
  const h = harness(); const uploads = []; h.sandbox.dropProps = { slot: h.sandbox.window.QK_SLOTS[0], slotData: { state: 'idle' }, onUpload: (...args) => uploads.push(args) };
  const card = h.run('QkDropzone(dropProps)'); card.props.onDrop({ preventDefault() {}, dataTransfer: { files: [] } });
  assert.equal(uploads.length, 0); assert.ok(h.toasts.length > 0);
});
await check('sample file labels describe their real destination without claiming no submission', async () => {
  const h = harness(); const chip = h.run("QkFileChip({tone:QK_TONES.indigo,name:'演示.xlsx',rows:null,size:null,source:'sample'})");
  assert.match(textOf(chip), /提交到演示学校/); assert.match(textOf(chip), /行数未确认/); assert.doesNotMatch(textOf(chip), /不会作为正式提交/);
});
await check('replacement upload invalidates earlier validation before the request settles', async () => {
  const h = harness(); await h.uploadAll(); await h.step1().props.onNext();
  let reject; h.sandbox.window.api.upload = () => new Promise((_, fail) => { reject = fail; });
  const pending = h.step1().props.onStartUpload('t01', realFile); await new Promise(resolve => setImmediate(resolve));
  reject(new Error('synthetic replacement failed')); await pending;
  assert.equal(h.find('QkStep2'), undefined); assert.equal(h.step1().props.uploads.t01.rows, null);
});
await check('old validation cannot reappear after a successful replacement upload', async () => {
  const h = harness(); await h.uploadAll(); const old = deferred();
  h.sandbox.window.api.post = () => old.promise;
  const pending = h.step1().props.onNext();
  await h.step1().props.onStartUpload('t01', { name: 'replacement.xlsx', size: 4096 });
  assert.equal(h.find('QkStep2'), undefined); h.toasts.length = 0;
  old.resolve({ report }); await pending;
  assert.equal(h.find('QkStep2'), undefined); assert.equal(h.step1().props.validating, false); noSuccess(h);
});
await check('validation responses completing in reverse order preserve the newest report', async () => {
  const h = harness(); await h.uploadAll(); const old = deferred(), current = deferred(); let requests = 0;
  h.sandbox.window.api.post = () => (++requests === 1 ? old.promise : current.promise);
  const first = h.step1().props.onNext(); const second = h.step1().props.onNext();
  const currentReport = { ...report, overview: { ...report.overview, teachers: 23 } };
  current.resolve({ report: currentReport }); await second;
  const validatedAt = h.find('QkStep2').props.validatedAt;
  old.resolve({ report }); await first;
  assert.equal(h.find('QkStep2').props.data, currentReport); assert.equal(h.find('QkStep2').props.validatedAt, validatedAt);
});
await check('stale validation rejection and finally do not clear a newer pending request', async () => {
  const h = harness(); await h.uploadAll(); const old = deferred(), current = deferred(); let requests = 0;
  h.sandbox.window.api.post = () => (++requests === 1 ? old.promise : current.promise);
  const first = h.step1().props.onNext(); const second = h.step1().props.onNext(); h.toasts.length = 0;
  old.reject(new Error('stale failure')); await first;
  assert.equal(h.step1().props.validating, true);
  assert.equal(nodes(h.render()).some(node => node.props?.role === 'alert'), false); assert.equal(h.toasts.length, 0);
  current.resolve({ report }); await second; assert.equal(h.find('QkStep2').props.data, report);
});
await check('old validation stays invalid after a removed file is uploaded again', async () => {
  const h = harness(); await h.uploadAll(); const old = deferred(); h.sandbox.window.api.post = () => old.promise;
  const pending = h.step1().props.onNext(); h.step1().props.onRemoveSlot('t01');
  await h.step1().props.onStartUpload('t01', realFile); old.resolve({ report }); await pending;
  assert.equal(h.find('QkStep2'), undefined);
});
await check('old validation cannot restore a report after reset and creation of a new task', async () => {
  let created = 0;
  const h = harness({ api: { post: async url => url === '/exam-tasks' ? { id: `task-${++created}` }
    : url.endsWith('/schedule-jobs') ? { jobId: 'job-1' } : { report } } });
  h.sandbox.window.api.get = async () => ({ taskId: 'task-1', status: 'succeeded', progress: 100, assignments: [] });
  h.sandbox.window.ui.confirm = async () => true;
  await h.uploadAll(); await h.step1().props.onNext(); await h.find('QkStep2').props.onNext();
  const restart = h.find('QkStep4').props.onRestart;
  const old = deferred(), originalPost = h.sandbox.window.api.post;
  h.sandbox.window.api.post = url => url === '/exam-tasks/task-1/imports/validate' ? old.promise : originalPost(url);
  const pending = h.step1().props.onNext(); await restart(); await h.uploadAll();
  assert.equal(created, 2); old.resolve({ report }); await pending;
  assert.equal(h.find('QkStep2'), undefined); assert.equal(h.step1().props.validating, false);
});
await check('a removed upload cannot restore its file when its response arrives late', async () => {
  const h = harness(); await h.uploadAll(); const old = deferred(); h.sandbox.window.api.upload = () => old.promise;
  const pending = h.step1().props.onStartUpload('t01', { name: 'old.xlsx', size: 4096 });
  await new Promise(resolve => setImmediate(resolve)); h.step1().props.onRemoveSlot('t01');
  old.resolve({ originalName: 'old.xlsx', report }); await pending;
  assert.equal(h.step1().props.uploads.t01.state, 'idle'); assert.equal(h.step1().props.uploads.t01.name, null);
});
await check('parallel initial uploads share one task and each retain their real response', async () => {
  const h = harness(); const handlers = h.step1().props;
  await Promise.all(['t01', 't02', 't03'].map(slot => handlers.onStartUpload(slot, realFile)));
  assert.equal(h.calls.filter(call => call.url === '/exam-tasks').length, 1);
  assert.equal(h.calls.filter(call => call.method === 'upload' && call.url === '/exam-tasks/task-real/uploads').length, 3);
  assert.equal(h.step1().props.allReady, true);
});
await check('replacing a completed demo file stops the superseded batch without a stranded spinner', async () => {
  const oldRoom = deferred();
  const h = harness({ orgId: demoOrg, api: { upload: async (url, file, fields) => fields.sample === 'true' && fields.kind === 'classroom'
    ? oldRoom.promise : { originalName: file?.name || '演示.xlsx', report } } });
  const demo = h.step1().props.onUseExample(); await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.step1().props.uploads.t02.state, 'validating');
  await h.step1().props.onStartUpload('t01', realFile); h.toasts.length = 0;
  oldRoom.resolve({ originalName: 'old-room.xlsx', report }); await demo;
  assert.equal(h.step1().props.uploads.t01.name, realFile.name);
  assert.equal(h.step1().props.anyValidating, false); assert.equal(h.find('QkStep2'), undefined); noSuccess(h);
});

await check('handoff accepts the same complete report shape as normal validation', async () => {
  const h = await runHandoff(completedJob, report);
  assert.deepEqual(JSON.parse(JSON.stringify(h.find('QkStep2').props.data)), report);
  assert.ok(h.find('QkStep2').props.validatedAt); assert.equal(h.step1().props.uploads.t01.rows, 17);
  assert.ok(h.find('QkStep4'));
});
await check('malformed or legacy absent handoff reports remain unknown without a validation timestamp', async () => {
  for (const validationReport of [undefined, null, {}, { severity: 'pass' }, { ...report, counts: { pass: 6 } }]) {
    const h = await runHandoff(completedJob, validationReport);
    assert.equal(h.find('QkStep2'), undefined);
    assert.equal(h.step1().props.uploads.t01.rows, null);
    assert.ok(h.find('QkStep4'), 'legacy handoffs may still display a confirmed backend result');
  }
});
await check('completed jobs without taskId retain the known task in both scheduling entry paths', async () => {
  const { taskId, ...snapshot } = completedJob;
  for (const start of [runSchedule, runHandoff]) {
    const h = await start(snapshot, report), view = h.find('QkStep4');
    assert.ok(view); assert.equal(view.props.taskId, 'task-real'); assert.equal(view.props.result.taskId, 'task-real');
    assert.equal(h.saved.get('jkxt:taskDetailId'), 'task-real');
  }
});
await check('mismatched result taskId is rejected in both scheduling entry paths', async () => {
  for (const start of [runSchedule, runHandoff]) {
    const h = await start({ ...completedJob, taskId: 'another-task' }, report);
    assert.equal(h.find('QkStep4'), undefined);
    assert.notEqual(h.saved.get('jkxt:taskDetailId'), 'another-task');
    assert.ok(h.toasts.some(([, options]) => /任务编号/.test(options?.desc || '')));
  }
});
await check('failed, cancelled, and infeasible jobs never become handoff success', async () => {
  for (const status of ['failed', 'cancelled', 'infeasible']) {
    const h = await runHandoff({ ...completedJob, status }, report);
    assert.equal(h.find('QkStep4'), undefined); assert.ok(h.toasts.some(([, options]) => options?.kind === 'warning'));
  }
});
await check('a terminal event with an unconfirmed final status is not packaged as success', async () => {
  for (const status of [undefined, null, '', 'running', 'unknown']) {
    for (const start of [runSchedule, runHandoff]) {
      const h = await start({ ...completedJob, status }, report);
      assert.equal(h.find('QkStep4'), undefined); assert.ok(h.toasts.some(([, options]) => ['warning', 'error'].includes(options?.kind)));
    }
  }
});
await check('a rejected result does not leave the progress view labeled complete', async () => {
  for (const snapshot of [{ ...completedJob, status: 'failed' }, { ...completedJob, status: undefined }, { ...completedJob, taskId: 'another-task' }]) {
    for (const start of [runSchedule, runHandoff]) {
      const h = await start(snapshot, report);
      assert.notEqual(h.find('QkStep3')?.props.stage, 6);
    }
  }
});
await check('100 percent in a start response stays pending until the final job is verified', async () => {
  let onProgress;
  const h = harness({ api: { post: async url => url === '/exam-tasks' ? { id: 'task-real' }
    : url.endsWith('/schedule-jobs') ? { jobId: 'job-real', status: 'running', progress: 100 } : { report } } });
  h.sandbox.window.api.get = async () => completedJob;
  h.sandbox.window.api.eventSource = () => ({
    addEventListener(name, listener) { if (name === 'progress') onProgress = listener; }, close() {},
  });
  await h.uploadAll(); await h.step1().props.onNext();
  const pending = h.find('QkStep2').props.onNext(); await new Promise(resolve => setImmediate(resolve));
  try {
    assert.equal(h.find('QkStep3').props.running, true);
    assert.equal(h.find('QkStep3').props.stage, 5);
    assert.equal(h.find('QkStep4'), undefined);
  } finally {
    onProgress({ data: JSON.stringify({ status: 'succeeded', progress: 100 }) }); await pending;
  }
  assert.equal(h.find('QkStep3').props.stage, 6);
  assert.equal(h.find('QkStep4').props.taskId, 'task-real');
});

console.log(JSON.stringify({ contract: 'QuickTruthfulness/v1', checks, passed: checks.filter(check => check.pass).length, total: checks.length,
  limits: 'Actual component handlers with controlled React hooks, timers, and API boundaries; no live service or browser coverage.' }, null, 2));
if (checks.some(check => !check.pass)) process.exitCode = 1;
