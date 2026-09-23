import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const babel = createRequire(import.meta.url)(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/edit.jsx'), 'utf8');
const compiled = babel.transform(source, { presets: ['react'], filename: 'edit.jsx' }).code;
const rows = [
  { assignmentId: 'slot-A', teacherId: 'teacher-A', teacherName: '原教师甲', role: 'main', source: 'solver', roomId: 'room-A', roomCode: '101', roomName: '第一考场', examId: 'exam-A', subjectName: '语文', examDate: '2026-09-06', startTime: '08:00', endTime: '10:00' },
  { assignmentId: 'slot-B', teacherId: 'teacher-C', teacherName: '原教师丙', role: 'assistant', source: 'solver', roomId: 'room-A', roomCode: '101', roomName: '第一考场', examId: 'exam-A', subjectName: '语文', examDate: '2026-09-06', startTime: '08:00', endTime: '10:00' },
];
const teachers = [{ id: 'teacher-A', name: '原教师甲', teacherNo: 'T01' }, { id: 'teacher-B', name: '候选教师乙', teacherNo: 'T02' }, { id: 'teacher-D', name: '同名教师', teacherNo: 'T03' }];
const task = { id: 'task-A', name: '本校九年级期中考试完整任务名称', status: 'scheduled', startsOn: '2026-09-06', endsOn: '2026-09-07' };
const assignments = { taskId: 'task-A', runId: 'run-A', status: 'scheduled', stats: { revisionCursor: 3 }, rows };
const deferred = () => { let resolve, reject; const promise = new Promise((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; };
const textOf = value => typeof value === 'string' || typeof value === 'number' ? String(value) : Array.isArray(value) ? value.map(textOf).join('') : value && typeof value === 'object' ? textOf(value.props?.children) : '';

function harness({ selectedTask = 'task-A', api = {} } = {}) {
  const hooks = [], effects = [], calls = [], toasts = [], routes = [], listeners = new Map();
  const storage = new Map(selectedTask ? [['jkxt:taskDetailId', selectedTask]] : []);
  let cursor = 0, dirty = false;
  const same = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const sandbox = vm.createContext({ console, Date, Intl, Map, Set, Number, String,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)) },
    window: { ui: { toast: (...args) => toasts.push(args), confirm: async () => true }, navigateTo: route => routes.push(route),
      addEventListener(name, listener) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(listener); },
      removeEventListener(name, listener) { listeners.get(name)?.delete(listener); } }, orgName: () => '本校',
    React: {
      Fragment: Symbol.for('react.fragment'), createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
      useState(initial) { const i = cursor++; if (!(i in hooks)) hooks[i] = typeof initial === 'function' ? initial() : initial;
        return [hooks[i], value => { hooks[i] = typeof value === 'function' ? value(hooks[i]) : value; dirty = true; }]; },
      useRef(initial) { const i = cursor++; if (!(i in hooks)) hooks[i] = { current: initial }; return hooks[i]; },
      useMemo(fn, deps) { const i = cursor++; if (!hooks[i] || !same(deps, hooks[i].deps)) hooks[i] = { deps, value: fn() }; return hooks[i].value; },
      useCallback(fn, deps) { return this.useMemo(() => fn, deps); },
      useEffect(fn, deps) { const i = cursor++; if (!hooks[i] || !same(deps, hooks[i].deps)) { const prev = hooks[i]; hooks[i] = { deps }; effects.push(() => { prev?.cleanup?.(); hooks[i].cleanup = fn(); }); } },
    },
  });
  sandbox.window.api = {
    get: async (url, params) => { calls.push({ method: 'get', url, params }); return api.get ? api.get(url, params) : url === '/teachers' ? teachers : url.endsWith('/assignments') ? assignments : url === '/exam-tasks' ? [task] : task; },
    post: async (url, body) => { calls.push({ method: 'post', url, body }); return api.post ? api.post(url, body) : { allowed: true, conflicts: [] }; },
    patch: async (url, body) => { calls.push({ method: 'patch', url, body }); return api.patch ? api.patch(url, body) : { ...body, teacherName: '候选教师乙' }; },
  };
  for (const name of new Set(source.match(/\bIcon\w+\b/g))) sandbox[name] = name;
  for (const name of ['PageShell', 'Button', 'Tag', 'AvatarChip', 'FilterBar', 'EmptyState']) sandbox[name] = name;
  vm.runInContext(compiled, sandbox);
  const render = () => { cursor = 0; dirty = false; return sandbox.EditSchedulePage(); };
  const flatten = value => {
    if (!value || typeof value !== 'object') return [];
    if (Array.isArray(value)) return value.flatMap(flatten);
    const children = typeof value.type === 'function' ? value.type(value.props) : value.props?.children;
    return [value, ...flatten(children), ...flatten(value.props?.actions), ...flatten(value.props?.subtitle)];
  };
  const all = () => flatten(render());
  const find = id => all().find(n => n.props?.['data-testid'] === id);
  const flush = async () => { for (let i = 0; i < 8; i++) { render(); effects.splice(0).forEach(fn => fn()); await new Promise(resolve => setImmediate(resolve)); if (!dirty && !effects.length) break; } };
  return { sandbox, calls, toasts, routes, storage, render, all, find, flush,
    selectSlot(index = 0) { const slots = all().filter(n => n.props?.['data-testid'] === 'p9-assignment-slot'); assert.ok(slots[index], '岗位按钮存在'); return slots[index].props.onClick(); },
    chooseTeacher(id = 'teacher-B') { const button = all().find(n => n.props?.['data-teacher-id'] === id); assert.ok(button, '候选按钮存在'); return button.props.onClick(); },
    change(id, value) { const field = find(id); assert.ok(field, `${id} field exists`); field.props.onChange({ target: { value } }); },
    emit(name, event = {}) { listeners.get(name)?.forEach(fn => fn(event)); },
    unmount() { hooks.forEach(hook => hook?.cleanup?.()); },
    mutations() { return calls.filter(call => call.method !== 'get'); },
  };
}
const ready = async options => { const h = harness(options); await h.flush(); return h; };
const prepare = async h => { h.selectSlot(); h.chooseTeacher(); h.change('md-reason', '原教师临时请假，已人工核实替补时间'); await h.flush(); };
const secondaryAction = (h, action) => h.all().find(node => node.type === 'Button' && (action === 'publish'
  ? textOf(node) === '复核后发布排班' : node.props?.['aria-label'] === (action === 'undo' ? '撤销上一笔改派' : '重做上一笔改派')));
