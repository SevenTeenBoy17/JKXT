'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const apiSource = fs.readFileSync(
  path.resolve(__dirname, '../../src/api.js'),
  'utf8',
);

function loadApi({ hostname = '127.0.0.1', port = '', storedBase = null, windowBase } = {}) {
  const protocol = port === '443' ? 'https:' : 'http:';
  const host = port ? `${hostname}:${port}` : hostname;
  const location = {
    hostname,
    port,
    protocol,
    host,
    origin: `${protocol}//${host}`,
  };
  const window = {};
  if (windowBase !== undefined) window.JKXT_API_BASE = windowBase;

  const context = vm.createContext({
    ArrayBuffer,
    Blob,
    FormData,
    URL,
    URLSearchParams,
    fetch: async () => {
      throw new Error('fetch must not run while resolving the API base');
    },
    localStorage: {
      getItem(key) {
        assert.equal(key, 'jkxt:apiBase');
        return storedBase;
      },
    },
    location,
    window,
  });

  vm.runInContext(apiSource, context, { filename: 'src/api.js' });
  return window.api;
}

for (const port of ['19180', '8000', '4173']) {
  test(`local reverse proxy on arbitrary port ${port} defaults to same-origin /api/v1`, () => {
    const api = loadApi({ port });

    assert.equal(api.base, '/api/v1');
    assert.equal(api.url('/health'), '/api/v1/health');
    assert.doesNotMatch(api.base, /(?:127\.0\.0\.1|localhost):8080/);
  });
}

test('window.JKXT_API_BASE explicitly overrides the same-origin default and normalizes slashes', () => {
  const api = loadApi({
    port: '19180',
    windowBase: 'https://gateway.example.test/custom/v1///',
  });

  assert.equal(api.base, 'https://gateway.example.test/custom/v1');
  assert.equal(api.url('health'), 'https://gateway.example.test/custom/v1/health');
});

test('localStorage explicitly overrides both the default and window base and normalizes slashes', () => {
  const api = loadApi({
    port: '19180',
    storedBase: 'https://stored.example.test/api/v1////',
    windowBase: 'https://window.example.test/api/v1///',
  });

  assert.equal(api.base, 'https://stored.example.test/api/v1');
  assert.equal(api.url('/health'), 'https://stored.example.test/api/v1/health');
});

test('backend port 8080 also uses a relative same-origin API base', () => {
  const api = loadApi({ port: '8080' });

  assert.equal(api.base, '/api/v1');
  assert.equal(api.url('/health'), '/api/v1/health');
  assert.doesNotMatch(api.base, /(?:127\.0\.0\.1|localhost):8080/);
});
