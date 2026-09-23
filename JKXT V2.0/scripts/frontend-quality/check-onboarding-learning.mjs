import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/onboarding.jsx'), 'utf8');
const css = fs.readFileSync(path.join(root, 'src/styles/onboarding-learning.css'), 'utf8');
const dashboard = fs.readFileSync(path.join(root, 'src/pages/dashboard.jsx'), 'utf8');
const navigation = fs.readFileSync(path.join(root, 'src/navigation.js'), 'utf8');
const store = new Map();
const profile = { user: { id: 'teacher-001' }, organization: { id: 'school-001' } };
let hooks = [], cursor = 0, effects = [], storageBlocked = false, writes = 0, apiWrites = 0;
let appNodes = [], cancelledFrames = [], pendingFrames = new Map(), frameId = 0;
let layoutCursor = 0, layoutRecords = [], pendingLayouts = [], attachedRefs = new Set();
const windowMock = {
  __jkxtMe: profile, innerWidth: 1280, innerHeight: 900,
  api: { post: () => { apiWrites++; return Promise.resolve({}); } },
  ui: { confirm: async () => false },
  addEventListener() {}, removeEventListener() {},
};
const documentMock = {
  activeElement: { isConnected: true, focus() {} }, body: { style: { overflow: 'clip' } },
  documentElement: { isConnected: true },
  querySelectorAll: () => appNodes,
  querySelector: () => null,
  addEventListener() {}, removeEventListener() {},
};
const context = vm.createContext({
  window: windowMock, document: documentMock, localStorage: {
    getItem: (key) => { if (storageBlocked) throw new Error('storage disabled'); return store.get(key) || null; },
    setItem: (key, value) => { if (storageBlocked) throw new Error('storage disabled'); writes++; store.set(key, value); },
  },
  React: {
    createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }),
    useState: (initial) => { const index = cursor++; if (!(index in hooks)) hooks[index] = typeof initial === 'function' ? initial() : initial; return [hooks[index], (value) => { hooks[index] = typeof value === 'function' ? value(hooks[index]) : value; }]; },
    useRef: (value) => { const index = cursor++; if (!(index in hooks)) hooks[index] = { current: value }; return hooks[index]; },
    useEffect: (callback) => effects.push(callback),
    useLayoutEffect: (callback, dependencies) => {
      const index = layoutCursor++, previous = layoutRecords[index];
      if (!previous || !dependencies || !previous.dependencies || dependencies.some((value, offset) => !Object.is(value, previous.dependencies[offset])) || dependencies.length !== previous.dependencies.length) {
        pendingLayouts.push(() => {
          previous?.cleanup?.();
          layoutRecords[index] = { dependencies, cleanup: callback() };
        });
      }
    },
  },
  Button: 'Button', IconCheck: 'IconCheck', ProductGlyph: 'ProductGlyph',
  PRODUCT_ICON_COMPONENTS: { calendar: 'Calendar', upload: 'Upload', tasks: 'Tasks' },
  Date, Map, Set, JSON, Math, String, Boolean, Number, console,
  requestAnimationFrame: (callback) => { const id = ++frameId; pendingFrames.set(id, callback); return id; },
  cancelAnimationFrame: (id) => { cancelledFrames.push(id); pendingFrames.delete(id); },
  setTimeout, clearTimeout,
  performance: { now: () => 0 },
  getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
  matchMedia: () => ({ matches: true }),
});
vm.runInContext(Babel.transform(source, { presets: ['react'] }).code, context);
const evaluate = (code) => vm.runInContext(code, context);
context.testProfile = profile;
const plain = (value) => JSON.parse(JSON.stringify(value));
const checks = [];
async function check(name, run) {
  try { await run(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.stack.slice(0, 700) }); }
}
function render(expression = 'OnboardingLearningCard({profile:testProfile})', fresh = false) {
  if (fresh) {
    for (const record of layoutRecords) record?.cleanup?.();
    for (const ref of attachedRefs) { if (ref.current) ref.current.isConnected = false; ref.current = null; }
    hooks = []; layoutRecords = []; attachedRefs = new Set();
  }
  cursor = 0; layoutCursor = 0; effects = []; pendingLayouts = [];
  const tree = evaluate(expression);
  // Commit real element-shaped refs before layout effects, including disabled state.
  for (const node of nodes(tree)) {
    const ref = node.props?.ref;
    if (!ref || typeof ref !== 'object') continue;
    if (!ref.current) ref.current = {
      style: {}, isConnected: true,
      getClientRects: () => [{}], closest: () => null,
      querySelectorAll: () => [], contains(candidate) { return candidate === this; },
      focus() { if (!this.disabled && this.isConnected) documentMock.activeElement = this; },
    };
    ref.current.disabled = !!node.props.disabled;
    ref.current.isConnected = true;
    attachedRefs.add(ref);
  }
  for (const commit of pendingLayouts) commit();
  return tree;
}
function nodes(value) {
  if (!value || typeof value !== 'object') return [];
  if (Array.isArray(value)) return value.flatMap(nodes);
  return [value, ...nodes(value.props?.children)];
}
function textOf(value) {
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(textOf).join('');
  return value && typeof value === 'object' ? textOf(value.props?.children) : '';
}
const button = (tree, label) => nodes(tree).find((node) => ['button', 'Button'].includes(node.type) && textOf(node.props.children) === label);
const storageKey = () => evaluate('learningStorageKey(testProfile)');
function resetHarness() {
  store.clear(); hooks = []; apiWrites = 0; writes = 0; storageBlocked = false;
  documentMock.activeElement = documentMock.body;
  delete windowMock.JKXTDialogLayers;
}

