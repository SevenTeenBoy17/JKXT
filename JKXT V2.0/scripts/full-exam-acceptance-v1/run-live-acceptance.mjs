#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_DIR = path.resolve(SCRIPT_DIR, '..', '..');
const DATA_ROOT = path.resolve(PROJECT_DIR, 'test-data', 'full-exam-acceptance-v1');
const GENERATED_ROOT = path.resolve(DATA_ROOT, 'generated');
const PROFILE_PATH = path.resolve(DATA_ROOT, 'api-profile.v1.json');
const BASE_URL = (process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1').replace(/\/+$/, '');
const TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS || 20000);
const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS || 800);
const POLL_ATTEMPTS = Number(process.env.POLL_ATTEMPTS || 150);
const PLANNING_MODE = String(process.env.PLANNING_MODE || 'JOINT').trim().toUpperCase();
const RUN_ID = (process.env.RUN_ID || `full-exam-${new Date().toISOString().replace(/[:.]/g, '-')}`).replace(/[^A-Za-z0-9._-]/g, '-');
const EVIDENCE_ROOT = path.resolve(DATA_ROOT, 'live-evidence', RUN_ID);
const UNAVAILABLE_STATUSES = new Set([404, 405, 501]);

function assertInside(candidate, root) {
  const resolved = path.resolve(candidate);
  const resolvedRoot = path.resolve(root);
  if (resolved !== resolvedRoot && !resolved.startsWith(`${resolvedRoot}${path.sep}`)) {
    throw new Error(`Refusing to write outside ${resolvedRoot}: ${resolved}`);
  }
  return resolved;
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else if (char === '"') quoted = false;
      else cell += char;
    } else if (char === '"') quoted = true;
    else if (char === ',') {
      row.push(cell);
      cell = '';
    } else if (char === '\n') {
      row.push(cell.replace(/\r$/, ''));
      if (row.some((value) => value !== '')) rows.push(row);
      row = [];
      cell = '';
    } else cell += char;
  }
  if (cell || row.length) {
    row.push(cell.replace(/\r$/, ''));
    rows.push(row);
  }
  const [headers, ...values] = rows;
  return values.map((items) => Object.fromEntries(headers.map((header, index) => [header, items[index]])));
}

function readCsv(relativePath) {
  return parseCsv(fs.readFileSync(path.resolve(GENERATED_ROOT, relativePath), 'utf8'));
}

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.resolve(GENERATED_ROOT, relativePath), 'utf8'));
}

function readJsonl(relativePath) {
  return fs.readFileSync(path.resolve(GENERATED_ROOT, relativePath), 'utf8').split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line));
}

function asNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function asBoolean(value) {
  return value === true || String(value).trim().toLowerCase() === 'true';
}

function asNullableString(value) {
  if (value == null) return null;
  const text = String(value).trim();
  return text || null;
}

function asLocalDateTime(value) {
  const text = asNullableString(value);
  return text == null ? null : text.replace(/(?:Z|[+-]\d{2}:\d{2})$/, '');
}

function sortedStrings(values) {
  return (Array.isArray(values) ? values : []).map(String).sort((left, right) => left.localeCompare(right));
}

function splitList(value) {
  return String(value || '').split('|').map((item) => item.trim()).filter(Boolean);
}

function normalizedWindows(values) {
  return (Array.isArray(values) ? values : []).map((value) => ({
    start: asLocalDateTime(value?.start),
    end: asLocalDateTime(value?.end),
    reason: asNullableString(value?.reason),
  })).sort((left, right) => `${left.start}|${left.end}|${left.reason || ''}`.localeCompare(`${right.start}|${right.end}|${right.reason || ''}`));
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function endpoint(template, values = {}) {
  return template.replace(/\{([A-Za-z]+)\}/g, (_, key) => encodeURIComponent(values[key] ?? `{${key}}`));
}

function sanitize(value) {
  if (Array.isArray(value)) return value.map(sanitize);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => {
    if (/password|token|cookie|captcha|authorization/i.test(key)) return [key, '[REDACTED]'];
    return [key, sanitize(item)];
  }));
}

class EvidenceStore {
  constructor(root) {
    this.root = assertInside(root, DATA_ROOT);
    this.sequence = 0;
    this.index = [];
    fs.mkdirSync(this.root, { recursive: true });
  }

  record(entry) {
    this.sequence += 1;
    const evidenceId = `HTTP-${String(this.sequence).padStart(4, '0')}`;
    const file = `${evidenceId}.json`;
    const payload = { evidenceId, ...entry };
    const serialized = `${JSON.stringify(payload, null, 2)}\n`;
    const target = assertInside(path.resolve(this.root, file), this.root);
    fs.writeFileSync(target, serialized, 'utf8');
    this.index.push({ evidenceId, file, sha256: sha256(serialized), status: entry.status ?? null, method: entry.method, path: entry.path });
    return evidenceId;
  }

  finalize() {
    const target = assertInside(path.resolve(this.root, 'evidence-index.json'), this.root);
    const serialized = `${JSON.stringify({ schemaVersion: 'full-exam-http-evidence-index/v1', runId: RUN_ID, records: this.index }, null, 2)}\n`;
    fs.writeFileSync(target, serialized, 'utf8');
    return { path: target, sha256: sha256(serialized), records: this.index.length };
  }
}

class HttpClient {
  constructor(store, identity = {}) {
    this.store = store;
    this.identity = identity;
    this.cookie = identity.cookie || process.env.SESSION_COOKIE || '';
    this.bearer = identity.bearer || process.env.AUTH_TOKEN || '';
  }

  async request(method, requestPath, options = {}) {
    const url = requestPath.startsWith('http') ? requestPath : `${BASE_URL}${requestPath.startsWith('/') ? '' : '/'}${requestPath}`;
    const headers = { Accept: 'application/json', ...(options.headers || {}) };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (this.cookie) headers.Cookie = this.cookie;
    if (this.bearer) headers.Authorization = `Bearer ${this.bearer}`;
    if (process.env.TEST_TENANT_ID) headers['X-Org-Id'] = process.env.TEST_TENANT_ID;
    const normalizedMethod = method.toUpperCase();
    const safelyRetryable = ['GET', 'HEAD', 'OPTIONS'].includes(normalizedMethod)
      || Boolean(options.headers?.['Idempotency-Key']);
    const maxAttempts = safelyRetryable ? 3 : 1;
    let previousEvidenceId = null;
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      const startedAt = new Date().toISOString();
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), options.timeoutMs || TIMEOUT_MS);
      try {
        const response = await fetch(url, {
          method,
          headers,
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
          signal: controller.signal,
        });
        const text = await response.text();
        let body = null;
        if (text) {
          try { body = JSON.parse(text); } catch { body = text.slice(0, 4096); }
        }
        const setCookies = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
        if (setCookies.length) this.cookie = setCookies.map((value) => value.split(';')[0]).join('; ');
        const evidenceId = this.store.record({
          startedAt,
          endedAt: new Date().toISOString(),
          method,
          path: new URL(url).pathname,
          attempt,
          retryOf: previousEvidenceId,
          request: sanitize({ headers: options.headers || {}, body: options.body }),
          status: response.status,
          responseHeaders: {
            contentType: response.headers.get('content-type'),
            allow: response.headers.get('allow'),
            etag: response.headers.get('etag'),
            traceId: response.headers.get('x-trace-id'),
          },
          body: sanitize(body),
        });
        return { status: response.status, ok: response.ok, body, headers: response.headers, evidenceId };
      } catch (error) {
        const evidenceId = this.store.record({
          startedAt,
          endedAt: new Date().toISOString(),
          method,
          path: new URL(url).pathname,
          attempt,
          retryOf: previousEvidenceId,
          request: sanitize({ headers: options.headers || {}, body: options.body }),
          status: null,
          networkError: { name: error.name, message: error.message },
        });
        previousEvidenceId = evidenceId;
        if (attempt === maxAttempts) return { status: null, ok: false, body: null, error, evidenceId };
        await new Promise((resolve) => setTimeout(resolve, 200 * attempt));
      } finally {
        clearTimeout(timer);
      }
    }
    throw new Error('HTTP retry loop exhausted without a result');
  }
}

function addCheck(round, id, status, evidenceId, expected, observed, message) {
  round.checks.push({ id, status, evidenceId: evidenceId || null, expected, observed, message: message || null });
}

function classifyResponse(response) {
  if (response.status == null || UNAVAILABLE_STATUSES.has(response.status)) return 'UNAVAILABLE';
  return 'FAIL';
}

function isExplicitlyUnsupported(response) {
  if (response.status == null || UNAVAILABLE_STATUSES.has(response.status)) return true;
  const code = String(response.body?.code || response.body?.errorCode || '').toUpperCase();
  const message = String(response.body?.message || response.body?.detail || '').toUpperCase();
  return [400, 409, 422].includes(response.status)
    && (['JOINT_INPUTS_UNSUPPORTED', 'PLANNING_MODE_UNSUPPORTED', 'UNSUPPORTED_PLANNING_MODE', 'NOT_IMPLEMENTED'].includes(code)
      || (message.includes('JOINT') && (message.includes('UNSUPPORTED') || message.includes('NOT IMPLEMENTED'))));
}