const readTwoTasks = async url => url === '/teachers' ? teachers : url.endsWith('/assignments')
  ? { ...assignments, taskId: url.includes('task-B') ? 'task-B' : 'task-A', runId: url.includes('task-B') ? 'run-B' : 'run-A' }
  : url.includes('task-B') ? { ...task, id: 'task-B', name: '第二场真实考试' } : task;
const checks = [];
async function check(name, run) { try { await run(); checks.push({ name, pass: true }); } catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 500) }); } }

await check('no selected task never guesses the first server task', async () => {
  const h = await ready({ selectedTask: null }); assert.equal(h.calls.length, 0); assert.equal(h.find('p9-assignment-slot'), undefined);
  assert.match(textOf(h.render()), /选择.*任务|未选择任务/);
});
await check('actual task details provide the full title and mismatched identity fails closed', async () => {
  const h = await ready(); assert.ok(h.calls.some(c => c.url === '/exam-tasks/task-A'));
  assert.match(textOf(h.render().props.subtitle), /本校九年级期中考试完整任务名称/);
  const invalid = await ready({ api: { get: async url => url === '/teachers' ? teachers : url.endsWith('/assignments') ? assignments : { ...task, id: 'other-task' } } });
  assert.equal(invalid.find('p9-assignment-slot'), undefined);
});
await check('slot, candidate selection and drag drop only prepare a preview', async () => {
  const h = await ready(); h.selectSlot(); h.chooseTeacher(); await h.flush(); assert.equal(h.mutations().length, 0);
  assert.match(textOf(h.find('md-preview')), /原教师甲/); assert.match(textOf(h.find('md-preview')), /候选教师乙/);
  const slot = h.all().filter(n => n.props?.['data-testid'] === 'p9-assignment-slot')[1];
  slot.props.onDrop({ preventDefault() {}, dataTransfer: { getData: type => type === 'application/x-jkxt-teacher-id' ? 'teacher-B' : '' } });
  await h.flush(); assert.equal(h.mutations().length, 0); assert.match(textOf(h.find('md-preview')), /原教师丙/);
});
await check('candidate search uses names and employee numbers and missing workload stays unknown', async () => {
  const h = await ready(); h.selectSlot(); h.change('md-candidate-search', 'T02');
  assert.equal(h.all().filter(n => n.props?.['data-testid'] === 'p9-candidate-teacher').length, 1);
  assert.match(textOf(h.find('p9-candidate-teacher')), /未提供/);
});
await check('confirmation requires a reason and sends exactly one checked revision-bound patch', async () => {
  const h = await ready(); h.selectSlot(); h.chooseTeacher();
  assert.equal(h.find('md-confirm').props.disabled, true); await h.find('md-confirm').props.onClick(); assert.equal(h.mutations().length, 0);
  h.change('md-reason', '核实后调整'); const confirm = h.find('md-confirm').props.onClick;
  await Promise.all([confirm(), confirm()]); await h.flush();
  assert.deepEqual(h.mutations().map(c => c.method), ['post', 'patch']);
  assert.equal(h.mutations()[1].body.expectedRevision, 3); assert.equal(h.mutations()[1].body.reason, '核实后调整');
  assert.equal(h.find('md-preview'), undefined); assert.ok(h.toasts.some(([message]) => message === '监考员已改派'));
});
await check('cancel and Escape discard the preview without saving', async () => {
  for (const action of ['cancel', 'escape']) {
    const h = await ready(); await prepare(h);
    if (action === 'cancel') h.find('md-cancel').props.onClick(); else h.emit('keydown', { key: 'Escape', preventDefault() {} });
    await h.flush(); assert.equal(h.find('md-preview'), undefined); assert.equal(h.mutations().length, 0);
  }
});
await check('refresh and task navigation invalidate a retained confirmation handler', async () => {
  for (const action of ['refresh', 'navigate']) {
    const h = await ready(); await prepare(h); const retained = h.find('md-confirm').props.onClick;
    await h.find(action === 'refresh' ? 'md-refresh' : 'md-task-switch').props.onClick(); await h.flush(); await retained();
    assert.equal(h.mutations().length, 0); assert.equal(h.find('md-preview'), undefined);
  }
});
await check('denied or missing precheck approval preserves context and never patches', async () => {
  for (const response of [{ allowed: false, conflicts: [{ message: '同一时段已安排监考', assignmentId: 'slot-A' }] }, {}]) {
    const h = await ready({ api: { post: async () => response } }); await prepare(h); await h.find('md-confirm').props.onClick(); await h.flush();
    assert.equal(h.calls.some(c => c.method === 'patch'), false); assert.ok(h.find('md-preview')); assert.ok(h.find('md-error'));
    assert.equal(h.toasts.some(([, options]) => options?.kind === 'success'), false);
  }
});
await check('revision conflict and unconfirmed patch response require refresh before retry', async () => {
  for (const patch of [async () => { throw Object.assign(new Error('排班已被其他人修改'), { status: 409 }); }, async () => ({ assignmentId: 'wrong-slot', teacherId: 'teacher-B' })]) {
    const h = await ready({ api: { patch } }); await prepare(h); await h.find('md-confirm').props.onClick(); await h.flush();
    assert.ok(h.find('md-error')); assert.equal(h.find('md-confirm').props.disabled, true);
    assert.equal(h.toasts.some(([, options]) => options?.kind === 'success'), false);
  }
});
await check('cancelling a failed preview cannot bypass the mandatory refresh', async () => {
  const h = await ready({ api: { patch: async () => { throw Object.assign(new Error('修订冲突'), { status: 409 }); } } });
  await prepare(h); await h.find('md-confirm').props.onClick(); await h.flush();
  h.find('md-cancel').props.onClick(); await prepare(h);
  assert.equal(h.find('md-confirm').props.disabled, true);
  await h.find('md-refresh').props.onClick(); await h.flush(); await prepare(h);
  assert.equal(h.find('md-confirm').props.disabled, false);
});
await check('unmount while precheck is pending prevents the later patch', async () => {
  const gate = deferred(); const h = await ready({ api: { post: () => gate.promise } }); await prepare(h);
  const pending = h.find('md-confirm').props.onClick(); h.unmount(); gate.resolve({ allowed: true, conflicts: [] }); await pending;
  assert.equal(h.calls.some(c => c.method === 'patch'), false);
});
await check('external task selection clears the old preview and rejects stale confirmation', async () => {
  const h = await ready(); await prepare(h); const retained = h.find('md-confirm').props.onClick;
  h.storage.set('jkxt:taskDetailId', 'task-B'); h.emit('storage', { key: 'jkxt:taskDetailId', newValue: 'task-B' }); await h.flush(); await retained();
  assert.equal(h.mutations().length, 0); assert.equal(h.find('md-preview'), undefined);
});
await check('external task change during a pending precheck prevents the old patch', async () => {
  const gate = deferred(); const h = await ready({ api: { post: () => gate.promise } }); await prepare(h);
  const pending = h.find('md-confirm').props.onClick();
  h.storage.set('jkxt:taskDetailId', 'task-B'); h.emit('storage', { key: 'jkxt:taskDetailId', newValue: 'task-B' }); await h.flush();
  gate.resolve({ allowed: true, conflicts: [] }); await pending;
  assert.equal(h.calls.some(c => c.method === 'patch'), false); assert.equal(h.find('md-preview'), undefined);
});
await check('late task details cannot overwrite a newly selected task', async () => {
  const old = deferred();
  const h = await ready({ api: { get: async url => url === '/teachers' ? teachers : url === '/exam-tasks/task-A' ? old.promise
    : url === '/exam-tasks/task-B' ? { ...task, id: 'task-B', name: '第二场真实考试' }
    : { ...assignments, taskId: url.includes('task-B') ? 'task-B' : 'task-A' } } });
  h.storage.set('jkxt:taskDetailId', 'task-B'); h.emit('storage', { key: 'jkxt:taskDetailId', newValue: 'task-B' }); await h.flush();
  old.resolve(task); await h.flush();
  assert.match(textOf(h.render().props.subtitle), /第二场真实考试/);
  assert.doesNotMatch(textOf(h.render().props.subtitle), /本校九年级期中/);
});
await check('legacy replacement helper still accepts its original public contract', async () => {
  const h = harness(); const result = await h.sandbox.requestScheduleReplacement(h.sandbox.window.api, 'task-A', rows[0], 'teacher-B', 3, 'legacy reason');
  assert.equal(result.patched.teacherId, 'teacher-B'); assert.equal(h.mutations()[1].body.reason, 'legacy reason');
});
await check('mandatory refresh blocks undo redo and publish buttons and retained handlers', async () => {
  for (const action of ['undo', 'redo', 'publish']) {
    const h = await ready({ api: { patch: async () => { throw Object.assign(new Error('修订冲突'), { status: 409 }); } } });
    const retained = secondaryAction(h, action).props.onClick;
    await prepare(h); await h.find('md-confirm').props.onClick(); await h.flush(); h.find('md-cancel').props.onClick(); await h.flush();
    assert.ok(h.find('md-refresh-required')); assert.equal(secondaryAction(h, action).props.disabled, true);
    const count = h.mutations().length; await secondaryAction(h, action).props.onClick(); await retained();
    assert.equal(h.mutations().length, count);
  }
});
await check('publish confirmation is invalidated by task run or revision changes', async () => {
  for (const change of ['task', 'run', 'revision']) {
    let changed = false;
    const confirm = deferred();
    const h = await ready({ api: { get: async url => {
      const value = await readTwoTasks(url);
      return changed && url.endsWith('/assignments') ? { ...value, runId: change === 'run' ? 'new-run' : value.runId,
        stats: { revisionCursor: change === 'revision' ? 4 : 3 } } : value;
    } } });
    h.sandbox.window.ui.confirm = () => confirm.promise;
    const pending = secondaryAction(h, 'publish').props.onClick(); changed = true;
    const nextId = change === 'task' ? 'task-B' : 'task-A'; h.storage.set('jkxt:taskDetailId', nextId);
    h.emit('storage', { key: 'jkxt:taskDetailId', newValue: nextId }); await h.flush();
    confirm.resolve(true); await pending; await h.flush();
    assert.equal(h.calls.some(c => c.url.endsWith('/publish')), false); assert.equal(h.storage.get('jkxt:taskDetailId'), nextId);
  }
});
await check('late undo redo and publish responses never reload or announce the old task', async () => {
  for (const action of ['undo', 'redo', 'publish']) {
    for (const failed of [false, true]) {
      const response = deferred(); const h = await ready({ api: { get: readTwoTasks, post: () => response.promise } });
      const pending = secondaryAction(h, action).props.onClick(); await new Promise(resolve => setImmediate(resolve));
      assert.equal(h.mutations().length, 1);
      h.storage.set('jkxt:taskDetailId', 'task-B'); h.emit('storage', { key: 'jkxt:taskDetailId', newValue: 'task-B' }); await h.flush();
      const reads = h.calls.filter(c => c.method === 'get').length;
      if (failed) response.reject(new Error('旧任务请求失败')); else response.resolve({ alreadyPublished: false, notificationStatus: 'created', recipientCount: 2 });
      await pending; await h.flush();
      assert.equal(h.calls.filter(c => c.method === 'get').length, reads);
      assert.equal(h.storage.get('jkxt:taskDetailId'), 'task-B'); assert.match(textOf(h.render().props.subtitle), /第二场真实考试/);
      assert.equal(h.toasts.some(([, options]) => options?.kind === 'success'), false); assert.equal(h.find('md-error'), undefined);
    }
  }
});
await check('current task undo redo and confirmed publish still use their original endpoints', async () => {
  for (const action of ['undo', 'redo', 'publish']) {
    const h = await ready(); await secondaryAction(h, action).props.onClick(); await h.flush();
    assert.equal(h.mutations().length, 1);
    assert.equal(h.mutations()[0].url, '/tasks/task-A/' + (action === 'publish' ? 'publish' : 'schedule/' + action));
    assert.ok(h.toasts.some(([, options]) => options?.kind === 'success'));
  }
});

console.log(JSON.stringify({ contract: 'ManualDispatchV2/v1', checks, passed: checks.filter(c => c.pass).length, total: checks.length,
  limits: 'Real component hooks and handlers with controlled API boundaries. No browser, live backend, or production mutations.' }, null, 2));
if (checks.some(c => !c.pass)) process.exitCode = 1;
