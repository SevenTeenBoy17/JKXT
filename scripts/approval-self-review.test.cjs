'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const Babel = require('../assets/vendor/babel-standalone.7.29.0.min.js');
const filename = process.env.JKXT_APPROVAL_SOURCE || path.join(__dirname, '../src/pages/exam-plans.jsx');
const source = Babel.transform(fs.readFileSync(filename, 'utf8'), { presets: ['react'], filename }).code;
const AUTHOR = '10000000-0000-4000-8000-000000000001';
const REVIEWER = '10000000-0000-4000-8000-000000000002';
const PLAN = '20000000-0000-4000-8000-000000000001';
const REVISION = '30000000-0000-4000-8000-000000000001';
const REQUEST = '40000000-0000-4000-8000-000000000001';
const profile = (id = REVIEWER, permissions = ['schedule:manage']) => ({
  user: { id, username: 'school.admin', displayName: '测试管理员', role: 'ADMIN', mustChangePassword: false },
  org: { id: '50000000-0000-4000-8000-000000000001', slug: 'fixture', name: '测试学校', status: 'ACTIVE' },
  term: null, permissions, onboarded: true,
});
const request = (overrides = {}) => ({
  id: REQUEST, planId: PLAN, planName: '测试计划', revisionId: REVISION, revisionNo: 1,
  status: 'PENDING', requestedBy: AUTHOR, requestedByName: '测试申请人',
  decidedBy: null, decidedByName: null, decisionReason: null, decidedAt: null,
  createdAt: '2026-10-21T00:00:00Z', version: 0, ...overrides,
});

