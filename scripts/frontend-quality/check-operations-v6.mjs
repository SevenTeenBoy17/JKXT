import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

// Offline consumer/model checks only. No browser, application server, credentials,
// database or live workflow API is used. Generated output is explicitly scoped.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const startedAt = new Date().toISOString();
const checks = [];
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
const jsxPaths = ['src/ui/operations-v6-assets.jsx', 'src/pages/operations-v6-emergency.jsx', 'src/pages/operations-v6-approvals.jsx'];
const trackedPaths = [...jsxPaths, 'src/styles/operations-v6.css', 'src/pages/operations-workbench.jsx', 'src/navigation.js', 'src/app.jsx', 'src/babel-script-scope.js', 'Dashboard.html', 'artifacts/operations-v6-frontend/baseline/Dashboard.html', 'artifacts/operations-v6-frontend/visual-harness.html', 'artifacts/operations-v6-frontend/visual-fixtures.js'];
const inputHashes = trackedPaths.map(relative => ({ path: relative, sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(root, relative))).digest('hex') }));
const approvalSource = read('src/pages/operations-v6-approvals.jsx');
let compiled;
const context = vm.createContext({ window: {}, console, Date, Intl });
const emergencyContext = vm.createContext({ window: {}, console, Date, Intl });

async function check(name, assertion) {
  const begin = Date.now();
  try { await assertion(); checks.push({ name, pass: true, durationMs: Date.now() - begin }); }
  catch (error) { checks.push({ name, pass: false, durationMs: Date.now() - begin, error: String(error.message) }); }
}

await check('Three actual V6 JSX files parse with vendored Babel', () => {
  for (const relative of jsxPaths) {
    const transformed = Babel.transform(read(relative), { presets: ['env', 'react'], filename: relative }).code;
    new vm.Script(transformed, { filename: relative });
    if (relative.endsWith('approvals.jsx')) compiled = transformed;
    if (relative.endsWith('emergency.jsx')) vm.runInContext(transformed, emergencyContext);
  }
  vm.runInContext(compiled, context);
});

const model = context.window.Ops6ApprovalModel;
const emergencyModel = emergencyContext.window.Ops6EmergencyModel;
await check('Distinct id-less emergency records retain distinct canonical selection keys across reorder', () => {
  const first = { emergencyType: 'TEACHER_SICK_LEAVE', planId: 'TEST-plan-1', affectedResources: { teacherIds: ['T016'], roomCode: 'B201' }, createdAt: '2026-09-07T01:00:00Z' };
  const second = { emergencyType: 'ROOM_UNAVAILABLE', planId: 'TEST-plan-2', affectedResources: { roomCode: 'B203' }, createdAt: '2026-09-07T02:00:00Z' };
  const keyed = emergencyModel.keyRows([first, second]);
  assert.notEqual(keyed[0].__ops6Key, keyed[1].__ops6Key);
  const reorderedFirst = { createdAt: first.createdAt, affectedResources: { roomCode: 'B201', teacherIds: ['T016'] }, planId: first.planId, emergencyType: first.emergencyType };
  const reordered = emergencyModel.keyRows([second, reorderedFirst]);
  assert.equal(reordered[0].__ops6Key, keyed[1].__ops6Key);
  assert.equal(reordered[1].__ops6Key, keyed[0].__ops6Key);
  assert.equal(reordered.find(item => item.__ops6Key === keyed[0].__ops6Key).planId, first.planId);
});

await check('Identical id-less emergency duplicates receive unique occurrence keys', () => {
  const record = { emergencyType: 'ROOM_UNAVAILABLE', planId: 'TEST-plan-1', affectedResources: { roomCode: 'B201' } };
  const keyed = emergencyModel.keyRows([record, plain(record), plain(record)]);
  assert.equal(new Set(keyed.map(item => item.__ops6Key)).size, 3);
  assert.equal(keyed.find(item => item.__ops6Key === keyed[1].__ops6Key), keyed[1]);
});

