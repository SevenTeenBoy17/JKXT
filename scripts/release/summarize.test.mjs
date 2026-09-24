import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';
import { collectSourceSnapshot, hash } from './check-release.mjs';
import { buildExecutionPackage } from './execution-package.mjs';

// Synthetic evidence-adapter fixtures only: no Docker, business database or product test is run.
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const xml = '<testsuite name="Synthetic" tests="1" failures="0" errors="0" skipped="0"><testcase name="positive" classname="fixture.FixtureTest"/></testsuite>';
function fixture(t, runId = 'release_g1_synthetic') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jkxt-summary-test-'));
  t.after(() => {
    const real = fs.realpathSync(root);
    assert.equal(path.dirname(real), fs.realpathSync(os.tmpdir()));
    assert.match(path.basename(real), /^jkxt-summary-test-/);
    fs.rmSync(real, { recursive: true });
  });
  const write = (relative, value) => {
    const file = path.join(root, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value));
    return file;
  };
  const read = relative => JSON.parse(fs.readFileSync(path.join(root, relative), 'utf8'));
  for (const script of ['summarize.mjs', 'check-release.mjs', 'execution-package.mjs']) {
    write(`scripts/release/${script}`, fs.readFileSync(path.join(scriptDirectory, script), 'utf8'));
  }
  write('backend/src/test/java/FixtureTest.java', 'package fixture; class FixtureTest { @Test void positive() {} }');
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: 'pipe' }).trim();
  git('init', '--quiet'); git('add', '.');
  git('-c', 'user.name=Synthetic Adapter Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'Synthetic fixture only');
  const gitSha = git('rev-parse', 'HEAD'), snapshot = collectSourceSnapshot(root);
  const release = 'docs/release', evidence = `${release}/evidence/${runId}`;
  write(`${release}/fixture-matrix.md`, `Synthetic test only.\n基线：\`${gitSha}\`。共 84 项场景\n## FIXTURE｜Synthetic\n| 编号 | 场景 | 给定 | 操作 | 必须观察到的结果 |\n|---|---|---|---|---|\n` + Array.from({ length: 84 }, (_, i) => `| FIXTURE-${String(i + 1).padStart(2, '0')} | Synthetic ${i + 1} | Input | Action | Expected |`).join('\n'));
  write(`${release}/fixture-architecture.md`, '## 一、Synthetic scope\nNot product evidence.');
  write(`${release}/execution-package.json`, buildExecutionPackage(root, [{ role: 'matrix', path: `${release}/fixture-matrix.md` }, { role: 'architecture', path: `${release}/fixture-architecture.md` }]));
  write(`${evidence}/environment.json`, { status: 'READY', run: runId, project: `jkxt-test-${runId.replaceAll('_', '-')}`, database: `jkxt_test_${runId}`, databaseEndpoint: '127.0.0.1:25432', redisEndpoint: '127.0.0.1:26379', ephemeralStorage: true, businessDatabaseAccessed: false, testcontainersEnabled: true });
  write(`${evidence}/before-suite-source.json`, snapshot);
  write(`${evidence}/spring-mappings.json`, { schema: 'JKXTSpringMappings/v1', collection: 'RequestMappingHandlerMapping', environment: 'isolated-test', runId, gitSha, sourceDigest: snapshot.sha256, mappings: [{ path: '/fixture', methods: ['GET'], handler: 'Synthetic#fixture' }] });
  write(`${evidence}/junit/TEST-fixture.xml`, xml);
  const args = ['-B', '-o', `-Djkxt.test.reportsDirectory=${path.join(root, evidence, 'junit').replaceAll('\\', '/')}`, '-Djkxt.testcontainers.enabled=true', `-Djkxt.release.mappingOutput=${path.join(root, evidence, 'spring-mappings.json').replaceAll('\\', '/')}`, `-Djkxt.release.runId=${runId}`, `-Djkxt.release.gitSha=${gitSha}`, `-Djkxt.release.sourceDigest=${snapshot.sha256}`, 'test'];
  const command = (id, folder = evidence, argumentsList = args) => {
    const output = `${id}.txt`, text = 'SYNTHETIC fixture; this is not a product execution log.\n';
    write(`${folder}/${output}`, text);
    write(`${folder}/${id}.source.json`, snapshot);
    write(`${folder}/${id}.source-after.json`, snapshot);
    write(`${folder}/${id}.command.json`, { schema: 'JKXT.CommandEvidence/v1', id, gitSha, sourceDigest: snapshot.sha256, sourceUnchanged: true, sourceSnapshot: `${id}.source.json`, sourceAfter: `${id}.source-after.json`, executable: id === 'full-maven-suite' ? 'mvn' : 'docker', arguments: argumentsList, cwd: path.join(root, 'backend'), startedUtc: new Date().toISOString(), endedUtc: new Date().toISOString(), exitCode: 0, nativeExitCode: 0, output, outputSha256: hash(text) });
  };
  command('docker-preflight', evidence, ['version']);
  command('infrastructure-up', evidence, ['compose', 'up', '-d', '--wait']);
  command('full-maven-suite');
  const run = (selection = runId) => {
    const result = spawnSync(process.execPath, [path.join(root, 'scripts/release/summarize.mjs'), selection], { encoding: 'utf8' });
    return { ...result, report: fs.existsSync(path.join(root, release, 'baseline.json')) ? read(`${release}/baseline.json`) : null };
  };
  return { root, runId, evidence, write, read, command, run, args, snapshot };
}

