#!/usr/bin/env node
// Evidence validation only. This command neither executes tests nor authorizes deployment.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { validateExecutionPackage } from './execution-package.mjs';

const HEX = /^[a-f0-9]{64}$/;
const SHA = /^[a-f0-9]{40}$/;
const OMIT = /^docs\/release\//;
const slash = p => p.replaceAll('\\', '/');
export const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const demand = (condition, code, message) => { if (!condition) throw new Error(`${code}: ${message}`); };
const integer = n => Number.isSafeInteger(n) && n >= 0;
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const parseJson = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));

function inside(root, relative) {
  demand(nonempty(relative) && !path.isAbsolute(relative) && !relative.includes('\\') && !relative.split('/').includes('..'), 'UNSAFE_PATH', String(relative));
  const resolved = path.resolve(root, relative);
  const real = fs.realpathSync(resolved);
  const rel = path.relative(fs.realpathSync(root), real);
  demand(rel && rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel) && fs.statSync(real).isFile(), 'UNSAFE_PATH', relative);
  return real;
}

function artifact(root, ref) {
  demand(ref && nonempty(ref.path) && HEX.test(ref.sha256), 'MISSING_ARTIFACT', 'path and SHA-256 are required');
  const file = inside(root, ref.path);
  demand(hash(fs.readFileSync(file)) === ref.sha256, 'ARTIFACT_HASH_MISMATCH', ref.path);
  demand(fs.statSync(file).size > 0, 'EMPTY_ARTIFACT', ref.path);
  return file;
}

export function collectSourceSnapshot(root) {
  const output = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: root, encoding: 'utf8' });
  const paths = [...new Set(output.split('\0').filter(Boolean).map(slash).filter(p => !OMIT.test(p)))].sort();
  demand(paths.length > 0, 'EMPTY_SOURCE', 'Git source list is empty');
  const files = paths.map(p => { const data = fs.readFileSync(inside(root, p)); return { path: p, bytes: data.length, sha256: hash(data) }; });
  return { files, sha256: hash(JSON.stringify(files)) };
}

