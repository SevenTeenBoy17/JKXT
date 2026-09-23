import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = fs.readFileSync(path.join(root, 'src/pages/exam-plans.jsx'), 'utf8');
const css = fs.readFileSync(path.join(root, 'src/styles/plans-v5.css'), 'utf8');
const require = createRequire(import.meta.url);
const babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const states = [];
const refs = [];
let stateIndex = 0;
let refIndex = 0;
let publishCalls = 0;
let refreshCalls = 0;
let requestFailure = null;
let publishArgs;
const context = vm.createContext({
  console, Intl, Date, Set, Map, JSON, Number, String,
  React: {
    createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.length === 1 ? children[0] : children } }),
    useState: initial => { const i = stateIndex++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial; return [states[i], value => { states[i] = typeof value === 'function' ? value(states[i]) : value; }]; },
    useRef: initial => { const i = refIndex++; return refs[i] ||= { current: initial }; },
    useEffect: () => {},
    useCallback: callback => callback,
    Fragment: 'Fragment',
  },
  window: { api: { examPlans: { publish: async (...args) => { publishCalls++;publishArgs = args;if (requestFailure) throw requestFailure;return { id: 'revision-a', status: 'PUBLISHED' }; } } } },
});
['Button','BlueprintDialog','BlueprintAsset','IconSearch','IconChevron','IconSettings','IconHelp','IconShield','IconAlert','IconCheck','PageShell','SectionTitle'].forEach(name => { context[name] = name; });
vm.runInContext(babel.transform(source, { presets: ['env', 'react'] }).code, context);
const call = (name, ...args) => context[name](...args);
const reset = () => { states.length = 0;refs.length = 0;stateIndex = 0;refIndex = 0; };
const renderLifecycle = (status = 'APPROVED') => { stateIndex = 0;refIndex = 0;return call('PlanLifecycleActions', { planId: 'plan-a', planName: '期中考试', planNo: 'PLAN-A', revision: { id: 'revision-a', status, revisionNo: 2, revisionCursor: 9 }, onChanged: async () => { refreshCalls++; } }); };
const flatten = node => !node || typeof node !== 'object' ? [] : [node, ...[node.props?.children, node.props?.footer].flat(Infinity).flatMap(flatten)];
const button = (tree, label) => flatten(tree).find(node => node.type === 'Button' && node.props.children === label);

assert.equal(call('planListFacts', {version: 99, activeRevisionId: 'revision-a'}).revision, '进入计划查看', 'DB optimistic lock must not be presented as business edition');
assert.equal(call('planListFacts', {createdAt: '2026-09-06T10:00:00Z'}).date, '进入场次查看', 'creation time must not be presented as exam date');
assert.equal(call('planListFacts', {revisionNo: 3}).revision, '第 3 版');
assert.equal(call('planListFacts', {examDate: '2026-11-05'}).date, '2026/11/5');
assert.equal(call('planNextStep', 'DRAFT').tab, 'inputs');
assert.equal(call('planNextStep', 'PENDING_APPROVAL').tab, 'conflicts');
assert.equal(call('planNextStep', 'PUBLISHED').tab, 'publish');
assert.equal(call('resolvePlanRevision', [{id:'other'}], 'missing', 'other'), null, 'explicit source version must not fall back');

reset();
let tree = renderLifecycle('DRAFT');
assert.equal(button(tree, '发布此版本'), undefined, 'unapproved revision has no publish action');
reset();
tree = renderLifecycle();
button(tree, '发布此版本').props.onClick();
tree = renderLifecycle();
let dialog = flatten(tree).find(node => node.type === 'BlueprintDialog');
assert.ok(dialog, 'publish opens accessible shared dialog');
assert.ok(button(dialog, '取消'));
assert.equal(publishCalls, 0, 'opening or cancelling must not publish');
dialog.props.onClose();
assert.equal(flatten(renderLifecycle()).some(node => node.type === 'BlueprintDialog'), false);
button(renderLifecycle(), '发布此版本').props.onClick();
dialog = flatten(renderLifecycle()).find(node => node.type === 'BlueprintDialog');
const confirm = button(dialog, '确认发布').props.onClick;
await Promise.all([confirm(), confirm()]);
assert.equal(publishCalls, 1, 'same-frame duplicate confirmation is locked');
assert.equal(publishArgs[0], 'plan-a');
assert.equal(publishArgs[1], 'revision-a');
assert.equal(publishArgs[2].headers['If-Match'], '9');
assert.equal(refreshCalls, 1);
assert.equal(flatten(renderLifecycle()).some(node => node.type === 'BlueprintDialog'), false, 'verified response closes confirmation');

reset();requestFailure = {status: 503, message:'unavailable'};
button(renderLifecycle(), '发布此版本').props.onClick();
dialog = flatten(renderLifecycle()).find(node => node.type === 'BlueprintDialog');
await button(dialog, '确认发布').props.onClick();
tree = renderLifecycle();
dialog = flatten(tree).find(node => node.type === 'BlueprintDialog');
assert.equal(button(dialog, '确认发布').props.disabled, true, 'ambiguous response prevents blind repeat');
await button(dialog, '确认发布').props.onClick();
assert.equal(publishCalls, 2);
assert.ok(button(tree, '刷新并核对状态'));

reset();
const empty = call('PlanList', {state:{status:'ready',data:[]},onRetry(){},onOpen(){},onRefresh(){}});
assert.ok(flatten(empty).some(node => node.props['data-testid'] === 'plans-empty'));
assert.equal(flatten(empty).some(node => node.type === 'tr'), false, 'empty organization does not receive sample rows');
reset();
const payload = Array.from({length:25}, (_,i) => ({id:`plan-${i}`,name:`计划 ${i}`,status:'DRAFT'}));
const list = call('PlanList', {state:{status:'ready',data:payload},onRetry(){},onOpen(){},onRefresh(){}});
assert.equal(flatten(list).filter(node => node.props?.['data-testid']?.startsWith('plan-row-')).length, 12);
assert.ok(flatten(list).some(node => node.type === 'table'));
assert.ok(button(list, '下一页'));
assert.match(source, /key=\{`\$\{planId\}:\$\{selectedRevisionId\}`\}/);
assert.match(source, /key=\{`\$\{selectedRevisionId\}:\$\{tab\}`\}/, 'resource panel remount prevents cross-version stale paint');
assert.match(source, /PLAN_TABS = \[\s*\{ id: 'sessions'/, 'five output tabs remain distinct from data preparation');
assert.match(css, /white-space:nowrap/);
assert.match(css, /@media\(max-width:760px\)/);
assert.match(css, /@media\(prefers-reduced-motion:reduce\)/);
assert.match(css, /@media\(forced-colors:active\)/);
assert.doesNotMatch(css, /(?:^|\n)(?:body|html|\.sidebar|\.topbar)[\s{]/, 'style ownership excludes global shell');
console.log('PASS Plans V5: JSX compile, truthful metadata, five-tab/source isolation, real empty state, pagination, approved-only publish, cancel, cursor, duplicate lock, uncertain-result guard, responsive/theme scope.');
