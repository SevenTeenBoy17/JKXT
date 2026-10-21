'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const Babel = require('../assets/vendor/babel-standalone.7.29.0.min.js');
const root = path.resolve(__dirname, '..');
const filename = process.env.JKXT_OPS6_AUDIT_SOURCE || path.join(root, 'src/pages/operations-v6-approvals.jsx');
const code = Babel.transform(fs.readFileSync(filename, 'utf8'), { presets: ['react'], filename }).code;
const PLAN = '20000000-0000-4000-8000-000000000001', REV = '30000000-0000-4000-8000-000000000001';
const NEXT = '30000000-0000-4000-8000-000000000002';
const event = (id = 1, action = 'APPROVAL_SUBMITTED') => ({ id: `40000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
  action, occurredAt: '2026-10-21T00:00:00.123456Z', actorDisplayName: '合成审核员' });
const page = (items = [event()], extras = {}) => ({ planId: PLAN, revisionId: REV, source: 'EXAM_PLAN_AUDIT_EVENT', items,
  hasMore: false, nextCursor: null, ...extras });
const defer = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const nodes = value => Array.isArray(value) ? value.flatMap(nodes) : value && typeof value === 'object'
  ? [value, ...nodes(value.children)] : [];
const text = value => Array.isArray(value) ? value.map(text).join('') : value && typeof value === 'object'
  ? text(value.children) : typeof value === 'string' || typeof value === 'number' ? String(value) : '';

// Production hooks, event handlers, validation, and rendering run unchanged. Only the
// React scheduler/element boundary and remote API are controlled; this is not browser E2E.
function host(t, respond = async () => page()) {
  let cursor = 0, dirty = false, mounted = true, lateWrites = 0, tree, item = { planId: PLAN, revisionId: REV };
  const slots = [], effects = [], calls = [];
  const same = (a, b) => a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const React = {
    Fragment: 'fragment', createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
    useState(initial) { const i = cursor++; if (!(i in slots)) slots[i] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[i].value, next => { if (!mounted) { lateWrites++; return; } const value = typeof next === 'function' ? next(slots[i].value) : next;
        if (!Object.is(value, slots[i].value)) { slots[i].value = value; dirty = true; } }]; },
    useRef(initial) { const i = cursor++; return slots[i] ||= { current: initial }; },
    useMemo(make, deps) { const i = cursor++; if (!same(slots[i]?.deps, deps)) slots[i] = { deps, value: make() }; return slots[i].value; },
    useCallback(fn, deps) { return React.useMemo(() => fn, deps); },
    useEffect(fn, deps) { const i = cursor++; if (!same(slots[i]?.deps, deps)) { const old = slots[i]; slots[i] = { deps, cleanup: old?.cleanup };
      effects.push(() => { slots[i].cleanup?.(); slots[i].cleanup = fn(); }); } },
  };
  const window = { __jkxtMe: { user: { id: '10000000-0000-4000-8000-000000000001', role: 'ADMIN' },
    org: { id: '50000000-0000-4000-8000-000000000001' }, permissions: ['tenant:read'] }, api: { examPlans: {
      auditEvents: async (...args) => { calls.push(args); return respond(args, calls.length); },
    } } };
  const context = vm.createContext({ window, React, Button: 'button', Date, Intl });
  vm.runInContext(code, context, { filename });
  const component = vm.runInContext('typeof Ops6AuditHistory === "function" ? Ops6AuditHistory : null', context);
  assert.equal(typeof component, 'function', 'the real version audit component must exist');
  function render() { cursor = 0; dirty = false; tree = component({ item }); const frame = tree; while (effects.length) effects.shift()(); return frame; }
  async function flush() { for (let i = 0; i < 30; i++) { await Promise.resolve(); if (dirty && mounted) render(); } }
  const button = label => nodes(tree).find(x => x.type === 'button' && text(x) === label);
  const toggle = open => { const details = nodes(tree).find(x => x.type === 'details'); assert.ok(details?.props.onToggle); details.props.onToggle({ currentTarget: { open } }); render(); };
  const unmount = () => { if (!mounted) return; mounted = false; for (const slot of slots) slot?.cleanup?.(); };
  t.after(() => { unmount(); assert.equal(lateWrites, 0, 'production must not update state after unmount'); }); render();
  return { window, calls, render, flush, toggle, button, unmount, get lateWrites() { return lateWrites; }, text: () => text(tree),
    isOpen: () => Boolean(nodes(tree).find(x => x.type === 'details')?.props.open),
    navigate(next) { item = next; return render(); }, rows: () => nodes(tree).filter(x => x.type === 'tr' && x.props['data-event-id']) };
}

test('audit remains lazy, then requests the exact version and renders safe metadata only', async t => {
  const h = host(t, async () => page([{ ...event(), reason: 'SECRET_REASON', beforeState: { token: 'SECRET_TOKEN' } }]));
  await h.flush(); assert.equal(h.calls.length, 0); assert.match(h.text(), /本版本已记录操作/);
  h.toggle(true); await h.flush();
  assert.equal(h.calls.length, 1); assert.deepEqual(JSON.parse(JSON.stringify(h.calls[0])), [PLAN, REV, { limit: 20 }]);
  assert.match(h.text(), /提交审批/); assert.match(h.text(), /合成审核员/); assert.match(h.text(), /08:00/);
  assert.doesNotMatch(h.text(), /SECRET_REASON|SECRET_TOKEN|完整操作追溯/);
  assert.match(h.text(), /不含全部|不代表完整/);
});
test('empty is a successful absence of returned events, not a complete-history claim', async t => {
  const h = host(t, async () => page([])); h.toggle(true); await h.flush();
  assert.match(h.text(), /未返回.*记录|暂无.*记录/); assert.match(h.text(), /不代表.*未.*操作|不代表.*没有.*操作/);
});
test('loading is visible until the request resolves', async t => {
  const pending = defer(), h = host(t, () => pending.promise); h.toggle(true); await h.flush(); assert.match(h.text(), /正在读取/);
  pending.resolve(page()); await h.flush(); assert.equal(h.rows().length, 1);
});
for (const status of [401, 403, 404, 500]) test(`HTTP ${status} has an explicit error and retry state`, async t => {
  const h = host(t, async (_, count) => { if (count === 1) throw Object.assign(Error('private internal detail'), { status }); return page(); });
  h.toggle(true); await h.flush(); assert.equal(h.rows().length, 0); assert.ok(h.button('重试读取')); assert.doesNotMatch(h.text(), /private internal detail/);
  await h.button('重试读取').props.onClick(); await h.flush(); assert.equal(h.rows().length, 1);
});
for (const invalid of [page([], { revisionId: NEXT }), page([], { source: 'AUDIT_LOG' }), page([], { items: null }),
  page([], { hasMore: true, nextCursor: null }), page([event(1), event(1)]), page([{ ...event(), occurredAt: 'invalid' }])]) {
  test(`malformed or out-of-scope response is not presented as an empty page: ${JSON.stringify(invalid)}`, async t => {
    const h = host(t, async () => invalid); h.toggle(true); await h.flush(); assert.ok(h.button('重试读取')); assert.equal(h.rows().length, 0);
  });
}
test('pagination locks double invocation, deduplicates overlap, and ends without claiming all history', async t => {
  const second = defer(); const h = host(t, async (_, n) => n === 1 ? page([event(2)], { hasMore: true, nextCursor: 'cursor-2' }) : second.promise);
  h.toggle(true); await h.flush(); const load = h.button('加载更多'); assert.ok(load);
  const first = load.props.onClick(), duplicate = load.props.onClick(); await h.flush(); assert.equal(h.calls.length, 2);
  assert.deepEqual(JSON.parse(JSON.stringify(h.calls[1])), [PLAN, REV, { limit: 20, cursor: 'cursor-2' }]);
  second.resolve(page([event(2), event(1)])); await Promise.all([first, duplicate]); await h.flush();
  assert.equal(h.rows().length, 2); assert.equal(Boolean(h.button('加载更多')), false); assert.match(h.text(), /已到.*记录末尾|已显示.*返回记录/);
});
test('load-more failure preserves same-scope rows and retries the same cursor without duplication', async t => {
  const h = host(t, async (_, n) => { if (n === 1) return page([event(2)], { hasMore: true, nextCursor: 'cursor-2' });
    if (n === 2) throw Error('offline'); return page([event(1)]); });
  h.toggle(true); await h.flush(); await h.button('加载更多').props.onClick(); await h.flush();
  assert.equal(h.rows().length, 1); assert.ok(h.button('重试加载更多'));
  await h.button('重试加载更多').props.onClick(); await h.flush(); assert.equal(h.rows().length, 2);
  assert.equal(h.calls[1][2].cursor, h.calls[2][2].cursor);
});
test('access loss during pagination clears previously rendered events', async t => {
  const h = host(t, async (_, n) => { if (n === 1) return page([event()], { hasMore: true, nextCursor: 'c' });
    throw Object.assign(Error('forbidden'), { status: 403 }); });
  h.toggle(true); await h.flush(); await h.button('加载更多').props.onClick(); await h.flush();
  assert.equal(h.rows().length, 0); assert.match(h.text(), /权限|无权/);
});
test('404 during pagination removes stale records for the now-unavailable revision', async t => {
  const h = host(t, async (_, n) => { if (n === 1) return page([event()], { hasMore: true, nextCursor: 'c' });
    throw Object.assign(Error('missing'), { status: 404 }); });
  h.toggle(true); await h.flush(); await h.button('加载更多').props.onClick(); await h.flush();
  assert.equal(h.rows().length, 0); assert.match(h.text(), /版本不可用/); assert.ok(h.button('重试读取'));
});
for (const outcome of ['success', 'error']) test(`collapsing while loading stays collapsed after ${outcome}`, async t => {
  const pending = defer(), h = host(t, () => pending.promise); h.toggle(true); await h.flush(); h.toggle(false);
  assert.equal(h.isOpen(), false);
  if (outcome === 'success') pending.resolve(page()); else pending.reject(Error('offline'));
  await h.flush(); assert.equal(h.isOpen(), false);
});
test('refresh restarts at the first page and does not mix it with the old list', async t => {
  const h = host(t, async (_, n) => n === 1 ? page([event(1)], { hasMore: true, nextCursor: 'c' }) : page([event(3)]));
  h.toggle(true); await h.flush(); await h.button('刷新操作记录').props.onClick(); await h.flush();
  assert.equal(h.calls[1][2].cursor, undefined); assert.equal(h.rows().length, 1);
  assert.equal(h.rows()[0].props['data-event-id'], event(3).id);
});
test('version navigation hides old data in the first frame and ignores the old in-flight page', async t => {
  const pending = defer(); const h = host(t, async (_, n) => n === 1 ? page([event()], { hasMore: true, nextCursor: 'c' }) : pending.promise);
  h.toggle(true); await h.flush(); const loading = h.button('加载更多').props.onClick(); await h.flush();
  const frame = h.navigate({ planId: PLAN, revisionId: NEXT }); assert.doesNotMatch(text(frame), /合成审核员/);
  pending.resolve(page([event(2)])); await loading; await h.flush(); assert.equal(h.rows().length, 0);
});
for (const identity of ['org', 'user']) test(`${identity} scope change hides old data synchronously`, async t => {
  const h = host(t); h.toggle(true); await h.flush();
  h.window.__jkxtMe[identity] = { ...h.window.__jkxtMe[identity], id: '60000000-0000-4000-8000-000000000009' };
  assert.doesNotMatch(text(h.render()), /合成审核员/); await h.flush(); assert.equal(h.rows().length, 0);
});
test('unmount ignores an unresolved response', async t => {
  const pending = defer(), h = host(t, () => pending.promise); h.toggle(true); await h.flush(); h.unmount();
  pending.resolve(page()); await h.flush();
  assert.equal(h.lateWrites, 0, 'a successful late response must not update unmounted state');
});
test('unmount ignores an unresolved rejection', async t => {
  const pending = defer(), h = host(t, () => pending.promise); h.toggle(true); await h.flush(); h.unmount();
  pending.reject(Error('offline')); await h.flush();
  assert.equal(h.lateWrites, 0, 'a failed late response must not update unmounted state');
});
for (const action of ['NEW_EVENT', '__proto__', 'toString']) test(`unknown ${action} remains visible as text with no fabricated actor`, async t => {
  const h = host(t, async () => page([{ ...event(1, action), actorDisplayName: null }])); h.toggle(true); await h.flush();
  assert.ok(h.text().includes(action)); assert.match(h.text(), /操作人未提供|姓名未提供/); assert.doesNotMatch(h.text(), /function|系统操作/);
});

test('real API client encodes the exact audit endpoint, pagination and cookie transport', async () => {
  const requests = [];
  const window = { location: { search: '', pathname: '/Dashboard.html' } };
  const context = vm.createContext({ window, localStorage: { getItem: () => null }, document: { cookie: '' },
    URLSearchParams, FormData, Blob, ArrayBuffer, AbortController, setTimeout, clearTimeout,
    fetch: async (url, options) => { requests.push({ url, options }); return new Response(JSON.stringify(page()), { status: 200 }); } });
  vm.runInContext(fs.readFileSync(process.env.JKXT_AUDIT_API_SOURCE || path.join(root, 'src/api.js'), 'utf8'), context);
  assert.equal(typeof window.api.examPlans.auditEvents, 'function', 'API method is required');
  await window.api.examPlans.auditEvents(PLAN, REV, { limit: 20, cursor: 'a+b/c=' });
  assert.equal(requests[0].url, `/api/v1/exam-plans/${PLAN}/revisions/${REV}/audit-events?limit=20&cursor=a%2Bb%2Fc%3D`);
  assert.equal(requests[0].options.method, 'GET'); assert.equal(requests[0].options.credentials, 'include');
});

test('the real review page mounts the exact-item audit component', () => {
  const item = { planId: PLAN, revisionId: REV, id: 'request', planName: 'Synthetic plan' };
  const context = vm.createContext({ window: { __jkxtMe: null }, Button: 'button', PageShell: 'page', orgName: () => 'Synthetic org',
    React: { Fragment: 'fragment', createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
      useState: value => [value, () => {}], useRef: value => ({ current: value }), useEffect() {}, useCallback: fn => fn },
    useLiveResource: () => [{ status: 'ready', data: { revision: { id: REV, planId: PLAN, revisionNo: 1, revisionCursor: 0, status: 'DRAFT' }, history: [] } }, () => {}] });
  vm.runInContext(code, context, { filename });
  const tree = vm.runInContext('Ops6ApprovalReview', context)({ item, onBack() {} });
  const audit = nodes(tree).find(x => x.type === vm.runInContext('Ops6AuditHistory', context));
  assert.ok(audit, 'real review must mount the audit feature, not leave it unreachable');
  assert.equal(audit.props.item, item);
});
for (const change of [me => { me.permissions = []; }, me => { me.user.role = 'VIEWER'; }, me => { me.org.id = ''; }]) {
  test('unverified identity or missing read access does not invoke audit API', async t => {
    const h = host(t); change(h.window.__jkxtMe); h.render(); h.toggle(true); await h.flush();
    assert.equal(h.calls.length, 0); assert.equal(h.rows().length, 0); assert.match(h.text(), /不能读取/);
  });
}