function javaStructure(source) {
  // Preserve positions/newlines, erase comments and literal contents before reading declarations.
  return source.replace(/"""[\s\S]*?"""|"(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*'|\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g, value => value.replace(/[^\r\n]/g, ' '));
}

function testDeclarations(root, snapshot) {
  const declarations = [];
  for (const ref of snapshot.files) {
    if (!/(?:^|\/)backend\/src\/test\/java\/.+\.java$/.test(ref.path)) continue;
    const name = path.posix.basename(ref.path, '.java');
    const source = javaStructure(fs.readFileSync(inside(root, ref.path), 'utf8'));
    const annotations = [...source.matchAll(/@(?:(?:org\.junit\.jupiter\.(?:api|params))\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/g)];
    if (!annotations.length) continue;
    demand(/^(?:Test.+|.+Tests?|.+TestCase)$/.test(name), 'UNSUPPORTED_TEST_DISCOVERY', ref.path);
    const packageName = /^\s*package\s+([\w.]+)\s*;/m.exec(source)?.[1];
    demand(packageName, 'UNKNOWN_TEST_PACKAGE', ref.path);
    demand(!/@(?:[\w.]*\.)?Nested\b|\bclass\s+\w+\s+extends\b/.test(source), 'UNSUPPORTED_TEST_DISCOVERY', `Nested/inherited test structure needs explicit discovery support: ${ref.path}`);
    const className = `${packageName}.${name}`;
    const methods = [];
    for (const match of annotations) {
      demand(['Test', 'ParameterizedTest'].includes(match[1]), 'UNSUPPORTED_TEST_DISCOVERY', `${match[1]} requires an independent invocation manifest: ${ref.path}`);
      let depth = 0;
      for (const c of source.slice(0, match.index)) { if (c === '{') depth++; if (c === '}') depth--; }
      demand(depth === 1, 'UNSUPPORTED_TEST_DISCOVERY', `Test annotation outside top-level test class: ${ref.path}`);
      let rest = source.slice(match.index + match[0].length).trimStart();
      const skipArguments = () => {
        if (!rest.startsWith('(')) return;
        let level = 0, end = -1;
        for (let i = 0; i < rest.length; i++) { if (rest[i] === '(') level++; if (rest[i] === ')' && --level === 0) { end = i; break; } }
        demand(end >= 0, 'UNSUPPORTED_TEST_DISCOVERY', `Unbalanced annotation: ${ref.path}`);
        rest = rest.slice(end + 1).trimStart();
      };
      skipArguments();
      while (rest.startsWith('@')) {
        const annotation = /^@[\w.]+/.exec(rest);
        demand(annotation, 'UNSUPPORTED_TEST_DISCOVERY', ref.path);
        rest = rest.slice(annotation[0].length).trimStart();
        skipArguments();
      }
      const method = /^(?:(?:public|protected|private|final|static|synchronized)\s+)*void\s+([\w$]+)\s*\(/.exec(rest);
      demand(method, 'UNSUPPORTED_TEST_DISCOVERY', `Cannot safely identify annotated test method: ${ref.path}`);
      demand(!methods.includes(method[1]), 'UNSUPPORTED_TEST_DISCOVERY', `Overloaded/duplicate test method needs signature-aware discovery: ${className}#${method[1]}`);
      methods.push(method[1]);
    }
    declarations.push({ className, methods });
  }
  demand(declarations.length > 0, 'NO_DISCOVERED_TEST_CLASSES', 'No source-derived JUnit test classes found');
  return declarations;
}

export function discoverTestClasses(root, snapshot = collectSourceSnapshot(root)) {
  return [...new Set(testDeclarations(root, snapshot).map(d => d.className))].sort();
}

export function discoverTestMethods(root, snapshot = collectSourceSnapshot(root)) {
  return testDeclarations(root, snapshot).flatMap(d => d.methods.map(name => `${d.className}#${name}`)).sort();
}

export function junitMethodIdentity(id) {
  const m = /^([^#]+)#([\w$]+)(?:\([^]*\))?(?:\[\d+\])?$/.exec(id);
  demand(m, 'UNSUPPORTED_TEST_IDENTITY', id);
  return `${m[1]}#${m[2]}`;
}

function rejectSkipTestCommand(command) {
  const flags = [...command.argv.join(' ').matchAll(/(?:^|\s)-D\s*(skipTests|maven\.test\.skip)(?:=([^\s]+))?(?=\s|$)/g)];
  demand(flags.every(flag => flag[2]?.toLowerCase() === 'false'), 'SKIP_TEST_COMMAND', `Compilation-only command cannot provide test evidence: ${command.id}`);
}

function xmlAttributes(raw) {
  const attrs = {};
  let rest = raw;
  while (rest.trim()) {
    const m = /^\s+([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/.exec(rest);
    demand(m, 'INVALID_XML', 'Unsupported or malformed XML attribute');
    demand(!Object.hasOwn(attrs, m[1]), 'INVALID_XML', 'Duplicate XML attribute');
    attrs[m[1]] = (m[2] ?? m[3]).replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
    rest = rest.slice(m[0].length);
  }
  return attrs;
}

// Strict bounded reader for Maven Surefire/Failsafe JUnit XML, not a general XML library.
// Declared counters alone never count as tests: they must agree with actual testcase nodes.
export function parseJUnit(xml) {
  demand(typeof xml === 'string' && xml.trim(), 'EMPTY_REPORT', 'JUnit XML is empty');
  demand(!/<!DOCTYPE|<!ENTITY/i.test(xml), 'INVALID_XML', 'DTD/entities are not permitted');
  const token = /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<\?[\s\S]*?\?>|<[^>]*>|[^<]+/gy;
  const stack = [];
  const suites = [];
  let activeSuite = null;
  let activeCase = null;
  let rootSeen = false;
  let pos = 0;
  for (;;) {
    token.lastIndex = pos;
    const m = token.exec(xml);
    if (!m) break;
    pos = token.lastIndex;
    const value = m[0];
    if (value.startsWith('<!--') || value.startsWith('<?')) continue;
    if (value.startsWith('<![CDATA[')) { demand(stack.length > 0, 'INVALID_XML', 'CDATA outside root'); continue; }
    if (!value.startsWith('<')) { demand(stack.length > 0 || !value.trim(), 'INVALID_XML', 'Text outside root'); continue; }
    const closing = /^<\/([\w:.-]+)\s*>$/.exec(value);
    if (closing) {
      const entry = stack.pop();
      demand(entry && entry.name === closing[1], 'INVALID_XML', 'Unbalanced closing tag');
      if (entry.name === 'testcase') activeCase = null;
      if (entry.name === 'testsuite') activeSuite = null;
      continue;
    }
    const opening = /^<([\w:.-]+)([\s\S]*?)(\/?)>$/.exec(value);
    demand(opening, 'INVALID_XML', 'Malformed element');
    const [, name, raw, selfClosing] = opening;
    const attrs = xmlAttributes(raw);
    const parent = stack.at(-1)?.name;
    if (!parent) {
      demand(!rootSeen && ['testsuite', 'testsuites'].includes(name), 'INVALID_XML', 'Exactly one JUnit root required');
      rootSeen = true;
    }
    if (name === 'testsuite') {
      demand(!activeSuite && (!parent || parent === 'testsuites'), 'INVALID_XML', 'Nested/invalid testsuite');
      activeSuite = { attrs, cases: [] };
      suites.push(activeSuite);
    }
    if (name === 'testcase') {
      demand(activeSuite && parent === 'testsuite' && nonempty(attrs.name) && nonempty(attrs.classname), 'INVALID_XML', 'Named testcase/classname required');
      activeCase = { id: `${attrs.classname}#${attrs.name}`, failures: 0, errors: 0, skipped: 0 };
      activeSuite.cases.push(activeCase);
    }
    if (['failure', 'error', 'skipped', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'].includes(name)) {
      demand(activeCase && parent === 'testcase', 'INVALID_XML', 'Outcome outside testcase');
      const key = name === 'skipped' ? 'skipped' : /error/i.test(name) ? 'errors' : 'failures';
      activeCase[key] = 1;
    }
    if (!selfClosing) stack.push({ name });
    else {
      if (name === 'testcase') activeCase = null;
      if (name === 'testsuite') activeSuite = null;
    }
  }
  demand(pos === xml.length && stack.length === 0 && rootSeen && suites.length > 0, 'INVALID_XML', 'Truncated XML or no suite');
  const total = { discovered: 0, executed: 0, failures: 0, errors: 0, skipped: 0, ids: [] };
  for (const suite of suites) {
    const actual = { tests: suite.cases.length, failures: 0, errors: 0, skipped: 0 };
    for (const c of suite.cases) {
      demand(c.failures + c.errors + c.skipped <= 1, 'INVALID_XML', 'Multiple outcomes on one testcase');
      for (const key of ['failures', 'errors', 'skipped']) actual[key] += c[key];
      total.ids.push(c.id);
    }
    for (const key of Object.keys(actual)) {
      demand(/^\d+$/.test(suite.attrs[key] ?? '') && Number(suite.attrs[key]) === actual[key], 'JUNIT_COUNTER_MISMATCH', `${suite.attrs.name ?? 'suite'} ${key}`);
    }
    total.discovered += actual.tests;
    total.executed += actual.tests - actual.skipped;
    for (const key of ['failures', 'errors', 'skipped']) total[key] += actual[key];
  }
  demand(total.discovered > 0, 'EMPTY_REPORT', 'JUnit has zero testcase nodes');
  demand(new Set(total.ids).size === total.ids.length, 'DUPLICATE_TEST', 'Duplicate testcase identities');
  return total;
}

export function validateRelease({ root, reportPath, phase = 'g0-g1' }) {
  root = fs.realpathSync(root);
  demand(phase === 'g0-g1', 'PRODUCTION_GATE_NOT_IMPLEMENTED', 'This G0/G1 evidence gate cannot authorize final production release');
  const report = parseJson(path.resolve(root, reportPath));
  demand(report.schema === 'JKXTReleaseBaseline/v1', 'INVALID_SCHEMA', 'Expected JKXTReleaseBaseline/v1');
  demand(report.phase === 'g0-g1', 'INVALID_PHASE', 'Expected g0-g1');
  demand(report.status === 'READY', 'BASELINE_NOT_READY', `status=${report.status ?? 'MISSING'}`);
  demand(Array.isArray(report.blockers) && report.blockers.length === 0, 'BLOCKERS_PRESENT', 'Missing/nonempty blockers');
  const gitRoot = fs.realpathSync(execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: root, encoding: 'utf8' }).trim());
  demand(gitRoot === root, 'WRONG_ROOT', 'Use the actual Git repository root');
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  demand(SHA.test(report.gitSha) && report.gitSha === head, 'WRONG_SHA', 'Report does not match current HEAD');
  const snapshot = collectSourceSnapshot(root);
  const expected = report.sourceSnapshot;
  demand(expected && HEX.test(expected.sha256) && Array.isArray(expected.files), 'MISSING_SOURCE_SNAPSHOT', 'Full source manifest/hash required');
  demand(JSON.stringify(expected.files) === JSON.stringify(snapshot.files) && expected.sha256 === snapshot.sha256, 'SOURCE_HASH_MISMATCH', 'Source changed, omitted, reordered, or added after collection');
  demand(nonempty(report.runId), 'MISSING_RUN_ID', 'Unique current execution runId required');
  demand(Array.isArray(report.commands) && report.commands.length > 0, 'MISSING_COMMANDS', 'Executed command evidence required');
  const commands = new Map();
  for (const c of report.commands) {
    demand(nonempty(c.id) && !commands.has(c.id), 'INVALID_COMMAND', 'Unique command id required');
    demand(c.status === 'EXECUTED' && c.exitCode === 0, 'COMMAND_NOT_PASSED', `${c.id}: ${c.status}, exit=${c.exitCode}`);
    demand(c.runId === report.runId && c.gitSha === head && c.sourceDigest === snapshot.sha256, 'COMMAND_IDENTITY_MISMATCH', c.id);
    demand(Array.isArray(c.argv) && c.argv.length > 0 && c.argv.every(nonempty) && nonempty(c.cwd), 'INVALID_COMMAND', 'argv/cwd required');
    const start = Date.parse(c.startedAt), end = Date.parse(c.finishedAt);
    demand(Number.isFinite(start) && Number.isFinite(end) && start <= end && end <= Date.now() + 60000, 'INVALID_COMMAND_TIME', c.id);
    artifact(root, c.log);
    commands.set(c.id, c);
  }
  const tests = report.tests;
  demand(tests?.status === 'EXECUTED' && Array.isArray(tests.reports) && tests.reports.length > 0, 'MISSING_TEST_REPORTS', 'Nonempty executed JUnit reports required');
  const requiredClasses = discoverTestClasses(root, snapshot);
  demand(Array.isArray(tests.requiredClasses) && JSON.stringify(tests.requiredClasses) === JSON.stringify(requiredClasses), 'REQUIRED_TEST_CLASS_MISMATCH', 'Required classes must match current source-derived discovery, not a selected green subset');
  const sum = { discovered: 0, executed: 0, failures: 0, errors: 0, skipped: 0 };
  const reportFiles = new Set();
  const ids = new Set();
  for (const ref of tests.reports) {
    const file = artifact(root, ref);
    demand(!reportFiles.has(file), 'DUPLICATE_REPORT', ref.path);
    reportFiles.add(file);
    const command = commands.get(ref.commandId);
    demand(command, 'MISSING_TEST_COMMAND', ref.path);
    rejectSkipTestCommand(command);
    demand(ref.runId === report.runId && ref.gitSha === head && ref.sourceDigest === snapshot.sha256, 'REPORT_IDENTITY_MISMATCH', ref.path);
    const counts = parseJUnit(fs.readFileSync(file, 'utf8'));
    for (const id of counts.ids) { demand(!ids.has(id), 'DUPLICATE_TEST', id); ids.add(id); }
    for (const key of Object.keys(sum)) sum[key] += counts[key];
  }
  for (const key of Object.keys(sum)) demand(integer(tests[key]) && tests[key] === sum[key], 'SUMMARY_MISMATCH', `${key}: declared=${tests[key]}, XML=${sum[key]}`);
  const reportedClasses = new Set([...ids].map(id => id.split('#')[0]));
  const missingClasses = requiredClasses.filter(name => !reportedClasses.has(name));
  demand(missingClasses.length === 0, 'MISSING_TEST_CLASSES', missingClasses.join(', '));
  demand(sum.executed > 0 && sum.failures === 0 && sum.errors === 0 && sum.skipped === 0, 'TESTS_NOT_PASSED', JSON.stringify(sum));
  const reportedMethods = new Set([...ids].map(junitMethodIdentity));
  const missingMethods = discoverTestMethods(root, snapshot).filter(name => !reportedMethods.has(name));
  demand(missingMethods.length === 0, 'MISSING_TEST_METHODS', missingMethods.join(', '));
  demand(report.executionPackage?.status === 'VERIFIED', 'MISSING_EXECUTION_PACKAGE', 'Verified >=84-scenario execution package required');
  const pkg = parseJson(artifact(root, report.executionPackage.artifact));
  demand(pkg.schema === 'JKXTExecutionPackage/v1' && Array.isArray(pkg.scenarios) && pkg.scenarios.length >= 84, 'INSUFFICIENT_SCENARIOS', 'At least 84 actual scenario records required');
  demand(pkg.scenarios.every(s => nonempty(s.id) && nonempty(s.title)) && new Set(pkg.scenarios.map(s => s.id)).size === pkg.scenarios.length, 'INVALID_SCENARIOS', 'Unique id and title required');
  demand(report.executionPackage.scenarioCount === pkg.scenarios.length, 'SCENARIO_COUNT_MISMATCH', 'Use actual package count');
  demand(Array.isArray(pkg.sourceArtifacts) && pkg.sourceArtifacts.length > 0, 'MISSING_PACKAGE_SOURCE', 'Original supplied execution package provenance required');
  for (const ref of pkg.sourceArtifacts) artifact(root, ref);
  validateExecutionPackage(root, pkg);
  demand(report.runtimeMappings?.status === 'COLLECTED' && commands.has(report.runtimeMappings.commandId), 'MISSING_RUNTIME_MAPPINGS', 'Successful runtime collection command required');
  const runtime = parseJson(artifact(root, report.runtimeMappings.artifact));
  demand(runtime.schema === 'JKXTSpringMappings/v1' && runtime.collection === 'RequestMappingHandlerMapping' && runtime.environment === 'isolated-test', 'NOT_RUNTIME_MAPPINGS', 'Static regex inventory is not Spring registration evidence');
  demand(runtime.runId === report.runId && runtime.gitSha === head && runtime.sourceDigest === snapshot.sha256, 'RUNTIME_IDENTITY_MISMATCH', 'Stale runtime identity');
  demand(Array.isArray(runtime.mappings) && runtime.mappings.length > 0 && runtime.mappings.every(m => nonempty(m.path) && nonempty(m.handler) && Array.isArray(m.methods) && m.methods.length > 0 && m.methods.every(nonempty)), 'EMPTY_RUNTIME_MAPPINGS', 'Registered method/path/handler entries required');
  demand(report.runtimeMappings.count === runtime.mappings.length, 'RUNTIME_COUNT_MISMATCH', 'Use actual registered mapping count');
  return { schema: 'JKXTLocalGateResult/v1', phase, status: 'PASS', productionReady: false, supervisorAcceptance: 'NOT_CLAIMED', gitSha: head, sourceDigest: snapshot.sha256, tests: sum, scenarioCount: pkg.scenarios.length, runtimeMappingCount: runtime.mappings.length };
}

function main() {
  const args = process.argv.slice(2);
  const allowed = new Set(['--root', '--report', '--phase']);
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    demand(allowed.has(args[i]) && nonempty(args[i + 1]) && !options[args[i]], 'USAGE', 'node check-release.mjs --root <git-root> --report <baseline.json> [--phase g0-g1]');
    options[args[i]] = args[i + 1];
  }
  demand(options['--root'] && options['--report'], 'USAGE', '--root and --report are required');
  console.log(JSON.stringify(validateRelease({ root: options['--root'], reportPath: options['--report'], phase: options['--phase'] }), null, 2));
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(JSON.stringify({ schema: 'JKXTLocalGateResult/v1', status: 'REJECTED', productionReady: false, reason: error.message }, null, 2)); process.exitCode = 1; }
}