test('summarize exact underscored isolated run and junit/ using the actual G0/G1 gate', t => {
  const f = fixture(t), result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.report.status, 'READY');
  assert.equal(result.report.productionReady, false);
  assert.equal(result.report.runId, f.runId);
  assert.equal(result.report.tests.executed, 1);
  assert.equal(result.report.runtimeMappings.count, 1);
  assert.equal(result.report.tests.reports[0].path, `${f.evidence}/junit/TEST-fixture.xml`);
});

test('reject an ambiguous command ID instead of picking the first historical full suite', t => {
  const f = fixture(t, 'run-one');
  f.command('full-maven-suite', 'docs/release/evidence/run-two');
  const result = f.run('full-maven-suite');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /AMBIGUOUS_EXECUTION/);
});

const blockedCases = [
  ['missing runtime mappings', f => fs.unlinkSync(path.join(f.root, f.evidence, 'spring-mappings.json')), /MISSING_RUNTIME_MAPPINGS/],
  ['static rather than Spring runtime mappings', f => { const x = f.read(`${f.evidence}/spring-mappings.json`); x.collection = 'annotation-regex'; f.write(`${f.evidence}/spring-mappings.json`, x); }, /NOT_RUNTIME_MAPPINGS/],
  ['stale runtime identity', f => { const x = f.read(`${f.evidence}/spring-mappings.json`); x.runId = 'different-run'; f.write(`${f.evidence}/spring-mappings.json`, x); }, /RUNTIME_IDENTITY_MISMATCH/],
  ['missing package', f => fs.unlinkSync(path.join(f.root, 'docs/release/execution-package.json')), /MISSING_EXECUTION_PACKAGE/],
  ['no actual XML', f => fs.unlinkSync(path.join(f.root, f.evidence, 'junit/TEST-fixture.xml')), /MISSING_TEST_REPORTS/],
  ['environment BLOCKED', f => { const x = f.read(`${f.evidence}/environment.json`); x.status = 'BLOCKED'; f.write(`${f.evidence}/environment.json`, x); }, /ISOLATION_NOT_PROVEN/],
  ['business database name', f => { const x = f.read(`${f.evidence}/environment.json`); x.database = 'jkxt'; f.write(`${f.evidence}/environment.json`, x); }, /ISOLATION_NOT_PROVEN/],
  ['business Redis port', f => { const x = f.read(`${f.evidence}/environment.json`); x.redisEndpoint = '127.0.0.1:6379'; f.write(`${f.evidence}/environment.json`, x); }, /ISOLATION_NOT_PROVEN/],
  ['remote database', f => { const x = f.read(`${f.evidence}/environment.json`); x.databaseEndpoint = 'production.example:25432'; f.write(`${f.evidence}/environment.json`, x); }, /ISOLATION_NOT_PROVEN/],
  ['missing preflight command', f => fs.unlinkSync(path.join(f.root, f.evidence, 'docker-preflight.command.json')), /ENOENT/],
  ['Maven nonzero exit', f => { const x = f.read(`${f.evidence}/full-maven-suite.command.json`); x.exitCode = 1; f.write(`${f.evidence}/full-maven-suite.command.json`, x); }, /COMMAND_NOT_PASSED/],
  ['infrastructure nonzero exit', f => { const x = f.read(`${f.evidence}/infrastructure-up.command.json`); x.exitCode = 1; f.write(`${f.evidence}/infrastructure-up.command.json`, x); }, /COMMAND_NOT_PASSED/],
  ['wrong command SHA', f => { const x = f.read(`${f.evidence}/full-maven-suite.command.json`); x.gitSha = '0'.repeat(40); f.write(`${f.evidence}/full-maven-suite.command.json`, x); }, /COMMAND_IDENTITY_MISMATCH/],
  ['source drift during command', f => { const x = f.read(`${f.evidence}/full-maven-suite.command.json`); x.sourceUnchanged = false; f.write(`${f.evidence}/full-maven-suite.command.json`, x); }, /SOURCE_CHANGED_DURING_COMMAND/],
  ['post-run source manifest drift', f => { const x = f.read(`${f.evidence}/full-maven-suite.source-after.json`); x.files = []; f.write(`${f.evidence}/full-maven-suite.source-after.json`, x); }, /COMMAND_SOURCE_SNAPSHOT_MISMATCH/],
  ['selected tests masquerading as full suite', f => { const x = f.read(`${f.evidence}/full-maven-suite.command.json`); x.arguments.push('-Dtest=FixtureTest'); f.write(`${f.evidence}/full-maven-suite.command.json`, x); }, /NOT_FULL_SUITE/],
  ['compile-only command', f => { const x = f.read(`${f.evidence}/full-maven-suite.command.json`); x.arguments.push('-DskipTests=true'); f.write(`${f.evidence}/full-maven-suite.command.json`, x); }, /SKIP_TEST_COMMAND/],
  ['testcontainers disabled', f => { const x = f.read(`${f.evidence}/full-maven-suite.command.json`); x.arguments = x.arguments.map(a => a.replace('jkxt.testcontainers.enabled=true', 'jkxt.testcontainers.enabled=false')); f.write(`${f.evidence}/full-maven-suite.command.json`, x); }, /NOT_FULL_SUITE/],
  ['another run report directory', f => { const x = f.read(`${f.evidence}/full-maven-suite.command.json`); x.arguments = x.arguments.map(a => a.startsWith('-Djkxt.test.reportsDirectory=') ? '-Djkxt.test.reportsDirectory=/another/junit' : a); f.write(`${f.evidence}/full-maven-suite.command.json`, x); }, /SUITE_OUTPUT_IDENTITY_MISMATCH/],
];
for (const [name, change, reason] of blockedCases) test(`retain BLOCKED: ${name}`, t => {
  const f = fixture(t); change(f); const result = f.run();
  assert.equal(result.status, 0, result.stderr); // Successful summarization is not a passing release gate.
  assert.equal(result.report.status, 'BLOCKED');
  assert.equal(result.report.productionReady, false);
  assert.match(result.report.blockers.join('\n'), reason);
  assert.notEqual(result.report.localGate?.status, 'PASS');
});

