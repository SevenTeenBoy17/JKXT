const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const appUrlInput = process.env.APP_URL;
const hasExplicitApiBase = Object.prototype.hasOwnProperty.call(process.env, 'API_BASE')
  && String(process.env.API_BASE || '').trim().length > 0;
const apiBaseInput = hasExplicitApiBase ? String(process.env.API_BASE).trim() : '';
const screenshotDir = process.env.SCREENSHOT_DIR || path.resolve('.codex-supervisor', 'browser-smoke');
const skipLogin = process.env.SKIP_LOGIN === '1';
const expectStaticPreview = process.env.EXPECT_STATIC_PREVIEW === '1';
const username = process.env.USERNAME || 'jwc.demo';
const password = process.env.PASSWORD || 'Demo@2025';
const timeoutMs = Number(process.env.TIMEOUT_MS || '45000');
const chromeChannel = process.env.PLAYWRIGHT_CHROME_CHANNEL || '';

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function normalizeDashboardUrl(value) {
  assert(value, 'APP_URL is required');
  const url = new URL(value);
  if (!url.pathname || url.pathname === '/') url.pathname = '/Dashboard.html';
  return url;
}

function normalizeApiBase(value) {
  if (value) return value.replace(/\/+$/, '');
  return '/api/v1';
}

function hasVisibleFailureText(text) {
  const needles = [
    'Failed to fetch',
    '当前静态预览环境',
    '未连接后端',
    '未接通后端',
    '预览模式 · 未接入服务端',
    '仅前端模拟,未接入服务端',
  ];
  return needles.find((needle) => text.includes(needle)) || '';
}

function hasStaticPreviewText(text) {
  const needles = [
    '静态预览 · 未接通后端',
    '当前静态预览环境未连接后端',
    '当前静态预览环境',
    '未连接后端',
    '未接通后端',
  ];
  return needles.find((needle) => text.includes(needle)) || '';
}

async function capture(page, fileName) {
  const file = path.join(screenshotDir, fileName);
  await page.screenshot({ path: file, fullPage: true });
  return file;
}

async function getBodyText(page) {
  return await page.locator('body').innerText({ timeout: 10000 }).catch(() => '');
}

async function waitForCaptchaResponse(page) {
  return await page.waitForResponse(
    (res) => res.url().includes('/auth/captcha') && res.status() === 200,
    { timeout: Math.min(timeoutMs, 20000) }
  ).catch(() => null);
}

