import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = fs.readFileSync(path.join(root, 'src/pages/tasks-page.jsx'), 'utf8');
const babel = createRequire(import.meta.url)(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const compiled = babel.transform(source, { presets: ['react'], filename: 'tasks-page.jsx' }).code;
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const textOf = x => x == null || typeof x === 'boolean' ? '' : Array.isArray(x) ? x.map(textOf).join(' ') : typeof x === 'object' ? textOf(x.props?.children) : String(x);
const task = (id = 'task-A', extra = {}) => ({ id, taskNo: `RW-${id}`, name: `学校任务 ${id}`, status: 'scheduled', updatedAt: '2026-09-06T01:10:00Z', stats: { exams: 0, assignments: 2 }, uploads: [], ...extra });

function harness({ rows = [task()], local = [], get, fetcher, missingApi = false, storageFails = false } = {}) {
  const instances = new Map(), effects = [], calls = [], routes = [], downloads = [], notices = [], storage = new Map();
  let current, cursor, dirty = false, tree, used;
  const same = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => Object.is(x, b[i]));
  const sandbox = vm.createContext({ console, Date, Intl, Map, Set, Blob, URL, setTimeout, clearTimeout,
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => { if (storageFails) throw Error('storage disabled'); storage.set(key, value); } },
    orgName: () => '当前学校',
    fetch: async (...args) => { calls.push({ method: 'fetch', args }); return fetcher ? fetcher(...args) : { ok: true, blob: async () => new Blob(['xlsx'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }) }; },
    window: { navigateTo: route => routes.push(route), ui: { toast: (...args) => notices.push(args) }, JKXT: { listTasks: () => local } },
    React: {
      Fragment: 'Fragment', createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
      useState(initial) { const instance = current, i = cursor++; if (!(i in instance.hooks)) instance.hooks[i] = typeof initial === 'function' ? initial() : initial; return [instance.hooks[i], value => { instance.hooks[i] = typeof value === 'function' ? value(instance.hooks[i]) : value; dirty = true; }]; },
      useRef(initial) { const i = cursor++; if (!(i in current.hooks)) current.hooks[i] = { current: initial }; return current.hooks[i]; },
      useMemo(fn, deps) { const i = cursor++; if (!current.hooks[i] || !same(deps, current.hooks[i].deps)) current.hooks[i] = { deps, value: fn() }; return current.hooks[i].value; },
      useCallback(fn, deps) { return this.useMemo(() => fn, deps); },
      useEffect(fn, deps) { const instance = current, i = cursor++, previous = instance.hooks[i]; if (!previous || !same(deps, previous.deps)) { const entry = { deps }; instance.hooks[i] = entry; effects.push(() => { previous?.cleanup?.(); entry.cleanup = fn(); }); } },
    },
  });
  if (!missingApi) sandbox.window.api = { get: url => { calls.push({ method: 'get', url }); return get ? get(url) : Promise.resolve(url === '/exam-tasks' ? rows : rows.find(row => url.endsWith('/' + row.id))); }, url: url => '/api/v1' + url };
  for (const name of new Set(source.match(/\bIcon\w+\b/g))) sandbox[name] = name;
  for (const name of ['PageShell', 'Button', 'FilterBar', 'EmptyState', 'Tag', 'BlueprintDrawer', 'BlueprintAsset']) { sandbox[name] = name; sandbox.window[name] = name; }
  vm.runInContext(compiled, sandbox);
  sandbox.captureDownload = (blob, filename) => downloads.push({ blob, filename });
  vm.runInContext('taskDownloadBlob = captureDownload', sandbox);
  function expand(node, key) {
    if (Array.isArray(node)) return node.map((item, i) => expand(item, `${key}/${item?.props?.key ?? i}`));
    if (!node || typeof node !== 'object') return node;
    if (typeof node.type === 'function') {
      const id = `${key}:${node.type.name}`;
      used.add(id);
      if (!instances.has(id)) instances.set(id, { hooks: [] });
      current = instances.get(id); cursor = 0;
      return expand(node.type(node.props), id);
    }
    const props = { ...node.props };
    for (const slot of ['children', 'actions', 'action', 'right', 'footer', 'subtitle']) if (props[slot] != null) props[slot] = expand(props[slot], `${key}:${slot}`);
    return { ...node, props };
  }
  function render() {
    used = new Set(); dirty = false;
    tree = expand({ type: sandbox.TasksPage, props: {} }, 'root');
    for (const [id, instance] of instances) if (!used.has(id)) { instance.hooks.forEach(h => h?.cleanup?.()); instances.delete(id); }
    return tree;
  }
  const flatten = value => !value || typeof value !== 'object' ? [] : Array.isArray(value) ? value.flatMap(flatten) : [value, ...['children', 'actions', 'action', 'right', 'footer', 'subtitle'].flatMap(slot => flatten(value.props?.[slot]))];
  const all = () => flatten(render());
  const find = id => all().find(node => node.props?.['data-testid'] === id);
  const flush = async () => { for (let i = 0; i < 14; i++) { render(); effects.splice(0).forEach(fn => fn()); await new Promise(resolve => setImmediate(resolve)); if (!dirty && !effects.length) break; } render(); };
  return { sandbox, calls, routes, downloads, notices, storage, render, all, find, flush,
    click(id) { const node = find(id); assert.ok(node, `${id} exists`); return node.props.onClick?.(); },
    change(id, value) { const node = find(id); assert.ok(node, `${id} exists`); node.props.onChange({ target: { value } }); },
    unmount() { instances.forEach(instance => instance.hooks.forEach(h => h?.cleanup?.())); instances.clear(); },
  };
}
const ready = async options => { const h = harness(options); await h.flush(); return h; };
const checks = [];
async function check(name, run) { try { await run(); checks.push({ name, pass: true }); } catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 500) }); } }