await check('owned JSX files compile independently', () => {
  Babel.transform(dashboard, { presets: ['react'] });
  assert.ok(dashboard.includes('JKXTLearningGuides?.storageKey'));
});
await check('teaching paths stay separate and only reference existing navigation routes', () => {
  const paths = plain(evaluate('JKXT_LEARNING_PATHS'));
  assert.deepEqual(paths.map((item) => item.id), ['quick', 'plan']);
  assert.equal(paths.every((item) => item.steps.length === 4), true);
  for (const item of paths) for (const step of item.steps) assert.match(navigation, new RegExp(`['"]?${step.route}['"]?\\s*:`), step.route);
  assert.equal(paths[0].steps.some((step) => ['approvals', 'exam-plans'].includes(step.route)), false);
  assert.match(JSON.stringify(paths), /不会自动同步修改/);
  assert.match(JSON.stringify(paths), /发布不等于通知已送达/);
});
await check('learning storage isolates account, school and mode with collision-safe encoding', () => {
  const base = storageKey();
  assert.notEqual(base, evaluate('learningStorageKey({...testProfile,user:{id:"teacher-002"}})'));
  assert.notEqual(base, evaluate('learningStorageKey({...testProfile,organization:{id:"school-002"}})'));
  assert.notEqual(base, evaluate('learningStorageKey(testProfile,"super")'));
  assert.notEqual(evaluate('learningStorageKey({user:{id:"a:b"},organization:{id:"c"}})'), evaluate('learningStorageKey({user:{id:"b"},organization:{id:"c:a"}})'));
  assert.notEqual(evaluate('learningStorageKey({user:{id:"中文甲"},organization:{id:"本校"}})'), evaluate('learningStorageKey({user:{id:"中文乙"},organization:{id:"本校"}})'));
  assert.equal(evaluate('learningStorageKey({user:{id:"u"}})'), null);
});
await check('unknown versions, malformed storage and unsupported learning ids recover safely', () => {
  const initial = plain(evaluate('normalizeLearningState(null)'));
  assert.deepEqual(plain(evaluate('normalizeLearningState({version:"future",learnedIds:["quick-prepare"]})')), initial);
  const filtered = plain(evaluate('normalizeLearningState({version:"v1",pathId:"plan",selectedId:"quick-review",learnedIds:["not-real","quick-prepare","quick-prepare"]})'));
  assert.deepEqual(filtered.learnedIds, ['quick-prepare']);
  assert.equal(filtered.selectedId, 'plan-prepare');
  store.set(storageKey(), '{invalid');
  assert.deepEqual(plain(evaluate('readLearningState(testProfile)')), initial);
});
await check('acknowledging a lesson is idempotent and never completes a different path', () => {
  const learned = plain(evaluate('updateLearningState(updateLearningState(null,{type:"learn",id:"quick-prepare"}),{type:"learn",id:"quick-prepare"})'));
  assert.deepEqual(learned.learnedIds, ['quick-prepare']);
  assert.deepEqual(plain(evaluate('updateLearningState(null,{type:"learn",id:"plan-publish"}).learnedIds')), []);
});
await check('returning users get a compact entry without fabricated learning credit', () => {
  resetHarness();
  const state = plain(evaluate('readLearningState({...testProfile,onboarded:true})'));
  assert.equal(state.collapsed, true); assert.deepEqual(state.learnedIds, []);
});
await check('actual lesson control records local confirmation and renders honest progress', () => {
  resetHarness(); let tree = render(undefined, true);
  button(tree, '我已了解').props.onClick(); tree = render();
  assert.deepEqual(JSON.parse(store.get(storageKey())).learnedIds, ['quick-prepare']);
  assert.match(textOf(tree), /学习 1\/4/);
  assert.match(textOf(tree), /不代表业务办理完成/);
  assert.equal(button(tree, '已了解本步骤').props.disabled, true);
  assert.equal(apiWrites, 0);
});
await check('each open-page button navigates without writing business data', () => {
  resetHarness(); const destinations = []; windowMock.navigateTo = (route) => destinations.push(route);
  let tree = render(undefined, true); button(tree, '打开排班工作台').props.onClick();
  button(tree, '联合考试计划').props.onClick(); tree = render();
  button(tree, '打开考试计划').props.onClick();
  assert.deepEqual(destinations, ['quick', 'exam-plans']); assert.equal(apiWrites, 0);
});
await check('branch selection and collapsed state persist, later continue restores the selected lesson', () => {
  resetHarness(); let tree = render(undefined, true);
  button(tree, '联合考试计划').props.onClick(); tree = render();
  const publishStep = nodes(tree).find((node) => node.type === 'button' && textOf(node).includes('审查与发布'));
  publishStep.props.onClick(); tree = render();
  button(tree, '稍后继续').props.onClick(); tree = render(undefined, true);
  assert.equal(tree.props.open, false);
  assert.equal(JSON.parse(store.get(storageKey())).selectedId, 'plan-publish');
  assert.match(textOf(tree), /继续学习/);
  tree.props.onToggle({ currentTarget: { open: true } }); tree = render();
  assert.equal(tree.props.open, true); assert.equal(button(tree, '打开审批与发布') !== undefined, true);
});
await check('cancelled reset retains learning; confirmed reset clears both paths but not tour state', async () => {
  resetHarness(); let tree = render(undefined, true); button(tree, '我已了解').props.onClick(); tree = render();
  const tourKey = evaluate('tourStorageKey(testProfile)'); store.set(tourKey, JSON.stringify({ status: 'completed' }));
  const before = store.get(storageKey());
  const settleReset = async (confirmed) => {
    let settle;
    windowMock.ui.confirm = () => new Promise((resolve) => { settle = resolve; });
    const trigger = button(tree, '重置学习记录').props.ref.current;
    trigger.focus();
    const pending = button(tree, '重置学习记录').props.onClick();
    tree = render();
    assert.equal(button(tree, '等待确认…').props.disabled, true);
    documentMock.activeElement = documentMock.body;
    trigger.focus(); assert.equal(documentMock.activeElement, documentMock.body, 'disabled trigger cannot receive generic overlay focus restoration');
    settle(confirmed); await pending;
    assert.equal(documentMock.activeElement, documentMock.body, 'focus must wait for enabled commit');
    tree = render();
    assert.equal(button(tree, '重置学习记录').props.disabled, false);
    assert.equal(documentMock.activeElement, trigger, 'reset confirmation returns focus after enabled layout commit');
  };
  await settleReset(false); assert.equal(store.get(storageKey()), before);
  await settleReset(true);
  assert.deepEqual(JSON.parse(store.get(storageKey())).learnedIds, []);
  assert.equal(JSON.parse(store.get(tourKey)).status, 'completed'); assert.equal(apiWrites, 0);
});
await check('unavailable confirmation does not silently reset', async () => {
  resetHarness(); let tree = render(undefined, true); button(tree, '我已了解').props.onClick(); tree = render();
  windowMock.ui.confirm = undefined; await button(tree, '重置学习记录').props.onClick(); tree = render();
  assert.equal(JSON.parse(store.get(storageKey())).learnedIds.length, 1);
  assert.match(textOf(tree), /学习记录保持不变/);
});
await check('blocked localStorage and missing identity show an honest session-only fallback', () => {
  resetHarness(); storageBlocked = true; let tree = render(undefined, true); button(tree, '我已了解').props.onClick(); tree = render();
  assert.match(textOf(tree), /当前浏览器无法保存/); assert.match(textOf(tree), /学习 1\/4/); assert.equal(writes, 0);
  storageBlocked = false; assert.equal(evaluate('writeLearningState({},"admin",normalizeLearningState(null))'), false);
});
await check('ring delegates Anime motion with cleanup, and synchronously falls back without engine', () => {
  resetHarness(); let cleanupCount = 0, invocation = null;
  windowMock.JKXTMotion = { animateLearning: (...args) => { invocation = args; return () => { cleanupCount++; }; } };
  const tree = render(undefined, true);
  const circle = nodes(tree).find((node) => node.type === 'circle' && node.props.pathLength === '100');
  const ring = circle.props.ref.current;
  assert.ok(ring, 'rendered progress circle has its committed ref');
  const cleanup = effects[1](); assert.equal(invocation[0], ring); assert.equal(invocation[2], 0);
  cleanup(); assert.equal(cleanupCount, 1);
  delete windowMock.JKXTMotion; effects[1](); assert.equal(ring.style.strokeDashoffset, '100');
});
await check('paused spotlight resumes by stable id; completed/replayed tour starts normally', () => {
  resetHarness(); store.set(evaluate('tourStorageKey(testProfile)'), JSON.stringify({ status: 'paused', lastStepId: 'global-search' }));
  const tree = render('OnboardingModal({me:testProfile,mode:"admin",source:"help"})', true);
  assert.ok(button(tree, '继续第 4 步'));
  assert.equal(evaluate('resumeTourStepIndex({status:"completed",lastStepId:"global-search"},ADMIN_TOUR_STEPS)'), 0);
  assert.equal(evaluate('resumeTourStepIndex({status:"paused",lastStepId:"removed"},ADMIN_TOUR_STEPS)'), 0);
  assert.equal(evaluate('ADMIN_TOUR_STEPS[0].id'), 'term-context');
});
await check('closing spotlight restores exact original aria-hidden, inert and body overflow', () => {
  const makeNode = (initial) => {
    const attributes = new Map(Object.entries(initial));
    return { attributes, getAttribute: (key) => attributes.has(key) ? attributes.get(key) : null,
      setAttribute: (key, value) => attributes.set(key, value), removeAttribute: (key) => attributes.delete(key),
      get inert() { return attributes.has('inert'); }, set inert(value) { if (value) attributes.set('inert', ''); else attributes.delete('inert'); } };
  };
  appNodes = [makeNode({ 'aria-hidden': 'false' }), makeNode({ 'aria-hidden': 'true', inert: 'inert' }), makeNode({})];
  const before = appNodes.map((node) => [...node.attributes]);
  render('OnboardingModal({me:testProfile,mode:"admin",source:"help"})', true);
  const cleanup = effects[0](); assert.equal(documentMock.body.style.overflow, 'hidden'); cleanup();
  assert.deepEqual(appNodes.map((node) => [...node.attributes]), before);
  assert.equal(documentMock.body.style.overflow, 'clip'); appNodes = [];
});
await check('spotlight position frame is tracked and cancelled on cleanup', () => {
  pendingFrames = new Map(); cancelledFrames = [];
  const target = { getBoundingClientRect: () => ({ top: 20, bottom: 64, left: 300, right: 450, width: 150, height: 44 }) };
  documentMock.querySelector = (selector) => selector === '.app-shell' ? { dataset: { pageId: 'dashboard' } } : target;
  render('OnboardingModal({me:testProfile,mode:"admin",activePage:"dashboard"})', true); hooks[0] = 'tour';
  render('OnboardingModal({me:testProfile,mode:"admin",activePage:"dashboard"})');
  const cleanup = effects[3](); assert.equal(pendingFrames.size, 1); cleanup(); assert.equal(pendingFrames.size, 0);
  assert.ok(cancelledFrames.length); documentMock.querySelector = () => null;
});
await check('teaching uses real text, keyboard controls, narrow-screen layout and reduced-motion guard', () => {
  resetHarness(); const tree = render(undefined, true);
  assert.equal(tree.type, 'details'); assert.ok(nodes(tree).find((node) => node.type === 'summary'));
  const circle = nodes(tree).find((node) => node.type === 'circle' && node.props.pathLength === '100'); assert.ok(circle);
  assert.match(css, /prefers-reduced-motion: no-preference/);
  assert.match(css, /:focus-visible/); assert.match(css, /min-height: 44px/);
  assert.match(css, /@media \(max-width: 680px\)/); assert.match(css, /grid-template-columns: 1fr/);
  const learningSource = source.slice(source.indexOf('const JKXT_LEARNING_VERSION'));
  assert.doesNotMatch(learningSource, /window\.api|fetch\(|\/auth\/me\/onboarded|demoData|setInterval/);
  assert.doesNotMatch(learningSource, /github-readme-stats|api\.github\.com/);
});

const result = { suite: 'onboarding-learning-v1', checks, passed: checks.filter((check) => check.pass).length, total: checks.length };
console.log(JSON.stringify(result, null, 2));
if (result.passed !== result.total) process.exitCode = 1;
