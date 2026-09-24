import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { parseScenarioMatrix, parseArchitecture, buildExecutionPackage, validateExecutionPackage } from './execution-package.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const matrixPath = 'docs/release/inputs/02_后端与全栈验收矩阵.md';
const architecturePath = 'docs/release/inputs/03_架构与优化决策.md';
const matrix = fs.readFileSync(path.join(root, matrixPath), 'utf8');
const architecture = fs.readFileSync(path.join(root, architecturePath), 'utf8');
const refs = [{ role: 'matrix', path: matrixPath }, { role: 'architecture', path: architecturePath }];

test('provided originals contain exactly 84 full scenarios, 14 groups, and all 12 architecture sections', () => {
  const pkg = buildExecutionPackage(root, refs);
  assert.equal(pkg.baselineSha, 'f206bcf6dff291d346cf5c3cf5a68bfce938935e');
  assert.equal(pkg.scenarios.length, 84);
  assert.equal(new Set(pkg.scenarios.map(s => s.group)).size, 14);
  assert.equal(pkg.architectureDecisions.length, 12);
  assert.ok(pkg.scenarios.every(s => s.given && s.operation && s.expected && s.sourceLine > 0));
  assert.match(pkg.scenarios.find(s => s.id === 'SOLVE-03').expected, /早 B、晚 A/);
  assert.match(pkg.architectureDecisions[2].content, /独立验证结果/);
  assert.match(pkg.architectureDecisions[11].content, /人工批准/);
  assert.deepEqual(validateExecutionPackage(root, pkg), pkg);
});

test('CRLF conversion keeps scenario text and source line mapping unchanged', () => {
  assert.deepEqual(parseScenarioMatrix(matrix.replace(/\r?\n/g, '\r\n'), matrixPath), parseScenarioMatrix(matrix, matrixPath));
});
test('escaped pipes remain in requirement cells rather than silently dropping text', () => {
  const source = matrix.replace('严格生产检查已开启', '严格生产检查 A\\|B 已开启');
  assert.match(parseScenarioMatrix(source, matrixPath).scenarios[0].expected, /A\|B/);
});
for (const [label, mutate] of [
  ['duplicate ID', s => s.replace('| ENV-02 |', '| ENV-01 |')],
  ['invalid ID', s => s.replace('| ENV-02 |', '| BROKEN |')],
  ['wrong section', s => s.replace('| ENV-02 |', '| AUTH-99 |')],
  ['empty condition', s => s.replace('| 严格生产模式 |', '|  |')],
  ['truncated row', s => s.replace('| 不安全生产配置拒绝启动 |', '|')],
  ['unknown header', s => s.replace('| 必须观察到的结果 |', '| Looks OK |')],
  ['false declared count', s => s.replace('共 84 项场景', '共 83 项场景')],
  ['missing baseline', s => s.replace('f206bcf6dff291d346cf5c3cf5a68bfce938935e', 'unknown')],
]) test(`malformed original fails closed: ${label}`, () => assert.throws(() => parseScenarioMatrix(mutate(matrix), matrixPath), /INVALID_PACKAGE_SOURCE/));

test('empty architecture section is not accepted as read requirements', () => {
  assert.throws(() => parseArchitecture('## 一、Empty\n## 二、Next\nContent', architecturePath), /empty architecture/);
});
test('changed source hash rejects package provenance', () => {
  assert.throws(() => buildExecutionPackage(root, [{ ...refs[0], sha256: '0'.repeat(64) }, refs[1]]), /source hash mismatch/);
});
test('missing architecture input rejects incomplete intake', () => {
  assert.throws(() => buildExecutionPackage(root, [refs[0]]), /both required/);
});
test('path traversal rejects source outside the candidate', () => {
  assert.throws(() => buildExecutionPackage(root, [{ ...refs[0], path: '../private.md' }, refs[1]]), /inside repository/);
});
for (const field of ['given', 'operation', 'expected', 'sourceLine']) test(`normalized ${field} cannot be rewritten independently of source`, () => {
  const pkg = buildExecutionPackage(root, refs);
  pkg.scenarios[0][field] = 'weakened';
  assert.throws(() => validateExecutionPackage(root, pkg), /PACKAGE_SOURCE_MISMATCH/);
});
test('architecture advice cannot silently disappear from normalized package', () => {
  const pkg = buildExecutionPackage(root, refs);
  pkg.architectureDecisions[2].content = 'removed counterexample';
  assert.throws(() => validateExecutionPackage(root, pkg), /PACKAGE_SOURCE_MISMATCH/);
});
test('reordering and replacing scenarios cannot conceal missing original IDs', () => {
  const pkg = buildExecutionPackage(root, refs);
  pkg.scenarios.reverse();
  assert.throws(() => validateExecutionPackage(root, pkg), /PACKAGE_SOURCE_MISMATCH/);
});
test('CLI preserves existing artifacts and never overwrites a previous intake', t => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'jkxt-package-test-'));
  t.after(() => {
    const actual = fs.realpathSync(temporary);
    assert.equal(path.dirname(actual), fs.realpathSync(os.tmpdir()));
    assert.match(path.basename(actual), /^jkxt-package-test-/);
    fs.rmSync(actual, { recursive: true });
  });
  fs.mkdirSync(path.join(temporary, 'docs/release/inputs'), { recursive: true });
  fs.copyFileSync(path.join(root, matrixPath), path.join(temporary, matrixPath));
  fs.copyFileSync(path.join(root, architecturePath), path.join(temporary, architecturePath));
  const output = 'docs/release/package.json';
  const invoke = () => spawnSync(process.execPath, [path.join(root, 'scripts/release/execution-package.mjs'), temporary, matrixPath, architecturePath, output], { encoding: 'utf8' });
  const first = invoke();
  assert.equal(first.status, 0, first.stderr);
  assert.equal(JSON.parse(first.stdout).businessTestsExecuted, 0);
  const bytes = fs.readFileSync(path.join(temporary, output));
  const repeat = invoke();
  assert.equal(repeat.status, 1);
  assert.match(repeat.stderr, /EEXIST/);
  assert.deepEqual(fs.readFileSync(path.join(temporary, output)), bytes);
});
