import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import vm from 'node:vm';
import {execFileSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const destination=path.join(root,'dist');
// Fixed build directory only; never accept an arbitrary deletion target from CLI/env.
if(fs.existsSync(destination)&&fs.lstatSync(destination).isSymbolicLink()) throw new Error('Refusing symlink build directory');
fs.rmSync(destination,{recursive:true,force:true});
fs.mkdirSync(path.join(destination,'compiled'),{recursive:true});
const require=createRequire(import.meta.url);
const babel=require(path.join(root,'assets/vendor/babel-standalone.7.29.0.min.js'));
vm.runInNewContext(fs.readFileSync(path.join(root,'src/babel-script-scope.js'),'utf8'),
  {window:{Babel:babel},document:{querySelectorAll:()=>[]}});
const hash=value=>crypto.createHash('sha256').update(value).digest('hex');
const entries=[];
function emit(name, content, input) {
  const bytes=Buffer.isBuffer(content)?content:Buffer.from(content);
  const file=`compiled/${name.replace(/[^a-zA-Z0-9_-]/g,'-')}.${hash(bytes).slice(0,16)}.${name==='utilities'?'css':'js'}`;
  fs.writeFileSync(path.join(destination,file),bytes);
  entries.push({input,output:file,sha256:hash(bytes)});
  return file;
}
fs.cpSync(path.join(root,'assets'),path.join(destination,'assets'),{recursive:true,
  filter:source=>!/(react(?:-dom)?\.18\.3\.1\.development\.js|babel-standalone\.7\.29\.0\.min\.js|tailwindcss-cdn\.js|xlsx\.full\.0\.18\.5\.min\.js)$/.test(source)});
fs.cpSync(path.join(root,'src/styles'),path.join(destination,'src/styles'),{recursive:true});
fs.copyFileSync(path.join(root,'node_modules/react/LICENSE'),path.join(destination,'REACT-LICENSE.txt'));
const cssTemporary=path.join(destination,'compiled/utilities.css');
// Tailwind v4 Node API (no CLI/watch stack): compile + oxide scanner + lightningcss minify.
{
  const {compile,optimize}=require('@tailwindcss/node');
  const {Scanner}=require('@tailwindcss/oxide');
  const entry=path.join(root,'src/styles/tailwind-build.css');
  const compiler=await compile(fs.readFileSync(entry,'utf8'),{base:path.dirname(entry),onDependency:()=>{}});
  const scanner=new Scanner({sources:compiler.sources});
  fs.writeFileSync(cssTemporary,optimize(compiler.build(scanner.scan()),{minify:true}).code);
}
const css=emit('utilities',fs.readFileSync(cssTemporary),'tailwind.config.cjs + Dashboard.html + src');
fs.unlinkSync(cssTemporary);
let html=fs.readFileSync(path.join(root,'Dashboard.html'),'utf8');
html=html.replace(/<script>\s*tailwind\.config\s*=[\s\S]*?<\/script>/,'');
html=html.replace(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi,(tag, attributes, inline)=>{
  const source=attributes.match(/\bsrc="([^"]+)"/i)?.[1];
  if(!source) return tag;
  const clean=source.split('?')[0];
  if(clean==='assets/vendor/tailwindcss-cdn.js') return `<link rel="stylesheet" href="${css}" />`;
  if(clean.includes('babel-standalone.')||clean==='src/babel-script-scope.js') return '';
  if(/^(?:https?:)?\/\//.test(clean)) throw new Error(`External runtime dependency is forbidden: ${clean}`);
  const input=path.resolve(root,clean);
  if(!input.startsWith(root+path.sep)) throw new Error('Script escapes source root');
  let code;
  if(clean==='assets/vendor/react.18.3.1.development.js') {
    code=fs.readFileSync(path.join(root,'node_modules/react/umd/react.production.min.js'));
  } else if(clean==='assets/vendor/react-dom.18.3.1.development.js') {
    code=fs.readFileSync(path.join(root,'node_modules/react-dom/umd/react-dom.production.min.js'));
  } else {
    code=fs.readFileSync(input,'utf8');
    if(/type="text\/babel"/.test(attributes)) {
      code=babel.transform(code,{filename:clean,presets:['env','react'],plugins:['jkxt-script-scope'],
        compact:true,comments:false,sourceMaps:false}).code;
    }
  }
  const output=emit(path.basename(clean).replace(/\.(js|jsx)$/,''),code,clean);
  return `<script${attributes.replace(/\s*type="text\/babel"/,'').replace(source,output)}></script>`;
});
if(/text\/babel|react(?:-dom)?\.18\.3\.1\.development|tailwind\.config|babel-standalone/.test(html)) throw new Error('Development runtime leaked into build');
fs.writeFileSync(path.join(destination,'Dashboard.html'),html);
fs.writeFileSync(path.join(destination,'build-manifest.json'),JSON.stringify({schema:'jkxt-frontend-build/v1',
  htmlSha256:hash(html),lockSha256:hash(fs.readFileSync(path.join(root,'package-lock.json'))),entries},null,2)+'\n');
console.log(`Built ${entries.length} content-addressed assets; no browser JSX/Tailwind compilation.`);
