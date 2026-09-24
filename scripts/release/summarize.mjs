// Reconstruct exact-run evidence; a G0/G1 baseline is never production acceptance.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { hash, collectSourceSnapshot, discoverTestClasses, discoverTestMethods, parseJUnit, validateRelease } from './check-release.mjs';
import { validateExecutionPackage } from './execution-package.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const release = path.join(root, 'docs/release');
const evidence = path.join(release, 'evidence');
const selected = process.argv[2];
const demand = (condition, message) => { if (!condition) throw new Error(message); };
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const relative = file => path.relative(root, file).replaceAll('\\', '/') || '.';
const emptyCounts = () => ({ discovered: 0, executed: 0, failures: 0, errors: 0, skipped: 0 });
function contained(file, boundary = root) {
  const rel = path.relative(fs.realpathSync(boundary), fs.realpathSync(file));
  demand(rel && rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel), `UNSAFE_EVIDENCE_PATH: ${file}`);
  return file;
}
function files(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const file = contained(path.join(dir, e.name), evidence);
    demand(!e.isSymbolicLink(), `UNSAFE_EVIDENCE_LINK: ${file}`);
    return e.isDirectory() ? files(file) : [file];
  });
}
function sibling(recordFile, name) {
  demand(typeof name === 'string' && name === path.basename(name) && !/[\\/]/.test(name), `UNSAFE_EVIDENCE_PATH: ${name}`);
  return contained(path.join(path.dirname(recordFile), name), path.dirname(recordFile));
}
function loadRecord(recordFile) {
  const record = read(recordFile);
  demand(record.schema === 'JKXT.CommandEvidence/v1' && /^[a-z0-9-]+$/.test(record.id), `INVALID_COMMAND_RECORD: ${recordFile}`);
  demand(path.basename(recordFile) === `${record.id}.command.json`, `COMMAND_FILENAME_MISMATCH: ${recordFile}`);
  const log = sibling(recordFile, record.output);
  demand(hash(fs.readFileSync(log)) === record.outputSha256, `COMMAND_LOG_CHANGED: ${relative(recordFile)}`);
  // A run-isolated full suite writes junit/, whereas historical narrow runs use <command-id>/.
  const dir = path.join(path.dirname(recordFile), record.id === 'full-maven-suite' ? 'junit' : record.id);
  const reports = fs.existsSync(dir) ? fs.readdirSync(contained(dir, evidence)).filter(n => /^TEST-.*\.xml$/.test(n)).sort().map(n => contained(path.join(dir, n), evidence)) : [];
  const counts = emptyCounts(), refs = [];
  for (const file of reports) {
    const data = fs.readFileSync(file), parsed = parseJUnit(data.toString('utf8'));
    for (const key of Object.keys(counts)) counts[key] += parsed[key];
    refs.push({ path: relative(file), sha256: hash(data), commandId: record.id,
      gitSha: record.gitSha, sourceDigest: record.sourceDigest ?? 'MISSING_PRE_RUN_DIGEST' });
  }
  return { record, recordFile, recordPath: relative(recordFile), counts, refs,
    xmlStatus: reports.length ? 'COLLECTED' : 'NOT_COLLECTED', logPath: relative(log) };
}
function command(item, runId) {
  const r = item.record;
  return { id: r.id, status: 'EXECUTED', exitCode: r.exitCode, argv: [r.executable, ...r.arguments],
    cwd: relative(r.cwd), startedAt: r.startedUtc, finishedAt: r.endedUtc, runId,
    gitSha: r.gitSha, sourceDigest: r.sourceDigest, log: { path: item.logPath, sha256: r.outputSha256 } };
}

