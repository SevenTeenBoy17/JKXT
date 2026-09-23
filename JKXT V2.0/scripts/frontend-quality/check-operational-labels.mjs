import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/exam-plans.jsx'), 'utf8');
const backend = fs.readFileSync(path.join(root, 'backend/src/main/java/com/jkxt/examops/service/OperationsQueryService.java'), 'utf8');
const context = vm.createContext({ window: {}, Intl, Date, console,
  React: { createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }), useCallback: (fn) => fn },
  orgName: () => '合成测试学校', PageShell: 'PageShell', Button: 'Button', SectionTitle: 'SectionTitle',
});
vm.runInContext(Babel.transform(source, { presets: ['react'] }).code, context);
const evaluate = (code) => vm.runInContext(code, context);
const plain = (value) => JSON.parse(JSON.stringify(value));
const checks = [];
function check(name, run) {
  try { run(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 400) }); }
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

check('every current backend readiness total has a Chinese label', () => {
  const labels = plain(evaluate('OPERATIONAL_TOTAL_LABELS'));
  const keys = [...backend.matchAll(/totals\.put\("([^"]+)"/g)].map((match) => match[1]);
  assert.ok(keys.length >= 11);
  for (const key of [...keys, 'generatedAt', 'ready', 'blockingIssueCount']) assert.match(labels[key] || '', /[\u4e00-\u9fff]/, key);
});

check('every current risk code has a Chinese explanation', () => {
  const labels = plain(evaluate('OPERATIONAL_ISSUE_LABELS'));
  const codes = [...backend.matchAll(/add\(issues, "([^"]+)"/g)].map((match) => match[1]);
  assert.ok(codes.length >= 10);
  for (const code of codes) {
    assert.match(labels[code]?.[0] || '', /[\u4e00-\u9fff]/, code);
    assert.match(labels[code]?.[1] || '', /[\u4e00-\u9fff]/, code);
  }
  assert.equal(labels.ACTIVE_EMERGENCIES[0], '未处理应急变更');
});

check('timestamp is Beijing time text, never a numeric KPI', () => {
  const value = plain(evaluate('operationalSummaryItem("generatedAt", "2026-09-05T02:03:04.123Z")'));
  assert.equal(value.numeric, false);
  assert.equal(value.dateTime, '2026-09-05T02:03:04.123Z');
  assert.match(value.display, /2026\/09\/05\s+10:03:04/);
  assert.match(value.label, /北京时间/);
  assert.equal(evaluate('operationalSummaryItem("generatedAt", "invalid-raw-timestamp").display'), 'invalid-raw-timestamp');
});

check('boolean readiness does not become a fabricated count', () => {
  assert.deepEqual(plain(evaluate('[operationalSummaryItem("ready",true).display,operationalSummaryItem("ready",false).display]')), ['已就绪', '尚未就绪']);
  assert.equal(evaluate('operationalSummaryItem("ready", false).numeric'), false);
  assert.equal(evaluate('operationalSummaryItem("blockingIssueCount", 0).display'), '0');
  assert.equal(evaluate('operationalSummaryItem("activeEmergencies", 17).display'), '17');
});

check('timestamp cell has wrap-safe normal typography and a two-column span', () => {
  context.fixture = { generatedAt: '2026-09-05T02:03:04.123Z', ready: false, blockingIssueCount: 3, totals: { activeEmergencies: 3 }, issues: [] };
  evaluate('useLiveResource = () => [{status:"ready",data:fixture},()=>{}]');
  const tree = evaluate('OperationalQueuePage({title:"风险与待办",subtitle:"",endpoint:()=>{},emptyLabel:"空",routeId:"risks"})');
  const cell = nodes(tree).find((node) => node.props['data-readiness-field'] === 'generatedAt');
  assert.ok(cell);
  assert.match(cell.props.className, /min-w-0/);
  assert.match(cell.props.className, /col-span-2/);
  const time = nodes(cell).find((node) => node.type === 'time');
  assert.equal(time.props.dateTime, fixtureTimestamp());
  const value = nodes(cell).find((node) => node.props.className?.includes('mt-2'));
  assert.match(value.props.className, /text-\[14px\]/);
  assert.doesNotMatch(value.props.className, /font-mono|text-\[20px\]/);
  assert.equal(value.props.style.overflowWrap, 'anywhere');
});
function fixtureTimestamp() { return '2026-09-05T02:03:04.123Z'; }

check('localized risk keeps exact raw code, message, count and extra fields in details', () => {
  context.issue = { code: 'ACTIVE_EMERGENCIES', message: 'Emergency changes still require resolution', count: 17, futureField: 'preserve-me' };
  const before = JSON.stringify(context.issue);
  const tree = evaluate('OperationalIssueRows({rows:[issue]})');
  const heading = nodes(tree).find((node) => node.type === 'h3');
  assert.equal(textOf(heading), '未处理应急变更');
  const raw = nodes(tree).find((node) => node.type === 'pre');
  assert.deepEqual(JSON.parse(textOf(raw)), context.issue);
  assert.ok(textOf(tree).includes('17'));
  assert.equal(JSON.stringify(context.issue), before);
});

check('unknown future metrics and risks retain their raw values without invented translation', () => {
  assert.equal(evaluate('operationalSummaryItem("futureMetric", "original-value").display'), 'original-value');
  context.issue = { code: 'FUTURE_BACKEND_CODE', message: 'Future backend message', count: 2 };
  const tree = evaluate('OperationalIssueRows({rows:[issue]})');
  assert.equal(textOf(nodes(tree).find((node) => node.type === 'h3')), '其他待核查问题');
  assert.ok(textOf(tree).includes('Future backend message'));
  assert.deepEqual(JSON.parse(textOf(nodes(tree).find((node) => node.type === 'pre'))), context.issue);
});

console.log(JSON.stringify({ contract: 'OperationalReadinessPresentation/v1', checks, passed: checks.filter((item) => item.pass).length, total: checks.length }, null, 2));
if (checks.some((item) => !item.pass)) process.exitCode = 1;
