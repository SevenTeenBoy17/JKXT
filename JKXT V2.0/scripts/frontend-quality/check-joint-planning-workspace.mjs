import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const page = fs.readFileSync(path.join(root, 'src', 'pages', 'exam-plans.jsx'), 'utf8');
const api = fs.readFileSync(path.join(root, 'src', 'api.js'), 'utf8');
const resources = [
  'demands', 'demand-classes', 'demand-windows', 'candidates', 'enrollments',
  'candidate-windows', 'special-requirements', 'room-pool', 'room-windows',
  'seat-topology', 'teacher-pool', 'avoidances', 'campus-travel',
];
const checks = [];
const check = (id, pass, detail) => checks.push({ id, pass: Boolean(pass), detail });

check('api.all-13-versioned-inputs', resources.every((resource) => api.includes(`'${resource}'`)), 'all 13 canonical resource slugs are registered');
check('api.get-and-whole-collection-post', /jointInputSnapshot:[\s\S]*api\.get\(jointInputPath/.test(api) && /replaceJointInput:[\s\S]*api\.post\(jointInputPath[\s\S]*\{ items \}/.test(api), 'GET snapshot and POST { items } replacement helpers exist');
check('solver.joint-mode', /mode:\s*['"]JOINT['"]/.test(page) && !/mode:\s*['"]INVIGILATOR_ONLY['"]/.test(page), 'planning job requests JOINT mode only');
check('concurrency.required-headers', page.includes("'If-Match': String(cursor)") && page.includes("'Idempotency-Key': idempotencyKey"), 'replacement sends cursor and idempotency headers');
check('concurrency.stale-cursor', /status\s*===\s*409/.test(page) && /版本游标已过期/.test(page) && /await loadResource\(dialog\)/.test(page), '409 stale cursor is surfaced and re-read without blind retry');
check('states.loading-empty-error-retry', ['joint-input-loading-', 'joint-input-empty-', 'joint-input-error-', 'joint-input-retry-'].every((token) => page.includes(token)), 'each resource has explicit loading, empty, error, and retry hooks');
check('states.no-fake-success', /服务器成功响应前不会显示成功状态/.test(page) && /await window\.api\.examPlans\.replaceJointInput/.test(page) && !/mock(?:Data|Success|Fallback)/i.test(page), 'success is coupled to the real replacement response');
check('import.preview-before-commit', /previewCollection\(source, format\)/.test(page) && /导入预览/.test(page) && /preview\.errors\.length/.test(page), 'JSON/CSV parsing exposes record, field, and error preview before commit');
check('import.cursor-refresh', /setCursor\(nextCursor\)/.test(page) && /await loadAll\(\)/.test(page), 'successful collection replacement refreshes cursor and all resource snapshots');
check('solver.structured-diagnostics', /function SolverDiagnostics/.test(page) && /affectedResourceIds/.test(page) && /repairSuggestions/.test(page) && /DEADLINE_EXCEEDED/.test(page) && /CANCELLED/.test(page), 'diagnostics include stage, affected resources, suggestions, cancellation and deadline terminal states');
check('a11y.dialog-and-labels', /role="dialog"/.test(page) && /aria-modal="true"/.test(page) && /aria-labelledby="joint-import-title"/.test(page) && /aria-label="选择导入文件"/.test(page), 'import dialog and file controls have accessible names');
check('a11y.minimum-control-size', /\.joint-control\{min-height:44px\}/.test(page), 'workspace controls enforce a 44px minimum height');
check('responsive.required-breakpoints', ['320 390 768 1024 1440', 'min-width:390px', 'min-width:768px', 'min-width:1024px', 'min-width:1440px'].every((token) => page.includes(token)), 'responsive contract covers all required viewport widths');
check('theme.semantic-tokens', ['var(--surface-raised)', 'var(--surface-muted)', 'var(--border-soft)', 'var(--text-soft)', 'var(--brand)'].every((token) => page.includes(token)), 'workspace uses semantic theme tokens');

const failed = checks.filter((entry) => !entry.pass);
console.log(JSON.stringify({
  contract: 'JointPlanningWorkspaceContract/v1',
  resources,
  summary: { passed: checks.length - failed.length, failed: failed.length, total: checks.length },
  checks,
}, null, 2));
if (failed.length) process.exitCode = 1;