function main() {
  demand(process.argv.length === 3 && /^[a-z0-9_-]+$/.test(selected ?? ''), 'USAGE: Pass one exact isolated-run directory or an unambiguous historical command ID');
  contained(evidence);
  const recordFiles = files(evidence).filter(f => f.endsWith('.command.json'));
  const runDirectory = path.join(evidence, selected);
  const fullFile = path.join(runDirectory, 'full-maven-suite.command.json');
  let selectedFile, isolated = false;
  if (fs.existsSync(fullFile)) { selectedFile = contained(fullFile, evidence); isolated = true; }
  else {
    const matches = recordFiles.filter(f => path.basename(f) === `${selected}.command.json`);
    demand(matches.length <= 1, 'AMBIGUOUS_EXECUTION: Select the exact isolated-run directory, not a repeated command ID');
    demand(matches.length === 1, 'EXECUTION_NOT_FOUND: No command for this selector; a preflight-only run has no executed full suite');
    selectedFile = matches[0];
    // Selecting the full-suite command ID is still narrow identity, not a run identity.
  }
  const active = loadRecord(selectedFile), source = collectSourceSnapshot(root);
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const blockers = [], history = [];
  for (const file of recordFiles) {
    try {
      const x = file === selectedFile ? active : loadRecord(file), r = x.record;
      history.push({ id: r.id, exitCode: r.exitCode, startedUtc: r.startedUtc, endedUtc: r.endedUtc,
        gitSha: r.gitSha, commandRecord: x.recordPath, xmlStatus: x.xmlStatus, ...x.counts });
    } catch (error) {
      // Retain damaged unrelated history explicitly; it cannot be selected as passing evidence.
      history.push({ commandRecord: relative(file), evidenceStatus: 'INVALID', reason: error.message });
    }
  }
  const packageFile = path.join(release, 'execution-package.json');
  let executionPackage = { status: 'MISSING', scenarioCount: 0 };
  if (fs.existsSync(packageFile)) {
    const pkg = read(contained(packageFile));
    validateExecutionPackage(root, pkg);
    executionPackage = { status: 'VERIFIED', scenarioCount: pkg.scenarios.length,
      architectureSectionCount: pkg.architectureDecisions.length,
      artifact: { path: relative(packageFile), sha256: hash(fs.readFileSync(packageFile)) },
      note: 'Source requirements verified only; no business scenario is marked PASS.' };
  }
  let commands = [command(active, selected)], runtimeMappings = { status: 'NOT_COLLECTED', count: 0 };
  let environment = { status: 'NOT_VERIFIED' };
  if (!isolated) blockers.push('PARTIAL_EXECUTION: Historical unit/slice evidence does not establish an isolated full suite.');
  if (isolated) {
    try {
      const envFile = contained(path.join(runDirectory, 'environment.json'), runDirectory), env = read(envFile);
      environment = { ...env, artifact: { path: relative(envFile), sha256: hash(fs.readFileSync(envFile)) } };
      const ports = [env.databaseEndpoint, env.redisEndpoint].map(value => /^127\.0\.0\.1:(\d+)$/.exec(value ?? '')?.[1]).map(Number);
      demand(env.status === 'READY' && env.run === selected && env.database === `jkxt_test_${selected}`
        && env.project === `jkxt-test-${selected.replaceAll('_', '-')}`
        && env.ephemeralStorage === true && env.businessDatabaseAccessed === false && env.testcontainersEnabled === true
        && ports.every(p => p >= 1024 && p <= 65535 && ![5432, 6379].includes(p)) && ports[0] !== ports[1], 'ISOLATION_NOT_PROVEN: Fresh dedicated test database/Redis metadata required');
      const required = ['docker-preflight', 'infrastructure-up'].map(id => loadRecord(contained(path.join(runDirectory, `${id}.command.json`), runDirectory)));
      commands = [...required, active].map(item => command(item, selected));
      for (const item of [...required, active]) {
        demand(item.record.sourceUnchanged === true, `SOURCE_CHANGED_DURING_COMMAND: ${item.record.id}`);
        for (const field of ['sourceSnapshot', 'sourceAfter']) {
          const snapshot = read(sibling(item.recordFile, item.record[field]));
          demand(snapshot.sha256 === source.sha256 && JSON.stringify(snapshot.files) === JSON.stringify(source.files), `COMMAND_SOURCE_SNAPSHOT_MISMATCH: ${item.record.id}/${field}`);
        }
      }
      const before = read(contained(path.join(runDirectory, 'before-suite-source.json'), runDirectory));
      demand(before.sha256 === source.sha256 && JSON.stringify(before.files) === JSON.stringify(source.files), 'SUITE_SOURCE_SNAPSHOT_MISMATCH');
      const args = active.record.arguments;
      const property = name => {
        const values = args.filter(a => a.startsWith(`-D${name}=`));
        demand(values.length === 1, `MISSING_OR_DUPLICATE_SUITE_PROPERTY: ${name}`);
        return values[0].slice(name.length + 3);
      };
      demand(args.includes('test') && property('jkxt.testcontainers.enabled') === 'true'
        && !args.some(a => /^-D(?:test|surefire\.excludes|excludeTests|groups|excludedGroups)(?:=|$)/.test(a)), 'NOT_FULL_SUITE: Unfiltered Maven tests with migration containers required');
      demand(path.resolve(property('jkxt.test.reportsDirectory')) === path.resolve(runDirectory, 'junit')
        && path.resolve(property('jkxt.release.mappingOutput')) === path.resolve(runDirectory, 'spring-mappings.json')
        && property('jkxt.release.runId') === selected && property('jkxt.release.gitSha') === head
        && property('jkxt.release.sourceDigest') === source.sha256, 'SUITE_OUTPUT_IDENTITY_MISMATCH');
    } catch (error) { blockers.push(error.message); }
    const mappingFile = path.join(runDirectory, 'spring-mappings.json');
    if (fs.existsSync(mappingFile)) {
      const runtime = read(contained(mappingFile, runDirectory));
      runtimeMappings = { status: 'COLLECTED', count: runtime.mappings?.length ?? 0, commandId: active.record.id,
        artifact: { path: relative(mappingFile), sha256: hash(fs.readFileSync(mappingFile)) } };
    }
  }
  const baseline = {
    schema: 'JKXTReleaseBaseline/v1', phase: 'g0-g1', status: 'READY', blockers,
    productionReady: false, generatedAt: new Date().toISOString(), gitSha: head, runId: selected, sourceSnapshot: source,
    commands, environment,
    tests: { status: active.refs.length ? 'EXECUTED' : 'NOT_RUN', requiredClasses: discoverTestClasses(root, source),
      declaredMethods: discoverTestMethods(root, source).length, ...active.counts,
      reports: active.refs.map(ref => ({ ...ref, runId: selected })),
      note: 'Counts are parsed from selected actual JUnit bytes. Missing source classes/methods and skips remain gate blockers.' },
    executionPackage, runtimeMappings,
    remainingReleaseScope: 'G2-G8, 84 business scenarios, external providers, production deployment and customer acceptance remain separate; this report cannot authorize them.',
    history: history.sort((a, b) => (a.startedUtc ?? '').localeCompare(b.startedUtc ?? ''))
  };
  const destination = path.join(release, 'baseline.json');
  if (fs.existsSync(destination)) contained(destination, release);
  // Reuse the fail-closed release validator rather than implementing a weaker parallel gate.
  // Never expose a provisional READY baseline if validation is interrupted.
  const candidate = path.join(release, `baseline.${process.pid}.candidate.json`);
  const localBlockers = [...blockers];
  baseline.blockers = [];
  fs.writeFileSync(candidate, JSON.stringify(baseline, null, 2), { flag: 'wx' });
  try { baseline.localGate = validateRelease({ root, reportPath: relative(candidate) }); }
  catch (error) { blockers.push(error.message); }
  finally { fs.unlinkSync(candidate); }
  baseline.blockers = [...new Set([...localBlockers, ...blockers])];
  baseline.status = baseline.blockers.length ? 'BLOCKED' : 'READY';
  if (baseline.status === 'BLOCKED' && baseline.localGate?.status === 'PASS') delete baseline.localGate;
  fs.writeFileSync(destination, JSON.stringify(baseline, null, 2));
  console.log(JSON.stringify({ status: baseline.status, productionReady: false, gitSha: head, sourceDigest: source.sha256,
    sourceClasses: baseline.tests.requiredClasses.length, declaredMethods: baseline.tests.declaredMethods,
    selected, counts: active.counts, runtimeMappings: runtimeMappings.count, historyCount: history.length, blockers: baseline.blockers }, null, 2));
}
try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
