import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const require=createRequire(import.meta.url);
const babel=require(path.join(root,'assets/vendor/babel-standalone.7.29.0.min.js'));
const loader=path.join(root,'src/babel-script-scope.js');
const tags=[{dataset:{}},{dataset:{plugins:'existing-plugin'}}];
let plugins=[];
if(fs.existsSync(loader)){
  vm.runInNewContext(fs.readFileSync(loader,'utf8'),{window:{Babel:babel},document:{querySelectorAll:()=>tags}});
  plugins=['jkxt-script-scope'];
}
const compile=source=>babel.transform(source,{presets:['env','react'],plugins}).code;
const context=vm.createContext({window:{},React:{createElement:(type,props,...children)=>({type,props:{...props,children}})},console});
const common=fs.readFileSync(path.join(root,'src/common.jsx'),'utf8');
vm.runInContext(compile(common),context);
// Babel emits another _excluded binding for a later page's object-rest parameter.
// Run it in the SAME global environment, matching Dashboard's classic scripts.
vm.runInContext(compile('function LaterStatus({status,...props}) {return props;}'),context);
const button=vm.runInContext('Button({icon:function Icon(){},loading:true,danger:true,onClick:function action(){},"aria-label":"保存",children:"保存"})',context);
assert.equal(button.props.icon,undefined,'component-only icon must never leak into DOM after later scripts');
assert.equal(button.props.loading,undefined,'component-only loading must never leak');
assert.equal(button.props.danger,undefined,'component-only danger must never leak');
assert.equal(button.props.disabled,true);
assert.equal(button.props['aria-busy'],'true');
assert.equal(button.props['aria-label'],'保存');
assert.equal(typeof button.props.onClick,'function');
assert.equal(vm.runInContext('LaterStatus({status:"ready",id:"real"}).status',context),undefined);
assert.equal(vm.runInContext('LaterStatus({status:"ready",id:"real"}).id',context),'real');
assert.equal(tags[0].dataset.plugins,'jkxt-script-scope');
assert.equal(tags[1].dataset.plugins,'existing-plugin,jkxt-script-scope');
const html=fs.readFileSync(path.join(root,'Dashboard.html'),'utf8');
assert.ok(html.indexOf('src/babel-script-scope.js')>html.indexOf('src/app.jsx'),'register after all JSX tags exist, before DOMContentLoaded');
console.log('PASS: shared classic scripts preserve public components and isolate generated rest-key arrays; button states/actions and existing plugins preserved.');
