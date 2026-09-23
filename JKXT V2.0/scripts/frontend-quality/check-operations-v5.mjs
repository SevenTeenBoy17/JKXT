import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/operations-workbench.jsx'), 'utf8');
const checks = [];
const routes = [];
const sources = [];
let hooks = [], cursor = 0, effects = [], resource = { status: 'loading', data: null }, reloads = 0;
const context = vm.createContext({
  console, Intl, Date,
  window: {
    navigateTo: route => routes.push(route),
    BlueprintAsset: 'BlueprintAsset', BlueprintDrawer: 'BlueprintDrawer',
    JKXTPlanNavigation: { open: target => sources.push(target) },
  },
  React: {
    createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }),
    Fragment: 'Fragment',
    useCallback: fn => fn,
    useState: initial => {
      const index = cursor++;
      if (!(index in hooks)) hooks[index] = typeof initial === 'function' ? initial() : initial;
      return [hooks[index], next => { hooks[index] = typeof next === 'function' ? next(hooks[index]) : next; }];
    },
    useRef: initial => {
      const index = cursor++;
      if (!(index in hooks)) hooks[index] = { current: initial };
      return hooks[index];
    },
    useEffect: fn => {
      const index = cursor++;
      if (!(index in hooks)) { hooks[index] = true; effects.push(fn); }
    },
  },
  PageShell: 'PageShell', Button: 'Button', ResourceState: 'ResourceState',
  IconChevron: 'IconChevron', IconRefresh: 'IconRefresh', IconSearch: 'IconSearch',
  IconClock: 'IconClock', IconHelp: 'IconHelp', IconAlert: 'IconAlert', IconBuilding: 'IconBuilding',
  orgName: () => '合成测试学校',
  useLiveResource: () => [resource, () => { reloads++; }],
  OPERATIONAL_ISSUE_LABELS: {},
  OPERATIONAL_TOTAL_LABELS: { plans: '未归档计划', activeEmergencies: '未处理应急变更' },
});
vm.runInContext(Babel.transform(source, { presets: ['react'], filename: 'operations-workbench.jsx' }).code, context);
const run = code => vm.runInContext(code, context);
const plain = value => JSON.parse(JSON.stringify(value));
const nodes = value => !value || typeof value !== 'object' ? [] : Array.isArray(value)
  ? value.flatMap(nodes) : [value, ...nodes(value.props?.children)];
const text = value => typeof value === 'string' || typeof value === 'number' ? String(value) : Array.isArray(value)
  ? value.map(text).join('') : value && typeof value === 'object' ? text(value.props?.children) : '';
function expand(value) {
  if (!value || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map(expand);
  if (typeof value.type === 'function') return expand(value.type(value.props));
  return { ...value, props: { ...value.props, children: expand(value.props?.children) } };
}
function render(page) {
  cursor = 0;
  const tree = expand(run(`${page}()`));
  effects.splice(0).forEach(fn => fn());
  return tree;
}
function reset(data, status = 'ready') {
  hooks = []; cursor = 0; effects = []; routes.length = 0; sources.length = 0;
  resource = { status, data, error: status === 'error' ? new Error('isolated test failure') : null };
  context.window.__jkxtEmergencyReturnId = '';
}
function check(name, fn) {
  try { fn(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message }); }
}

check('risk projection keeps aggregate counts and exact object destinations', () => {
  context.issue = { code: 'ACTIVE_EMERGENCIES', count: 13, message: 'raw backend record' };
  const value = plain(run('opsRiskView(issue)'));
  assert.match(value.title, /记录需复核/);
  assert.doesNotMatch(value.title + value.description, /未处理|待处理|已经解决/);
  assert.equal(value.count, 13);
  assert.equal(value.target.route, 'emergency');
  assert.equal(run('opsRiskView({code:"UNCOVERED_DUTY_POSTS",count:2}).target.label'), '选择计划核查岗位');
  assert.equal(run('opsRiskView({code:"FUTURE",count:null}).target'), null);
  assert.equal(run('opsRiskView({code:"FUTURE",count:null}).count'), '未提供');
});

