// Reconstruct evidence from actual command records and JUnit bytes; never invent passing runs.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { hash, collectSourceSnapshot, discoverTestClasses, discoverTestMethods, parseJUnit } from './check-release.mjs';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const release = path.join(root, 'docs/release');
const selected = process.argv[2];
if (!selected || !/^[a-z0-9-]+$/.test(selected)) throw new Error('Pass the exact latest unit-run id');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const relative = file => path.relative(root, file).replaceAll('\\', '/');
const files = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? files(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const countsFor = recordFile => {
  const record = read(recordFile);
  const dir = path.join(path.dirname(recordFile), record.id);
  const reports = fs.existsSync(dir) ? fs.readdirSync(dir).filter(n => /^TEST-.*\.xml$/.test(n)).map(n => path.join(dir, n)) : [];
  const counts = { discovered: 0, executed: 0, failures: 0, errors: 0, skipped: 0 };
  const refs = [];
  for (const file of reports) {
    const data = fs.readFileSync(file);
    const parsed = parseJUnit(data.toString('utf8'));
    for (const key of Object.keys(counts)) counts[key] += parsed[key];
    refs.push({ path: relative(file), sha256: hash(data), commandId: record.id,
      runId: selected, gitSha: record.gitSha, sourceDigest: record.sourceDigest ?? 'MISSING_PRE_RUN_DIGEST' });
  }
  const log = path.join(path.dirname(recordFile), record.output);
  if (hash(fs.readFileSync(log)) !== record.outputSha256) throw new Error(`Command log changed: ${record.id}`);
  return { record, recordPath: relative(recordFile), counts, refs,
    xmlStatus: reports.length ? 'COLLECTED' : 'NOT_COLLECTED', logPath: relative(log) };
};
const all = files(path.join(release, 'evidence')).filter(f => f.endsWith('.command.json')).map(countsFor);
const active = all.find(x => x.record.id === selected);
if (!active) throw new Error('Selected execution does not exist');
const source = collectSourceSnapshot(root);
const head = execFileSync('git', ['rev-parse','HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const blockers = [
  'Docker engine unavailable; dedicated PostgreSQL 16 / Redis 7 were not created; DB suites BLOCKED.',
  'Original 84-scenario execution package is MISSING; no scenario coverage has been invented.',
  'Spring runtime inventory attempt was rejected by TestDatabaseIsolationGuard before storage access; actual mappings NOT_COLLECTED.',
  'Full integration, migration containers, DB/export assertions and all G2+ release criteria remain NOT_RUN.'
];
if (active.record.gitSha !== head || active.record.sourceDigest !== source.sha256) blockers.push('Selected unit evidence is not bound to the current complete source snapshot; rerun after freeze.');
const baseline = {
  schema: 'JKXTReleaseBaseline/v1', phase: 'g0-g1', status: 'BLOCKED', blockers,
  generatedAt: new Date().toISOString(), gitSha: head, runId: selected, sourceSnapshot: source,
  commands: [{ id: active.record.id, status:'EXECUTED', exitCode: active.record.exitCode,
    argv:[active.record.executable,...active.record.arguments], cwd: relative(active.record.cwd),
    startedAt: active.record.startedUtc, finishedAt: active.record.endedUtc, runId: selected,
    gitSha: active.record.gitSha, sourceDigest: active.record.sourceDigest,
    log:{path:active.logPath,sha256:active.record.outputSha256} }],
  tests: { status:'BLOCKED', requiredClasses:discoverTestClasses(root,source),
    declaredMethods:discoverTestMethods(root,source).length,...active.counts,reports:active.refs,
    note:'Counts describe only the selected actually executed unit/slice subset. Unrun classes are not skips or passes.' },
  executionPackage:{status:'MISSING',scenarioCount:0},
  runtimeMappings:{status:'BLOCKED',count:0,commandId:'runtime-mapping-guard-block'},
  history: all.map(x => ({ id:x.record.id,exitCode:x.record.exitCode,startedUtc:x.record.startedUtc,
    endedUtc:x.record.endedUtc,gitSha:x.record.gitSha,commandRecord:x.recordPath,
    xmlStatus:x.xmlStatus,...x.counts })).sort((a,b)=>a.startedUtc.localeCompare(b.startedUtc))
};
fs.writeFileSync(path.join(release,'baseline.json'),JSON.stringify(baseline,null,2));
console.log(JSON.stringify({status:baseline.status,gitSha:head,sourceDigest:source.sha256,
  sourceClasses:baseline.tests.requiredClasses.length,declaredMethods:baseline.tests.declaredMethods,
  selected,counts:active.counts,historyCount:all.length,blockers},null,2));