for (const outcome of ['failure', 'error', 'skipped']) test(`preserve actual ${outcome} XML counts without green promotion`, t => {
  const f = fixture(t), key = outcome === 'failure' ? 'failures' : outcome === 'error' ? 'errors' : 'skipped';
  f.write(`${f.evidence}/junit/TEST-fixture.xml`, `<testsuite name="Synthetic" tests="1" failures="${key === 'failures' ? 1 : 0}" errors="${key === 'errors' ? 1 : 0}" skipped="${key === 'skipped' ? 1 : 0}"><testcase name="positive" classname="fixture.FixtureTest"><${outcome}/></testcase></testsuite>`);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.report.tests[key], 1);
  assert.equal(result.report.tests.executed, outcome === 'skipped' ? 0 : 1);
  assert.equal(result.report.status, 'BLOCKED');
  assert.match(result.report.blockers.join('\n'), /TESTS_NOT_PASSED/);
});

test('legacy selected unit counts still work but cannot qualify as a full suite', t => {
  const f = fixture(t);
  f.command('legacy-unit', 'docs/release/evidence', ['test', '-Dtest=FixtureTest']);
  f.write('docs/release/evidence/legacy-unit/TEST-fixture.xml', xml);
  const result = f.run('legacy-unit');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.report.tests.executed, 1);
  assert.equal(result.report.status, 'BLOCKED');
  assert.match(result.report.blockers.join('\n'), /PARTIAL_EXECUTION/);
});

test('old failed execution is retained but never combined into a newer run', t => {
  const f = fixture(t);
  f.command('full-maven-suite', 'docs/release/evidence/old_run');
  const old = f.read('docs/release/evidence/old_run/full-maven-suite.command.json');
  old.exitCode = 1; f.write('docs/release/evidence/old_run/full-maven-suite.command.json', old);
  f.write('docs/release/evidence/old_run/junit/TEST-fixture.xml', xml.replace('failures="0"', 'failures="1"').replace('/></testsuite>', '><failure/></testcase></testsuite>'));
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.report.status, 'READY');
  assert.equal(result.report.tests.executed, 1);
  assert.equal(result.report.tests.failures, 0);
  assert.equal(result.report.history.find(h => h.commandRecord.includes('/old_run/')).failures, 1);
});

test('tampered selected log rejects collection without overwriting the previous report', t => {
  const f = fixture(t);
  f.write('docs/release/baseline.json', { marker: 'PRESERVE' });
  f.write(`${f.evidence}/full-maven-suite.txt`, 'tampered');
  const result = f.run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /COMMAND_LOG_CHANGED/);
  assert.deepEqual(result.report, { marker: 'PRESERVE' });
});
