const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const APP_URL = process.env.APP_URL || 'http://127.0.0.1:18180/Dashboard.html';
const API_BASE = (process.env.API_BASE || 'http://127.0.0.1:18180/api/v1').replace(/\/+$/, '');
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve('screenshots');

fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

async function screenshot(page, name) {
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, name), fullPage: true });
}

async function waitForCaptcha(page) {
  let captcha;
  const response = await page.waitForResponse(
    (res) => res.url().includes('/auth/captcha') && res.status() === 200,
    { timeout: 20000 }
  ).catch(() => null);
  if (response) captcha = await response.json();
  if (!captcha?.debugAnswer) {
    const refresh = page.locator('button[aria-label="Refresh captcha"]');
    const retry = page.waitForResponse((res) => res.url().includes('/auth/captcha') && res.status() === 200, { timeout: 20000 });
    await refresh.click();
    captcha = await (await retry).json();
  }
  return captcha;
}

async function login(page, username, password, beforeShot, afterShot) {
  await page.addInitScript((apiBase) => {
    localStorage.setItem('jkxt:apiBase', apiBase);
  }, API_BASE);
  const captchaPromise = waitForCaptcha(page);
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('input', { timeout: 30000 });
  const captcha = await captchaPromise;
  await screenshot(page, beforeShot);

  const inputs = page.locator('input');
  await inputs.nth(0).fill(username);
  await inputs.nth(1).fill(password);
  await inputs.nth(2).fill(captcha.debugAnswer);
  await page.locator('button[type="submit"]').click();
  await page.waitForFunction(() => window.__jkxtMe && window.__jkxtMe.user, null, { timeout: 40000 });
  await screenshot(page, afterShot);
}

async function createScheduledTask(page) {
  return await page.evaluate(async () => {
    window.api.setBase(localStorage.getItem('jkxt:apiBase'));
    const task = await window.api.post('/exam-tasks', { name: 'P11 Playwright E2E task', source: 'quick' });
    for (const kind of ['teacher', 'classroom', 'exam']) {
      const form = new FormData();
      form.append('kind', kind);
      form.append('sample', 'true');
      await window.api.request(`/exam-tasks/${task.id}/uploads`, { method: 'POST', body: form });
    }
    const validation = await window.api.post(`/exam-tasks/${task.id}/imports/validate`, {});
    const start = await window.api.post(`/exam-tasks/${task.id}/schedule-jobs`, {});
    let job = null;
    for (let i = 0; i < 35; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 700));
      job = await window.api.get(`/schedule-jobs/${start.jobId}`);
      if (['succeeded', 'failed', 'infeasible'].includes(job.status)) break;
    }
    if (!job || job.status !== 'succeeded') throw new Error(`schedule job did not succeed: ${job && job.status}`);
    const assignments = await window.api.get(`/exam-tasks/${task.id}/assignments`);
    localStorage.setItem('jkxt:taskDetailId', task.id);
    return { task, validation, job, assignments };
  });
}

async function editAndPublish(page, workflow) {
  return await page.evaluate(async ({ taskId, assignments }) => {
    const teachers = await window.api.get('/teachers', { status: 'active' });
    const first = assignments.rows[0];
    let patch = null;
    for (const teacher of teachers) {
      const teacherId = teacher.uuid || teacher.id;
      if (!teacherId || teacherId === first.teacherId) continue;
      const check = await window.api.post(`/tasks/${taskId}/schedule/check-conflicts`, {
        assignmentId: first.assignmentId,
        teacherId,
      });
      if (check.allowed) {
        patch = await window.api.patch(`/tasks/${taskId}/schedule`, {
          assignmentId: first.assignmentId,
          teacherId,
          expectedRevision: Number(assignments.stats.revisionCursor || 0),
          reason: 'P11 Playwright E2E reassignment',
        });
        break;
      }
    }
    if (!patch) throw new Error('no conflict-free teacher found for E2E reassignment');
    const published = await window.api.post(`/tasks/${taskId}/publish`, {});
    return { patch, published };
  }, { taskId: workflow.task.id, assignments: workflow.assignments });
}

async function navigateAndShot(page, pageId, shot) {
  await page.evaluate((target) => window.navigateTo(target), pageId);
  await page.waitForTimeout(1600);
  await screenshot(page, shot);
}

(async () => {
  const launchOptions = { headless: true };
  if (process.env.PLAYWRIGHT_CHROME_CHANNEL) launchOptions.channel = process.env.PLAYWRIGHT_CHROME_CHANNEL;
  const browser = await chromium.launch(launchOptions);
  try {
    const adminContext = await browser.newContext({ viewport: { width: 1440, height: 960 } });
    const adminPage = await adminContext.newPage();
    await login(adminPage, 'jwc.demo', 'Demo@2025', 'p11-e2e-login.png', 'p11-e2e-dashboard.png');

    const workflow = await createScheduledTask(adminPage);
    await navigateAndShot(adminPage, 'quick', 'p11-e2e-quick.png');
    await navigateAndShot(adminPage, 'edit', 'p11-e2e-edit-before.png');
    const edit = await editAndPublish(adminPage, workflow);
    await navigateAndShot(adminPage, 'edit', 'p11-e2e-edit-after-publish.png');
    await navigateAndShot(adminPage, 'history', 'p11-e2e-history.png');

    const superContext = await browser.newContext({ viewport: { width: 1440, height: 960 } });
    const superPage = await superContext.newPage();
    await login(superPage, 'ops.demo', 'Demo@2025', 'p11-e2e-super-login.png', 'p11-e2e-super-dashboard.png');
    await navigateAndShot(superPage, 'super-tenants', 'p11-e2e-super-tenants.png');
    await navigateAndShot(superPage, 'super-security', 'p11-e2e-super-security.png');

    console.log(JSON.stringify({
      status: 'passed',
      taskId: workflow.task.id,
      jobId: workflow.job.jobId,
      assignments: workflow.assignments.rows.length,
      patchRevision: edit.patch.revision,
      publishStatus: edit.published.status,
      screenshots: [
        'p11-e2e-login.png',
        'p11-e2e-dashboard.png',
        'p11-e2e-quick.png',
        'p11-e2e-edit-before.png',
        'p11-e2e-edit-after-publish.png',
        'p11-e2e-history.png',
        'p11-e2e-super-dashboard.png',
        'p11-e2e-super-tenants.png',
        'p11-e2e-super-security.png',
      ],
    }, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
