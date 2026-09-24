import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { collectSourceSnapshot, discoverTestClasses, discoverTestMethods, hash, junitMethodIdentity, parseJUnit, validateRelease } from './check-release.mjs';
import { buildExecutionPackage } from './execution-package.mjs';

// Entirely synthetic verifier fixtures. A passing fixture is never product acceptance.
const basicXml = '<testsuite name="ReleaseFixture" tests="1" failures="0" errors="0" skipped="0"><testcase name="positive" classname="fixture.FixtureTest"/></testsuite>';
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jkxt-release-gate-test-'));
  t.after(() => {
    const real = fs.realpathSync(root);
    assert.equal(path.dirname(real), fs.realpathSync(os.tmpdir()));
    assert.match(path.basename(real), /^jkxt-release-gate-test-/);
    fs.rmSync(real, { recursive: true });
  });
  const git = (...args) => execFileSync('git', args, { cwd: root, stdio: 'pipe', encoding: 'utf8' }).trim();
  git('init', '--quiet');
  fs.writeFileSync(path.join(root, 'source.java'), 'class Fixture {}\n');
  fs.mkdirSync(path.join(root, 'backend/src/test/java'), { recursive: true });
  fs.writeFileSync(path.join(root, 'backend/src/test/java/FixtureTest.java'), 'package fixture; class FixtureTest { @Test void positive() {} }\n');
  git('add', '.');
  git('-c', 'user.name=Gate Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'Synthetic gate fixture');
  const gitSha = git('rev-parse', 'HEAD');
  const sourceSnapshot = collectSourceSnapshot(root);
  const runId = 'synthetic-fixture-not-a-product-run';
  const sourceDigest = sourceSnapshot.sha256;
  fs.mkdirSync(path.join(root, 'docs/release'), { recursive: true });
  const write = (name, value) => {
    const file = `docs/release/${name}`;
    const data = typeof value === 'string' ? value : JSON.stringify(value);
    fs.writeFileSync(path.join(root, file), data);
    return { path: file, sha256: hash(data) };
  };
  const provenance = write('fixture-source.md', `SYNTHETIC verifier fixture, not customer acceptance.\n基线：\`${gitSha}\`。共 84 项场景\n## FIXTURE｜Synthetic\n| 编号 | 场景 | 给定 | 操作 | 必须观察到的结果 |\n|---|---|---|---|---|\n` + Array.from({ length: 84 }, (_, i) => `| FIXTURE-${String(i + 1).padStart(2, '0')} | Synthetic ${i + 1} | Input | Action | Expected |`).join('\n'));
  const architecture = write('fixture-architecture.md', 'SYNTHETIC verifier fixture.\n## 一、Scope\nNot product evidence.\n');
  const pkg = write('package.json', buildExecutionPackage(root, [{ ...provenance, role: 'matrix' }, { ...architecture, role: 'architecture' }]));
  const runtime = write('mappings.json', { schema: 'JKXTSpringMappings/v1', collection: 'RequestMappingHandlerMapping', environment: 'isolated-test', runId, gitSha, sourceDigest, mappings: [{ path: '/fixture', methods: ['GET'], handler: 'FixtureController#fixture' }] });
  const xml = write('TEST-fixture.xml', basicXml);
  const log = write('command.txt', 'Synthetic fixture command log; no product command executed.');
  const now = new Date().toISOString();
  const report = { schema: 'JKXTReleaseBaseline/v1', phase: 'g0-g1', status: 'READY', blockers: [], gitSha, runId, sourceSnapshot,
    commands: [{ id: 'fixture', status: 'EXECUTED', exitCode: 0, argv: ['synthetic-fixture'], cwd: '.', startedAt: now, finishedAt: now, runId, gitSha, sourceDigest, log }],
    tests: { status: 'EXECUTED', requiredClasses: discoverTestClasses(root, sourceSnapshot), discovered: 1, executed: 1, failures: 0, errors: 0, skipped: 0, reports: [{ ...xml, commandId: 'fixture', runId, gitSha, sourceDigest }] },
    executionPackage: { status: 'VERIFIED', scenarioCount: 84, artifact: pkg },
    runtimeMappings: { status: 'COLLECTED', count: 1, commandId: 'fixture', artifact: runtime } };
  function run(phase = 'g0-g1') {
    write('baseline.json', report);
    return validateRelease({ root, reportPath: 'docs/release/baseline.json', phase });
  }
  return { root, report, run, write };
}

