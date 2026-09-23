/* Registered A-only regression: actual TimelineCard/icons/Button and existing product CSS.
 * Virtual-origin GET fixtures only. Does not connect to localhost, backend, DB or user Chrome.
 * Keep assertions unchanged between RED and GREEN; product source is snapshotted before launch. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const playwrightModule = process.env.JKXT_PLAYWRIGHT_MODULE || 'playwright';
const { chromium } = require(playwrightModule);
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined;
const root = path.resolve(__dirname, '../..');
const startedAt = new Date().toISOString(), stamp = startedAt.replace(/[:.]/g, '-');
const out = path.join(root, 'artifacts/site-acceptance-20260907/qa-calendar-scope-artifacts', stamp);
fs.mkdirSync(out, { recursive: true });
const hash = input => crypto.createHash('sha256').update(input).digest('hex');
const sources = new Map();
const read = file => { if (!sources.has(file)) sources.set(file, fs.readFileSync(path.join(root, file))); return sources.get(file); };
const testHash = hash(fs.readFileSync(__filename));
const specPath = 'artifacts/site-acceptance-20260907/display-scope-spec-20260908.md';
const registered = {
  schema: 'DashboardCalendarScopeChecks/v1', registeredAt: startedAt, goal: 'goal-1c7c93e5b99d9351', version: 3,
  specification: { path: specPath, sha256: hash(read(specPath)) }, testHash,
  assertions: [
    'Historical seven-day payload discloses actual start/end and latest-successful-schedule scope, never implies today or publication.',
    'Historical and future windows without today have no current-time line. A true today at indices zero and two retains the correct-column line.',
    'Missing currentTime never draws a meaningless line even when today exists.',
    'Selected-day headings disclose exact returned date, same latest-success scope; header selection and bounded previous/next navigation preserve exact exams.',
    'Month/year disclose latest-successful-run-per-task aggregate scope and exactly filter real calendarDays by selected month/year.',
    'Month/year to day preserves the existing selected day, not imaginary cross-view detail drillthrough.',
    'Empty and missing-date payloads do not infer this-week or synthetic dates/exams; missing complete analytics is honest.',
    'Pending GET shows loading; injected 503 shows failure, never empty/demo; retry restores real component using registered fixtures.',
    '1440x900 normal motion and 390x700 reduced motion: no horizontal document overflow, tab click/keyboard activation and screenshots.',
    'All network calls are virtual-origin GET only; no external request or mutation attempt, no missing asset or uncaught error.',
    'Source bytes are stable during each run. Original 119/26 tests and all backend files are untouched by this runner.'
  ],
  fixtureBoundary: 'Synthetic schedule dates and labels, actual current component code. No live health or database-data claim.'
};
fs.writeFileSync(path.join(out, 'registered-checks.json'), JSON.stringify(registered, null, 2));
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
vm.runInNewContext(read('src/babel-script-scope.js').toString(), { window: { Babel }, document: { querySelectorAll: () => [] } });
const compiled = new Map();
for (const file of ['src/icons.jsx', 'src/common.jsx', 'src/timeline.jsx']) {
  compiled.set('/' + file, Babel.transform(read(file).toString(), { presets: ['react', 'env'], plugins: ['jkxt-script-scope'] }).code);
}
const dashboard = read('Dashboard.html').toString();
const head = dashboard.slice(0, dashboard.indexOf('</head>')).replace(/<title>[\s\S]*?<\/title>/, '<title>日历展示口径隔离验收（合成）</title>');
const html = head + '</head><body><div style="max-width:1180px;margin:0 auto;padding:16px"><div id="fixture-note">隔离验收 · 合成考试数据</div><div id="calendar-fixture"></div></div>' +
  '<script src="/assets/vendor/react.18.3.1.development.js"></script><script src="/assets/vendor/react-dom.18.3.1.development.js"></script>' +
  [...compiled.keys()].map(file => '<script src="' + file + '"></script>').join('') +
  '<script>window.api={get:async function(url){const r=await fetch("/api/v1"+url);const data=await r.json();if(!r.ok)throw new Error(data.message);return data;}};ReactDOM.createRoot(document.getElementById("calendar-fixture")).render(React.createElement(window.TimelineCard));</script></body></html>';
const origin = 'http://jkxt.calendar-scope.test', fixedCurrentDate = '2026-09-08';
const calendarDays = [
  { isoDate: '2026-06-15', examCount: 8, supervisionCount: 16, roomCount: 4 },
  { isoDate: '2026-06-16', examCount: 9, supervisionCount: 18, roomCount: 5 },
  { isoDate: '2026-07-01', examCount: 3, supervisionCount: 6, roomCount: 2 },
  { isoDate: '2027-01-05', examCount: 4, supervisionCount: 8, roomCount: 2 }
];
function payloadFor(state) {
  if (state === 'empty') return { currentDate: fixedCurrentDate, currentTime: '10:42', days: [], exams: [], calendarDays: [], availableYears: [2026] };
  const start = state === 'future' ? '2026-10-12' : state === 'today-zero' || state === 'missing-time' ? fixedCurrentDate : state === 'today-two' ? '2026-09-06' : '2026-06-15';
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = new Date(start + 'T00:00:00Z'); date.setUTCDate(date.getUTCDate() + index);
    const isoDate = date.toISOString().slice(0, 10);
    return { isoDate, date: String(date.getUTCDate()), dow: '日一二三四五六'[date.getUTCDay()], label: '周' + '日一二三四五六'[date.getUTCDay()], today: isoDate === fixedCurrentDate, dim: false };
  });
  const exams = days.flatMap((day, dayIdx) => [0, 1].map(index => ({
    id: 'fixture-' + dayIdx + '-' + index, row: index, start: dayIdx + 1, span: 1,
    label: '合成考试-' + day.isoDate + '-' + (index + 1), meta: '合成排班任务 · 2 名监考', color: 1 + (dayIdx % 6),
    supN: 2, time: index ? '14:00' : '08:00', room: '合成考场', examDate: day.isoDate, subjectName: '合成科目'
  })));
  const payload = { currentDate: fixedCurrentDate, currentTime: state === 'missing-time' ? '' : '10:42', weekStart: start, days, exams, calendarDays, availableYears: [2026, 2027], generatedAt: '2026-09-08T10:42:00Z' };
  if (state === 'unsupported') { delete payload.calendarDays; delete payload.availableYears; }
  if (state === 'missing-dates') {
    payload.days = days.map(({ isoDate, date, ...day }) => ({ ...day, today: false }));
    delete payload.weekStart; payload.exams = [];
  }
  return payload;
}
const results = [], requests = [], writes = [], external = [], unknownGets = [], pageErrors = [], consoleErrors = [], screenshots = [], injectedFailures = [];
let browser, context, page, scenario = 'historical', currentCheck = '', releasePending;
const environment = { node: process.version, platform: process.platform, playwrightModule: require.resolve(playwrightModule), chromiumExecutable: executablePath || chromium.executablePath() };
const section = () => page.locator('section[aria-labelledby="dashboard-exam-overview-title"]');
const subtitle = () => section().locator('div[aria-live="polite"]').first();
const marker = () => section().locator('div.absolute.top-0.bottom-2.w-px');
const tab = name => section().getByRole('button', { name, exact: true }).first();
async function geometry() {
  const rect = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth, height: innerHeight }));
  assert.ok(rect.document <= rect.viewport + 1, JSON.stringify(rect)); return rect;
}
async function shot(name) {
  const file = path.join(out, name.replace(/[^a-zA-Z0-9_-]/g, '-') + '.png');
  await page.screenshot({ path: file, fullPage: false });
  screenshots.push({ path: file, sha256: hash(fs.readFileSync(file)), manuallyReviewed: false }); return file;
}
async function check(name, fn) {
  currentCheck = name;
  try { results.push({ name, pass: true, details: await fn() }); console.log('PASS ' + name); }
  catch (e) { const item = { name, pass: false, error: String(e.stack || e).slice(0, 3500) }; try { item.screenshot = await shot('failure-' + name); } catch {} results.push(item); console.log('FAIL ' + name + ': ' + e.message); }
  finally { if (releasePending) { releasePending(); releasePending = null; } }
}
async function open(state, size) {
  scenario = state; await page.setViewportSize(size);
  await page.emulateMedia({ reducedMotion: size.width === 390 ? 'reduce' : 'no-preference' });
  await page.goto(origin + '/fixture?state=' + state + '&nonce=' + Date.now(), { waitUntil: 'domcontentloaded' });
  await section().waitFor();
  if (state !== 'loading') await page.getByLabel('正在加载考试安排', { exact: true }).waitFor({ state: 'hidden' });
  await page.evaluate(() => document.fonts.ready);
}
async function choosePeriod(year, month) {
  await section().getByRole('button', { name: /年/ }).filter({ has: page.locator('svg') }).first().click();
  const dialog = section().getByRole('dialog', { name: '选择统计时间' });
  await dialog.getByRole('button', { name: String(year), exact: true }).click();
  if (month) await dialog.getByRole('button', { name: month + ' 月', exact: true }).click();
}
(async () => {
  try {
    browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) }); environment.browserVersion = browser.version();
    context = await browser.newContext({ locale: 'zh-CN', serviceWorkers: 'block' });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      requests.push({ method: request.method(), path: url.pathname, scenario, check: currentCheck });
      if (url.origin !== origin) { external.push(url.href); await route.abort('blockedbyclient'); return; }
      if (request.method() !== 'GET') { writes.push({ method: request.method(), path: url.pathname }); await route.abort('blockedbyclient'); return; }
      if (url.pathname === '/api/v1/timeline/schedule') {
        const state = scenario;
        if (state === 'loading') await new Promise(resolve => { releasePending = resolve; });
        if (state === 'error') { injectedFailures.push({ path: url.pathname, status: 503, check: currentCheck }); await route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"合成接口失败，请重试"}' }); return; }
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(payloadFor(state)) }); return;
      }
      if (url.pathname.startsWith('/api/')) { unknownGets.push(url.pathname); await route.fulfill({ status: 404, body: '{}' }); return; }
      if (url.pathname === '/fixture') { await route.fulfill({ contentType: 'text/html', body: html }); return; }
      if (compiled.has(url.pathname)) { await route.fulfill({ contentType: 'application/javascript', body: compiled.get(url.pathname) }); return; }
      const relative = decodeURIComponent(url.pathname).replace(/^\//, ''), file = path.resolve(root, relative);
      if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { unknownGets.push(url.pathname); await route.fulfill({ status: 404, body: 'Unregistered asset' }); return; }
      const mime = { '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png' };
      await route.fulfill({ contentType: mime[path.extname(file)] || 'application/octet-stream', body: read(relative) });
    });
    page = await context.newPage(); page.setDefaultTimeout(3500);
    page.on('pageerror', error => pageErrors.push({ check: currentCheck, error: String(error) }));
    page.on('console', message => { if (message.type() === 'error') consoleErrors.push({ check: currentCheck, text: message.text() }); });
    await check('real component compiled with existing styles', async () => { assert.ok(compiled.has('/src/timeline.jsx')); assert.match(html, /frontend-polish.css/); return { testHash, timelineHash: hash(read('src/timeline.jsx')) }; });
    for (const size of [{ width: 1440, height: 900 }, { width: 390, height: 700 }]) {
      const w = size.width;
      await check('historical period scope and range ' + w, async () => {
        await open('historical', size);
        assert.equal(await section().getByRole('heading').first().innerText(), '排班周览');
        const text = await subtitle().innerText();
        assert.match(text, /2026-06-15\s*[—–至]\s*2026-06-21/); assert.match(text, /最近一次成功排班/); assert.match(text, /14\s*场考试/);
        assert.doesNotMatch(text, /已发布|已确认|近\s*7\s*天|本周/);
        await shot('historical-week-' + w); return geometry();
      });
      await check('historical no false current-time marker ' + w, async () => { await open('historical', size); assert.equal(await marker().count(), 0); });
      await check('future no false current-time marker ' + w, async () => { await open('future', size); assert.equal(await marker().count(), 0); assert.match(await subtitle().innerText(), /2026-10-12\s*[—–至]\s*2026-10-18/); });
      for (const item of [{ state: 'today-zero', index: 0 }, { state: 'today-two', index: 2 }]) {
        await check(item.state + ' marker correct column ' + w, async () => {
          await open(item.state, size); assert.equal(await marker().count(), 1);
          assert.equal(await marker().innerText(), '10:42');
          const pos = await marker().evaluate(node => {
            const parent = node.parentElement.getBoundingClientRect(), actual = node.getBoundingClientRect();
            return { localX: actual.x - parent.x, width: parent.width };
          });
          const col = (pos.width - 6 * 8) / 7, expected = (col + 8) * item.index + col / 2;
          assert.ok(Math.abs(pos.localX - expected) <= 2, JSON.stringify({ ...pos, expected }));
          return pos;
        });
      }
      await check('missing time has no marker ' + w, async () => { await open('missing-time', size); assert.equal(await marker().count(), 0); });
      await check('selected day exact date and navigation ' + w, async () => {
        await open('historical', size); const before = requests.filter(r => r.path.startsWith('/api/')).length;
        await section().locator('button[title="查看 周二 当日明细"]').click();
        assert.equal(await tab('日').getAttribute('aria-pressed'), 'true');
        assert.equal(await section().getByRole('heading').first().innerText(), '所选日期考试安排');
        assert.match(await subtitle().innerText(), /2026-06-16.*最近一次成功排班/);
        const list = section().locator('.timeline-day-list');
        assert.match(await list.innerText(), /合成考试-2026-06-16-1/); assert.doesNotMatch(await list.innerText(), /合成考试-2026-06-15/);
        await tab('周').click(); await tab('日').focus(); await page.keyboard.press('Enter');
        assert.match(await subtitle().innerText(), /2026-06-16/);
        await section().getByRole('button', { name: '前一天', exact: true }).click();
        assert.equal(await section().getByRole('button', { name: '前一天', exact: true }).isDisabled(), true);
        assert.match(await subtitle().innerText(), /2026-06-15/);
        for (let i = 0; i < 6; i++) await section().getByRole('button', { name: '后一天', exact: true }).click();
        assert.equal(await section().getByRole('button', { name: '后一天', exact: true }).isDisabled(), true);
        assert.match(await subtitle().innerText(), /2026-06-21/);
        assert.equal(requests.filter(r => r.path.startsWith('/api/')).length, before);
        await shot('selected-day-' + w); return geometry();
      });
      await check('month year aggregate scope and exact filtering ' + w, async () => {
        await open('historical', size); const before = requests.filter(r => r.path.startsWith('/api/')).length;
        await tab('月').click();
        assert.match(await subtitle().innerText(), /2027 年 1 月.*4 场考试.*1 个考试日/);
        assert.match(await subtitle().innerText(), /各任务最近成功排班汇总/);
        await choosePeriod(2026, 6);
        assert.match(await subtitle().innerText(), /2026 年 6 月.*17 场考试.*2 个考试日/);
        await tab('年').click();
        assert.match(await subtitle().innerText(), /2026 年.*20 场考试.*3 个考试日/);
        assert.match(await subtitle().innerText(), /各任务最近成功排班汇总/);
        await choosePeriod(2027);
        assert.match(await subtitle().innerText(), /2027 年.*4 场考试.*1 个考试日/);
        await shot('annual-scope-' + w);
        await tab('日').click();
        assert.match(await subtitle().innerText(), /2026-06-15.*最近一次成功排班/);
        assert.equal(requests.filter(r => r.path.startsWith('/api/')).length, before);
        return geometry();
      });
      await check('empty response honest in all views ' + w, async () => {
        await open('empty', size); assert.equal(await marker().count(), 0);
        assert.match(await subtitle().innerText(), /日期范围未提供.*0 场考试/);
        assert.equal(await section().getByText('该日期范围内暂无已排定考试', { exact: true }).count(), 1);
        assert.doesNotMatch(await section().innerText(), /初二·数学|高三·英语|2026-06-15|本周/);
        await tab('日').click(); assert.equal(await section().getByText('当前没有可用的考试日期', { exact: true }).count(), 1); assert.match(await subtitle().innerText(), /日期未提供/);
        await tab('月').click(); assert.equal(await section().getByText('当前范围暂无已排定考试', { exact: true }).count(), 1);
        await tab('年').click(); assert.match(await subtitle().innerText(), /0 场考试.*0 个考试日/);
        await shot('empty-annual-' + w); return geometry();
      });
      await check('missing dates are never guessed ' + w, async () => {
        await open('missing-dates', size); assert.match(await subtitle().innerText(), /日期范围未提供/); assert.doesNotMatch(await subtitle().innerText(), /本周|2026-/);
        await tab('日').click(); assert.match(await subtitle().innerText(), /日期未提供/);
      });
      await check('unsupported complete analytics stays honest ' + w, async () => {
        await open('unsupported', size); await tab('月').click();
        assert.equal(await section().getByText('完整统计数据尚未启用', { exact: true }).count(), 1);
        assert.match(await subtitle().innerText(), /不支持完整月度与年度统计/);
        assert.doesNotMatch(await section().innerText(), /近\s*7\s*天/);
        await tab('年').click(); assert.equal(await section().getByRole('status').count(), 1); return geometry();
      });
      await check('loading without empty or mock schedule ' + w, async () => {
        await open('loading', size); assert.equal(await page.getByLabel('正在加载考试安排', { exact: true }).count(), 1);
        assert.equal(await marker().count(), 0); assert.match(await subtitle().innerText(), /正在读取考试安排/);
        assert.doesNotMatch(await section().innerText(), /合成考试|初二·数学|当前范围暂无|该日期范围内暂无/);
      });
      await check('error retry restores actual component ' + w, async () => {
        await open('error', size); assert.equal(await section().getByRole('alert').count(), 1); assert.match(await subtitle().innerText(), /数据连接异常/);
        assert.equal(await marker().count(), 0);
        scenario = 'historical'; await section().getByRole('button', { name: '重新加载', exact: true }).click();
        await section().getByRole('alert').waitFor({ state: 'hidden' });
        assert.match(await subtitle().innerText(), /2026-06-15.*2026-06-21/);
        assert.equal(await section().getByRole('heading').first().innerText(), '排班周览'); return geometry();
      });
    }
    await check('virtual GET only no external or mutation', async () => { assert.deepEqual(writes, []); assert.deepEqual(external, []); assert.deepEqual(unknownGets, []); return { requests: requests.length, apiGets: requests.filter(r => r.path.startsWith('/api/')).length, writes: writes.length, external: external.length }; });
    await check('runtime and console health', async () => {
      assert.deepEqual(pageErrors, []);
      const unexpected = consoleErrors.filter(item => !/Failed to load resource.*503/.test(item.text));
      assert.deepEqual(unexpected, []); assert.equal(injectedFailures.length, 2);
      return { pageErrors, expectedInjected503: injectedFailures.length, consoleErrors };
    });
    await check('source stability', async () => {
      const changed = [...sources].filter(([file, bytes]) => hash(fs.readFileSync(path.join(root, file))) !== hash(bytes)).map(([file]) => file);
      assert.deepEqual(changed, []); return { changed, files: sources.size };
    });
  } catch (error) { results.push({ name: 'runner', pass: false, error: String(error.stack || error) }); }
  finally {
    if (releasePending) releasePending();
    if (context) await context.close();
    if (browser) await browser.close();
    const endedAt = new Date().toISOString();
    const sourceHashes = Object.fromEntries([...sources].map(([file, bytes]) => [file, hash(bytes)]));
    const report = { schema: 'DashboardCalendarScopeResult/v1', startedAt, endedAt, environment, registered, sourceHashes, checks: results.length, passed: results.filter(r => r.pass).length, failed: results.filter(r => !r.pass).length, results, requests, writes, external, unknownGets, injectedFailures, pageErrors, consoleErrors, screenshots };
    const reportPath = path.join(out, 'results.json');
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ reportPath, reportHash: hash(fs.readFileSync(reportPath)), testHash, sourceHash: sourceHashes['src/timeline.jsx'], checks: report.checks, passed: report.passed, failed: report.failed, screenshots: screenshots.length }));
    process.exitCode = report.failed ? 1 : 0;
  }
})();