async function run() {
  const dashboardUrl = normalizeDashboardUrl(appUrlInput);
  const apiBase = normalizeApiBase(apiBaseInput);
  fs.mkdirSync(screenshotDir, { recursive: true });

  const launchOptions = { headless: true };
  if (chromeChannel) launchOptions.channel = chromeChannel;
  let browser;
  try {
    browser = await chromium.launch(launchOptions);
  } catch (error) {
    if (!chromeChannel) throw error;
    browser = await chromium.launch({ headless: true });
  }

  const consoleErrors = [];
  const requestFailures = [];
  const apiResponses = [];
  const scriptRequests = [];

  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 960 } });
    const page = await context.newPage();
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    page.on('requestfailed', (request) => {
      const url = request.url();
      if (url.includes('/api/v1') || new URL(url).origin === dashboardUrl.origin) {
        requestFailures.push({ url, error: request.failure()?.errorText || '' });
      }
    });
    page.on('request', (request) => {
      if (request.resourceType() === 'script') {
        scriptRequests.push(request.url());
      }
    });
    page.on('response', (response) => {
      const url = response.url();
      if (url.includes('/api/v1')) {
        apiResponses.push({ url, status: response.status() });
      }
    });

    // A fresh browser context must exercise api.js's real same-origin default.
    // Only an explicit API_BASE request may seed the persisted cross-origin
    // override; otherwise this smoke test would hide API-base regressions.
    if (hasExplicitApiBase) {
      await page.addInitScript((base) => {
        localStorage.setItem('jkxt:apiBase', base);
      }, apiBase);
    }

    const captchaPromise = waitForCaptchaResponse(page);
    await page.goto(`${dashboardUrl.href}${dashboardUrl.search ? '&' : '?'}browserSmoke=${Date.now()}`, {
      waitUntil: 'domcontentloaded',
      timeout: timeoutMs,
    });
    await page.waitForSelector('form button[type="submit"], body', { timeout: timeoutMs });
    const captchaResponse = await captchaPromise;
    await page.waitForTimeout(1000);

    const title = await page.title();
    const bodyText = await getBodyText(page);
    const failureText = hasVisibleFailureText(bodyText);
    const apiState = await page.evaluate(() => ({
      apiBase: window.api?.base || null,
      storedApiBase: localStorage.getItem('jkxt:apiBase'),
      hasApiClient: !!window.api,
    }));
    const loginScreenshot = await capture(page, 'browser-ui-login.png');

    assert(title && title.length > 0, 'page title is empty');
    assert(apiState.hasApiClient, 'window.api client was not initialized');
    assert(apiState.apiBase === apiBase, `window.api base mismatch: ${apiState.apiBase}`);
    if (hasExplicitApiBase) {
      assert(apiState.storedApiBase === apiBase, `explicit API base was not persisted: ${apiState.storedApiBase}`);
    } else {
      assert(apiState.apiBase === '/api/v1', `fresh context did not use same-origin API base: ${apiState.apiBase}`);
      assert(apiState.storedApiBase === null, `fresh context was unexpectedly seeded: ${apiState.storedApiBase}`);
    }
    if (expectStaticPreview) {
      const staticPreviewText = hasStaticPreviewText(bodyText);
      assert(staticPreviewText, 'static-preview disconnected status text was not visible');
      assert(!bodyText.includes('系统在线 · 服务正常'), 'service-normal status was visible in static-preview mode');

      const externalScriptUrls = scriptRequests.filter((url) => {
        try {
          return new URL(url).origin !== dashboardUrl.origin;
        } catch {
          return true;
        }
      });
      assert(externalScriptUrls.length === 0, `external script requests observed: ${JSON.stringify(externalScriptUrls)}`);

      console.log(JSON.stringify({
        status: 'passed',
        mode: 'static-preview',
        appUrl: dashboardUrl.origin,
        dashboardUrl: dashboardUrl.href,
        apiBase,
        apiBaseSource: hasExplicitApiBase ? 'explicit-env' : 'auto-same-origin',
        title,
        staticPreviewText,
        captchaResponseObserved: !!captchaResponse,
        screenshots: [loginScreenshot],
        apiResponses,
        requestFailures,
        consoleErrors: consoleErrors.slice(0, 10),
        scriptRequestCount: scriptRequests.length,
        sameOriginScriptCount: scriptRequests.length - externalScriptUrls.length,
        externalScriptUrls,
      }, null, 2));
      return;
    }
    assert(!failureText, `visible failure text found: ${failureText}`);
    assert(captchaResponse, 'backend captcha response was not observed');

    let captchaPayload = null;
    try { captchaPayload = await captchaResponse.json(); } catch {}
    assert(captchaPayload?.captchaId, 'captcha response did not include captchaId');
    assert(!String(captchaPayload.captchaId).startsWith('local-'), 'captcha fell back to local preview mode');

    const result = {
      status: 'passed',
      appUrl: dashboardUrl.origin,
      dashboardUrl: dashboardUrl.href,
      apiBase,
      apiBaseSource: hasExplicitApiBase ? 'explicit-env' : 'auto-same-origin',
      title,
      captchaStatus: captchaResponse.status(),
      captchaIdPrefix: String(captchaPayload.captchaId).slice(0, 8),
      loginChecked: false,
      screenshots: [loginScreenshot],
      apiResponses,
      requestFailures,
      consoleErrors: consoleErrors.slice(0, 10),
    };

    if (!skipLogin) {
      assert(captchaPayload.debugAnswer, 'captcha debugAnswer missing; use -SkipLogin for production or enable UAT captcha debug');
      const inputs = page.locator('input');
      await inputs.nth(0).fill(username);
      await inputs.nth(1).fill(password);
      await inputs.nth(2).fill(captchaPayload.debugAnswer);
      await page.locator('form button[type="submit"]').click();
      await page.waitForFunction(() => window.__jkxtMe && window.__jkxtMe.user, null, { timeout: timeoutMs });
      await page.waitForTimeout(1000);
      const afterLoginText = await getBodyText(page);
      const afterLoginFailure = hasVisibleFailureText(afterLoginText);
      assert(!afterLoginFailure, `visible failure text after login: ${afterLoginFailure}`);
      const authState = await page.evaluate(() => ({
        page: localStorage.getItem('jkxt:page'),
        mode: localStorage.getItem('jkxt:mode'),
        user: window.__jkxtMe?.user?.username || null,
      }));
      assert(authState.user === username, `logged-in user mismatch: ${authState.user}`);
      const dashboardShot = await capture(page, 'browser-ui-dashboard.png');
      await page.evaluate(() => window.navigateTo && window.navigateTo('quick'));
      await page.waitForTimeout(1200);
      const quickText = await getBodyText(page);
      const quickFailure = hasVisibleFailureText(quickText);
      assert(!quickFailure, `visible failure text on quick page: ${quickFailure}`);
      assert(quickText.includes('后端') || quickText.includes('backend'), 'quick page did not show backend-connected copy');
      const quickShot = await capture(page, 'browser-ui-quick.png');
      result.loginChecked = true;
      result.user = authState.user;
      result.page = authState.page;
      result.mode = authState.mode;
      result.screenshots.push(dashboardShot, quickShot);
    }

    const externalScriptUrls = scriptRequests.filter((url) => {
      try {
        return new URL(url).origin !== dashboardUrl.origin;
      } catch {
        return true;
      }
    });
    assert(externalScriptUrls.length === 0, `external script requests observed: ${JSON.stringify(externalScriptUrls)}`);
    result.scriptRequestCount = scriptRequests.length;
    result.sameOriginScriptCount = scriptRequests.length - externalScriptUrls.length;
    result.externalScriptUrls = externalScriptUrls;
    assert(requestFailures.length === 0, `request failures observed: ${JSON.stringify(requestFailures)}`);
    console.log(JSON.stringify(result, null, 2));
  } finally {
    await browser.close();
  }
}

run().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
