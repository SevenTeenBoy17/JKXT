import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const Babel = require(path.join(root, 'assets/vendor/babel-standalone.7.29.0.min.js'));
const source = fs.readFileSync(path.join(root, 'src/pages/stats-supervise.jsx'), 'utf8');
const compiled = Babel.transform(source, { presets: ['react'] }).code;
const checks = [];
const settle = () => new Promise((resolve) => setImmediate(resolve));
const clean = (value) => JSON.parse(JSON.stringify(value));
function nodes(tree) {
  if (!tree || typeof tree !== 'object') return [];
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return [tree, ...nodes(tree.props?.children), ...nodes(tree.props?.actions)];
}
function text(tree) {
  if (tree == null || typeof tree === 'boolean') return '';
  if (typeof tree !== 'object') return String(tree);
  if (Array.isArray(tree)) return tree.map(text).join(' ');
  return text(tree.props?.children);
}
function harness(get) {
  const slots = [], downloads = [], notices = [];
  let cursor = 0, effects = [];
  const same = (a, b) => a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const context = vm.createContext({ console, window: { api: { get }, ui: { toast: (...args) => notices.push(args) } }, orgName: () => '测试学校' });
  context.window.XLSX = {
    utils: { aoa_to_sheet: (rows) => clean(rows), book_new: () => ({}), book_append_sheet: (book, sheet) => { book.sheet = sheet; } },
    writeFile: (book, name) => downloads.push({ rows: book.sheet, name }),
  };
  context.React = {
    Fragment: 'Fragment',
    createElement: (type, props, ...children) => ({ type, props: { ...props, children: children.flat(Infinity) } }),
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = typeof initial === 'function' ? initial() : initial;
      return [slots[index], (next) => { slots[index] = typeof next === 'function' ? next(slots[index]) : next; }];
    },
    useEffect(fn, deps) {
      const index = cursor++;
      if (!same(slots[index]?.deps, deps)) effects.push(() => {
        slots[index]?.cleanup?.();
        slots[index] = { deps, cleanup: fn() };
      });
    },
  };
  for (const name of ['PageShell', 'Button', 'SectionTitle', 'MiniKPI', 'FilterBar', 'DataTable', 'BarChartJS', 'AvatarChip', ...new Set(source.match(/\bIcon[A-Z]\w*/g))]) context[name] = name;
  vm.runInContext(compiled, context);
  return {
    context, downloads, notices,
    render() {
      cursor = 0;
      const tree = vm.runInContext('StatsSupervisePage()', context);
      const pending = effects; effects = [];
      pending.forEach((effect) => effect());
      return tree;
    },
  };
}
const button = (tree, label) => nodes(tree).find((node) => ['button', 'Button'].includes(node.type) && text(node).includes(label));
const table = (tree) => nodes(tree).find((node) => node.type === 'DataTable');
const chart = (tree) => nodes(tree).find((node) => node.type === 'BarChartJS');
const kpi = (tree, label) => nodes(tree).find((node) => node.type === 'MiniKPI' && node.props.label === label);
async function check(name, run) {
  try { await run(); checks.push({ name, pass: true }); }
  catch (error) { checks.push({ name, pass: false, error: error.message.slice(0, 350) }); }
}

