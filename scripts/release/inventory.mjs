import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { validateExecutionPackage } from './execution-package.mjs';

// G0 static inventory, deliberately NOT a Spring runtime route collector or test runner.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const app = 'JKXT V2.0/';
const out = path.join(root, 'docs/release/inventory');
const require = createRequire(import.meta.url);
const babel = require(path.join(root, app, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const tracked = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
const deep = new Set([
  'src/api.js', 'src/navigation.js', 'src/pages/dashboard.jsx', 'src/ai-workflow.js',
  'backend/src/main/java/com/jkxt/security/SecurityConfig.java',
  'backend/src/main/java/com/jkxt/security/api/AuthController.java',
  'backend/src/main/java/com/jkxt/security/auth/SessionService.java',
  'backend/src/main/java/com/jkxt/security/auth/SessionAuthenticationFilter.java',
  'backend/src/main/java/com/jkxt/common/api/HealthController.java',
  'backend/src/main/java/com/jkxt/scheduling/api/SchedulingController.java',
  'backend/src/main/java/com/jkxt/scheduling/api/ScheduleEditController.java',
  'backend/src/main/java/com/jkxt/scheduling/service/ScheduleAsyncConfig.java',
  'backend/src/main/java/com/jkxt/scheduling/service/ScheduleProgressSink.java',
  'backend/src/main/java/com/jkxt/importer/api/ImportController.java',
  'backend/src/main/java/com/jkxt/reporting/api/ReportingController.java',
  'backend/src/main/java/com/jkxt/examops/api/ExamPlanController.java',
  'backend/src/main/java/com/jkxt/examops/api/PlanningJobController.java',
  'backend/src/main/java/com/jkxt/examops/api/ClosedLoopController.java',
  'backend/src/main/java/com/jkxt/examops/api/OperationsController.java',
  'backend/src/main/java/com/jkxt/examops/api/JointInputController.java',
  'backend/src/main/java/com/jkxt/platform/api/AdminPlatformController.java',
  'backend/src/main/java/com/jkxt/tasks/api/ExamTaskController.java',
  'backend/src/main/java/com/jkxt/masterdata/api/TeacherController.java',
  'backend/src/main/java/com/jkxt/org/api/OrgSettingsController.java',
  'backend/src/main/java/com/jkxt/notify/api/NotificationController.java',
  'backend/src/main/java/com/jkxt/notify/service/NotificationDispatcher.java',
  'backend/src/main/java/com/jkxt/notify/channel/LoggingEmailSender.java',
  'backend/src/main/java/com/jkxt/notify/channel/LoggingSmsSender.java',
  'backend/src/main/java/com/jkxt/ai/api/AiPolicyController.java',
  'backend/src/main/java/com/jkxt/ai/client/OpenAiCompatibleLlmClient.java',
  'backend/src/main/java/com/jkxt/ai/config/LlmTestModeGuard.java',
].map(p => app + p));
// The main agent fully read both supplied documents in this intake; retain their original bytes.
deep.add('docs/release/inputs/02_后端与全栈验收矩阵.md');
deep.add('docs/release/inputs/03_架构与优化决策.md');
const textExtensions = new Set(['.java', '.js', '.jsx', '.mjs', '.cjs', '.css', '.html', '.json', '.xml', '.yml', '.yaml', '.properties', '.sql', '.ps1', '.sh', '.cmd', '.py', '.md', '.txt', '.example', '.conf']);
const texts = new Map();
const hash = b => crypto.createHash('sha256').update(b).digest('hex');
// Include the release tools/configuration and authored release documents themselves.
// Exclude volatile evidence and generated inventory JSON/CSV to avoid recursive self-hashes.
const sourcePaths = tracked.filter(p => !p.startsWith('docs/release/evidence/')
  && !(p.startsWith('docs/release/inventory/') && /\.(json|csv)$/.test(p)));
const rows = sourcePaths.map(file => {
  const bytes = fs.readFileSync(path.join(root, file));
  const thirdParty = /\/assets\/(vendor|fonts)\//.test(file);
  const isText = !thirdParty && (textExtensions.has(path.extname(file)) || /(?:Dockerfile|mvnw|\.gitignore|\.gitattributes|\.dockerignore|Caddyfile)$/.test(file));
  if (isText) texts.set(file, bytes.toString('utf8'));
  const category = thirdParty ? 'third-party-vendor-or-font' : /\/assets\//.test(file) ? 'media-asset'
    : /\/db\/migration\//.test(file) ? 'database-migration' : /\/backend\/src\/test\//.test(file) ? 'backend-test'
    : /\/backend\/src\/main\/java\//.test(file) ? 'backend-source' : /\/src\//.test(file) && /\.(jsx?|css)$/.test(file) ? 'frontend-source'
    : /(?:^|\/)scripts\/|\/test-data\//.test(file) && !/\.xml$/.test(file) ? 'script-or-test-data-generator' : /\.md$/.test(file) ? 'documentation'
    : /\.(ya?ml|properties|json|xml|conf|example)$/.test(file) || /Dockerfile|Caddyfile|\.gitignore|\.gitattributes|mvnw/.test(file) ? 'configuration-or-build'
    : /\.html$/.test(file) ? 'frontend-entry' : 'other';
  return { path: file, category, ownership: thirdParty ? 'THIRD_PARTY' : 'PROJECT_SNAPSHOT', bytes: bytes.length,
    sha256: hash(bytes), read_status: deep.has(file) ? 'DEEP_READ' : isText ? 'SCAN' : 'UNREAD',
    read_basis: deep.has(file) ? 'Human full-file read during G0 inventory; no behavior pass implied'
      : isText ? 'Machine lexical/AST or text-content inventory; not a human deep read'
      : 'Filename, size and hash only; media/vendor semantics not inspected' };
});
const java = [...texts].filter(([p]) => p.includes('/src/main/java/') && p.endsWith('.java'));
const tests = [...texts].filter(([p]) => p.includes('/src/test/') && p.endsWith('.java'));
const classMap = new Map(java.map(([file, source]) => [path.basename(file, '.java'), { file, source }]));
const uniq = a => [...new Set(a)].sort();
const lineAt = (s, i) => s.slice(0, i).split('\n').length;
const stringValues = s => [...s.matchAll(/"((?:\\.|[^"\\])*)"/g)].map(x => x[1]);
function maskJava(s) {
  return s.replace(/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g,
    m => m.replace(/[^\r\n]/g, ' '));
}
function closeAt(s, start, open, close) {
  let depth = 0;
  for (let i = start; i < s.length; i++) {
    if (s[i] === open) depth++;
    if (s[i] === close && --depth === 0) return i;
  }
  return -1;
}
function tablesFor(className, seen = new Set(), depth = 0) {
  if (seen.has(className) || depth > 3 || !classMap.has(className)) return [];
  seen.add(className);
  const { source } = classMap.get(className);
  const tables = [...source.matchAll(/(?:\bfrom|\bjoin|\bupdate|\binsert\s+into|\bdelete\s+from)\s+([a-z][a-z0-9_]*)(?=\s|\()/gi)]
    .map(m => m[1].toLowerCase()).filter(t => t.includes('_') || ['teacher','classroom','subject','organization','notification','announcement','exam'].includes(t));
  tables.push(...[...source.matchAll(/@Table\(name\s*=\s*"([^"]+)"/g)].map(m => m[1]));
  const refs = [...source.matchAll(/\b([A-Z][A-Za-z0-9]+(?:Repository|Service))\b/g)].map(m => m[1]);
  const repoEntity = source.match(/extends\s+JpaRepository\s*<\s*(\w+)/);
  if (repoEntity) refs.push(repoEntity[1]);
  for (const ref of uniq(refs)) tables.push(...tablesFor(ref, seen, depth + 1));
  return uniq(tables);
}
function filterPermission(method, route) {
  if (['/health','/version','/auth/captcha','/auth/login'].some(p => route === '/api/v1' + p)) return 'permitAll';
  if (route.startsWith('/api/v1/admin/')) return "ROLE_SUPER_ADMIN";
  if (route.startsWith('/api/v1/auth/me') || route.startsWith('/api/v1/notifications') || method === 'POST' && route === '/api/v1/auth/logout') return 'authenticated';
  if (['POST','PUT','DELETE'].includes(method) && /^\/api\/v1\/(teachers|classes|classrooms|subjects)(\/|$)/.test(route) || method === 'POST' && route.startsWith('/api/v1/import/')) return 'masterdata:manage';
  if (method === 'POST' && /^\/api\/v1\/exam-tasks\/[^/]+\/schedule-jobs$/.test(route)) return 'schedule:manage';
  if (method === 'POST' && /^\/api\/v1\/(scheduling|nl-policy|tasks)\//.test(route) || method === 'PATCH' && route.startsWith('/api/v1/tasks/')) return 'schedule:manage';
  if (['POST','DELETE'].includes(method) && /^\/api\/v1\/exam-tasks(?:\/|$)/.test(route)) return 'exam:manage';
  if (['POST','PUT','PATCH','DELETE'].includes(method)) return 'ROLE_ADMIN or ROLE_SUPER_ADMIN (fallback)';
  return method === 'GET' ? 'tenant:read' : 'authenticated';
}
const routes = [];
const parseWarnings = [];
for (const [file, source] of java.filter(([, s]) => /@RestController\b/.test(s))) {
  const classPos = source.search(/public\s+class\s/);
  const header = source.slice(0, classPos);
  const prefix = (header.match(/@RequestMapping\s*\(\s*"([^"]+)"/)?.[1] || '').replace('${jkxt.api-base:/api/v1}', '/api/v1');
  const classPermission = header.match(/@PreAuthorize\("([^"]+)"\)/)?.[1] || '';
  const fieldTypes = new Map([...source.matchAll(/private\s+final\s+([\w.]+)(?:<[^;]+>)?\s+(\w+)\s*;/g)].map(m => [m[2], m[1].split('.').at(-1)]));
  const masked = maskJava(source);
  const annotation = /@(?:org\.springframework\.web\.bind\.annotation\.)?(Get|Post|Put|Patch|Delete|Request)Mapping\b/g;
  for (const match of source.matchAll(annotation)) {
    if (match.index < classPos) continue;
    let after = match.index + match[0].length;
    while (/\s/.test(source[after] || 'x')) after++;
    let args = '';
    if (source[after] === '(') {
      const end = closeAt(masked, after, '(', ')');
      args = source.slice(after + 1, end); after = end + 1;
    }
    const methodMatch = /public\s+(?:[\w.<>,?\[\] ]+\s+)?(\w+)\s*\(/.exec(masked.slice(after));
    if (!methodMatch) { parseWarnings.push(file + ':' + lineAt(source, match.index) + ' method unresolved'); continue; }
    const methodStart = after + methodMatch.index;
    const openParams = masked.indexOf('(', methodStart);
    const closeParams = closeAt(masked, openParams, '(', ')');
    const bodyStart = masked.indexOf('{', closeParams);
    const bodyEnd = closeAt(masked, bodyStart, '{', '}');
    const body = source.slice(bodyStart, bodyEnd + 1);
    const declaration = source.slice(after, bodyStart);
    let paths = [];
    const pathArg = args.match(/(?:value|path)\s*=\s*(\{[^}]*\}|"(?:\\.|[^"\\])*")/);
    if (pathArg) paths = stringValues(pathArg[1]);
    else if (/^\s*["{]/.test(args)) paths = stringValues(args.split(/,\s*(?:produces|consumes|method)\s*=/)[0]);
    if (!paths.length) paths = [''];
    const methods = match[1] === 'Request' ? [...args.matchAll(/RequestMethod\.(\w+)/g)].map(x => x[1]) : [match[1].toUpperCase()];
    const calls = uniq([...body.matchAll(/\b(\w+)\.(\w+)\s*\(/g)].filter(m => fieldTypes.has(m[1])).map(m => fieldTypes.get(m[1]) + '.' + m[2]));
    if (file.endsWith('/JointInputController.java') && /return write\(/.test(body)) calls.push('JointInputWriteService.replace (via controller.write)');
    if (file.endsWith('/ImportController.java') && /return templateResponse\(/.test(body)) calls.push('ImportValidationService.template (via controller.templateResponse)');
    const serviceClasses = uniq(calls.map(c => c.split('.')[0]));
    // Template helper is called indirectly within ImportController.
    if (file.endsWith('/ImportController.java') && /template/i.test(methodMatch[1])) serviceClasses.push('ImportValidationService');
    const dbTables = uniq(serviceClasses.flatMap(c => tablesFor(c)));
    for (const routePath of paths) for (const method of methods.length ? methods : ['ANY']) {
      const route = prefix + routePath;
      const staticClass = /\/fixtures(?:\/|$)/.test(route) ? '模拟' : route === '/api/v1/admin/backups/run' ? '部分实现' : '真实实现（静态代码）';
      const candidates = tests.filter(([, t]) => serviceClasses.some(c => t.includes(c)) || t.includes(path.basename(file, '.java')) || t.includes('"' + route.replace(/\{[^}]+\}/g, ''))).map(([p]) => p);
      routes.push({ method, route, controller: path.basename(file, '.java'), handler: methodMatch[1], source_file: file,
        source_line: lineAt(source, match.index), filter_permission: filterPermission(method, route),
        method_permission: declaration.match(/@PreAuthorize\("([^"]+)"\)/)?.[1] || classPermission || 'none (filter applies)',
        service_calls: calls, database_candidates: dbTables, database_evidence: 'STATIC_CLASS_DEPENDENCY_SUPERSET_NOT_PER_METHOD_PROOF',
        artifact: /SseEmitter/.test(declaration) ? 'SSE progress events' : /\/export$/.test(route) ? 'XLSX/PDF byte download'
          : /template/.test(route) ? 'XLSX template byte download' : method === 'DELETE' ? 'HTTP 204 / persisted deletion' : 'JSON response; verify DB mutation and response semantics with test',
        classification: staticClass, runtime_registration: 'UNVERIFIED_BY_THIS_STATIC_COLLECTOR',
        behavior_status: 'NOT_RUN_BY_THIS_COLLECTOR', test_candidates: candidates, test_mapping_status: 'STATIC_CANDIDATES_NOT_EXECUTED_COVERAGE',
        frontend_matches: [], gap: /\/fixtures(?:\/|$)/.test(route) ? 'Dev-only guard returns 404 outside dev; not production workflow'
          : route === '/api/v1/admin/backups/run' ? 'Scans existing backup files; does not execute pg_dump; unavailable if no new file'
          : route.startsWith('/api/v1/nl-policy/') ? 'Real external AI requires authorized configuration and separately authorized external-call test'
          : 'Runtime registration, exact DB/assertions, authorization and browser behavior require fresh G1+ evidence' });
    }
  }
}
const frontend = [];
const frontendFiles = [...texts].filter(([p]) => p.startsWith(app + 'src/') && /\.(js|jsx)$/.test(p));
function walk(node, visit, parents = []) {
  if (!node || typeof node !== 'object') return;
  if (typeof node.type === 'string') visit(node, parents);
  for (const [key, value] of Object.entries(node)) {
    if (['loc','start','end','extra','comments','leadingComments','trailingComments','innerComments','tokens'].includes(key)) continue;
    if (Array.isArray(value)) value.forEach(v => walk(v, visit, [...parents, node]));
    else if (value && typeof value === 'object') walk(value, visit, [...parents, node]);
  }
}
function member(n) {
  if (!n) return '';
  if (n.type === 'Identifier') return n.name;
  if (n.type === 'MemberExpression' || n.type === 'OptionalMemberExpression') return member(n.object) + '.' + (n.computed ? n.property.value || '{dynamic}' : n.property.name);
  return '';
}
function pathPattern(n, vars = new Map(), seen = new Set()) {
  if (!n) return '{dynamic}';
  if (n.type === 'Identifier' && vars.has(n.name) && !seen.has(n.name)) return pathPattern(vars.get(n.name), vars, new Set([...seen,n.name]));
  if (n.type === 'StringLiteral') return n.value;
  if (n.type === 'TemplateLiteral') return n.quasis.map((q, i) => q.value.cooked + (i < n.expressions.length ? pathPattern(n.expressions[i],vars,seen) : '')).join('');
  if (n.type === 'BinaryExpression' && n.operator === '+') return pathPattern(n.left,vars,seen) + pathPattern(n.right,vars,seen);
  if (n.type === 'CallExpression' && /\.url$/.test(member(n.callee))) return pathPattern(n.arguments[0],vars,seen);
  return '{dynamic}';
}
function functionName(parents) {
  for (const n of [...parents].reverse()) {
    if (n.type === 'ObjectProperty' && ['ArrowFunctionExpression','FunctionExpression'].includes(n.value?.type)) return n.key.name || n.key.value;
    if (n.type === 'FunctionDeclaration') return n.id?.name || '(anonymous)';
    if (n.type === 'VariableDeclarator' && ['ArrowFunctionExpression','FunctionExpression'].includes(n.init?.type)) return n.id?.name || '(destructured)';
  }
  return '(top-level/dynamic)';
}
const wrapperPatterns = new Map();
for (const [file, source] of frontendFiles) {
  let ast;
  try { ast = babel.transform(source, { ast: true, code: false, sourceType: 'script', parserOpts: { plugins: ['jsx'] } }).ast; }
  catch (e) { parseWarnings.push(file + ': AST_FAILED: ' + e.message); continue; }
  // Resolve only unique local string/template bindings. Ambiguous shadowed names stay unresolved.
  const vars = new Map(), repeated = new Set();
  walk(ast,n=>{if(n.type==='VariableDeclarator' && n.id?.type==='Identifier' && n.init){if(vars.has(n.id.name))repeated.add(n.id.name);else vars.set(n.id.name,n.init);}});
  for(const name of repeated) vars.delete(name);
  walk(ast, (n, parents) => {
    if (!['CallExpression','OptionalCallExpression'].includes(n.type)) return;
    const call = member(n.callee).replace(/^window\./, '');
    const verbs = { get:'GET',post:'POST',put:'PUT',patch:'PATCH',del:'DELETE',upload:'POST',eventSource:'GET',url:'URL_ONLY',webSocket:'WEBSOCKET' };
    const tail = call.split('.').at(-1);
    const helperMethods = {useSuperResource:'GET',useSuperObject:'GET',superPost:'POST',superPut:'PUT'};
    const isApi = call === 'fetch' || call.startsWith('api.') || Object.hasOwn(helperMethods,call);
    if (!isApi) return;
    const method = (Object.hasOwn(helperMethods,call) ? helperMethods[call] : null) || (call === 'fetch' ? 'GET_OR_OPTIONS' : verbs[tail] || 'WRAPPER');
    if (['api.request','api.setBase','api.wsUrl'].includes(call)) return;
    const pattern = pathPattern(n.arguments[0],vars);
    let wrapper = null;
    if (file.endsWith('/api.js') && verbs[tail]) {
      const assignment = [...parents].reverse().find(a => a.type === 'AssignmentExpression' && member(a.left).startsWith('api.'));
      if (assignment) wrapper = member(assignment.left) + '.' + functionName(parents);
      if (wrapper) wrapperPatterns.set(wrapper, { method, pattern });
    }
    frontend.push({ file, line:n.loc.start.line, operation:functionName(parents), call, method,
      request_pattern:pattern, wrapper, routes:[], mapping:'STATIC_NOT_EXECUTED',
      callsite_kind:file.endsWith('/api.js')?'WRAPPER_DEFINITION':'PAGE_OR_COMPONENT_CALL_SITE' });
  });
}
const manualWrapper = {
  'api.examPlans.jointInputSnapshot': { method:'GET',pattern:'/plan-revisions/{dynamic}/inputs/{dynamic}' },
  'api.examPlans.replaceJointInput': { method:'POST',pattern:'/plan-revisions/{dynamic}/inputs/{dynamic}' },
};
for (const ref of frontend) {
  if (ref.call.split('.').length > 2) {
    const resolved = manualWrapper[ref.call] || wrapperPatterns.get(ref.call);
    if (resolved) { ref.method = resolved.method; ref.request_pattern = resolved.pattern; }
  }
  if (!ref.request_pattern.startsWith('/')) { ref.mapping = 'UNRESOLVED_DYNAMIC_REQUEST'; continue; }
  const normalized = (ref.request_pattern.startsWith('/api/') ? ref.request_pattern : '/api/v1' + ref.request_pattern).split('?')[0].replace(/\{[^}]+\}/g, '{}');
  for (const route of routes) {
    const a = normalized.split('/'), b = route.route.replace(/\{[^}]+\}/g,'{}').split('/');
    if (a.length !== b.length || !a.every((s,i) => s === b[i] || s === '{}' || b[i] === '{}')) continue;
    if (!['URL_ONLY','GET_OR_OPTIONS','WRAPPER'].includes(ref.method) && ref.method !== route.method) continue;
    const id = route.method + ' ' + route.route;
    ref.routes.push(id);
    route.frontend_matches.push({file:ref.file,line:ref.line,operation:ref.operation,call:ref.call,request_pattern:ref.request_pattern,kind:ref.callsite_kind});
  }
  ref.mapping = ref.routes.length ? 'STATIC_ROUTE_CANDIDATE' : 'NO_STATIC_ROUTE_MATCH_NOT_PROOF_OF_UNREACHABILITY';
}
for(const route of routes) route.frontend_reachability = route.frontend_matches.some(r=>r.kind==='PAGE_OR_COMPONENT_CALL_SITE')
  ? 'STATIC_UI_CALL_CANDIDATE_NOT_CLICK_TESTED' : 'NO_STATIC_UI_CALL_OBSERVED_NOT_PROOF_OF_UNREACHABILITY';
const asyncPatterns = /@Scheduled|@Async|@EventListener|@PostConstruct|CompletableFuture\.runAsync|SseEmitter|RedisMessageListener|StreamMessageListener|WebSocket|\.receive\(|\.subscribe\(/g;
const background = java.flatMap(([file, source]) => [...source.matchAll(asyncPatterns)].filter(m => !source.slice(Math.max(0,m.index-10),m.index).includes('import ')).map(m => ({ file, line:lineAt(source,m.index),token:m[0],status:'STATIC_DISCOVERY_NOT_RUNTIME_VERIFIED' })));
const packageFile = path.join(root,'docs/release/execution-package.json');
let executionPackage = {status:'MISSING',scenarioCount:0};
if (fs.existsSync(packageFile)) {
  const pkg = validateExecutionPackage(root,JSON.parse(fs.readFileSync(packageFile,'utf8')));
  executionPackage = {status:'VERIFIED_INPUT_ONLY',scenarioCount:pkg.scenarios.length,
    architectureSections:pkg.architectureDecisions.length,artifact:'docs/release/execution-package.json',
    sha256:hash(fs.readFileSync(packageFile)),businessPassCount:0};
}
const summary = { schema:'JKXTStaticInventory/v1', generated_at:new Date().toISOString(), git_sha:sha,
  base_sha:'f206bcf6dff291d346cf5c3cf5a68bfce938935e', scope:'Published current-source candidate including nonignored candidate additions, root release tools/configuration and authored documents; excludes volatile release evidence, generated inventory JSON/CSV and original-machine files',
  worktree_diff_sha256:hash(execFileSync('git',['diff','HEAD','--'],{cwd:root})),
  modified_tracked_files:execFileSync('git',['diff','--name-only','HEAD','--'],{cwd:root,encoding:'utf8'}).trim().split('\n').filter(Boolean),
  counts:{files:rows.length,deep_read:rows.filter(r=>r.read_status==='DEEP_READ').length,scanned:rows.filter(r=>r.read_status==='SCAN').length,unread:rows.filter(r=>r.read_status==='UNREAD').length,
    categories:rows.reduce((a,r)=>(a[r.category]=(a[r.category]||0)+1,a),{}),controllers:java.filter(([,s])=>/@RestController\b/.test(s)).length,static_routes:routes.length,
    frontend_request_sites:frontend.length,frontend_files:frontendFiles.length,unresolved_frontend_requests:frontend.filter(r=>r.mapping==='UNRESOLVED_DYNAMIC_REQUEST').length,
    route_rows_with_frontend_candidates:routes.filter(r=>r.frontend_matches.length).length,backend_test_files:tests.length,async_token_locations:background.length},
  actual_spring_registration:'NOT_COLLECTED_BY_THIS_SCRIPT; see separate runtime collector',
  behavior_tests:'NOT_RUN_BY_THIS_SCRIPT', execution_package_84:executionPackage,parse_warnings:parseWarnings };
fs.mkdirSync(out,{recursive:true});
const json=(name,data)=>fs.writeFileSync(path.join(out,name),JSON.stringify(data,null,2)+'\n');
const csv=(name,data,columns)=>{const enc=v=>'"'+(Array.isArray(v)?JSON.stringify(v):String(v??'')).replaceAll('"','""')+'"';fs.writeFileSync(path.join(out,name),'\uFEFF'+[columns.map(enc).join(','),...data.map(r=>columns.map(c=>enc(r[c])).join(','))].join('\n')+'\n');};
json('summary.json',summary);json('files.json',rows);json('static-routes.json',routes);json('frontend-requests.json',frontend);json('background-discovery.json',background);
csv('files.csv',rows,['path','category','ownership','bytes','sha256','read_status','read_basis']);
csv('traceability.csv',routes,['method','route','controller','handler','source_file','source_line','frontend_matches','filter_permission','method_permission','service_calls','database_candidates','database_evidence','artifact','classification','runtime_registration','behavior_status','test_candidates','test_mapping_status','gap']);
csv('frontend-requests.csv',frontend,['file','line','operation','call','method','request_pattern','routes','mapping']);
console.log(JSON.stringify(summary,null,2));
if (parseWarnings.length) process.exitCode=2;
