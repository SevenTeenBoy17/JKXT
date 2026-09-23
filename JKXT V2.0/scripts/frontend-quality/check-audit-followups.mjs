import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const checks = [];
const plain = value => JSON.parse(JSON.stringify(value));
const nodes = value => !value || typeof value !== 'object' ? [] : Array.isArray(value)
  ? value.flatMap(nodes) : [value, ...nodes(value.props?.children)];
const textOf = value => typeof value === 'string' || typeof value === 'number' ? String(value)
  : Array.isArray(value) ? value.map(textOf).join('') : value && typeof value === 'object' ? textOf(value.props?.children) : '';
function load(file, overrides = {}) {
  const source = read(file);
  const icons = Object.fromEntries([...source.matchAll(/\bIcon\w+\b/g)].map(match => [match[0], match[0]]));
  const context = vm.createContext({ window: {}, console, ...icons,
    React: { createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }) },
    PageShell: 'PageShell', SectionTitle: 'SectionTitle', Tag: 'Tag', LineChartJS: 'LineChartJS',
    ...overrides,
  });
  vm.runInContext(Babel.transform(source, { presets: ['react'] }).code, context);
  return { context, run: code => vm.runInContext(code, context) };
}
async function check(name, fn) {
  try { await fn(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 600) }); }
}

const kpi = load('src/kpis.jsx');
await check('running tasks use latest job state and exclude done, failed, draft and cancelled', () => {
  kpi.context.fixture = [
    { id: 'a', status: 'scheduled', latestJob: { status: 'succeeded' } },
    { id: 'b', status: 'published' }, { id: 'c', status: 'failed' },
    { id: 'd', status: 'draft' }, { id: 'e', status: 'cancelled' },
    { id: 'f', status: 'scheduling', latestJob: { status: 'failed' } },
    { id: 'g', status: 'ready', latestJob: { status: 'running' } },
    { id: 'h', status: 'scheduling' }, { id: 'i', latestJob: { status: 'pending' } },
  ];
  const metrics = plain(kpi.run('dashboardMetrics([], [], fixture, {totals:{count:12}})'));
  assert.equal(metrics.tasks, 9);
  assert.equal(metrics.runningTasks, 3);
  assert.equal(metrics.sessions, 12);
});

await check('resource cards count only actual active teachers and available room records', () => {
  assert.deepEqual(plain(kpi.run(`dashboardMetrics(
    [{id:'1',status:'active'},{id:'2',status:'left'},{id:'3',status:'disabled'}],
    {rows:[{uuid:'1',status:'active'},{uuid:'2',status:'maintenance'},{uuid:'3',status:'disabled'},{uuid:'4',status:'available'}]},
    [], {totals:{count:0}})`)), { teachers: 1, classrooms: 2, tasks: 0, runningTasks: 0, sessions: 0 });
});

await check('missing or malformed responses remain unknown instead of fabricated zero', () => {
  assert.deepEqual(plain(kpi.run('dashboardMetrics(null, {}, {rows:[null]}, {totals:{count:null}})')),
    { teachers: null, classrooms: null, tasks: null, runningTasks: null, sessions: null });
  assert.equal(kpi.run('dashboardMetrics([],[],[],{totals:{count:""}}).sessions'), null);
  assert.equal(kpi.run('dashboardMetrics([],[],[],{totals:{count:-1}}).sessions'), null);
});

await check('actual KPI effect rejected APIs render em dashes and visible failure captions', async () => {
  let value = null;
  let effectRuns = 0;
  const page = load('src/kpis.jsx');
  page.context.window.api = { get: async () => { throw new Error('synthetic offline'); } };
  page.context.React.useState = () => [value, next => { value = next; }];
  page.context.React.useEffect = effect => { if (!effectRuns++) effect(); };
  page.run('KPIRow()');
  await new Promise(resolve => setImmediate(resolve));
  const cards = nodes(page.run('KPIRow()')).filter(node => node.props.glyph);
  assert.equal(cards.length, 4);
  for (const card of cards) {
    assert.equal(card.props.value, '—');
    assert.equal(card.props.sub2, '读取失败，请刷新重试');
  }
});

await check('KPI unmount still suppresses late responses', async () => {
  let resolve;
  let writes = 0;
  let cleanup;
  const pending = new Promise(done => { resolve = done; });
  const page = load('src/kpis.jsx');
  page.context.window.api = { get: () => pending };
  page.context.React.useState = () => [null, () => { writes += 1; }];
  page.context.React.useEffect = effect => { cleanup = effect(); };
  page.run('KPIRow()');
  cleanup();
  resolve([]);
  await new Promise(done => setImmediate(done));
  assert.equal(writes, 0);
});

