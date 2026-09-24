// Initialize real supplied scenario tracking, never manufacture execution evidence.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { hash, collectSourceSnapshot } from './check-release.mjs';
import { validateExecutionPackage } from './execution-package.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const packagePath = 'docs/release/execution-package.json';
const bytes = fs.readFileSync(path.join(root, packagePath));
const pkg = validateExecutionPackage(root, JSON.parse(bytes.toString('utf8')));
const gitSha = execFileSync('git', ['rev-parse','HEAD'], { cwd:root, encoding:'utf8' }).trim();
const phases = { ENV:'G1/G2', AUTH:'G2', PERM:'G2', DATA:'G3', IMPORT:'G3', SOLVE:'G4',
  PUBLISH:'G4', ASYNC:'G5', NOTIFY:'G5', REPORT:'G6', AI:'G5', OPS:'G6', FRONT:'G7', RELEASE:'G8' };
const requiresSeparateApproval = new Set(['NOTIFY-02','AI-02','OPS-05','RELEASE-06']);
const ledger = {
  schema:'JKXTScenarioLedger/v1', initializedAt:new Date().toISOString(), gitSha,
  sourceDigest:collectSourceSnapshot(root).sha256, executionPackage:{path:packagePath,sha256:hash(bytes)},
  meaning:'All entries are required acceptance work. NOT_RUN is not PASS or scope exclusion. Candidate references in SCENARIO-COVERAGE-REVIEW-20260924.md are static pointers, not executed coverage.',
  scenarios:pkg.scenarios.map(s => ({ id:s.id, title:s.title, group:s.group, phase:phases[s.group] ?? 'UNASSIGNED',
    scope:'REQUIRED', status:'NOT_RUN', source:{path:s.sourcePath,line:s.sourceLine},
    staticMapping:'docs/release/SCENARIO-COVERAGE-REVIEW-20260924.md',
    authorization:requiresSeparateApproval.has(s.id) ? 'Separate approval required before real external sending, paid calls or production actions; no silent exclusion' : 'Isolated development/test only',
    execution:{testIds:[],commands:[],environment:null,candidateSha:null,sourceDigest:null,exitCode:null,
      assertions:[],reports:[],defects:[],retests:[]},
    notes:s.id === 'SOLVE-03' ? ['Prior unit red/green evidence exists for truthful UNKNOWN; bind a fresh run explicitly before changing scenario status.'] : [] }))
};
const output = path.join(root,'docs/release/scenario-ledger.json');
fs.writeFileSync(output,JSON.stringify(ledger,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({status:'INITIALIZED_NOT_RUN',scenarios:ledger.scenarios.length,
  businessTestsExecuted:0,artifact:'docs/release/scenario-ledger.json',sha256:hash(fs.readFileSync(output))},null,2));
