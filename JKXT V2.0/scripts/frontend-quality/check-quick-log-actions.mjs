import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/quick-step3.jsx'), 'utf8');
const code = Babel.transform(source, { presets: ['react'] }).code;
const sampleLogs = Object.freeze([
  Object.freeze({ t: '09:01:02', text: '读取教师名单：张老师 ✓' }),
  Object.freeze({ t: '09:01:03', text: '检查科目：语文 / 数学' }),
]);
const nodes = value => !value || typeof value !== 'object' ? [] : Array.isArray(value)
  ? value.flatMap(nodes) : [value, ...nodes(value.props?.children)];
const textOf = value => typeof value === 'string' || typeof value === 'number' ? String(value)
  : Array.isArray(value) ? value.map(textOf).join('') : value && typeof value === 'object' ? textOf(value.props?.children) : '';
const deferred = () => { let resolve, reject; const promise = new Promise((done, fail) => { resolve = done; reject = fail; }); return { promise, resolve, reject }; };

function harness({ logs = sampleLogs, backendActive = true, clipboard = async () => {}, clickError = false, urlError = false } = {}) {
  const toasts = [], copied = [], blobs = [], created = [], revoked = [], timers = [], anchors = [], downloads = [], attached = [];
  let apiCalls = 0;
  const noRemote = () => { apiCalls++; throw new Error('local log controls must never request a backend'); };
  const sandbox = {
    console, Date, Blob: class extends Blob { constructor(parts, options) { super(parts, options); blobs.push(this); } },
    URL: {
      createObjectURL(blob) { if (urlError) throw new Error('object URL unavailable'); const url = `blob:current-page-${created.length}`; created.push({ url, blob }); return url; },
      revokeObjectURL(url) { revoked.push(url); },
    },
    setTimeout(fn, delay) { timers.push({ fn, delay }); return timers.length; }, clearTimeout() {},
    navigator: clipboard ? { clipboard: { writeText(value) { copied.push(value); return clipboard(value); } } } : {},
    window: { ui: { toast: (...args) => toasts.push(args) }, api: { get: noRemote, post: noRemote } }, fetch: noRemote,
    document: {
      body: { appendChild(anchor) { attached.push(anchor); } },
      createElement(tag) {
        assert.equal(tag, 'a');
        const anchor = { style: {}, click() { if (clickError) throw new Error('download failed'); downloads.push({ href: this.href, filename: this.download }); },
          remove() { const index = attached.indexOf(this); if (index >= 0) attached.splice(index, 1); this.removed = true; } };
        anchors.push(anchor); return anchor;
      },
    },
    React: { createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
      useState: initial => [typeof initial === 'function' ? initial() : initial, () => {}], useRef: current => ({ current }), useEffect() {} },
    testLogs: logs, testBackendActive: backendActive,
  };
  for (const name of new Set(source.match(/\bIcon\w+\b/g))) sandbox[name] = name;
  const context = vm.createContext(sandbox); vm.runInContext(code, context);
  const tree = vm.runInContext('QkCollapsibleLog({logs:testLogs,running:false,backendActive:testBackendActive})', context);
  const find = expression => nodes(tree).find(node => node.type === 'button' && expression.test(textOf(node)));
  return { tree, copy: find(/复制/), download: find(/下载/), toasts, copied, blobs, created, revoked, timers, anchors, downloads, attached,
    get apiCalls() { return apiCalls; }, flushTimers() { timers.splice(0).forEach(({ fn }) => fn()); } };
}

const checks = [];
async function check(name, run) {
  try { await run(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message }); }
}
const successes = h => h.toasts.filter(([, options]) => options?.kind === 'success');

