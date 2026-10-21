'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const Babel = require('../assets/vendor/babel-standalone.7.29.0.min.js');
const root = path.resolve(__dirname, '..');
const filename = process.env.JKXT_DUTY_ROLES_SOURCE || path.join(root, 'src/pages/operations-v6-approvals.jsx');
const production = Babel.transform(fs.readFileSync(filename, 'utf8'), { presets: ['react'], filename }).code;
// Both persisted requirement and assignment roles are constrained by the real backend schema.
// /plan-revisions/{id}/duties returns a.duty_role unchanged (PlanRevisionResourceService).
const migration = fs.readFileSync(path.join(root, 'backend/src/main/resources/db/migration/V19__exam_operations_foundation.sql'), 'utf8');
const enumLists = [...migration.matchAll(/duty_role varchar\(32\) NOT NULL CHECK \(duty_role IN \(([^)]+)\)\)/g)]
  .map(match => [...match[1].matchAll(/'([^']+)'/g)].map(value => value[1]));
const expected = new Map([
  ['CHIEF', '主监考'], ['INVIGILATOR', '监考'], ['ASSISTANT', '副监考'], ['ROAMING', '巡考'], ['RESERVE', '备用监考'],
]);
const row = role => ({
  id: '10000000-0000-4000-8000-000000000001',
  session_id: '20000000-0000-4000-8000-000000000001',
  session_external_code: '30000000-0000-4000-8000-000000000001',
  teacher_id: '40000000-0000-4000-8000-000000000001', teacher_name: '合成教师甲', teacher_no: 'T001',
  starts_at: '2026-10-21T00:00:00Z', ends_at: '2026-10-21T02:00:00Z', duty_role: role,
  status: 'DRAFT', source: 'SOLVER', version: 0,
});
const nodes = value => Array.isArray(value) ? value.flatMap(nodes) : value && typeof value === 'object'
  ? [value, ...nodes(value.children)] : [];
const text = value => Array.isArray(value) ? value.map(text).join('')
  : value && typeof value === 'object' ? text(value.children) : typeof value === 'string' ? value : '';

// Render the actual production drawer and comparison components. Only the already-loaded
// resource and React element boundary are controlled; no network or mutation is performed.
function render(rows) {
  const context = vm.createContext({
    window: { BlueprintDrawer: 'drawer' }, Button: 'button', Date, Intl,
    React: { Fragment: 'fragment', useCallback: fn => fn,
      createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }) },
    useLiveResource: () => [{ status: 'ready', data: rows }, () => {}],
  });
  vm.runInContext(production, context, { filename });
  const arrangement = vm.runInContext('Ops6VersionArrangement', context);
  const comparison = vm.runInContext('Ops6DutyComparison', context);
  const revision = { revisionNo: 2, status: 'PENDING_APPROVAL' };
  return {
    drawer: arrangement({ item: { planId: 'plan', revisionId: 'revision', planName: '合成计划' }, revision, onClose() {} }),
    difference: comparison({ data: { source: { revisionNo: 1 }, before: rows,
      after: rows.map(value => ({ ...value, teacher_no: 'T002', teacher_name: '合成教师乙' })) }, revision }),
  };
}

function assertRole(role, label, field = 'duty_role') {
  const value = row(role);
  if (field !== 'duty_role') { delete value.duty_role; value[field] = role; }
  const result = render([value]);
  const cell = nodes(result.drawer).find(node => node.type === 'td' && node.props['data-label'] === '岗位');
  assert.equal(text(cell), label, `drawer role ${String(role)}`);
  assert.ok(text(result.difference).includes(label), `comparison must render ${label}`);
  assert.equal(text(result.difference).includes('角色未提供'), false);
}

test('runtime role fixtures are drawn from both real backend duty-role constraints', () => {
  assert.equal(enumLists.length, 2, 'requirement and assignment constraints must both be found');
  assert.deepEqual(new Set(enumLists[0]), new Set(enumLists[1]));
  assert.equal(enumLists[0].length, expected.size);
  for (const code of enumLists[0]) assert.ok(expected.has(code), `New backend role requires a reviewed Chinese label: ${code}`);
});

for (const code of enumLists[0] || []) {
  test(`backend ${code} has a Chinese label in both arrangement and comparison`, () => assertRole(code, expected.get(code)));
}
for (const [code, label, field] of [
  ['main', '主监考', 'role'], ['assistant', '副监考', 'role'], ['MAIN', '主监考', 'dutyRole'],
]) {
  test(`legacy ${code}/${field} remains compatible in both tables`, () => assertRole(code, label, field));
}
for (const code of ['NEW_ROLE', '__proto__', 'constructor', 'toString', 'hasOwnProperty', 42]) {
  test(`unknown role ${String(code)} stays visible without prototype lookup`, () => assertRole(code, `未识别岗位（${code}）`));
}
for (const value of [undefined, null, '', '   ']) {
  test(`absent role ${JSON.stringify(value)} remains explicitly unavailable`, () => {
    const { drawer } = render([row(value)]);
    const cell = nodes(drawer).find(node => node.type === 'td' && node.props['data-label'] === '岗位');
    assert.equal(text(cell), '角色未提供');
  });
}
test('role correction preserves source session identity, teacher and honest room limitation', () => {
  const { drawer } = render([row('CHIEF')]);
  const content = text(drawer);
  assert.ok(content.includes('30000000-0000-4000-8000-000000000001'));
  assert.ok(content.includes('合成教师甲') && content.includes('T001'));
  assert.match(content, /08:00.*10:00/);
  assert.ok(content.includes('考场信息未提供'));
});
