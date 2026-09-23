#!/usr/bin/env node
/*
 * 前端语法门禁。
 *
 * 这个前端没有打包器、没有 lint、没有测试：Dashboard.html 直接用 Babel Standalone
 * 在浏览器里编译 src/ 下的 .jsx/.js。因此一个语法错不会在任何构建步骤里暴露，
 * 而是直接变成用户侧白屏。此脚本用仓库内 vendor 的同一份 Babel（7.29.0）离线编译
 * 全部前端源码，把"白屏"提前成一条命令行报错。
 *
 * 用法：node scripts/check-jsx.js        （退出码 0 = 全部通过）
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const BABEL = path.join(ROOT, 'assets/vendor/babel-standalone.7.29.0.min.js');

if (!fs.existsSync(BABEL)) {
  console.error('找不到 ' + BABEL + '；请确认 assets/vendor/ 完整。');
  process.exit(2);
}

const Babel = require(BABEL);
if (!Babel || typeof Babel.transform !== 'function') {
  console.error('Babel 加载异常，无法校验。');
  process.exit(2);
}

function collect(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) collect(full, out);
    else if (/\.(jsx|js)$/.test(entry.name)) out.push(full);
  }
  return out;
}

const files = collect(path.join(ROOT, 'src'), []);
let failed = 0;

for (const file of files) {
  const rel = path.relative(ROOT, file).split(path.sep).join('/');
  try {
    // 与 Dashboard.html 中 <script type="text/babel"> 的处理保持一致：只用 react preset。
    Babel.transform(fs.readFileSync(file, 'utf8'), { presets: ['react'], filename: rel });
  } catch (err) {
    failed++;
    console.error('FAIL  ' + rel);
    console.error('      ' + String(err.message).split('\n')[0]);
  }
}

console.log('已校验 ' + files.length + ' 个前端文件，失败 ' + failed + ' 个。');
process.exit(failed ? 1 : 0);