await check('copy waits for clipboard acknowledgement before success', async () => {
  const pending = deferred(), h = harness({ clipboard: () => pending.promise });
  const operation = h.copy.props.onClick();
  assert.equal(successes(h).length, 0); pending.resolve(); await operation;
  assert.equal(successes(h).length, 1); assert.match(h.toasts[0][0], /当前日志/);
});
await check('clipboard rejection reports failure instead of a false success', async () => {
  const rejected = Promise.reject(new Error('permission denied')); rejected.catch(() => {});
  const h = harness({ clipboard: () => rejected }); await h.copy.props.onClick();
  assert.equal(successes(h).length, 0); assert.equal(h.toasts[0]?.[1]?.kind, 'error'); assert.match(h.toasts[0][0], /复制失败/);
});
await check('missing clipboard API reports unavailable and preserves source data', async () => {
  const h = harness({ clipboard: null }); await h.copy.props.onClick();
  assert.equal(successes(h).length, 0); assert.equal(h.toasts[0]?.[1]?.kind, 'error'); assert.equal(h.copied.length, 0);
  assert.deepEqual(sampleLogs.map(line => line.text), ['读取教师名单：张老师 ✓', '检查科目：语文 / 数学']);
});
await check('copy contains exactly the currently displayed Unicode log lines and no remote request', async () => {
  const h = harness(); await h.copy.props.onClick();
  assert.equal(h.copied[0], sampleLogs.map(line => `[${line.t}] ${line.text}`).join('\n')); assert.equal(h.apiCalls, 0);
});
await check('empty log actions are disabled and handlers have no side effects', async () => {
  const h = harness({ logs: [] }); assert.equal(h.copy.props.disabled, true); assert.equal(h.download.props.disabled, true);
  await h.copy.props.onClick(); await h.download.props.onClick();
  assert.equal(h.copied.length + h.created.length + h.toasts.length + h.apiCalls, 0);
});
await check('download creates a real UTF-8 BOM log snapshot with explicit current-page scope', async () => {
  const h = harness(); assert.equal(textOf(h.download), '下载当前日志'); await h.download.props.onClick();
  assert.equal(h.downloads.length, 1); assert.match(h.downloads[0].filename, /\.log$/); assert.match(h.downloads[0].href, /^blob:/);
  assert.match(h.blobs[0].type, /text\/plain;charset=utf-8/);
  const bytes = new Uint8Array(await h.blobs[0].arrayBuffer()); assert.deepEqual([...bytes.slice(0, 3)], [239, 187, 191]);
  const text = await h.blobs[0].text(); assert.match(text, /仅当前页面/); assert.match(text, /非后端完整日志/);
  assert.match(text, /后端/); for (const line of sampleLogs) assert.ok(text.includes(`[${line.t}] ${line.text}`));
  assert.equal(h.apiCalls, 0); assert.match(h.toasts[0][0], /已发起.*下载/);
});
await check('download removes temporary anchor and revokes its object URL after dispatch', async () => {
  const h = harness(); await h.download.props.onClick();
  assert.equal(h.downloads.length, 1); assert.equal(h.attached.length, 0); assert.equal(h.anchors[0].removed, true);
  assert.equal(h.revoked.length, 0); assert.equal(h.timers.length, 1); assert.ok(h.timers[0].delay > 0);
  h.flushTimers(); assert.deepEqual(h.revoked, [h.created[0].url]);
});
await check('demo snapshot remains labeled simulation and never claims remote full-log export', async () => {
  const h = harness({ backendActive: false }); await h.download.props.onClick();
  assert.equal(h.blobs.length, 1); const text = await h.blobs[0].text(); assert.match(text, /模拟/); assert.match(text, /非后端完整日志/);
  assert.equal(h.apiCalls, 0); assert.doesNotMatch(h.toasts.map(([message]) => message).join(' '), /已模拟下载|完整日志已下载/);
});
await check('failed download reports failure and still releases its temporary resources', async () => {
  const h = harness({ clickError: true }); await h.download.props.onClick();
  assert.equal(h.downloads.length, 0); assert.equal(successes(h).length, 0); assert.equal(h.toasts[0]?.[1]?.kind, 'error');
  assert.match(h.toasts[0][0], /下载失败/); assert.equal(h.attached.length, 0);
  h.flushTimers(); assert.deepEqual(h.revoked, [h.created[0].url]);
});
await check('unsupported object URL creates neither a fake download nor a remote fallback', async () => {
  const h = harness({ urlError: true }); await h.download.props.onClick();
  assert.equal(h.downloads.length, 0); assert.equal(h.apiCalls, 0); assert.equal(successes(h).length, 0);
  assert.equal(h.toasts[0]?.[1]?.kind, 'error'); assert.equal(h.attached.length, 0);
});

console.log(JSON.stringify({ suite: 'quick-current-log-actions-v1', boundary: 'VM behavior plus native Blob bytes; browser save completion is not asserted',
  passed: checks.filter(check => check.pass).length, total: checks.length, checks }, null, 2));
if (checks.some(check => !check.pass)) process.exitCode = 1;