await check('initial loading neither renders nor exports synthetic rows', async () => {
  const h = harness(() => new Promise(() => {}));
  const tree = h.render();
  assert.ok(nodes(tree).some((node) => node.props.role === 'status' && text(node).includes('加载')));
  assert.equal(table(tree), undefined);
  assert.equal(chart(tree), undefined);
  const exportButton = button(tree, '导出报表');
  assert.equal(exportButton.props.disabled, true);
  exportButton.props.onClick();
  assert.equal(h.downloads.length, 0);
});
await check('failed load provides retry and only exposes successful real rows', async () => {
  let calls = 0;
  const row = { name: '真实记录', count: 2, hours: 3, allowance: 12 };
  const h = harness(async () => { if (++calls === 1) throw new Error('offline'); return { rows: [row] }; });
  h.render(); await settle();
  let tree = h.render();
  assert.ok(nodes(tree).some((node) => node.props.role === 'alert'));
  assert.equal(table(tree), undefined);
  button(tree, '导出报表').props.onClick();
  assert.equal(h.downloads.length, 0);
  button(tree, '重试').props.onClick();
  h.render(); await settle(); tree = h.render();
  assert.deepEqual(clean(table(tree).props.rows), [row]);
  assert.equal(calls, 2);
});
await check('permission, expired session, unavailable API and malformed payload are errors', async () => {
  for (const [get, expected] of [
    [async () => { throw Object.assign(new Error(), { status: 403 }); }, '无权'],
    [async () => { throw Object.assign(new Error(), { status: 401 }); }, '登录'],
    [undefined, '不可用'],
    [async () => ({ totals: {} }), '失败'],
    [async () => ({ rows: [null] }), '失败'],
  ]) {
    const h = harness(get); h.render(); await settle(); const tree = h.render();
    assert.ok(nodes(tree).some((node) => node.props.role === 'alert' && text(node).includes(expected)));
    assert.equal(table(tree), undefined);
    assert.equal(button(tree, '导出报表').props.disabled, true);
  }
});
await check('successful empty data is distinct from filtered empty and never exported', async () => {
  const empty = harness(async () => ({ rows: [] })); empty.render(); await settle();
  const blank = empty.render();
  assert.ok(text(blank).includes('暂无监考统计'));
  assert.equal(button(blank, '导出报表').props.disabled, true);
  const h = harness(async () => ({ rows: [{ name: '唯一记录', count: 1, hours: 2, allowance: 0 }] }));
  h.render(); await settle(); let tree = h.render();
  nodes(tree).find((node) => node.type === 'FilterBar').props.onSearch('不存在'); tree = h.render();
  assert.ok(text(tree).includes('没有符合筛选条件'));
  assert.equal(chart(tree), undefined);
  button(tree, '导出报表').props.onClick(); assert.equal(h.downloads.length, 0);
  button(tree, '清除筛选').props.onClick(); tree = h.render();
  assert.equal(table(tree).props.rows.length, 1);
});
await check('zero remains numeric while missing metrics are unavailable in cells and export', async () => {
  const h = harness(async () => ({ rows: [
    { name: '真实零', count: 0, hours: 0, allowance: 0 },
    { name: '未提供', count: null, hours: null, allowance: null },
  ] }));
  h.render(); await settle(); const tree = h.render();
  assert.equal(kpi(tree, '总监考次数').props.value, '—');
  const columns = table(tree).props.columns;
  for (const key of ['count', 'hours', 'allowance', 'avg', 'ratio']) {
    assert.equal(text(columns.find((column) => column.key === key).render(table(tree).props.rows[1])).trim(), '—');
  }
  assert.equal(text(columns.find((column) => column.key === 'count').render(table(tree).props.rows[0])).trim(), '0');
  button(tree, '导出报表').props.onClick();
  assert.deepEqual(h.downloads[0].rows.slice(1).map((row) => row.slice(2)), [[0, 0, 0], [null, null, null]]);
  const zero = harness(async () => ({ rows: [{ name: '真实零', count: 0, hours: 0, allowance: 0 }] }));
  zero.render(); await settle(); assert.equal(kpi(zero.render(), '总监考次数').props.value, 0);
});
await check('metric share, graph, summary and export use the same filtered actual rows', async () => {
  const rows = [{ name: '甲', count: 1, hours: 9, allowance: 0 }, { name: '乙', count: 3, hours: 1, allowance: 10 }];
  const h = harness(async () => ({ rows })); h.render(); await settle(); let tree = h.render();
  button(tree, '监考时长').props.onClick(); h.render(); await settle(); tree = h.render();
  const ratio = table(tree).props.columns.find((column) => column.key === 'ratio');
  assert.equal(text(ratio.render(rows[0])).trim(), '90.0%');
  nodes(tree).find((node) => node.type === 'FilterBar').props.onSearch('乙'); tree = h.render();
  assert.deepEqual(clean(chart(tree).props.labels), ['乙']);
  assert.equal(kpi(tree, '总监考时长').props.value, 1);
  button(tree, '导出报表').props.onClick();
  assert.deepEqual(h.downloads[0].rows.slice(1).map((row) => row[0]), ['乙']);
});
await check('dimension changes hide old rows immediately and ignore obsolete responses', async () => {
  const pending = [];
  const h = harness((route) => new Promise((resolve) => pending.push({ route, resolve })));
  let tree = h.render();
  button(tree, '按学科').props.onClick(); tree = h.render();
  assert.equal(table(tree), undefined);
  assert.equal(button(tree, '导出报表').props.disabled, true);
  pending[1].resolve({ rows: [{ name: '当前学科', count: 3, hours: 2, allowance: 0 }] }); await settle();
  pending[0].resolve({ rows: [{ name: '过期教师', count: 7, hours: 4, allowance: 0 }] }); await settle();
  assert.deepEqual(clean(table(h.render()).props.rows.map((row) => row.name)), ['当前学科']);
  button(h.render(), '按年级').props.onClick();
  assert.equal(table(h.render()), undefined);
});

await check('real spreadsheet serialization preserves numeric zero and blank missing values', async () => {
  const XLSX = require(path.join(root, 'assets/vendor/xlsx.full.0.18.5.min.js'));
  const h = harness(async () => ({ rows: [{ name: '有零值', count: 0, hours: null, allowance: 0 }] }));
  let exported;
  h.context.window.XLSX = { utils: XLSX.utils, writeFile(book) { exported = XLSX.read(XLSX.write(book, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer' }); } };
  h.render(); await settle(); button(h.render(), '导出报表').props.onClick();
  const sheet = exported.Sheets[exported.SheetNames[0]];
  assert.equal(sheet.C2.t, 'n'); assert.equal(sheet.C2.v, 0);
  assert.equal(sheet.D2, undefined);
  assert.equal(sheet.E2.t, 'n'); assert.equal(sheet.E2.v, 0);
});
await check('download failure never produces a success notice', async () => {
  const h = harness(async () => ({ rows: [{ name: '真实记录', count: 1, hours: 1, allowance: 0 }] }));
  h.context.window.XLSX.writeFile = () => { throw new Error('download-failed'); };
  h.render(); await settle(); button(h.render(), '导出报表').props.onClick();
  assert.ok(h.notices.some((notice) => notice[1].kind === 'error'));
  assert.ok(!h.notices.some((notice) => notice[1].kind === 'success'));
});

console.log(JSON.stringify({ contract: 'StatsTruthfulness/v1', checks, passed: checks.filter((item) => item.pass).length, total: checks.length }, null, 2));
if (checks.some((item) => !item.pass)) process.exitCode = 1;
