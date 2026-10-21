'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');
const app = process.env.JKXT_TEST_APP_ROOT || path.resolve(__dirname, '..');
const Babel = require(path.join(app, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = Babel.transform(fs.readFileSync(path.join(app, 'src/pages/login.jsx'), 'utf8'),
  { presets: ['react'] }).code;
const apiSource = fs.readFileSync(path.join(app, 'src/api.js'), 'utf8');
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j9X8AAAAASUVORK5CYII=';
const challenge = id => ({ captchaId: id, imageBase64: PNG, expiresInSeconds: 180 });

// Run the real LoginPage and API adapter, its handlers and all effects. Only React scheduling,
// browser DOM/time and the HTTP boundary are controlled. This is not CDN/E2E evidence.
function fixture(t, { missingApi = false, ignoreAbort = false } = {}) {
  const slots = [], effects = [], requests = [], logins = [], toasts = [], timers = new Map();
  let cursor = 0, dirty = false, mounted = true, tree, nextTimer = 1, time = 0, lateUpdates = 0;
  const sameDeps = (left, right) => left && right && left.length === right.length
    && left.every((value, index) => Object.is(value, right[index]));
  const schedule = (callback, delay, interval = false) => {
    const id = nextTimer++;
    timers.set(id, { callback, due: time + delay, interval, delay });
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
    useRef(value) {
      const index = cursor++;
      return (slots[index] ||= { current: value });
    },
    useMemo(make, deps) {
      const index = cursor++;
      if (!slots[index] || !sameDeps(slots[index].deps, deps)) slots[index] = { value: make(), deps };
      return slots[index].value;
    },
    useCallback(callback, deps) { return React.useMemo(() => callback, deps); },
    useEffect(callback, deps) {
      const index = cursor++;
      if (!slots[index] || !sameDeps(slots[index].deps, deps)) {
        const previous = slots[index];
        slots[index] = { deps, cleanup: previous?.cleanup };
        effects.push(() => { slots[index].cleanup?.(); slots[index].cleanup = callback(); });
      }
    },
  };
  const response = (status, text) => ({ status, ok: status >= 200 && status < 300,
    headers: { get: () => null }, text: async () => text });
  const fetch = (route, options = {}) => {
    if (route === '/api/v1/auth/school/login') {
      logins.push(JSON.parse(options.body));
      return Promise.resolve(response(200, JSON.stringify({ user: { role: 'ADMIN' } })));
    }
    assert.equal(route, '/api/v1/auth/captcha', 'Only the expected captcha route may be requested');
    assert.equal(options.method, 'POST');
    assert.equal(options.credentials, 'include');
    assert.equal(options.headers['X-JKXT-Portal'], 'school');
    return new Promise((resolve, reject) => {
      const request = { route, signal: options.signal, reject,
        resolve: value => resolve(response(200, JSON.stringify(value))),
        resolveResponse: (status, text) => resolve(response(status, text)),
      };
      requests.push(request);
      if (!ignoreAbort) options.signal?.addEventListener('abort', () =>
        reject(new DOMException('Request aborted', 'AbortError')), { once: true });
    });
  };
  const window = {
    location: { hostname: 'synthetic.netlify.app' },
    crypto: webcrypto, btoa: value => Buffer.from(value, 'binary').toString('base64'),
    setTimeout: (callback, delay) => schedule(callback, delay), clearTimeout: id => timers.delete(id),
    addEventListener() {}, removeEventListener() {}, dispatchEvent() {},
    ui: { toast: (...args) => toasts.push(args) },
  };
  const context = vm.createContext({
    React, window, URLSearchParams, AbortController, DOMException, FormData, Blob, ArrayBuffer, fetch,
    CustomEvent: class {}, sessionStorage: { getItem: () => null, setItem() {} },
    setTimeout: window.setTimeout, clearTimeout: window.clearTimeout,
    setInterval: (callback, delay) => schedule(callback, delay, true), clearInterval: window.clearTimeout,
    document: {
      cookie: '', body: { appendChild() {} }, querySelectorAll: () => [],
      createElement: () => ({ style: {}, classList: { add() {}, remove() {} }, remove() {} }),
    },
    localStorage: { getItem: () => null, removeItem() {}, setItem() {} },
    IconHelp() {}, IconSettings() {}, IconUsers() {}, Checkbox() {},
  });
  if (!missingApi) vm.runInContext(apiSource, context);
  vm.runInContext(source, context);
  function render() {
    cursor = 0; dirty = false;
    tree = vm.runInContext('LoginPage({})', context);
    while (effects.length) effects.shift()();
  }
  async function flush() {
    for (let count = 0; count < 20; count++) {
      await Promise.resolve();
      if (mounted && dirty) render();
    }
  }
  async function advance(milliseconds) {
    const target = time + milliseconds;
    for (;;) {
      const next = [...timers].filter(([, timer]) => timer.due <= target)
        .sort((a, b) => a[1].due - b[1].due)[0];
      if (!next) break;
      const [id, timer] = next;
      time = timer.due;
      if (timer.interval) timer.due += timer.delay; else timers.delete(id);
      timer.callback();
      await flush();
    }
    time = target;
    await flush();
  }
  const nodes = node => node && typeof node === 'object'
    ? [node, ...(node.children || []).flatMap(nodes)] : [];
  const text = node => Array.isArray(node) ? node.map(text).join('')
    : typeof node === 'string' ? node : node && typeof node === 'object' ? text(node.children || []) : '';
  function find(predicate, label) {
    const found = nodes(tree).find(predicate);
    assert.ok(found, `Missing rendered ${label}`);
    return found.props;
  }
  const input = id => find(node => node.type === 'input' && node.props.id === id, id);
  const refresh = () => find(node => node.type === 'button' && node.props['aria-label'] === '刷新验证码', 'captcha refresh');
  const form = () => find(node => node.type === 'form', 'login form');
  const captchaImages = () => nodes(tree).filter(node => node.type === 'img' && node.props.alt === '验证码图片');
  function unmount() {
    if (!mounted) return;
    mounted = false;
    for (const slot of slots) slot?.cleanup?.();
  }
  t.after(unmount);
  render();
  return {
    requests, logins, toasts, input, refresh, form, captchaImages, flush, advance, unmount,
    text: () => text(tree), lateUpdates: () => lateUpdates,
    async change(id, value) { input(id).onChange({ target: { value } }); await flush(); },
  };
}

function assertUnavailable(page) {
  assert.equal(page.input('login-captcha').value, '', 'Failed challenge must clear the answer');
  assert.equal(page.captchaImages().length, 0, 'No local or stale challenge image may remain');
  assert.doesNotMatch(page.text(), /静态预览|本地验证码|系统在线|服务正常/);
  assert.match(page.text(), /暂未连接|尚未连接|连接超时/);
  assert.match(page.text(), /刷新|重试/);
  assert.equal(page.refresh().disabled, false, 'Manual retry must be available after failure');
}

test('missing API never manufactures a local captcha', async t => {
  const page = fixture(t, { missingApi: true });
  await page.flush();
  assertUnavailable(page);
  assert.equal(page.requests.length, 0);
});

for (const [label, outcome] of [
  ['HTTP 502', request => request.resolveResponse(502, '<html>proxy unavailable</html>')],
  ['network failure', request => request.reject(new TypeError('Failed to fetch'))],
  ['missing captcha image', request => request.resolve({ captchaId: 'server-challenge' })],
  ['invalid captcha image', request => request.resolve({ captchaId: 'server-challenge', imageBase64: '<html>not an image</html>' })],
  ['missing captcha id', request => request.resolve({ imageBase64: PNG })],
  ['blank captcha id', request => request.resolve(challenge('   '))],
  ['non-string captcha id', request => request.resolve(challenge({ unexpected: true }))],
  ['HTML success response', request => request.resolveResponse(200, '<html>Starting service</html>')],
  ['local captcha id from an invalid response', request => request.resolve(challenge('local-untrusted'))],
]) {
  test(`${label} clears the previous challenge and cannot submit it`, async t => {
    const page = fixture(t);
    page.requests[0].resolve(challenge('initial-real-id'));
    await page.flush();
    await page.change('login-username', 'synthetic-user');
    await page.change('login-password', 'synthetic-input-only');
    await page.change('login-captcha', 'ABCDE');
    const oldSubmit = page.form().onSubmit;
    const pending = page.refresh().onClick();
    await page.flush();
    assert.equal(page.input('login-captcha').value, '', 'Refresh invalidates the old answer immediately');
    assert.equal(page.captchaImages().length, 0);
    outcome(page.requests[1]);
    await pending;
    await page.flush();
    assertUnavailable(page);
    await oldSubmit({ preventDefault() {} });
    await page.flush();
    assert.equal(page.logins.length, 0, 'Even a stale form handler must not submit a retired challenge');
    const retry = page.refresh().onClick();
    page.requests[2].resolve(challenge('recovered-real-id'));
    await retry; await page.flush();
    assert.match(page.text(), /验证码服务已连接/);
    assert.equal(page.refresh().disabled, false);
    await page.change('login-captcha', 'NEWCODE');
    await page.form().onSubmit({ preventDefault() {} });
    assert.equal(page.logins.length, 1);
    assert.equal(page.logins[0].captchaId, 'recovered-real-id');
  });
}

test('cold-start request gets 20 seconds, times out even if transport ignores abort, and permits retry', async t => {
  const page = fixture(t, { ignoreAbort: true });
  await page.flush();
  await page.advance(1800);
  assert.equal(page.requests[0].signal.aborted, false, 'The old 1.8-second cutoff must be removed');
  assert.equal(page.refresh().disabled, true);
  await page.advance(18200);
  assert.equal(page.requests[0].signal.aborted, true);
  assertUnavailable(page);
  const retry = page.refresh().onClick();
  page.requests[1].resolve(challenge('new-real-id'));
  await retry; await page.flush();
  assert.equal(page.captchaImages()[0].props.src, PNG);
  assert.match(page.text(), /验证码服务已连接/);
  page.requests[0].resolve(challenge('late-old-id'));
  await page.flush();
  await page.change('login-username', 'synthetic-user');
  await page.change('login-password', 'synthetic-input-only');
  await page.change('login-captcha', 'ABCDE');
  await page.form().onSubmit({ preventDefault() {} });
  assert.equal(page.logins.length, 1);
  assert.equal(page.logins[0].captchaId, 'new-real-id', 'Late timeout response cannot replace the new challenge');
});

test('manual refresh cannot reenter before React rerenders and makes no background retry', async t => {
  const page = fixture(t);
  page.requests[0].resolve(challenge('initial-real-id'));
  await page.flush();
  const click = page.refresh().onClick;
  const first = click();
  const second = click();
  await page.flush();
  assert.equal(page.requests.length, 2, 'Rapid duplicate clicks must start one request only');
  assert.equal(page.refresh().disabled, true);
  page.requests[1].resolve(challenge('fresh-real-id'));
  await Promise.all([first, second]); await page.flush();
  assert.equal(page.refresh().disabled, false);
  await page.advance(300000);
  assert.equal(page.requests.length, 2, 'No idle keepalive or autonomous retry is permitted');
});

test('unmount aborts an in-flight captcha and ignores a transport response delivered afterward', async t => {
  const page = fixture(t, { ignoreAbort: true });
  await page.flush();
  page.unmount();
  assert.equal(page.requests[0].signal.aborted, true);
  page.requests[0].resolve(challenge('after-unmount'));
  await page.flush();
  await page.advance(30000);
  assert.equal(page.lateUpdates(), 0, 'Unmounted page must not receive state updates');
  assert.equal(page.toasts.length, 0);
});

test('image error event retires the challenge and an old image event cannot clear its replacement', async t => {
  const page = fixture(t);
  page.requests[0].resolve(challenge('first-image-id'));
  await page.flush();
  await page.change('login-username', 'synthetic-user');
  await page.change('login-password', 'synthetic-input-only');
  await page.change('login-captcha', 'ABCDE');
  const oldSubmit = page.form().onSubmit;
  const oldImageError = page.captchaImages()[0].props.onError;
  oldImageError();
  await page.flush();
  assertUnavailable(page);
  assert.match(page.text(), /图片加载失败/);
  await oldSubmit({ preventDefault() {} });
  assert.equal(page.logins.length, 0);
  const retry = page.refresh().onClick();
  page.requests[1].resolve(challenge('replacement-image-id'));
  await retry; await page.flush();
  oldImageError();
  await page.flush();
  assert.equal(page.captchaImages().length, 1);
  assert.match(page.text(), /验证码服务已连接/);
  await page.change('login-captcha', 'NEWCODE');
  await page.form().onSubmit({ preventDefault() {} });
  assert.equal(page.logins[0].captchaId, 'replacement-image-id');
});
