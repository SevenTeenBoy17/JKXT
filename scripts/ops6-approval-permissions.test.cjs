'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const Babel = require('../assets/vendor/babel-standalone.7.29.0.min.js');
const compile = filename => Babel.transform(fs.readFileSync(filename, 'utf8'), { presets: ['react'], filename }).code;
const liveResource = compile(path.join(__dirname, '../src/pages/exam-plans.jsx'));
const filename = process.env.JKXT_OPS6_APPROVAL_SOURCE || path.join(__dirname, '../src/pages/operations-v6-approvals.jsx');
const production = compile(filename);
const AUTHOR = '10000000-0000-4000-8000-000000000001', REVIEWER = '10000000-0000-4000-8000-000000000002';
const PLAN = '20000000-0000-4000-8000-000000000001', REVISION = '30000000-0000-4000-8000-000000000001';
const REQUEST = '40000000-0000-4000-8000-000000000001';
const profile = (id = REVIEWER, permissions = ['schedule:manage']) => ({
  user: { id, username: 'school.admin', displayName: '测试管理员', role: 'ADMIN', mustChangePassword: false },
  org: { id: '50000000-0000-4000-8000-000000000001', slug: 'fixture', name: '测试学校', status: 'ACTIVE' },
  term: null, permissions, onboarded: true,
});
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

