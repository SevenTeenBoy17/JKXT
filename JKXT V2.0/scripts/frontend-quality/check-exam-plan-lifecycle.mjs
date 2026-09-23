import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, '..', '..');
const paths = {
  lifecycle: path.join(projectRoot, 'src', 'pages', 'exam-plans.jsx'),
  theme: path.join(projectRoot, 'src', 'styles', 'theme-system.css'),
  glass: path.join(projectRoot, 'src', 'styles', 'glass-system.css'),
};

const source = Object.fromEntries(
  Object.entries(paths).map(([name, filePath]) => [name, fs.readFileSync(filePath, 'utf8')]),
);

const checks = [];
const contrastResults = [];

function check(id, condition, detail) {
  checks.push({ id, pass: Boolean(condition), detail });
}

function section(text, startMarker, endMarker) {
  const start = text.indexOf(startMarker);
  const end = start < 0 ? -1 : text.indexOf(endMarker, start + startMarker.length);
  if (start < 0 || end < 0) return '';
  return text.slice(start, end);
}

function findMatchingBrace(text, openingIndex) {
  let depth = 0;
  let quote = '';
  let lineComment = false;
  let blockComment = false;

  for (let index = openingIndex; index < text.length; index += 1) {
    const char = text[index];
    const next = text[index + 1];

    if (lineComment) {
      if (char === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (char === '*' && next === '/') {
        blockComment = false;
        index += 1;
      }
      continue;
    }
    if (quote) {
      if (char === '\\') {
        index += 1;
      } else if (char === quote) {
        quote = '';
      }
      continue;
    }
    if (char === '/' && next === '/') {
      lineComment = true;
      index += 1;
      continue;
    }
    if (char === '/' && next === '*') {
      blockComment = true;
      index += 1;
      continue;
    }
    if (char === '\'' || char === '"' || char === '`') {
      quote = char;
      continue;
    }
    if (char === '{') depth += 1;
    if (char === '}') {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  return -1;
}

function ruleBody(css, selector) {
  let selectorIndex = css.indexOf(selector);
  while (selectorIndex >= 0) {
    const afterSelector = css.slice(selectorIndex + selector.length);
    if (selector.endsWith(',') || /^\s*\{/.test(afterSelector)) break;
    selectorIndex = css.indexOf(selector, selectorIndex + selector.length);
  }
  if (selectorIndex < 0) return '';
  const openingIndex = css.indexOf('{', selectorIndex + selector.length);
  if (openingIndex < 0) return '';
  const closingIndex = findMatchingBrace(css, openingIndex);
  return closingIndex < 0 ? '' : css.slice(openingIndex + 1, closingIndex);
}

function declarations(body) {
  const entries = {};
  for (const match of body.matchAll(/--([a-z0-9-]+)\s*:\s*([^;]+);/gi)) {
    entries[`--${match[1]}`] = match[2].trim();
  }
  return entries;
}

function hexToRgb(hex) {
  const normalized = hex.trim().toLowerCase();
  if (!/^#[0-9a-f]{6}$/.test(normalized)) {
    throw new Error(`Unsupported colour value: ${hex}`);
  }
  return [1, 3, 5].map((offset) => Number.parseInt(normalized.slice(offset, offset + 2), 16));
}

function relativeLuminance(hex) {
  const channels = hexToRgb(hex).map((channel) => {
    const value = channel / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return (0.2126 * channels[0]) + (0.7152 * channels[1]) + (0.0722 * channels[2]);
}

function contrastRatio(foreground, background) {
  const foregroundLuminance = relativeLuminance(foreground);
  const backgroundLuminance = relativeLuminance(background);
  return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
    / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05);
}

function mixSrgb(foreground, background, foregroundWeight) {
  const foregroundChannels = hexToRgb(foreground);
  const backgroundChannels = hexToRgb(background);
  const channels = foregroundChannels.map((channel, index) => (
    Math.round((channel * foregroundWeight) + (backgroundChannels[index] * (1 - foregroundWeight)))
  ));
  return `#${channels.map((channel) => channel.toString(16).padStart(2, '0')).join('')}`;
}

function recordContrast(id, foreground, background) {
  const ratio = contrastRatio(foreground, background);
  contrastResults.push({ id, foreground, background, ratio: Number(ratio.toFixed(2)) });
  check(`contrast.${id}`, ratio >= 4.5, `${ratio.toFixed(2)}:1 (${foreground} on ${background})`);
}

const cancelBlock = section(
  source.lifecycle,
  'const cancelPlanning = async () => {',
  '\n\n  const createSuccessor',
);
const cancelGuardMatch = /if\s*\(cancelledStatus\s*===\s*['"]CANCELLED['"]\)\s*\{/.exec(cancelBlock);
const cancelGuardOpen = cancelGuardMatch
  ? cancelBlock.indexOf('{', cancelGuardMatch.index + cancelGuardMatch[0].lastIndexOf('{'))
  : -1;
const cancelGuardClose = cancelGuardOpen >= 0 ? findMatchingBrace(cancelBlock, cancelGuardOpen) : -1;
const confirmedCancelBranch = cancelGuardClose >= 0
  ? cancelBlock.slice(cancelGuardOpen + 1, cancelGuardClose)
  : '';
const beforeConfirmedCancelBranch = cancelGuardOpen >= 0
  ? cancelBlock.slice(0, cancelGuardOpen)
  : cancelBlock;
const nonCancelledBranch = cancelGuardClose >= 0
  ? cancelBlock.slice(cancelGuardClose + 1)
  : '';
const outsideConfirmedCancelBranch = `${beforeConfirmedCancelBranch}\n${nonCancelledBranch}`;
const refMutation = /(?:\+\+\s*)?planningRunRef\.current\s*(?:\+\+|--|\+=|-=|=)/;

check('lifecycle.cancel-block-present', Boolean(cancelBlock), 'cancelPlanning implementation is present');
check(
  'lifecycle.cancel-success-guarded',
  Boolean(cancelGuardMatch)
    && /setMessage\(['"]后端已确认取消求解/.test(confirmedCancelBranch)
    && !/setMessage\(['"]后端已确认取消求解/.test(outsideConfirmedCancelBranch),
  'success copy is reachable only from the CANCELLED branch',
);
check(
  'lifecycle.running-cancel-waits',
  /else\s*\{[\s\S]*setCancelRequested\(true\)[\s\S]*等待后端确认[\s\S]*仍视为运行中/.test(nonCancelledBranch),
  'a non-CANCELLED response stays in the waiting state',
);
check(
  'lifecycle.running-cancel-keeps-polling',
  !refMutation.test(outsideConfirmedCancelBranch)
    && /planningRunRef\.current\s*\+=\s*1/.test(confirmedCancelBranch),
  'only a confirmed CANCELLED response invalidates the active polling run',
);

const monitorBlock = section(
  source.lifecycle,
  'const monitorPlanningJob = async (initialJob, runId) => {',
  '\n\n  const startPlanning',
);
check(
  'lifecycle.auto-poll-pause-truthful',
  /自动查询已暂停/.test(monitorBlock) && /任务仍在后台运行/.test(monitorBlock),
  'auto-poll exhaustion is described as a pause, not a terminal result',
);
check(
  'lifecycle.resume-control',
  /activeJob\s*&&\s*busy\s*!==\s*['"]planning['"][\s\S]{0,180}onClick=\{resumePlanning\}[\s\S]{0,80}继续查询进度/.test(source.lifecycle)
    && /const resumePlanning = async \(\) => \{[\s\S]*monitorPlanningJob\(job, runId\)/.test(source.lifecycle),
  'an active job can resume polling after the automatic pause',
);
check(
  'lifecycle.plan-revision-isolation',
  source.lifecycle.includes('key={`${planId}:${selectedRevisionId}`}'),
  'PlanLifecycleActions is remounted for every plan/revision identity',
);

const rootTokens = declarations(ruleBody(source.theme, ':root'));
const darkTokens = declarations(ruleBody(source.theme, ':root[data-scheme="dark"]'));
const palettes = ['indigo', 'blue', 'green', 'rose', 'cyan', 'purple'];
const paletteAccents = Object.fromEntries(palettes.map((palette) => {
  const selector = `:root[data-scheme="dark"][data-palette="${palette}"]`;
  return [palette, declarations(ruleBody(source.theme, selector))['--accent']];
}));

check(
  'theme.semantic-aliases',
  rootTokens['--surface-brand-soft'] === 'var(--accent-soft)'
    && rootTokens['--ink-soft'] === 'var(--fg-muted)'
    && rootTokens['--text-soft'] === 'var(--fg-muted)',
  'reviewed UI states resolve through semantic theme tokens',
);
check(
  'theme.dark-accent-soft-contract',
  darkTokens['--accent-soft'] === 'color-mix(in srgb, var(--accent) 22%, var(--surface-raised))',
  'dark selected surfaces use the declared 22% sRGB accent mix',
);

const selectedRowRule = ruleBody(
  source.glass,
  '.data-table tbody tr[aria-selected="true"] > td,',
);
check(
  'theme.selected-row-semantic-surface',
  /background\s*:\s*var\(--surface-brand-soft\)\s*!important\s*;/.test(selectedRowRule),
  'selected table rows use --surface-brand-soft',
);
const onboardingRule = ruleBody(source.glass, '.onboarding-kicker,');
check(
  'theme.onboarding-semantic-foreground',
  /color\s*:\s*var\(--ink-soft\)\s*;/.test(onboardingRule),
  'onboarding kicker and progress use --ink-soft',
);

const raised = darkTokens['--surface-raised'];
const muted = darkTokens['--surface-muted'];
const foregroundDefault = darkTokens['--fg-default'];
const foregroundMuted = darkTokens['--fg-muted'];

for (const palette of palettes) {
  const accent = paletteAccents[palette];
  if (!accent || !raised) {
    check(`contrast.dark-accent-${palette}`, false, 'missing dark accent or raised-surface token');
    continue;
  }
  recordContrast(`dark-accent-${palette}`, accent, raised);
}

if (foregroundDefault && raised) {
  for (const palette of palettes) {
    const accent = paletteAccents[palette];
    if (!accent) continue;
    recordContrast(
      `selected-row-${palette}`,
      foregroundDefault,
      mixSrgb(accent, raised, 0.22),
    );
  }
} else {
  check('contrast.selected-row', false, 'missing selected-row semantic tokens');
}

if (foregroundMuted && muted) {
  recordContrast('history-kpi-muted-copy', foregroundMuted, muted);
} else {
  check('contrast.history-kpi-muted-copy', false, 'missing history KPI semantic tokens');
}

if (foregroundMuted && raised) {
  recordContrast('onboarding-kicker-progress', foregroundMuted, raised);
} else {
  check('contrast.onboarding-kicker-progress', false, 'missing onboarding semantic tokens');
}

const failures = checks.filter((entry) => !entry.pass);
const report = {
  contract: 'ExamPlanFrontendLifecycleContract/v1',
  sourceFiles: Object.values(paths).map((filePath) => path.relative(projectRoot, filePath).replaceAll('\\', '/')),
  threshold: { wcag: '2.2 AA', normalTextContrast: 4.5 },
  summary: { passed: checks.length - failures.length, failed: failures.length, total: checks.length },
  checks,
  contrastResults,
};

console.log(JSON.stringify(report, null, 2));
if (failures.length > 0) process.exitCode = 1;
