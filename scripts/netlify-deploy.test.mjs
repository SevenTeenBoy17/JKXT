import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { prepareNetlify, validateApiOrigin } from './deploy/prepare-netlify.mjs';

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const origin = 'https://jkxt-api-8x2q.onrender.com';
const sha = value => crypto.createHash('sha256').update(value).digest('hex');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jkxt-netlify-test-'));
  // Only this exact mkdtemp-owned directory is removed by test cleanup.
  t.after(() => {
    const resolved = fs.realpathSync(root);
    assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('jkxt-netlify-test-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  });
  const dist = path.join(root, 'dist');
  fs.mkdirSync(path.join(dist, 'compiled'), { recursive: true });
  const script = 'window.fixtureLoaded = true;';
  const output = `compiled/api.${sha(script).slice(0, 16)}.js`;
  const html = `<!doctype html><html><head><style>body{margin:0}</style></head><body><script src="${output}"></script></body></html>`;
  fs.writeFileSync(path.join(dist, output), script);
  fs.writeFileSync(path.join(dist, 'Dashboard.html'), html);
  fs.writeFileSync(path.join(dist, 'build-manifest.json'), JSON.stringify({
    schema: 'jkxt-frontend-build/v1', htmlSha256: sha(html),
    entries: [{ input: 'src/api.js', output, sha256: sha(script) }],
  }));
  fs.writeFileSync(path.join(root, '.env'), 'DATABASE_PASSWORD=OUTSIDE_SECRET_SENTINEL');
  return { root, dist, html, output };
}

test('accepts only an explicit canonical Render HTTPS origin', () => {
  assert.equal(validateApiOrigin(origin), origin);
  for (const invalid of [undefined, '', ' ', 'https://example.onrender.com',
    'https://your-service.onrender.com', 'https://replace-me.onrender.com',
    'http://jkxt-api.onrender.com', 'https://localhost', 'https://127.0.0.1',
    'https://onrender.com', 'https://jkxt-api.onrender.com.evil.invalid',
    'https://child.jkxt-api.onrender.com', 'https://jkxt-api.onrender.com/',
    'https://jkxt-api.onrender.com/path', 'https://jkxt-api.onrender.com?token=secret',
    'https://jkxt-api.onrender.com#fragment', 'https://u:p@jkxt-api.onrender.com',
    'https://jkxt-api.onrender.com:443', 'https://JKXT-API.onrender.com',
    `${origin}\r\n/api/* https://evil.invalid 200`, `${origin} `, `${origin}\n`, `${origin}\r\n`, `${origin}\t`,
    'https://-jkxt.onrender.com', 'https://jkxt-.onrender.com']) {
    assert.throws(() => validateApiOrigin(invalid), /JKXT_API_ORIGIN/);
  }
});

test('writes only two deployment files inside the existing dist and preserves build bytes', t => {
  const f = fixture(t);
  const before = fs.readdirSync(f.root).sort();
  const result = prepareNetlify({ appRoot: f.root, apiOrigin: origin });
  assert.deepEqual(result.files, ['_redirects', '_headers']);
  assert.deepEqual(fs.readdirSync(f.root).sort(), before);
  assert.equal(fs.readFileSync(path.join(f.dist, 'Dashboard.html'), 'utf8'), f.html);
  const published = fs.readdirSync(f.dist).filter(name => fs.statSync(path.join(f.dist, name)).isFile())
    .map(name => fs.readFileSync(path.join(f.dist, name), 'utf8')).join('\n');
  assert.ok(!published.includes('OUTSIDE_SECRET_SENTINEL'));
  assert.equal(fs.readFileSync(path.join(f.root, '.env'), 'utf8'), 'DATABASE_PASSWORD=OUTSIDE_SECRET_SENTINEL');
  prepareNetlify({ appRoot: f.root, apiOrigin: origin });
  assert.deepEqual(fs.readdirSync(f.root).sort(), before);
});

test('every API path has a forced same-path proxy and there is no HTML catch-all', t => {
  const f = fixture(t);
  prepareNetlify({ appRoot: f.root, apiOrigin: origin });
  const rules = fs.readFileSync(path.join(f.dist, '_redirects'), 'utf8').split('\n')
    .filter(line => line && !line.startsWith('#')).map(line => line.trim().split(/\s+/));
  assert.deepEqual(rules, [
    ['/api/v1', `${origin}/api/v1`, '200!'],
    ['/api/v1/*', `${origin}/api/v1/:splat`, '200!'],
    ['/', '/Dashboard.html', '200'],
  ]);
  for (const pathname of ['/api/v1', '/api/v1/', '/api/v1/health', '/api/v1/does-not-exist']) {
    const match = rules.find(([from]) => from.endsWith('*') ? pathname.startsWith(from.slice(0, -1)) : pathname === from);
    assert.ok(match, pathname);
    assert.ok(match[1].startsWith(origin + '/api/v1'));
    assert.equal(match[2], '200!');
  }
  for (const pathname of ['/api', '/api/v2/unknown', '/assets/missing.js', '/school/login']) {
    assert.ok(!rules.some(([from]) => from.endsWith('*') ? pathname.startsWith(from.slice(0, -1)) : pathname === from));
  }
});

test('real portal resolver keeps the original query-based school and platform entry', () => {
  const source = fs.readFileSync(path.join(app, 'src/api.js'), 'utf8');
  for (const [pathname, search, expected] of [
    ['/', '', 'school'], ['/Dashboard.html', '', 'school'],
    ['/', '?portal=super', 'platform'], ['/Dashboard.html', '?portal=super', 'platform'],
    ['/Dashboard.html', '?login=SuPeR', 'platform'], ['/Dashboard.html', '?entry=super', 'platform'],
    ['/Dashboard.html', '?portal=admin&entry=super', 'school'],
  ]) {
    const window = { location: { pathname, search }, localStorage: { getItem: () => null }, sessionStorage: { setItem() {} } };
    vm.runInNewContext(source, { window, localStorage: window.localStorage, sessionStorage: window.sessionStorage,
      URLSearchParams, URL, console });
    assert.equal(window.api.base, '/api/v1');
    assert.equal(window.api.portal(), expected);
  }
});

test('static headers keep legacy inline code usable and cache only hashed compiled files immutably', t => {
  const f = fixture(t);
  prepareNetlify({ appRoot: f.root, apiOrigin: origin });
  const headers = fs.readFileSync(path.join(f.dist, '_headers'), 'utf8');
  assert.match(headers, /X-Content-Type-Options: nosniff/);
  assert.match(headers, /frame-ancestors 'none'/);
  assert.doesNotMatch(headers, /(?:default|script|style)-src|Set-Cookie|Access-Control-Allow-Origin/);
  assert.match(headers, /\/compiled\/\*\n  Cache-Control: public, max-age=31536000, immutable/);
  assert.match(headers, /\/assets\/\*\n  Cache-Control: public, max-age=300, must-revalidate/);
  assert.match(headers, /\/Dashboard\.html\n  Cache-Control: no-store/);
  assert.doesNotMatch(headers, /\/api\//);
});

test('missing origin fails before touching a valid build', t => {
  const f = fixture(t);
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: '' }), /JKXT_API_ORIGIN/);
  assert.equal(fs.existsSync(path.join(f.dist, '_redirects')), false);
  assert.equal(fs.existsSync(path.join(f.dist, '_headers')), false);
});