async function authenticate(store, prefix, username, password, role, fallbackCookie = '', fallbackBearer = '') {
  const client = new HttpClient(store, { cookie: fallbackCookie, bearer: fallbackBearer });
  if (client.cookie || client.bearer) return { status: 'PASS', client, identity: null, evidenceIds: [] };
  if (!username || !password) return { status: 'UNAVAILABLE', client, message: `${prefix} credentials are not configured`, evidenceIds: [] };
  const captcha = await client.request('POST', '/auth/captcha');
  if (captcha.status !== 200 || !captcha.body?.captchaId) {
    return { status: classifyResponse(captcha), client, message: 'Captcha endpoint is unavailable or invalid', evidenceIds: [captcha.evidenceId] };
  }
  const answer = process.env[`${prefix}_CAPTCHA_ANSWER`] || captcha.body.debugAnswer;
  if (!answer) {
    return { status: 'UNAVAILABLE', client, message: 'Captcha answer is unavailable; use a test backend with debug captcha or provide an answer', evidenceIds: [captcha.evidenceId] };
  }
  const login = await client.request('POST', '/auth/login', {
    body: { username, password, role: role || undefined, captchaId: captcha.body.captchaId, captcha: answer },
  });
  if (login.status !== 200 || !login.body?.user?.id || !client.cookie) {
    return { status: classifyResponse(login), client, message: 'Login failed or did not issue a session cookie', evidenceIds: [captcha.evidenceId, login.evidenceId] };
  }
  return { status: 'PASS', client, identity: login.body, evidenceIds: [captcha.evidenceId, login.evidenceId] };
}

async function fetchAuthenticatedContext(authResult, profile, checks, name) {
  if (authResult?.status !== 'PASS') return null;
  const template = profile.endpoints.authMe;
  if (!template) {
    preflightCheck(checks, `auth.${name}-context`, 'UNAVAILABLE', null, 'configured authenticated /auth/me endpoint', null, 'Cannot prove the authenticated tenant without authMe.');
    return null;
  }
  const response = await authResult.client.request('GET', template);
  const valid = response.status === 200 && response.body?.user?.id && response.body?.org?.id;
  preflightCheck(
    checks,
    `auth.${name}-context`,
    valid ? 'PASS' : classifyResponse(response),
    response.evidenceId,
    'HTTP 200 with user.id and org.id',
    valid ? { userId: response.body.user.id, orgId: response.body.org.id, orgSlug: response.body.org.slug } : sanitize(response.body || response.status),
    valid ? null : 'Authenticated tenant context could not be proven.',
  );
  return valid ? response.body : null;
}

function verifyMutationTenant(checks, submitterContext, approverContext) {
  const mutationOptIn = process.env.ALLOW_TEST_MUTATION === '1';
  preflightCheck(
    checks,
    'safety.mutation-opt-in',
    mutationOptIn ? 'PASS' : 'UNAVAILABLE',
    null,
    'ALLOW_TEST_MUTATION=1',
    process.env.ALLOW_TEST_MUTATION || null,
    mutationOptIn ? null : 'No master data or plans were created because mutation was not explicitly enabled.',
  );
  if (!mutationOptIn) return false;

  const expectedOrgId = asNullableString(process.env.TEST_TENANT_ID);
  const confirmed = process.env.CONFIRM_ISOLATED_TEST_TENANT === '1';
  if (!expectedOrgId || !confirmed || !submitterContext?.org?.id) {
    preflightCheck(
      checks,
      'safety.isolated-test-tenant',
      'UNAVAILABLE',
      null,
      { testTenantIdConfigured: true, explicitConfirmation: true, authenticatedOrgId: true },
      { testTenantIdConfigured: Boolean(expectedOrgId), explicitConfirmation: confirmed, authenticatedOrgId: submitterContext?.org?.id || null },
      'Set TEST_TENANT_ID and CONFIRM_ISOLATED_TEST_TENANT=1, and provide credentials that expose the same authenticated org via /auth/me.',
    );
    return false;
  }
  const submitterMatches = String(submitterContext.org.id).toLowerCase() === expectedOrgId.toLowerCase();
  const approverMatches = !approverContext?.org?.id || String(approverContext.org.id).toLowerCase() === expectedOrgId.toLowerCase();
  const matches = submitterMatches && approverMatches;
  preflightCheck(
    checks,
    'safety.isolated-test-tenant',
    matches ? 'PASS' : 'FAIL',
    null,
    { expectedOrgId, submitterOrgId: expectedOrgId, approverOrgId: approverContext ? expectedOrgId : null, explicitConfirmation: true },
    { expectedOrgId, submitterOrgId: submitterContext.org.id, approverOrgId: approverContext?.org?.id || null, explicitConfirmation: confirmed },
    matches ? 'Tenant identity matched before any master-data write.' : 'Tenant mismatch blocked all master-data writes and planning.',
  );
  return matches;
}

async function replaceJointInput(client, profile, key, values, ids, round) {
  const template = profile.endpoints[key];
  if (!template) {
    addCheck(round, `api.${key}`, 'UNAVAILABLE', null, 'configured typed-input endpoint', null, `${key} is absent from API profile`);
    return false;
  }
  const response = await client.request('POST', endpoint(template, ids), {
    headers: {
      'Idempotency-Key': `${RUN_ID}-${ids.revisionId}-${key}`,
      'If-Match': String(ids.revisionCursor),
    },
    body: { items: values },
  });
  if (![200, 201, 202].includes(response.status)) {
    const status = isExplicitlyUnsupported(response) ? 'UNAVAILABLE' : classifyResponse(response);
    addCheck(round, `api.${key}`, status, response.evidenceId, 'HTTP 200/201/202 typed replacement', sanitize(response.body || response.status));
    return false;
  }
  const acceptedCount = asNumber(response.body?.acceptedCount);
  const revisionCursor = asNumber(response.body?.revisionCursor);
  const valid = acceptedCount === values.length && revisionCursor > asNumber(ids.revisionCursor);
  addCheck(
    round,
    `api.${key}`,
    valid ? 'PASS' : 'FAIL',
    response.evidenceId,
    { acceptedCount: values.length, cursorAdvanced: true },
    { acceptedCount, previousCursor: ids.revisionCursor, revisionCursor, replayed: response.body?.replayed === true },
  );
  if (!valid) return false;
  ids.revisionCursor = revisionCursor;
  return true;
}

async function verifyJointInput(client, profile, key, values, ids, round) {
  const response = await client.request('GET', endpoint(profile.endpoints[key], ids));
  if (response.status !== 200 || !Array.isArray(response.body?.items)) {
    const status = isExplicitlyUnsupported(response) ? 'UNAVAILABLE' : classifyResponse(response);
    addCheck(round, `api.${key}.readback`, status, response.evidenceId, 'HTTP 200 ResourceSnapshot', sanitize(response.body || response.status));
    return false;
  }
  const observed = {
    revisionId: response.body.revisionId,
    revisionCursor: response.body.revisionCursor,
    items: response.body.items.length,
  };
  const valid = String(response.body.revisionId) === String(ids.revisionId)
    && response.body.items.length === values.length
    && asNumber(response.body.revisionCursor) === asNumber(ids.revisionCursor);
  addCheck(round, `api.${key}.readback`, valid ? 'PASS' : 'FAIL', response.evidenceId,
    { revisionId: ids.revisionId, revisionCursor: ids.revisionCursor, items: values.length }, observed);
  return valid;
}