test('synthetic complete evidence passes local verifier, never production or Supervisor', t => {
  const f = fixture(t), result = f.run();
  assert.equal(result.status, 'PASS');
  assert.equal(result.productionReady, false);
  assert.equal(result.supervisorAcceptance, 'NOT_CLAIMED');
  assert.deepEqual(result.tests, { discovered: 1, executed: 1, failures: 0, errors: 0, skipped: 0 });
});

const invalidCases = [
  ['empty report list', f => { f.report.tests.reports = []; }, /MISSING_TEST_REPORTS/],
  ['zero declared execution', f => { f.report.tests.executed = 0; }, /SUMMARY_MISMATCH/],
  ['wrong commit', f => { f.report.gitSha = '0'.repeat(40); }, /WRONG_SHA/],
  ['changed source', f => { fs.appendFileSync(path.join(f.root, 'source.java'), '// changed'); }, /SOURCE_HASH_MISMATCH/],
  ['added source omitted from manifest', f => { fs.writeFileSync(path.join(f.root, 'new.java'), 'class New {}'); }, /SOURCE_HASH_MISMATCH/],
  ['source manifest omission', f => { f.report.sourceSnapshot.files = []; }, /SOURCE_HASH_MISMATCH/],
  ['JUnit bytes tampered', f => { fs.appendFileSync(path.join(f.root, f.report.tests.reports[0].path), 'changed'); }, /ARTIFACT_HASH_MISMATCH/],
  ['missing XML file', f => { f.report.tests.reports[0].path = 'docs/release/no.xml'; }, /ENOENT/],
  ['duplicate report', f => { f.report.tests.reports.push({ ...f.report.tests.reports[0] }); }, /DUPLICATE_REPORT/],
  ['test NOT_RUN', f => { f.report.tests.status = 'NOT_RUN'; }, /MISSING_TEST_REPORTS/],
  ['test BLOCKED', f => { f.report.tests.status = 'BLOCKED'; }, /MISSING_TEST_REPORTS/],
  ['baseline BLOCKED', f => { f.report.status = 'BLOCKED'; }, /BASELINE_NOT_READY/],
  ['command NOT_RUN', f => { f.report.commands[0].status = 'NOT_RUN'; }, /COMMAND_NOT_PASSED/],
  ['command nonzero exit', f => { f.report.commands[0].exitCode = 1; }, /COMMAND_NOT_PASSED/],
  ['missing command', f => { f.report.commands = []; }, /MISSING_COMMANDS/],
  ['stale command SHA', f => { f.report.commands[0].gitSha = '1'.repeat(40); }, /COMMAND_IDENTITY_MISMATCH/],
  ['stale report run', f => { f.report.tests.reports[0].runId = 'old-run'; }, /REPORT_IDENTITY_MISMATCH/],
  ['missing 84-scenario execution package', f => { f.report.executionPackage.status = 'MISSING'; }, /MISSING_EXECUTION_PACKAGE/],
  ['missing runtime registration evidence', f => { f.report.runtimeMappings.status = 'NOT_RUN'; }, /MISSING_RUNTIME_MAPPINGS/],
  ['existing blocker', f => { f.report.blockers.push('Docker unavailable'); }, /BLOCKERS_PRESENT/],
  ['path escape', f => { f.report.tests.reports[0].path = '../outside.xml'; }, /UNSAFE_PATH/],
  ['future command completion', f => { f.report.commands[0].finishedAt = '2999-01-01T00:00:00Z'; }, /INVALID_COMMAND_TIME/],
  ['required class list removed', f => { f.report.tests.requiredClasses = []; }, /REQUIRED_TEST_CLASS_MISMATCH/],
];
for (const [name, change, reason] of invalidCases) test(`reject ${name}`, t => { const f = fixture(t); change(f); assert.throws(() => f.run(), reason); });