const history = load('src/pages/history.jsx');
await check('one month/null growth never becomes +0% or an August comparison', () => {
  history.context.fixture = { labels: ['2026-06'], data: [2], trendPct: null };
  const view = plain(history.run('historyTrendPresentation(fixture)'));
  assert.equal(view.subtitle, '2026 年 6 月 · 监考人次');
  assert.equal(view.trendPct, null);
  const tree = history.run('MonthlyTrend({trend:fixture})');
  const title = nodes(tree).find(node => node.type === 'SectionTitle');
  assert.equal(title.props.right, null);
  const chart = nodes(tree).find(node => node.type === 'LineChartJS');
  assert.equal(chart.props.unit, ' 人次');
});

await check('multi-month comparison names actual first and last months with numeric zero preserved', () => {
  const view = plain(history.run('historyTrendPresentation({labels:["2025-12","2026-02"],data:[6,6],trendPct:0})'));
  assert.equal(view.trendPct, 0);
  assert.equal(view.comparison, '2026 年 2 月较2025 年 12 月');
  assert.equal(history.run('historyTrendPresentation({labels:["2026-01","2026-02"],data:[0,7],trendPct:100}).trendPct'), null);
});

await check('history report copy reflects latest succeeded run, not a fixed term or publication claim', () => {
  const source = read('src/pages/history.jsx');
  assert.match(source, /badge="最近一次成功排班"/);
  assert.match(source, /不是全学期累计，也不代表已发布结果/);
  assert.doesNotMatch(source, /sub="本学期 8 - 11 月监考量"|较 8 月|>切换学期</);
  assert.match(read('src/kpis.jsx'), /label="最近一次成功排班"/);
});

const rooms = load('src/pages/classrooms.jsx');
await check('legacy capacity-derived usage is never normalized as measured occupancy', () => {
  for (const usage of [0, 54, 92, null, undefined]) {
    rooms.context.fixture = { uuid: 'synthetic-room', code: 'QA-ROOM', examCapacity: 36, capacity: 48, usage, status: 'active' };
    const row = plain(rooms.run('normalizeClassroomRow(fixture,0)'));
    assert.equal(row.usage, null);
    assert.equal(row.examCapacity, 36);
    assert.equal(row.status, 'available');
  }
  const source = read('src/pages/classrooms.jsx');
  assert.match(source, /未接入统计/);
  assert.match(source, /按档案状态统计，非实时空闲/);
  assert.doesNotMatch(source, /r\.usage\}%|label="平均占用率"/);
});

await check('maintenance, disabled and unknown rooms never become available', () => {
  for (const [status, expected] of [['maintenance', 'maintenance'], ['disabled', 'disabled'], ['unexpected', 'unknown']]) {
    rooms.context.fixture = { status };
    assert.equal(rooms.run('normalizeClassroomRow(fixture,0).status'), expected);
  }
});

await check('scoped layout CSS retains native widget scrolling and readable AI actions', () => {
  const css = read('src/styles/usability-20260905.css');
  assert.match(css, /\.app-shell \.qk-step-card\s*\{[^}]*max-width:\s*100%;[^}]*overflow-x:\s*auto;/);
  assert.match(css, /\.app-shell \.filter-bar\s*\{[^}]*min-width:\s*0;[^}]*width:\s*100%;/);
  assert.match(css, /\.app-shell \.filter-bar > \.inline-flex\s*\{\s*flex-wrap:\s*wrap;/);
  assert.match(css, /\.ai-policy-form button:not\(\[role="switch"\]\)\s*\{[^}]*font-size:\s*\.875rem;/);
  assert.match(css, /\.ai-policy-form div:is\([^}]+font-size:\s*\.75rem;/);
  assert.doesNotMatch(css, /(?:html|body|\.login)[^{]*\{[^}]*overflow:\s*hidden/);
});

console.log(JSON.stringify({ contract: 'AuditFollowups/v1', checks, passed: checks.filter(check => check.pass).length, total: checks.length,
  limits: 'CSS assertions are regression guards, not browser layout proof; live Chrome verification is reported separately.' }, null, 2));
if (checks.some(check => !check.pass)) process.exitCode = 1;
