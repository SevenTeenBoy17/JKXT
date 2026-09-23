import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/super.jsx'), 'utf8');
const checks = [];
const context = vm.createContext({ window: {}, console, Blob, setTimeout });
vm.runInContext(Babel.transform(source, { presets: ['react'] }).code, context);
const evaluate = (code) => vm.runInContext(code, context);
async function check(name, run) {
  try { await run(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 400) }); }
}
const clean = (value) => JSON.parse(JSON.stringify(value));
const values = { emailNotif: true, twoFactor: false, guestAccess: false, autoBackup: true, maintenance: false };

await check('settings accept only typed supported keys', () => {
  context.sample = { ...values, unsupported: 'not writable' };
  assert.deepEqual(clean(evaluate('normalizeSuperPlatformSettings(sample)')), values);
  assert.throws(() => evaluate('normalizeSuperPlatformSettings({})'));
  assert.throws(() => evaluate('normalizeSuperPlatformSettings({...sample, twoFactor:"true"})'));
});
await check('save performs PUT and verifies persisted GET', async () => {
  const calls = [];
  context.sample = values;
  context.window.api = {
    put: async (route, body) => { calls.push(['PUT', route, clean(body)]); return values; },
    get: async (route) => { calls.push(['GET', route]); return values; },
  };
  assert.deepEqual(clean(await evaluate('saveSuperPlatformSettings(sample)')), values);
  assert.deepEqual(calls, [['PUT', '/admin/platform-settings', values], ['GET', '/admin/platform-settings']]);
});
await check('failed or mismatched readback is never success', async () => {
  context.window.api = { put: async () => values, get: async () => ({ ...values, maintenance: true }) };
  await assert.rejects(evaluate('saveSuperPlatformSettings(sample)'), /回读/);
  context.window.api = { put: async () => { throw new Error('write failed'); }, get: async () => values };
  await assert.rejects(evaluate('saveSuperPlatformSettings(sample)'), /write failed/);
  context.window.api = {};
  await assert.rejects(evaluate('saveSuperPlatformSettings(sample)'), /不可用/);
});
await check('cancel restores last verified draft; unsupported fields are readonly', () => {
  const settings = source.slice(source.indexOf('function SuperSystemSettingsPage()'), source.indexOf('// ── 5. Audit'));
  assert.match(settings, /setSwitches\(\{\s*\.\.\.savedSettings\s*\}\)/);
  assert.doesNotMatch(settings, /defaultValue|点击或拖拽上传|确认保存并广播|配置已下发/);
  assert.match(settings, /暂不支持在线上传/);
  assert.match(settings, /role="switch"/);
  assert.match(settings, /aria-checked=/);
  assert.match(settings, /savingRef\.current/);
  assert.match(settings, /disabled=\{[^}]*settingsLoading/);
});
await check('CSV exports exact selected records and escapes spreadsheet formulas', () => {
  context.rows = [{ uuid: 'id-1', id: 'L0001', actor: '=1+1', action: '含"引号",换行\n内容', tenant: '校园', at: '2026-09-05 08:00:00', payload: '  @SUM(1,2)' }];
  const out = clean(evaluate('buildSuperAuditExport(rows, "csv", {window:"近 7 天"})'));
  assert.equal(out.extension, 'csv');
  assert.equal(out.mime, 'text/csv;charset=utf-8');
  assert.ok(out.content.startsWith('\uFEFF'));
  assert.ok(out.content.includes('"\'=1+1"'));
  assert.ok(out.content.includes('"\'  @SUM(1,2)"'));
  assert.ok(out.content.includes('"含""引号"",换行\n内容"'));
  assert.ok(out.content.includes('id-1'));
});
await check('JSON preserves real payload, includes scope, rejects unsupported format', () => {
  const out = clean(evaluate('buildSuperAuditExport(rows, "json", {tenant:"校园"})'));
  const json = JSON.parse(out.content);
  assert.deepEqual(json.records, clean(context.rows));
  assert.equal(json.filters.tenant, '校园');
  assert.equal(json.recordCount, 1);
  assert.equal(json.sourceLimit, 200);
  assert.throws(() => evaluate('buildSuperAuditExport(rows, "pdf", {})'), /格式/);
});
await check('download produces real Blob link and cleanup', () => {
  let clicked = false, removed = false, revoked = false, blob;
  context.document = { body: { appendChild() {} }, createElement: () => ({ click() { clicked = true; }, remove() { removed = true; } }) };
  context.URL = { createObjectURL(value) { blob = value; return 'blob:test'; }, revokeObjectURL(value) { assert.equal(value, 'blob:test'); revoked = true; } };
  context.setTimeout = (callback) => callback();
  evaluate('downloadSuperAuditExport(rows, "json", {})');
  assert.equal(clicked, true); assert.equal(removed, true); assert.equal(revoked, true);
  assert.ok(blob instanceof Blob); assert.ok(blob.size > 0);
});
await check('audit uses current filter snapshot and no fabricated delivery or userAgent', () => {
  const audit = source.slice(source.indexOf('function SuperAuditPage()'), source.indexOf('// ── 6. Security'));
  assert.match(audit, /filteredAuditRows\.map/);
  assert.match(audit, /downloadSuperAuditExport/);
  assert.match(audit, /200/);
  assert.doesNotMatch(audit, /邮件发送下载链接|Mozilla\/5\.0 \(Macintosh\)|均已处理|跨 5 个租户/);
});
await check('typography patch is scoped to authenticated app and fixed scale', () => {
  const css = fs.readFileSync(path.join(root, 'src/styles/usability-20260905.css'), 'utf8');
  assert.match(css, /\.app-shell/);
  assert.match(css, /--type-page-size:\s*1\.5rem/);
  assert.match(css, /focus-visible/);
  assert.match(css, /var\(--surface-muted\)/);
  assert.match(css, /overflow-wrap/);
  assert.doesNotMatch(css, /\.login|\.brand-lockup|user-scalable/);
});

