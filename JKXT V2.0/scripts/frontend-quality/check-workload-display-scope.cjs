// Fixed exact-copy contract for wording-only corrections. Not a browser/API test.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../..');
const startedAt = new Date().toISOString();
const stamp = startedAt.replace(/[:.]/g, '-');
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
const read = file => fs.readFileSync(path.join(root, file));
const text = file => read(file).toString('utf8').replace(/\r\n/g, '\n');
const base = 'artifacts/site-acceptance-20260907/checkpoint/';
const peoplePath = 'src/pages/people.jsx';
const statsPath = 'src/pages/stats-supervise.jsx';
const people = text(peoplePath), stats = text(statsPath);
const peopleBefore = text(base + 'people-pre-workload-scope-20260908.jsx');
const statsBefore = text(base + 'stats-pre-workload-scope-20260908.jsx');
const testHash = hash(fs.readFileSync(__filename));
const specPath = 'artifacts/site-acceptance-20260907/display-scope-spec-20260908.md';
const out = path.join(root, 'artifacts/site-acceptance-20260907/qa-workload-display-artifacts', stamp);
fs.mkdirSync(out, { recursive: true });
const edits = {
  people: [
    ["label: '本学期监考'", "label: '累计监考'"],
    ["l: '本学期监考'", "l: '累计监考'"],
    ['label="本学期总监考" value={totalSup} unit="场次"', 'label="累计监考人次" value={totalSup} unit="人次"'],
    ['维护教师档案、查看个人排班、调整年级与任教科目', '维护教师档案与个人排班。累计监考按本校各任务最近一次成功排班汇总，不随顶部学期筛选，也不代表已发布。'],
    ['回避规则以所选任务的策略和后端冲突校验为准。', '累计监考按本校各任务最近一次成功排班汇总，不随顶部学期筛选，也不代表已发布。回避规则以所选任务的策略和后端冲突校验为准。']
  ],
  stats: [
    ['按学校已保存的监考记录统计工作量、时长与津贴，支持筛选和导出', '按本校最近一次成功排班统计工作量、时长与津贴；不是全学期累计，不随顶部学期筛选，也不代表已发布结果。'],
    ['>学期设置</Button>', '>学校设置</Button>'],
    ['`监考统计_${dimLabel}维度.xlsx`', '`监考统计_最近成功排班_${dimLabel}维度.xlsx`']
  ]
};
function applyExpected(source, changes) {
  for (const [before, after] of changes) {
    assert.equal(source.split(before).length - 1, 1, 'Each expected edit must identify exactly one baseline string: ' + before);
    source = source.replace(before, after);
  }
  return source;
}
const checks = [];
function check(name, fn) {
  try { fn(); checks.push({ name, pass: true }); console.log('PASS ' + name); }
  catch (error) { checks.push({ name, pass: false, error: error.message }); console.log('FAIL ' + name + ': ' + error.message.slice(0, 250)); }
}
const registered = {
  schema: 'WorkloadDisplayCopyContract/v1', startedAt, testHash,
  specification: { path: specPath, sha256: hash(read(specPath)) },
  allowedEdits: edits,
  boundaries: ['No query, arithmetic, state/event handler, filter, export row/column changes', 'No browser, network, database or user session access', 'Original 119/26 tests frozen', 'Exact old-source RED and same-test-hash GREEN required']
};
fs.writeFileSync(path.join(out, 'registered-checks.json'), JSON.stringify(registered, null, 2));
check('people checkpoint matches final empty-roster revision', () => assert.equal(hash(read(base + 'people-pre-workload-scope-20260908.jsx')), '66163bb372c5040ccd0f094291f97f5b965891fc881a0db8e67189e210d08d0d'));
check('statistics checkpoint identity is fixed', () => assert.equal(hash(read(base + 'stats-pre-workload-scope-20260908.jsx')), 'fd23e8a0298c29544d52d87fc25bbaf759e0fada8826ddbb1ada274eac63c831'));
check('people source differs only by five registered wording edits', () => assert.equal(people, applyExpected(peopleBefore, edits.people)));
check('people table, drawer and summary no longer mislabel cumulative assignments as this term', () => {
  assert.ok(!people.includes('本学期监考') && !people.includes('本学期总监考'));
  assert.ok(people.includes("label: '累计监考'") && people.includes("l: '累计监考'") && people.includes('label="累计监考人次" value={totalSup} unit="人次"'));
});
check('people main page and drawer each disclose the same cross-task latest-success scope', () => assert.equal(people.split('累计监考按本校各任务最近一次成功排班汇总，不随顶部学期筛选，也不代表已发布。').length - 1, 2));
check('statistics source differs only by three registered wording edits', () => assert.equal(stats, applyExpected(statsBefore, edits.stats)));
check('statistics scope is latest successful run and not term cumulative or publication', () => assert.ok(stats.includes('按本校最近一次成功排班统计工作量、时长与津贴；不是全学期累计，不随顶部学期筛选，也不代表已发布结果。')));
check('school settings entry keeps its navigation handler and is not a report filter', () => assert.ok(stats.includes("onClick={() => window.navigateTo && window.navigateTo('settings')}>学校设置</Button>")));
check('export filename carries report scope without adding or removing spreadsheet rows', () => assert.ok(stats.includes('`监考统计_最近成功排班_${dimLabel}维度.xlsx`')));
check('original 119 runner remains frozen', () => assert.equal(hash(read('scripts/frontend-quality/check-site-acceptance.cjs')), '580540678d25111f1cef4d3e3f06d72044101104bb34691f2746fed8340b7966'));
check('original 26 empty-roster runner remains frozen', () => assert.equal(hash(read('scripts/frontend-quality/check-people-empty-state.cjs')), 'd8664ecd0b45cd72ff3625c70a190cdc537b54ee9d499af8aea6856776a632d5'));
check('both real JSX pages compile', () => {
  const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
  Babel.transform(people, { presets: ['react'] });
  Babel.transform(stats, { presets: ['react'] });
});
const failed = checks.filter(item => !item.pass).length;
const report = { ...registered, finishedAt: new Date().toISOString(), passed: checks.length - failed, total: checks.length, failed, actualExitCode: failed ? 1 : 0, results: checks, sources: [peoplePath, statsPath].map(file => ({ file, sha256: hash(read(file)) })) };
fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ report: path.join(out, 'results.json'), passed: report.passed, total: report.total, failed, testHash }));
process.exitCode = report.actualExitCode;
