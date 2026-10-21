'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const appRoot = path.resolve(__dirname, '..');
const Babel = require('../assets/vendor/babel-standalone.7.29.0.min.js');
const appSource = Babel.transform(fs.readFileSync(process.env.JKXT_SESSION_APP_SOURCE || path.join(appRoot, 'src/app.jsx'), 'utf8'),
  { presets: ['react'] }).code;
const apiSource = fs.readFileSync(path.join(appRoot, 'src/api.js'), 'utf8');
const profile = (role = 'ADMIN', mustChangePassword = false) => ({
  user: { id: 'synthetic-session-user', username: 'session.fixture', role, mustChangePassword }, onboarded: true,
});

// Execute the actual App, every App effect, and the actual API adapter. Only the
// browser/React scheduler and HTTP boundary are deterministic; this is not E2E.
function fixture(t, { ignoreAbort = false, remembered = true, search = '', missingApi = false } = {}) {
  const slots = [], effects = [], requests = [], events = [], timers = new Map(), listeners = new Map();
  const stored = new Map(remembered ? [['jkxt:auth', '1'], ['jkxt:page', 'dashboard']] : []);
  let cursor = 0, dirty = false, tree, mounted = true, time = 0, nextTimer = 1, lateUpdates = 0;
  const localStorage = { getItem: key => stored.get(key) ?? null,
    setItem: (key, value) => stored.set(key, String(value)), removeItem: key => stored.delete(key) };
  const sameDeps = (left, right) => left && right && left.length === right.length
    && left.every((value, index) => Object.is(value, right[index]));
  const schedule = (callback, delay) => {
    const id = nextTimer++;
    timers.set(id, { callback, due: time + delay });
    return id;
  };
  const React = {
    Fragment: 'fragment',
    createElement: (type, props, ...children) => ({ type, props: props || {}, children: children.flat(Infinity) }),
    useState(initial) {
      const index = cursor++;
      if (!slots[index]) {
        slots[index] = { value: typeof initial === 'function' ? initial() : initial };
        slots[index].set = value => {
          if (!mounted) { lateUpdates++; return; }
          const next = typeof value === 'function' ? value(slots[index].value) : value;
          if (!Object.is(next, slots[index].value)) { slots[index].value = next; dirty = true; }
        };
      }
      return [slots[index].value, slots[index].set];
    },
    useRef(value) { return (slots[cursor++] ||= { current: value }); },
    useEffect(callback, deps) {
      const index = cursor++;
      if (!slots[index] || !sameDeps(slots[index].deps, deps)) {
        const previous = slots[index];
        slots[index] = { deps, cleanup: previous?.cleanup };
        effects.push(() => { slots[index].cleanup?.(); slots[index].cleanup = callback(); });
      }
    },
  };
  function LoginPage() {}
  const window = {
    location: { search, origin: 'https://session-fixture.invalid', hostname: 'session-fixture.invalid' },
    JKXTNavigation: { pageMap: {
      dashboard: { mode: 'admin', title: '学校工作台', Comp: () => function DashboardFixture() {} },
      'super-dashboard': { mode: 'super', title: '超管工作台', Comp: () => function SuperFixture() {} },
    } },
    setTimeout: schedule, clearTimeout: id => timers.delete(id), scrollTo() {},
    addEventListener(type, callback) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(callback); },
    removeEventListener(type, callback) { listeners.get(type)?.delete(callback); },
    dispatchEvent(event) { events.push(event); for (const callback of listeners.get(event.type) || []) callback(event); },
    ui: { toast() {}, confirm: async () => true },
  };
  const fetch = (url, options = {}) => {
    assert.equal(url, '/api/v1/auth/me', 'Session boot may only request the real identity endpoint');
    assert.equal(options.method, 'GET');
    assert.equal(options.credentials, 'include');
    assert.equal(options.headers['X-JKXT-Portal'], search === '?portal=super' ? 'platform' : 'school');
    return new Promise((resolve, reject) => {
      const request = { signal: options.signal, reject,
        respond(status, body = '', textPromise) {
          resolve({ status, ok: status >= 200 && status < 300, headers: { get: () => null },
            text: () => textPromise || Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)) });
        },
      };
      requests.push(request);
      if (!ignoreAbort) options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
    });
  };
  const document = { cookie: '', body: { style: {} }, title: '', getElementById: () => null,
    addEventListener() {}, removeEventListener() {} };
  const context = vm.createContext({ React, window, document, localStorage,
    sessionStorage: { getItem: () => null, setItem() {} }, URLSearchParams, FormData, Blob, ArrayBuffer,
    AbortController, DOMException, CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
    fetch, setTimeout: schedule, clearTimeout: id => timers.delete(id), requestAnimationFrame: callback => schedule(callback, 0),
    cancelAnimationFrame: id => timers.delete(id), ReactDOM: { createRoot: () => ({ render() {} }) },
    useTweaks: defaults => [defaults, () => {}], LoginPage, orgName: () => '合成测试学校',
    OverlayHost() {}, IconLogout() {}, Topbar() {}, Sidebar() {}, ProductGlyph() {}, OnboardingModal() {},
    TweaksPanel() {}, TweakSection() {}, TweakColor() {}, TweakRadio() {}, TweakSlider() {}, TweakToggle() {}, TweakButton() {}, TweakSelect() {},
  });
  if (!missingApi) vm.runInContext(apiSource, context);
  vm.runInContext(appSource, context);
  function render() {
    cursor = 0; dirty = false;
    tree = vm.runInContext('App()', context);
    while (effects.length) effects.shift()();
  }
  async function flush() {
    for (let count = 0; count < 30; count++) {
      await Promise.resolve();
      if (mounted && dirty) render();
    }
  }
  async function advance(milliseconds) {
    const target = time + milliseconds;
    for (;;) {
      const next = [...timers].filter(([, timer]) => timer.due <= target).sort((a, b) => a[1].due - b[1].due)[0];
      if (!next) break;
      const [id, timer] = next; time = timer.due; timers.delete(id); timer.callback(); await flush();
    }
    time = target; await flush();
  }
  const nodes = node => node && typeof node === 'object' ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const text = node => Array.isArray(node) ? node.map(text).join('') : typeof node === 'string' ? node
    : node && typeof node === 'object' ? text(node.children || []) : '';
  const retry = () => {
    const button = nodes(tree).find(node => node.type === 'button' && /重新连接/.test(text(node)));
    assert.ok(button, 'The real App must provide an explicit manual reconnect action');
    return button.props;
  };
  function unmount() {
    if (!mounted) return;
    mounted = false;
    for (const slot of slots) slot?.cleanup?.();
  }
  t.after(unmount);
  render();
  return { requests, events, stored, window, flush, advance, retry, unmount,
    text: () => text(tree), lateUpdates: () => lateUpdates,
    loginVisible: () => nodes(tree).some(node => node.type === LoginPage),
    shellVisible: () => nodes(tree).some(node => node.props['data-page-id']),
    forcedPasswordVisible: () => nodes(tree).some(node => node.type?.name === 'RequiredPasswordChange'),
  };
}