await check('Emergency UI keys are non-enumerable and never appear in serialized backend records', () => {
  const original = { id: 'TEST-emergency-1', planId: 'TEST-plan-1', emergencyType: 'ROOM_UNAVAILABLE' };
  const [keyed] = emergencyModel.keyRows([original]);
  assert.equal(typeof keyed.__ops6Key, 'string');
  assert.equal(Object.getOwnPropertyDescriptor(keyed, '__ops6Key').enumerable, false);
  assert.equal(Object.hasOwn(original, '__ops6Key'), false);
  assert.equal(Object.keys(keyed).includes('__ops6Key'), false);
  assert.equal(JSON.stringify(keyed), JSON.stringify(original));
  assert.deepEqual(plain(keyed), original);
});

await check('Queue distinguishes request PENDING, exact approved version, historical publication and missing join', () => {
  assert.equal(model.stage({ status: 'PENDING', revision: { status: 'PENDING_APPROVAL' } }), 'pending');
  assert.equal(model.stage({ status: 'APPROVED', revision: { status: 'APPROVED' } }), 'publish');
  assert.equal(model.stage({ status: 'APPROVED', revision: { status: 'PUBLISHED' } }), 'history');
  assert.notEqual(model.stage({ status: 'APPROVED', joinError: 'missing version' }), 'publish');
  assert.equal(model.stage({ status: 'REJECTED', revision: { status: 'REVIEW_REQUIRED' } }), 'history');
});

await check('Validation gate requires valid=true, zero hard conflicts and complete empty issues', () => {
  assert.equal(model.checked({ validationSummary: { valid: true, hardViolationCount: 0, issues: [] } }), true);
  for (const value of [null, {}, { valid: true }, { valid: true, issues: [] }, { valid: false, hardViolationCount: 0, issues: [] }, { valid: true, hardViolationCount: 1, issues: [] }, { valid: true, hardViolationCount: 0, issues: [{ code: 'BLOCKED' }] }]) {
    assert.equal(model.checked({ validationSummary: value }), false);
  }
});

await check('Mutation response confirms the exact plan, revision and requested terminal state', () => {
  const item = { planId: 'TEST-plan', revisionId: 'TEST-version' };
  const expected = { approve: 'APPROVED', reject: 'REVIEW_REQUIRED', publish: 'PUBLISHED' };
  for (const [kind, status] of Object.entries(expected)) {
    const response = { id: item.revisionId, planId: item.planId, status, publishedAt: kind === 'publish' ? '2026-09-07T02:30:00Z' : null };
    assert.equal(model.writeResult(response, item, kind), response);
    for (const invalid of [null, {}, { ...response, id: 'TEST-other-version' }, { ...response, planId: 'TEST-other-plan' }, { ...response, status: 'PENDING_APPROVAL' }]) {
      assert.throws(() => model.writeResult(invalid, item, kind));
    }
    if (kind === 'publish') {
      for (const publishedAt of [null, '', 'not-a-time']) assert.throws(() => model.writeResult({ ...response, publishedAt }, item, kind));
    }
  }
  assert.throws(() => model.writeResult({ id: item.revisionId, planId: item.planId, status: 'APPROVED' }, item, 'unknown'));
});

await check('Registration/submission date uses Beijing day across UTC midnight and rejects bad dates', () => {
  assert.equal(model.day('2026-09-06T23:45:00Z'), '2026-09-07');
  assert.equal(model.day('2026-09-06T15:59:59Z'), '2026-09-06');
  assert.equal(model.day('2026-09-06T16:00:00Z'), '2026-09-07');
  assert.equal(model.day(null), '');
  assert.equal(model.day('not-a-date'), '');
});

const snakeDuty = { session_external_code: 'AM-01', duty_role: 'MAIN', starts_at: '2026-09-07T00:30:00Z', ends_at: '2026-09-07T02:00:00Z', teacher_no: 'T016', status: 'ASSIGNED' };
const camelDuty = { sessionCode: 'AM-01', dutyRole: 'MAIN', startsAt: snakeDuty.starts_at, endsAt: snakeDuty.ends_at, teacherNo: 'T021', status: 'ASSIGNED' };
await check('Duty comparison consumes real snake_case and compatible camelCase without inventing room identity', () => {
  const diff = model.diff([snakeDuty], [camelDuty]);
  assert.equal(diff.changed.length, 1);
  assert.equal(diff.changed[0].before[0].teacher, 'T016');
  assert.equal(diff.changed[0].after[0].teacher, 'T021');
  assert.equal(diff.changed[0].room, '');
});