check('risk prioritization never mutates, drops, or invents API categories', () => {
  context.issues = [{ code: 'FUTURE', count: 7 }, { code: 'FAILED_DELIVERIES', count: 1 }, { code: 'ACTIVE_EMERGENCIES', count: 13 }];
  const before = JSON.stringify(context.issues);
  assert.deepEqual(plain(run('opsPrioritizeIssues(issues).map(item=>item.code)')), ['ACTIVE_EMERGENCIES', 'FAILED_DELIVERIES', 'FUTURE']);
  assert.equal(JSON.stringify(context.issues), before);
});

check('risk overview is action-first, not a metric wall, and expands without writes', () => {
  reset({ ready: false, blockingIssueCount: 13, generatedAt: '2026-11-05T00:30:00Z', totals: { activeEmergencies: 13 }, issues: [{ code: 'ACTIVE_EMERGENCIES', count: 13 }] });
  let tree = render('OpsRiskPage');
  assert.match(text(tree), /优先处理/);
  assert.match(text(tree), /核查顺序/);
  assert.equal(nodes(tree).filter(node => node.props?.className?.includes('ops-metric')).length, 0);
  assert.match(text(tree), /数量可能重叠/);
  const toggle = nodes(tree).find(node => node.props?.['aria-expanded'] === false && node.props?.['aria-controls']?.startsWith('ops5-risk-'));
  assert.ok(toggle, 'A keyboard-operable category expansion is required');
  toggle.props.onClick();
  tree = render('OpsRiskPage');
  assert.ok(nodes(tree).some(node => node.props?.['aria-expanded'] === true));
  assert.match(text(tree), /核查覆盖范围/);
  assert.match(text(tree), /逐条风险编号/);
  assert.equal(routes.length, 0);
  nodes(tree).find(node => node.type === 'Button' && text(node) === '查看应急记录').props.onClick();
  assert.equal(routes.at(-1), 'emergency');
});

check('load errors and permission errors never render successful summaries or records', () => {
  for (const page of ['OpsRiskPage', 'OpsEmergencyPage']) {
    for (const status of ['loading', 'permission', 'error']) {
      reset(page === 'OpsRiskPage' ? { ready: true, totals: {}, issues: [] } : [{ id: 'secret-record', planName: '不得残留的计划' }], status);
      const tree = render(page);
      assert.ok(nodes(tree).some(node => node.type === 'ResourceState' && node.props.state.status === status));
      assert.doesNotMatch(text(tree), /不得残留的计划|本次检查没有返回问题|当前展示：应急变更记录/);
      assert.equal(nodes(tree).filter(node => node.type === 'BlueprintDrawer').length, 0);
      if (page === 'OpsEmergencyPage') {
        const createEntry = nodes(tree.props.actions).find(node => node.type === 'Button' && text(node) === '选择计划办理变更');
        assert.equal(createEntry?.props.disabled, true, 'Unreadable records must not invite a create/change workflow');
      }
    }
  }
});

check('registered time does not fall back to record creation or invent an end', () => {
  context.record = { createdAt: '2026-11-04T08:20:00Z', affectedResources: {} };
  assert.equal(run('opsEmergencyTime(record)'), '生效时间未提供');
  context.record.affectedResources = { effectiveAt: '2026-11-05T00:00:00Z' };
  const actual = run('opsEmergencyTime(record)');
  assert.match(actual, /08:00/);
  assert.doesNotMatch(actual, /16:20|12:00/);
  context.record.affectedResources.endsAt = '2026-11-05T04:00:00Z';
  assert.match(run('opsEmergencyTime(record)'), /12:00/);
});

check('resource summary reads only registered resources, never resolves guessed names', () => {
  context.record = { planName: '合成计划', affectedResources: { teacherIds: ['T-021'], effectiveAt: '2026-11-05T00:00:00Z' } };
  assert.match(run('opsEmergencyResources(record)'), /T-021/);
  assert.doesNotMatch(run('opsEmergencyResources(record)'), /老师|A101|语文/);
  assert.equal(run('opsEmergencyResources({affectedResources:{}})'), '登记资源未提供');
  assert.equal(run('opsEmergencyResources({affectedResources:{futureField:"raw"}})'), '其他登记字段，请查看详情');
});

const records = [
  { id: 'event-A', planId: 'plan-A', revisionId: 'source-A', revisionNo: 2, planName: '期末考试', emergencyType: 'TEACHER_SICK_LEAVE', status: 'ACTIVE', affectedResources: { teacherIds: ['T-021'] }, actionTaken: { substitutions: 1 } },
  { id: 'event-B', planId: 'plan-B', revisionId: 'source-B', revisionNo: 1, planName: '补考', emergencyType: 'ROOM_UNAVAILABLE', status: 'ACTIVE', affectedResources: { roomCodes: ['A102'] } },
];

