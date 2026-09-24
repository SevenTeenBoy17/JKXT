import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';

// Synthetic, disposable Git fixtures only. No Maven, Docker, database or network calls.
const helperDirectory = path.dirname(fileURLToPath(import.meta.url));
const quote = value => `'${String(value).replaceAll("'", "''")}'`;
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jkxt-command-recorder-test-'));
  t.after(() => {
    const resolved = fs.realpathSync(root);
    assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
    assert.match(path.basename(resolved), /^jkxt-command-recorder-test-/);
    fs.rmSync(resolved, { recursive: true });
  });
  const scripts = path.join(root, 'scripts/release');
  fs.mkdirSync(scripts, { recursive: true });
  for (const name of ['record-command.ps1', 'capture-source.mjs', 'check-release.mjs', 'execution-package.mjs']) {
    fs.copyFileSync(path.join(helperDirectory, name), path.join(scripts, name));
  }
  fs.mkdirSync(path.join(root, 'backend/src/test/java'), { recursive: true });
  fs.writeFileSync(path.join(root, 'backend/src/test/java/FixtureTest.java'),
    'package fixture; class FixtureTest { @Test void required() {} }\n');
  fs.writeFileSync(path.join(root, 'source.java'), 'class SyntheticRecorderFixture {}\n');
  const git = (...args) => execFileSync('git', args, { cwd: root, stdio: 'pipe', encoding: 'utf8' });
  git('init', '--quiet');
  git('add', '.');
  git('-c', 'user.name=Recorder Fixture', '-c', 'user.email=fixture@example.invalid',
    'commit', '--quiet', '-m', 'Synthetic recorder fixture, not product evidence');
  const evidence = path.join(root, 'docs/release/evidence');
  const env = { ...process.env };
  for (const name of Object.keys(env)) {
    if (/^(?:JKXT_|SPRING_|POSTGRES_|REDIS_|MAVEN_|DOCKER_|TESTCONTAINERS_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|NODE_OPTIONS$)/i.test(name)) {
      delete env[name];
    }
  }
  const pathKey = Object.keys(env).find(name => name.toLowerCase() === 'path') ?? 'PATH';
  env[pathKey] = `${path.dirname(process.execPath)}${path.delimiter}${env[pathKey] ?? ''}`;
  function run(id, executable, args = []) {
    const command = `$ErrorActionPreference = 'Stop'; & ${quote(path.join(scripts, 'record-command.ps1'))} -Id ${quote(id)} ` +
      `-Executable ${quote(executable)} -Arguments @(${args.map(quote).join(',')}) ` +
      `-WorkingDirectory ${quote(root)} -EvidenceDirectory ${quote(evidence)}; exit $LASTEXITCODE`;
    const result = spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-EncodedCommand', Buffer.from(command, 'utf16le').toString('base64')],
    { cwd: root, env, encoding: 'utf8', timeout: 30000, windowsHide: true });
    assert.ifError(result.error);
    assert.equal(result.signal, null, result.stderr);
    return result;
  }
  const record = id => readJson(path.join(evidence, `${id}.command.json`));
  const snapshot = (id, after = false) => readJson(path.join(evidence, `${id}.source${after ? '-after' : ''}.json`));
  return { root, evidence, run, record, snapshot };
}

test('native success records exit 0 and matching before/after source identity', t => {
  const f = fixture(t);
  const run = f.run('native-success', process.execPath, ['-e', 'console.log("synthetic recorder success")']);
  assert.equal(run.status, 0, run.stderr);
  const record = f.record('native-success');
  assert.equal(record.exitCode, 0);
  assert.equal(record.nativeExitCode, 0);
  assert.equal(record.sourceUnchanged, true);
  assert.equal(record.sourceDigest, f.snapshot('native-success').sha256);
  assert.equal(record.sourceDigest, f.snapshot('native-success', true).sha256);
});

test('native nonzero exit is preserved and cannot be overwritten by successful snapshot capture', t => {
  const f = fixture(t);
  const run = f.run('native-failure', process.execPath, ['-e', 'console.log("expected synthetic failure"); process.exit(7)']);
  assert.equal(run.status, 7, run.stderr);
  assert.equal(f.record('native-failure').exitCode, 7);
  assert.equal(f.record('native-failure').nativeExitCode, 7);
  assert.equal(f.record('native-failure').sourceUnchanged, true);
});

test('a command with no native exit code cannot reuse source-capture success as exit 0', t => {
  const f = fixture(t);
  const run = f.run('missing-exit', 'Write-Output', ['synthetic PowerShell cmdlet, no native exit code']);
  assert.equal(run.status, 127, run.stderr);
  assert.equal(f.record('missing-exit').exitCode, 127);
  assert.equal(f.record('missing-exit').nativeExitCode, 127);
});

test('invocation failure is recorded as nonzero instead of inheriting a stale exit code', t => {
  const f = fixture(t);
  // Existing harmless cmdlet; nonexistent fixture path produces a terminating invocation error.
  const missing = path.join(f.root, 'intentionally-absent-file');
  const run = f.run('invocation-error', 'Get-Item', [missing]);
  assert.equal(run.status, 127, run.stderr);
  assert.equal(f.record('invocation-error').exitCode, 127);
  assert.match(fs.readFileSync(path.join(f.evidence, 'invocation-error.txt'), 'utf8'), /intentionally-absent-file/);
});

test('native exit 0 is rejected as 86 when source changes while the command runs', t => {
  const f = fixture(t);
  const run = f.run('source-drift', process.execPath, ['-e',
    'require("node:fs").appendFileSync("source.java", "// changed by synthetic test\\n"); console.log("drift fixture")']);
  assert.equal(run.status, 86, run.stderr);
  const record = f.record('source-drift');
  assert.equal(record.nativeExitCode, 0);
  assert.equal(record.exitCode, 86);
  assert.equal(record.sourceUnchanged, false);
  assert.notEqual(f.snapshot('source-drift').sha256, f.snapshot('source-drift', true).sha256);
});

test('duplicate evidence id is refused without changing any original evidence bytes', t => {
  const f = fixture(t);
  const initial = f.run('duplicate', process.execPath, ['-e', 'console.log("original synthetic output")']);
  assert.equal(initial.status, 0, initial.stderr);
  const originals = new Map(fs.readdirSync(f.evidence).map(name =>
    [name, fs.readFileSync(path.join(f.evidence, name))]));
  const repeated = f.run('duplicate', process.execPath, ['-e', 'console.log("must never run")']);
  assert.notEqual(repeated.status, 0);
  assert.match(repeated.stderr, /Evidence id already exists/);
  assert.deepEqual(fs.readdirSync(f.evidence).sort(), [...originals.keys()].sort());
  for (const [name, bytes] of originals) assert.deepEqual(fs.readFileSync(path.join(f.evidence, name)), bytes, name);
});

test('an orphan source snapshot also refuses reuse and preserves original bytes', t => {
  const f = fixture(t);
  fs.mkdirSync(f.evidence, { recursive: true });
  const snapshot = path.join(f.evidence, 'orphan.source.json');
  const original = Buffer.from('synthetic orphan snapshot must not be overwritten');
  fs.writeFileSync(snapshot, original);
  const result = f.run('orphan', process.execPath, ['-e', 'console.log("must never run")']);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Evidence id already exists/);
  assert.deepEqual(fs.readFileSync(snapshot), original);
  assert.deepEqual(fs.readdirSync(f.evidence), ['orphan.source.json']);
});
