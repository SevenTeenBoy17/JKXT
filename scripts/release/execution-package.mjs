// Lossless source-backed acceptance intake. Requirements are not execution results.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const requireValue = (condition, message) => { if (!condition) throw new Error(`INVALID_PACKAGE_SOURCE: ${message}`); };
const text = bytes => bytes.toString('utf8').replace(/^\uFEFF/, '');

export function parseScenarioMatrix(markdown, sourcePath) {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');
  const baselineSha = /基线：\s*`([a-f0-9]{40})`/.exec(markdown)?.[1];
  requireValue(baselineSha, 'matrix must identify the audited baseline SHA');
  const scenarios = [];
  const ids = new Set();
  let group = null;
  let headerSeen = false;
  for (const [index, line] of lines.entries()) {
    if (line.startsWith('## ')) {
      group = /^## ([A-Z][A-Z0-9_]*)[｜|]/.exec(line)?.[1] ?? null;
      headerSeen = false;
    }
    if (!line.trimStart().startsWith('|')) continue;
    const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map(c => c.trim().replace(/\\\|/g, '|'));
    if (cells.every(c => /^:?-{3,}:?$/.test(c))) continue;
    if (cells[0] === '编号') {
      requireValue(cells.join('|') === '编号|场景|给定|操作|必须观察到的结果', `unexpected matrix header at line ${index + 1}`);
      headerSeen = true;
      continue;
    }
    requireValue(group && headerSeen && cells.length === 5 && cells.every(Boolean), `malformed/unscoped scenario at line ${index + 1}`);
    const [id, title, given, operation, expected] = cells;
    requireValue(new RegExp(`^${group}-\\d{2,}$`).test(id) && !ids.has(id), `duplicate/invalid scenario ${id}`);
    ids.add(id);
    scenarios.push({ id, group, title, given, operation, expected, sourcePath, sourceLine: index + 1 });
  }
  const declared = /共\s*(\d+)\s*项场景/.exec(markdown)?.[1];
  requireValue(scenarios.length > 0 && (!declared || Number(declared) === scenarios.length), 'declared scenario count disagrees with source rows');
  return { baselineSha, scenarios };
}

export function parseArchitecture(markdown, sourcePath) {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n');
  const starts = lines.flatMap((line, index) => /^## .+/.test(line) ? [{ index, title: line.slice(3).trim() }] : []);
  requireValue(starts.length > 0, 'architecture sections are missing');
  return starts.map((item, index) => {
    const content = lines.slice(item.index + 1, starts[index + 1]?.index ?? lines.length).join('\n').trim();
    requireValue(content, `empty architecture section ${item.title}`);
    return { id: `ADR-${String(index + 1).padStart(2, '0')}`, title: item.title, content, sourcePath, sourceLine: item.index + 1 };
  });
}

function sourceFile(root, ref) {
  requireValue(typeof ref.path === 'string' && !path.isAbsolute(ref.path) && !ref.path.includes('\\') && !ref.path.split('/').includes('..'), 'source path must stay inside repository');
  const resolved = fs.realpathSync(path.resolve(root, ref.path));
  const relative = path.relative(fs.realpathSync(root), resolved);
  requireValue(relative && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative) && fs.statSync(resolved).isFile(), 'source path escapes repository or is not a file');
  return resolved;
}

export function buildExecutionPackage(root, refs) {
  requireValue(Array.isArray(refs) && refs.length >= 2, 'matrix and architecture source documents are both required');
  requireValue(new Set(refs.map(r => r.path)).size === refs.length, 'duplicate source paths');
  requireValue(refs.some(r => r.role === 'matrix') && refs.filter(r => r.role === 'architecture').length === 1
    && refs.every(r => ['matrix', 'architecture'].includes(r.role)), 'source roles must include matrix and exactly one architecture');
  const sourceArtifacts = [], scenarios = [], architectureDecisions = [];
  let baselineSha = null;
  for (const ref of refs) {
    const bytes = fs.readFileSync(sourceFile(root, ref));
    requireValue(bytes.length > 0, `empty source ${ref.path}`);
    const sha256 = digest(bytes);
    if (ref.sha256 !== undefined) requireValue(ref.sha256 === sha256, `source hash mismatch: ${ref.path}`);
    sourceArtifacts.push({ role: ref.role, path: ref.path, sha256 });
    if (ref.role === 'matrix') {
      const parsed = parseScenarioMatrix(text(bytes), ref.path);
      requireValue(!baselineSha || baselineSha === parsed.baselineSha, 'matrix baselines disagree');
      baselineSha = parsed.baselineSha;
      scenarios.push(...parsed.scenarios);
    } else architectureDecisions.push(...parseArchitecture(text(bytes), ref.path));
  }
  requireValue(scenarios.length >= 84 && new Set(scenarios.map(s => s.id)).size === scenarios.length, 'minimum 84 distinct source-backed scenarios required');
  return { schema: 'JKXTExecutionPackage/v1', baselineSha,
    scope: 'Acceptance requirements only; not test execution or production authorization',
    sourceArtifacts, scenarios, architectureDecisions };
}

export function validateExecutionPackage(root, pkg) {
  requireValue(pkg?.schema === 'JKXTExecutionPackage/v1', 'execution package schema is missing');
  const rebuilt = buildExecutionPackage(root, pkg.sourceArtifacts);
  // Compare every requirement cell, origin/line, and full architecture text, not just a count.
  for (const field of ['baselineSha', 'sourceArtifacts', 'scenarios', 'architectureDecisions']) {
    if (JSON.stringify(pkg[field]) !== JSON.stringify(rebuilt[field])) throw new Error(`PACKAGE_SOURCE_MISMATCH: ${field} differs from original source documents`);
  }
  return rebuilt;
}

function main() {
  const [rootArg, matrix, architecture, output] = process.argv.slice(2);
  requireValue(rootArg && matrix && architecture && output && process.argv.length === 6,
    'Usage: node execution-package.mjs <root> <relative-matrix> <relative-architecture> <relative-output>');
  const root = fs.realpathSync(rootArg);
  requireValue(/^docs\/release\/[\w./-]+\.json$/.test(output) && !output.split('/').includes('..'), 'output must be a docs/release JSON artifact');
  const pkg = buildExecutionPackage(root, [{ role: 'matrix', path: matrix }, { role: 'architecture', path: architecture }]);
  const dest = path.resolve(root, output);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const realParent = fs.realpathSync(path.dirname(dest));
  requireValue(realParent.startsWith(root + path.sep), 'output parent escapes repository');
  fs.writeFileSync(dest, JSON.stringify(pkg, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ status: 'VERIFIED_INPUT_ONLY', scenarios: pkg.scenarios.length,
    groups: [...new Set(pkg.scenarios.map(s => s.group))], architectureSections: pkg.architectureDecisions.length,
    sourceArtifacts: pkg.sourceArtifacts, output, sha256: digest(fs.readFileSync(dest)), businessTestsExecuted: 0 }, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
