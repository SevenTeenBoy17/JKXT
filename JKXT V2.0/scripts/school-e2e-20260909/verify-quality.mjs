// Read-only evidence validation. This does not execute the school workflow, provider,
// browser, or a Supervisor gate. Only the explicit frontend mode launches its existing
// isolated, mutation-blocking suite. Machine approval and formal identity remain external.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const OUT = path.join(ROOT, 'artifacts/school-e2e-20260909/gates');
const DATA = path.join(ROOT, 'artifacts/school-e2e-20260909/data');
const WORK = path.join(ROOT, 'artifacts/school-e2e-20260909/workflows');
const PROFILE = path.join(ROOT, '.agent-supervisor/quality-profile.json');
const SINCE = Date.parse('2026-09-09T02:16:39.719Z');
const BASE = 'http://127.0.0.1:18190/api/v1';
const MODES = ['dataset', 'workflows', 'authorization', 'ai', 'browser', 'visual', 'frontend', 'selftest', 'registration'];
const mode = process.argv[2];
assert(MODES.includes(mode) && process.argv.length === 3, `Use exactly one mode: ${MODES.join(', ')}`);
const require = createRequire(import.meta.url);
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const digest = file => sha(fs.readFileSync(file));
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const HEX = /^[a-f0-9]{64}$/i;
const slash = file => file.replaceAll('\\', '/');
const relative = file => slash(path.relative(ROOT, file));
const check = (value, message) => assert.ok(value, message);
function within(base, file) {
  const resolved = path.resolve(base, file);
  const rel = path.relative(base, resolved);
  check(rel && rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel), `Path outside evidence root: ${file}`);
  const real = fs.realpathSync(resolved);
  const realRel = path.relative(fs.realpathSync(base), real);
  check(realRel && realRel !== '..' && !realRel.startsWith(`..${path.sep}`) && !path.isAbsolute(realRel), 'Evidence symlink escapes its root');
  check(fs.statSync(real).isFile(), 'Evidence must be a regular file');
  return real;
}
function boundArtifact(base, ref) {
  check(ref && typeof ref.path === 'string' && HEX.test(ref.sha256), 'MISSING artifact path/hash binding');
  const file = within(base, ref.path);
  assert.equal(digest(file), ref.sha256.toLowerCase(), `Artifact digest changed: ${relative(file)}`);
  return file;
}
function fresh(start, end) {
  check(Number.isFinite(Date.parse(start)) && Date.parse(start) >= SINCE, 'MISSING current-round start timestamp');
  check(Date.parse(end) >= Date.parse(start) && Date.parse(end) <= Date.now() + 60000, 'Invalid completion timestamp');
}
function passedChecks(checks) {
  check(Array.isArray(checks) && checks.length > 0, 'MISSING nonempty behavioral checks');
  check(checks.every(c => c.pass === true || c.status === 'PASS'), 'A required check failed or is unavailable');
}
const twoRounds = rounds => assert.deepEqual(rounds.map(r => r.round), [1, 2], 'Two distinct declared rounds required');
const manifestBinding = hash => assert.equal(hash, digest(path.join(DATA, 'manifest.json')), 'Stale data manifest binding');
function realBrowserBoundary(report) {
  check(['http://127.0.0.1:18190', BASE].includes(report.baseUrl) && report.realBackend === true && report.syntheticSchool?.orgId, 'MISSING isolated real-backend browser boundary');
}
function providerEntry(entry) {
  check(entry && Date.parse(entry.startedAt) >= SINCE && Date.parse(entry.startedAt) <= Date.now(), 'Stale or missing provider response');
  check(entry.httpStatus === 200 && entry.status === 'passed' && entry.contentCorrect === true && entry.finishReason === 'stop', 'External AI call failed/unavailable');
  check(entry.providerRequestId && entry.responseModel && entry.usage?.total_tokens > 0, 'MISSING provider identity/usage');
  const response = JSON.parse(entry.content);
  check(response.nonce === entry.nonce && response.posts === 60 && typeof response.conclusion === 'string', 'AI result semantic validation failed');
}
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(d => d.isDirectory() ? walk(path.join(dir, d.name)) : d.isFile() ? [path.join(dir, d.name)] : []);
}
function sourceSnapshot(snapshot) {
  check(snapshot && Array.isArray(snapshot.files) && snapshot.files.length > 0, 'MISSING application source snapshot');
  const expected = ['Dashboard.html', ...walk(path.join(ROOT, 'src')).map(relative), ...walk(path.join(ROOT, 'backend/src/main')).map(relative)].sort();
  const actual = snapshot.files.map(f => slash(f.path)).sort();
  assert.deepEqual(actual, expected, 'Application snapshot must bind every current frontend/backend-main source file');
  for (const ref of snapshot.files) boundArtifact(ROOT, ref);
  if (snapshot.changedDuringRun !== undefined) assert.deepEqual(snapshot.changedDuringRun, [], 'Source changed during execution');
  return snapshot.files.length;
}
function dataManifest() {
  const manifest = read(path.join(DATA, 'manifest.json'));
  assert.equal(manifest.schema, 'school-e2e-dataset/v1');
  assert.equal(manifest.synthetic, true, 'Synthetic records must not be represented as verified personal data');
  assert.equal(manifest.teacherCount, 120);
  assert.equal(manifest.generatorSha256, digest(path.join(ROOT, 'scripts/school-e2e-20260909/generate-school-data.mjs')));
  assert.equal(manifest.xlsxVendorSha256, digest(path.join(ROOT, 'assets/vendor/xlsx.full.0.18.5.min.js')));
  check(Array.isArray(manifest.files) && manifest.files.length >= 10, 'MISSING dataset file manifest');
  assert.equal(new Set(manifest.files.map(f => f.path)).size, manifest.files.length, 'Duplicate dataset paths');
  for (const ref of manifest.files) {
    const file = boundArtifact(DATA, ref);
    assert.equal(fs.statSync(file).size, ref.bytes, 'Dataset size mismatch');
  }
  passedChecks(manifest.checks);
  twoRounds(manifest.rounds);
  check(manifest.rounds.every(r => r.noActualApiExecuted === true), 'Dataset must not claim API execution');
  return manifest;
}
function dataset() {
  const manifest = dataManifest();
  const XLSX = require(path.join(ROOT, 'assets/vendor/xlsx.full.0.18.5.min.js'));
  const rows = file => {
    const bytes = fs.readFileSync(within(DATA, file));
    check(bytes[0] === 0x50 && bytes[1] === 0x4b, 'XLSX must be real ZIP/OOXML');
    const book = XLSX.read(bytes, { type: 'buffer', cellFormula: true });
    check(book.SheetNames.includes('使用说明'), 'Synthetic usage label sheet required');
    for (const sheet of Object.values(book.Sheets)) for (const cell of Object.values(sheet)) check(!cell || typeof cell !== 'object' || !('f' in cell), 'Unexpected executable spreadsheet formula');
    return XLSX.utils.sheet_to_json(book.Sheets[book.SheetNames[0]], { defval: '', raw: true });
  };
  const teachers = rows('shared/teachers.xlsx'), classes = rows('shared/classes.xlsx'), rooms = rows('shared/classrooms.xlsx'), subjects = rows('shared/subjects.xlsx');
  assert.equal(teachers.length, 120); assert.equal(classes.length, 30); assert.equal(rooms.length, 36); assert.equal(subjects.length, 9);
  assert.equal(new Set(teachers.map(t => t['工号'])).size, 120);
  check(teachers.every(t => t['工号'].startsWith('SYN-') && t['姓名'].startsWith('合成') && !t['手机号'] && !t['邮箱']), 'Synthetic teachers require explicit labels and blank contacts');
  const teacherIds = new Set(teachers.map(t => t['工号'])), classIds = new Set(classes.map(c => c['班级编号'])), subjectNames = new Set(subjects.map(s => s['科目名称'])), roomMap = new Map(rooms.map(r => [r['考场编号'], r]));
  check(classes.every(c => teacherIds.has(c['班主任工号'])), 'Unresolved homeroom teacher');
  for (const round of manifest.rounds) {
    const exams = rows(round.allInput), keys = new Set();
    assert.equal(exams.length, 180);
    assert.equal(exams.reduce((n, e) => n + e['监考人数'], 0), 360);
    for (const e of exams) {
      const room = roomMap.get(e['考场编号']);
      check(room && classIds.has(e['班级']) && subjectNames.has(e['科目']), 'Exam foreign reference missing');
      check(e['人数'] > 0 && e['人数'] <= room['考试容量'], 'Room capacity exceeded');
      check(e['开始时间'] < e['结束时间'] && e['监考人数'] === 2, 'Invalid exam time/duty count');
      const key = `${e['考试日期']}|${e['开始时间']}|${e['考场编号']}`;
      check(!keys.has(key), 'Duplicate room/time allocation'); keys.add(key);
    }
  }
  return { status: 'PASS_OFFLINE_ONLY', teachers: teachers.length, rounds: 2, fileCount: manifest.files.length, manifestSha256: digest(path.join(DATA, 'manifest.json')) };
}
function phaseReport(phase) {
  check(fs.existsSync(WORK), 'MISSING workflow evidence directory');
  const dirs = fs.readdirSync(WORK).filter(n => n.endsWith(`-${phase}`) && fs.existsSync(path.join(WORK, n, 'result.json'))).sort();
  check(dirs.length > 0, `MISSING real HTTP phase ${phase}`);
  // Latest run wins, including a failure; never search backward until a green report is found.
  const dir = path.join(WORK, dirs.at(-1)), report = read(path.join(dir, 'result.json'));
  assert.equal(report.schema, 'school-e2e-real-http/v1'); assert.equal(report.phase, phase);
  fresh(report.startedAt, report.endedAt); assert.equal(report.baseUrl, BASE);
  check(report.synthetic === true && report.isolated === true, 'Wrong test data/runtime boundary');
  assert.equal(report.status, 'PASS', `Latest ${phase} run did not pass`); passedChecks(report.checks);
  manifestBinding(report.dataManifestSha256);
  const runner = path.join(ROOT, 'scripts/school-e2e-20260909/run-workflows.mjs');
  if (report.runnerSha256 !== digest(runner)) {
    const frozen = path.join(dir, 'runner-source.mjs');
    check(fs.existsSync(frozen), 'MISSING exact historical runner source; summary alone is insufficient');
    assert.equal(report.runnerSha256, digest(frozen), 'Historical runner hash mismatch');
  }
  sourceSnapshot(report.sourceSnapshot);
  check(report.sourceStableDuringPhase === true, 'MISSING unchanged source before/after the phase');
  const index = read(path.join(dir, 'http-index.json'));
  check(Array.isArray(index) && index.length > 0 && index.length === report.summary.requests, 'MISSING complete HTTP index');
  const requests = index.map(ref => {
    const record = read(boundArtifact(dir, ref));
    check(record.id === ref.id && Number.isInteger(record.status) && HEX.test(record.rawResponseSha256), 'MISSING actual HTTP response proof');
    check(record.path.startsWith('/') && !record.path.startsWith('//'), 'Unsafe recorded API path');
    check(Date.parse(record.startedAt) >= Date.parse(report.startedAt) && Date.parse(record.endedAt) <= Date.parse(report.endedAt), 'Request outside phase interval');
    return record;
  });
  return { dir, report, requests };
}
const succeeded = r => r.status >= 200 && r.status < 300;
function assertPublishChain(requests, revisionId) {
  const mutation = (action, status) => requests.find(r => r.method === 'POST' && r.path.endsWith(`/revisions/${revisionId}/${action}`) && succeeded(r) && r.response?.status === status);
  const submit = mutation('submit-approval', 'PENDING_APPROVAL'), approve = mutation('approve', 'APPROVED'), publish = mutation('publish', 'PUBLISHED');
  check(submit && approve && publish, 'MISSING submit/independent approval/publish HTTP state chain');
  check(publish.response.publishedAt && Date.parse(submit.startedAt) <= Date.parse(approve.startedAt) && Date.parse(approve.startedAt) <= Date.parse(publish.startedAt), 'Invalid publish state sequence');
  const identities = requests.filter(r => r.path === '/auth/login' && succeeded(r)).map(r => r.response?.user?.id).filter(Boolean);
  check(new Set(identities).size >= 2, 'MISSING different authenticated approver identity');
  const persistedApproval = requests.filter(r => r.method === 'GET' && r.path.startsWith('/approval-requests') && succeeded(r) && Array.isArray(r.response)).flatMap(r => r.response).find(a => a.revisionId === revisionId && a.status === 'APPROVED');
  const submitter = submit.actorId || submit.actor?.userId || persistedApproval?.requestedBy;
  const approver = approve.actorId || approve.actor?.userId || persistedApproval?.decidedBy;
  check(submitter && approver && submitter !== approver && identities.includes(submitter) && identities.includes(approver), 'MISSING approval action bound to different authenticated identities');
  check(requests.some(r => r.method === 'POST' && r.path.endsWith(`/revisions/${revisionId}/approve`) && r.status === 409 && r.response?.code === 'APPROVAL_SELF_REVIEW_FORBIDDEN'), 'MISSING actual self-approval rejection');
  check(requests.some(r => r.method === 'GET' && r.path.endsWith(`/revisions/${revisionId}`) && succeeded(r) && r.response?.status === 'PUBLISHED' && r.response?.publishedAt), 'MISSING published revision GET persistence readback');
  check(requests.some(r => r.method === 'GET' && /^\/exam-plans\/[^/]+$/.test(r.path) && succeeded(r) && r.response?.activeRevisionId === revisionId), 'MISSING active plan GET persistence readback');
}
function workflows() {
  dataManifest();
  const imported = phaseReport('import');
  const master = read(path.join(imported.dir, 'configured-master.json'));
  check(master.teachers?.length === 120 && master.classes?.length === 30 && master.classrooms?.length === 36, 'MISSING persisted imported school roster');
  for (const kind of ['teachers', 'classes', 'classrooms', 'subjects']) check(imported.requests.some(r => r.method === 'POST' && r.path === `/import/${kind}/commit` && succeeded(r)), `MISSING XLSX ${kind} import commit`);
  const rounds = [];
  for (const n of [1, 2]) {
    const ev = phaseReport(`round${n}`), s = ev.report.state?.rounds?.[n];
    check(s?.revisionId && s?.planId, 'MISSING persisted round revision identity');
    assertPublishChain(ev.requests, s.revisionId);
    const oracle = read(path.join(ev.dir, `oracle-round-${n}.json`));
    check(oracle.round === n && oracle.status === 'PASS' && oracle.hardViolations === 0 && oracle.failures.length === 0, 'Hard constraint oracle failed');
    check(oracle.metrics?.sessions === 180 && oracle.metrics?.duties === 360 && oracle.metrics?.seats > 0, 'MISSING complete large-school output coverage');
    check(ev.requests.some(r => r.method === 'GET' && r.path.startsWith('/planning-jobs/') && succeeded(r) && r.response?.status === 'SUCCEEDED'), 'MISSING real solver completed state');
    for (const kind of ['sessions', 'rooms', 'seats', 'duties']) check(ev.requests.some(r => r.method === 'GET' && r.path === `/plan-revisions/${s.revisionId}/${kind}` && succeeded(r) && Array.isArray(r.response) && r.response.length > 0), `MISSING ${kind} output GET`);
    const published = read(path.join(ev.dir, 'published-readback.json'));
    check(published.plan?.activeRevisionId === s.revisionId && published.revision?.status === 'PUBLISHED', 'Published readback disagrees with HTTP');
    check(published.deliveries?.length > 0 && published.deliveries.every(d => d.status === 'DELIVERED' && d.channel === 'IN_APP'), 'MISSING real in-app delivery persistence');
    rounds.push({ round: n, planId: s.planId, revisionId: s.revisionId, requests: ev.requests.length, evidence: relative(ev.dir) });
  }
  assert.equal(new Set(rounds.map(r => r.revisionId)).size, 2, 'Two distinct published revision runs required');
  // Negative and emergency paths are additive; positive happy-path results do not waive them.
  for (const phase of ['negative', 'emergency']) phaseReport(phase);
  return { rounds, boundary: 'HTTP and artifact integrity; not real-browser interaction or host-signed identity' };
}
function ai() {
  const root = path.join(ROOT, 'artifacts/school-e2e-20260909/ai');
  const files = fs.existsSync(root) ? walk(root).filter(f => path.basename(f) === 'results.json') : [];
  check(files.length > 0, 'MISSING external provider execution evidence');
  const file = files.sort().at(-1), report = read(file);
  assert.equal(report.schema, 'RealProviderProbe/v1');
  check(report.endpoint === 'https://ws-dgs4ei9jjypkwezu.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions' && report.fallback === false, 'Wrong provider boundary');
  check(Array.isArray(report.results) && report.results.length > 0, 'MISSING actual model responses');
  for (const entry of report.results) providerEntry(entry);
  const app = phaseReport('ai');
  const calls = app.requests.filter(r => r.method === 'POST' && r.path === '/nl-policy/parse');
  for (const model of ['qwen-plus', 'qwen-flash']) {
    const call = calls.find(r => r.request?.model === model && succeeded(r));
    check(call && call.response?.status === 'succeeded' && call.response.fallback === false && call.response.provider === 'aliyun' && call.response.model === model && call.response.policyId, `MISSING actual application ${model} policy parse`);
    check(call.response.dsl?.hard?.some(c => c.type === 'teacher_avoid_class' && c.enabled === true), 'MISSING expected AI hard policy result');
    const providers = app.requests.filter(r => r.method === 'GET' && r.path === '/ai/providers' && succeeded(r) && Date.parse(r.startedAt) >= Date.parse(call.endedAt)).at(-1);
    check(providers?.response?.recentCalls?.some(c => c.policyId === call.response.policyId || c.id === call.response.policyId), 'MISSING persisted AI recent-call audit projection');
  }
  return { models: report.results.map(r => r.responseModel), application: relative(app.dir), limitations: ['Daily max and overlap are not supported hard DSL types; this gate does not claim their AI-hard-rule execution.', 'Policy draft is not automatic publish authorization.'] };
}
function browserReport() {
  const file = path.join(ROOT, 'artifacts/school-e2e-20260909/browser/result.json');
  check(fs.existsSync(file), 'MISSING real browser evidence; isolated fixture suite does not substitute');
  const report = read(file); assert.equal(report.schema, 'school-e2e-browser/v1');
  fresh(report.startedAt, report.endedAt);
  realBrowserBoundary(report);
  sourceSnapshot(report.sourceSnapshot);
  check(Array.isArray(report.pageErrors) && report.pageErrors.length === 0, 'Browser errors not clear');
  return report;
}
function authorization() {
  const role = phaseReport('roleprobe'), teacher = phaseReport('teacher');
  const login = role.requests.find(r => r.path === '/auth/login' && succeeded(r) && r.response?.user?.role === 'TEACHER_QUERY');
  check(login, 'MISSING least-privilege TEACHER_QUERY persisted login');
  check(teacher.requests.some(r => [401, 403].includes(r.status)), 'MISSING actual unauthenticated/forbidden response');
  const crossOrg = teacher.report.checks.find(c => /cross.?org|跨.*租户|跨.*学校/i.test(c.id) && c.status === 'PASS');
  check(crossOrg && teacher.requests.some(r => r.id === crossOrg.evidenceId && [403, 404].includes(r.status)), 'MISSING cross-organization isolation actual denied HTTP request');
  return { roleEvidence: relative(role.dir), teacherEvidence: relative(teacher.dir) };
}
function browser() {
  const report = browserReport(); passedChecks(report.checks);
  check(Array.isArray(report.controlInventory) && report.controlInventory.length > 0, 'MISSING complete page/button inventory');
  for (const control of report.controlInventory) {
    if (control.disposition === 'not-applicable') { check(control.reason?.length >= 10, 'Not-applicable control needs actual reason'); continue; }
    const c = report.checks.find(v => v.controlId === control.id);
    check(c && c.expectedEffect && c.actualEffect, `MISSING observable result for control ${control.id}`);
    check(c.screenshotArtifact && c.domArtifact, 'MISSING DOM and screenshot proof');
    boundArtifact(ROOT, c.screenshotArtifact); boundArtifact(ROOT, c.domArtifact);
    if (c.effectType === 'business-mutation') {
      check(c.readbackArtifact && c.requestArtifact, 'MISSING real request/persisted readback, animation alone is not a business effect');
      const request = read(boundArtifact(ROOT, c.requestArtifact)), readback = read(boundArtifact(ROOT, c.readbackArtifact));
      check(succeeded(request) && readback.method === 'GET' && succeeded(readback) && readback.response !== undefined, 'Mutation/readback evidence invalid');
    } else if (c.effectType === 'download') boundArtifact(ROOT, c.downloadArtifact);
    else check(c.effectType === 'navigation' || c.effectType === 'local-ui', 'Unknown or unsupported button effect');
  }
  return { controls: report.controlInventory.length, checks: report.checks.length };
}
function visual() {
  const report = browserReport(); passedChecks(report.visualChecks);
  for (const width of [390, 1024, 1440]) check(report.visualChecks.some(c => c.viewport?.width === width), `MISSING ${width}px viewport visual checks`);
  for (const state of ['keyboard', 'empty', 'error', 'populated']) check(report.visualChecks.some(c => c.state === state), `MISSING ${state} UI state`);
  for (const c of report.visualChecks) {
    const file = boundArtifact(ROOT, c.screenshotArtifact), bytes = fs.readFileSync(file);
    check(bytes.length > 1000 && bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])), 'Screenshot is not a nonempty PNG');
    check(c.overflow === false && c.overlap === false && c.textReadable === true, 'Visual quality defect remains');
  }
  return { visualChecks: report.visualChecks.length, boundary: 'Evidence validated; subjective scores cannot override binary failures' };
}
function frontend() {
  const test = path.join(ROOT, 'scripts/frontend-quality/check-site-acceptance.cjs');
  const modulePath = 'C:/Users/nuoya/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright';
  check(fs.existsSync(path.join(modulePath, 'package.json')), 'MISSING bundled Playwright; no silent installation');
  const chromium = require(modulePath).chromium.executablePath();
  check(fs.existsSync(chromium), 'MISSING installed isolated Chromium');
  const start = Date.now(), testHash = digest(test);
  const result = spawnSync(process.execPath, [test], { cwd: ROOT, encoding: 'utf8', timeout: 300000, maxBuffer: 16 * 1024 * 1024, env: { ...process.env, JKXT_PLAYWRIGHT_MODULE: modulePath, PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH: chromium } });
  process.stdout.write(result.stdout || ''); process.stderr.write(result.stderr || '');
  check(!result.error && result.status === 0, `Fresh frontend suite failed: ${result.error?.message || result.status}`);
  assert.equal(digest(test), testHash, 'Suite source changed during test');
  const report = read(path.join(ROOT, 'artifacts/site-acceptance-20260907/frontend-results.json'));
  check(Date.parse(report.startedAt) >= start - 1000 && report.total >= 119 && report.passed === report.total, 'MISSING fresh full 119+ suite');
  assert.deepEqual(report.changedDuringRun, []);
  for (const [file, hash] of Object.entries(report.sourceHashes || {})) assert.equal(digest(within(ROOT, file)), hash, 'Frontend source drift');
  return { passed: report.passed, total: report.total, boundary: report.fixtures, browser: report.browser };
}
function selftest() {
  const tests = [];
  const rejects = (name, fn) => { assert.throws(fn); tests.push({ name, pass: true, expected: 'reject' }); };
  const accepts = (name, fn) => { fn(); tests.push({ name, pass: true, expected: 'accept unit fixture only' }); };
  rejects('empty evidence rejected', () => passedChecks([]));
  rejects('one round rejected', () => twoRounds([{ round: 1 }]));
  rejects('stale manifest rejected', () => manifestBinding('0'.repeat(64)));
  rejects('modified HTTP artifact rejected', () => boundArtifact(ROOT, { path: 'Dashboard.html', sha256: '0'.repeat(64) }));
  rejects('source drift rejected', () => boundArtifact(ROOT, { path: 'src/pages/exam-plans.jsx', sha256: 'f'.repeat(64) }));
  rejects('missing publish GET rejected', () => assertPublishChain([], 'fixture-revision'));
  rejects('synthetic-only browser rejected', () => realBrowserBoundary({ baseUrl: BASE, realBackend: false, syntheticSchool: { orgId: 'fixture-school' } }));
  rejects('missing provider response rejected', () => providerEntry(undefined));
  rejects('zero checks rejected', () => passedChecks([]));
  rejects('path escape rejected', () => within(OUT, '../verification-contract.json'));
  rejects('partial source snapshot rejected', () => sourceSnapshot({ files: [{ path: 'Dashboard.html', sha256: digest(path.join(ROOT, 'Dashboard.html')) }] }));
  const time = '2026-09-09T02:20:00Z', p = '/exam-plans/fixture-plan/revisions/fixture-revision';
  const request = (method, url, response, status = 200) => ({ method, path: url, response, status, startedAt: time });
  const fixture = [request('POST', '/auth/login', { user: { id: 'person-a' } }), request('POST', '/auth/login', { user: { id: 'person-b' } }), request('POST', p + '/submit-approval', { status: 'PENDING_APPROVAL' }), request('POST', p + '/approve', { status: 'APPROVED' }), request('POST', p + '/approve', { code: 'APPROVAL_SELF_REVIEW_FORBIDDEN' }, 409), request('POST', p + '/publish', { status: 'PUBLISHED', publishedAt: time }), request('GET', p, { status: 'PUBLISHED', publishedAt: time }), request('GET', '/exam-plans/fixture-plan', { activeRevisionId: 'fixture-revision' })];
  fixture[2].actorId = 'person-a'; fixture[3].actorId = 'person-b';
  accepts('valid state-chain unit fixture accepted', () => assertPublishChain(fixture, 'fixture-revision'));
  rejects('same approver identity rejected', () => assertPublishChain(fixture.filter(r => r.response?.user?.id !== 'person-b'), 'fixture-revision'));
  rejects('publish without GET rejected', () => assertPublishChain(fixture.filter(r => r.path !== p), 'fixture-revision'));
  accepts('nonempty checks accepted', () => passedChecks([{ pass: true }]));
  accepts('two rounds accepted', () => twoRounds([{ round: 1 }, { round: 2 }]));
  rejects('duplicate rounds rejected', () => twoRounds([{ round: 1 }, { round: 1 }]));
  rejects('failed checks rejected', () => passedChecks([{ pass: false }]));
  rejects('approval action identity missing rejected', () => assertPublishChain(fixture.map(r => ({ ...r, actorId: undefined })), 'fixture-revision'));
  return { tests, total: tests.length, passed: tests.length, boundary: 'Validator unit checks only. Fixtures are never written to workflow/browser/AI evidence.' };
}
function registration() {
  const profile = read(PROFILE), gates = [...profile.common_gates, ...(profile.gates || [])];
  assert.equal(new Set(gates.map(g => g.id)).size, gates.length, 'Duplicate gate IDs');
  for (const id of profile.global_gates) check(gates.some(g => g.id === id), `Required gate undefined: ${id}`);
  const aliases = { node: 'C:\\Program Files\\nodejs\\node.exe', docker: 'C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe' };
  const registrations = gates.filter(g => g.command).map(g => {
    if (g.command[0] === 'supervisor-trusted-core-runner') {
      const category = g.id === 'review.coderabbit' ? 'independent' : 'test-integrity';
      assert.deepEqual(g.command, ['supervisor-trusted-core-runner', 'bin/run-coderabbit-review.py', '--review-category', category]);
      return { gateId: g.id, command: g.command, runtime: 'immutable-core', allowedArgvApprovalRequired: false, machineRegistryEdited: false, status: 'NOT_EXECUTED' };
    }
    const executable = aliases[g.command[0]]; check(executable && fs.existsSync(executable), `MISSING registered executable alias: ${g.command[0]}`);
    const canonicalArgv = [executable, ...g.command.slice(1)];
    return { gateId: g.id, criterionId: g.criterion_id, registryAlias: g.command[0], executable, executableSha256: digest(executable), canonicalArgv, canonicalArgvJson: JSON.stringify(canonicalArgv), allowedArgvSha256: sha(JSON.stringify(canonicalArgv)), cwd: ROOT, machineRegistryEdited: false, status: 'REQUIRES_HOST_OWNER_APPROVAL' };
  });
  const plan = { schema: 'SchoolGateRegistrationPlan/v1', goal: 'goal-1c7c93e5b99d9351', goalVersion: 4, generatedAt: new Date().toISOString(), profilePath: relative(PROFILE), profileSha256: digest(PROFILE), validatorSha256: digest(fileURLToPath(import.meta.url)), approvalsApplied: false, status: 'PROJECT_PREPARATION_ONLY', registryRisk: 'T3 host-owner approval required. Do not edit core/keys/history, relax gates, or claim runner/reviewer identity.', knownRegistryBlocker: 'Stale pwsh executable SHA makes the entire registry fail-closed. Actual Git executable remains usable.', nextSteps: ['Host owner verifies and authorizes executable digest/argv updates.', 'After approval, use supported future bootstrap to load this profile; do not rewrite existing round state.', 'Freeze the exact diff and run immutable independent and test-integrity reviews separately.', 'Use core gate requests only; project-local success is not formal approval.'], registrations };
  const file = path.join(OUT, 'registration-plan.json');
  fs.writeFileSync(file, JSON.stringify(plan, null, 2) + '\n');
  return { file: relative(file), sha256: digest(file), registrations: registrations.length, formal: false };
}
fs.mkdirSync(OUT, { recursive: true });
const startedAt = new Date().toISOString(); let details, failure;
try { details = ({ dataset, workflows, authorization, ai, browser, visual, frontend, selftest, registration })[mode](); }
catch (error) { failure = { name: error.name, message: error.message, stack: error.stack?.split('\n').slice(0, 5) }; }
const report = { schema: 'SchoolQualityLocalValidation/v1', mode, startedAt, endedAt: new Date().toISOString(), status: failure ? 'FAIL_OR_MISSING' : 'PASS_LOCAL_ONLY', formalGateExecuted: false, collector: 'project-local-validator; no trusted identity claim', validatorSha256: digest(fileURLToPath(import.meta.url)), details, failure };
const file = path.join(OUT, `${startedAt.replace(/[:.]/g, '-')}-${mode}.json`);
fs.writeFileSync(file, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ ...report, evidence: relative(file) }, null, 2));
if (failure) process.exitCode = 1;