// Execute actual JSX handlers using a minimal hook renderer, not source matching alone.
function hookRenderer(expression) {
  const slots = [];
  let cursor = 0;
  let effects = [];
  const same = (a, b) => Boolean(a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i])));
  context.React = {
    Fragment: 'Fragment',
    createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }),
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = typeof initial === 'function' ? initial() : initial;
      return [slots[index], (next) => { slots[index] = typeof next === 'function' ? next(slots[index]) : next; }];
    },
    useRef(initial) { const index = cursor++; if (!(index in slots)) slots[index] = { current: initial }; return slots[index]; },
    useCallback(fn, deps) { const index = cursor++; if (!same(slots[index]?.deps, deps)) slots[index] = { deps, fn }; return slots[index].fn; },
    useEffect(fn, deps) {
      const index = cursor++;
      if (!same(slots[index]?.deps, deps)) effects.push(() => {
        slots[index]?.cleanup?.();
        slots[index] = { deps, cleanup: fn() };
      });
    },
  };
  for (const name of ['PageShell', 'Button', 'SectionTitle', 'FeatureKPI', 'FilterBar', 'DataTable', ...new Set(source.match(/\bIcon[A-Z]\w*/g))]) context[name] = name;
  return () => {
    cursor = 0;
    const tree = evaluate(expression);
    const pending = effects; effects = [];
    pending.forEach((effect) => effect());
    return tree;
  };
}
const settle = () => new Promise((resolve) => setImmediate(resolve));
function nodes(tree) {
  if (!tree || typeof tree !== 'object') return [];
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return [tree, ...nodes(tree.props?.children), ...nodes(tree.props?.actions)];
}

await check('actual settings controls save, cancel and block duplicate submission', async () => {
  let persisted = { ...values }, puts = 0;
  context.window.api = {
    get: async () => ({ ...persisted }),
    put: async (_, payload) => { puts += 1; persisted = clean(payload); return { ...persisted }; },
  };
  context.window.ui = { confirm: async () => true, toast() {} };
  context.window.location = { origin: 'https://school.example.test' };
  const render = hookRenderer('SuperSystemSettingsPage()');
  assert.equal(nodes(render()).find((node) => node.props?.role === 'switch').props.disabled, true);
  await settle();
  let tree = render();
  const controls = () => nodes(tree).filter((node) => node.props?.role === 'switch');
  assert.equal(controls()[0].props['aria-checked'], true);
  controls()[0].props.onClick(); tree = render();
  assert.equal(controls()[0].props['aria-checked'], false);
  nodes(tree).find((node) => node.type === 'Button' && node.props.children.includes('撤销修改')).props.onClick();
  tree = render(); assert.equal(controls()[0].props['aria-checked'], true);
  controls()[0].props.onClick(); tree = render();
  const save = nodes(tree).find((node) => node.type === 'Button' && node.props.children.includes('保存配置值'));
  await Promise.all([save.props.onClick(), save.props.onClick()]);
  tree = render();
  assert.equal(puts, 1);
  assert.equal(persisted.emailNotif, false);
  assert.equal(nodes(tree).find((node) => node.type === 'Button' && node.props.children.includes('保存配置值')).props.disabled, true);
});

