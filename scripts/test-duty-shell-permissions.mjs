import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const production = Babel.transform(fs.readFileSync(path.join(root, 'src/sidebar.jsx'), 'utf8'), {presets: ['react']}).code;
const appSource = fs.readFileSync(path.join(root, 'src/app.jsx'), 'utf8');
const identityEvent = appSource.match(/window\.dispatchEvent\(new CustomEvent\('([^']+)', \{ detail: profile \}\)\)/)?.[1];
const teacher = {user: {id: 'teacher-a', role: 'TEACHER_QUERY'}, org: {id: 'school-a'}, permissions: ['duty:self']};
const manager = {user: {id: 'manager-a', role: 'ADMIN'}, org: {id: 'school-a'}, permissions: ['tenant:read', 'schedule:manage']};
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => {resolve = a; reject = b;}); return {promise, resolve, reject}; };

// Executes the actual production component/effects; this hook host is not React DOM or a browser E2E.
function mount(me, get, mode = 'admin') {
  const slots = [], effects = [], listeners = new Map();
  let cursor = 0, dirty = false, tree, counts = {}, mounted = true, lateStateWrites = 0, active = 'dashboard';
  const equal = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => Object.is(x, b[i]));
  const React = {
    createElement: (type, props, ...children) => ({type, props: {...props, children}, children}), Fragment: 'fragment',
    useState(initial) { const index = cursor++; if (!(index in slots)) slots[index] = typeof initial === 'function' ? initial() : initial;
      return [slots[index], value => {if (!mounted) lateStateWrites++; slots[index] = typeof value === 'function' ? value(slots[index]) : value; dirty = true;}]; },
    useRef(initial) {const index = cursor++; return slots[index] ??= {current: initial};},
    useEffect(fn, deps) {const index = cursor++; if (!slots[index] || !equal(slots[index].deps, deps)) {
      const previous = slots[index]; slots[index] = {deps}; effects.push(() => {previous?.cleanup?.(); slots[index].cleanup = fn();});
    }},
  };
  const window = {
    __jkxtMe: me, api: {get},
    JKXTNavigation: {groups: (_mode, value) => {counts = JSON.parse(JSON.stringify(value)); return []; }},
    addEventListener(name, fn) {if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn);},
    removeEventListener(name, fn) {listeners.get(name)?.delete(fn);},
    dispatchEvent(event) {for (const fn of listeners.get(event.type) || []) fn(event);},
  };
  const context = vm.createContext({React, window, SidebarGlyph: 'glyph', IconChevron: 'chevron', console});
  vm.runInContext(production, context);
  const render = () => {cursor = 0; dirty = false; tree = window.Sidebar({active, mode});
    const frameCounts = JSON.parse(JSON.stringify(counts));
    while (effects.length) effects.shift()(); return frameCounts;};
  const flush = async () => {for (let i = 0; i < 8; i++) {await new Promise(resolve => setImmediate(resolve)); if (dirty && mounted) render();}};
  render();
  return {flush, get counts() {if (dirty && mounted) render(); return counts;}, get tree() {return tree;},
    navigate(next) {active = next; return render();},
    changeIdentity(next, eventOnly = false) {window.__jkxtMe = next; if (eventOnly) window.dispatchEvent({type: identityEvent}); else render();},
    unmount() {mounted = false; for (const value of slots) value?.cleanup?.();},
    get lateStateWrites() {return lateStateWrites;},
    get listenerCount() {return [...listeners.values()].reduce((sum, set) => sum + set.size, 0);},
  };
}

test('identity notification is the real production app event, not a private test protocol', () => {
  assert.equal(identityEvent, 'jkxt:me-changed');
});

test('teacher and unverified identities never request school-wide counts regardless of role label', async () => {
  for (const me of [teacher, {...teacher, user: {...teacher.user, role: 'ADMIN'}}, null,
    {...manager, permissions: []}, {...manager, permissions: 'tenant:read'}]) {
    const calls = [];
    const host = mount(me, async path => {calls.push(path); return [{status: 'published'}];});
    await host.flush(); assert.deepEqual(calls, []); assert.deepEqual(host.counts, {});
  }
});

test('authorized tenant reader displays actual API count and completion proportion', async () => {
  const calls = [];
  const host = mount(manager, async path => {calls.push(path); return [
    {status: 'published'}, {latestJob: {status: 'succeeded'}}, {status: 'scheduled'}, {status: 'draft'},
  ];});
  await host.flush(); assert.deepEqual(calls, ['/exam-tasks']); assert.deepEqual(host.counts, {tasks: 4, donePct: 75});
});

test('same mode user switch clears old counts and rejects late old-user response', async () => {
  const first = deferred(), second = deferred(); let calls = 0;
  const host = mount(manager, () => (++calls === 1 ? first.promise : second.promise));
  host.changeIdentity({...manager, user: {...manager.user, id: 'manager-b'}});
  await host.flush(); assert.equal(calls, 2); assert.deepEqual(host.counts, {});
  second.resolve([{status: 'draft'}]); await host.flush(); assert.deepEqual(host.counts, {tasks: 1, donePct: 0});
  first.resolve(Array.from({length: 7}, () => ({status: 'published'}))); await host.flush();
  assert.deepEqual(host.counts, {tasks: 1, donePct: 0});
});

test('organization change is independent of role and clears previous school counts', async () => {
  const pending = deferred(); let calls = 0;
  const host = mount(manager, async () => ++calls === 1 ? [{status: 'published'}, {status: 'draft'}] : pending.promise);
  await host.flush(); assert.deepEqual(host.counts, {tasks: 2, donePct: 50});
  host.changeIdentity({...manager, org: {id: 'school-b'}});
  await host.flush(); assert.equal(calls, 2); assert.deepEqual(host.counts, {});
  pending.resolve([]); await host.flush(); assert.deepEqual(host.counts, {tasks: 0, donePct: null});
});