// Real Ops6ApprovalReview, useLiveResource, validators, effects and event handlers run here.
// React scheduling and API/DOM boundaries are controlled; this does not claim browser E2E.
function fixture(t, { me = profile(), approved = false, requestedBy = AUTHOR, requestStatus,
  revisionOverrides = {}, reloadRevision, decide, loadFailure = false, noRequest = false } = {}) {
  const slots = [], effects = [], reads = [], writes = [];
  let cursor = 0, dirty = false, mounted = true, tree, lateWrites = 0;
  const same = (a, b) => a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const React = {
    Fragment: 'fragment',
    createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, next => {
        if (!mounted) { lateWrites++; return; }
        const value = typeof next === 'function' ? next(slots[index].value) : next;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(initial) { const index = cursor++; return slots[index] ||= { current: initial }; },
    useMemo(make, deps) { const index = cursor++; if (!same(slots[index]?.deps, deps)) slots[index] = { deps, value: make() }; return slots[index].value; },
    useCallback(fn, deps) { return React.useMemo(() => fn, deps); },
    useEffect(fn, deps) {
      const index = cursor++;
      if (!same(slots[index]?.deps, deps)) {
        const old = slots[index]; slots[index] = { deps, cleanup: old?.cleanup };
        effects.push(() => { slots[index].cleanup?.(); slots[index].cleanup = fn(); });
      }
    },
  };
  let revision = { id: REVISION, planId: PLAN, revisionNo: 1, revisionCursor: 7,
    status: approved ? 'APPROVED' : 'PENDING_APPROVAL', changeReason: '测试安排', publishedAt: null,
    validationSummary: { valid: true, hardViolationCount: 0, issues: [] }, ...revisionOverrides };
  const item = { id: REQUEST, planId: PLAN, planName: '测试计划', revisionId: REVISION, revisionNo: 1,
    status: requestStatus || (approved ? 'APPROVED' : 'PENDING'), requestedBy, requestedByName: '测试提交人',
    decidedBy: approved ? REVIEWER : null, decidedByName: approved ? '独立审核员' : null,
    decisionReason: approved ? '已核对' : null, decidedAt: approved ? '2026-10-21T01:00:00Z' : null,
    createdAt: '2026-10-21T00:00:00Z', version: approved ? 1 : 0 };
  const window = { __jkxtMe: me, Ops6Asset: 'asset', BlueprintDialog: 'dialog', api: {
    operations: { approvals: async () => { if (loadFailure) throw new Error('fixture unavailable'); return noRequest ? [] : [item]; } },
    examPlans: {
      revision: async (...args) => {
        reads.push(args); assert.deepEqual(args, [PLAN, REVISION]);
        return reads.length > 1 && reloadRevision ? reloadRevision(revision) : revision;
      },
      ...Object.fromEntries(['approve', 'reject', 'publish'].map(kind => [kind, async (...args) => {
        writes.push({ kind, args: JSON.parse(JSON.stringify(args)) });
        if (decide) return decide(kind, revision);
        revision = { ...revision, revisionCursor: 8, status: { approve: 'APPROVED', reject: 'REVIEW_REQUIRED', publish: 'PUBLISHED' }[kind],
          publishedAt: kind === 'publish' ? '2026-10-21T02:00:00Z' : null };
        item.status = kind === 'reject' ? 'REJECTED' : 'APPROVED';
        return revision;
      }])),
    },
  } };
  const context = vm.createContext({ React, window, Button: 'button', PageShell: 'page', orgName: () => '测试学校', console });
  vm.runInContext(liveResource, context);
  vm.runInContext(production, context, { filename });
  const component = vm.runInContext('Ops6ApprovalReview', context);
  function render() { cursor = 0; dirty = false; tree = component({ item, onBack() {} }); while (effects.length) effects.shift()(); return tree; }
  async function flush() { for (let i = 0; i < 32; i++) { await Promise.resolve(); if (dirty && mounted) render(); } }
  const nodes = node => Array.isArray(node) ? node.flatMap(nodes) : node && typeof node === 'object'
    ? [node, ...nodes(node.children), ...nodes(node.props?.footer)] : [];
  const text = node => Array.isArray(node) ? node.map(text).join('')
    : node && typeof node === 'object' ? text(node.children) : typeof node === 'string' ? node : '';
  const button = label => nodes(tree).find(node => node.type === 'button' && text(node) === label);
  function fillReason() {
    const field = nodes(tree).find(node => node.type === 'textarea');
    assert.ok(field); field.props.onChange({ target: { value: '  已核对安排  ' } }); render();
  }
  function checkPublish() {
    const field = nodes(tree).find(node => node.type === 'input' && node.props.type === 'checkbox');
    assert.ok(field); field.props.onChange({ target: { checked: true } }); render();
  }
  function unmount() { mounted = false; for (const slot of slots) slot?.cleanup?.(); }
  t.after(unmount); render();
  return { button, flush, fillReason, checkPublish, render, window, reads, writes, unmount, get lateWrites() { return lateWrites; }, text: () => text(tree) };
}

async function assertDecisionBlocked(h) {
  h.fillReason();
  for (const [start, confirm] of [['批准此版本', '确认批准'], ['退回修改', '确认退回']]) {
    const action = h.button(start); assert.ok(action?.props.disabled, `${start} must be disabled`);
    action.props.onClick(); h.render();
    const final = h.button(confirm); assert.ok(final?.props.disabled, `${confirm} must remain disabled`);
    await final.props.onClick(); await h.flush();
    assert.equal(h.writes.length, 0, 'forced invocation cannot reach a write API');
    h.button('取消').props.onClick(); h.render();
  }
}

for (const [name, options] of [
  ['submitter', { me: profile(AUTHOR) }],
  ['missing permission', { me: profile(REVIEWER, []) }],
  ['missing permissions array', { me: { ...profile(), permissions: undefined } }],
  ['missing submitter ID', { requestedBy: null }],
  ['malformed submitter identity', { requestedBy: 'school.admin' }],
  ['missing actor', { me: null }],
  ['username as actor ID', { me: profile('school.admin') }],
]) {
  test(`${name} cannot approve or reject through the review page`, async t => {
    const h = fixture(t, options); await h.flush(); await assertDecisionBlocked(h);
  });
}

for (const [start, confirm, kind] of [['批准此版本', '确认批准', 'approve'], ['退回修改', '确认退回', 'reject']]) {
  test(`authorized independent reviewer can ${kind} only after confirmation`, async t => {
    const h = fixture(t); await h.flush(); h.fillReason();
    assert.equal(h.button(start).props.disabled, false);
    h.button(start).props.onClick(); h.render(); assert.equal(h.writes.length, 0);
    await h.button(confirm).props.onClick(); await h.flush();
    assert.deepEqual(h.writes, [{ kind, args: [PLAN, REVISION, { reason: '已核对安排' }, { headers: { 'If-Match': '7' } }] }]);
    assert.ok(h.reads.length >= 3, 'load, pre-decision re-read and refresh must remain');
    assert.match(h.text(), kind === 'approve' ? /此版本已批准/ : /审批已退回/);
  });
}

for (const [name, me] of [['missing permission', profile(REVIEWER, [])], ['missing identity', null]]) {
  test(`approved revision cannot be published with ${name}`, async t => {
    const h = fixture(t, { me, approved: true }); await h.flush();
    assert.equal(h.button('发布此版本').props.disabled, true);
    h.button('发布此版本').props.onClick(); h.render(); h.checkPublish();
    const action = h.button('确认发布'); assert.equal(action.props.disabled, true);
    await action.props.onClick(); await h.flush(); assert.equal(h.writes.length, 0);
  });
}

test('submitter may publish their revision after independent approval with explicit permission and consent', async t => {
  const h = fixture(t, { me: profile(AUTHOR), approved: true }); await h.flush();
  assert.equal(h.button('发布此版本').props.disabled, false);
  h.button('发布此版本').props.onClick(); h.render();
  assert.equal(h.button('确认发布').props.disabled, true);
  await h.button('确认发布').props.onClick(); assert.equal(h.writes.length, 0);
  h.checkPublish(); assert.equal(h.button('确认发布').props.disabled, false);
  await h.button('确认发布').props.onClick(); await h.flush();
  assert.deepEqual(h.writes, [{ kind: 'publish', args: [PLAN, REVISION, { headers: { 'If-Match': '7' } }] }]);
  assert.match(h.text(), /发布请求已成功返回/);
});

test('stale revision cursor prevents a decision and requires refresh', async t => {
  const h = fixture(t, { reloadRevision: revision => ({ ...revision, revisionCursor: 8 }) });
  await h.flush(); h.fillReason(); h.button('批准此版本').props.onClick(); h.render();
  await h.button('确认批准').props.onClick(); await h.flush();
  assert.equal(h.writes.length, 0); assert.match(h.text(), /刷新后确认/);
  assert.equal(h.button('确认批准').props.disabled, true);
});

test('decision lock prevents double submission before the next render', async t => {
  const pending = deferred();
  const h = fixture(t, { decide: async (kind, revision) => { await pending.promise; return { ...revision, status: 'APPROVED' }; } });
  await h.flush(); h.fillReason(); h.button('批准此版本').props.onClick(); h.render();
  const confirm = h.button('确认批准');
  const first = confirm.props.onClick(), second = confirm.props.onClick();
  await h.flush(); assert.equal(h.writes.length, 1);
  pending.resolve(); await Promise.all([first, second]); await h.flush();
  assert.equal(h.writes.length, 1);
});

for (const [name, options] of [
  ['no request', { noRequest: true }], ['processed request', { requestStatus: 'REJECTED' }],
  ['inconsistent revision status', { revisionOverrides: { status: 'REVIEW_REQUIRED' } }],
  ['failed lookup', { loadFailure: true }],
]) {
  test(`${name} does not display available review actions`, async t => {
    const h = fixture(t, options); await h.flush();
    for (const label of ['批准此版本', '退回修改', '发布此版本']) assert.equal(Boolean(h.button(label)), false);
    assert.equal(h.writes.length, 0);
  });
}

test('missing revision cursor cannot enable a decision', async t => {
  const h = fixture(t, { revisionOverrides: { revisionCursor: undefined } });
  await h.flush(); await assertDecisionBlocked(h);
});

test('incomplete validation still blocks publication after approval', async t => {
  const h = fixture(t, { approved: true, revisionOverrides: { validationSummary: { valid: true } } });
  await h.flush(); assert.equal(h.button('发布此版本').props.disabled, true);
});