for (const ignoreAbort of [false, true]) test(`session wait is bounded to 15 seconds, even when abort is ${ignoreAbort ? 'ignored' : 'honoured'}`, async t => {
  const page = fixture(t, { ignoreAbort });
  assert.equal(page.requests.length, 1);
  assert.match(page.text(), /正在加载会话/);
  await page.advance(14999);
  assert.match(page.text(), /正在加载会话/);
  await page.advance(1);
  assert.equal(page.requests[0].signal?.aborted, true);
  assert.match(page.text(), /暂时无法连接服务/);
  assert.doesNotMatch(page.text(), /正在加载会话/);
  assert.equal(page.stored.get('jkxt:auth'), '1');
  assert.equal(page.loginVisible(), false);
  assert.equal(page.shellVisible(), false);
  assert.equal(page.retry().disabled, false);
  await page.advance(300000);
  assert.equal(page.requests.length, 1, 'No timer may silently keep a free server alive');
});

for (const status of [502, 503, 504]) test(`HTTP ${status} keeps remembered identity without rendering an authenticated page`, async t => {
  const page = fixture(t);
  page.requests[0].respond(status);
  await page.flush();
  assert.match(page.text(), /暂时无法连接服务/);
  assert.equal(page.stored.get('jkxt:auth'), '1');
  assert.equal(page.loginVisible(), false);
  assert.equal(page.shellVisible(), false);
  assert.equal(page.retry().disabled, false);
});

test('network failure remains retryable and is not represented as an expired login', async t => {
  const page = fixture(t);
  page.requests[0].reject(new TypeError('Failed to fetch'));
  await page.flush();
  assert.match(page.text(), /暂时无法连接服务/);
  assert.equal(page.stored.get('jkxt:auth'), '1');
  assert.equal(page.loginVisible(), false);
  assert.equal(page.retry().disabled, false);
});

test('404 is an explicit interface configuration failure, not a logged-out session', async t => {
  const page = fixture(t);
  page.requests[0].respond(404, { code: 'RESOURCE_NOT_FOUND' });
  await page.flush();
  assert.match(page.text(), /会话接口不存在/);
  assert.equal(page.stored.get('jkxt:auth'), '1');
  assert.equal(page.loginVisible(), false);
});

test('only a real 401 clears remembered authentication and renders the login page', async t => {
  const page = fixture(t);
  page.requests[0].respond(401, { code: 'AUTH_UNAUTHENTICATED' });
  await page.flush();
  assert.equal(page.stored.has('jkxt:auth'), false);
  assert.equal(page.loginVisible(), true);
  assert.equal(page.shellVisible(), false);
});