test('permission revocation clears existing counts without another forbidden request', async () => {
  let calls = 0;
  const host = mount(manager, async () => {calls++; return [{status: 'published'}];});
  await host.flush(); assert.deepEqual(host.counts, {tasks: 1, donePct: 100});
  host.changeIdentity({...manager, permissions: ['duty:self']}); await host.flush();
  assert.equal(calls, 1); assert.deepEqual(host.counts, {});
});

test('identity change notification alone invalidates pending old privileged response', async () => {
  const pending = deferred(); let calls = 0;
  const host = mount(manager, () => {calls++; return pending.promise;});
  host.changeIdentity(teacher, true);
  pending.resolve([{status: 'published'}]); await host.flush();
  assert.equal(calls, 1); assert.deepEqual(host.counts, {});
});

test('real app identity event can grant tenant read without a parent render', async () => {
  let calls = 0;
  const host = mount(teacher, async () => {calls++; return [{status: 'published'}];});
  await host.flush(); assert.equal(calls, 0);
  host.changeIdentity({...teacher, permissions: ['duty:self', 'tenant:read']}, true);
  await host.flush(); assert.equal(calls, 1); assert.deepEqual(host.counts, {tasks: 1, donePct: 100});
});

test('batched account round trip does not resurrect the first session count response', async () => {
  const first = deferred(), current = deferred(); let calls = 0;
  const host = mount(manager, () => ++calls === 1 ? first.promise : current.promise);
  host.changeIdentity({...manager, user: {...manager.user, id: 'manager-b'}}, true);
  host.changeIdentity(manager, true);
  await host.flush(); assert.equal(calls, 2); assert.deepEqual(host.counts, {});
  current.resolve([{status: 'draft'}]); await host.flush();
  first.resolve(Array.from({length: 7}, () => ({status: 'published'}))); await host.flush();
  assert.deepEqual(host.counts, {tasks: 1, donePct: 0});
});

test('permission order alone does not duplicate count reads', async () => {
  let calls = 0;
  const host = mount(manager, async () => {calls++; return [];}); await host.flush();
  host.changeIdentity({...manager, permissions: [...manager.permissions].reverse()}); await host.flush();
  assert.equal(calls, 1); assert.deepEqual(host.counts, {tasks: 0, donePct: null});
});

test('unmount disposes identity listener and pending count callbacks', async () => {
  const pending = deferred();
  const host = mount(manager, () => pending.promise);
  host.unmount(); pending.resolve([{status: 'published'}]); await host.flush();
  assert.equal(host.lateStateWrites, 0); assert.equal(host.listenerCount, 0);
});

test('super mode retains three real platform reads and not the school task request', async () => {
  const calls = [];
  const host = mount({...manager, user: {id: 'super-a', role: 'SUPER_ADMIN'}}, async path => {
    calls.push(path); return path === '/admin/system-health' ? {uptimeSeconds: 7200} : [{id: 1}, {id: 2}];
  }, 'super');
  await host.flush(); assert.deepEqual(calls.sort(), ['/admin/accounts', '/admin/system-health', '/admin/tenants']);
  assert.deepEqual(host.counts, {tenants: 2, accounts: 2, uptimeSeconds: 7200});
});

test('failed or malformed count responses never invent zero or success badges', async () => {
  for (const reply of [null, {items: []}, new Error('403')]) {
    const host = mount(manager, async () => {if (reply instanceof Error) throw reply; return reply;});
    await host.flush(); assert.deepEqual(host.counts, {});
  }
});

test('navigation reloads task counts after creating a task without reauthentication', async () => {
  let rows = [{status: 'published'}], calls = 0;
  const host = mount(manager, async () => {calls++; return rows;});
  await host.flush(); assert.equal(host.counts.tasks, 1);
  rows = [...rows, {status: 'scheduled'}];
  assert.deepEqual(host.navigate('tasks'), {}, 'first rendered frame must hide previous-page counts before effects'); await host.flush();
  assert.equal(calls, 2);
  assert.deepEqual(host.counts, {tasks: 2, donePct: 100});
  host.navigate('tasks'); await host.flush(); assert.equal(calls, 2, 'same-page render must not poll');
});

test('navigation ignores late previous-page counts and clears counts on a failed refresh', async () => {
  const old = deferred(), latest = deferred(); let calls = 0;
  const host = mount(manager, () => ++calls === 1 ? old.promise : calls === 2 ? latest.promise : Promise.reject(new Error('503')));
  host.navigate('tasks'); await host.flush();
  latest.resolve([{status: 'draft'}, {status: 'published'}]); await host.flush();
  old.resolve(Array.from({length: 7}, () => ({status: 'published'}))); await host.flush();
  assert.deepEqual(host.counts, {tasks: 2, donePct: 50});
  host.navigate('edit'); await host.flush(); assert.deepEqual(host.counts, {});
});

test('navigation never grants teacher access to school-wide counts', async () => {
  const calls = [];
  const host = mount(teacher, async route => {calls.push(route); return [];});
  host.navigate('tasks'); await host.flush(); host.navigate('edit'); await host.flush();
  assert.deepEqual(calls, []); assert.deepEqual(host.counts, {});
});

test('unfiltered task completion is not presented as current-term progress', async () => {
  const host = mount(manager, async () => [{status: 'scheduled'}]); await host.flush();
  const text = node => node == null ? '' : typeof node !== 'object' ? String(node) : Array.isArray(node) ? node.map(text).join('') : text(node.children);
  assert.doesNotMatch(text(host.tree), /本学期排班进度/);
  assert.match(text(host.tree), /任务排班完成率/);
});