for (const outcome of ['failure', 'error', 'skipped']) test(`reject actual XML ${outcome}, even with accurate summary`, t => {
  const f = fixture(t);
  const key = outcome === 'failure' ? 'failures' : outcome === 'error' ? 'errors' : 'skipped';
  f.report.tests[key] = 1;
  if (outcome === 'skipped') f.report.tests.executed = 0;
  const xml = `<testsuite name="Fixture" tests="1" failures="${key === 'failures' ? 1 : 0}" errors="${key === 'errors' ? 1 : 0}" skipped="${key === 'skipped' ? 1 : 0}"><testcase name="negative" classname="fixture.FixtureTest"><${outcome}/></testcase></testsuite>`;
  Object.assign(f.report.tests.reports[0], f.write('TEST-fixture.xml', xml));
  assert.throws(() => f.run(), /TESTS_NOT_PASSED/);
});

test('reject final production request until later gates are implemented', t => { const f = fixture(t); assert.throws(() => f.run('release'), /PRODUCTION_GATE_NOT_IMPLEMENTED/); });
test('reject empty XML rather than trusting status/count strings', () => assert.throws(() => parseJUnit(''), /EMPTY_REPORT/));
test('reject zero-case XML suite', () => assert.throws(() => parseJUnit('<testsuite name="none" tests="0" failures="0" errors="0" skipped="0"/>'), /EMPTY_REPORT/));
test('reject declaration/testcase mismatch', () => assert.throws(() => parseJUnit(basicXml.replace('tests="1"', 'tests="2"')), /JUNIT_COUNTER_MISMATCH/));
test('reject hidden failure with forged zero counter', () => assert.throws(() => parseJUnit(basicXml.replace('<testcase name="positive" classname="fixture.FixtureTest"/>', '<testcase name="positive" classname="fixture.FixtureTest"><failure/></testcase>')), /JUNIT_COUNTER_MISMATCH/));
test('reject malformed/truncated XML', () => assert.throws(() => parseJUnit(basicXml.slice(0, -12)), /INVALID_XML/));
test('reject DTD external entity', () => assert.throws(() => parseJUnit('<!DOCTYPE test [<!ENTITY foo SYSTEM "file:///secret">]>' + basicXml), /INVALID_XML/));
test('accept normal Surefire property, comment, CDATA diagnostic content', () => {
  const xml = '<?xml version="1.0" encoding="UTF-8"?>' + basicXml.replace('<testcase', '<properties><property name="x" value="a &amp; b"/></properties><!-- diagnostic --><testcase').replace('</testsuite>', '<system-out><![CDATA[<not-a-testcase/>]]></system-out></testsuite>');
  assert.equal(parseJUnit(xml).executed, 1);
});
test('reject retry failure history hidden under successful totals', () => assert.throws(() => parseJUnit(basicXml.replace('<testcase name="positive" classname="fixture.FixtureTest"/>', '<testcase name="positive" classname="fixture.FixtureTest"><flakyFailure/></testcase>')), /JUNIT_COUNTER_MISMATCH/));
test('reject less than 84 actual scenarios despite declared count', t => {
  const f = fixture(t), original = f.report.executionPackage.artifact;
  const pkg = JSON.parse(fs.readFileSync(path.join(f.root, original.path), 'utf8'));
  pkg.scenarios.pop();
  f.report.executionPackage.artifact = f.write('package.json', pkg);
  assert.throws(() => f.run(), /INSUFFICIENT_SCENARIOS/);
});
test('reject static routes masquerading as runtime mappings', t => {
  const f = fixture(t), ref = f.report.runtimeMappings.artifact;
  const runtime = JSON.parse(fs.readFileSync(path.join(f.root, ref.path), 'utf8'));
  runtime.collection = 'annotation-regex';
  f.report.runtimeMappings.artifact = f.write('mappings.json', runtime);
  assert.throws(() => f.run(), /NOT_RUNTIME_MAPPINGS/);
});