// Execute the production component, its effects and event handlers. Only React scheduling
// and the external API/UI boundary are controlled: this is component evidence, not browser E2E.
function fixture(t, { me = profile(), rows = [request()], load, status = 'PENDING_APPROVAL' } = {}) {
  const slots = [], effects = [], writes = [], notices = [], confirmations = [], reads = [];
  let cursor = 0, dirty = false, mounted = true, tree;
  const same = (a, b) => a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const React = {
    Fragment: 'fragment',
    createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, next => {
        if (!mounted) return;
        const value = typeof next === 'function' ? next(slots[index].value) : next;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(initial) { const index = cursor++; return slots[index] ||= { current: initial }; },
    useMemo(make, deps) {
      const index = cursor++;
      if (!same(slots[index]?.deps, deps)) slots[index] = { deps, value: make() };
      return slots[index].value;
    },
    useCallback(fn, deps) { return React.useMemo(() => fn, deps); },
    useEffect(fn, deps) {
      const index = cursor++;
      if (!same(slots[index]?.deps, deps)) {
        const old = slots[index]; slots[index] = { deps, cleanup: old?.cleanup };
        effects.push(() => { slots[index].cleanup?.(); slots[index].cleanup = fn(); });
      }
    },
  };
  const window = {
    __jkxtMe: me,
    ui: { confirm: async value => { confirmations.push(value); return true; }, toast: (...args) => notices.push(args) },
    api: {
      operations: { approvals: async () => { reads.push('approvals'); return load ? load() : rows; } },
      examPlans: Object.fromEntries(['approve', 'reject'].map(decision => [decision, async (...args) => {
        writes.push({ decision, args: JSON.parse(JSON.stringify(args)) });
        return { id: REVISION, status: decision === 'approve' ? 'APPROVED' : 'REVIEW_REQUIRED', revisionCursor: 8 };
      }])),
    },
  };
  const props = { planId: PLAN, revision: { id: REVISION, status, revisionCursor: 7, revisionNo: 1 },
    onChanged: async result => { props.revision = result; dirty = true; } };
  const context = vm.createContext({ React, window, Button: 'button', console });
  vm.runInContext(source, context, { filename });
  const component = vm.runInContext('PlanLifecycleActions', context);
  function render() {
    cursor = 0; dirty = false; tree = component(props);
    while (effects.length) effects.shift()();
    return tree;
  }
  async function flush() {
    for (let i = 0; i < 24; i++) { await Promise.resolve(); if (dirty && mounted) render(); }
  }
  const nodes = node => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const text = node => Array.isArray(node) ? node.map(text).join('')
    : node && typeof node === 'object' ? text(node.children) : typeof node === 'string' ? node : '';
  const button = label => nodes(tree).find(node => node.type === 'button' && text(node) === label);
  function setReason(value = '已核对考试安排') {
    const field = nodes(tree).find(node => node.type === 'textarea');
    assert.ok(field, 'approval reason field must be rendered');
    field.props.onChange({ target: { value } }); render();
  }
  t.after(() => { mounted = false; for (const slot of slots) slot?.cleanup?.(); });
  render();
  return { button, flush, render, setReason, writes, confirmations, reads, notices, props, window,
    text: () => text(tree), field: () => nodes(tree).find(node => node.type === 'textarea') };
}

async function assertBlocked(h, message) {
  for (const label of ['批准', '驳回']) {
    const action = h.button(label);
    assert.ok(!action || action.props.disabled, `${message}: ${label} must not be available`);
    // Also invoke a disabled control's handler directly: UI decoration must not be the only guard.
    if (action) await action.props.onClick();
  }
  assert.equal(h.writes.length, 0, `${message}: no decision may cross the API boundary`);
  assert.equal(h.confirmations.length, 0, `${message}: no decision confirmation may open`);
}

test('submitter cannot approve or reject their own pending request', async t => {
  const h = fixture(t, { me: profile(AUTHOR) }); await h.flush();
  h.setReason(); await assertBlocked(h, 'self review');
  assert.match(h.text(), /其他.*审批|不能.*审批.*自己|本人.*不能/);
});

for (const [label, decision, message] of [['批准', 'approve', '版本已批准'], ['驳回', 'reject', '版本已驳回']]) {
  test(`a different authorized user can ${decision} the exact pending revision`, async t => {
    // Username is deliberately the same as the submitter's normal account name; only UUID matters.
    const h = fixture(t); await h.flush(); h.setReason('  已核对考试安排  ');
    const action = h.button(label); assert.ok(action && !action.props.disabled);
    await action.props.onClick(); await h.flush();
    assert.deepEqual(h.writes, [{ decision, args: [PLAN, REVISION, { reason: '已核对考试安排' }, { headers: { 'If-Match': '7' } }] }]);
    assert.equal(h.confirmations.length, 1);
    assert.ok(h.text().includes(message), 'component must show the acknowledged decision');
    assert.equal(Boolean(h.button('批准')), false, 'processed revision must no longer show approval');
  });
}

for (const [name, me] of [
  ['missing permission', profile(REVIEWER, [])],
  ['missing permissions field', { ...profile(), permissions: undefined }],
  ['missing user ID', profile(undefined)],
  ['username supplied as ID', profile('school.admin')],
  ['missing profile', null],
]) {
  test(`${name} fails closed even for an ADMIN role`, async t => {
    if (name === 'missing user ID') me.user.id = undefined;
    const h = fixture(t, { me }); await h.flush(); h.setReason(); await assertBlocked(h, name);
  });
}

for (const [name, rows] of [
  ['no pending request', []],
  ['already approved request', [request({ status: 'APPROVED' })]],
  ['already rejected request', [request({ status: 'REJECTED' })]],
  ['request for another revision', [request({ revisionId: '30000000-0000-4000-8000-000000000002' })]],
  ['request for another plan', [request({ planId: '20000000-0000-4000-8000-000000000002' })]],
  ['missing submitter ID', [request({ requestedBy: null })]],
  ['username in submitter ID', [request({ requestedBy: 'school.admin' })]],
  ['ambiguous pending requests', [request(), request({ id: '40000000-0000-4000-8000-000000000002' })]],
]) {
  test(`${name} does not offer a decision`, async t => {
    const h = fixture(t, { rows }); await h.flush(); h.setReason(); await assertBlocked(h, name);
  });
}

test('pending approval data cannot enable controls before loading completes', async t => {
  let resolve;
  const h = fixture(t, { load: () => new Promise(done => { resolve = done; }) });
  h.setReason(); await assertBlocked(h, 'loading');
  resolve([request()]); await h.flush();
  assert.equal(h.button('批准').props.disabled, false);
});

test('approval lookup failure fails closed', async t => {
  const h = fixture(t, { load: async () => { throw new Error('fixture unavailable'); } });
  await h.flush(); h.setReason(); await assertBlocked(h, 'failed lookup');
  assert.match(h.text(), /未能|失败|无法/);
});

test('processed revision status cannot reuse an older pending request', async t => {
  const h = fixture(t, { status: 'REVIEW_REQUIRED' }); await h.flush();
  await assertBlocked(h, 'non-pending revision');
  assert.equal(h.reads.length, 0);
});

test('switching revisions cannot reuse a previous approval lookup', async t => {
  let resolveNext, loads = 0;
  const h = fixture(t, { load: () => ++loads === 1 ? [request()] : new Promise(done => { resolveNext = done; }) });
  await h.flush(); h.setReason(); assert.equal(h.button('批准').props.disabled, false);
  h.props.revision = { ...h.props.revision, id: '30000000-0000-4000-8000-000000000002' };
  h.render(); await assertBlocked(h, 'new revision before loading');
  resolveNext([request()]); await h.flush(); await assertBlocked(h, 'unmatched request after loading');
});