await check('queue keeps task, latest job and publication as separate facts', async () => {
  const h = await ready({ rows: [task('published', { status: 'published', latestJob: { status: 'failed', errorMessage: '实际失败原因' } })] });
  const text = textOf(h.render());
  assert.match(text, /排班状态/); assert.match(text, /发布状态/); assert.match(text, /已发布/); assert.match(text, /求解失败/);
  assert.ok(!text.includes('已完成')); assert.ok(!text.includes('%'));
});
await check('school queue and CSV never merge browser-local preview records', async () => {
  const h = await ready({ local: [{ id: 'local-A', name: '浏览器旧预览', source: 'upload', schedule: [], stats: {} }] });
  assert.match(textOf(h.find('tasks-v5-queue')), /学校任务/);
  assert.ok(!textOf(h.find('tasks-v5-queue')).includes('浏览器旧预览'));
  assert.match(textOf(h.find('tasks-v5-local')), /浏览器旧预览/);
  h.click('tasks-v5-export'); const csv = await h.downloads[0].blob.text(); assert.ok(!csv.includes('浏览器旧预览'));
});
await check('successful empty school response enables onboarding even with separate local previews', async () => {
  const h = await ready({ rows: [], local: [{ id: 'local-A', name: '本地示例', schedule: [] }] });
  assert.match(textOf(h.find('tasks-v5-empty')), /当前学校/);
  assert.ok(!textOf(h.render()).includes('本账号未导入'));
  assert.ok(h.find('tasks-v5-template-teachers')); assert.ok(h.find('tasks-v5-template-classrooms')); assert.ok(h.find('tasks-v5-template-exams'));
});
await check('new login in an existing school still displays that school records', async () => {
  const h = await ready(); assert.match(textOf(h.find('tasks-v5-queue')), /学校任务 task-A/); assert.equal(h.find('tasks-v5-empty'), undefined);
});
await check('loading never exposes zero count or an empty-school invitation', async () => {
  const pending = deferred(), h = harness({ get: () => pending.promise }); await h.flush();
  assert.ok(h.find('tasks-loading')); assert.equal(h.find('tasks-v5-empty'), undefined); assert.equal(h.find('tasks-v5-queue'), undefined);
  assert.ok(!textOf(h.render()).includes('0 个任务')); h.unmount(); pending.resolve([]);
});
await check('permission, network and malformed responses are errors, never onboarding or local fallback', async () => {
  for (const get of [() => Promise.reject(Object.assign(Error('无权限'), { status: 403 })), () => Promise.reject(Error('离线')), () => Promise.resolve({ items: [] }), () => Promise.resolve([null]), () => Promise.resolve([{ name: '缺少标识' }])]) {
    const h = await ready({ get, local: [{ id: 'local-A', name: '不允许回退', schedule: [] }] });
    assert.ok(h.find('tasks-load-error')); assert.equal(h.find('tasks-v5-empty'), undefined); assert.equal(h.find('tasks-v5-queue'), undefined);
  }
});
await check('missing API method and synchronous API errors are rendered, not thrown', async () => {
  for (const options of [{ missingApi: true }, { get: () => { throw Error('同步失败'); } }]) { const h = await ready(options); assert.ok(h.find('tasks-load-error')); }
  const h = harness(); h.sandbox.window.api.get = undefined; await h.flush(); assert.ok(h.find('tasks-load-error'));
});
await check('retry completes against server records and stale request cannot overwrite it', async () => {
  const old = deferred(); let listCalls = 0;
  const h = harness({ get: () => ++listCalls === 1 ? old.promise : Promise.resolve([task('new')]) }); await h.flush();
  h.click('tasks-v5-refresh'); await h.flush(); old.resolve([task('old')]); await h.flush();
  assert.match(textOf(h.find('tasks-v5-queue')), /学校任务 new/); assert.ok(!textOf(h.find('tasks-v5-queue')).includes('学校任务 old'));
});
await check('search and needs-attention are real filters; filtered empty is not a new school', async () => {
  const h = await ready({ rows: [task('published', { status: 'published' }), task('failed', { status: 'failed' }), task('draft', { status: 'draft' })] });
  h.click('tasks-v5-attention'); assert.ok(!textOf(h.find('tasks-v5-queue')).includes('学校任务 published')); assert.match(textOf(h.find('tasks-v5-queue')), /学校任务 failed/);
  h.change('tasks-v5-search', '不存在'); assert.ok(h.find('tasks-v5-filter-empty')); assert.equal(h.find('tasks-v5-empty'), undefined);
  h.click('tasks-v5-reset'); assert.match(textOf(h.find('tasks-v5-queue')), /学校任务 published/);
});
await check('actual export preserves real zero, selected rows and formula protection', async () => {
  const h = await ready({ rows: [task('A', { name: '=公式任务', stats: { exams: 0, assignments: 0 } }), task('B')] });
  h.change('tasks-v5-search', '公式'); h.click('tasks-v5-export');
  const csv = await h.downloads[0].blob.text(); assert.ok(csv.includes("'=公式任务")); assert.ok(csv.includes('"0"')); assert.ok(!csv.includes('学校任务 B')); assert.match(csv, /发布状态/);
});
await check('failure drawer fetches the selected task and shows actual upload reports without writes', async () => {
  const failed = task('failed', { status: 'failed', uploads: [{ id: 'u-A', kind: 'teachers', originalName: '真实教师表.xlsx', report: { counts: { pass: 0, warn: 0, error: 1 }, items: [{ severity: 'error', row: 8, problem: '真实错误描述', suggest: '核对工号' }] } }], latestJob: { jobId: 'job-A', status: 'failed', errorMessage: '真实求解原因' } });
  const h = await ready({ rows: [failed] }); h.click('tasks-v5-detail-failed'); await h.flush();
  assert.ok(h.calls.some(call => call.url === '/exam-tasks/failed')); assert.match(textOf(h.find('tasks-v5-drawer')), /真实教师表.xlsx/); assert.match(textOf(h.find('tasks-v5-drawer')), /真实求解原因/); assert.match(textOf(h.find('tasks-v5-drawer')), /真实错误描述/);
  assert.ok(h.calls.every(call => call.method === 'get'));
});
await check('published task with a failed latest job does not claim all past results absent', async () => {
  const h = await ready({ rows: [task('A', { status: 'published', latestJob: { status: 'failed' } })] }); h.click('tasks-v5-detail-A'); await h.flush();
  const text = textOf(h.find('tasks-v5-drawer')); assert.match(text, /已发布/); assert.match(text, /最近.*失败|最近.*未成功/); assert.ok(!text.includes('未生成可发布结果')); assert.match(text, /原因.*未提供|未提供.*原因/);
});
await check('drawer missing reports and missing job stay unavailable, not green success', async () => {
  const h = await ready({ rows: [task('A', { status: 'draft', uploads: [{ id: 'u-A', kind: 'exams', originalName: '考试.xlsx', report: null }] })] }); h.click('tasks-v5-detail-A'); await h.flush();
  const text = textOf(h.find('tasks-v5-drawer')); assert.match(text, /暂无校验报告/); assert.match(text, /尚无求解作业/); assert.ok(!text.includes('校验通过'));
});
await check('drawer rejects wrong identity and retains a retry rather than stale task data', async () => {
  const h = await ready({ get: url => Promise.resolve(url === '/exam-tasks' ? [task('A')] : task('other', { name: '错误任务秘密' })) }); h.click('tasks-v5-detail-A'); await h.flush();
  assert.ok(h.find('tasks-v5-detail-error')); assert.ok(!textOf(h.find('tasks-v5-drawer')).includes('错误任务秘密'));
});
await check('closed drawer ignores a late response when another task opens', async () => {
  const delayed = deferred(); const h = await ready({ get: url => url === '/exam-tasks' ? Promise.resolve([task('A'), task('B')]) : url.endsWith('/A') ? delayed.promise : Promise.resolve(task('B')) });
  h.click('tasks-v5-detail-A'); await h.flush(); h.click('tasks-v5-close-detail'); await h.flush(); h.click('tasks-v5-detail-B'); await h.flush(); delayed.resolve(task('A', { name: '迟到的 A 内容' })); await h.flush();
  assert.match(textOf(h.find('tasks-v5-drawer')), /学校任务 B/); assert.ok(!textOf(h.find('tasks-v5-drawer')).includes('迟到的 A 内容'));
});
await check('full-detail action binds exact task ID and cannot silently create a different task', async () => {
  const h = await ready(); h.click('tasks-v5-detail-task-A'); await h.flush(); h.click('tasks-v5-full-detail');
  assert.equal(h.storage.get('jkxt:taskDetailId'), 'task-A'); assert.deepEqual(h.routes, ['task-detail']);
  const denied = await ready({ storageFails: true }); denied.click('tasks-v5-detail-task-A'); await denied.flush(); denied.click('tasks-v5-full-detail'); assert.equal(denied.routes.length, 0);
});
await check('template download uses the real blank-template endpoint and rejects error documents', async () => {
  const h = await ready({ rows: [] }); await h.click('tasks-v5-template-teachers'); await h.flush(); assert.ok(h.calls.some(call => call.method === 'fetch' && call.args[0] === '/api/v1/import/templates/teachers')); assert.equal(h.downloads[0].filename, '教师表-模板.xlsx');
  const fail = await ready({ rows: [], fetcher: async () => ({ ok: true, blob: async () => new Blob(['{"error":true}'], { type: 'application/json' }) }) });
  await fail.click('tasks-v5-template-exams'); await fail.flush(); assert.equal(fail.downloads.length, 0); assert.ok(fail.find('tasks-v5-template-error'));
});
await check('new stylesheet is scoped, theme-based and has readable responsive controls', () => {
  const css = fs.readFileSync(path.join(root, 'src/styles/tasks-v5.css'), 'utf8');
  assert.match(css, /font-size:\s*16px/); assert.match(css, /line-height:\s*26px/); assert.match(css, /font-size:\s*13px/); assert.match(css, /min-height:\s*44px/);
  assert.match(css, /overflow-wrap:\s*anywhere/); assert.match(css, /focus-visible/); assert.match(css, /@media/); assert.doesNotMatch(css, /#[\da-f]{3,8}\b/i);
  assert.doesNotMatch(css, /var\(--(?:success|warning|danger)-soft\)/, 'status backgrounds must adapt to the current raised surface');
  assert.match(css, /\.tasks-v5__pill\s*\{[^}]*white-space:\s*normal/, 'unknown status text must wrap rather than overflow a narrow cell');
});

console.log(JSON.stringify({ contract: 'TasksV5/actual-hooks-and-handlers', checks, passed: checks.filter(check => check.pass).length, failed: checks.filter(check => !check.pass).length }, null, 2));
if (checks.some(check => !check.pass)) process.exitCode = 1;