test('missing dist or build files fail closed without creating output', t => {
  const f = fixture(t);
  fs.renameSync(f.dist, path.join(f.root, 'saved-dist'));
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /dist/);
  assert.equal(fs.existsSync(f.dist), false);
  fs.renameSync(path.join(f.root, 'saved-dist'), f.dist);
  fs.unlinkSync(path.join(f.dist, 'Dashboard.html'));
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /Dashboard/);
  assert.equal(fs.existsSync(path.join(f.dist, '_redirects')), false);
});

test('tampered or traversal build-manifest entries fail before writing', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.dist, f.output), 'tampered');
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /hash|checksum/i);
  const manifest = JSON.parse(fs.readFileSync(path.join(f.dist, 'build-manifest.json')));
  manifest.entries[0].output = '../.env';
  fs.writeFileSync(path.join(f.dist, 'build-manifest.json'), JSON.stringify(manifest));
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /compiled|path/i);
  assert.equal(fs.existsSync(path.join(f.dist, '_redirects')), false);
});

test('unexpected publish files and secret-bearing filenames fail before writing', t => {
  const f = fixture(t);
  fs.writeFileSync(path.join(f.dist, '.env'), 'SECRET_SENTINEL');
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /publish|unexpected/i);
  fs.unlinkSync(path.join(f.dist, '.env'));
  fs.mkdirSync(path.join(f.dist, 'assets'));
  fs.writeFileSync(path.join(f.dist, 'assets', 'private-key.pem'), 'SECRET_SENTINEL');
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /publish|secret/i);
  assert.equal(fs.existsSync(path.join(f.dist, '_redirects')), false);
});

test('dist directory junction cannot redirect writes outside the app', t => {
  const f = fixture(t);
  const outside = path.join(f.root, 'outside');
  fs.renameSync(f.dist, outside);
  fs.symlinkSync(outside, f.dist, process.platform === 'win32' ? 'junction' : 'dir');
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /symlink|real directory/i);
  assert.equal(fs.existsSync(path.join(outside, '_redirects')), false);
});

test('preexisting output directory or hardlink is refused before either output changes', t => {
  const f = fixture(t);
  const outside = path.join(f.root, 'outside-sentinel');
  fs.writeFileSync(outside, 'PRESERVE');
  fs.linkSync(outside, path.join(f.dist, '_redirects'));
  assert.throws(() => prepareNetlify({ appRoot: f.root, apiOrigin: origin }), /hardlink|link|regular/i);
  assert.equal(fs.readFileSync(outside, 'utf8'), 'PRESERVE');
  assert.equal(fs.existsSync(path.join(f.dist, '_headers')), false);
});

test('CLI rejects arbitrary output arguments instead of accepting a destructive target', () => {
  const result = spawnSync(process.execPath, [path.join(app, 'scripts/deploy/prepare-netlify.mjs'), '--dist', '..'],
    { encoding: 'utf8', env: { ...process.env, JKXT_API_ORIGIN: origin } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /arguments|fixed.*dist/i);
});

test('Netlify config publishes only dist with the existing frontend build', () => {
  const config = fs.readFileSync(path.join(app, 'netlify.toml'), 'utf8');
  assert.match(config, /publish\s*=\s*"dist"/);
  assert.match(config, /command\s*=\s*"npm run build && node scripts\/deploy\/prepare-netlify\.mjs"/);
  assert.doesNotMatch(config, /\[\[redirects\]\]|\[\[plugins\]\]|SECRETS_SCAN.*false|DATABASE_URL|REDIS_URL/);
});