await check('Duty comparison is order independent and retains group multiplicity', () => {
  const second = { ...snakeDuty, teacher_no: 'T035' };
  assert.equal(model.diff([snakeDuty, second], [second, snakeDuty]).changed.length, 0);
  const changed = model.diff([snakeDuty, second], [camelDuty, second]);
  assert.deepEqual(plain(changed.changed[0].before.map(x => x.teacher).sort()), ['T016', 'T035']);
  assert.deepEqual(plain(changed.changed[0].after.map(x => x.teacher).sort()), ['T021', 'T035']);
  assert.equal(model.diff([snakeDuty, snakeDuty], [snakeDuty]).changed.length, 1);
});

await check('Missing duty context is counted as uncomparable instead of guessed', () => {
  const broken = [{ teacher_no: 'T016' }, { ...snakeDuty, starts_at: '' }];
  const diff = model.diff(broken, []);
  assert.equal(diff.uncomparable, 2);
  assert.equal(diff.changed.length, 0);
});

// DashboardIntegration/v2 replaces the historical one-commit byte allowlist.
// Cache tags and unrelated inline styles may evolve, but executable dependency
// identity, ordering, uniqueness, current V6 overrides and fixture isolation may not.
function dashboardIntegration(html) {
  const clean = html.replace(/<!--[\s\S]*?-->/g, '');
  const dependencies = [...clean.matchAll(/<(script|link)\b([^>]*)>/gi)].flatMap(match => {
    const attributes = Object.fromEntries([...match[2].matchAll(/([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)].map(attribute => [attribute[1].toLowerCase(), attribute[2] ?? attribute[3]]));
    const kind = match[1].toLowerCase();
    const raw = kind === 'script' ? attributes.src : attributes.rel === 'stylesheet' ? attributes.href : null;
    if (!raw) return [];
    const url = new URL(raw, 'http://dashboard.test/');
    return [{ kind, raw, attributes, source: match[0], offset: match.index, local: url.origin === 'http://dashboard.test', path: decodeURIComponent(url.pathname).replace(/^\//, '') }];
  });
  const requiredScripts = [
    'assets/vendor/react.18.3.1.development.js', 'assets/vendor/react-dom.18.3.1.development.js',
    'assets/vendor/babel-standalone.7.29.0.min.js', 'assets/vendor/gsap.3.13.0.min.js', 'assets/vendor/anime.4.5.0.umd.min.js',
    'src/glass-motion.js', 'src/icons.jsx', 'src/ui/glass-primitives.jsx', 'src/overlay.jsx', 'src/common.jsx',
    'src/ui/blueprint-v5.jsx', 'src/charts.jsx', 'src/navigation.js', 'src/onboarding.jsx', 'src/api.js',
    'src/pages/operations-workbench.jsx', ...jsxPaths, 'src/app.jsx', 'src/babel-script-scope.js',
  ];
  const requiredStyles = [
    'assets/fonts/geist/wght.css', 'assets/fonts/noto-sans-sc/wght.css',
    'src/styles/glass-system.css', 'src/styles/theme-system.css', 'src/styles/blueprint-v5.css', 'src/styles/operations-v5.css',
    'src/styles/operations-v6.css', 'src/styles/operations-v6-emergency.css', 'src/styles/frontend-polish.css', 'src/styles/onboarding-learning.css',
  ];
  const one = (relative, kind) => {
    const found = dependencies.filter(item => item.local && item.path === relative && item.kind === kind);
    assert.equal(found.length, 1, `${relative} must load exactly once as ${kind}`);
    assert.ok(fs.existsSync(path.join(root, relative)), `${relative} must exist locally`);
    return found[0];
  };
  const seen = new Set();
  for (const item of dependencies.filter(item => item.local)) {
    assert.ok(!seen.has(item.path), `Duplicate dependency after URL normalization: ${item.path}`); seen.add(item.path);
    if (/^(?:src|assets)\//.test(item.path)) assert.ok(fs.existsSync(path.join(root, item.path)), `Missing local dependency: ${item.path}`);
  }
  for (const relative of requiredScripts) {
    const item = one(relative, 'script');
    assert.equal(item.attributes.type || '', relative.endsWith('.jsx') ? 'text/babel' : '', `${relative} script type`);
    assert.doesNotMatch(item.source, /\s(?:async|defer)(?:\s|=|>)/i, `${relative} must preserve declared execution order`);
  }
  for (const relative of requiredStyles) one(relative, 'link');
  const before = (first, second) => assert.ok(one(first, first.endsWith('.css') ? 'link' : 'script').offset < one(second, second.endsWith('.css') ? 'link' : 'script').offset, `${first} must precede ${second}`);
  before('assets/vendor/react.18.3.1.development.js', 'assets/vendor/react-dom.18.3.1.development.js');
  before('assets/vendor/react-dom.18.3.1.development.js', 'assets/vendor/babel-standalone.7.29.0.min.js');
  before('assets/vendor/gsap.3.13.0.min.js', 'src/glass-motion.js');
  before('assets/vendor/anime.4.5.0.umd.min.js', 'src/glass-motion.js');
  for (const relative of ['src/overlay.jsx', 'src/ui/blueprint-v5.jsx', 'src/app.jsx']) before('src/glass-motion.js', relative);
  before('src/icons.jsx', 'src/common.jsx'); before('src/ui/glass-primitives.jsx', 'src/overlay.jsx');
  before('src/overlay.jsx', 'src/ui/blueprint-v5.jsx'); before('src/common.jsx', 'src/pages/operations-workbench.jsx');
  before('src/api.js', 'src/pages/operations-workbench.jsx'); before('src/navigation.js', 'src/app.jsx');
  for (const relative of jsxPaths.slice(1)) {
    before('src/pages/operations-workbench.jsx', relative); before('src/ui/operations-v6-assets.jsx', relative);
    before('src/ui/blueprint-v5.jsx', relative); before(relative, 'src/app.jsx');
  }
  for (const item of dependencies.filter(item => item.local && item.path.endsWith('.jsx'))) {
    before('assets/vendor/babel-standalone.7.29.0.min.js', item.path);
    before(item.path, 'src/babel-script-scope.js');
  }
  before('src/styles/blueprint-v5.css', 'src/styles/operations-v6.css');
  before('src/styles/operations-v5.css', 'src/styles/operations-v6.css');
  before('src/styles/operations-v6.css', 'src/styles/operations-v6-emergency.css');
  before('src/styles/operations-v6-emergency.css', 'src/styles/frontend-polish.css');
  assert.equal([...clean.matchAll(/\bid\s*=\s*["']root["']/g)].length, 1, 'One application mount root is required');
  assert.doesNotMatch(clean, /visual-fixtures\.js|visual-harness\.html/, 'Production entry must not load synthetic fixture code');
  assert.doesNotMatch(clean, /fonts\.googleapis\.com|fonts\.gstatic\.com/, 'Current font dependencies must stay local');
  return dependencies;
}

await check('DashboardIntegration/v2 keeps necessary local dependencies unique, ordered and fixture-free', () => {
  dashboardIntegration(read('Dashboard.html'));
});

await check('Declared production order installs actual V6 page functions into the existing navigation routes', () => {
  const dependencies = dashboardIntegration(read('Dashboard.html'));
  const relevant = new Set(['src/navigation.js', 'src/pages/operations-workbench.jsx', ...jsxPaths]);
  let requests = 0;
  const integration = vm.createContext({ window: { api: new Proxy({}, { get() { requests++; throw new Error('No business API during integration wiring check'); } }) }, console, Date, Intl });
  for (const dependency of dependencies.filter(item => relevant.has(item.path))) {
    vm.runInContext(Babel.transform(read(dependency.path), { presets: ['react'], filename: dependency.path }).code, integration);
  }
  const nav = integration.window.JKXTNavigation;
  assert.equal(nav.pageMap.emergency.mode, 'admin'); assert.equal(nav.pageMap.approvals.mode, 'admin');
  assert.equal(nav.pageMap.emergency.Comp(), vm.runInContext('Ops6EmergencyPage', integration), 'Emergency route uses V6, not the legacy override');
  assert.equal(nav.pageMap.approvals.Comp(), vm.runInContext('Ops6ApprovalPage', integration), 'Approval route uses V6');
  assert.equal(typeof integration.window.Ops6Asset, 'function');
  assert.equal(integration.window.Ops6ApprovalModel.stage({ status: 'APPROVED', revision: { status: 'PUBLISHED' } }), 'history');
  assert.equal(integration.window.Ops6EmergencyModel.keyRows([{ id: 'TEST-current-entry' }]).length, 1);
  assert.match(read('src/app.jsx'), /ReactDOM\.createRoot\(document\.getElementById\('root'\)\)/);
  assert.match(read('src/babel-script-scope.js'), /registerPlugin\('jkxt-script-scope'/);
  assert.equal(requests, 0);
});

await check('Harness is opaque-origin, connect-src none, TEST-labelled and loads real production files', () => {
  const html = read('artifacts/operations-v6-frontend/visual-harness.html');
  assert.match(html, /sandbox="allow-scripts"/);
  assert.doesNotMatch(html, /sandbox="[^"]*allow-same-origin/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /form-action 'none'/);
  assert.match(html, /credentials:'omit'/);
  assert.match(html, /TEST 合成资料/);
  for (const relative of jsxPaths) assert(html.includes('/' + relative));
  for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
    if (match[1].trim()) new vm.Script(match[1]);
  }
});

function fixtureHarness() {
  const listeners = {};
  const fixtureParent = { postMessage() {} };
  const fixtureWindow = { addEventListener(name, fn) { listeners[name] = fn; } };
  const fixtureContext = vm.createContext({
    window: fixtureWindow, parent: fixtureParent, navigator: {}, innerWidth: 1440, innerHeight: 900, setTimeout,
    document: { getElementById() { return {}; } },
    React: { createElement() { return {}; }, Fragment: 'Fragment' },
    ReactDOM: { createRoot() { return { render() {} }; } },
  });
  vm.runInContext(read('artifacts/operations-v6-frontend/visual-fixtures.js'), fixtureContext);
  fixtureWindow.VisualFixture.start();
  return { window: fixtureWindow, configure(state) { listeners.message({ source: fixtureParent, data: { type: 'visual-fixture-config', route: 'approvals', state } }); } };
}

await check('Real fixture starts with zero writes and six approval requests in separate version states', async () => {
  const { window: fx } = fixtureHarness();
  assert.equal(fx.VisualFixture.snapshot().mutations, 0);
  const approvals = await fx.api.operations.approvals();
  assert.equal(approvals.length, 6);
  assert.equal(approvals.filter(x => x.status === 'PENDING').length, 2);
  let waiting = 0;
  for (const item of approvals.filter(x => x.status === 'APPROVED')) {
    const revision = await fx.api.examPlans.revision(item.planId, item.revisionId);
    if (revision.status === 'APPROVED') waiting++;
  }
  assert.equal(waiting, 1);
  assert.equal(fx.VisualFixture.snapshot().mutations, 0);
});

await check('Real fixture duties have backend field casing, version-specific IDs, no fabricated room', async () => {
  const { window: fx } = fixtureHarness();
  const before = await fx.api.examPlans.assignments('TEST-plan-1-r3');
  const after = await fx.api.examPlans.assignments('TEST-plan-1-r4');
  assert.equal(before[0].teacher_no, 'T016');
  assert.equal(after[0].teacher_no, 'T021');
  assert.notEqual(before[0].teacher_id, after[0].teacher_id);
  assert.notEqual(before[0].session_id, after[0].session_id);
  assert(before.every(row => row.session_external_code && !('roomCode' in row) && !('room_code' in row)));
  assert.equal(model.diff(before, after).changed.length, 1);
});

await check('Fixture approval does not publish; explicit publication creates only pending notifications', async () => {
  const { window: fx } = fixtureHarness();
  const approved = await fx.api.examPlans.approve('TEST-plan-1', 'TEST-plan-1-r4', { reason: 'TEST 核查通过' }, { headers: { 'If-Match': '14' } });
  assert.equal(approved.status, 'APPROVED');
  assert.equal(approved.publishedAt, null);
  assert.equal(approved.revisionCursor, 15);
  assert.equal(fx.VisualFixture.snapshot().mutations, 1);
  assert.equal((await fx.api.get('/operations/notification-deliveries', { revisionId: approved.id })).length, 0);
  const published = await fx.api.examPlans.publish('TEST-plan-1', approved.id, { headers: { 'If-Match': '15' } });
  assert.equal(published.status, 'PUBLISHED');
  const deliveries = await fx.api.get('/operations/notification-deliveries', { revisionId: published.id });
  assert.equal(deliveries.length, 4);
  assert(deliveries.every(row => row.status === 'PENDING' && !row.deliveredAt && !row.readAt));
  assert.equal(fx.VisualFixture.snapshot().mutations, 2);
});

await check('Fixture self-review, blank opinion and stale revision reject with zero successful writes', async () => {
  const fixture = fixtureHarness();
  const fx = fixture.window;
  const headers = { headers: { 'If-Match': '14' } };
  for (const kind of ['approve', 'reject']) {
    fixture.configure('self');
    await assert.rejects(fx.api.examPlans[kind]('TEST-plan-1', 'TEST-plan-1-r4', { reason: 'TEST 核查' }, headers), error => error.code === 'APPROVAL_SELF_REVIEW_FORBIDDEN');
    assert.equal(fx.VisualFixture.snapshot().mutations, 0);
  }
  fixture.configure('normal');
  await assert.rejects(fx.api.examPlans.approve('TEST-plan-1', 'TEST-plan-1-r4', { reason: ' ' }, headers), error => error.status === 422);
  fixture.configure('stale');
  await assert.rejects(fx.api.examPlans.publish('TEST-plan-3', 'TEST-plan-3-r3', { headers: { 'If-Match': '13' } }), error => error.status === 409);
  assert.equal(fx.VisualFixture.snapshot().mutations, 0);
});

await check('Fixture empty and permission states stay distinct and networking is blocked', async () => {
  const fixture = fixtureHarness();
  fixture.configure('empty');
  assert.equal((await fixture.window.api.operations.approvals()).length, 0);
  assert.equal((await fixture.window.api.operations.emergencyChanges()).length, 0);
  assert.equal(fixture.window.VisualFixture.snapshot().mutations, 0);
  fixture.configure('permission');
  await assert.rejects(fixture.window.api.operations.approvals(), error => error.status === 403);
  await assert.rejects(fixture.window.fetch('/api/v1/anything'), error => error.status === 405);
  assert.equal(fixture.window.VisualFixture.snapshot().mutations, 0);
});

function reviewHarness(status = 'PENDING_APPROVAL', user = null, options = {}) {
  const hooks = [];
  let cursor = 0;
  const item = { id: 'TEST-approval', planId: 'TEST-plan', revisionId: 'TEST-revision', planName: 'TEST 计划' };
  const revision = { id: item.revisionId, planId: item.planId, revisionNo: 4, revisionCursor: 14, status, validationSummary: { valid: true, hardViolationCount: 0, issues: [] } };
  const history = [{ ...item, status: status === 'APPROVED' ? 'APPROVED' : 'PENDING', requestedBy: '10000000-0000-4000-8000-000000000001' }];
  const writes = [];
  const write = async (...args) => { writes.push(args); return Object.hasOwn(options, 'response') ? options.response : revision; };
  const browserWindow = { __jkxtMe: user ? { user, permissions: ['schedule:manage'] } : null, Ops6Asset: 'Ops6Asset', BlueprintDrawer: 'BlueprintDrawer', BlueprintDialog: 'BlueprintDialog', api: { examPlans: { revision: async () => revision, approve: write, reject: write, publish: write } } };
  const jsxContext = vm.createContext({
    window: browserWindow, console, Date, Intl, PageShell: 'PageShell', Button: 'Button', orgName: () => 'TEST 学校',
    requestAnimationFrame: fn => fn(),
    useLiveResource: () => [{ status: 'ready', data: { revision, history } }, async () => {}],
    React: {
      createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }), Fragment: 'Fragment',
      useState(initial) { const index = cursor++; if (!(index in hooks)) hooks[index] = typeof initial === 'function' ? initial() : initial; return [hooks[index], next => { hooks[index] = typeof next === 'function' ? next(hooks[index]) : next; }]; },
      useRef(initial) { const index = cursor++; if (!(index in hooks)) hooks[index] = { current: initial }; return hooks[index]; },
      useCallback: fn => fn, useEffect() {}, useLayoutEffect() {},
    },
  });
  vm.runInContext(compiled, jsxContext);
  return { writes, render() { cursor = 0; return vm.runInContext('Ops6ApprovalReview', jsxContext)({ item, onBack() {} }); } };
}
const nodes = value => !value || typeof value !== 'object' ? [] : Array.isArray(value) ? value.flatMap(nodes) : [value, ...nodes(value.props?.children)];
const content = value => typeof value === 'string' || typeof value === 'number' ? String(value) : Array.isArray(value) ? value.map(content).join('') : value && typeof value === 'object' ? content(value.props?.children) : '';
const button = (tree, label) => nodes(tree).find(node => node.type === 'Button' && content(node) === label);

await check('Actual approval component with missing identity blocks approval and rejection even after opinion entry', () => {
  const harness = reviewHarness();
  let tree = harness.render();
  const textarea = nodes(tree).find(node => node.type === 'textarea');
  assert(textarea, 'opinion textarea exists');
  textarea.props.onChange({ target: { value: 'TEST 已核对' } });
  tree = harness.render();
  assert.equal(button(tree, '批准此版本')?.props.disabled, true);
  assert.equal(button(tree, '退回修改')?.props.disabled, true);
  assert.equal(harness.writes.length, 0);
});

await check('Actual publish preparation with missing identity cannot open a publication confirmation', () => {
  const harness = reviewHarness('APPROVED');
  const tree = harness.render();
  assert.equal(button(tree, '发布此版本')?.props.disabled, true);
  assert.equal(harness.writes.length, 0);
});

await check('Actual approval and rejection reject malformed success payloads without announcing business success or permitting a repeat', async () => {
  for (const kind of ['approve', 'reject']) {
    const harness = reviewHarness('PENDING_APPROVAL', { id: '10000000-0000-4000-8000-000000000002', displayName: 'TEST 王老师' }, { response: {} });
    let tree = harness.render();
    nodes(tree).find(node => node.type === 'textarea').props.onChange({ target: { value: 'TEST 已核对' } });
    tree = harness.render();
    button(tree, kind === 'approve' ? '批准此版本' : '退回修改').props.onClick();
    tree = harness.render();
    await button(tree, kind === 'approve' ? '确认批准' : '确认退回').props.onClick();
    tree = harness.render();
    assert.equal(harness.writes.length, 1);
    assert.match(content(tree), /请求结果或版本状态需要核对/);
    assert.doesNotMatch(content(tree), /此版本已批准，仍需单独确认发布|审批已退回，请在来源版本修改/);
    assert.equal(button(tree, kind === 'approve' ? '确认批准' : '确认退回').props.disabled, true);
  }
});

await check('Actual publication rejects malformed success payload, remains uncertain and requires re-check', async () => {
  const harness = reviewHarness('APPROVED', { id: '10000000-0000-4000-8000-000000000002', displayName: 'TEST 王老师' }, { response: {} });
  let tree = harness.render();
  button(tree, '发布此版本').props.onClick();
  tree = harness.render();
  nodes(tree).find(node => node.type === 'input' && node.props.type === 'checkbox').props.onChange({ target: { checked: true } });
  tree = harness.render();
  let dialog = nodes(tree).find(node => node.type === 'BlueprintDialog');
  assert.equal(button(dialog.props.footer, '确认发布').props.disabled, false);
  await button(dialog.props.footer, '确认发布').props.onClick();
  tree = harness.render();
  dialog = nodes(tree).find(node => node.type === 'BlueprintDialog');
  assert.equal(harness.writes.length, 1);
  assert.match(content(tree), /请求结果或版本状态需要核对/);
  assert.doesNotMatch(content(tree), /发布请求已成功返回/);
  assert.equal(button(dialog.props.footer, '确认发布').props.disabled, true);
});

const report = {
  schema: 'OperationsV6OfflineChecks/v1', startedAt, finishedAt: new Date().toISOString(), cwd: root,
  scope: 'Offline production-model, actual-component guard and isolated-fixture checks; no live API calls',
  passed: checks.filter(result => result.pass).length, total: checks.length, checks, inputHashes,
  notCovered: ['Browser layout and visual alignment', 'Keyboard focus and actual DOM accessibility', 'Responsive rendering and reduced-motion observation', 'Live backend authorization or business-write success', 'Formal Supervisor acceptance'],
};
const resultPath = path.join(root, 'artifacts/operations-v6-frontend/model-test-results.json');
fs.writeFileSync(resultPath, JSON.stringify(report, null, 2) + '\n');
for (const result of checks) console.log(`${result.pass ? 'PASS' : 'FAIL'} ${result.name}${result.error ? ': ' + result.error : ''}`);
console.log(`${report.passed}/${report.total} offline checks passed; output ${resultPath}`);
if (report.passed !== report.total) process.exitCode = 1;