check('emergency list keeps real names, separate times, neutral registration, and source columns', () => {
  reset(records);
  const tree = render('OpsEmergencyPage');
  for (const label of ['事件', '生效时间', '来源版本', '登记资源', '操作', '期末考试', '记录有效', '生效时间未提供']) assert.ok(text(tree).includes(label), label);
  assert.equal(nodes(tree).filter(node => node.type === 'Button' && text(node) === '查看详情').length, 2);
  assert.doesNotMatch(text(tree), /标记解决|关闭事件|替补已完成/);
});

check('emergency drawer is controlled and exact source navigation never chooses a successor', () => {
  reset(records);
  let tree = render('OpsEmergencyPage');
  nodes(tree).find(node => node.type === 'Button' && text(node) === '查看详情').props.onClick();
  tree = render('OpsEmergencyPage');
  const drawer = nodes(tree).find(node => node.type === 'BlueprintDrawer');
  assert.ok(drawer?.props.open);
  assert.match(text(drawer), /来源版本是发生变更时的原版本，不是后继版本/);
  assert.match(text(drawer), /接口记录的处理动作/);
  nodes(drawer).find(node => node.type === 'Button' && text(node) === '查看来源版本').props.onClick();
  assert.deepEqual(plain(sources[0]), { planId: 'plan-A', revisionId: 'source-A', returnRoute: 'emergency', returnLabel: '返回应急记录' });
  assert.equal(context.window.__jkxtEmergencyReturnId, 'event-A');
});

check('missing relation disables navigation and drawer close preserves list filters', () => {
  reset([{ ...records[0], revisionId: undefined }, records[1]]);
  let tree = render('OpsEmergencyPage');
  nodes(tree).find(node => node.type === 'input').props.onChange({ target: { value: '期末' } });
  tree = render('OpsEmergencyPage');
  nodes(tree).find(node => node.type === 'Button' && text(node) === '查看详情').props.onClick();
  tree = render('OpsEmergencyPage');
  const source = nodes(tree).find(node => node.type === 'Button' && text(node) === '查看来源版本');
  assert.equal(source.props.disabled, true);
  source.props.onClick();
  assert.equal(sources.length, 0);
  nodes(tree).find(node => node.props?.['aria-label'] === '关闭记录详情').props.onClick();
  tree = render('OpsEmergencyPage');
  assert.equal(nodes(tree).find(node => node.type === 'input').props.value, '期末');
  assert.equal(nodes(tree).filter(node => node.type === 'BlueprintDrawer').length, 0);
});

check('empty records and filtered-empty state do not fabricate rows', () => {
  reset([]);
  let tree = render('OpsEmergencyPage');
  assert.match(text(tree), /尚无应急变更记录/);
  assert.equal(nodes(tree).filter(node => node.type === 'Button' && text(node) === '查看详情').length, 0);
  reset(records);
  tree = render('OpsEmergencyPage');
  nodes(tree).find(node => node.type === 'input').props.onChange({ target: { value: '不存在' } });
  assert.match(text(render('OpsEmergencyPage')), /没有符合条件的记录/);
});

check('styling is scoped, theme-aware and keeps Chinese auxiliary text at least 13px', () => {
  const css = fs.readFileSync(path.join(root, 'src/styles/operations-v5.css'), 'utf8');
  assert.match(css, /\.ops-v5/);
  assert.match(css, /font-size:\s*16px/);
  assert.match(css, /line-height:\s*26px/);
  assert.match(css, /min-height:\s*44px/);
  assert.match(css, /var\(--(?:text|brand|surface|border)/);
  assert.doesNotMatch(css, /font-size:\s*(?:[0-9]|1[0-2](?:\.\d+)?)px/);
  assert.doesNotMatch(css, /(?:#sidebar|\.sidebar|\.topbar|\.page-title-mark)/);
});

console.log(JSON.stringify({ contract: 'OperationsWorkbench/v5', checks, passed: checks.filter(item => item.pass).length, total: checks.length }, null, 2));
if (checks.some(item => !item.pass)) process.exitCode = 1;