await check('resource requests reject invalid shapes and ignore stale responses', async () => {
  const resolvers = [];
  context.window.api = { get: () => new Promise((resolve) => resolvers.push(resolve)) };
  context.testPath = '/admin/audit-logs?hours=24';
  const render = hookRenderer('useSuperResource(testPath, SUPER_AUDIT)');
  render();
  context.testPath = '/admin/audit-logs?hours=168';
  render();
  resolvers[1]([{ id: 'current' }]); await settle();
  assert.equal(render()[0][0].id, 'current');
  resolvers[0]([{ id: 'stale' }]); await settle();
  assert.equal(render()[0][0].id, 'current');
  context.testPath = '/admin/audit-logs?hours=720';
  render(); resolvers[2]({ unexpected: 'not a list' }); await settle();
  const state = render();
  assert.equal(state[0].length, 0); assert.equal(state[4], context.testPath); assert.equal(state[2], false);
});

await check('tenant detail uses real fields and transfers its audit filter', async () => {
  let navigated = '', drawer;
  context.window.ui = { closeDrawer() {}, drawer: (next) => { drawer = next; } };
  context.window.navigateTo = (route) => { navigated = route; };
  context.tenant = { id: 'demo', slug: 'demo', name: '合成学校', city: '测试', type: 'school', plan: 'standard', admins: 3, adminCap: 5, students: 20, status: 'active', createdAt: '2026-09-05', contactName: '测试联系人' };
  context.TenantBadge = 'TenantBadge'; context.Tag = 'Tag';
  evaluate('openTenantDrawer(tenant)');
  const detail = JSON.stringify(drawer.content);
  assert.ok(detail.includes('测试联系人'));
  assert.ok(detail.includes('接口未提供'));
  assert.ok(!detail.includes('王明远') && !detail.includes('刚刚 · 在线') && !detail.includes('admin@'));
  evaluate('openTenantEditDrawer(tenant)');
  assert.ok(nodes(drawer.content).filter((node) => node.type === 'input').every((node) => node.props.readOnly));
  assert.ok(!JSON.stringify(drawer.footer).includes('保存修改'));
  evaluate('openTenantAudit(tenant)');
  assert.equal(navigated, 'super-audit');
  context.window.api = { get: async () => [] };
  const render = hookRenderer('SuperAuditPage()');
  const tree = render();
  const filter = nodes(tree).find((node) => node.type === 'FilterBar');
  assert.equal(filter.props.filters[2].value, 'DEMO');
  assert.equal(context.window.__jkxtSuperAuditTenant, undefined);
  await settle();
});

await check('announcements do not invent content, recipients, read rates or delivery', () => {
  const announcements = source.slice(source.indexOf('function openAnnouncementDrawer'));
  assert.doesNotMatch(announcements, /ANNOUNCEMENT_BODIES|tenantReads|publishedCount\s*\*\s*180|\/\s*1\.62/);
  assert.doesNotMatch(announcements, /actions\.push|saveAndPush|邮件 \+ 站内信|已重新推送/);
  assert.match(announcements, /通知通道未接入/);
  assert.match(announcements, /if \(!updated\) return null/);
});

await check('failed announcement save preserves editor and never publishes', async () => {
  let drawer, closeCount = 0, posts = 0;
  const announcement = { id: 'AN001', uuid: '00000000-0000-4000-8000-000000000001', title: '原始标题', body: '真实正文', severity: 'info', target: 'all', status: 'draft' };
  context.window.api = { get: async () => [announcement], put: async () => { throw Error('synthetic-save-failure'); }, post: async () => { posts += 1; return announcement; } };
  context.window.ui = { drawer: (next) => { drawer = next; }, closeDrawer() { closeCount += 1; }, toast() {} };
  context.ProductGlyph = 'ProductGlyph'; context.Tag = 'Tag';
  const render = hookRenderer('SuperAnnouncementsPage()');
  render(); await settle();
  const tree = render();
  nodes(tree).find((node) => node.type === 'div' && node.props.key === 'AN001').props.onClick();
  nodes(drawer.footer).find((node) => node.type === 'Button' && node.props.children.includes('编辑')).props.onClick();
  closeCount = 0;
  context.document = { querySelector: () => ({ querySelector: (selector) => ({ value: selector.includes('title') ? '修改标题' : selector.includes('body') ? '修改内容' : '' }) }) };
  const save = nodes(drawer.footer).find((node) => node.type === 'Button' && node.props.children.includes('保存并发布'));
  await save.props.onClick();
  assert.equal(posts, 0); assert.equal(closeCount, 0);
  context.document = { querySelector: () => ({ querySelector: () => ({ value: '' }) }) };
  const empty = clean(evaluate('announcementFormPayload("form", {title:"旧标题", body:"旧正文"})'));
  assert.equal(empty.title, ''); assert.equal(empty.body, '');
});

