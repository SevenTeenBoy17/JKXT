/* Full-route frontend acceptance using actual current App and explicitly synthetic GET fixtures.
 * No service, user browser/session, backend writes or network passthrough. */
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const playwrightModule=process.env.JKXT_PLAYWRIGHT_MODULE||'playwright';
const {chromium}=require(playwrightModule);
const chromiumExecutable=process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH||undefined;
const environment={node:process.version,platform:process.platform,arch:process.arch,playwrightModule:require.resolve(playwrightModule),playwrightModuleOverride:!!process.env.JKXT_PLAYWRIGHT_MODULE,chromiumExecutable:chromiumExecutable||chromium.executablePath(),chromiumExecutableOverride:!!chromiumExecutable,browserVersion:null};
const stamp=new Date().toISOString().replace(/[:.]/g,'-'),startedAt=new Date().toISOString();
const root=path.resolve(__dirname,'../..'),out=path.join(root,'artifacts/site-acceptance-20260907'),shots=path.join(out,'frontend-screenshots',stamp);
fs.mkdirSync(shots,{recursive:true});
const digest=v=>crypto.createHash('sha256').update(v).digest('hex'),sources=new Map();
const read=file=>{if(!sources.has(file))sources.set(file,fs.readFileSync(path.join(root,file)));return sources.get(file);};
const nav={window:{}};vm.runInNewContext(read('src/navigation.js').toString(),nav);
const routes=Object.entries(nav.window.JKXTNavigation.pageMap).map(([id,v])=>({id,title:v.title,mode:v.mode,component:String(v.Comp).match(/window\.([\w]+)/)?.[1]}));
const htmlSource=read('Dashboard.html').toString(),Babel=require(path.join(root,'assets/vendor/babel-standalone.7.29.0.min.js'));
vm.runInNewContext(read('src/babel-script-scope.js').toString(),{window:{Babel},document:{querySelectorAll:()=>[]}});
const compiled=new Map(),pageSources=new Map();
for(const item of htmlSource.matchAll(/<script\s+type="text\/babel"\s+src="([^"?]+)[^"]*"[^>]*><\/script>/g)){
  const file=item[1],text=read(file).toString();pageSources.set(file,text);
  compiled.set('/'+file,Babel.transform(text,{presets:['react','env'],plugins:['jkxt-script-scope']}).code);
}
let html=htmlSource.replace(/<script\s+type="text\/babel"/g,'<script').replace(/<script src="src\/babel-script-scope[^>]+><\/script>/,'');
html=html.replace('<div id="root"></div>','<div id="root"></div><script>const fixtureParams=new URLSearchParams(location.search);localStorage.setItem("jkxt:auth","1");localStorage.setItem("jkxt:mode",fixtureParams.get("mode")||"admin");localStorage.setItem("jkxt:page",fixtureParams.get("page")||"dashboard");localStorage.setItem("jkxt:onboarded","1");window.__SITE_ACCEPTANCE_FIXTURE=true;</script>');
const tests=fs.readdirSync(path.join(root,'scripts/frontend-quality')).filter(f=>/^check-/.test(f)&&f!=='check-site-acceptance.cjs').map(file=>({file,text:fs.readFileSync(path.join(root,'scripts/frontend-quality',file),'utf8')}));
const aliases={risks:'operations-workbench',emergency:'operations-v6-emergency',approvals:'operations-v6-approvals',tasks:'tasks-page',ai:'ai-policy',classes:'classes-subjects',subjects:'classes-subjects','exam-plans':'exam-plans','task-detail':'task-detail'};
const inventory=routes.map(route=>{
  const candidates=[...pageSources].filter(([file,text])=>text.includes('function '+route.component+'(')||text.includes('window.'+route.component+' =')||file.endsWith('/'+(route.id.startsWith('super-')?'super':aliases[route.id]||route.id)+'.jsx'));
  return {...route,files:candidates.map(([f])=>f),sourceControls:candidates.reduce((sum,[,text])=>sum+(text.match(/<(?:Button|button|input|select|textarea|summary)\b/g)||[]).length,0),existingTests:tests.filter(t=>candidates.some(([file])=>t.text.includes(file)||t.text.includes(path.basename(file)))).map(t=>t.file)};
});
const registered={registeredAt:startedAt,goal:'goal-1c7c93e5b99d9351',version:2,criteria:['criterion-host-4-3b89d1464cd9','criterion-host-8-339e6a10c4c9'],source:'Current navigation map, actual App shell and source component contracts; additive UI/accessibility/responsiveness gates.',assertions:['All 24 routes render meaningful heading/body without uncaught framework errors','1440x900,1024x700,390x700 document width <= viewport + 1px','Each route has keyboard-focusable control and Tab focus remains in visible DOM','Keyboard search and mobile navigation open/close restore focus','Read-only modal creation/cancel and invalid/blocked action feedback is visible','All four resource-form required-field errors have role=alert or live announcement','After validation, dialog buttons remain reachable by scrolling and Escape returns focus','After validation, scrolling and focusing, all dialog headings and close controls remain inside panel and viewport, with close center unobscured','Dynamic reduced motion settles global engines with at least one actual Chart instance','Synthetic statistics filtering and downloaded Excel retain zero versus missing values','External requests never pass through; every POST/PUT/PATCH/DELETE is blocked','Only the deliberately injected HTTP 503 may appear in error console','Source snapshots do not drift during a run'],inventory};
fs.writeFileSync(path.join(out,'frontend-registered-checks.json'),JSON.stringify(registered,null,2));
fs.writeFileSync(path.join(out,'frontend-registered-checks-'+stamp+'.json'),JSON.stringify(registered,null,2));
const origin='http://jkxt.site-acceptance.test',results=[],requests=[],writeAttempts=[],external=[],pageErrors=[],consoleErrors=[],missingAssets=[],screenshots=[],unknownGets=[],validationAnnouncements=[],dialogHeaders=[],dialogStages=[],injectedFailures=[];
let browser,context,page,mode='admin',activeRoute='dashboard',failPath='',scenario='empty';
const profile=()=>({onboarded:true,user:{id:'synthetic-user',displayName:'验收人员（合成）',username:'fixture-only',role:mode==='super'?'SUPER_ADMIN':'ADMIN'},org:{id:'synthetic-school',name:'前端验收学校（合成）',slug:'qa-only'},term:{id:'synthetic-term',name:'合成学期',academicYear:'2026-2027',termNo:1,startDate:'2026-09-01',endDate:'2027-01-31'}});
function fixture(url){
  const key=url.pathname.replace(/^\/api\/v1/,'');
  if(key==='/stats/supervise'&&scenario==='statistics')return {rows:[{name:'合成教师甲',code:'QA-001',grade:'高一',subjects:'语文',sub:'验收合成',count:0,hours:null,allowance:0},{name:'合成教师乙',code:'QA-002',grade:'高二',subjects:'数学',sub:'验收合成',count:3,hours:4.5,allowance:150}]};
  const dict={
    '/auth/me':profile(),'/auth/me/preferences':{version:1,theme:{palette:'indigo',mode:'light',density:'comfortable'}},'/org/settings/appearance':{version:1,defaultPalette:'indigo'},'/org/settings':{allowanceUnit:null},'/terms':[], '/terms/current':null,
    '/teachers':[], '/classrooms':[], '/classes':[], '/subjects':[], '/exam-tasks':[], '/exam-plans':[], '/approval-requests':[], '/emergency-changes':[], '/notifications':[],
    '/operations/readiness':{generatedAt:'2026-09-07T09:00:00Z',totals:{activePlans:0,activeEmergencies:0},issues:[],ready:true,blockingCount:0},
    '/timeline/schedule':{days:[],rows:[],calendarDays:[],availableYears:[]},'/stats/history':{rows:[],trend:{labels:[],data:[]},byGrade:[]},'/stats/supervise':{rows:[]},
    '/ai/providers':{providers:[],catalog:[]},
    '/admin/tenants':[], '/admin/accounts':[], '/admin/announcements':[], '/admin/audit-logs':[], '/admin/system-health':{},
    '/admin/security/overview':{failedLogins:null,suspiciousIps:null,activeRules:null,lastBackup:null,threats:[],backups:[]},'/admin/platform-settings':{emailNotif:false,twoFactor:false,guestAccess:false,autoBackup:false,maintenance:false},
  };
  if(Object.hasOwn(dict,key))return dict[key];
  unknownGets.push({route:activeRoute,path:key});return [];
}
async function check(name,fn){try{const details=await fn();results.push({name,pass:true,details});console.log('PASS '+name);}catch(e){results.push({name,pass:false,error:String(e.stack||e).slice(0,2600)});console.log('FAIL '+name+': '+e.message);try{await page?.evaluate(()=>{window.ui?.closeModal();window.ui?.closeDrawer();});await page?.waitForTimeout(160);}catch{}}}
async function screenshot(name){const file=path.join(shots,name+'.png');await page.screenshot({path:file,fullPage:false});screenshots.push(file);return file;}
async function captureDialogStage(dialog,route,width,stage){
  const value=await dialog.evaluate((panel,{route,width,stage})=>{
    const scroll=e=>({tag:e.tagName,cls:e.className,scrollTop:e.scrollTop,scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,overflow:getComputedStyle(e).overflow});
    const ancestors=[];for(let e=panel;e;e=e.parentElement)ancestors.push(scroll(e));
    const close=panel.querySelector('button[aria-label="关闭"]'),closeRect=close.getBoundingClientRect(),hit=document.elementFromPoint(closeRect.x+closeRect.width/2,closeRect.y+closeRect.height/2);
    return {route,width,stage,viewport:{width:innerWidth,height:innerHeight},panel:panel.getBoundingClientRect().toJSON(),title:panel.querySelector('h3').getBoundingClientRect().toJSON(),close:closeRect.toJSON(),closeUnobscured:close===hit||close.contains(hit),scrollTop:panel.scrollTop,overflow:getComputedStyle(panel).overflow,ancestors,content:[...panel.children].map(scroll)};
  },{route,width,stage});dialogStages.push(value);return value;
}
async function open(route){
  activeRoute=route.id;
  if(mode!==route.mode||!page.url().startsWith(origin)){mode=route.mode;await page.goto(origin+'/Dashboard.html?mode='+mode+'&page='+route.id);}
  else await page.evaluate(id=>window.navigateTo(id),route.id);
  await page.waitForSelector('.app-shell[data-page-id="'+route.id+'"]',{timeout:20000});
  await page.waitForTimeout(200);await page.evaluate(()=>document.fonts.ready);
}
async function metrics(){return page.evaluate(()=>{
  const main=document.querySelector('#main-content')||document.querySelector('main'),rect=e=>e.getBoundingClientRect();
  const controls=[...main.querySelectorAll('button,a[href],input,select,textarea,summary')].filter(e=>!e.disabled&&e.tabIndex>=0&&rect(e).width&&rect(e).height&&getComputedStyle(e).visibility!=='hidden');
  const overflow=[...main.querySelectorAll('*')].filter(e=>rect(e).width&&rect(e).right>innerWidth+1&&!e.closest('[role="dialog"]')).slice(0,12).map(e=>({tag:e.tagName,cls:e.className?.baseVal||e.className,text:(e.textContent||'').trim().slice(0,50),right:rect(e).right}));
  return {title:document.title,heading:main.querySelector('h1,h2')?.textContent,textLength:(main.innerText||'').length,width:innerWidth,doc:document.documentElement.scrollWidth,mainWidth:rect(main).width,controls:controls.length,overflow};
});}
async function keyboardFocus(){
  const main=page.locator('#main-content'),first=main.locator('button:not([disabled]),a[href],input:not([disabled]),select:not([disabled]),textarea:not([disabled]),summary').filter({visible:true}).first();
  if(!await first.count())return {state:'no enabled content control; global navigation remains available'};
  await first.focus();await page.keyboard.press('Tab');
  return page.evaluate(()=>{const e=document.activeElement,r=e.getBoundingClientRect();return {tag:e.tagName,label:e.getAttribute('aria-label')||e.textContent?.trim().slice(0,80)||e.placeholder,connected:e.isConnected,hidden:!!e.closest('[inert],[aria-hidden="true"]'),width:r.width,height:r.height};});
}
(async()=>{
  browser=await chromium.launch({headless:true,...(chromiumExecutable?{executablePath:chromiumExecutable}:{})});environment.browserVersion=browser.version();context=await browser.newContext({viewport:{width:1440,height:900},locale:'zh-CN',reducedMotion:'no-preference'});
  await context.route('**/*',async route=>{
    const req=route.request(),url=new URL(req.url());requests.push({url:url.href,method:req.method(),route:activeRoute});
    if(url.origin!==origin){external.push(url.href);await route.abort('blockedbyclient');return;}
    if(url.pathname.startsWith('/api/')){
      if(req.method()!=='GET'){writeAttempts.push({route:activeRoute,method:req.method(),path:url.pathname});await route.fulfill({status:403,contentType:'application/json',body:JSON.stringify({message:'独立前端验收已阻止后台写入',code:'FRONTEND_FIXTURE_WRITE_BLOCKED'})});return;}
      if(failPath&&url.pathname.includes(failPath)){injectedFailures.push({route:activeRoute,path:url.pathname,status:503});await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({message:'合成接口暂不可用，请重试',code:'FIXTURE_UNAVAILABLE'})});return;}
      await route.fulfill({contentType:'application/json',body:JSON.stringify(fixture(url))});return;
    }
    if(url.pathname==='/Dashboard.html'||url.pathname==='/'){await route.fulfill({contentType:'text/html',body:html});return;}
    if(compiled.has(url.pathname)){await route.fulfill({contentType:'application/javascript',body:compiled.get(url.pathname)});return;}
    const relative=decodeURIComponent(url.pathname).replace(/^\//,''),file=path.resolve(root,relative);
    if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()){missingAssets.push(relative);await route.fulfill({status:404,body:'Missing local fixture asset'});return;}
    const mime={'.js':'application/javascript','.css':'text/css','.svg':'image/svg+xml','.woff2':'font/woff2','.png':'image/png','.jpg':'image/jpeg','.gif':'image/gif','.mp4':'video/mp4','.webp':'image/webp','.json':'application/json'};
    await route.fulfill({contentType:mime[path.extname(file)]||'application/octet-stream',body:read(relative)});
  });
  page=await context.newPage();page.setDefaultTimeout(6000);page.on('pageerror',e=>pageErrors.push({route:activeRoute,error:String(e)}));page.on('console',message=>{if(message.type()==='error')consoleErrors.push({route:activeRoute,text:message.text()});});
  await check('route contract contains 24 pages and all current sources compile',async()=>{assert.equal(routes.length,24);assert.ok(compiled.size>=35);return {routes:routes.length,compiled:compiled.size,inventory};});
  for(const route of routes){
    await check('route '+route.id+' renders, responds to keyboard, and has no new runtime error',async()=>{
      const errors=pageErrors.length;await page.setViewportSize({width:1440,height:900});await open(route);const value=await metrics();
      assert.ok(value.textLength>30,JSON.stringify(value));assert.ok(value.heading,JSON.stringify(value));assert.match(value.title,/Quick 排监考/);assert.equal(pageErrors.length,errors,JSON.stringify(pageErrors.slice(errors)));
      const focus=await keyboardFocus();if(focus.connected!==undefined){assert.equal(focus.connected,true);assert.equal(focus.hidden,false);assert.ok(focus.width>0&&focus.height>0);}
      await page.evaluate(()=>document.querySelector('#main-content').scrollTo(0,0));await screenshot(route.id+'-1440');return {value,focus};
    });
    for(const size of [{width:1440,height:900},{width:1024,height:700},{width:390,height:700}])await check('responsive '+route.id+' '+size.width+'x'+size.height,async()=>{
      await page.setViewportSize(size);await page.waitForTimeout(90);const value=await metrics();if(size.width===390)await screenshot(route.id+'-390');assert.ok(value.doc<=size.width+1,JSON.stringify(value));assert.ok(value.mainWidth>100,JSON.stringify(value));return value;
    });
  }
  await check('mobile navigation opens with keyboard, Escape restores trigger, route navigation closes drawer',async()=>{
    await page.setViewportSize({width:390,height:700});await open(routes.find(r=>r.id==='dashboard'));
    const trigger=page.getByRole('button',{name:'打开导航',exact:true});await trigger.focus();await page.keyboard.press('Enter');await page.waitForSelector('.app-shell.nav-open');
    await page.keyboard.press('Escape');await page.waitForFunction(()=>!document.querySelector('.app-shell').classList.contains('nav-open'));assert.equal(await trigger.evaluate(e=>e===document.activeElement),true);
    await trigger.click();await page.locator('#primary-sidebar').getByRole('button',{name:'考场与座位',exact:true}).click();await page.waitForSelector('.app-shell[data-page-id="classrooms"]');assert.equal(await page.locator('.app-shell.nav-open').count(),0);
  });
  await check('global command palette opens by keyboard, search filters, Escape restores focus',async()=>{
    await page.setViewportSize({width:1440,height:900});await open(routes[0]);const trigger=page.getByRole('button',{name:'打开全局搜索',exact:true});await trigger.focus();await page.keyboard.press('Enter');await page.getByRole('dialog').waitFor();
    const input=page.getByRole('combobox');await input.fill('考场');await page.waitForTimeout(120);assert.ok((await page.getByRole('listbox').innerText()).includes('考场'));await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'detached'});assert.equal(await trigger.evaluate(e=>e===document.activeElement),true);
  });
  for(const form of [{route:'people',button:'新增教师',error:'工号和姓名为必填项'},{route:'classrooms',button:'新增教室',error:'编号和名称为必填项'},{route:'classes',button:'新增班级',error:'班级编号、名称和年级为必填项'},{route:'subjects',button:'新增科目',error:'科目名称为必填项'}]){
    for(const width of [1440,390])await check(`form ${form.route} ${width}px opens, validates empty input, Escape returns focus without writes`,async()=>{
      await page.setViewportSize({width,height:width===1440?900:700});await open(routes.find(r=>r.id===form.route));
      const trigger=page.locator('#main-content').getByRole('button',{name:form.button,exact:true}).first();await trigger.focus();await page.keyboard.press('Enter');
      const dialog=page.getByRole('dialog');await dialog.waitFor();await page.waitForTimeout(220);assert.equal(await dialog.count(),1);
      const submit=dialog.getByRole('button',{name:'确认新增',exact:true});await submit.scrollIntoViewIfNeeded();const box=await submit.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width+1);await submit.click();
      await dialog.getByText(form.error,{exact:true}).waitFor();assert.deepEqual(writeAttempts,[]);
      await captureDialogStage(dialog,form.route,width,'immediate-validation');
      if(width===390)await screenshot(`form-${form.route}-${width}-immediate-validation`);
      const announced=await dialog.getByText(form.error,{exact:true}).evaluate(e=>({role:e.getAttribute('role'),live:e.getAttribute('aria-live')}));validationAnnouncements.push({route:form.route,width,error:form.error,...announced});
      const fields=await dialog.locator('input,select,textarea').evaluateAll(nodes=>nodes.map(e=>({tag:e.tagName,name:e.getAttribute('aria-label')||e.getAttribute('aria-labelledby')||(e.labels?.length?[...e.labels].map(l=>l.textContent.trim()).join(' '):''),placeholder:e.placeholder||''})));
      assert.ok(fields.every(field=>field.name),JSON.stringify(fields));
      await submit.scrollIntoViewIfNeeded();const afterError=await submit.boundingBox();assert.ok(afterError.y>=0&&afterError.y+afterError.height<=(width===1440?900:700)+1,JSON.stringify(afterError));
      await captureDialogStage(dialog,form.route,width,'after-scroll');
      if(width===390)await screenshot(`form-${form.route}-${width}-after-scroll`);
      await submit.focus();dialogHeaders.push(await dialog.evaluate((panel,{route,width})=>({route,width,panel:panel.getBoundingClientRect().toJSON(),title:panel.querySelector('h3').getBoundingClientRect().toJSON(),close:panel.querySelector('button[aria-label="关闭"]').getBoundingClientRect().toJSON(),scrollTop:panel.scrollTop,overflow:getComputedStyle(panel).overflow}),{route:form.route,width}));
      await captureDialogStage(dialog,form.route,width,'after-focus');
      await screenshot(`form-${form.route}-${width}`);await page.keyboard.press('Escape');await dialog.waitFor({state:'detached'});assert.equal(await trigger.evaluate(e=>e===document.activeElement),true);return {button:form.button,error:form.error,fields};
    });
  }
  await check('resource form validation messages are announced to assistive technology',async()=>{
    assert.equal(validationAnnouncements.length,8);assert.ok(validationAnnouncements.every(e=>e.role==='alert'||['polite','assertive'].includes(e.live)),JSON.stringify(validationAnnouncements));return validationAnnouncements;
  });
  await check('modal heading and close affordance stay visible after validation and focusing bottom actions',async()=>{
    assert.equal(dialogHeaders.length,8);assert.ok(dialogHeaders.every(e=>e.title.top>=e.panel.top&&e.title.bottom<=e.panel.bottom&&e.close.top>=e.panel.top&&e.close.bottom<=e.panel.bottom),JSON.stringify(dialogHeaders));return dialogHeaders;
  });
  await check('modal heading stays visible at all three uncorrected validation scroll and focus stages',async()=>{
    assert.equal(dialogStages.length,24);const hidden=dialogStages.filter(e=>e.title.top<e.panel.top||e.title.bottom>e.panel.bottom||e.close.top<e.panel.top||e.close.bottom>e.panel.bottom);
    if(hidden.length)fs.writeFileSync(path.join(out,'frontend-modal-stages-'+stamp+'.json'),JSON.stringify(dialogStages,null,2));
    assert.equal(hidden.length,0,JSON.stringify(hidden));return dialogStages;
  });
  await check('modal panels and headings stay inside viewport and close center is unobscured at all stages',async()=>{
    assert.equal(dialogStages.length,24);const failures=dialogStages.filter(e=>!e.closeUnobscured||[e.panel,e.title,e.close].some(r=>r.top<0||r.left<0||r.bottom>e.viewport.height+1||r.right>e.viewport.width+1));
    assert.equal(failures.length,0,JSON.stringify(failures));return dialogStages.map(e=>({route:e.route,width:e.width,stage:e.stage,viewport:e.viewport,closeUnobscured:e.closeUnobscured}));
  });
  await check('exam plan creation empty validation and cancellation leave list unchanged',async()=>{
    await page.setViewportSize({width:390,height:700});await open(routes.find(r=>r.id==='exam-plans'));await page.getByRole('button',{name:'新建计划',exact:true}).click();
    const form=page.getByTestId('create-plan-panel');await form.waitFor();await form.getByRole('button',{name:'创建草稿',exact:true}).click();await form.getByRole('alert').getByText('请填写计划名称',{exact:true}).waitFor();
    assert.deepEqual(writeAttempts,[]);await form.getByRole('button',{name:'取消',exact:true}).click();await form.waitFor({state:'detached'});await screenshot('exam-plan-cancel-390');
  });
  await check('super-admin unavailable bulk imports have explicit visible labels and explanation',async()=>{
    const entries=[];for(const id of ['super-tenants','super-accounts']){await page.setViewportSize({width:1440,height:900});await open(routes.find(r=>r.id===id));const button=page.getByRole('button',{name:'批量导入未接入',exact:true});assert.equal(await button.isDisabled(),true);const title=await button.getAttribute('title');assert.ok(title?.includes('接口'));entries.push({route:id,title});}return entries;
  });
  await check('empty task onboarding opens three-table workflow without creating synthetic tasks',async()=>{
    await page.setViewportSize({width:1440,height:900});await open(routes.find(r=>r.id==='tasks'));await page.getByRole('button',{name:'导入并创建任务',exact:true}).click();await page.waitForSelector('.app-shell[data-page-id="quick"]');assert.deepEqual(writeAttempts,[]);assert.ok((await page.locator('#main-content').innerText()).includes('导入'));
  });
  await check('reduced motion applies mid-session and remains across route changes',async()=>{
    scenario='statistics';await open(routes.find(r=>r.id==='stats-supervise'));await page.waitForFunction(()=>Object.values(window.Chart?.instances||{}).length>0);
    await page.emulateMedia({reducedMotion:'reduce'});await page.waitForFunction(()=>document.documentElement.dataset.motion==='reduced');
    const value=await page.evaluate(()=>({mode:document.documentElement.dataset.motion,charts:Object.values(window.Chart?.instances||{}).map(c=>c.options.animation)}));assert.equal(value.mode,'reduced');assert.ok(value.charts.length>0);assert.ok(value.charts.every(a=>a===false));await page.emulateMedia({reducedMotion:'no-preference'});return value;
  });
  await check('populated synthetic statistics filter and local Excel preserve zero versus missing values',async()=>{
    await page.setViewportSize({width:390,height:700});await page.waitForTimeout(120);const size=await metrics();assert.ok(size.doc<=391,JSON.stringify(size));
    const search=page.locator('#main-content').getByPlaceholder('搜索...',{exact:true});await search.fill('合成教师甲');await page.waitForTimeout(120);assert.equal(await page.locator('#main-content tbody tr').count(),1);
    const pending=page.waitForEvent('download');await page.getByRole('button',{name:'导出报表',exact:true}).click();const download=await pending;const file=path.join(out,'frontend-synthetic-statistics-'+stamp+'.xlsx');await download.saveAs(file);
    const XLSX=require(path.join(root,'assets/vendor/xlsx.full.0.18.5.min.js')),workbook=XLSX.read(fs.readFileSync(file),{type:'buffer'}),rows=XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]],{header:1,defval:null});
    assert.equal(rows.length,2);assert.equal(rows[1][0],'合成教师甲');assert.equal(rows[1][2],0);assert.equal(rows[1][3],null);assert.equal(rows[1][4],0);
    await screenshot('statistics-filtered-390');await search.fill('无此合成教师');await page.getByText('没有符合筛选条件的监考记录',{exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'导出报表',exact:true}).isDisabled(),true);
    await page.getByRole('button',{name:'清除筛选',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('#main-content tbody tr').length===2);scenario='empty';return {download:download.suggestedFilename(),file,rows,synthetic:true};
  });
  await check('resource read error offers visible retry and recovers without phantom data',async()=>{
    failPath='/teachers';await open(routes.find(r=>r.id==='people'));await page.getByText('合成接口暂不可用，请重试',{exact:false}).first().waitFor();
    const retry=page.getByRole('button',{name:/重试|重新加载/}).first();assert.ok(await retry.isEnabled());failPath='';await retry.click();await page.waitForTimeout(200);assert.equal(await page.getByText('合成接口暂不可用，请重试',{exact:false}).count(),0);return {fixture:'GET teachers 503 then empty array'};
  });
  await check('application console has no errors except the deliberately injected unavailable GET',async()=>{
    assert.deepEqual(pageErrors,[]);assert.ok(consoleErrors.every(e=>e.route==='people'&&e.text==='Failed to load resource: the server responded with a status of 503 (Service Unavailable)'),JSON.stringify(consoleErrors));assert.equal(consoleErrors.length,injectedFailures.length);return {injectedFailures,consoleErrors};
  });
  await check('external network has no passthrough, all unexpected writes remain recorded and blocked',async()=>{assert.deepEqual(external,[]);assert.deepEqual(writeAttempts,[]);assert.deepEqual(unknownGets,[]);assert.deepEqual(missingAssets,[]);return {requests:requests.length,unknownGets,missingAssets};});
})().catch(e=>{results.push({name:'harness startup/runtime',pass:false,error:String(e.stack||e)});console.error(e);}).finally(async()=>{
  await browser?.close();const sourceHashes=Object.fromEntries([...sources].map(([f,b])=>[f,digest(b)]));const changedDuringRun=[...sources].filter(([f,b])=>digest(fs.readFileSync(path.join(root,f)))!==digest(b)).map(([f])=>f);
  const report={suite:'full-site-current-source-isolated-chrome',startedAt,finishedAt:new Date().toISOString(),origin,browser:'Chromium isolated headless; not user Chrome',environment,fixtures:'Synthetic empty tenant/super-admin GET responses plus two explicitly synthetic statistics rows. No real user data/backend; all mutations blocked.',results,passed:results.filter(r=>r.pass).length,total:results.length,pageErrors,consoleErrors,writeAttempts,external,missingAssets:[...new Set(missingAssets)],unknownGets,inventory,screenshots,sourceHashes,changedDuringRun};
  const file=path.join(out,'frontend-results-'+stamp+'.json');fs.writeFileSync(file,JSON.stringify(report,null,2));fs.writeFileSync(path.join(out,'frontend-results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({passed:report.passed,total:report.total,file,changedDuringRun},null,2));if(report.passed!==report.total||changedDuringRun.length)process.exitCode=1;
});
