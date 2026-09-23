/* Isolated DOM acceptance. Uses current source components; no API, user session or server. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require('C:/Users/nuoya/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../..');
const out = path.join(root, 'artifacts/frontend-polish-20260907');
const shots = path.join(out, 'dom-screenshots');
fs.mkdirSync(shots, { recursive: true });
const origin = 'http://jkxt.frontend.test';
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
const sourceSnapshots = new Map();
function snapshotSource(file) {
  if (!sourceSnapshots.has(file)) sourceSnapshots.set(file, fs.readFileSync(path.join(root, file)));
  return sourceSnapshots.get(file);
}
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
vm.runInNewContext(snapshotSource('src/babel-script-scope.js').toString('utf8'), { window: { Babel }, document: { querySelectorAll: () => [] } });
const jsxFiles = ['src/icons.jsx', 'src/ui/glass-primitives.jsx', 'src/overlay.jsx', 'src/common.jsx', 'src/ui/blueprint-v5.jsx', 'src/onboarding.jsx', 'src/charts.jsx'];
const compiled = new Map(jsxFiles.map((file) => ['/compiled/' + file.replace(/\.jsx$/, '.js'), Babel.transform(snapshotSource(file).toString('utf8'), { presets: ['react', 'env'], plugins: ['jkxt-script-scope'], filename: file }).code]));
for (const file of ['src/glass-motion.js','src/styles/frontend-polish.css','src/styles/onboarding-learning.css']) snapshotSource(file);
const fixture = `
const domResults = [];
const domProfile = { user: { id: 'synthetic-dom-user', displayName: '合成验收账号' }, organization: { id: 'synthetic-dom-school' } };
window.__jkxtMe = domProfile;
window.navigateTo = route => { document.getElementById('last-route').textContent = route; };
function recordDom(promise, tag) { promise.then(value => domResults.push({tag,value,at:performance.now()})); }
function longContent(kind) { return <div><p>合成测试内容，仅用于验证滚动和焦点，不含学校或教师真实数据。</p>{Array.from({length:18},(_,i)=><p key={i} style={{margin:'18px 0'}}>核对说明 {i+1}：查看内容、检查状态，再执行明确的操作。</p>)}<Button id={kind+'-inside-last'} onClick={()=>{}}>内容末尾控件</Button></div>; }
window.domHarness = {
  openModal(title='通用弹窗', tag='modal') { recordDom(window.ui.modal({title,size:'md',content:longContent('modal'),footer:<><Button onClick={()=>window.ui.closeModal('cancel')}>取消弹窗</Button><Button variant="primary" id="modal-finish" onClick={()=>{window.__closeStarted=performance.now();window.ui.closeModal('done');window.__presentAtClose=!!document.querySelector('.ui-modal');}}>完成并关闭</Button></>}),tag); },
  openDrawer(title='详情抽屉', tag='drawer') { recordDom(window.ui.drawer({title,content:<>{longContent('drawer')}<Button id="drawer-to-modal" onClick={()=>{ui.closeDrawer();domHarness.openModal('由抽屉打开的弹窗','chain-modal');}}>转到弹窗</Button></>,footer:<><Button onClick={()=>window.ui.closeDrawer('cancel')}>取消抽屉</Button><Button variant="primary" id="drawer-finish" onClick={()=>{window.__closeStarted=performance.now();window.ui.closeDrawer('done');window.__presentAtClose=!!document.querySelector('.ui-drawer');}}>关闭详情</Button></>}),tag); },
  openConfirm(title='操作确认',tag='confirm') { recordDom(window.ui.confirm({title,desc:'仅用于确认返回值与动效时序的合成测试。',confirmText:'确认操作',cancelText:'取消操作'}),tag); },
  results:domResults,blueprintResults:[],
};
function DomFixture() { const [bp,setBP]=React.useState({open:false,busy:false});const BPComp=bp.kind==='drawer'?BlueprintDrawer:BlueprintDialog;domHarness.setBlueprint=setBP;return <div className="app-shell" data-page-id="dashboard"><div className="app-layout"><main className="app-main" id="main-content" style={{padding:20,minWidth:0}}><h1 style={{fontSize:24,fontWeight:700}}>合成组件验收 · 非业务页面</h1><p>当前源文件组件的独立验收，不调用后台、不创建业务数据。</p><div style={{display:'flex',gap:12,flexWrap:'wrap',margin:'16px 0'}}><Button id="launch-modal" onClick={()=>domHarness.openModal()}>打开测试弹窗</Button><Button id="launch-drawer" onClick={()=>domHarness.openDrawer()}>打开测试抽屉</Button><Button id="launch-confirm" onClick={()=>domHarness.openConfirm()}>打开测试确认</Button><Button id="launch-blueprint" onClick={()=>setBP({open:true,busy:false})}>打开业务浮层</Button></div><p id="last-route" aria-live="polite"/><OnboardingLearningCard profile={domProfile}/><section style={{marginTop:24,padding:16,background:'white',border:'1px solid #DDE4ED',borderRadius:12}}><h2 style={{fontSize:16}}>合成图表 · 非业务指标</h2><BarChartJS labels={['准备','复核']} datasets={[{label:'合成数据',data:[2,3]}]} height={180}/></section></main></div><OverlayHost/><BPComp open={bp.open} busy={bp.busy} title="业务浮层验收" description="实际BlueprintLayer，合成内容" onClose={()=>{domHarness.blueprintResults.push(performance.now());setBP({open:false,busy:false});}} footer={<><Button>底部测试控件</Button><Button id="bp-open-confirm" onClick={()=>domHarness.openConfirm('跨系统确认','cross-confirm')}>跨系统确认</Button></>}>{longContent('blueprint')}</BPComp></div>; }
ReactDOM.createRoot(document.getElementById('root')).render(<DomFixture/>);
window.__domFixtureReady=true;`;
compiled.set('/compiled/dom-fixture.js', Babel.transform(fixture, { presets: ['react', 'env'], plugins: ['jkxt-script-scope'] }).code);
const htmlHead = snapshotSource('Dashboard.html').toString('utf8').match(/<head>([\s\S]*?)<\/head>/)[1].replace(/<title>[^<]*<\/title>/, '<title>JKXT 前端独立组件验收</title>');
const scripts = ['assets/vendor/chart.umd.4.4.1.min.js', 'assets/vendor/react.18.3.1.development.js', 'assets/vendor/react-dom.18.3.1.development.js', 'assets/vendor/gsap.3.13.0.min.js', 'assets/vendor/anime.4.5.0.umd.min.js', 'src/glass-motion.js', ...compiled.keys()];
const html = '<!doctype html><html lang="zh-CN"><head>' + htmlHead + '</head><body class="app-body"><div id="root"></div>' + scripts.map((src) => '<script src="' + src + '"></script>').join('') + '</body></html>';
const mime = { '.js':'application/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.woff2':'font/woff2', '.png':'image/png', '.jpg':'image/jpeg', '.webp':'image/webp', '.json':'application/json' };
const results = [], requests = [], forbidden = [], consoleErrors = [], consoleWarnings = [], screenshots = [];
let browser, context, page;
const start = new Date().toISOString();
async function check(name, run) {
  try { const details = await run(); results.push({name,pass:true,details}); console.log('PASS '+name); }
  catch (error) { results.push({name,pass:false,error:String(error.stack||error).slice(0,1400)}); console.log('FAIL '+name+': '+error.message); }
}
async function ready() { await page.waitForFunction(()=>window.__domFixtureReady && document.querySelector('.learning-guide'), {timeout:15000}); await page.evaluate(()=>document.fonts.ready); }
async function reset() { await page.emulateMedia({reducedMotion:'no-preference'}); await page.evaluate(()=>localStorage.clear()); await page.reload(); await ready(); }
async function closeAll() { await page.evaluate(()=>{window.ui.closeModal();window.ui.closeDrawer();document.querySelector('[role="alertdialog"] button')?.click();}); await page.waitForTimeout(300); }
async function snapshot(name) { const file=path.join(shots,name+'.png'); await page.screenshot({path:file,fullPage:false}); screenshots.push(file); return file; }
const visibleDialog = () => page.locator('.ui-overlay [role="dialog"],.ui-overlay [role="alertdialog"]');

(async()=>{
  browser = await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  context = await browser.newContext({viewport:{width:1440,height:900},reducedMotion:'no-preference',locale:'zh-CN'});
  await context.route('**/*',async route=>{
    const url=new URL(route.request().url()); requests.push(url.href);
    if(url.origin!==origin || url.pathname.startsWith('/api/')) {forbidden.push(url.href);await route.abort('blockedbyclient');return;}
    if(url.pathname==='/' || url.pathname==='/dom-harness.html') {await route.fulfill({contentType:'text/html',body:html});return;}
    if(compiled.has(url.pathname)) {await route.fulfill({contentType:'application/javascript',body:compiled.get(url.pathname)});return;}
    const file=path.resolve(root,'.'+decodeURIComponent(url.pathname));
    if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()) {await route.fulfill({status:404,body:'Local test asset missing'});return;}
    await route.fulfill({contentType:mime[path.extname(file)]||'application/octet-stream',body:snapshotSource(path.relative(root,file).split(path.sep).join('/'))});
  });
  page=await context.newPage(); page.setDefaultTimeout(6000);
  page.on('pageerror',error=>consoleErrors.push(error.message));
  page.on('console',msg=>{if(msg.type()==='error')consoleErrors.push(msg.text());if(msg.type()==='warning')consoleWarnings.push(msg.text());});
  await page.goto(origin+'/dom-harness.html'); await ready();
  await check('identity, real components and local engines loaded',async()=>{
    assert.equal(await page.title(),'JKXT 前端独立组件验收');
    assert.equal(await page.getByRole('heading',{name:'合成组件验收 · 非业务页面'}).count(),1);
    const values=await page.evaluate(()=>({gsap:gsap.version,anime:typeof anime.animate,motion:document.documentElement.dataset.motionEngine,charts:Object.keys(Chart.instances).length}));
    assert.equal(values.gsap,'3.13.0');assert.equal(values.anime,'function');assert.equal(values.motion,'ready');assert.equal(values.charts,1);return values;
  });
  await check('modal exit retains DOM until 120ms transition then restores trigger focus',async()=>{
    await page.locator('#launch-modal').click();await visibleDialog().waitFor();await page.waitForTimeout(240);
    await page.locator('#modal-finish').click();await page.waitForFunction(()=>domHarness.results.some(r=>r.tag==='modal'));
    const result=await page.evaluate(()=>({elapsed:domHarness.results.find(r=>r.tag==='modal').at-window.__closeStarted,value:domHarness.results.find(r=>r.tag==='modal').value,immediate:window.__presentAtClose,focus:document.activeElement.id,inert:document.getElementById('root').inert}));
    assert.equal(result.immediate,true);assert.ok(result.elapsed>=90&&result.elapsed<1500,JSON.stringify(result));assert.equal(result.value,'done');assert.equal(result.focus,'launch-modal');assert.equal(result.inert,false);assert.equal(await visibleDialog().count(),0);return result;
  });
  await check('drawer exit has temporal presence, keyboard Escape and focus return',async()=>{
    await reset();await page.locator('#launch-drawer').click();await visibleDialog().waitFor();await page.waitForTimeout(230);
    await page.locator('#drawer-finish').click();await page.waitForFunction(()=>domHarness.results.length===1);
    const result=await page.evaluate(()=>({elapsed:domHarness.results[0].at-window.__closeStarted,value:domHarness.results[0].value,immediate:window.__presentAtClose,focus:document.activeElement.id}));
    assert.equal(result.immediate,true);assert.ok(result.elapsed>=90&&result.elapsed<1500);assert.equal(result.focus,'launch-drawer');assert.equal(result.value,'done');
    await page.locator('#launch-drawer').click();await visibleDialog().waitFor();await page.keyboard.press('Escape');await visibleDialog().waitFor({state:'detached'});assert.equal(await page.evaluate(()=>document.activeElement.id),'launch-drawer');return result;
  });
  await check('confirm cancel settles false once and keyboard focus is contained',async()=>{
    await reset();await page.locator('#launch-confirm').click();await page.getByRole('alertdialog').waitFor();
    for(let i=0;i<5;i++)await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(()=>!!document.activeElement.closest('[role="alertdialog"]')),true);
    await page.getByRole('button',{name:'取消操作',exact:true}).click();await page.waitForFunction(()=>domHarness.results.length===1);
    assert.equal(await page.evaluate(()=>domHarness.results[0].value),false);assert.equal(await page.evaluate(()=>document.activeElement.id),'launch-confirm');
  });
  await check('rapid modal replacement cancels obsolete close without removing replacement',async()=>{
    await reset();await page.locator('#launch-modal').click();await visibleDialog().waitFor();
    await page.evaluate(()=>{ui.closeModal('old');domHarness.openModal('替换后的弹窗','replacement');});
    await page.waitForTimeout(350);assert.equal(await page.getByRole('heading',{name:'替换后的弹窗',exact:true}).count(),1);
    const facts=await page.evaluate(()=>({results:domHarness.results.map(r=>({tag:r.tag,value:r.value})),focusInside:!!document.activeElement.closest('.ui-modal')}));
    assert.deepEqual(facts.results,[{tag:'modal',value:null}]);assert.equal(facts.focusInside,true);await closeAll();return facts;
  });
  await check('newest overlay visual z-order and focus agree across modal then drawer then confirm',async()=>{
    await reset();await page.locator('#launch-modal').click();await visibleDialog().waitFor();
    await page.evaluate(()=>domHarness.openDrawer('后打开的抽屉'));await page.getByRole('heading',{name:'后打开的抽屉'}).waitFor();
    let facts=await page.evaluate(()=>Array.from(document.querySelectorAll('.ui-overlay')).map(shell=>({z:Number(getComputedStyle(shell).zIndex),focused:shell.contains(document.activeElement),kind:shell.querySelector('.ui-drawer')?'drawer':'modal'})));
    assert.equal(facts.find(f=>f.focused).z,Math.max(...facts.map(f=>f.z)));assert.equal(facts.find(f=>f.focused).kind,'drawer');
    await page.evaluate(()=>domHarness.openConfirm('最上层确认'));await page.getByRole('alertdialog').waitFor();assert.equal(await page.evaluate(()=>!!document.activeElement.closest('[role="alertdialog"]')),true);
    await page.keyboard.press('Escape');await page.getByRole('alertdialog').waitFor({state:'detached'});assert.equal(await page.evaluate(()=>!!document.activeElement.closest('.ui-drawer')),true);
    await page.keyboard.press('Escape');await page.locator('.ui-drawer').waitFor({state:'detached'});assert.equal(await page.evaluate(()=>!!document.activeElement.closest('.ui-modal')),true);await closeAll();return facts;
  });
  await check('drawer-to-modal chain returns focus to original root trigger after both close',async()=>{
    await reset();await page.locator('#launch-drawer').click();await page.locator('.ui-drawer').waitFor();
    await page.locator('#drawer-to-modal').click();await page.locator('.ui-modal').waitFor();await page.locator('.ui-drawer').waitFor({state:'detached'});
    await page.locator('#modal-finish').click();await visibleDialog().waitFor({state:'detached'});
    const focus=await page.evaluate(()=>document.activeElement.id);assert.equal(focus,'launch-drawer');return {focus};
  });
  await check('BlueprintLayer X exit is asynchronous, idempotent and restores focus',async()=>{
    await reset();await page.locator('#launch-blueprint').click();await page.locator('.bp-layer').waitFor();await page.waitForTimeout(240);
    await page.evaluate(()=>{window.__bpStarted=performance.now();const button=document.querySelector('.bp-close');button.click();button.click();window.__bpImmediate=!!document.querySelector('.bp-layer');});
    await page.locator('.bp-layer').waitFor({state:'detached'});
    const result=await page.evaluate(()=>({count:domHarness.blueprintResults.length,elapsed:domHarness.blueprintResults[0]-window.__bpStarted,immediate:window.__bpImmediate,focus:document.activeElement.id}));
    assert.equal(result.count,1);assert.equal(result.immediate,true);assert.ok(result.elapsed>=90&&result.elapsed<1500);assert.equal(result.focus,'launch-blueprint');return result;
  });
  await check('BlueprintLayer Escape exits and busy transition cancels an in-flight close',async()=>{
    await reset();await page.locator('#launch-blueprint').click();await page.locator('.bp-layer').waitFor();
    await page.evaluate(()=>{document.querySelector('.bp-close').click();ReactDOM.flushSync(()=>domHarness.setBlueprint({open:true,busy:true}));});
    await page.waitForTimeout(300);assert.equal(await page.locator('.bp-layer').count(),1);assert.equal(await page.evaluate(()=>domHarness.blueprintResults.length),0);
    assert.equal(await page.locator('.bp-close').isDisabled(),true);
    await page.evaluate(()=>domHarness.setBlueprint({open:true,busy:false}));await page.keyboard.press('Escape');await page.locator('.bp-layer').waitFor({state:'detached'});
    assert.equal(await page.evaluate(()=>domHarness.blueprintResults.length),1);
  });
  await check('BlueprintLayer unmount cancels exit and leaves no stale tween/callback',async()=>{
    await reset();await page.locator('#launch-blueprint').click();await page.locator('.bp-layer').waitFor();
    await page.evaluate(()=>{window.__oldBpPanel=document.querySelector('.bp-layer');document.querySelector('.bp-close').click();ReactDOM.flushSync(()=>domHarness.setBlueprint({open:false,busy:false}));});
    await page.waitForTimeout(300);assert.equal(await page.locator('.bp-portal').count(),0);assert.equal(await page.evaluate(()=>domHarness.blueprintResults.length),0);assert.equal(await page.evaluate(()=>gsap.getTweensOf(window.__oldBpPanel).length),0);
  });
  await check('BlueprintLayer reduced synchronous close remains idempotent under duplicate clicks',async()=>{
    await reset();await page.emulateMedia({reducedMotion:'reduce'});await page.locator('#launch-blueprint').click();await page.locator('.bp-layer').waitFor();
    await page.evaluate(()=>{window.__bpStarted=performance.now();const close=document.querySelector('.bp-close');close.click();close.click();});
    await page.locator('.bp-layer').waitFor({state:'detached'});
    const result=await page.evaluate(()=>({count:domHarness.blueprintResults.length,elapsed:domHarness.blueprintResults[0]-window.__bpStarted}));
    assert.equal(result.count,1);assert.ok(result.elapsed<70);await page.emulateMedia({reducedMotion:'no-preference'});return result;
  });
  await check('BlueprintDrawer then legacy confirm: only newest overlay handles Escape and focus',async()=>{
    await reset();await page.evaluate(()=>domHarness.setBlueprint({open:true,busy:false,kind:'drawer'}));await page.locator('.bp-layer').waitFor();
    await page.locator('#bp-open-confirm').click();await page.locator('[role="alertdialog"]').waitFor();
    const before=await page.evaluate(()=>({focus:document.activeElement.closest('[role="alertdialog"]')?.getAttribute('role'),blueprintInert:document.querySelector('.bp-layer')?.closest('[inert]')!==null,confirmInert:document.querySelector('[role="alertdialog"]')?.closest('[inert]')!==null}));
    await page.keyboard.press('Escape');await page.waitForTimeout(300);
    const after=await page.evaluate(()=>({blueprint:!!document.querySelector('.bp-layer'),confirm:!!document.querySelector('[role="alertdialog"]'),focusInBlueprint:!!document.activeElement.closest('.bp-layer'),closed:domHarness.blueprintResults.length}));
    assert.equal(before.focus,'alertdialog',JSON.stringify({before,after}));assert.equal(after.blueprint,true,JSON.stringify({before,after}));assert.equal(after.confirm,false);assert.equal(after.focusInBlueprint,true);return {before,after};
  });
  await check('legacy drawer then BlueprintDialog: only newest overlay handles Escape and focus',async()=>{
    await reset();await page.locator('#launch-drawer').click();await page.locator('.ui-drawer').waitFor();await page.evaluate(()=>domHarness.setBlueprint({open:true,busy:false}));await page.locator('.bp-layer').waitFor();
    const before=await page.evaluate(()=>({focusInBlueprint:!!document.activeElement.closest('.bp-layer'),legacyInert:!!document.querySelector('.ui-drawer')?.closest('[inert]')}));
    await page.keyboard.press('Escape');await page.waitForTimeout(300);
    const after=await page.evaluate(()=>({blueprint:!!document.querySelector('.bp-layer'),legacy:!!document.querySelector('.ui-drawer'),focusInLegacy:!!document.activeElement.closest('.ui-drawer')}));
    assert.equal(before.focusInBlueprint,true,JSON.stringify({before,after}));assert.equal(after.blueprint,false,JSON.stringify({before,after}));assert.equal(after.legacy,true,JSON.stringify({before,after}));assert.equal(after.focusInLegacy,true,JSON.stringify({before,after}));return {before,after};
  });
  await check('cross-system closing all layers restores root inert and body overflow in both orders',async()=>{
    const evidence=[];
    for(const first of ['blueprint','legacy']) {
      await reset();const original=await page.evaluate(()=>({inert:document.getElementById('root').inert,aria:document.getElementById('root').getAttribute('aria-hidden'),overflow:document.body.style.overflow}));
      if(first==='blueprint'){await page.locator('#launch-blueprint').click();await page.locator('.bp-layer').waitFor();await page.evaluate(()=>domHarness.openConfirm('叠层确认'));await page.locator('[role="alertdialog"]').waitFor();}
      else{await page.locator('#launch-drawer').click();await page.locator('.ui-drawer').waitFor();await page.evaluate(()=>domHarness.setBlueprint({open:true,busy:false}));await page.locator('.bp-layer').waitFor();}
      await page.keyboard.press('Escape');await page.waitForTimeout(260);assert.equal(await page.evaluate(()=>document.getElementById('root').inert),true);
      await page.keyboard.press('Escape');await page.waitForTimeout(260);
      const restored=await page.evaluate(()=>({inert:document.getElementById('root').inert,aria:document.getElementById('root').getAttribute('aria-hidden'),overflow:document.body.style.overflow}));assert.deepEqual(restored,original);evidence.push({first,restored});
    }return evidence;
  });
  await check('unmounting lower Blueprint layer leaves newer confirmation locked and focused',async()=>{
    await reset();await page.locator('#launch-blueprint').click();await page.locator('.bp-layer').waitFor();await page.locator('#bp-open-confirm').click();await page.locator('[role="alertdialog"]').waitFor();
    await page.evaluate(()=>ReactDOM.flushSync(()=>domHarness.setBlueprint({open:false,busy:false})));await page.waitForTimeout(100);
    const middle=await page.evaluate(()=>({rootInert:document.getElementById('root').inert,overflow:document.body.style.overflow,top:!!document.activeElement.closest('[role="alertdialog"]')}));assert.equal(middle.rootInert,true);assert.equal(middle.overflow,'hidden');assert.equal(middle.top,true);
    await page.keyboard.press('Escape');await page.locator('[role="alertdialog"]').waitFor({state:'detached'});assert.equal(await page.evaluate(()=>document.getElementById('root').inert),false);return middle;
  });
  await check('reduced-motion changes during exit settle immediately and stop chart motion',async()=>{
    await reset();await page.locator('#launch-modal').click();await visibleDialog().waitFor();
    await page.evaluate(()=>{window.__reduceStarted=performance.now();ui.closeModal('reduced');});await page.emulateMedia({reducedMotion:'reduce'});
    await visibleDialog().waitFor({state:'detached'});await page.waitForFunction(()=>Object.values(Chart.instances)[0].options.animation===false);
    const data=await page.evaluate(()=>({mode:document.documentElement.dataset.motion,animation:Object.values(Chart.instances)[0].options.animation,results:domHarness.results.length}));
    assert.equal(data.mode,'reduced');assert.equal(data.animation,false);assert.equal(data.results,1);
    await page.locator('#launch-confirm').click();await page.getByRole('alertdialog').waitFor();await page.getByRole('button',{name:'取消操作',exact:true}).click();await page.getByRole('alertdialog').waitFor({state:'detached'});
    await page.emulateMedia({reducedMotion:'no-preference'});return data;
  });
  await check('teaching controls persist distinct branches and Anime reaches exact 75 offset',async()=>{
    await reset();await page.getByRole('button',{name:'我已了解',exact:true}).click();
    await page.waitForFunction(()=>Math.abs(parseFloat(getComputedStyle(document.querySelector('.learning-guide__ring-value')).strokeDashoffset)-75)<.05);
    await page.getByRole('button',{name:'联合考试计划',exact:true}).click();await page.getByRole('button',{name:'我已了解',exact:true}).click();
    await page.getByRole('button',{name:'稍后继续',exact:true}).click();assert.equal(await page.locator('.learning-guide').getAttribute('open'),null);
    await page.reload();await ready();assert.equal(await page.locator('.learning-guide').getAttribute('open'),null);
    await page.locator('.learning-guide__summary').click();assert.equal(await page.getByRole('button',{name:'联合考试计划',exact:true}).getAttribute('aria-pressed'),'true');
    const record=await page.evaluate(()=>JSON.parse(localStorage.getItem(JKXTLearningGuides.storageKey(window.__jkxtMe))));assert.deepEqual(record.learnedIds,['quick-prepare','plan-prepare']);
    await page.getByRole('button',{name:'打开考试计划',exact:true}).click();assert.equal(await page.locator('#last-route').textContent(),'exam-plans');return record;
  });
  await check('teaching reset cancel preserves, confirm clears both paths and restores focus',async()=>{
    await page.getByRole('button',{name:'重置学习记录',exact:true}).click();await page.getByRole('alertdialog').waitFor();await page.getByRole('button',{name:'保留记录',exact:true}).click();await page.getByRole('alertdialog').waitFor({state:'detached'});
    let record=await page.evaluate(()=>JSON.parse(localStorage.getItem(JKXTLearningGuides.storageKey(window.__jkxtMe))));assert.equal(record.learnedIds.length,2);
    await page.getByRole('button',{name:'重置学习记录',exact:true}).click();await page.getByRole('alertdialog').waitFor();await page.getByRole('alertdialog').getByRole('button',{name:'重置学习记录',exact:true}).click();await page.getByRole('alertdialog').waitFor({state:'detached'});
    record=await page.evaluate(()=>JSON.parse(localStorage.getItem(JKXTLearningGuides.storageKey(window.__jkxtMe))));assert.deepEqual(record.learnedIds,[]);
    assert.equal(await page.evaluate(()=>document.activeElement.textContent),'重置学习记录');return {learnedIds:record.learnedIds};
  });
  await check('teaching reset restores temporarily disabled trigger with reduced motion',async()=>{
    await reset();await page.emulateMedia({reducedMotion:'reduce'});await page.getByRole('button',{name:'我已了解',exact:true}).click();
    await page.getByRole('button',{name:'重置学习记录',exact:true}).click();await page.getByRole('alertdialog').waitFor();
    await page.getByRole('alertdialog').getByRole('button',{name:'重置学习记录',exact:true}).click();await page.getByRole('alertdialog').waitFor({state:'detached'});
    await page.waitForTimeout(100);
    const facts=await page.evaluate(()=>({focus:document.activeElement.textContent,tag:document.activeElement.tagName,learned:JSON.parse(localStorage.getItem(JKXTLearningGuides.storageKey(window.__jkxtMe))).learnedIds}));
    assert.deepEqual(facts.learned,[]);assert.equal(facts.focus,'重置学习记录',JSON.stringify({tag:facts.tag}));await page.emulateMedia({reducedMotion:'no-preference'});return facts;
  });
  await check('reduced reset cancel retains progress and new overlay retains focus over reset trigger',async()=>{
    await reset();await page.emulateMedia({reducedMotion:'reduce'});await page.getByRole('button',{name:'我已了解',exact:true}).click();
    await page.getByRole('button',{name:'重置学习记录',exact:true}).click();await page.getByRole('alertdialog').waitFor();await page.getByRole('alertdialog').getByRole('button',{name:'保留记录',exact:true}).click();await page.getByRole('alertdialog').waitFor({state:'detached'});await page.waitForTimeout(60);
    assert.equal(await page.evaluate(()=>document.activeElement.textContent),'重置学习记录');assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem(JKXTLearningGuides.storageKey(window.__jkxtMe))).learnedIds.length),1);
    await page.getByRole('button',{name:'重置学习记录',exact:true}).click();await page.getByRole('alertdialog').waitFor();
    await page.evaluate(()=>{Array.from(document.querySelectorAll('[role="alertdialog"] button')).find(b=>b.textContent==='保留记录').click();domHarness.openModal('新的顶层窗口','newest-reset');});
    await page.getByRole('heading',{name:'新的顶层窗口',exact:true}).waitFor();await page.waitForTimeout(100);
    assert.equal(await page.evaluate(()=>!!document.activeElement.closest('.ui-modal')),true);await closeAll();await page.emulateMedia({reducedMotion:'no-preference'});
  });
  await check('Fontsource Chinese and Latin variable fonts actually load locally',async()=>{
    const fonts=await page.evaluate(async()=>{const zh=await document.fonts.load('14px "Noto Sans SC Variable"','监考教师排班');const latin=await document.fonts.load('14px "Geist Variable"','Quick 123');return {chinese:zh.map(f=>({family:f.family,status:f.status})),latin:latin.map(f=>({family:f.family,status:f.status})),body:getComputedStyle(document.body).fontFamily};});
    assert.ok(fonts.chinese.length>0);assert.ok(fonts.chinese.every(f=>f.status==='loaded'));assert.ok(fonts.latin.length>0);assert.match(fonts.body,/Noto Sans SC Variable/);return fonts;
  });
  await check('learning details keyboard toggle hides inactive controls from navigation',async()=>{
    await reset();await page.locator('.learning-guide__summary').focus();await page.keyboard.press('Enter');
    assert.equal(await page.locator('.learning-guide').getAttribute('open'),null);assert.equal(await page.getByRole('button',{name:'我已了解',exact:true}).count(),0);
    await page.keyboard.press('Space');assert.notEqual(await page.locator('.learning-guide').getAttribute('open'),null);
    await page.getByRole('button',{name:'我已了解',exact:true}).focus();await page.keyboard.press('Enter');
    assert.equal(await page.getByRole('button',{name:'已了解本步骤',exact:true}).isDisabled(),true);
  });
  for(const size of [{width:1440,height:900},{width:390,height:700}]) {
    await check(`confirmation ${size.width}x${size.height} compact width and long text scrollable with reachable actions`,async()=>{
      await reset();await page.setViewportSize(size);
      await page.evaluate(()=>{ui.confirm({title:'长文确认的独立验收',desc:'这是本地合成的长文确认内容，用于核验滚动、阅读和取消操作始终可达，不涉及业务数据。'.repeat(100),confirmText:'确认长文操作',cancelText:'取消长文操作'});});
      await page.getByRole('alertdialog').waitFor();await page.waitForTimeout(220);
      const metrics=await page.getByRole('alertdialog').evaluate(el=>({rect:el.getBoundingClientRect().toJSON(),client:el.clientHeight,scroll:el.scrollHeight,overflow:getComputedStyle(el).overflowY,doc:document.documentElement.scrollWidth}));
      assert.ok(metrics.rect.width<=460.5&&metrics.rect.width<=size.width,JSON.stringify(metrics));
      assert.ok(metrics.rect.x>=0&&metrics.rect.x+metrics.rect.width<=size.width+1&&metrics.rect.y>=0&&metrics.rect.y+metrics.rect.height<=size.height+1,JSON.stringify(metrics));
      assert.ok(metrics.scroll>metrics.client,JSON.stringify(metrics));assert.ok(['auto','scroll'].includes(metrics.overflow));assert.ok(metrics.doc<=size.width+1);
      const cancel=page.getByRole('button',{name:'取消长文操作',exact:true});await cancel.scrollIntoViewIfNeeded();await cancel.focus();
      const action=await cancel.boundingBox();assert.ok(action.y>=0&&action.y+action.height<=size.height+1,JSON.stringify(action));
      await snapshot(`confirmation-${size.width}x${size.height}`);await page.keyboard.press('Enter');await page.getByRole('alertdialog').waitFor({state:'detached'});return {metrics,action};
    });
  }
  for(const size of [{width:1440,height:900},{width:1024,height:700},{width:390,height:700}]) {
    await check(`responsive ${size.width}x${size.height} no horizontal overflow and reachable footer actions`,async()=>{
      await reset();await page.setViewportSize(size);await page.waitForTimeout(160);
      await page.locator('.learning-guide__summary').scrollIntoViewIfNeeded();await snapshot(`learning-top-${size.width}x${size.height}`);
      const dimensions=await page.evaluate(()=>({doc:document.documentElement.scrollWidth,width:innerWidth,card:document.querySelector('.learning-guide').getBoundingClientRect().toJSON()}));assert.ok(dimensions.doc<=size.width+1,JSON.stringify(dimensions));
      await page.getByRole('button',{name:'我已了解',exact:true}).scrollIntoViewIfNeeded();assert.equal(await page.getByRole('button',{name:'我已了解',exact:true}).isVisible(),true);
      await snapshot(`learning-${size.width}x${size.height}`);
      await page.locator('#launch-modal').click();await visibleDialog().waitFor();await page.waitForTimeout(240);
      const box=await page.locator('.ui-modal').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=size.width+1&&box.y>=0&&box.y+box.height<=size.height+1,JSON.stringify(box));
      const footer=await page.locator('#modal-finish').boundingBox();assert.ok(footer.y>=0&&footer.y+footer.height<=size.height+1,JSON.stringify(footer));
      await snapshot(`modal-${size.width}x${size.height}`);await page.locator('#modal-finish').click();await visibleDialog().waitFor({state:'detached'});
      await page.locator('#launch-drawer').click();await visibleDialog().waitFor();await page.waitForTimeout(240);
      const drawer=await page.locator('.ui-drawer').boundingBox();assert.ok(drawer.x>=0&&drawer.x+drawer.width<=size.width+1,JSON.stringify(drawer));
      const drawerFooter=await page.locator('#drawer-finish').boundingBox();assert.ok(drawerFooter.y>=0&&drawerFooter.y+drawerFooter.height<=size.height+1,JSON.stringify(drawerFooter));
      await snapshot(`drawer-${size.width}x${size.height}`);await page.locator('#drawer-finish').click();await visibleDialog().waitFor({state:'detached'});return {dimensions,modal:box,drawer};
    });
  }
  await check('no API/external requests, framework errors or application console errors',async()=>{
    assert.deepEqual(forbidden,[]);assert.deepEqual(consoleErrors,[]);return {requests:requests.length,fontRequests:requests.filter(u=>u.endsWith('.woff2')).length,knownWarnings:consoleWarnings.filter(w=>/tailwindcss\.com should not be used|in-browser Babel transformer/.test(w)).length};
  });
})().catch(error=>{results.push({name:'harness startup',pass:false,error:String(error.stack||error)});console.error(error);}).finally(async()=>{
  await browser?.close();
  const hashes=Object.fromEntries([...sourceSnapshots].map(([file,data])=>[file,digest(data)]));
  const changedDuringRun=[...sourceSnapshots].filter(([file,data])=>digest(fs.readFileSync(path.join(root,file)))!==digest(data)).map(([file])=>file);
  const report={suite:'frontend-polish-real-dom',startedAt:start,finishedAt:new Date().toISOString(),origin,browser:'Google Chrome isolated headless context',businessData:'none; synthetic fixture only',results,passed:results.filter(r=>r.pass).length,total:results.length,forbidden,consoleErrors,consoleWarnings:[...new Set(consoleWarnings)],screenshots,hashes,changedDuringRun};
  fs.writeFileSync(path.join(out,'dom-results.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({passed:report.passed,total:report.total,report:path.join(out,'dom-results.json')},null,2));
  if(report.passed!==report.total)process.exitCode=1;
});