test('manual reconnect is single-flight before rerender and can restore the real session', async t => {
  const page = fixture(t);
  page.requests[0].respond(504);
  await page.flush();
  const click = page.retry().onClick;
  click(); click();
  await page.flush();
  assert.equal(page.requests.length, 2);
  assert.match(page.text(), /正在加载会话/);
  assert.equal(page.shellVisible(), false);
  page.requests[1].respond(200, profile());
  await page.flush();
  assert.equal(page.shellVisible(), true);
  assert.equal(page.window.__jkxtMe.user.id, 'synthetic-session-user');
  await page.advance(300000);
  assert.equal(page.requests.length, 2);
});

test('late success from a timed-out request cannot replace a later 401 or revive a session', async t => {
  const page = fixture(t, { ignoreAbort: true });
  await page.advance(15000);
  page.retry().onClick(); await page.flush();
  page.requests[1].respond(401, { code: 'AUTH_UNAUTHENTICATED' }); await page.flush();
  page.requests[0].respond(200, profile('SUPER_ADMIN')); await page.flush();
  assert.equal(page.loginVisible(), true);
  assert.equal(page.shellVisible(), false);
  assert.equal(page.stored.has('jkxt:auth'), false);
  assert.equal(page.window.__jkxtMe, null);
});

test('late 401 from an aborted attempt cannot log out a newer verified session', async t => {
  const page = fixture(t, { ignoreAbort: true });
  await page.advance(15000);
  page.retry().onClick(); await page.flush();
  page.requests[1].respond(200, profile()); await page.flush();
  assert.equal(page.shellVisible(), true);
  page.requests[0].respond(401, { code: 'AUTH_UNAUTHENTICATED' }); await page.flush();
  assert.equal(page.shellVisible(), true, 'A discarded old request must not emit a global unauthorized event');
  assert.equal(page.loginVisible(), false);
  assert.equal(page.stored.get('jkxt:auth'), '1');
  assert.equal(page.window.__jkxtMe.user.id, 'synthetic-session-user');
  assert.equal(page.events.filter(event => event.type === 'jkxt:api-unauthorized').length, 0);
});

test('unmount aborts transport and prevents all late session writes', async t => {
  const page = fixture(t, { ignoreAbort: true });
  page.unmount();
  assert.equal(page.requests[0].signal?.aborted, true);
  page.requests[0].respond(200, profile()); await page.flush(); await page.advance(30000);
  assert.equal(page.lateUpdates(), 0);
  assert.equal(page.window.__jkxtMe, undefined);
  assert.equal(page.events.some(event => event.type === 'jkxt:me-changed'), false);
});

test('a stalled response body is covered by the same bounded session deadline', async t => {
  const page = fixture(t, { ignoreAbort: true });
  page.requests[0].respond(200, '', new Promise(() => {}));
  await page.flush(); await page.advance(15000);
  assert.match(page.text(), /暂时无法连接服务/);
  assert.equal(page.requests[0].signal?.aborted, true);
});

for (const body of ['', 'null', '{}', '<html>proxy page</html>', { user: {} },
  { user: { role: 'ADMIN' } }, { user: { id: ' ', role: 'ADMIN' } },
  { user: { id: 'fixture', role: 'UNSUPPORTED' } }]) test(`invalid successful identity is never accepted: ${JSON.stringify(body)}`, async t => {
  const page = fixture(t);
  page.requests[0].respond(200, body); await page.flush();
  assert.match(page.text(), /会话响应不完整/);
  assert.equal(page.shellVisible(), false);
  assert.equal(page.loginVisible(), false);
  assert.equal(page.events.some(event => event.type === 'jkxt:me-changed'), false);
});

for (const role of ['ADMIN', 'VIEWER', 'TEACHER_QUERY', 'SUPER_ADMIN']) test(`real identity with supported ${role} role remains accepted`, async t => {
  const page = fixture(t, { search: role === 'SUPER_ADMIN' ? '?portal=super' : '' });
  page.requests[0].respond(200, profile(role)); await page.flush();
  assert.equal(page.shellVisible(), true);
  assert.equal(page.window.__jkxtMe.user.role, role);
  assert.equal(page.stored.get('jkxt:mode'), role === 'SUPER_ADMIN' ? 'super' : 'admin');
});

test('platform identity and required password change remain server controlled', async t => {
  const page = fixture(t, { search: '?portal=super' });
  page.requests[0].respond(200, profile('SUPER_ADMIN', true)); await page.flush();
  assert.equal(page.forcedPasswordVisible(), true);
  assert.equal(page.shellVisible(), false);
  assert.equal(page.window.__jkxtMe.user.role, 'SUPER_ADMIN');
  assert.equal(page.stored.get('jkxt:mode'), 'super');
});

test('missing API reports a retryable startup fault without fabricating login', async t => {
  const page = fixture(t, { missingApi: true }); await page.flush();
  assert.match(page.text(), /接口尚未就绪/);
  assert.equal(page.requests.length, 0);
  assert.equal(page.shellVisible(), false);
  assert.equal(page.retry().disabled, false);
});