await check('backup unavailable and malformed responses never claim a completed backup', async () => {
  const notices = []; let reloads = 0;
  context.window.ui = { toast: (message, options) => notices.push({ message, ...options }) };
  context.reloadBackup = async () => { reloads += 1; };
  context.window.api = { post: async () => ({ status: 'unavailable', message: 'No pg_dump; run host backup first' }) };
  await evaluate('scanSuperBackupArtifacts(reloadBackup)');
  assert.equal(notices[0].kind, 'info');
  assert.equal(reloads, 1);
  assert.ok(!notices.some((n) => n.kind === 'success'));
  notices.length = 0;
  context.window.api.post = async () => ({ status: 'succeeded' });
  await evaluate('scanSuperBackupArtifacts(reloadBackup)');
  assert.equal(notices[0].kind, 'error');
  notices.length = 0;
  context.window.api.post = async () => ({ status: 'succeeded', id: 'verified-existing-file.sql' });
  await evaluate('scanSuperBackupArtifacts(reloadBackup)');
  assert.equal(notices[0].kind, 'success');
  assert.equal(notices[0].message, '备份文件已登记');
  assert.ok(notices[0].desc.includes('仅扫描现有文件'));
});

await check('security exposes real metrics without synthetic scores, interceptions or execution claims', () => {
  const security = source.slice(source.indexOf('function SuperSecurityPage()'), source.indexOf('// ── 7. Announcements'));
  assert.doesNotMatch(security, /RadialGauge|security\.score|value="32"|4\.2 GB|target.*admin@|root@jkxt|已拦截|等级:良好/);
  assert.match(security, /尚未接入网关执行/);
  assert.match(security, /不会创建新备份或上传云端/);
  assert.match(security, /scanningRef\.current/);
  assert.match(security, /security\.activeRules/);
  assert.match(security, /rows=\{backups\}/);
});

await check('dashboard uses actual range queries and never invents safety or activity', () => {
  const dashboard = source.slice(source.indexOf('function SuperHero('), source.indexOf('// ── 2.'));
  assert.doesNotMatch(dashboard, /security\.score|value="(?:86|71|3 \/ 7)"|286|124\.65|i < 3 \? '今天'/);
  assert.match(dashboard, /\/admin\/audit-logs\?hours=/);
  assert.match(dashboard, /auditLoadedPath !== auditPath/);
  assert.match(dashboard, /useSuperObject\('\/admin\/system-health', SUPER_HEALTH\)/);
  assert.match(dashboard, /m\.value == null/);
});

await check('account records cannot masquerade as editable or two-factor verified', () => {
  const accounts = source.slice(source.indexOf('function SuperAccountsPage()'), source.indexOf('const SUPER_PLATFORM_SETTING_KEYS'));
  assert.doesNotMatch(accounts, /acc\.twoFactor|a\.twoFactor|r\.twoFactor|\.recover@|136-\*\*\*\*|defaultValue=\{f\.v\}|s\.on|可在 30 天内恢复|保留 30 天/);
  assert.match(accounts, /value=\{f\.v\} readOnly/);
  assert.match(accounts, /不会|尚无可用接口/);
  assert.match(accounts, /软删除账号/);
  assert.match(accounts, /按上次登录时间统计，不代表当前在线会话/);
});

await check('object loaders reject arrays and ignore obsolete in-flight responses', async () => {
  let resolveOld;
  context.objectPath = '/old-object';
  context.window.api = { get: async (route) => route === '/old-object' ? await new Promise((resolve) => { resolveOld = resolve; }) : { route } };
  const render = hookRenderer('useSuperObject(objectPath, SUPER_HEALTH)');
  render();
  context.objectPath = '/new-object'; render(); await settle();
  assert.equal(render()[0].route, '/new-object');
  resolveOld({ route: '/old-object' }); await settle();
  assert.equal(render()[0].route, '/new-object');
  context.window.api.get = async () => [];
  await render()[1]();
  assert.equal(render()[3], '/new-object');
});

await check('account creation never silently defaults tenant after failed tenant loading', async () => {
  let posts = 0;
  context.window.api = { get: async () => { throw new Error('tenant-load-failed'); }, post: async () => { posts += 1; return {}; } };
  const render = hookRenderer('SuperAccountFormModal({})');
  render(); await settle();
  let tree = render();
  assert.ok(nodes(tree).some((node) => node.props.role === 'alert'));
  const field = (label) => nodes(tree).find((node) => node.props['aria-label'] === label);
  field('姓名').props.onChange({ target: { value: '测试管理员' } });
  field('邮箱').props.onChange({ target: { value: 'admin@example.test' } });
  tree = render();
  const create = nodes(tree).find((node) => node.type === 'Button' && node.props.children.includes('创建账号'));
  assert.equal(create.props.disabled, true);
  await create.props.onClick();
  assert.equal(posts, 0);
  assert.ok(!JSON.stringify(tree).includes('默认当前租户'));
});

console.log(JSON.stringify({ contract: 'SettingsActions/v1', checks, passed: checks.filter((c) => c.pass).length, total: checks.length }, null, 2));
if (checks.some((c) => !c.pass)) process.exitCode = 1;
