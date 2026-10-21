'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const Babel = require('../assets/vendor/babel-standalone.7.29.0.min.js');
const filename = process.env.JKXT_OPS6_PROJECTION_SOURCE || path.join(__dirname, '../src/pages/operations-v6-approvals.jsx');
const production = Babel.transform(fs.readFileSync(filename, 'utf8'), { presets: ['react'], filename }).code;
// PlanRevisionResourceService /duties additive contract: names are separate from
// stable external session codes; room labels are current classroom metadata.
const duty = overrides => ({ id: 'a1', session_id: 'revision-local-session-1',
  session_external_code: '20000000-0000-4000-8000-000000000001', session_name: '七年级语文考试', subject_name: '语文',
  room_assignment_id: 'room-assignment-1', classroom_id: 'classroom-1', room_name: '东楼一考场', room_code: 'R101',
  teacher_id: 'teacher-1', teacher_name: '合成教师甲', teacher_no: 'T001', duty_role: 'CHIEF',
  starts_at: '2026-10-21T00:00:00Z', ends_at: '2026-10-21T02:00:00Z', status: 'DRAFT', source: 'SOLVER', version: 0, ...overrides });
const nodes = value => Array.isArray(value) ? value.flatMap(nodes) : value && typeof value === 'object'
  ? [value, ...nodes(value.children)] : [];
const text = value => Array.isArray(value) ? value.map(text).join('') : value && typeof value === 'object'
  ? text(value.children) : typeof value === 'string' ? value : '';
function render(before, after = before.map(x => ({ ...x, teacher_name: '合成教师乙', teacher_no: 'T002' }))) {
  const context = vm.createContext({ window: { BlueprintDrawer: 'drawer' }, Button: 'button', Date, Intl,
    React: { Fragment: 'fragment', useCallback: fn => fn,
      createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }) },
    useLiveResource: () => [{ status: 'ready', data: after }, () => {}] });
  vm.runInContext(production, context, { filename });
  const revision = { revisionNo: 2, status: 'PENDING_APPROVAL' };
  const drawer = vm.runInContext('Ops6VersionArrangement', context)({ item: { planId: 'plan', revisionId: 'revision' }, revision, onClose() {} });
  const diff = vm.runInContext('Ops6DutyComparison', context)({ data: { source: { revisionNo: 1 }, before, after }, revision });
  return { drawer, diff, drawerCells: nodes(drawer).filter(x => x.type === 'td' && x.props['data-label'] === '场次与时段'),
    diffCells: nodes(diff).filter(x => x.type === 'td' && x.props['data-label'] === '变更范围') };
}
test('real session and room names appear in rendered cells, not only raw JSON', () => {
  const result = render([duty()]);
  for (const cell of [...result.drawerCells, ...result.diffCells]) {
    assert.match(text(cell), /七年级语文考试/); assert.match(text(cell), /东楼一考场/); assert.match(text(cell), /R101/);
    assert.doesNotMatch(text(cell), /接口未提供/);
  }
  assert.doesNotMatch(text(result.drawer), /考场关联当前接口未提供/);
});
test('subject is a fallback only when the session name is absent', () => {
  const result = render([duty({ session_name: '  ', subject_name: '语文' })]);
  assert.match(text(result.drawerCells[0]), /语文/); assert.match(text(result.diffCells[0]), /语文/);
});
test('legacy external code and explicit missing-room notice survive without added fields', () => {
  const result = render([duty({ session_name: null, subject_name: null, room_name: null, room_code: null, room_assignment_id: null, classroom_id: null })]);
  assert.match(text(result.drawerCells[0]), /20000000-0000-4000-8000-000000000001/);
  assert.match(text(result.drawerCells[0]), /考场.*未提供/); assert.match(text(result.diffCells[0]), /考场.*未提供/);
});
for (const [name, code, expected] of [[null, 'R102', 'R102'], ['西楼考场', null, '西楼考场']]) test(`room partial metadata remains useful: ${expected}`, () => {
  const result = render([duty({ room_name: name, room_code: code })]);
  assert.ok(text(result.drawerCells[0]).includes(expected)); assert.ok(text(result.diffCells[0]).includes(expected));
});
test('same names with distinct stable session codes never merge groups', () => {
  const result = render([duty(), duty({ id: 'a2', session_external_code: '20000000-0000-4000-8000-000000000002' })]);
  assert.equal(result.diffCells.length, 2);
});
test('new revision-local IDs do not split the same session and current names win display only', () => {
  const before = [duty({ session_name: '旧场次名称', room_name: '旧考场名' })];
  const after = [duty({ session_id: 'revision-local-session-2', room_assignment_id: 'revision-room-2', teacher_no: 'T002', session_name: '当前场次名称', room_name: '当前考场名' })];
  const result = render(before, after); assert.equal(result.diffCells.length, 1);
  assert.match(text(result.diffCells[0]), /当前场次名称/); assert.match(text(result.diffCells[0]), /当前考场名/);
  assert.doesNotMatch(text(result.diffCells[0]), /旧场次名称|旧考场名/);
});
test('renaming labels alone is not fabricated as a personnel change', () => {
  const result = render([duty()], [duty({ session_name: '改名但同场次', room_name: '改名但同考场' })]);
  assert.equal(result.diffCells.length, 0); assert.match(text(result.diff), /未发现人员变化/);
});
test('completely absent names and codes remain explicitly unavailable', () => {
  const result = render([duty({ session_name: null, subject_name: null, session_external_code: null, room_name: null, room_code: null })]);
  assert.match(text(result.drawerCells[0]), /场次未提供/); assert.match(text(result.drawerCells[0]), /考场.*未提供/);
});
