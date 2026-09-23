// Isolated real Chrome download test. Synthetic logs, no server or business API.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium}=require('C:/Users/nuoya/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..'),out=path.join(root,'artifacts/frontend-polish-20260907');
const source=fs.readFileSync(path.join(root,'src/pages/quick-step3.jsx'),'utf8');
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
const Babel=require(path.join(root,'assets/vendor/babel-standalone.7.29.0.min.js'));
const compiled=Babel.transform(source,{presets:['react']}).code;
const icons=Babel.transform(fs.readFileSync(path.join(root,'src/icons.jsx'),'utf8'),{presets:['react']}).code;
const fixture="window.ui={toast:(msg,opts)=>{document.getElementById('status').textContent=msg;}};ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(QkCollapsibleLog,{logs:[{t:'09:01:02',text:'合成中文日志：张老师 ✓'}],running:false,backendActive:true}));";
const html='<html lang="zh-CN"><head><meta charset="utf-8"></head><body><div id="root"></div><p id="status" role="status"></p><script src="/react.js"></script><script src="/react-dom.js"></script><script src="/icons.js"></script><script src="/component.js"></script><script src="/fixture.js"></script></body></html>';
const routes=new Map([['/',html],['/react.js',fs.readFileSync(path.join(root,'assets/vendor/react.18.3.1.development.js'))],['/react-dom.js',fs.readFileSync(path.join(root,'assets/vendor/react-dom.18.3.1.development.js'))],['/icons.js',icons],['/component.js',compiled],['/fixture.js',fixture]]);
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const context=await browser.newContext({acceptDownloads:true});
 const page=await context.newPage(),errors=[],blocked=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',route=>{const url=new URL(route.request().url());if(url.origin!=='http://jkxt.log.test'||!routes.has(url.pathname)){blocked.push(url.pathname);return route.abort();}return route.fulfill({status:200,contentType:url.pathname==='/'?'text/html; charset=utf-8':'text/javascript; charset=utf-8',body:routes.get(url.pathname)});});
 const report={schema:'CurrentLogDownloadChrome/v1',startedAt:new Date().toISOString(),sourceSha256:sha(source),synthetic:true,checks:[]};
 try{
  await page.goto('http://jkxt.log.test/');await page.getByRole('button',{name:/日志/}).first().click();
  const pending=page.waitForEvent('download');await page.getByRole('button',{name:'下载当前日志',exact:true}).click();const download=await pending;
  assert.match(download.suggestedFilename(),/^quick-backend-current-logs-.*\.log$/);
  const target=path.join(out,'synthetic-current-log.log');await download.saveAs(target);const bytes=fs.readFileSync(target);
  assert.deepEqual([...bytes.subarray(0,3)],[239,187,191]);const text=bytes.toString('utf8');
  assert.match(text,/合成中文日志：张老师 ✓/);assert.match(text,/仅当前页面已显示的日志，非后端完整日志/);
  assert.match(await page.locator('#status').innerText(),/已发起当前日志下载/);
  await page.waitForTimeout(1100);assert.equal(await page.locator('a[download]').count(),0);assert.equal(errors.length,0);assert.equal(blocked.length,0);
  report.checks.push({name:'Actual browser creates Unicode current-page log file and removes temporary anchor without API',pass:true});
  report.downloadSha256=sha(bytes);report.filename=download.suggestedFilename();report.sourceUnchanged=sha(fs.readFileSync(path.join(root,'src/pages/quick-step3.jsx')))==report.sourceSha256;assert.equal(report.sourceUnchanged,true);
 }catch(e){report.checks.push({name:'Actual browser current log download',pass:false,error:e.message});process.exitCode=1;}
 finally{report.finishedAt=new Date().toISOString();report.pageErrors=errors;report.blockedRequests=blocked;fs.writeFileSync(path.join(out,'log-dom-results.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));await context.close();await browser.close();}
})();