for (const [label, mutate] of [
  ['replaced scenario ID', pkg => { pkg.scenarios[0].id = 'REPLACED-01'; }],
  ['weakened scenario expectation', pkg => { pkg.scenarios[0].expected = 'A toast is sufficient'; }],
  ['invented extra scenario with no source', pkg => { pkg.scenarios.push({ id: 'INVENTED-01', title: 'Invented' }); }],
]) test(`reject normalized package ${label} despite recomputed artifact hash`, t => {
  const f = fixture(t);
  const pkg = JSON.parse(fs.readFileSync(path.join(f.root, f.report.executionPackage.artifact.path), 'utf8'));
  mutate(pkg);
  f.report.executionPackage.scenarioCount = pkg.scenarios.length;
  f.report.executionPackage.artifact = f.write('package.json', pkg);
  assert.throws(() => f.run(), /PACKAGE_SOURCE_MISMATCH|INVALID_PACKAGE_SOURCE/);
});
test('reject a single green XML file when another required source test class is missing', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.root, 'backend/src/test/java/RequiredTest.java'), 'package fixture; class RequiredTest { @Test void required() {} }');
  f.report.sourceSnapshot = collectSourceSnapshot(f.root);
  f.report.tests.requiredClasses = discoverTestClasses(f.root, f.report.sourceSnapshot);
  const digest = f.report.sourceSnapshot.sha256;
  f.report.commands[0].sourceDigest = digest;
  f.report.tests.reports[0].sourceDigest = digest;
  assert.throws(() => f.run(), /MISSING_TEST_CLASSES: fixture.RequiredTest/);
});
test('reject partial method execution within an otherwise reported class', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.root, 'backend/src/test/java/FixtureTest.java'), 'package fixture; class FixtureTest { @Test void positive() {} @Test void critical() {} }');
  f.report.sourceSnapshot = collectSourceSnapshot(f.root);
  const digest = f.report.sourceSnapshot.sha256;
  f.report.commands[0].sourceDigest = digest;
  f.report.tests.reports[0].sourceDigest = digest;
  const runtime = JSON.parse(fs.readFileSync(path.join(f.root, f.report.runtimeMappings.artifact.path), 'utf8'));
  runtime.sourceDigest = digest;
  f.report.runtimeMappings.artifact = f.write('mappings.json', runtime);
  assert.throws(() => f.run(), /MISSING_TEST_METHODS: fixture.FixtureTest#critical/);
});
for (const flag of ['-DskipTests', '-DskipTests=true', '-Dmaven.test.skip=true', '-Dmaven.test.skip', '-D skipTests=true']) test(`reject test evidence bound to compile-only command ${flag}`, t => {
  const f = fixture(t);
  f.report.commands[0].argv = ['mvn', 'package', flag];
  assert.throws(() => f.run(), /SKIP_TEST_COMMAND/);
});
test('explicit false skip flags do not reject executed test evidence', t => {
  const f = fixture(t);
  f.report.commands[0].argv = ['mvn', 'test', '-DskipTests=false', '-Dmaven.test.skip=false'];
  assert.equal(f.run().status, 'PASS');
});
test('normalize actual Surefire parameterized method names without losing class identity', () => {
  assert.equal(junitMethodIdentity('fixture.FixtureTest#unsafeDatabaseTargetsAreRejected(String)[1]'), 'fixture.FixtureTest#unsafeDatabaseTargetsAreRejected');
  assert.equal(junitMethodIdentity('fixture.FixtureTest#mutation(String, String, int)[2]'), 'fixture.FixtureTest#mutation');
  assert.equal(junitMethodIdentity('fixture.FixtureTest#ordinary'), 'fixture.FixtureTest#ordinary');
  assert.throws(() => junitMethodIdentity('fixture.FixtureTest#1: custom display name'), /UNSUPPORTED_TEST_IDENTITY/);
});
test('discover annotated methods through multiline arguments while ignoring literal and comment decoys', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.root, 'backend/src/test/java/FixtureTest.java'), `package fixture;
class FixtureTest {
  String decoy = "@Test void fake() {}";
  // @Test void commentedOut() {}
  @ParameterizedTest(name = "{0}")
  @CsvSource({"a,b", "c,d"})
  void positive(String first, String second) {}
  @Test void critical() {}
}`);
  assert.deepEqual(discoverTestMethods(f.root), ['fixture.FixtureTest#critical', 'fixture.FixtureTest#positive']);
});
test('parameterized XML instances prove their required declared method', t => {
  const f = fixture(t);
  Object.assign(f.report.tests.reports[0], f.write('TEST-fixture.xml', basicXml.replace('name="positive"', 'name="positive(String, int)[1]"')));
  assert.equal(f.run().status, 'PASS');
});
test('unknown dynamic test discovery fails closed rather than omitting required methods', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.root, 'backend/src/test/java/FixtureTest.java'), 'package fixture; class FixtureTest { @TestFactory Stream<DynamicTest> cases() {} }');
  assert.throws(() => discoverTestMethods(f.root), /UNSUPPORTED_TEST_DISCOVERY/);
});
