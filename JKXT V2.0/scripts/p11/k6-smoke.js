import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<10000'],
  },
};

const BASE_URL = (__ENV.BASE_URL || 'http://host.docker.internal:18180/api/v1').replace(/\/+$/, '');

function jsonHeaders() {
  return { headers: { 'Content-Type': 'application/json' } };
}

function parseJson(res) {
  try {
    return res.json();
  } catch (error) {
    return {};
  }
}

function postJson(path, body) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body || {}), jsonHeaders());
}

export default function () {
  const health = http.get(`${BASE_URL}/health`);
  check(health, { 'health UP': (r) => r.status === 200 && String(r.body).includes('"UP"') });

  const captcha = parseJson(postJson('/auth/captcha', {}));
  const login = postJson('/auth/login', {
    username: 'jwc.demo',
    password: 'Demo@2025',
    captchaId: captcha.captchaId,
    captcha: captcha.debugAnswer,
  });
  check(login, { 'login ok': (r) => r.status === 200 && String(r.body).includes('jwc.demo') });

  check(http.get(`${BASE_URL}/teachers`), { 'teachers list ok': (r) => r.status === 200 });
  check(http.get(`${BASE_URL}/classrooms`), { 'classrooms list ok': (r) => r.status === 200 });
  check(http.get(`${BASE_URL}/stats/history`), { 'history stats ok': (r) => r.status === 200 });
  check(http.get(`${BASE_URL}/stats/supervise`), { 'supervise stats ok': (r) => r.status === 200 });

  const task = parseJson(postJson('/exam-tasks', { name: 'P11 k6 private smoke', source: 'quick' }));
  check({ task }, { 'task created': (v) => !!v.task.id });

  for (const kind of ['teacher', 'classroom', 'exam']) {
    const upload = http.post(`${BASE_URL}/exam-tasks/${task.id}/uploads`, {
      kind,
      sample: 'true',
      file: http.file(new Uint8Array([65]), `${kind}.txt`, 'text/plain'),
    });
    check(upload, { [`${kind} sample upload ok`]: (r) => r.status === 200 && String(r.body).includes('sha256') });
  }

  const validation = http.post(`${BASE_URL}/exam-tasks/${task.id}/imports/validate`);
  check(validation, { 'validation ready': (r) => r.status === 200 && String(r.body).includes('"ready"') });

  const jobStart = parseJson(http.post(`${BASE_URL}/exam-tasks/${task.id}/schedule-jobs`));
  check({ jobStart }, { 'job id returned': (v) => !!v.jobStart.jobId });

  let job;
  for (let i = 0; i < 25; i += 1) {
    sleep(0.7);
    const res = http.get(`${BASE_URL}/schedule-jobs/${jobStart.jobId}`);
    job = parseJson(res);
    if (['succeeded', 'failed', 'infeasible'].includes(job.status)) break;
  }

  check({ job }, {
    'job succeeded': (v) => v.job && v.job.status === 'succeeded',
    'job assignments returned': (v) => v.job && v.job.assignments && v.job.assignments.length > 0,
    'fairness five returned': (v) => v.job && v.job.fairness && v.job.fairness.length >= 5,
  });
}
