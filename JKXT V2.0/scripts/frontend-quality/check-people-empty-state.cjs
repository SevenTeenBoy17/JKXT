/* Registered regression: current real App/PeoplePage/TeacherFormModal, synthetic GET only.
 * Does not connect to a service, reuse a user session, submit a form, or alter the 119-site runner.
 * Dependencies may be supplied per child process; no installation/download is performed. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const playwrightModule = process.env.JKXT_PLAYWRIGHT_MODULE || 'playwright';
const { chromium } = require(playwrightModule);
const chromiumExecutable = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined;
const root = path.resolve(__dirname, '../..');
const startedAt = new Date().toISOString();
const stamp = startedAt.replace(/[:.]/g, '-');
const out = path.join(root, 'artifacts/site-acceptance-20260907/qa-empty-roster-artifacts', stamp);
fs.mkdirSync(out, { recursive: true });
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const sources = new Map();
const read = file => {
  if (!sources.has(file)) sources.set(file, fs.readFileSync(path.join(root, file)));
  return sources.get(file);
};
const testHash = hash(fs.readFileSync(__filename));
const specPath = 'artifacts/site-acceptance-20260907/empty-roster-spec-20260908.md';
const specification = { path: specPath, sha256: hash(read(specPath)) };
const registered = {
  schema: 'PeopleEmptyRosterChecks/v1', registeredAt: startedAt,
  goal: 'goal-1c7c93e5b99d9351', version: 3, specification, testHash,
  assertions: [
    'Successful empty roster with no filters explains that no teachers have been added; existing TeacherFormModal opens and cancels without write requests.',
    'The nonfunctional More filters entry is absent and all four actual filters remain available.',
    'Empty roster with a keyword still explains no match; clearing returns to initial guidance.',
    'Each grade/subject/homeroom/status selection changes the real table rows and resets to all rows.',
    'Combined filters plus unmatched keyword show no-match; clear resets both keyword and all filters and restores the real list.',
    'An intentionally pending teacher GET shows loading, never initial-empty or filtered-empty.',
    'HTTP 503 shows failure rather than empty; retry successfully restores a populated list.',
    '1440x900 and 390x700 have no document overflow; opened real forms can be cancelled and reopen cleanly.',
    'Every external and non-GET request is blocked; no backend mutation is attempted; no uncaught runtime error or missing local asset.',
    'All source bytes used for rendering remain unchanged during this run.',
    'An initial empty roster does not render a teacher schedule section that asks the user to select from a nonexistent table.',
    'A populated roster retains its task-scoped teacher schedule section, including when filters currently match no teachers.'
  ],
  testData: 'Synthetic teachers only; separate browser context; no localhost service or user credentials.'
};
fs.writeFileSync(path.join(out, 'registered-checks.json'), JSON.stringify(registered, null, 2));
const htmlSource = read('Dashboard.html').toString();
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
vm.runInNewContext(read('src/babel-script-scope.js').toString(), { window: { Babel }, document: { querySelectorAll: () => [] } });
const compiled = new Map();
for (const item of htmlSource.matchAll(/<script\s+type="text\/babel"\s+src="([^"?]+)[^"]*"[^>]*><\/script>/g)) {
  compiled.set('/' + item[1], Babel.transform(read(item[1]).toString(), { presets: ['react', 'env'], plugins: ['jkxt-script-scope'] }).code);
}
let html = htmlSource.replace(/<script\s+type="text\/babel"/g, '<script').replace(/<script src="src\/babel-script-scope[^>]+><\/script>/, '');
html = html.replace('<div id="root"></div>', '<div id="root"></div><script>localStorage.setItem("jkxt:auth","1");localStorage.setItem("jkxt:mode","admin");localStorage.setItem("jkxt:page","people");localStorage.setItem("jkxt:onboarded","1");window.__PEOPLE_EMPTY_ROSTER_FIXTURE=true;</script>');
const origin = 'http://jkxt.people-empty.test';
const teacherRows = [
  { id: '10000000-0000-4000-8000-000000000001', teacherNo: 'QA-EMPTY-001', name: '合成教师甲', gender: 'female', grade: '高一', subjects: ['语文'], isHomeroom: true, homeroomOf: '高一一班', status: 'active', supCount: 0 },
  { id: '10000000-0000-4000-8000-000000000002', teacherNo: 'QA-EMPTY-002', name: '合成教师乙', gender: 'male', grade: '高二', subjects: ['数学'], isHomeroom: false, status: 'leave', supCount: 2 },
  { id: '10000000-0000-4000-8000-000000000003', teacherNo: 'QA-EMPTY-003', name: '合成教师丙', gender: 'female', grade: '高一', subjects: ['数学'], isHomeroom: false, status: 'active', supCount: 1 }
];
const fixtureProfile = { onboarded: true, user: { id: 'synthetic-user', role: 'TENANT_ADMIN', displayName: '空名册验收（合成）' }, org: { id: 'synthetic-school', name: '隔离前端验收学校（合成）' }, term: { id: 'synthetic-term', name: '2026-2027 第一学期（合成）', yearLabel: '2026-2027', term: 'S1' } };
const getFixtures = {
  '/auth/me': fixtureProfile, '/auth/me/preferences': { version: 1, theme: { palette: 'indigo', mode: 'light', density: 'comfortable' } },
  '/org/settings/appearance': { version: 1, defaultPalette: 'indigo' }, '/org/settings': { allowanceUnit: null },
  '/terms': [], '/terms/current': null, '/notifications': [], '/exam-tasks': [], '/exam-plans': [],
  '/classes': [], '/subjects': [], '/classrooms': [],
  '/operations/readiness': { totals: {}, issues: [], ready: true, blockingCount: 0 }
};
const results = [], requests = [], externalRequests = [], writeAttempts = [], unknownGets = [], pageErrors = [], consoleErrors = [], missingAssets = [], injectedFailures = [], screenshots = [];
let browser, context, page, scenario = 'empty', currentCheck = '', releasePending = null;
const environment = { node: process.version, platform: process.platform, playwrightModule: require.resolve(playwrightModule), chromiumExecutable: chromiumExecutable || chromium.executablePath(), browserVersion: null };
async function shot(name) {
  const target = path.join(out, name.replace(/[^a-zA-Z0-9_-]/g, '-') + '.png');
  await page.screenshot({ path: target, fullPage: false });
  screenshots.push({ path: target, sha256: hash(fs.readFileSync(target)), manuallyReviewed: false });
  return target;
}
async function check(name, fn) {
  currentCheck = name;
  try { const details = await fn(); results.push({ name, pass: true, details }); console.log('PASS ' + name); }
  catch (error) {
    const result = { name, pass: false, error: String(error.stack || error).slice(0, 4000) };
    try { result.screenshot = await shot('failure-' + name); } catch {}
    results.push(result); console.log('FAIL ' + name + ': ' + error.message);
  } finally { if (releasePending) { releasePending(); releasePending = null; } }
}
async function openScenario(next, size) {
  scenario = next; await page.setViewportSize(size);
  await page.emulateMedia({ reducedMotion: size.width === 390 ? 'reduce' : 'no-preference' });
  await page.goto(origin + '/Dashboard.html?scenario=' + next + '&nonce=' + Date.now(), { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.app-shell[data-page-id="people"]', { timeout: 15000 });
  await page.getByRole('heading', { name: '监考人员管理', exact: true }).waitFor();
  await page.evaluate(() => document.fonts.ready);
  if (next !== 'loading') await page.getByText('正在加载教师数据', { exact: true }).waitFor({ state: 'hidden' });
}
function emptyCard() { return page.locator('#main-content .empty-state'); }
function rows() { return page.locator('#main-content .data-table tbody tr'); }
async function rowNames() {
  return rows().evaluateAll(nodes => nodes.map(n => n.textContent.match(/合成教师[甲乙丙]/)?.[0]).filter(Boolean));
}
async function expectRows(names) {
  await page.waitForFunction(expected => {
    const names = [...document.querySelectorAll('#main-content .data-table tbody tr')].map(n => n.textContent.match(/合成教师[甲乙丙]/)?.[0]).filter(Boolean);
    return JSON.stringify(names) === JSON.stringify(expected);
  }, names);
  assert.deepEqual(await rowNames(), names);
}
async function pickFilter(label, value) {
  const pill = page.locator('#main-content .filter-pill').filter({ has: page.getByText(label, { exact: true }) });
  assert.equal(await pill.count(), 1);
  await pill.getByRole('button').click();
  await pill.getByRole('option', { name: value, exact: true }).click();
}
async function resetFilters() { await page.locator('#main-content .filter-bar').getByRole('button', { name: '清空筛选', exact: true }).click(); }
async function expectDefaultFilters() {
  for (const label of ['年级:', '科目:', '班主任:', '状态:']) {
    const pill = page.locator('#main-content .filter-pill').filter({ has: page.getByText(label, { exact: true }) });
    assert.match(await pill.getByRole('button').innerText(), /全部/);
  }
}
async function geometry() {
  const value = await page.evaluate(() => ({ width: innerWidth, docWidth: document.documentElement.scrollWidth }));
  assert.ok(value.docWidth <= value.width + 1, JSON.stringify(value)); return value;
}
(async () => {
  try {
    browser = await chromium.launch({ headless: true, ...(chromiumExecutable ? { executablePath: chromiumExecutable } : {}) });
    environment.browserVersion = browser.version();
    context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      requests.push({ method: request.method(), path: url.pathname, scenario, check: currentCheck });
      if (url.origin !== origin) { externalRequests.push(url.href); await route.abort('blockedbyclient'); return; }
      if (request.method() !== 'GET') {
        writeAttempts.push({ method: request.method(), path: url.pathname, check: currentCheck });
        await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ message: '隔离前端测试禁止写入', code: 'FIXTURE_WRITE_BLOCKED' }) }); return;
      }
      if (url.pathname.startsWith('/api/')) {
        const key = url.pathname.replace(/^\/api\/v1/, '');
        if (key === '/teachers') {
          const teacherState = scenario;
          if (teacherState === 'loading') await new Promise(resolve => { releasePending = resolve; });
          if (teacherState === 'error') {
            injectedFailures.push({ check: currentCheck, path: url.pathname, status: 503 });
            await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '合成教师接口暂不可用，请重试', code: 'FIXTURE_UNAVAILABLE' }) }); return;
          }
          await route.fulfill({ contentType: 'application/json', body: JSON.stringify(teacherState === 'populated' ? teacherRows : []) }); return;
        }
        if (!Object.hasOwn(getFixtures, key)) { unknownGets.push({ key, check: currentCheck }); await route.fulfill({ status: 404, body: '{"message":"Unregistered fixture GET"}' }); return; }
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(getFixtures[key]) }); return;
      }
      if (url.pathname === '/Dashboard.html' || url.pathname === '/') { await route.fulfill({ contentType: 'text/html', body: html }); return; }
      if (compiled.has(url.pathname)) { await route.fulfill({ contentType: 'application/javascript', body: compiled.get(url.pathname) }); return; }
      const relative = decodeURIComponent(url.pathname).replace(/^\//, ''), target = path.resolve(root, relative);
      if (!target.startsWith(root + path.sep) || !fs.existsSync(target) || fs.statSync(target).isDirectory()) {
        missingAssets.push(relative); await route.fulfill({ status: 404, body: 'Missing local fixture asset' }); return;
      }
      const mime = { '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif', '.mp4': 'video/mp4', '.webp': 'image/webp', '.json': 'application/json' };
      await route.fulfill({ contentType: mime[path.extname(target)] || 'application/octet-stream', body: read(relative) });
    });
    page = await context.newPage(); page.setDefaultTimeout(5000);
    page.on('pageerror', error => pageErrors.push({ check: currentCheck, error: String(error) }));
    page.on('console', message => { if (message.type() === 'error') consoleErrors.push({ check: currentCheck, text: message.text() }); });
    await check('current app and actual people source compile', async () => { assert.ok(compiled.has('/src/pages/people.jsx')); assert.ok(compiled.has('/src/overlay.jsx')); assert.ok(compiled.size >= 35); return { compiled: compiled.size, peopleSha256: hash(read('src/pages/people.jsx')) }; });
    for (const size of [{ width: 1440, height: 900 }, { width: 390, height: 700 }]) {
      const suffix = String(size.width);
      await check('no dead more-filters and four retained controls ' + suffix, async () => {
        await openScenario('empty', size);
        assert.equal(await page.getByRole('button', { name: '更多筛选', exact: true }).count(), 0);
        assert.equal(await page.locator('#main-content .filter-pill').count(), 4);
        await expectDefaultFilters(); return geometry();
      });
      await check('initial empty opens real teacher form and cancels ' + suffix, async () => {
        await openScenario('empty', size);
        assert.equal(await emptyCard().getByText('尚未添加教师', { exact: true }).count(), 1);
        assert.equal(await emptyCard().getByRole('button', { name: '清空筛选', exact: true }).count(), 0);
        assert.match(await emptyCard().innerText(), /新增教师.*批量导入|批量导入.*新增教师/s);
        const entry = emptyCard().getByRole('button', { name: '新增教师', exact: true });
        await entry.scrollIntoViewIfNeeded(); await shot('initial-empty-' + suffix);
        await entry.click();
        const dialog = page.getByRole('dialog', { name: '新增教师', exact: true });
        await dialog.waitFor();
        assert.equal(await dialog.getByLabel(/工号/).count(), 1);
        assert.equal(await dialog.getByLabel(/姓名/).count(), 1);
        assert.equal(await dialog.getByRole('button', { name: '确认新增', exact: true }).count(), 1);
        await dialog.getByLabel(/姓名/).fill('只在浏览器内输入未保存');
        await shot('existing-teacher-form-' + suffix);
        await dialog.getByRole('button', { name: '取消', exact: true }).click();
        await dialog.waitFor({ state: 'hidden' });
        assert.equal(await entry.evaluate(e => document.activeElement === e), true);
        assert.equal(writeAttempts.length, 0);
        await entry.click(); await dialog.waitFor();
        assert.equal(await dialog.getByLabel(/姓名/).inputValue(), '');
        await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' });
        assert.equal(await entry.evaluate(e => document.activeElement === e), true);
        return { realFormCancelledAndEscaped: true, writes: writeAttempts.length, geometry: await geometry() };
      });
      await check('empty roster keyword no-match clears to initial guidance ' + suffix, async () => {
        await openScenario('empty', size);
        await page.getByPlaceholder('搜索姓名、工号、电话...').fill('没有这位教师');
        await page.getByText('没有符合条件的教职工', { exact: true }).waitFor();
        await emptyCard().getByRole('button', { name: '清空筛选', exact: true }).click();
        assert.equal(await page.getByPlaceholder('搜索姓名、工号、电话...').inputValue(), '');
        await emptyCard().getByText('尚未添加教师', { exact: true }).waitFor();
        return { initialGuidanceRestored: true };
      });
      for (const [label, value, expected] of [['年级:', '高一', ['合成教师甲', '合成教师丙']], ['科目:', '数学', ['合成教师乙', '合成教师丙']], ['班主任:', '是', ['合成教师甲']], ['状态:', '请假', ['合成教师乙']]]) {
        await check('retained filter ' + label + ' ' + suffix, async () => {
          await openScenario('populated', size); await expectRows(teacherRows.map(t => t.name));
          await pickFilter(label, value); await expectRows(expected);
          await resetFilters(); await expectDefaultFilters(); await expectRows(teacherRows.map(t => t.name));
          return { filter: label, selected: value, matched: expected, restoredCount: await rows().count() };
        });
      }
      await check('combined no-match clears search and filters ' + suffix, async () => {
        await openScenario('populated', size);
        await pickFilter('年级:', '高一'); await pickFilter('科目:', '语文'); await pickFilter('班主任:', '是'); await pickFilter('状态:', '在职');
        await expectRows(['合成教师甲']);
        await page.getByPlaceholder('搜索姓名、工号、电话...').fill('不存在的关键词');
        await emptyCard().getByText('没有符合条件的教职工', { exact: true }).waitFor();
        assert.equal(await page.getByText('尚未添加教师', { exact: true }).count(), 0);
        await emptyCard().scrollIntoViewIfNeeded(); await shot('filtered-no-match-' + suffix);
        await emptyCard().getByRole('button', { name: '清空筛选', exact: true }).click();
        assert.equal(await page.getByPlaceholder('搜索姓名、工号、电话...').inputValue(), '');
        await expectDefaultFilters(); await expectRows(teacherRows.map(t => t.name));
        return { rowsRestored: await rowNames(), geometry: await geometry() };
      });
      await check('pending GET never masquerades as empty ' + suffix, async () => {
        await openScenario('loading', size);
        await page.getByText('正在加载教师数据', { exact: true }).waitFor();
        assert.equal(await page.getByText('尚未添加教师', { exact: true }).count(), 0);
        assert.equal(await page.getByText('没有符合条件的教职工', { exact: true }).count(), 0);
        assert.ok(releasePending);
        await shot('loading-' + suffix); releasePending(); releasePending = null;
        await page.getByText('正在加载教师数据', { exact: true }).waitFor({ state: 'hidden' });
        return { pendingRenderedAsLoading: true };
      });
      await check('HTTP error is distinct and retry restores real rows ' + suffix, async () => {
        await openScenario('error', size);
        await page.getByText('教师数据加载失败', { exact: true }).waitFor();
        assert.equal(await page.getByText('尚未添加教师', { exact: true }).count(), 0);
        assert.equal(await page.getByText('没有符合条件的教职工', { exact: true }).count(), 0);
        assert.match(await emptyCard().innerText(), /合成教师接口暂不可用/);
        await emptyCard().scrollIntoViewIfNeeded(); await shot('error-' + suffix);
        scenario = 'populated'; await emptyCard().getByRole('button', { name: '重新加载', exact: true }).click();
        await expectRows(teacherRows.map(t => t.name));
        assert.equal(await page.getByText('教师数据加载失败', { exact: true }).count(), 0);
        return { restored: await rowNames() };
      });
      await check('empty roster does not show teacher schedule selection ' + suffix, async () => {
        await openScenario('empty', size);
        await emptyCard().getByText('尚未添加教师', { exact: true }).waitFor();
        assert.equal(await page.locator('[aria-label="教师逐场排班"]').count(), 0);
        assert.equal(await page.getByText('请选择教师查看排班', { exact: true }).count(), 0);
        await emptyCard().scrollIntoViewIfNeeded(); await shot('initial-empty-no-timeline-' + suffix);
        return { scheduleSectionCount: 0, geometry: await geometry() };
      });
      await check('populated roster retains schedule even with unmatched filters ' + suffix, async () => {
        await openScenario('populated', size); await expectRows(teacherRows.map(t => t.name));
        const schedule = page.locator('[aria-label="教师逐场排班"]');
        assert.equal(await schedule.count(), 1);
        await schedule.getByRole('heading', { name: '合成教师甲 · 逐场排班', exact: true }).waitFor();
        await schedule.scrollIntoViewIfNeeded(); await shot('populated-with-schedule-' + suffix);
        await page.getByPlaceholder('搜索姓名、工号、电话...').fill('不存在的关键词');
        await emptyCard().getByText('没有符合条件的教职工', { exact: true }).waitFor();
        assert.equal(await schedule.count(), 1);
        await schedule.getByRole('heading', { name: '合成教师甲 · 逐场排班', exact: true }).waitFor();
        await schedule.scrollIntoViewIfNeeded(); await shot('unmatched-with-schedule-' + suffix);
        return { scheduleSectionCount: 1, selectedTeacher: '合成教师甲', retainedWhenNoRowsMatch: true };
      });
    }
    await check('network console and source integrity', async () => {
      assert.deepEqual(externalRequests, []); assert.deepEqual(writeAttempts, []); assert.deepEqual(unknownGets, []); assert.deepEqual(missingAssets, []); assert.deepEqual(pageErrors, []);
      assert.equal(injectedFailures.length, 2);
      assert.equal(consoleErrors.length, injectedFailures.length, JSON.stringify(consoleErrors));
      assert.ok(consoleErrors.every(e => e.text.includes('503')), JSON.stringify(consoleErrors));
      const drift = [...sources].filter(([file, bytes]) => hash(fs.readFileSync(path.join(root, file))) !== hash(bytes)).map(([file]) => file);
      assert.deepEqual(drift, []); return { writeAttempts: 0, externalRequests: 0, deliberate503: 2, sourceDrift: drift };
    });
  } catch (error) { results.push({ name: 'fatal harness error', pass: false, error: String(error.stack || error) }); }
  finally {
    if (releasePending) releasePending();
    await browser?.close();
    const failed = results.filter(r => !r.pass);
    const report = { schema: 'PeopleEmptyRosterResult/v1', startedAt, finishedAt: new Date().toISOString(), scope: 'Current source, isolated synthetic browser fixtures, not live backend acceptance', actualExitCode: failed.length ? 1 : 0, environment, specification, testHash, sourceHashes: [...sources].map(([file, bytes]) => ({ file, sha256: hash(bytes) })), counts: { passed: results.length - failed.length, failed: failed.length, total: results.length, screenshots: screenshots.length }, results, requests, externalRequests, writeAttempts, unknownGets, pageErrors, consoleErrors, missingAssets, injectedFailures, screenshots };
    const reportPath = path.join(out, 'results.json');
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ reportPath, reportSha256: hash(fs.readFileSync(reportPath)), ...report.counts, actualExitCode: report.actualExitCode, testHash, peopleSha256: hash(read('src/pages/people.jsx')) }));
    process.exitCode = report.actualExitCode;
  }
})();
