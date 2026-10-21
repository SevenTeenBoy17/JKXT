(function () {
  const externalBase = '/api/v1';

  function normalizeBase(base) {
    return String(base || externalBase).replace(/\/+$/, '');
  }

  function defaultBase() {
    try {
      const storedBase = localStorage.getItem('jkxt:apiBase');
      if (storedBase) return normalizeBase(storedBase);
    } catch {}
    if (window.JKXT_API_BASE) return normalizeBase(window.JKXT_API_BASE);
    // Production, private Compose, preview and local reverse proxies all expose
    // the backend below the same origin.  A localhost port is not enough
    // information to infer a different backend (and used to misroute 19180 to
    // an unrelated :8080 listener).  Cross-origin development remains an
    // explicit opt-in via jkxt:apiBase or window.JKXT_API_BASE above.
    return externalBase;
  }

  function encodeQuery(query) {
    if (!query) return '';
    const params = query instanceof URLSearchParams ? query : new URLSearchParams();
    if (!(query instanceof URLSearchParams)) {
      Object.entries(query).forEach(([key, value]) => {
        if (value === undefined || value === null || value === '') return;
        if (Array.isArray(value)) value.forEach((item) => params.append(key, item));
        else params.set(key, value);
      });
    }
    const text = params.toString();
    return text ? '?' + text : '';
  }

  function resolveUrl(path, query) {
    if (/^https?:\/\//i.test(path)) return path + encodeQuery(query);
    const cleanPath = String(path || '').startsWith('/') ? String(path) : '/' + String(path || '');
    return api.base + cleanPath + encodeQuery(query);
  }

  async function parseBody(response) {
    const text = await response.text();
    if (!text) return null;
    try { return JSON.parse(text); }
    catch {
      // A proxy's HTML error page is not a user-facing backend message.
      // Preserve real JSON business errors; never place raw proxy output in the login form.
      if (!response.ok) {
        const unavailable = [502, 503, 504].includes(response.status);
        return {
          code: unavailable ? 'SERVICE_UNAVAILABLE' : 'API_INVALID_RESPONSE',
          message: unavailable ? '服务暂未就绪或连接超时，请稍后重试。' : `请求失败（HTTP ${response.status}），服务未返回有效响应。`,
        };
      }
      return { message: text };
    }
  }

  function normalizeError(response, payload) {
    const nested = payload?.error || {};
    // Gateways may return an empty body (including Netlify's timeout), null or
    // an empty JSON object. Apply the same fallback as an HTML gateway error
    // without replacing structured business diagnostics or changing status.
    const unavailable = [502, 503, 504].includes(response.status);
    const message = payload?.message || nested.message || (unavailable
      ? '服务暂未就绪或连接超时，请稍后重试。'
      : `请求失败（HTTP ${response.status}）`);
    const error = new Error(message);
    error.status = response.status;
    error.code = payload?.code || nested.code || (unavailable ? 'SERVICE_UNAVAILABLE' : 'API_ERROR');
    error.details = payload?.details || nested.details || payload?.errors || payload;
    error.traceId = payload?.traceId || nested.traceId || response.headers.get('X-Trace-Id') || null;
    error.response = payload;
    return error;
  }

  function bodyInit(body, headers) {
    if (body === undefined || body === null) return undefined;
    if (body instanceof FormData || body instanceof Blob || body instanceof ArrayBuffer) return body;
    if (typeof body === 'string') return body;
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
    return JSON.stringify(body);
  }

  // PB-5 (round 9): the backend enforces Spring Security SPA CSRF on every unsafe request. The
  // session cookie stays HttpOnly; the separate, readable XSRF-TOKEN cookie is echoed in a header.
  const CSRF_COOKIE = 'XSRF-TOKEN';
  const CSRF_HEADER = 'X-XSRF-TOKEN';

  // PB-4 (round 9, login-entry isolation): each entry has its own HttpOnly session cookie and every request
  // names its entry in X-JKXT-Portal so the server reads the matching cookie. Resolve once for this
  // document: the login UI, restored session, logout and SSE must keep the same entry. A fresh bare
  // Dashboard is SCHOOL even when this tab previously visited PLATFORM (2026-09-20 §3).
  const PORTAL_HEADER = 'X-JKXT-Portal';
  const PORTAL_KEY = 'jkxt:portal';

  function resolvePagePortal() {
    try {
      const params = new URLSearchParams(window.location.search || '');
      for (const key of ['portal', 'login', 'entry']) {
        if (params.has(key)) {
          // An empty/unknown primary entry stays SCHOOL; do not fall through to a super alias.
          return params.get(key).toLowerCase() === 'super' ? 'platform' : 'school';
        }
      }
    } catch {}
    return 'school';
  }

  const pagePortal = resolvePagePortal();
  // Storage is only a record of the current page, never an authority over its URL. A browser that
  // denies storage must still honour an explicit entry. Switching entry requires a new document.
  try { sessionStorage.setItem(PORTAL_KEY, pagePortal); } catch {}

  function currentPortal() {
    return pagePortal;
  }

  function applyPortal(headers) {
    if (Object.keys(headers).some((key) => key.toLowerCase() === PORTAL_HEADER.toLowerCase())) return;
    headers[PORTAL_HEADER] = currentPortal();
  }

  // EventSource cannot send X-JKXT-Portal, so SSE uses a fetch stream with the same headers and exposes the
  // small EventSource surface the pages use (addEventListener, onerror, close).
  function fetchEventSource(url) {
    const listeners = {};
    const controller = new AbortController();
    const source = {
      onerror: null,
      onmessage: null,
      addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
      close() { controller.abort(); },
    };
    const dispatch = (type, data) => {
      const event = { type, data };
      (listeners[type] || []).forEach((fn) => { try { fn(event); } catch {} });
      if (type === 'message' && typeof source.onmessage === 'function') source.onmessage(event);
    };
    const fail = (error) => {
      if (controller.signal.aborted) return;
      (listeners.error || []).forEach((fn) => { try { fn(error); } catch {} });
      if (typeof source.onerror === 'function') source.onerror(error);
    };
    const headers = { Accept: 'text/event-stream' };
    applyPortal(headers);
    fetch(url, { credentials: 'include', headers, signal: controller.signal }).then(async (response) => {
      if (!response.ok || !response.body) { fail(new Error('sse-http-' + response.status)); return; }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let eventType = 'message';
      let data = [];
      for (;;) {
        const { value, done } = await reader.read();
        if (done) { fail(new Error('sse-closed')); return; }
        buffer += decoder.decode(value, { stream: true });
        let index;
        while ((index = buffer.search(/\r?\n/)) >= 0) {
          const line = buffer.slice(0, index);
          buffer = buffer.slice(index + (buffer[index] === '\r' ? 2 : 1));
          if (line === '') {
            if (data.length) dispatch(eventType, data.join('\n'));
            eventType = 'message';
            data = [];
          } else if (line.startsWith('event:')) {
            eventType = line.slice(6).trim() || 'message';
          } else if (line.startsWith('data:')) {
            data.push(line.slice(5).replace(/^ /, ''));
          }
        }
      }
    }).catch(fail);
    return source;
  }
  const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

  function readCookie(name) {
    try {
      const prefix = name + '=';
      const found = String(document.cookie || '').split(';').map((part) => part.trim())
        .find((part) => part.startsWith(prefix));
      return found ? decodeURIComponent(found.slice(prefix.length)) : null;
    } catch {
      return null;
    }
  }

  function applyCsrf(method, headers) {
    if (SAFE_METHODS.has(String(method || 'GET').toUpperCase())) return;
    if (Object.keys(headers).some((key) => key.toLowerCase() === CSRF_HEADER.toLowerCase())) return;
    const token = readCookie(CSRF_COOKIE);
    if (token) headers[CSRF_HEADER] = token;
  }

  const api = {
    externalBase,
    base: defaultBase(),

    setBase(base) {
      api.base = normalizeBase(base);
      return api.base;
    },

    url(path, query) {
      return resolveUrl(path, query);
    },

    async request(path, options = {}) {
      const headers = { ...(options.headers || {}) };
      const init = {
        method: options.method || 'GET',
        credentials: options.credentials || 'include',
        headers,
        signal: options.signal,
      };

      const body = bodyInit(options.body, headers);
      if (body !== undefined) init.body = body;
      applyCsrf(init.method, headers);
      applyPortal(headers);

      const response = await fetch(resolveUrl(path, options.query), init);
      const payload = await parseBody(response);

      // A cancelled bootstrap may finish while a manual retry is already using
      // a new session. Never let that stale response clear auth or retry CSRF.
      if (options.signal?.aborted) {
        const error = new Error('请求已取消');
        error.name = 'AbortError';
        throw error;
      }

      if (!response.ok) {
        // A missing/rotated CSRF cookie is re-rendered by the 403 response itself; retry once.
        if (response.status === 403 && payload?.code === 'CSRF_TOKEN_INVALID' && !options.csrfRetried
            && readCookie(CSRF_COOKIE)) {
          return api.request(path, { ...options, csrfRetried: true });
        }
        const error = normalizeError(response, payload);
        if (response.status === 401) {
          window.dispatchEvent(new CustomEvent('jkxt:api-unauthorized', { detail: error }));
        }
        throw error;
      }

      return payload;
    },

    get(path, query, options = {}) {
      return api.request(path, { ...options, method: 'GET', query });
    },

    post(path, body, options = {}) {
      return api.request(path, { ...options, method: 'POST', body });
    },

    put(path, body, options = {}) {
      return api.request(path, { ...options, method: 'PUT', body });
    },

    patch(path, body, options = {}) {
      return api.request(path, { ...options, method: 'PATCH', body });
    },

    del(path, body, options = {}) {
      return api.request(path, { ...options, method: 'DELETE', body });
    },

    upload(path, files, fields = {}, options = {}) {
      const form = new FormData();
      Object.entries(fields || {}).forEach(([key, value]) => {
        if (value === undefined || value === null) return;
        form.append(key, value);
      });

      if (files instanceof File || files instanceof Blob) {
        form.append(options.fileField || 'file', files);
      } else if (Array.isArray(files)) {
        files.forEach((file) => form.append(options.fileField || 'files', file));
      } else if (files && typeof files === 'object') {
        Object.entries(files).forEach(([key, value]) => {
          if (Array.isArray(value)) value.forEach((file) => form.append(key, file));
          else if (value !== undefined && value !== null) form.append(key, value);
        });
      }

      return api.request(path, { ...options, method: options.method || 'POST', body: form });
    },

    portal() {
      return currentPortal();
    },

    eventSource(path, query) {
      return fetchEventSource(resolveUrl(path, query));
    },

    wsUrl(path, query) {
      const url = new URL(resolveUrl(path, query), location.origin);
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      return url.toString();
    },

    webSocket(path, protocols, query) {
      const url = api.wsUrl(path, query);
      return protocols === undefined ? new WebSocket(url) : new WebSocket(url, protocols);
    },
  };

  api.auth = {
    captcha: () => api.post('/auth/captcha', {}),
    login: (body) => api.post(currentPortal() === 'platform' ? '/auth/platform/login' : '/auth/school/login', body),
    logout: () => api.post('/auth/logout', {}),
    me: () => api.get('/auth/me'),
  };

  api.preferences = {
    get: () => api.get('/auth/me/preferences'),
    update: (body, options = {}) => api.patch('/auth/me/preferences', body, options),
  };

  api.appearance = {
    get: () => api.get('/org/settings/appearance'),
    update: (body, options = {}) => api.put('/org/settings/appearance', body, options),
  };

  const jointInputResources = Object.freeze([
    'demands', 'demand-classes', 'demand-windows', 'candidates', 'enrollments',
    'candidate-windows', 'special-requirements', 'room-pool', 'room-windows',
    'seat-topology', 'teacher-pool', 'avoidances', 'campus-travel',
  ]);

  const jointInputPath = (revisionId, resource) => {
    if (!jointInputResources.includes(resource)) throw new Error('Unsupported joint-planning input resource: ' + resource);
    return '/plan-revisions/' + encodeURIComponent(revisionId) + '/inputs/' + encodeURIComponent(resource);
  };

  api.legacyPublication = {
    get: (taskId) => api.get('/tasks/' + encodeURIComponent(taskId) + '/publication'),
    submitApproval: (taskId, runId, runRevisionCursor) => api.post('/tasks/' + encodeURIComponent(taskId) + '/publication/submit-approval', { runId, runRevisionCursor }),
  };

  api.examPlans = {
    list: (query) => api.get('/exam-plans', query),
    get: (id) => api.get('/exam-plans/' + encodeURIComponent(id)),
    create: (body, options = {}) => api.post('/exam-plans', body, options),
    revisions: (id) => api.get('/exam-plans/' + encodeURIComponent(id) + '/revisions'),
    revision: (planId, revisionId) => api.get('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId)),
    validate: (planId, revisionId, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/validate', {}, options),
    createRevision: (planId, body, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions', body, options),
    startPlanning: (planId, revisionId, body, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/planning-jobs', body, options),
    planningJob: (jobId) => api.get('/planning-jobs/' + encodeURIComponent(jobId)),
    cancelPlanning: (jobId) => api.post('/planning-jobs/' + encodeURIComponent(jobId) + '/cancel', {}),
    submitApproval: (planId, revisionId, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/submit-approval', {}, options),
    approve: (planId, revisionId, body = {}, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/approve', body, options),
    reject: (planId, revisionId, body = {}, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/reject', body, options),
    publish: (planId, revisionId, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/publish', {}, options),
    emergencyChange: (planId, revisionId, body, options = {}) => api.post('/exam-plans/' + encodeURIComponent(planId) + '/revisions/' + encodeURIComponent(revisionId) + '/emergency-changes', body, options),
    sessions: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/sessions'),
    rooms: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/rooms'),
    seats: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/seats'),
    assignments: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/duties'),
    constraints: (revisionId) => api.get('/plan-revisions/' + encodeURIComponent(revisionId) + '/constraints'),
    jointInputResources,
    jointInputSnapshot: (revisionId, resource, options = {}) => api.get(jointInputPath(revisionId, resource), undefined, options),
    replaceJointInput: (revisionId, resource, items, options = {}) => api.post(jointInputPath(revisionId, resource), { items }, options),
  };

  api.operations = {
    readiness: () => api.get('/operations/readiness'),
    approvals: () => api.get('/approval-requests'),
    emergencyChanges: () => api.get('/emergency-changes'),
  };

  function dutyIdentity(row) {
    if (typeof row?.id !== 'string' || !row.id.trim() || !Number.isSafeInteger(row.engagementNo) || row.engagementNo < 0) {
      throw new Error('岗位标识或接岗批次不完整，请刷新核对，不能提交。');
    }
  }
  function dutyReason(value) {
    const reason = typeof value === 'string' ? value.trim() : '';
    if (!reason || reason.length > 500) throw new Error('请填写 1–500 字的原因。');
    return reason;
  }
  function uncertainDutyResult() {
    const error = new Error('服务端未返回匹配的办理结果。请刷新核对，勿重复提交。');
    error.code = 'DUTY_RESULT_UNCERTAIN';
    return error;
  }
  api.duties = {
    list: () => api.get('/auth/me/duty-assignments'),
    async act(row, action, reason) {
      dutyIdentity(row);
      const statuses = {accept: 'ACCEPTED', reject: 'REJECTED', 'check-in': 'CHECKED_IN'};
      if (!Object.prototype.hasOwnProperty.call(statuses, action)) throw new Error('不支持的接岗操作');
      const body = action === 'reject' ? {reason: dutyReason(reason)} : {};
      const result = await api.post('/auth/me/duty-assignments/' + encodeURIComponent(row.id) + '/' + action,
        body, {headers: {'X-Duty-Engagement': String(row.engagementNo)}});
      if (result?.assignmentId !== row.id || result.engagementNo !== row.engagementNo || result.status !== statuses[action]) throw uncertainDutyResult();
      return result;
    },
  };
  api.substitutions = {
    list: () => api.get('/substitution-cases'),
    async resolve(row, replacementTeacherId, reason) {
      dutyIdentity(row);
      if (!replacementTeacherId || replacementTeacherId === row.teacherId) throw new Error('请选择另一位教师。');
      const result = await api.post('/substitution-cases/' + encodeURIComponent(row.id) + '/resolve',
        {replacementTeacherId, reason: dutyReason(reason)});
      if (result?.id !== row.id || result.dutyAssignmentId !== row.dutyAssignmentId || result.engagementNo !== row.engagementNo
          || result.status !== 'RESOLVED' || result.replacementTeacherId !== replacementTeacherId) throw uncertainDutyResult();
      return result;
    },
  };

  // 学年学期（后端 org/api/AcademicTermController）。此前前端连"想调都没得调"——
  // api.js 里没有任何 term 方法，顶栏只能提示"请到信息设置操作"而那里没有该功能。
  // 机构业务设置（org/api/OrgSettingsController）。津贴单价此前全库无修改接口——
  // onboarding 承诺「场次×时长×单价」，但单价建租户后焊死在默认 120。
  api.orgSettings = {
    get: () => api.get('/org/settings'),
    update: (body) => api.put('/org/settings', body),
  };

  api.terms = {
    list: () => api.get('/terms'),
    current: () => api.get('/terms/current'),
    create: (body) => api.post('/terms', body),
    update: (id, body) => api.put('/terms/' + id, body),
    activate: (id) => api.post('/terms/' + id + '/activate', {}),
    remove: (id) => api.del('/terms/' + id),
  };

  window.api = api;
})();
