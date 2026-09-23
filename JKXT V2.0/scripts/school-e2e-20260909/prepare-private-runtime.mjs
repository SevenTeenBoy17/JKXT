import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';

const privateRoot='C:/Users/nuoya/AppData/Local/JKXT/runtime/school-e2e-20260909';
const target=path.join(privateRoot,'runtime.env');
const attachment='C:/Users/nuoya/.codex/attachments/2eb0200f-2fc0-4898-8921-3a664b84b571/pasted-text.txt';
const key=fs.readFileSync(attachment,'utf8').match(/API秘钥[：:]\s*(sk-[A-Za-z0-9._-]+)/)?.[1];
if(!key)throw new Error('No supplied key. Nothing written.');
if(fs.existsSync(target))throw new Error('Private configuration already exists; refusing overwrite');
fs.mkdirSync(privateRoot,{recursive:true});
const account=execFileSync('whoami.exe',[],{encoding:'utf8'}).trim();
execFileSync('icacls.exe',[privateRoot,'/inheritance:r','/grant:r',`${account}:(OI)(CI)F`,'SYSTEM:(OI)(CI)F'],{stdio:'pipe'});
// Generated secret material is local-only, outside repository and HTTP roots.
fs.writeFileSync(target,`SCHOOL_DB_PASSWORD=${crypto.randomBytes(24).toString('hex')}\nSCHOOL_LLM_API_KEY=${key}\n`,{flag:'wx'});
console.log(JSON.stringify({prepared:true,privateEnvironmentPath:target,credentialSource:'user attachment',keyPrinted:false}));
