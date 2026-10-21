const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../src/api.js'), 'utf8');
function client(status, body) {
  const storage = {getItem: () => null, setItem() {}};
  const window = {location: {search: ''}, dispatchEvent() {}};
  vm.runInNewContext(source, {window, localStorage: storage, sessionStorage: storage,
    document: {cookie: ''}, URLSearchParams, FormData, Blob, ArrayBuffer,
    CustomEvent: class {}, fetch: async () => ({status, ok: status >= 200 && status < 300,
      headers: {get: () => null}, text: async () => body})});
  return window.api;
}
for (const status of [502, 503, 504]) test(`proxy HTML ${status} is a short retryable error, not raw HTML`, async () => {
  await assert.rejects(client(status, '<html><script>private-proxy-internals</script>'+'x'.repeat(100000)+'</html>').get('/auth/me'), e => {
    assert.equal(e.status, status);
    assert.equal(e.code, 'SERVICE_UNAVAILABLE');
    assert.match(e.message, /稍后重试/);
    assert(e.message.length < 120);
    assert.doesNotMatch(JSON.stringify(e), /private-proxy-internals|<html>|xxxx/);
    return true;
  });
});
test('other non-JSON failures preserve HTTP status but not server output', async () => {
  await assert.rejects(client(413, '<html>internal-path</html>').get('/fixture'), e => {
    assert.equal(e.status, 413); assert.match(e.message, /413/);
    assert.doesNotMatch(JSON.stringify(e), /internal-path|<html>/); return true;
  });
});
test('structured business errors keep code, details and trace ID', async () => {
  await assert.rejects(client(400, JSON.stringify({code:'IMPORT_INVALID',message:'请核对第3行',details:{row:3},traceId:'fixture'})).get('/fixture'), e => {
    assert.equal(e.code,'IMPORT_INVALID'); assert.equal(e.message,'请核对第3行');
    assert.equal(e.details.row,3); assert.equal(e.traceId,'fixture'); return true;
  });
});
test('valid JSON success remains unchanged', async () => {
  assert.equal((await client(200, '{"status":"UP"}').get('/health')).status,'UP');
});

// Exercise the real adapter with a bounded response sequence, not a mocked API.
// These are local transport-contract tests, not browser authentication evidence.
function sequencedClient(replies, {cookie = '', search = ''} = {}) {
  const queue = replies.slice();
  const calls = [], events = [];
  const storage = {getItem: () => null, setItem() {}};
  const document = {cookie};
  const window = {location: {search}, dispatchEvent: event => events.push(event)};
  vm.runInNewContext(source, {window, localStorage: storage, sessionStorage: storage,
    document, URLSearchParams, FormData, Blob, ArrayBuffer,
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
    fetch: async (url, options) => {
      calls.push({url, options});
      assert.ok(queue.length, 'The adapter must not make an unexpected request or retry');
      const reply = queue.shift();
      if (reply.cookie !== undefined) document.cookie = reply.cookie;
      return new Response(reply.body, {status: reply.status, headers: reply.headers});
    }});
  return {api: window.api, calls, events};
}

test('non-JSON 200 text and HTML preserve the existing success message contract', async () => {
  for (const body of ['plain success', '<html>successful-fixture</html>']) {
    const {api, calls, events} = sequencedClient([{status: 200, body}]);
    assert.equal((await api.get('/fixture')).message, body);
    assert.equal(calls.length, 1);
    assert.equal(events.length, 0);
  }
});

test('nested JSON business errors preserve message, code, details and trace ID', async () => {
  const body = JSON.stringify({error: {message: '业务冲突', code: 'CONFLICT', details: {row: 4}, traceId: 'nested-trace'}});
  const {api} = sequencedClient([{status: 409, body}]);
  await assert.rejects(api.get('/fixture'), error => {
    assert.equal(error.status, 409);
    assert.equal(error.message, '业务冲突');
    assert.equal(error.code, 'CONFLICT');
    assert.equal(error.details.row, 4);
    assert.equal(error.traceId, 'nested-trace');
    return true;
  });
});

test('401 keeps its unauthorized event for JSON and sanitized HTML failures', async () => {
  for (const body of ['{"code":"AUTH_UNAUTHENTICATED","message":"请登录"}', '<html>private-auth-proxy</html>']) {
    const {api, calls, events} = sequencedClient([{status: 401, body}]);
    await assert.rejects(api.auth.me(), error => {
      assert.equal(error.status, 401);
      assert.equal(events.length, 1);
      assert.equal(events[0].type, 'jkxt:api-unauthorized');
      assert.equal(events[0].detail, error);
      if (body.startsWith('{')) {
        assert.equal(error.code, 'AUTH_UNAUTHENTICATED');
        assert.equal(error.message, '请登录');
      } else {
        assert.equal(error.code, 'API_INVALID_RESPONSE');
        assert.doesNotMatch(error.message + JSON.stringify(error) + JSON.stringify(events), /private-auth-proxy|<html>/);
      }
      return true;
    });
    assert.equal(calls.length, 1);
  }
});

test('CSRF rotation retries once with the new cookie and preserves request context', async () => {
  const {api, calls, events} = sequencedClient([
    {status: 403, body: '{"code":"CSRF_TOKEN_INVALID"}', cookie: 'XSRF-TOKEN=new%20token'},
    {status: 200, body: '{"saved":true}'},
  ], {cookie: 'XSRF-TOKEN=old-token', search: '?portal=super'});
  assert.equal((await api.post('/fixture', {value: 1})).saved, true);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].options.headers['X-XSRF-TOKEN'], 'old-token');
  assert.equal(calls[1].options.headers['X-XSRF-TOKEN'], 'new token');
  for (const call of calls) {
    assert.equal(call.url, '/api/v1/fixture');
    assert.equal(call.options.method, 'POST');
    assert.equal(call.options.credentials, 'include');
    assert.equal(call.options.headers['X-JKXT-Portal'], 'platform');
    assert.equal(call.options.body, '{"value":1}');
  }
  assert.equal(events.length, 0);
});

test('a persistent CSRF error is rejected after exactly one retry', async () => {
  const reply = {status: 403, body: '{"code":"CSRF_TOKEN_INVALID"}'};
  const {api, calls} = sequencedClient([reply, reply], {cookie: 'XSRF-TOKEN=token'});
  await assert.rejects(api.post('/fixture', {}), error => {
    assert.equal(error.status, 403);
    assert.equal(error.code, 'CSRF_TOKEN_INVALID');
    return true;
  });
  assert.equal(calls.length, 2);
});

test('a CSRF error without a readable cookie never retries', async () => {
  const {api, calls} = sequencedClient([{status: 403, body: '{"code":"CSRF_TOKEN_INVALID"}'}]);
  await assert.rejects(api.post('/fixture', {}), error => {
    assert.equal(error.status, 403);
    assert.equal(error.code, 'CSRF_TOKEN_INVALID');
    return true;
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.headers['X-XSRF-TOKEN'], undefined);
});