async function pollJob(client, profile, jobId, ids, round) {
  const template = profile.endpoints.planningJobGet;
  if (!template) {
    addCheck(round, 'planning.job-poll', 'UNAVAILABLE', null, 'configured endpoint', null, 'planningJobGet is absent from API profile');
    return null;
  }
  for (let attempt = 0; attempt < POLL_ATTEMPTS; attempt += 1) {
    const response = await client.request('GET', endpoint(template, { ...ids, jobId }));
    if (response.status !== 200) {
      addCheck(round, 'planning.job-poll', classifyResponse(response), response.evidenceId, 200, response.status);
      return null;
    }
    const status = String(response.body?.status || response.body?.state || '').toUpperCase();
    if (['SUCCEEDED', 'SUCCESS', 'READY', 'REVIEW_REQUIRED'].includes(status)) {
      addCheck(round, 'planning.job-poll', 'PASS', response.evidenceId, 'terminal success', status);
      return response.body;
    }
    if (['FAILED', 'INFEASIBLE', 'INVALID', 'CANCELLED'].includes(status)) {
      addCheck(round, 'planning.job-poll', 'FAIL', response.evidenceId, 'terminal success', response.body, 'Planning job failed or was infeasible');
      return null;
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
  addCheck(round, 'planning.job-poll', 'FAIL', null, `terminal within ${POLL_ATTEMPTS} polls`, null, 'Planning job timed out');
  return null;
}

function buildJointInputResources(masterIds) {
  const requiredId = (values, key, label) => {
    const value = values[key];
    if (!value) throw new Error(`Missing tenant-owned ${label} UUID for stable key ${key}`);
    return value;
  };
  const students = new Map(readCsv('shared/students.csv').map((item) => [item.studentId, item]));
  const demands = readCsv('shared/exam-demands.csv').map((item) => ({
    demandCode: item.demandCode,
    name: item.name,
    subjectId: requiredId(masterIds.subjectIds, item.subjectCode, 'subject'),
    durationMinutes: asNumber(item.durationMinutes),
    candidateCount: asNumber(item.candidateCount),
    invigilatorCount: asNumber(item.invigilatorCount),
    minRooms: asNumber(item.minRooms),
    maxRooms: asNumber(item.maxRooms),
    splitAllowed: asBoolean(item.splitAllowed),
    requiredRoomFeatures: splitList(item.requiredRoomFeatures),
    settings: {
      examType: item.examType,
      chiefInvigilatorCount: asNumber(item.chiefInvigilatorCount),
      preferredCampus: item.preferredCampus,
      lockLevel: item.lockLevel,
      lockedWindowCode: asNullableString(item.lockedWindowCode),
      lockedRoomCode: asNullableString(item.lockedRoomCode),
    },
  }));
  const demandClasses = readCsv('shared/demand-classes.csv').map((item) => ({
    demandCode: item.demandCode,
    classId: requiredId(masterIds.classIds, item.classCode, 'class'),
    expectedCandidates: asNumber(item.expectedCandidates),
    settings: { sourceClassCode: item.classCode },
  }));
  const demandWindows = readCsv('shared/demand-windows.csv').map((item) => ({
    demandCode: item.demandCode,
    windowCode: item.windowCode,
    startsAt: item.startsAt,
    endsAt: item.endsAt,
    preferenceWeight: asNumber(item.preferenceWeight),
    locked: asBoolean(item.locked),
    settings: {},
  }));
  const candidates = readCsv('shared/candidates.csv').map((item) => ({
    candidateCode: item.candidateCode,
    name: item.name,
    classId: requiredId(masterIds.classIds, item.classCode, 'class'),
    attributes: {
      campus: item.campus,
      specialNeed: asNullableString(students.get(item.candidateCode)?.specialNeed),
      sourceClassCode: item.classCode,
    },
  }));
  const enrollments = readCsv('shared/candidate-enrollments.csv').map((item) => ({
    candidateCode: item.candidateCode,
    demandCode: item.demandCode,
    status: item.status,
    settings: {},
  }));
  const candidateWindows = readCsv('shared/candidate-windows.csv').map((item) => ({
    candidateCode: item.candidateCode,
    windowType: item.windowType,
    startsAt: item.startsAt,
    endsAt: item.endsAt,
    reason: item.reason,
    settings: {},
  }));
  const specialRequirements = readCsv('shared/special-requirements.csv').map((item) => ({
    candidateCode: item.candidateCode,
    demandCode: asNullableString(item.demandCode),
    requirementCode: item.requirementCode,
    requirementType: item.requirementType,
    requiredFeatures: splitList(item.requiredFeatures),
    extraMinutes: asNumber(item.extraMinutes),
    separateRoom: asBoolean(item.separateRoom),
    details: { privateNoteRef: item.privateNoteRef },
  }));
  const roomPool = readCsv('shared/room-pool.csv').map((item) => ({
    classroomId: requiredId(masterIds.classroomIds, item.roomCode, 'classroom'),
    campusCode: item.campusCode,
    examCapacity: asNumber(item.examCapacity),
    features: splitList(item.features),
    status: item.status,
    settings: { sourceRoomCode: item.roomCode },
  }));
  const roomWindows = readCsv('shared/room-windows.csv').map((item) => ({
    classroomId: requiredId(masterIds.classroomIds, item.roomCode, 'classroom'),
    windowType: item.windowType,
    startsAt: item.startsAt,
    endsAt: item.endsAt,
    reason: item.reason,
    settings: { sourceRoomCode: item.roomCode },
  }));
  const seatTopology = readCsv('shared/seat-topology.csv').map((item) => ({
    classroomId: requiredId(masterIds.classroomIds, item.roomCode, 'classroom'),
    seatCode: item.seatCode,
    rowNo: asNumber(item.rowNo),
    columnNo: asNumber(item.columnNo),
    features: splitList(item.features),
    status: item.status,
  }));
  const teacherPool = readCsv('shared/teacher-pool.csv').map((item) => ({
    teacherId: requiredId(masterIds.teacherIds, item.teacherId, 'teacher'),
    eligibleRoles: splitList(item.eligibleRoles),
    maxDailySessions: asNumber(item.maxDailySessions),
    minRestMinutes: asNumber(item.minRestMinutes),
    preferredCampus: item.preferredCampus,
    status: item.status,
    preferences: {
      qualificationExpiry: item.qualificationExpiry,
      flags: splitList(item.preferences),
      sourceTeacherId: item.teacherId,
    },
  }));
  const avoidances = readCsv('shared/teacher-avoidances.csv').map((item) => ({
    teacherId: requiredId(masterIds.teacherIds, item.teacherId, 'teacher'),
    targetType: item.targetType,
    demandCode: item.targetType === 'DEMAND' ? item.targetCode : null,
    classId: item.targetType === 'CLASS' ? requiredId(masterIds.classIds, item.targetCode, 'class') : null,
    candidateCode: item.targetType === 'CANDIDATE' ? item.targetCode : null,
    severity: item.severity,
    reason: item.reason,
    details: { sourceTargetCode: item.targetCode },
  }));
  const campusTravel = readCsv('shared/campus-travel.csv').map((item) => ({
    fromCampus: item.fromCampus,
    toCampus: item.toCampus,
    travelMinutes: asNumber(item.travelMinutes),
    bufferMinutes: asNumber(item.bufferMinutes),
    settings: {},
  }));
  return {
    jointDemands: demands,
    jointDemandClasses: demandClasses,
    jointDemandWindows: demandWindows,
    jointCandidates: candidates,
    jointEnrollments: enrollments,
    jointCandidateWindows: candidateWindows,
    jointSpecialRequirements: specialRequirements,
    jointRoomPool: roomPool,
    jointRoomWindows: roomWindows,
    jointSeatTopology: seatTopology,
    jointTeacherPool: teacherPool,
    jointAvoidances: avoidances,
    jointCampusTravel: campusTravel,
  };
}

function buildMasterDataResources() {
  const sessions = readCsv('shared/sessions.csv');
  const dutyWindows = [...new Map(sessions.map((item) => {
    const start = asLocalDateTime(item.startsAt);
    const end = asLocalDateTime(item.endsAt);
    return [`${start}|${end}`, { start, end, reason: 'generated-exam-window' }];
  })).values()].sort((left, right) => `${left.start}|${left.end}`.localeCompare(`${right.start}|${right.end}`));
  const leavesByTeacher = new Map();
  for (const item of readCsv('round-2/leave-requests.csv')) {
    if (!asBoolean(item.approved)) continue;
    const values = leavesByTeacher.get(item.teacherId) || [];
    values.push({
      start: asLocalDateTime(item.startsAt),
      end: asLocalDateTime(item.endsAt),
      reason: asNullableString(item.reasonCode),
    });
    leavesByTeacher.set(item.teacherId, values);
  }
  const teachers = readCsv('shared/teachers.csv').map((item) => ({
    key: item.teacherId,
    sourceSubjectName: item.subject,
    payload: {
      teacherNo: item.teacherId,
      name: item.name,
      gender: String(item.gender).toUpperCase() === 'M' ? 'male' : String(item.gender).toUpperCase() === 'F' ? 'female' : 'unknown',
      grade: item.homeroomClass ? item.homeroomClass.split('-')[0] : null,
      phone: null,
      email: null,
      status: asBoolean(item.active) ? 'active' : 'disabled',
      age: asNumber(item.age),
      position: asNullableString(item.position),
      leaveWindows: normalizedWindows(leavesByTeacher.get(item.teacherId) || []),
      functionalAccommodations: item.healthFlag ? [item.healthFlag] : [],
      dutyWindows: normalizedWindows(dutyWindows),
      isHomeroom: Boolean(item.homeroomClass),
      homeroomClassId: null,
      qualifications: ['INVIGILATOR', ...(asBoolean(item.chiefQualified) ? ['CHIEF'] : [])],
      qualificationExpiry: asNullableString(item.qualificationExpiry),
      maxDailySessions: asNumber(item.maxDailySessions),
      preferredCampus: asNullableString(item.campus),
      // Deliberately no subjectIds: a subject name is not a verified tenant-owned UUID.
    },
  }));
  const classrooms = readCsv('shared/rooms.csv').map((item) => {
    const features = [
      `CAMPUS_${String(item.campus).toUpperCase()}`,
      asBoolean(item.accessible) ? 'ACCESSIBLE' : null,
      asBoolean(item.hearingAssist) ? 'HEARING_ASSIST' : null,
      asBoolean(item.separateRoom) ? 'SEPARATE_ROOM' : null,
      asBoolean(item.computerReady) ? 'COMPUTER_READY' : null,
    ].filter(Boolean);
    return {
      key: item.roomCode,
      payload: {
        code: item.roomCode,
        name: item.name,
        building: asNullableString(item.building),
        floor: asNullableString(item.floor),
        capacity: asNumber(item.capacity),
        examCapacity: asNumber(item.capacity),
        roomType: 'standard',
        status: String(item.status).toUpperCase() === 'ACTIVE' ? 'active' : 'disabled',
        features,
      },
    };
  });
  const subjects = readCsv('shared/subjects.csv').map((item) => ({
    key: item.subjectCode,
    payload: {
      code: item.subjectCode,
      name: item.name,
      category: 'main',
      examType: 'written',
      hours: 0,
      color: 'slate',
      status: 'active',
    },
  }));
  const classes = readCsv('shared/classes.csv').map((item) => ({
    key: item.classCode,
    payload: {
      code: item.classCode,
      name: item.name,
      grade: item.grade,
      stage: item.stage,
      studentCount: asNumber(item.studentCount),
      homeroomTeacherId: null,
      defaultClassroomId: null,
      status: 'active',
      subjectIds: null,
    },
  }));
  return { teachers, classrooms, subjects, classes };
}

function teacherComparable(value) {
  return {
    teacherNo: asNullableString(value?.teacherNo),
    name: asNullableString(value?.name),
    gender: asNullableString(value?.gender),
    grade: asNullableString(value?.grade),
    phone: asNullableString(value?.phone),
    email: asNullableString(value?.email),
    status: asNullableString(value?.status),
    age: value?.age == null ? null : asNumber(value.age),
    position: asNullableString(value?.position),
    leaveWindows: normalizedWindows(value?.leaveWindows),
    functionalAccommodations: sortedStrings(value?.functionalAccommodations),
    dutyWindows: normalizedWindows(value?.dutyWindows),
    isHomeroom: asBoolean(value?.isHomeroom),
    homeroomClassId: asNullableString(value?.homeroomClassId),
    qualifications: sortedStrings(value?.qualifications),
    qualificationExpiry: asNullableString(value?.qualificationExpiry),
    maxDailySessions: value?.maxDailySessions == null ? null : asNumber(value.maxDailySessions),
    preferredCampus: asNullableString(value?.preferredCampus),
  };
}

function classroomComparable(value) {
  return {
    code: asNullableString(value?.code),
    name: asNullableString(value?.name),
    building: asNullableString(value?.building),
    floor: asNullableString(value?.floor),
    capacity: asNumber(value?.capacity),
    examCapacity: asNumber(value?.examCapacity),
    roomType: asNullableString(value?.roomType),
    status: asNullableString(value?.status),
    features: sortedStrings(value?.features),
  };
}

function subjectComparable(value) {
  return {
    code: asNullableString(value?.code),
    name: asNullableString(value?.name),
    category: asNullableString(value?.category),
    examType: asNullableString(value?.examType),
    hours: asNumber(value?.hours),
    color: asNullableString(value?.color),
    status: asNullableString(value?.status),
  };
}

function classComparable(value) {
  return {
    code: asNullableString(value?.code),
    name: asNullableString(value?.name),
    grade: asNullableString(value?.grade),
    stage: asNullableString(value?.stage),
    studentCount: asNumber(value?.studentCount),
    status: asNullableString(value?.status),
  };
}

function duplicateValues(items, keyOf) {
  const counts = new Map();
  for (const item of items) {
    const key = asNullableString(keyOf(item));
    if (key) counts.set(key, (counts.get(key) || 0) + 1);
  }
  return [...counts.entries()].filter(([, count]) => count > 1).map(([key, count]) => ({ key, count }));
}

function preflightCheck(checks, id, status, evidenceId, expected, observed, message = null) {
  checks.push({ id, status, evidenceId: evidenceId || null, expected, observed, message });
}

async function upsertMasterCollection(client, checks, options) {
  const {
    name, collectionPath, updatePath, desired, existing, keyOf, comparable,
  } = options;
  const existingByKey = new Map(existing.map((item) => [keyOf(item), item]));
  const counts = { created: 0, updated: 0, unchanged: 0 };
  let lastEvidenceId = null;
  for (const item of desired) {
    const current = existingByKey.get(item.key);
    if (current && canonicalJson(comparable(current)) === canonicalJson(comparable(item.payload))) {
      counts.unchanged += 1;
      continue;
    }
    const uuid = asNullableString(current?.uuid);
    if (current && !uuid) {
      preflightCheck(checks, `masterdata.${name}.upsert`, 'FAIL', null, 'existing resource with UUID path id', { key: item.key, uuid: current?.uuid || null }, 'The list response cannot be safely updated because uuid is missing.');
      return false;
    }
    const method = current ? 'PUT' : 'POST';
    const requestPath = current ? endpoint(updatePath, { id: uuid }) : collectionPath;
    // Master-data endpoints do not currently implement Idempotency-Key. Re-run safety
    // comes from GET + the stable teacherNo/code natural key, never from a blind POST.
    let response = await client.request(method, requestPath, { body: item.payload });
    if (response.status == null) {
      // These legacy master-data writes do not accept Idempotency-Key. Reconcile by
      // natural key before retrying so a lost success response can never create a
      // duplicate row. Retry only when the follow-up list proves the key is absent.
      const reconciliation = await client.request('GET', collectionPath);
      const reconciled = reconciliation.status === 200 && Array.isArray(reconciliation.body)
        ? reconciliation.body.find((candidate) => keyOf(candidate) === item.key)
        : null;
      if (reconciled && canonicalJson(comparable(reconciled)) === canonicalJson(comparable(item.payload))) {
        lastEvidenceId = reconciliation.evidenceId;
        counts[current ? 'updated' : 'created'] += 1;
        continue;
      }
      if (reconciliation.status === 200 && !reconciled) {
        response = await client.request(method, requestPath, { body: item.payload });
      }
    }
    lastEvidenceId = response.evidenceId;
    const returnedKey = keyOf(response.body || {});
    if (![200, 201].includes(response.status) || returnedKey !== item.key || !response.body?.uuid) {
      preflightCheck(
        checks,
        `masterdata.${name}.upsert`,
        classifyResponse(response),
        response.evidenceId,
        `HTTP 200/201 with stable key ${item.key} and uuid`,
        sanitize({ status: response.status, key: returnedKey, uuid: response.body?.uuid }),
        `Stopped ${name} preparation at ${item.key}; planning was not started.`,
      );
      return false;
    }
    counts[current ? 'updated' : 'created'] += 1;
  }
  preflightCheck(
    checks,
    `masterdata.${name}.upsert`,
    'PASS',
    lastEvidenceId,
    { target: desired.length, blindPosts: 0 },
    counts,
    'Every create/update request is retained as an HTTP evidence record; unchanged rows require no write.',
  );
  return true;
}

function verifyPreparedCollection(checks, options) {
  const {
    name, desired, actual, keyOf, comparable, evidenceId,
  } = options;
  const duplicates = duplicateValues(actual, keyOf);
  preflightCheck(
    checks,
    `masterdata.${name}.duplicates`,
    duplicates.length === 0 ? 'PASS' : 'FAIL',
    evidenceId,
    0,
    { duplicateCount: duplicates.length, samples: duplicates.slice(0, 10) },
    duplicates.length ? 'Natural-key duplicates make re-run behavior ambiguous; planning was blocked.' : null,
  );
  if (duplicates.length) return false;
  const actualByKey = new Map(actual.map((item) => [keyOf(item), item]));
  const missing = [];
  const mismatched = [];
  for (const item of desired) {
    const value = actualByKey.get(item.key);
    if (!value) missing.push(item.key);
    else if (canonicalJson(comparable(value)) !== canonicalJson(comparable(item.payload))) mismatched.push(item.key);
  }
  const ok = missing.length === 0 && mismatched.length === 0;
  preflightCheck(
    checks,
    `masterdata.${name}.coverage`,
    ok ? 'PASS' : 'FAIL',
    evidenceId,
    { matched: desired.length, missing: 0, mismatched: 0 },
    { matched: desired.length - missing.length - mismatched.length, missing: missing.slice(0, 10), mismatched: mismatched.slice(0, 10) },
    ok ? null : 'The required master-data scale/profile was not persisted exactly; planning was blocked.',
  );
  return ok;
}

async function prepareMasterData(client, profile, resources, checks) {
  const generatedTeacherDuplicates = duplicateValues(resources.teachers, (item) => item.key);
  const generatedClassroomDuplicates = duplicateValues(resources.classrooms, (item) => item.key);
  const generatedSubjectDuplicates = duplicateValues(resources.subjects, (item) => item.key);
  const generatedClassDuplicates = duplicateValues(resources.classes, (item) => item.key);
  const teachersWithDutyWindows = resources.teachers.filter((item) => item.payload.dutyWindows.length > 0).length;
  const teachersWithQualificationProfiles = resources.teachers.filter((item) => (
    item.payload.qualifications.includes('INVIGILATOR')
    && item.payload.qualificationExpiry
    && item.payload.maxDailySessions >= 1
    && item.payload.preferredCampus
  )).length;
  const fabricatedSubjectIds = resources.teachers.filter((item) => Object.hasOwn(item.payload, 'subjectIds')).length;
  const generatedInputValid = resources.teachers.length === 150
    && resources.classrooms.length === 60
    && resources.subjects.length === 5
    && resources.classes.length === 60
    && generatedTeacherDuplicates.length === 0
    && generatedClassroomDuplicates.length === 0
    && generatedSubjectDuplicates.length === 0
    && generatedClassDuplicates.length === 0
    && teachersWithDutyWindows === 150
    && teachersWithQualificationProfiles === 150
    && fabricatedSubjectIds === 0;
  preflightCheck(
    checks,
    'masterdata.generated-input',
    generatedInputValid ? 'PASS' : 'FAIL',
    null,
    { teachers: 150, classrooms: 60, subjects: 5, classes: 60, duplicateTeacherNo: 0, duplicateClassroomCode: 0, duplicateSubjectCode: 0, duplicateClassCode: 0, teachersWithDutyWindows: 150, teachersWithQualificationProfiles: 150, fabricatedSubjectIds: 0 },
    {
      teachers: resources.teachers.length,
      classrooms: resources.classrooms.length,
      subjects: resources.subjects.length,
      classes: resources.classes.length,
      duplicateTeacherNo: generatedTeacherDuplicates.length,
      duplicateClassroomCode: generatedClassroomDuplicates.length,
      duplicateSubjectCode: generatedSubjectDuplicates.length,
      duplicateClassCode: generatedClassDuplicates.length,
      teachersWithDutyWindows,
      teachersWithQualificationProfiles,
      fabricatedSubjectIds,
    },
    generatedInputValid ? null : 'Generated master-data input is incomplete or ambiguous; no HTTP mutation or planning followed.',
  );
  if (!generatedInputValid) return null;
  const requiredEndpoints = ['teachers', 'teacherUpdate', 'classrooms', 'classroomUpdate', 'subjects', 'subjectUpdate', 'classes', 'classUpdate'];
  const missingEndpoints = requiredEndpoints.filter((key) => !profile.endpoints[key]);
  if (missingEndpoints.length) {
    preflightCheck(checks, 'masterdata.api-profile', 'UNAVAILABLE', null, requiredEndpoints, missingEndpoints, 'Required master-data endpoints are absent from the API profile.');
    return null;
  }
  const specs = [
    { name: 'subjects', collection: 'subjects', update: 'subjectUpdate', desired: resources.subjects, keyOf: (item) => item.code, comparable: subjectComparable },
    { name: 'classes', collection: 'classes', update: 'classUpdate', desired: resources.classes, keyOf: (item) => item.code, comparable: classComparable },
    { name: 'teachers', collection: 'teachers', update: 'teacherUpdate', desired: resources.teachers, keyOf: (item) => item.teacherNo, comparable: teacherComparable },
    { name: 'classrooms', collection: 'classrooms', update: 'classroomUpdate', desired: resources.classrooms, keyOf: (item) => item.code, comparable: classroomComparable },
  ];
  const before = new Map();
  for (const spec of specs) {
    const response = await client.request('GET', profile.endpoints[spec.collection]);
    if (response.status !== 200 || !Array.isArray(response.body)) {
      preflightCheck(checks, `masterdata.${spec.name}.list-before`, classifyResponse(response), response.evidenceId, 'HTTP 200 array', response.status, `${spec.name} preflight failed; no planning followed.`);
      return null;
    }
    const duplicates = duplicateValues(response.body, spec.keyOf);
    preflightCheck(checks, `masterdata.${spec.name}.natural-key-precondition`, duplicates.length === 0 ? 'PASS' : 'FAIL', response.evidenceId, 0, { duplicateCount: duplicates.length, samples: duplicates.slice(0, 10) });
    if (duplicates.length) return null;
    before.set(spec.name, response);
  }

  for (const spec of specs) {
    const upserted = await upsertMasterCollection(client, checks, {
      name: spec.name,
      collectionPath: profile.endpoints[spec.collection],
      updatePath: profile.endpoints[spec.update],
      desired: spec.desired,
      existing: before.get(spec.name).body,
      keyOf: spec.keyOf,
      comparable: spec.comparable,
    });
    if (!upserted) return null;
  }

  const actual = new Map();
  let allValid = true;
  for (const spec of specs) {
    const response = await client.request('GET', profile.endpoints[spec.collection]);
    if (response.status !== 200 || !Array.isArray(response.body)) {
      preflightCheck(checks, `masterdata.${spec.name}.list-after`, classifyResponse(response), response.evidenceId, 'HTTP 200 array', response.status, `Could not prove ${spec.name} persistence; planning was blocked.`);
      return null;
    }
    const valid = verifyPreparedCollection(checks, {
      name: spec.name,
      desired: spec.desired,
      actual: response.body,
      keyOf: spec.keyOf,
      comparable: spec.comparable,
      evidenceId: response.evidenceId,
    });
    allValid = allValid && valid;
    actual.set(spec.name, response.body);
  }
  if (!allValid) return null;

  const idMap = (name, keyOf) => Object.fromEntries(actual.get(name)
    .filter((item) => asNullableString(keyOf(item)) && asNullableString(item.uuid))
    .map((item) => [keyOf(item), item.uuid]));
  const ids = {
    subjectIds: idMap('subjects', (item) => item.code),
    classIds: idMap('classes', (item) => item.code),
    teacherIds: idMap('teachers', (item) => item.teacherNo),
    classroomIds: idMap('classrooms', (item) => item.code),
  };
  const completeMappings = resources.subjects.every((item) => ids.subjectIds[item.key])
    && resources.classes.every((item) => ids.classIds[item.key])
    && resources.teachers.every((item) => ids.teacherIds[item.key])
    && resources.classrooms.every((item) => ids.classroomIds[item.key]);
  preflightCheck(
    checks,
    'masterdata.stable-key-uuid-mapping',
    completeMappings ? 'PASS' : 'FAIL',
    null,
    { subjects: 5, classes: 60, teachers: 150, classrooms: 60 },
    {
      subjects: Object.keys(ids.subjectIds).length,
      classes: Object.keys(ids.classIds).length,
      teachers: Object.keys(ids.teacherIds).length,
      classrooms: Object.keys(ids.classroomIds).length,
    },
    completeMappings ? 'Only tenant-returned UUIDs are used in JOINT typed inputs; no identifier is fabricated.' : 'Stable-key UUID mapping is incomplete; JOINT input upload was blocked.',
  );
  return completeMappings ? ids : null;
}

function coverageMetrics(resources) {
  const sessions = resources.sessions || [];
  const rooms = resources.rooms || [];
  const seats = resources.seats || [];
  const duties = resources.duties || [];
  return {
    sessions: sessions.length,
    rooms: rooms.length,
    seats: seats.length,
    duties: duties.length,
    sessionCoverage: sessions.length / 300,
    roomCoverage: rooms.length / 300,
    seatCoverage: seats.length / 15000,
    dutyCoverage: duties.length / 600,
  };
}

function assignmentKey(value) {
  const parts = [
    value?.session_external_code ?? value?.sessionExternalCode,
    value?.duty_role ?? value?.dutyRole,
    value?.teacher_no ?? value?.teacherNo,
  ].map(asNullableString);
  return parts.every(Boolean) ? parts.join('|') : null;
}

function retention(before, after) {
  const originalKeys = before.map(assignmentKey);
  const currentKeys = after.map(assignmentKey);
  const missingBefore = originalKeys.filter((key) => key == null).length;
  const missingAfter = currentKeys.filter((key) => key == null).length;
  const original = new Set(originalKeys.filter(Boolean));
  const current = new Set(currentKeys.filter(Boolean));
  if (original.size === 0 || missingBefore || missingAfter) {
    return { rate: 0, missingBefore, missingAfter, baselineKeys: original.size, currentKeys: current.size };
  }
  let retained = 0;
  for (const key of original) if (current.has(key)) retained += 1;
  return { rate: retained / original.size, missingBefore, missingAfter, baselineKeys: original.size, currentKeys: current.size };
}

function deliveryKey(value) {
  const eventId = value?.event_id ?? value?.eventId ?? value?.outbox_id ?? value?.outboxId;
  const recipientId = value?.recipient_id ?? value?.recipientId;
  const channel = value?.channel;
  const parts = [eventId, recipientId, channel].map(asNullableString);
  return parts.every(Boolean) ? parts.join('|') : null;
}

function capacityGapRejection(response) {
  const code = response.body?.code ?? response.body?.errorCode ?? response.body?.error_code;
  const message = asNullableString(response.body?.message) ?? '';
  const match = /capacityGap\s*=\s*(\d+)/i.exec(message);
  const structuredGap = asNumber(
    response.body?.capacityGap
      ?? response.body?.capacity_gap
      ?? response.body?.details?.capacityGap
      ?? response.body?.details?.capacity_gap,
  );
  const capacityGap = structuredGap > 0 ? structuredGap : (match ? Number(match[1]) : 0);
  return {
    rejected: response.status === 422 && code === 'ROOM_CAPACITY_UNAVAILABLE' && capacityGap > 0,
    status: response.status,
    code,
    capacityGap,
    message,
  };
}

async function getRevision(client, profile, ids) {
  return client.request('GET', endpoint(profile.endpoints.revisionGet, ids));
}

async function fetchRevisionResources(client, profile, ids, round) {
  const result = {};
  for (const key of ['sessionsGet', 'roomsGet', 'seatsGet', 'dutiesGet']) {
    const response = await client.request('GET', endpoint(profile.endpoints[key], ids));
    if (response.status !== 200 || !Array.isArray(response.body)) {
      addCheck(round, `coverage.${key}`, classifyResponse(response), response.evidenceId, 'HTTP 200 array', response.status);
      return null;
    }
    result[key.replace('Get', '')] = response.body;
  }
  return result;
}

async function validateSuccessor(client, profile, ids, disruption, emergencyResponse, round) {
  const successorRevisionId = emergencyResponse.body?.successorRevisionId
    ?? emergencyResponse.body?.successor_revision_id
    ?? emergencyResponse.body?.revisionId
    ?? emergencyResponse.body?.revision_id;
  const checkId = `round3.${disruption.disruptionId}.successor-validation`;
  if (!successorRevisionId) {
    addCheck(round, checkId, 'UNAVAILABLE', emergencyResponse.evidenceId, 'successorRevisionId', null, 'Emergency response did not identify a successor revision');
    return null;
  }
  const successorIds = { ...ids, revisionId: successorRevisionId };
  const revision = await getRevision(client, profile, successorIds);
  const cursor = revision.body?.revisionCursor ?? revision.body?.revision_cursor;
  if (revision.status !== 200 || cursor == null) {
    addCheck(round, checkId, classifyResponse(revision), revision.evidenceId, 'HTTP 200 successor with revision cursor', sanitize(revision.body || revision.status));
    return null;
  }
  const validation = await client.request('POST', endpoint(profile.endpoints.validate, successorIds), {
    headers: { 'If-Match': String(cursor) },
  });
  const issues = Array.isArray(validation.body?.issues) ? validation.body.issues : [];
  const codes = issues.map((item) => item.code).filter(Boolean);
  const capacityGap = asNumber(
    emergencyResponse.body?.impactAnalysis?.capacityGap
      ?? emergencyResponse.body?.impactAnalysis?.capacity_gap
      ?? emergencyResponse.body?.impact_analysis?.capacityGap
      ?? emergencyResponse.body?.impact_analysis?.capacity_gap,
  );
  if (disruption.type === 'CANDIDATE_COUNT_INCREASE') {
    const gapDetected = capacityGap > 0;
    addCheck(
      round,
      `round3.${disruption.disruptionId}.capacity-gap`,
      gapDetected ? 'PASS' : 'FAIL',
      emergencyResponse.evidenceId,
      '>0 capacity gap requiring rejection',
      capacityGap,
    );
    if (!gapDetected) return null;
    const rejected = validation.status === 422
      && validation.body?.valid === false
      && asNumber(validation.body?.hardViolationCount ?? validation.body?.hard_violation_count) > 0
      && codes.includes('ROOM_CAPACITY_UNCOVERED');
    addCheck(
      round,
      checkId,
      rejected ? 'PASS' : 'FAIL',
      validation.evidenceId,
      { status: 422, valid: false, requiredCode: 'ROOM_CAPACITY_UNCOVERED' },
      sanitize({ status: validation.status, valid: validation.body?.valid, hardViolationCount: validation.body?.hardViolationCount ?? validation.body?.hard_violation_count, codes }),
      rejected ? 'Capacity-infeasible successor was truthfully rejected.' : 'A capacity gap must never validate as feasible.',
    );
    return rejected ? successorRevisionId : null;
  }
  const valid = validation.status === 200
    && validation.body?.valid === true
    && asNumber(validation.body?.hardViolationCount ?? validation.body?.hard_violation_count) === 0;
  addCheck(
    round,
    checkId,
    valid ? 'PASS' : classifyResponse(validation),
    validation.evidenceId,
    { status: 200, valid: true, hardViolationCount: 0 },
    sanitize({ status: validation.status, valid: validation.body?.valid, hardViolationCount: validation.body?.hardViolationCount ?? validation.body?.hard_violation_count, codes }),
    valid ? null : 'A supposedly feasible emergency successor failed validation.',
  );
  return valid ? successorRevisionId : null;
}

async function runDisruptions(client, profile, ids, baselineDuties, round) {
  const disruptions = readJsonl('round-3/disruptions.jsonl');
  const template = profile.endpoints.emergencyChange;
  if (!template) {
    addCheck(round, 'round3.emergency-api', 'UNAVAILABLE', null, 'configured endpoint', null, 'Emergency change endpoint is absent');
    return false;
  }
  const baselineMissingKeys = baselineDuties.filter((item) => assignmentKey(item) == null).length;
  addCheck(
    round,
    'round3.assignment-stable-keys',
    baselineDuties.length > 0 && baselineMissingKeys === 0 ? 'PASS' : 'FAIL',
    null,
    'Every duty exposes session_external_code|duty_role|teacher_no (snake or camel)',
    { duties: baselineDuties.length, missingKeys: baselineMissingKeys },
  );
  if (!baselineDuties.length || baselineMissingKeys) return false;
  const successors = [];
  const rejectedWithoutSuccessor = [];
  for (const disruption of disruptions) {
    const idempotencyKey = `${RUN_ID}-${disruption.disruptionId}`;
    const first = await client.request('POST', endpoint(template, ids), {
      headers: { 'Idempotency-Key': idempotencyKey },
      body: disruption,
    });
    if (disruption.type === 'CANDIDATE_COUNT_INCREASE' && first.status === 422) {
      const firstRejection = capacityGapRejection(first);
      addCheck(
        round,
        `round3.${disruption.disruptionId}.capacity-gap`,
        firstRejection.rejected ? 'PASS' : 'FAIL',
        first.evidenceId,
        { status: 422, code: 'ROOM_CAPACITY_UNAVAILABLE', capacityGap: '>0', successorCreated: false },
        sanitize(firstRejection),
        firstRejection.rejected ? 'The infeasible increase was rejected before a successor could be created.' : 'Capacity failure did not use the required truthful error contract.',
      );
      if (!firstRejection.rejected) return false;
      const replay = await client.request('POST', endpoint(template, ids), {
        headers: { 'Idempotency-Key': idempotencyKey },
        body: disruption,
      });
      const replayRejection = capacityGapRejection(replay);
      const stableRejection = replayRejection.rejected && replayRejection.capacityGap === firstRejection.capacityGap;
      addCheck(
        round,
        `round3.${disruption.disruptionId}.idempotency`,
        stableRejection ? 'PASS' : 'FAIL',
        replay.evidenceId,
        'same truthful capacity rejection with no successor resource',
        sanitize({ first: firstRejection, replay: replayRejection }),
      );
      if (!stableRejection) return false;
      const sourceDuties = await client.request('GET', endpoint(profile.endpoints.dutiesGet, ids));
      if (sourceDuties.status !== 200 || !Array.isArray(sourceDuties.body)) {
        addCheck(round, `round3.${disruption.disruptionId}.retention`, classifyResponse(sourceDuties), sourceDuties.evidenceId, disruption.minimumRetentionRate, sourceDuties.status);
        return false;
      }
      const observed = retention(baselineDuties, sourceDuties.body);
      const retained = observed.missingBefore === 0 && observed.missingAfter === 0 && observed.rate >= disruption.minimumRetentionRate;
      addCheck(round, `round3.${disruption.disruptionId}.retention`, retained ? 'PASS' : 'FAIL', sourceDuties.evidenceId, `>=${disruption.minimumRetentionRate} after rejected non-mutating change`, observed);
      if (!retained) return false;
      rejectedWithoutSuccessor.push({ disruptionId: disruption.disruptionId, evidenceId: first.evidenceId, reason: firstRejection.code });
      continue;
    }
    if (![200, 201, 202].includes(first.status)) {
      addCheck(round, `round3.${disruption.disruptionId}`, classifyResponse(first), first.evidenceId, 'HTTP 200/201/202', first.status);
      return false;
    }
    const replay = await client.request('POST', endpoint(template, ids), {
      headers: { 'Idempotency-Key': idempotencyKey },
      body: disruption,
    });
    const firstId = first.body?.id || first.body?.changeId || first.body?.change_id || first.body?.revisionId || first.body?.revision_id;
    const replayId = replay.body?.id || replay.body?.changeId || replay.body?.change_id || replay.body?.revisionId || replay.body?.revision_id;
    if (![200, 201, 202].includes(replay.status) || !firstId || firstId !== replayId) {
      addCheck(round, `round3.${disruption.disruptionId}.idempotency`, 'FAIL', replay.evidenceId, 'same stable resource id', { firstId, replayId, replayStatus: replay.status });
      return false;
    }
    addCheck(round, `round3.${disruption.disruptionId}.idempotency`, 'PASS', replay.evidenceId, 'same stable resource id', replayId);
    const successorRevisionId = await validateSuccessor(client, profile, ids, disruption, first, round);
    if (!successorRevisionId) return false;
    successors.push({ disruptionId: disruption.disruptionId, revisionId: successorRevisionId, evidenceId: first.evidenceId });
    if (disruption.minimumRetentionRate != null) {
      const duties = await client.request('GET', endpoint(profile.endpoints.dutiesGet, { ...ids, revisionId: successorRevisionId }));
      if (duties.status !== 200 || !Array.isArray(duties.body)) {
        addCheck(round, `round3.${disruption.disruptionId}.retention`, classifyResponse(duties), duties.evidenceId, disruption.minimumRetentionRate, duties.status);
        return false;
      }
      const observed = retention(baselineDuties, duties.body);
      const retained = observed.missingBefore === 0 && observed.missingAfter === 0 && observed.rate >= disruption.minimumRetentionRate;
      addCheck(round, `round3.${disruption.disruptionId}.retention`, retained ? 'PASS' : 'FAIL', duties.evidenceId, `>=${disruption.minimumRetentionRate} using external session/role/teacher keys`, observed);
      if (!retained) return false;
    }
  }
  const uniqueSuccessors = new Set(successors.map((item) => item.revisionId));
  const handledDisruptions = successors.length + rejectedWithoutSuccessor.length;
  const successorsValid = handledDisruptions === disruptions.length && uniqueSuccessors.size === successors.length;
  addCheck(
    round,
    'round3.successor-revisions',
    successorsValid ? 'PASS' : 'FAIL',
    successors.at(-1)?.evidenceId,
    { handledDisruptions: disruptions.length, uniqueSuccessors: 'all created successors', truthfulPreSuccessorRejectionsAllowed: true },
    {
      handledDisruptions,
      recorded: successors.length,
      unique: uniqueSuccessors.size,
      successors: successors.map(({ disruptionId, revisionId }) => ({ disruptionId, revisionId })),
      rejectedWithoutSuccessor,
    },
    successorsValid ? null : 'Every created successor must be distinct and recorded; truthful pre-successor rejections must also be accounted for.',
  );
  if (!successorsValid) return false;
  addCheck(
    round,
    'round3.successor-validation-complete',
    'PASS',
    successors.at(-1)?.evidenceId,
    { createdSuccessors: successors.length, validatedSuccessors: successors.length },
    { createdSuccessors: successors.length, validatedSuccessors: successors.length, disruptionIds: successors.map((item) => item.disruptionId) },
  );
  const deliveriesTemplate = profile.endpoints.deliveriesGet;
  if (!deliveriesTemplate) {
    addCheck(round, 'round3.notification-duplicates', 'UNAVAILABLE', null, 0, null, 'Delivery audit endpoint is absent');
    return false;
  }
  const mergedDeliveries = [];
  const deliveryCounts = [];
  let lastDeliveryEvidenceId = null;
  for (const successor of successors) {
    const response = await client.request('GET', endpoint(deliveriesTemplate, { ...ids, revisionId: successor.revisionId }));
    lastDeliveryEvidenceId = response.evidenceId;
    if (response.status !== 200 || !Array.isArray(response.body)) {
      addCheck(round, 'round3.deliveries-per-successor', classifyResponse(response), response.evidenceId, 'HTTP 200 array for every successor', { revisionId: successor.revisionId, status: response.status });
      return false;
    }
    deliveryCounts.push({ revisionId: successor.revisionId, count: response.body.length });
    mergedDeliveries.push(...response.body.map((item) => ({ revisionId: successor.revisionId, item })));
  }
  const everySuccessorHasDeliveries = deliveryCounts.every((item) => item.count > 0);
  addCheck(
    round,
    'round3.deliveries-per-successor',
    everySuccessorHasDeliveries ? 'PASS' : 'FAIL',
    lastDeliveryEvidenceId,
    { queriedSuccessors: successors.length, minimumPerSuccessor: 1 },
    { queriedSuccessors: deliveryCounts.length, counts: deliveryCounts },
  );
  if (!everySuccessorHasDeliveries) return false;
  const keys = mergedDeliveries.map(({ item }) => deliveryKey(item));
  const invalidKeys = keys.filter((key) => key == null).length;
  if (invalidKeys) {
    addCheck(round, 'round3.notification-duplicates', 'FAIL', lastDeliveryEvidenceId, { duplicates: 0, invalidStableKeys: 0 }, { duplicates: null, invalidStableKeys: invalidKeys });
    return false;
  }
  const duplicates = keys.length - new Set(keys).size;
  addCheck(round, 'round3.notification-duplicates', duplicates === 0 ? 'PASS' : 'FAIL', lastDeliveryEvidenceId, { duplicates: 0, key: 'event/outbox|recipient|channel', snakeCamelCompatible: true }, { duplicates, deliveries: keys.length, invalidStableKeys: invalidKeys });
  return duplicates === 0;
}

async function runRound(roundNumber, submitter, approver, profile, resources) {
  const round = {
    round: roundNumber,
    name: readJson(`round-${roundNumber}/scenario.json`).name,
    status: 'UNAVAILABLE',
    checks: [],
  };
  const key = `${RUN_ID}-round-${roundNumber}-plan`;
  const planBody = {
    planNo: `UAT-${RUN_ID.slice(-18)}-R${roundNumber}`.toUpperCase(),
    name: `完整考务一体化验收 R${roundNumber} ${RUN_ID}`,
    initialRevisionTitle: `Round ${roundNumber} baseline`,
    settings: { acceptanceRunId: RUN_ID, round: roundNumber, synthetic: true },
  };
  const created = await submitter.request('POST', profile.endpoints.plans, {
    headers: { 'Idempotency-Key': key }, body: planBody,
  });
  if (created.status !== 201 || !created.body?.id) {
    addCheck(round, 'plan.create', classifyResponse(created), created.evidenceId, 'HTTP 201 with plan id', created.status);
    return round;
  }
  const planId = created.body.id;
  addCheck(round, 'plan.create', 'PASS', created.evidenceId, 'HTTP 201 with plan id', planId);
  const replay = await submitter.request('POST', profile.endpoints.plans, {
    headers: { 'Idempotency-Key': key }, body: planBody,
  });
  addCheck(round, 'plan.idempotency', replay.status === 201 && replay.body?.id === planId ? 'PASS' : 'FAIL', replay.evidenceId, planId, replay.body?.id || replay.status);
  if (replay.status !== 201 || replay.body?.id !== planId) {
    round.status = 'FAIL';
    return round;
  }
  const revisions = await submitter.request('GET', endpoint(profile.endpoints.revisions, { planId }));
  if (revisions.status !== 200 || !Array.isArray(revisions.body) || !revisions.body[0]?.id) {
    addCheck(round, 'revision.initial', classifyResponse(revisions), revisions.evidenceId, 'initial revision', revisions.status);
    return round;
  }
  let revision = revisions.body[0];
  const ids = { planId, revisionId: revision.id };
  addCheck(round, 'revision.initial', 'PASS', revisions.evidenceId, 'DRAFT initial revision', { id: revision.id, status: revision.status, cursor: revision.revisionCursor });

  const emptyValidation = await submitter.request('POST', endpoint(profile.endpoints.validate, ids), {
    headers: { 'If-Match': String(revision.revisionCursor) },
  });
  const truthfulEmpty = emptyValidation.status === 422
    && emptyValidation.body?.valid === false
    && asNumber(emptyValidation.body?.hardViolationCount) > 0
    && Array.isArray(emptyValidation.body?.issues)
    && emptyValidation.body.issues.length > 0;
  addCheck(round, 'validation.empty-is-not-success', truthfulEmpty ? 'PASS' : 'FAIL', emptyValidation.evidenceId, 'HTTP 422 with explicit hard issues', sanitize(emptyValidation.body || emptyValidation.status));
  if (!truthfulEmpty) {
    round.status = 'FAIL';
    return round;
  }
  let stale = await submitter.request('POST', endpoint(profile.endpoints.validate, ids), {
    headers: { 'If-Match': String(revision.revisionCursor) },
  });
  // A validation request can finish server-side while the connection drops before
  // the response arrives. Reconcile with a read before repeating it: only retry
  // after proving the original cursor is already stale, which makes the repeat a
  // guaranteed non-mutating conflict check rather than a blind POST retry.
  if (stale.status == null) {
    const currentRevision = await submitter.request('GET', endpoint(profile.endpoints.revisionGet, ids));
    const reconciled = currentRevision.status === 200
      && asNumber(currentRevision.body?.revisionCursor) > asNumber(revision.revisionCursor);
    addCheck(round, 'revision.stale-cursor-reconcile', reconciled ? 'PASS' : 'FAIL', currentRevision.evidenceId,
      'current revision cursor greater than the stale cursor after ambiguous network outcome',
      sanitize(currentRevision.body || currentRevision.status));
    if (reconciled) {
      stale = await submitter.request('POST', endpoint(profile.endpoints.validate, ids), {
        headers: { 'If-Match': String(revision.revisionCursor) },
      });
    }
  }
  const staleRejected = stale.status === 409 && ['REVISION_CONFLICT', 'RESOURCE_CONFLICT'].includes(stale.body?.code);
  addCheck(round, 'revision.stale-cursor', staleRejected ? 'PASS' : 'FAIL', stale.evidenceId, 'HTTP 409 conflict', sanitize(stale.body || stale.status));
  if (!staleRejected) {
    round.status = 'FAIL';
    return round;
  }

  if (PLANNING_MODE !== 'JOINT') {
    addCheck(round, 'planning.mode', 'UNAVAILABLE', null, 'JOINT', PLANNING_MODE, 'This strict runner never uploads preallocated oracle rooms/seats/duties. Use PLANNING_MODE=JOINT with the typed input graph.');
    return round;
  }
  const beforeInputs = await getRevision(submitter, profile, ids);
  if (beforeInputs.status !== 200 || beforeInputs.body?.revisionCursor == null) {
    addCheck(round, 'revision.before-joint-inputs', classifyResponse(beforeInputs), beforeInputs.evidenceId, 'current revision cursor', sanitize(beforeInputs.body || beforeInputs.status));
    return round;
  }
  ids.revisionCursor = asNumber(beforeInputs.body.revisionCursor);
  addCheck(round, 'revision.before-joint-inputs', 'PASS', beforeInputs.evidenceId, 'current revision cursor', ids.revisionCursor);
  const jointUploads = [
    'jointDemands',
    'jointDemandClasses',
    'jointDemandWindows',
    'jointCandidates',
    'jointEnrollments',
    'jointCandidateWindows',
    'jointSpecialRequirements',
    'jointRoomPool',
    'jointRoomWindows',
    'jointSeatTopology',
    'jointTeacherPool',
    'jointAvoidances',
    'jointCampusTravel',
  ];
  for (const resourceKey of jointUploads) {
    const uploaded = await replaceJointInput(submitter, profile, resourceKey, resources.joint[resourceKey], ids, round);
    if (!uploaded) return round;
  }
  for (const resourceKey of jointUploads) {
    const verified = await verifyJointInput(submitter, profile, resourceKey, resources.joint[resourceKey], ids, round);
    if (!verified) return round;
  }

  const jobStart = await submitter.request('POST', endpoint(profile.endpoints.planningJobCreate, ids), {
    headers: { 'Idempotency-Key': `${RUN_ID}-round-${roundNumber}-joint-solve` },
    body: { mode: PLANNING_MODE, seed: 20260831, failOnPartialCoverage: true, timeoutSeconds: 120 },
  });
  const jobId = jobStart.body?.jobId || jobStart.body?.id;
  if (![200, 201, 202].includes(jobStart.status) || !jobId) {
    const status = isExplicitlyUnsupported(jobStart) ? 'UNAVAILABLE' : classifyResponse(jobStart);
    addCheck(round, 'planning.job-start', status, jobStart.evidenceId, 'HTTP 200/201/202 with job id', sanitize(jobStart.body || jobStart.status));
    return round;
  }
  addCheck(round, 'planning.job-start', 'PASS', jobStart.evidenceId, 'job id', jobId);
  const job = await pollJob(submitter, profile, jobId, ids, round);
  if (!job) {
    round.status = round.checks.some((item) => item.status === 'FAIL') ? 'FAIL' : 'UNAVAILABLE';
    return round;
  }

  const refreshed = await getRevision(submitter, profile, ids);
  if (refreshed.status !== 200 || refreshed.body?.revisionCursor == null) {
    addCheck(round, 'revision.after-solve', classifyResponse(refreshed), refreshed.evidenceId, 'revision cursor', refreshed.status);
    return round;
  }
  revision = refreshed.body;
  const validation = await submitter.request('POST', endpoint(profile.endpoints.validate, ids), {
    headers: { 'If-Match': String(revision.revisionCursor) },
  });
  const valid = validation.status === 200 && validation.body?.valid === true && validation.body?.hardViolationCount === 0;
  addCheck(round, 'validation.zero-hard-constraints', valid ? 'PASS' : 'FAIL', validation.evidenceId, { status: 200, hardViolationCount: 0 }, sanitize(validation.body || validation.status));
  if (!valid) {
    round.status = 'FAIL';
    return round;
  }
  revision = validation.body.revision;
  const actualResources = await fetchRevisionResources(submitter, profile, ids, round);
  if (!actualResources) return round;
  const coverage = coverageMetrics(actualResources);
  const fullCoverage = coverage.sessionCoverage === 1 && coverage.roomCoverage >= 1 && coverage.seatCoverage >= 1 && coverage.dutyCoverage >= 1;
  addCheck(round, 'coverage.all-resources', fullCoverage ? 'PASS' : 'FAIL', null, { sessionCoverage: 1, roomCoverage: 1, seatCoverage: 1, dutyCoverage: 1 }, coverage);
  if (!fullCoverage) {
    round.status = 'FAIL';
    return round;
  }

  const submit = await submitter.request('POST', endpoint(profile.endpoints.submitApproval, ids), {
    headers: { 'If-Match': String(revision.revisionCursor) },
  });
  if (submit.status !== 200 || submit.body?.status !== 'PENDING_APPROVAL') {
    addCheck(round, 'approval.submit', classifyResponse(submit), submit.evidenceId, 'PENDING_APPROVAL', sanitize(submit.body || submit.status));
    return round;
  }
  addCheck(round, 'approval.submit', 'PASS', submit.evidenceId, 'PENDING_APPROVAL', submit.body.status);
  revision = submit.body;
  if (!approver) {
    addCheck(round, 'approval.independent', 'UNAVAILABLE', null, 'separate approver identity', null, 'Approver credentials/token are not configured');
    return round;
  }
  const approve = await approver.request('POST', endpoint(profile.endpoints.approve, ids), {
    headers: { 'If-Match': String(revision.revisionCursor) }, body: { reason: `Automated isolated-tenant acceptance ${RUN_ID}` },
  });
  if (approve.status !== 200 || approve.body?.status !== 'APPROVED') {
    addCheck(round, 'approval.independent', classifyResponse(approve), approve.evidenceId, 'APPROVED by separate identity', sanitize(approve.body || approve.status));
    return round;
  }
  addCheck(round, 'approval.independent', 'PASS', approve.evidenceId, 'APPROVED', approve.body.status);
  revision = approve.body;
  const publish = await submitter.request('POST', endpoint(profile.endpoints.publish, ids), {
    headers: { 'If-Match': String(revision.revisionCursor) },
  });
  if (publish.status !== 200 || publish.body?.status !== 'PUBLISHED') {
    addCheck(round, 'publication', classifyResponse(publish), publish.evidenceId, 'PUBLISHED', sanitize(publish.body || publish.status));
    return round;
  }
  addCheck(round, 'publication', 'PASS', publish.evidenceId, 'PUBLISHED', publish.body.status);
  const immutable = await submitter.request('POST', endpoint(profile.endpoints.validate, ids), {
    headers: { 'If-Match': String(publish.body.revisionCursor) },
  });
  const immutablePass = immutable.status === 409 && immutable.body?.code === 'REVISION_IMMUTABLE';
  addCheck(round, 'publication.immutable', immutablePass ? 'PASS' : 'FAIL', immutable.evidenceId, 'HTTP 409 REVISION_IMMUTABLE', sanitize(immutable.body || immutable.status));
  if (!immutablePass) {
    round.status = 'FAIL';
    return round;
  }

  if (roundNumber === 2) {
    const cases = readJsonl('round-2/infeasible-cases.jsonl');
    if (!profile.endpoints.infeasibleCase) {
      addCheck(round, 'round2.infeasible-diagnostics', 'UNAVAILABLE', null, 'configured endpoint', null, 'Infeasible-case endpoint is absent');
      return round;
    }
    for (const testCase of cases) {
      const response = await submitter.request('POST', endpoint(profile.endpoints.infeasibleCase, ids), {
        headers: { 'Idempotency-Key': `${RUN_ID}-${testCase.caseId}` }, body: testCase.change,
      });
      const issues = response.body?.issues || response.body?.conflicts || [];
      const codes = new Set(issues.map((item) => item.code));
      const diagnosed = [409, 422].includes(response.status) && testCase.expectedCodes.some((code) => codes.has(code));
      addCheck(round, `round2.${testCase.caseId}`, diagnosed ? 'PASS' : classifyResponse(response), response.evidenceId, testCase.expectedCodes, sanitize(response.body || response.status));
      if (!diagnosed) return round;
    }
  }

  if (roundNumber === 3) {
    const ok = await runDisruptions(submitter, profile, ids, actualResources.duties, round);
    if (!ok) return round;
  }

  round.status = round.checks.every((item) => item.status === 'PASS') ? 'PASS' : round.checks.some((item) => item.status === 'FAIL') ? 'FAIL' : 'UNAVAILABLE';
  return round;
}

async function main() {
  assertInside(EVIDENCE_ROOT, DATA_ROOT);
  if (!fs.existsSync(path.resolve(GENERATED_ROOT, 'manifest.json'))) {
    throw new Error('Generated datasets are missing. Run generate-datasets.mjs and validate-datasets.mjs first.');
  }
  const offlineReportPath = path.resolve(GENERATED_ROOT, 'offline-validation-report.json');
  if (!fs.existsSync(offlineReportPath) || JSON.parse(fs.readFileSync(offlineReportPath, 'utf8')).status !== 'PASS') {
    throw new Error('Offline dataset validation report is missing or not PASS.');
  }
  const profile = JSON.parse(fs.readFileSync(process.env.API_PROFILE_FILE || PROFILE_PATH, 'utf8'));
  const store = new EvidenceStore(EVIDENCE_ROOT);
  const startedAt = new Date().toISOString();
  const preflight = new HttpClient(store);
  const health = await preflight.request('GET', profile.endpoints.health);
  const preflightChecks = [{
    id: 'api.health',
    status: health.status === 200 && health.body?.status === 'UP' ? 'PASS' : classifyResponse(health),
    evidenceId: health.evidenceId,
    expected: 'HTTP 200 status=UP',
    observed: sanitize(health.body || health.status),
  }];
  let submitterAuth = null;
  let approverAuth = null;
  let submitterContext = null;
  let approverContext = null;
  if (preflightChecks[0].status === 'PASS') {
    submitterAuth = await authenticate(
      store,
      'SUBMITTER',
      process.env.SUBMITTER_USERNAME || process.env.USERNAME,
      process.env.SUBMITTER_PASSWORD || process.env.PASSWORD,
      process.env.SUBMITTER_ROLE || process.env.ROLE,
      process.env.SUBMITTER_SESSION_COOKIE || process.env.SESSION_COOKIE || '',
      process.env.SUBMITTER_AUTH_TOKEN || process.env.AUTH_TOKEN || '',
    );
    preflightChecks.push({
      id: 'auth.submitter', status: submitterAuth.status,
      evidenceId: submitterAuth.evidenceIds.at(-1) || null,
      expected: 'authenticated isolated-test-tenant submitter',
      observed: submitterAuth.identity ? { userId: submitterAuth.identity.user.id, orgId: submitterAuth.identity.org?.id } : null,
      message: submitterAuth.message || null,
    });
    submitterContext = await fetchAuthenticatedContext(submitterAuth, profile, preflightChecks, 'submitter');
    if (submitterAuth.status === 'PASS' && profile.endpoints.readiness) {
      const readiness = await submitterAuth.client.request('GET', profile.endpoints.readiness);
      const readinessContract = readiness.status === 200
        && typeof readiness.body?.ready === 'boolean'
        && Number.isInteger(readiness.body?.blockingIssueCount)
        && readiness.body?.totals
        && Array.isArray(readiness.body?.issues);
      preflightChecks.push({
        id: 'operations.readiness-contract',
        status: readinessContract ? 'PASS' : classifyResponse(readiness),
        evidenceId: readiness.evidenceId,
        expected: 'HTTP 200 with ready/blockingIssueCount/totals/issues',
        observed: sanitize(readiness.body || readiness.status),
        message: readinessContract ? null : 'Readiness endpoint is unavailable or violates its response contract.',
      });
    }
    const approverConfigured = Boolean(process.env.APPROVER_SESSION_COOKIE || process.env.APPROVER_AUTH_TOKEN || (process.env.APPROVER_USERNAME && process.env.APPROVER_PASSWORD));
    if (approverConfigured) {
      approverAuth = await authenticate(
        store,
        'APPROVER',
        process.env.APPROVER_USERNAME,
        process.env.APPROVER_PASSWORD,
        process.env.APPROVER_ROLE,
        process.env.APPROVER_SESSION_COOKIE || '',
        process.env.APPROVER_AUTH_TOKEN || '',
      );
      preflightChecks.push({
        id: 'auth.approver', status: approverAuth.status,
        evidenceId: approverAuth.evidenceIds.at(-1) || null,
        expected: 'authenticated independent approver',
        observed: approverAuth.identity ? { userId: approverAuth.identity.user.id, orgId: approverAuth.identity.org?.id } : null,
        message: approverAuth.message || null,
      });
      approverContext = await fetchAuthenticatedContext(approverAuth, profile, preflightChecks, 'approver');
    }
  }

  const rounds = [];
  const tenantMutationAllowed = verifyMutationTenant(preflightChecks, submitterContext, approverContext);
  let masterDataIds = null;
  if (tenantMutationAllowed && submitterAuth?.status === 'PASS') {
    masterDataIds = await prepareMasterData(submitterAuth.client, profile, buildMasterDataResources(), preflightChecks);
  }
  if (tenantMutationAllowed && masterDataIds) {
    const resources = { joint: buildJointInputResources(masterDataIds) };
    for (let round = 1; round <= 3; round += 1) {
      rounds.push(await runRound(round, submitterAuth.client, approverAuth?.status === 'PASS' ? approverAuth.client : null, profile, resources));
    }
  }

  const statuses = [...preflightChecks.map((item) => item.status), ...rounds.map((item) => item.status)];
  const fullJointMode = PLANNING_MODE === 'JOINT';
  const executionStatus = statuses.includes('FAIL') ? 'FAIL' : statuses.includes('UNAVAILABLE') || rounds.length !== 3 ? 'UNAVAILABLE' : 'PASS';
  const overallStatus = executionStatus === 'PASS' && !fullJointMode ? 'UNAVAILABLE' : executionStatus;
  const evidenceIndex = store.finalize();
  const result = {
    schemaVersion: 'full-exam-live-acceptance-result/v1',
    runId: RUN_ID,
    mode: 'live-http',
    planningMode: PLANNING_MODE,
    startedAt,
    endedAt: new Date().toISOString(),
    baseUrl: BASE_URL,
    overallStatus,
    onlineAcceptancePassed: fullJointMode && overallStatus === 'PASS' && rounds.length === 3 && rounds.every((round) => round.status === 'PASS'),
    safety: {
      mutationOptIn: process.env.ALLOW_TEST_MUTATION === '1',
      isolatedTestTenantRequired: true,
      mocksOrFallbacksAccepted: false,
      syntheticDataset: true,
      fullJointModeRequiredForAcceptance: true,
      jointTypedInputsRequired: true,
      oracleFilesUploaded: false,
    },
    preflightChecks,
    rounds,
    evidenceIndex,
    knownContractGaps: profile.knownCurrentGaps || [],
  };
  const resultPath = assertInside(path.resolve(EVIDENCE_ROOT, 'result.json'), EVIDENCE_ROOT);
  const serialized = `${JSON.stringify(result, null, 2)}\n`;
  fs.writeFileSync(resultPath, serialized, 'utf8');
  process.stdout.write(`${JSON.stringify({ overallStatus, onlineAcceptancePassed: result.onlineAcceptancePassed, rounds: rounds.map((round) => ({ round: round.round, status: round.status })), resultPath, resultSha256: sha256(serialized) })}\n`);
  if (overallStatus === 'FAIL') process.exitCode = 1;
  else if (overallStatus === 'UNAVAILABLE') process.exitCode = 3;
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
