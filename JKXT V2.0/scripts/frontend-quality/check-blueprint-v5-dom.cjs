#!/usr/bin/env node
/* Independent real-DOM QA of production components in the labeled, memory-only fixture.
   No application/auth API is loaded and no production backend request is permitted. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { chromium } = require(process.env.JKXT_PLAYWRIGHT_PATH || 'playwright');
const root = path.resolve(__dirname, '../..');
const out = path.join(root, 'artifacts/frontend-v5-20260906');
const shots = path.join(out, 'dom-shots');
const url = process.env.JKXT_VISUAL_HARNESS_URL || 'http://127.0.0.1:18180/artifacts/frontend-v5-20260906/visual-harness.html';
if (!/^http:\/\/(?:127\.0\.0\.1|localhost):\d+\/artifacts\/frontend-v5-20260906\/visual-harness\.html$/.test(url)) throw Error('Only the isolated local visual-harness document is accepted.');
fs.mkdirSync(shots, { recursive: true });
const report = { contract: 'BlueprintV5/independent-real-DOM', startedAt: new Date().toISOString(), environment: { url, browser: 'bundled Playwright Chromium headless', viewport: { width: 1520, height: 1080 }, fixtureOnly: true, backendAcceptance: false }, checks: [], console: [], network: [], bindings: {}, findings: [] };
const caseFilter = process.env.JKXT_DOM_QA_FILTER ? new RegExp(process.env.JKXT_DOM_QA_FILTER) : null;
report.environment.caseFilter = caseFilter?.source || null;
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex');
const bindingFiles = ['Dashboard.html', 'src/icons.jsx', 'src/ui/glass-primitives.jsx', 'src/overlay.jsx', 'src/common.jsx', 'src/ui/blueprint-v5.jsx', 'src/styles/blueprint-v5.css', 'src/pages/tasks-page.jsx', 'src/styles/tasks-v5.css', 'src/pages/edit.jsx', 'src/styles/manual-v5.css', 'src/pages/exam-plans.jsx', 'src/styles/plans-v5.css', 'src/pages/operations-workbench.jsx', 'src/styles/operations-v5.css', 'src/styles/glass-system.css', 'src/styles/theme-system.css', 'src/styles/usability-20260905.css', 'src/styles/workflow-v2.css', 'src/styles/task-workflow-v2.css', 'src/styles/manual-dispatch-v2.css', 'src/babel-script-scope.js', 'assets/blueprint-v5/symbols-clean-transparent.png', 'assets/blueprint-v5/empty-transparent.png', 'assets/vendor/tailwindcss-cdn.js', 'assets/vendor/react.18.3.1.development.js', 'assets/vendor/react-dom.18.3.1.development.js', 'assets/vendor/gsap.3.13.0.min.js', 'assets/vendor/babel-standalone.7.29.0.min.js', 'artifacts/frontend-v5-20260906/visual-harness.html', 'artifacts/frontend-v5-20260906/visual-fixtures.js', 'scripts/frontend-quality/check-blueprint-v5-dom.cjs'];
report.bindings.before = Object.fromEntries(bindingFiles.map(file => [file, hash(file)]));
let browser, page, frame;
const slug = value => value.replace(/[^\w.-]/g, '-');
const capture = async name => { const file = path.join(shots, slug(name) + '.png'); await page.locator('#fixture').screenshot({ path: file }); return path.relative(root, file).replace(/\\/g, '/'); };
const focus = () => frame.evaluate(() => { const active = document.activeElement; return { tag: active?.tagName, text: active?.textContent?.trim().slice(0, 110), id: active?.id, testId: active?.getAttribute('data-testid'), assignment: active?.getAttribute('data-assignment-id'), teacher: active?.getAttribute('data-teacher-id'), insideDialog: Boolean(active?.closest('[role="dialog"]')), rootInert: document.getElementById('root').inert }; });
const fit = () => frame.evaluate(() => {
  const viewport = document.documentElement.clientWidth;
  const tables = [...document.querySelectorAll('table')].filter(table => table.getClientRects().length).map(table => {
    let scroll = table.parentElement;
    while (scroll && !['auto', 'scroll'].includes(getComputedStyle(scroll).overflowX)) scroll = scroll.parentElement;
    const box = table.getBoundingClientRect();
    return { className: table.className, width: box.width, overflowsViewport: box.right > viewport + 1 || box.left < -1, scrollContainer: scroll?.className || null, scrollWidth: scroll?.scrollWidth || null, clientWidth: scroll?.clientWidth || null, containerRight: scroll?.getBoundingClientRect().right ?? null };
  });
  const outside = [...document.querySelectorAll('.fixture-workspace *')].filter(element => {
    if (!element.getClientRects().length) return false;
    const box = element.getBoundingClientRect();
    if (box.right <= viewport + 1 && box.left >= -1) return false;
    let parent = element.parentElement;
    while (parent && parent !== document.body) { if (['auto', 'scroll'].includes(getComputedStyle(parent).overflowX)) return false; parent = parent.parentElement; }
    return true;
  }).slice(0, 8).map(element => ({ tag: element.tagName, className: String(element.className), text: element.textContent.trim().slice(0, 70), right: element.getBoundingClientRect().right }));
  return { viewport, windowWidth: innerWidth, docWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth, tables, outside };
});
async function check(name, run, { severity = 'P2', location = null } = {}) {
  if (caseFilter && !caseFilter.test(name)) return;
  const item = { name, startedAt: new Date().toISOString() };
  try { item.observed = await run(item); item.pass = true; }
  catch (error) { item.pass = false; item.error = error.message; item.severity = severity; item.location = location; try { item.failureScreenshot = await capture('FAIL-' + name); item.focus = await focus(); item.fit = await fit(); } catch (_) {} report.findings.push({ name, severity, location, message: error.message, screenshot: item.failureScreenshot }); }
  item.finishedAt = new Date().toISOString(); report.checks.push(item); console.log((item.pass ? 'PASS ' : 'FAIL ') + name + (item.error ? ': ' + item.error.split('\n')[0] : ''));
  fs.writeFileSync(path.join(out, 'dom-qa.progress.json'), JSON.stringify(report, null, 2));
}
const routeNames = { risks: '风险与待办', 'exam-plans': '考试计划', tasks: '考务任务', edit: '人工调度', emergency: '应急与事件' };
const emptyText = { risks: '本次检查没有返回问题', 'exam-plans': '尚未创建考试计划', tasks: '还没有考务任务', edit: '请先选择考务任务', emergency: '尚无应急变更记录' };
const normalSelector = { risks: '.ops5-risk-row', 'exam-plans': '[data-testid="plan-row-TEST-plan-1"]', tasks: '[data-testid="tasks-v5-queue"]', edit: '[data-testid="p9-assignment-slot"]', emergency: '.ops5-record-table tbody tr' };
async function go(route, state = 'normal', width = 1440, scheme = 'light') {
  await page.locator('#width').selectOption(String(width));
  await page.locator('#scheme').selectOption(scheme);
  await page.locator('#state').selectOption(state);
  // Use actual route controls; the harness's initial query route may be superseded by its first status message.
  await page.locator(`[data-route="${route}"]`).click();
  await frame.waitForFunction(expected => window.__jkxtActivePage === expected, route);
  await frame.getByRole('heading', { level: 1, name: routeNames[route], exact: true }).waitFor();
  if (state === 'normal') await frame.locator(normalSelector[route]).first().waitFor();
  else if (state === 'empty') await frame.getByText(emptyText[route], { exact: true }).waitFor();
  else if (state === 'error') await frame.getByRole('alert').filter({ hasText: /模拟服务暂不可用/ }).first().waitFor();
  await frame.waitForFunction(() => [...document.images].every(image => image.complete));
  await frame.evaluate(() => window.scrollTo(0, 0));
}
async function dialogTrap() {
  const dialog = frame.getByRole('dialog'); await dialog.waitFor();
  assert.equal((await focus()).insideDialog, true, 'focus must enter the dialog');
  assert.equal((await focus()).rootInert, true, 'background root must be inert');
  await dialog.focus(); await page.keyboard.press('Shift+Tab');
  assert.equal((await focus()).insideDialog, true, 'Shift+Tab must remain in dialog');
  await page.keyboard.press('Tab');
  assert.equal((await focus()).insideDialog, true, 'forward wrap must remain in dialog');
  await frame.waitForFunction(() => !window.gsap?.getTweensOf(document.querySelector('[role="dialog"]')).some(tween => tween.isActive()));
  const geometry = await dialog.evaluate(element => {
    const panel = element.getBoundingClientRect();
    const footer = element.querySelector('.bp-layer__footer');
    return { panel: { top: panel.top, bottom: panel.bottom, left: panel.left, right: panel.right }, width: innerWidth, height: innerHeight, footer: footer ? { bottom: footer.getBoundingClientRect().bottom, buttons: footer.querySelectorAll('button').length } : null };
  });
  assert.ok(geometry.panel.left >= -1 && geometry.panel.right <= geometry.width + 1, 'dialog must fit horizontally');
  assert.ok(geometry.panel.top >= -1 && geometry.panel.bottom <= geometry.height + 1, 'dialog must fit vertically');
  return geometry;
}
const fixtureLog = async () => (await page.locator('#log').textContent()).trim().split('\n').filter(Boolean).map(line => JSON.parse(line));
async function manualPreview(width = 1440) { await go('edit', 'normal', width); const slot = frame.locator('[data-testid="p9-assignment-slot"]').first(); const id = await slot.getAttribute('data-assignment-id'); await slot.click(); await frame.locator('[data-teacher-id="TEST-teacher-2"]').click(); await frame.getByRole('dialog').waitFor(); return id; }
async function main() {
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: report.environment.viewport });
  page = await context.newPage(); page.setDefaultTimeout(12000);
  page.on('console', message => { if (['error', 'warning'].includes(message.type())) report.console.push({ type: message.type(), text: message.text() }); });
  page.on('pageerror', error => report.console.push({ type: 'pageerror', text: error.message }));
  await context.route('**/*', async request => {
    const req = request.request(), address = new URL(req.url());
    if (address.protocol === 'http:' && address.origin === new URL(url).origin && req.method() === 'GET' && !address.pathname.startsWith('/api/')) { report.network.push({ method: req.method(), path: address.pathname, allowed: true }); return request.continue(); }
    report.network.push({ method: req.method(), path: address.pathname, allowed: false }); return request.abort();
  });
  await page.goto(url); await page.locator('#status').filter({ hasText: '沙箱' }).waitFor();
  frame = page.frames().find(candidate => candidate.parentFrame());
  assert.ok(frame, 'isolated frame exists');
  report.bindings.loadedSources = await page.evaluate(() => window.__visualHarnessManifest);
  await check('identity-and-isolation', async () => {
    assert.equal(await page.title(), 'V5 独立前端测试 · 不写入学校');
    assert.equal(await page.locator('#fixture').getAttribute('sandbox'), 'allow-scripts');
    assert.ok((await frame.locator('body').innerText()).includes('独立前端测试，不写入学校'));
    assert.ok(await frame.evaluate(() => document.querySelector('meta[http-equiv="Content-Security-Policy"]').content.includes("connect-src 'none'")));
    return { title: await page.title(), opaqueOrigin: await frame.evaluate(() => location.origin) };
  });
  for (const width of [390, 768, 1440]) for (const route of Object.keys(routeNames)) for (const state of ['normal', 'empty', 'error']) {
    await check(`matrix-${route}-${state}-${width}`, async item => {
      await go(route, state, width); const geometry = await fit();
      item.screenshot = await capture(`${route}-${state}-${width}`);
      assert.equal(geometry.windowWidth, width, 'actual frame content viewport width');
      assert.ok(geometry.docWidth <= geometry.viewport + 1 && geometry.bodyWidth <= geometry.viewport + 1, `global horizontal overflow: ${JSON.stringify(geometry)}`);
      for (const table of geometry.tables) if (table.overflowsViewport) assert.ok(table.scrollContainer && table.containerRight <= geometry.viewport + 1, `overflowing table has no bounded local scroll: ${JSON.stringify(table)}`);
      assert.equal(geometry.outside.length, 0, `non-scroll content escapes viewport: ${JSON.stringify(geometry.outside)}`);
      return geometry;
    });
  }
  for (const width of [390, 1440]) {
    await check(`task-failure-drawer-focus-Escape-${width}`, async item => {
      await go('tasks', 'normal', width); const trigger=frame.locator('[data-testid="tasks-v5-detail-TEST-task-failed"]'); await trigger.click();
      await frame.getByText('TEST：可用监考教师不足，无法同时覆盖两个测试考场。', { exact: false }).waitFor();
      const geometry=await dialogTrap(); item.screenshot=await capture(`tasks-failure-drawer-${width}`); await page.keyboard.press('Escape'); await frame.getByRole('dialog').waitFor({ state: 'hidden' });
      assert.equal((await focus()).testId,'tasks-v5-detail-TEST-task-failed','drawer must restore task trigger focus');return { geometry, returnedFocus: await focus() };
    }, { location: 'src/ui/blueprint-v5.jsx' });
    await check(`manual-cancel-restores-original-slot-${width}`, async item => {
      const assignment=await manualPreview(width); const geometry=await dialogTrap();item.screenshot=await capture(`manual-confirm-${width}`);
      await frame.locator('[data-testid="md-cancel"]').click();await frame.getByRole('dialog').waitFor({ state: 'hidden' });
      const returnedFocus=await focus();assert.equal(returnedFocus.assignment,assignment,`cancel must restore original assignment, got ${JSON.stringify(returnedFocus)}`);return { geometry,returnedFocus };
    }, { location: 'src/pages/edit.jsx:354' });
    await check(`manual-Escape-restores-original-slot-${width}`, async () => {
      const assignment=await manualPreview(width);await dialogTrap();await page.keyboard.press('Escape');await frame.getByRole('dialog').waitFor({ state: 'hidden' });const returnedFocus=await focus();assert.equal(returnedFocus.assignment,assignment,`Escape must restore original assignment, got ${JSON.stringify(returnedFocus)}`);return returnedFocus;
    }, { location: 'src/pages/edit.jsx:354' });
    await check(`manual-blocked-conflict-preserves-original-${width}`, async item => {
      await manualPreview(width);await frame.locator('[data-testid="md-reason"]').fill('TEST 临时请假，请核对替补');const before=await fixtureLog();await frame.locator('[data-testid="md-confirm"]').click();await frame.locator('[data-testid="md-error"]').waitFor();
      await dialogTrap();item.screenshot=await capture(`manual-blocked-${width}`);const after=await fixtureLog();assert.equal(after.filter(x=>x.method==='PATCH').length,before.filter(x=>x.method==='PATCH').length,'blocked precheck cannot patch even fixture');assert.ok(after.filter(x=>x.method==='CHECK').length>before.filter(x=>x.method==='CHECK').length);
      assert.equal(await frame.locator('[data-testid="md-reason"]').inputValue(),'TEST 临时请假，请核对替补');await frame.locator('[data-testid="md-reselect"]').click();await frame.getByRole('dialog').waitFor({ state: 'hidden' });assert.notEqual((await focus()).tag,'BODY','reselection must retain an actionable focus target');return { focus:await focus() };
    }, { location:'src/pages/edit.jsx' });
    await check(`approved-publish-cancel-focus-${width}`, async item => {
      await go('exam-plans','normal',width);await frame.getByRole('button',{name:'审查并发布',exact:true}).click();const publish=frame.getByRole('button',{name:'发布此版本',exact:true});await publish.waitFor();const before=await fixtureLog();await publish.click();await dialogTrap();item.screenshot=await capture(`plans-publish-confirm-${width}`);await frame.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click();await frame.getByRole('dialog').waitFor({state:'hidden'});const after=await fixtureLog();assert.equal(after.filter(x=>x.method==='PUBLISH').length,before.filter(x=>x.method==='PUBLISH').length,'cancel must not publish');assert.match((await focus()).text,/发布此版本/);await publish.click();await page.keyboard.press('Escape');await frame.getByRole('dialog').waitFor({state:'hidden'});assert.match((await focus()).text,/发布此版本/);return { focus:await focus() };
    },{location:'src/ui/blueprint-v5.jsx / src/pages/exam-plans.jsx'});
    await check(`emergency-source-drawer-focus-Escape-${width}`, async item => {
      await go('emergency','normal',width);const trigger=frame.getByRole('button',{name:'查看详情',exact:true}).first();await trigger.click();await dialogTrap();item.screenshot=await capture(`emergency-detail-${width}`);assert.ok((await frame.getByRole('dialog').innerText()).includes('第 2 版'));await page.keyboard.press('Escape');await frame.getByRole('dialog').waitFor({state:'hidden'});assert.match((await focus()).text,/查看详情/);await frame.getByRole('button',{name:'查看详情',exact:true}).nth(1).click();assert.equal(await frame.getByRole('button',{name:'查看来源版本',exact:true}).isDisabled(),true);await page.keyboard.press('Escape');return {focus:await focus()};
    },{location:'src/pages/operations-workbench.jsx / src/ui/blueprint-v5.jsx'});
  }
  for(const route of Object.keys(routeNames)) await check(`dark-${route}-390`,async item=>{await go(route,'normal',390,'dark');item.screenshot=await capture(`dark-${route}-390`);const geometry=await fit();assert.ok(geometry.docWidth<=geometry.viewport+1);return geometry;});
  for (const [width, expectedSize] of [[390,36],[768,40],[1440,48]]) await check(`risk-symbol-responsive-size-${width}`,async item=>{
    await go('risks','normal',width);
    const symbols=await frame.locator('.ops5-risk-heading > .bp-asset--symbol').evaluateAll(elements=>elements.map(element=>{const box=element.getBoundingClientRect(),style=getComputedStyle(element);return {width:box.width,height:box.height,backgroundImage:style.backgroundImage};}));
    item.observed=symbols;item.screenshot=await capture(`risk-symbols-${width}`);assert.equal(symbols.length,3);
    for(const symbol of symbols){assert.equal(symbol.width,expectedSize,`symbol width at ${width}`);assert.equal(symbol.height,expectedSize,`symbol height at ${width}`);assert.ok(symbol.backgroundImage.includes('/assets/blueprint-v5/symbols-clean-transparent.png'),'computed style must reference the cleaned production sprite');}
    return symbols;
  },{location:'src/ui/blueprint-v5.jsx / src/styles/operations-v5.css'});
  await check('clean-symbol-sprite-has-real-alpha',async item=>{
    const observed=await page.evaluate(async()=>{const image=new Image();image.src='/assets/blueprint-v5/symbols-clean-transparent.png';await image.decode();const canvas=document.createElement('canvas');canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;const context=canvas.getContext('2d',{willReadFrequently:true});context.drawImage(image,0,0);const pixels=context.getImageData(0,0,canvas.width,canvas.height).data;let transparent=0,opaque=0,partial=0;for(let index=3;index<pixels.length;index+=4){if(pixels[index]===0)transparent++;else if(pixels[index]===255)opaque++;else partial++;}return {width:canvas.width,height:canvas.height,transparent,opaque,partial,total:pixels.length/4};});
    item.observed=observed;assert.ok(observed.transparent>0,'sprite must contain fully transparent pixels');assert.ok(observed.opaque>0,'sprite must retain visible opaque artwork');return observed;
  },{location:'assets/blueprint-v5/symbols-clean-transparent.png'});
  await check('desktop-risk-1920-no-global-overflow',async item=>{
    await page.setViewportSize({width:2000,height:1200});await go('risks','normal',1440);await page.locator('#fixture').evaluate(element=>{element.style.width='1920px';});await frame.waitForFunction(()=>innerWidth===1920);await frame.evaluate(()=>window.scrollTo(0,0));
    const geometry=await fit();item.screenshot=await capture('risks-normal-1920');assert.equal(geometry.windowWidth,1920);assert.ok(geometry.docWidth<=1921&&geometry.bodyWidth<=1921);assert.equal(geometry.outside.length,0);await page.setViewportSize(report.environment.viewport);return geometry;
  });
  for (const scheme of ['light','dark']) await check(`plan-status-readable-contrast-${scheme}`,async item=>{
    await go('exam-plans','normal',390,scheme);
    const badges=await frame.locator('.plan-status-badge').evaluateAll(elements=>{
      const canvas=document.createElement('canvas');canvas.width=canvas.height=1;const context=canvas.getContext('2d',{willReadFrequently:true});
      const luminance=color=>{context.clearRect(0,0,1,1);context.fillStyle=color;context.fillRect(0,0,1,1);const channels=[...context.getImageData(0,0,1,1).data].slice(0,3).map(channel=>{channel/=255;return channel<=.04045?channel/12.92:Math.pow((channel+.055)/1.055,2.4);});return channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;};
      return elements.map(element=>{const style=getComputedStyle(element),foreground=luminance(style.color),background=luminance(style.backgroundColor);return {text:element.textContent,fontPx:parseFloat(style.fontSize),fg:style.color,bg:style.backgroundColor,contrast:(Math.max(foreground,background)+.05)/(Math.min(foreground,background)+.05)};});
    });item.observed=badges;item.screenshot=await capture(`plans-status-contrast-${scheme}-390`);assert.ok(badges.length>=4,'all four fixture status labels are measured');
    assert.ok(badges.every(badge=>badge.contrast>=4.5),`small status text needs 4.5:1: ${JSON.stringify(badges)}`);
    assert.ok(badges.every(badge=>badge.fontPx>=13),`auxiliary status text must be at least 13px: ${JSON.stringify(badges)}`);return badges;
  },{location:'src/pages/exam-plans.jsx:107 / src/styles/plans-v5.css'});
  await check('reduced-motion-dialog-has-no-live-animation',async item=>{
    await page.emulateMedia({reducedMotion:'reduce'});await manualPreview(390);await dialogTrap();
    // glass-system.css:2421 intentionally uses a 0.01ms accessibility reset.
    // Permit only that effectively instant reset, not a visible transition.
    const observed=await frame.evaluate(()=>({reduced:matchMedia('(prefers-reduced-motion: reduce)').matches,gsapTweens:window.gsap?.getTweensOf(document.querySelector('.bp-layer')).filter(tween=>tween.isActive()).length||0,allowedResetSeconds:0.00001,css:[...document.querySelectorAll('.bp-layer,.bp-layer *')].filter(element=>element.getClientRects().length).map(element=>{const style=getComputedStyle(element);return {tag:element.tagName,className:element.className,animation:style.animationName,transition:style.transitionDuration};}).filter(style=>style.animation!=='none'||!style.transition.split(',').every(value=>parseFloat(value)<=0.00001))}));item.observed=observed;item.screenshot=await capture('manual-reduced-motion-390');assert.equal(observed.reduced,true);assert.equal(observed.gsapTweens,0,'reduced motion must not start GSAP tweens');assert.equal(observed.css.length,0,'reduced motion allows only the existing <=0.01ms reset, no animation');await page.keyboard.press('Escape');return observed;
  });
  await check('no-backend-network-or-relevant-console-errors',async()=>{
    assert.equal(report.network.filter(entry=>entry.path.startsWith('/api/')).length,0,'no backend network attempt is allowed');
    const relevant=report.console.filter(entry=>entry.type==='pageerror'||entry.type==='error'||(entry.type==='warning'&&!/cdn\.tailwindcss\.com should not be used in production/.test(entry.text)));
    assert.equal(relevant.length,0,JSON.stringify(relevant));return {requests:report.network.length,backendRequests:0,explainedWarnings:report.console.length};
  });
}
main().catch(error=>{report.fatal={message:error.message,stack:error.stack};console.error(error);process.exitCode=1;}).finally(async()=>{
  if(browser)await browser.close();report.finishedAt=new Date().toISOString();report.bindings.after=Object.fromEntries(bindingFiles.map(file=>[file,hash(file)]));report.bindings.changedDuringRun=bindingFiles.filter(file=>report.bindings.before[file]!==report.bindings.after[file]);report.passed=report.checks.filter(check=>check.pass).length;report.failed=report.checks.filter(check=>!check.pass).length;
  report.status=report.bindings.changedDuringRun.length?'stale':report.failed||report.fatal||!report.checks.length?'incomplete':'passed-fixture-only';
  fs.writeFileSync(path.join(out,'dom-qa.report.json'),JSON.stringify(report,null,2));
  const lines=['# V5 独立真实 DOM 检查','',`时间：${report.startedAt} — ${report.finishedAt}`,`环境：${url}`,`结果：${report.passed} 通过，${report.failed} 失败。状态：${report.status}。仅 TEST 沙箱；不是后端或学校数据验收。`,'',...report.findings.map(item=>`- ${item.severity} ${item.name}: ${item.message} (${item.screenshot||'无截图'})`),'',`运行中变动绑定：${report.bindings.changedDuringRun.join(', ')||'无'}`,`完整证据：dom-qa.report.json；截图：dom-shots/。`];fs.writeFileSync(path.join(out,'dom-qa.report.md'),lines.join('\n'));
  if(report.failed||report.fatal||!report.checks.length||report.bindings.changedDuringRun.length)process.exitCode=1;console.log(`DOM QA: ${report.passed} passed / ${report.failed} failed; ${report.status}; report artifacts/frontend-v5-20260906/dom-qa.report.json`);
});
